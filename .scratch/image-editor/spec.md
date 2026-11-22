# PhotoSync 非破坏性图片编辑器与联机暗房规格说明

Status: ready-for-agent

## Problem Statement

摄影师使用 PhotoSync 通过 USB PTP 连接 `Camera`（相机）进行现场联机拍摄或选片交付时，同步（`Sync`）到手机上的 RAW 与 JPEG 照片目前仅能按固定默认曲线浏览和原样导出（`Export`）：
1. **缺少高位深非破坏性精修能力**：当现场光线复杂导致欠曝/过曝、白平衡偏色、镜头色散、透视变形或需要曲线、HSL、色调分离、`.cube` 胶片 LUT、去雾及人像美肤/重塑时，用户不得不先把每张照片导出到系统相册，再切到第三方修图软件逐张处理，且第三方软件往往只吃 8-bit JPEG，白白丢掉了相机 RAW 的 16-bit 动态余量与拍摄 EXIF。
2. **缺少面向联机拍摄（Tethering）的批量暗房闭环**：在边拍边传场景下，摄影师调好第一张试光片后，无法将该调色参数（`Preset`）自动套用到后续新同步的照片上（`LiveTetherPreset`），也无法在连拍照片之间快速复制/粘贴调色参数或批量排队烘焙导出（`Bake`），导致现场看片与快修交付效率低下。

## Solution

构建一个专为相机伴侣场景打造的 **GLES 3.0 16-bit 浮点非破坏性图片编辑器与联机暗房（Tethered Darkroom）**：
1. **30 项编辑能力（34 个原子参数）全端侧 GPU 管线**：RAW 照片以 16-bit 线性光 `RGBA16F` 输入（默认叠加对齐浏览页观感的 `BaseLook`，亦可一键切为纯线性起点），按「几何与光学重采样（横向色散 pre-crop、旋转、翻转、裁切、拉直、透视、液化、人像 MLS 重塑、美体）→ 线性影调与 D65 锚定 Bradford 白平衡（曝光、亮度、高光、阴影、白/黑点、光感）→ 显示域色彩（正负分段对比度、单调三次曲线、软色域压缩、OKLCH 饱和度/自然饱和度/带低色度门控的 8 色带 HSL、笛卡尔 OKLab 色调分离、去紫边、褪色）→ 空间细节（在线/离线双模式人像美肤频率分离、引导滤波降噪、暗通道去雾、清晰度、纹理、联动皮肤抑制的 Y 通道锐化）→ 效果与输出（`.cube` 3D LUT 四面体插值、全局坐标颗粒、Post-crop 晕影、sRGB 三角抖动量化）」顺序渲染。
2. **非破坏性 `EditGraph` 与联机暗房闭环**：所有编辑仅以 `EditGraph` JSON 保存，永不改写原始照片文件。编辑器内点「完成」秒存参数与屏幕级预览缓存退回主界面；主界面直接展示编辑后效果与「已编辑」标签、支持按住大图对比原图、支持底部快捷预设栏一键换 `Preset`、支持开启 `LiveTetherPreset` 让后续新 `Sync` 照片自动套用预设、支持带 `CopyMask` 的跨图复制/粘贴参数及后台批量队列 `Bake`。
3. **高像素列带流式 `Bake` 与无损 EXIF 写回**：通过 1024px 全图 Pre-pass 提取全局耦合量，配合三档源图驻留策略与 `1920×512 (halo=64)` 列带 `glReadPixels(GL_PACK_ROW_LENGTH)` 散射至 `libjpeg-turbo` 流式编码，并在落盘后通过可寻址描述符写回白名单拍摄 EXIF（强制置 `Orientation=1`），在 ≤512MB 内存预算内支持高达 61MP–100MP 照片的零拼缝导出。

## User Stories

