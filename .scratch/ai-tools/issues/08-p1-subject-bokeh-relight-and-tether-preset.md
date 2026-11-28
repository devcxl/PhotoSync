# 08: P1 进阶：主体背景虚化、前后景独立调色与 Preset/LiveTetherPreset 联机联动

**What to build:** 在已有的 `SubjectMask` 基础上兑现“一模多用”摄影价值：在「AI → 主体」面板新增 **Pass C 末端模拟背景虚化**（仅虚化背景保持主体发丝锐利）与 **Pass B 前景/背景独立相对调色**（前景与背景各自的曝光、色温、饱和度偏移滑杆）；将 `SubjectOps` 参数轴接入 `Preset`、`CopyMask` 可选勾选列表与 `LiveTetherPreset` 联机暗房流水线——用户在棚拍或现场联机时激活包含换底/虚化/前后景调色的预设，每张新同步照片在串行入库队列中自动计算自身 `SubjectMask` 并生成成片预览；当设备处于 ≤4 GB 低端档或 MODNet 模型尚未下载时，自动静默降级为仅套用传统调色参数，绝不阻塞相机 USB 同步。

**Blocked by:** 05: P1 主体蒙版（MODNet + 分块 GuidedFilter + FMGE）与去背/换底垂直切片

**Status:** resolved

- [ ] 在「AI → 主体」拖动背景虚化与前景/背景曝光、色温、饱和度滑杆，画布实时渲染且仅对对应区域生效，主体边缘与发丝保持自然过渡无光晕断层；全分辨率烘焙结果与预览一致。（实现已就位：`SubjectWeightTexture` 供 Pass B 相对调色、`SubjectBokehMath` 在 Pass C 末端以 `maxRadiusPx` 限制 band halo；`SubjectBokehAndRelightTest` / `CloudPassBOrderingTest` 覆盖数学与顺序，真机 GL 光晕与 24MP 烘焙一致性未复验）
- [x] 保存当前画面为 `Preset` 或通过 `CopyMask` 跨图粘贴到另一张照片时，仅复制 `SubjectOps` 参数而不复制源图 `SubjectMask` 文件，目标照片基于自身画面独立生成蒙版并生效。（`CopyMaskSubjectTest` / `CopyMaskAiIsolationTest` 覆盖遮罩引用不跨图；编辑器内应用含 `SubjectOps` 预设时由 `PresetSubjectGate` + `EditorSubjectController.ensureMaskForPreset` 自动补算，`PresetSubjectGateTest` 通过）
- [x] 开启含 `SubjectOps` 的 `LiveTetherPreset` 后，标准档设备（>4 GB 且 MODNet 已下载）在联机入库串行队列中自动为新照片生成带背景虚化/换底/前后景调色的预览。（`TetheredSubjectPresetTest` 覆盖串行队列、档位判定与预览渲染；真机 USB 联机未复跑）
- [x] 模拟 ≤4 GB 低内存档或未下载 MODNet 时，`LiveTetherPreset` 静默降级为仅应用传统调色，USB 同步入库零阻塞、零崩溃，进入编辑器后仍可按需补算。（`TetheredSubjectPresetTest` 覆盖低档位与模型缺失降级；真机同步未复跑）
