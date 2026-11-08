package com.example.core.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class StorageAccessTier {
    ALL_FILES,
    LEGACY_RUNTIME,
    SAF_DOCUMENT_TREE,
    NONE
}

enum class PermissionRequestType {
    MANAGE_ALL_FILES,
    LEGACY_STORAGE,
    STORAGE_ACCESS_FRAMEWORK
}

object PermissionManager {

    private const val PREFS_NAME = "mt_explorer_permissions"
    private const val KEY_SAF_URI = "saf_tree_uri"

    /**
     * Checks if Android 11+ (API 30+) All Files Access permission is granted.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
    }

    /**
     * Checks if legacy READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE permissions are granted.
     */
    fun hasLegacyStoragePermission(context: Context): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val writeGranted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return readGranted && writeGranted
    }

    /**
     * Retrieves the persisted Storage Access Framework (SAF) tree URI if valid and permissions remain active.
     */
    fun getPersistedSafUri(context: Context): Uri? {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_SAF_URI, null) ?: return null

        try {
            val uri = Uri.parse(uriString)
            // Verify this URI is still granted in ContentResolver
            val persistedList = context.contentResolver.persistedUriPermissions
            val hasValidPermission = persistedList.any { perm ->
                perm.uri == uri && perm.isReadPermission
            }
            return if (hasValidPermission) uri else null
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Checks if SAF document tree access is currently available.
     */
    fun hasSafAccess(context: Context): Boolean {
        return getPersistedSafUri(context) != null
    }

    /**
     * Determines current storage access tier.
     */
    fun getStorageAccessTier(context: Context): StorageAccessTier {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasAllFilesAccess()) return StorageAccessTier.ALL_FILES
        } else {
            if (hasLegacyStoragePermission(context)) return StorageAccessTier.LEGACY_RUNTIME
        }

        if (hasSafAccess(context)) return StorageAccessTier.SAF_DOCUMENT_TREE

        return StorageAccessTier.NONE
    }

    /**
     * Checks if the app has any usable level of storage access.
     */
    fun hasAnyStorageAccess(context: Context): Boolean {
        return getStorageAccessTier(context) != StorageAccessTier.NONE
    }

    /**
     * Creates intent to open system Settings for MANAGE_EXTERNAL_STORAGE.
     */
    fun createManageAllFilesIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Exception) {
                return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } else {
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Returns legacy storage permissions array to request on Android 10 and lower.
     */
    fun getLegacyPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Creates intent for Storage Access Framework (SAF) folder picker.
     */
    fun createOpenDocumentTreeIntent(initialUri: Uri? = null): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            addFlags(flags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    /**
     * Takes persistable URI permission and saves the tree URI to SharedPreferences.
     */
    fun persistDocumentTreeUri(context: Context, uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAF_URI, uri.toString())
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Revokes persisted SAF access and removes the stored URI.
     */
    fun revokeSafAccess(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_SAF_URI, null)
        if (uriStr != null) {
            try {
                val uri = Uri.parse(uriStr)
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }
            prefs.edit().remove(KEY_SAF_URI).apply()
        }
    }

    /**
     * Determines which request flow should be recommended to the user.
     */
    fun getRecommendedRequestType(): PermissionRequestType {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionRequestType.MANAGE_ALL_FILES
        } else {
            PermissionRequestType.LEGACY_STORAGE
        }
    }
}
