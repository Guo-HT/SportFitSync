package com.sportfit.sync.platform.igpsport

import com.sportfit.sync.platform.PlatformException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the interpretation of the `GetOssTokenForApp` response.
 *
 * The transport itself is not exercised (it needs a real iGPSPORT account);
 * what is asserted here is everything that can be decided without one: which
 * fields make up the credential triple and how a broken answer is rejected.
 */
class IgpsportOssProviderTest {

    private fun successBody(
        accessKeyId: String = "AK",
        accessKeySecret: String = "SK",
        securityToken: String = "ST",
        endpoint: String = "oss-cn-beijing.aliyuncs.com",
        bucket: String = "igpsport-oss",
    ): String = """
        {"code":0,"data":{
          "accessKeyId":"$accessKeyId",
          "accessKeySecret":"$accessKeySecret",
          "securityToken":"$securityToken",
          "endpoint":"$endpoint",
          "bucketName":"$bucket"
        }}
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    fun `parses the credential triple`() {
        val credentials = IgpsportOssProvider.parse(successBody())

        assertEquals("AK", credentials.accessKeyId)
        assertEquals("SK", credentials.accessKeySecret)
        assertEquals("ST", credentials.securityToken)
        assertEquals("oss-cn-beijing.aliyuncs.com", credentials.endpoint)
        assertEquals("igpsport-oss", credentials.bucket)
    }

    @Test
    fun `the endpoint loses its scheme so the uploader can build the url`() {
        val credentials = IgpsportOssProvider.parse(
            successBody(endpoint = "https://oss-cn-beijing.aliyuncs.com"),
        )

        assertEquals("oss-cn-beijing.aliyuncs.com", credentials.endpoint)
    }

    // -----------------------------------------------------------------------
    // Failures
    // -----------------------------------------------------------------------

    @Test
    fun `a business error is not mistaken for credentials`() {
        val body = """{"code":401,"message":"token expired"}"""

        val error = assertThrows(PlatformException.Protocol::class.java) {
            IgpsportOssProvider.parse(body)
        }
        assertTrue(error.message!!.contains("token expired"))
    }

    @Test
    fun `a response without data is rejected`() {
        val body = """{"code":0}"""

        val error = assertThrows(PlatformException.Protocol::class.java) {
            IgpsportOssProvider.parse(body)
        }
        assertTrue(error.message!!.contains("data"))
    }

    @Test
    fun `a missing credential field is rejected`() {
        val body = """
            {"code":0,"data":{
              "accessKeyId":"AK",
              "accessKeySecret":"SK",
              "endpoint":"oss-cn-beijing.aliyuncs.com",
              "bucketName":"igpsport-oss"
            }}
        """.trimIndent()

        val error = assertThrows(PlatformException.Protocol::class.java) {
            IgpsportOssProvider.parse(body)
        }
        assertTrue(error.message!!.contains("securityToken"))
    }

    @Test
    fun `a missing bucket is rejected`() {
        val body = """
            {"code":0,"data":{
              "accessKeyId":"AK",
              "accessKeySecret":"SK",
              "securityToken":"ST",
              "endpoint":"oss-cn-beijing.aliyuncs.com"
            }}
        """.trimIndent()

        val error = assertThrows(PlatformException.Protocol::class.java) {
            IgpsportOssProvider.parse(body)
        }
        assertTrue(error.message!!.contains("bucketName"))
    }

    @Test
    fun `a malformed body is rejected instead of crashing`() {
        val error = assertThrows(PlatformException.Protocol::class.java) {
            IgpsportOssProvider.parse("<html><body>502 Bad Gateway</body></html>")
        }
        assertTrue(error.message!!.contains("JSON"))
    }

    @Test
    fun `an empty body is rejected`() {
        assertThrows(PlatformException.Protocol::class.java) {
            IgpsportOssProvider.parse("")
        }
    }
}
