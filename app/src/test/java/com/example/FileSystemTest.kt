package com.example

import com.example.core.fs.LocalFileSystem
import com.example.core.fs.VPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSystemTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVPathOperations() {
        val path = VPath("/storage/emulated/0/Download/test.zip")
        assertEquals("test.zip", path.name)
        assertEquals("zip", path.extension)
        assertEquals("/storage/emulated/0/Download", path.parent?.normalized)

        val resolved = path.parent!!.resolve("subfolder")
        assertEquals("/storage/emulated/0/Download/subfolder", resolved.normalized)

        val root = VPath("/")
        assertEquals(null, root.parent)
        assertTrue(root.isRoot)
        assertEquals(listOf("storage", "emulated", "0", "Download", "test.zip"), path.segments)
    }

    @Test
    fun testLocalFileSystemOperations() = runBlocking {
        val rootDir = tempFolder.newFolder("fs_test")
        val fs = LocalFileSystem(rootPath = VPath(rootDir.absolutePath))

        // Create directory
        val subDir = VPath(File(rootDir, "docs").absolutePath)
        assertTrue(fs.mkdir(subDir))
        assertTrue(fs.exists(subDir))

        // Create file
        val file1 = subDir.resolve("hello.txt")
        fs.openWrite(file1).use {
            it.write("Hello World".toByteArray())
        }
        assertTrue(fs.exists(file1))

        // Stat file
        val stat = fs.stat(file1)
        assertNotNull(stat)
        assertEquals("hello.txt", stat!!.name)
        assertEquals(11L, stat.size)
        assertFalse(stat.isDirectory)

        // Read file
        val content = fs.openRead(file1).bufferedReader().readText()
        assertEquals("Hello World", content)

        // List directory
        val list = fs.list(subDir)
        assertEquals(1, list.size)
        assertEquals("hello.txt", list[0].name)

        // Rename file
        val file2 = subDir.resolve("renamed.txt")
        assertTrue(fs.rename(file1, file2))
        assertFalse(fs.exists(file1))
        assertTrue(fs.exists(file2))

        // Delete directory
        assertTrue(fs.delete(subDir, recursive = true))
        assertFalse(fs.exists(subDir))
    }
}
