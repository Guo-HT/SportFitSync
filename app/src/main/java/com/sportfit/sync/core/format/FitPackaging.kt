package com.sportfit.sync.core.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A FIT payload travelling from a source platform to a target platform.
 *
 * The upstream Python project keeps this ambiguous: Garmin downloads are ZIP
 * archives, Coros downloads are raw FIT, and Coros imports *require* a ZIP.
 * On Android we always carry the bytes plus an explicit `zipped` flag and
 * normalise at the boundary, so the sync engine never has to guess.
 */
data class FitPayload(
    val bytes: ByteArray,
    val fileName: String,
    val zipped: Boolean,
) {
    val sizeBytes: Int get() = bytes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FitPayload) return false
        return fileName == other.fileName && zipped == other.zipped && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + fileName.hashCode()) * 31 + zipped.hashCode()
}

object FitPackaging {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun isZip(bytes: ByteArray): Boolean {
        if (bytes.size < ZIP_MAGIC.size) return false
        return ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }
    }

    /** Wraps raw FIT bytes in a ZIP whose single entry is named `entryName`. */
    fun zip(fitBytes: ByteArray, entryName: String): ByteArray {
        val out = ByteArrayOutputStream(fitBytes.size + 256)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(fitBytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Extracts the first `.fit` entry from a ZIP archive. Returns null when the
     * archive has no FIT entry (e.g. a Garmin export that only carries a TCX).
     */
    fun extractFit(zipBytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".fit")) {
                    return zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** Lists entry names; used for diagnostics and unit tests. */
    fun listEntries(zipBytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun baseName(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "activity" }

    /**
     * Guarantees a *raw FIT* payload (Garmin upload endpoint and Strava both
     * reject ZIPs). Falls back to the original payload when the archive cannot
     * be unpacked, letting the target platform produce the real error.
     */
    fun ensureRawFit(payload: FitPayload): FitPayload {
        if (!payload.zipped && !isZip(payload.bytes)) return payload
        val fit = extractFit(payload.bytes) ?: return payload
        return FitPayload(bytes = fit, fileName = "${baseName(payload.fileName)}.fit", zipped = false)
    }

    /**
     * Guarantees a *ZIP* payload (Coros `activity/fit/import` only accepts a
     * zip object in OSS). The inner entry keeps the `.fit` extension because
     * Coros parses it.
     */
    fun ensureZip(payload: FitPayload): FitPayload {
        if (payload.zipped || isZip(payload.bytes)) {
            return payload.copy(zipped = true, fileName = ensureExtension(payload.fileName, "zip"))
        }
        val entryName = "${baseName(payload.fileName)}.fit"
        return FitPayload(
            bytes = zip(payload.bytes, entryName),
            fileName = "${baseName(payload.fileName)}.zip",
            zipped = true,
        )
    }

    private fun ensureExtension(fileName: String, extension: String): String =
        if (fileName.lowercase().endsWith(".$extension")) fileName
        else fileName.substringBeforeLast('.', fileName) + ".$extension"

    fun describe(payload: FitPayload): String =
        "${payload.fileName} (${payload.sizeBytes} bytes, ${if (payload.zipped) "zip" else "fit"})"
}
