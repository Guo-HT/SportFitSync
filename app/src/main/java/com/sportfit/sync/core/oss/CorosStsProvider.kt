package com.sportfit.sync.core.oss

import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.Hashing
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.int
import com.sportfit.sync.core.net.obj
import com.sportfit.sync.core.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Temporary OSS credentials minted by Coros.
 *
 * Coros users are split across two object stores: Aliyun OSS for the China
 * region and AWS S3 for the international/European regions. Both are reached
 * through the same STS service with different bucket/service/sign triples.
 */
data class CorosStsCredentials(
    val regionId: Int,
    val bucket: String,
    val service: String,
    val accessKeyId: String,
    val accessKeySecret: String,
    /** Aliyun `SecurityToken` / AWS `SessionToken`. */
    val securityToken: String,
) {
    val isAliyun: Boolean get() = service == "aliyun"
}

/**
 * Mints the short-lived OSS credentials that a Coros upload needs.
 *
 * ## How the channel moved (2026-10-03)
 *
 * Coros used to hand out STS credentials anonymously:
 *
 * ```
 * GET https://faq.coros.com/openapi/oss/sts?bucket=…&service=…&app_id=…&sign=…&v=2
 * ```
 *
 * That endpoint is **gone** (nginx 404). The trailing-slash variant still
 * reaches the API but is refused by policy:
 *
 * ```
 * GET https://faq.coros.com/openapi/oss/sts/?…   → 403 {"msg":"sts for this
 *                                                    bucket is only available
 *                                                    via the v2 channel"}
 * ```
 *
 * The "v2 channel" the message refers to is what the **web training centre**
 * uses: a BFF proxy in front of the very same STS service, authenticated with
 * the account's login token instead of a static signature.
 *
 * ```
 * GET https://trainingcn.coros.com/api/proxy/oss/sts?bucket=coros-oss&service=aliyun&v=2
 * Cookie: CPL-coros-token=<accessToken from account/login>
 * ```
 *
 * Verified by hand on 2026-10-27 (see upstream issue #41 / PR #42 of
 * `XiaoSiHwang/garmin-sync-coros`, which found the same channel):
 *
 * | request | answer |
 * |---|---|
 * | no cookie | 401 `{"code":401,"msg":"unauthorized","reason":"no_token"}` |
 * | `Cookie: CPL-coros-token=<bogus>` | 401 `{"code":401,…,"reason":"token_invalid"}` |
 * | `accesstoken:` / `Authorization:` header instead | 401 `…,"reason":"no_token"` |
 *
 * The `token_invalid` vs `no_token` split is the proof that the cookie is the
 * recognised mechanism: only the cookie reaches the token check.
 *
 * The successful payload is unchanged (`{"code":200,"data":{"credentials":…}}`)
 * and the credential blob still decodes with the same salt, so only the
 * transport changed.
 */
object CorosStsProvider {

    private const val STS_VERSION = 2
    private const val APP_ID = "1660188068672619112"

    /**
     * Legacy anonymous endpoint.
     *
     * The trailing slash is deliberate: without it nginx answers 404 for a
     * route that no longer exists, with it the request reaches the API and is
     * rejected with a policy message that explains *why*.
     */
    private const val LEGACY_STS_ENDPOINT = "https://faq.coros.com/openapi/oss/sts/"

    /** Cookie name the training centre proxy expects the login token in. */
    internal const val TOKEN_COOKIE_NAME = "CPL-coros-token"

    /** Verbatim from the upstream `sts_config.py`. */
    private val BUCKET_BY_REGION = mapOf(
        1 to ("coros-s3" to "aws"),
        2 to ("coros-oss" to "aliyun"),
        3 to ("eu-coros" to "aws"),
        101 to ("test-coros" to "aws"),
        102 to ("coros-test-bj" to "aliyun"),
        103 to ("test-eu-coros" to "aws"),
    )

    /**
     * Host of the training centre that fronts the STS service.
     *
     * The Chinese web app lives on `trainingcn.coros.com`, the international
     * one on `training.coros.com`; both expose the same proxy route.
     */
    private val PROXY_HOST_BY_REGION = mapOf(
        1 to "training.coros.com",
        2 to "trainingcn.coros.com",
        3 to "training.coros.com",
        101 to "training.coros.com",
        102 to "trainingcn.coros.com",
        103 to "training.coros.com",
    )

    /**
     * Static signature of the legacy channel — one per **bucket**, not per
     * storage service.
     *
     * `coros-s3` (international) and `eu-coros` (Europe) are both AWS but use
     * different signatures; sharing one of them yields `signature error`.
     */
    private val SIGN_BY_REGION = mapOf(
        1 to "E34EF0E34A498A54A9C3EAEFC12B7CAF",
        2 to "9AD4AA35AAFEE6BB1E847A76848D58DF",
        3 to "877571111A1EE5316E4B590103D4B5B3",
    )

    /** Best-effort signatures for the test regions, whose own ones are unknown. */
    private val SIGN_BY_SERVICE = mapOf(
        "aliyun" to "9AD4AA35AAFEE6BB1E847A76848D58DF",
        "aws" to "877571111A1EE5316E4B590103D4B5B3",
    )