1. As a photographer, I want to tap an Edit button on the main darkroom toolbar, so that I can open the current photo in the full-screen editor without leaving the app.
2. As a photographer editing a RAW photo, I want the initial 0-parameter view in the editor to match the main viewer's default tone curve and auto-brightening (`camera_default` `BaseLook`), so that the image does not suddenly jump darker or flatter when I enter the editor.
3. As a colorist editing a RAW photo, I want a top-bar toggle to switch `BaseLook` between `camera_default` and `linear_neutral`, so that I can start grading from a flat linear response whenever I need full manual control.
4. As a photographer, I want to adjust Exposure in EV (`-5.0..+5.0`) in linear light, so that overall image brightness scales physically like an in-camera exposure shift.
5. As a photographer, I want to adjust Brightness (`-100..+100`) weighted around middle gray, so that I can lift or darken midtones without blowing out white points or crushing black points.
6. As a photographer, I want to recover blown Highlights (`-100..+100`) and lift dark Shadows (`-100..+100`) in linear light before `[0, 1]` clipping, so that I can rescue 16-bit RAW dynamic range headroom.
7. As a photographer, I want to adjust Whites (`-100..+100`) with a soft shoulder roll-off in linear light, so that negative values recover overexposed highlight detail without turning normal white pixels into muddy gray.
8. As a photographer, I want to adjust Blacks (`-100..+100`) as a toe stretch anchored at zero, so that I can shape deep shadow contrast distinctly from film fade.
9. As a photographer, I want to adjust Contrast (`-100..+100`) with a piecewise smooth S-curve for positive values and midrange compression for negative values, so that lowering contrast never distorts endpoint slopes.
10. As a photographer, I want an Ambiance-style Glow slider (`-100..+100`) that combines midtone power lift, zero-anchored shadow lift, local clarity, and subtle vibrance compensation, so that one slider makes photos look luminous and transparent without washing out colors.
11. As a photographer, I want to adjust Color Temperature and Tint (`-100..+100`) using Bradford chromatic adaptation anchored to the sRGB D65 white point at `(0, 0)`, so that neutral sliders are strictly identity and warming/cooling feels natural.
12. As a photographer, I want to adjust Saturation and Vibrance (`-100..+100`) in perceptual OKLCH space with high-chroma clamping, deep-shadow noise attenuation, and skin-tone protection, so that boosting vibrance enriches muted colors without over-saturating existing vivid colors, skin, or shadow chroma noise.
13. As a photographer, I want to edit 4-channel (RGB composite, R, G, B) tone curves by adding, dragging, or dragging-out-to-delete control points interpolated with Fritsch–Carlson monotone cubic Hermite splines, so that custom curves never produce ringing halos in smooth sky gradients.
14. As a photographer, I want an 8-band HSL mixer (Red, Orange, Yellow, Green, Aqua, Blue, Purple, Magenta) operating in OKLCH with a low-chroma gate (`smoothstep(0.01, 0.05, C)`), so that shifting blue luminance or hue never creates blotchy artifacts on neutral gray walls or shadows.
15. As a photographer, I want Split Toning (Highlight Hue/Sat, Shadow Hue/Sat, Balance) computed as Cartesian vector additions in OKLab `(a, b)`, so that even monochrome or neutral gray (`C = 0`) pixels receive the exact target tint hue.
16. As a photographer, I want a Fade slider (`0..100`) that lifts the pure black floor and slightly desaturates chroma, so that I can create a matte film print look.
17. As a photographer, I want to import community `.cube` 3D LUT files via the system file picker and blend their strength (`0..100`) using tetrahedral interpolation, so that I can apply external cinema and film looks accurately.
18. As a photographer, I want to sharpen images on the luminance channel with amount (`0..150`), full-resolution radius (`0.5..3.0px`), threshold (`0..100`), and edge masking (`0..100`), so that edges become crisp without introducing chromatic fringes or amplifying flat-area noise.
19. As a photographer, I want Clarity (`-100..+100`) and Texture (`-100..+100`) controls driven by a multi-scale low-frequency guide pyramid (`2%` and `0.3%` of the short edge), so that mid-frequency structure pops without halo artifacts.
20. As a photographer, I want Luminance and Chroma Noise Reduction (`0..100`) with a Detail preservation slider (`0..100`), so that high-ISO grain and color mottling are smoothed while edges remain sharp.
21. As a photographer, I want a Dehaze slider (`-100..+100`) using dark-channel prior transmission guided by a full-image atmospheric light $A$, so that hazy landscapes regain depth without tile seam mismatches.
22. As a photographer, I want a Film Grain effect (Amount, Size, Roughness) evaluated in post-crop global coordinates with a deterministic persisted seed, so that preview and tiled full-resolution export show the exact same grain pattern without tile boundaries.
23. As a photographer, I want a Post-crop Vignette control (Amount, Midpoint, Feather, Roundness), so that darkening or brightening the corners always follows the cropped frame and transitions smoothly according to the feather width.
24. As a photographer, I want manual Chromatic Aberration correction (Red/Cyan and Blue/Yellow lateral scaling around the pre-crop optical center, plus Defringe purple/green edge desaturation), so that lens color fringing is removed before sharpening.
25. As a photographer, I want 90-degree rotation, horizontal/vertical flip, aspect-locked cropping, arbitrary-angle straightening, and 4-point perspective correction that automatically compute the maximum inscribed valid rectangle, so that geometric corrections never leave black triangles at the borders.
26. As a photographer, I want a Liquify brush (Push, Restore/Erase, Size, Strength) that accumulates strokes into a resolution-independent `128×128` displacement mesh, so that I can retouch contours smoothly and reopen the project instantaneously without replaying hundreds of strokes or overflowing database row limits.
27. As a portrait photographer, I want frequency-separation Skin Smoothing (Smooth, Texture, Even Tone) that automatically excludes eyes, eyebrows, lips, and hair, suppresses sharpening over skin areas, and works offline using built-in lightweight models while seamlessly upgrading when the 6-class segmentation model finishes downloading.
28. As a portrait photographer, I want 6-axis Face Reshaping (Slim Face, Enlarge Eyes, Slim Nose, Chin, Forehead, Smile) driven by 478 3D landmarks in a rotation-invariant face coordinate frame across up to 5 faces in a group photo, so that tilted faces and multi-person portraits deform naturally without warping neighboring faces or background lines.
29. As a portrait photographer, I want Body Reshaping (Slim Waist and Lengthen Legs) initialized from face/body mask centroids with two draggable on-canvas guide lines and fixed-aspect inscribed cropping, so that I can refine waist and leg proportions in standing or seated poses without changing image dimensions or bending background walls.
30. As a photographer, I want two-finger pinch/pan to always zoom and pan the canvas in every tool mode (immediately canceling any unmoved single-finger liquify touch `< 8dp`), so that I never accidentally smear the image while zooming in.
31. As a photographer dragging a slider, I want real-time preview at 1024px proxy resolution (`≤ 8ms`) with continuous movements within `300ms` coalesced into a single Undo step (up to 50 steps), followed by full-resolution detail rendering `150ms` after release, so that sliders feel instantaneous and Undo steps are meaningful.
32. As a photographer, I want to tap "Done" in the editor to save my `EditGraph` and a screen-sized preview cache in milliseconds without waiting for a full-resolution `Bake`, so that I can return to the main gallery immediately and see my edited look and an "Edited" badge in the pager and filmstrip.
33. As a photographer browsing the main gallery or working in the editor, I want to press-and-hold the photo to temporarily view the unedited original (`Before`) and release to return to the edited look (`After`), so that I can judge my adjustments at any time.
34. As a tethered photographer, I want to save my current adjustments as a reusable `Preset` and toggle it as the active `LiveTetherPreset`, so that every new photo arriving from the `Camera` via USB `Sync` automatically receives that look and renders its preview before `AutoFollow` displays it.
35. As a photographer editing a photo while the `Camera` is still tethered, I want newly synced photos to be ingested and preset-bound silently in the background with only a subtle `+N new photos` pill in the editor top bar, so that incoming shots never steal focus or interrupt my active brush or slider gesture.
36. As a photographer reviewing a burst of shots, I want to Copy Edits from one photo (automatically excluding single-photo geometry, liquify, and face/body warps via `CopyMask`, with an option to include them) and Paste Edits onto one or multiple other photos from the main gallery, so that I can grade a series consistently in seconds.
37. As a photographer, I want to tap Export on an edited photo (or select multiple edited photos for a background batch `Bake` queue) to produce a full-resolution JPEG in the system gallery with original shooting EXIF preserved and `Orientation = 1`, while long-pressing Export lets me export the unedited original if needed.
38. As a photographer using a 50MP–100MP camera or a mid-range phone without floating-point FBO extensions, I want the exporter and renderer to automatically select the safe source residency tier and dithered FBO fallback, so that exports never crash with OutOfMemoryError or black screens.
39. As a user deleting a photo in the app, I want its associated `EditGraph`, preview caches, and liquify mesh files to be automatically cleaned up, so that no orphaned data accumulates on disk.
40. As a user whose system cache was cleared by Android, I want photos with missing source files to retain their `EditGraph` record while gracefully displaying a "Source file missing — reconnect camera to re-sync" state instead of crashing.

