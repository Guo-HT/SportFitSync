package com.sportfit.sync.platform.garmin

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.arr
import com.sportfit.sync.core.net.bodyBytesOrThrow
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.obj
import com.sportfit.sync.core.net.str
import com.sportfit.sync.core.util.TimeParsing
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Garmin Connect API surface: activity listing, FIT download and FIT upload.
 *
 * Access-token lifetime is handled here rather than by the caller: Garmin has
 * no refresh grant, so an expired OAuth2 token triggers a fresh OAuth1
 * exchange. `garth` omits any 401 retry; we keep the proactive check and let a
 * genuine 401 surface as [PlatformException.Auth] so the UI can re-login.
 */
internal class GarminApi(
    initialTokens: GarminTokens,
    private val auth: GarminAuth,
    private val persist: suspend (GarminTokens) -> Unit,
    private val log: suspend (String) -> Unit,
) {

    @Volatile
    private var tokens: GarminTokens = initialTokens

    private val domains: GarminDomains get() = GarminDomains(tokens.domain)

    private suspend fun accessToken(): String {
        if (tokens.isExpired()) {
            log("Garmin 访问令牌已过期，正在刷新…")
            tokens = auth.refresh(tokens)
            persist(tokens)
        }
        return tokens.accessToken
    }

    private suspend fun authorized(builder: Request.Builder): Request.Builder {
        builder.header("Authorization", "Bearer ${accessToken()}")
        // Deliberately no User-Agent: the upstream client removes it after
        // login because Garmin rejects API calls that carry one.
        return builder
    }

    suspend fun listActivities(limit: Int): List<ActivityMeta> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<ActivityMeta>()
        var start = 0
        while (collected.size < limit) {
            val page = fetchPage(start, pageSize = minOf(100, limit - collected.size))
            if (page.isEmpty()) break
            collected += page
            if (page.size < 100) break
            start += 100
        }
        collected.take(limit)
    }

    private suspend fun fetchPage(start: Int, pageSize: Int): List<ActivityMeta> {
        val request = authorized(
            Request.Builder().url(
                "${domains.connectApi}/activitylist-service/activities/search/activities" +
                    "?start=$start&limit=$pageSize",
            ),
        ).build()

        val body = HttpClients.api.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw PlatformException.Auth("Garmin 登录态已失效")
                else -> {
                    if (!response.isSuccessful) {
                        throw PlatformException.Network("Garmin 活动列表失败：HTTP ${response.code}")
                    }
                    response.bodyTextOrThrow()
                }
            }
        }

        val array = AppJson.parseToJsonElement(body).let { element ->
            if (element is kotlinx.serialization.json.JsonArray) element else null
        } ?: return emptyList()

        return array.mapNotNull { row ->
            val id = row.str("activityId") ?: return@mapNotNull null
            ActivityMeta(
                id = id,
                name = row.str("activityName").orEmpty(),
                startTime = TimeParsing.parseFlexible(row.str("startTimeGMT")),
                distanceMeters = row.str("distance")?.toDoubleOrNull(),
                durationSec = row.str("duration")?.toDoubleOrNull()?.toLong(),
                sportType = row.obj("activityType")?.str("typeKey"),
            )
        }
    }

    suspend fun download(activityId: String): FitPayload = withContext(Dispatchers.IO) {
        val request = authorized(
            Request.Builder().url(
                "${domains.connectApi}/download-service/files/activity/$activityId",
            ),
        ).build()

        val bytes = HttpClients.transfer.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw PlatformException.Auth("Garmin 登录态已失效")
                else -> {
                    if (!response.isSuccessful) {
                        throw PlatformException.Network("下载 Garmin 活动失败：HTTP ${response.code}")
                    }
                    response.bodyBytesOrThrow()
                }
            }
        }

        // The endpoint returns a ZIP (`{activityId}_ACTIVITY.fit` inside), but
        // classify by magic bytes so a raw FIT would also be handled.
        val zipped = FitPackaging.isZip(bytes)
        FitPayload(
            bytes = bytes,
            fileName = "$activityId.${if (zipped) "zip" else "fit"}",
            zipped = zipped,
        )
    }

    suspend fun upload(payload: FitPayload): UploadOutcome = withContext(Dispatchers.IO) {
        // Garmin ingests raw FIT/GPX/TCX only — never a ZIP.
        val raw = FitPackaging.ensureRawFit(payload)
        if (raw.zipped) {
            return@withContext UploadOutcome.Failed("无法从压缩包中解析出 FIT 文件")
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                raw.fileName,
                raw.bytes.toRequestBody("text/plain".toMediaType()),
            )
            .build()

        val request = authorized(
            Request.Builder().url("${domains.connectApi}/upload-service/upload").post(multipart),
        ).build()

        val result = HttpClients.transfer.newCall(request).execute().use { response ->
            response.code to response.bodyTextOrThrow()
        }
        val (code, body) = result

        if (code == 401 || code == 403) {
            throw PlatformException.Auth("Garmin 登录态已失效")
        }

        val json = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()
        val importResult = json?.obj("detailedImportResult")
        val uploadId = importResult?.str("uploadId")

        // `uploadId` must be checked for absence/emptiness, not truthiness:
        // `0` is a legitimate value in garth's logic.
        val accepted = code == 202 && !uploadId.isNullOrEmpty()
        if (accepted) return@withContext UploadOutcome.Success

        if (code == 409) {
            val message = importResult
                ?.arr("failures")?.firstOrNull()
                ?.arr("messages")?.firstOrNull()
                ?.str("content")
                .orEmpty()
            return@withContext if (message.contains("Duplicate Activity")) {
                UploadOutcome.Duplicate
            } else {
                UploadOutcome.Failed(message.ifBlank { "Garmin 拒绝导入（409）" })
            }
        }

        UploadOutcome.Failed("Garmin 上传失败：HTTP $code ${body.take(200)}")
    }
}
