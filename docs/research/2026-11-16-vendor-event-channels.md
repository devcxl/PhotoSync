# Canon / Nikon / Sony 的事件通道在真实拍摄场景里有多可靠

**日期**: 2026-11-16
**状态**: 调研（对应 GitHub issue #7）

本文只回答“通道行为”，不做方案设计。所有结论尽量落在两类一手材料上：

1. 厂商文档（Canon EDSDK API Programming Reference、Sony Camera Remote SDK / Camera Remote Command 公开说明）；
2. 参考实现 libgphoto2 的源码（`gphoto/libgphoto2`，commit `31a2f40`，2026-11-13），
   以及仓库内由真实相机抓出的 `DeviceInfo` 转储（`camlibs/ptp2/cameras/*.txt`），
   后者是“相机自己声明的能力”，属于设备侧一手证据。

二手材料（GitHub issue、gphoto-devel 邮件列表、第三方实现）只用于旁证，凡是承重结论都已标注能否由一手材料直接支持。

---

## 1. 结论摘要

1. **“有新照片”这件事，三家走的根本不是同一种机制。**
   Canon EOS 是**主机拉取**（`EOS_GetEvent 0x9116`，无 USB 中断事件通道）；Nikon 也是**主机拉取**
   （`GetEvent 0x90C7` / `GetEventEx 0x941C`，中断端点只作兜底）；Sony 是**设备推送**
   （USB 中断端点上的 `0xC201`），但参考实现**不把它当作“文件已存在”**，而是轮询属性
   `0xD215 ObjectInMemory`。
2. **标准事件 `ObjectAdded 0x4002` / `ObjectRemoved 0x4003` 只有 Nikon 真正在用。**
   Sony ILCE-1 / A7SM3 的 `DeviceInfo` 事件表里**没有** 0x4002/0x4003，只有 `0x4004/0x4005` 与
   `0xC2xx` 厂商事件；部分 Canon EOS 机身（如 100D）**广告**了 0x4002/0x4003，但 libgphoto2
   明确写道“EOS 没有普通 PTP 事件”，实际数据走 `0x9116` 的私有事件流。
3. **延迟量级由轮询节奏决定，不由相机决定。** libgphoto2 的 EOS/Nikon 等待循环采用 50 ms 起、
   每次 +50 ms、上限 200 ms 的退避（`library.c:170`），因此“主机知道”的延迟通常是
   一个轮询周期 + 相机落盘时间；Sony 的事件本身几乎是即时的，但事件到达时对象**可能还不可读**。
4. **三家都存在实证的“事件不可用/会丢”的机型或模式**：Nikon 1 系列的 `0x90C7` 被参考实现直接禁用
   （`library.c:540-550`）；Sony 的 `0xC201` 若立刻去读对象“会让相机崩溃”（`library.c:7593-7596`）；
   Canon EOS 只在“同时存卡（或卡+PC）”的配置下才产生文件级新增事件（EDSDK §4.2.4）。
5. **只有 Canon 有明确的长连接心跳**：`EOS_KeepDeviceOn 0x911D`，约每 10 s 一次
   （`config.c:14550-14566`）；EDSDK 侧的等价物是 `kEdsStateEvent_WillSoonShutDown` →
   `kEdsCameraCommand_ExtendShutDownTimer`。Nikon、Sony 在参考实现里都没有空闲心跳。
6. **通道断掉之后没有自动恢复**：libgphoto2 里没有重连逻辑，USB 错误直接向上抛，调用方必须
   close + re-init；Sony 侧还有“相机 RAM 残留只有重新插拔 USB 才清”的注释（`library.c:5119-5121`）。

---

## 2. Canon EOS

### 2.1 谁投递“有新照片”

- **opcode**：`PTP_OC_CANON_EOS_GetEvent = 0x9116`，带 data phase，返回一段 TLV 事件块
  （`ptp.h:428`；`ptp.c:3760-3772` 的 `ptp_canon_eos_getevent`）。
- **事件码**（`ptp.h:1297-1344`，解码在 `ptp-pack.c:1989-2015`、`ptp-pack.c:2708-2710`）：
  - `0xC181 ObjectAddedEx`：句柄、StorageID、ObjectFormat、大小、父对象、**文件名内联**；
  - `0xC1A7 ObjectAddedEx64`、`0xC1B6 ObjectAddedEx64LFN`：同类事件的 64 位/LFN 变体；
  - `0xC186 RequestObjectTransfer`（`0xC1A9`/`0xC1B8` 为 64 位/LFN 变体）：相机要求主机取走某个对象；
  - `0xC182 ObjectRemoved`：删除。
- **谁发起**：**主机**。EOS 不使用 USB 中断事件端点（至少参考实现如此，Canon SDK 同样是拉模型）。
  libgphoto2 在通用事件检查里对 EOS 直接返回，并写明“should not get here ... EOS has no normal
  PTP events and another queue handling”
  （`ptp.c:3636-3643`）。Canon 自己的 SDK 也是拉模型：`EdsGetEvent()` 的文档是
  “In console application, please call this function regularly to acquire the event from a camera”
  （EDSDK 头文件 `EdsGetEvent` 注释）。
- **节奏**：`0x9116` 是**非阻塞**的，没事件时返回空块；参考实现的做法是“反复调用直到相机不再报告”
  （`ptp.c:3774-3788` 的 `ptp_check_eos_events`，注释原文 “call it repeatedly until the camera does not report any”）。
  因此间隔完全由客户端决定：libgphoto2 用 50→200 ms 退避（`library.c:170-186`），
  同时每轮都尝试 keepalive（见 2.3）。

### 2.2 PC 连接模式下，机身快门拍的照片会不会发事件

分两种情况，Canon 文档写得很直白：

- **`kEdsObjectEvent_DirItemCreated`（文件创建）**：EDSDK §4.2.4 原文
  “This event is generated **if the camera has been set to store captured images simultaneously on the
  camera and a computer**, for example, but **not if the camera is set to store images on the computer alone**。”
  → 也就是说：**取决于“存哪儿”这个配置**，不是无条件发送。
