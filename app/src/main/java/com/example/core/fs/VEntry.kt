package com.example.core.fs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EntryType {
    FOLDER,
    ARCHIVE,
    IMAGE,
    VIDEO,
    AUDIO,
    CODE,
    APK,
    DOCUMENT,
    GENERIC_FILE
}

data class VEntry(
    val name: String,
    val path: VPath,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val isHidden: Boolean = name.startsWith("."),
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    val mimeType: String = "application/octet-stream"
) {
    val extension: String
        get() = path.extension

    val entryType: EntryType
        get() {
            if (isDirectory) return EntryType.FOLDER
            val ext = extension.lowercase()
            return when (ext) {
                "zip", "7z", "tar", "gz", "bz2", "xz", "rar", "zst" -> EntryType.ARCHIVE
                "apk", "apks", "xapk", "apkm" -> EntryType.APK
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic" -> EntryType.IMAGE
                "mp4", "mkv", "avi", "mov", "webm", "flv", "3gp", "ts" -> EntryType.VIDEO
                "mp3", "m4a", "flac", "ogg", "wav", "aac", "opus" -> EntryType.AUDIO
                "kt", "java", "xml", "json", "html", "css", "js", "ts", "py", "sh", "c", "cpp", "h", "gradle", "properties", "md", "txt", "yml", "yaml" -> EntryType.CODE
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub" -> EntryType.DOCUMENT
                else -> EntryType.GENERIC_FILE
            }
        }

    val formattedSize: String
        get() {
            if (isDirectory) return ""
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            if (digitGroups >= units.size) digitGroups = units.size - 1
            val value = size / Math.pow(1024.0, digitGroups.toDouble())
            return if (digitGroups == 0) {
                "${size.toLong()} B"
            } else {
                String.format(Locale.US, "%.1f %s", value, units[digitGroups])
            }
        }

    val formattedDate: String
        get() {
            if (lastModified <= 0) return ""
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(lastModified))
        }
}
