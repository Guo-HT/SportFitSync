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
 * Uploads one object into the bucket that a set of Coros STS credentials
 * grants access to.
 *
 * Both stores are written with a hand-rolled signature rather than a vendor
 * SDK: the payload is a single small ZIP and the request is a plain `PUT`, so
 * pulling in the Aliyun OSS or AWS SDK would cost megabytes of APK for one
 * code path.
 */
interface OssUploader {
    suspend fun upload(objectKey: String, bytes: ByteArray, contentType: String = "application/zip")
}

object OssUploaders {
    fun forCredentials(credentials: CorosStsCredentials): OssUploader =
        if (credentials.isAliyun) AliyunOssUploader(credentials) else AwsS3Uploader(credentials)
}

// ---------------------------------------------------------------------------
// Aliyun OSS — signature V1 (HMAC-SHA1), path style `bucket.endpoint/key`.
// ---------------------------------------------------------------------------
internal class AliyunOssUploader(
    private val credentials: CorosStsCredentials,
    private val endpointHost: String = "oss-cn-beijing.aliyuncs.com",
) : OssUploader {

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
            val ossHeaders = if (credentials.securityToken.isNotEmpty()) {
                "x-oss-security-token:${credentials.securityToken}\n"
            } else {
                ""
            }
            val canonicalResource = "/${credentials.bucket}/$objectKey"

            val stringToSign = buildString {
                append("PUT\n")
                append(contentMd5).append('\n')
                append(contentType).append('\n')
                append(date).append('\n')
                append(ossHeaders)
                append(canonicalResource)
            }

            val signature = Hashing.base64(
                Hashing.hmacSha1(credentials.accessKeySecret, stringToSign),
            )

            val url = "https://${credentials.bucket}.$endpointHost/$objectKey"
            val builder = Request.Builder()
                .url(url)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .header("Date", date)
                .header("Content-MD5", contentMd5)
                .header("Authorization", "OSS ${credentials.accessKeyId}:$signature")
            if (credentials.securityToken.isNotEmpty()) {
                builder.header("x-oss-security-token", credentials.securityToken)
            }

            HttpClients.transfer.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { response.body?.string().orEmpty().take(300) }
                        .getOrDefault("")
                    throw IOException("上传到阿里云 OSS 失败：HTTP ${response.code} $detail")
                }
            }
        }
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