- **`kEdsObjectEvent_DirItemRequestTransfer`（要求传输）**：EDSDK §4.2.12 原文
  “This event is generated after remote release from a computer **or local release from a camera**。”
  → **机身快门（local release）确实会触发事件**，前提是这套配置下相机要把图交给主机。
- **模式与存储目标的耦合在参考实现里可见**：libgphoto2 会写 `PTP_DPC_CANON_CaptureTransferMode`
  （`CANON_TRANSFER_CARD` / `CANON_TRANSFER_MEMORY`，`ptp.h:2069-2070`，设置见 `library.c:4884-4898`），
  并读 `PTP_DPC_CANON_EOS_CaptureDestination`（`0xD11C`，`PTP_CANON_EOS_CAPTUREDEST_HD = 4`，`ptp.h:2136-2137`）；
  当图像只存在于 RAM 时，返回的对象没有父对象，路径被伪造成 `capt%04d.jpg`（`library.c:5030-5046`）。
- **格式差异**：`ObjectAddedEx` 的载荷里带 `ObjectFormat`，CR3/CRW 与 JPEG 走同一事件，只是格式码不同
  （`ptp-pack.c:1989-2015`）；RAW+JPEG 时是**两条事件**，参考实现的 capture 循环只取第一条，
  另一条留给后续 wait 循环（`library.c:4677-4679` 注释 “for RAW+JPG mode capture, we just return the first image ...”）。
- **UI 状态的影响**：EDSDK §2.7 定义 UI Lock；把 EVF 输出设为仅 PC 时“UILock will be set for the camera
  except for the SET button”（§5.2.65）。也就是说，若主机把相机锁进完全远程状态，机身快门本来就不可用于正常拍摄，
  “机身拍摄是否发事件”这个问题在该状态下不成立。

### 2.3 延迟与丢失

- **延迟**：一次 `0x9116` 往返 + 相机落盘。参考实现的等待循环里，事件从进入 `params->eos_events`
  到被业务取走最多跨一个退避周期（≤200 ms）。真正的大头是相机的写卡时间，参考实现对整个 capture
  给了 90 s 上限（`library.c:4598` 的 `EOS_CAPTURE_TIMEOUT (90*1000)`，用于 `library.c:4687`）。
- **设备侧有队列**：`ptp_check_eos_events` 的设计（“反复调用直到空”）本身说明相机内部缓冲多个事件，
  一次调用可能返回多条。事件块的解码是顺序 TLV，遇到 `size`/`datasize` 不一致会
  “canon eos event decoder ran over supplied data, skipping entries” 并 break
  （`ptp-pack.c:1926-1936`、`ptp-pack.c:1966-1974`），即畸形块会让后续事件在这轮里读不到。
- **会主动丢事件**：参考实现在每次触发拍摄前把已收集的 EOS 事件整批丢弃，
  日志是 “discarding %d EOS events”（`library.c:6235-6239`），目的是避免上一轮的
  `ObjectAdded` 被误当成本次结果。这是“事件流不排队到业务层”的直接证据。
- **需要 keepalive**：需要。`camera_keep_device_on()` 在 `EOS_KeepDeviceOn 0x911D` 可用时，
  距上次心跳超过 10 s 就发一次（`config.c:14550-14566`）。同一处注释还记录了一个实测事实：
  EOS R8 的自动关机最短可设 15 s，但“仍在 USB 连接状态下，即使完全没有流量也不会在几分钟内关机”
  （`config.c:14556-14559`）——即心跳的必要性随机型/固件而变。

### 2.4 恢复

- **机身休眠/自动关机**：EDSDK 会在关机前发 `kEdsStateEvent_WillSoonShutDown`，载荷是“还剩多少秒”，
  官方给出的应对是 `EdsSendCommand(..., kEdsCameraCommand_ExtendShutDownTimer, ...)`（§4.2.15、§3.1.14 表格；
  该命令的说明即 “Requests to extend the time for the auto shut-off timer. (Keep Device On)”）。
  真正的 `kEdsStateEvent_Shutdown` 意味着已经断了，此时无法从主机唤醒。
- **通道断掉后**：libgphoto2 没有重连逻辑，USB 侧错误直接变成 `PTP I/O Error` / `PTP Timeout`
  往上传；恢复手段是重新 `camera_init`（重新 OpenSession + 重新 `SetRemoteMode`/`SetEventMode`）。
  会话关闭还有一个机型坑：2016 年后的 EOS “do not like that and report 0x2005 on all following opcodes”，
  所以这些机型用 `DEVICE_FLAG_DONT_CLOSE_SESSION` 干脆不关会话（`library.c:3374-3377`、`device-flags.h:277-281`）。
- **换卡**：EDSDK 有 `kEdsObjectEvent_VolumeInfoChanged` / `VolumeUpdateItems`（§4.2.8、§4.2.9），
  但 libgphoto2 的 EOS 事件类型枚举里**没有** volume 类事件（`ptp.h:1892-1901`），
  其通用换卡处理只在标准 `0x4004/0x4005` 上生效（`ptp.c:3566-3579`，会重建 storageids 并清空对象缓存）。
  EOS 是否把换卡也塞进 `0xC1xx` 流里，参考实现没有建模。

### 2.5 是否也发标准 `0x4002` / `0x4003`，删除事件是否存在

- **广告了，但没有作为标准事件投递**。EOS 100D 的 `DeviceInfo` 明确列出
  `0x4002 ObjectAdded` / `0x4003 ObjectRemoved` / `0x4004` / `0x4005` / `0x4007` / `0x4009`
  （`camlibs/ptp2/cameras/canon-eos100d.txt:875-882`），但参考实现在 EOS 分支上根本不读中断端点
  （`ptp.c:3636-3643`），实际事件全部来自 `0x9116` 的 `0xC1xx`。
