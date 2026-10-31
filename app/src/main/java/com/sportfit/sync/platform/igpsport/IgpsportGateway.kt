package com.sportfit.sync.platform.igpsport

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
import com.sportfit.sync.core.oss.AliyunOssCredentials
import com.sportfit.sync.core.oss.OssUploaders
import com.sportfit.sync.core.util.TimeParsing
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformException
import com.sportfit.sync.platform.PlatformGateway
import com.sportfit.sync.platform.PlatformSource
import com.sportfit.sync.platform.PlatformTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** iGPSPORT has separate Chinese and international sites. */
private data class IgpsportRegion(
    val baseUrl: String,
    val origin: String,
    val referer: String,
)

private val REGIONS = mapOf(
    "cn" to IgpsportRegion(
        baseUrl = "https://prod.zh.igpsport.com/service",
        origin = "https://login.passport.igpsport.cn",
        referer = "https://login.passport.igpsport.cn/",
    ),
    "global" to IgpsportRegion(
        baseUrl = "https://prod.en.igpsport.com/service",
        origin = "https://login.passport.igpsport.com",
        referer = "https://login.passport.igpsport.com/",
    ),
)

@Serializable
private data class IgpsportLoginRequest(
    val username: String,
    val password: String,
    val appId: String,
)

/** Body of the `uploadByOss` notification. */
@Serializable
private data class IgpsportUploadRequest(
    /** Activity title iGPSPORT shows for the imported file. */
    val fileName: String,
    /** Key of the object that was written into the OSS bucket. */
    val ossName: String,
)

/**
 * iGPSPORT client.
 *
 * Login returns a bearer token that is valid only for the site it came from,
 * so the region selector is part of the account rather than a per-request
 * option.
 *
 * The client is both the source and the target of a sync: the download side
 * reads activities back, the upload side borrows the token to mint OSS
 * credentials and to notify the web API.
 */
