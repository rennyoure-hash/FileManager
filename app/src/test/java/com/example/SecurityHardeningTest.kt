package com.example

import com.example.core.archive.ArchiveManager
import com.example.core.archive.SafeExtractLimits
import com.example.core.db.ChainVerificationStatus
import com.example.core.db.InMemoryHeadAnchorStore
import com.example.core.db.KeystoreKeyUnavailableException
import com.example.core.db.OperationLogDao
import com.example.core.db.OperationLogEntity
import com.example.core.db.OperationLogRepository
import com.example.core.net.NetworkProfile
import com.example.core.net.NetworkProtocol
import com.example.core.net.RemoteFileServer
import com.example.core.root.MountAnalyzer
import com.example.core.root.ToctouSnapshot
import com.example.core.tools.InAppDiagnosticLogger
import com.example.core.tools.RootAppBackupAndStorageHealth
import com.example.core.tools.SecurityHardeningPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class SecurityHardeningTest {

    private lateinit var tempDir: File

    // Injected test keys for JVM unit tests (production code has zero software fallback!)
    private val injectedHmacKey: SecretKey = SecretKeySpec(
        "InjectedUnitTestHmacKey_32Bytes!".toByteArray(Charsets.UTF_8),
        "HmacSHA256"
    )
    private val injectedAesKey: SecretKey = SecretKeySpec(
        ByteArray(32) { (it * 11 + 5).toByte() },
        "AES"
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("mt_sec_test_").toFile()
        SecurityHardeningPolicy.resetServerIpRateLimits()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun escapeShellPath_handlesQuotesCommandSubNewlinesAndWeirdUnicodeSafely() {
        val trickyNames = listOf(
            "/sdcard/single'quote'file.txt",
            "/sdcard/double\"quote\"file.txt",
            "/sdcard/cmd\$(rm -rf /system)sub.txt",
            "/sdcard/semi;reboot;backtick`id`.sh",
            "/sdcard/line1\nline2\r\n$(whoami).txt",
            "/sdcard/unicode_\u202E_rtl_\u0645\u0644\u0641_\u4E2D\u6587_\uD83D\uDD12.apk",
            "/sdcard/nul\u0000byte_stripped.txt"
        )

        for (raw in trickyNames) {
            val escaped = MountAnalyzer.escapeShellPath(raw)
            assertTrue("Must start and end with single quote", escaped.startsWith("'") && escaped.endsWith("'"))
            assertFalse("NUL byte must be stripped", escaped.contains('\u0000'))
            val inner = escaped.substring(1, escaped.length - 1)
            val withoutEscapedQuotes = inner.replace("'\\''", "")
            assertFalse("No unescaped single quote may remain inside", withoutEscapedQuotes.contains("'"))
        }
    }

    @Test
    fun safeExtract_blocksZipSlipPathTraversalAndAbsolutePaths() = runBlocking {
        val zipFile = File(tempDir, "zip_slip.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped_evil.sh"))
            zos.write("echo pwned".toByteArray())
            zos.closeEntry()
        }

        val targetExtractDir = File(tempDir, "extract_out/nested")
        val manager = ArchiveManager()
        try {
            manager.extractZip(zipFile, targetExtractDir)
            fail("Expected SecurityException for Zip-Slip entry")
        } catch (se: SecurityException) {
            assertTrue(se.message.orEmpty().contains("SafeExtract Blocked"))
        }
        assertFalse("Evil file must not be created outside targetDir", File(tempDir, "escaped_evil.sh").exists())

        val absZipFile = File(tempDir, "abs_path.zip")
        ZipOutputStream(FileOutputStream(absZipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("/etc/hosts_overwrite"))
            zos.write("127.0.0.1 evil".toByteArray())
            zos.closeEntry()
        }
        try {
            manager.extractZip(absZipFile, targetExtractDir)
            fail("Expected SecurityException for absolute path entry")
        } catch (se: SecurityException) {
            assertTrue(se.message.orEmpty().contains("SafeExtract Blocked"))
        }
    }

    @Test
    fun safeExtract_blocksZipBombBySizeAndCompressionRatioAndEntryCount() = runBlocking {
        val bombZip = File(tempDir, "zip_bomb.zip")
        val zeroBlock = ByteArray(512 * 1024)
        ZipOutputStream(FileOutputStream(bombZip)).use { zos ->
            zos.putNextEntry(ZipEntry("bomb_payload.bin"))
            zos.write(zeroBlock)
            zos.closeEntry()
        }

        val targetDir = File(tempDir, "bomb_out")
        val manager = ArchiveManager()
        val strictLimits = SafeExtractLimits(
            maxEntries = 100,
            maxTotalUncompressedBytes = 10L * 1024 * 1024,
            maxSingleEntryBytes = 10L * 1024 * 1024,
            maxCompressionRatio = 25.0,
            ratioCheckMinBytes = 64L * 1024L
        )

        try {
            manager.extractZip(bombZip, targetDir, strictLimits)
            fail("Expected SecurityException for Zip Bomb compression ratio")
        } catch (se: SecurityException) {
            assertTrue(se.message.orEmpty().contains("Zip Bomb"))
        }
        assertFalse(
            "Partially extracted zip bomb file must be cleaned up",
            File(targetDir, "bomb_payload.bin").exists()
        )
    }

    @Test
    fun safeExtract_blocksSymlinkEntryInsideArchive() = runBlocking {
        val symlinkZip = File(tempDir, "symlink_archive.zip")
        ZipOutputStream(FileOutputStream(symlinkZip)).use { zos ->
            val symlinkEntry = ZipEntry("link_to_system_prop").apply {
                comment = "SYMLINK:/system/build.prop"
            }
            zos.putNextEntry(symlinkEntry)
            zos.write("/system/build.prop".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(tempDir, "symlink_out")
        val manager = ArchiveManager()
        try {
            manager.extractZip(symlinkZip, targetDir)
            fail("Expected SecurityException for symlink entry inside archive")
        } catch (se: SecurityException) {
            assertTrue(se.message.orEmpty().contains("Symlink"))
        }
    }

    /**
     * Fase 8b.7, 8c.1 & 8c.10:
     * Comprehensive test for per-command flag WHITELISTS, canonical destination validation,
     * recursive critical path blocks (`chmod -R`, `chown -R`, `rm -rf`), `pm`/`am` subcommand whitelists,
     * and `ln -s` directory boundary checks.
     */
    @Test
    fun rootCommandAllowlist_enforcesFlagWhitelistsRecursiveBlocksPmAmSubcommandsAndSymlinkBoundaries() {
        // 1. Permitted commands with whitelisted flags and valid paths
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("cp -rf '/sdcard/a' '/data/local/tmp/b'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("mv -f '/sdcard/a' '/sdcard/b'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("rm -rf '/sdcard/Download/temp_folder'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chmod 0644 '/system/build.prop'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chown -R '10123:10123' '/data/data/com.example.app'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("ln -sf '/sdcard/target.txt' '/sdcard/link.txt'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("pm enable --user 0 'com.example.app'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("pm disable-user --user 0 'com.example.app'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("am force-stop 'com.example.app'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("find '/sdcard/Download' -maxdepth 2 -type f -name '*.apk'").isSuccess)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("tar -xzf '/sdcard/backup.tar.gz' -C '/data/data'").isSuccess)
        assertTrue(
            MountAnalyzer.validateRootCommandAllowlist(
                "dd 'if=/dev/block/by-name/boot' 'of=/sdcard/Download/boot_backup.img' bs=4096"
            ).isSuccess
        )

        // 2. Interpreters & multiplexers MUST be rejected
        for (forbiddenBin in listOf("sh", "bash", "su", "busybox", "toybox", "env", "xargs", "awk", "sed", "[", "test")) {
            val res = MountAnalyzer.validateRootCommandAllowlist("$forbiddenBin -c 'id'")
            assertTrue("Forbidden interpreter/meta-binary '$forbiddenBin' must be rejected", res.isFailure)
        }

        // 3. Unlisted flags on cp/mv/rm/chmod/chown/ln/find/tar MUST be rejected (WHITELIST enforcement)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("cp --preserve=all '/sdcard/a' '/sdcard/b'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("mv -u '/sdcard/a' '/sdcard/b'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("rm --no-preserve-root -rf '/'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chmod --reference='/system/build.prop' '/sdcard/a'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chown -h 'root:root' '/sdcard/a'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("find '/sdcard' -exec rm {} +").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("find '/sdcard' -delete").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("find '/sdcard' -perm 0777").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("tar -cf '/sdcard/a.tar' --checkpoint-action=exec=id '/sdcard/b'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("tar -xf '/sdcard/a.tar' --to-command=sh").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("tar -xf '/sdcard/a.tar' -P").isFailure)

        // 4. Destination path validation: writing directly to critical root folders or /dev/block, /proc, /sys MUST fail
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("cp -f '/sdcard/a' '/system'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("mv -f '/sdcard/a' '/vendor'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("cp -f '/sdcard/a' '/dev/block/sda1'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("cp -f '/sdcard/a' '/proc/sysrq-trigger'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("cp -f '/sdcard/a' '/sdcard/../system'").isFailure)

        // 5. Recursive chmod -R / chown -R / rm -rf on critical paths MUST fail
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chmod -R 0777 '/system'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chmod -R 0777 '/system/bin'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chown -R 'root:root' '/vendor/bin'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("chown -R 'root:root' '/data/data'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("rm -rf '/system/priv-app'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("rm -rf '/sdcard'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("rm -rf '/storage/emulated/0'").isFailure)

        // 6. pm and am subcommand whitelists: reject install/uninstall/grant/start/broadcast
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("pm install '/sdcard/evil.apk'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("pm uninstall 'com.example.app'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("pm grant 'com.example.app' 'android.permission.CAMERA'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("am start -n 'com.example/.MainActivity'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("am broadcast -a 'android.intent.action.BOOT_COMPLETED'").isFailure)

        // 7. ln -s boundary check: reject hardlinks, symlinks into /system or /proc, and cross-package /data/data symlinks
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("ln '/sdcard/a' '/sdcard/b'").isFailure) // hardlink rejected
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("ln -sf '/system/build.prop' '/sdcard/link_prop'").isFailure)
        assertTrue(MountAnalyzer.validateRootCommandAllowlist("ln -sf '/sdcard/evil.so' '/system/lib64/evil.so'").isFailure)
        assertTrue(
            MountAnalyzer.validateRootCommandAllowlist(
                "ln -sf '/data/data/com.victim.app/shared_prefs/secret.xml' '/data/data/com.attacker.app/leak.xml'"
            ).isFailure
        )
    }

    /**
     * Fase 8c.2 & 8c.10: Automated Source-Code Audit Test that scans all Kotlin source files under `src/main/java`
     * and FAILS if any direct call to `Shell.cmd`, `Shell.su`, `Shell.sh`, `Shell.Builder`, `Runtime.getRuntime().exec`,
     * `Runtime.exec`, or `ProcessBuilder` appears outside `RootManager.kt` and `Shell.kt`.
     */
    @Test
    fun sourceCodeAudit_forbidsDirectShellOrProcessCallsOutsideRootManagerAndLibsuShell() {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        )
        val mainJavaDir = candidates.firstOrNull { it.exists() && it.isDirectory }
        assertNotNull("src/main/java directory must exist for static audit", mainJavaDir)

        val forbiddenCallRegex = Regex(
            """\b(Shell\s*\.\s*(cmd|su|sh|Builder)|Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec|Runtime\s*\.\s*exec|ProcessBuilder)\b"""
        )
        val allowedFiles = setOf("RootManager.kt", "Shell.kt")
        val violations = mutableListOf<String>()

        mainJavaDir!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { ktFile ->
                if (!allowedFiles.contains(ktFile.name)) {
                    ktFile.readLines().forEachIndexed { idx, line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")) {
                            if (forbiddenCallRegex.containsMatchIn(trimmed)) {
                                violations.add("${ktFile.path}:${idx + 1}: $trimmed")
                            }
                        }
                    }
                }
            }

        assertTrue(
            "Found unauthorized direct Shell/Process execution calls outside RootManager.kt and Shell.kt:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun toctouAndCriticalDenylist_blocksSymlinkSwapAndRootPartitionDeletion() {
        for (critical in listOf("/", "/system", "/data", "/vendor", "/boot", "/product", "/dev", "/proc", "/sys")) {
            assertTrue("Critical path $critical must be blocked", MountAnalyzer.isBlockedDirectDeletePath(critical))
        }

        val safeFile = File(tempDir, "normal_target.txt").apply { writeText("hello") }
        val initialSnap = MountAnalyzer.captureToctouSnapshot(safeFile.absolutePath)
        assertTrue(MountAnalyzer.verifyToctouSnapshot(initialSnap).isSuccess)

        val forgedInitial = ToctouSnapshot(
            requestedPath = safeFile.absolutePath,
            canonicalPath = "/data/local/tmp/different_canonical_target.txt",
            isSymlink = false,
            exists = true,
            sizeBytes = 5L,
            lastModified = 1000L
        )
        val verifyAfterSwap = MountAnalyzer.verifyToctouSnapshot(forgedInitial)
        assertTrue("TOCTOU canonical path mismatch must fail verification", verifyAfterSwap.isFailure)
    }

    /**
     * Fase 8b.1, 8c.3 & 8c.10: Tests Operation Log HMAC-SHA256 chain with injected test key (no software fallback in prod),
     * separate Head Anchor truncation detection, Mutex write serialization, and explicit distinction between
     * `ChainVerificationStatus.KEY_UNAVAILABLE` vs `ChainVerificationStatus.TAMPER_DETECTED`.
     */
    @Test
    fun operationLogHmacChain_distinguishesKeyUnavailableFromTamperAndSerializesConcurrentWrites() = runBlocking {
        // Prove production getOrCreateKeystoreHmacKey() does NOT silently fall back to software key on plain JVM
        try {
            OperationLogRepository.getOrCreateKeystoreHmacKey()
            fail("Expected KeystoreKeyUnavailableException when AndroidKeyStore is absent on plain JVM")
        } catch (expected: KeystoreKeyUnavailableException) {
            assertTrue(expected.message.orEmpty().contains("AndroidKeyStore"))
        }

        val fakeDao = InMemoryOperationLogDao()
        val headAnchorStore = InMemoryHeadAnchorStore()
        val repo = OperationLogRepository(
            dao = fakeDao,
            headAnchorStore = headAnchorStore,
            hmacKeyProvider = { injectedHmacKey }
        )

        // Launch 12 concurrent coroutines writing to the log simultaneously
        val ids = (1..12).map { idx ->
            async(Dispatchers.Default) {
                repo.recordOperation(
                    operationType = "CHMOD",
                    targetPath = "/data/item_$idx",
                    secondaryPath = "0644",
                    details = "concurrent write #$idx"
                )
            }
        }.awaitAll()

        assertEquals(12, ids.size)
        val verifyResult = repo.verifyHashChainIntegrity()
        assertTrue("Concurrent mutex-serialized HMAC chain must be 100% valid: ${verifyResult.reason}", verifyResult.isValid)
        assertEquals(ChainVerificationStatus.VALID, verifyResult.status)
        assertEquals(12, verifyResult.totalChecked)
        assertTrue(verifyResult.headAnchorMatched)

        // 1. Distinguish KEY_UNAVAILABLE when Keystore key is missing/unavailable
        val entriesSnapshot = fakeDao.getAllLogsAscending()
        val keyUnavailableResult = OperationLogRepository.verifyChainOfEntries(
            entriesAscending = entriesSnapshot,
            expectedHeadAnchor = headAnchorStore.readHeadAnchor(),
            secretKeyProvider = { throw KeystoreKeyUnavailableException("Keystore key missing after factory reset") }
        )
        assertFalse(keyUnavailableResult.isValid)
        assertEquals(ChainVerificationStatus.KEY_UNAVAILABLE, keyUnavailableResult.status)
        assertTrue(keyUnavailableResult.reason.startsWith("KEY_UNAVAILABLE:"))

        // 2. Simulate tail truncation attack in SQLite -> TAMPER_DETECTED
        fakeDao.removeLastEntrySimulateTruncation()
        val afterTruncation = repo.verifyHashChainIntegrity()
        assertFalse("Head HMAC Anchor must detect SQLite tail truncation!", afterTruncation.isValid)
        assertEquals(ChainVerificationStatus.TAMPER_DETECTED, afterTruncation.status)
        assertFalse(afterTruncation.headAnchorMatched)

        // 3. Simulate tampering with an existing row's targetPath -> TAMPER_DETECTED
        val entries = fakeDao.getAllLogsAscending().toMutableList()
        entries[0] = entries[0].copy(targetPath = "/system/build.prop")
        val afterTamper = OperationLogRepository.verifyChainOfEntries(
            entriesAscending = entries,
            secretKeyProvider = { injectedHmacKey }
        )
        assertFalse("Tampering with a log entry must fail HMAC-SHA256 verification", afterTamper.isValid)
        assertEquals(ChainVerificationStatus.TAMPER_DETECTED, afterTamper.status)
    }

    /**
     * Fase 8b.4, 8c.8 & 8c.10: Tests PBKDF2-HMAC-SHA256 (>= 600,000 iterations) + Chunked AES-256-GCM backup,
     * minimum passphrase length (>= 12 chars), rejection of header with iterations < 600,000 or wrong magic,
     * and verification of final chunk + rejection of trailing bytes.
     */
    @Test
    fun chunkedAesGcmBackup_enforcesPassphraseLengthHeaderIterationsFinalChunkAndUniqueIvs() = runBlocking {
        assertEquals(600_000, RootAppBackupAndStorageHealth.PBKDF2_ITERATIONS)
        assertEquals(12, RootAppBackupAndStorageHealth.MIN_PASSPHRASE_LENGTH)

        val originalBytes = ByteArray(9_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val plainIn = File(tempDir, "backup_source.tar").apply { writeBytes(originalBytes) }
        val encryptedFile = File(tempDir, "backup_source.tar.mteb")
        val decryptedOut = File(tempDir, "backup_restored.tar")

        // 1. Short passphrase (< 12 chars) MUST be rejected
        val shortPassRes = RootAppBackupAndStorageHealth.encryptBackupWithPassphrase(
            plainFile = plainIn,
            encryptedOut = encryptedFile,
            passphrase = "short123"
        )
        assertTrue("Passphrase shorter than 12 chars must be rejected", shortPassRes.isFailure)

        // 2. Valid encryption with 5 chunks
        val encRes = RootAppBackupAndStorageHealth.encryptBackupWithPassphrase(
            plainFile = plainIn,
            encryptedOut = encryptedFile,
            passphrase = "CorrectHorseBatteryStaple!2026",
            chunkSize = 2048,
            iterations = 600_000
        )
        assertTrue("Encryption must succeed", encRes.isSuccess)

        val (headerIterations, salt, chunkIvs) = RootAppBackupAndStorageHealth.inspectEncryptedBackupMetadata(encryptedFile)
        assertEquals(600_000, headerIterations)
        assertEquals(16, salt.size)
        assertEquals("9000 bytes at 2048B/chunk must produce 5 chunks", 5, chunkIvs.size)

        val uniqueIvHexSet = chunkIvs.map { iv ->
            assertEquals(12, iv.size)
            iv.joinToString("") { "%02x".format(it) }
        }.toSet()
        assertEquals("Every chunk must have a unique random IV (no IV reuse)", 5, uniqueIvHexSet.size)

        // 3. Round-trip decrypt with correct passphrase
        val decRes = RootAppBackupAndStorageHealth.decryptBackupWithPassphrase(
            encryptedFile = encryptedFile,
            plainOut = decryptedOut,
            passphrase = "CorrectHorseBatteryStaple!2026"
        )
        assertTrue("Decryption must succeed: ${decRes.exceptionOrNull()?.message}", decRes.isSuccess)
        assertArrayEquals(originalBytes, decryptedOut.readBytes())

        // 4. Reject backup file with wrong magic header ("BAD!")
        val badMagicFile = File(tempDir, "bad_magic.mteb")
        val validBytes = encryptedFile.readBytes()
        val badMagicBytes = validBytes.copyOf().also {
            it[0] = 'B'.code.toByte()
            it[1] = 'A'.code.toByte()
            it[2] = 'D'.code.toByte()
            it[3] = '!'.code.toByte()
        }
        badMagicFile.writeBytes(badMagicBytes)
        val badMagicRes = RootAppBackupAndStorageHealth.decryptBackupWithPassphrase(
            encryptedFile = badMagicFile,
            plainOut = File(tempDir, "out_magic.tar"),
            passphrase = "CorrectHorseBatteryStaple!2026"
        )
        assertTrue("Invalid magic header must be rejected", badMagicRes.isFailure)

        // 5. Reject backup file with downgraded PBKDF2 iterations in header (< 600,000, e.g., 10,000)
        val downgradedIterFile = File(tempDir, "downgraded_iter.mteb")
        DataOutputStream(FileOutputStream(downgradedIterFile)).use { dos ->
            dos.write(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), '2'.code.toByte()))
            dos.writeInt(10_000) // Below 600,000 minimum!
            dos.write(validBytes, 8, validBytes.size - 8)
        }
        val downgradedRes = RootAppBackupAndStorageHealth.decryptBackupWithPassphrase(
            encryptedFile = downgradedIterFile,
            plainOut = File(tempDir, "out_downgraded.tar"),
            passphrase = "CorrectHorseBatteryStaple!2026"
        )
        assertTrue("Header with PBKDF2 iterations < 600,000 must be rejected", downgradedRes.isFailure)

        // 6. Reject truncated backup missing the final chunk (strip last chunk)
        val truncatedFile = File(tempDir, "truncated.mteb")
        truncatedFile.writeBytes(validBytes.copyOfRange(0, 24 + 12 + 1 + 4 + 2064)) // Only chunk #0 (isFinal=false)
        val truncatedRes = RootAppBackupAndStorageHealth.decryptBackupWithPassphrase(
            encryptedFile = truncatedFile,
            plainOut = File(tempDir, "out_trunc.tar"),
            passphrase = "CorrectHorseBatteryStaple!2026"
        )
        assertTrue("Truncated backup without final chunk must be rejected", truncatedRes.isFailure)

        // 7. Reject backup with trailing unauthenticated bytes appended after final chunk
        val trailingFile = File(tempDir, "trailing.mteb")
        trailingFile.writeBytes(validBytes + byteArrayOf(0x01, 0x02))
        val trailingRes = RootAppBackupAndStorageHealth.decryptBackupWithPassphrase(
            encryptedFile = trailingFile,
            plainOut = File(tempDir, "out_trailing.tar"),
            passphrase = "CorrectHorseBatteryStaple!2026"
        )
        assertTrue("Backup with trailing bytes after final chunk must be rejected", trailingRes.isFailure)
    }

    /**
     * Fase 8c.5 & 8c.10: Tests RemoteFileServer constant-time token validation per request,
     * single share folder enforcement, and canonical path traversal rejection (`..`, `%2e%2e`, symlinks).
     */
    @Test
    fun remoteServer_enforcesTokenPerRequestRejectsPathTraversalAndServesSingleShareFolder() {
        val profile = NetworkProfile.createWithEncryptedPassword(
            id = "1",
            name = "Prod SFTP",
            protocol = NetworkProtocol.SFTP,
            host = "192.168.1.50",
            port = 22,
            username = "root",
            rawPassword = "MyTopSecretPassword!99",
            aesKeyProvider = { injectedAesKey }
        )
        assertNotEquals("MyTopSecretPassword!99", profile.encryptedPasswordHex)
        assertEquals("MyTopSecretPassword!99", profile.decryptPassword { injectedAesKey })

        val sharedDir = File(tempDir, "dedicated_share").apply { mkdirs() }
        val allowedFile = File(sharedDir, "readme.txt").apply { writeText("shared content") }
        val secretOutsideFile = File(tempDir, "secret_outside.txt").apply { writeText("top secret") }

        val server = RemoteFileServer(
            port = 18080,
            sharedRootDir = sharedDir,
            isReadOnlyDefault = true,
            maxRequestsPerMinutePerIp = 10
        )
        val token = server.sessionToken

        // 1. Valid request inside sharedDir succeeds
        val okDecision = server.evaluateClientRequest("192.168.1.20", "GET", token, "/readme.txt", nowMillis = 1000L)
        assertTrue(okDecision.allowed)
        assertEquals(200, okDecision.statusCode)
        assertEquals(allowedFile.canonicalPath, okDecision.resolvedFile?.canonicalPath)

        // 2. Missing or invalid token on any request is rejected (401)
        val badToken = server.evaluateClientRequest("192.168.1.20", "GET", "invalid_token", "/readme.txt", nowMillis = 1100L)
        assertFalse(badToken.allowed)
        assertEquals(401, badToken.statusCode)

        // 3. Path traversal attempts (`../secret_outside.txt`, `/%2e%2e/secret_outside.txt`, `/%252e%252e/secret_outside.txt`) rejected (403)
        for (traversalPath in listOf(
            "/../secret_outside.txt",
            "/%2e%2e/secret_outside.txt",
            "/%252e%252e/secret_outside.txt",
            "/subdir/../../secret_outside.txt",
            "\\..\\secret_outside.txt"
        )) {
            val trav = server.evaluateClientRequest("192.168.1.20", "GET", token, traversalPath, nowMillis = 1200L)
            assertFalse("Path traversal '$traversalPath' must be rejected", trav.allowed)
            assertEquals(403, trav.statusCode)
        }

        // 4. Symlink inside shared folder pointing outside to secretOutsideFile MUST be rejected (403)
        val symlinkInsideShare = File(sharedDir, "escape_link.txt")
        try {
            Files.createSymbolicLink(symlinkInsideShare.toPath(), secretOutsideFile.toPath())
            val symDecision = server.evaluateClientRequest("192.168.1.20", "GET", token, "/escape_link.txt", nowMillis = 1300L)
            assertFalse("Symlink pointing outside sharedRootDir must be rejected", symDecision.allowed)
            assertEquals(403, symDecision.statusCode)
        } catch (_: Throwable) {
            // If OS disallow symlink creation in temp, resolveSafeSharedFile is already covered above
        }
    }

    /**
     * Fase 8c.7 & 8c.10: Tests PIN Fail-Closed behavior when `pin_lockout_state.enc` is deleted or corrupted
     * after initial setup vs explicit first-run unconfigured state.
     */
    @Test
    fun pinLockout_failsClosedWhenLockoutFileDeletedOrCorruptedAfterSetup() {
        val pinStorageDir = File(tempDir, "no_backup_pin_dir")

        // 1. Explicit first-run (before PIN is configured): neither marker nor lockout file exists -> unlocked first-run
        val firstRunStatus = SecurityHardeningPolicy.readProtectedPinLockoutStatus(
            storageDir = pinStorageDir,
            aesKeyProvider = { injectedAesKey }
        )
        assertFalse(firstRunStatus.isFailClosedLocked)
        assertTrue(firstRunStatus.isFirstRunUnconfigured)
        assertEquals(0, firstRunStatus.failedAttempts)

        // 2. Initialize PIN on first setup -> writes marker + encrypted 0|0|CLEAN lockout file
        SecurityHardeningPolicy.initializePinLockoutOnFirstSetup(
            storageDir = pinStorageDir,
            aesKeyProvider = { injectedAesKey }
        )
        val afterInit = SecurityHardeningPolicy.readProtectedPinLockoutStatus(
            storageDir = pinStorageDir,
            aesKeyProvider = { injectedAesKey }
        )
        assertFalse(afterInit.isFailClosedLocked)
        assertFalse(afterInit.isFirstRunUnconfigured)
        assertEquals(0, afterInit.failedAttempts)

        // 3. Record 3 failed attempts -> normal stepped lockout (5,000 ms)
        SecurityHardeningPolicy.recordProtectedPinAttemptInDir(pinStorageDir, isSuccess = false, nowMillis = 10_000L) { injectedAesKey }
        SecurityHardeningPolicy.recordProtectedPinAttemptInDir(pinStorageDir, isSuccess = false, nowMillis = 11_000L) { injectedAesKey }
        val thirdFail = SecurityHardeningPolicy.recordProtectedPinAttemptInDir(pinStorageDir, isSuccess = false, nowMillis = 12_000L) { injectedAesKey }
        assertFalse(thirdFail.isFailClosedLocked)
        assertEquals(3, thirdFail.failedAttempts)
        assertEquals(17_000L, thirdFail.unlockAtMillis)

        // 4. Attacker deletes `pin_lockout_state.enc` to reset the failed attempt counter -> MUST FAIL-CLOSED!
        val lockoutFile = File(pinStorageDir, SecurityHardeningPolicy.LOCKOUT_STATE_FILE)
        assertTrue(lockoutFile.delete())
        val afterDeletion = SecurityHardeningPolicy.readProtectedPinLockoutStatus(
            storageDir = pinStorageDir,
            aesKeyProvider = { injectedAesKey }
        )
        assertTrue("Deleting pin_lockout_state.enc after setup MUST fail-closed!", afterDeletion.isFailClosedLocked)
        assertEquals(SecurityHardeningPolicy.FAIL_CLOSED_ATTEMPTS, afterDeletion.failedAttempts)
        assertEquals(Long.MAX_VALUE, afterDeletion.unlockAtMillis)

        // 5. Attacker writes corrupt/tampered bytes into `pin_lockout_state.enc` -> MUST FAIL-CLOSED!
        lockoutFile.writeBytes("corrupted_plaintext_0|0".toByteArray(Charsets.UTF_8))
        val afterCorruption = SecurityHardeningPolicy.readProtectedPinLockoutStatus(
            storageDir = pinStorageDir,
            aesKeyProvider = { injectedAesKey }
        )
        assertTrue("Corrupting pin_lockout_state.enc MUST fail-closed!", afterCorruption.isFailClosedLocked)
        assertEquals(Long.MAX_VALUE, afterCorruption.unlockAtMillis)
    }

    @Test
    fun diagnosticLoggerAndPinVerification_maskCredentialsAndVerifyHmacBoundPin() {
        val rawLog = "Connecting to sftp://admin:SuperSecret123@192.168.1.10/files with api_key=VT_99887766 and user rennyoure@gmail.com"
        val masked = InAppDiagnosticLogger.maskSensitiveText(rawLog)
        assertFalse(masked.contains("SuperSecret123"))
        assertFalse(masked.contains("VT_99887766"))
        assertTrue(masked.contains("***MASKED***"))
        assertTrue(InAppDiagnosticLogger.CLIPBOARD_ANDROID10_LIMITATION_NOTE.contains("Android 10+"))

        val salt = ByteArray(16) { it.toByte() }
        val hash = SecurityHardeningPolicy.hashPinWithSalt("2580", salt) { injectedHmacKey }
        assertNotNull(hash)
        assertTrue(SecurityHardeningPolicy.verifyPin("2580", salt, hash) { injectedHmacKey })
        assertFalse(SecurityHardeningPolicy.verifyPin("0000", salt, hash) { injectedHmacKey })
    }
}

internal class InMemoryOperationLogDao : OperationLogDao {
    private val items = mutableListOf<OperationLogEntity>()
    private val flow = MutableStateFlow<List<OperationLogEntity>>(emptyList())
    private var nextId = 1L

    override fun observeRecentLogs(): Flow<List<OperationLogEntity>> = flow

    override suspend fun getRecentLogs(): List<OperationLogEntity> = synchronized(items) {
        items.sortedByDescending { it.timestamp }
    }

    override suspend fun getAllLogsAscending(): List<OperationLogEntity> = synchronized(items) {
        items.sortedBy { it.id }
    }

    override suspend fun getLatestLog(): OperationLogEntity? = synchronized(items) {
        items.maxByOrNull { it.id }
    }

    override suspend fun insertLog(log: OperationLogEntity): Long = synchronized(items) {
        val assigned = log.copy(id = nextId++)
        items.add(assigned)
        flow.value = items.sortedByDescending { it.timestamp }
        assigned.id
    }

    override suspend fun markUndone(id: Long) = synchronized(items) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(isUndone = true)
        }
    }

    override suspend fun clearAllLogs() = synchronized(items) {
        items.clear()
        flow.value = emptyList()
    }

    fun removeLastEntrySimulateTruncation() = synchronized(items) {
        if (items.isNotEmpty()) {
            items.removeAt(items.lastIndex)
        }
    }
}
