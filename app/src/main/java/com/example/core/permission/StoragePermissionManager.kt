package com.example.core.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents a comprehensive, immutable snapshot of the device's storage permission status.
 * Consumed by the UI (Composables, ViewModels, Activities) to render banners, dialogs, and navigation states.
 */
data class StoragePermissionUiState(
    val hasAllFilesAccess: Boolean = false,
    val hasLegacyStoragePermission: Boolean = false,
    val hasSafAccess: Boolean = false,
    val currentTier: StorageAccessTier = StorageAccessTier.NONE,
    val persistedSafDirectories: List<SafDirectoryInfo> = emptyList(),
    val showRationaleDialog: Boolean = false,
    val showSafFallbackOffer: Boolean = false,
    val recommendedRequestType: PermissionRequestType = PermissionRequestType.MANAGE_ALL_FILES
) {
    val hasAnyAccess: Boolean
        get() = currentTier != StorageAccessTier.NONE

    val isFullStorageGranted: Boolean
        get() = currentTier == StorageAccessTier.ALL_FILES || currentTier == StorageAccessTier.LEGACY_RUNTIME

    val canUseSafFallbackOnly: Boolean
        get() = !isFullStorageGranted && hasSafAccess
}

/**
 * StoragePermissionManager encapsulates:
 * 1. Direct checks for [Environment.isExternalStorageManager] on Android 11+ (API 30+) with safe legacy fallbacks.
 * 2. The conditional flow between requesting system-wide All Files Access (MANAGE_EXTERNAL_STORAGE)
 *    versus offering Storage Access Framework (SAF) folder picker fallbacks.
 * 3. Reactive StateFlow ([uiState]) providing a clean, cohesive state object for ViewModels and Compose UI.
 */
class StoragePermissionManager(private val context: Context) {

    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow(computeCurrentState())
    val uiState: StateFlow<StoragePermissionUiState> = _uiState.asStateFlow()

    /**
     * Recomputes and updates the reactive [uiState] based on the latest system permission states.
     * Call this on Activity lifecycle events (e.g., onResume) or after returning from system dialogs/activities.
     */
    fun refreshState(): StoragePermissionUiState {
        val currentDialogs = _uiState.value
        val newState = computeCurrentState(
            showRationale = currentDialogs.showRationaleDialog,
            showSafOffer = currentDialogs.showSafFallbackOffer
        )
        _uiState.value = newState
        return newState
    }

    /**
     * Checks directly if the system has granted system-wide All Files Access via Environment.isExternalStorageManager().
     * On Android 10 and lower, returns false as MANAGE_EXTERNAL_STORAGE does not exist.
     */
    fun isExternalStorageManager(): Boolean {
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
     * Checks if legacy READ/WRITE external storage permissions are granted (Android 10 and lower).
     */
    fun hasLegacyStoragePermission(): Boolean {
        return PermissionManager.hasLegacyStoragePermission(appContext)
    }

    /**
     * Checks if any SAF document tree access has been persisted.
     */
    fun hasSafAccess(): Boolean {
        return PermissionManager.hasSafAccess(appContext)
    }

    /**
     * Determines current active storage access tier.
     */
    fun getStorageAccessTier(): StorageAccessTier {
        return PermissionManager.getStorageAccessTier(appContext)
    }

    /**
     * Requests the UI to display the compliance rationale dialog explaining why
     * MANAGE_EXTERNAL_STORAGE is required for file management before redirecting to system settings.
     */
    fun promptRationaleDialog() {
        _uiState.value = _uiState.value.copy(showRationaleDialog = true)
    }

    /**
     * Dismisses the compliance rationale dialog without initiating a permission request.
     */
    fun dismissRationaleDialog() {
        _uiState.value = _uiState.value.copy(showRationaleDialog = false)
    }

    /**
     * Handles user confirmation from the rationale dialog:
     * Dismisses the dialog and launches system-wide Settings permission request.
     */
    fun confirmRationaleAndLaunchSettings(
        manageAllFilesLauncher: ActivityResultLauncher<Intent>,
        legacyLauncher: ActivityResultLauncher<Array<String>>
    ) {
        dismissRationaleDialog()
        when (PermissionManager.getRecommendedRequestType()) {
            PermissionRequestType.MANAGE_ALL_FILES -> {
                val intent = PermissionManager.createManageAllFilesIntent(appContext)
                manageAllFilesLauncher.launch(intent)
            }
            PermissionRequestType.LEGACY_STORAGE -> {
                legacyLauncher.launch(PermissionManager.getLegacyPermissions())
            }
            PermissionRequestType.STORAGE_ACCESS_FRAMEWORK -> {
                promptSafFallback()
            }
        }
    }

    /**
     * Handles returning from the system Settings / Permission screen.
     * Evaluates whether full storage access was granted. If NOT granted, conditionally triggers
     * the SAF fallback offer dialog if the user doesn't already have SAF access.
     */
    fun onPermissionResultReturned(): StoragePermissionUiState {
        val current = refreshState()
        if (!current.isFullStorageGranted && !current.hasSafAccess) {
            promptSafFallback()
        }
        return _uiState.value
    }

    /**
     * Triggers the SAF fallback offer dialog prompting the user to grant individual folder access.
     */
    fun promptSafFallback() {
        _uiState.value = _uiState.value.copy(showSafFallbackOffer = true)
    }

    /**
     * Dismisses the SAF fallback offer dialog.
     */
    fun dismissSafFallback() {
        _uiState.value = _uiState.value.copy(showSafFallbackOffer = false)
    }

    /**
     * Launches the Storage Access Framework (SAF) folder picker (`ACTION_OPEN_DOCUMENT_TREE`).
     */
    fun launchSafFolderPicker(pickerLauncher: ActivityResultLauncher<Uri?>) {
        dismissSafFallback()
        pickerLauncher.launch(null)
    }

    /**
     * Handles a newly granted SAF tree URI: persists permissions and updates state.
     */
    fun onSafFolderGranted(uri: Uri): Boolean {
        val success = PermissionManager.persistDocumentTreeUri(appContext, uri)
        refreshState()
        return success
    }

    /**
     * Revokes access to a previously granted SAF folder URI.
     */
    fun revokeSafAccess(uri: Uri) {
        PermissionManager.revokeSafAccess(appContext, uri)
        refreshState()
    }

    private fun computeCurrentState(
        showRationale: Boolean = false,
        showSafOffer: Boolean = false
    ): StoragePermissionUiState {
        val allFiles = isExternalStorageManager()
        val legacy = hasLegacyStoragePermission()
        val saf = hasSafAccess()
        val tier = when {
            allFiles -> StorageAccessTier.ALL_FILES
            legacy && Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> StorageAccessTier.LEGACY_RUNTIME
            saf -> StorageAccessTier.SAF_DOCUMENT_TREE
            else -> StorageAccessTier.NONE
        }
        val persistedUris = PermissionManager.getPersistedSafUris(appContext)
        val safInfos = persistedUris.map { PermissionManager.getSafDirectoryInfo(appContext, it) }

        return StoragePermissionUiState(
            hasAllFilesAccess = allFiles,
            hasLegacyStoragePermission = legacy,
            hasSafAccess = saf,
            currentTier = tier,
            persistedSafDirectories = safInfos,
            showRationaleDialog = showRationale,
            showSafFallbackOffer = showSafOffer,
            recommendedRequestType = PermissionManager.getRecommendedRequestType()
        )
    }
}
