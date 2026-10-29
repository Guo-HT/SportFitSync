package com.sportfit.sync.platform.garmin

import com.sportfit.sync.core.net.Hashing
import okhttp3.HttpUrl
import java.security.SecureRandom

/**
 * OAuth1 request signing (RFC 5849) for Garmin's `oauth-service` endpoints.
 *
 * Garmin uses the same consumer key for every region and expects
 * `HMAC-SHA1` with credentials in the `Authorization` header — exactly what
 * `requests_oauthlib` defaults to in the upstream Python client.
 *
 * Re-implemented by hand because the only Kotlin OAuth1 libraries on Maven
 * pull in a full OAuth stack we do not need for two requests.
 */
internal object GarminOAuth1 {

    private val random = SecureRandom()

    /** Fixed parameter order used by oauthlib, reproduced verbatim. */
    private val HEADER_ORDER = listOf(
        "oauth_nonce",
        "oauth_timestamp",
        "oauth_version",
        "oauth_signature_method",
        "oauth_consumer_key",
        "oauth_token",
        // Must be rendered last, and must be present: omitting it makes every
        // Garmin request fail with `401 Invalid signature`.
        "oauth_signature",
    )

    fun authorizationHeader(
        method: String,
        url: HttpUrl,
        consumerKey: String,
        consumerSecret: String,
        token: String? = null,
        tokenSecret: String? = null,
        bodyFormParams: Map<String, String> = emptyMap(),
    ): String {
        val nonce = nonce()
        val timestamp = (System.currentTimeMillis() / 1000).toString()

        val oauthParams = linkedMapOf(
            "oauth_nonce" to nonce,
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0",
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_consumer_key" to consumerKey,
        )
        if (!token.isNullOrEmpty()) oauthParams["oauth_token"] = token

        val baseString = signatureBaseString(method, url, oauthParams, bodyFormParams)
        val signingKey = Hashing.percentEncode(consumerSecret) + "&" +
            Hashing.percentEncode(tokenSecret.orEmpty())
        val signature = Hashing.base64(Hashing.hmacSha1(signingKey, baseString))

        val headerParams = oauthParams + ("oauth_signature" to signature)
        val rendered = HEADER_ORDER
            .filter { headerParams.containsKey(it) }
            .joinToString(", ") { name ->
                "$name=\"${Hashing.percentEncode(headerParams.getValue(name))}\""
            }
        return "OAuth $rendered"
    }

    /**
     * The RFC 5849 signature base string.
     *
     * Exposed separately because it encodes the one detail that silently
     * breaks Garmin auth: query values are percent-encoded *again* here, so
     * `login-url=https://…` becomes `login-url=https%3A%2F%2F…` in the base
     * string even though it appears encoded in the request URL. Getting this
     * wrong yields `401 Invalid signature`.
     */
    internal fun signatureBaseString(
        method: String,
        url: HttpUrl,
        oauthParams: Map<String, String>,
        bodyFormParams: Map<String, String> = emptyMap(),
    ): String {
        val signingParams = mutableListOf<Pair<String, String>>()
        oauthParams.forEach { (name, value) -> signingParams += name to value }
        url.queryParameterNames.forEach { name ->
            url.queryParameterValues(name).forEach { value ->
                signingParams += name to value.orEmpty()
            }
        }
        bodyFormParams.forEach { (name, value) -> signingParams += name to value }

        val encoded = signingParams
            .map { (name, value) -> Hashing.percentEncode(name) to Hashing.percentEncode(value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }

        val baseUrl = buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != HttpUrl.defaultPort(url.scheme)) append(':').append(url.port)
            append(url.encodedPath)
        }

        return listOf(
            method.uppercase(),
            Hashing.percentEncode(baseUrl),
            Hashing.percentEncode(encoded),
        ).joinToString("&")
    }

    private fun nonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
