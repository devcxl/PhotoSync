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

PhotoSync is an Android camera companion app designed for photographers and imaging enthusiasts. Connect your camera directly via USB — no Wi-Fi, no desktop software client needed — to browse, zoom, and preview RAW/JPEG photos on your phone, then export them to your system gallery.

## Features

- **USB Direct Connection** — Supports MTP/PTP protocol; camera connects to device via USB, no network required
- **RAW Decoding** — Based on LibRaw + lcms2 color management, supports 20+ formats including ARW, CR3, NEF, DNG, RAF
- **Smooth Browsing** — Thumbnail-first loading, large image tile rendering, two-finger zoom and pan
- **Lossless Export** — JPEG direct copy, RAW decoded and saved to system gallery

## Use Cases

- On-location review without draining camera battery
- Quickly import RAW/JPEG to tablet or large-screen device for review
- Batch export photos to local storage, no compression, no cloud upload

## Quick Start

1. Connect camera to phone/tablet via USB cable
2. Set camera to "PC Connection" or "MTP" mode
3. Open PhotoSync, device is recognized automatically, start browsing and exporting

---

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

## Supported RAW Formats

Sony (ARW/SR2) · Canon (CR2/CR3) · Nikon (NEF/NRW) · Fujifilm (RAF) · Panasonic (RW2) · Adobe (DNG) · Olympus (ORF) · Samsung (SRW) · Pentax (PEF) · Leica (RWL) · Hasselblad (3FR) · and more

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
