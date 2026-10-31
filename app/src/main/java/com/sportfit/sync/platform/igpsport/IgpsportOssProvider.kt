package com.sportfit.sync.platform.igpsport

import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.int
import com.sportfit.sync.core.net.obj
import com.sportfit.sync.core.net.str
import com.sportfit.sync.core.oss.normalizeEndpointHost
import com.sportfit.sync.platform.PlatformException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Short-lived Aliyun OSS credentials minted by iGPSPORT for a single upload.
 *
 * The triple is an STS-style credential: it carries a security token and
 * expires shortly after it was handed out, so it is fetched right before the
 * object is written and never persisted.
 */
internal data class IgpsportOssCredentials(
    val accessKeyId: String,
    val accessKeySecret: String,
    val securityToken: String,
    /** OSS host, e.g. `oss-cn-beijing.aliyuncs.com` (no scheme). */
    val endpoint: String,
    val bucket: String,
)

/**
 * Mints the Aliyun OSS credentials an iGPSPORT upload needs.
 *
 * iGPSPORT has no direct file endpoint: the FIT is written into the company's
 * OSS bucket first and the web API is then told about the object, which is
 * the same two-step dance the upstream Python project
 * ([Dunky-Z/FitSync](https://github.com/Dunky-Z/FitSync)) performs.
 *
 * ```
 * GET {baseUrl}/mobile/api/AliyunService/GetOssTokenForApp
 * Authorization: Bearer <access token>
 * → {"code":0,"data":{"accessKeyId":…,"accessKeySecret":…,"securityToken":…,
 *                     "endpoint":…,"bucketName":…}}
 * ```
 *
 * The route lives under the same regional service root as the rest of the
 * API (`https://prod.zh.igpsport.com/service` for China,
 * `https://prod.en.igpsport.com/service` for the global site).
 */
internal object IgpsportOssProvider {

    /** Route that hands out the STS triple. */
    private const val OSS_TOKEN_PATH = "/mobile/api/AliyunService/GetOssTokenForApp"

    /**
     * Fetches the short-lived Aliyun OSS credentials for one upload.
     *
     * @param baseUrl the regional service root, e.g.
     *   `https://prod.zh.igpsport.com/service`.
     * @param bearer the login token; the route answers HTTP 401 without it.
     */
    suspend fun fetch(baseUrl: String, bearer: String): IgpsportOssCredentials =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$OSS_TOKEN_PATH")
                .get()
                .header("user-agent", HttpClients.defaultUserAgent)
                .header("accept", "application/json, text/plain, */*")
                .header("authorization", "Bearer $bearer")
                .build()

            val body = HttpClients.api.newCall(request).execute().use { response ->
                // The status code comes first: without the bearer token the
                // route answers HTTP 401 instead of a JSON body.
                when {
                    response.code == 401 ->
                        throw PlatformException.Auth("iGPSPORT 登录态已失效，无法获取 OSS 凭证")
                    response.code == 403 ->
                        throw PlatformException.Auth("iGPSPORT 拒绝发放 OSS 凭证：权限不足")
                    !response.isSuccessful ->
                        throw IOException("获取 iGPSPORT OSS 凭证失败：HTTP ${response.code}")
                    else -> response.bodyTextOrThrow()
                }
            }
            parse(body)
        }

    /**
     * Parses a `GetOssTokenForApp` response body.
     *
     * @throws PlatformException.Protocol when the body is not JSON, carries a
     *   business error, or misses one of the credential fields.
     */
    internal fun parse(body: String): IgpsportOssCredentials {
        val root = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()
            ?: throw PlatformException.Protocol(
                "iGPSPORT OSS 凭证响应不是 JSON：${body.take(60)}",
            )

        val code = root.int("code")
        if (code != null && code != 0) {
            throw PlatformException.Protocol(
                "iGPSPORT OSS 凭证获取失败：${root.str("message") ?: "未知错误"}",
            )
        }

        val data = root.obj("data")
            ?: throw PlatformException.Protocol("iGPSPORT OSS 凭证响应缺少 data")

        val accessKeyId = data.str("accessKeyId")
            ?: throw PlatformException.Protocol("iGPSPORT OSS 凭证缺少 accessKeyId")
        val accessKeySecret = data.str("accessKeySecret")
            ?: throw PlatformException.Protocol("iGPSPORT OSS 凭证缺少 accessKeySecret")
        val securityToken = data.str("securityToken")
            ?: throw PlatformException.Protocol("iGPSPORT OSS 凭证缺少 securityToken")
        val endpoint = data.str("endpoint")
            ?: throw PlatformException.Protocol("iGPSPORT OSS 凭证缺少 endpoint")
        // The web API calls the bucket `bucketName`; `bucket` is accepted as a
        // fallback because the two spellings show up in the wild.
        val bucket = data.str("bucketName") ?: data.str("bucket")
            ?: throw PlatformException.Protocol("iGPSPORT OSS 凭证缺少 bucketName")

        return IgpsportOssCredentials(
            accessKeyId = accessKeyId,
            accessKeySecret = accessKeySecret,
            securityToken = securityToken,
            endpoint = normalizeEndpoint(endpoint),
            bucket = bucket.trim(),
        )
    }

    /**
     * Normalises the endpoint into a bare OSS host.
     *
     * iGPSPORT answers with the endpoint the web app uses, with or without a
     * scheme (`https://oss-cn-beijing.aliyuncs.com` vs
     * `oss-cn-beijing.aliyuncs.com`). The shared uploader builds the object
     * URL itself, so only the host is kept here; the rule itself lives in
     * `core/oss` so every platform normalises an endpoint the same way.
     */
    private fun normalizeEndpoint(raw: String): String {
        if (raw.isBlank()) {
            throw PlatformException.Protocol("iGPSPORT OSS 凭证的 endpoint 为空")
        }
        return normalizeEndpointHost(raw)
    }
}