- **删除事件存在**：`0xC182 ObjectRemoved`（`ptp.h:1298`，解码 `ptp-pack.c:2708-2710`，
  业务处理 `library.c:4652-4656` / `library.c:7050-7056`），EDSDK 侧对应
  `kEdsObjectEvent_DirItemRemoved`（§4.2.5，“Deleted objects are indicated in event data”）。
- **主机侧删除**是否回吐事件：libgphoto2 用 `DEVICE_FLAG_DELETE_SENDS_EVENT` 表达这件事
  （`device-flags.h:212-217`），但该标志只在老 Canon PowerShot/IXUS 与少数 EOS 机型上设置，
  注释写明 “not on 350D”（`library.c:9441-9452`）。即 Canon 内部也不一致。

**置信度：高**（机制与模式依赖均有厂商文档 + 参考实现双向支持）。
**未能验证**：新机型（R 系列、R5/R6 II 之后）在“卡+PC 同时保存”下 `DirItemCreated` 与
`DirItemRequestTransfer` 到底发哪一个、是否两者都发；EDSDK 3.4（2016）之后的版本说明未公开可查，
不同固件的实际行为无法从公开材料确认。

---

## 3. Nikon

### 3.1 谁投递“有新照片”

- **opcode**：`PTP_OC_NIKON_GetEvent = 0x90C7`（无参数，data in，`ptp.h:588`）。
  新机身另有 `PTP_OC_NIKON_GetEventEx = 0x941C`，`ptp.h:637` 的注释是
  “can do multiparameter events, compared to GetEvent”。
- **返回格式**（`ptp-pack.c:2772-2795` 的 `ptp_unpack_Nikon_EC`）：`u16 count` + `count × {u16 code, u32 param1}`。
  即一次事务可以取回**一批**事件，设备侧有队列。
- **事件码**：标准 `0x4002 ObjectAdded`（参数 1 = 对象句柄）；
  厂商 `0xC101 ObjectAddedInSDRAM`（参数 1 = SDRAM 里的对象句柄，`ptp.h:1350`）、
  `0xC102 CaptureCompleteRecInSdram`（`ptp.h:1351`）、`0x400d CaptureComplete`。
- **谁发起**：**主机拉取**。libgphoto2 的 Nikon 分支优先走 `0x90C7`/`0x941C`，只有在
  “从来没拿到过事件”时才回退到 USB 中断端点（`ptp.c:3613-3636`，判据是 `params->event90c7works`）。
- **节奏**：同样非阻塞，空队列时返回 `count = 0`。等待循环节奏与 Canon 相同（50→200 ms 退避，`library.c:170-186`）。

### 3.2 PC 连接模式下，机身快门拍的照片会不会发事件

- **会，但有前提。** libgphoto2 的 Nikon 等待分支把 `0x4002` 直接映射成 `GP_EVENT_FILE_ADDED`
  （`library.c:7140-7160`），这条路径就是给“相机侧拍摄后主机收图”用的。
- **前提一：必须处在 PC 控制模式。** 参考实现在拍摄前调用
  `ptp_nikon_changecameramode(params, 1)`，`ptp.h:5014-5024` 对该命令的注释是
  “This command can switch the camera to full PC control mode”（调用点 `library.c:4328-4335`、
  `library.c:6445-6452`）。社区侧的表述与之一致（二手）：
  “put the camera in Application mode, but to get image events in Application mode you have to call
  GetEvent yourself”（libgphoto2 issue #980）。
- **前提二：`0x90C7` 在该机型上确实工作。** Nikon 1 系列（V1/J1/S1）被参考实现**主动禁用**该命令，
  日志 “On Nikon S1/J1/V1: disable NIKON_GetEvent as its unreliable”，并把对应 opcode 覆盖掉
  （`library.c:540-550`，关联 issue #569/#716/#845）。
- **前提三：文件已经写完。** `0x4002` 只说明句柄存在，`GetObjectInfo` 可能仍失败。
  Nikon 拍摄主循环里为此专门留了“记住句柄、等相机空闲后再加载”的分支
  （`library.c:6149-6170`，注释举 Leica Q3 43 为例：“never re-sends the ObjectAdded event”），
  另有一个 `tries = 20` 的空轮询窗口专门等 `ObjectAdded` 到达（`library.c:6130`）；
  而**等待循环**里一旦 `GetObjectInfo` 失败就直接 `break`，事件被消费掉且不重排（`library.c:7156-7157`）。
- **格式差异**：`0x4002` 对 NEF / JPEG 一视同仁，都靠 `GetObjectInfo` 里的 `ObjectFormat` 区分；
  目录也会通过 `0x4002` 报出来（`ObjectFormat == PTP_OFC_Association`），参考实现会忽略它并 reset 文件系统
  （`library.c:7181-7184`）。SDRAM 模式（`0xC101`）则只有句柄，文件名要靠 `GetObjectInfo` 取。

### 3.3 延迟与丢失

- **延迟**：一个 `0x90C7` 往返（camlib 侧通常是毫秒级）+ 相机写卡。等待循环 ≤200 ms 退避。
- **批量队列**：一次 `GetEvent` 可取回多条（`ptp-pack.c:2779` 读 `count`），说明设备侧缓冲多条事件；
  主机若不 drain，队列最终由固件决定是否覆盖，**公开材料没有给出队列深度**。
- **实证的丢失样例**：
  - `0x4002` 之后 `GetObjectInfo` 失败 → 等待循环直接丢弃该事件（`library.c:7156-7157`）；
  - SDRAM 抓拍时 `0xC101` 报的句柄可能尚不可读，社区日志里出现
    “we get told that the new object is there via the c101 event, but it does not seem to be there :/”
    （gphoto2 issue #164，二手）；
  - 一部分机型“收不到可用的 `ObjectAdded`”，参考实现用 `NIKON_BROKEN_CAP` 兜底：不靠事件，
    改比对 `GetObjectHandles` 前后差异，每轮 `sleep(1)`、最多 5 次（`library.c:6036,6074-6120`，
    标志定义 `device-flags.h:229-232`）。该标志挂在 27 个 Coolpix/KeyMission 机型上
    （如 `library.c:1461,1497,1567,1679,1705`），**DSLR 与 Z 系列没有挂**——即“完全不可靠”集中在
    旧消费级机身，专业机身用标准 `0x4002`。
