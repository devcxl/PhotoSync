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

[English version](./README.en.md) · [Русская версия](./README.ru.md)

</div>

---

PhotoSync 是一款 Android 相机伴侣应用，专为摄影师和影像爱好者设计。通过 USB 直连相机，无需 Wi-Fi、无需安装任何电脑客户端，即可在本机浏览、缩放预览 RAW/JPEG 照片，并导出到系统相册。

## 功能亮点

- **USB 直连相机** — 支持 MTP/PTP 协议，相机通过 USB 连接本机，无需网络
- **RAW 解码** — 基于 LibRaw + lcms2 色彩管理，支持 ARW、CR3、NEF、DNG、RAF 等 20+ 格式
- **流畅阅片** — 缩略图优先加载，大图分块渲染，支持双指缩放和平移
- **无损导出** — JPEG 直接拷贝，RAW 解码后保存到系统相册

## 适用场景

- 外出拍摄时即拍即看，不耗相机屏幕电量
- 现场将 RAW/JPEG 快速导入平板或大屏设备查看
- 批量导出照片到本地，无压缩、无云端上传

## 快速上手

1. 用 USB 线将相机连接到手机/平板
2. 在相机上选择"PC 连接"或"MTP"模式
3. 打开 PhotoSync，自动识别设备，开始浏览和导出

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.0 / C++ / JNI |
| UI | XML + ViewBinding / Jetpack Compose |
| 异步 | Coroutines + Flow |
| 数据库 | Room |
| 图像处理 | LibRaw 0.21.4 + lcms2 2.18 + OpenCV 4.12.0 |
| 架构 | MVVM + Clean Architecture |
| 最低版本 | Android 7.0（API 24） |

## 支持的 RAW 格式

Sony (ARW/SR2) · Canon (CR2/CR3) · Nikon (NEF/NRW) · Fujifilm (RAF) · Panasonic (RW2) · Adobe (DNG) · Olympus (ORF) · Samsung (SRW) · Pentax (PEF) · Leica (RWL) · Hasselblad (3FR) · 以及更多

## 项目结构

```
PhotoSync/
├── app/                    # 主应用模块
│   └── src/main/java/cn/devcxl/photosync/
│       ├── activity/       # Activity、ViewModel
│       ├── adapter/        # RecyclerView / ViewPager 适配器
│       ├── data/           # Room 数据库、DAO、Entity
│       ├── ptp/            # USB PTP 协议实现与连接管理
│       ├── receiver/       # USB 广播接收器
│       └── utils/          # 工具类
├── libraw/                 # Native 库模块（LibRaw + lcms2）
│   └── src/main/
│       ├── cpp/            # JNI 桥接 + CMake 构建
│       └── java/           # RawWrapper Kotlin 封装
└── gradle/
    └── libs.versions.toml  # 依赖版本管理
```

## 构建

```bash
# 前置条件：Android SDK、NDK、CMake 3.22+
# 首次克隆需初始化子模块
git submodule update --init --recursive

# Debug APK
./gradlew assembleDebug

# Release AAB (Google Play)
./gradlew bundleRelease

# 运行单元测试
./gradlew testDebugUnitTest

# Lint 检查
./gradlew lintDebug
```

生成的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
Release AAB 位于 `app/build/outputs/bundle/release/app-release.aab`。
