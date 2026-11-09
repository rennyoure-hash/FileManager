package com.example.core.tools

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.FileObserver
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.core.db.KeystoreKeyUnavailableException
import com.example.core.db.OperationLogRepository
import com.example.core.root.MountAnalyzer
import com.example.core.root.RootCommandResult
import com.example.core.root.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Fase 7.A, Fase 8.2 & Fase 8b.9: In-App Crash & Diagnostic Logger with automatic sensitive credential/path masking
 * and best-effort auto-clearing clipboard protection (with explicit Android 10+ limitation notice).
 */
object InAppDiagnosticLogger {
    private const val LOG_FILE_NAME = "mt_explorer_diagnostic.log"
    private const val MAX_LOG_BYTES = 256 * 1024L // 256 KB rolling cap

    /**
     * Fase 8b.9: Explicit documentation of Android 10+ (API 29+) and OEM IME clipboard history limitations.
     * Displayed in the UI so users understand auto-clear is a best-effort mitigation, NOT an absolute guarantee.
     */
    const val CLIPBOARD_ANDROID10_LIMITATION_NOTE =
        "Catatan Keterbatasan Android 10+ (API 29+): Pembersihan otomatis clipboard setelah 15 detik bersifat " +
            "best-effort (defense-in-depth) dan BUKAN jaminan mutlak. Pada Android 10+, akses clipboard dibatasi " +
            "ketika aplikasi berada di latar belakang, dan fitur riwayat papan klip (Clipboard History) bawaan " +
            "keyboard OEM/pihak ketiga dapat menyimpan salinan teks di luar jangkauan ClipboardManager.clearPrimaryClip()."

    private val CREDENTIAL_URL_REGEX = Regex("([a-zA-Z0-9+.-]+://[^:\\s/]+:)([^@\\s]+)(@)")
    private val KEY_VALUE_SECRET_REGEX =
        Regex("(?i)(password|passwd|passphrase|secret|token|api[_-]?key|authorization)\\s*([=:])\\s*([^\\s,;\"']+)")
    private val EMAIL_REGEX = Regex("([a-zA-Z0-9._%+-]{2})[a-zA-Z0-9._%+-]*(@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})")

