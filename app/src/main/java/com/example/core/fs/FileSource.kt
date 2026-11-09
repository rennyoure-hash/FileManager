package com.example.core.fs

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.example.core.archive.ArchiveFileSystem
import com.example.core.archive.ArchiveFormat
import com.example.core.archive.ArchiveUtils
import com.example.core.root.MountAnalyzer
import com.example.core.root.RootManager
import com.example.core.tools.KeystoreSecretManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey

/**
 * Fase 3 & Fase 6: Unified FileSource interface so local directories, root paths,
 * archives (ZIP/TAR/7z/RAR), and remote network shares can be browsed with the same UI.
 */
interface FileSource {
    val sourceId: String
    val displayName: String
    val rootPath: VPath
    val isReadOnly: Boolean

    suspend fun listEntries(path: VPath): List<VEntry>
    suspend fun stat(path: VPath): VEntry?
    suspend fun openInputStream(path: VPath): InputStream
}

class LocalOrRootFileSource(
    private val fileSystem: FileSystem,
    override val rootPath: VPath = VPath.ROOT
) : FileSource {
    override val sourceId: String = fileSystem.id
    override val displayName: String = fileSystem.name
    override val isReadOnly: Boolean = !fileSystem.capabilities.contains(Capability.ATOMIC_RENAME)

    override suspend fun listEntries(path: VPath): List<VEntry> = withContext(Dispatchers.IO) {
        fileSystem.list(path)
    }

    override suspend fun stat(path: VPath): VEntry? = withContext(Dispatchers.IO) {
        fileSystem.stat(path)
    }

    override suspend fun openInputStream(path: VPath): InputStream = withContext(Dispatchers.IO) {
        fileSystem.openRead(path)
    }
}

class ArchiveBrowseFileSource(
    private val archiveFile: File
) : FileSource {
    override val sourceId: String = "archive:${archiveFile.absolutePath}"
    override val displayName: String = archiveFile.name
    override val rootPath: VPath = VPath(archiveFile.absolutePath)
    override val isReadOnly: Boolean = true

    private val delegate = ArchiveFileSystem(rootPath, archiveFile)

    override suspend fun listEntries(path: VPath): List<VEntry> = withContext(Dispatchers.IO) {
        val format = ArchiveUtils.formatOf(archiveFile.name)
        when (format) {
            ArchiveFormat.ZIP -> delegate.list(path)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> {
                val escaped = MountAnalyzer.escapeShellPath(archiveFile.absolutePath)
                val cmd = when (format) {
                    ArchiveFormat.TAR -> "tar -tf $escaped 2>/dev/null"
                    ArchiveFormat.TAR_GZ -> "tar -ztf $escaped 2>/dev/null"
                    else -> ""
                }
                val out = if (cmd.isNotEmpty()) {
                    RootManager.executeCommand(cmd, reportErrorToUi = false).stdout
                } else {
                    emptyList()
                }
                out.mapNotNull { line ->
                    val trimmed = line.trim().trimEnd('/')
                    if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.contains("..")) null
                    else VEntry(
                        name = trimmed.substringAfterLast('/'),
                        path = VPath("${archiveFile.absolutePath}/$trimmed"),
                        isDirectory = line.trim().endsWith("/"),
                        size = 0L,
                        lastModified = archiveFile.lastModified()
                    )
                }
            }
            else -> delegate.list(path)
        }
    }

    override suspend fun stat(path: VPath): VEntry? = withContext(Dispatchers.IO) {
        delegate.stat(path)
    }

    override suspend fun openInputStream(path: VPath): InputStream = withContext(Dispatchers.IO) {
        delegate.openRead(path)
    }
}

data class ApkDetailedMetadata(
    val archivePath: String,
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val signatureSha256: String,
    val dexFiles: List<String>,
    val nativeAbis: List<String>
)

object ApkInspectorAndInstaller {

