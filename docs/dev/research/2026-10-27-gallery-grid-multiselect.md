# 相册式网格浏览与多选删除：技术调研

> 调研时间：2026-10-27　|　范围：Android 7.0 (API 24) ~ API 36，单机应用，照片来源为 USB PTP 导入
> 关联代码：`app/src/main/java/cn/devcxl/photosync/activity/MainActivity.kt`、`adapter/PhotoPagerAdapter.kt`、`data/PhotoDao.kt`

## 研究结论摘要

1. **当前形态是「单张全屏流水」，不是「相册」**：只有一个 `ViewPager2` 全屏预览，删除只能删当前页那一张，没有网格、没有选择态、没有批次/日期维度。要做相册，缺的不是一个网格控件，而是 **缩略图仓库 + 列表数据 + 选择态** 三件事。
2. **数据基础比 UI 更值得先修**：导入文件落在 `externalCacheDir`，而系统在存储紧张时会清空缓存目录，用户「以为已导入」的照片可能凭空消失；并且 `Room` 使用 `fallbackToDestructiveMigration()`，一旦加字段就会静默清库。**先修存储位置与迁移策略，再谈相册体验**（置信度：高）。
3. **删除语义要按「文件归属」分两种情况**：本项目自有的文件（cache/files 目录）可直接 `File.delete` + 删 DB 行；一旦用户把照片导出到系统相册（MediaStore），Android 11+ 必须走 `createTrashRequest`/`createDeleteRequest` 并弹系统确认框，Android 16 起单次请求上限 2000 个 Uri（置信度：高，官方文档明确）。
4. **缩略图是网格性能的唯一瓶颈，且必须自己实现 RAW 分支**：JPEG 可用 Coil/BitmapFactory，RAW 必须走 LibRaw 内嵌 JPEG（项目已有 `decodeThumbnail`），不能指望通用图片库。现有缩略图缓存绑在 `MainActivity` 里，必须抽成共享仓库，否则网格和翻页会各下一份。
5. **UI 技术选型**：仓库已移除 Compose 依赖与编译器插件，且 `AGENTS.md` §11 明确要求全部界面使用 **XML + ViewBinding**、不得重新引入 Compose。因此网格应实现为 **新增独立 `GalleryActivity` + `RecyclerView` + `GridLayoutManager` + `ListAdapter`/`DiffUtil`**，保留现有全屏预览页不动，而不是把已近 720 行的 `MainActivity` 改造成网格（置信度：高，与仓库约定一致；性能判断中）。

## 背景与问题定义

- **研究目标**：为「平铺浏览全部照片 + 多选删除」给出可落地、可验证的技术方案，覆盖数据层、缩略图、交互、删除权限、性能与迁移风险。
- **范围**：Android 原生；照片集合规模假设 10² ~ 10⁴ 张；文件位于 App 私有目录（当前现状）为主，系统相册（MediaStore）为可选扩展。
- **限制**：不引入服务端；不改现有 PTP 协议层；`minSdk 24`（需同时兼容 API 24 与 API 34+ 的分区存储/部分授权行为）。
- **输出形式**：方案对比 + 分阶段实施建议 + 验收标准。

## 研究方法

- **来源策略**：优先 Android 官方文档（存储、媒体、Paging、权限）；版本行为以官方版本说明为准；工程实践类结论标注为「推断」。
- **迭代轮次**：
  - 第 1 轮：摸清现有实现（读代码，确认 UI/数据/删除/存储事实）→ 发现「缓存目录」与「破坏性迁移」两个结构性风险。
  - 第 2 轮：核对 Android 存储与批量删除的官方语义（含 API 版本差异与数量上限）。
  - 第 3 轮：核对列表规模化方案（Paging 3）与缩略图/授权的边界，做反方检查（Paging 是否过度设计）。
- **已知局限**：未在真机上做帧率/内存实测，性能结论均为量级推断；`Room` 与 `RecyclerView` 的版本行为未逐版本核对。

## 关键发现

### 发现 1：现有实现会「丢照片」，相册必须先解决数据归属

