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

- **Dual-pane browsing** — view two directories side by side on tablets/foldables, or tabs on phones
- **Fast multi-selection** — long-press, select all, invert, range selection
- **File operations** — copy, move, delete, rename, new file/folder, properties
- **Conflict handling** — overwrite dialog showing size and time of both files
- **Task queue** — progress, cancel, overwrite/skip/rename options
- **Archives** — ZIP browse/extract/create (more formats coming)
- **Text viewer/editor** — view and edit text files
- **Image viewer** — view images in folders
- **Storage access** — internal storage, SD card, USB OTG
- **Firebase integration** — auth, cloud storage, AI features
- **Bookmarks & search** — quick access to your favorite locations

---

## 🚀 Installation

### Option 1: Download APK (Recommended)

1. Go to the [Actions](https://github.com/rennyoure-hash/FileManager/actions) tab
2. Select the latest successful **CI** run
3. Download `app-debug-apk` artifact
4. Transfer the APK to your Android device
5. Enable "Install from unknown sources" in your device settings
6. Open the APK and tap Install

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
| Database | Room |
| Settings | DataStore |
| Network | OkHttp, Retrofit |
| Images | Coil |
| Cloud | Firebase (Firestore, Auth, AI) |
| DI | KSP (Room, Moshi) |
| Testing | JUnit, Robolectric, Espresso |
| CI/CD | GitHub Actions |

---

## 📁 Project Structure

```
app/src/main/java/com/example/
├── core/
│   ├── fs/           # FileSystem abstraction (local, archive, cloud, root)
│   ├── tasks/        # Task engine (copy, move, delete with progress)
│   ├── permission/   # Permission management
│   └── firebase/     # Firebase auth & cloud repository
├── feature/
│   └── browser/      # File browser UI (ViewModel, Compose screens)
└── MainActivity.kt   # Entry point
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