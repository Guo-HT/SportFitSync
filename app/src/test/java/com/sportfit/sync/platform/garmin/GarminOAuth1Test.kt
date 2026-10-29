package com.sportfit.sync.platform.garmin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminOAuth1Test {

    private fun url(query: String) =
        "https://connectapi.garmin.com/oauth-service/oauth/preauthorized?$query".toHttpUrl()

    @Test
    fun `login-url is percent encoded twice inside the base string`() {
        // Regression guard for the single most common Garmin auth failure:
        // the URL carries login-url already encoded, and RFC 5849 encoding
        // encodes it a second time. Getting this wrong returns 401 Invalid
        // signature, which is very hard to diagnose from the API response.
        val baseString = GarminOAuth1.signatureBaseString(
            method = "GET",
            url = url(
                "ticket=ST-12345" +
                    "&login-url=https://sso.garmin.com/sso/embed" +
                    "&accepts-mfa-tokens=true",
            ),
            oauthParams = mapOf("oauth_consumer_key" to "ck"),
        )

        assertTrue(
            "expected double-encoded login-url, got: $baseString",
            baseString.contains(
                "login-url%3Dhttps%253A%252F%252Fsso.garmin.com%252Fsso%252Fembed",
            ),
        )
        // The path is part of the base string, not the query.
        assertTrue(baseString.startsWith("GET&https%3A%2F%2Fconnectapi.garmin.com"))
    }

    @Test
    fun `query parameters are sorted by encoded name then value`() {
        val baseString = GarminOAuth1.signatureBaseString(
            method = "GET",
            url = url("b=2&a=1&a=0"),
            oauthParams = mapOf("oauth_consumer_key" to "ck"),
        )
        val encoded = baseString.substringAfterLast('&')
            .let { java.net.URLDecoder.decode(it.replace("%25", "%25"), "UTF-8") }

        // Decoding once yields the still-encoded parameter list; a and a=0 must
        // precede a=1, and the oauth_* params sort after the query params.
        val ordered = java.net.URLDecoder.decode(baseString, "UTF-8")
        assertTrue(ordered.indexOf("a=0") < ordered.indexOf("a=1"))
        assertTrue(ordered.indexOf("a=1") < ordered.indexOf("b=2"))
        assertTrue(ordered.indexOf("b=2") < ordered.indexOf("oauth_consumer_key=ck"))
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun `form body parameters participate in the signature`() {
        // `mfa_token` travels in an x-www-form-urlencoded body, so oauthlib
        // includes it in the base string; omitting it yields 401.
        val withoutBody = GarminOAuth1.signatureBaseString(
            method = "POST",
            url = "https://connectapi.garmin.com/oauth-service/oauth/exchange/user/2.0".toHttpUrl(),
            oauthParams = mapOf("oauth_token" to "ot"),
        )
        val withBody = GarminOAuth1.signatureBaseString(
            method = "POST",
            url = "https://connectapi.garmin.com/oauth-service/oauth/exchange/user/2.0".toHttpUrl(),
            oauthParams = mapOf("oauth_token" to "ot"),
            bodyFormParams = mapOf("mfa_token" to "MFA-1"),
        )

        assertTrue(withBody.contains("mfa_token"))
        assertTrue(withoutBody != withBody)
    }

    @Test
    fun `authorization header carries the oauth1 fields in oauthlib order`() {
        val header = GarminOAuth1.authorizationHeader(
            method = "GET",
            url = url("ticket=ST-1"),
            consumerKey = "fc3e99d2-118c-44b8-8ae3-03370dde24c0",
            consumerSecret = "secret",
        )

        assertTrue(header.startsWith("OAuth "))
        val order = listOf(
            "oauth_nonce=",
            "oauth_timestamp=",
            "oauth_version=",
            "oauth_signature_method=",
            "oauth_consumer_key=",
            "oauth_signature=",
        ).map { header.indexOf(it) }
        assertTrue("all oauth params present: $header", order.all { it >= 0 })
        assertEquals("params must appear in oauthlib order", order.sorted(), order)
        assertTrue(header.contains("oauth_signature_method=\"HMAC-SHA1\""))
        assertTrue(header.contains("oauth_version=\"1.0\""))
        // No resource owner at this stage.
        assertTrue(!header.contains("oauth_token="))
    }

    @Test
    fun `exchange header includes the oauth token`() {
        val header = GarminOAuth1.authorizationHeader(
            method = "POST",
            url = "https://connectapi.garmin.com/oauth-service/oauth/exchange/user/2.0".toHttpUrl(),
            consumerKey = "ck",
            consumerSecret = "secret",
            token = "ot-1",
            tokenSecret = "ots-1",
        )
        assertTrue(header.contains("oauth_token=\"ot-1\""))
    }
}
