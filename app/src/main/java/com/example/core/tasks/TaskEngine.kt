package com.example.core.tasks

import com.example.core.fs.FileSystem
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class TaskEngine(private val scope: CoroutineScope) {

    private val _currentTask = MutableStateFlow<TaskProgress?>(null)
    val currentTask: StateFlow<TaskProgress?> = _currentTask.asStateFlow()

    private var activeJob: Job? = null
    private var conflictDeferred: CompletableDeferred<Pair<ConflictAction, Boolean>>? = null
    private var rememberConflictAction: ConflictAction? = null

    fun cancelActiveTask() {
        activeJob?.cancel()
        conflictDeferred?.cancel()
        _currentTask.value = _currentTask.value?.copy(
            status = TaskStatus.CANCELLED,
            currentFileName = "Cancelled"
        )
    }

    fun resolveConflict(action: ConflictAction, applyToAll: Boolean) {
        if (applyToAll) {
            rememberConflictAction = action
        }
        conflictDeferred?.complete(action to applyToAll)
    }

    fun startCopy(
        sources: List<VEntry>,
        targetDir: VPath,
        sourceFs: FileSystem,
        targetFs: FileSystem,
        onComplete: (Boolean) -> Unit = {}
    ) {
        startTransfer(
            sources = sources,
            targetDir = targetDir,
            sourceFs = sourceFs,
            targetFs = targetFs,
            isMove = false,
            onComplete = onComplete
        )
    }

    fun startMove(
        sources: List<VEntry>,
        targetDir: VPath,
        sourceFs: FileSystem,
        targetFs: FileSystem,
        onComplete: (Boolean) -> Unit = {}
    ) {
        startTransfer(
            sources = sources,
            targetDir = targetDir,
            sourceFs = sourceFs,
            targetFs = targetFs,
            isMove = true,
            onComplete = onComplete
        )
    }

    fun startDelete(
        sources: List<VEntry>,
        fs: FileSystem,
        onComplete: (Boolean) -> Unit = {}
    ) {
        activeJob?.cancel()
        val taskId = UUID.randomUUID().toString()

        activeJob = scope.launch(Dispatchers.IO) {
            _currentTask.value = TaskProgress(
                taskId = taskId,
                title = "Deleting ${sources.size} item(s)",
                type = TaskType.DELETE,
                status = TaskStatus.RUNNING,
                totalFiles = sources.size
            )

            var processed = 0
            var success = true
            try {
                for (source in sources) {
                    ensureActive()
                    _currentTask.value = _currentTask.value?.copy(
                        currentFileName = source.name,
                        filesProcessed = processed,
                        percent = if (sources.isNotEmpty()) (processed.toFloat() / sources.size) else 1f
                    )
                    val deleted = fs.delete(source.path, recursive = true)
                    if (!deleted) success = false
                    processed++
                }

                _currentTask.value = _currentTask.value?.copy(
                    status = TaskStatus.COMPLETED,
                    percent = 1f,
                    filesProcessed = processed,
                    currentFileName = "Done"
                )
                onComplete(success)
            } catch (e: Exception) {
                _currentTask.value = _currentTask.value?.copy(
                    status = if (e is kotlinx.coroutines.CancellationException) TaskStatus.CANCELLED else TaskStatus.FAILED,
                    errorMessage = e.message
                )
                onComplete(false)
            }
        }
    }

    private fun startTransfer(
        sources: List<VEntry>,
        targetDir: VPath,
        sourceFs: FileSystem,
        targetFs: FileSystem,
        isMove: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        activeJob?.cancel()
        rememberConflictAction = null
        val taskId = UUID.randomUUID().toString()
        val title = if (isMove) "Moving ${sources.size} item(s)" else "Copying ${sources.size} item(s)"

        activeJob = scope.launch(Dispatchers.IO) {
            _currentTask.value = TaskProgress(
                taskId = taskId,
                title = title,
                type = if (isMove) TaskType.MOVE else TaskType.COPY,
                status = TaskStatus.SCANNING,
                currentFileName = "Calculating size..."
            )

            try {
                // 1. Pre-scan total bytes & file count
                val allItems = mutableListOf<Pair<VEntry, String>>() // entry, relativePath
                var totalBytes = 0L

                for (source in sources) {
                    scanRecursive(source, "", sourceFs, allItems)
                }

                totalBytes = allItems.sumOf { if (it.first.isDirectory) 0L else it.first.size }
                val totalFiles = allItems.size

                _currentTask.value = _currentTask.value?.copy(
                    status = TaskStatus.RUNNING,
                    totalBytes = totalBytes,
                    totalFiles = totalFiles
                )

                var processedBytes = 0L
                var processedFiles = 0
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastCalc = 0L
                var speedBps = 0L

                // 2. Perform transfer
                for ((entry, relPath) in allItems) {
                    ensureActive()
                    var destinationPath = targetDir.resolve(relPath)

                    _currentTask.value = _currentTask.value?.copy(
                        currentFileName = entry.name,
                        filesProcessed = processedFiles,
                        bytesProcessed = processedBytes,
                        percent = if (totalBytes > 0) (processedBytes.toFloat() / totalBytes) else (processedFiles.toFloat() / totalFiles.coerceAtLeast(1)),
                        speedBps = speedBps
                    )

                    if (entry.isDirectory) {
                        targetFs.mkdir(destinationPath)
                        processedFiles++
                        continue
                    }

                    // Check conflict
                    var shouldWrite = true
                    if (targetFs.exists(destinationPath)) {
                        val destStat = targetFs.stat(destinationPath)
                        val action = rememberConflictAction ?: askConflict(entry, destStat, targetDir)

                        when (action) {
                            ConflictAction.OVERWRITE -> {
                                targetFs.delete(destinationPath, recursive = false)
                                shouldWrite = true
                            }
                            ConflictAction.SKIP -> {
                                shouldWrite = false
                                processedBytes += entry.size
                                processedFiles++
                            }
                            ConflictAction.AUTO_RENAME -> {
                                destinationPath = generateUniquePath(destinationPath, targetFs)
                                shouldWrite = true
                            }
                            ConflictAction.ASK -> {
                                shouldWrite = true
                            }
                        }
                    }

                    if (shouldWrite) {
                        // Safe streaming copy via temporary file
                        val tempPath = destinationPath.parent?.resolve(".tmp_copy_${System.currentTimeMillis()}_${entry.name}")
                            ?: destinationPath.resolve(".tmp_part")

                        var inputStream: InputStream? = null
                        var outputStream: OutputStream? = null
                        var fileSuccess = false

                        try {
                            inputStream = sourceFs.openRead(entry.path)
                            outputStream = targetFs.openWrite(tempPath, append = false)
                            val buffer = ByteArray(64 * 1024)
                            var read: Int

                            while (inputStream.read(buffer).also { read = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                outputStream.write(buffer, 0, read)
                                processedBytes += read
                                bytesSinceLastCalc += read

                                val now = System.currentTimeMillis()
                                val elapsed = now - lastSpeedCalcTime
                                if (elapsed >= 500) {
                                    speedBps = (bytesSinceLastCalc * 1000) / elapsed
                                    lastSpeedCalcTime = now
                                    bytesSinceLastCalc = 0L

                                    _currentTask.value = _currentTask.value?.copy(
                                        bytesProcessed = processedBytes,
                                        percent = if (totalBytes > 0) (processedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f,
                                        speedBps = speedBps
                                    )
                                }
                            }
                            outputStream.flush()
                            fileSuccess = true
                        } finally {
                            try { inputStream?.close() } catch (_: Exception) {}
                            try { outputStream?.close() } catch (_: Exception) {}
                        }

                        if (fileSuccess) {
                            // Target written cleanly, rename temp to actual destination
                            targetFs.rename(tempPath, destinationPath)
                            processedFiles++

                            // If Move: delete source after successful verification
                            if (isMove) {
                                sourceFs.delete(entry.path, recursive = false)
                            }
                        } else {
                            // Clean up partial file on failure/cancellation
                            targetFs.delete(tempPath, recursive = false)
                        }
                    }
                }

                // If moving directories, clean up empty source directories in reverse order
                if (isMove) {
                    for (source in sources) {
                        if (source.isDirectory) {
                            sourceFs.delete(source.path, recursive = true)
                        }
                    }
                }

                _currentTask.value = _currentTask.value?.copy(
                    status = TaskStatus.COMPLETED,
                    percent = 1f,
                    currentFileName = "Completed",
                    bytesProcessed = totalBytes,
                    filesProcessed = totalFiles
                )
                onComplete(true)
            } catch (e: Exception) {
                _currentTask.value = _currentTask.value?.copy(
                    status = if (e is kotlinx.coroutines.CancellationException) TaskStatus.CANCELLED else TaskStatus.FAILED,
                    errorMessage = e.message
                )
                onComplete(false)
            }
        }
    }

    private suspend fun scanRecursive(
        entry: VEntry,
        relPrefix: String,
        fs: FileSystem,
        outList: MutableList<Pair<VEntry, String>>
    ) {
        val relPath = if (relPrefix.isEmpty()) entry.name else "$relPrefix/${entry.name}"
        outList.add(entry to relPath)

        if (entry.isDirectory) {
            val children = fs.list(entry.path)
            for (child in children) {
                scanRecursive(child, relPath, fs, outList)
            }
        }
    }

    private suspend fun askConflict(
        source: VEntry,
        dest: VEntry?,
        targetDir: VPath
    ): ConflictAction {
        val destinationEntry = dest ?: VEntry(
            name = source.name,
            path = targetDir.resolve(source.name),
            isDirectory = source.isDirectory
        )
        val conflict = FileConflict(
            source = source,
            destination = destinationEntry,
            targetDirectory = targetDir
        )

        val deferred = CompletableDeferred<Pair<ConflictAction, Boolean>>()
        conflictDeferred = deferred

        _currentTask.value = _currentTask.value?.copy(
            status = TaskStatus.PAUSED_CONFLICT,
            conflict = conflict
        )

        val (action, applyAll) = deferred.await()
        if (applyAll) {
            rememberConflictAction = action
        }
        _currentTask.value = _currentTask.value?.copy(
            status = TaskStatus.RUNNING,
            conflict = null
        )
        return action
    }

    private suspend fun generateUniquePath(path: VPath, fs: FileSystem): VPath {
        val parent = path.parent ?: VPath("/")
        val baseName = if (path.extension.isNotEmpty()) {
            path.name.substringBeforeLast(".")
        } else {
            path.name
        }
        val ext = if (path.extension.isNotEmpty()) ".${path.extension}" else ""

        var index = 1
        var candidate = parent.resolve("$baseName ($index)$ext")
        while (fs.exists(candidate)) {
            index++
            candidate = parent.resolve("$baseName ($index)$ext")
        }
        return candidate
    }

    fun clearFinishedTask() {
        if (_currentTask.value?.status == TaskStatus.COMPLETED ||
            _currentTask.value?.status == TaskStatus.FAILED ||
            _currentTask.value?.status == TaskStatus.CANCELLED
        ) {
            _currentTask.value = null
        }
    }
}
