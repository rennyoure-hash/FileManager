package com.example

import com.example.core.root.MountAnalyzer
import com.example.core.root.MountEntry
import com.example.core.root.PartitionBadge
import com.example.core.root.PreWriteAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootWritableTest {

    private val sampleProcMounts = """
        /dev/block/dm-0 / erofs ro,seclabel,relatime 0 0
        tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0
        devpts /dev/pts devpts rw,seclabel,relatime,mode=600,ptmxmode=000 0 0
        proc /proc proc rw,relatime,gid=3009,hidepid=invisible 0 0
        sysfs /sys sysfs rw,seclabel,relatime 0 0
        /dev/block/dm-1 /system_ext ext4 ro,seclabel,relatime 0 0
        /dev/block/dm-2 /vendor squashfs ro,seclabel,relatime 0 0
        /dev/block/dm-3 /product ext4 ro,seclabel,relatime 0 0
        /dev/block/by-name/userdata /data f2fs rw,lazytime,seclabel,nosuid,nodev,noatime 0 0
        /data/media /storage/emulated fuse rw,nosuid,nodev,noexec,noatime 0 0
        /dev/fuse /mnt/SD\040Card fuse rw,nosuid,nodev 0 0
    """.trimIndent()

    @Test
    fun parseProcMounts_extractsMountPointFsTypeAndOptionsCorrectly() {
        val mounts = MountAnalyzer.parseProcMounts(sampleProcMounts)
        assertEquals(11, mounts.size)

        val rootMount = mounts.first { it.mountPoint == "/" }
        assertEquals("/dev/block/dm-0", rootMount.device)
        assertEquals("erofs", rootMount.fsType)
        assertTrue(rootMount.isRo)
        assertFalse(rootMount.isRw)
        assertTrue(rootMount.isPermanentReadOnly)
        assertEquals(PartitionBadge.RO_PERMANENT, rootMount.badge)

        val dataMount = mounts.first { it.mountPoint == "/data" }
        assertEquals("f2fs", dataMount.fsType)
        assertTrue(dataMount.isRw)
        assertFalse(dataMount.isRo)
        assertFalse(dataMount.isPermanentReadOnly)
        assertEquals(PartitionBadge.RW, dataMount.badge)

        val productMount = mounts.first { it.mountPoint == "/product" }
        assertEquals("ext4", productMount.fsType)
        assertTrue(productMount.isRo)
        assertFalse(productMount.isRw)
        assertFalse(productMount.isPermanentReadOnly)
        assertEquals(PartitionBadge.RO, productMount.badge)

        // Verify octal \040 space unescaping
        val sdCardMount = mounts.firstOrNull { it.mountPoint == "/mnt/SD Card" }
        assertNotNull(sdCardMount)
        assertEquals("fuse", sdCardMount?.fsType)
    }

    @Test
    fun findMountForPath_usesLongestPrefixMatchAtPathBoundaries() {
        val mounts = MountAnalyzer.parseProcMounts(sampleProcMounts) + listOf(
            MountEntry(
                device = "/dev/block/dm-5",
                mountPoint = "/system",
                fsType = "ext4",
                options = listOf("ro", "relatime")
            )
        )

        // /system/build.prop -> /system
        val sysMatch = MountAnalyzer.findMountForPath("/system/build.prop", mounts)
        assertNotNull(sysMatch)
        assertEquals("/system", sysMatch?.mountPoint)

        // /system_ext/priv-app/SystemUI.apk -> /system_ext (must NOT match /system!)
        val sysExtMatch = MountAnalyzer.findMountForPath("/system_ext/priv-app/SystemUI.apk", mounts)
        assertNotNull(sysExtMatch)
        assertEquals("/system_ext", sysExtMatch?.mountPoint)

        // /data/local/tmp/test.sh -> /data
        val dataTmpMatch = MountAnalyzer.findMountForPath("/data/local/tmp/test.sh", mounts)
        assertNotNull(dataTmpMatch)
        assertEquals("/data", dataTmpMatch?.mountPoint)
        assertTrue(dataTmpMatch?.isRw == true)

        // /dev/pts/0 -> /dev/pts (longer than /dev)
        val devPtsMatch = MountAnalyzer.findMountForPath("/dev/pts/0", mounts)
        assertNotNull(devPtsMatch)
        assertEquals("/dev/pts", devPtsMatch?.mountPoint)

        // /acct/uid_0 -> falls back to /
        val fallbackRoot = MountAnalyzer.findMountForPath("/acct/uid_0", mounts)
        assertNotNull(fallbackRoot)
        assertEquals("/", fallbackRoot?.mountPoint)

        // Empty list returns null
        assertNull(MountAnalyzer.findMountForPath("/system", emptyList()))
    }

    @Test
    fun escapeShellPath_preventsCommandInjectionWithSpecialChars() {
        // 1. Path with spaces
        val pathWithSpaces = "/sdcard/My Documents/file name with spaces.txt"
        assertEquals(
            "'/sdcard/My Documents/file name with spaces.txt'",
            MountAnalyzer.escapeShellPath(pathWithSpaces)
        )

        // 2. Path with single quotes (') -> replaced with '\''
        val pathWithSingleQuote = "/sdcard/ O'Reilly's Book.pdf"
        assertEquals(
            "'/sdcard/ O'\\''Reilly'\\''s Book.pdf'",
            MountAnalyzer.escapeShellPath(pathWithSingleQuote)
        )

        // 3. Path with dollar variable expansion ($), semicolon (;), and backticks (`)
        val injectionAttempt = "/data/local/tmp/bad\$PATH;rm -rf /;`id`\"$(whoami)\""
        val escaped = MountAnalyzer.escapeShellPath(injectionAttempt)
        assertEquals(
            "'/data/local/tmp/bad\$PATH;rm -rf /;`id`\"$(whoami)\"'",
            escaped
        )

        // 4. Combined single quote + semicolon + backtick + dollar
        val combinedPayload = "/sdcard/test'; touch /data/pwned; `reboot` \$HOME '"
        val escapedCombined = MountAnalyzer.escapeShellPath(combinedPayload)
        assertEquals(
            "'/sdcard/test'\\''; touch /data/pwned; `reboot` \$HOME '\\'''",
            escapedCombined
        )
    }

    @Test
    fun evaluatePreWriteDecision_distinguishesCompressedRoFromRemountableAndAlreadyRw() {
        // 1. Compressed filesystems: erofs, squashfs, iso9660 -> NEVER remount
        for (compressedFs in listOf("erofs", "squashfs", "iso9660", "EROFS")) {
            val entry = MountEntry(
                device = "/dev/block/dm-0",
                mountPoint = "/system",
                fsType = compressedFs,
                options = listOf("ro")
            )
            val decision = MountAnalyzer.evaluatePreWriteDecision(entry)
            assertEquals(PreWriteAction.BLOCK_PERMANENT_RO, decision.action)
            assertEquals(
                "Partisi ini read-only permanen (filesystem terkompresi)",
                decision.message
            )
        }

        // 2. Already RW filesystem (f2fs / ext4) -> proceed directly
        val rwEntry = MountEntry(
            device = "/dev/block/userdata",
            mountPoint = "/data",
            fsType = "f2fs",
            options = listOf("rw", "noatime")
        )
        val rwDecision = MountAnalyzer.evaluatePreWriteDecision(rwEntry)
        assertEquals(PreWriteAction.PROCEED_ALREADY_RW, rwDecision.action)

        // 3. Read-only ext4 / f2fs -> requires remount rw
        for (remountableFs in listOf("ext4", "f2fs")) {
            val roEntry = MountEntry(
                device = "/dev/block/dm-1",
                mountPoint = "/system",
                fsType = remountableFs,
                options = listOf("ro", "relatime")
            )
            val roDecision = MountAnalyzer.evaluatePreWriteDecision(roEntry)
            assertEquals(PreWriteAction.REMOUNT_RW_REQUIRED, roDecision.action)
        }
    }

    @Test
    fun safetyGuards_blocksDirectRootDeletionAndDetectsSensitivePaths() {
        // Blocked direct delete targets
        assertTrue(MountAnalyzer.isBlockedDirectDeletePath("/"))
        assertTrue(MountAnalyzer.isBlockedDirectDeletePath("/system"))
        assertTrue(MountAnalyzer.isBlockedDirectDeletePath("/system/"))
        assertTrue(MountAnalyzer.isBlockedDirectDeletePath("/data"))
        assertTrue(MountAnalyzer.isBlockedDirectDeletePath("/vendor"))

        // Allowed item-by-item deletions inside partitions
        assertFalse(MountAnalyzer.isBlockedDirectDeletePath("/data/local/tmp/test.txt"))
        assertFalse(MountAnalyzer.isBlockedDirectDeletePath("/system/etc/hosts"))
        assertFalse(MountAnalyzer.isBlockedDirectDeletePath("/sdcard/Download/file.zip"))

        // Sensitive write confirmation paths
        val ownPkg = "com.aistudio.mtexplorer"
        assertTrue(MountAnalyzer.isSensitiveWritePath("/system/build.prop", ownPkg))
        assertTrue(MountAnalyzer.isSensitiveWritePath("/vendor/etc/fstab", ownPkg))
        assertTrue(MountAnalyzer.isSensitiveWritePath("/product/app/Test", ownPkg))
        assertTrue(MountAnalyzer.isSensitiveWritePath("/boot/ramdisk", ownPkg))
        assertTrue(MountAnalyzer.isSensitiveWritePath("/dev/block/sda1", ownPkg))
        assertTrue(MountAnalyzer.isSensitiveWritePath("/data/data/com.whatsapp/shared_prefs", ownPkg))
        assertFalse(MountAnalyzer.isSensitiveWritePath("/data/data/$ownPkg/files", ownPkg))
        assertFalse(MountAnalyzer.isSensitiveWritePath("/data/local/tmp/script.sh", ownPkg))
    }
}
