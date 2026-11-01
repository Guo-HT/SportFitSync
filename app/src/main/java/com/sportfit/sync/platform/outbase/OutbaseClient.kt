package com.sportfit.sync.platform.outbase

import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.int
import com.sportfit.sync.core.net.str
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar
import java.util.UUID

/**
 * Client for Outbase's FIT ingestion pipeline.
 *
 * Outbase has no public upload API. The scheme below was reverse-engineered
 * from the traffic the outbase.cn web app produces, and it is a two-step
 * dance:
 *
 * 1. **Store the bytes** — the FIT is PUT into Outbase's CDN with a
 *    browser-shaped multipart request. The `h5` endpoint accepts anonymous
 *    requests: the only thing that identifies the upload is the `uri` the
 *    client invents, so nothing has to be signed.
 * 2. **Register the object** — a JSON call tells the backend that an object
 *    now exists, so it can be parsed into an activity. This call *is*
 *    authenticated, with the `sessionId` cookie the login left behind.
 *
 * ```
 * POST {CDN}/zeusfit/resource/h5/upload?source=zeusfit&id={guid}{yyyymmdd}
 *      &uri=/resource/{guid[0:2]}/{guid[2:4]}/{guid}{yyyymmdd}.fit&momoid=0&
 * Content-Type: multipart/form-data   (field "file")
 * → {"message":"SUCCESS"}
 *
 * POST {CDN}/zeusfit/api/h5/sport/upload/fit
 * Sessionid: {sessionId}
 * {"fitGuid":"{guid}{yyyymmdd}","sign":"{md5}","fileName":"…","fileSize":N}
 * → {"ec":0}
 * ```
 *
 * Two details from the reference implementation are worth keeping:
 *
 * - The `ec` code decides everything. `0` is success, `503` (or an `em` that
 *   mentions a duplicate) means the activity is already there, and `410001`
 *   means the session died and the user has to log in again.
 * - Outbase parses the FIT **asynchronously**. A response that says the file
 *   is still being processed is *not* a success — the activity may never make
 *   it into the library. Treating it as success would silently drop the
 *   activity, so it is reported as a failure and retried on the next run.
 */
