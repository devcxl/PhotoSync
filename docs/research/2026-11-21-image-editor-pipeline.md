# 手机端图片编辑器：30 项能力的算法拆解与在 PhotoSync 内的落地方案

> **调研日期**：2026-11-21
> **范围**：Android `minSdk 24` / `compileSdk 36`；本仓库 `:app` + `:libraw`；RAW + JPEG 两条输入
> **关联材料**：`docs/dev/research/2026-10-27-ai-vlm-raw-tuning.md`（"LibRaw 只负责解码、观感必须另做一层"的结论）、`libraw/src/main/cpp/raw_postprocess.{h,cpp}`、`app/src/main/java/cn/devcxl/photosync/activity/MainActivity.kt`
> **本文回答**：曝光/亮度/对比度/光感/高光/阴影/白色色阶/黑色色阶/饱和度/自然饱和度/曲线/HSL/色调分离/色温/色调/褪色/锐化/清晰度/颗粒/降噪/晕影/色散/噪点/裁切旋转扭曲翻转/滤镜/人像美肤/面部重塑/美体这 30 项分别怎么实现、放在管线哪一段、依赖什么、在本仓库从哪改、按什么顺序做。

---

## 0. 结论摘要

1. **渲染核心自己写，用 GLES 3.0 + 自建 EGL/FBO 管线**。`android.media.effect` 没有 3D LUT、没有高光/阴影/HSL，且"部分效果并非所有设备可用"；`RenderScript` 自 API 31 起废弃；`RuntimeShader`（AGSL）从 API 33 才有，覆盖不了 `minSdk 24`。GLES 3.0 覆盖 API 24+ 的绝大多数设备（ES 2.0-only 的 Mali-400 级设备需在 Play 上用 `glEsVersion` 过滤或运行时降级）。
2. **两个工作空间，三段式**：解码层（LibRaw / ImageDecoder）→ **线性光 F16 工作空间**（白平衡、曝光、高光/阴影都在这里数学正确）→ **显示域（sRGB gamma）**（曲线、LUT 滤镜、颗粒、晕影按显示域语义做）。这也是 Lightroom/XMP `crs` 参数语义能对上的前提。
3. **点运算不要烘焙成 3D LUT 也能跑满帧**：30 个滑杆里绝大多数是逐像素运算，一个 fragment shader 里全算完即可；只有"滤镜"真的需要外部 `.cube` 3D LUT。烘焙 LUT 只在需要导出"可移植 look"时才做，避免预览/导出一致性问题。
4. **RAW 是差异化优势，不是包袱**：白平衡乘子、曝光、高光重建在 LibRaw 解码阶段做（16bit 线性输出），观感调节在 GPU 做。LibRaw 官方明确说自己的 RGB 渲染"不在 production-quality 范围内"，不要指望它出片。
5. **顺序即正确性**：几何（裁切/旋转/透视/翻转）→ 线性影调与白平衡 → 显示域色彩（曲线/HSL/分离色调/LUT）→ 空间细节（清晰度/锐化/降噪/去雾）→ 效果（颗粒/晕影/色散）→ 输出量化。顺序错了会出现"调清晰度把裁切边缘拉出黑边""降噪把颗粒抹平"这类问题。
6. **人像用 MediaPipe**：Face Landmarker 给 478 个 3D 关键点 + 52 个表情系数；Selfie Multiclass 分割给 `background/hair/body-skin/face-skin/clothes/others` 六类 mask（Pixel 6 上 GPU 71ms / CPU 218ms）。美肤 = mask + 引导滤波/频率分离；重塑 = 关键点驱动位移场（MLS/ARAP）；美体 = 人体 mask + 轴向局部缩放。全部端上推理，不上传。
7. **导出必须支持分块**：`Bitmap.compress` 需要整幅位图，100MP RAW 的 RGBA16F 放不下。分块渲染要带 halo（模糊半径/形变半径），且颗粒、晕影、遮罩必须用全局坐标，否则拼缝可见。大图导出建议在 `:editor` 里加 NDK 流式 JPEG 编码（libjpeg-turbo，IJG/BSD 系许可）。
8. **非破坏性数据模型是前提**：编辑参数存 JSON（`EditGraph`），Room 加一张 `photo_edits` 表；原始文件永不改写；导出走新的 MediaStore 条目。现有的 `PhotoEntity` 不动，`AppDatabase` 从 v3 升 v4。
9. **现有代码只做两处改动**：`RawWrapper` 增加"解码为线性 16bit"的入口（绕开 `raw_postprocess.cpp` 里硬编码的 tone curve）；`MainActivity` 的 `saveToGallery` 抽成可复用的 `GallerySink`，编辑页复用，PTP 链路零改动。
10. **分期落地**：P0 影调 + 几何 + LUT 滤镜 + 导出（约 4–6 周）；P1 曲线/HSL/分离色调 + 细节组（4–6 周）；P2 人像（5–8 周）；P3 AI 降噪/去雾/自动色散（4–8 周）。P0+P1 即可作为"可信的 v1"。

---

## 1. 现状与硬约束

### 1.1 仓库里已经有什么

| 事实 | 位置 | 对编辑器的影响 |
|---|---|---|
| 单 `:app` + `:libraw` 两个模块，无 Compose，ViewBinding | `settings.gradle`、`app/build.gradle` | 编辑器应作为**新模块** `:editor` 引入，避免 `MainActivity` 继续膨胀（已 1437 行） |
| RAW 解码只输出 **8bit RGB / ARGB_8888** | `RawWrapper.decodeToRGB` / `decodeToBitmap` | 必须新增 16bit 线性输出，否则曝光/曲线在 8bit 上做必出断层 |
| 解码后**硬编码**了一条 tone curve + ISO 自适应降噪 | `libraw/src/main/cpp/raw_postprocess.cpp`（`ToneCurveParams` 默认值 `0.78/0.12/0.18`） | 编辑模式需要"中性解码"开关，把这些默认值归零，观感交给 GPU |
| JPEG 走 `SubsamplingScaleImageView` 分块显示，RAW 走整幅 Bitmap | `PhotoPagerAdapter`（`resolvePhotoViewerMode`） | 编辑器需要**自己的**取图路径，不复用 ViewPager 的 cache |
| 导出在 `MainActivity.exportIndex` → `saveToGallery`（MediaStore + IS_PENDING） | `MainActivity.kt:1084-1270` | 抽 `GallerySink`，编辑导出复用同一套 API 29+/29- 分支 |
| Room `version = 3`，只存照片元数据 | `AppDatabase.kt` | 加 `photo_edits` 表 + `MIGRATION_3_4`，并补迁移测试 |
| 文件落在 `externalCacheDir`（PTP 下载） | `UsbPtpConnectionManager` / `SyncParams` | 缓存可能被系统清理 → 编辑记录要能识别"源文件已丢失"并优雅降级 |

