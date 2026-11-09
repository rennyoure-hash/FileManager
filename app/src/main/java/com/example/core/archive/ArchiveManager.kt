package com.example.core.archive

import com.example.core.fs.FileSystem
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import com.example.core.fs.Capability
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ArchiveFormat { ZIP, TAR, TAR_GZ, GZIP, BZ2, XZ, ZSTD, SEVEN_ZIP, RAR }

object ArchiveUtils {
    fun formatOf(name: String): ArchiveFormat? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveFormat.ZIP
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            lower.endsWith(".tar") -> ArchiveFormat.TAR
            lower.endsWith(".gz") -> ArchiveFormat.GZIP
            lower.endsWith(".bz2") -> ArchiveFormat.BZ2
            lower.endsWith(".xz") -> ArchiveFormat.XZ
            lower.endsWith(".zst") -> ArchiveFormat.ZSTD
            lower.endsWith(".7z") -> ArchiveFormat.SEVEN_ZIP
            lower.endsWith(".rar") -> ArchiveFormat.RAR
            else -> null
        }
    }

    fun isArchive(name: String) = formatOf(name) != null

    /** Normalizes entry name, throws if zip-slip. */
    fun validateEntryPath(targetDir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/').removePrefix("/")
        val canonicalTarget = targetDir.canonicalPath
        val canonicalDest = File(canonicalTarget, normalized).canonicalPath
        require(canonicalDest.startsWith(canonicalTarget)) { "Path traversal blocked: $entryName" }
        return File(canonicalDest)
    }
}

class ArchiveManager {
    /** Extract ZIP with zip-slip protection. Returns list of extracted files. */
    suspend fun extractZip(archive: File, targetDir: File): List<File> = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val extracted = mutableListOf<File>()
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val outFile = ArchiveUtils.validateEntryPath(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        zip.copyTo(out)
                    }
                    extracted.add(outFile)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        extracted
    }

    /** Create ZIP from files/dirs. Streams everything. */
    suspend fun createZip(sources: List<File>, output: File): Boolean = withContext(Dispatchers.IO) {
        try {
            output.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                for (src in sources) {
                    addToZip(zip, src, src.name)
                }
            }
            true
        } catch (_: Exception) { false }
    }

    private fun addToZip(zip: ZipOutputStream, file: File, zipEntryName: String) {
        if (file.isDirectory) {
            val dirEntry = ZipEntry("$zipEntryName/")
            zip.putNextEntry(dirEntry)
            zip.closeEntry()
            for (child in file.listFiles().orEmpty()) {
                addToZip(zip, child, "$zipEntryName/${child.name}")
            }
        } else {
            val entry = ZipEntry(zipEntryName)
            zip.putNextEntry(entry)
            FileInputStream(file).use { input ->
                input.copyTo(zip)
            }
            zip.closeEntry()
        }
    }

    /** List ZIP entries without extracting. */
    suspend fun listZip(archive: File): List<VEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<VEntry>()
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                entries.add(
                    VEntry(
                        name = entry.name.trimEnd('/').substringAfterLast("/"),
                        path = VPath(entry.name),
                        isDirectory = entry.isDirectory,
                        size = entry.size.coerceAtLeast(0L),
                        lastModified = entry.time,
                        mimeType = if (entry.isDirectory) "inode/directory" else "application/octet-stream"
                    )
                )
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        entries
    }

    /** Extract single entry from ZIP. */
    suspend fun extractSingleEntry(archive: File, entryName: String, output: File): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { out ->
                            zip.copyTo(out)
                        }
                        return@withContext true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            false
        } catch (_: Exception) { false }
    }

    /** Extract GZIP single file. */
    suspend fun extractGzip(archive: File, output: File): Boolean = withContext(Dispatchers.IO) {
        try {
            java.util.zip.GZIPInputStream(FileInputStream(archive)).use { input ->
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { out ->
                    input.copyTo(out)
                }
            }
            true
        } catch (_: Exception) { false }
    }
}

/** FileSystem that opens a ZIP as a virtual directory. */
class ArchiveFileSystem(
    val archivePath: VPath,
    val file: File
) : FileSystem {
    override val id: String = "archive:${archivePath.normalized}"
    override val name: String = "Archive"
    override val rootPath: VPath = archivePath
    override val capabilities: Set<Capability> = setOf(Capability.RANDOM_ACCESS)

    private val manager = ArchiveManager()

    private var cachedEntries: List<VEntry>? = null

    override suspend fun list(path: VPath): List<VEntry> {
        val entries = cachedEntries ?: manager.listZip(file).also { cachedEntries = it }
        val relative = path.normalized.removePrefix(archivePath.normalized).trim('/')
        if (relative.isEmpty()) {
            // top-level only
            return entries.filter { entry ->
                val n = entry.path.normalized
                !n.contains("/") || n.trimEnd('/').count { c -> c == '/' } == 0
            }
        }
        // children only: prefix + exactly one segment deeper
        val prefix = relative.trimEnd('/')
        return entries.filter { entry ->
            val n = entry.path.normalized.trimEnd('/')
            if (n == prefix) return@filter false
            if (!n.startsWith("$prefix/")) return@filter false
            val rest = n.removePrefix("$prefix/")
            !rest.contains("/")
        }
    }

    override suspend fun stat(path: VPath): VEntry? {
        val entries = manager.listZip(file)
        return entries.firstOrNull { it.path == path }
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        // Simplified: extract to temp then read
        val temp = File.createTempFile("archive_entry", ".tmp")
        val ok = runBlockingExtract(path, temp)
        require(ok) { "Entry not found" }
        return FileInputStream(temp)
    }

    private fun runBlockingExtract(path: VPath, out: File): Boolean {
        return try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                manager.extractSingleEntry(file, path.normalized.removePrefix(archivePath.normalized).trim('/'), out)
            }
        } catch (_: Exception) { false }
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        throw UnsupportedOperationException("Archive is read-only")
    }

    override suspend fun mkdir(path: VPath): Boolean = false
    override suspend fun rename(from: VPath, to: VPath): Boolean = false
    override suspend fun delete(path: VPath, recursive: Boolean): Boolean = false
    override suspend fun exists(path: VPath): Boolean = stat(path) != null
}
