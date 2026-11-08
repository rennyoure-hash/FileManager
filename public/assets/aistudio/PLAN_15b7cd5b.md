# Android File Manager: Build Plan (for the coding agent)

Goal: a native Android APK that manages all files on the phone, in the spirit of MT Manager: dual-pane browsing, archives, text/hex editing, network storage, root/Shizuku access.
Distribution: sideloaded APK first. Play Store is optional later.
Rule: build everything from scratch. Use MT Manager only as a feature reference. Do not copy its code, assets, or strings.

## 0. How to work with this plan
- Go phase by phase (section 7). Do not start the next phase until the current "Done when" checks pass.
- Follow AGENTS.md rules at all times (read before edit, smallest change, run before saying done).
- Tick the checkboxes in this file as tasks finish.
- Anything marked VERIFY must be checked on a real device or in official docs before you rely on it.

## 1. Scope
In scope: everything in section 2 marked P0 and P1, plus P2 where cheap.
Out of scope for v1: APK reverse-engineering (dex/arsc/xml editors, signature removal, app cloning), plugin system, translation mode. Revisit only after phase 8.
All features are free. No paywall, no login, no ads, no telemetry.

## 2. Feature inventory (researched from MT Manager)

P0 (core file management)
- Browse internal storage, SD card, USB OTG; breadcrumb path bar; sort and filter; show hidden files
- Select (long-press, select all, invert, range); copy, move, delete, rename, new file/folder; properties
- Dual-pane mode (two directories at once, copy/move between them, sync/back/forward)
- Tabs or history per pane; bookmarks
- Task queue with progress, cancel, and an overwrite dialog showing size and time of both files
- Search by name (and later content); open with, share
- ZIP browse/extract/create (archive opens like a folder)
- Text viewer/editor; image viewer

P1
- 7z, tar, gz/bz2/xz, zstd, zlib; RAR read; password archives; multi-part ZIP/7z create and extract
- Syntax highlighting, regex find/replace, hex editor, text compare
- Audio and video player (audio track and subtitle switching, auto rotate)
- Recycle bin (internal storage), batch rename (with saved input history), checksums, file split/merge (by size, text length, delimiter)
- Installed apps list (sort by name, size, first install time), extract APK, uninstall
- Network storage: FTP/FTPS, SFTP (incl. jump host), SMB, WebDAV
- Remote management over HTTP so the phone can be managed from a browser
- Shizuku and root access (Android/data, Android/obb, system dirs)
- Built-in terminal emulator

P2
- S3 object storage, duplicate finder, storage analyzer, settings backup, read-only APK info viewer (package, permissions, signature)

Notes from research
- MT Manager marks only a few items as VIP: method flowcharts and single-method smali-to-Java conversion in the Dex tools, "code" search in the AXml batch replace, and word completion in the text editor. No complete official free-vs-paid list was found, so treat everything else as free-tier.
- MT Manager targets old APIs (min 21, target 28), which helps it keep broad file access. A new app targeting a current API must follow the permission rules in section 3.
- Recent MT Manager changes worth matching: single-window copy/move, long-press menus instead of swipe menus, in-flight optimization for SMB transfers, faster payload.bin extraction.

## 3. Technical decisions

Stack
- Kotlin, Jetpack Compose, Material 3, WindowSizeClass for adaptive layouts
- Coroutines and Flow; Hilt (or Koin) for DI; Room (bookmarks, history, network profiles); DataStore (settings)
- Archives: Apache Commons Compress, zip4j, a 7z library, junrar (RAR read-only). Check each license before adding
- Network: Apache Commons Net (FTP), sshj (SFTP), smbj (SMB), OkHttp (WebDAV), an S3 client
- Media: Media3 ExoPlayer; Images: Coil; Code editor: a Compose-friendly or View-based editor library (evaluate sora-editor)
- HTTP remote management: Ktor or NanoHTTPD
- Credentials: encrypt with Android Keystore (Tink). Never store plaintext passwords.
- minSdk 26 unless the user says otherwise. targetSdk: current stable. VERIFY.

Storage access tiers (hide behind one interface)
1. SAF (scoped access): fallback when the user grants nothing else
2. MANAGE_EXTERNAL_STORAGE: read/write all shared storage, including SD and USB roots. It does NOT open other apps' Android/data. Request via ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION and check Environment.isExternalStorageManager(). Google Play restricts this permission to apps whose core purpose needs it; file managers are an allowed category.
3. Shizuku (no root): needed for Android/data and Android/obb on newer Android (reported for API 34+ by third-party projects). VERIFY on the target phone.
4. Root (su): full filesystem when available

Build environment
- If the agent runs inside Termux, the standard Android SDK build tools (aapt2) may not run on ARM Termux. Check `./gradlew assembleDebug` first. A Termux-provided aapt2 may be needed (android.aapt2FromMavenOverride). VERIFY before assuming it works.