### 1.2 不能碰的红线

- `minSdk 24`：不能假设 `RuntimeShader`（33）、`RGBA_F16` Bitmap（26）、`ImageDecoder`（28）、`GL_EXT_color_buffer_float`（ES 3.2）存在。
- **GLES 3.0 不是所有 API 24 设备的硬保证**：ES 2.0-only 的低端 GPU 仍在流通。要么在 manifest 声明 `glEsVersion 0x00030000 required` 让 Play 过滤，要么保留一条"仅基础影调"的 ES 2.0 降级路径（工作量约 +1 周）。
- 单文件 ≤ 500 行、函数 ≤ 50 行（`AGENTS.md`）：面板 + 滑杆 + 手势 + 渲染粘合代码很容易超，必须按"参数定义 / 面板 UI / 渲染 pass"切开。
- `ktlint`/`detekt` 未接入：不要顺手加插件，否则质量门禁会红。

---

## 2. 技术选型（含一手依据）

| 方案 | 结论 | 依据 |
|---|---|---|
| **GLES 3.0 + 自建 EGL** | **采用**。覆盖 API 24+ 的绝大多数设备；可写任意 shader；支持 3D 纹理（LUT）与多渲染目标 | ES 3.0 规范：纹理尺寸至少 2048；`RGBA16F` 在 sized format 表中**不是** color-renderable，需扩展（见下）。ES 2.0-only 设备用 manifest 的 `<uses-feature android:glEsVersion="0x00030000" android:required="true"/>` 过滤 |
| `GL_EXT_color_buffer_half_float` | **运行时探测**。有则中间缓冲用 `RGBA16F`，无则回落 `RGBA8` + 抖动 | Khronos 扩展原文：该扩展"allows 16-bit floating point formats … to be rendered to via framebuffer objects"，且"applications must check framebuffer completeness" |
| `android.graphics.RuntimeShader`（AGSL） | **不用于 v1**。API 33+ 才有，双实现会让预览/导出/QA 翻倍；可作为后续高端机快路径 | Android 官方参考："Added in API level 33" |
| `android.media.effect.EffectFactory` | **否决**。没有高光/阴影/HSL/曲线，没有自定义 shader，且官方声明"Some effects may not be available on all platforms" | Android 官方参考：`isEffectSupported` 语义 + 效果清单 |
| `RenderScript` | **否决**。API 31 废弃 | 官方参考："This class was deprecated in API level 31" |
| `androidx.graphics:graphics-core` | **可选**。1.0.x 起提供 `GLFrameBufferRenderer`/`FrameBuffer` 等脚手架，但定位是低延迟渲染；自建 EGL 更可控 | AndroidX Graphics 发布说明 |
| **OpenCV (`org.opencv:opencv`)** | **只做参考实现/离线校验**，不进主链路。Maven Central 已有 4.14.0 / 5.1.0 | Maven Central `maven-metadata.xml` |
| **MediaPipe Tasks Vision** | **采用**。`com.google.mediapipe:tasks-vision` 最新 1.2.3（Google Maven 元数据 2026-11-11），Apache-2.0 | Google Maven 元数据 + 仓库 LICENSE |
| **LibRaw** | **保留，仅解码**。LGPL-2.1 / CDDL-1.0 双许可；官方声明渲染非生产级 | libraw.org "About / Licensing" |
| **libjpeg-turbo**（NDK） | 分块导出时的流式编码器；IJG/BSD 系许可（以仓库 LICENSE.md 为准） | libjpeg-turbo 官方许可页 |
| **`.cube` LUT** | 滤镜生态的事实标准。3D 尺寸 N∈[2,256]，红通道索引变化最快，规范建议**四面体插值** | Adobe *Cube LUT Specification 1.0* |
| **XMP `crs:` 命名空间** | 仅用于"导出可互操作侧车"（可选）。属性名与 Lightroom 对齐，便于用户把参数带到桌面端 | Adobe XMP 命名空间文档 |

> 关于位深：`Bitmap.Config.RGBA_F16` 是 API 26+；`RGBA_1010102` 是 API 33+。预览链路在 API 24–25 上只能用 `ARGB_8888`，靠抖动掩盖 8bit 色带。

---

## 3. 管线总览

```
                    ┌──────────────── 源 ────────────────┐
RAW ──LibRaw(线性16bit)──┐                                 │
JPEG ──ImageDecoder──────┴──► 方向归一化 ──► 工作纹理      │
                                (RGBA16F / RGBA8)          │
                                                           │
  ┌──────────────────────── 预览：SurfaceView + EGL ───────┴─────────┐
  │  ① 几何 pass    ② 线性影调 pass   ③ 显示域色彩 pass              │
  │  ④ 空间细节 pass（可分离模糊 / 引导滤波 / 降噪）                  │
  │  ⑤ 效果 pass（LUT 滤镜 / 颗粒 / 晕影 / 遮罩合成）                 │
  │  ⑥ 输出 pass（gamma 编码 + 抖动 + 量化）                          │
  └──────────────────────────────────────────────────────────────────┘
                                                           │
  ┌──────────────── 导出：EGL pbuffer + 分块（带 halo）────┴─────────┐
  │  同样的 ①–⑥，按 tile 渲染 → 流式 JPEG / 整幅 Bitmap              │
  └──────────────────────────────────────────────────────────────────┘
```

### 3.1 阶段职责表

| # | 阶段 | 输入 → 输出 | 典型实现 | 分辨率 |
|---|---|---|---|---|
| ① | 几何 | 源纹理 → 校正后纹理 | 单 pass，逆映射采样；H 矩阵 + 位移场 | 工作分辨率 |
| ② | 线性影调 | 线性 RGBA → 线性 RGBA | 单 pass：白平衡矩阵、曝光、亮度、高光/阴影、局部对比输入 | 工作分辨率 |
| ③ | 显示域色彩 | 线性 → gamma | 单 pass：对比度、白/黑色阶端点、曲线（1D LUT 纹理）、饱和度/自然饱和度/HSL（OKLCH）、分离色调、褪色 | 工作分辨率 |
| ④ | 空间细节 | gamma → gamma | 多 pass：降采样模糊金字塔 → 清晰度/纹理/锐化/降噪/去雾 | 金字塔 + 全分辨率合成 |
| ⑤ | 效果 | gamma → gamma | LUT 3D 采样、颗粒（全局坐标）、晕影、色散补偿、遮罩/美肤合成 | 全分辨率 |
| ⑥ | 输出 | gamma → 8bit | sRGB 编码 + 三角抖动 + 量化 + 编码器 | 目标尺寸 |