- **发现内容**：`MainActivity.configureConnectionController()` 把下载目录设为 `externalCacheDir`，导入成功只往 `photos` 表插一行 `path`。而 Android 明确说明缓存目录中的文件**可能在系统需要空间时被删除**；用户看到网格里仍有条目、点开却是空图，是必然会发生的事故。另外 `AppDatabase` 使用 `fallbackToDestructiveMigration()`，任何 schema 变更（例如给照片加宽高、调色参数）都会**静默清空整张表**。
- **依据来源**：
  - [Access app-specific files](https://developer.android.com/training/data-storage/app-specific) — Android Developers — 持续更新 — 权威 5 / 一手 5 / 时效 5 / 独立 5 / 可验证 5（综合 25）
  - 代码事实：`MainActivity.configureConnectionController()`（`setFileDownloadPath(externalCacheDir)`）、`AppDatabase.getInstance()`（`fallbackToDestructiveMigration`）
- **置信度**：高
- **反方证据**：个人自用工具、导入后立刻导出到系统相册的话，缓存被清理的概率不高；但这属于「把正确性寄托在用户习惯上」，不能作为设计前提。

### 发现 2：批量删除在「自有文件」与「系统相册」是两套完全不同的机制

- **发现内容**：
  - 自有文件（当前全部场景）：`File.delete()` + `dao.deleteByIds()`，一步到位，无需系统弹窗。
  - 系统相册中的媒体：分区存储下，删除他人创建的媒体项需要逐项 `RecoverableSecurityException` 授权；Android 11+ 可改用 `MediaStore.createTrashRequest()`（进回收站，可恢复）或 `createDeleteRequest()`（立即永久删除），两者都返回 `PendingIntent`，必须 `startIntentSenderForResult` 让用户确认，结果以 `RESULT_OK` 判定。
  - **Android 16 (Baklava) 起，单次请求最多 2000 个 Uri**，超出抛 `IllegalArgumentException` → 大批量删除必须分批。
  - Android 12+ 可申请 `MANAGE_MEDIA`（媒体管理特殊权限）免除逐次确认，但该权限实际上只对系统相册类应用开放。
- **依据来源**：
  - [Access media files from shared storage](https://developer.android.com/training/data-storage/shared/media) — Android Developers — 权威 5 / 一手 5 / 时效 5 / 独立 5（综合 25）
  - [MediaStore#createDeleteRequest](https://developer.android.com/reference/android/provider/MediaStore) — Android Developers — 权威 5 / 一手 5 / 时效 5（综合 25）
  - [Storage use cases and best practices](https://developer.android.com/training/data-storage/use-cases) — Android Developers — 权威 5 / 一手 5 / 时效 4（综合 23）
- **置信度**：高
- **反方证据**：暂无。仅需注意 `createTrashRequest` 的「回收站保留时长」由系统定义，应用无法假设。

### 发现 3：缩略图必须自建仓库，RAW 与 JPEG 走两条路

- **发现内容**：网格在 3 列 ~ 200dp 的单元里只需要长边 300~512px，而项目已具备 RAW 内嵌 JPEG 提取能力（`RawWrapper.decodeThumbnail(path)` + `targetLongEdge` 采样）。通用图片库（Coil/Glide）无法识别 RAW，因此网格的加载器必须是「按扩展名分派」的门面：
  - JPEG → `BitmapFactory` 采样解码（仓库无 Coil/Glide；若要引入可换取内存/磁盘缓存与生命周期取消，但会新增依赖）
  - RAW → `RawWrapper.decodeThumbnailBitmap(path, 512)`，**串行**（现有代码已用 `Dispatchers.Default.limitedParallelism(1)` 限制并发，这个约束要保留，否则 20 个 RAW 同时解码会 OOM）
  - 内存缓存 key 用 `path + 目标尺寸`，磁盘缓存存 512px JPEG（首次生成后秒开）
- **依据来源**：
  - 代码事实：`RawWrapper.kt:decodeThumbnailBitmap`、`MainActivity.kt:thumbnailCache/rawThumbnailDispatcher`
  - [LibRaw API: dcraw_make_mem_thumb](https://www.libraw.org/docs/API-CXX.html) — LibRaw — 权威 5 / 一手 5（综合 25）
- **置信度**：高（能力边界来自代码与官方 API），缓存命中率与首屏耗时属推断
- **反方证据**：若照片总量 < 200 张，直接在 `Dispatchers.IO` 解码不缓存也能接受，磁盘缓存属于优化而非必需。

### 发现 4：Paging 3 对本地 Room 列表是「可选项」，不是必需品

- **发现内容**：官方推荐用 Paging 3 + `LazyPagingItems` 支撑长列表与无限滚动，Room 也提供 `PagingSource`。但 Paging 的收益来自「分批加载 + 占位符 + 增量刷新」；本项目数据源是本地表，单行 < 200 字节，1 万行全量查询约数 MB 级，`Flow<List<PhotoEntity>>` 完全可承受。Paging 的真实代价是：选择态需要跨页维护（`Set<id>` 而非 `Set<index>`）、`snapshot()` 与删除后的失效处理复杂化。
- **依据来源**：
  - [Load and display paged data](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data) — Android Developers — 权威 5 / 一手 5 / 时效 5（综合 25）
  - [Lazily load data with lists and Paging](https://developer.android.com/develop/ui/compose/quick-guides/content/lazily-load-list) — Android Developers — 权威 5 / 一手 5（综合 25）
- **置信度**：中（官方推荐 Paging，但「本项目不需要」是基于规模推断）
- **反方证据**：官方文档明确建议长列表使用 Paging；若未来接入「扫描整个系统相册」或云同步，规模上万后 Paging 更稳。

### 发现 5：选择态用稳定 ID，不用索引；删除要可撤销

- **发现内容**：`PhotoEntity.id` 已是自增主键且 `path` 唯一索引，天然适合做选择键。工程要点：
  - 选择集 `Set<Long>`（id），配合 `ListAdapter` + `setHasStableIds(true)`（`getItemId` 返回 `entity.id`）与 `DiffUtil`，删除后列表重组不会错位。
  - 长按进入选择模式；`BackHandler` 退出；顶栏显示「已选 N 张」+ 全选/反选。
  - 删除前确认，删除后给「撤销」Snackbar（策略：先把文件移到 `filesDir/trash/`，DB 标记 `deleted_at`，超时或下次启动清理）。这比「删了就是删了」更符合相册类应用的用户预期。
- **依据来源**：
  - 代码事实：`PhotoEntity.kt`、`PhotoDao.kt`
  - 业界惯例（Google Photos / 系统相册的「最近删除」）—— 属设计惯例，非规范
- **置信度**：中（交互结论来自惯例，非硬证据）
- **反方证据**：软删除会带来存储占用与「为什么空间没释放」的困惑；对个人工具，直接删 + 明确确认也合理。

### 发现 6：现有 `PhotoEntity` 缺少网格所需的元数据

- **发现内容**：表里只有 `path / name / uri_string / created_at`，没有宽高、方向、文件大小、拍摄时间。后果：网格无法预分配单元（RAW 首帧要等解码完才知道比例）、无法按拍摄日期分组、无法显示「12.3 MB / 6000×4000」。PTP 的 `ObjectInfo` 通常带尺寸信息，导入时顺手落库成本最低；已有存量数据可在首次解码缩略图时回填。
- **依据来源**：代码事实 `PhotoEntity.kt`、`ptp/usbcamera/ObjectInfo.kt`
- **置信度**：高
- **反方证据**：若网格统一用正方形裁切（`ContentScale.Crop`），宽高并非必需，只影响「自适应列宽/瀑布流」这类高级形态。

## 对比分析

### 方案对比（UI 与数据）

| 维度 | A. 新 Activity + RecyclerView 网格 | B. 新 Compose 页面 + LazyVerticalGrid | C. 直接把 MainActivity 图库化（ViewPager2 + 网格切换） |
|---|---|---|---|
| 与现有代码冲突 | 无，纯增量 | 需重新引入 Compose 依赖与编译器插件（`AGENTS.md` §11 禁止） | 高，720 行 Activity 再膨胀 |
| 选择态实现 | Adapter + `ActionMode` + `Set<Long>`，样板代码较多但可控 | `Set<Long>` + `BackHandler`，最直接 | 混乱，两套列表状态共存 |
| 动画/手势 | 需手写共享元素 | `AnimatedVisibility`/`sharedBounds` 现成 | 难 |
| 引入新依赖 | 0（`recyclerview` 随 `viewpager2` 已在） | Compose BOM + material3 + activity-compose + 编译器插件 | 0 |
| 学习/维护成本 | 低（仓库唯一 UI 栈） | 中，且形成双 UI 栈 | 高 |
| 可回滚性 | 高（删文件即回滚） | 高 | 低 |
| 推荐度 | **推荐** | 不推荐（与仓库 UI 决策冲突） | 不推荐 |

### 删除路径对比

| 场景 | 机制 | 用户确认 | 可恢复 | 数量限制 |
|---|---|---|---|---|
| App 私有文件（当前） | `File.delete()` + DB 删除 | 应用内对话框 | 需自建回收站 | 无 |
| 已导出到系统相册，Android 11+ | `createTrashRequest` | 系统弹窗 | 是（系统回收站） | ≤2000/次（Android 16+） |
| 已导出，Android 10 | `RecoverableSecurityException` 逐项授权 | 系统弹窗（每个文件） | 否 | 逐项 |
| 已导出，Android 9- | `WRITE_EXTERNAL_STORAGE` + `ContentResolver.delete` | 无 | 否 | 无 |

## 反方观点与分歧

- **「Paging 3 才专业」**：官方文档确实推荐长列表用 Paging；本报告认为万级以下本地表现在无必要。分歧点在于规模假设，若产品定位是「接管整个手机相册」，应改用 Paging。
- **「RecyclerView 的选择态样板代码太多，不如 Compose」**：确实，Compose 的 `Set<Long>` + `BackHandler` 更短。但仓库已移除 Compose 并明令不得重新引入（`AGENTS.md` §11），为网格单独恢复一套 UI 栈属于本末倒置；RecyclerView 侧用 `ActionMode` + `stable ids` 可把选择态控制在百余行内。
- **「多选删除太危险，应默认软删除」**：反方认为直接删不可逆；我方认为软删除会带来「空间没释放」的困惑，折中方案是直接删 + 明确的二次确认 + 显示张数与总大小。
- **「缓存目录当图库也行」**：反方认为导入后立刻导出即可；我方认为这会让「相册」这个功能本身失去意义（相册的前提是持久化）。
- **未解决**：`fallbackToDestructiveMigration` 是否要保留（开发期方便 vs 用户数据安全），需要产品决策。

## 风险与不确定性

- **未验证**：网格在 5000 张 RAW 下的首屏时间、滚动掉帧与内存峰值；RAW 缩略图首次生成的累计耗时（估计数百 ms/张，串行）。
- **需要实测**：`RecyclerView` 网格在低端机（API 24~28）的滚动稳定性与 `LruCache` 内存峰值；从网格跳进 `SubsamplingScaleImageView` / `PhotoView` 时的加载延迟。
- **元评审结论**：
  - 剩余未知：无真机性能数据；未核对项目实际 `compose-bom` 版本对应 API。
  - 最弱证据：性能与规模结论（均为量级推断）。
  - 可能错误的假设：「照片数量在千级」。PTP 一次同步可能导入上千张，长期使用可能上万。
  - 遗漏角度：多设备/多次导入的**批次分组**（相册的真实语义往往按「来源/日期」而非平铺）；搜索与筛选（按文件名/日期）；横竖屏与平板多列。
- **最坏情况分析**：
  - 失败方式：先做网格后改存储，用户已导入的照片在系统清缓存时丢失，网格里全是坏图。
  - 回滚可行性：UI 层可回滚（删 Activity/Adapter）；数据层回滚会丢用户数据。
  - 损失是否可控：**不可控**（用户照片不可再生，相机卡可能已格式化）。
  - 连锁影响：网格放大「坏条目」的暴露面，用户信任度下降。

## 建议

按优先级，**P0 必须先于网格落地**：

1. **P0 — 存储归位**：导入目录从 `externalCacheDir` 改到 `filesDir/photos`（或 `getExternalFilesDir(DIRECTORY_PICTURES)`），提供一次性迁移（把 cache 里已有文件搬到新目录并更新 `path`）。验收：清理应用缓存后照片仍可打开。
2. **P0 — 迁移策略**：为 `photos` 表加字段（`width`、`height`、`orientation`、`size_bytes`、`shot_at`、`deleted_at`）时写真正的 `Migration(2,3)`；仅 debug 构建保留破坏性迁移。验收：升级安装后旧数据完整。
3. **P1 — 抽出 `ThumbnailRepository`**：把 `MainActivity` 里的 `thumbnailCache / inFlightThumbnailPaths / rawThumbnailDispatcher / getJpegSourceInfo` 迁出，提供 `suspend fun load(path, targetEdge): Bitmap?` + 磁盘缓存；网格与预览共用。验收：同一张 RAW 在两处只解码一次（日志可证）。
4. **P1 — 网格页**：新增 `GalleryActivity`（XML + `RecyclerView` + `GridLayoutManager`，列表用 `ListAdapter`/`DiffUtil`，`setHasStableIds(true)` 配合 `getItemId = entity.id`），正方形裁切 + 类型角标（RAW/JPG）+ 右上角选择圈。默认按 `created_at DESC`（注意现有 DAO 是 ASC）。
5. **P1 — 多选删除**：长按进入选择态 → 顶栏计数 + 全选 → 确认对话框 → 事务内 `deleteByIds` + 删文件 → Snackbar 撤销（若采用软删除）。验收：删 200 张后列表与磁盘一致、无残留孤儿文件。
6. **P2 — 相册维度**：按日期/导入批次分组（sticky header），或按设备（`SyncDevice`）分「胶卷」。这才是用户口中的「相册管理」。
7. **P2 — 导出后删除的系统语义**：若「导出到相册」的照片也要能从网格删除，接入 `createTrashRequest`（优先，可恢复）+ 2000 上限分批。
8. **P2 — 入口与状态保持**：预览页右上角加「网格」入口；从网格点进预览时传 `photoId`，返回时恢复滚动位置与选择态（`rememberSaveable`）。

**建议先写测试**（项目已有 JUnit4）：
- `PhotoDao.deleteByIds` 的原子性与「文件删除失败但 DB 已删」的一致性策略。
- 选择态纯函数（全选/反选/删除后清理）用 `internal fun` + 单测。
- 缩略图分派函数 `resolveSizedThumbnailPath(path, targetEdge)` 的单测（RAW/JPEG 两分支）。

## 参考来源

| 标题 | URL | 发布者 | 发布时间 | 类型 |
|---|---|---|---|---|
| Access app-specific files | https://developer.android.com/training/data-storage/app-specific | Android Developers | 持续更新（2026-10 访问） | 官方 |
| Access media files from shared storage | https://developer.android.com/training/data-storage/shared/media | Android Developers | 持续更新 | 官方 |
| MediaStore（createDeleteRequest / createTrashRequest） | https://developer.android.com/reference/android/provider/MediaStore | Android Developers | 持续更新 | 官方 |
| Storage use cases and best practices | https://developer.android.com/training/data-storage/use-cases | Android Developers | 持续更新 | 官方 |
| Grant partial access to photos and videos | https://developer.android.com/about/versions/14/changes/partial-photo-video-access | Android Developers | 2026-03-03 | 官方 |
| Load and display paged data | https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data | Android Developers | 持续更新 | 官方 |
| Lazily load data with lists and Paging | https://developer.android.com/develop/ui/compose/quick-guides/content/lazily-load-list | Android Developers | 持续更新 | 官方 |
| LibRaw C++ API（dcraw_make_mem_thumb 等） | https://www.libraw.org/docs/API-CXX.html | LibRaw LLC | 持续更新 | 官方 |
| 项目代码：MainActivity / PhotoDao / AppDatabase / RawWrapper | 本地仓库 | 项目自身 | 2026-10-27 | 一手 |
