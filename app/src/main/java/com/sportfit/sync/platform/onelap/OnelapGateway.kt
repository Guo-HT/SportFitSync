package com.sportfit.sync.platform.onelap

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.Hashing
import com.sportfit.sync.core.net.arr
import com.sportfit.sync.core.net.bodyBytesOrThrow
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.int
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

@Serializable
private data class OnelapLoginRequest(val account: String, val password: String)

/**
 * OneLap (`顽鹿`) client — source only.
 *
 * This platform still runs on plain HTTP, which is why
 * `network_security_config.xml` whitelists exactly `www.onelap.cn` and
 * `u.onelap.cn` instead of enabling cleartext globally.
 */
internal class OnelapClient(
    private val username: String,
    private val password: String,
    private val log: suspend (String) -> Unit,
) {

    private var cookies: String? = null

    suspend fun login(): Unit = withContext(Dispatchers.IO) {
        val payload = AppJson.encodeToString(
            OnelapLoginRequest.serializer(),
            OnelapLoginRequest(account = username, password = Hashing.md5Hex(password)),
        )
        val request = Request.Builder()
            .url("http://www.onelap.cn/api/login")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", HttpClients.defaultUserAgent)
            .build()

        HttpClients.api.newCall(request).execute().use { response ->
            val body = response.bodyTextOrThrow()
            val root = AppJson.parseToJsonElement(body)
            if (root.int("code") != 200) {
                throw PlatformException.Auth(
                    "OneLap 登录失败：${root.str("error") ?: "未知错误"}",
                )
            }
            // The legacy site authenticates the follow-up calls with a session
            // cookie rather than a bearer token.
            cookies = response.headers("Set-Cookie")
                .mapNotNull { it.substringBefore(';').takeIf(String::isNotBlank) }
                .joinToString("; ")
                .ifBlank { null }
        }
        log("OneLap 登录成功")
    }

    private fun authorized(url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("User-Agent", HttpClients.defaultUserAgent)
        cookies?.let { builder.header("Cookie", it) }
        return builder
    }

    suspend fun listActivities(limit: Int): List<ActivityMeta> = withContext(Dispatchers.IO) {
        val body = HttpClients.api.newCall(
            authorized("http://u.onelap.cn/analysis/list").build(),
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw PlatformException.Network("OneLap 活动列表失败：HTTP ${response.code}")
            }
            response.bodyTextOrThrow()
        }

        val root = AppJson.parseToJsonElement(body)
        val rows = root.arr("data")
            ?: throw PlatformException.Protocol("OneLap 活动列表响应缺少 data")

        // The API returns oldest-first; the UI wants newest-first.
        rows.reversed().take(limit).mapNotNull { row ->
            val id = row.str("created_at") ?: row.str("id") ?: return@mapNotNull null
            ActivityMeta(
                id = id,
                name = row.str("name")?.takeIf { it.isNotBlank() }
                    ?: "${row.str("date").orEmpty()}的骑行",
                startTime = TimeParsing.parseFlexible(
                    row.str("date") ?: row.str("startTime") ?: row.str("created_at"),
                ),
                distanceMeters = row.str("distance")?.toDoubleOrNull(),
                durationSec = (row.str("duration") ?: row.str("ridingTime") ?: row.str("total_time"))
                    ?.toDoubleOrNull()?.toLong(),
                sportType = row.str("sportType"),
            )
        }
    }

    suspend fun download(activityId: String): FitPayload = withContext(Dispatchers.IO) {
        // `durl` lives on the list row, so re-read the list to find it: OneLap
        // has no per-activity detail endpoint.
        val body = HttpClients.api.newCall(
            authorized("http://u.onelap.cn/analysis/list").build(),
        ).execute().use { it.bodyTextOrThrow() }
        val rows = AppJson.parseToJsonElement(body).arr("data")
            ?: throw PlatformException.Protocol("OneLap 活动列表响应缺少 data")

        val row = rows.firstOrNull {
            it.str("created_at") == activityId || it.str("id") == activityId
        } ?: throw PlatformException.Protocol("OneLap 未找到活动 $activityId")

        var durl = row.str("durl")
            ?: throw PlatformException.Protocol("OneLap 活动 $activityId 没有下载地址")
        if (!durl.startsWith("http")) durl = "http://u.onelap.cn$durl"

        val bytes = HttpClients.transfer.newCall(authorized(durl).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw PlatformException.Network("下载 OneLap 活动失败：HTTP ${response.code}")
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
}

object OnelapGateway : PlatformGateway {

    override suspend fun createSource(ctx: PlatformContext): PlatformSource {
        val username = ctx.account.email ?: throw PlatformException.Auth("未填写 OneLap 账号")
        val password = ctx.account.password ?: throw PlatformException.Auth("未填写 OneLap 密码")
        val client = OnelapClient(username, password, ctx.log)
        client.login()
        return object : PlatformSource {
            override val platform: Platform = Platform.ONELAP
            override suspend fun listActivities(limit: Int) = client.listActivities(limit)
            override suspend fun download(activity: ActivityMeta) = client.download(activity.id)
        }
    }

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget =
        throw PlatformException.Protocol("OneLap 仅支持作为数据来源，不支持上传")
}