- **需要 keepalive 吗**：参考实现里**没有空闲心跳**。`NIKON_DeviceReady 0x90C8`（`ptp.h:589`）只用于
  拍摄期的忙等（`nikon_wait_busy()`，`config.c:648-666`），例如 `usleep(2000)` × 100 次重试
  （`library.c:4403-4412`）。

### 3.4 恢复

- **机身休眠 / 自动关机**：Nikon 侧没有 EDSDK 那样的“即将关机”事件，参考实现也没有唤醒路径；
  断链表现为后续事务 I/O 错误。
- **通道断了之后**：同样只能 close + re-init。参考实现会在 init 阶段“补”上被固件隐藏的命令
  （`fixup_cached_deviceinfo`，`library.c:520-660`），这也是 Nikon 每次重连必须做的事。
- **换卡**：走标准 `0x4004/0x4005`，参考实现会重取 storageids 并清空对象缓存
  （`ptp.c:3566-3579`）。Nikon Z6/Z9 的 `DeviceInfo` 确实列出这两个事件
  （`cameras/nikon-z6.txt:399-414`、`cameras/nikon-z9.txt` 同段）。

### 3.5 是否也发标准 `0x4002` / `0x4003`，删除事件是否存在

- **是，Nikon 是三家唯一真正使用标准事件码的**：Z6 的 `DeviceInfo` 列出
  `0x4002 ObjectAdded`、`0x4003 ObjectRemoved`、`0x4004/0x4005`、`0x4006 DevicePropChanged`、
  `0x4007 ObjectInfoChanged`、`0x400d CaptureComplete`，外加 `0xC101`（`cameras/nikon-z6.txt:399-413`）。
- **删除**：标准 `0x4003` 存在，参考实现的通用路径会清缓存并 reset 文件系统（`ptp.c` 的 `PTP_EC_ObjectRemoved`
  分支、`library.c:7721-7726`）。
- **但主机主动删除后相机是否回吐 `0x4003`**：参考实现不期待——`DELETE_SENDS_EVENT` 标志**没有**设置在任何
  Nikon 机型上（`library.c` 相机表中 `Nikon:` 行与 `DELETE_SENDS_EVENT` 交集为 0）。

**置信度：中**。机制、事件码、机型差异都有一手代码/设备转储支撑；但“Nikon 官方规定的发送条件”
无公开文档（Nikon SDK 需 NDA），因此“哪些模式/固件会发、队列深度多少”只能从参考实现反推。
**未能验证**：Nikon 官方对 `0x90C7` 的事件投递保证；Z8/Z9 在“仅 PC 控制”模式下的实测行为；
BD/连拍时 `0x4002` 是否会合并或丢失。

---

## 4. Sony

### 4.1 谁投递“有新照片”

- **事件码**：`PTP_EC_Sony_ObjectAdded = 0xC201`、`PTP_EC_Sony_ObjectRemoved = 0xC202`、
  `PTP_EC_Sony_DevicePropChanged = 0xC203`、`PTP_EC_Sony_CapturedEvent = 0xC206`、
  `PTP_EC_Sony_ContentsTransferEvent = 0xC20D`（`ptp.h:1370-1379`）。
- **谁发起**：**设备推送**。Sony 没有厂商版 `GetEvent`；事件走 USB 中断 IN 端点。
  libgphoto2 的读法是 `gp_port_check_int`，超时 150 ms（Canon 为 300 ms）、失败再读一次
  （`usb.c:45-48,536-539`、`usb.c:530-566`）。libusb1 后端常驻 **10 个** 256 字节中断 URB 并自动重挂，
  已完成的事件进链表等 camlib 取（`libusb1.c:115-117`、`libusb1.c:752-810`）。
- **但参考实现不把它当“新照片”**：通用路径里 `0xC201` 的处理块被 `#if 0` 掉，注释是
  “We are handling this in the above Sony case. events might come to early (or too late ...)
  and retrieving the image will crash the camera”，随后 `break` 落到 `default:`
  变成 `GP_EVENT_UNKNOWN`（`library.c:7590-7600`、`library.c:7736-7739`）。
- **它实际依赖的信号是属性**：`PTP_DPC_SONY_ObjectInMemory = 0xD215`
  “used to signal when to retrieve new object”（`ptp.h:3581`）。
  等待循环每轮做 `0x9209 SDIO_GetAllExtDevicePropInfo`（`ptp.h:716`，注释说“约 4126 字节的 blob”）
  再读 `0xD215`，`>= 0x8000` 时认为有一张图在 RAM，句柄固定为 `0xFFFFC001`
  （`library.c:7258-7300`；host 抓拍路径同逻辑 `library.c:5207-5245`）。
- **节奏**：事件侧是设备推送（几乎即时），属性侧是主机轮询，每轮一次 4 KB 级传输，无显式 sleep。

### 4.2 PC 连接模式下，机身快门拍的照片会不会发事件

- **公开材料没有 Sony 对 `0xC201` 发送条件的说明**（Camera Remote Command 文档需企业注册）。
  能确认的是：
  - Sony 机身**广告**了 `0xC201/0xC202`（`cameras/sony-ilce1.txt:142-157`、`cameras/sony-a7sm3.txt` 同段），
    说明事件码在设备侧存在；
  - 参考实现在**主机触发**的快门循环里确实收到过 `0xC201`，并用它提前结束等待
    （`library.c:5215-5226`，注释 “needed on older cameras like the a58”）；
  - 参考实现在**等待机身拍摄**的场景里**完全不使用** `0xC201`，只用 `0xD215`
    （`library.c:7258-7300`）。`0xD215` 的语义是“相机 RAM 里待取的图”，
    对应的是抓拍到 RAM 的流程，不是“卡上新文件”。
