# 01: P0 统一端侧 AI 底座、多镜像模型管理页与真机摸底门禁

**What to build:** 为应用引入统一端侧推理底座（`onnxruntime-android:1.22.0`，CPU/XNNPACK，保留现有 MediaPipe 专职人脸关键点与离线美肤兜底），彻底屏蔽其冷启动遥测与多余 ABI/符号膨胀；建立进程内单活互斥的推理会话管理器（严禁冷启动或主界面/预设条预热，标准机闲置 30 秒自动释放，≤4 GB 低端机推理完立即释放）；扩展按需模型下载器支持多镜像有序回退（GitHub Releases 主源 → 备用镜像）与系统文件选择器（SAF）本地导入 `.onnx`（强制字节数 + SHA-256 强校验与断点续传）；在设置页交付「AI 模型管理」界面，让用户清晰查看各模型占用空间、许可证与状态，并支持单独下载、删除与重下；同步完成 MI-GAN 生成器-only 512 ONNX 产物固化，并在标准机与低端机完成 MODNet 与 MI-GAN 延迟及峰值 RSS 真机摸底。

**Blocked by:** None (can start immediately)

**Status:** in-progress (code-complete, hardware-gate-pending)

- [x] 集成 `onnxruntime-android:1.22.0` 后，release 构建产物保留 `ai.onnxruntime.**`、不包含 `x86`/`x86_64` 与 native 调试符号，且合并后的清单中不再包含 `ai.onnxruntime.TelemetryInitializer`。
- [x] 单活互斥 `OrtSession` 生命周期管理器通过单测与真机验证：主界面启动与展开预设条时不触发任何 ORT 模型加载；切换模型先关闭旧会话；标准档（>4 GB）闲置 30 秒或触发 `onTrimMemory` 时释放会话，低端档（≤4 GB）推理完成立即释放，且捕获 `OutOfMemoryError`/`OrtException` 返回可恢复错误。（JVM 单测通过；真机验证随第 5 项硬件门禁一并完成。）
- [x] 扩展后的模型下载器通过 JVM 单测：主源超时或失败自动切至备用镜像；断点续传完成后精确校验字节数与 SHA-256；校验失败自动删除损坏文件；SAF 本地导入 `.onnx` 通过同一校验路径且拒绝篡改文件。
- [x] 设置页「AI 模型管理」可完整展示按需模型列表、已占用空间与下载状态，支持触发下载、本地导入、单模型删除与重下，且删除模型不破坏已保存的编辑记录。
- [ ] 完成 MI-GAN 生成器-only 固定 512 ONNX（无动态裁切算子）导出并固化字节数与 SHA-256，并在至少 1 台标准机（≥8 GB）与 1 台低端机（≤4 GB）记录 MODNet 与 MI-GAN 实际推理耗时和峰值 RSS，结果归档至调研/验收记录。
