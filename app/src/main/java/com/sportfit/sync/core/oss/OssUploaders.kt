package com.sportfit.sync.core.oss

import com.sportfit.sync.core.net.Hashing
import com.sportfit.sync.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Uploads one object into the bucket a set of credentials grants access to.
 *
 * Both stores are written with a hand-rolled signature rather than a vendor
 * SDK: the payload is a single small ZIP and the request is a plain `PUT`, so
 * pulling in the Aliyun OSS or AWS SDK would cost megabytes of APK for one
 * code path.
 *
 * The Aliyun bucket **and endpoint are platform supplied**: Coros always
 * points at `oss-cn-beijing.aliyuncs.com`, while iGPSPORT hands out the
 * endpoint and bucket its own STS service returned. Both travel through the
 * same signer, so the only difference is the host the `PUT` is sent to.
 */
interface OssUploader {
    suspend fun upload(objectKey: String, bytes: ByteArray, contentType: String = "application/zip")
}

/**
 * Aliyun OSS credentials whose endpoint and bucket come from the platform.
 */
data class AliyunOssCredentials(
    val bucket: String,
    /** Endpoint as the platform handed it out, e.g. "oss-cn-beijing.aliyuncs.com". */
    val endpoint: String,
    val accessKeyId: String,
    val accessKeySecret: String,
    /** Aliyun `SecurityToken` for STS credentials; empty for long-lived keys. */
    val securityToken: String,
)

object OssUploaders {
    fun forCredentials(credentials: CorosStsCredentials): OssUploader =
        if (credentials.isAliyun) AliyunOssUploader(credentials) else AwsS3Uploader(credentials)

    /**
     * Builds an uploader for a platform supplied Aliyun bucket/endpoint pair.
     *
     * The endpoint is normalised to a bare host (see [normalizeEndpointHost]),
     * so the caller may pass it in whatever shape the platform handed it out.
     */
    fun forAliyun(credentials: AliyunOssCredentials): OssUploader =
        AliyunOssUploader(
            bucket = credentials.bucket,
            endpointHost = credentials.endpoint,
            accessKeyId = credentials.accessKeyId,
            accessKeySecret = credentials.accessKeySecret,
            securityToken = credentials.securityToken,
        )
}

// ---------------------------------------------------------------------------
// Endpoint normalisation.
//
// A platform hands its endpoint out in whatever shape its STS service uses:
// a bare host, a full URL, or a URL with a trailing path. The request only
// ever needs the bare host, so the scheme, the trailing slash and the path
// are stripped; the port is kept because it addresses the service.
// ---------------------------------------------------------------------------

/** The endpoint Coros' China bucket lives on; it never changes. */
private const val COROS_ALIYUN_ENDPOINT = "oss-cn-beijing.aliyuncs.com"

private val ENDPOINT_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/**
 * Reduces an endpoint to the bare host the request is sent to.
 *
 * `oss-cn-beijing.aliyuncs.com`, `https://oss-cn-beijing.aliyuncs.com`,
 * `http://oss-cn-beijing.aliyuncs.com/` and
 * `https://oss-cn-beijing.aliyuncs.com/some/path` all normalise to
 * `oss-cn-beijing.aliyuncs.com`; a port is preserved.
 *
 * @throws IllegalArgumentException when the endpoint is blank.
 */
internal fun normalizeEndpointHost(endpoint: String): String {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) {
        throw IllegalArgumentException("阿里云 OSS endpoint 为空")
    }
    val withoutScheme = ENDPOINT_SCHEME.replace(trimmed, "")
    val host = withoutScheme.trimStart('/')
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .trim()
    if (host.isEmpty()) {
        throw IllegalArgumentException("阿里云 OSS endpoint 为空")
    }
    return host
}

