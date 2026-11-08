package com.example.core.tasks

import com.example.core.fs.VEntry
import com.example.core.fs.VPath

enum class TaskType {
    COPY,
    MOVE,
    DELETE
}

enum class TaskStatus {
    IDLE,
    SCANNING,
    RUNNING,
    PAUSED_CONFLICT,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ConflictAction {
    ASK,
    OVERWRITE,
    SKIP,
    AUTO_RENAME
}

data class FileConflict(
    val source: VEntry,
    val destination: VEntry,
    val targetDirectory: VPath
)

data class TaskProgress(
    val taskId: String,
    val title: String,
    val type: TaskType,
    val status: TaskStatus = TaskStatus.IDLE,
    val currentFileName: String = "",
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val filesProcessed: Int = 0,
    val totalFiles: Int = 0,
    val percent: Float = 0f,
    val speedBps: Long = 0L,
    val conflict: FileConflict? = null,
    val errorMessage: String? = null
)