> 为什么几何放最前：裁切后像素变少，后续 pass 成本下降；同时"post-crop 晕影"必须基于裁切后的画幅，先裁后做晕影自然成立。
> 为什么降噪不在最前：对 RAW，真正的降噪在解码阶段（传感器域）效果最好；显示域的"降噪"滑杆只是补救，放在细节组里可接受。

---

## 4. 参数语义与数学

统一约定：

- `Y = 0.2126R + 0.7152G + 0.0722B`（线性 sRGB，Rec.709 亮度系数）。
- `E(x)` = sRGB 编码（gamma），`E⁻¹` = 解码；滑杆归一化到 `[-1, 1]`（UI 再映射到 Lightroom 风格的范围，见附录 A）。
- **所有参数为 0 时，管线必须逐像素恒等**（这是最容易漏、也最容易被用户发现的 bug，测试里要单独断言）。
- 色彩运算在 **OKLab/OKLCH** 做（感知均匀，避免 HSL 的"蓝色一动就变紫"）。OKLab 是纯代数变换，GPU 上约 30–40 条指令。

### 4.1 影调组

| 参数 | 语义 | 实现（工作域） | 备注 |
|---|---|---|---|
| **曝光** Exposure | 整体明暗，单位 EV | 线性域 `rgb *= 2^EV`，EV∈[-5,+5] | 与 XMP `crs:Exposure2012` 同义；RAW 可把一部分让给 `exp_shift` |
| **亮度** Brightness | 只抬中间调，黑/白点不动 | 线性域乘增益 `g = 2^(b·w(Y))`，`w(Y)=exp(-½((log₂(Y/0.18))/1.1)²)` | 与"曝光"区分开：曝光抬全图，亮度只抬中灰附近 |
| **对比度** Contrast | 中间调 S 曲线 | gamma 域 `y' = mix(y, smoothstep(y), c)`；`c<0` 时 `y' = mix(y, 0.25 + 0.5y, −c)` | `smoothstep(y)=y²(3−2y)` 保端点、单调；负值压缩到中灰附近 |
| **高光** Highlights | 只动亮部 | 线性域增益，权重 `w_hi = smoothstep(0.5, 1.0, E(Y))` | 负值=回收高光；RAW 可在解码阶段先做一次 `highlight` 重建 |
| **阴影** Shadows | 只动暗部 | 同上，`w_sh = 1 − smoothstep(0.0, 0.5, E(Y))` | 提亮时按 `Y` 加权，避免整图发灰 |
| **白色色阶** Whites | 白点（上端） | gamma 域 `y' = clamp(y / W, 0, 1)`，`W = 1 − 0.20w` | 正值提亮高光并提前裁切；负值留 headroom |
| **黑色色阶** Blacks | 黑点（下端） | `B = −0.12b`；`y' = clamp((y − B)/(1 − B), 0, 1)` | 正值抬黑（哑光），负值压黑（死黑） |
| **光感** Glow | 复合："亮部更柔、暗部有细节、整体通透" | 三步小组合：① `y = y^(1/(1+0.25g))`；② `y += 0.10g(1−y)²`；③ `clarity += 0.15g` | 中文 App 语义不统一；若产品指的是 Snapseed "氛围"，把③换成"局部对比+饱和度"更贴切。**这是唯一需要产品拍板的参数** |
| **褪色** Fade | 胶片哑光 | `y = y(1−0.15f) + 0.12f`；`C *= 1−0.25f`；可选 `+0.02f` 蓝 | 在曲线之后、颗粒之前 |

**顺序**：白平衡 → 曝光 → 亮度 → 高光/阴影 → 对比度 → 白/黑色阶 → 曲线。（白平衡与曝光都是线性域乘性运算，可交换；但先白平衡再曝光更接近相机处理链。）

### 4.2 色彩组

#### 色温 / 色调

物理正确做法是**线性域色适应**（Bradford），不是"给蓝色乘个系数"：

```
mired = 1e6 / 6500 + 60·t            // t ∈ [-1,1]，负值变冷、正值变暖
T = 1e6 / mired                       // 约 4700K–10700K（±60 mired）
xy  = planckianLocus(T) + tint·(uv 垂线偏移)
XYZ_white_src = xyY → XYZ(D65)
adapt = M_A⁻¹ · diag(LMS_dst / LMS_src) · M_A   // M_A 为 Bradford 矩阵
rgb_lin = XYZ→sRGB · adapt · sRGB→XYZ · rgb_lin
```

- RAW："as shot" 白平衡已由 LibRaw 的 `cam_mul` 应用，滑杆是**相对偏移**；要做到"绝对开尔文"需要每机型标定，v1 不建议。
- JPEG：没有可信的拍摄白点，滑杆同样是相对值。
- 色调（Tint）在 `uv` 上垂直于等温线，范围约 ±150（对齐 `crs:Tint`）。

#### 饱和度 / 自然饱和度 / HSL

在 OKLCH 上操作：

```
饱和度      C *= 1 + s
自然饱和度  C *= 1 + v·(1 − C/0.32)·skin(h)
            skin(h) = 1 − 0.6·exp(−½((h−45°)/20°)²)      // 保护肤色
HSL         h += Σ wᵢ·Δhᵢ ;  C *= 1 + Σ wᵢ·ΔCᵢ ;  L *= 1 + 0.3·Σ wᵢ·ΔLᵢ
            wᵢ = exp(−½(Δhᵢ/25°)²)                         // 8 个色带
```

8 个色带中心（OKLCH 色相角，需按实测微调）：
红 29°、橙 55°、黄 110°、绿 142°、青 195°、蓝 264°、紫 300°、品红 328°。

> 为什么不用 HSL：sRGB-HSL 的"蓝"覆盖了从青到紫的极大范围，一动就串色；OKLCH 的色相更接近人眼，肤色带也更窄。

#### 曲线

- 控制点用 **Fritsch–Carlson 单调三次 Hermite** 插值，避免普通三次样条的过冲（过冲会在天空上产生光晕）。
- 生成 1024 项 1D LUT 纹理（R/G/B/A 四个通道打包：RGB 各一条 + 复合曲线一条）。
- 应用顺序：**先 RGB 复合曲线，再单通道曲线**（与 Camera Raw 的 point curve 行为一致）。
- 参数化区域（高光/亮调/暗调/阴影）直接合入同一条 LUT，不额外开 pass。

#### 色调分离（Split Toning）

```
k = 0.5 − 0.25β                       // β 为平衡滑杆（-1..1）
w_hi = smoothstep(k, 1.0, L)
w_sh = 1 − smoothstep(0.0, k, L)
h = mixHue(h, H_hi, w_hi·S_hi) ; C = mix(C, C_target, w_hi·S_hi)
h = mixHue(h, H_sh, w_sh·S_sh) ; C = mix(C, C_target, w_sh·S_sh)
```