## 4. Architecture

Package-by-feature in one module for v1: `core/fs`, `core/archive`, `core/net`, `core/tasks`, `feature/browser`, `feature/editor`, `feature/viewer`, `feature/network`, `feature/tools`, `feature/settings`.

Everything is a FileSystem. Local, root, Shizuku, archives, and every network protocol implement the same interface, so copy/move/delete/search work everywhere.

```kotlin
interface FileSystem {
    val id: String
    val capabilities: Set<Capability> // ATOMIC_RENAME, PERMISSIONS, SYMLINKS, RANDOM_ACCESS, TRASH
    suspend fun list(path: VPath): List<VEntry>
    suspend fun stat(path: VPath): VEntry
    fun openRead(path: VPath, offset: Long = 0): InputStream
    fun openWrite(path: VPath, append: Boolean = false): OutputStream
    suspend fun mkdir(path: VPath)
    suspend fun rename(from: VPath, to: VPath)
    suspend fun delete(path: VPath, recursive: Boolean)
}
```

Task engine
- Every copy/move/delete/extract/compress is a Job run in a foreground service
- Exposes StateFlow progress (bytes, files, speed, ETA); cooperative cancel; pause/resume where the protocol allows
- Conflict handling: ask (with size/time of both), skip, overwrite, rename, apply to all

Data safety rules (non-negotiable)
- Stream everything. Never load whole files into memory.
- Write to a temp name, then rename into place. A failed write must never destroy the original.
- Cross-volume move = copy, verify size (and checksum for network), then delete the source.
- Extraction must block path traversal ("zip slip") and refuse entries that escape the target dir.
- Do not follow symlinks into loops; detect cycles.
- Delete asks for confirmation, and uses the recycle bin on internal storage when enabled.

## 5. UI design

Principles: thumb-reachable actions, no gesture-only features, predictable, fast on 100k-file folders (lazy lists, paging, background thumbnail loading).

Phone portrait, single pane

```
┌────────────────────────────┐
│ ☰  Internal › DCIM › Cam 🔍 ⋮│  top bar + scrollable breadcrumb
├────────────────────────────┤
│ [ A: Cam ] [ B: Download ] │  pane tabs (dual mode only)
├────────────────────────────┤
│ ☐ 📁 Camera        12 items │
│ ☐ 🖼 IMG_001.jpg   2.1 MB   │
│ ☐ 📦 backup.zip    48 MB    │
│                             │
├────────────────────────────┤
│ Copy  Cut  Delete  Share  ⋯ │  bottom action bar (appears on selection)
└────────────────────────────┘
                         (＋)  new file/folder
```

Layout by window size
- Compact (phone portrait): one pane visible. In dual mode, A and B are tabs you swipe or tap between, and the action bar has "Copy to other pane" and "Move to other pane".
- Medium/Expanded (landscape, foldable, tablet): two panes side by side with a draggable divider.

Navigation drawer: storage roots (Internal, SD, USB, Root, Shizuku), bookmarks, network storage, tools (search, installed apps, recycle bin, terminal, remote management), settings.

List
- Densities: compact, comfortable, grid with thumbnails
- Row: icon or thumbnail, name (wraps in comfortable mode), size, modified date; optional permissions column
- Sort by name, size, date, type; folders first; natural number sorting (file2 before file10)

Interactions
- Tap opens; long-press enters selection mode and opens the context menu (no swipe-only menus)
- Drag-select range; select all; invert
- Predictive back: up one directory, then exit
- Pull to refresh; breadcrumb tap jumps to ancestor

Dialogs
- Overwrite: show size and modified time of both files, "apply to all"
- Properties: size (recursive, async), permissions, owner, timestamps, checksums
- Progress: notification plus an in-app task sheet with cancel

Theme: Material You dynamic color, light/dark/AMOLED black, adjustable list density and font size, Indonesian and English strings from day one.

Editor/viewer screens
- Text editor: line numbers, syntax highlighting, regex find/replace, undo/redo, encoding picker, large-file mode, shortcut bar above the keyboard
- Hex editor: offset, hex, ASCII columns, edit and save
- Image viewer: pinch zoom, swipe through siblings in the folder
- Media player: audio/video, background audio, subtitle and audio-track switching

## 6. Accessibility spec (testable)

TalkBack and screen readers
- Every icon-only control has a contentDescription. Visible text and spoken label must match (needed for Voice Access).
- File rows are one focus unit that reads: type, name, size in spoken units ("2.1 megabytes"), modified date in full, selected state.
- Selection state is exposed via semantics (selected/stateDescription), not just color or a checkbox image.
- Every gesture has a menu alternative: long-press and drag actions also exist as custom accessibility actions (Copy, Move, Rename, Select, Open menu). Drag-and-drop between panes must also be doable with "Copy/Move to other pane".
- Progress and completion are announced via live regions ("Copying 3 of 12 files, 40 percent", "Done, 12 files copied").
- Headings are marked; focus order follows visual order; after a dialog closes, focus returns to where it was.

