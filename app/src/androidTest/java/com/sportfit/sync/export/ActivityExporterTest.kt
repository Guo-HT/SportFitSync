package com.sportfit.sync.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real export path on a device: on API 29+ this goes through
 * MediaStore into the public Downloads collection, which is the part a JVM
 * test cannot cover.
 */
@RunWith(AndroidJUnit4::class)
class ActivityExporterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun writes_a_file_that_can_be_read_back_verbatim() = runTest {
        val exporter = ActivityExporter(context)
        val bytes = ByteArray(512) { (it % 251).toByte() }
        val fileName = "test-${System.currentTimeMillis()}.fit"

        val written = exporter.write(fileName, bytes)

        assertEquals(fileName, written.fileName)
        assertEquals(bytes.size, written.bytes)
        assertTrue("path should mention Downloads", written.displayPath.contains("Download"))

        val readBack = written.uri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: java.io.File(written.displayPath).readBytes()

        assertEquals(bytes.toList(), readBack.toList())
    }

    @Test
    fun writing_a_file_with_a_slash_free_name_does_not_throw() = runTest {
        val exporter = ActivityExporter(context)
        val name = ActivityExporter.buildFileName("2024-05-01", "晨跑/测试", "demo-1")
        val written = exporter.write(name, ByteArray(64))
        assertTrue(written.fileName.endsWith(".fit"))
    }
}
