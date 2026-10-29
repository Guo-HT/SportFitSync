package com.sportfit.sync.core.net

import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Small crypto/encoding helpers shared by the platform clients.
 *
 * Kept dependency-free (JCA only) so it also runs in plain JVM unit tests.
 */
object Hashing {

    fun md5Hex(input: String): String = md5Hex(input.toByteArray(Charsets.UTF_8))

    fun md5Hex(input: ByteArray): String = MessageDigest.getInstance("MD5")
        .digest(input)
        .joinToString("") { "%02x".format(it) }

    fun sha256Hex(input: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(input)
        .joinToString("") { "%02x".format(it) }

    fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

    fun hmacSha256(key: ByteArray, data: String): ByteArray = hmac("HmacSHA256", key, data)

    fun hmacSha1(key: String, data: String): ByteArray =
        hmac("HmacSHA1", key.toByteArray(Charsets.UTF_8), data)

    private fun hmac(algorithm: String, key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    // java.util.Base64 (not android.util.Base64) so this object stays pure-JVM
    // and can be covered by fast unit tests. minSdk 26 guarantees availability.
    fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    fun base64Decode(value: String): ByteArray =
        java.util.Base64.getMimeDecoder().decode(value)

    /**
     * RFC 3986 percent-encoding, as required by OAuth1 signature base strings
     * and AWS SigV4 canonical requests.
     *
     * `URLEncoder` is *not* usable here: it encodes spaces as `+` and leaves
     * `*` alone, both of which break the signature.
     */
    fun percentEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    /** Used only for optimistic display of URLs in logs; never for signing. */
    fun urlEncodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Constant salt Coros mixes into its base64 STS credential payload. */
    const val COROS_STS_SALT = "9y78gpoERW4lBNYL"

    /**
     * Decodes Coros' obfuscated STS credential blob:
     * strip the fixed salt, base64-decode, parse JSON.
     */
    fun decodeCorosStsCredentials(encoded: String): String =
        String(base64Decode(encoded.replace(COROS_STS_SALT, "")), Charsets.UTF_8)
}
