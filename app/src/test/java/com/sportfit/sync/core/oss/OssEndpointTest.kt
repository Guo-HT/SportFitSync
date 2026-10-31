package com.sportfit.sync.core.oss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the endpoint normalisation the Aliyun uploader relies on.
 *
 * A platform hands its endpoint out in an unknown shape — a bare host, a full
 * URL, a URL with a trailing path — so it is normalised to the bare host the
 * request is sent to. The upload itself is not exercised here (it needs a
 * real bucket); what is asserted is everything that can be decided without
 * one: how an endpoint string is reduced, and that a blank one is refused.
 */
class OssEndpointTest {

    // -----------------------------------------------------------------------
    // Normalisation
    // -----------------------------------------------------------------------

    @Test
    fun `a bare host is kept as it is`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com",
            normalizeEndpointHost("oss-cn-beijing.aliyuncs.com"),
        )
    }

    @Test
    fun `the https scheme is stripped`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com",
            normalizeEndpointHost("https://oss-cn-beijing.aliyuncs.com"),
        )
    }

    @Test
    fun `the http scheme is stripped`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com",
            normalizeEndpointHost("http://oss-cn-beijing.aliyuncs.com"),
        )
    }

    @Test
    fun `a trailing slash is removed`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com",
            normalizeEndpointHost("https://oss-cn-beijing.aliyuncs.com/"),
        )
    }

    @Test
    fun `a path is dropped`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com",
            normalizeEndpointHost("https://oss-cn-beijing.aliyuncs.com/some/path"),
        )
    }

    @Test
    fun `a port is preserved`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com:8080",
            normalizeEndpointHost("https://oss-cn-beijing.aliyuncs.com:8080"),
        )
    }

    @Test
    fun `a port is preserved without a scheme`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com:8080",
            normalizeEndpointHost("oss-cn-beijing.aliyuncs.com:8080"),
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(
            "oss-cn-beijing.aliyuncs.com",
            normalizeEndpointHost("  https://oss-cn-beijing.aliyuncs.com  "),
        )
    }

    // -----------------------------------------------------------------------
    // Rejection
    // -----------------------------------------------------------------------

    @Test
    fun `a blank endpoint is rejected`() {
        val error = runCatching { normalizeEndpointHost("   ") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("阿里云 OSS endpoint 为空", error!!.message)
    }

    @Test
    fun `an empty endpoint is rejected`() {
        val error = runCatching { normalizeEndpointHost("") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("阿里云 OSS endpoint 为空", error!!.message)
    }
}
