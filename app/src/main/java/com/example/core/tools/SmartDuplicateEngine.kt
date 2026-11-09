package com.example.core.tools

import android.system.Os
import com.example.core.db.OperationLogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

enum class DuplicateKeepPolicy(val label: String) {
    OLDEST("Terlama (Oldest mtime)"),
    NEWEST("Terbaru (Newest mtime)"),
    PRIORITY_FOLDER("Folder Prioritas"),
    SHORTEST_NAME("Nama Terpendek")
}

enum class DuplicateExecutionMode(val label: String) {
    SCAN_MANUAL_REVIEW("Scan & Review Manual"),
    AUTO_DELETE("Auto-Delete (Wajib Dry-Run Pertama)"),
    SCHEDULED_OPT_IN("Terjadwal (Charging + Idle)")
}

data class DuplicateScanConfig(
    val rootDirectories: List<File>,
    val minSizeBytes: Long = 1024L, // Default 1 KB
    val keepPolicy: DuplicateKeepPolicy = DuplicateKeepPolicy.OLDEST,
    val priorityFolders: List<String> = emptyList(),
    val explicitlyAllowedProtectedPaths: Set<String> = emptySet(),
    val throttleDelayMsPerChunk: Long = 0L,
    val retentionDays: Int = 30
)

data class ScannedCandidateFile(
    val file: File,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val inodeKey: String?
)

data class SmartDuplicateGroup(
    val id: String,
    val sizeBytes: Long,
    val partialHash4K: String,
    val fullSha256: String,
    val files: List<ScannedCandidateFile>,
    val recommendedKeepFile: ScannedCandidateFile,
    val recommendedDeleteFiles: List<ScannedCandidateFile>
) {
    val reclaimableBytes: Long
        get() = recommendedDeleteFiles.sumOf { it.sizeBytes }
}

data class DuplicateScanProgress(
    val stage: String,
    val scannedFiles: Int,
    val candidateGroups: Int,
    val currentFile: String = ""
)

data class DuplicateDryRunSummary(
    val totalGroups: Int,
    val totalDuplicateFilesToDelete: Int,
    val totalBytesSaved: Long,
    val keptFilesCount: Int,
    val groups: List<SmartDuplicateGroup>
)

data class DuplicateExecutionReport(
    val movedToRecycleBinCount: Int,
    val permanentlyDeletedCount: Int,
    val bytesSaved: Long,
    val skippedModifiedFiles: List<String>,
    val skippedFailedFiles: List<String>,
    val trashedEntries: List<DuplicateTrashedEntry>
)

data class DuplicateTrashedEntry(
    val originalPath: String,
    val trashFilePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val trashedAt: Long,
    val expiresAt: Long
)

