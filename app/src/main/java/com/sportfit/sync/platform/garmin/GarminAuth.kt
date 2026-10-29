package com.sportfit.sync.platform.garmin

import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.double
import com.sportfit.sync.core.net.long
import com.sportfit.sync.core.net.str
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.platform.MfaProvider
import com.sportfit.sync.platform.PlatformException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder

/**
 * Garmin Connect authentication: SSO → service ticket → OAuth1 → OAuth2.
 *
 * This mirrors `garth==0.4.38` (`sso.py` + `http.py`) step for step, including
 * its quirks, because the endpoint is unforgiving about them:
 *
 *  - `User-Agent` is the iPhone string for SSO, but
 *    `com.garmin.android.apps.connectmobile` for the two OAuth1 calls.
 *  - MFA is detected purely by the substring `MFA` in the final page title.
 *  - The CSRF token must be re-read from the MFA page, not reused.
 *  - `login-url` is percent-encoded twice inside the signature base string
 *    (a single encode yields `401 Invalid signature`).
 */
class GarminAuth(
    private val mfa: MfaProvider,
    private val log: suspend (String) -> Unit = {},
) {

    /** Response material captured before the connection is released. */
    private data class HttpResult(val code: Int, val body: String, val finalUrl: String)

    suspend fun login(
        email: String,
        password: String,
        domains: GarminDomains,
    ): GarminTokens = withContext(Dispatchers.IO) {
        val consumer = consumerCredentials()

        // Step 1 — seed the Cloudflare/SSO cookies.
        val embedUrl = buildUrl(
            domains.ssoEmbed,
            linkedMapOf(
                "id" to "gauth-widget",
                "embedWidget" to "true",
                "gauthHost" to domains.sso,
            ),
        )
        get(embedUrl, userAgent = SSO_USER_AGENT)

        // Step 2 — fetch the sign-in page and its CSRF token.
        val signinParams = linkedMapOf(
            "id" to "gauth-widget",
            "embedWidget" to "true",
            "gauthHost" to domains.ssoEmbed,
            "service" to domains.ssoEmbed,
            "source" to domains.ssoEmbed,
            "redirectAfterAccountLoginUrl" to domains.ssoEmbed,
            "redirectAfterAccountCreationUrl" to domains.ssoEmbed,
        )
        val signinUrl = buildUrl("${domains.sso}/signin", signinParams)
        val signinPage = get(signinUrl, userAgent = SSO_USER_AGENT, referer = embedUrl.toString())
        val signinHtml = signinPage.body
        var html = signinHtml
        var title = extractTitle(html)
            ?: throw PlatformException.Auth("Garmin 登录页解析失败（未找到标题）")

        // Step 3 — submit credentials.
        val csrf = extractCsrf(signinHtml)
            ?: throw PlatformException.Auth("Garmin 登录页解析失败（未找到 _csrf）")
        val loginResponse = postForm(
            url = signinUrl,
            form = linkedMapOf(
                "username" to email,
                "password" to password,
                "embed" to "true",
                "_csrf" to csrf,
            ),
            userAgent = SSO_USER_AGENT,
            referer = signinPage.finalUrl,
        )
        if (loginResponse.code == 401) {
            throw PlatformException.Auth("Garmin 账号或密码错误")
        }
        html = loginResponse.body
        extractTitle(html)?.let { title = it }

        // Step 3b — optional MFA challenge. Garmin signals it only through the
        // page title ("Enter MFA code for login"), with no dedicated status code.
        if (title.contains("MFA")) {
            val code = mfa.requestCode(Platform.GARMIN)
                ?: throw PlatformException.Auth("Garmin 需要两步验证码，但未输入")
            val mfaCsrf = extractCsrf(html)
                ?: throw PlatformException.Auth("Garmin 两步验证页解析失败（未找到 _csrf）")
            log("Garmin 提交两步验证码…")
            val mfaResponse = postForm(
                url = buildUrl("${domains.sso}/verifyMFA/loginEnterMfaCode", signinParams),
                form = linkedMapOf(
                    "mfa-code" to code,
                    "embed" to "true",
                    "_csrf" to mfaCsrf,
                    "fromPage" to "setupEnterMfaCode",
                ),
                userAgent = SSO_USER_AGENT,
                referer = loginResponse.finalUrl,
            )
            html = mfaResponse.body
            extractTitle(html)?.let { title = it }
        }

        if (title != "Success") {
            throw PlatformException.Auth("Garmin 登录未成功（页面标题：$title）")
        }

        val ticket = TICKET_REGEX.find(html)?.groupValues?.get(1)
            ?: throw PlatformException.Auth("Garmin 登录成功但未取到 ticket")

        exchange(oauth1FromTicket(ticket, domains, consumer), domains, consumer)
    }

    /**
     * Mints a fresh OAuth2 token from an existing OAuth1 token.
     *
     * This *is* Garmin's refresh mechanism — the same endpoint used at the end
     * of a normal login, with no `refresh_token` parameter involved.
     */
    suspend fun refresh(oauth1: GarminTokens): GarminTokens = withContext(Dispatchers.IO) {
        exchange(oauth1, GarminDomains(oauth1.domain), consumerCredentials())
    }

    // -----------------------------------------------------------------------

    private fun oauth1FromTicket(
        ticket: String,
        domains: GarminDomains,
        consumer: ConsumerCredentials,
    ): GarminTokens {
        val url = buildUrl(
            "${domains.connectApi}/oauth-service/oauth/preauthorized",
            linkedMapOf(
                "ticket" to ticket,
                "login-url" to domains.ssoEmbed,
                "accepts-mfa-tokens" to "true",
            ),
        )
        // No oauth_token at this stage: there is no resource owner yet.
        val authorization = GarminOAuth1.authorizationHeader(
            method = "GET",
            url = url,
            consumerKey = consumer.key,
            consumerSecret = consumer.secret,
        )
        val result = execute(
            Request.Builder()
                .url(url)
                .header("User-Agent", OAUTH_USER_AGENT)
                .header("Authorization", authorization)
                .build(),
        )
        if (result.code !in 200..299) {
            throw PlatformException.Auth("Garmin 换取 OAuth1 令牌失败：HTTP ${result.code}")
        }

        val params = parseForm(result.body)
        val token = params["oauth_token"]
            ?: throw PlatformException.Auth("Garmin 未返回 oauth_token")
        val secret = params["oauth_token_secret"]
            ?: throw PlatformException.Auth("Garmin 未返回 oauth_token_secret")

        return GarminTokens(
            domain = domains.domain,
            oauth1Token = token,
            oauth1Secret = secret,
            accessToken = "",
            mfaToken = params["mfa_token"],
            mfaExpiresAt = params["mfa_expiration_timestamp"],
        )
    }

    private suspend fun exchange(
        oauth1: GarminTokens,
        domains: GarminDomains,
        consumer: ConsumerCredentials,
    ): GarminTokens {
        val url = "${domains.connectApi}/oauth-service/oauth/exchange/user/2.0".toHttpUrl()
        // The body is form-encoded, so `mfa_token` participates in the
        // signature; changing the body without re-signing yields 401.
        val bodyParams: Map<String, String> =
            oauth1.mfaToken?.let { linkedMapOf("mfa_token" to it) } ?: emptyMap()

        val authorization = GarminOAuth1.authorizationHeader(
            method = "POST",
            url = url,
            consumerKey = consumer.key,
            consumerSecret = consumer.secret,
            token = oauth1.oauth1Token,
            tokenSecret = oauth1.oauth1Secret,
            bodyFormParams = bodyParams,
        )

        val formBuilder = FormBody.Builder()
        bodyParams.forEach { (name, value) -> formBuilder.add(name, value) }

        val result = execute(
            Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .header("User-Agent", OAUTH_USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .build(),
        )
        if (result.code !in 200..299) {
            throw PlatformException.Auth("Garmin 换取访问令牌失败：HTTP ${result.code}")
        }

        val json = AppJson.parseToJsonElement(result.body)
        val accessToken = json.str("access_token")
            ?: throw PlatformException.Auth("Garmin 未返回 access_token")
        val nowSeconds = System.currentTimeMillis() / 1000

        // Garmin returns lifetimes, not absolute expiries; garth computes the
        // absolute values client-side and so do we.
        val expiresIn = json.long("expires_in") ?: json.double("expires_in")?.toLong() ?: 0L
        val refreshExpiresIn = json.long("refresh_token_expires_in")
            ?: json.double("refresh_token_expires_in")?.toLong() ?: 0L

        log("Garmin 登录成功（域：${domains.domain}）")
        return oauth1.copy(
            accessToken = accessToken,
            refreshToken = json.str("refresh_token").orEmpty(),
            tokenType = json.str("token_type") ?: "Bearer",
            scope = json.str("scope"),
            expiresAt = nowSeconds + expiresIn,
            refreshTokenExpiresAt = nowSeconds + refreshExpiresIn,
        )
    }

    // -----------------------------------------------------------------------

    data class ConsumerCredentials(val key: String, val secret: String)

    /** Fetched once per process; the embedded pair is the offline fallback. */
    @Volatile
    private var cachedConsumer: ConsumerCredentials? = null

    private fun consumerCredentials(): ConsumerCredentials {
        cachedConsumer?.let { return it }
        val fetched = try {
            val result = execute(Request.Builder().url(CONSUMER_URL).build())
            if (result.code !in 200..299) throw IOException("HTTP ${result.code}")
            val json = AppJson.parseToJsonElement(result.body)
            ConsumerCredentials(
                key = json.str("consumer_key") ?: throw IOException("missing consumer_key"),
                secret = json.str("consumer_secret") ?: throw IOException("missing consumer_secret"),
            )
        } catch (_: Exception) {
            null
        }
        val resolved = fetched ?: EMBEDDED_CONSUMER
        cachedConsumer = resolved
        return resolved
    }

    private fun execute(request: Request): HttpResult =
        HttpClients.api.newCall(request).execute().use { response ->
            HttpResult(
                code = response.code,
                body = response.bodyTextOrThrow(),
                finalUrl = response.request.url.toString(),
            )
        }

    private fun get(url: HttpUrl, userAgent: String, referer: String? = null): HttpResult {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        // garth sends the *post-redirect* URL of the previous step, lowercase
        // header name, including the query string.
        referer?.let { builder.header("referer", it) }
        return execute(builder.build())
    }

    private fun postForm(
        url: HttpUrl,
        form: Map<String, String>,
        userAgent: String,
        referer: String? = null,
    ): HttpResult {
        val body = FormBody.Builder().apply {
            form.forEach { (name, value) -> add(name, value) }
        }.build()
        val builder = Request.Builder().url(url).post(body).header("User-Agent", userAgent)
        referer?.let { builder.header("referer", it) }
        return execute(builder.build())
    }

    private fun buildUrl(base: String, params: Map<String, String>): HttpUrl {
        val builder = base.toHttpUrl().newBuilder()
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) return@mapNotNull null
                decodePercent(pair.substring(0, index)) to decodePercent(pair.substring(index + 1))
            }
            .toMap()

    private fun decodePercent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private companion object {
        const val CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"

        /** Observed 2026-10-07; refreshed from [CONSUMER_URL] when reachable. */
        val EMBEDDED_CONSUMER = ConsumerCredentials(
            key = "fc3e99d2-118c-44b8-8ae3-03370dde24c0",
            secret = "E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF",
        )

        const val SSO_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        const val OAUTH_USER_AGENT = "com.garmin.android.apps.connectmobile"

        val CSRF_REGEX = Regex("""name="_csrf"\s+value="(.+?)"""")
        val TITLE_REGEX = Regex("<title>(.+?)</title>")
        val TICKET_REGEX = Regex("""embed\?ticket=([^"]+)""")

        fun extractCsrf(html: String): String? = CSRF_REGEX.find(html)?.groupValues?.get(1)

        fun extractTitle(html: String): String? = TITLE_REGEX.find(html)?.groupValues?.get(1)
    }
}
