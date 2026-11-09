package com.example.core.fs

import com.example.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class RootFileSystem(
    private val localFs: LocalFileSystem = LocalFileSystem(),
    override val id: String = "root",
    override val name: String = "Root System",
    override val rootPath: VPath = VPath("/")
) : FileSystem {

    override val capabilities: Set<Capability> = setOf(
        Capability.ATOMIC_RENAME,
        Capability.PERMISSIONS,
        Capability.RANDOM_ACCESS
    )

    override suspend fun list(path: VPath): List<VEntry> = withContext(Dispatchers.IO) {
        val dir = File(path.normalized)
        if (dir.exists() && dir.canRead()) {
            val localEntries = localFs.list(path)
            if (localEntries.isNotEmpty() || dir.listFiles() != null) {
                return@withContext localEntries
            }
        }

        val quoted = RootManager.shellQuote(path.normalized)
        val result = RootManager.executeCommand("ls -la $quoted")
        if (!result.isSuccess) {
            return@withContext localFs.list(path)
        }

        parseLsOutput(path, result.stdout)
    }

    internal fun parseLsOutput(parentPath: VPath, lines: List<String>): List<VEntry> {
        val entries = mutableListOf<VEntry>()
        val whitespaceRegex = Regex("\\s+")
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("total ")) continue

            // Standard `ls -la` format:
            // drwxr-xr-x  4 root root 4096 2025-01-01 12:00 dirname
            val parts = line.split(whitespaceRegex, limit = 8)
            if (parts.size < 8) continue

            val perms = parts[0]
            if (perms.length < 4) continue
            val isDir = perms.startsWith("d") || perms.startsWith("l")
            val size = parts[4].toLongOrNull() ?: 0L
            val rawName = parts[7]
            val name = if (perms.startsWith("l") && rawName.contains(" -> ")) {
                rawName.substringBefore(" -> ")
            } else {
                rawName
            }

            if (name == "." || name == "..") continue

            val entryPath = parentPath.resolve(name)
            entries.add(
                VEntry(
                    name = name,
                    path = entryPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else size,
                    lastModified = System.currentTimeMillis(),
                    isHidden = name.startsWith("."),
                    canRead = true,
                    canWrite = perms.getOrNull(2) == 'w',
                    mimeType = if (isDir) "inode/directory" else "application/octet-stream"
                )
            )
        }
        return entries
    }

    override suspend fun stat(path: VPath): VEntry? = withContext(Dispatchers.IO) {
        val file = File(path.normalized)
        if (file.exists() && file.canRead()) {
            localFs.stat(path)?.let { return@withContext it }
        }

        val quoted = RootManager.shellQuote(path.normalized)
        val result = RootManager.executeCommand("stat -c '%F|%s|%Y' $quoted")
        if (!result.isSuccess || result.stdout.isEmpty()) {
            return@withContext localFs.stat(path)
        }

        val line = result.stdout.first().trim()
        val tokens = line.split("|")
        val isDir = tokens.getOrNull(0)?.contains("directory", ignoreCase = true) == true
        val size = tokens.getOrNull(1)?.toLongOrNull() ?: 0L
        val mtimeSec = tokens.getOrNull(2)?.toLongOrNull() ?: 0L

        VEntry(
            name = path.name.ifEmpty { "/" },
            path = path,
            isDirectory = isDir,
            size = if (isDir) 0L else size,
            lastModified = mtimeSec * 1000L,
            isHidden = path.name.startsWith("."),
            canRead = true,
            canWrite = true,
            mimeType = if (isDir) "inode/directory" else "application/octet-stream"
        )
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        val file = File(path.normalized)
        if (file.exists() && file.canRead()) {
            return localFs.openRead(path, offset)
        }
        val quoted = RootManager.shellQuote(path.normalized)
        val cmd = if (offset > 0) {
            "tail -c +${offset + 1} $quoted"
        } else {
            "cat $quoted"
        }
        val res = runBlocking { RootManager.executeCommand(cmd) }
        if (!res.isSuccess) {
            throw IOException("Root read failed for ${path.normalized}: ${res.errorText}")
        }
        return ByteArrayInputStream(res.outputText.toByteArray(Charsets.UTF_8))
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        val file = File(path.normalized)
        val parent = file.parentFile
        if ((file.exists() && file.canWrite()) || (parent != null && parent.exists() && parent.canWrite())) {
            return localFs.openWrite(path, append)
        }

        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                val tempFile = File.createTempFile("mt_root_write_", ".tmp")
                try {
                    tempFile.writeBytes(toByteArray())
                    val srcQuoted = RootManager.shellQuote(tempFile.absolutePath)
                    val dstQuoted = RootManager.shellQuote(path.normalized)
                    val op = if (append) "cat $srcQuoted >> $dstQuoted" else "cp -f $srcQuoted $dstQuoted"
                    val res = runBlocking { RootManager.executeCommand(op) }
                    if (!res.isSuccess) {
                        throw IOException("Root write failed for ${path.normalized}: ${res.errorText}")
                    }
                } finally {
                    tempFile.delete()
                }
            }
        }
    }

    override suspend fun mkdir(path: VPath): Boolean = withContext(Dispatchers.IO) {
        if (localFs.mkdir(path)) return@withContext true
        val quoted = RootManager.shellQuote(path.normalized)
        RootManager.executeCommand("mkdir -p $quoted").isSuccess
    }

    override suspend fun rename(from: VPath, to: VPath): Boolean = withContext(Dispatchers.IO) {
        if (localFs.rename(from, to)) return@withContext true
        val srcQuoted = RootManager.shellQuote(from.normalized)
        val dstQuoted = RootManager.shellQuote(to.normalized)
        RootManager.executeCommand("mv -f $srcQuoted $dstQuoted").isSuccess
    }

    override suspend fun delete(path: VPath, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val file = File(path.normalized)
        if (file.exists() && file.canWrite() && localFs.delete(path, recursive)) {
            return@withContext true
        }
        val quoted = RootManager.shellQuote(path.normalized)
        val flag = if (recursive) "-rf" else "-f"
        RootManager.executeCommand("rm $flag $quoted").isSuccess
    }

    override suspend fun exists(path: VPath): Boolean = withContext(Dispatchers.IO) {
        if (localFs.exists(path)) return@withContext true
        val quoted = RootManager.shellQuote(path.normalized)
        RootManager.executeCommand("test -e $quoted").isSuccess
    }
}
