package com.example.core.search

import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SearchResult(val entry: VEntry, val relativePath: String)

class SearchManager {

    /** Search by name recursively. */
    suspend fun searchByName(root: VPath, query: String, maxResults: Int = 500): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return@withContext emptyList()
            val results = mutableListOf<SearchResult>()
            val rootFile = File(root.normalized)
            if (!rootFile.exists() || !rootFile.isDirectory) return@withContext emptyList()

            rootFile.walkTopDown().forEach { file ->
                if (results.size >= maxResults) return@forEach
                if (file.name.lowercase().contains(q)) {
                    val rel = file.absolutePath.removePrefix(rootFile.absolutePath).trim('/')
                    results.add(
                        SearchResult(
                            entry = VEntry(
                                name = file.name,
                                path = VPath(file.absolutePath),
                                isDirectory = file.isDirectory,
                                size = if (file.isDirectory) 0L else file.length(),
                                lastModified = file.lastModified()
                            ),
                            relativePath = rel
                        )
                    )
                }
            }
            results
        }

    /** Search by file content (text files only). */
    suspend fun searchByContent(root: VPath, query: String, maxResults: Int = 100): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return@withContext emptyList()
            val results = mutableListOf<SearchResult>()
            val rootFile = File(root.normalized)
            if (!rootFile.exists() || !rootFile.isDirectory) return@withContext emptyList()

            rootFile.walkTopDown().forEach { file ->
                if (results.size >= maxResults) return@forEach
                if (!file.isFile || file.length() > 5 * 1024 * 1024) return@forEach
                // Skip binary files by extension
                val ext = file.extension.lowercase()
                if (ext in listOf("jpg", "png", "mp4", "mp3", "apk", "zip", "7z", "rar")) return@forEach
                try {
                    // Streaming read line-by-line to avoid OOM on large folders/files
                    var found = false
                    file.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (line.lowercase().contains(q)) {
                                found = true
                                break
                            }
                        }
                    }
                    if (found) {
                        val rel = file.absolutePath.removePrefix(rootFile.absolutePath).trim('/')
                        results.add(
                            SearchResult(
                                entry = VEntry(
                                    name = file.name,
                                    path = VPath(file.absolutePath),
                                    isDirectory = false,
                                    size = file.length(),
                                    lastModified = file.lastModified()
                                ),
                                relativePath = rel
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            results
        }
}