internal class IgpsportClient(
    private val username: String,
    private val password: String,
    region: String?,
    val log: suspend (String) -> Unit,
) {

    private val config = REGIONS[region?.lowercase()] ?: REGIONS.getValue("cn")

    private var token: String? = null

    /** The regional service root every iGPSPORT endpoint hangs off. */
    val baseUrl: String get() = config.baseUrl

    /** The bearer token in use, if the client is logged in. */
    internal val bearer: String? get() = token

    /** Restores a previously persisted bearer token instead of logging in. */
    fun restore(token: String) {
        this.token = token
    }

    suspend fun login(): Unit = withContext(Dispatchers.IO) {
        val payload = AppJson.encodeToString(
            IgpsportLoginRequest.serializer(),
            IgpsportLoginRequest(username = username, password = password, appId = "igpsport-web"),
        )
        val request = Request.Builder()
            .url("${config.baseUrl}/auth/account/login")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("user-agent", HttpClients.defaultUserAgent)
            .header("accept", "application/json, text/plain, */*")
            .header("content-type", "application/json")
            .header("origin", config.origin)
            .header("referer", config.referer)
            .build()

        val body = HttpClients.api.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PlatformException.Auth("iGPSPORT 登录失败：HTTP ${response.code}")
            }
            response.bodyTextOrThrow()
        }
        val root = AppJson.parseToJsonElement(body)
        if (root.int("code") != 0) {
            throw PlatformException.Auth(
                "iGPSPORT 登录失败：${root.str("message") ?: "未知错误"}",
            )
        }
        token = root.obj("data")?.str("access_token")
            ?: throw PlatformException.Auth("iGPSPORT 登录响应缺少 access_token")
        log("iGPSPORT 登录成功")
    }

    private fun authorized(url: String): Request.Builder {
        val bearer = token ?: throw PlatformException.Auth("iGPSPORT 尚未登录")
        return Request.Builder()
            .url(url)
            .header("user-agent", HttpClients.defaultUserAgent)
            .header("accept", "application/json, text/plain, */*")
            .header("authorization", "Bearer $bearer")
    }

    /**
     * Cheap validity probe for a cached token.
     *
     * Mirrors the upstream Python project's `test_token`: one page of the
     * activity list with `pageSize=1`, so the check costs almost nothing.
     * A rejected token answers HTTP 401/403, everything else that is not a
     * `code == 0` body counts as "not usable".
     */
    suspend fun testToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${config.baseUrl}/web-gateway/web-analyze/activity/queryMyActivity" +
                "?pageNo=1&pageSize=1&reqType=0&sort=1"
            HttpClients.api.newCall(authorized(url).build()).execute().use { response ->
                if (response.code == 401 || response.code == 403) return@withContext false
                if (!response.isSuccessful) return@withContext false
                val root = runCatching { AppJson.parseToJsonElement(response.bodyTextOrThrow()) }
                    .getOrNull()
                    ?: return@withContext false
                root.int("code") == 0
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            // A transport hiccup is not proof of an expired token, but the
            // caller's fallback (log in again) is harmless either way.
            false
        }
    }

    suspend fun listActivities(limit: Int): List<ActivityMeta> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<ActivityMeta>()
        var page = 1
        while (collected.size < limit) {
            val url = "${config.baseUrl}/web-gateway/web-analyze/activity/queryMyActivity" +
                "?pageNo=$page&pageSize=$PAGE_SIZE&reqType=0&sort=1"
            val body = HttpClients.api.newCall(authorized(url).build()).execute().use { response ->
                if (response.code == 401) throw PlatformException.Auth("iGPSPORT 登录态已失效")
                response.bodyTextOrThrow()
            }
            val root = AppJson.parseToJsonElement(body)
            if (root.int("code") != 0) {
                throw PlatformException.Protocol(
                    "iGPSPORT 活动列表失败：${root.str("message") ?: "未知错误"}",
                )
            }
            val data = root.obj("data") ?: break
            val rows = data.arr("rows") ?: break
            if (rows.isEmpty()) break

            rows.forEach { row ->
                val id = row.str("rideId") ?: return@forEach
                collected += ActivityMeta(
                    id = id,
                    name = buildString {
                        append(row.str("startTimeString").orEmpty())
                        append(row.str("title").orEmpty())
                    },
                    startTime = TimeParsing.parseFlexible(
                        row.str("startTime") ?: row.str("startTimeString") ?: row.str("startTimeGMT"),
                    ),
                    distanceMeters = row.str("distance")?.toDoubleOrNull(),
                    durationSec = (row.str("duration") ?: row.str("ridingTime"))
                        ?.toDoubleOrNull()?.toLong(),
                    sportType = row.str("sportType"),
                )
            }

            val total = data.str("total")?.toIntOrNull() ?: data.int("total") ?: 0
            if (page * PAGE_SIZE >= total) break
            page++
        }
        collected.take(limit)
    }

    suspend fun download(activityId: String): FitPayload = withContext(Dispatchers.IO) {
        val detailUrl = "${config.baseUrl}/web-gateway/web-analyze/activity/queryActivityDetail/$activityId"
        val body = HttpClients.api.newCall(authorized(detailUrl).build()).execute().use { response ->
            if (response.code == 401) throw PlatformException.Auth("iGPSPORT 登录态已失效")
            response.bodyTextOrThrow()
        }
        val root = AppJson.parseToJsonElement(body)
        val data = root.obj("data")
            ?: throw PlatformException.Protocol("iGPSPORT 未返回活动详情（活动 $activityId）")
        val fitUrl = data.str("fitUrl") ?: data.str("fitOssPath")
            ?: throw PlatformException.Protocol("iGPSPORT 活动 $activityId 没有可用的 FIT 地址")

        val bytes = HttpClients.transfer.newCall(Request.Builder().url(fitUrl).build())
            .execute().use { response ->
                if (!response.isSuccessful) {
                    throw PlatformException.Network("下载 iGPSPORT 活动失败：HTTP ${response.code}")
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

    private companion object {
        const val PAGE_SIZE = 100
    }
}

/** Builds logged-in iGPSPORT sources and targets. */
object IgpsportGateway : PlatformGateway {

    override suspend fun createSource(ctx: PlatformContext): PlatformSource =
        IgpsportSource(connect(ctx))

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget =
        IgpsportTarget(connect(ctx))

    /**
     * Establishes the session, reusing the persisted token when it still works.
     *
     * iGPSPORT hands out a bearer token without publishing its lifetime, so
     * the probe below is the only reliable invalidation signal: a cached
     * token that answers HTTP 401 is dropped and a fresh login is performed.
     */
    private suspend fun connect(ctx: PlatformContext): IgpsportClient {
        val username = ctx.account.email ?: throw PlatformException.Auth("未填写 iGPSPORT 账号")
        val password = ctx.account.password ?: throw PlatformException.Auth("未填写 iGPSPORT 密码")
        val client = IgpsportClient(username, password, ctx.account.region, ctx.log)

        val cached = ctx.account.token?.trim()?.takeIf { it.isNotEmpty() }
        if (cached != null) {
            client.restore(cached)
            if (client.testToken()) {
                ctx.log("复用已保存的 iGPSPORT 登录态")
                return client
            }
            ctx.log("iGPSPORT 登录态已失效，重新登录…")
        }

        client.login()
        val token = client.bearer ?: throw PlatformException.Auth("iGPSPORT 登录响应缺少 access_token")
        ctx.persistToken(token)
        return client
    }
}

internal class IgpsportSource(private val client: IgpsportClient) : PlatformSource {

    override val platform: Platform = Platform.IGPSPORT

    override suspend fun listActivities(limit: Int) = client.listActivities(limit)

    override suspend fun download(activity: ActivityMeta) = client.download(activity.id)
}

/**
 * Uploads into iGPSPORT through its Aliyun OSS staging area.
 *
 * iGPSPORT has no direct file endpoint: the raw FIT is written into the
 * company's OSS bucket under an object key of its own, and the web API is
 * then told about that object (`activity/uploadByOss`), which parses the
 * file and creates the activity.
 */
internal class IgpsportTarget(private val client: IgpsportClient) : PlatformTarget {

    override val platform: Platform = Platform.IGPSPORT

    /**
     * Target-side listing, so the sync engine can de-duplicate against what
     * the account already has. Reuses the client's queryMyActivity paging.
     */
    override suspend fun listActivities(limit: Int): List<ActivityMeta> =
        client.listActivities(limit)

    override suspend fun upload(payload: FitPayload, activityName: String?): UploadOutcome =
        withContext(Dispatchers.IO) {
            try {
                // iGPSPORT parses a raw FIT; a ZIP is rejected outright.
                val raw = FitPackaging.ensureRawFit(payload)
                if (raw.zipped) {
                    return@withContext UploadOutcome.Failed("无法从压缩包中解析出 FIT 文件")
                }

                val bearer = client.bearer
                    ?: return@withContext UploadOutcome.Failed("iGPSPORT 未登录，无法上传")

                val objectKey = "$OSS_OBJECT_PREFIX${UUID.randomUUID()}"
                val fileName = buildFileName(activityName, raw.fileName)

                // The OSS credentials are minted per upload and expire quickly.
                client.log("获取 iGPSPORT OSS 上传凭证…")
                val credentials = IgpsportOssProvider.fetch(client.baseUrl, bearer)
                val uploader = OssUploaders.forAliyun(
                    AliyunOssCredentials(
                        bucket = credentials.bucket,
                        endpoint = credentials.endpoint,
                        accessKeyId = credentials.accessKeyId,
                        accessKeySecret = credentials.accessKeySecret,
                        securityToken = credentials.securityToken,
                    ),
                )

                client.log("上传 FIT 到阿里云 OSS：$objectKey")
                uploader.upload(objectKey, raw.bytes, contentType = "application/octet-stream")

                client.log("通知 iGPSPORT 导入活动：$fileName")
                notifyUpload(bearer, fileName, objectKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                UploadOutcome.Failed(error.message ?: "iGPSPORT 上传失败")
            }
        }

    /**
     * Tells iGPSPORT that the object is in the bucket.
     *
     * ```
     * POST {baseUrl}/web-gateway/web-analyze/activity/uploadByOss
     * {"fileName":"<title>.fit","ossName":"<objectKey>"}
     * ```
     *
     * The status code has to be checked **before** the body: an
     * authentication failure answers HTTP 403 with a body that still says
     * `{"code":0,"message":"success","data":"The token was expected to have
     * 3 parts, but got 0."}`, so a `code == 0` check alone would report a
     * rejected upload as a success.
     */
    private suspend fun notifyUpload(
        bearer: String,
        fileName: String,
        ossName: String,
    ): UploadOutcome {
        val body = AppJson.encodeToString(
            IgpsportUploadRequest.serializer(),
            IgpsportUploadRequest(fileName = fileName, ossName = ossName),
        )
        val request = Request.Builder()
            .url("${client.baseUrl}$UPLOAD_BY_OSS_PATH")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("user-agent", HttpClients.defaultUserAgent)
            .header("accept", "application/json, text/plain, */*")
            .header("content-type", "application/json")
            .header("authorization", "Bearer $bearer")
            .build()

        return HttpClients.api.newCall(request).execute().use { response ->
            val text = runCatching { response.bodyTextOrThrow() }.getOrDefault("")
            when {
                // Status first: the 403 body of an authentication failure
                // still carries `code == 0`.
                response.code == 401 ->
                    UploadOutcome.Failed("iGPSPORT 登录态已失效，请重新登录后重试")
                response.code == 403 ->
                    UploadOutcome.Failed("iGPSPORT 拒绝了上传请求：令牌无效或没有导入权限")
                !response.isSuccessful ->
                    UploadOutcome.Failed("iGPSPORT 上传通知失败：HTTP ${response.code}")
                else -> {
                    val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                    when {
                        root == null ->
                            UploadOutcome.Failed("iGPSPORT 上传通知返回了非 JSON 内容")
                        root.int("code") != 0 ->
                            UploadOutcome.Failed(
                                "iGPSPORT 上传未确认：${root.str("message") ?: "未知错误"}",
                            )
                        else -> UploadOutcome.Success
                    }
                }
            }
        }
    }

    /**
     * Builds the name iGPSPORT shows for the imported activity.
     *
     * iGPSPORT names the activity after the uploaded file, so the title of the
     * source activity becomes the file name. The characters that are illegal
     * in a file name are replaced the same way the upstream Python project
     * does it; the extension is always `.fit` because iGPSPORT only parses
     * raw FIT files.
     */
    private fun buildFileName(activityName: String?, payloadFileName: String): String {
        val fallback = payloadFileName.substringAfterLast('/').substringBeforeLast('.')
        val base = activityName?.takeIf { it.isNotBlank() } ?: fallback
        var cleaned = base.replace(ILLEGAL_FILE_CHARS, "_").trim()
        if (cleaned.isBlank()) cleaned = "activity"
        // Never double the extension: "Morning Ride.fit" stays what it is.
        if (cleaned.endsWith(FIT_EXTENSION, ignoreCase = true)) {
            cleaned = cleaned.dropLast(FIT_EXTENSION.length)
        }
        // iGPSPORT titles are short; keep the whole name inside the limit.
        return cleaned.take(MAX_TITLE_LENGTH - FIT_EXTENSION.length) + FIT_EXTENSION
    }

    private companion object {
        /**
         * iGPSPORT's own app id, used as the prefix of every uploaded object.
         *
         * The upstream Python project hard-codes the same number and the
         * server expects the objects to live under it.
         */
        const val OSS_OBJECT_PREFIX = "1456042-"

        /** Route that turns an uploaded object into an activity. */
        const val UPLOAD_BY_OSS_PATH = "/web-gateway/web-analyze/activity/uploadByOss"

        /** Characters that are illegal in a file name. */
        val ILLEGAL_FILE_CHARS = Regex("[<>:\"/\\\\|?*]")

        const val FIT_EXTENSION = ".fit"

        /** iGPSPORT titles are short, so the name is truncated to fit. */
        const val MAX_TITLE_LENGTH = 120
    }
}