- **Sony 自己把“拍完通知”当成后加的能力**：Camera Remote SDK v1.05.00（2021-11）才加入
  “Still Image Capture Complete Notification” 与 “Contents Transfer mode”；
  v1.10.00（2023-09）才加入“Event notification, such as video recording start/stop ...”
  （Sony Camera Remote SDK 版本说明页）。即在这之前，官方高阶 API 不提供拍摄完成事件。
- **模式差异**：Sony 进控制模式需要 `SDIO_ConnectDevice`（1、2 阶段）、退出用阶段 3，
  之后把 `PTP_DPC_SONY_PriorityMode (0xD25A)` 设为 `1 = Application`（`config.c:3146-3150`、`library.c:736-778`）。
  另有 `SDIO_SetContentsTransferMode (0x9212)` 与 `0xC20D ContentsTransferEvent`，
  但参考实现**只定义了常量、从未调用**（`ptp.h:719` 有定义，`library.c`/`config.c` 无引用），
  这条“内容传输模式”路径在参考实现里完全没有覆盖。

### 4.3 延迟与丢失

- **延迟**：事件本身几乎即时；但“事件到了 ≠ 图能读”。参考实现的注释写明事件可能“太早或太晚”，
  且“太早去取图会让相机崩溃”（`library.c:7593-7596`）；`0xD215` 的注释还补了一句
  “This variable also turns to 1, but downloading then will crash the firmware, we seem to need to wait for 0x8000”
  （`library.c:5233-5236`）。
- **丢失/错配的具体形态**：上一次抓拍失败会在相机 RAM 里留下残图，下次拍摄时“会立刻被当成可用对象”，
  导致抓到的是**上一张**（`library.c:5113-5121`）。参考实现因此每次拍摄前先清理 RAM：
  `0xD215 >= 0x8000` 时把 `0xFFFFC001` 读掉丢弃，且明确指出
  “Camera on-off does not delete RAM. Just USB reconnection helps”。
- **队列**：Sony 侧没有“批量取事件”的事务（不像 Nikon 的 count 数组、Canon 的 TLV 块），
  事件只经中断端点逐条送达；主机侧缓冲由 libusb 的 10 个 URB（每个 256 B）+ 链表承担
  （`libusb1.c:115-117`、`libusb1.c:830-860`）。设备侧缓存深度无公开数据。
- **需要 keepalive 吗**：参考实现里**没有**。Sony 路径的“保活”靠持续的属性/命令流量
  （`0x9209` blob 拉取本身就是流量）。

### 4.4 恢复

- **连接初期不稳定**：ZV-1 / RX100M7 / A7R4 / ILCE-7M3 等机型连接后需要约 3 s 才能拍摄，
  参考实现的做法是“等满 3 s，期间排空事件并拉属性”，并注明“looked for various trigger events or
  property changes on the ZV-1 but nothing worked except waiting”（`library.c:5066-5099`）。
- **模式切换**：`SDIO_ConnectDevice` 的 1/2/3 阶段是一次成对操作，断链后必须重新走一遍；
  参考实现没有重连逻辑。
- **换卡**：Sony 只广告 `0x4004/0x4005`（`cameras/sony-ilce1.txt:143-144`），
  参考实现的通用处理会清空 storageids 与对象缓存（`ptp.c:3566-3579`）。
  但 `camera_wait_for_event` 的 Sony 分支并不消费 store 事件（它只盯 `0xD215`），
  换卡后的可见性实际由文件系统层重置决定。
- **断链后的 RAM 残留**：见上，“只有重新插拔 USB 才清”（`library.c:5119-5121`）。

### 4.5 是否也发标准 `0x4002` / `0x4003`，删除事件是否存在

- **不发标准事件**。ILCE-1 与 A7SM3 的 `DeviceInfo` 事件表里只有
  `0x4004 StoreAdded`、`0x4005 StoreRemoved` 与 `0xC201…0xC20D`
  （`cameras/sony-ilce1.txt:142-157`、`cameras/sony-a7sm3.txt` 同段）——**没有 0x4002/0x4003**。
- **删除事件存在但是厂商码**：`0xC202 Sony_ObjectRemoved`（`ptp.h:1371`）。
  参考实现同样**没有**把它映射成 `ObjectRemoved`：`handleregular` 里只对 Panasonic 做了码映射，
  Sony 只映射了 `0xC203 → DevicePropChanged`（`library.c:7583-7604`），因此 `0xC202` 也落到
  `default:` → `GP_EVENT_UNKNOWN`。
- **厂商侧的删除能力时间线**：Camera Remote SDK v2.00.00（2025-06-23）才加入
  “Support for deleting data stored on the camera”，且限定机型（ILCE-7CR/7SM3/7M4/7CM2/6700 等）。
  在这之前官方高阶 API 没有删除能力。

**置信度：低—中**。事件码、端点、参考实现的行为都是高置信度（代码 + 设备转储）；
但“机身快门 + PC Remote 连接时 `0xC201` 是否必发、发了是否可立即读”**没有**可引用的一手材料，
参考实现的选择（绕开 `0xC201`）本身也说明它不被信任。
**未能验证**：Sony Camera Remote Command 文档里的 `0xC201` 发送条件与 `0xC20D` 语义；
`0xC202` 在机身删除/格式化/换卡时的实际触发；不同固件版本对 `0xD215` 语义的差异。

---

## 5. 横评：延迟、丢失、恢复

