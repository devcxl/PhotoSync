# 人像关键点 MLS 网格与美肤/美体遮罩合成规范

Type: grilling
Status: resolved
Blocked by: 01, 03

## Question

结合 MediaPipe 模型调研结论与几何画幅策略，锁定人像组的算法与工程规范：
1. **478 点 Face Landmarker 关键点分组契约**：瘦脸（下颌线/颧骨）、大眼（左右眼中心与眼周环）、缩鼻（鼻梁中线与鼻翼）、下巴/额头、微笑（嘴角与唇线）各自对应的精确 MediaPipe Landmark 索引集合与局部坐标系定义（如何适应人脸旋转倾斜角度）。
2. **MLS（移动最小二乘）64×64 网格求解规格**：锚点（不动点，防止整张图边缘跟着脸一起变形）的布置策略、相似变换权重指数 $\alpha$、CPU 求解时机（仅在滑杆变化时在后台线程重算 64×64 位移纹理）以及与阶段 ① 液化位移场的叠加顺序。
3. **美肤与美体遮罩合成规格**：`selfie_multiclass_256x256` 的 6 类概率图如何与五官保护区（眼睛/眉毛/嘴唇多边形扣除）及肤色门控合成为 `skinMask`；多人脸场景（画面中有 2 张以上人脸）的目标选择或全员应用策略。

## Answer

### 1. MediaPipe 478 点 Face Landmarker 关键点分组与局部人脸坐标系

#### 旋转不变局部人脸坐标系 $(O_f, \hat{u}_f, \hat{v}_f, W_f, H_f)$
对检测到的每张人脸（`numFaces = 5`，过滤包围盒面积 `< 0.5%` 全图面积的远景小脸）：
- **原点 $O_f$**：鼻梁中心 `landmark[168]`（两眼内眼角中点上方）；
- **横向单位轴 $\hat{u}_f$**：从右眼外眼角 `landmark[33]` 指向左眼外眼角 `landmark[263]` 的归一化向量（自动适应歪头旋转角 $\theta_f$）；
- **纵向单位轴 $\hat{v}_f$**：$\hat{u}_f$ 顺时针旋转 $90^\circ$（从眉心 `landmark[10]` 指向下巴尖 `landmark[152]` 方向）；
- **尺度基准**：脸宽 $W_f = \|P_{234} - P_{454}\|$（左右颧骨外缘距离），脸高 $H_f = \|P_{10} - P_{152}\|$。

#### 面部重塑 6 项滑杆的精确 Landmark 索引与位移向量契约

| 子参数 | 控制点集合（MediaPipe Canonical Mesh 478 索引） | 目标位移公式（$s \in [-1, 1]$） |
|---|---|---|
| **瘦脸** `face` | 左下颌线 `[132, 58, 172, 136, 150, 149, 176, 148]`；右下颌线 `[361, 288, 397, 365, 379, 378, 400, 377]`；颧骨锚点 `[234, 454]` | 各下颌点沿 $\hat{u}_f$ 朝人脸中轴线收缩：$\Delta P_i = -0.07 \cdot s \cdot \text{sign}((P_i - O_f)\cdot \hat{u}_f) \cdot w_i \cdot W_f \hat{u}_f$（越靠近腮角 `172/397` 权重 $w_i$ 越大，下巴尖 `152` 处权重为 0） |
| **大眼** `eyes` | 右眼环 `[33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246]`（中心 `468` 右虹膜中心）；左眼环 `[263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466]`（中心 `473` 左虹膜中心） | 各眼周点相对虹膜中心 $C_{eye}$ 径向外扩：$\Delta P_i = 0.12 \cdot s \cdot (P_i - C_{eye})$ |
| **缩鼻** `nose` | 左鼻翼 `[129, 49, 131, 115, 218]`；右鼻翼 `[358, 279, 360, 344, 438]`；鼻尖锚点 `[1, 4]` | 左右鼻翼沿 $\hat{u}_f$ 朝鼻中线收缩：$\Delta P_i = -0.08 \cdot s \cdot ((P_i - P_1)\cdot \hat{u}_f)\hat{u}_f$ |
| **下巴** `chin` | 下巴底弧 `[148, 152, 377]`（锚定嘴底 `17` 与下颌拐角 `136, 365`） | 沿纵向轴 $\hat{v}_f$ 伸缩：$\Delta P_i = 0.06 \cdot s \cdot H_f \hat{v}_f$ |
| **额头** `forehead` | 额头顶弧 `[103, 67, 109, 10, 338, 297, 332]`（锚定眉弓 `[70, 105, 334, 300]`） | 发际线推高/压低：$\Delta P_i = -0.06 \cdot s \cdot H_f \hat{v}_f$ |
| **微笑** `smile` | 左嘴角 `[61, 146, 91]`；右嘴角 `[291, 375, 321]`（锚定上唇中心 `0`、下唇中心 `17`） | 嘴角向斜上方提拉：$\Delta P_{61/291} = s \cdot ( \pm 0.035 W_f \hat{u}_f - 0.045 H_f \hat{v}_f )$ |

### 2. MLS（移动最小二乘）相似变换网格与单一位移纹理合并

