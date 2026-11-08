package com.example.core.fs

import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class LocalFileSystem(
    override val id: String = "local",
    override val name: String = "Local Storage",
    override val rootPath: VPath = VPath("/")
) : FileSystem {

    override val capabilities: Set<Capability> = setOf(
        Capability.ATOMIC_RENAME,
        Capability.PERMISSIONS,
        Capability.RANDOM_ACCESS
    )

    private fun toFile(path: VPath): File {
        return File(path.normalized)
    }

    override suspend fun list(path: VPath): List<VEntry> = withContext(Dispatchers.IO) {
        val target = toFile(path)
        if (!target.exists() || !target.isDirectory) {
            return@withContext emptyList()
        }

        val files = target.listFiles() ?: return@withContext emptyList()
        val result = ArrayList<VEntry>(files.size)

        for (file in files) {
            val isDir = file.isDirectory
            val size = if (isDir) 0L else file.length()
            val lastMod = file.lastModified()
            val canRead = file.canRead()
            val canWrite = file.canWrite()
            val mimeType = getMimeType(file)

            result.add(
                VEntry(
                    name = file.name,
                    path = VPath(file.absolutePath),
                    isDirectory = isDir,
                    size = size,
                    lastModified = lastMod,
                    isHidden = file.name.startsWith("."),
                    canRead = canRead,
                    canWrite = canWrite,
                    mimeType = mimeType
                )
            )
        }
        result
    }

    override suspend fun stat(path: VPath): VEntry? = withContext(Dispatchers.IO) {
        val file = toFile(path)
        if (!file.exists()) return@withContext null
        val isDir = file.isDirectory
        VEntry(
            name = file.name,
            path = VPath(file.absolutePath),
            isDirectory = isDir,
            size = if (isDir) 0L else file.length(),
            lastModified = file.lastModified(),
            isHidden = file.name.startsWith("."),
            canRead = file.canRead(),
            canWrite = file.canWrite(),
            mimeType = getMimeType(file)
        )
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        val file = toFile(path)
        val stream = FileInputStream(file)
        if (offset > 0) {
            var skipped = 0L
            while (skipped < offset) {
                val n = stream.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
        }
        return stream
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        val file = toFile(path)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return FileOutputStream(file, append)
    }

    override suspend fun mkdir(path: VPath): Boolean = withContext(Dispatchers.IO) {
        val file = toFile(path)
        if (file.exists()) return@withContext file.isDirectory
        file.mkdirs()
    }

    override suspend fun rename(from: VPath, to: VPath): Boolean = withContext(Dispatchers.IO) {
        val src = toFile(from)
        val dest = toFile(to)
        if (!src.exists()) return@withContext false
        if (dest.exists()) return@withContext false
        val destParent = dest.parentFile
        if (destParent != null && !destParent.exists()) {
            destParent.mkdirs()
        }
        src.renameTo(dest)
    }

    override suspend fun delete(path: VPath, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val file = toFile(path)
        if (!file.exists()) return@withContext true
        if (recursive && file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    override suspend fun exists(path: VPath): Boolean = withContext(Dispatchers.IO) {
        toFile(path).exists()
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) return "inode/directory"
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return "application/octet-stream"

        try {
            val mime = MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
            if (mime != null) return mime
        } catch (_: Throwable) {
            // Fallback for JVM unit tests where android framework classes are unmocked
        }

        return when (ext) {
            "txt", "log", "text" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/x-rar-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "kt", "java", "gradle", "py", "sh", "c", "cpp" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
