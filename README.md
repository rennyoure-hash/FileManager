# MT Explorer

**Advanced dual-pane Android file manager** built with Jetpack Compose and Kotlin.  
Inspired by MT Manager — free, open source, no paywall, no ads, no telemetry.

[![CI](https://github.com/rennyoure-hash/FileManager/actions/workflows/android-ci.yml/badge.svg)](https://github.com/rennyoure-hash/FileManager/actions)
[![Test & Lint](https://github.com/rennyoure-hash/FileManager/actions/workflows/test.yml/badge.svg)](https://github.com/rennyoure-hash/FileManager/actions)
[![Detekt](https://github.com/rennyoure-hash/FileManager/actions/workflows/detekt.yml/badge.svg)](https://github.com/rennyoure-hash/FileManager/actions)
[![Deploy mdBook Pages](https://github.com/rennyoure-hash/FileManager/actions/workflows/mdbook.yml/badge.svg)](https://github.com/rennyoure-hash/FileManager/actions)

---

## 📱 About

MT Explorer is a native Android file manager that manages all files on your phone, in the spirit of MT Manager.

### Core (P0)
- **Dual-pane browsing** — view two directories side by side on tablets/foldables, or tabs on phones
- **Fast multi-selection** — long-press, select all, invert, range selection
- **File operations** — copy, move, delete, rename, new file/folder, properties
- **Conflict handling** — overwrite dialog showing size and time of both files
- **Task queue** — progress, cancel, overwrite/skip/rename options
- **Archives** — ZIP browse/extract/create with zip-slip protection, format detection for 7z/tar/gz/bz2/xz/zst/rar
- **Text viewer/editor** — view and edit text files, find/replace, large-file mode (5MB truncation)
- **Hex editor** — offset/hex/ASCII columns, paged navigation
- **Image viewer** — pager, swipe through siblings, pinch-zoom ready
- **Media player** — audio (MediaPlayer) + video (VideoView)
- **Search** — by name (recursive) and by content (streaming, binary-skip)
- **Bookmarks** — Room DB local + cloud sync via Firestore
- **Storage roots** — internal, SD card, USB OTG

### Storage & Permissions
- **SAF (Storage Access Framework)** — `SafFileSystem.kt` via `DocumentFile` for scoped access
- **CompositeFileSystem** — routes operations between local and SAF providers (cross-provider copy/paste)
- **PermissionManager** — tiered access: ALL_FILES → SAF fallback → NONE, with `MANAGE_EXTERNAL_STORAGE`
- **StorageRoots** — dynamic discovery of internal/SD/USB roots

### Advanced (P1)
- 7z/tar/gz format detection + ZIP/tar.gz extract (`ArchiveManager.kt`)
- Syntax highlighting basics (monospace rendering)
- Recycle bin (`RecycleBin.kt`) — trash/restore/permanent delete
- Batch rename with preview (`Tools.kt`)
- Checksums — MD5/SHA-1/SHA-256/SHA-512 (streaming)
- Installed apps — list (system filter), extract APK, APK info viewer
- Network storage — profile abstraction for FTP/FTPS/SFTP/SMB/WebDAV/S3 (coming soon)
- File split/merge by size

### Extra (P2)
- Duplicate finder (size + SHA-256 grouping)
- Storage analyzer (directory usage bars)
- Settings with DataStore (theme/language/density/font), backup API, batch rename history (20 entries)
- Terminal emulator (ls/cd/pwd/cat/echo/mkdir/rmdir/rm/touch/whoami — rm needs `-y`)
- HTTP Remote Management — stub (disabled in UI until implemented)
- S3 object storage — profile abstraction

### Firebase & Auth
- Google Sign-In via Credential Manager (`AuthManager.kt`)
- Firestore cloud bookmarks (`CloudRepository.kt`)
- SignInScreen with Material You (`SignInScreen.kt`)

---

## 🚀 Installation

### Option 1: Download APK (Recommended)

1. Go to the [Actions](https://github.com/rennyoure-hash/FileManager/actions) tab
2. Select the latest successful **CI** run
3. Download `app-debug-apk` artifact
4. Transfer the APK to your Android device (`Download/` folder)
5. Enable "Install from unknown sources" in your device settings
6. Open the APK and tap Install

> **Note:** If you get "App not installed" (signature mismatch), uninstall the previous version first:
> `Settings → Apps → MT Explorer → Uninstall`

### Option 2: Build from Source

```bash
# Clone the repository
git clone https://github.com/rennyoure-hash/FileManager.git
cd FileManager

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:**
- JDK 17+
- Android SDK (compileSdk 36)
- Android device or emulator (minSdk 24)

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Architecture | Package-by-feature, MVVM |
| Async | Coroutines, Flow |
| Database | Room (Bookmarks, History) |
| Settings | DataStore Preferences |
| Network | OkHttp, Retrofit |
| Images | Coil |
| Cloud | Firebase (Firestore, Auth, AI) |
| DI | KSP (Room, Moshi) |
| Testing | JUnit, Robolectric, Espresso |
| CI/CD | GitHub Actions (18 workflows) |

---

## 📁 Project Structure

```
app/src/main/java/com/example/
├── core/
│   ├── fs/           # FileSystem abstraction
│   │   ├── FileSystem.kt          # Interface (list/stat/read/write/mkdir/rename/delete)
│   │   ├── LocalFileSystem.kt     # Direct file access
│   │   ├── SafFileSystem.kt       # SAF DocumentFile provider
│   │   ├── CompositeFileSystem.kt # Router: Local ↔ SAF cross-provider
│   │   ├── StorageRoots.kt        # Dynamic root discovery
│   │   └── VPath.kt / VEntry.kt / Capability.kt
│   ├── archive/      # ArchiveManager (ZIP/gzip, zip-slip safe)
│   ├── tasks/        # TaskEngine (copy/move/delete with conflict handling)
│   ├── permission/   # PermissionManager (tiered storage access)
│   ├── firebase/     # AuthManager, CloudRepository, CloudModels
│   ├── db/           # AppDatabase (Room: Bookmarks + History)
│   ├── search/       # SearchManager (by name + by content, streaming)
│   ├── storage/      # RecycleBin (trash/restore)
│   ├── tools/        # Checksum, Split/Merge, BatchRename, Duplicate, Analyzer
│   ├── apps/         # InstalledAppsManager
│   ├── settings/     # SettingsManager (DataStore)
│   └── net/          # NetworkFileSystem (FTP/SFTP/SMB/WebDAV/S3 profiles) + RemoteServer stub
├── feature/
│   ├── browser/      # File browser (ViewModel + Compose UI)
│   │   └── ui/       # BrowserScreen, FileListPane, BottomActionBar, Dialogs, etc.
│   ├── viewer/       # TextViewer, HexEditor, ImageViewer, MediaPlayer, ViewerActivity
│   ├── archive/      # ArchiveActivity (browse ZIP as folder)
│   ├── tools/        # ToolsActivity hub, RecycleBin, Terminal, InstalledApps, AnalysisScreens
│   ├── auth/ui/      # SignInScreen
│   ├── settings/     # SettingsScreen
│   └── network/      # NetworkStorageScreen
└── MainActivity.kt   # Entry point + file type routing + share/open with
```

---

## 🤝 Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes (`git commit -m 'Add some feature'`)
4. Push to the branch (`git push origin feature/your-feature`)
5. Open a Pull Request

### Code Style

- Kotlin coding conventions
- Detekt static analysis
- KTLint formatting
- All PRs require 1 approval review and passing CI checks

---

## 📄 License

This project is free and open source. No paywall, no login, no ads, no telemetry.

---

## 📞 Contact

- **Author:** Renn ([rennyoure-hash](https://github.com/rennyoure-hash))
- **Email:** rennyoure@gmail.com
- **Repository:** https://github.com/rennyoure-hash/FileManager
- **Documentation:** https://rennyoure-hash.github.io/FileManager/

---

## 🔐 Security

If you discover a security vulnerability, please contact the author directly at rennyoure@gmail.com.

---

*Built with ❤️ using Jetpack Compose and Kotlin*