| 维度 | Canon EOS | Nikon | Sony（ILCE/SDIO） |
|---|---|---|---|
| “新照片”信号 | 主机拉 `0x9116`，取 `0xC181`/`0xC1A7`/`0xC1B6`（ObjectAddedEx，**内联文件名**）或 `0xC186`（要求取图） | 主机拉 `0x90C7`/`0x941C`，取 `0x4002`（句柄）或 `0xC101`（SDRAM 句柄） | 设备推 `0xC201`（USB INT EP）；参考实现改用属性 `0xD215` |
| 谁发起 / 端点 | 纯主机拉取；EOS **不用**中断事件端点（`ptp.c:3636-3643`） | 主机拉取优先；拿不到事件才回退中断端点（`ptp.c:3613-3636`） | 设备推送；主机读 INT EP，150 ms 超时 ×2（`usb.c:48`、`usb.c:545-556`） |
| 典型学习延迟 | 1 个轮询周期（50→200 ms 退避）+ 写卡（`library.c:170`） | 同上 + 抓到句柄后可能需等文件写完再加载（`library.c:6149-6170`） | 事件几乎即时，但对象可能尚不可读；可用信号是属性轮询周期 |
| 丢事件风险 | 有：不 drain 会积压；解码遇畸形块会丢弃剩余条目（`ptp-pack.c:1932`）；每次 capture 前主动丢弃旧事件（`library.c:6235`） | 有：`0x4002` 后 `GetObjectInfo` 失败即丢（`library.c:7156`）；Nikon 1 的 `0x90C7` 被禁用（`library.c:540`） | 有：事件早于对象可读，强读会崩（`library.c:7593`、`5233`）；RAM 残图会顶替新图（`library.c:5113`） |
| 需要 keepalive | **需要**：`0x911D EOS_KeepDeviceOn`，约 10 s（`config.c:14550`）；SDK 侧 `ExtendShutDownTimer` | 无空闲心跳；`0x90C8 DeviceReady` 只用于拍摄忙等（`config.c:648`） | 无；仅靠持续属性/命令流量 |
| 事件丢失后如何恢复 | 排空 `0x9116` 队列重取；通道断则 close+init；2016 后机型不要 CloseSession | 排空 `0x90C7`；通道断则 close+init；init 时需重做被隐藏命令的补全 | 重新拉 `0x9209` 全量属性重算 `0xD215`；RAM 残留只有**重新插拔 USB** 才清 |
| 标准 `0x4002`/`0x4003` | 广告但不投递（`canon-eos100d.txt:875-882` vs `ptp.c:3636`） | **使用**（`nikon-z6.txt:399-413`） | **不广告**（`sony-ilce1.txt:142-157`） |
| 删除事件 | `0xC182` → `PTP_EOSEvent_ObjectRemoved`（`library.c:7050`） | `0x4003`（`library.c:7721`） | `0xC202`，参考实现只当 UNKNOWN（`library.c:7590-7604`） |

---

## 6. 对 PhotoSync 的含义

先说清现状（本仓库事实，不是推测；下列路径前缀均为
`app/src/main/java/cn/devcxl/photosync/ptp/`）：

- 默认走 `POLL_LIST`，**每 2 s 全量 `GetObjectHandles`**
  （`usbcamera/BaselineInitiator.kt:33`、`:1139-1176`）；
- Canon EOS 与 Nikon 已有 `EVENT` 实现：EOS 每 200 ms 读 `0xC181`
  （`usbcamera/eos/EosInitiator.kt:363-408`，另有 `SetRemoteMode`/`SetEventMode`），
  Nikon 读 `0x90C7` 并对不支持的机型回退到列表轮询（`usbcamera/nikon/NikonInitiator.kt:129-175`）；
- 连接时默认设 `POLL_LIST`，然后对 Sony 单独改成 `EVENT`（`manager/UsbPtpConnectionController.kt:284-287`），
  而 Sony 的 `EVENT` 路径实际轮询的是 `0xD215`
  （`usbcamera/sony/SonyInitiator.kt:85-113` + `usbcamera/BaselineInitiator.kt:1102-1128`）。

按厂商，事件通道相对“2 s 全量轮询”能带来什么、又会引入什么风险（只列事实）：

**Canon EOS**
- 能拿到：`0xC181` 的载荷里内联 filename/size/storage/parent（`ptp-pack.c:1989-2015`），
  因此知道“新图叫什么”不必先做全量枚举；参考实现出于一致性仍会重取一次 `GetObjectInfo`
  （`library.c:4661-4663` 注释）。事件流同时携带属性变更，可顺带更新相机设置
  （`PTP_EOSEvent_PropertyChanged`，`ptp.h:1893`）。
- 风险：只在“存卡 / 卡+PC”配置下才有文件级新增（EDSDK §4.2.4）；RAM 模式下的图不是文件
  （`library.c:5030-5046`），事件表现为 `0xC186` 要求取图；必须持续 pump `0x9116`，
  否则设备队列积压、且每次 capture 前的“discard”逻辑会主动丢掉旧事件（`library.c:6235-6239`）；
  空闲需要 10 s 心跳，否则依赖机身自动关机设置（`config.c:14556-14559`）。

**Nikon**
- 能拿到：`0x4002` 直接给句柄，一次事务可带多条事件（`ptp-pack.c:2779`），
  省掉 O(n) 的句柄全量拉取；SDRAM 抓拍另有 `0xC101`。
- 风险：Nikon 1 系列 `0x90C7` 不可用（`library.c:540-550`），必须保留列表兜底；
  事件到达时对象可能还没写完，参考实现里“等待循环”会**直接丢弃**这类事件（`library.c:7156-7157`），
  即事件模式并不等于“必达”；换卡仍需重建对象缓存（`ptp.c:3566`）。

**Sony**
- 能拿到：`0xC201/0xC202` 是唯一的新增/删除事件源（标准 `0x4003` 根本不存在），
  理论上可以做到“不轮询整棵树”。
- 风险：事件不保证对象可读，参考实现因为“读会崩”而整段 `#if 0`（`library.c:7593-7596`）；
  `0xC202` 未被映射（`library.c:7604`）；`0xD215` 只覆盖“抓到 RAM”的图，不覆盖机身拍到卡的图；
  连接后约 3 s 不稳定（`library.c:5066-5099`）；RAM 残图会顶替新图且只有重插 USB 才清
  （`library.c:5113-5121`）。

---

## 7. 引用清单

### 7.1 厂商一手文档

