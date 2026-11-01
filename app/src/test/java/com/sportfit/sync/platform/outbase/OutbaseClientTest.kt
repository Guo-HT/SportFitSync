package com.sportfit.sync.platform.outbase

import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for the Outbase upload protocol.
 *
 * The protocol has no official documentation, so the parts that are easy to
 * get subtly wrong — the object URL, the register body, and the meaning of
 * each business code — are pinned down here.
 */
class OutbaseClientTest {

    // ---- CDN URL -----------------------------------------------------------

    @Test
    fun `the cdn url carries the object uri the storage layer shards on`() {
        val guid = "1f2e3d4c-5b6a-7988-9a0b-1c2d3e4f5061"
        val url = OutbaseClient.buildCdnUrl(guid, "20261031")

        // The first four hex digits of the guid become two directory levels.
        assertTrue(url.startsWith(OutbaseClient.CDN_UPLOAD))
        assertTrue(url.contains("id=$guid" + "20261031"))
        assertTrue(
            url.contains("uri=/resource/1f/2e/$guid" + "20261031.fit"),
        )
        assertTrue(url.contains("source=zeusfit"))
        assertTrue(url.contains("momoid=0"))
    }

    @Test
    fun `the cdn url is stable for the same guid and day`() {
        val guid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertEquals(
            OutbaseClient.buildCdnUrl(guid, "20260101"),
            OutbaseClient.buildCdnUrl(guid, "20260101"),
        )
    }

    // ---- Register body -----------------------------------------------------

    @Test
    fun `the register body signs the file with its md5`() {
        val bytes = "hello outbase".toByteArray()
        val body = OutbaseClient.buildRegisterBody(
            fitGuid = "guid-value",
            dateTag = "20261031",
            sign = OutbaseClient.md5Hex(bytes),
            fileName = "ride.fit",
            fileSize = bytes.size,
        )

        assertTrue(body.contains("\"fitGuid\":\"guid-value20261031\""))
        assertTrue(body.contains("\"sign\":\"${OutbaseClient.md5Hex(bytes)}\""))
        assertTrue(body.contains("\"fileName\":\"ride.fit\""))
        assertTrue(body.contains("\"fileSize\":${bytes.size}"))
    }

    @Test
    fun `md5 matches the known digest of an empty payload`() {
        // d41d8cd98f00b204e9800998ecf8427e
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", OutbaseClient.md5Hex(ByteArray(0)))
    }

    @Test
    fun `md5 matches the known digest of abc`() {
        // 900150983cd24fb0d6963f7d28e17f72
        assertEquals("900150983cd24fb0d6963f7d28e17f72", OutbaseClient.md5Hex("abc".toByteArray()))
    }

    // ---- Date tag ----------------------------------------------------------

    @Test
    fun `the date tag is the local calendar day`() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("20261031", OutbaseClient.dateTag(calendar))
    }

    // ---- Register response classification ----------------------------------

    @Test
    fun `ec zero means the activity was accepted`() {
        assertEquals(UploadOutcome.Success, OutbaseClient.classifyRegister(0, ""))
    }

    @Test
    fun `ec 503 means the activity is already there`() {
        assertEquals(UploadOutcome.Duplicate, OutbaseClient.classifyRegister(503, ""))
    }

    @Test
    fun `a duplicate reported in the message is also a duplicate`() {
        assertEquals(
            UploadOutcome.Duplicate,
            OutbaseClient.classifyRegister(-1, "该活动已存在"),
        )
        assertEquals(
            UploadOutcome.Duplicate,
            OutbaseClient.classifyRegister(-1, "相同时间的活动重复"),
        )
    }

    @Test
    fun `ec 410001 means the session died`() {
        val failure = runCatching {
            OutbaseClient.classifyRegister(410001, "Please log in")
        }.exceptionOrNull()

        assertNotNull("a dead session must not be reported as a plain failure", failure)
        assertTrue(failure is PlatformException.Auth)
    }

    @Test
    fun `a message about logging in means the session died too`() {
        val failure = runCatching {
            OutbaseClient.classifyRegister(-1, "请先登录")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is PlatformException.Auth)
    }

    @Test
    fun `a pending parse is a failure, not a success`() {
        // Outbase parses the FIT asynchronously. Reporting "success" here
        // would write the activity to the ledger even though it may never
        // reach the library, so it has to stay retryable.
        val outcome = OutbaseClient.classifyRegister(0, "文件待处理")

        assertTrue(outcome is UploadOutcome.Failed)
        assertTrue((outcome as UploadOutcome.Failed).reason.contains("重试"))
    }

    @Test
    fun `an unknown error is a plain failure`() {
        val outcome = OutbaseClient.classifyRegister(500, "internal error")
        assertTrue(outcome is UploadOutcome.Failed)
    }

    // ---- CDN response ------------------------------------------------------

    @Test
    fun `the cdn answers with the success marker`() {
        assertTrue(OutbaseClient.isCdnSuccess("""{"message":"SUCCESS"}"""))
        assertFalse(OutbaseClient.isCdnSuccess("""{"message":"FAILED"}"""))
        assertFalse(OutbaseClient.isCdnSuccess("not json at all"))
        assertFalse(OutbaseClient.isCdnSuccess(""))
    }

    // ---- Session cookie ----------------------------------------------------

    @Test
    fun `the session id is pulled out of the cookie header`() {
        val header = "a=1; sessionId=abc123; b=2"
        assertEquals("abc123", OutbaseAuth.extractSessionId(header))
    }

    @Test
    fun `the session id is found even when it is the only cookie`() {
        assertEquals("abc123", OutbaseAuth.extractSessionId("sessionId=abc123"))
    }

    @Test
    fun `a quoted cookie value is unquoted`() {
        assertEquals("abc123", OutbaseAuth.extractSessionId("sessionId=\"abc123\""))
    }

    @Test
    fun `a cookie header without the session yields null`() {
        assertNull(OutbaseAuth.extractSessionId("a=1; b=2"))
        assertNull(OutbaseAuth.extractSessionId(""))
        assertNull(OutbaseAuth.extractSessionId(null))
    }

    @Test
    fun `an empty session value is treated as missing`() {
        assertNull(OutbaseAuth.extractSessionId("sessionId="))
    }

    @Test
    fun `a cookie whose name merely starts with the session name is ignored`() {
        assertNull(OutbaseAuth.extractSessionId("sessionIdExtra=nope"))
    }
}
