# PhotoSync

![Android](https://img.shields.io/badge/Android-7.0%20%28API%2024%29-brightgreen?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple?style=flat-square&logo=kotlin&logoColor=purple)
![Gradle](https://img.shields.io/badge/Gradle-8.12-yellow?style=flat-square&logo=gradle)
![NDK](https://img.shields.io/badge/NDK-27.0.12014523-red?style=flat-square&logo=android-ndk)
![CI](https://img.shields.io/github/actions/workflow/status/devcxl/PhotoSync/release.yml?style=flat-square&logo=github-actions)
![Stars](https://img.shields.io/github/stars/devcxl/PhotoSync?style=flat-square&logo=github)

Android USB PTP 相机照片同步与预览应用。通过 USB Host 直连相机，在本机缓存 RAW/JPEG，支持滑动阅片、缩放查看、RAW 解码与导出。

## 功能亮点

- **USB PTP 直连** — 支持 MTP/PTP 协议连接相机，自动检测设备插拔
- **RAW 解码** — 基于 LibRaw + lcms2 色彩管理，支持 20+ 种 RAW 格式（ARW、CR3、NEF、DNG、RAF 等）
- **流畅阅片** — 缩略图优先加载 + 双层位图缓存，大图分块渲染
- **缩放查看** — 支持双指缩放和平移浏览高分辨率照片
- **照片导出** — JPEG 直接拷贝（无损）、RAW 解码后导出到系统相册

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.0 / Java |
| UI | XML + ViewBinding / Jetpack Compose |
| 异步 | Coroutines + Flow |
| 数据库 | Room |
| 图像处理 | LibRaw 0.21.4 + lcms2 2.18 + OpenCV 4.12.0 |
| 架构 | MVVM + Clean Architecture |
| 最低版本 | Android 7.0（API 24） |

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

# 运行单元测试
./gradlew testDebugUnitTest

# Lint 检查
./gradlew lintDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 支持的 RAW 格式

Sony (ARW/SR2) · Canon (CR2/CR3) · Nikon (NEF/NRW) · Fujifilm (RAF) · Panasonic (RW2) · Adobe (DNG) · Olympus (ORF) · Samsung (SRW) · Pentax (PEF) · Leica (RWL) · Hasselblad (3FR) · 以及更多

## 许可证

- **LibRaw** — LGPL 2.1 / CDDL 1.0 双许可
- **Little-CMS** — MIT