    suspend fun inspectApkFile(context: Context, apkFile: File): ApkDetailedMetadata? = withContext(Dispatchers.IO) {
        if (!apkFile.exists()) return@withContext null
        try {
            val pm = context.packageManager
            val flags = PackageManager.GET_PERMISSIONS or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return@withContext null
            val appInfo = pkgInfo.applicationInfo
            appInfo?.sourceDir = apkFile.absolutePath
            appInfo?.publicSourceDir = apkFile.absolutePath

            val label = try {
                appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgInfo.packageName
            } catch (_: Throwable) {
                pkgInfo.packageName
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appInfo?.minSdkVersion ?: 0
            } else 0
            val targetSdk = appInfo?.targetSdkVersion ?: 0
            val perms = pkgInfo.requestedPermissions?.toList().orEmpty()

            val sigBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures?.firstOrNull()?.toByteArray()
            }
            val sigSha256 = if (sigBytes != null) {
                MessageDigest.getInstance("SHA-256").digest(sigBytes).joinToString(":") { "%02X".format(it) }
            } else {
                "Tidak tersedia"
            }

            val dexList = mutableListOf<String>()
            val abis = mutableSetOf<String>()
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    if (name.endsWith(".dex") && !name.contains("/")) {
                        dexList.add("$name (${e.size / 1024} KB)")
                    } else if (name.startsWith("lib/") && name.endsWith(".so")) {
                        val abi = name.removePrefix("lib/").substringBefore("/")
                        if (abi.isNotEmpty()) abis.add(abi)
                    }
                }
            }

            ApkDetailedMetadata(
                archivePath = apkFile.absolutePath,
                packageName = pkgInfo.packageName,
                appLabel = label,
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                permissions = perms,
                signatureSha256 = sigSha256,
                dexFiles = dexList.sorted(),
                nativeAbis = abis.sorted()
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Fase 8b.8: Uses `PendingIntent.FLAG_IMMUTABLE` on all PendingIntents.
     */
    suspend fun installSplitApksSession(context: Context, apkFiles: List<File>): Result<Int> = withContext(Dispatchers.IO) {
        if (apkFiles.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Tidak ada file APK untuk diinstal"))
        }
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val totalSize = apkFiles.sumOf { it.length() }
            if (totalSize > 0) {
                params.setSize(totalSize)
            }
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                for (apk in apkFiles) {
                    FileInputStream(apk).use { input ->
                        session.openWrite(apk.name, 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                }
                val intent = Intent(context, context.javaClass)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pendingIntent = PendingIntent.getActivity(context, sessionId, intent, flags)
                session.commit(pendingIntent.intentSender)
            }
            Result.success(sessionId)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

/**
 * Fase 6, Fase 8b.2 & Fase 8c.4:
 * AES-256-GCM Encrypted Vault (Android Keystore backed) where ONLY the Vault key is biometric/user-auth-bound:
 * - `setUserAuthenticationRequired(true)`
 * - `setInvalidatedByBiometricEnrollment(true)`
 * - Unlocked exclusively via `BiometricVaultAuthenticator.authenticateVaultCipher` (`BiometricPrompt.CryptoObject(cipher)`).
 */
object SecurityVaultAndShredder {
    const val FLASH_F2FS_SHRED_WARNING =
        "Peringatan Keamanan: Pada penyimpanan Flash modern (UFS/eMMC) dengan filesystem F2FS/EXT4 " +
            "dan Wear-Leveling, penimpaan blok fisik (Secure Delete Overwrite) tidak menjamin penghapusan " +
            "seluruh jejak blok fisik di controller NAND. Gunakan enkripsi penuh (FBE / Vault AES-256-GCM) untuk data sangat rahasia."

    /**
     * Delegates to [KeystoreSecretManager.getOrCreateBiometricAuthenticatedKey] so there is a single
     * biometric-bound Vault key (`mt_explorer_biometric_vault_aes256_key`) in the application.
     */
    fun getOrCreateBiometricBoundVaultKey(): SecretKey =
        KeystoreSecretManager.getOrCreateBiometricAuthenticatedKey()

    /**
     * Initializes a [Cipher] bound to [getOrCreateBiometricBoundVaultKey] to be wrapped in a
     * `BiometricPrompt.CryptoObject(cipher)` before encrypting/decrypting biometric-locked vault items.
     */
    fun createCipherForBiometricCryptoObject(encryptMode: Boolean, iv: ByteArray? = null): Cipher =
        KeystoreSecretManager.createBiometricCryptoObjectCipher(encryptMode = encryptMode, iv = iv)

    /**
     * Reads the 12-byte GCM IV header from an encrypted Vault file so a decrypt-mode [Cipher]
     * can be initialized and passed into `BiometricVaultAuthenticator.authenticateVaultCipher(encryptMode = false, iv = iv)`.
     */
    fun readVaultFileIv(vaultFile: File): ByteArray {
        FileInputStream(vaultFile).use { fileIn ->
            val ivLen = fileIn.read()
            require(ivLen in 12..16) { "Header IV file vault tidak valid" }
            val iv = ByteArray(ivLen)
            val readIv = fileIn.read(iv)
            require(readIv == ivLen) { "Gagal membaca IV dari file vault" }
            return iv
        }
    }

    /**
     * Streaming encryption of a file using an authenticated [authenticatedCipher] from `BiometricPrompt.CryptoObject`.
     */
    suspend fun encryptFileStreaming(
        sourceFile: File,
        destVaultFile: File,
        authenticatedCipher: Cipher
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val iv = authenticatedCipher.iv
            destVaultFile.parentFile?.mkdirs()
            FileOutputStream(destVaultFile).use { fileOut ->
                fileOut.write(iv.size)
                fileOut.write(iv)
                CipherOutputStream(fileOut, authenticatedCipher).use { cipherOut ->
                    FileInputStream(sourceFile).use { input ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            cipherOut.write(buf, 0, read)
                        }
                    }
                }
            }
            Result.success(destVaultFile)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Streaming decryption of a vault file using an authenticated [authenticatedCipher] from `BiometricPrompt.CryptoObject`.
     */
    suspend fun decryptFileStreaming(
        vaultFile: File,
        destPlainFile: File,
        authenticatedCipher: Cipher
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            FileInputStream(vaultFile).use { fileIn ->
                val ivLen = fileIn.read()
                require(ivLen in 12..16) { "Header IV file vault tidak valid" }
                val iv = ByteArray(ivLen)
                val readIv = fileIn.read(iv)
                require(readIv == ivLen) { "Gagal membaca IV dari file vault" }

                destPlainFile.parentFile?.mkdirs()
                CipherInputStream(fileIn, authenticatedCipher).use { cipherIn ->
                    FileOutputStream(destPlainFile).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        while (cipherIn.read(buf).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            out.write(buf, 0, read)
                        }
                    }
                }
            }
            Result.success(destPlainFile)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun secureDeleteFile(file: File, passes: Int = 2): Result<Unit> = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(IllegalArgumentException("File tidak ditemukan: ${file.absolutePath}"))
        }
        try {
            val length = file.length()
            val random = SecureRandom()
            val buf = ByteArray(16 * 1024)

            RandomAccessFile(file, "rws").use { raf ->
                for (pass in 0 until passes.coerceIn(1, 3)) {
                    raf.seek(0L)
                    var remaining = length
                    while (remaining > 0L) {
                        currentCoroutineContext().ensureActive()
                        val chunk = minOf(remaining, buf.size.toLong()).toInt()
                        if (pass == 0) {
                            buf.fill(0, 0, chunk)
                        } else {
                            random.nextBytes(buf)
                        }
                        raf.write(buf, 0, chunk)
                        remaining -= chunk
                    }
                    raf.fd.sync()
                }
            }
            val scrubbedFile = File(file.parentFile, "00000000.tmp")
            val targetToDelete = if (file.renameTo(scrubbedFile)) scrubbedFile else file
            if (!targetToDelete.delete()) {
                return@withContext Result.failure(IllegalStateException("Gagal menghapus file setelah overwrite"))
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
