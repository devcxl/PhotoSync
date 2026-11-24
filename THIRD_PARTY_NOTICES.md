# 第三方开源组件声明（Third-Party Notices）

PhotoSync 本身以 [MIT](LICENSE) 许可发布。本文件列出随应用分发的第三方组件、许可、源码
位置，以及 LGPL 组件的替换方式。

## LibRaw

- **版本**：0.21.4
- **源码**：`libraw/src/main/cpp/third_party/LibRaw/`（git submodule）
- **许可**：LGPL-2.1 / CDDL-1.0 双授权；本项目按 **LGPL-2.1** 分发
- **产物**：独立的 `lib/<abi>/libraw.so`

### LGPL-2.1 合规说明

LGPL-2.1 第 6 条要求：把该库组合进非 LGPL 应用分发二进制时，必须允许接收者修改该库并
替换应用中的对应库文件。本项目通过以下方式满足：

1. LibRaw（连同静态链接其中的 MIT 许可 Little-CMS 与 OpenMP 运行时）编译为**独立的**
   `libraw.so`，不与 JNI 桥接库 `libraw_wrapper.so` 静态合并；
2. 完整源码与构建脚本随仓库提供：`libraw/src/main/cpp/third_party/LibRaw` 与
   `libraw/src/main/cpp/CMakeLists.txt`；构建环境为 Android NDK + CMake（版本见
   `libraw/build.gradle` 与 CI workflow）；
3. 任何人均可修改 LibRaw 源码、重新编译 `libraw.so`，替换 APK 中的
   `lib/<abi>/libraw.so`，重新签名后安装；本项目不限制为替换该库而进行的重打包；
4. `libraw_wrapper.so` 只通过 LibRaw 的公开 C++ 接口动态链接 `libraw.so`。

**当前限制**：JNI 桥接层中自动高光增益的上限（`raw_autobright.cpp`）仍读取 LibRaw 的
内部直方图（`get_internal_data_pointer()`），因此替换 `libraw.so` 时请使用 **0.21.x**
兼容构建，以保证内部结构布局一致；后续会改为仅依赖公开 API。

## Little-CMS (lcms2)

- **版本**：2.18
- **源码**：`libraw/src/main/cpp/third_party/Little-CMS/`（git submodule）
- **许可**：MIT
- **链接方式**：静态链接进 `libraw.so`

## libjpeg-turbo

- **版本**：3.2.2
- **源码**：`libraw/src/main/cpp/third_party/libjpeg-turbo/`（git submodule）
- **许可**：IJG / BSD-3-Clause / zlib（详见上游 `LICENSE.md`）
- **链接方式**：以静态库形式链接进 `libraw_wrapper.so`，仅启用编码器；符号通过
  `-Wl,--exclude-libs,ALL` 隐藏，避免与系统 `libjpeg.so` 冲突。

## Android NDK 运行时

- **libc++_shared**（`lib/<abi>/libc++_shared.so`）：Apache-2.0 with LLVM exceptions。
  `libraw.so` 与 `libraw_wrapper.so` 共享该运行时，以保证跨 `.so` 边界传递 C++ 对象安全。
- **libomp**（OpenMP）：Apache-2.0 with LLVM exceptions。静态链接进 `libraw.so`，其符号
  导出供 `libraw_wrapper.so` 复用，确保进程内只有一份 OpenMP 线程池。

## 报告问题

如发现许可或归属问题，请在仓库 Issues 中提出；如需要替换 LibRaw，可按上文步骤自行重编
`libraw.so` 并重打包。
