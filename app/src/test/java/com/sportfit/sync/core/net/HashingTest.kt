package com.sportfit.sync.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {

    @Test
    fun `md5 matches the canonical vector`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Hashing.md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Hashing.md5Hex(""))
    }

    @Test
    fun `sha256 matches the canonical vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256Hex("abc"),
        )
    }

    @Test
    fun `percentEncode follows rfc3986 not form encoding`() {
        // Space must be %20 (never '+'), and '*', '~' handling differs from
        // java.net.URLEncoder. OAuth1 signatures break otherwise.
        assertEquals("a%20b", Hashing.percentEncode("a b"))
        assertEquals("a%2Bb", Hashing.percentEncode("a+b"))
        assertEquals("~-_.", Hashing.percentEncode("~-_."))
        assertEquals(
            "https%3A%2F%2Fsso.garmin.com%2Fsso%2Fembed",
            Hashing.percentEncode("https://sso.garmin.com/sso/embed"),
        )
        assertEquals("%2A", Hashing.percentEncode("*"))
    }

    @Test
    fun `hmacSha1 matches the reference vector`() {
        val mac = Hashing.hmacSha1("key", "The quick brown fox jumps over the lazy dog")
        val hex = mac.joinToString("") { "%02x".format(it) }
        assertEquals("de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9", hex)
    }

    @Test
    fun `base64 round trip`() {
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -1, -128)
        val encoded = Hashing.base64(bytes)
        assertEquals("AAECA3//gA==", encoded)
        assertEquals(bytes.toList(), Hashing.base64Decode(encoded).toList())
    }

    @Test
    fun `decodes coros sts credential obfuscation`() {
        // Coros strips a fixed salt, then base64-decodes the remainder.
        val json = """{"AccessKeyId":"AK","AccessKeySecret":"SK"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val salted = encoded.substring(0, 3) + Hashing.COROS_STS_SALT + encoded.substring(3)

        assertEquals(json, Hashing.decodeCorosStsCredentials(salted))
    }
}
