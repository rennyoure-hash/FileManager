package com.example.core.archive

import com.example.core.fs.Capability
import com.example.core.fs.FileSystem
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ArchiveFormat { ZIP, TAR, TAR_GZ, GZIP, BZ2, XZ, ZSTD, SEVEN_ZIP, RAR }

data class SafeExtractLimits(
    val maxEntries: Int = 10_000,
    val maxTotalUncompressedBytes: Long = 512L * 1024L * 1024L, // 512 MB
    val maxSingleEntryBytes: Long = 256L * 1024L * 1024L, // 256 MB
    val maxCompressionRatio: Double = 100.0,
    val ratioCheckMinBytes: Long = 64L * 1024L // Check compression ratio after 64 KB
)

object ArchiveUtils {
    private val WINDOWS_DRIVE_REGEX = Regex("^[a-zA-Z]:.*")

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

    /**
     * Checks if [entryName] is an absolute path or contains parent traversal segments (`..`).
     */
    fun isUnsafeArchiveEntryName(entryName: String): Boolean {
        if (entryName.isEmpty() || entryName.contains('\u0000')) return true
        if (entryName.startsWith("/") || entryName.startsWith("\\")) return true
        if (WINDOWS_DRIVE_REGEX.matches(entryName)) return true
        val normalized = entryName.replace('\\', '/')
        val segments = normalized.split('/')
        return segments.any { it == ".." }
    }

    /**
     * Fase 7.A: Validates and resolves [entryName] inside [targetDir].
     * Rejects absolute paths (`/etc/passwd`), Windows drive paths, and Zip-Slip (`../../evil`).
     */
    fun validateEntryPath(targetDir: File, entryName: String): File {
        if (isUnsafeArchiveEntryName(entryName)) {
            throw SecurityException("SafeExtract Blocked (Zip-Slip / Absolute Path): '$entryName'")
        }
        val normalized = entryName.replace('\\', '/').trimStart('/')
        val canonicalTargetDir = targetDir.canonicalFile
        val candidateFile = File(canonicalTargetDir, normalized).canonicalFile
        val targetPrefix = canonicalTargetDir.path.let { if (it.endsWith(File.separator)) it else "$it${File.separator}" }
        if (candidateFile != canonicalTargetDir && !candidateFile.path.startsWith(targetPrefix)) {
            throw SecurityException("SafeExtract Blocked (Path Traversal outside targetDir): '$entryName'")
        }
        return candidateFile
    }

    /**
     * Detects whether a ZIP entry is a symbolic link (via POSIX mode bits or symlink naming conventions).
     */
    fun isZipEntrySymlink(entry: ZipEntry, rawExternalAttr: Long = 0L): Boolean {
        // POSIX S_IFLNK = 0xA000 (octal 0120000) in upper 16 bits of external file attributes
        val unixMode = ((rawExternalAttr ushr 16) and 0xFFFFL).toInt()
        if ((unixMode and 0xF000) == 0xA000) {
            return true
        }
        val comment = entry.comment.orEmpty()
        if (comment.startsWith("SYMLINK:", ignoreCase = true)) {
            return true
        }
        return false
    }
}

class ArchiveManager {

    /**
     * Fase 7.A Safe Extract:
     * - Blocks Zip-Slip & absolute paths inside archives.
     * - Rejects/skips symbolic link entries inside archives.
     * - Enforces Zip Bomb protections: entry count limit, single-entry & total uncompressed byte limits,
     *   and compression ratio limit during streaming extraction.
     */
    suspend fun extractZip(
        archive: File,
        targetDir: File,
        limits: SafeExtractLimits = SafeExtractLimits()
    ): List<File> = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val extracted = mutableListOf<File>()
        var totalUncompressedBytes = 0L
        var entryCount = 0

