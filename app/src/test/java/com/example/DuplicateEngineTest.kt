package com.example

import com.example.core.tools.DuplicateKeepPolicy
import com.example.core.tools.DuplicateScanConfig
import com.example.core.tools.ScannedCandidateFile
import com.example.core.tools.SmartDuplicateEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DuplicateEngineTest {

    private lateinit var tempWorkspace: File
    private lateinit var trashDir: File

    @Before
    fun setUp() {
        tempWorkspace = Files.createTempDirectory("mt_dup_test_").toFile()
        trashDir = File(tempWorkspace, "dup_trash").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempWorkspace.deleteRecursively()
    }

    private fun createTestFile(
        relativePath: String,
        content: ByteArray,
        mtime: Long = System.currentTimeMillis()
    ): File {
        val file = File(tempWorkspace, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
        file.setLastModified(mtime)
        return file
    }

    @Test
    fun keepPolicy_selectsExpectedWinnerForAllFourPolicies() {
        val c1 = ScannedCandidateFile(
            file = File("/storage/emulated/0/Download/very_long_copy_name.bin"),
            path = "/storage/emulated/0/Download/very_long_copy_name.bin",
            sizeBytes = 4096L,
            lastModified = 1000L, // Oldest
            inodeKey = "1:101"
        )
        val c2 = ScannedCandidateFile(
            file = File("/storage/emulated/0/DCIM/Camera/medium_name.bin"),
            path = "/storage/emulated/0/DCIM/Camera/medium_name.bin",
            sizeBytes = 4096L,
            lastModified = 5000L, // Newest
            inodeKey = "1:102"
        )
        val c3 = ScannedCandidateFile(
            file = File("/storage/emulated/0/Documents/a.bin"),
            path = "/storage/emulated/0/Documents/a.bin",
            sizeBytes = 4096L,
            lastModified = 3000L, // Shortest name ("a.bin")
            inodeKey = "1:103"
        )
        val candidates = listOf(c1, c2, c3)

        // 1. OLDEST -> c1 (mtime = 1000L)
        val (keepOldest, delOldest) = SmartDuplicateEngine.applyKeepPolicy(candidates, DuplicateKeepPolicy.OLDEST)
        assertEquals(c1.path, keepOldest.path)
        assertEquals(2, delOldest.size)

        // 2. NEWEST -> c2 (mtime = 5000L)
        val (keepNewest, delNewest) = SmartDuplicateEngine.applyKeepPolicy(candidates, DuplicateKeepPolicy.NEWEST)
        assertEquals(c2.path, keepNewest.path)
        assertEquals(2, delNewest.size)

        // 3. PRIORITY_FOLDER -> DCIM/Camera has priority over Documents and Download
        val (keepPriority, delPriority) = SmartDuplicateEngine.applyKeepPolicy(
            candidates = candidates,
            policy = DuplicateKeepPolicy.PRIORITY_FOLDER,
            priorityFolders = listOf("/storage/emulated/0/DCIM/Camera", "/storage/emulated/0/Documents")
        )
        assertEquals(c2.path, keepPriority.path)
        assertEquals(2, delPriority.size)

        // 4. SHORTEST_NAME -> c3 ("a.bin")
        val (keepShortest, delShortest) = SmartDuplicateEngine.applyKeepPolicy(candidates, DuplicateKeepPolicy.SHORTEST_NAME)
        assertEquals(c3.path, keepShortest.path)
        assertEquals(2, delShortest.size)
    }

    @Test
    fun mandatoryInvariant_alwaysRetainsAtLeastOneCopyPerGroupEvenWhenAllSelectedForDelete() = runBlocking {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        val scanRoot = File(tempWorkspace, "scan_inv")
        val f1 = createTestFile("scan_inv/dirA/photo1.jpg", payload, mtime = 10_000L)
        val f2 = createTestFile("scan_inv/dirB/photo2.jpg", payload, mtime = 20_000L)
        val f3 = createTestFile("scan_inv/dirC/photo3.jpg", payload, mtime = 30_000L)

        val engine = SmartDuplicateEngine(trashDir)
        val groups = engine.scanDuplicates(
            DuplicateScanConfig(
                rootDirectories = listOf(scanRoot),
                minSizeBytes = 1024L,
                keepPolicy = DuplicateKeepPolicy.OLDEST
            )
        )
        assertEquals(1, groups.size)
        val group = groups.first()

        // Attempt to select ALL 3 files in the group for deletion
        val allPaths = setOf(f1.absolutePath, f2.absolutePath, f3.absolutePath)
        val enforcedDeleteList = SmartDuplicateEngine.enforceMinimumOneCopyInvariant(group, allPaths)

        // Invariant: at most 2 can be deleted, at least 1 MUST remain
        assertEquals(2, enforcedDeleteList.size)
        assertFalse(enforcedDeleteList.any { it.path == group.recommendedKeepFile.path })

        // Execute cleanup with allPaths requested -> f1 (recommendedKeepFile) MUST still exist on disk!
        val report = engine.executeDuplicateCleanup(
            groups = groups,
            customSelectedDeletePaths = allPaths,
            permanentDelete = false
        )
        assertEquals(2, report.movedToRecycleBinCount)
        assertTrue("Invariant: kept copy must still exist on disk", f1.exists())
        assertFalse(f2.exists())
        assertFalse(f3.exists())
    }

    @Test
    fun hardlinkAndSymlinkAndSystemPaths_areIgnoredDuringScan() = runBlocking {
        val payload = ByteArray(4096) { 0x42 }
        val scanRoot = File(tempWorkspace, "scan_links")
        val realFile1 = createTestFile("scan_links/normal1.dat", payload, mtime = 1000L)
        val hardlinkToFile1 = createTestFile("scan_links/hardlink_same_inode.dat", payload, mtime = 1000L)
        val symlinkFile = createTestFile("scan_links/symlink_file.dat", payload, mtime = 1000L)
        val zeroByte1 = createTestFile("scan_links/empty1.txt", ByteArray(0))
        val zeroByte2 = createTestFile("scan_links/empty2.txt", ByteArray(0))
        val androidDataFile = createTestFile("scan_links/Android/data/com.app/cache.dat", payload)

        // Simulate realFile1 and hardlinkToFile1 sharing inode "99:777", and symlinkFile being a symlink
        val fakeInodes = mapOf(
            realFile1.absolutePath to "99:777",
            hardlinkToFile1.absolutePath to "99:777"
        )
        val engine = SmartDuplicateEngine(
            duplicateRecycleBinDir = trashDir,
            inodeExtractor = { file -> fakeInodes[file.absolutePath] ?: "99:${file.absolutePath.hashCode()}" },
            symlinkDetector = { file -> file.absolutePath == symlinkFile.absolutePath }
        )

        val groups = engine.scanDuplicates(
            DuplicateScanConfig(
                rootDirectories = listOf(scanRoot),
                minSizeBytes = 1024L
            )
        )

        // Because hardlink shares inode with realFile1, symlink is ignored, 0-byte is ignored,
        // and Android/data is protected -> only 1 eligible copy of normal1.dat exists, so 0 duplicate groups!
        assertTrue("Hardlinks, symlinks, 0-byte, and Android/data must be ignored", groups.isEmpty())
        assertTrue(zeroByte1.exists() && zeroByte2.exists() && androidDataFile.exists())
    }

    @Test
    fun fileModifiedBetweenScanAndDelete_isDetectedAndSafelySkipped() = runBlocking {
        val payload = ByteArray(6000) { (it % 127).toByte() }
        val scanRoot = File(tempWorkspace, "scan_mod")
        val keptFile = createTestFile("scan_mod/keep_original.bin", payload, mtime = 10_000L)
        val dupModifiedLater = createTestFile("scan_mod/dup_modified.bin", payload, mtime = 20_000L)
        val dupUnchanged = createTestFile("scan_mod/dup_unchanged.bin", payload, mtime = 30_000L)

        val engine = SmartDuplicateEngine(trashDir)
        val groups = engine.scanDuplicates(
            DuplicateScanConfig(
                rootDirectories = listOf(scanRoot),
                minSizeBytes = 1024L,
                keepPolicy = DuplicateKeepPolicy.OLDEST
            )
        )
        assertEquals(1, groups.size)

        // Simulate user or another app modifying dupModifiedLater AFTER scan completes but BEFORE delete runs
        val newContent = payload.clone().also { it[0] = 0x7F }
        dupModifiedLater.writeBytes(newContent)
        dupModifiedLater.setLastModified(99_000L)

        val report = engine.executeDuplicateCleanup(groups = groups, permanentDelete = false)

        // dupUnchanged is moved to recycle bin, while dupModifiedLater is safely skipped!
        assertEquals(1, report.movedToRecycleBinCount)
        assertEquals(listOf(dupModifiedLater.absolutePath), report.skippedModifiedFiles)
        assertTrue("Kept file must still exist", keptFile.exists())
        assertTrue("Modified file must NOT be deleted", dupModifiedLater.exists())
        assertFalse("Unchanged duplicate was moved to recycle bin", dupUnchanged.exists())

        // Verify Undo / Restore restores dupUnchanged back to its original path
        val restoredCount = engine.restoreFromRecycleBin(report.trashedEntries)
        assertEquals(1, restoredCount)
        assertTrue("Restored duplicate must exist again at original path", dupUnchanged.exists())
    }
}
