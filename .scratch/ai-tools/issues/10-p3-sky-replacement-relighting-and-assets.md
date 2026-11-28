# 10: P3 换天空（零模型分割、OKLab 前景重光照与 0 KB 程序化/自选天空）垂直切片

**What to build:** 打通离线优先的换天空完整垂直切片：实现 `SkySegmenter` 接口与 v1 零模型经典天空分割（顶部种子区域成长 + OKLCH 色度/高亮度连通性 + 垂直空间先验 + 分块 `GuidedFilter` 边缘精修，天空占比 `< 3%` 给出温和提示不强行涂抹），并预留按需下载合规轻量模型的插槽；在现有 1024px `Pre-pass` 一次性统计原/新天空 OKLab 均值差作为统一参数，驱动 **Pass B 前景受光色彩协调**（含限幅亮度联动，彻底避免分块导出拼缝）；在 **Pass D（3D LUT / Grain / Vignette 之前）**完成天空合成、地平线纵向偏移、地平线柔化带、边缘微缩与过渡带溢色抑制；内置 4 组 0 KB Shader 程序化大气天空基准（晴空、落日、蓝调、暮云）开箱离线可用，支持 SAF 导入用户自拍天空图（4096 px 内容寻址存储与单独删除）及复用多镜像下载器按需获取可选 CC0 高清云彩包，并将 `SkyOps` 纳入 `Preset`、`CopyMask` 与 `LiveTetherPreset` 联机联动。

**Blocked by:** 01: P0 统一端侧 AI 底座、多镜像模型管理页与真机摸底门禁, 02: EditGraph Schema v2、内容寻址 AI 资产存储与 7 Tab「AI」编辑器框架, 05: P1 主体蒙版（MODNet + 分块 GuidedFilter + FMGE）与去背/换底垂直切片, 08: P1 进阶：主体背景虚化、前后景独立调色与 Preset/LiveTetherPreset 联机联动

**Status:** resolved

- [x] 离线无任何下载即可在「AI → 天空」切换 4 组 0 KB 程序化大气天空（晴空/落日/蓝调/暮云），不增加 APK 静态图片体积；支持 SAF 导入用户本地天空图（落盘为内容寻址缓存）及按需下载 CC0 天空包。（`ProceduralSkyPreset` 四组解析式 Rayleigh/Mie 渐变随 shader 生成，`SkyPanelView` 直接列出四组 chip；SAF 导入经 `SkyCustomAssetStore` 做 JPEG 校验、4096 px 长边上限与 SHA-256 内容寻址，`SkyCustomAssetStoreTest` 七例通过。CC0 云彩包仅在 `SkyModelCatalog` 预置 slot 并复用 `AiModelDownloadManager` 多镜像/校验流程，产物尚未固化（未 pin 字节数与 digest，安装会以 `NOT_AVAILABLE` 干净失败），真机下载未测）
- [x] 经典天空分割配分块 `GuidedFilter` 在预览与 24MP 列带烘焙上边缘对齐清晰且无分块接缝；对室内或无明显天空（`< 3%`）照片显示温和提示而不破坏画面。（`ClassicSkySegmenter` 顶部种子 + OKLCH 色度/亮度连通性 + 垂直先验 + 分块 `GuidedFilter`，`ClassicSkySegmenterTest` 覆盖大天空、室内无天空与硬垂直边缘建筑不入掩码；`MIN_USABLE_COVERAGE = 0.03` 触发温和提示，`CopyMaskSkyAndTetherTest.shouldDegradeToTheTraditionalGrade_whenTheSkySegmenterFindsNoSky` 覆盖降级；`BakePassChain` 以 `band.tileUv` 映射全帧天空 UV 保证列带同源采样，真机 GL 像素与 24MP 接缝未复验）
- [x] 调节「前景协调」滑杆时，Pass B 按 Pre-pass OKLab 差值自然协调前景受光色调且暗部不死黑、亮部不过曝；调节地平线偏移/柔化与边缘微缩实时响应，后续叠加 3D LUT / 颗粒 / 暗角统一作用于合成后整帧。（`SkyRelightMath` 以天空掩码内 OKLab 均值差驱动前景色偏，亮度联动限幅 12%、深阴影守卫两例由 `SkyRelightingAndBlendTest` 锁定；地平线偏移/柔化带宽/边缘微缩/过渡带溢色抑制各有单测；`shouldGradeTheSkyThroughTheLut_whenPassDRunsAfterTheComposite` 锁定 Pass D 在合成之后，真机 GL 实时性与视觉未复验）
- [x] `SkyOps` 支持单步撤销、Before/After 原图对比、保存进 `Preset` / 跨图复制（目标图独立计算天空掩码）及 `LiveTetherPreset` 联机串行套用。（`CopyMaskSkyAndTetherTest` 覆盖跨图仅复制参数并清空目标 mask、天空轴关闭时不覆盖目标、提取预设丢弃 mask、联机串行算目标天空、无天空/存储失败降级为传统调色、无 `SkyOps` 时跳过 preparer；撤销走 `EditorUndoController` 参数键、Before/After 沿用原片纹理；真机 USB 联机与长按对比未复跑）