## Implementation Decisions

### 1. Module Boundaries and Host Contracts
- Introduce a standalone `:editor` Android library module that depends on `:libraw` (`RawWrapper`), MediaPipe Tasks Vision (`1.2.3`), and AndroidX ExifInterface (`1.4.2`). `:editor` must **never** depend on `:app`.
- `:editor` exposes three host contracts implemented and injected by `:app`:
  - `EditorLaunchArgs(photoId, sourcePath, displayName, isRaw)` for self-contained activity launch without querying `:app` DAOs.
  - `EditStore` for reading/writing `EditGraph` records, preview cache paths, `UserPreset` entries, and the active `LiveTetherPreset` ID.
  - `GallerySink` for publishing exported images to `MediaStore` (`IS_PENDING` on API 29+, atomic temp-file rename on API 24–28) and providing a seekable `"rw"` `ParcelFileDescriptor` or `File` for post-encode EXIF writeback.
- Keep `domain/` inside `:editor` pure Kotlin (zero `android.*` imports) so all color science, spline math, validation, and MLS solvers run in JVM unit tests.

### 2. UI Layout, Gesture Routing, and Throttling State Machine
- **A+B+C Hybrid Layout**:
  - `EditorActivity` uses a 3-tier bottom drawer (Tier 1: active control `ViewFlipper` switching between zero-snap `DialSeekBar`, 4-channel `CurveEditorView`, 8-band `HslPanelView`, and `LiquifyBrushPanel`; Tier 2: horizontal sub-tool chips with a blue dot `•` on modified tools and double-tap to reset; Tier 3: 6 fixed category tabs `Preset · Geometry · Tone · Color · Detail · Portrait`). In landscape, the bottom drawer moves to a `320dp` right-hand side panel.
  - `MainActivity` adds a top-bar `editButton`, a `photoEditedBadge` inside the metadata pill, press-and-hold on the main image for Before/After comparison, and a collapsible `QuickPresetStrip` above the bottom filmstrip housing preset chips, the `LiveTetherPreset` toggle, and Copy/Paste Edits actions.
