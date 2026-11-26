# 05: P1 主体蒙版（MODNet + 分块 GuidedFilter + FMGE）与去背/换底垂直切片

**What to build:** 打通 P1 端侧人像发丝精抠完整垂直切片：用户在编辑器「AI → 主体」点击「透明去背」或「纯色换底（含预设色块）」时，若 MODNet 尚未下载则弹出按需下载确认单（含 25.9 MB 体积提示、多镜像下载进度与失败切 SAF 本地导入），模型就绪后经单活 ORT 底座在 512² 上推理生成 `SubjectMask` 并落盘为内容寻址外置资产；预览渲染与 24–60MP 列带分块烘焙统一使用分块 `GuidedFilter`（Tier 1 GPU 走 GLSL 6×Box，Tier 2 退回 CPU band fast guided filter，单 band ~63 MB）与 **FMGE 去色溢**（1024 代理初始化 + band 内 2 轮细化）消除发丝泛白边，合成放在 Pass D 的 3D LUT / Grain / Vignette 之前；透明去背画布显示棋盘格且导出走流式无损 PNG，纯色换底导出走 JPEG；支持单步撤销、长按 Before/After 对比原片，并在烘焙期遇 OOM 时自动降级为双线性上采样 + 边缘羽化确保导出不中断。

**Blocked by:** 01: P0 统一端侧 AI 底座、多镜像模型管理页与真机摸底门禁, 02: EditGraph Schema v2、内容寻址 AI 资产存储与 7 Tab「AI」编辑器框架, 03: 全分辨率流式透明 PNG 烘焙导出通道（PngStripSink）

**Status:** resolved

- [x] 首次进入「AI → 主体」触发去背/换底且未下载 MODNet 时，正确弹出按需下载底单；下载完成或本地 SAF 导入校验通过后自动继续生成 `SubjectMask` 并实时更新画布。（JVM 单测覆盖控制流与校验路径；真机 UI 未复跑）
- [ ] 透明去背模式画布展示棋盘格背景，导出后在系统相册生成带准确发丝透明通道的无损 PNG；纯色换底模式支持切换底色且导出为保留拍摄 EXIF 的 JPEG；换底前后再调 3D LUT / 颗粒 / 暗角可自然作用于整帧画面。（实现与合成顺序已就位：棋盘格在 Pass D **之后**叠加，CPU 预览/缩略图与 GL 顺序一致；PNG 走 `PngStripSink`、JPEG 保留 EXIF；真机发丝像素与 EXIF 逐项复验未跑）
- [ ] 预览与 24MP 全分辨率列带烘焙均经过分块 `GuidedFilter` 与 FMGE 去色溢，无整帧 OOM、无列带接缝，且暗底人像发丝无明显亮白边；模拟烘焙期 OOM 时自动降级为双线性羽化并成功完成导出。（`BandGuidedFilterTest` / `BakeSubjectSupportTest` / `SubjectMaskRenderContractTest` 通过；24MP 真机 OOM 与接缝未复验）
- [x] 支持独立撤销、长按 Before/After 展示原始相机底片、保存退出后主界面缩略图与大图同步显示去背/换底效果，且重开编辑器可复用已缓存 `SubjectMask` 无需重复推理。（JVM 单测覆盖撤销与复用；长按对比在消除集成中改为独立原片纹理，真机像素未复跑）