    fun maskSensitiveText(raw: String): String {
        var masked = raw
        masked = CREDENTIAL_URL_REGEX.replace(masked) { m ->
            "${m.groupValues[1]}***MASKED***${m.groupValues[3]}"
        }
        masked = KEY_VALUE_SECRET_REGEX.replace(masked) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}***MASKED***"
        }
        masked = EMAIL_REGEX.replace(masked) { m ->
            "${m.groupValues[1]}***${m.groupValues[2]}"
        }
        return masked
    }

    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logEvent(
                    appContext,
                    "FATAL_CRASH",
                    "Thread=${thread.name}\n${throwable.stackTraceToString()}"
                )
            } catch (_: Throwable) {
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun logEvent(context: Context, level: String, message: String) {
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val file = File(dir, LOG_FILE_NAME)
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                val tail = file.readText().takeLast((MAX_LOG_BYTES / 2).toInt())
                file.writeText(tail)
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sanitized = maskSensitiveText(message)
            file.appendText("[$timestamp] [$level] $sanitized\n")
        } catch (_: Throwable) {
        }
    }

    fun readLogs(context: Context): String {
        return try {
            val file = File(File(context.filesDir, "logs"), LOG_FILE_NAME)
            if (file.exists()) maskSensitiveText(file.readText()) else "(Belum ada log diagnostik tercatat)"
        } catch (_: Throwable) {
            "(Gagal membaca log)"
        }
    }

    fun clearLogs(context: Context) {
        try {
            File(File(context.filesDir, "logs"), LOG_FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * Fase 8.2 & 8b.9: Copies masked diagnostic text to Clipboard and attempts best-effort
     * auto-clear after [clearAfterMs]. See [CLIPBOARD_ANDROID10_LIMITATION_NOTE].
     */
    fun copyWithAutoClearClipboard(
        context: Context,
        scope: CoroutineScope,
        label: String,
        text: String,
        clearAfterMs: Long = 15_000L
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, maskSensitiveText(text)))
        scope.launch(Dispatchers.Main) {
            delay(clearAfterMs)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * Fase 8c.4: Device lock-screen & Biometric readiness status.
 */
data class VaultDeviceSecurityStatus(
    val isDeviceLockScreenSecure: Boolean,
    val isStrongBiometricReady: Boolean,
    val message: String
)

/**
 * Fase 8c.4: BiometricPrompt + CryptoObject authenticator using `androidx.biometric:biometric:1.1.0`.
 * - Verifies `KeyguardManager.isDeviceSecure` first so devices without a lock screen are handled gracefully.
 * - Binds authentication to a hardware Keystore `Cipher` wrapped in `BiometricPrompt.CryptoObject(cipher)`.
 * - Only the Vault key (`BIOMETRIC_VAULT_KEY_ALIAS`) is user-authentication-bound (`setUserAuthenticationRequired(true)`),
 *   whereas background keys (Operation Log HMAC, Head Anchor, Network Credentials, PIN HMAC) are NOT auth-bound.
 */
object BiometricVaultAuthenticator {

    fun checkDeviceSecurity(context: Context): VaultDeviceSecurityStatus {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isDeviceSecure = keyguard?.isDeviceSecure == true
        if (!isDeviceSecure) {
            return VaultDeviceSecurityStatus(
                isDeviceLockScreenSecure = false,
                isStrongBiometricReady = false,
                message = "Kunci layar perangkat (PIN/Pola/Sandi) belum diaktifkan. Aktifkan kunci layar dan daftarkan biometrik di Pengaturan Sistem untuk membuka kunci Vault."
            )
        }
        val bioManager = BiometricManager.from(context)
        val canAuth = bioManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when (canAuth) {
            BiometricManager.BIOMETRIC_SUCCESS -> VaultDeviceSecurityStatus(
                isDeviceLockScreenSecure = true,
                isStrongBiometricReady = true,
                message = "Biometrik Kelas 3 (Strong) & Keystore CryptoObject siap digunakan."
            )
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> VaultDeviceSecurityStatus(
                isDeviceLockScreenSecure = true,
                isStrongBiometricReady = false,
                message = "Kunci layar aktif, namun belum ada sidik jari/biometrik yang didaftarkan di perangkat ini."
            )
            else -> VaultDeviceSecurityStatus(
                isDeviceLockScreenSecure = true,
                isStrongBiometricReady = false,
                message = "Sensor biometrik kuat tidak tersedia saat ini (kode status: $canAuth)."
            )
        }
    }

    /**
     * Launches `androidx.biometric.BiometricPrompt` bound to a Keystore [Cipher] via `BiometricPrompt.CryptoObject(cipher)`.
     */
    fun authenticateVaultCipher(
        activity: FragmentActivity,
        encryptMode: Boolean,
        iv: ByteArray? = null,
        title: String = "Autentikasi Biometrik Vault",
        subtitle: String = "Verifikasi identitas untuk membuka kunci kriptografi Android Keystore",
        onAuthenticated: (Cipher) -> Unit,
        onError: (String) -> Unit
    ) {
        val security = checkDeviceSecurity(activity)
        if (!security.isDeviceLockScreenSecure || !security.isStrongBiometricReady) {
            onError(security.message)
            return
        }

        val cipher = try {
            KeystoreSecretManager.createBiometricCryptoObjectCipher(encryptMode = encryptMode, iv = iv)
        } catch (t: Throwable) {
            onError("Gagal menginisialisasi Cipher Keystore Vault (biometrik mungkin berubah/di-enroll ulang): ${t.message}")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlockedCipher = result.cryptoObject?.cipher
                    if (unlockedCipher != null) {
                        onAuthenticated(unlockedCipher)
                    } else {
                        onError("CryptoObject tidak dikembalikan oleh BiometricPrompt!")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError("Autentikasi biometrik dibatalkan/gagal ($errorCode): $errString")
                }

                override fun onAuthenticationFailed() {
                    // Biometric recognized as non-matching; prompt stays open for retry
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Batal")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}

/**
 * Fase 8b.2, Fase 8c.3 & Fase 8c.4:
 * Android Keystore Secret Manager for encrypting Network Credentials, VirusTotal API Keys,
 * and the Biometric-bound Vault key (`setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)`).
 * - **Zero software fallback in production**: If `AndroidKeyStore` is unavailable, throws [KeystoreKeyUnavailableException].
 * - Unit tests inject an explicit `aesKeyProvider: () -> SecretKey` parameter instead of using any `if (DEBUG)` or catch-fallback.
 * - **Only the Vault key** (`BIOMETRIC_VAULT_KEY_ALIAS`) has `setUserAuthenticationRequired(true)`.
 *   `CREDENTIALS_AES_KEY_ALIAS` has `setUserAuthenticationRequired(false)` so background network/VT operations do not block.
 */
object KeystoreSecretManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CREDENTIALS_AES_KEY_ALIAS = "mt_explorer_credentials_aes256_key"
    private const val BIOMETRIC_VAULT_KEY_ALIAS = "mt_explorer_biometric_vault_aes256_key"
    private const val VT_API_KEY_FILE = "vt_api_key.enc"

    /**
     * Retrieves or generates the non-exportable `AndroidKeyStore` AES-256-GCM key for credentials & lockout state.
     * Explicitly NOT user-authentication-bound (`setUserAuthenticationRequired(false)`), and NEVER falls back
     * to a software key in production.
     */
    fun getOrCreateCredentialsAesKey(): SecretKey {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(CREDENTIALS_AES_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                CREDENTIALS_AES_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            kg.init(spec)
            return kg.generateKey()
        } catch (t: Throwable) {
            throw KeystoreKeyUnavailableException(
                "AndroidKeyStore tidak tersedia untuk '$CREDENTIALS_AES_KEY_ALIAS' (fallback software dilarang di produksi): ${t.message}",
                t
            )
        }
    }

    /**
     * Fase 8b.2 & 8c.4: Creates or retrieves the ONLY user-authentication-bound Keystore AES-256-GCM key
     * (used exclusively for the Encrypted Vault):
     * - `setUserAuthenticationRequired(true)`
     * - `setInvalidatedByBiometricEnrollment(true)`
     * Used with `androidx.biometric.BiometricPrompt.CryptoObject(cipher)`.
     */
    fun getOrCreateBiometricAuthenticatedKey(): SecretKey {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(BIOMETRIC_VAULT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                BIOMETRIC_VAULT_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(true)
            }
            kg.init(builder.build())
            return kg.generateKey()
        } catch (t: Throwable) {
            throw KeystoreKeyUnavailableException(
                "Gagal membuat kunci Vault Biometrik di AndroidKeyStore (pastikan kunci layar & biometrik aktif): ${t.message}",
                t
            )
        }
    }

    /**
     * Initializes a [Cipher] instance bound to [getOrCreateBiometricAuthenticatedKey] for wrapping
     * inside `androidx.biometric.BiometricPrompt.CryptoObject(cipher)`.
     */
    fun createBiometricCryptoObjectCipher(encryptMode: Boolean, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getOrCreateBiometricAuthenticatedKey()
        if (encryptMode) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            requireNotNull(iv) { "IV wajib disediakan untuk dekripsi CryptoObject" }
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher
    }

    /**
     * Encrypts sensitive bytes using the Keystore AES-256-GCM key (or an injected test key in unit tests).
     */
    fun encryptSecretBytes(
        plainBytes: ByteArray,
        aesKeyProvider: () -> SecretKey = { getOrCreateCredentialsAesKey() }
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKeyProvider())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainBytes)
        return ByteArray(1 + iv.size + cipherText.size).also { out ->
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(cipherText, 0, out, 1 + iv.size, cipherText.size)
        }
    }

    fun decryptSecretBytes(
        packed: ByteArray,
        aesKeyProvider: () -> SecretKey = { getOrCreateCredentialsAesKey() }
    ): ByteArray {
        require(packed.size > 13) { "Ciphertext terlalu pendek" }
        val ivLen = packed[0].toInt() and 0xFF
        require(ivLen in 12..16 && packed.size > 1 + ivLen) { "Header IV tidak valid" }
        val iv = packed.copyOfRange(1, 1 + ivLen)
        val cipherText = packed.copyOfRange(1 + ivLen, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKeyProvider(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }

    fun encryptSecretToHex(
        plainSecret: String,
        aesKeyProvider: () -> SecretKey = { getOrCreateCredentialsAesKey() }
    ): String {
        if (plainSecret.isEmpty()) return ""
        val encrypted = encryptSecretBytes(plainSecret.toByteArray(Charsets.UTF_8), aesKeyProvider)
        return encrypted.joinToString("") { "%02x".format(it) }
    }

    fun decryptSecretFromHex(
        hexCipher: String,
        aesKeyProvider: () -> SecretKey = { getOrCreateCredentialsAesKey() }
    ): String {
        if (hexCipher.isBlank() || hexCipher.length % 2 != 0) return ""
        val bytes = ByteArray(hexCipher.length / 2) { i ->
            hexCipher.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(decryptSecretBytes(bytes, aesKeyProvider), Charsets.UTF_8)
    }

    fun saveEncryptedVirusTotalApiKey(
        context: Context,
        apiKey: String,
        aesKeyProvider: () -> SecretKey = { getOrCreateCredentialsAesKey() }
    ) {
        val target = File(context.noBackupFilesDir, VT_API_KEY_FILE)
        if (apiKey.isBlank()) {
            target.delete()
            return
        }
        target.writeBytes(encryptSecretBytes(apiKey.trim().toByteArray(Charsets.UTF_8), aesKeyProvider))
    }

    fun loadDecryptedVirusTotalApiKey(
        context: Context,
        aesKeyProvider: () -> SecretKey = { getOrCreateCredentialsAesKey() }
    ): String {
        val target = File(context.noBackupFilesDir, VT_API_KEY_FILE)
        if (!target.exists()) return ""
        return try {
            String(decryptSecretBytes(target.readBytes(), aesKeyProvider), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }
}

/**
 * Fase 7.A: Integrity Monitor (non-recursive FileObserver capped at MAX_WATCHES + SHA-256 snapshot check).
 */
data class WatchedFileSnapshot(
    val path: String,
    val baselineSha256: String,
    val baselineSize: Long,
    val baselineMtime: Long,
    val lastCheckedAt: Long = System.currentTimeMillis()
)

class FileIntegrityMonitor(
    private val operationLogRepository: OperationLogRepository? = null
) {
    companion object {
        const val MAX_WATCHES = 16
    }

    private val snapshots = ConcurrentHashMap<String, WatchedFileSnapshot>()
    private val observers = ConcurrentHashMap<String, FileObserver>()

    suspend fun addWatch(file: File): Result<WatchedFileSnapshot> = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext Result.failure(IllegalArgumentException("File/folder tidak ditemukan: ${file.absolutePath}"))
        }
        if (snapshots.size >= MAX_WATCHES && !snapshots.containsKey(file.absolutePath)) {
            return@withContext Result.failure(IllegalStateException("Batas maksimum watch integritas tercapai ($MAX_WATCHES item)"))
        }
        val sha = if (file.isFile) ChecksumTool.compute(file, "SHA-256") else "DIR_SNAPSHOT_${file.listFiles()?.size ?: 0}"
        val snap = WatchedFileSnapshot(
            path = file.absolutePath,
            baselineSha256 = sha,
            baselineSize = if (file.isFile) file.length() else 0L,
            baselineMtime = file.lastModified()
        )
        snapshots[file.absolutePath] = snap
        Result.success(snap)
    }

    fun removeWatch(path: String) {
        snapshots.remove(path)
        observers.remove(path)?.stopWatching()
    }

    fun listWatched(): List<WatchedFileSnapshot> = snapshots.values.toList()

    suspend fun verifyAllSnapshots(): List<String> = withContext(Dispatchers.IO) {
        val alerts = mutableListOf<String>()
        for ((path, baseline) in snapshots) {
            val f = File(path)
            if (!f.exists()) {
                val msg = "INTEGRITY ALERT: File terpantau dihapus: $path"
                alerts.add(msg)
                operationLogRepository?.recordOperation(
                    operationType = "INTEGRITY_ALERT",
                    targetPath = path,
                    details = msg,
                    canUndo = false
                )
                continue
            }
            val currentSha = if (f.isFile) ChecksumTool.compute(f, "SHA-256") else "DIR_SNAPSHOT_${f.listFiles()?.size ?: 0}"
            if (currentSha != baseline.baselineSha256) {
                val msg = "INTEGRITY ALERT: Hash berubah pada $path (awal=${baseline.baselineSha256.take(12)}..., kini=${currentSha.take(12)}...)"
                alerts.add(msg)
                operationLogRepository?.recordOperation(
                    operationType = "INTEGRITY_ALERT",
                    targetPath = path,
                    details = msg,
                    canUndo = false
                )
            }
        }
        alerts
    }
}

/**
 * Fase 7.A & 8b.2: Opt-in VirusTotal Hash Checker (sends ONLY SHA-256 hash, NEVER uploads the file,
 * stores user API key encrypted via Android Keystore).
 */
data class VirusTotalHashResult(
    val sha256: String,
    val maliciousCount: Int,
    val suspiciousCount: Int,
    val harmlessCount: Int,
    val summaryMessage: String
)

object VirusTotalHashChecker {
    private const val MAX_REQUESTS_PER_MINUTE = 4
    private val requestTimestamps = ArrayDeque<Long>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun checkRateLimitAllowed(nowMillis: Long = System.currentTimeMillis()): Long {
        while (requestTimestamps.isNotEmpty() && nowMillis - requestTimestamps.first() > 60_000L) {
            requestTimestamps.removeFirst()
        }
        return if (requestTimestamps.size >= MAX_REQUESTS_PER_MINUTE) {
            val oldest = requestTimestamps.first()
            (60_000L - (nowMillis - oldest)).coerceAtLeast(1000L)
        } else {
            requestTimestamps.addLast(nowMillis)
            0L
        }
    }

    suspend fun checkSha256HashWithStoredKey(context: Context, sha256: String): Result<VirusTotalHashResult> {
        val decryptedKey = KeystoreSecretManager.loadDecryptedVirusTotalApiKey(context)
        return checkSha256HashOnly(sha256, decryptedKey)
    }

    suspend fun checkSha256HashOnly(sha256: String, userApiKey: String): Result<VirusTotalHashResult> =
        withContext(Dispatchers.IO) {
            val cleanHash = sha256.trim().lowercase()
            if (cleanHash.length != 64 || !cleanHash.all { it in '0'..'9' || it in 'a'..'f' }) {
                return@withContext Result.failure(IllegalArgumentException("Format hash SHA-256 tidak valid (harus 64 karakter hex)"))
            }
            if (userApiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API Key VirusTotal belum diisi (Opt-in)"))
            }
            val waitMs = checkRateLimitAllowed()
            if (waitMs > 0L) {
                return@withContext Result.failure(
                    IllegalStateException("Rate limit VirusTotal aktif (maks 4 req/menit). Coba lagi dalam ${waitMs / 1000} detik.")
                )
            }

            try {
                val request = Request.Builder()
                    .url("https://www.virustotal.com/api/v3/files/$cleanHash")
                    .header("x-apikey", userApiKey.trim())
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body?.string().orEmpty()
                            val mal = extractJsonStat(body, "malicious")
                            val susp = extractJsonStat(body, "suspicious")
                            val harm = extractJsonStat(body, "harmless")
                            Result.success(
                                VirusTotalHashResult(
                                    sha256 = cleanHash,
                                    maliciousCount = mal,
                                    suspiciousCount = susp,
                                    harmlessCount = harm,
                                    summaryMessage = "Deteksi VT: $mal malicious, $susp suspicious, $harm harmless (Hanya hash yang dikirim)"
                                )
                            )
                        }
                        404 -> Result.success(
                            VirusTotalHashResult(
                                sha256 = cleanHash,
                                maliciousCount = 0,
                                suspiciousCount = 0,
                                harmlessCount = 0,
                                summaryMessage = "Hash belum pernah dilaporkan di database VirusTotal (File tidak diunggah)"
                            )
                        )
                        429 -> Result.failure(IllegalStateException("HTTP 429: Kuota/Rate limit API VirusTotal tercapai"))
                        else -> Result.failure(IllegalStateException("VirusTotal HTTP ${response.code}"))
                    }
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    private fun extractJsonStat(json: String, field: String): Int {
        val regex = Regex("\"$field\"\\s*:\\s*(\\d+)")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }
}

/**
 * Fase 7.B: Perceptual Hash (64-bit dHash) Similar Photo Detector.
 * Strictly MANUAL REVIEW ONLY — never allows automatic deletion because "similar" != "identical".
 */
data class SimilarPhotoPair(
    val photoA: File,
    val photoB: File,
    val hammingDistance: Int,
    val similarityPercent: Int,
    val isAutoDeleteAllowed: Boolean = false
)

object PerceptualPhotoSimilarityEngine {

    fun computeDHash64(imageFile: File): Long? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val decoded = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: return null
            val scaled = Bitmap.createScaledBitmap(decoded, 9, 8, true)
            if (scaled != decoded) decoded.recycle()

            var hash = 0L
            var bitIndex = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val leftLum = luminance(scaled.getPixel(x, y))
                    val rightLum = luminance(scaled.getPixel(x + 1, y))
                    if (leftLum > rightLum) {
                        hash = hash or (1L shl bitIndex)
                    }
                    bitIndex++
                }
            }
            scaled.recycle()
            hash
        } catch (_: Throwable) {
            null
        }
    }

    fun hammingDistance64(hashA: Long, hashB: Long): Int {
        return java.lang.Long.bitCount(hashA xor hashB)
    }

    suspend fun findSimilarPhotosManualReviewOnly(
        directory: File,
        maxHammingDistance: Int = 10
    ): List<SimilarPhotoPair> = withContext(Dispatchers.IO) {
        if (!directory.exists() || !directory.isDirectory) return@withContext emptyList()
        val imageExts = setOf("jpg", "jpeg", "png", "webp")
        val photos = directory.walkTopDown()
            .filter { it.isFile && imageExts.contains(it.extension.lowercase()) }
            .take(300)
            .toList()

        val hashes = photos.mapNotNull { file ->
            computeDHash64(file)?.let { file to it }
        }

        val pairs = mutableListOf<SimilarPhotoPair>()
        for (i in hashes.indices) {
            for (j in i + 1 until hashes.size) {
                val (fileA, hashA) = hashes[i]
                val (fileB, hashB) = hashes[j]
                val dist = hammingDistance64(hashA, hashB)
                if (dist <= maxHammingDistance) {
                    val sim = ((64 - dist) * 100) / 64
                    pairs.add(
                        SimilarPhotoPair(
                            photoA = fileA,
                            photoB = fileB,
                            hammingDistance = dist,
                            similarityPercent = sim,
                            isAutoDeleteAllowed = false
                        )
                    )
                }
            }
        }
        pairs.sortedBy { it.hammingDistance }
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

enum class FolderDiffStatus {
    ONLY_IN_LEFT,
    ONLY_IN_RIGHT,
    MODIFIED_CONFLICT,
    IDENTICAL
}

data class FolderDiffItem(
    val relativePath: String,
    val status: FolderDiffStatus,
    val leftFile: File?,
    val rightFile: File?
)

object FolderDiffAndSyncEngine {
    suspend fun compareFolders(leftRoot: File, rightRoot: File): List<FolderDiffItem> = withContext(Dispatchers.IO) {
        val leftMap = mutableMapOf<String, File>()
        val rightMap = mutableMapOf<String, File>()

        if (leftRoot.exists()) {
            leftRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.absolutePath.removePrefix(leftRoot.absolutePath).trimStart('/', '\\')
                leftMap[rel] = f
            }
        }
        if (rightRoot.exists()) {
            rightRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.absolutePath.removePrefix(rightRoot.absolutePath).trimStart('/', '\\')
                rightMap[rel] = f
            }
        }

        val allKeys = (leftMap.keys + rightMap.keys).sorted()
        allKeys.map { rel ->
            val l = leftMap[rel]
            val r = rightMap[rel]
            val status = when {
                l != null && r == null -> FolderDiffStatus.ONLY_IN_LEFT
                l == null && r != null -> FolderDiffStatus.ONLY_IN_RIGHT
                l != null && r != null -> {
                    if (l.length() == r.length() && abs(l.lastModified() - r.lastModified()) < 2000L) {
                        FolderDiffStatus.IDENTICAL
                    } else {
                        FolderDiffStatus.MODIFIED_CONFLICT
                    }
                }
                else -> FolderDiffStatus.IDENTICAL
            }
            FolderDiffItem(relativePath = rel, status = status, leftFile = l, rightFile = r)
        }
    }
}

/**
 * Fase 7.C, Fase 8b.4 & Fase 8c.8:
 * - Encrypted Root App + Data Backup/Restore:
 *   * Minimum passphrase length enforced (`MIN_PASSPHRASE_LENGTH = 12`).
 *   * `PBKDF2WithHmacSHA256` with **600,000 iterations** (`MIN_PBKDF2_ITERATIONS = 600_000`); header with < 600,000 iterations or invalid magic (`MTB2`) is strictly rejected.
 *   * **Chunked AES-256-GCM** (`CHUNK_SIZE_BYTES = 64 * 1024`):
 *     Each chunk uses a freshly generated, cryptographically random 12-byte IV (`SecureRandom`),
 *     verified to never repeat, with `chunkIndex` and `isFinalChunk` bound into GCM AAD (`updateAAD`),
 *     and the 16-byte salt stored in the file header.
 *   * Verifies that the final chunk (`isFinalChunk == true`) is present AND that there are zero trailing bytes after the final chunk.
 * - Read-Only Partition Backup (`dd if=/dev/block/... of=/sdcard/...`, NEVER writes to `/dev/block`).
 * - `.nomedia` Manager & `fstrim` / Storage Health.
 */
object RootAppBackupAndStorageHealth {
    const val PBKDF2_ITERATIONS = 600_000
    const val MIN_PBKDF2_ITERATIONS = 600_000
    const val MIN_PASSPHRASE_LENGTH = 12
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val CHUNK_SIZE_BYTES = 64 * 1024
    private val HEADER_MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), '2'.code.toByte())

    fun validatePassphraseStrength(passphrase: String) {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Passphrase backup/restore minimal harus $MIN_PASSPHRASE_LENGTH karakter (saat ini: ${passphrase.length} karakter)!"
        }
    }

    fun deriveAesKeyFromPassphrase(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): SecretKeySpec {
        require(iterations >= MIN_PBKDF2_ITERATIONS) {
            "Iterasi PBKDF2 ($iterations) kurang dari batas keamanan minimum ($MIN_PBKDF2_ITERATIONS)!"
        }
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun buildChunkAad(salt: ByteArray, chunkIndex: Int, isFinalChunk: Boolean): ByteArray {
        return ByteBuffer.allocate(salt.size + 5)
            .put(salt)
            .putInt(chunkIndex)
            .put(if (isFinalChunk) 1.toByte() else 0.toByte())
            .array()
    }

    /**
     * Fase 8b.4 & 8c.8: Encrypts a backup file in chunks (`chunkSize` default 64 KB) using `PBKDF2WithHmacSHA256`
     * (>= 600,000 iterations) + `AES/GCM/NoPadding` with a unique random 12-byte IV per chunk (zero IV reuse),
     * enforcing [MIN_PASSPHRASE_LENGTH] (12 chars).
     */
    suspend fun encryptBackupWithPassphrase(
        plainFile: File,
        encryptedOut: File,
        passphrase: String,
        chunkSize: Int = CHUNK_SIZE_BYTES,
        iterations: Int = PBKDF2_ITERATIONS
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            validatePassphraseStrength(passphrase)
            require(iterations >= MIN_PBKDF2_ITERATIONS) {
                "Iterasi PBKDF2 minimal $MIN_PBKDF2_ITERATIONS"
            }
            val safeChunkSize = chunkSize.coerceIn(1024, 1024 * 1024)
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val secretKey = deriveAesKeyFromPassphrase(passphrase.toCharArray(), salt, iterations)
            val seenIvs = HashSet<String>()

            encryptedOut.parentFile?.mkdirs()
            DataOutputStream(FileOutputStream(encryptedOut).buffered()).use { dos ->
                // Write header: 4B magic + 4B iterations + 16B salt
                dos.write(HEADER_MAGIC)
                dos.writeInt(iterations)
                dos.write(salt)

                FileInputStream(plainFile).buffered().use { fis ->
                    val buffer = ByteArray(safeChunkSize)
                    var chunkIndex = 0
                    val totalLen = plainFile.length()
                    var totalReadSoFar = 0L

                    while (true) {
                        val read = readChunkFully(fis, buffer)
                        val isEmptyFileFirstChunk = (read <= 0 && chunkIndex == 0 && totalLen == 0L)
                        if (read <= 0 && !isEmptyFileFirstChunk) break

                        val actualPlain = if (isEmptyFileFirstChunk) ByteArray(0) else buffer.copyOf(read)
                        totalReadSoFar += actualPlain.size
                        val isFinalChunk = isEmptyFileFirstChunk || totalReadSoFar >= totalLen

                        // Generate unique random 12-byte IV per chunk — guarantee zero IV reuse
                        val chunkIv = ByteArray(IV_BYTES)
                        var ivHex: String
                        do {
                            random.nextBytes(chunkIv)
                            ivHex = chunkIv.joinToString("") { "%02x".format(it) }
                        } while (!seenIvs.add(ivHex))

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, chunkIv))
                        cipher.updateAAD(buildChunkAad(salt, chunkIndex, isFinalChunk))
                        val cipherBytes = cipher.doFinal(actualPlain)

                        dos.write(chunkIv)
                        dos.writeBoolean(isFinalChunk)
                        dos.writeInt(cipherBytes.size)
                        dos.write(cipherBytes)

                        chunkIndex++
                        if (isFinalChunk) break
                    }
                }
                dos.flush()
            }
            Result.success(encryptedOut)
        } catch (t: Throwable) {
            encryptedOut.delete()
            Result.failure(t)
        }
    }

    /**
     * Fase 8b.4 & 8c.8: Decrypts a chunked AES-256-GCM backup archive, enforcing:
     * - Passphrase length >= [MIN_PASSPHRASE_LENGTH] (12 chars)
     * - Header magic == `MTB2`
     * - Header PBKDF2 iterations >= 600,000 (`MIN_PBKDF2_ITERATIONS`)
     * - Unique per-chunk IVs (zero IV reuse)
     * - Per-chunk AAD (`chunkIndex` + `isFinalChunk`)
     * - Presence of final chunk (`isFinalChunk == true`) AND zero trailing bytes after the final chunk.
     */
    suspend fun decryptBackupWithPassphrase(
        encryptedFile: File,
        plainOut: File,
        passphrase: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            validatePassphraseStrength(passphrase)
            DataInputStream(FileInputStream(encryptedFile).buffered()).use { dis ->
                val magic = ByteArray(4)
                dis.readFully(magic)
                if (!magic.contentEquals(HEADER_MAGIC)) {
                    throw SecurityException("Header magic file backup tidak valid (bukan format MTB2)!")
                }

                val iterations = dis.readInt()
                if (iterations < MIN_PBKDF2_ITERATIONS || iterations > 10_000_000) {
                    throw SecurityException(
                        "Iterasi PBKDF2 pada header ($iterations) ditolak karena kurang dari standar keamanan minimum ($MIN_PBKDF2_ITERATIONS)!"
                    )
                }

                val salt = ByteArray(SALT_BYTES)
                dis.readFully(salt)
                val secretKey = deriveAesKeyFromPassphrase(passphrase.toCharArray(), salt, iterations)
                val seenIvs = HashSet<String>()

                plainOut.parentFile?.mkdirs()
                FileOutputStream(plainOut).buffered().use { fos ->
                    var chunkIndex = 0
                    var sawFinalChunk = false

                    while (!sawFinalChunk) {
                        val chunkIv = ByteArray(IV_BYTES)
                        try {
                            dis.readFully(chunkIv)
                        } catch (_: EOFException) {
                            break
                        }
                        val ivHex = chunkIv.joinToString("") { "%02x".format(it) }
                        if (!seenIvs.add(ivHex)) {
                            throw SecurityException(
                                "Security Violation: Terdeteksi penggunaan ulang IV (IV reuse) pada chunk #$chunkIndex!"
                            )
                        }

                        val isFinalChunk = dis.readBoolean()
                        val cipherLen = dis.readInt()
                        if (cipherLen !in 16..(2 * 1024 * 1024)) {
                            throw SecurityException("Ukuran chunk terenkripsi tidak valid: $cipherLen")
                        }
                        val cipherBytes = ByteArray(cipherLen)
                        dis.readFully(cipherBytes)

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, chunkIv))
                        cipher.updateAAD(buildChunkAad(salt, chunkIndex, isFinalChunk))
                        val plainChunk = cipher.doFinal(cipherBytes)
                        fos.write(plainChunk)

                        sawFinalChunk = isFinalChunk
                        chunkIndex++
                    }

                    if (!sawFinalChunk) {
                        throw SecurityException(
                            "File backup terpotong (truncated) — chunk final (isFinalChunk=true) tidak ditemukan!"
                        )
                    }

                    // Verify there are no trailing/injected bytes after the final authenticated chunk
                    val trailingByte = dis.read()
                    if (trailingByte != -1) {
                        throw SecurityException(
                            "Security Violation: Ditemukan byte tambahan tidak sah setelah chunk final!"
                        )
                    }
                    fos.flush()
                }
            }
            Result.success(plainOut)
        } catch (t: Throwable) {
            plainOut.delete()
            Result.failure(t)
        }
    }

    /**
     * Helper for security audit / unit testing: inspects the header iterations, salt, and all per-chunk IVs
     * of an encrypted backup file without decrypting the payload.
     */
    fun inspectEncryptedBackupMetadata(encryptedFile: File): Triple<Int, ByteArray, List<ByteArray>> {
        DataInputStream(FileInputStream(encryptedFile).buffered()).use { dis ->
            val magic = ByteArray(4)
            dis.readFully(magic)
            require(magic.contentEquals(HEADER_MAGIC)) { "Invalid backup magic header" }
            val iterations = dis.readInt()
            val salt = ByteArray(SALT_BYTES)
            dis.readFully(salt)
            val chunkIvs = mutableListOf<ByteArray>()
            while (true) {
                val iv = ByteArray(IV_BYTES)
                try {
                    dis.readFully(iv)
                } catch (_: EOFException) {
                    break
                }
                val isFinal = dis.readBoolean()
                val len = dis.readInt()
                val skipBuf = ByteArray(len)
                dis.readFully(skipBuf)
                chunkIvs.add(iv)
                if (isFinal) break
            }
            return Triple(iterations, salt, chunkIvs)
        }
    }

    private fun readChunkFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = input.read(buffer, total, buffer.size - total)
            if (r == -1) break
            total += r
        }
        return total
    }

    suspend fun restoreAppDataWithOwnerAndSelinux(packageName: String, backupTarFile: File): RootCommandResult =
        withContext(Dispatchers.IO) {
            val cleanPkg = packageName.trim()
            if (!cleanPkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
                return@withContext RootCommandResult(-1, emptyList(), listOf("Nama paket tidak valid"))
            }
            val dataDir = "/data/data/$cleanPkg"
            val escapedPkg = MountAnalyzer.escapeShellPath(cleanPkg)
            val escapedDataDir = MountAnalyzer.escapeShellPath(dataDir)
            val escapedTar = MountAnalyzer.escapeShellPath(backupTarFile.absolutePath)

            val statUid = RootManager.executeCommand("stat -c '%u:%g' $escapedDataDir", reportErrorToUi = false)
            val uidGid = statUid.outputText.trim().takeIf { it.contains(":") } ?: "root:root"
            val escapedUidGid = MountAnalyzer.escapeShellPath(uidGid)

            RootManager.executeCommand(
                "am force-stop $escapedPkg && tar -xzf $escapedTar -C '/data/data' && chown -R $escapedUidGid $escapedDataDir && restorecon -FR $escapedDataDir"
            )
        }

    suspend fun exportPartitionReadOnly(partitionName: String, destImageFile: File): RootCommandResult =
        withContext(Dispatchers.IO) {
            val allowedPartitions = setOf("boot", "boot_a", "boot_b", "recovery", "init_boot", "init_boot_a", "init_boot_b")
            val cleanPart = partitionName.trim().lowercase()
            if (!allowedPartitions.contains(cleanPart)) {
                return@withContext RootCommandResult(
                    -1,
                    emptyList(),
                    listOf("Hanya ekspor READ-ONLY partisi boot/recovery yang diizinkan ($cleanPart ditolak)")
                )
            }
            val destCanonical = destImageFile.canonicalPath
            if (destCanonical.startsWith("/dev") || destCanonical.startsWith("/system") || destCanonical.startsWith("/boot")) {
                return@withContext RootCommandResult(
                    -1,
                    emptyList(),
                    listOf("Tujuan ekspor partisi harus berada di storage pengguna, tidak boleh di $destCanonical")
                )
            }
            destImageFile.parentFile?.mkdirs()
            val srcBlock = "/dev/block/by-name/$cleanPart"
            val escapedIf = MountAnalyzer.escapeShellPath("if=$srcBlock")
            val escapedOf = MountAnalyzer.escapeShellPath("of=$destCanonical")
            RootManager.executeCommand("dd $escapedIf $escapedOf bs=4096")
        }

    suspend fun runFstrimData(): RootCommandResult = withContext(Dispatchers.IO) {
        RootManager.executeCommand("fstrim -v '/data'")
    }

    suspend fun setNoMediaInFolders(context: Context, directories: List<File>, enableNoMedia: Boolean): Int =
        withContext(Dispatchers.IO) {
            var changed = 0
            val affectedPaths = mutableListOf<String>()
            for (dir in directories) {
                if (!dir.exists() || !dir.isDirectory) continue
                val noMedia = File(dir, ".nomedia")
                val ok = if (enableNoMedia) {
                    if (!noMedia.exists()) noMedia.createNewFile() else true
                } else {
                    if (noMedia.exists()) noMedia.delete() else true
                }
                if (ok) {
                    changed++
                    affectedPaths.add(dir.absolutePath)
                }
            }
            if (affectedPaths.isNotEmpty()) {
                try {
                    MediaScannerConnection.scanFile(context, affectedPaths.toTypedArray(), null, null)
                } catch (_: Throwable) {
                }
            }
            changed
        }
}

