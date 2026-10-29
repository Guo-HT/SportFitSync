package com.sportfit.sync.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where an exported activity file ended up. */
data class ExportedFile(
    val fileName: String,
    val displayPath: String,
    val uri: Uri?,
    val bytes: Int,
)

/**
 * Writes activity files somewhere the user can actually reach them.
 *
 * On API 29+ this goes through MediaStore, so the file lands in the real
 * `Downloads/SportFitSync` folder with no storage permission at all. On older
 * releases MediaStore.Downloads does not exist, so it falls back to the app's
 * external files directory — still user-visible over USB/MTP, still
 * permission-free.
 *
 * This is the escape hatch for the whole app: if a target platform's API
 * changes or its import path breaks, the user can still get their data out as
 * ordinary `.fit` files.
 */
class ActivityExporter(private val context: Context) {

    suspend fun write(
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
    ): ExportedFile = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(fileName, bytes, mimeType)
        } else {
            writeToAppExternalDir(fileName, bytes)
        }
    }

    private fun writeViaMediaStore(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): ExportedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("无法在下载目录创建文件：$fileName")

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("无法写入文件：$fileName")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }

        // Clearing IS_PENDING publishes the file to other apps.
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )

        return ExportedFile(
            fileName = fileName,
            displayPath = "$relativePath/$fileName",
            uri = uri,
            bytes = bytes.size,
        )
    }

    private fun writeToAppExternalDir(fileName: String, bytes: ByteArray): ExportedFile {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "exports")
        val dir = File(root, SUBFOLDER).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return ExportedFile(
            fileName = fileName,
            displayPath = file.absolutePath,
            uri = null,
            bytes = bytes.size,
        )
    }

    companion object {
        const val SUBFOLDER = "SportFitSync"

        /**
         * Builds a filename that survives every platform: activity names
         * routinely contain `/`, `:` and emoji.
         */
        fun buildFileName(datePrefix: String?, name: String?, activityId: String): String {
            // Collapse whitespace *before* scrubbing illegal characters,
            // otherwise a newline would become an underscore instead of a
            // readable space.
            val safeName = (name ?: "")
                .replace(Regex("\\s+"), " ")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .take(40)
            val parts = listOfNotNull(
                datePrefix?.takeIf { it.isNotBlank() },
                safeName.takeIf { it.isNotBlank() },
                activityId,
            )
            return parts.joinToString("_") + ".fit"
        }
    }
}
