# EditGraph JSON Schema v1 与预置滤镜格式规范

Type: grilling
Status: resolved
Blocked by: 03, 04

## Question

基于已敲定的参数语义、画幅裁切策略与 UI 交互状态机，制定非破坏性编辑图 `EditGraph` 的完整 JSON Schema v1 与持久化契约：
1. 34 个原子参数在 JSON 中的精确字段名、类型、取值范围、默认值、归一化映射函数（UI 显示值 ↔ Shader Uniform 值）及非法值（NaN/Inf/越界）的边界 Clamp 规则。
2. 特殊复杂结构的数据表示：
   - 曲线（`curve`）4 通道控制点数组约束（最少/最多点数、端点单调性校验）；
   - 液化（`liquify`）笔刷轨迹序列 vs 半分辨率位移网格的序列化策略（如何保证 `graph_json` 不会膨胀到数 MB 撑爆 Room CursorWindow 2MB 限制）；
   - 颗粒随机种子（`seed`）的生成与持久化时机。
3. 内置滤镜（参数预设 Preset）与外部 `.cube` 3D LUT 资源在 `EditGraph` 中的引用标识、强度混合与向后兼容升级（`schemaVersion` v1→v2 迁移函数）契约。
4. **联机暗房（Tethered Darkroom）扩展数据模型**：
   - `baseLook`（`"camera_default" | "linear_neutral"`）字段定义；
   - 用户预设表（`user_presets`）或 JSON 存储格式、「联机自动预设（`LiveTetherPreset`）」激活状态持久化，以及跨照片「复制/粘贴编辑参数」的子集掩码（`CopyMask`：默认排除单图几何/液化/人像关键点位移）契约。

## Answer

### 1. `EditGraph` JSON Schema v1 规范结构与 34 参数归一化契约

```json
{
  "schema": 1,
  "baseLook": "camera_default",
  "geometry": {
    "rotate90": 0,
    "flipH": false,
    "flipV": false,
    "straighten": 0.0,
    "crop": [0.0, 0.0, 1.0, 1.0],
    "perspective": [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0],
    "liquify": null
  },
  "tone": {
    "exposure": 0.0,
    "brightness": 0,
    "contrast": 0,
    "glow": 0,
    "highlights": 0,
    "shadows": 0,
    "whites": 0,
    "blacks": 0
  },
  "color": {
    "temp": 0,
    "tint": 0,
    "saturation": 0,
    "vibrance": 0,
    "hsl": {
      "red": [0, 0, 0],
      "orange": [0, 0, 0],
      "yellow": [0, 0, 0],
      "green": [0, 0, 0],
      "aqua": [0, 0, 0],
      "blue": [0, 0, 0],
      "purple": [0, 0, 0],
      "magenta": [0, 0, 0]
    },
    "curve": {
      "rgb": [[0.0, 0.0], [1.0, 1.0]],
      "r": [[0.0, 0.0], [1.0, 1.0]],
      "g": [[0.0, 0.0], [1.0, 1.0]],
      "b": [[0.0, 0.0], [1.0, 1.0]]
    },
    "split": {
      "hiHue": 45,
      "hiSat": 0,
      "shHue": 210,
      "shSat": 0,
      "balance": 0
    },
    "fade": 0,
    "lut": null
  },
  "detail": {
    "sharpen": { "amount": 0, "radius": 1.0, "threshold": 0, "masking": 0 },
    "clarity": 0,
    "texture": 0,
    "dehaze": 0,
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
    "body": { "waist": 0, "legs": 0 }
  }
}
```

#### 边界校验（`EditValidator`）与归一化规则

1. **拒绝 `NaN` / `Infinity`**：反序列化时任何浮点字段若 `!isFinite()`，立即回退为该字段默认值，绝不将 `NaN` 传入 GLSL Uniform。
2. **UI 值 → Shader 归一化公式**：
   - `exposure`：`[-5.0, +5.0]`（步进 `0.05`），直接作为 `uExposure`（EV）；
   - 双极对称滑杆（`brightness`, `contrast`, `glow`, `highlights`, `shadows`, `whites`, `blacks`, `temp`, `tint`, `saturation`, `vibrance`, `clarity`, `texture`, `dehaze`, `split.balance`, `vignette.amount`, `vignette.roundness`, `ca.redCyan`, `ca.blueYellow`, `portrait.reshape.*`, `portrait.body.*`）：UI 整数 `[-100, +100]` → Shader `u = clamp(v / 100.0f, -1.0f, 1.0f)`；
   - 单极正向滑杆（`fade`, `split.hiSat`, `split.shSat`, `detail.sharpen.threshold/masking`, `detail.nr.*`, `effects.grain.amount/size/roughness`, `effects.vignette.midpoint/feather`, `effects.ca.defringe`, `portrait.skin.*`）：UI 整数 `[0, 100]` → Shader `u = clamp(v / 100.0f, 0.0f, 1.0f)`；
   - `detail.sharpen.amount`：UI `[0, 150]` → `u = clamp(v / 100.0f, 0.0f, 1.5f)`；`radius`：`[0.5f, 3.0f]`（全清像素单位）；
   - `hsl.<band>`：`[Δh, Δs, Δl]`，每项 UI `[-100, +100]` → `Δh_rad = (Δh / 100.0f) * (π / 6.0f)`（最大 ±30°），`ΔC = Δs / 100.0f`，`ΔL = Δl / 100.0f`。
