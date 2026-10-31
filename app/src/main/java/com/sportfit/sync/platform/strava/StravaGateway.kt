package com.sportfit.sync.platform.strava

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.arr
import com.sportfit.sync.core.net.bodyBytesOrThrow
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.int
import com.sportfit.sync.core.net.obj
import com.sportfit.sync.core.net.str
import com.sportfit.sync.core.util.TimeParsing
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformException
import com.sportfit.sync.platform.PlatformGateway
import com.sportfit.sync.platform.PlatformSource
import com.sportfit.sync.platform.PlatformTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Strava OAuth2 state, persisted as the account's token blob. */
@Serializable
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Unix seconds. */
    val expiresAt: Long = 0,
    val athleteId: String? = null,
) {
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAt <= nowSeconds + 60 // refresh a minute early; tokens last 6h
}

/**
 * Strava API v3 client (source and target).
 *
 * Unlike the other platforms, Strava is OAuth2-only: the user has to register
 * their own API application and authorise it, so `client_id` / `client_secret`
 * are stored alongside the tokens.
 */
internal class StravaClient(
    private val clientId: String,
    private val clientSecret: String,
    initialTokens: StravaTokens,
    private val persist: suspend (StravaTokens) -> Unit,
    private val log: suspend (String) -> Unit,
) {

    @Volatile
    private var tokens: StravaTokens = initialTokens

    private suspend fun bearer(): String {
        if (tokens.isExpired()) {
            log("Strava 令牌已过期，正在刷新…")
            tokens = refresh()
            persist(tokens)
        }
        return tokens.accessToken
    }

    private suspend fun refresh(): StravaTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", tokens.refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val body = HttpClients.api.newCall(
            Request.Builder().url(TOKEN_URL).post(form).build(),
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw PlatformException.Auth("Strava 令牌刷新失败：HTTP ${response.code}")
            }
            response.bodyTextOrThrow()
        }
        val json = AppJson.parseToJsonElement(body)
        tokens.copy(
            accessToken = json.str("access_token")
                ?: throw PlatformException.Auth("Strava 未返回 access_token"),
            refreshToken = json.str("refresh_token") ?: tokens.refreshToken,
            expiresAt = json.str("expires_at")?.toLongOrNull()
                ?: (System.currentTimeMillis() / 1000 + (json.int("expires_in") ?: 21600)),
        )
    }

    private suspend fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${bearer()}")

    suspend fun listActivities(limit: Int): List<ActivityMeta> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<ActivityMeta>()
        var page = 1
        while (collected.size < limit) {
            val perPage = minOf(200, limit - collected.size)
            val url = "$API_BASE/athlete/activities?page=$page&per_page=$perPage"
            val body = HttpClients.api.newCall(authorized(url).build()).execute().use { response ->
                if (response.code == 401) throw PlatformException.Auth("Strava 登录态已失效")
                response.bodyTextOrThrow()
            }
            val array = AppJson.parseToJsonElement(body) as? JsonArray ?: break
            if (array.isEmpty()) break
            array.forEach { row ->
                val id = row.str("id") ?: return@forEach
                collected += ActivityMeta(
                    id = id,
                    name = row.str("name").orEmpty(),
                    startTime = TimeParsing.parseFlexible(row.str("start_date")),
                    distanceMeters = row.str("distance")?.toDoubleOrNull(),
                    durationSec = (row.str("moving_time") ?: row.str("elapsed_time"))
                        ?.toDoubleOrNull()?.toLong(),
                    sportType = row.str("sport_type") ?: row.str("type"),
                )
            }
            if (array.size < perPage) break
            page++
        }
        collected.take(limit)
    }

    suspend fun download(activityId: String): FitPayload = withContext(Dispatchers.IO) {
        val url = "$API_BASE/activities/$activityId/export_original"
        val bytes = HttpClients.transfer.newCall(authorized(url).build()).execute().use { response ->
            if (response.code == 401) throw PlatformException.Auth("Strava 登录态已失效")
            if (!response.isSuccessful) {
                throw PlatformException.Network(
                    "下载 Strava 活动失败：HTTP ${response.code}（该活动可能不是 FIT 原始文件）",
                )
            }
            response.bodyBytesOrThrow()
        }
        val zipped = FitPackaging.isZip(bytes)
        FitPayload(
            bytes = bytes,
            fileName = "$activityId.${if (zipped) "zip" else "fit"}",
            zipped = zipped,
        )
    }

    suspend fun upload(payload: FitPayload, activityName: String? = null): UploadOutcome = withContext(Dispatchers.IO) {
        val raw = FitPackaging.ensureRawFit(payload)
        if (raw.zipped) return@withContext UploadOutcome.Failed("无法从压缩包中解析出 FIT 文件")

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                raw.fileName,
                raw.bytes.toRequestBody("application/octet-stream".toMediaType()),
            )
            .addFormDataPart("data_type", "fit")
            .apply {
                // Strava takes the activity title from the upload form; without
                // it the activity is named after the file we happened to send.
                if (!activityName.isNullOrBlank()) {
                    addFormDataPart("name", activityName)
                }
            }
            .build()

        val body = HttpClients.transfer.newCall(
            authorized("$API_BASE/uploads").post(multipart).build(),
        ).execute().use { response ->
            if (response.code == 401) throw PlatformException.Auth("Strava 登录态已失效")
            response.bodyTextOrThrow()
        }

        val uploadId = AppJson.parseToJsonElement(body).str("id")
            ?: return@withContext UploadOutcome.Failed("Strava 未返回上传 id")

        // Strava processes uploads asynchronously; `/uploads/{id}` must be
        // polled until it reports an activity or an error.
        repeat(MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            val statusBody = HttpClients.api.newCall(
                authorized("$API_BASE/uploads/$uploadId").build(),
            ).execute().use { it.bodyTextOrThrow() }
            val status = AppJson.parseToJsonElement(statusBody)
            val error = status.str("error")
            if (!error.isNullOrBlank()) {
                return@withContext if (error.contains("duplicate", ignoreCase = true)) {
                    UploadOutcome.Duplicate
                } else {
                    UploadOutcome.Failed("Strava：$error")
                }
            }
            val activityId = status.str("activity_id")
            if (!activityId.isNullOrBlank()) {
                return@withContext UploadOutcome.Success
            }
        }
        UploadOutcome.Failed("Strava 上传超时（活动仍在处理中）")
    }

    private companion object {
        const val API_BASE = "https://www.strava.com/api/v3"
        const val TOKEN_URL = "https://www.strava.com/oauth/token"
        const val MAX_POLLS = 30
        const val POLL_INTERVAL_MS = 3_000L
    }
}