β 越大，交界越靠暗部 → 高光着色范围越大（对齐 Lightroom 的 Balance 方向）。色相混合必须走**最短弧**，否则 350°→10° 会绕一圈。

#### 滤镜（3D LUT）

- 解析 `.cube`（`LUT_3D_SIZE`、`DOMAIN_MIN/MAX`、R 变化最快）。
- GPU 用 `sampler3D`（ES 3.0 支持 3D 纹理）+ **四面体插值**；33³ 约 4 次采样。
- 强度滑杆 = 与原图 `mix`，在 gamma 域做（LUT 通常按显示域 authoring）。
- 内置滤镜优先用"参数预设"（= 一组 EditGraph 值）而不是 LUT 文件：可无损、可继续微调、体积小；社区 LUT 才走 `.cube`。

### 4.3 细节组

| 参数 | 公式 / 做法 | 坑 |
|---|---|---|
| **锐化** Sharpen | `hi = Y − gaussian(Y, r)`；`edge = gradMag(Y)`；`Y' = Y + amount·hi·gate(edge)·gate(threshold)` | 只作用于 Y，避免彩边；半径按**全分辨率像素**定义，预览时乘以缩放比，否则预览骗人 |
| **清晰度** Clarity | 大半径（≈2% 短边）高通，限幅 `clamp(Y−blur, ±0.08)`，再乘中间调权重 | 不限幅会在天空/边缘出黑边白边；追求更好效果用引导滤波代替高斯 |
| **纹理** Texture | 同清晰度但半径 ≈0.3% 短边 | Lightroom 有，可后置；与清晰度共用一个金字塔 |
| **去雾** Dehaze | 暗通道先验：`Idark = min_c min_filter(I)`；`A = 最亮 0.1%`；`t = 1 − ω·Idark/A`；`J = (I−A)/max(t,0.1) + A` | 在降采样图上求 `t` 再引导滤波上采样；ω 上限 0.95，否则暗部噪点爆炸 |
| **降噪** NR | 亮度：引导滤波（r=2，eps=σ²），detail 回混；色度：CbCr 大半径模糊 | 8bit 输入做降噪会糊；RAW 应在解码阶段用 `threshold/med_passes/fbdd` 或 AI 模型 |
| **颗粒** Grain | `n = noise(globalXY/size + seed)`；`Y' = Y + n·amp·√(4Y(1−Y))`；三通道 30% 去相关 | 必须在**全局坐标**生成，分块导出才无缝；种子存进 EditGraph，保证预览=导出 |
| **噪点** Noise | 若指"加噪"：同颗粒但均匀分布、无尺寸控制；若指"噪声"：见降噪 | 命名歧义要在 UI 文案上消歧 |
| **晕影** Vignette | `r = 归一化到裁切画幅中心`；`f = 1 + v·0.6·smoothstep(mid, 1, r)`；中点为 mid，圆度调 aspect | 必须是 **post-crop**；正向=vignette 加亮，负向=压暗（对齐 `crs:VignetteAmount`） |
| **色散** CA | 横向色差：R/B 通道绕光心按 `(1 + k·r²)` 缩放采样；纵向色差用紫/绿边去饱和 | 自动估计 k 需要多尺度边缘对齐；v1 先做手动 R/C、B/Y 两滑杆 + defringe |

### 4.4 几何组

| 能力 | 实现 |
|---|---|
| 方向归一化 | 读 EXIF Orientation，在解码后统一旋转/镜像。不要依赖解码器是否自动应用方向，自己归一化一次最稳 |
| 旋转 90° | 交换宽高 + 采样坐标置换（整数矩阵，无插值损失） |
| 翻转 | 负缩放，`uv = 1−uv` |
| 裁切 | 归一化矩形（0..1，基于"方向归一化后"的图），裁剪 + 重新映射 uv；UI 提供比例锁 |
| 拉直（任意角度） | 旋转 + 自动内接矩形裁切（按角度算最大内接矩形），避免黑角 |
| 透视（"扭曲"①） | 4 点单应 `H`（`Matrix.setPolyToPoly` 只用于 UI），shader 里用 `H⁻¹` 逆映射采样；bilinear，必要时 2×2 超采样抗锯齿 |
| 液化（"扭曲"②） | 画笔位移场：把笔刷位移累加到半分辨率 displacement texture，fragment 里 `uv += texture(disp, uv)`；支持撤销栈 |
| 镜头畸变（可选） | Brown–Conrady `r' = r(1 + k₁r² + k₂r⁴)`；没有镜头档案时给手动 k₁ 滑杆。**不要直接引入 lensfun**（LGPL-3.0，APK 静态链接合规成本高） |

### 4.5 人像组

#### 依赖的模型

| 任务 | 输出 | 用途 |
|---|---|---|
| `FaceLandmarker`（`face_landmarker.task`） | 478 个 3D 归一化关键点、52 个 blendshape、4×4 姿态矩阵 | 面部重塑的控制点；眼睛/嘴唇/眉毛保护区域；微笑/张嘴驱动 |
| `ImageSegmenter` + `selfie_multiclass_256x256.tflite` | 6 类：background / hair / body-skin / face-skin / clothes / others | 皮肤平滑的 mask；美体的身体 mask；背景保护 |
| `ImageSegmenter` + `deeplab_v3.tflite`（可选） | 通用类别（person 等） | 非自拍、全身照的人体 mask |
| `FaceLandmarker` 的连接表 | 脸轮廓/眼/唇的折线 | 直接当控制点分组，不用自己维护索引 |

> 模型文件放 `assets/` 或 Play Asset Delivery；`tasks-vision` 本身 Apache-2.0，模型许可随 HuggingFace 模型卡。

#### 美肤

```
M   = feather(segment(face-skin) ∪ segment(body-skin)) ∩ skinColorGate
base = guidedFilter(I, r=8..16, eps)
det  = I − base
out  = base + det·(1 − s·M)          // s ∈ [0, 0.9]
```

- **只压高频、保留低频**：直接模糊会塑料感；频率分离能保留毛孔和光影。
- 眼睛/嘴唇/眉毛/发丝必须从 mask 里扣除（landmark 区域 + hair mask），否则"眼睛被磨平"。
- 色度单独处理：CbCr 的平滑半径可以更大，亮度保留细节，肤色更均匀而不糊。
- 可选"祛斑"：小半径 patch 修复（Poisson/`seamlessClone`），属于独立功能，不要混进"美肤"滑杆。

#### 面部重塑

控制点 + 位移：

