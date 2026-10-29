package com.sportfit.sync.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityExporterNamingTest {

    @Test
    fun `builds date_name_id file names`() {
        val name = ActivityExporter.buildFileName("2024-05-01", "晨跑", "12345")
        assertEquals("2024-05-01_晨跑_12345.fit", name)
    }

    @Test
    fun `strips characters that are illegal on any file system`() {
        val name = ActivityExporter.buildFileName(null, "a/b:c*d?e\"f<g>h|i", "9")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertFalse(name.contains('"'))
        assertFalse(name.contains('<'))
        assertFalse(name.contains('>'))
        assertFalse(name.contains('|'))
        assertTrue(name.endsWith("_9.fit"))
    }

    @Test
    fun `collapses whitespace and newlines`() {
        val name = ActivityExporter.buildFileName(null, "  morning\n\trun  ", "7")
        assertEquals("morning run_7.fit", name)
    }

    @Test
    fun `falls back to the id when the name is unusable`() {
        assertEquals("42.fit", ActivityExporter.buildFileName(null, null, "42"))
        assertEquals("42.fit", ActivityExporter.buildFileName("", "   ", "42"))
    }

    @Test
    fun `caps the descriptive part so the path stays writable`() {
        val name = ActivityExporter.buildFileName(null, "x".repeat(200), "5")
        // 40 characters of name, plus separator and id, plus extension.
        assertEquals(40 + 1 + 1 + 4, name.length)
    }
}