// ---------------------------------------------------------------------------
// Aliyun OSS — signature V1 (HMAC-SHA1).
//
// Aliyun accepts two address styles: virtual-hosted
// (`https://{bucket}.{host}/{key}`) and path style
// (`https://{host}/{bucket}/{key}`). Which one a bucket expects is not
// documented, so for a *platform supplied* endpoint the virtual-hosted style
// is tried first and the path style is used as a single retry.
//
// The V1 canonical resource is `/bucket/key` in *both* styles, so the very
// same signature serves the first attempt and the retry.
//
// Coros' bucket is the exception: its virtual-hosted address is verified
// against the live bucket, so that path keeps its single attempt and its
// original error message (see `fallbackToPathStyle`).
// ---------------------------------------------------------------------------
internal class AliyunOssUploader(
    private val bucket: String,
    endpointHost: String,
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val securityToken: String,
    /**
     * Whether a failed upload is retried once in the other address style.
     *
     * Only platform-supplied endpoints need it: their bucket may or may not
     * be reachable in the virtual-hosted style and we cannot know which.
     * Coros' bucket is known to answer the virtual-hosted style, so retrying
     * there would only add a second doomed request and blur the message.
     */
    private val fallbackToPathStyle: Boolean = true,
) : OssUploader {

    /** The bare host every request is sent to, e.g. `oss-cn-beijing.aliyuncs.com`. */
    private val host: String = normalizeEndpointHost(endpointHost)

    /** Coros' China bucket always lives on [COROS_ALIYUN_ENDPOINT]. */
    constructor(credentials: CorosStsCredentials) : this(
        bucket = credentials.bucket,
        endpointHost = COROS_ALIYUN_ENDPOINT,
        accessKeyId = credentials.accessKeyId,
        accessKeySecret = credentials.accessKeySecret,
        securityToken = credentials.securityToken,
        fallbackToPathStyle = false,
    )

    private val rfc1123: SimpleDateFormat
        get() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

    override suspend fun upload(objectKey: String, bytes: ByteArray, contentType: String) {
        withContext(Dispatchers.IO) {
            val date = rfc1123.format(Date())
            val contentMd5 = Hashing.base64(
                java.security.MessageDigest.getInstance("MD5").digest(bytes),
            )

            // Canonicalised x-oss-* headers, lower-cased and sorted by name.
            val ossHeaders = if (securityToken.isNotEmpty()) {
                "x-oss-security-token:$securityToken\n"
            } else {
                ""
            }
            val canonicalResource = "/$bucket/$objectKey"

            val stringToSign = buildString {
                append("PUT\n")
                append(contentMd5).append('\n')
                append(contentType).append('\n')
                append(date).append('\n')
                append(ossHeaders)
                append(canonicalResource)
            }

            val signature = Hashing.base64(
                Hashing.hmacSha1(accessKeySecret, stringToSign),
            )

            // The canonical resource carries the bucket in both address
            // styles, so one signature serves both attempts.
            val authorization = "OSS $accessKeyId:$signature"

            val virtualHosted = put(
                url = "https://$bucket.$host/$objectKey",
                bytes = bytes,
                contentType = contentType,
                date = date,
                contentMd5 = contentMd5,
                authorization = authorization,
            )
            if (virtualHosted.succeeded) return@withContext

            if (!fallbackToPathStyle) {
                // Coros' bucket is known to answer the virtual-hosted style, so
                // a second attempt in the other style could only fail again
                // and bury the real reason under a confusing second status.
                throw IOException(
                    "上传到阿里云 OSS 失败：${virtualHosted.status()} ${virtualHosted.detail}",
                )
            }

            val pathStyle = put(
                url = "https://$host/$bucket/$objectKey",
                bytes = bytes,
                contentType = contentType,
                date = date,
                contentMd5 = contentMd5,
                authorization = authorization,
            )
            if (pathStyle.succeeded) return@withContext

            val failure = StringBuilder(
                "上传到阿里云 OSS 失败：虚拟主机风格 ${virtualHosted.status()}，路径风格 ${pathStyle.status()}",
            )
            listOf(
                "虚拟主机风格" to virtualHosted,
                "路径风格" to pathStyle,
            ).filter { it.second.detail.isNotEmpty() }.forEach { (style, attempt) ->
                failure.append("；").append(style).append("：").append(attempt.detail)
            }
            throw IOException(failure.toString())
        }
    }

    /**
     * Performs one upload attempt and reports the outcome instead of
     * throwing, so the caller can decide whether to retry in the other
     * address style.
     */
    private fun put(
        url: String,
        bytes: ByteArray,
        contentType: String,
        date: String,
        contentMd5: String,
        authorization: String,
    ): Attempt {
        val builder = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .header("Date", date)
            .header("Content-MD5", contentMd5)
            .header("Authorization", authorization)
        if (securityToken.isNotEmpty()) {
            builder.header("x-oss-security-token", securityToken)
        }

        return try {
            HttpClients.transfer.newCall(builder.build()).execute().use { response ->
                Attempt(
                    code = response.code,
                    detail = runCatching { response.body?.string().orEmpty().take(300) }
                        .getOrDefault(""),
                )
            }
        } catch (io: IOException) {
            // A transport error: there is no HTTP status code to report.
            Attempt(code = null, detail = io.message.orEmpty())
        }
    }

    /** The outcome of a single address-style attempt. */
    private class Attempt(
        /** The HTTP status code, or null when the request never got a response. */
        val code: Int?,
        /** The (truncated) response body, or the transport error message. */
        val detail: String,
    ) {
        val succeeded: Boolean get() = code != null && code in 200..299

        /** `HTTP 404`, or `网络错误` when the request never reached the server. */
        fun status(): String = code?.let { "HTTP $it" } ?: "网络错误"
    }
}