| 预设 | 控制点 | 位移规则 |
|---|---|---|
| 瘦脸 | 下颌线/颧骨点 | 朝脸中线收 `α·d`，`d` 为该点到中线的距离，α≈0.02–0.08 |
| 大眼 | 眼周点 | 以眼中心做径向缩放，半径≈2.5×眼宽，α≈0.05–0.12 |
| 缩鼻 | 鼻翼点 | 朝鼻梁中线收 |
| 下巴/额头 | 下巴尖/发际线 | 纵向拉伸/压缩，带 smooth falloff |
| 嘴型/微笑 | 嘴角点 | 水平外扩 + blendshape 驱动上扬 |

实现：用 **MLS（移动最小二乘）相似变换**在 64×64 网格上求解位移场 → 上采样成纹理 → fragment 逆映射采样。不要在导出时按像素解 MLS（100MP 会卡死），位移场是分辨率无关的。

#### 美体

```
// 瘦腰：水平挤压，纵向窗口
x' = x + α·(x − cx)·f(y)·P(x,y)
f(y) = smoothstep(y0, y1, y) · (1 − smoothstep(y2, y3, y))
// 拉腿：髋部以下纵向拉伸，接缝处用平滑 ramp
y' = y + β·g(y),  g 在髋线处 C¹ 连续
```

- **必须乘人体 mask `P`** 并羽化，否则背景直线会跟着弯。
- 拉腿会改变画幅比例：要么裁切，要么允许输出尺寸变化（建议裁切）。
- 背景保护的高级做法是 content-aware resampling（seam carving / 结构线保护），成本高，放 P3。

> 合规：人脸关键点属于生物识别相关信息。PIPL 下"人脸信息应存储于人脸识别设备内、不得通过互联网对外传输"（《人脸识别技术应用安全管理办法》）。本方案全程端上、不落盘关键点、不上传，是唯一低风险路线。

---

## 5. 渲染引擎设计

### 5.1 线程与表面

- 一条专用 GL 线程 + 一个 EGLContext；预览用 `SurfaceView`（比 `TextureView` 少一次合成拷贝，缩放/裁剪由 shader 做）。
- 导出用 **surfaceless EGL / pbuffer**，与预览共享 context（`EGL_KHR_share_context` 或直接同 context 串行），避免 shader 重编译。
- `RENDERMODE_WHEN_DIRTY`：只在参数变化/手势时渲染，省电；拖动时降到 1024px 代理分辨率，松手后 150ms 再出全质量帧。

### 5.2 精度与格式回落

| 能力 | 条件 | 回落 |
|---|---|---|
| `RGBA16F` 中间缓冲 | `GL_EXT_color_buffer_half_float` + FBO 完整性检查通过 | `RGBA8` + 输出抖动；曲线 LUT 仍用 16bit 精度算，只是存储 8bit |
| 3D 纹理 LUT | ES 3.0 必有 `sampler3D` | 无（ES 3.0 是硬门槛） |
| 大图纹理 | `GL_MAX_TEXTURE_SIZE` 至少 2048，实际 4096–16384 | 分块渲染；不要假设 8192 |
| 浮点渲染目标 32F | `EXT_color_buffer_float`（ES 3.2 常见） | 不用，16F 足够 |

> 预览的色彩管理：EGL 输出按 **sRGB** 走，宽色域屏由系统合成时做转换。不要在预览里自己乘 P3 矩阵——那会让"屏幕上看到的"与"导出的 sRGB 文件"不一致，是最难查的一类色偏。

### 5.3 Pass 预算

| 场景 | pass 数 | 目标 |
|---|---|---|
| 拖动滑杆（仅点运算变化） | 1（几何缓存 + 单个色彩/影调 shader） | ≤ 8ms @1440×1080 中端机 |
| 松手（含清晰度/降噪） | 5–7 | ≤ 33ms |
| 导出 24MP | 每 tile 6 pass，tile 1024–2048 | ≤ 3s（不含 RAW 解码） |

### 5.4 分块导出

- tile 尺寸 1024 或 2048；**halo = 最大空间半径**（清晰度 ≈2% 短边、降噪半径、CA 位移、形变半径取最大值）。
- 点运算阶段（①②③）对 padded tile 直接算，代价可忽略；空间阶段只在 padded 区域内做，输出裁掉 halo。
- 全局坐标量：颗粒、晕影、遮罩、形变场、LUT 坐标 —— 全部按"整图 uv"计算，不能按 tile 局部 uv。
- 编码：`Bitmap.compress` 只能整幅；tile 方案必须用 NDK 流式 JPEG（`jpeg_write_scanlines`，按行顺序写）。若产品接受 24MP 以内，可先走"整幅 Bitmap + compress"，把分块留到 P1。

### 5.5 内存与 RAW 上限

- 100MP RAW 全解码：`100e6 × 3 × 2B = 600MB`，必然 OOM。策略：
  1. 预览：LibRaw `half_size=1`（2×2 合并，顺带降噪），或解码后缩放到 2048 长边；
  2. 导出：长边上限 8000px（可配置），超过则用 `half_size` 或拒绝并提示；
  3. 全程 `Bitmap.recycle()` + 纹理池 + `onTrimMemory` 释放。
- 纹理池按 (w,h,format) 复用；预览/导出切换时清空。

### 5.6 取消与异常

- 每帧/每 tile 检查 `coroutineContext.isActive`；GL 调用之间插入 `glFinish` 检查点（只在导出用，预览不用）。
- shader 编译失败 → 降级到"仅几何 + 基础影调"的最小管线，并 `Timber.e` 记录 GPU 型号/GL_VERSION/扩展列表（诊断必需）。
- 设备黑名单：按 `Build.HARDWARE`/`GL_RENDERER` 维护已知坏驱动列表（例如某些 Mali 的 3D 纹理采样 bug），走 RGBA8 + 无 LUT 路径。

---

## 6. 数据模型与模块边界

### 6.1 EditGraph（非破坏性）

```json
{
  "schema": 1,
  "photoId": 42,
  "ops": [
    {"id": "geometry", "crop": [0.05, 0.02, 0.95, 0.98], "rotate90": 0, "flipH": false,
     "straighten": -1.2, "perspective": [0,0, 1,0, 1,1, 0,1], "liquify": null},
    {"id": "tone", "exposure": 0.35, "brightness": 0.0, "contrast": 12,
     "highlights": -30, "shadows": 18, "whites": 5, "blacks": -4, "glow": 0.0},
    {"id": "color", "temp": -8, "tint": 2, "saturation": 4, "vibrance": 15,
     "hsl": {"red": [0,0,0], "...": [0,0,0]},
     "split": {"shHue": 210, "shSat": 8, "hiHue": 45, "hiSat": 4, "balance": 0},
     "curve": {"rgb": [[0,0],[0.25,0.22],[0.75,0.8],[1,1]], "r": [], "g": [], "b": []},
     "fade": 0.0, "lut": {"id": "kodak_2383", "strength": 0.6}},
    {"id": "detail", "clarity": 10, "texture": 0, "sharpen": {"amount": 40, "radius": 0.8, "threshold": 3, "masking": 20},
     "nr": {"luma": 20, "chroma": 25, "detail": 50}, "dehaze": 0},
    {"id": "effects", "grain": {"amount": 12, "size": 25, "roughness": 50},
     "vignette": {"amount": -12, "midpoint": 50, "feather": 60, "roundness": 0},
     "ca": {"redCyan": 0, "blueYellow": 0, "defringe": 0}},
    {"id": "portrait", "skin": {"smooth": 30, "texture": 50, "evenTone": 10},
     "reshape": {"face": 20, "eyes": 15, "nose": 0, "chin": 10, "smile": 0},
     "body": {"waist": 0, "legs": 0}}
  ]
}
```