- **State Machine & Gesture Router** (distilled from the validated prototype):

```javascript
// Derived from prototype-editor-ui-and-state.html
function resolveGestureRoute(group, tool, pointerCount) {
  if (pointerCount >= 2) {
    return "CANVAS_TWO_FINGER_PAN_ZOOM"; // Always preempts single-finger tools; cancels liquify stroke if moved < 8dp
  }
  if (group === "GEOMETRY" && (tool === "CROP_STRAIGHTEN" || tool === "PERSPECTIVE")) {
    return "CROP_PERSPECTIVE_HANDLE";    // Drag corners/edges/dial with auto-inscribed valid rectangle
  }
  if (group === "GEOMETRY" && tool === "LIQUIFY") {
    return "LIQUIFY_BRUSH_STROKE";       // Accumulate displacement vectors into 128x128 half-float grid
  }
  if (group === "COLOR" && tool === "CURVE") {
    return "CURVE_HERMITE_KNOT";         // Drag/add/remove monotone cubic knots inside curve grid
  }
  return "CANVAS_SINGLE_PAN_HOLD_COMPARE";
}
```

- **Render Throttling & Undo Coalescing**:
  - Active slider drag runs at `1024px` proxy resolution (`PROXY_1024_FAST`, point-operation passes only when spatial params are unchanged, target `≤ 8ms`).
  - Slider ticks within a `300ms` rolling window coalesce into a single immutable `EditGraph` snapshot on the 50-step Undo stack.
  - `150ms` after finger release (`IDLE_FULL_16F`), the GL thread executes the full preview resolution `RGBA16F` pipeline including spatial detail passes (`≤ 33ms`).