        // Pre-scan ZipFile central directory if available for symlink mode attributes and declared ratios
        val symlinkEntryNames = mutableSetOf<String>()
        val declaredCompressedSizes = mutableMapOf<String, Long>()
        try {
            ZipFile(archive).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val ze = entries.nextElement()
                    if (ze.compressedSize > 0) {
                        declaredCompressedSizes[ze.name] = ze.compressedSize
                    }
                    if (ArchiveUtils.isZipEntrySymlink(ze)) {
                        symlinkEntryNames.add(ze.name)
                    }
                }
            }
        } catch (_: Throwable) {
            // Stream-only fallback
        }

        val archiveCompressedLength = archive.length().coerceAtLeast(1L)

        ZipInputStream(FileInputStream(archive)).use { zip ->
            val buffer = ByteArray(8192)
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw SecurityException(
                        "SafeExtract Blocked (Zip Bomb): Jumlah entri melebihi batas maksimum (${limits.maxEntries})"
                    )
                }

                val currentEntry = entry
                // Reject symlinks inside archive
                if (symlinkEntryNames.contains(currentEntry.name) || ArchiveUtils.isZipEntrySymlink(currentEntry)) {
                    throw SecurityException(
                        "SafeExtract Blocked (Symlink in Archive): Entri symlink '${currentEntry.name}' ditolak"
                    )
                }

                // Validate Zip-Slip & absolute paths
                val outFile = ArchiveUtils.validateEntryPath(targetDir, currentEntry.name)

                if (currentEntry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    var entryBytesWritten = 0L
                    val compressedRef = (
                        if (currentEntry.compressedSize > 0) currentEntry.compressedSize
                        else declaredCompressedSizes[currentEntry.name] ?: archiveCompressedLength
                        ).coerceAtLeast(1L)

                    try {
                        FileOutputStream(outFile).use { out ->
                            var read: Int
                            while (zip.read(buffer).also { read = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                entryBytesWritten += read
                                totalUncompressedBytes += read

                                if (entryBytesWritten > limits.maxSingleEntryBytes) {
                                    throw SecurityException(
                                        "SafeExtract Blocked (Zip Bomb): Ukuran entri '${currentEntry.name}' melebihi batas (${limits.maxSingleEntryBytes} bytes)"
                                    )
                                }
                                if (totalUncompressedBytes > limits.maxTotalUncompressedBytes) {
                                    throw SecurityException(
                                        "SafeExtract Blocked (Zip Bomb): Total ukuran ekstrak melebihi batas (${limits.maxTotalUncompressedBytes} bytes)"
                                    )
                                }
                                if (entryBytesWritten >= limits.ratioCheckMinBytes) {
                                    val ratio = entryBytesWritten.toDouble() / compressedRef.toDouble()
                                    if (ratio > limits.maxCompressionRatio) {
                                        throw SecurityException(
                                            "SafeExtract Blocked (Zip Bomb): Rasio kompresi terlalu tinggi (${"%.1f".format(ratio)}:1 > ${limits.maxCompressionRatio}:1) pada '${currentEntry.name}'"
                                        )
                                    }
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                        extracted.add(outFile)
                    } catch (se: SecurityException) {
                        outFile.delete()
                        throw se
                    }
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
        } catch (_: Exception) {
            false
        }
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

    /** List ZIP entries without extracting (skips unsafe / symlink entries). */
    suspend fun listZip(archive: File, limits: SafeExtractLimits = SafeExtractLimits()): List<VEntry> =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<VEntry>()
            var count = 0
            ZipInputStream(FileInputStream(archive)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    count++
                    if (count > limits.maxEntries) break
                    val e = entry
                    if (!ArchiveUtils.isUnsafeArchiveEntryName(e.name) && !ArchiveUtils.isZipEntrySymlink(e)) {
                        entries.add(
                            VEntry(
                                name = e.name.trimEnd('/').substringAfterLast("/"),
                                path = VPath(e.name),
                                isDirectory = e.isDirectory,
                                size = e.size.coerceAtLeast(0L),
                                lastModified = e.time,
                                mimeType = if (e.isDirectory) "inode/directory" else "application/octet-stream"
                            )
                        )
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            entries
        }

    /** Extract single entry from ZIP with SafeExtract limits. */
    suspend fun extractSingleEntry(
        archive: File,
        entryName: String,
        output: File,
        limits: SafeExtractLimits = SafeExtractLimits()
    ): Boolean = withContext(Dispatchers.IO) {
        if (ArchiveUtils.isUnsafeArchiveEntryName(entryName)) {
            return@withContext false
        }
        try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                val buffer = ByteArray(8192)
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        if (ArchiveUtils.isZipEntrySymlink(entry)) return@withContext false
                        output.parentFile?.mkdirs()
                        var written = 0L
                        FileOutputStream(output).use { out ->
                            var r: Int
                            while (zip.read(buffer).also { r = it } != -1) {
                                written += r
                                if (written > limits.maxSingleEntryBytes) {
                                    output.delete()
                                    return@withContext false
                                }
                                out.write(buffer, 0, r)
                            }
                        }
                        return@withContext true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Extract GZIP single file with size limit. */
    suspend fun extractGzip(
        archive: File,
        output: File,
        limits: SafeExtractLimits = SafeExtractLimits()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            GZIPInputStream(FileInputStream(archive)).use { input ->
                output.parentFile?.mkdirs()
                val buffer = ByteArray(8192)
                var written = 0L
                FileOutputStream(output).use { out ->
                    var r: Int
                    while (input.read(buffer).also { r = it } != -1) {
                        written += r
                        if (written > limits.maxTotalUncompressedBytes) {
                            output.delete()
                            return@withContext false
                        }
                        out.write(buffer, 0, r)
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
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
            return entries.filter { entry ->
                val n = entry.path.normalized
                !n.contains("/") || n.trimEnd('/').count { c -> c == '/' } == 0
            }
        }
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
        val temp = File.createTempFile("archive_entry", ".tmp")
        val ok = runBlockingExtract(path, temp)
        require(ok) { "Entry not found or blocked by SafeExtract" }
        return FileInputStream(temp)
    }

    private fun runBlockingExtract(path: VPath, out: File): Boolean {
        return try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                manager.extractSingleEntry(file, path.normalized.removePrefix(archivePath.normalized).trim('/'), out)
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        throw UnsupportedOperationException("Archive is read-only")
    }

    override suspend fun mkdir(path: VPath): Boolean = false
    override suspend fun rename(from: VPath, to: VPath): Boolean = false
    override suspend fun delete(path: VPath, recursive: Boolean): Boolean = false
    override suspend fun exists(path: VPath): Boolean = stat(path) != null
}