要点：

- **每个 op 有独立 `id`**，便于局部失效重算与撤销；
- 撤销栈保存**不可变快照**（最多 50 步）；连续拖动滑杆按 300ms 合并为一次事务，避免一步一帧；
- 数值字段全部带范围校验（边界处 clamp，拒绝 NaN/Inf），永不把外部 JSON 直接塞进 shader uniform；
- `schema` 版本化；迁移函数按 `v1→v2` 顺序执行，老照片永远可打开；
- 关键点/遮罩**不进** EditGraph（体积大、可重建），只存"检测版本 + 模型版本 + 输入哈希"，命中缓存则复用。

### 6.2 Room

```kotlin
@Entity(tableName = "photo_edits", indices = [Index("photo_id", unique = true)])
data class PhotoEditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "graph_json") val graphJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
```

- 升级 `AppDatabase` v3 → v4，新增 `MIGRATION_3_4`（`CREATE TABLE` + 唯一索引），补一条迁移测试；
- **注意**：当前 `AppDatabase` 配置了 `fallbackToDestructiveMigration()`，迁移写错会静默清库；编辑器上线前要么补齐迁移，要么在 v4 起改为显式迁移并移除该回退；
- 缩略图/预览缓存放 `cacheDir/edits/<photoId>/<hash>.jpg`，按 LRU 清理，不进 Room；
- 源文件缺失（缓存被系统清理）时，编辑记录保留但 UI 标灰，提示重新从相机同步。

### 6.3 模块与依赖方向

```
:app ──► :editor ──► :libraw        (只依赖 RawWrapper)
  │         │
  │         └──► MediaPipe / OpenCV(可选)
  └──► :libraw

:editor/
  domain/       EditGraph, Preset, EditValidator, Curve, HslBands     (纯 Kotlin，无 Android)
  render/       GlEngine, GlProgram, TexturePool, PassChain, TileExporter
  render/pass/  GeometryPass, TonePass, ColorPass, DetailPass, EffectsPass, OutputPass
  lut/          CubeLutParser, LutTexture
  vision/       FaceLandmarks, SkinMask, WarpField, PortraitAnalyzer
  export/       Exporter, GallerySink, JpegStreamWriter(NDK)
  data/         PhotoEditEntity, PhotoEditDao, EditRepository
  ui/           EditorActivity, EditorViewModel, panels/*, widgets/*
  src/main/cpp/ jpeg_stream_writer.cpp, CMakeLists.txt
```

- `domain` 不引 Android，可直接 JVM 单测（符合 `AGENTS.md` 的测试要求）；
- `:editor` 不反向依赖 `:app`；
- `MainActivity` 只增加：长按/点击工具栏 → `EditorActivity.start(this, photoId)`。

### 6.4 现有代码的最小改动

1. **`RawWrapper`**：新增
   ```kotlin
   external fun decodeToLinear16(path: String, maxLongEdge: Int): ByteArray?  // [w:4][h:4][RGB16...]
   ```
   在 JNI 里设置 `output_bps=16`、`gamm[0]=1, gamm[1]=1`（线性）、`no_auto_bright=1`、`user_qual` 保持默认；**不要**调用现有 `writeProcessedToRgb888`（会套 tone curve）。
2. **`raw_postprocess.cpp`**：抽出 `ToneCurveParams::neutral()`（`shadowMidtoneGamma=1, sCurveStrength=0, vibrance=0`），编辑路径显式传入，导出/浏览路径保持现状。
3. **`MainActivity.saveToGallery`**：迁移为 `:editor` 的 `GallerySink.save(displayName, mime, write)`，`MainActivity` 调用同一实现，行为不变（含 API 29 `IS_PENDING` 与 29- 直接写文件 + `MediaStore` 广播）。
4. **`AppDatabase`**：v4 + 迁移，暴露 `photoEditDao()`；`PhotoDao` 保持不变。
5. **`strings.xml`**：所有滑杆名、分组名、提示走资源，不硬编码（含中英）。

---

## 7. 性能、兼容与验收

### 7.1 预算

| 指标 | 目标 | 测量 |
|---|---|---|
| 预览帧时间（仅影调/色彩） | ≤ 16ms @ 1440×1080，中端机（SD 7 系 / 天玑 8000） | `EXT_disjoint_timer_query` 或 `adb shell dumpsys gfxinfo` |
| 拖动响应（降分辨率代理） | ≤ 50ms 感知延迟 | 手动 + macrobenchmark |
| 人脸检测（1024px） | ≤ 300ms | MediaPipe 官方基准：SelfieMulticlass Pixel 6 GPU 71ms；FaceLandmarker 需实测 |
| 导出 24MP JPEG | ≤ 3s（不含 RAW 解码） | 端上埋点，分阶段计时 |
| 峰值内存 | ≤ 512MB（8GB 机型） | `dumpsys meminfo` |

### 7.2 兼容矩阵

- GPU：Adreno / Mali / PowerVR 各取 2 台真机；模拟器（SwiftShader）只做逻辑回归，不做性能。
- API：24（最低）、26（F16 Bitmap）、28（ImageDecoder）、29（MediaStore IS_PENDING）、33（可选 AGSL 快路径）。
- 屏幕：挖孔/折叠屏/横屏；裁切比例与安全区无关，但 UI 面板要适配。

### 7.3 验收

- **恒等测试**：所有参数为 0，输出与输入逐像素差 ≤ 1/255（8bit 路径）。
- **金标图**：每类参数各 5 组极端值，与 CPU 参考实现比 ΔE2000 ≤ 1.5（点运算）／结构相似度 SSIM ≥ 0.99（空间运算）。
- **顺序测试**：随机 100 组参数，验证"先裁切后晕影"与"先晕影后裁切"结果不同且符合预期。
- **分块一致性**：同一张图整幅导出 vs 分块导出，逐像素差 ≤ 1。
- **EXIF 方向**：8 种 orientation × (JPEG/RAW) 全绿。
- **LUT 解析**：畸形 `.cube`（行数不符、domain 反了、超 256）必须拒绝而不是崩溃。

---

