# 11: 液化画笔与 O(1) 128×128 位移网格持久化切片

**What to build:** 在「几何 · 液化」工具下交付交互式画布液化画笔（向前推拉 Push、恢复橡皮擦 Restore、笔刷半径 Size 与强度 Strength 调节）：单指在画布涂抹时按屏幕坐标逆映射至图像坐标并在 `128×128` `half_float` 2D 位移场网格（`uWarpTex`）上实时累积位移向量；双指按下缩放画布时若单指落笔移动 `< 8dp` 立即自动取消该笔以防双指缩放误涂抹；会话内维护 50 步 `128×128` 网格环形撤销栈（仅占 `3.125 MiB`），保存时将位移网格 Deflate 压缩写入外置二进制文件并在 `EditGraph` 中记录相对路径与 SHA-256，确保数据库 JSON 体积恒定 $O(1)$ 且重开照片无需重放历史画笔。

**Blocked by:** 04: 几何重采样与光学横向色散校正切片（Pass A）

**Status:** resolved

- [x] 实现 `LiquifyBrushOverlayView` 与笔刷面板（推拉/恢复模式切换、半径与强度滑杆、一键重置液化）。
- [x] 在 `GestureRouter` 中落实双指抢占防误涂规则：第二个手指按下（`ACTION_POINTER_DOWN`）时，若当前单指液化笔画累计移动距离 `< 8dp`，立即回滚该笔未提交增量并无缝切换为双指画布缩放/平移。
- [x] 实现 `128×128` `GL_RG16F` 位移网格累积、50 步环形缓冲 Undo/Redo，以及 `filesDir/edits/<photoId>/liquify_128x128.bin`（Deflate + SHA-256 校验）外置持久化，接入 Pass A 逆映射采样。
- [x] 包含双指抢占回滚状态机测试与 `128×128` 网格序列化/反序列化往返一致性测试。

## Resolution notes

**Inverse-mapping convention, stamped at the current touch.** `LiquifyMesh` stores, per grid cell,
the offset that must be added to the pre-crop sample coordinate (`sourceUv = p + displacement(p)`),
so a rightwards drag accumulates a *negative* horizontal displacement — that is what makes pixels
follow the finger. Each stamp is centred on the point the finger is at now and weighted by
`(1 - r²)²` inside the brush radius; `Restore` multiplies the same weight into `(1 - w)`, so repeated
stamps converge on zero without overshooting. Cells are interleaved `(dx, dy)` row-major, and
`displacementAt` samples them with the texel-centre convention of a `GL_LINEAR` /
`GL_CLAMP_TO_EDGE` fetch, so the JVM reference and the shader read the same field.

**Pass A samples the grid in pre-crop space, so the brush must travel there too.** The spec chains
`preCropUv += texture(uWarpTex, preCropUv).xy` *before* the lateral-CA lookups, which is exactly what
`GeometryCaGlProgram` now does behind a `uHasWarpTex` branch (plus `uWarpScale`/`uWarpOffset` so a
driver without `GL_RG16F` can decode the quantized `GL_RGBA8` fallback unchanged). Because the grid
lives in pre-crop UV, `LiquifyStrokeProjection` pushes every touch through the same `H⁻¹` and scales
the radius by the local `d(preCropU)/d(outputU)` derivative — a half-width crop therefore gets a
`0.5×` radius, and a stroke lands under the ring even when the photo is cropped or straightened.

**Two-finger preemption is a state machine, not a heuristic.** `LiquifyStrokeArbiter` accumulates
cumulative travel from `ACTION_DOWN` against `GestureRouter.preemptionSlopPx(density)` (8dp). On
`ACTION_POINTER_DOWN` it returns `ROLL_BACK_UNCOMMITTED_STROKE` below the slop — the session restores
the pre-stroke `LiquifyMesh` copy byte for byte, with no undo entry — and `KEEP_STROKE` at or above
it, sealing the stroke as one undo step before the pinch takes the canvas. `GestureRouter` gains
`GestureRoute.LIQUIFY_BRUSH_STROKE` so the router and the overlay agree on ownership.

**Undo is two cooperating stacks.** Strokes are byte-exact mesh snapshots in `LiquifyHistory`
(50 × `64 KiB` half-float grids = `3,276,800 B`, asserted in a test), parameters stay in the existing
`EditorUndoController`. `EditorLiquifyController` keeps `EditGraph.geometry.liquify` truthful —
present while a mesh exists, cleared the moment the last displacement is undone or reset — so the
chip badge, preview cache and (later) export all agree on whether a warp exists.

**O(1) persistence with a real integrity check.** `LiquifyMeshCodec` encodes the grid as the raw
`RG16F` byte stream (`65,536 B`, little-endian half floats), Deflates it, and hashes the *compressed*
payload; `LiquifyMeshStore` writes it through a temp file plus rename into
`filesDir/edits/<photoId>/liquify_128x128.bin` and confines reads under `filesDir`. Inflating is
capped at one grid, so a corrupt or hostile file is rejected rather than expanded, and
`EditValidator` drops any reference whose path escapes, whose grid size is not 128, or whose digest
is neither blank nor a full SHA-256. Loading a photo with a broken payload degrades to "no liquify"
with a logged warning instead of a crash.

**Preview cache reproduces the warp.** `GeometryCpuResampler.resample`/`resampleLinear` take an
optional `liquifyMesh` and apply `preCrop + displacement(preCrop)` before their CA lookups, so the
"Done" thumbnail shows the same contour the canvas showed.

**Deferred.** The warp lands in the shared `uWarpTex` slot that the portrait MLS and body warps will
also feed (ticket 12), so those passes only have to write into the same grid contract. Preview
uploads read the live mesh from the UI thread while the GL thread converts it; a stamp that is
mid-flight can produce one torn frame, which the next `requestRender` corrects — acceptable for a
preview and avoided in the eventual full-resolution `Bake`, which renders from the settled mesh.
