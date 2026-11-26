# 09: P4 编辑器云端生成闭环（CloudEditLayer、首次隐私告知与几何冲突守卫）

**What to build:** 打通 P4 云端生成式能力在编辑器内的端到端体验：仅当用户在设置中开启 P4 总开关后，「AI」分组才显示「云端」子工具（关闭时彻底隐藏）；首次发起云端请求前强制弹出不可静默跳过的隐私与费用知情确认单；支持三种输入模式——整图自然语言改图、带涂抹 Mask 局部重绘（Gemini 明确提示 `SOFT` 语义边界不保证严苛选区）、以及带 `SubjectMask` 反选背景替换；发送前经闭世界清洗管线剥离全部 EXIF 与嵌入式缩略图，发送期底栏顶部显示可取消阶段进度；云端返回结果落盘为内容寻址的 Post-Pass A / Pre-Pass B `CloudEditLayer`（绑定当前 `geometryDigest`，支持不透明度滑杆与局部边缘羽化），云端改图后用户可继续在本地调节曝光/色彩/3D LUT/颗粒并支持单步撤销、长按 Before/After 对比相机原片与全分辨率烘焙；若事后修改 Pass A 几何（裁剪/旋转/透视/液化）导致 `geometryDigest` 失配，弹出非破坏性确认单由用户选择移除云端层或取消几何改动。

**Blocked by:** 02: EditGraph Schema v2、内容寻址 AI 资产存储与 7 Tab「AI」编辑器框架, 05: P1 主体蒙版（MODNet + 分块 GuidedFilter + FMGE）与去背/换底垂直切片, 07: P4 云端 BYOK 协议引擎（OpenAI-compatible / Gemini / ComfyUI）与配置页切片

**Status:** resolved

- [x] P4 总开关关闭时编辑器底栏与子工具条完全不出现「云端」入口；开启后可见，且首次点击发送前必须通过隐私/费用知情确认单。（`EditorPanelController` 过滤 `AI_CLOUD` chip + `EditorActivity.onResume` 重读开关；`EditorCloudControllerTest.shouldHideTheCloudEntry_whenTheGlobalSwitchIsOff` 与 `shouldBlockTheSendAndAskForConsent_whenConsentWasNotAccepted` 通过；真机设置页往返未复跑）
- [x] 支持整图指令、涂抹局部重绘与反选 `SubjectMask` 换背景三种请求形态；选择 Gemini 适配器且使用局部涂抹时，UI 显式提示不保证严格像素选区边界。（`CloudEditServiceTest` 覆盖三种形态与 Gemini `SOFT` 提示数据；涂抹遮罩由 `CloudPaintedMask` 直接栅格化可见帧笔迹）
- [ ] 云端回传图像落盘为 Post-Pass A `CloudEditLayer`：支持调节不透明度，继续叠加本地 Pass B/C/D 调色与 3D LUT 正常生效，长按 Before/After 可直接对照原始相机照片，保存重开与全分辨率烘焙一致。（集成完成：Pass B 着色器在读取 Pass A 输出后、BaseLook/WB/Tone/Color 之前融合 `uCloudLayerTex`+`uCloudMaskTex`，compareOriginal 强制短路；预览 / 离屏保存 / 列带烘焙（`BakePassChain` 以 `band.tileUv` 映射全帧 UV）/ CPU 路径（`LinearCloudLayer` 线性域融合）统一同一接缝，`CloudPassBOrderingTest` 覆盖顺序与分块 UV、`CloudLayerBlenderTest` 覆盖不透明度；真机 GL 像素、长按对比与保存重开未复验）
- [x] 存在 `CloudEditLayer` 时尝试裁剪、旋转、透视或液化，自动触发 `geometryDigest` 失配确认底单：选「取消」完整保留原画幅与云端层，选「移除云端层」作为一个可撤销事务清除云端层并应用新几何。（`EditorUndoController.parameterGuard` 在每次几何写入前否决，覆盖裁剪/拉直/旋转/镜像/透视/液化与预设；`EditorCloudControllerTest` 的 veto / confirm / cancel 三例通过；真机弹窗流程未复跑）
