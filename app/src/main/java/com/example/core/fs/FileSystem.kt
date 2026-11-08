package com.example.core.fs

import java.io.InputStream
import java.io.OutputStream

interface FileSystem {
    val id: String
    val name: String
    val rootPath: VPath
    val capabilities: Set<Capability>

    suspend fun list(path: VPath): List<VEntry>
    suspend fun stat(path: VPath): VEntry?
    fun openRead(path: VPath, offset: Long = 0L): InputStream
    fun openWrite(path: VPath, append: Boolean = false): OutputStream
    suspend fun mkdir(path: VPath): Boolean
    suspend fun rename(from: VPath, to: VPath): Boolean
    suspend fun delete(path: VPath, recursive: Boolean = true): Boolean
    suspend fun exists(path: VPath): Boolean
}
