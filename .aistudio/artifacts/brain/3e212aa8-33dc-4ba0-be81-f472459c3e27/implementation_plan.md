# Plan: MANAGE_EXTERNAL_STORAGE Permission Flow & Compliance

## Overview
Implement compliant, user-friendly `MANAGE_EXTERNAL_STORAGE` permission handling for MT Explorer in accordance with Android 11+ (API 30+) storage policies and Google Play guidelines for file management core use cases.

Based on user preferences:
1. **Educational Compliance Rationale Dialog**: Before launching system settings, display an explicit dialog explaining why a full-featured file manager requires All Files Access (`MANAGE_EXTERNAL_STORAGE`), detailing its purpose (managing, archiving, viewing, and organizing files across shared storage).
2. **Settings Direct Flow**: Launch `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (with fallback to `Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` or app details).
3. **SAF Fallback on Denial**: If the user declines All Files Access or returns without granting it, smoothly offer and guide them into granting folder access via Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) with persisted URI permissions.

---

## Architecture & Implementation Steps

### 1. Permission Rationale & Compliance Dialog
- Create `StorageAccessRationaleDialog` composable (or integrate into `PermissionBanner.kt` / `Dialogs.kt`):
  - Informs the user clearly of the core purpose: browsing, moving, copying, compressing/extracting, and editing files across primary internal storage and SD cards.
  - Buttons:
    - **"Open Settings"**: Launches the system All Files Access toggle.
    - **"Use Specific Folder (SAF)"**: Immediately launches the SAF folder picker fallback.
    - **"Not Now"**: Dismisses the dialog with minimal disruption.

### 2. Activity Result & State Management in `MainActivity.kt` & `FileManagerViewModel.kt`
- Introduce state in `FileManagerViewModel` (`showStorageRationaleDialog: StateFlow<Boolean>`).
- In `MainActivity.kt`:
  - When storage access is required, trigger the rationale dialog first instead of jumping directly into Settings.
  - On user confirmation in the dialog, launch `manageAllFilesLauncher`.
  - When returning from Settings, if `hasAllFilesAccess()` is still `false`, show a gentle prompt offering the SAF folder picker fallback so the user can continue using the app for specific directories.

### 3. Verification & Safety
- Verify compilation with `compile_applet`.
- Run unit & Robolectric tests (`gradle :app:testDebugUnitTest`) ensuring all security constraints and permission logic pass without regression.