// ---------------------------------------------------------------------------
// AWS S3 — signature V4 (`AWS4-HMAC-SHA256`), region eu-central-1.
// ---------------------------------------------------------------------------
internal class AwsS3Uploader(
    private val credentials: CorosStsCredentials,
    private val region: String = "eu-central-1",
) : OssUploader {

    override suspend fun upload(objectKey: String, bytes: ByteArray, contentType: String) {
        withContext(Dispatchers.IO) {
            val host = "${credentials.bucket}.s3.$region.amazonaws.com"
            val url = "https://$host/$objectKey"

            val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.format(Date())
            val dateStamp = amzDate.substring(0, 8)
            val payloadHash = Hashing.sha256Hex(bytes)

            val canonicalHeaders = buildString {
                append("content-type:$contentType\n")
                append("host:$host\n")
                append("x-amz-content-sha256:$payloadHash\n")
                append("x-amz-date:$amzDate\n")
                append("x-amz-security-token:${credentials.securityToken}\n")
            }
            val signedHeaders =
                "content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token"

            val canonicalRequest = buildString {
                append("PUT\n")
                append("/$objectKey\n")
                append("\n") // no query string
                append(canonicalHeaders).append('\n')
                append(signedHeaders).append('\n')
                append(payloadHash)
            }

            val scope = "$dateStamp/$region/s3/aws4_request"
            val stringToSign = buildString {
                append("AWS4-HMAC-SHA256\n")
                append(amzDate).append('\n')
                append(scope).append('\n')
                append(Hashing.sha256Hex(canonicalRequest))
            }

            val signingKey = hmacChain(credentials.accessKeySecret, dateStamp, region)
            val signature = Hashing.hmacSha256(signingKey, stringToSign)
                .joinToString("") { "%02x".format(it) }

            val authorization = "AWS4-HMAC-SHA256 " +
                "Credential=${credentials.accessKeyId}/$scope, " +
                "SignedHeaders=$signedHeaders, " +
                "Signature=$signature"

            val request = Request.Builder()
                .url(url)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .header("Content-Type", contentType)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("x-amz-security-token", credentials.securityToken)
                .header("Authorization", authorization)
                .build()

            HttpClients.transfer.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { response.body?.string().orEmpty().take(300) }
                        .getOrDefault("")
                    throw IOException("上传到 AWS S3 失败：HTTP ${response.code} $detail")
                }
            }
        }
    }

    private fun hmacChain(secret: String, dateStamp: String, region: String): ByteArray {
        val kDate = Hashing.hmacSha256(("AWS4$secret").toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = Hashing.hmacSha256(kDate, region)
        val kService = Hashing.hmacSha256(kRegion, "s3")
        return Hashing.hmacSha256(kService, "aws4_request")
    }
}
