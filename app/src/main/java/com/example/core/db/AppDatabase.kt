package com.example.core.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.example.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val paneId: String, // A or B
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "file_tags")
data class FileTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val tagName: String,
    val colorHex: String = "#00E5FF",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Fase 2, Fase 8.5, Fase 8b.1 & Fase 8c.3:
 * Operation Log with Keystore-backed HMAC-SHA256 Chain (`prevHash` -> `entryHash`)
 * and separate Head HMAC Anchor storage to detect SQLite row modification, deletion, or tail truncation.
 */
@Entity(tableName = "operation_logs")
data class OperationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val operationType: String, // DELETE, OVERWRITE, CHMOD, CHOWN, REMOUNT, DUPLICATE_TRASH, BUILD_PROP, RENAME, INTEGRITY_ALERT
    val targetPath: String,
    val secondaryPath: String = "", // backupPath or oldPath/newPath
    val details: String = "",
    val canUndo: Boolean = false,
    val isUndone: Boolean = false,
    val prevHash: String = "GENESIS",
    val entryHash: String = ""
)

@Entity(tableName = "queued_tasks")
data class QueuedTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String, // COPY, MOVE, DELETE, EXTRACT, COMPRESS, DUPLICATE_CLEAN
    val sourcePathsJoined: String,
    val destinationPath: String,
    val status: String, // QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    val progressPercent: Int = 0,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY name ASC")
    suspend fun getAll(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks ORDER BY name ASC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM bookmarks WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): BookmarkEntity?
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecent(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE paneId = :paneId")
    suspend fun clearForPane(paneId: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

@Dao
interface FileTagDao {
    @Query("SELECT * FROM file_tags ORDER BY createdAt DESC")
    fun observeAllTags(): Flow<List<FileTagEntity>>

    @Query("SELECT * FROM file_tags WHERE path = :path")
    fun observeTagsForPath(path: String): Flow<List<FileTagEntity>>

    @Query("SELECT * FROM file_tags WHERE tagName = :tagName")
    suspend fun getFilesByTag(tagName: String): List<FileTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: FileTagEntity): Long

    @Query("DELETE FROM file_tags WHERE id = :id")
    suspend fun deleteTagById(id: Long)

    @Query("DELETE FROM file_tags WHERE path = :path AND tagName = :tagName")
    suspend fun deleteTagForPath(path: String, tagName: String)
}

@Dao
interface OperationLogDao {
    @Query("SELECT * FROM operation_logs ORDER BY timestamp DESC LIMIT 200")
    fun observeRecentLogs(): Flow<List<OperationLogEntity>>

    @Query("SELECT * FROM operation_logs ORDER BY timestamp DESC LIMIT 200")
    suspend fun getRecentLogs(): List<OperationLogEntity>

    @Query("SELECT * FROM operation_logs ORDER BY id ASC")
    suspend fun getAllLogsAscending(): List<OperationLogEntity>

    @Query("SELECT * FROM operation_logs ORDER BY id DESC LIMIT 1")
    suspend fun getLatestLog(): OperationLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: OperationLogEntity): Long

    @Query("UPDATE operation_logs SET isUndone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query("DELETE FROM operation_logs")
    suspend fun clearAllLogs()
}

@Dao
interface QueuedTaskDao {
    @Query("SELECT * FROM queued_tasks ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<QueuedTaskEntity>>

    @Query("SELECT * FROM queued_tasks WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED') ORDER BY createdAt ASC")
    suspend fun getPendingTasks(): List<QueuedTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: QueuedTaskEntity): Long

    @Update
    suspend fun updateTask(task: QueuedTaskEntity)

    @Query("UPDATE queued_tasks SET status = :status, progressPercent = :progress, processedBytes = :processed, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(
        id: Long,
        status: String,
        progress: Int,
        processed: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM queued_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("DELETE FROM queued_tasks WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED')")
    suspend fun clearFinishedTasks()
}

@Database(
    entities = [
        BookmarkEntity::class,
        HistoryEntity::class,
        FileTagEntity::class,
        OperationLogEntity::class,
        QueuedTaskEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun fileTagDao(): FileTagDao
    abstract fun operationLogDao(): OperationLogDao
    abstract fun queuedTaskDao(): QueuedTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mt_explorer.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

/**
 * Fase 8c.3: Explicitly distinguishes:
 * - [VALID]: HMAC chain and Head Anchor verified intact.
 * - [KEY_UNAVAILABLE]: AndroidKeyStore key is missing, invalidated, or hardware Keystore is unavailable (NOT data tampering).
 * - [TAMPER_DETECTED]: Cryptographic mismatch in `prevHash`, `entryHash`, or Head Anchor (active tampering / row deletion / truncation).
 */
enum class ChainVerificationStatus {
    VALID,
    KEY_UNAVAILABLE,
    TAMPER_DETECTED
}

class KeystoreKeyUnavailableException(message: String, cause: Throwable? = null) : KeyStoreException(message, cause)

data class HashChainVerificationResult(
    val isValid: Boolean,
    val totalChecked: Int,
    val brokenAtLogId: Long? = null,
    val headAnchorMatched: Boolean = true,
    val status: ChainVerificationStatus = if (isValid) ChainVerificationStatus.VALID else ChainVerificationStatus.TAMPER_DETECTED,
    val reason: String = "OK"
)

/**
 * Fase 8b.1 & 8c.3: Separate storage for the Operation Log Head HMAC outside the Room SQLite database.
 * Encrypted at rest using Android Keystore AES-256-GCM in `noBackupFilesDir` so an attacker
 * who deletes or truncates rows in `mt_explorer.db` is immediately detected.
 * No silent plaintext fallback is permitted in production; unit tests may inject a test AES key via constructor.
 */
interface HeadHmacAnchorStore {
    fun readHeadAnchor(): Pair<String, Int>?
    fun writeHeadAnchor(headHmac: String, entryCount: Int)
}

class KeystoreEncryptedHeadAnchorStore(
    private val anchorFile: File,
    private val aesKeyProvider: () -> SecretKey = { getOrCreateHardwareAnchorAesKey() }
) : HeadHmacAnchorStore {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ANCHOR_AES_ALIAS = "mt_explorer_oplog_anchor_aes256_key"

        fun getOrCreateHardwareAnchorAesKey(): SecretKey {
            try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existing = ks.getEntry(ANCHOR_AES_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (existing != null) return existing.secretKey
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                kg.init(
                    KeyGenParameterSpec.Builder(
                        ANCHOR_AES_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                return kg.generateKey()
            } catch (t: Throwable) {
                throw KeystoreKeyUnavailableException(
                    "Kunci AndroidKeyStore untuk Head Anchor ('$ANCHOR_AES_ALIAS') tidak tersedia: ${t.message}",
                    t
                )
            }
        }
    }

    override fun readHeadAnchor(): Pair<String, Int>? {
        if (!anchorFile.exists()) return null
        val raw = anchorFile.readBytes()
        val text = valDecrypt(raw) ?: throw SecurityException("Head Anchor rusak atau gagal didekripsi dengan kunci Keystore!")
        val parts = text.split("|")
        if (parts.size < 2) throw SecurityException("Format Head Anchor korup!")
        val hmac = parts[0].trim()
        val count = parts[1].trim().toIntOrNull() ?: throw SecurityException("Nilai jumlah entri Head Anchor korup!")
        return hmac to count
    }

    override fun writeHeadAnchor(headHmac: String, entryCount: Int) {
        anchorFile.parentFile?.mkdirs()
        val payload = "$headHmac|$entryCount".toByteArray(Charsets.UTF_8)
        val encrypted = valEncrypt(payload)
        anchorFile.writeBytes(encrypted)
    }

    private fun valEncrypt(plain: ByteArray): ByteArray {
        val ksKey = aesKeyProvider()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, ksKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        return ByteArray(1 + iv.size + cipherText.size).also { out ->
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(cipherText, 0, out, 1 + iv.size, cipherText.size)
        }
    }

    private fun valDecrypt(data: ByteArray): String? {
        val ksKey = aesKeyProvider()
        if (data.size < 14) return null
        val ivLen = data[0].toInt() and 0xFF
        if (ivLen !in 12..16 || data.size <= 1 + ivLen) return null
        val iv = data.copyOfRange(1, 1 + ivLen)
        val cipherText = data.copyOfRange(1 + ivLen, data.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, ksKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw SecurityException("Head Anchor gagal verifikasi tag autentikasi AES-GCM (indikasi manipulasi): ${e.message}", e)
        }
    }
}

class InMemoryHeadAnchorStore : HeadHmacAnchorStore {
    @Volatile
    private var anchor: Pair<String, Int>? = null

    override fun readHeadAnchor(): Pair<String, Int>? = anchor

    override fun writeHeadAnchor(headHmac: String, entryCount: Int) {
        anchor = headHmac to entryCount
    }
}

/**
 * Fase 8b.1 & Fase 8c.3:
 * OperationLogRepository strictly requires a hardware/AndroidKeyStore `HmacSHA256` key in production
 * (no software fallback key in production code!).
 * Unit tests inject an explicit `hmacKeyProvider` via constructor.
 */
class OperationLogRepository(
    private val dao: OperationLogDao,
    private val headAnchorStore: HeadHmacAnchorStore? = null,
    private val hmacKeyProvider: () -> SecretKey = { getOrCreateKeystoreHmacKey() }
) {
    constructor(context: Context, dao: OperationLogDao) : this(
        dao = dao,
        headAnchorStore = KeystoreEncryptedHeadAnchorStore(
            File(context.noBackupFilesDir, "oplog_head_anchor.mac")
        ),
        hmacKeyProvider = { getOrCreateKeystoreHmacKey() }
    )

    val logsFlow: Flow<List<OperationLogEntity>> = dao.observeRecentLogs()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val OPLOG_HMAC_KEY_ALIAS = "mt_explorer_oplog_hmac_sha256_key"

        /**
         * Fase 8b.1: Global coroutine Mutex that strictly serializes all Operation Log writes
         * so concurrent callers can never race on `prevHash` or head anchor updates.
         */
        val logWriteMutex = Mutex()

        const val GENESIS_HASH = "GENESIS_HMAC_0000000000000000000000000000000000000000000000000000"

        /**
         * Fase 8c.3: Retrieves or generates a non-exportable `HmacSHA256` key inside AndroidKeyStore
         * (`PURPOSE_SIGN or PURPOSE_VERIFY`).
         * Does NOT fall back to any software key! If `AndroidKeyStore` is missing or fails,
         * throws [KeystoreKeyUnavailableException]. Unit tests must use constructor/parameter injection.
         */
        fun getOrCreateKeystoreHmacKey(requireExistingOnly: Boolean = false): SecretKey {
            try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existing = ks.getEntry(OPLOG_HMAC_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (existing != null) {
                    return existing.secretKey
                }
                if (requireExistingOnly) {
                    throw KeystoreKeyUnavailableException(
                        "Kunci HMAC Operation Log ('$OPLOG_HMAC_KEY_ALIAS') tidak ditemukan di AndroidKeyStore (kemungkinan Keystore di-reset)."
                    )
                }
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    OPLOG_HMAC_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                return keyGenerator.generateKey()
            } catch (e: KeystoreKeyUnavailableException) {
                throw e
            } catch (t: Throwable) {
                throw KeystoreKeyUnavailableException(
                    "AndroidKeyStore tidak tersedia untuk kunci HMAC '$OPLOG_HMAC_KEY_ALIAS' (fallback software dilarang di kode produksi): ${t.message}",
                    t
                )
            }
        }

        /**
         * Fase 8b.1 & 8c.3: Computes `HmacSHA256` over the chained log fields using [secretKey].
         */
        fun computeEntryHmac(
            prevHash: String,
            timestamp: Long,
            operationType: String,
            targetPath: String,
            secondaryPath: String,
            details: String,
            secretKey: SecretKey = getOrCreateKeystoreHmacKey()
        ): String {
            val payload = "$prevHash|$timestamp|$operationType|$targetPath|$secondaryPath|$details"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKey)
            val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Verifies an ordered list of [OperationLogEntity] entries (oldest to newest)
         * using `HmacSHA256`, and distinguishes between:
         * - [ChainVerificationStatus.KEY_UNAVAILABLE] when the Keystore key is missing/unavailable
         * - [ChainVerificationStatus.TAMPER_DETECTED] when a row's HMAC, `prevHash`, or Head Anchor fails verification
         * - [ChainVerificationStatus.VALID] when all entries and the Head Anchor match.
         */
        fun verifyChainOfEntries(
            entriesAscending: List<OperationLogEntity>,
            expectedHeadAnchor: Pair<String, Int>? = null,
            secretKeyProvider: () -> SecretKey = { getOrCreateKeystoreHmacKey(requireExistingOnly = entriesAscending.isNotEmpty()) }
        ): HashChainVerificationResult {
            val secretKey: SecretKey = try {
                secretKeyProvider()
            } catch (e: KeystoreKeyUnavailableException) {
                return HashChainVerificationResult(
                    isValid = false,
                    totalChecked = 0,
                    headAnchorMatched = false,
                    status = ChainVerificationStatus.KEY_UNAVAILABLE,
                    reason = "KEY_UNAVAILABLE: Kunci AndroidKeyStore tidak ditemukan / tidak dapat diakses (${e.message})"
                )
            } catch (t: Throwable) {
                return HashChainVerificationResult(
                    isValid = false,
                    totalChecked = 0,
                    headAnchorMatched = false,
                    status = ChainVerificationStatus.KEY_UNAVAILABLE,
                    reason = "KEY_UNAVAILABLE: Gagal memuat kunci HMAC dari Keystore (${t.message})"
                )
            }

            if (entriesAscending.isEmpty()) {
                if (expectedHeadAnchor != null && expectedHeadAnchor.second > 0) {
                    return HashChainVerificationResult(
                        isValid = false,
                        totalChecked = 0,
                        headAnchorMatched = false,
                        status = ChainVerificationStatus.TAMPER_DETECTED,
                        reason = "TAMPER_DETECTED: Tabel log kosong padahal Head Anchor mencatat ${expectedHeadAnchor.second} entri (Indikasi penghapusan paksa!)"
                    )
                }
                return HashChainVerificationResult(
                    isValid = true,
                    totalChecked = 0,
                    status = ChainVerificationStatus.VALID,
                    reason = "Log kosong (Valid)"
                )
            }

            var expectedPrev = GENESIS_HASH
            for (entry in entriesAscending) {
                if (entry.prevHash != expectedPrev) {
                    return HashChainVerificationResult(
                        isValid = false,
                        totalChecked = entriesAscending.size,
                        brokenAtLogId = entry.id,
                        status = ChainVerificationStatus.TAMPER_DETECTED,
                        reason = "TAMPER_DETECTED: prevHash mismatch pada ID=${entry.id} (expected=${expectedPrev.take(12)}..., actual=${entry.prevHash.take(12)}...)"
                    )
                }
                val recomputed = computeEntryHmac(
                    prevHash = entry.prevHash,
                    timestamp = entry.timestamp,
                    operationType = entry.operationType,
                    targetPath = entry.targetPath,
                    secondaryPath = entry.secondaryPath,
                    details = entry.details,
                    secretKey = secretKey
                )
                if (!MessageDigest.isEqual(recomputed.toByteArray(), entry.entryHash.toByteArray())) {
                    return HashChainVerificationResult(
                        isValid = false,
                        totalChecked = entriesAscending.size,
                        brokenAtLogId = entry.id,
                        status = ChainVerificationStatus.TAMPER_DETECTED,
                        reason = "TAMPER_DETECTED: HMAC-SHA256 tidak cocok / dimodifikasi pada ID=${entry.id}"
                    )
                }
                expectedPrev = entry.entryHash
            }

            if (expectedHeadAnchor != null) {
                val (anchorHmac, anchorCount) = expectedHeadAnchor
                if (entriesAscending.size != anchorCount ||
                    !MessageDigest.isEqual(expectedPrev.toByteArray(), anchorHmac.toByteArray())
                ) {
                    return HashChainVerificationResult(
                        isValid = false,
                        totalChecked = entriesAscending.size,
                        brokenAtLogId = entriesAscending.last().id,
                        headAnchorMatched = false,
                        status = ChainVerificationStatus.TAMPER_DETECTED,
                        reason = "TAMPER_DETECTED: Head HMAC Anchor mismatch! (DB count=${entriesAscending.size}, Anchor count=$anchorCount) — Indikasi pemotongan/rollback log!"
                    )
                }
            }

            return HashChainVerificationResult(
                isValid = true,
                totalChecked = entriesAscending.size,
                headAnchorMatched = true,
                status = ChainVerificationStatus.VALID,
                reason = "Integritas HMAC-SHA256 Keystore Chain & Head Anchor terverifikasi (${entriesAscending.size} entri utuh)"
            )
        }
    }

    /**
     * Fase 8b.1 & 8c.3: Serialized with [logWriteMutex] so concurrent log writes never race on `prevHash`
     * and atomically update the separate [headAnchorStore].
     */
    suspend fun recordOperation(
        operationType: String,
        targetPath: String,
        secondaryPath: String = "",
        details: String = "",
        canUndo: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        logWriteMutex.withLock {
            val secretKey = hmacKeyProvider()
            val now = System.currentTimeMillis()
            val allLogs = dao.getAllLogsAscending()
            val latest = allLogs.lastOrNull()
            val prevHash = latest?.entryHash?.takeIf { it.isNotEmpty() } ?: GENESIS_HASH
            val entryHash = computeEntryHmac(
                prevHash = prevHash,
                timestamp = now,
                operationType = operationType,
                targetPath = targetPath,
                secondaryPath = secondaryPath,
                details = details,
                secretKey = secretKey
            )
            val id = dao.insertLog(
                OperationLogEntity(
                    timestamp = now,
                    operationType = operationType,
                    targetPath = targetPath,
                    secondaryPath = secondaryPath,
                    details = details,
                    canUndo = canUndo,
                    isUndone = false,
                    prevHash = prevHash,
                    entryHash = entryHash
                )
            )
            headAnchorStore?.writeHeadAnchor(entryHash, allLogs.size + 1)
            id
        }
    }

    suspend fun verifyHashChainIntegrity(): HashChainVerificationResult = withContext(Dispatchers.IO) {
        logWriteMutex.withLock {
            val allAscending = dao.getAllLogsAscending()
            val anchor = try {
                headAnchorStore?.readHeadAnchor()
            } catch (e: KeystoreKeyUnavailableException) {
                return@withLock HashChainVerificationResult(
                    isValid = false,
                    totalChecked = 0,
                    headAnchorMatched = false,
                    status = ChainVerificationStatus.KEY_UNAVAILABLE,
                    reason = "KEY_UNAVAILABLE: Kunci Head Anchor tidak tersedia (${e.message})"
                )
            } catch (se: SecurityException) {
                return@withLock HashChainVerificationResult(
                    isValid = false,
                    totalChecked = allAscending.size,
                    headAnchorMatched = false,
                    status = ChainVerificationStatus.TAMPER_DETECTED,
                    reason = "TAMPER_DETECTED: ${se.message}"
                )
            }
            verifyChainOfEntries(
                entriesAscending = allAscending,
                expectedHeadAnchor = anchor,
                secretKeyProvider = hmacKeyProvider
            )
        }
    }

    suspend fun undoOperation(entry: OperationLogEntity): Result<String> = withContext(Dispatchers.IO) {
        if (!entry.canUndo || entry.isUndone) {
            return@withContext Result.failure(IllegalStateException("Operasi ini tidak dapat di-undo atau sudah di-undo."))
        }
        try {
            when (entry.operationType) {
                "DELETE", "OVERWRITE", "BUILD_PROP", "DUPLICATE_TRASH", "SYSTEM_EDIT" -> {
                    val backupFile = File(entry.secondaryPath)
                    if (!backupFile.exists()) {
                        return@withContext Result.failure(IllegalStateException("File cadangan tidak ditemukan di ${entry.secondaryPath}"))
                    }
                    val destFile = File(entry.targetPath)
                    val restored = if (destFile.parentFile?.canWrite() == true) {
                        destFile.parentFile?.mkdirs()
                        backupFile.copyTo(destFile, overwrite = true)
                        if (entry.operationType == "DUPLICATE_TRASH") {
                            backupFile.delete()
                            File(backupFile.parent, "${backupFile.name}.meta").delete()
                        }
                        true
                    } else {
                        val res = RootManager.rootCopy(backupFile.absolutePath, destFile.absolutePath)
                        res.isSuccess
                    }
                    if (!restored) {
                        return@withContext Result.failure(IllegalStateException("Gagal memulihkan file ke ${entry.targetPath}"))
                    }
                    dao.markUndone(entry.id)
                    Result.success("Berhasil memulihkan ${entry.targetPath}")
                }
                "RENAME" -> {
                    val currentFile = File(entry.secondaryPath)
                    val originalFile = File(entry.targetPath)
                    val ok = if (currentFile.parentFile?.canWrite() == true) {
                        currentFile.renameTo(originalFile)
                    } else {
                        RootManager.rootRename(entry.secondaryPath, entry.targetPath).isSuccess
                    }
                    if (!ok) {
                        return@withContext Result.failure(IllegalStateException("Gagal mengembalikan nama ke ${entry.targetPath}"))
                    }
                    dao.markUndone(entry.id)
                    Result.success("Nama file dikembalikan ke ${originalFile.name}")
                }
                "CHMOD" -> {
                    val previousMode = entry.secondaryPath.trim()
                    if (previousMode.isEmpty()) {
                        return@withContext Result.failure(IllegalStateException("Mode permission sebelumnya tidak tercatat"))
                    }
                    val res = RootManager.rootChmod(entry.targetPath, previousMode, recursive = false)
                    if (!res.isSuccess) {
                        return@withContext Result.failure(IllegalStateException("Gagal chmod kembali: ${res.errorText}"))
                    }
                    dao.markUndone(entry.id)
                    Result.success("Permission ${entry.targetPath} dikembalikan ke $previousMode")
                }
                "REMOUNT" -> {
                    val res = RootManager.remountPath(entry.targetPath, writable = false, pinnedByUser = false)
                    if (res is com.example.core.root.RemountOutcome.Success) {
                        dao.markUndone(entry.id)
                        Result.success("Mountpoint ${entry.targetPath} dikembalikan ke RO")
                    } else {
                        Result.failure(IllegalStateException("Gagal mengembalikan mount ke RO"))
                    }
                }
                else -> Result.failure(IllegalStateException("Tipe operasi ${entry.operationType} belum mendukung rollback otomatis"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
