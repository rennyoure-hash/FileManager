package com.example.core.fs

import com.example.core.root.RootManager
import java.io.InputStream
import java.io.OutputStream

class CompositeFileSystem(
    val localFs: LocalFileSystem,
    val safFs: SafFileSystem,
    val rootFs: RootFileSystem = RootFileSystem(localFs)
) : FileSystem {
    override val id: String = "composite"
    override val name: String = "Storage System"
    override val rootPath: VPath = localFs.rootPath
    override val capabilities: Set<Capability> = localFs.capabilities + safFs.capabilities

    fun getDelegate(path: VPath): FileSystem {
        return if (path.normalized.startsWith("saf:/") || path.rawPath.startsWith("saf:") || path.rawPath.startsWith("saf:/")) {
            safFs
        } else if (RootManager.isRootExplorerEnabledFlow.value) {
            rootFs
        } else {
            localFs
        }
    }

    override suspend fun list(path: VPath): List<VEntry> {
        return getDelegate(path).list(path)
    }

    override suspend fun stat(path: VPath): VEntry? {
        return getDelegate(path).stat(path)
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        return getDelegate(path).openRead(path, offset)
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        return getDelegate(path).openWrite(path, append)
    }

    override suspend fun mkdir(path: VPath): Boolean {
        return getDelegate(path).mkdir(path)
    }

    override suspend fun rename(from: VPath, to: VPath): Boolean {
        return getDelegate(from).rename(from, to)
    }

    override suspend fun delete(path: VPath, recursive: Boolean): Boolean {
        return getDelegate(path).delete(path, recursive)
    }

    override suspend fun exists(path: VPath): Boolean {
        return getDelegate(path).exists(path)
    }
}
