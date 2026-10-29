package com.sportfit.sync.core.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPackagingTest {

    private val rawFit = ByteArray(64) { it.toByte() }

    @Test
    fun `detects zip by magic bytes`() {
        val zip = FitPackaging.zip(rawFit, "1_ACTIVITY.fit")
        assertTrue(FitPackaging.isZip(zip))
        assertFalse(FitPackaging.isZip(rawFit))
        assertFalse(FitPackaging.isZip(byteArrayOf()))
    }

    @Test
    fun `wrapping a raw fit produces a single fit entry`() {
        val zip = FitPackaging.zip(rawFit, "123_ACTIVITY.fit")
        assertEquals(listOf("123_ACTIVITY.fit"), FitPackaging.listEntries(zip))
    }

    @Test
    fun `ensureZip wraps a raw fit and names the outer file dot zip`() {
        val payload = FitPayload(rawFit, "23103943262.fit", zipped = false)
        val zipped = FitPackaging.ensureZip(payload)

        assertTrue(zipped.zipped)
        assertEquals("23103943262.zip", zipped.fileName)
        assertTrue(FitPackaging.isZip(zipped.bytes))
        // The inner entry keeps the .fit extension: Coros parses it.
        assertEquals(listOf("23103943262.fit"), FitPackaging.listEntries(zipped.bytes))
    }

    @Test
    fun `ensureZip is idempotent for an already zipped payload`() {
        val once = FitPackaging.ensureZip(FitPayload(rawFit, "42.fit", zipped = false))
        val twice = FitPackaging.ensureZip(once)
        assertArrayEquals(once.bytes, twice.bytes)
        assertEquals("42.zip", twice.fileName)
    }

    @Test
    fun `ensureRawFit unwraps a garmin style archive`() {
        // Mirrors a real Garmin download: one entry named `{id}_ACTIVITY.fit`.
        val zip = FitPackaging.zip(rawFit, "23103943262_ACTIVITY.fit")
        val payload = FitPayload(zip, "23103943262.zip", zipped = true)

        val raw = FitPackaging.ensureRawFit(payload)

        assertFalse(raw.zipped)
        assertEquals("23103943262.fit", raw.fileName)
        assertArrayEquals(rawFit, raw.bytes)
    }

    @Test
    fun `ensureRawFit passes through a payload that is already raw`() {
        val payload = FitPayload(rawFit, "42.fit", zipped = false)
        val result = FitPackaging.ensureRawFit(payload)
        assertArrayEquals(rawFit, result.bytes)
        assertFalse(result.zipped)
    }

    @Test
    fun `ensureRawFit keeps the original when the archive has no fit entry`() {
        val zip = FitPackaging.zip(rawFit, "readme.txt")
        val payload = FitPayload(zip, "broken.zip", zipped = true)

        val result = FitPackaging.ensureRawFit(payload)

        // Nothing to extract, so the caller is handed back the archive and the
        // target platform gets to produce the real error.
        assertTrue(result.zipped)
        assertArrayEquals(zip, result.bytes)
    }

    @Test
    fun `round trips through both normalisers`() {
        val original = FitPayload(rawFit, "7.fit", zipped = false)
        val zipped = FitPackaging.ensureZip(original)
        val back = FitPackaging.ensureRawFit(zipped)
        assertArrayEquals(rawFit, back.bytes)
        assertNotNull(back.fileName)
    }
}
