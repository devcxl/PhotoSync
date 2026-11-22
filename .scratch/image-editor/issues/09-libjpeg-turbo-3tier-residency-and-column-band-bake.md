# 09: NDK libjpeg-turbo 三档源图驻留与列带流式分块烘焙导出切片（Bake Pipeline）

**What to build:** 打通高像素（24MP / 50MP / 61MP / 100MP）全分辨率非破坏性烘焙导出（`Bake`）管线：在 `:libraw` 中通过 `ExternalProject_Add` 编译链接 `libjpeg-turbo 3.2.2`（开启 ARM NEON SIMD，强制禁用多趟 `optimize_coding`/渐进式以保持编码器 $O(1)$ 内存），在 surfaceless EGL 上结合 1024px 全图 Pre-pass、三档源图纹理驻留策略（≤26MP 整图驻留 GPU；26–65MP Native `RGB16` 驻留+按列带逆映射包围盒动态切片上传突破 `GL_MAX_TEXTURE_SIZE`；>65MP 或低内存机启用 LibRaw `half_size=1`）与 `1920×512 (halo=64px)` 列带 `glReadPixels(GL_PACK_ROW_LENGTH)` 散射，按全宽行条带流式喂入 `jpeg_write_scanlines(JCS_EXT_RGBA)`，最后经 `GallerySink` 与 `ExifCopier` 写回拍摄 EXIF（`Orientation=1`），并在主界面与编辑器顶栏「导出」按钮交付使用。

**Blocked by:** 01: 抽取相册落盘与 EXIF 写回契约（Prefactoring）, 07: 外部 .cube 3D LUT 导入、全局颗粒、晕影与 GPU 4 级降级切片（Pass D）, 08: 1024px 全图 Pre-pass 金字塔、清晰度/纹理/锐化/降噪与暗通道去雾切片（Pass C）

**Status:** ready-for-agent

- [ ] 在 `:libraw` CMake 中通过 `ExternalProject_Add` + `IMPORTED` 静态库集成 `libjpeg-turbo 3.2.2`（开启 `WITH_SIMD=ON`，配合 `-Wl,--exclude-libs,ALL` 隐藏导出符号），封装 JNI 流式编码器（强制 `optimize_coding = FALSE`、单趟基线扫描、`JCS_EXT_RGBA`、质量 `95`）。
- [ ] 实现三档源图驻留策略，确保 50MP/61MP 照片在 `GL_MAX_TEXTURE_SIZE = 4096/8192` 的设备上通过 Native 源图按列带逆映射包围盒动态切片上传完成 100% 原生全分辨率导出，且全程峰值内存 $\le 512\text{MB}$。
- [ ] 实现 `1920×512 (halo=64)` 列带渲染与 `glReadPixels(64, 64, W_valid, H_valid)` + `GL_PACK_ROW_LENGTH = W_crop` + `GL_PACK_SKIP_PIXELS = x0` 散射拼接，并支持协程取消（每列带检查 `isActive`）。
- [ ] 主界面对已编辑照片短按「导出」触发后台 `Bake` 成片，长按弹出「导出编辑后成片 / 导出未编辑原图」菜单。
- [ ] Instrumented 测试验证：开启清晰度、去雾、颗粒、晕影与横向色散时，整幅导出与 `1920×512` 列带分块导出逐像素最大绝对差 $\le 1/255$（零拼缝），且 8 种 EXIF Orientation 输入导出后 `TAG_ORIENTATION == "1"`、拍摄元数据完整。
