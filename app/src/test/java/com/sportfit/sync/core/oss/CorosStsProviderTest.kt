package com.sportfit.sync.core.oss

import com.sportfit.sync.core.net.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the STS channel selection and the credential decoding.
 *
 * The transport itself is not exercised (it needs a real Coros account); what
 * is asserted here is everything that can be decided without one: which
 * endpoint is used for which region, which authentication it carries, and how
 * the responses are interpreted.
 */
class CorosStsProviderTest {

    private fun encodeCredentials(json: String): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        // Coros mixes the salt into the blob at an arbitrary position.
        return encoded.substring(0, 3) + Hashing.COROS_STS_SALT + encoded.substring(3)
    }

    private fun successBody(json: String): String =
        """{"code":200,"data":{"credentials":"${encodeCredentials(json)}"}}"""

    // -----------------------------------------------------------------------
    // Channel selection
    // -----------------------------------------------------------------------

    @Test
    fun `china region uses the chinese training centre proxy`() {
        val channels = CorosStsProvider.stsChannels(
            regionId = 2,
            bucket = "coros-oss",
            service = "aliyun",
            accessToken = "token-123",
        )

        val proxy = channels.first()
        assertEquals("https://trainingcn.coros.com/api/proxy/oss/sts?bucket=coros-oss&service=aliyun&v=2", proxy.url)
        assertEquals("CPL-coros-token=token-123", proxy.headers["Cookie"])
    }

    @Test
    fun `overseas regions use the international training centre proxy`() {
        val channels = CorosStsProvider.stsChannels(
            regionId = 1,
            bucket = "coros-s3",
            service = "aws",
            accessToken = "token-123",
        )

        assertTrue(channels.first().url.startsWith("https://training.coros.com/api/proxy/oss/sts?"))
        assertTrue(channels.first().url.contains("bucket=coros-s3"))
        assertTrue(channels.first().url.contains("service=aws"))

        val eu = CorosStsProvider.stsChannels(3, "eu-coros", "aws", "token-123")
        assertTrue(eu.first().url.startsWith("https://training.coros.com/api/proxy/oss/sts?"))
    }

    @Test
    fun `without a login token the proxy channel is skipped`() {
        val channels = CorosStsProvider.stsChannels(
            regionId = 2,
            bucket = "coros-oss",
            service = "aliyun",
            accessToken = null,
        )

        assertEquals(1, channels.size)
        assertTrue(channels.single().url.startsWith("https://faq.coros.com/openapi/oss/sts/"))
    }

    @Test
    fun `the legacy channel is always the last resort`() {
        val channels = CorosStsProvider.stsChannels(
            regionId = 2,
            bucket = "coros-oss",
            service = "aliyun",
            accessToken = "token-123",
        )

        assertEquals(2, channels.size)
        val legacy = channels.last()
        assertTrue(legacy.url.startsWith("https://faq.coros.com/openapi/oss/sts/?"))
        assertTrue(legacy.url.contains("app_id=1660188068672619112"))
        assertTrue(legacy.url.contains("v=2"))
        // The legacy channel is the anonymous one: no cookie is attached.
        assertTrue(legacy.headers.isEmpty())
    }

    @Test
    fun `the legacy signature is per bucket, not per storage service`() {
        // coros-s3 (international) and eu-coros (Europe) are both AWS but do
        // not share a signature.
        val international = CorosStsProvider.stsChannels(1, "coros-s3", "aws", "token-123").last()
        val europe = CorosStsProvider.stsChannels(3, "eu-coros", "aws", "token-123").last()

        assertTrue(international.url.contains("sign=E34EF0E34A498A54A9C3EAEFC12B7CAF"))
        assertTrue(europe.url.contains("sign=877571111A1EE5316E4B590103D4B5B3"))
    }

    // -----------------------------------------------------------------------
    // Response interpretation
    // -----------------------------------------------------------------------

    @Test
    fun `parses aliyun style credentials`() {
        val body = successBody(
            """{"AccessKeyId":"AK","AccessKeySecret":"SK","SecurityToken":"ST"}""",
        )

        val result = CorosStsProvider.parseStsResponse(200, body)
        assertTrue(result is CorosStsProvider.StsResult.Ok)
        val credentials = (result as CorosStsProvider.StsResult.Ok).credentials
        assertEquals("AK", credentials.accessKeyId)
        assertEquals("SK", credentials.accessKeySecret)
        assertEquals("ST", credentials.securityToken)
    }

    @Test
    fun `parses aws style credentials`() {
        val body = successBody(
            """{"AccessKeyId":"AK","SecretAccessKey":"SK","SessionToken":"ST"}""",
        )

        val result = CorosStsProvider.parseStsResponse(200, body)
        assertTrue(result is CorosStsProvider.StsResult.Ok)
        val credentials = (result as CorosStsProvider.StsResult.Ok).credentials
        assertEquals("AK", credentials.accessKeyId)
        assertEquals("SK", credentials.accessKeySecret)
        assertEquals("ST", credentials.securityToken)
    }

    @Test
    fun `a rejected login token is reported as token_invalid`() {
        val body = """{"code":401,"msg":"unauthorized","reason":"token_invalid"}"""

        val result = CorosStsProvider.parseStsResponse(401, body)
        assertTrue(result is CorosStsProvider.StsResult.Failed)
        assertTrue((result as CorosStsProvider.StsResult.Failed).reason.contains("token_invalid"))
    }

    @Test
    fun `a missing token is reported as no_token`() {
        val body = """{"code":401,"msg":"unauthorized","reason":"no_token"}"""

        val result = CorosStsProvider.parseStsResponse(401, body)
        assertTrue(result is CorosStsProvider.StsResult.Failed)
        assertTrue((result as CorosStsProvider.StsResult.Failed).reason.contains("no_token"))
    }

    @Test
    fun `the legacy policy rejection keeps the server message`() {
        // faq.coros.com answers HTTP 403 with a plain msg and no code field.
        val body = """{"msg":"sts for this bucket is only available via the v2 channel"}"""

        val result = CorosStsProvider.parseStsResponse(403, body)
        assertTrue(result is CorosStsProvider.StsResult.Failed)
        val reason = (result as CorosStsProvider.StsResult.Failed).reason
        assertTrue(reason.contains("v2 channel"))
        assertTrue(reason.contains("403"))
    }

    @Test
    fun `a business error inside HTTP 200 is not mistaken for success`() {
        val body = """{"code":1019,"msg":"Access token is invalid"}"""

        val result = CorosStsProvider.parseStsResponse(200, body)
        assertTrue(result is CorosStsProvider.StsResult.Failed)
        assertTrue((result as CorosStsProvider.StsResult.Failed).reason.contains("1019"))
    }

    @Test
    fun `an html error page is not mistaken for credentials`() {
        val body = "<html><head><title>404 Not Found</title></head></html>"

        val result = CorosStsProvider.parseStsResponse(404, body)
        assertTrue(result is CorosStsProvider.StsResult.Failed)
        assertNotNull((result as CorosStsProvider.StsResult.Failed).reason)
    }
}