## 8. 分期计划

| 阶段 | 内容 | 交付物 | 估时（1 名熟悉图形学的 Android 工程师） |
|---|---|---|---|
| **P0** | 几何（方向/旋转/翻转/裁切/拉直）、影调组、饱和度/自然饱和度、色温/色调、导出、EditGraph + Room、入口 | 可用的"基础调节 + 导出" | 4–6 周 |
| **P1** | 曲线、HSL、分离色调、褪色、清晰度/纹理/锐化/降噪、晕影、颗粒、LUT 滤镜、分块导出 | 对齐 Lightroom 基础面板的 v1 | 4–6 周 |
| **P2** | 人像：美肤、面部重塑（瘦脸/大眼/鼻/下巴/微笑）、美体（瘦腰/拉腿）、液化 | 人像模式 | 5–8 周 |
| **P3** | 去雾、自动色散估计、AI RAW 降噪（PMRID 类）、宽色域/Display P3、XMP 侧车互操作 | 差异化能力 | 4–8 周 |

> 先把 P0 的"导出正确性"打穿（颜色、方向、EXIF、内存），再堆功能；否则后面每个功能都要重新验一遍导出。

---

## 9. 风险与开放问题

| 风险 | 影响 | 应对 |
|---|---|---|
| "光感"语义不明确 | 做出来不是产品想要的 | 先按 §4.1 的复合定义实现，UI 文案写"光感（氛围）"；提供 A/B 两套权重，用真实照片评审 |
| 与 Lightroom 数值对不齐 | 用户预期落空 | 明确定位为"观感接近"，不做逐数值对齐；用同一张 ColorChecker/灰卡做主观校准 |
| GPU 碎片化 | 花屏/黑屏/色偏 | ES 3.0 基线 + 扩展探测 + 设备黑名单 + 最小管线降级 |
| 大图/RAW 内存 | OOM、崩溃 | 长边上限、`half_size` 预览、分块导出、`onTrimMemory` |
| 人像模型体积/许可 | 包体增大、上架审核 | 模型走 Play Asset Delivery；保留模型卡与许可声明 |
| 生物识别合规 | 法律风险 | 全程端上、不上传、不持久化关键点、首次使用弹披露 |
| 8bit 色带 | 天空/渐变出现断层 | 16F 中间缓冲 + 输出三角抖动；无扩展时降低对比度滑杆上限并提示 |

**需要产品/技术负责人拍板的 3 件事**：
1. "光感"到底指"氛围"还是"曝光感知"？（决定公式）
2. v1 是否包含人像？（决定是否先引入 MediaPipe 依赖与模型）
3. 导出是否允许改变画幅（拉腿/透视）？还是强制裁切回原比例？

---

## 附录 A：参数总表

| UI 名称 | 规范名 | 范围 / 默认 | 作用域 | 实现要点 | XMP 近似 |
|---|---|---|---|---|---|
| 曝光 | exposure | -5..+5 / 0 | 线性 | `×2^EV` | `crs:Exposure2012` |
| 亮度 | brightness | -100..100 / 0 | 线性 | 中灰加权增益 | （Process 2010 `crs:Brightness`） |
| 对比度 | contrast | -100..100 / 0 | gamma | smoothstep 混合 | `crs:Contrast2012` |
| 光感 | glow | -100..100 / 0 | 线性+gamma | 三段复合 | 无 |
| 高光 | highlights | -100..100 / 0 | 线性 | 亮部加权增益 | `crs:Highlights2012` |
| 阴影 | shadows | -100..100 / 0 | 线性 | 暗部加权增益 | `crs:Shadows2012` |
| 白色色阶 | whites | -100..100 / 0 | gamma | 白点缩放 | `crs:Whites2012` |
| 黑色色阶 | blacks | -100..100 / 0 | gamma | 黑点偏移 | `crs:Blacks2012` |
| 色温 | temperature | -100..100 / 0（RAW 为相对） | 线性 | Bradford 色适应 | `crs:Temperature`（2000–50000K） |
| 色调 | tint | -100..100 / 0 | 线性 | uv 垂直偏移 | `crs:Tint`（-150..150） |
| 饱和度 | saturation | -100..100 / 0 | OKLCH | `C×(1+s)` | `crs:Saturation` |
| 自然饱和度 | vibrance | -100..100 / 0 | OKLCH | 低饱和加权 + 肤色保护 | `crs:Vibrance` |
| HSL | hsl[8] | 每带 -100..100 / 0 | OKLCH | 8 带高斯权重 | `crs:HueAdjustmentRed` 等 |
| 曲线 | curve | 4 组控制点 / 线性 | gamma | 单调三次 + 1D LUT | `crs:ToneCurve` |
| 色调分离 | splitToning | 色相 0..360，饱和 0..100，平衡 -100..100 | gamma | 明暗加权混色 | `crs:SplitToning*` |
| 褪色 | fade | 0..100 / 0 | gamma | 黑点抬升 + 降饱和 | （无直接对应） |
| 锐化 | sharpen | 数量 0..150，半径 0.5..3px，阈值 0..100，蒙版 0..100 | Y 通道 | 反锐化掩模 + 边缘门控 | `crs:Sharpness` |
| 清晰度 | clarity | -100..100 / 0 | gamma | 大半径高通 + 限幅 | `crs:Clarity2012` |
| 纹理 | texture | -100..100 / 0 | gamma | 中半径高通 | `crs:Texture` |
| 降噪 | nr | 亮度 0..100，色度 0..100，细节 0..100 | gamma | 引导滤波 + 色度模糊 | `crs:LuminanceSmoothing` / `crs:ColorNoiseReduction` |
| 颗粒 | grain | 数量 0..100，大小 0..100，粗糙度 0..100 | gamma | 全局坐标噪声 | `crs:GrainAmount` |
| 晕影 | vignette | 数量 -100..100，中点 0..100，羽化 0..100，圆度 -100..100 | gamma | 裁切后径向 | `crs:VignetteAmount` |
| 色散 | ca | R/C -100..100，B/Y -100..100，去边 0..100 | 线性采样 | 径向缩放 + hue 去饱和 | `crs:ChromaticAberrationR/B` |
| 裁切 | crop | 归一化矩形 | 几何 | 逆映射 | `crs:HasCrop/Crop*` |
| 旋转 | rotate | 90°×4 + 拉直 -45..45 | 几何 | 整数矩阵 + 内接裁切 | `crs:CropAngle` |
| 翻转 | flip | H/V | 几何 | 负缩放 | — |
| 透视 | perspective | 4 角点 | 几何 | 单应矩阵 | `crs:Perspective*` |
| 液化 | liquify | 笔刷 | 几何 | 位移场纹理 | — |
| 滤镜 | lut | 0..100 强度 | gamma | `.cube` + 四面体插值 | `crs:Look` |
| 美肤 | skin | 平滑/纹理/匀肤 0..100 | 人像 | mask + 频率分离 | — |
| 面部重塑 | reshape | 脸/眼/鼻/下巴/微笑 -100..100 | 人像 | 关键点 + MLS | — |
| 美体 | body | 腰/腿 -100..100 | 人像 | 人体 mask + 轴向缩放 | — |

