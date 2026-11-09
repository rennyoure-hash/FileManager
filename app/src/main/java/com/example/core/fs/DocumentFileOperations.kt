package com.example.core.fs

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.core.permission.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Helper utility that interacts with storage items (both local files and SAF URIs)
 * exclusively via the AndroidX [DocumentFile] API (`DocumentFile.fromFile` and `DocumentFile.fromTreeUri`).
 * Provides Copy, Move, Rename, and Delete operations for file context menu actions.
 */
object DocumentFileOperations {

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Resolves a [VPath] into a [DocumentFile], supporting both local file paths
     * (`DocumentFile.fromFile`) and Storage Access Framework paths (`DocumentFile.fromTreeUri`).
     */
    fun resolveDocumentFile(context: Context, path: VPath): DocumentFile? {
        val raw = path.normalized
        if (raw.startsWith("saf:/") || path.rawPath.startsWith("saf:")) {
            val clean = raw.removePrefix("saf:/").removePrefix("saf:").trimStart('/')
            if (clean.isEmpty()) return null
            val parts = clean.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null

            val treeUri: Uri = PermissionManager.decodeSafUri(parts[0])
            val rootDoc = try {
                DocumentFile.fromTreeUri(context, treeUri)
            } catch (_: Exception) {
                null
            } ?: return null

            var current: DocumentFile? = rootDoc
            for (seg in parts.drop(1)) {
                current = current?.findFile(seg) ?: return null
            }
            return current
        } else {
            val file = File(raw)
            return DocumentFile.fromFile(file)
        }
    }

    /**
     * Renames a file or directory using [DocumentFile.renameTo].
     */
    suspend fun rename(context: Context, entry: VPath, newName: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withContext false
        val doc = resolveDocumentFile(context, entry) ?: return@withContext false
        if (!doc.exists()) return@withContext false
        doc.renameTo(trimmed)
    }

    /**
     * Deletes a file or directory using [DocumentFile.delete].
     */
    suspend fun delete(context: Context, entry: VPath): Boolean = withContext(Dispatchers.IO) {
        val doc = resolveDocumentFile(context, entry) ?: return@withContext false
        if (!doc.exists()) return@withContext true
        doc.delete()
    }

    /**
     * Copies a single file or directory recursively to [targetDirPath] using [DocumentFile] APIs.
     */
    suspend fun copy(context: Context, sourcePath: VPath, targetDirPath: VPath): Boolean = withContext(Dispatchers.IO) {
        val sourceDoc = resolveDocumentFile(context, sourcePath) ?: return@withContext false
        val targetDirDoc = resolveDocumentFile(context, targetDirPath) ?: return@withContext false
        if (!sourceDoc.exists() || !targetDirDoc.exists() || !targetDirDoc.isDirectory) {
            return@withContext false
        }
        copyDocumentRecursive(context, sourceDoc, targetDirDoc)
    }

    /**
     * Moves a single file or directory to [targetDirPath] using [DocumentFile] APIs (copies then deletes source).
     */
    suspend fun move(context: Context, sourcePath: VPath, targetDirPath: VPath): Boolean = withContext(Dispatchers.IO) {
        if (sourcePath.parent == targetDirPath) return@withContext true
        val sourceDoc = resolveDocumentFile(context, sourcePath) ?: return@withContext false
        val targetDirDoc = resolveDocumentFile(context, targetDirPath) ?: return@withContext false
        if (!sourceDoc.exists() || !targetDirDoc.exists() || !targetDirDoc.isDirectory) {
            return@withContext false
        }

        val copied = copyDocumentRecursive(context, sourceDoc, targetDirDoc)
        if (copied) {
            sourceDoc.delete()
        } else {
            false
        }
    }

    private fun copyDocumentRecursive(
        context: Context,
        source: DocumentFile,
        targetParentDir: DocumentFile
    ): Boolean {
        val name = source.name ?: return false
        if (source.isDirectory) {
            val existingDir = targetParentDir.findFile(name)
            val destDir = if (existingDir != null && existingDir.isDirectory) {
                existingDir
            } else {
                targetParentDir.createDirectory(name) ?: return false
            }
            for (child in source.listFiles()) {
                if (!copyDocumentRecursive(context, child, destDir)) {
                    return false
                }
            }
            return true
        } else {
            // Remove existing file with same name if present to overwrite cleanly, or create unique
            targetParentDir.findFile(name)?.let { existing ->
                if (existing.uri != source.uri) {
                    existing.delete()
                } else {
                    return true
                }
            }

            val mime = source.type ?: getMimeType(name)
            val destFile = targetParentDir.createFile(mime, name) ?: return false

            return try {
                openInputStream(context, source).use { input ->
                    openOutputStream(context, destFile).use { output ->
                        if (input == null || output == null) return false
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                true
            } catch (_: Exception) {
                destFile.delete()
                false
            }
        }
    }

    private fun openInputStream(context: Context, doc: DocumentFile): InputStream? {
        val uri = doc.uri
        return if (uri.scheme == "file") {
            uri.path?.let { FileInputStream(File(it)) }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun openOutputStream(context: Context, doc: DocumentFile): OutputStream? {
        val uri = doc.uri
        return if (uri.scheme == "file") {
            uri.path?.let { FileOutputStream(File(it)) }
        } else {
            context.contentResolver.openOutputStream(uri, "wt")
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isNotEmpty()) {
            try {
                val mime = MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
                if (mime != null) return mime
            } catch (_: Throwable) {
            }
        }
        return "application/octet-stream"
    }
}
