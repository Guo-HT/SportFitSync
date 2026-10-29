package com.sportfit.sync.platform.coros

import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.Hashing
import com.sportfit.sync.core.net.arr
import com.sportfit.sync.core.net.bodyBytesOrThrow
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.obj
import com.sportfit.sync.core.net.parseJsonOrThrow
import com.sportfit.sync.core.net.str
import com.sportfit.sync.core.util.TimeParsing
import com.sportfit.sync.domain.ActivityMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Persisted between runs so a signed-in Coros session can be reused. */
@Serializable
data class CorosToken(
    val accessToken: String,
    val userId: String,
    val regionId: Int,
)

/**
 * Coros (`高驰`) API client.
 *
 * Two properties of this platform shape the whole design:
 *
 *  1. **Uploads cannot be direct.** Coros only ingests FIT files that already
 *     live in its own object storage, so a sync into Coros is a three-step
 *     dance: mint STS credentials, `PUT` a ZIP into the bucket, then tell the
 *     import endpoint to pick it up. [importFromOss] is therefore called by
 *     `CorosTarget`, not by the generic upload path.
 *  2. **Login is single-device.** Signing in here invalidates the session in
 *     the Coros phone app, so the UI warns the user before the first sync.
 */
class CorosClient(
    private val email: String,
    private val password: String,
    private val log: suspend (String) -> Unit = {},
) {

    private val json = "application/json;charset=UTF-8".toMediaType()

    var accessToken: String? = null
        private set
    var userId: String? = null
        private set
    var regionId: Int = DEFAULT_REGION
        private set

    private val teamApi: String
        get() = TEAM_API.getValue(regionId)

    /** Restores a previously persisted session instead of logging in again. */
    fun restore(token: CorosToken) {
        accessToken = token.accessToken
        userId = token.userId
        regionId = token.regionId
    }

    fun snapshot(): CorosToken? {
        val token = accessToken ?: return null
        val user = userId ?: return null
        return CorosToken(accessToken = token, userId = user, regionId = regionId)
    }

    suspend fun login(): CorosToken = withContext(Dispatchers.IO) {
        val payload = AppJson.encodeToString(
            CorosLoginRequest.serializer(),
            CorosLoginRequest(
                account = email,
                pwd = Hashing.md5Hex(password),
                accountType = 2,
            ),
        )

        val request = Request.Builder()
            .url(LOGIN_URL)
            .post(payload.toRequestBody(json))
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", HttpClients.defaultUserAgent)
            .header("referer", "https://teamcnapi.coros.com/")
            .header("origin", "https://teamcnapi.coros.com/")
            .build()

        val body = HttpClients.api.newCall(request).execute().use { response ->
            response.bodyTextOrThrow()
        }

        val root = AppJson.parseToJsonElement(body)
        val result = root.str("result")
        if (result != "0000") {
            val message = root.str("message") ?: "未知错误"
            throw IOException("Coros 登录失败：$message")
        }

        val data = root.obj("data") ?: throw IOException("Coros 登录响应缺少 data")
        val token = data.str("accessToken") ?: throw IOException("Coros 登录响应缺少 accessToken")
        val user = data.str("userId") ?: throw IOException("Coros 登录响应缺少 userId")
        val region = data.str("regionId")?.toIntOrNull() ?: DEFAULT_REGION

        accessToken = token
        userId = user
        regionId = region
        log("Coros 登录成功（userId=$user, regionId=$region）")

        CorosToken(accessToken = token, userId = user, regionId = region)
    }

    suspend fun listActivities(limit: Int): List<ActivityMeta> = withContext(Dispatchers.IO) {
        val token = requireToken()
        val collected = mutableListOf<ActivityMeta>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages && collected.size < limit) {
            val request = Request.Builder()
                .url("$teamApi/activity/query?size=$PAGE_SIZE&pageNumber=$page")
                .header("Accept", "application/json, text/plain, */*")
                .header("accesstoken", token)
                .build()

            val body = HttpClients.api.newCall(request).execute().use { response ->
                if (response.code == 401) throw CorosUnauthorized()
                response.bodyTextOrThrow()
            }

            val root = AppJson.parseToJsonElement(body)
            requireBusinessSuccess(root)

            val data = root.obj("data")
            val rows = data?.arr("dataList")
            if (rows == null || rows.isEmpty()) break

            totalPages = data.str("totalPage")?.toIntOrNull() ?: page

            rows.forEach { row ->
                val id = row.str("labelId") ?: row.str("id") ?: return@forEach
                collected += ActivityMeta(
                    id = id,
                    name = row.str("name") ?: row.str("label") ?: "",
                    startTime = TimeParsing.parseFlexible(row.str("startTime") ?: row.str("date")),
                    distanceMeters = row.str("distance")?.toDoubleOrNull(),
                    durationSec = row.str("duration")?.toDoubleOrNull()?.toLong(),
                    sportType = row.str("sportType"),
                )
            }
            page++
        }
        collected.take(limit)
    }

    suspend fun download(activityId: String, sportType: String?): FitPayload =
        withContext(Dispatchers.IO) {
            val token = requireToken()
            val type = sportType?.toIntOrNull() ?: 1

            val urlRequest = Request.Builder()
                .url(
                    "$teamApi/activity/detail/download" +
                        "?labelId=$activityId&sportType=$type&fileType=4",
                )
                .post(FormBody.Builder().build())
                .header("Accept", "application/json, text/plain, */*")
                .header("accesstoken", token)
                .build()

            val urlBody = HttpClients.api.newCall(urlRequest).execute().use { response ->
                if (response.code == 401) throw CorosUnauthorized()
                response.bodyTextOrThrow()
            }
            val urlRoot = AppJson.parseToJsonElement(urlBody)
            requireBusinessSuccess(urlRoot)
            val fileUrl = urlRoot.obj("data")?.str("fileUrl")
                ?: throw IOException("Coros 未返回下载地址（活动 $activityId）")

            val bytes = HttpClients.transfer.newCall(
                Request.Builder().url(fileUrl).header("accesstoken", token).build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载 Coros 活动失败：HTTP ${response.code}")
                }
                response.bodyBytesOrThrow()
            }

            // Coros normally returns a raw FIT, but the container is not
            // guaranteed, so classify by magic bytes rather than by trust.
            val zipped = FitPackaging.isZip(bytes)
            FitPayload(
                bytes = bytes,
                fileName = "$activityId.${if (zipped) "zip" else "fit"}",
                zipped = zipped,
            )
        }

    /** Tells Coros to import the object we just placed in its bucket. */
    suspend fun importFromOss(
        bucket: String,
        serviceName: String,
        ossObjectKey: String,
        md5: String,
        fileName: String,
        sizeBytes: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val token = requireToken()

        val jsonParameter = AppJson.encodeToString(
            CorosImportRequest.serializer(),
            CorosImportRequest(
                source = 1,
                timezone = TimeParsing.COROS_IMPORT_TIMEZONE,
                bucket = bucket,
                md5 = md5,
                size = sizeBytes,
                `object` = ossObjectKey,
                serviceName = serviceName,
                oriFileName = fileName,
            ),
        )

        val request = Request.Builder()
            .url("$teamApi/activity/fit/import")
            .post(
                FormBody.Builder().add("jsonParameter", jsonParameter).build(),
            )
            .header("Accept", "application/json, text/plain, */*")
            .header("accesstoken", token)
            .build()

        val body = HttpClients.api.newCall(request).execute().use { response ->
            if (response.code == 401) throw CorosUnauthorized()
            response.bodyTextOrThrow()
        }

        val root = AppJson.parseToJsonElement(body)
        root.str("result") == "0000" && root.obj("data")?.str("status") == "2"
    }

    /**
     * Coros reports business failures with **HTTP 200** and a `result` code.
     *
     * Verified against the live API (2026-10-07):
     *   valid token   -> 200, ~195 KB body containing `data.dataList`
     *   invalid token -> 200, 82-byte body `{"result":"1019",
     *                    "message":"Access token is invalid"}`
     *
     * Treating only HTTP 401 as "unauthorized" silently produced empty
     * listings, so the gateway believed its cached token was still good and
     * never re-logged in.
     */
    private fun requireBusinessSuccess(root: kotlinx.serialization.json.JsonElement) {
        val result = root.str("result") ?: return // success payloads carry apiCode/data
        if (result == "0000") return

        val message = root.str("message") ?: "未知错误"
        if (result == INVALID_TOKEN_CODE || message.contains("token", ignoreCase = true)) {
            accessToken = null
            throw CorosUnauthorized("Coros 令牌已失效：$message（result=$result）")
        }
        throw IOException("Coros 接口返回错误：$message（result=$result）")
    }

    private fun requireToken(): String =
        accessToken ?: throw IOException("Coros 尚未登录")

    companion object {
        const val DEFAULT_REGION = 2

        private const val LOGIN_URL = "https://teamcnapi.coros.com/account/login"

        /** Coros' business code for an invalid/expired access token. */
        private const val INVALID_TOKEN_CODE = "1019"
        private const val PAGE_SIZE = 200

        /** Verbatim from upstream `region_config.py`. */
        private val TEAM_API = mapOf(
            1 to "https://teamapi.coros.com",
            2 to "https://teamcnapi.coros.com",
            3 to "https://teameuapi.coros.com",
            101 to "https://teamapitest.coros.com",
            102 to "https://teamcnapitest.coros.com",
            103 to "https://teameuapitest.coros.com",
        )
    }
}

/** Signals that the cached Coros session was rejected and a re-login is needed. */
class CorosUnauthorized(message: String = "Coros 登录态已失效") : IOException(message)

@Serializable
private data class CorosLoginRequest(
    val account: String,
    val pwd: String,
    val accountType: Int,
)

@Serializable
private data class CorosImportRequest(
    val source: Int,
    val timezone: Int,
    val bucket: String,
    val md5: String,
    val size: Int,
    val `object`: String,
    val serviceName: String,
    val oriFileName: String,
)
