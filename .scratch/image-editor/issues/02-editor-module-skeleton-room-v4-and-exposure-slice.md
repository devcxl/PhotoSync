# 02: 编辑器模块骨架、非破坏性 EditGraph 持久化与首条曝光垂直切片

**What to build:** 建立独立的 `:editor` 模块（不反向依赖 `:app`）与 Room 数据库 v4 迁移（新增带 `ON DELETE CASCADE` 外键的 `photo_edits` 表与 `user_presets` 表），打通第一条贯穿全层的 Tracer Bullet 垂直切片：用户在主界面顶部点击新增的「编辑」按钮进入 `EditorActivity`，在自建 EGL GLES 3.0 `SurfaceView` 画布上通过带零点磁吸的刻度滑杆实时调节「曝光（Exposure）」、按住画布对比未编辑原图、体验 1024px 拖动代理节流与 300ms 撤销/重做栈事务合并；点击「完成」秒存 `EditGraph` JSON 与屏幕级预览缓存退回主界面，主界面大图与底部胶片条立即展示编辑后画面并点亮「已编辑」标签；删除照片时自动级联清除数据库编辑记录与磁盘预览缓存。

**Blocked by:** 01: 抽取相册落盘与 EXIF 写回契约（Prefactoring）

**Status:** ready-for-agent

- [ ] 新增 `:editor` 模块，定义 `EditorLaunchArgs`、`EditStore`、`GallerySink` 宿主契约接口，`:editor` 不包含任何对 `:app` 的依赖。
- [ ] 完成 `EditGraph` JSON Schema v1 模型与 `EditValidator`（拒绝 `NaN`/`Infinity`，越界自动 Clamp，支持 `isIdentity()` 恒等判定）。
- [ ] `AppDatabase` 从 v3 升级至 v4，提供 `MIGRATION_3_4` 创建 `photo_edits`（`FOREIGN KEY(photo_id) REFERENCES photos(id) ON DELETE CASCADE` + `photo_id` 唯一索引）与 `user_presets` 表；删除照片时同步清理 `edits/<photoId>` 缓存目录。
- [ ] 实现 GLES 3.0 自建 EGL 渲染线程与 `SurfaceView` 预览画布，打通曝光滑杆（`-5.0..+5.0 EV` 线性域 `exp2`）、300ms 窗口撤销/重做事务合并（最多 50 步）、长按画布对比原图（Before/After），以及横屏右侧 `320dp` / 竖屏底部三层面板布局。
- [ ] 点击「完成」保存非恒等 `EditGraph` 与预览缓存后退回主界面，主界面 `ViewPager2` 与 `FilmstripAdapter` 显示编辑后预览图并展示「已编辑」胶囊；若参数全部归零（`isIdentity() == true`），自动移除 `photo_edits` 记录与「已编辑」胶囊。
- [ ] 包含 `EditValidator`、300ms 撤销合并状态机与 Room `MIGRATION_3_4` 级联删除的自动化测试。