1. **不动锚点（Boundary & Halo Pins）**：
   - 图像四条外边界均匀布设 32 个不动锚点（$\Delta P = 0$）；
   - 每张人脸椭圆外围 $1.8 \times$ 半径处环绕布设 16 个局部不动锚点（$\Delta P = 0$），确保多人合影时各张脸的形变被严格限制在各自脸周，绝不拉歪相邻人脸或背景直线。
2. **网格求解与 `128 × 128` `GL_RG16F` 统一合并**：
   - 在 CPU 后台线程对 `64 × 64` 格点按 Schaefer 2006 移动最小二乘相似变换（权重 $w_i(v) = \|v - q_i\|^{-2\alpha}, \alpha = 1.0$）求解逆映射位移 $\Delta uv_{face}$（单次求解 `< 2ms`）；
   - 双线性上采样到 `128 × 128`，与**美体轴向位移 $\Delta uv_{body}$** 及**液化画笔网格 $\Delta uv_{liquify}$** 直接在 `128 × 128` 网格上向量相加：
     $$\Delta uv_{total}(x, y) = \Delta uv_{face}(x, y) + \Delta uv_{body}(x, y) + \Delta uv_{liquify}(x, y)$$
   - 上传为单张 `128 × 128` `GL_RG16F` 纹理 `uWarpTex`（启用 `GL_LINEAR` 硬件插值），阶段 ① 几何 Shader 仅需 1 次 `texture(uWarpTex, uvPreCrop).xy` 采样。

### 3. 美肤遮罩（在线 6 类精细模式 + 离线无网兜底模式）

1. **五官保护多边形扣除（`eyeLipBrowHoleMask`）**：
   - 由 478 点连接表围成的 5 个闭合多边形（左眼眶、右眼眶、左眉、右眉、外唇轮廓 `[61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146]`）光栅化到 `256 × 256` 掩码并做 `3px` 高斯羽化，保护五官细节不被磨平。
2. **双模式 `skinMask` 合成**：
   - **在线完整模式（16.37 MB `selfie_multiclass_256x256.tflite` 已就绪）**：
     $$M_{seg} = \text{clamp}(P_{\text{class }2(\text{body-skin})} + P_{\text{class }3(\text{face-skin})} - P_{\text{class }1(\text{hair})}, 0, 1)$$
     $$M_{skin} = \text{feather}(M_{seg} \cdot (1 - M_{\text{eyeLipBrow}})) \cdot G_{\text{skinColor}}(L, C, h)$$
   - **离线兜底模式（首次尚未下载 16.37 MB 模型或外拍无网环境）**：
     利用 APK 内置的 `face_landmarker.task`（脸部轮廓椭圆外扩 + 脖颈梯形）与内置 250 KB `selfie_segmenter.tflite`（人像前景概率 $P_{\text{person}}$）加上更严格的 OKLCH 肤色门控 $G_{\text{skinColor}}$（$h \in [15^\circ, 75^\circ], C \in [0.02, 0.18], L \in [0.25, 0.92]$）：
     $$M_{skin}^{\text{offline}} = \text{feather}(P_{\text{person}} \cdot (1 - M_{\text{eyeLipBrow}})) \cdot G_{\text{skinColor}}(L, C, h)$$
     后台下载并 SHA-256 校验通过 `selfie_multiclass_256x256.tflite` 后，自动无缝切换为在线 6 类模式。

### 4. 美体（瘦腰 / 拉腿）空间定位与双引导线契约（零额外姿态模型体积）

1. **自动初值推导**：
   - 设人体前景掩码 $P_{\text{person}}$（在线模式取 $\text{class } 1 \cup 2 \cup 3 \cup 4$，离线模式取 `selfie_segmenter`）；
   - 若检测到主脸 $(O_f, H_f)$：默认腰部中心高度 $y_{\text{waist}} = \text{clamp}(O_{f,y} + 2.8 H_f, 0.35, 0.75)$，腰部纵向半宽 $\Delta y_{\text{waist}} = 1.1 H_f$，横向挤压中心 $c_x$ 取 $y_{\text{waist}}$ 处 $P_{\text{person}}$ 的水平质心；默认髋线高度 $y_{\text{hip}} = \text{clamp}(O_{f,y} + 3.6 H_f, 0.45, 0.82)$；
   - 若无正脸：取 $P_{\text{person}}$ 纵向有效包围盒的 $45\%$ 处为 $y_{\text{waist}}$、$55\%$ 处为 $y_{\text{hip}}$。
2. **画布双引导横线微调**：
   - 当用户在「人像 · 美体」面板操作时，画布叠加两条可单指上下拖拽的半透明水平引导线（腰线 `yWaist` 与髋线 `yHip`，保存在 `EditGraph.portrait.body` 的可选字段 `"waistY"` / `"hipY"` 中）。
3. **拉腿固定画幅内接裁切**：
   - 拉腿（$s_{\text{legs}} \in [-1, 1]$）在 $y \ge y_{\text{hip}}$ 区间应用 $C^1$ 连续纵向逆映射：$y_{\text{src}} = y - 0.12 \cdot s_{\text{legs}} \cdot \frac{(y - y_{\text{hip}})^2}{1 - y_{\text{hip}}} \cdot P_{\text{person}}(x, y)$；当 $s_{\text{legs}} > 0$ 时采样点上移（腿部向下伸展并自然裁掉原图底边外溢部分），输出图像宽高比保持不变。