class SmartDuplicateEngine(
    private val duplicateRecycleBinDir: File,
    private val operationLogRepository: OperationLogRepository? = null,
    private val inodeExtractor: (File) -> String? = Companion::defaultExtractInodeKey,
    private val symlinkDetector: (File) -> Boolean = Companion::defaultIsSymlink
) {

    init {
        duplicateRecycleBinDir.mkdirs()
    }

    companion object {
        private const val PARTIAL_CHUNK_BYTES = 4096 // 4 KB awal + 4 KB akhir
        private const val STREAM_BUFFER_BYTES = 16 * 1024

        val PROTECTED_SYSTEM_PREFIXES = listOf(
            "/system",
            "/vendor",
            "/product",
            "/data/data",
            "/data/user/0",
            "/data/app"
        )

        val PROTECTED_ANDROID_SUBPATHS = listOf(
            "/Android/data",
            "/Android/obb"
        )

        fun defaultIsSymlink(file: File): Boolean {
            return try {
                if (Files.isSymbolicLink(file.toPath())) {
                    true
                } else {
                    val parentCanon = file.parentFile?.canonicalFile ?: file.parentFile
                    val fileInCanonParent = if (parentCanon != null) File(parentCanon, file.name) else file
                    fileInCanonParent.canonicalPath != fileInCanonParent.absolutePath
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun defaultExtractInodeKey(file: File): String? {
            return try {
                val stat = Os.lstat(file.absolutePath)
                if (stat.st_ino != 0L) {
                    "${stat.st_dev}:${stat.st_ino}"
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Checks if a path is inside protected system/app directories unless explicitly allowed by the user.
         */
        fun isProtectedSystemOrAppPath(path: String, explicitlyAllowedPaths: Set<String>): Boolean {
            val normalized = path.replace('\\', '/').trimEnd('/')
            for (allowed in explicitlyAllowedPaths) {
                val normAllowed = allowed.replace('\\', '/').trimEnd('/')
                if (normAllowed.isNotEmpty() && (normalized == normAllowed || normalized.startsWith("$normAllowed/"))) {
                    return false
                }
            }
            for (prefix in PROTECTED_SYSTEM_PREFIXES) {
                if (normalized == prefix || normalized.startsWith("$prefix/")) {
                    return true
                }
            }
            for (sub in PROTECTED_ANDROID_SUBPATHS) {
                if (normalized.endsWith(sub) || normalized.contains("$sub/")) {
                    return true
                }
            }
            return false
        }

        /**
         * Stage 2: Computes SHA-256 of first 4 KB + last 4 KB (plus file length salt).
         */
        fun computePartialHash4K(file: File): String {
            val length = file.length()
            val digest = MessageDigest.getInstance("SHA-256")
            // Include file length in partial digest
            digest.update(length.toString().toByteArray())

            RandomAccessFile(file, "r").use { raf ->
                val headSize = minOf(PARTIAL_CHUNK_BYTES.toLong(), length).toInt()
                val headBuf = ByteArray(headSize)
                raf.seek(0L)
                val headRead = raf.read(headBuf)
                if (headRead > 0) {
                    digest.update(headBuf, 0, headRead)
                }

                if (length > PARTIAL_CHUNK_BYTES) {
                    val tailOffset = maxOf(PARTIAL_CHUNK_BYTES.toLong(), length - PARTIAL_CHUNK_BYTES)
                    val tailSize = (length - tailOffset).toInt()
                    if (tailSize > 0) {
                        val tailBuf = ByteArray(tailSize)
                        raf.seek(tailOffset)
                        val tailRead = raf.read(tailBuf)
                        if (tailRead > 0) {
                            digest.update(tailBuf, 0, tailRead)
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Stage 3: Full streaming SHA-256 computation without loading the entire file into memory.
         */
        suspend fun computeFullSha256Streaming(file: File, throttleDelayMs: Long = 0L): String =
            withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var read: Int
                    var chunks = 0
                    while (input.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        digest.update(buffer, 0, read)
                        chunks++
                        if (throttleDelayMs > 0L && chunks % 32 == 0) {
                            delay(throttleDelayMs)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }

        /**
         * Stage 4: Exact byte-by-byte stream comparison between two files before deletion.
         */
        suspend fun verifyExactByteMatch(fileA: File, fileB: File): Boolean = withContext(Dispatchers.IO) {
            if (!fileA.exists() || !fileB.exists()) return@withContext false
            if (fileA.length() != fileB.length()) return@withContext false
            if (fileA.absolutePath == fileB.absolutePath) return@withContext true

            FileInputStream(fileA).use { inA ->
                FileInputStream(fileB).use { inB ->
                    val bufA = ByteArray(STREAM_BUFFER_BYTES)
                    val bufB = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val readA = readFullyOrEof(inA, bufA)
                        val readB = readFullyOrEof(inB, bufB)
                        if (readA != readB) return@withContext false
                        if (readA == 0) break
                        for (i in 0 until readA) {
                            if (bufA[i] != bufB[i]) return@withContext false
                        }
                    }
                }
            }
            true
        }

        private fun readFullyOrEof(input: FileInputStream, buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val r = input.read(buffer, total, buffer.size - total)
                if (r == -1) break
                total += r
            }
            return total
        }

        /**
         * Selects which file to keep according to [policy] and returns (keepFile, deleteFiles).
         * Enforces the mandatory invariant: AT LEAST 1 COPY MUST REMAIN in the group.
         */
        fun applyKeepPolicy(
            candidates: List<ScannedCandidateFile>,
            policy: DuplicateKeepPolicy,
            priorityFolders: List<String> = emptyList()
        ): Pair<ScannedCandidateFile, List<ScannedCandidateFile>> {
            require(candidates.isNotEmpty()) { "Candidate list must not be empty" }
            if (candidates.size == 1) {
                return candidates.first() to emptyList()
            }

            val normalizedPriorities = priorityFolders.map { it.replace('\\', '/').trimEnd('/') }

            val sorted = candidates.sortedWith(
                Comparator { a, b ->
                    when (policy) {
                        DuplicateKeepPolicy.OLDEST -> {
                            val cmp = a.lastModified.compareTo(b.lastModified)
                            if (cmp != 0) cmp else a.path.compareTo(b.path)
                        }
                        DuplicateKeepPolicy.NEWEST -> {
                            val cmp = b.lastModified.compareTo(a.lastModified)
                            if (cmp != 0) cmp else a.path.compareTo(b.path)
                        }
                        DuplicateKeepPolicy.PRIORITY_FOLDER -> {
                            val rankA = priorityRank(a.path, normalizedPriorities)
                            val rankB = priorityRank(b.path, normalizedPriorities)
                            val cmp = rankA.compareTo(rankB)
                            if (cmp != 0) cmp else a.lastModified.compareTo(b.lastModified)
                        }
                        DuplicateKeepPolicy.SHORTEST_NAME -> {
                            val cmp = a.file.name.length.compareTo(b.file.name.length)
                            if (cmp != 0) cmp else a.path.compareTo(b.path)
                        }
                    }
                }
            )

            val winner = sorted.first()
            val toDelete = sorted.drop(1)
            // Invariant check: winner is kept, toDelete has size == candidates.size - 1
            check(toDelete.size < candidates.size) { "Invariant violated: at least 1 copy must remain" }
            return winner to toDelete
        }

        /**
         * Enforces the invariant on any user-customized deletion selection for a group:
         * Even if the caller attempts to mark ALL files in [group] for deletion, at least 1 copy
         * ([group.recommendedKeepFile]) is forcibly retained.
         */
        fun enforceMinimumOneCopyInvariant(
            group: SmartDuplicateGroup,
            requestedDeletePaths: Set<String>
        ): List<ScannedCandidateFile> {
            val allGroupFiles = group.files
            if (allGroupFiles.size <= 1) return emptyList()

            val selected = allGroupFiles.filter { requestedDeletePaths.contains(it.path) }
            return if (selected.size >= allGroupFiles.size) {
                // Caller tried to delete all copies -> force keep recommendedKeepFile
                selected.filter { it.path != group.recommendedKeepFile.path }
                    .let { filtered ->
                        if (filtered.size >= allGroupFiles.size) selected.drop(1) else filtered
                    }
            } else {
                selected
            }
        }

        private fun priorityRank(path: String, normalizedPriorityFolders: List<String>): Int {
            val norm = path.replace('\\', '/')
            for (i in normalizedPriorityFolders.indices) {
                val p = normalizedPriorityFolders[i]
                if (p.isNotEmpty() && (norm == p || norm.startsWith("$p/"))) {
                    return i
                }
            }
            return Int.MAX_VALUE
        }
    }

    /**
     * Multi-stage duplicate scan:
     * 1. Collect eligible files (exclude 0-byte, < minSizeBytes, symlinks, hardlinks to same inode, protected paths).
     * 2. Group by exact file size.
     * 3. Group by 4KB head+tail partial hash.
     * 4. Group by full streaming SHA-256 hash.
     * 5. Verify byte-by-byte match against group leader and apply KeepPolicy.
     */
    suspend fun scanDuplicates(
        config: DuplicateScanConfig,
        onProgress: (DuplicateScanProgress) -> Unit = {}
    ): List<SmartDuplicateGroup> = withContext(Dispatchers.IO) {
        val effectiveMinSize = maxOf(1L, config.minSizeBytes) // Always ignore 0-byte files
        val seenCanonicalPaths = mutableSetOf<String>()
        val seenInodes = mutableSetOf<String>()
        val candidates = mutableListOf<ScannedCandidateFile>()

        val explicitAllowed = config.explicitlyAllowedProtectedPaths +
            config.rootDirectories.map { it.absolutePath }.filter { path ->
                PROTECTED_SYSTEM_PREFIXES.any { path.startsWith(it) } ||
                    PROTECTED_ANDROID_SUBPATHS.any { path.contains(it) }
            }

        var scannedCount = 0

        for (rootDir in config.rootDirectories) {
            if (!rootDir.exists() || !rootDir.isDirectory) continue

            val stack = ArrayDeque<File>()
            stack.add(rootDir)

            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()

                if (symlinkDetector(dir)) continue
                if (dir != rootDir && isProtectedSystemOrAppPath(dir.absolutePath, explicitAllowed)) {
                    continue
                }

                val children = try {
                    dir.listFiles()
                } catch (_: Throwable) {
                    null
                } ?: continue

                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    if (symlinkDetector(child)) continue
                    val absPath = child.absolutePath
                    if (isProtectedSystemOrAppPath(absPath, explicitAllowed)) continue

                    if (child.isDirectory) {
                        stack.add(child)
                    } else if (child.isFile) {
                        val size = child.length()
                        // Exclude 0-byte files and files smaller than minSizeBytes
                        if (size <= 0L || size < effectiveMinSize) continue
                        if (!child.canRead()) continue

                        // Deduplicate same path
                        if (!seenCanonicalPaths.add(absPath)) continue

                        // Exclude hardlinks pointing to the exact same device:inode
                        val inodeKey = inodeExtractor(child)
                        if (inodeKey != null && !seenInodes.add(inodeKey)) {
                            continue
                        }

                        scannedCount++
                        if (scannedCount % 25 == 0) {
                            onProgress(
                                DuplicateScanProgress(
                                    stage = "Mengumpulkan kandidat ukuran file...",
                                    scannedFiles = scannedCount,
                                    candidateGroups = 0,
                                    currentFile = child.name
                                )
                            )
                        }

                        candidates.add(
                            ScannedCandidateFile(
                                file = child,
                                path = absPath,
                                sizeBytes = size,
                                lastModified = child.lastModified(),
                                inodeKey = inodeKey
                            )
                        )
                    }
                }
            }
        }

        // Stage 1: Group by exact file size
        val sizeGroups = candidates.groupBy { it.sizeBytes }.filterValues { it.size > 1 }
        val verifiedDuplicateGroups = mutableListOf<SmartDuplicateGroup>()

        for ((sizeBytes, sameSizeList) in sizeGroups) {
            currentCoroutineContext().ensureActive()
            onProgress(
                DuplicateScanProgress(
                    stage = "Menghitung hash 4KB awal+akhir...",
                    scannedFiles = scannedCount,
                    candidateGroups = verifiedDuplicateGroups.size,
                    currentFile = sameSizeList.first().file.name
                )
            )

            // Stage 2: Group by 4KB partial hash (first 4KB + last 4KB)
            val partialGroups = sameSizeList.mapNotNull { candidate ->
                try {
                    // Verify file was not modified/deleted during scan
                    if (!candidate.file.exists() || candidate.file.length() != candidate.sizeBytes) {
                        null
                    } else {
                        computePartialHash4K(candidate.file) to candidate
                    }
                } catch (_: Throwable) {
                    null
                }
            }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }

            // Stage 3: Group by full streaming SHA-256
            for ((partialHash, samePartialList) in partialGroups) {
                currentCoroutineContext().ensureActive()
                val fullHashGroups = mutableMapOf<String, MutableList<ScannedCandidateFile>>()

                for (candidate in samePartialList) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        DuplicateScanProgress(
                            stage = "Menghitung SHA-256 penuh (streaming)...",
                            scannedFiles = scannedCount,
                            candidateGroups = verifiedDuplicateGroups.size,
                            currentFile = candidate.file.name
                        )
                    )
                    try {
                        if (!candidate.file.exists() ||
                            candidate.file.length() != candidate.sizeBytes ||
                            candidate.file.lastModified() != candidate.lastModified
                        ) {
                            // File changed during scan -> skip safely
                            continue
                        }
                        val sha256 = computeFullSha256Streaming(candidate.file, config.throttleDelayMsPerChunk)
                        fullHashGroups.getOrPut(sha256) { mutableListOf() }.add(candidate)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        // Unreadable / locked file -> skip
                    }
                }

                // Stage 4: Byte-by-byte verification against the group anchor
                for ((sha256, sameHashList) in fullHashGroups) {
                    if (sameHashList.size < 2) continue
                    val anchor = sameHashList.first()
                    val byteVerified = mutableListOf(anchor)

                    for (idx in 1 until sameHashList.size) {
                        val other = sameHashList[idx]
                        if (verifyExactByteMatch(anchor.file, other.file)) {
                            byteVerified.add(other)
                        }
                    }

                    if (byteVerified.size > 1) {
                        val (keepFile, deleteFiles) = applyKeepPolicy(
                            candidates = byteVerified,
                            policy = config.keepPolicy,
                            priorityFolders = config.priorityFolders
                        )
                        verifiedDuplicateGroups.add(
                            SmartDuplicateGroup(
                                id = "${sizeBytes}_${sha256.take(16)}",
                                sizeBytes = sizeBytes,
                                partialHash4K = partialHash,
                                fullSha256 = sha256,
                                files = byteVerified,
                                recommendedKeepFile = keepFile,
                                recommendedDeleteFiles = deleteFiles
                            )
                        )
                    }
                }
            }
        }

        verifiedDuplicateGroups.sortedByDescending { it.reclaimableBytes }
    }

    /**
     * Generates a mandatory Dry-Run summary prior to Auto-Delete execution.
     */
    fun createDryRunSummary(groups: List<SmartDuplicateGroup>): DuplicateDryRunSummary {
        return DuplicateDryRunSummary(
            totalGroups = groups.size,
            totalDuplicateFilesToDelete = groups.sumOf { it.recommendedDeleteFiles.size },
            totalBytesSaved = groups.sumOf { it.reclaimableBytes },
            keptFilesCount = groups.size,
            groups = groups
        )
    }

    /**
     * Executes duplicate deletion safely:
     * 1. Enforces invariant: at least 1 copy per group MUST remain untouched.
     * 2. Re-verifies size, mtime, SHA-256 hash, AND exact byte-compare against the kept copy right before deleting.
     * 3. Moves duplicates to Duplicate Recycle Bin (default 30-day retention) unless [permanentDelete] is true.
     * 4. Records operation in [OperationLogRepository] for 1-click Restore / Undo.
     */
    suspend fun executeDuplicateCleanup(
        groups: List<SmartDuplicateGroup>,
        customSelectedDeletePaths: Set<String>? = null,
        permanentDelete: Boolean = false,
        retentionDays: Int = 30
    ): DuplicateExecutionReport = withContext(Dispatchers.IO) {
        var movedToBin = 0
        var permDeleted = 0
        var savedBytes = 0L
        val skippedModified = mutableListOf<String>()
        val skippedFailed = mutableListOf<String>()
        val trashedEntries = mutableListOf<DuplicateTrashedEntry>()
        val now = System.currentTimeMillis()
        val expiresAt = now + retentionDays.coerceAtLeast(1) * 86_400_000L

        for (group in groups) {
            currentCoroutineContext().ensureActive()
            if (group.files.size < 2) continue

            val requestedSet = customSelectedDeletePaths
                ?: group.recommendedDeleteFiles.map { it.path }.toSet()

            // Enforce invariant: NEVER delete all copies in a group
            val safeDeleteList = enforceMinimumOneCopyInvariant(group, requestedSet)
            val keptCandidate = group.files.firstOrNull { candidate ->
                safeDeleteList.none { it.path == candidate.path }
            } ?: group.recommendedKeepFile

            // Verify kept file still exists and matches snapshot
            val keptFile = keptCandidate.file
            if (!keptFile.exists() ||
                keptFile.length() != keptCandidate.sizeBytes ||
                keptFile.lastModified() != keptCandidate.lastModified
            ) {
                skippedModified.add(keptCandidate.path)
                continue
            }

            for (target in safeDeleteList) {
                currentCoroutineContext().ensureActive()
                val file = target.file

                // Pre-delete re-verification: size, mtime, SHA-256, and byte-compare
                if (!file.exists() ||
                    file.length() != target.sizeBytes ||
                    file.lastModified() != target.lastModified
                ) {
                    skippedModified.add(target.path)
                    continue
                }

                val currentHash = try {
                    computeFullSha256Streaming(file)
                } catch (_: Throwable) {
                    ""
                }
                if (currentHash != group.fullSha256) {
                    skippedModified.add(target.path)
                    continue
                }

                val byteMatch = try {
                    verifyExactByteMatch(keptFile, file)
                } catch (_: Throwable) {
                    false
                }
                if (!byteMatch) {
                    skippedModified.add(target.path)
                    continue
                }

                // Execute move to Duplicate Recycle Bin or permanent delete
                if (permanentDelete) {
                    if (file.delete()) {
                        permDeleted++
                        savedBytes += target.sizeBytes
                        operationLogRepository?.recordOperation(
                            operationType = "DELETE",
                            targetPath = target.path,
                            secondaryPath = "",
                            details = "Hapus permanen duplikat dari ${keptFile.name} (SHA-256: ${group.fullSha256.take(12)})",
                            canUndo = false
                        )
                    } else {
                        skippedFailed.add(target.path)
                    }
                } else {
                    val destTrashFile = allocateUniqueTrashFile(file.name)
                    val moved = try {
                        FileInputStream(file).use { input ->
                            FileOutputStream(destTrashFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        destTrashFile.setLastModified(target.lastModified)
                        if (file.delete()) {
                            writeTrashMeta(
                                trashFile = destTrashFile,
                                originalPath = target.path,
                                sizeBytes = target.sizeBytes,
                                sha256 = group.fullSha256,
                                trashedAt = now,
                                expiresAt = expiresAt
                            )
                            true
                        } else {
                            destTrashFile.delete()
                            false
                        }
                    } catch (_: Throwable) {
                        destTrashFile.delete()
                        false
                    }

                    if (moved) {
                        movedToBin++
                        savedBytes += target.sizeBytes
                        val entry = DuplicateTrashedEntry(
                            originalPath = target.path,
                            trashFilePath = destTrashFile.absolutePath,
                            sizeBytes = target.sizeBytes,
                            sha256 = group.fullSha256,
                            trashedAt = now,
                            expiresAt = expiresAt
                        )
                        trashedEntries.add(entry)
                        operationLogRepository?.recordOperation(
                            operationType = "DUPLICATE_TRASH",
                            targetPath = target.path,
                            secondaryPath = destTrashFile.absolutePath,
                            details = "Pindah duplikat ke Recycle Bin (dipertahankan: ${keptFile.name})",
                            canUndo = true
                        )
                    } else {
                        skippedFailed.add(target.path)
                    }
                }
            }
        }

        DuplicateExecutionReport(
            movedToRecycleBinCount = movedToBin,
            permanentlyDeletedCount = permDeleted,
            bytesSaved = savedBytes,
            skippedModifiedFiles = skippedModified,
            skippedFailedFiles = skippedFailed,
            trashedEntries = trashedEntries
        )
    }

    /**
     * Restores all items from a [DuplicateExecutionReport] back to their original paths.
     */
    suspend fun restoreFromRecycleBin(entries: List<DuplicateTrashedEntry>): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (entry in entries) {
            val src = File(entry.trashFilePath)
            if (!src.exists()) continue
            val dest = File(entry.originalPath)
            try {
                dest.parentFile?.mkdirs()
                FileInputStream(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                src.delete()
                File(src.parent, "${src.name}.meta").delete()
                restored++
            } catch (_: Throwable) {
            }
        }
        restored
    }

    /**
     * Purges expired items from the Duplicate Recycle Bin beyond retention period.
     */
    suspend fun purgeExpiredTrash(nowMillis: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        var purged = 0
        val files = duplicateRecycleBinDir.listFiles().orEmpty()
        for (metaFile in files.filter { it.name.endsWith(".meta") }) {
            try {
                val lines = metaFile.readLines()
                val expiresAt = lines.getOrNull(4)?.toLongOrNull() ?: continue
                if (nowMillis >= expiresAt) {
                    val dataFile = File(metaFile.parent, metaFile.name.removeSuffix(".meta"))
                    dataFile.delete()
                    metaFile.delete()
                    purged++
                }
            } catch (_: Throwable) {
            }
        }
        purged
    }

    private fun allocateUniqueTrashFile(baseName: String): File {
        var candidate = File(duplicateRecycleBinDir, baseName)
        var counter = 1
        val ext = candidate.extension
        val stem = candidate.nameWithoutExtension
        while (candidate.exists()) {
            val nextName = if (ext.isNotEmpty()) "$stem ($counter).$ext" else "$stem ($counter)"
            candidate = File(duplicateRecycleBinDir, nextName)
            counter++
        }
        return candidate
    }

    private fun writeTrashMeta(
        trashFile: File,
        originalPath: String,
        sizeBytes: Long,
        sha256: String,
        trashedAt: Long,
        expiresAt: Long
    ) {
        try {
            val meta = File(trashFile.parent, "${trashFile.name}.meta")
            meta.writeText(
                listOf(
                    originalPath,
                    sizeBytes.toString(),
                    sha256,
                    trashedAt.toString(),
                    expiresAt.toString()
                ).joinToString("\n")
            )
        } catch (_: Throwable) {
        }
    }
}