### 3. Non-Destructive `EditGraph` Schema v1, O(1) Liquify Mesh, and Room v4
- **Canonical `EditGraph` JSON Schema v1** (from prototype and schema decisions):

```json
{
  "schema": 1,
  "baseLook": "camera_default",
  "geometry": {
    "rotate90": 0, "flipH": false, "flipV": false, "straighten": 0.0,
    "crop": [0.0, 0.0, 1.0, 1.0],
    "perspective": [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0],
    "liquify": { "meshRelPath": "edits/42/liquify_128x128.bin", "gridSize": 128, "sha256": "..." }
  },
  "tone": {
    "exposure": 0.0, "brightness": 0, "contrast": 0, "glow": 0,
    "highlights": 0, "shadows": 0, "whites": 0, "blacks": 0
  },
  "color": {
    "temp": 0, "tint": 0, "saturation": 0, "vibrance": 0,
    "hsl": { "red": [0,0,0], "orange": [0,0,0], "yellow": [0,0,0], "green": [0,0,0],
             "aqua": [0,0,0], "blue": [0,0,0], "purple": [0,0,0], "magenta": [0,0,0] },
    "curve": { "rgb": [[0,0],[1,1]], "r": [[0,0],[1,1]], "g": [[0,0],[1,1]], "b": [[0,0],[1,1]] },
    "split": { "hiHue": 45, "hiSat": 0, "shHue": 210, "shSat": 0, "balance": 0 },
    "fade": 0,
    "lut": { "id": "kodak_2383", "strength": 60 }
  },
  "detail": {
    "sharpen": { "amount": 0, "radius": 1.0, "threshold": 0, "masking": 0 },
    "clarity": 0, "texture": 0, "dehaze": 0,
    "nr": { "luma": 0, "chroma": 0, "detail": 50 }
  },
  "effects": {
    "grain": { "amount": 0, "size": 25, "roughness": 50, "seed": 104729 },
    "vignette": { "amount": 0, "midpoint": 50, "feather": 50, "roundness": 0 },
    "ca": { "redCyan": 0, "blueYellow": 0, "defringe": 0 }
  },
  "portrait": {
    "skin": { "smooth": 0, "texture": 50, "evenTone": 0 },
    "reshape": { "face": 0, "eyes": 0, "nose": 0, "chin": 0, "forehead": 0, "smile": 0 },
    "body": { "waist": 0, "legs": 0, "waistY": null, "hipY": null }
  }
}
```

- **Liquify Storage**: Never store raw stroke point lists in JSON. Persist a compressed `128×128` 2D half-float displacement grid (`64 KiB` raw, `< 12 KiB` deflated) in private files storage and reference its relative path and SHA-256 in `EditGraph`.
- **`.cube` 3D LUT Pre-compilation**: On SAF import, parse and resample `.cube` files once into a `33×33×33` `RGBA16F` binary cache (`287,496 B` + 8-byte header) so runtime rendering maps it directly into `sampler3D` without text parsing.
- **Database Migration (`v3 → v4`)**: Add `photo_edits` (with `FOREIGN KEY(photo_id) REFERENCES photos(id) ON DELETE CASCADE` and unique index on `photo_id`) and `user_presets`. Deleting a photo cascades to `photo_edits` and deletes its preview/liquify directory.
- **Copy/Paste Mask (`CopyMask`)**: By default copies `{baseLook, tone, color, detail, effects, portrait.skin}` and excludes `{geometry.*, portrait.reshape, portrait.body}`.