/**
 * Fase 8c.7: Represents the PIN lockout verification state.
 * If [isFailClosedLocked] is `true`, the lockout file was deleted or corrupted after PIN setup,
 * and PIN verification is strictly blocked (fail-closed).
 */
data class PinLockoutStatus(
    val failedAttempts: Int,
    val unlockAtMillis: Long,
    val isFailClosedLocked: Boolean,
    val isFirstRunUnconfigured: Boolean = false,
    val statusReason: String = "OK"
)

/**
 * Fase 8.3, 8.4, Fase 8b.3, 8b.5 & Fase 8c.3, 8c.7:
 * - Keystore-bound PIN verification (`hashPinWithSalt`) without any software fallback in production.
 * - **Fail-Closed PIN Lockout State** (`readProtectedPinLockoutStatus`):
 *   If PIN has been configured (`pin_configured.marker` exists or `isPinConfigured == true`) and
 *   `pin_lockout_state.enc` is missing, unreadable, or fails AES-GCM authentication tag verification,
 *   the state **fails closed** (`isFailClosedLocked = true`, `failedAttempts = 999`, `unlockAtMillis = Long.MAX_VALUE`)
 *   unless it is explicitly detected as a first-run unconfigured state!
 * - Local HTTP/FTP Server Security Policy: random 128-bit session token, private LAN IP verification,
 *   and per-IP sliding-window rate limiter.
 */
