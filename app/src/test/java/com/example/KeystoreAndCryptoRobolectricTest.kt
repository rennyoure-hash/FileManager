package com.example

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.db.ChainVerificationStatus
import com.example.core.db.KeystoreEncryptedHeadAnchorStore
import com.example.core.db.OperationLogRepository
import com.example.core.tools.BiometricVaultAuthenticator
import com.example.core.tools.RootAppBackupAndStorageHealth
import com.example.core.tools.SecurityHardeningPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import javax.crypto.spec.SecretKeySpec

/**
 * Fase 8c.3 & 8c.4: Android Context / Robolectric test suite covering:
 * - Operation Log HMAC-SHA256 + Encrypted Head Anchor file in `context.noBackupFilesDir`
 * - PIN-HMAC & Fail-Closed Lockout state in `context.noBackupFilesDir`
 * - Chunked AES-256-GCM Backup & Restore in app storage
 * - `BiometricVaultAuthenticator.checkDeviceSecurity` handling of devices with and without a lock screen (`KeyguardManager.isDeviceSecure`).
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreAndCryptoRobolectricTest {

    private val testHmacKey = SecretKeySpec(
        "Robolectric_Injected_HmacKey_256".toByteArray(Charsets.UTF_8),
        "HmacSHA256"
    )
    private val testAesKey = SecretKeySpec(
        ByteArray(32) { (it * 17 + 3).toByte() },
        "AES"
    )

    @Test
    fun robolectric_logHmacEncryptedHeadAnchorPinLockoutAndBackupRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val anchorFile = File(context.noBackupFilesDir, "robo_oplog_head_anchor.mac")
        anchorFile.delete()

        val anchorStore = KeystoreEncryptedHeadAnchorStore(
            anchorFile = anchorFile,
            aesKeyProvider = { testAesKey }
        )
        val dao = InMemoryOperationLogDao()
        val repo = OperationLogRepository(
            dao = dao,
            headAnchorStore = anchorStore,
            hmacKeyProvider = { testHmacKey }
        )

        repo.recordOperation("REMOUNT", "/system", details = "remount rw")
        repo.recordOperation("CHMOD", "/system/build.prop", secondaryPath = "0644", details = "chmod 0644")

        assertTrue("Encrypted Head Anchor file must exist in noBackupFilesDir", anchorFile.exists())
        val verified = repo.verifyHashChainIntegrity()
        assertTrue(verified.isValid)
        assertEquals(ChainVerificationStatus.VALID, verified.status)
        assertEquals(2, verified.totalChecked)

        // Corrupt the encrypted Head Anchor file in noBackupFilesDir -> must report TAMPER_DETECTED
        anchorFile.writeBytes(byteArrayOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 99, 88, 77))
        val afterAnchorTamper = repo.verifyHashChainIntegrity()
        assertFalse(afterAnchorTamper.isValid)
        assertEquals(ChainVerificationStatus.TAMPER_DETECTED, afterAnchorTamper.status)

        // PIN-HMAC & Fail-Closed lockout in context.noBackupFilesDir
        val pinDir = File(context.noBackupFilesDir, "pin_test").apply { deleteRecursively(); mkdirs() }
        SecurityHardeningPolicy.initializePinLockoutOnFirstSetup(pinDir) { testAesKey }
        val salt = ByteArray(16) { (it + 1).toByte() }
        val pinTag = SecurityHardeningPolicy.hashPinWithSalt("7391", salt) { testHmacKey }
        assertTrue(SecurityHardeningPolicy.verifyPin("7391", salt, pinTag) { testHmacKey })

        // Delete lockout file -> fail-closed
        File(pinDir, SecurityHardeningPolicy.LOCKOUT_STATE_FILE).delete()
        val lockoutStatus = SecurityHardeningPolicy.readProtectedPinLockoutStatus(pinDir) { testAesKey }
        assertTrue(lockoutStatus.isFailClosedLocked)

        // Backup round-trip in context.cacheDir
        val plainBackup = File(context.cacheDir, "app_data.tar").apply {
            writeBytes(ByteArray(5000) { (it % 251).toByte() })
        }
        val encBackup = File(context.cacheDir, "app_data.tar.mteb")
        val decBackup = File(context.cacheDir, "app_data_restored.tar")
        val pass = "StrongPassphrase#2026!"

        assertTrue(
            RootAppBackupAndStorageHealth.encryptBackupWithPassphrase(plainBackup, encBackup, pass, chunkSize = 1024).isSuccess
        )
        assertTrue(
            RootAppBackupAndStorageHealth.decryptBackupWithPassphrase(encBackup, decBackup, pass).isSuccess
        )
        assertArrayEquals(plainBackup.readBytes(), decBackup.readBytes())
    }

    @Test
    fun robolectric_biometricVaultAuthenticator_handlesDeviceWithoutLockScreenGracefully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val shadowKeyguard = Shadows.shadowOf(keyguardManager)

        // 1. Device without a lock screen (isDeviceSecure = false)
        shadowKeyguard.setIsDeviceSecure(false)
        val insecureStatus = BiometricVaultAuthenticator.checkDeviceSecurity(context)
        assertFalse(insecureStatus.isDeviceLockScreenSecure)
        assertFalse(insecureStatus.isStrongBiometricReady)
        assertTrue(insecureStatus.message.contains("Kunci layar perangkat"))

        // 2. Device with a secure lock screen (isDeviceSecure = true)
        shadowKeyguard.setIsDeviceSecure(true)
        val secureStatus = BiometricVaultAuthenticator.checkDeviceSecurity(context)
        assertTrue(secureStatus.isDeviceLockScreenSecure)
    }
}