1. Canon Inc., *EDSDK API Programming Reference*（EDSDK 3.4，2016-04-01），公开副本：
   <https://downloads.triprism.com/public/%5B%20Steve%20%5D/forTyler/canon%203.4sdk/EDSDK_API%203.4.pdf>
   - §2.7 Camera Status（UI Lock / Direct Transfer）
   - §3.1.14 `EdsSendCommand`（`kEdsCameraCommand_ExtendShutDownTimer` = Keep Device On）
   - §4.2.4 `kEdsObjectEvent_DirItemCreated`（“store ... simultaneously on the camera and a computer ... not if ... on the computer alone”）
   - §4.2.5 `kEdsObjectEvent_DirItemRemoved`
   - §4.2.8/4.2.9 `VolumeInfoChanged` / `VolumeUpdateItems`
   - §4.2.12 `kEdsObjectEvent_DirItemRequestTransfer`（“remote release ... or local release from a camera”）
   - §4.2.15 `kEdsStateEvent_WillSoonShutDown`
   - §5.2.59 `kEdsPropID_SaveTo`、§5.2.65 `kEdsPropID_Evf_OutputDevice`
   - `EdsGetEvent()` 注释（函数原型见公开头文件 `EDSDK.h`；3.4 版本文档正文未收录该函数说明）
2. Sony Corporation, *Camera Remote Command*（PTP 扩展，需企业注册；页面公开部分可查）：
   <https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html>
3. Sony Corporation, *Camera Remote SDK*（版本说明页，用于事件/删除能力的时间线）：
   <https://support.d-imaging.sony.co.jp/app/sdk/en/index.html>
4. Sony 官方致 libgphoto2 维护者的说明（PTP 低层命令公开、Camera Remote Command 渠道）：
   `camlibs/ptp2/README.Sony`（libgphoto2 仓库内文件）

### 7.2 参考实现（libgphoto2 @ `31a2f4089f19aec6caca7920b03e3eb7a251c5b6`，2026-11-13）

permalink 前缀：`https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/`

| 位置 | 内容 |
|---|---|
| `camlibs/ptp2/ptp.h:428` | `PTP_OC_CANON_EOS_GetEvent 0x9116` |
| `camlibs/ptp2/ptp.h:441` | `PTP_OC_CANON_EOS_KeepDeviceOn 0x911D` |
| `camlibs/ptp2/ptp.h:424,426` | `SetRemoteMode 0x9114` / `SetEventMode 0x9115` |
| `camlibs/ptp2/ptp.h:1297-1344` | `0xC181 ObjectAddedEx`、`0xC182 ObjectRemoved`、`0xC186 RequestObjectTransfer`、64/LFN 变体 |
| `camlibs/ptp2/ptp.h:588-589,637` | Nikon `GetEvent 0x90C7`、`DeviceReady 0x90C8`、`GetEventEx 0x941C` |
| `camlibs/ptp2/ptp.h:1350-1352` | `0xC101 ObjectAddedInSDRAM`、`0xC102 CaptureCompleteRecInSdram` |
| `camlibs/ptp2/ptp.h:5014-5024` | `ptp_nikon_changecameramode` 注释 “switch the camera to full PC control mode” |
| `camlibs/ptp2/ptp.h:1370-1371` | `0xC201 Sony_ObjectAdded`、`0xC202 Sony_ObjectRemoved` |
| `camlibs/ptp2/ptp.h:3581` | `PTP_DPC_SONY_ObjectInMemory 0xD215` “used to signal when to retrieve new object” |
| `camlibs/ptp2/ptp.h:714,716,719` | `SDIO_ControlDevice 0x9207`、`SDIO_GetAllExtDevicePropInfo 0x9209`、`SDIO_SetContentsTransferMode 0x9212` |
| `camlibs/ptp2/ptp.c:3613-3643` | `ptp_check_event`：Nikon 拉取分支、EOS “no normal PTP events” 早退 |
| `camlibs/ptp2/ptp.c:3760-3788` | `ptp_canon_eos_getevent` / `ptp_check_eos_events`（“call it repeatedly until ... any”） |
| `camlibs/ptp2/ptp.c:4487-4580` | `_ptp_sony_getalldevicepropdesc`（`0x9209` 全量属性 blob） |
| `camlibs/ptp2/ptp.c:3566-3579` | `StoreAdded/StoreRemoved` → 重取 storageids + 清空对象缓存 |
| `camlibs/ptp2/ptp-pack.c:1989-2015` | `0xC181` 载荷解码（handle/storage/OFC/size/parent/filename） |
| `camlibs/ptp2/ptp-pack.c:2708-2710` | `0xC182` → `PTP_EOSEvent_ObjectRemoved` |
| `camlibs/ptp2/ptp-pack.c:2772-2795` | Nikon `GetEvent` 批量格式（`u16 count` + N×`{u16,u32}`） |
| `camlibs/ptp2/library.c:170-186` | `waiting_for_timeout`：50 ms 起、+50 ms、上限 200 ms |
| `camlibs/ptp2/library.c:540-550` | Nikon S1/J1/V1 “disable NIKON_GetEvent as its unreliable” |
| `camlibs/ptp2/library.c:733-778` | Sony SDIO vendor prop/event 合并、`PriorityMode=1 (Application)` |
| `camlibs/ptp2/library.c:4328-4335` | Nikon 拍摄前 `ChangeCameraMode(1)` |
| `camlibs/ptp2/library.c:4451-4490` | Nikon 抓拍循环等 `0xC101` / `0x4002` |
| `camlibs/ptp2/library.c:4598,4626-4687` | `EOS_CAPTURE_TIMEOUT 90s` 与 EOS 抓拍循环：`check_eos_events` + `keep_device_on` |
| `camlibs/ptp2/library.c:4884-4898` | Canon `CaptureTransferMode`（CARD / MEMORY） |
| `camlibs/ptp2/library.c:5066-5099` | Sony 连接后 ~3 s 等待 |
| `camlibs/ptp2/library.c:5113-5121` | Sony RAM 残留：“Just USB reconnection helps” |
| `camlibs/ptp2/library.c:5207-5245` | Sony 抓拍：`0xC201` + `0xD215` 双判据、“downloading then will crash the firmware” |
| `camlibs/ptp2/library.c:6074-6120` | Nikon `NIKON_BROKEN_CAP`：不靠事件，比对 `GetObjectHandles`（每轮 `sleep(1)`×5） |
| `camlibs/ptp2/library.c:6130,6149-6170` | Nikon `tries = 20` 空轮询窗口；`GetObjectInfo` 失败时“记住句柄延后加载” |
| `camlibs/ptp2/library.c:6235-6239` | EOS 每次 capture 前 “discarding %d EOS events” |
| `camlibs/ptp2/library.c:6856-6863` | EOS 等待循环：`keep_device_on` + `check_eos_events` |
| `camlibs/ptp2/library.c:7140-7160,7181-7184` | Nikon 等待循环 `0x4002` → FILE_ADDED；`GetObjectInfo` 失败即 `break`；目录事件被忽略 |
| `camlibs/ptp2/library.c:7258-7300` | Sony 等待循环：只认 `0xD215` |
| `camlibs/ptp2/library.c:7583-7604` | Sony 事件映射：`0xC201` 被 `#if 0` 掉、`0xC203` → DevicePropChanged |
| `camlibs/ptp2/library.c:7736-7739` | 未匹配事件 → `GP_EVENT_UNKNOWN` |
| `camlibs/ptp2/library.c:9441-9452` | Canon 删除事件差异（“not on 350D”） |
| `camlibs/ptp2/config.c:648-666` | `nikon_wait_busy` / `DeviceReady` 忙等 |
| `camlibs/ptp2/config.c:3146-3150` | Sony `PriorityMode`：0=Camera, 1=Application |
| `camlibs/ptp2/config.c:14550-14566` | `camera_keep_device_on`：约 10 s 一次 `0x911D`；EOS R8 自动关机观察 |
| `camlibs/ptp2/device-flags.h:212-217,229-232,277-281` | `DELETE_SENDS_EVENT`、`NIKON_BROKEN_CAPTURE`、`DONT_CLOSE_SESSION` |
| `camlibs/ptp2/usb.c:45-48,536-539,530-566` | 中断事件读：150 ms（Canon 300 ms）、失败重读一次 |
| `libgphoto2_port/libusb1/libusb1.c:115-117,752-810` | 10 个常驻中断 URB、256 B 缓冲、自动重挂 |
| `camlibs/ptp2/cameras/canon-eos100d.txt:875-882` | EOS 100D 广告的事件：`0x4002/0x4003/0x4004/0x4005/0x4007/0x4009/0xC101` |
| `camlibs/ptp2/cameras/nikon-z6.txt:399-413` | Nikon Z6 广告的事件（含 `0x4002`、`0x4003`、`0xC101`） |
| `camlibs/ptp2/cameras/sony-ilce1.txt:142-157` | Sony ILCE-1 广告的事件（`0x4004/0x4005` + `0xC201…0xC20D`，**无** `0x4002`） |
| `camlibs/ptp2/cameras/sony-a7sm3.txt` | 同段，事件表与 ILCE-1 一致 |