object SecurityHardeningPolicy {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PIN_HMAC_KEY_ALIAS = "mt_explorer_pin_bound_hmac_key"
    private const val PIN_PBKDF2_ITERATIONS = 120_000
    const val LOCKOUT_STATE_FILE = "pin_lockout_state.enc"
    const val PIN_CONFIGURED_MARKER_FILE = "pin_configured.marker"
    const val FAIL_CLOSED_ATTEMPTS = 999

    private val ipRequestHistory = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /**
     * Retrieves or generates the non-exportable `AndroidKeyStore` `HmacSHA256` key for PIN verification.
     * Explicitly NOT user-auth-bound (`setUserAuthenticationRequired(false)`), and NEVER falls back
     * to a software key in production. Unit tests pass an injected `hmacKeyProvider`.
     */
    fun getOrCreatePinKeystoreHmacKey(): SecretKey {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(PIN_HMAC_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                PIN_HMAC_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            kg.init(spec)
            return kg.generateKey()
        } catch (t: Throwable) {
            throw KeystoreKeyUnavailableException(
                "AndroidKeyStore tidak tersedia untuk '$PIN_HMAC_KEY_ALIAS' (fallback software dilarang di produksi): ${t.message}",
                t
            )
        }
    }

    /**
     * Computes a stepped/exponential lockout delay in milliseconds based on consecutive failed PIN attempts:
     * - 0..2 failures -> 0 ms
     * - 3 failures -> 5,000 ms (5s)
     * - 4 failures -> 15,000 ms (15s)
     * - 5 failures -> 30,000 ms (30s)
     * - 6+ failures -> 60,000 ms * 2^(failures - 6), capped at 15 minutes
     */
    fun calculateLockoutDelayMs(failedAttempts: Int): Long {
        return when {
            failedAttempts < 3 -> 0L
            failedAttempts == 3 -> 5_000L
            failedAttempts == 4 -> 15_000L
            failedAttempts == 5 -> 30_000L
            else -> {
                val shift = (failedAttempts - 6).coerceIn(0, 4)
                (60_000L shl shift).coerceAtMost(15 * 60_000L)
            }
        }
    }