3. **恒等判定（`EditGraph.isIdentity()`）**：当所有数值等于默认值、曲线为两点对角线、`lut == null`、`liquify == null` 且 `baseLook == "camera_default"` 时判定为恒等；恒等时退出编辑器自动删除 `photo_edits` 行与预览缓存，主界面不显示「已编辑」标签。

### 2. 复杂结构与二进制资产契约

1. **曲线（`curve`）约束**：
   - 每条通道最少 2 个控制点、最多 16 个控制点；坐标 `(x, y)` 均 clamp 到 `[0.0, 1.0]`；
   - 控制点按 `x` 严格递增排序（相邻点允许最小间距 $\Delta x \ge 1/256 \approx 0.0039$，若小于该间距则合并或拒绝插入，防止 Fritsch–Carlson 导数除以 0）。
2. **液化（`liquify`）O(1) 网格存储契约（杜绝 Room 2MB `CursorWindow` 溢出）**：
   - 不持久化无限增长的画笔轨迹点序列；
   - 液化位移场固定为分辨率无关的 **`128 × 128` 2D 向量网格**（每个格点存归一化 UV 位移 `(dx, dy)`，采用 IEEE 754 `half_float` 存储，原始大小固定 `128 × 128 × 4B = 65,536 B`）；
   - 编辑器会话内：50 步撤销栈在 Native/DirectByteBuffer 环形缓冲中保存 `128×128` 网格快照（50 步仅 `3.125 MiB`）；
   - 落盘持久化：写入 `filesDir/edits/<photoId>/liquify_128x128.bin`（Deflate 压缩后通常 `< 12 KiB`），`EditGraph` JSON 中仅记录：
     ```json
     "liquify": { "meshRelPath": "edits/42/liquify_128x128.bin", "gridSize": 128, "sha256": "..." }
     ```
   - 重新打开编辑时，无需重放数百笔历史画笔，直接读取 `.bin` 一次 `glTexImage2D(GL_RG16F)` 上传即可。
3. **预设（`Preset`）与外部 `.cube` 3D LUT 预编译格式**：
   - 内置滤镜优先采用纯参数预设（即一份只包含 `tone`/`color`/`detail`/`effects` 的 `EditGraph` JSON 片段，0 额外体积且用户可继续微调滑杆）；
   - 外部 `.cube` LUT（通过 SAF 导入，原始 ASCII 约 970 KB）：导入时由 `CubeLutParser` 校验（`LUT_3D_SIZE` $\in [2, 65]$，`DOMAIN_MIN < DOMAIN_MAX`），重采样/归一化为标准 **`33 × 33 × 33` `RGBA16F` 二进制文件**（8 字节魔数头 `"LUT16F\x01\x21"` + `33³ × 8B = 287,496 B`）存入 `filesDir/luts/<lutId>.lut16f`；
   - `EditGraph` 引用格式：`"lut": { "id": "kodak_2383", "strength": 60 }`（`strength` UI `0..100` → Shader `0.0..1.0`）。
4. **颗粒随机种子（`grain.seed`）**：
   - 首次将 `grain.amount` 从 `0` 拉大时，生成一次确定性伪随机整数 `seed ∈ [1, 999999]` 并写入 `EditGraph`，此后预览与分块导出始终复用该 `seed`，确保预览与导出颗粒图案完全一致。

### 3. Room v4 表结构、`LiveTetherPreset` 与 `CopyMask` 契约

1. **`AppDatabase` v3 → v4 迁移 SQL（`MIGRATION_3_4`）**：
   ```sql
   CREATE TABLE IF NOT EXISTS `photo_edits` (
       `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
       `photo_id` INTEGER NOT NULL,
       `schema_version` INTEGER NOT NULL,
       `graph_json` TEXT NOT NULL,
       `preview_cache_path` TEXT,
       `updated_at` INTEGER NOT NULL,
       FOREIGN KEY(`photo_id`) REFERENCES `photos`(`id`) ON DELETE CASCADE
   );
   CREATE UNIQUE INDEX IF NOT EXISTS `index_photo_edits_photo_id` ON `photo_edits` (`photo_id`);

   CREATE TABLE IF NOT EXISTS `user_presets` (
       `id` TEXT PRIMARY KEY NOT NULL,
       `name` TEXT NOT NULL,
       `graph_subset_json` TEXT NOT NULL,
       `lut_file_path` TEXT,
       `is_builtin` INTEGER NOT NULL DEFAULT 0,
       `created_at` INTEGER NOT NULL
   );
   ```
2. **联机自动预设（`LiveTetherPreset`）持久化与触发**：
   - 激活状态保存在 `SharedPreferences` 键 `pref_live_tether_preset_id: String?`；
   - 当 `CameraSyncEngine` 下载完成一张新照片并调用 `photoDao.insert(photoEntity)` 获得新 `photoId` 后，若 `pref_live_tether_preset_id != null`，立即读取对应 `user_presets.graph_subset_json` 插入 `photo_edits` 表，并触发后台轻量渲染生成 `cacheDir/edits/<photoId>/<hash>.jpg`。
3. **跨照片复制/粘贴掩码（`CopyMask`）**：
   ```kotlin
   data class CopyMask(
       val includeBaseLook: Boolean = true,
       val includeTone: Boolean = true,
       val includeColor: Boolean = true,
       val includeDetail: Boolean = true,
       val includeEffects: Boolean = true,
       val includePortraitSkin: Boolean = true,
       val includeGeometryCropRotate: Boolean = false, // 默认排除单图构图
       val includeLiquify: Boolean = false,            // 默认排除单图液化
       val includePortraitReshapeBody: Boolean = false // 默认排除单图人脸/美体位移
   )
   ```
