# 11: 全链路安全降级矩阵验证、真机金标回归与合规文档收官

**What to build:** 对 P0–P4 全部端侧与可选云端 AI 能力进行收官验收与加固：构建覆盖两档内存（≤4 GB 低端档 / >4 GB 标准档）× 三档 GPU（Tier 1 `RGBA16F` / Tier 2 `RGBA8` / Tier 3 最小 Shader）的自动化降级矩阵与真机金标回归套件，逐条验证 24MP 透明 PNG 导出内存与无 OOM、≤4 GB 推理完 `OrtSession` 计数归零、外发图片零 EXIF 与零缩略图残留、公网明文 HTTP 与跨源重定向拦截、以及 Release R8 APK/AAB 体积与遥测清理门禁；完成 `THIRD_PARTY_NOTICES.md`（ONNX Runtime MIT、MODNet Apache-2.0、MI-GAN MIT + 权重许可、CC0 天空素材声明）、`CONTEXT.md` 与三语 README 的同步核验。

**Blocked by:** 06: P2 智能消除（MI-GAN-512 局部裁切融合 + 确认涂抹画笔）垂直切片, 09: P4 编辑器云端生成闭环（CloudEditLayer、首次隐私告知与几何冲突守卫）, 10: P3 换天空（零模型分割、OKLab 前景重光照与 0 KB 程序化/自选天空）垂直切片

**Status:** ready-for-agent

- [ ] 真机/Instrumented 回归通过：24MP RAW 与 JPEG 执行「去背导出 PNG」「消除 + 调色导出 JPEG」「换天 + LUT 导出 JPEG」全部无 OOM、像素视觉通过金标验收且无列带拼缝。
- [ ] 内存与降级门禁通过：模拟 ≤4 GB 低内存档执行 MODNet 与 MI-GAN 推理结束后 `OrtSession` 活跃计数归零；模拟烘焙期 OOM 与 Tier 2/Tier 3 GPU 均按降级矩阵优雅回退不崩溃。
- [ ] 安全与 Release 门禁通过：`assembleRelease` 与 `bundleRelease` 产物经检查确认不含 `x86`/`x86_64`、不含 `TelemetryInitializer` provider、保留 `ai.onnxruntime.**`；外发清洗与公网明文拦截回归全绿。
- [ ] `THIRD_PARTY_NOTICES.md`、`CONTEXT.md` 与三语 README（中/英/俄）均已完成合规与功能同步审查，且全套 `./gradlew assembleDebug testDebugUnitTest lintDebug` 通过。