    /**
     * Fetches credentials for the account's region.
     *
     * @param accessToken the token from `account/login`; it authorises the
     *   training-centre proxy. Without it only the legacy channel is tried.
     */
    suspend fun fetch(regionId: Int, accessToken: String?): CorosStsCredentials =
        withContext(Dispatchers.IO) {
            val (bucket, service) = BUCKET_BY_REGION[regionId]
                ?: throw IOException("未知的 Coros 区域：$regionId")

            val failures = mutableListOf<String>()
            var tokenRejected = false

            for (channel in stsChannels(regionId, bucket, service, accessToken)) {
                val request = Request.Builder()
                    .url(channel.url)
                    .get()
                    .apply { channel.headers.forEach { (name, value) -> header(name, value) } }
                    .build()

                val answer = try {
                    HttpClients.corosSts.newCall(request).execute().use { response ->
                        response.bodyTextOrThrow() to response.code
                    }
                } catch (error: IOException) {
                    failures += "${channel.label}：请求失败（${error.message ?: "网络错误"}）"
                    null
                }
                if (answer == null) continue

                when (val result = parseStsResponse(answer.second, answer.first)) {
                    is StsResult.Ok -> return@withContext CorosStsCredentials(
                        regionId = regionId,
                        bucket = bucket,
                        service = service,
                        accessKeyId = result.credentials.accessKeyId,
                        accessKeySecret = result.credentials.accessKeySecret,
                        securityToken = result.credentials.securityToken,
                    )

                    is StsResult.Failed -> {
                        failures += "${channel.label}：${result.reason}"
                        if (result.reason.contains("token_invalid")) tokenRejected = true
                    }
                }
            }

            throw IOException(
                buildString {
                    append("无法获取 Coros OSS 临时凭证（已尝试 ")
                    append(failures.size)
                    append(" 个通道）：")
                    append(failures.joinToString("；"))
                    if (tokenRejected) {
                        append("。训练中心代理拒绝了当前登录令牌，请重新登录 Coros 账号后重试")
                    }
                },
            )
        }

    /**
     * The channels to try, in order.
     *
     * The proxy comes first because it is the only one that still works; the
     * legacy endpoint is kept only in case Coros restores the anonymous
     * channel (its answer explains the policy, which is worth having).
     */
    internal fun stsChannels(
        regionId: Int,
        bucket: String,
        service: String,
        accessToken: String?,
    ): List<StsChannel> {
        val channels = mutableListOf<StsChannel>()

        val proxyHost = PROXY_HOST_BY_REGION[regionId]
        if (proxyHost != null && !accessToken.isNullOrBlank()) {
            channels += StsChannel(
                label = "训练中心代理 $proxyHost",
                url = "https://$proxyHost/api/proxy/oss/sts" +
                    "?bucket=$bucket&service=$service&v=$STS_VERSION",
                headers = mapOf("Cookie" to "$TOKEN_COOKIE_NAME=$accessToken"),
            )
        }

        val sign = SIGN_BY_REGION[regionId] ?: SIGN_BY_SERVICE.getValue(service)
        channels += StsChannel(
            label = "旧版匿名接口 faq.coros.com",
            url = "$LEGACY_STS_ENDPOINT?bucket=$bucket&service=$service" +
                "&app_id=$APP_ID&sign=$sign&v=$STS_VERSION",
            headers = emptyMap(),
        )

        return channels
    }

    /** One STS endpoint plus the headers needed to reach it. */
    internal data class StsChannel(
        val label: String,
        val url: String,
        val headers: Map<String, String>,
    )

    /** The decoded credential triple, before it is bound to a region. */
    internal data class RawCredentials(
        val accessKeyId: String,
        val accessKeySecret: String,
        val securityToken: String,
    )

    /** Outcome of interpreting one STS response. */
    internal sealed class StsResult {
        data class Ok(val credentials: RawCredentials) : StsResult()
        data class Failed(val reason: String) : StsResult()
    }

    /**
     * Interprets one STS response.
     *
     * Coros mixes two failure styles: HTTP status codes (`401` from the
     * proxy) and a business `code` field inside an HTTP 200 body.
     */
    internal fun parseStsResponse(code: Int, body: String): StsResult {
        if (code == 401) {
            val reason = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()
                ?.str("reason")
                ?: "unauthorized"
            return StsResult.Failed("HTTP 401（$reason）")
        }

        val root = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()
            ?: return StsResult.Failed("响应不是 JSON（${body.take(60)}）")

        val businessCode = root.int("code")
        if (businessCode != null && businessCode != 200) {
            val message = root.str("msg") ?: root.str("message") ?: "未知错误"
            return StsResult.Failed("HTTP $code，业务错误 code=$businessCode：$message")
        }
        if (businessCode == null) {
            // Some Coros endpoints answer without a `code` field, e.g. the
            // legacy channel's policy rejection.
            val message = root.str("msg") ?: root.str("message")
            if (message != null) return StsResult.Failed("HTTP $code：$message")
        }

        val encoded = root.obj("data").str("credentials")
            ?: return StsResult.Failed("响应缺少 data.credentials")

        val decoded = runCatching {
            AppJson.parseToJsonElement(Hashing.decodeCorosStsCredentials(encoded))
        }.getOrNull() ?: return StsResult.Failed("凭证解码失败")

        val accessKeyId = decoded.str("AccessKeyId")
            ?: return StsResult.Failed("凭证缺少 AccessKeyId")
        // Aliyun calls it `AccessKeySecret`/`SecurityToken`, AWS calls it
        // `SecretAccessKey`/`SessionToken`; both spellings show up in the wild.
        val secret = decoded.str("AccessKeySecret") ?: decoded.str("SecretAccessKey")
            ?: return StsResult.Failed("凭证缺少密钥")
        val token = decoded.str("SecurityToken") ?: decoded.str("SessionToken") ?: ""

        return StsResult.Ok(RawCredentials(accessKeyId, secret, token))
    }
}
