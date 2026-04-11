<p align="center">
  <img width="128" height="128" src="data:image/svg+xml,%3Csvg%20width%3D%22512%22%20height%3D%22512%22%20viewBox%3D%220%200%20512%20512%22%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%3E%3Crect%20width%3D%22512%22%20height%3D%22512%22%20rx%3D%2264%22%20fill%3D%22%23F7F4EE%22%2F%3E%3Cpath%20d%3D%22M%20353.5%20412.0%20A%20184%20184%200%201%201%20408.5%20358.9%22%20fill%3D%22none%22%20stroke%3D%22%23D4D4D4%22%20stroke-width%3D%2222%22%20stroke-linecap%3D%22round%22%2F%3E%3Cpath%20d%3D%22M%20408%20333.0%20L%20408.5%20358.9%20L%20431.5%20346%22%20fill%3D%22none%22%20stroke%3D%22%23D4D4D4%22%20stroke-width%3D%2222%22%20stroke-linecap%3D%22round%22%20stroke-linejoin%3D%22round%22%2F%3E%3Ccircle%20cx%3D%22256%22%20cy%3D%22256%22%20r%3D%22144%22%20fill%3D%22%233F7FDC%22%2F%3E%3Ccircle%20cx%3D%22428%22%20cy%3D%2275%22%20r%3D%2221%22%20fill%3D%22%23EA4335%22%2F%3E%3C%2Fsvg%3E">
</p>

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
