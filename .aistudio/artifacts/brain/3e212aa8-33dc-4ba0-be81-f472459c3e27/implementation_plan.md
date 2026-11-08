# MT Explorer - Advanced Dual-Pane Android File Manager

A native, high-performance Android file manager engineered in the spirit of MT Manager, featuring dual-pane browsing, a unified virtual file system layer (`FileSystem`), an asynchronous task engine with conflict resolution, and rich file operations from day one.

### User Review & Critical Decisions

> [!IMPORTANT]
> Because interactive clarification was skipped with the prompt to continue ("lanjutkan"), the following sound engineering defaults have been selected:
> - **App Name**: MT Explorer (package: `com.example`, app title: "MT Explorer")
> - **Deployment Target**: Sideloaded APK with Android 11+ `MANAGE_EXTERNAL_STORAGE` for shared storage access and Storage Access Framework (SAF) fallback.
> - **Implementation Phasing**: Delivering **Phase 0 (Foundation)** and **Phase 1 (Core File Operations & Dual-Pane UI)** with full support for dual panes, breadcrumbs, multi-selection, file tasks (copy, move, delete, rename, mkdir), overwrite resolution dialogs, properties inspection, and file sharing.

---

### 1. Overview & Core Concept

- **What It Does**: MT Explorer provides power-user file browsing on Android with two simultaneous navigation contexts (Pane A and Pane B). It allows seamless copying, moving, browsing, inspecting, and managing files across internal shared storage, SD cards, and USB media without paywalls, ads, or tracking.
- **Target Audience**: Power users, developers, and Android enthusiasts seeking an uncluttered, responsive, MT Manager-grade tool for device maintenance, batch file handling, and cross-directory workflows.
- **Key Value**: Streamed, crash-safe file execution with non-blocking UI, complete visual parity across portrait (tabbed dual-pane) and landscape/tablet (side-by-side dual-pane), and zero partial-file corruption on cancelled operations.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Permission Onboarding**: On first launch, a clear, single-screen onboarding card explains storage permissions. If `Environment.isExternalStorageManager()` is false, users are directly routed to the system's `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` screen with a single tap, while maintaining an in-app SAF fallback.
2. **Dual-Pane Browsing**:
   - **Compact screens (phone portrait)**: Shows active pane with quick tabs `[ Pane A: /storage/... ]` and `[ Pane B: /Download ]`. Swiping or tapping toggles between panes. The bottom action bar includes "Copy to Other Pane" and "Move to Other Pane".
   - **Expanded / Landscape screens**: Renders Pane A and Pane B side-by-side with an adjustable split pane divider.
3. **Breadcrumbs & Navigation**: Interactive horizontal breadcrumb strip where tapping any segment instantly jumps to that ancestor directory, plus a quick jump to Storage Roots (Internal, SD Card, App Storage).
4. **Multi-Selection Mode**: Long-press enters multi-selection mode with count indicators, Select All, Invert, and a bottom contextual action sheet (Copy, Cut/Move, Delete, Rename, Share, Properties).
5. **Conflict & Overwrite Dialog**: When copying/moving to an existing file, an overwrite dialog displays source and destination file sizes, modification timestamps, and offers: *Overwrite*, *Skip*, *Rename (append index)*, and *Apply to All*.
6. **Task Center & Progress Sheet**: File copy/move operations stream progress (percentage, items processed, speed, and active file) with immediate cancellation support.

#### Visual Identity & Theme
- **Aesthetic Direction**: High-efficiency, technical dark/AMOLED aesthetic with crisp geometric structure, avoiding generic flat cards in favor of a sleek, utility-focused command console.
- **Color Palette**:
  - Dominant Background: Pitch Black (`#0B0F14`) & Deep Slate (`#121820`)
  - Accent / Primary: Electric Cyan (`#00D2FF`) and Neon Teal (`#00F5D4`)
  - Warning / Conflict: Radiant Amber (`#FFB703`)
  - Danger / Delete: Coral Crimson (`#FF4D6D`)
  - Text & Accents: Crisp High-Contrast White (`#F0F4F8`) and Muted Steel (`#8A99A8`)