object StravaGateway : PlatformGateway {

    override suspend fun createSource(ctx: PlatformContext): PlatformSource =
        StravaSource(connect(ctx))

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget =
        StravaTarget(connect(ctx))

    private suspend fun connect(ctx: PlatformContext): StravaClient {
        // For Strava the generic "email"/"password" slots carry client id/secret.
        val clientId = ctx.account.email
            ?: throw PlatformException.Auth("未配置 Strava Client ID")
        val clientSecret = ctx.account.password
            ?: throw PlatformException.Auth("未配置 Strava Client Secret")
        val tokens = ctx.account.token?.let {
            runCatching { AppJson.decodeFromString(StravaTokens.serializer(), it) }.getOrNull()
        } ?: throw PlatformException.Auth("尚未授权 Strava，请先在账号页完成授权")

        val persist: suspend (StravaTokens) -> Unit = { updated ->
            ctx.persistToken(AppJson.encodeToString(StravaTokens.serializer(), updated))
        }
        return StravaClient(clientId, clientSecret, tokens, persist, ctx.log)
    }
}

internal class StravaSource(private val client: StravaClient) : PlatformSource {
    override val platform: Platform = Platform.STRAVA
    override suspend fun listActivities(limit: Int) = client.listActivities(limit)
    override suspend fun download(activity: ActivityMeta) = client.download(activity.id)
}

internal class StravaTarget(private val client: StravaClient) : PlatformTarget {
    override val platform: Platform = Platform.STRAVA
    override suspend fun upload(payload: FitPayload, activityName: String?) =
        client.upload(payload, activityName)
}
