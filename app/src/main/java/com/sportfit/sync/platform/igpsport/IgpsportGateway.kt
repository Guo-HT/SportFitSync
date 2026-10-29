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
import com.sportfit.sync.core.util.TimeParsing
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformException
import com.sportfit.sync.platform.PlatformGateway
import com.sportfit.sync.platform.PlatformSource
import com.sportfit.sync.platform.PlatformTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

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

/**
 * iGPSPORT client (source only).
 *
 * Login returns a bearer token that is valid only for the site it came from,
 * so the region selector is part of the account rather than a per-request
 * option.
 */
internal class IgpsportClient(
    private val username: String,
    private val password: String,
    region: String?,
    private val log: suspend (String) -> Unit,
) {

    private val config = REGIONS[region?.lowercase()] ?: REGIONS.getValue("cn")

    private var token: String? = null

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

object IgpsportGateway : PlatformGateway {

    override suspend fun createSource(ctx: PlatformContext): PlatformSource {
        val username = ctx.account.email ?: throw PlatformException.Auth("未填写 iGPSPORT 账号")
        val password = ctx.account.password ?: throw PlatformException.Auth("未填写 iGPSPORT 密码")
        val client = IgpsportClient(username, password, ctx.account.region, ctx.log)
        client.login()
        return object : PlatformSource {
            override val platform: Platform = Platform.IGPSPORT
            override suspend fun listActivities(limit: Int) = client.listActivities(limit)
            override suspend fun download(activity: ActivityMeta) = client.download(activity.id)
        }
    }

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget =
        throw PlatformException.Protocol("iGPSPORT 仅支持作为数据来源，不支持上传")
}