- **Typography & Hierarchy**: Clean, high-legibility monospace numerals for file sizes, dates, and path strings paired with robust Sans-Serif headings for fast visual parsing.
- **Density**: Compact list rows with distinct file-type icons (folders, archives, media, code, binaries, documents).

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Unified `FileSystem` Abstraction**:
  - *Chosen Approach*: Define `interface FileSystem` (`list`, `stat`, `openRead`, `openWrite`, `mkdir`, `rename`, `delete`) and implement `LocalFileSystem` backed by `java.io.File` and `DocumentFile` where needed.
  - *Why*: Decouples the UI completely from the underlying storage source. All future phases (ZIP archives as folders, Root/Shizuku, FTP/SFTP/SMB) plug into the exact same browser and task engine without rewriting UI code.
- **Decision 2: Safe Streaming File Operations**:
  - *Chosen Approach*: Stream data via 64KB buffers with cooperative cancellation tokens and temporary file staging (`.tmp_copy`). If a copy is cancelled or fails, the destination temp file is purged, eliminating corrupted half-copied files.
  - *Why*: Guarantees zero data loss or partial writes during disk-full or user-cancelled transfers.
- **Decision 3: Adaptive Layout Architecture**:
  - *Chosen Approach*: Compute window size classes (`WindowWidthSizeClass`). On Compact widths, render tabbed pane switching with direct cross-pane transfer buttons; on Medium and Expanded widths, render dual side-by-side panes.
  - *Why*: Delivers an optimal ergonomic experience on both one-handed vertical mobile devices and tablets/foldables.

---

### 4. Technical Architecture & Data Strategy

#### System Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                              MainActivity                              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                         ┌──────────▼──────────┐
                         │   FileManagerApp    │
                         └──────────┬──────────┘
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
┌───────────────────────┐                         ┌──────────────────────┐
│  FileManagerViewModel │                         │      TaskEngine      │
│  - paneAState         │                         │  - activeTasks Flow  │
│  - paneBState         │                         │  - queue / cancel    │
│  - activePaneIndex    │                         │  - conflict callback │
│  - selectionState     │                         └──────────┬───────────┘
└──────────┬────────────┘                                    │
           │                                                 │
           └────────────────────────┬────────────────────────┘
                                    ▼
                         ┌──────────────────────┐
                         │      FileSystem      │
                         │     (Abstraction)    │
                         └──────────┬───────────┘
                                    │
                     ┌──────────────┴──────────────┐
                     ▼                             ▼
          ┌──────────────────────┐      ┌─────────────────────┐
          │   LocalFileSystem    │      │  (Future Providers: │
          │ (Internal / SD / SAF)│      │  Zip, Root, Network)│
          └──────────────────────┘      └─────────────────────┘
```

#### Core Components
- `core/fs/FileSystem.kt`: Core abstraction interface, `VPath`, `VEntry`, permissions, and capabilities.
- `core/fs/LocalFileSystem.kt`: Production implementation for Android shared storage, external SD cards, and app storage.
- `core/tasks/TaskEngine.kt`: Background task dispatcher for copy, move, delete, recursive sizing, with progress reporting and conflict resolution.
- `feature/browser/FileManagerViewModel.kt`: Multi-pane state manager, history stack (back/forward), sorting (name, date, size, type), filter, and selection.
- `feature/browser/ui/`:
  - `BrowserScreen.kt`: Main scaffold containing top app bar, breadcrumbs, dual-pane view, and floating action button.
  - `DualPaneLayout.kt`: Side-by-side (expanded) and tabbed (compact) pane presentations.
  - `FileListPane.kt`: High-efficiency `LazyColumn` file list with gestures, selection checkboxes, and type-colored badges.
  - `BottomActionBar.kt`: Contextual bottom bar for batch operations (Copy, Cut, Delete, Rename, Share, Properties).
  - `OverwriteDialog.kt` & `PropertiesDialog.kt`: Dedicated modal dialogs for conflict inspection and recursive metadata reporting.
- `MainActivity.kt`: Lifecycle entry point with edge-to-edge support, storage permission triggers, and `FileProvider` intent sharing.
