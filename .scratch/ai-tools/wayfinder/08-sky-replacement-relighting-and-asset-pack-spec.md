# P3 换天空光影融合、前景色彩协调与天空素材分发规范

Type: grilling
Status: resolved
Assignee: devcxl/wayfinder-session
Blocked by:
- [端侧智能消除（MI-GAN / LaMa-Dilated）与天空分割模型事实调研](02-on-device-inpainting-and-sky-models-research.md)
- [非破坏性 AI 资产层、EditGraph 扩展与渲染/导出管线挂载点规范](05-non-destructive-ai-assets-and-editgraph-v2-spec.md)

## Question

在 P3 已锁定「端侧 v1 采用零模型经典天空分割 + 分块 `GuidedFilter` 精修、预留 `SkySegmenter` 按需模型插槽、前景色彩协调挂 Pass B、天空合成挂 Pass D（位于 3D LUT / Grain / Vignette 之前）」的前提下，锁定 P3 剩余的产品与算法细节：

1. **前景色彩协调（Relighting / Color Harmonization）算法**：在 Pass B 中如何根据新天空的色温、主色调与亮度统计，用轻量确定性算法（如 OKLab 均值/高光色度偏移 + 亮度对比度联动）自动协调前景受光面，并提供「融合强度」滑杆防止穿帮？
2. **地平线与复杂边缘过渡**：针对山脊、建筑、树枝与水面反光，如何定义地平线位置偏移、垂直渐变羽化、以及边缘亮度/溢色抑制参数？
3. **天空素材来源、版权与分发策略**：是否随 APK 内置极少数 CC0/自制轻量天空（或全部按需下载），以及如何支持用户导入自定义天空图片（SAF + 外置内容寻址缓存）？

## Answer

基于零模型经典天空分割与 Pass B / Pass D 挂载点契约，锁定 P3 换天空完整规范如下：

### 1. 零模型经典天空分割基线与热插拔契约
- **统一接口**：定义 `SkySegmenter` 接口，输出 `0..1` 连续天空概率图；v1 默认实现为 **零模型经典天空分割**（顶部种子区域成长 + OKLCH 色度/高亮度连通性 + 垂直空间先验），后续可在不改 UI/Shader 的前提下通过 `ModelDownloadManager` 热插拔明确 MIT/Apache-2.0 的轻量 ONNX 模型（2–5 MB）。
- **边缘精修**：在预览期（1024px 代理）与全分辨率列带烘焙期（2048² + 32 px 重叠分块）统一走 `GuidedFilter.smooth(guide = Luma, p = skyProb, radius = 8, eps = 1e-3)`，对齐建筑轮廓、山脊与疏朗树枝。
- **无天空保护**：当检测到天空有效面积占比 `< 3%` 时，给出轻量胶囊提示「未检测到明显天空区域」，允许用户撤销或手动切至 P4 云端（如已开启）处理复杂逆光树冠场景。

### 2. Pass B 前景色彩协调（OKLab 确定性重光照，零分块拼缝）
- **Pre-pass 一次性统计**：在现有 1024px `Pre-pass` 阶段一次性计算「原天空区域 OKLab 均值 `(L_old, a_old, b_old)`」与「新天空素材 OKLab 均值 `(L_new, a_new, b_new)`」及差值 `(ΔL, Δa, Δb)`，作为统一 Uniform 传入预览与每个列带分块，彻底消除分块导出色差拼缝。
- **Pass B 受光协调**：对前景区域 `(1 - skyMask)` 按 `relightStrength (0..100)` 与中高光/受光软权重 `smoothstep(0.08, 0.75, L_fg)` 施加色度迁移 `(Δa, Δb)` 与限幅亮度联动 `clamp(ΔL, -0.12, +0.12)`，避免蓝天换落日后地面发冷或暗部死黑。

### 3. Pass D 地平线过渡与边缘去白边
- **空间对齐与过渡参数**：支持 `horizonOffset`（`-0.30..+0.30` 调整新天空纵向位置）、`horizonFeather`（`0..100` 地平线大气雾化渐变带）与 `edgeShift`（`-2..+2 px` 掩码微缩防亮边）。
- **边缘溢色抑制**：在 `skyMask ∈ (0.05, 0.95)` 过渡带内，将前景边缘像素色度轻微向新天空邻域均值混合（类似发丝去色溢的轻量化版本），抑制逆光建筑/树枝的亮白/青色轮廓边。
- **合成次序**：天空合成固定在 Pass D 的 **3D LUT → Grain → Vignette 之前**，确保换天后的整张成片共用同一套色彩查找表、胶片颗粒与暗角。

### 4. 天空素材分发与版权策略（0 KB APK 膨胀）
- **4 组程序化大气基准（0 KB，开箱离线可用）**：在 Shader 中用解析瑞利/米氏散射近似生成 4 组默认天空（晴空蔚蓝、落日余晖、蓝调时刻、暖调暮云），不占安装包空间且零版权风险。
- **用户自定义天空图（SAF 导入）**：支持用户从相册导入自己拍摄的真实云彩/星空照片，导入后以内容寻址文件存入 `filesDir/skies/sky_<sha256>.jpg`（长边封顶 4096 px 兼顾 24–60MP 烘焙清晰度与内存），在 `EditGraph.sky.assetRef` 中记录相对路径与 SHA-256；自定义天空支持在设置/天空面板中单独删除。
- **可选按需 CC0 高清云彩包**：复用 P0 多镜像 `ModelDownloadManager`（GitHub Releases + 镜像回退 + SHA-256 校验）按需下载精选 CC0 公共领域高清云彩包，素材清单与出处逐条登记于 `THIRD_PARTY_NOTICES.md`。