    /**
     * Fase 8b.3 & 8c.3: Hashes the PIN using PBKDF2WithHmacSHA256 (120,000 iterations) AND binds it
     * with a non-exportable Android Keystore `HmacSHA256` signature so offline brute-force is impossible
     * without the device's hardware Keystore.
     */
    fun hashPinWithSalt(
        pin: String,
        salt: ByteArray,
        hmacKeyProvider: () -> SecretKey = { getOrCreatePinKeystoreHmacKey() }
    ): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_PBKDF2_ITERATIONS, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKeyProvider())
        mac.update(salt)
        val keystoreBoundTag = mac.doFinal(derived)
        return keystoreBoundTag.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(
        pin: String,
        salt: ByteArray,
        expectedHexHash: String,
        hmacKeyProvider: () -> SecretKey = { getOrCreatePinKeystoreHmacKey() }
    ): Boolean {
        val actual = hashPinWithSalt(pin, salt, hmacKeyProvider)
        return MessageDigest.isEqual(actual.toByteArray(), expectedHexHash.toByteArray())
    }

    /**
     * Fase 8c.7: Initializes the PIN lockout state on explicit first-time PIN setup, writing both
     * the `pin_configured.marker` and an authenticated initial `0|0|CLEAN` encrypted lockout file.
     */
    fun initializePinLockoutOnFirstSetup(
        storageDir: File,
        aesKeyProvider: () -> SecretKey = { KeystoreSecretManager.getOrCreateCredentialsAesKey() }
    ) {
        storageDir.mkdirs()
        val markerFile = File(storageDir, PIN_CONFIGURED_MARKER_FILE)
        val lockoutFile = File(storageDir, LOCKOUT_STATE_FILE)
        val payload = "0|0|CLEAN".toByteArray(Charsets.UTF_8)
        lockoutFile.writeBytes(KeystoreSecretManager.encryptSecretBytes(payload, aesKeyProvider))
        markerFile.writeText("PIN_CONFIGURED_V1")
    }

    /**
     * Fase 8c.7: Reads the protected PIN lockout state from [storageDir] (`context.noBackupFilesDir`).
     * - **Explicit First-Run Detection**: Only when `pin_configured.marker` AND `pin_lockout_state.enc`
     *   both do not exist (and [isPinAlreadyConfigured] is `false`), returns an unlocked `FIRST_RUN` state (`0` attempts).
     * - **Fail-Closed on Missing or Corrupt Lockout File**: If the PIN was already configured (`markerFile.exists()`
     *   or `isPinAlreadyConfigured == true`) and `pin_lockout_state.enc` is deleted/missing OR fails AES-GCM
     *   decryption/parsing, returns `PinLockoutStatus(failedAttempts = 999, unlockAtMillis = Long.MAX_VALUE, isFailClosedLocked = true)`.
     */
    fun readProtectedPinLockoutStatus(
        storageDir: File,
        isPinAlreadyConfigured: Boolean = File(storageDir, PIN_CONFIGURED_MARKER_FILE).exists(),
        aesKeyProvider: () -> SecretKey = { KeystoreSecretManager.getOrCreateCredentialsAesKey() }
    ): PinLockoutStatus {
        val markerFile = File(storageDir, PIN_CONFIGURED_MARKER_FILE)
        val lockoutFile = File(storageDir, LOCKOUT_STATE_FILE)
        val configured = isPinAlreadyConfigured || markerFile.exists()

        if (!lockoutFile.exists()) {
            return if (!configured) {
                PinLockoutStatus(
                    failedAttempts = 0,
                    unlockAtMillis = 0L,
                    isFailClosedLocked = false,
                    isFirstRunUnconfigured = true,
                    statusReason = "FIRST_RUN: PIN belum pernah dikonfigurasi"
                )
            } else {
                PinLockoutStatus(
                    failedAttempts = FAIL_CLOSED_ATTEMPTS,
                    unlockAtMillis = Long.MAX_VALUE,
                    isFailClosedLocked = true,
                    isFirstRunUnconfigured = false,
                    statusReason = "FAIL_CLOSED: File lockout '$LOCKOUT_STATE_FILE' hilang setelah PIN dikonfigurasi (indikasi penghapusan paksa)!"
                )
            }
        }

        return try {
            val decrypted = String(
                KeystoreSecretManager.decryptSecretBytes(lockoutFile.readBytes(), aesKeyProvider),
                Charsets.UTF_8
            )
            val parts = decrypted.split("|")
            if (parts.size < 2) {
                return PinLockoutStatus(
                    failedAttempts = FAIL_CLOSED_ATTEMPTS,
                    unlockAtMillis = Long.MAX_VALUE,
                    isFailClosedLocked = true,
                    statusReason = "FAIL_CLOSED: Format payload lockout korup!"
                )
            }
            val count = parts[0].toIntOrNull()
            val unlockAt = parts[1].toLongOrNull()
            if (count == null || unlockAt == null || count < 0 || unlockAt < 0L) {
                return PinLockoutStatus(
                    failedAttempts = FAIL_CLOSED_ATTEMPTS,
                    unlockAtMillis = Long.MAX_VALUE,
                    isFailClosedLocked = true,
                    statusReason = "FAIL_CLOSED: Nilai counter/timestamp lockout tidak valid!"
                )
            }
            PinLockoutStatus(
                failedAttempts = count,
                unlockAtMillis = unlockAt,
                isFailClosedLocked = false,
                isFirstRunUnconfigured = false,
                statusReason = "OK"
            )
        } catch (t: Throwable) {
            PinLockoutStatus(
                failedAttempts = FAIL_CLOSED_ATTEMPTS,
                unlockAtMillis = Long.MAX_VALUE,
                isFailClosedLocked = true,
                isFirstRunUnconfigured = false,
                statusReason = "FAIL_CLOSED: Gagal mendekripsi/memverifikasi GCM tag pada '$LOCKOUT_STATE_FILE' (${t.message})"
            )
        }
    }

    fun readProtectedPinLockoutState(
        context: Context,
        aesKeyProvider: () -> SecretKey = { KeystoreSecretManager.getOrCreateCredentialsAesKey() }
    ): Pair<Int, Long> {
        val status = readProtectedPinLockoutStatus(context.noBackupFilesDir, aesKeyProvider = aesKeyProvider)
        return status.failedAttempts to status.unlockAtMillis
    }

    fun recordProtectedPinAttemptInDir(
        storageDir: File,
        isSuccess: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        aesKeyProvider: () -> SecretKey = { KeystoreSecretManager.getOrCreateCredentialsAesKey() }
    ): PinLockoutStatus {
        storageDir.mkdirs()
        val markerFile = File(storageDir, PIN_CONFIGURED_MARKER_FILE)
        val lockoutFile = File(storageDir, LOCKOUT_STATE_FILE)

        val currentStatus = readProtectedPinLockoutStatus(
            storageDir = storageDir,
            isPinAlreadyConfigured = markerFile.exists(),
            aesKeyProvider = aesKeyProvider
        )
        // If already fail-closed locked due to tampering/deletion, refuse to reset even on a valid attempt!
        if (currentStatus.isFailClosedLocked) {
            return currentStatus
        }

        if (!markerFile.exists()) {
            markerFile.writeText("PIN_CONFIGURED_V1")
        }

        if (isSuccess) {
            // Write an authenticated 0|0|CLEAN state instead of deleting the file so deletion is always detectable!
            val cleanPayload = "0|0|CLEAN".toByteArray(Charsets.UTF_8)
            lockoutFile.writeBytes(KeystoreSecretManager.encryptSecretBytes(cleanPayload, aesKeyProvider))
            return PinLockoutStatus(
                failedAttempts = 0,
                unlockAtMillis = 0L,
                isFailClosedLocked = false,
                statusReason = "OK"
            )
        }

        val newFailures = currentStatus.failedAttempts + 1
        val delayMs = calculateLockoutDelayMs(newFailures)
        val unlockAt = if (delayMs > 0L) nowMillis + delayMs else 0L
        val payload = "$newFailures|$unlockAt|LOCKED".toByteArray(Charsets.UTF_8)
        lockoutFile.writeBytes(KeystoreSecretManager.encryptSecretBytes(payload, aesKeyProvider))
        return PinLockoutStatus(
            failedAttempts = newFailures,
            unlockAtMillis = unlockAt,
            isFailClosedLocked = false,
            statusReason = "LOCKOUT_ACTIVE"
        )
    }

    fun recordProtectedPinAttempt(
        context: Context,
        isSuccess: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        aesKeyProvider: () -> SecretKey = { KeystoreSecretManager.getOrCreateCredentialsAesKey() }
    ): Pair<Int, Long> {
        val status = recordProtectedPinAttemptInDir(
            storageDir = context.noBackupFilesDir,
            isSuccess = isSuccess,
            nowMillis = nowMillis,
            aesKeyProvider = aesKeyProvider
        )
        return status.failedAttempts to status.unlockAtMillis
    }

    fun cleanupResidualTempFilesOnStartup(context: Context): Int {
        var deletedCount = 0
        val prefixes = listOf("archive_entry", "build_prop_edit", "boot_script", "root_db_tmp", "00000000", "mt_root_write_")
        for (dir in listOfNotNull(context.cacheDir, context.filesDir)) {
            dir.listFiles()?.forEach { file ->
                if (prefixes.any { file.name.startsWith(it) } || file.name.endsWith(".tmp")) {
                    if (file.delete()) deletedCount++
                }
            }
        }
        return deletedCount
    }

    fun generateRandomSessionToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isLocalNetworkAddress(hostAddress: String): Boolean {
        val ip = hostAddress.trim()
        if (ip == "127.0.0.1" || ip == "::1" || ip.startsWith("127.")) return true
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val secondOctet = ip.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
            return secondOctet in 16..31
        }
        return false
    }

    /**
     * Fase 8b.5: Per-IP sliding-window rate limiter for the local HTTP/FTP server.
     * Returns `true` if the request is allowed, or `false` if the IP exceeded [maxRequestsPerMinute].
     */
    fun checkServerIpRateLimit(
        clientIp: String,
        maxRequestsPerMinute: Int = 60,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val cleanIp = clientIp.trim()
        val deque = ipRequestHistory.getOrPut(cleanIp) { ArrayDeque() }
        synchronized(deque) {
            while (deque.isNotEmpty() && nowMillis - deque.first() > 60_000L) {
                deque.removeFirst()
            }
            if (deque.size >= maxRequestsPerMinute) {
                return false
            }
            deque.addLast(nowMillis)
            return true
        }
    }

    fun resetServerIpRateLimits() {
        ipRequestHistory.clear()
    }
}
