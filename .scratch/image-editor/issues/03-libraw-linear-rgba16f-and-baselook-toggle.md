# 03: LibRaw 16-bit 线性 RGBA16F 解码与 RAW 基准观感切换

**What to build:** 扩展 `:libraw` 原生层，为编辑器提供直接输出 16-bit 线性光 `RGBA16F`（IEEE 754 `half_float`）`DirectByteBuffer` 与 `autoBrightGain` / 拍摄元数据的解码通道（绕开 8-bit 后处理量化且不在 JVM 堆上分配大数组），并在编辑器顶栏交付 RAW `BaseLook` 一键切换胶囊：默认采用 `camera_default`（在 GPU 端复刻浏览页的自动提亮与基础影调曲线，确保从主界面进入编辑器时 0 参数画面零跳变），点击可切换为 `linear_neutral`（纯平直线性起点），并在全分辨率/预览渲染中完整保留 16-bit RAW 的高光与暗部动态余量。

**Blocked by:** 02: 编辑器模块骨架、非破坏性 EditGraph 持久化与首条曝光垂直切片

**Status:** ready-for-agent

- [ ] `:libraw` 新增 16-bit 线性解码 JNI 接口：配置 `output_bps = 16`、`output_color = 1`、`gamm = [1.0, 1.0]`、`use_camera_wb = 1`、`no_auto_bright = 1`，支持按 `maxLongEdge` 开启 `half_size = 1` 与 C++ OpenMP area 降采样，将像素直接转为 `RGBA16F` 写入 `DirectByteBuffer`，并返回与浏览路径一致的 `autoBrightGain` 与有效 ISO。
- [ ] GLES 3.0 纹理上传层直接通过 `glTexImage2D(..., GL_RGBA16F, ..., GL_HALF_FLOAT, directBuffer)` 消费该缓冲区，全程不产生 JVM 堆全图 `ByteArray`。
- [ ] 编辑器顶栏对 RAW 照片显示「`RAW:默认观感 | 线性中性`」切换胶囊，绑定 `EditGraph.baseLook`（`"camera_default" | "linear_neutral"`）。
- [ ] 在 `camera_default` 且全部滑杆为 0 时，RAW 编辑预览画面与主界面浏览页默认解码画面的平均色差 $\Delta E_{2000} \le 1.0$，无肉眼可见的明暗跳变。
