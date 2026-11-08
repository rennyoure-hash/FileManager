# Installation

## Sideloading APK

1. Download the latest `app-debug.apk` from GitHub Actions artifacts or Releases.
2. Transfer the APK to your Android device.
3. Open the file manager on your device and tap the APK.
4. Enable "Install from unknown sources" if prompted.
5. Tap Install.

## Build from Source

```bash
git clone https://github.com/rennyoure-hash/FileManager.git
cd FileManager
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