### 4. Native 16-bit Linear RAW Decoding & Orientation Normalization
- Extend `:libraw` JNI with a 16-bit linear decoding entry point that sets `output_bps = 16`, `output_color = 1` (sRGB primaries), `gamm[0] = 1.0, gamm[1] = 1.0`, `use_camera_wb = 1`, `no_auto_bright = 1`, computes `autoBrightGain` metadata (matching the viewer's `+2.0 EV` capped auto-brightening), and converts samples in an OpenMP loop into an IEEE 754 `half_float` `RGBA16F` `DirectByteBuffer` (avoiding JVM heap `ByteArray` allocations and bypassing 8-bit `raw_postprocess`).
- **Orientation Normalization (No Double Rotation)**: LibRaw already rotates output when `user_flip = -1`; JPEG decoding uses `BitmapFactory` (which does not auto-rotate) paired with a single `ExifInterface` orientation transform on texture upload. All working textures enter Pass A with `Orientation = 1`.

### 5. Physical 4-Group GLES 3.0 Pipeline, Color Science, and 1024px Pre-pass
- Logical 6 stages are fused into **4 physical FBO Pass Groups** to halve memory bandwidth:
  1. **Pass A (`GeometryCaPass`)**: Maps post-crop global UV through the inverse crop/straighten/perspective homography $H^{-1}$ to pre-crop UV, adds `texture(uWarpTex, uvPreCrop).xy` from the unified `128×128 GL_RG16F` warp texture (combining face MLS + body warp + liquify), and performs radial R/B scaling around the pre-crop optical center `(0.5, 0.5)` for Lateral CA.
  2. **Pass B (`ToneColorPass`, fusing stages ② and ③)**:
     - Applies `camera_default` `BaseLook` compensation when enabled on RAW inputs.
     - Applies the CPU-precomputed **D65-anchored relative Bradford chromatic adaptation matrix** (`uv_target = uv_D65 + (uv_t - uv_0)`, strictly identity $I$ at `temp=0, tint=0`, warming for `temp > 0`).
     - Applies Exposure, middle-gray Brightness, and linear Highlights/Shadows/Whites prior to `[0, 1]` clipping, followed by `softShoulder(x)` roll-off.
     - Encodes to sRGB, applies zero-anchored Blacks toe stretch, piecewise Contrast (`uContrast >= 0` vs `< 0`), Ambiance Glow stages ①–② ($y^{1/(1+0.20g)} + 0.10g\cdot y(1-y)^2$), and the 1024-entry Fritsch–Carlson monotone cubic 1D LUT (RGB composite then per-channel).
     - Scales linear RGB by the new luminance ratio with **soft gamut compression** (preventing single-channel `> 1.0` clipping hue shifts), converts to OKLCH for Saturation, Vibrance (`clamp(1 - C/0.32, 0, 1)` × shadow attenuation × skin protection), and 8-band HSL (**multiplying $\Delta h$ and $\Delta L$ by the low-chroma gate `smoothstep(0.01, 0.05, C)`**).
     - Converts to Cartesian OKLab `(L, a, b)` for Split Toning vector addition, applies high-gradient purple/green Defringe, and applies Fade.
  3. **Pass C (`SpatialDetailChain`, short-circuited with 0 Draw Calls when all spatial parameters and `glow` are 0)**:
     - Runs a **1024px full-image Pre-pass** once to compute the global Dehaze atmospheric light $A$ (brightest `0.1%` of dark channel), coarse transmission $t_{\text{low}}$, and the `2%` / `0.3%` short-edge low-frequency guide pyramid (`uPrepassTex`).
     - Executes frequency-separation Skin Smoothing first, then guided-filter Luminance NR + $5\times 5$ edge-guided Chroma NR, Dehaze upsampling, Clarity (`clarity + 0.20*glow`) & Texture high-pass (`clamp(Y - base, ±0.08)`), and Y-channel edge-gated Sharpening multiplied by `(1 - skinMask)`.
  4. **Pass D (`EffectsOutputPass`, fusing stages ⑤ and ⑥)**:
     - Samples `.cube` 3D LUT via tetrahedral interpolation with base grid index clamped to `ivec3(int(size) - 2)` to prevent `texelFetch` out-of-bounds at `c = 1.0`.
     - Evaluates Film Grain in post-crop global coordinates with midtone envelope $\sqrt{4Y(1-Y)}$ and deterministic `seed`.
     - Applies Post-crop Vignette over `[mid - 0.5*feather, mid + 0.5*feather]` with aspect roundness.
     - Outputs sRGB with `1/255` triangular dithering into `GL_RGBA8`.

### 6. Portrait Vision Pipeline and Hybrid Model Packaging
- **Hybrid Model Distribution**: Bundle `face_landmarker.task` (`3.33 MB` deflated) and `selfie_segmenter.tflite` (`250 KB` FP16) inside APK `assets/`. Download `selfie_multiclass_256x256.tflite` (`16.37 MB` FP32) on demand over HTTPS with Range resume and SHA-256 verification on first entry to the Portrait panel. Do not bundle `deeplab_v3` or `pose_landmarker_full`.
- **Build Guardrails**: Exclude `x86` and `x86_64` JNI libraries (`-26.36 MB` on AAB), set `ndk.debugSymbolLevel = "none"` on release builds (`-6.21 MB` on AAB), and add `-keep class com.google.mediapipe.** { *; }` to ProGuard.
- **Offline-First Dual-Mode Skin Mask**: Before the 16.37 MB multiclass model is downloaded, synthesize `skinMask` offline from the built-in 478-point face hull (minus eye/eyebrow/lip polygons) + 250 KB person mask + OKLCH skin-color gate; switch seamlessly to the 6-class mask (`body-skin ∪ face-skin - hair - eyeLipBrow`) once downloaded.
- **Multi-Face MLS & Body Guide Lines**: Configure `FaceLandmarker` with `numFaces = 5`, solve Schaefer 2006 MLS similarity warp on a `64×64` grid using a rotation-invariant per-face coordinate system and $1.8\times$ halo anchor pins. Initialize waist and hip lines from face box + body mask centroids and expose two draggable horizontal guide lines on canvas; lengthen legs with fixed-aspect bottom inscribed cropping.

### 7. 3-Tier Source Residency, Column-Band Streaming JPEG Bake, and EXIF Writeback
- **CMake `libjpeg-turbo 3.2.2`**: Build static `libjpeg.a` via `ExternalProject_Add` with ARM NEON SIMD enabled, link with `-Wl,--exclude-libs,ALL`, force `optimize_coding = FALSE` and baseline single-scan (`JCS_EXT_RGBA`, quality `95`) so encoder memory stays $O(1)$ (`< 300 KiB`).
- **3-Tier Source Residency (capping total memory ≤ 512MB and bypassing `GL_MAX_TEXTURE_SIZE`)**:
  - **Tier 1 ($\le 26\text{MP}$ and long edge $\le \min(T_{\max}, 6144)$)**: Full-frame `RGBA16F` source texture uploaded once to GPU (`~215 MiB` total).
  - **Tier 2 ($26\text{MP}..65\text{MP}$ or long edge $> \min(T_{\max}, 6144)$, RAM $\ge 6\text{GB}$)**: Full image held in Native `RGB16` buffer; before rendering each column band, compute its inverse-mapped source bounding box $[x_0..x_1, y_0..y_1]$ and upload only that sub-slice ($\le 2560^2$) to GPU (`~340 MiB` total, preserving full 50MP/61MP native resolution).
  - **Tier 3 ($> 65\text{MP}$ or RAM $< 6\text{GB}$)**: Decode RAW with LibRaw `half_size = 1` (100MP → 25MP true RGB).
- **Column-Band Scatter (`1920×512`, `halo = 64px`)**: Render each band in a $(W_{\text{valid}}+128)\times(H_{\text{valid}}+128) \le 2048\times 640$ FBO, strip the 64px halo and scatter into a full-width 512-row `RGBA8` buffer via `glReadPixels(64, 64, W_valid, H_valid)` with `GL_PACK_ROW_LENGTH = W_crop` and `GL_PACK_SKIP_PIXELS = x0`, then feed `H_valid` rows directly to `jpeg_write_scanlines`.
- **EXIF Writeback**: On API 29+, close the `"w"` encoding descriptor, reopen the MediaStore URI with `"rw"` mode (seekable), copy whitelisted EXIF tags (excluding `TAG_MAKER_NOTE`; mapping RAW fields from LibRaw and decoding `timestamp` with `localtime()`), force `TAG_ORIENTATION = "1"` and cropped `TAG_IMAGE_WIDTH/LENGTH`, call `saveAttributes()`, and flip `IS_PENDING = 0`.

### 8. 4-Tier GPU Fallback Matrix
- **Tier 1**: GLES 3.0 + complete `GL_RGBA16F` FBOs (`GL_EXT_color_buffer_half_float`).
- **Tier 2**: GLES 3.0 without `half_float` FBO support → `RGBA16F` source/LUT textures with `GL_RGBA8` intermediate FBOs + per-pass triangular dithering.
- **Tier 3**: Shader compile/link failure → log `GL_RENDERER`/`GL_VERSION`/`infoLog` via `Timber.e` and fall back to a minimal Geometry + Basic Tone shader.
- **Tier 4**: MediaPipe Delegate defaults to `CPU`; tries `GPU` only when `reqGlEsVersion >= 0x30001`, catches `MediaPipeException`, persists device blacklist flag, and permanently falls back to `CPU`.

## Testing Decisions

### What Makes a Good Test
- Tests must assert observable mathematical, pixel, database, or state-machine outcomes at module boundaries—never internal private helpers, shader source strings, or mock interaction counts.
- Determinism is mandatory: fixed random seeds for grain, exact pixel delta tolerances ($\le 1/255$ for 8-bit outputs), and real SQLite/GLES execution for integration seams.

### High-Level Testing Seams and Modules Under Test
1. **Pure Domain Compiler Seam (JVM Unit Tests in `:editor`)**:
   - Exercises `EditValidator`, `EditGraph.isIdentity()`, `CopyMask`, `BradfordWbSolver` (asserting $< 10^{-6}$ deviation from identity $I$ at `temp=0, tint=0`), `MonotoneCubicCurve` (asserting zero overshoot across 500 random knot sets), `CubeLutParser` (asserting rejection of malformed `.cube` files and exact binary `.lut16f` layout), and `FaceMlsSolver` (asserting zero displacement at image borders and $1.8\times$ face halo pins).
   - *Prior Art*: Pure JVM policy and protocol tests in `:app` (`UsbPtpConnectionPolicyTest`, `CameraDeletionPolicyTest`, `PtpContainerTest`).
2. **Headless Bake Pipeline Seam (Android Instrumented Tests in `:editor` / `:app`)**:
   - Exercises `BakeExporter.bake(sourcePhoto, editGraph, gallerySink)` end-to-end on real GLES 3.0 pbuffer + `:libraw` + `libjpeg-turbo` + `ExifInterface`.
   - Asserts: (a) 0-parameter identity output differs from input by $\le 1/255$ per channel; (b) full-frame export vs `1920×512 (halo=64)` column-band tiled export with Clarity, Dehaze, Grain, Vignette, and Lateral CA enabled differs by $\le 1/255$ with zero visible seams; (c) all 8 EXIF `Orientation` inputs export with `TAG_ORIENTATION == "1"`, upright visual orientation, and intact shooting metadata (`DateTimeOriginal`, `Make`, `Model`, `FNumber`, `ISOSpeedRatings`).
3. **Tethered Darkroom Storage & Workflow Seam (JVM + Instrumented Migration Tests)**:
   - Exercises Room `MIGRATION_3_4` (`ON DELETE CASCADE` cleanup of `photo_edits` when a `PhotoEntity` is deleted), `LiveTetherPreset` auto-binding on new photo `Sync`, `300ms` Undo transaction coalescing, and two-finger gesture preemption of single-finger liquify strokes.
   - *Prior Art*: UI state and dialog decision tests (`MonitorUiStateTest`, `ConnectButtonUiTest`, `DeletePhotoDialogUiTest`).

## Out of Scope

- Cloud or on-device Vision-Language Model (VLM) automatic parameter generation (covered separately in an independent study).
- Modifying or overwriting original RAW/JPEG files on disk or on the `Camera` storage card.
- Video editing, multi-layer compositing, text/sticker overlays, or healing/clone-stamp patch inpainting (`seamlessClone` blemish removal is deferred to a future standalone tool).
- Relying on Google Play Asset Delivery (incompatible with direct APK distribution on GitHub Releases).

## Further Notes

- All user-facing slider names, category tabs, badges, and error messages must be externalized in Chinese and English string resources.
- All logging must use `Timber` without logging full user file paths or sensitive biometric coordinates.