Touch and motor
- Minimum touch target 48dp with spacing; selection checkboxes and action buttons included
- Primary actions in the bottom half of the screen for one-handed use
- No action requires a precise or timed gesture
- Switch Access and D-pad/keyboard navigation work everywhere. Hardware keyboard shortcuts: Ctrl+C/X/V, Delete, F2 (rename), Ctrl+A, Ctrl+F, Tab (switch pane), Enter (open), Backspace (up).

Visual
- Text contrast at least 4.5:1 (3:1 for large text and UI components), in every theme including AMOLED
- Never use color alone for meaning (errors, selection, file types)
- Supports system font size up to 200% and display size changes without clipped text or hidden actions
- Honors "remove animations": no essential info conveyed by motion
- Optional high-contrast theme; RTL layouts mirror correctly

Feedback
- Haptic feedback on long-press selection; optional sound/vibration on task completion
- Errors are written in plain language and say what to do ("Folder is read-only. Grant all-files access in Settings.")

Phone access setup (onboarding)
- First run explains in one screen what each access level unlocks, then sends the user to the exact system page: all-files access, SD card, Shizuku (optional), root (optional)
- The app works in a reduced mode when a permission is denied and says which features are limited. It never nags.
- Do NOT implement the app as an AccessibilityService. It only needs to be accessible.

## 7. Phases

Phase 0: Foundation
- [ ] Project skeleton, Gradle build verified (`./gradlew assembleDebug`), CI-style script for lint + tests
- [ ] Theme, navigation, empty browser screen
Done when: debug APK builds, installs, and opens on the user's phone.

Phase 1: Local browsing and core operations
- [ ] Permission flow (all-files access, with SAF fallback)
- [ ] LocalFileSystem; list, sort, filter, hidden files, breadcrumb, storage roots (internal, SD, USB)
- [ ] Selection mode; copy, move, delete, rename, new file/folder; properties
- [ ] Task engine with progress, cancel, overwrite dialog
- [ ] Open with / share via FileProvider
Done when: a folder of 10,000 files lists smoothly, copying a 2 GB file to SD can be cancelled and leaves no partial file, and all unit tests pass.

Phase 2: Dual-pane, bookmarks, search
- [ ] Dual-pane (adaptive by window size), pane tabs on phones, back/forward/sync
- [ ] Bookmarks, search (name, then content), history
Done when: copy/move between panes works on phone portrait and landscape.

Phase 3: Archives
- [ ] ArchiveFileSystem (browse as folder); extract/create ZIP, tar family, 7z; RAR read
- [ ] Password support, multi-part ZIP/7z, zip-slip protection tests
Done when: round-trip tests pass (create, extract, compare checksums) for each format.

Phase 4: Editors and viewers
- [ ] Text editor (highlighting, regex, undo), hex editor, image viewer, media player
- [ ] Text compare, checksums
Done when: a 100 MB log file opens in large-file mode without freezing; hex edits save correctly.

Phase 5: Tools
- [ ] Recycle bin, batch rename (preview before apply, saved history), split/merge, installed apps (list, extract APK, uninstall), storage analyzer
Done when: batch rename previews exactly match the result; trash restore returns files to the original path.

Phase 6: Network storage
- [ ] FTP/FTPS, SFTP (keys, jump host), SMB, WebDAV as FileSystems; saved profiles with encrypted credentials
- [ ] HTTP remote management (opt-in, off by default, shows URL, requires a password)
Done when: copy between a local folder and each protocol works, with resume for interrupted transfers where supported.

Phase 7: Privileged access and terminal
- [ ] Shizuku and root FileSystems; Android/data and Android/obb browsing; terminal emulator
Done when: Android/data opens on the target phone's Android version (VERIFY); the app degrades cleanly when Shizuku/root is absent.

Phase 8: Polish
- [ ] Full accessibility audit (section 6), performance pass, Indonesian/English strings review, crash handling, release build and signing
Done when: all section 6 rules pass Accessibility Scanner plus a manual TalkBack run through every P0 flow.

## 8. Testing
- Unit tests for every FileSystem implementation using one shared contract test suite
- Fault tests: kill the process mid-copy, fill the disk, remove the SD card, drop the network; verify no data loss
- Instrumented UI tests for P0 flows; Compose semantics tests for accessibility labels and actions
- Manual: TalkBack, font 200%, dark/AMOLED, landscape, split-screen

## 9. Questions for the user (ask before the related phase)
- Android version and brand of the phone (affects permissions and Android/data strategy)
- Sideload only, or Play Store later?
- App name and package name
- Will the build run inside Termux or on another machine?
