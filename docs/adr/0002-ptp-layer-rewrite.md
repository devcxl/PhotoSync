# 0002 — PTP 层按规范重写，项目改用 MIT

- 状态：已采纳
- 日期：2026-11-22
- 影响范围：`app/src/main/java/cn/devcxl/photosync/ptp/`

## 背景

`ptp/usbcamera/` 最初是 Java 代码，2026-05-15 的提交 `28c29ef` 把它逐文件翻译成了 Kotlin。
翻译保留了上游的类结构、字段名与控制流，10 个文件甚至原样保留了原始版权头：

- `Copyright 2000 by David Brownell <dbrownell@users.sourceforge.net>` — libptp2，GPL-2.0+
- `Copyright 2010 by Stefano Fornari` — cameraptp，GPL-2.0+（上游仓库按 AGPL-3.0 发布）

类清单与 [stefanofornari/cameraptp](https://github.com/stefanofornari/cameraptp) 的 `ste/ptp/*`
逐项对应，包括 `KodakExtension`、`ParamVector`、`OutputStreamData` 这类非常规命名。

翻译属于演绎作品：著作权不因改写语言或删除声明而消失，删除他人版权声明反而可能构成移除
权利管理信息。因此"改写成 Kotlin"和"加个不同名字"都不能让项目改用 MIT。

## 决策

按 PIMA 15740（PTP 1.0）与各厂商公开协议文档重新实现该层，并据此将项目许可改为 MIT。

新实现不继承旧类体系，而是按职责分层：

| 层 | 目录 | 职责 |
|---|---|---|
| 协议 | `ptp/protocol/` | 容器编解码、数据集（DeviceInfo/StorageInfo/ObjectInfo）、错误类型 |
| 传输 | `ptp/transport/` | USB bulk 读写、ZLP、超时、类请求；`PtpTransport` 便于 JVM 测试 |
| 会话 | `ptp/camera/PtpSession.kt` | 事务 ID、命令/数据/响应时序、`SessionAlreadyOpen` 恢复 |
| 门面 | `ptp/camera/PtpCamera.kt` | 会话互斥、存储/对象枚举、流式下载、删除 |
| 同步 | `ptp/camera/CameraSyncEngine.kt` | 事件通道与列表轮询两种触发方式、去重、失败降级 |
| 厂商 | `ptp/camera/vendor/` | Canon EOS / Nikon 事件通道、Sony SDIO 握手 |

协议常量（操作码、响应码、事件码、数据集布局）是规范事实，不构成表达；代码结构、命名与
控制流是新写的。

同时删除了无人调用的相机控制 API（拍摄、Bulb、实时取景、属性读写、对焦/变焦）。它们只
存在于上游移植代码里，本应用从不调用，属于移植带来的表面积而非需求。

## 结果

- 删除 36 个文件（约 4,700 行），新增 16 个文件（约 2,300 行）
- 新增 152 个 JVM 单测；`testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过
- 源码中不再有第三方版权头；许可由根目录 `LICENSE`（MIT）声明
- `PtpTransport` 接口让会话时序可以在 JVM 上用脚本化传输测试，不必接真相机

## 已知遗留

1. **LibRaw 的 LGPL 义务未处理**：`:libraw` 仍把 LibRaw（LGPL-2.1）与 lcms2（MIT）静态
   链接进单个 `libraw.so`。LGPL §6 要求使用者能替换该库，静态链接的单 `.so` 不满足。
   可选路径：把 LibRaw 编成独立 `.so` 并在运行时链接、提供可重链接的目标文件，或购买
   LibRaw 商业授权。此项不在本次改动范围内（`third_party/` 未改动）。
2. **需要真机验证**：单元测试覆盖协议编解码与策略，但 USB 传输、厂商事件通道、长时间
   同步仍需在 Canon / Nikon / Sony 机身上回归。
3. **相册删除路径**（`DeleteObject`）保留原有前置校验，但同样未在真机验证。
