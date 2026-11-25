# 02: EditGraph Schema v2、内容寻址 AI 资产存储与 7 Tab「AI」编辑器框架

**What to build:** 将非破坏性 `EditGraph` 升级至 Schema v2（保持 Room v4 表结构不变并完全向后兼容读取 v1 JSON），引入 `SubjectOps`、`InpaintPatch`、`SkyOps` 与 `CloudEditLayer` 的参数与外置资产引用定义及边界校验；建立统一的内容寻址外置资产存储（唯一临时文件 + 落盘同步 + 原子重命名 + 读取 SHA-256 校验），确保照片被 `Delete` 删除时级联清空整目录，并在冷启动/闲时安全清理无活跃会话且未被数据库引用的历史孤儿资产；在编辑器底栏将 6 个一级分类扩展为 7 等宽 Tab（新增「AI」分组，竖屏底部抽屉 / 横屏右侧抽屉适配），预置子工具 Chip 容器与底栏顶部非阻塞可取消异步进度条骨架；同步更新领域词汇表 `CONTEXT.md` 收录新增 AI 领域术语。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `EditGraph` Schema v2 编解码器与校验器通过 JVM 单测：无损读取所有历史 v1 JSON（新增 AI 节点默认为恒等状态）；拒绝 `NaN`/`Infinity` 并对越界参数自动钳位；未启用 AI 层时 `isIdentity()` 与渲染零开销短路行为不变。
- [ ] `CopyMask` 与 `PresetGraphMerger` 单测明确验证：`InpaintPatch` 与 `CloudEditLayer` 永远不会被跨照片复制或写入 `Preset`。
- [ ] 内容寻址外置 AI 资产存储通过单测：写盘采用原子重命名不覆盖旧资产；读取校验 SHA-256 并拒绝损坏文件；单张照片 `Delete` 时级联删除其外置资产目录；离线 GC 仅清理未被持久化编辑记录引用的过期资产且跳过活跃编辑/导出中的照片。
- [ ] 编辑器底栏展示 7 个等宽 Tab（含「AI」），在 360dp 竖屏与横屏下无文字截断或溢出；底栏顶部异步进度条支持展示进度、文案与点击「取消」中止当前协程作业。
- [ ] `CONTEXT.md` 已补充 `AiModel`、`SubjectMask`、`InpaintPatch`、`SkyLayer` 与 `CloudEditLayer` 的规范定义与禁用混用词。