### 7.3 二手材料（仅旁证，未用于承重结论）

1. libgphoto2 issue #569 / #716 / #845 —— Nikon 1（V1/J1/S1）`0x90C7` 不可用、capture 失败。
   <https://github.com/gphoto/libgphoto2/issues/569>、<https://github.com/gphoto/libgphoto2/issues/716>
2. libgphoto2 issue #980 —— “put the camera in Application mode, but to get image events in
   Application mode you have to call GetEvent yourself”。
   <https://github.com/gphoto/libgphoto2/issues/980>
3. gphoto2 issue #164 —— Nikon D5500：收到 `0xC101` 但 `GetObjectInfo` 报 InvalidObjectHandle。
   <https://github.com/gphoto/gphoto2/issues/164>
4. gphoto-devel 邮件列表（2015-10-15）—— Sony 抓包中出现
   `event: nparams=0x1, code=0xC202, p1=0xFFFFC001`，说明 `0xC202` 在实机上确实出现。
   <https://sourceforge.net/p/gphoto/mailman/message/34542659/>
5. digiCamControl `CameraControl.Devices/Nikon/NikonBase.cs` —— 独立实现同样定时调用
   `0x90C7` 并解析 `0x4002`/`0xC101`（旁证“Nikon 事件是主机拉取”）。
   <https://github.com/dukus/digiCamControl>
6. StackOverflow（Canon EDSDK 控制台程序需要调用 `EdsGetEvent()` 才会收到回调）——旁证拉模型。
   <https://stackoverflow.com/questions/13669312/>

### 7.4 本仓库内被引用的事实

| 位置 | 内容 |
|---|---|
| `app/src/main/java/cn/devcxl/photosync/ptp/usbcamera/BaselineInitiator.kt:33` | `POLL_LIST_INTERVAL_MS = 2000L` |
| `app/src/main/java/cn/devcxl/photosync/ptp/usbcamera/BaselineInitiator.kt:1102-1128` | EVENT 模式主循环（`waitVendorSpecifiedFileReadySignal`） |
| `app/src/main/java/cn/devcxl/photosync/ptp/usbcamera/BaselineInitiator.kt:1139-1176` | POLL_LIST 主循环（每 2 s 全量 `GetObjectHandles`） |
| `app/src/main/java/cn/devcxl/photosync/ptp/usbcamera/eos/EosInitiator.kt:363-408` | EOS：`SetRemoteMode`/`SetEventMode` + 200 ms `checkEvents`，认 `0xC181` |
| `app/src/main/java/cn/devcxl/photosync/ptp/usbcamera/nikon/NikonInitiator.kt:129-175` | Nikon：`0x90C7` 轮询，不支持时回退列表轮询 |
| `app/src/main/java/cn/devcxl/photosync/ptp/usbcamera/sony/SonyInitiator.kt:85-113` | Sony：轮询 `0xD215`（15 s 预算），>0x8000 时取 `0xFFFFC001` |
| `app/src/main/java/cn/devcxl/photosync/ptp/manager/UsbPtpConnectionController.kt:284-287` | 连接时默认 `POLL_LIST`，Sony 强制 `EVENT` |