internal class OutbaseClient(
    private val sessionId: String,
) {

    companion object {
        /** The browser-channel CDN endpoint that accepts anonymous uploads. */
        const val CDN_UPLOAD = "https://melon-gateway.immomo.com/zeusfit/resource/h5/upload"

        /** Endpoint that registers a stored object as an activity. */
        const val REGISTER = "https://melon-gateway.immomo.com/zeusfit/api/h5/sport/upload/fit"

        /** Endpoint that lists the caller's uploads; doubles as a session probe. */
        const val UPLOAD_LIST = "https://melon-gateway.immomo.com/zeusfit/api/h5/sport/upload/list"

        /**
         * Outbase rejects anything that does not look like the web app, so
         * the requests carry a desktop Chrome UA plus the `Origin`/`Referer`
         * pair the real page sends.
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** The `Uagent` header the gateway checks; the web app appends a suffix. */
        const val UAGENT = "$BROWSER_UA PCAgent/1.0.0"

        /** Business code for "not logged in". */
        const val EC_SESSION_EXPIRED = 410001

        /** Business code for "already uploaded". */
        const val EC_DUPLICATE = 503

        /**
         * Builds the CDN URL for one object.
         *
         * The `uri` is the object's path inside Outbase's bucket: the first
         * four hex digits of the guid become two directory levels, which is
         * how the storage layer shards its files.
         */
        internal fun buildCdnUrl(fitGuid: String, dateTag: String): String {
            val guidHex = fitGuid.replace("-", "")
            val prefix = "${guidHex.substring(0, 2)}/${guidHex.substring(2, 4)}"
            val uriPath = "/resource/$prefix/$fitGuid$dateTag.fit"
            val idParam = "$fitGuid$dateTag"
            return "$CDN_UPLOAD?source=zeusfit&id=$idParam&uri=$uriPath&momoid=0&"
        }

        /** The JSON body the register endpoint expects. */
        internal fun buildRegisterBody(
            fitGuid: String,
            dateTag: String,
            sign: String,
            fileName: String,
            fileSize: Int,
        ): String = AppJson.encodeToString(
            OutbaseRegisterBody.serializer(),
            OutbaseRegisterBody(
                fitGuid = "$fitGuid$dateTag",
                sign = sign,
                fileName = fileName,
                fileSize = fileSize,
            ),
        )

        /** MD5 of the uploaded bytes; the register call signs the file with it. */
        internal fun md5Hex(data: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

        /** The `yyyymmdd` tag the storage layer partitions objects by. */
        internal fun dateTag(calendar: Calendar = Calendar.getInstance()): String =
            "%04d%02d%02d".format(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )

        /**
         * Decides what a register response means.
         *
         * @param ec the business code (`0` is success).
         * @param em the human-readable message; some failures are only
         *   distinguishable by their text.
         */
        internal fun classifyRegister(ec: Int, em: String): UploadOutcome = when {
            // "待处理" means Outbase accepted the file but has not finished
            // parsing it. The activity is not in the library yet, so this must
            // not be reported as a success — the next run retries it. The
            // check comes first because a pending answer also carries ec=0.
            em.contains("待处理") || em.contains("处理中") ->
                UploadOutcome.Failed("Outbase 仍在处理中，下次同步会重试")
            ec == 0 -> UploadOutcome.Success
            ec == EC_DUPLICATE || em.contains("已存在") || em.contains("相同时间") ||
                em.contains("重复") -> UploadOutcome.Duplicate
            ec == EC_SESSION_EXPIRED || em.contains("log in", ignoreCase = true) ||
                em.contains("登录") -> throw PlatformException.Auth("Outbase 登录态已失效，请重新登录")
            else -> UploadOutcome.Failed("Outbase 注册失败：ec=$ec ${em.take(80)}")
        }

        /** Whether the CDN answered with the success marker. */
        internal fun isCdnSuccess(body: String): Boolean {
            val root = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() ?: return false
            return root.str("message") == "SUCCESS"
        }
    }

    /**
     * Uploads one FIT and registers it as an activity.
     *
     * @param fitData the raw FIT bytes.
     * @param fileName the name to register the activity under.
     */
    suspend fun upload(fitData: ByteArray, fileName: String): UploadOutcome =
        withContext(Dispatchers.IO) {
            val fitGuid = UUID.randomUUID().toString()
            val dateTag = dateTag()

            store(fitData, fileName, fitGuid, dateTag)
            register(fitData, fileName, fitGuid, dateTag)
        }

    /**
     * Step 1 — put the bytes into Outbase's storage.
     *
     * The request is anonymous: the `uri` invented for the object is the only
     * thing that identifies it. A failure here means the bytes never landed,
     * so there is nothing to register.
     */
    private fun store(fitData: ByteArray, fileName: String, fitGuid: String, dateTag: String) {
        val url = buildCdnUrl(fitGuid, dateTag)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                fitData.toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
            .header("Origin", "https://outbase.cn")
            .header("Referer", "https://outbase.cn/")
            .post(multipart)
            .build()

        val body = HttpClients.transfer.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Outbase 文件上传失败：HTTP ${response.code}")
            }
            response.bodyTextOrThrow()
        }

        if (!isCdnSuccess(body)) {
            throw PlatformException.Protocol("Outbase 文件上传失败：${body.take(80)}")
        }
    }

    /**
     * Step 2 — tell the backend the object exists so it can parse it.
     *
     * This is the authenticated half: without a live `sessionId` the gateway
     * answers `ec=410001`.
     */
    private fun register(
        fitData: ByteArray,
        fileName: String,
        fitGuid: String,
        dateTag: String,
    ): UploadOutcome {
        val body = buildRegisterBody(
            fitGuid = fitGuid,
            dateTag = dateTag,
            sign = md5Hex(fitData),
            fileName = fileName,
            fileSize = fitData.size,
        )

        val request = Request.Builder()
            .url(REGISTER)
            .header("Sessionid", sessionId)
            .header("Uagent", UAGENT)
            .header("User-Agent", BROWSER_UA)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://outbase.cn")
            .header("Referer", "https://outbase.cn/")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val text = HttpClients.api.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Outbase 注册失败：HTTP ${response.code}")
            }
            response.bodyTextOrThrow()
        }

        val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
            ?: throw PlatformException.Protocol("Outbase 注册响应不是 JSON：${text.take(60)}")

        val ec = root.int("ec") ?: -1
        val em = root.str("em").orEmpty()
        return classifyRegister(ec, em)
    }

    /**
     * Checks that the stored session still works.
     *
     * The list route answers `ec=410001` once the session has died, which is
     * the signal to send the user back to the login screen.
     */
    suspend fun probeSession(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UPLOAD_LIST)
            .header("Sessionid", sessionId)
            .header("Uagent", UAGENT)
            .header("User-Agent", BROWSER_UA)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://outbase.cn")
            .header("Referer", "https://outbase.cn/")
            .post("""{"index":0,"count":20}""".toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            val text = HttpClients.api.newCall(request).execute().use { response ->
                response.bodyTextOrThrow()
            }
            val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
            root?.int("ec") == 0
        }.getOrDefault(false)
    }
}

/**
 * The register request body.
 *
 * Outbase wants exactly these four fields, with the object's name in
 * `fitGuid` and the file's MD5 in `sign`.
 */
@kotlinx.serialization.Serializable
internal data class OutbaseRegisterBody(
    val fitGuid: String,
    val sign: String,
    val fileName: String,
    val fileSize: Int,
)
