package com.example.core.fs

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.core.permission.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class SafFileSystem(private val context: Context) : FileSystem {
    override val id: String = "saf"
    override val name: String = "Storage Access Framework"
    override val rootPath: VPath = VPath("saf:/")

    override val capabilities: Set<Capability> = setOf(
        Capability.PERMISSIONS
    )

    private fun parseSafPath(path: VPath): Pair<Uri?, List<String>> {
        val clean = path.normalized.removePrefix("saf:/").removePrefix("saf:").trimStart('/')
        if (clean.isEmpty()) return null to emptyList()

        val parts = clean.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null to emptyList()

        val encodedTreeUri = parts[0]
        val treeUri = PermissionManager.decodeSafUri(encodedTreeUri)
        val relativeSegments = parts.drop(1)
        return treeUri to relativeSegments
    }

    private fun findDocument(path: VPath): DocumentFile? {
        val (treeUri, segments) = parseSafPath(path)
        if (treeUri == null) return null

        val rootDoc = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (_: Exception) {
            null
        } ?: return null

        var current: DocumentFile? = rootDoc
        for (seg in segments) {
            current = current?.findFile(seg) ?: return null
        }
        return current
    }

    override suspend fun list(path: VPath): List<VEntry> = withContext(Dispatchers.IO) {
        val (treeUri, segments) = parseSafPath(path)

        // If at root "saf:/", list all granted SAF folders
        if (treeUri == null) {
            val uris = PermissionManager.getPersistedSafUris(context)
            val list = mutableListOf<VEntry>()
            for (uri in uris) {
                val info = PermissionManager.getSafDirectoryInfo(context, uri)
                val folderPath = VPath("saf://${info.encodedPath}")
                list.add(
                    VEntry(
                        name = info.name,
                        path = folderPath,
                        isDirectory = true,
                        size = 0L,
                        lastModified = 0L,
                        canRead = info.canRead,
                        canWrite = info.canWrite,
                        mimeType = "vnd.android.document/directory"
                    )
                )
            }
            return@withContext list
        }

        val targetDoc = findDocument(path) ?: return@withContext emptyList()
        if (!targetDoc.isDirectory) return@withContext emptyList()

        val children = targetDoc.listFiles()
        val result = ArrayList<VEntry>(children.size)

        for (child in children) {
            val childName = child.name ?: "Unknown"
            val childPath = path.resolve(childName)
            val isDir = child.isDirectory
            val size = if (isDir) 0L else child.length()
            val lastMod = child.lastModified()
            val canRead = child.canRead()
            val canWrite = child.canWrite()
            val mimeType = child.type ?: (if (isDir) "vnd.android.document/directory" else getMimeType(childName))

            result.add(
                VEntry(
                    name = childName,
                    path = childPath,
                    isDirectory = isDir,
                    size = size,
                    lastModified = lastMod,
                    isHidden = childName.startsWith("."),
                    canRead = canRead,
                    canWrite = canWrite,
                    mimeType = mimeType
                )
            )
        }
        result
    }

    override suspend fun stat(path: VPath): VEntry? = withContext(Dispatchers.IO) {
        val (treeUri, segments) = parseSafPath(path)
        if (treeUri == null) {
            return@withContext VEntry(
                name = "SAF Storage",
                path = rootPath,
                isDirectory = true,
                canRead = true,
                canWrite = true,
                mimeType = "vnd.android.document/directory"
            )
        }

        val doc = findDocument(path) ?: return@withContext null
        val isDir = doc.isDirectory
        val name = doc.name ?: (if (segments.isEmpty()) PermissionManager.getSafDirectoryInfo(context, treeUri).name else path.name)
        VEntry(
            name = name,
            path = path,
            isDirectory = isDir,
            size = if (isDir) 0L else doc.length(),
            lastModified = doc.lastModified(),
            isHidden = name.startsWith("."),
            canRead = doc.canRead(),
            canWrite = doc.canWrite(),
            mimeType = doc.type ?: (if (isDir) "vnd.android.document/directory" else getMimeType(name))
        )
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        val doc = findDocument(path) ?: throw FileNotFoundException("SAF document not found: ${path.normalized}")
        val inputStream = context.contentResolver.openInputStream(doc.uri)
            ?: throw IOException("Cannot open input stream for: ${path.normalized}")

        if (offset > 0) {
            var skipped = 0L
            while (skipped < offset) {
                val n = inputStream.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
        }
        return inputStream
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        val existingDoc = findDocument(path)
        if (existingDoc != null) {
            val mode = if (append) "wa" else "wt"
            return context.contentResolver.openOutputStream(existingDoc.uri, mode)
                ?: throw IOException("Cannot open output stream for: ${path.normalized}")
        }

        // File does not exist, find parent directory and create it
        val parentPath = path.parent ?: throw IOException("No parent directory for: ${path.normalized}")
        val parentDoc = findDocument(parentPath) ?: throw FileNotFoundException("Parent SAF directory not found: ${parentPath.normalized}")

        val mimeType = getMimeType(path.name)
        val newDoc = parentDoc.createFile(mimeType, path.name)
            ?: throw IOException("Failed to create document: ${path.name}")

        return context.contentResolver.openOutputStream(newDoc.uri, "wt")
            ?: throw IOException("Cannot open output stream for created document: ${path.name}")
    }

    override suspend fun mkdir(path: VPath): Boolean = withContext(Dispatchers.IO) {
        val parentPath = path.parent ?: return@withContext false
        val parentDoc = findDocument(parentPath) ?: return@withContext false
        val newDir = parentDoc.createDirectory(path.name)
        newDir != null
    }

    override suspend fun rename(from: VPath, to: VPath): Boolean = withContext(Dispatchers.IO) {
        val doc = findDocument(from) ?: return@withContext false
        doc.renameTo(to.name)
    }

    override suspend fun delete(path: VPath, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val doc = findDocument(path) ?: return@withContext false
        doc.delete()
    }

    override suspend fun exists(path: VPath): Boolean = withContext(Dispatchers.IO) {
        findDocument(path) != null
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isNotEmpty()) {
            try {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                if (mime != null) return mime
            } catch (_: Throwable) {
            }
            when (extension) {
                "txt" -> return "text/plain"
                "json" -> return "application/json"
                "xml" -> return "text/xml"
                "html" -> return "text/html"
                "pdf" -> return "application/pdf"
                "zip" -> return "application/zip"
                "apk" -> return "application/vnd.android.package-archive"
            }
        }
        return "application/octet-stream"
    }
}
