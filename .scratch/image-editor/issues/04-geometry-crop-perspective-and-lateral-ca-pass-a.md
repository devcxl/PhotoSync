# 04: 几何重采样与光学横向色散校正切片（Pass A）

**What to build:** 交付「几何」面板的旋转 90°、水平/垂直翻转、带比例锁的自由裁切、任意角度拉直（`-45°..+45°`）、四点透视校正，以及「细节/光学」中的横向色散（Lateral CA R/C 与 B/Y）校正。所有几何与光学重采样合并在物理 Pass A 中通过单次逆映射采样完成：先确保源图按 EXIF/LibRaw 一次性归一化至正向（严防二次旋转），在拉直与透视变形时自动求解最大内接无黑边矩形，并在裁切前（pre-crop）绕原图光心 `(0.5, 0.5)` 对 R/B 通道做径向缩放重采样以消除镜头横向色差。

**Blocked by:** 02: 编辑器模块骨架、非破坏性 EditGraph 持久化与首条曝光垂直切片

**Status:** resolved

- [x] 源图方向归一化：LibRaw 保持 `user_flip = -1` 不再二次旋转；JPEG 通过 `BitmapFactory` + `ExifInterface` 一次性转正为 `Orientation = 1`（验证全部 8 种 EXIF Orientation 输入均正向显示）。
- [x] 实现 `CropOverlayView` 与手势路由：切入裁切/拉直/透视工具时，单指拖拽四角/四边/刻度盘实时更新逆单应矩阵 $H^{-1}$，双指捏合仍可缩放平移画布底图。
- [x] 任意角度拉直与四点透视校正自动计算并约束在最大内接有效像素矩形内，预览与导出画面四角永不出现黑边或透明空洞。
- [x] 在 Pass A Shader 中基于 pre-crop 原始光心坐标实现 R/B 通道 `(1 + k * r²)` 横向色散径向重采样（先于裁切与后续锐化）。
- [x] 包含 8 种 EXIF 方向归一化、内接矩形几何约束与逆单应矩阵恒等性的自动化测试。

## Resolution notes

**Single orientation table.** `GeometryMath.discreteOpsForExifOrientation` is the one mapping from
EXIF `Orientation` to `(quarterTurns, flipH, flipV)`; `EditorBitmapLoader.applyExifOrientation`
now builds its `Bitmap` matrix from it, and the JVM test asserts all eight values against the
EXIF 2.32 corner table plus the discrete-slider parity. RAW keeps `user_flip = -1`, so no second
rotation exists anywhere.

**Exact inscribed straighten rectangle.** `GeometryMath.solveMaxInscribedRect` uses the closed form
`halfWidth <= min((aspect/2)/(|cos| + |sin|/aspect), (1/2)/(|sin| + |cos|/aspect))`, derived from the
rotated rectangle's own axis-aligned bounding box. Tests sweep `-45..45°` in `0.25°` steps across
seven aspect ratios and assert zero corner leak, plus 1% inflation always leaks — i.e. the bound is
tight, not merely safe.

**Perspective quad is a sampling bound, not a post-hoc crop.** The four pins define a quad inside
`T`-UV; `PassAGeometryCompiler` clamps every pin into `[0, 1]²`, rejects concave/degenerate drags,
and maps the output unit square onto that quad. Because a homography maps a convex set onto a convex
set, the whole rendered frame is contained in the quad, hence in the source image. A 2304-case sweep
(`4 aspects × 4 quarter turns × 4 mirror combos × 6 angles × 4 quads × 3 crops`) asserts every
output corner stays inside `[0, 1]²`.

**Preview shows the frame its handles live in.** `EditorPreviewGeometry` reveals the uncropped frame
for Crop/Straighten and the pre-quad-warp frame for Perspective, so the drawn handles and the drawn
pixels always agree; leaving the tool renders the real graph.

**Physical Pass A.** `GeometryCaGlProgram` evaluates the pre-compiled `H^-1` once per pixel and does
the `(1 + k·r²)` R/B radial scaling around pre-crop `(0.5, 0.5)` with green as reference; both CA
coefficients at zero short-circuit to a single fetch. `PassAFrameBuffer` selects Tier 1 `GL_RGBA16F`
when `EXT_color_buffer_half_float`/`EXT_color_buffer_float` is present and Tier 2 `GL_RGBA8`
otherwise, and is skipped entirely when geometry and CA are neutral so the 0-parameter view stays
bit-identical to the source texture.

**"Done" matches the GL output.** `PreviewCacheWriter` now runs `GeometryCpuResampler`, which reuses
the same `H^-1` and the same `GeometryMath.applyLateralCaSampleUv` reference math as the shader,
then applies the exposure LUT, so the cached thumbnail and the editor frame agree.

**Deferred.** The pass-A path requires GLES 3.0 (framebuffer objects); on a driver where the ES 3.0
shader fails to compile the editor logs once and falls back to the un-warped preview — the CPU cache
path is still correct, so only the live preview loses the warp. English (`values-en`) resources and
the killing of the legacy `editor_tool_coming_soon` path belong to the later slices that fill Preset
/ Color / Portrait.