## 附录 B：核心 shader 伪代码

```glsl
// ② 线性影调（示意，省略 uniform 声明）
vec3 toneLinear(vec3 c) {
    c = wbMatrix * c;                       // Bradford（已在 CPU 预乘）
    c *= exp2(uExposure);
    float Y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float wB = exp(-0.5 * pow(log2(max(Y, 1e-5) / 0.18) / 1.1, 2.0));
    c *= exp2(uBrightness * wB);
    Y = dot(c, vec3(0.2126, 0.7152, 0.0722));   // 亮度已变，权重要用新的 Y
    float y = encodeSrgb(Y);
    float wHi = smoothstep(0.5, 1.0, y);
    float wSh = 1.0 - smoothstep(0.0, 0.5, y);
    c *= exp2(uHighlights * wHi + uShadows * wSh);
    return c;
}

// ③ 显示域色彩（示意；影调作用在亮度上，再按比例带回 RGB，避免偏色）
// oklch 分量约定：x=L, y=C, z=色相角
vec3 colorDisplay(vec3 c) {
    float Y  = luminance(c);
    float y0 = encodeSrgb(Y);                                 // 显示域亮度
    float y1 = mix(y0, smoothstep(0.0, 1.0, y0), uContrast);  // 对比度
    y1 = clamp((y1 + 0.12 * uBlacks) / (1.0 + 0.12 * uBlacks), 0.0, 1.0);
    y1 = clamp(y1 / (1.0 - 0.20 * uWhites), 0.0, 1.0);
    vec3 c2 = c * (decodeSrgb(y1) / max(Y, 1e-6));            // 线性域替换亮度，保色相
    vec3 oklch = linearSrgbToOklch(c2);
    oklch.y *= 1.0 + uSaturation;                             // C 分量
    oklch = applyHslBands(oklch);
    return oklchToSrgb(oklch);
}

// ⑤ 四面体 LUT 采样（3D 纹理；size = LUT_3D_SIZE）
vec3 sampleLut(sampler3D lut, vec3 c, float size) {
    vec3 p = c * (size - 1.0);
    vec3 f = fract(p);
    ivec3 i = ivec3(floor(p));
    // 6 个顶点按 f 排序取 4 个，做四面体插值；此处省略分支
    return tetrahedral(lut, i, f);
}
```

## 附录 C：来源

| 来源 | 类型 | 作者/机构 | 时间 | 权威 | 一手 | 时效 | 独立 | 综合 |
|---|---|---|---|---|---|---|---|---|
| [RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader) | 官方 API 文档 | Google | 持续 | 5 | 5 | 5 | 5 | 25 |
| [RenderScript（deprecated in 31）](https://developer.android.com/reference/android/renderscript/RenderScript) | 官方 API 文档 | Google | 持续 | 5 | 5 | 5 | 5 | 25 |
| [EffectFactory](https://developer.android.com/reference/android/media/effect/EffectFactory) | 官方 API 文档 | Google | 持续 | 5 | 5 | 4 | 5 | 24 |
| [Bitmap.Config（RGBA_F16 API 26）](https://developer.android.com/reference/android/graphics/Bitmap.Config) | 官方 API 文档 | Google | 持续 | 5 | 5 | 5 | 5 | 25 |
| [ImageDecoder（API 28）](https://developer.android.com/reference/android/graphics/ImageDecoder) | 官方 API 文档 | Google | 持续 | 5 | 5 | 5 | 5 | 25 |
| [glTexImage2D（ES 3.0 sized formats）](https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glTexImage2D.xhtml) | 规范参考页 | Khronos | 持续 | 5 | 5 | 5 | 5 | 25 |
| [EXT_color_buffer_half_float](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_color_buffer_half_float.txt) | 扩展规范 | Khronos | 2017-09 | 5 | 5 | 5 | 5 | 25 |
| [MediaPipe Image Segmenter（模型/延迟）](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter) | 官方文档 | Google | 持续 | 5 | 5 | 5 | 5 | 25 |
| [MediaPipe Face Landmarker（478 点）](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android) | 官方文档 | Google | 持续 | 5 | 5 | 5 | 5 | 25 |
| [LibRaw About / Licensing](https://www.libraw.org/about) | 官方页面 | LibRaw LLC | 持续 | 5 | 5 | 5 | 5 | 25 |
| [Practical Deep Raw Image Denoising on Mobile Devices](https://arxiv.org/abs/2010.06935) | 论文 | Sony / arXiv | 2020-10 | 5 | 5 | 4 | 5 | 24 |
| [Cube LUT Specification 1.0](https://kono.phpage.fr/images/a/a1/Adobe-cube-lut-specification-1.0.pdf) | 规范 | Adobe | 2013-09 | 5 | 5 | 4 | 5 | 24 |
| [XMP Camera Raw namespace](https://developer.adobe.com/xmp/docs/xmp-namespaces/crs/) | 规范 | Adobe | 持续 | 5 | 5 | 5 | 5 | 25 |
| [XMP 规范 Part 1/2/3](https://developer.adobe.com/xmp/docs/xmp-specifications/) | 规范 | Adobe / ISO 16684 | 持续 | 5 | 5 | 5 | 5 | 25 |
| [androidx.graphics 发布说明](https://developer.android.com/jetpack/androidx/releases/graphics) | 官方文档 | Google | 2026-10 | 4 | 5 | 5 | 5 | 24 |
| [libjpeg-turbo 许可](https://libjpeg-turbo.org/About/License) | 官方页面 | libjpeg-turbo | 2021 | 4 | 5 | 4 | 5 | 23 |
| [《人脸识别技术应用安全管理办法》](https://www.gov.cn/zhengce/zhengceku/202503/content_7016075.htm) | 法规 | 中国政府网 | 2025-03 | 5 | 5 | 5 | 5 | 25 |
| [org.opencv:opencv 版本元数据](https://repo1.maven.org/maven2/org/opencv/opencv/maven-metadata.xml) | 仓库元数据 | Maven Central | 2026-11 | 4 | 5 | 5 | 5 | 24 |

> 说明：`crs:Exposure2012` / `Highlights2012` / `Texture` / `Dehaze` 等 Process 2012 之后的属性名，以 Adobe XMP 命名空间为准；ExifTool 的 XMP 表是常用的交叉核对来源，本文只把 Adobe 页面作为承重依据，二手工具页仅作旁证。
