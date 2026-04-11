<div align="center">
  <img width="128" height="128" src="./docs/logo.svg">
</div>

<h1 align="center">PhotoSync</h1>

<div align="center">

![Android](https://img.shields.io/badge/Android-7.0%20%28API%2024%29-brightgreen?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple?style=flat-square&logo=kotlin&logoColor=purple)
![Gradle](https://img.shields.io/badge/Gradle-8.12-yellow?style=flat-square&logo=gradle)
![NDK](https://img.shields.io/badge/NDK-27.0.12014523-red?style=flat-square&logo=android-ndk)
![CI](https://img.shields.io/github/actions/workflow/status/devcxl/PhotoSync/release.yml?style=flat-square&logo=github-actions)
![Stars](https://img.shields.io/github/stars/devcxl/PhotoSync?style=flat-square&logo=github)

</div>

---

Android USB PTP camera photo sync and preview app. Connects to cameras directly via USB Host, caches RAW/JPEG locally, supports swipe browsing, pinch-to-zoom, RAW decoding and export.

## Features

- **USB PTP Direct Connection** — Supports MTP/PTP protocol for camera connection with automatic device detection
- **RAW Decoding** — Based on LibRaw + lcms2 color management, supports 20+ RAW formats (ARW, CR3, NEF, DNG, RAF, etc.)
- **Smooth Browsing** — Thumbnail-first loading with dual-layer bitmap cache and large image tile rendering
- **Pinch-to-Zoom** — Two-finger zoom and pan for high-resolution photo viewing
- **Photo Export** — JPEG direct copy (lossless), RAW decoded and exported to system gallery

## Tech Stack

| Category | Tech |
|----------|------|
| Language | Kotlin 2.0 / Java |
| UI | XML + ViewBinding / Jetpack Compose |
| Async | Coroutines + Flow |
| Database | Room |
| Image Processing | LibRaw 0.21.4 + lcms2 2.18 + OpenCV 4.12.0 |
| Architecture | MVVM + Clean Architecture |
| Min Version | Android 7.0 (API 24) |

## Project Structure

```
PhotoSync/
├── app/                    # Main application module
│   └── src/main/java/cn/devcxl/photosync/
│       ├── activity/       # Activity, ViewModel
│       ├── adapter/        # RecyclerView / ViewPager adapters
│       ├── data/           # Room database, DAO, Entity
│       ├── ptp/            # USB PTP protocol implementation and connection management
│       ├── receiver/       # USB broadcast receivers
│       └── utils/          # Utilities
├── libraw/                 # Native library module (LibRaw + lcms2)
│   └── src/main/
│       ├── cpp/            # JNI bridge + CMake build
│       └── java/           # RawWrapper Kotlin wrapper
└── gradle/
    └── libs.versions.toml  # Dependency version management
```

## Build

```bash
# Prerequisites: Android SDK, NDK, CMake 3.22+
# Clone with submodules
git submodule update --init --recursive

# Debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Lint check
./gradlew lintDebug
```

APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Supported RAW Formats

Sony (ARW/SR2) · Canon (CR2/CR3) · Nikon (NEF/NRW) · Fujifilm (RAF) · Panasonic (RW2) · Adobe (DNG) · Olympus (ORF) · Samsung (SRW) · Pentax (PEF) · Leica (RWL) · Hasselblad (3FR) · and more

## License

- **LibRaw** — LGPL 2.1 / CDDL 1.0 dual license
- **Little-CMS** — MIT
