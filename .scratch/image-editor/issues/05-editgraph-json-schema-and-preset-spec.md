# EditGraph JSON Schema v1 与预置滤镜格式规范

Type: grilling
Status: open
Blocked by: 03, 04

## Question

基于已敲定的参数语义、画幅裁切策略与 UI 交互状态机，制定非破坏性编辑图 `EditGraph` 的完整 JSON Schema v1 与持久化契约：
1. 34 个原子参数在 JSON 中的精确字段名、类型、取值范围、默认值、归一化映射函数（UI 显示值 ↔ Shader Uniform 值）及非法值（NaN/Inf/越界）的边界 Clamp 规则。
2. 特殊复杂结构的数据表示：
   - 曲线（`curve`）4 通道控制点数组约束（最少/最多点数、端点单调性校验）；
   - 液化（`liquify`）笔刷轨迹序列 vs 半分辨率位移网格的序列化策略（如何保证 `graph_json` 不会膨胀到数 MB 撑爆 Room CursorWindow 2MB 限制）；
   - 颗粒随机种子（`seed`）的生成与持久化时机。
3. 内置滤镜（参数预设 Preset）与外部 `.cube` 3D LUT 资源在 `EditGraph` 中的引用标识、强度混合与向后兼容升级（`schemaVersion` v1→v2 迁移函数）契约。
