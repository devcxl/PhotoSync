# PTP 删除对象与删除事件：能否双向联动

**日期**: 2026-11-16
**状态**: 调研

> 范围声明：本文只回答「能不能做、有什么约束」，不设计功能。所有结论标注置信度，
> 无法验证的部分单独列出。代码证据统一取自 libgphoto2 提交
> `31a2f4089f19aec6caca7920b03e3eb7a251c5b6`（2026-11-13，`camlibs/ptp2/`），
> 引用格式为 `文件:行号`，仓库内代码引用格式为 `路径:行号`。

---

## 1. PTP 标准层

### 1.1 `DeleteObject` 的操作码与参数

标准文本（PIMA 15740:2000，PTP 1.0 的内容基准，后来成为 ISO 15740:2005）§10.4.11：

```
OperationCode:        0x100B
Operation Parameter1: ObjectHandle
Operation Parameter2: [ObjectFormatCode]
Operation Parameter3: None
Data:                 None
Data Direction:       N/A
```

参数只有 **两个**，第三个及以后不用。第二个参数的语义在标准里写得很死：

- 它**只在第一个参数为 `0xFFFFFFFF`（删除全部）时才有意义**，用于按格式过滤；
- 若第二个参数也是 `0xFFFFFFFF`，表示「只删图像对象」；
- **未使用时必须填 `0x00000000`**；
- 若值非 0 而 Responder 不支持按 `ObjectFormatCode` 指定，应返回
  `Specification_By_Format_Unsupported`（0x2014）。

（来源：PIMA 15740:2000 §10.4.11，PDF p.55–56）

删除单个对象时的通行做法因此是 `(handle, 0)`。libgphoto2 完全照此实现：

```c
/* ptp.c:2619 */
ptp_deleteobject (PTPParams* params, uint32_t handle, uint32_t ofc)
{
	PTPContainer ptp;
	PTP_CNT_INIT(ptp, PTP_OC_DeleteObject, handle, ofc);
```

调用方一律传 0：`C_PTP (ptp_deleteobject(params, handle, 0));`（`library.c:9437`）。

**关于 PTP 1.1 与 1.0 的差异**：PTP 1.1 对应 ISO 15740:2008（第二版），
其修订版 ISO 15740:2013（第三版，部分资料称 PTP 1.11，标准正文仍自称 PTP v1.1）。
我**没能取得 1.1 版 §DeleteObject 的条款原文**（ISO 15740:2013 正文付费，官方仅提供
前言与目录预览）。可以确认的是 1.1 仍然保留 `0x100B` 与两参数形态，且 1.1 明确保留
「参数存在但本操作不使用它」这一语义：

> 11.3.6 Parameter not supported — ResponseCode: 0x2006 … indicates that a **non-zero
> parameter** was specified in conjunction with the operation, **and that that parameter is
> not used for that operation**.

（来源：ISO 15740:2013 §11.3.6，见官方预览样本 PDF）

也就是说：在 1.1 下，若机身不实现「按格式过滤」，主机传非 0 的第二参数会拿到
`0x2006`；传 0 则不受影响。这与 1.0 的行为相容，**没有找到 1.1 引入语义变更的证据**，
但也不能断言 1.1 原条款一字未改。

**置信度**：1.0 参数语义 高（原文）；1.1 是否改动 中；「1.1 条款原文」**未能验证**。

### 1.2 响应码

1.0 §10.4.11 列出的 ResponseCode 选项：

```
OK, Operation_Not_Supported, Session_Not_Open, Invalid_TransactionID,
Invalid_ObjectHandle, Object_WriteProtected, Store_Read_Only, Partial_Deletion,
Store_Not_Available, Specification_By_Format_Unsupported, Invalid_Code_Format,
Device_Busy, Parameter_Not_Supported
```

对应的实际数字（1.0 §11.2）：`0x2001` OK、`0x2005` Operation_Not_Supported、
`0x2003` Session_Not_Open、`0x2009` Invalid_ObjectHandle、`0x200D`
Object_Write_Protected、`0x200E` Store_Read_Only、`0x2012` Partial_Deletion、
`0x2013` Store_Not_Available、`0x2014` Specification_By_Format_Unsupported、
`0x200F` Access_Denied、`0x2019` Device_Busy。

其中两个对 UI 很重要：

- `0x2012 Partial_Deletion`：请求删除全部，但部分对象被写保护，只有未保护对象被删；
- `0x200D Object_Write_Protected`：删除**单个**写保护对象时返回。

（来源：PIMA 15740:2000 §10.4.11 / §11.3.x）

**置信度**：高（规范原文）。

### 1.3 是强制还是可选：主机如何发现

**`DeleteObject` 是可选操作**。1.0 的「Operation Implementation Conformance」表
（Table 35）里 `GetDeviceInfo/OpenSession/CloseSession/GetStorageIDs/GetStorageInfo`
标注了 Mandatory `X`，而 `0x100B DeleteObject` 这一行**两列都是空的**：

```
0x1001 GetDeviceInfo   X  X  X
...
0x100B DeleteObject
```

（来源：PIMA 15740:2000 Table 35，PDF p.106）

主机通过 DeviceInfo 数据集的 `OperationsSupported`（字段 6）与 `EventsSupported`
（字段 7）得知当前 `FunctionalMode` 下设备支持哪些操作与事件：

> OperationsSupported: This field is an array of OperationCodes representing operations
> that the device is **currently supporting, given the FunctionalMode indicated**.
> EventsSupported: … events that are **currently generated** by the device …

（来源：PIMA 15740:2000 §5.5.2 Table 6 与字段说明，PDF p.15–16）

实践含义有两条，都容易被忽略：

1. 能力表是**随功能模式变化**的。同一台机身切到「PC 连接 / PC Remote / MTP」不同模式，
   `OperationsSupported` 会不同。所以能力必须在**每次连接后重新读**，不能缓存到下次。
2. 不支持时主机拿到的不是「静默失败」，而是 `0x2005 Operation_Not_Supported`
   （ISO 15740:2013 §11.3.5："This error should not normally occur, as the initiator should only invoke operations that the responder indicated were supported in its DeviceInfo data set."）。

libgphoto2 就是这个模型：删除入口先查能力表，不支持直接返回
`GP_ERROR_NOT_SUPPORTED`（`library.c:9430`、`library.c:9469`）；`--summary` 里那一行
「File Deletion / No File Deletion」也直接由这个查询产生（`library.c:8038-8042`）。

**置信度**：高（规范 + 参考实现一致）。**未能验证**：各具体机型在不同 USB 模式下的
`OperationsSupported` 原文（需要实机抓 `GetDeviceInfo`）。

### 1.4 「对象消失」的标准事件：`ObjectRemoved` 0x4003

```
EventCode: 0x4003
Parameter1: ObjectHandle
Parameter2: None
Parameter3: None
```

关键在于它的**触发条件**，规范原文：

> A data object was removed from the device **unexpectedly due to something external to
> the current session**. … If the data object that was removed was removed **because of a
> previous operation that is a part of this session, no event needs to be sent** to the
> opposing device.

（来源：PIMA 15740:2000 §12.5.3，PDF p.82）

这句话直接回答「主机自己删的要不要发事件」：**规范说不需要**。也就是说：

- 机身侧（外部）删除 → 应发 `ObjectRemoved`；
- 主机 `DeleteObject` 成功 → 不要求发，收到反而要当成实现瑕疵。

参考实现正是这样处理的：libgphoto2 把「`DeleteObject` 之后还发 `ObjectRemoved`」登记为
**机型级 bug 标志**，而不是正常行为：

```c
/* device-flags.h:216 */
#define DEVICE_FLAG_DELETE_SENDS_EVENT	0x00020000
/* ptp-bugs.h:27-38 */
#define PTPBUG_DELETE_SENDS_EVENT	DEVICE_FLAG_DELETE_SENDS_EVENT
#define DELETE_SENDS_EVENT(x) ((x)->device_flags & (DEVICE_FLAG_DELETE_SENDS_EVENT))
```

```c
/* library.c:9441（delete_file_func 内，删除成功之后） */
/* On some Canon firmwares, a DeleteObject causes a ObjectRemoved event
 * to be sent. At least on Digital IXUS II and PowerShot A85. But
 * not on 350D. */
if (DELETE_SENDS_EVENT(params) && ptp_event_issupported(params, PTP_EC_ObjectRemoved)) {
```

该标志只挂在部分 Canon 机型上（`library.c:2050` 起的一批，含 EOS 10D/450D/40D/1000D/
5D Mark II/7D/50D/5D Mark III/4000D 等）。**「删除会回一个事件」是例外，不是常态。**

另一个约束是 `ObjectRemoved` 的**条件强制性**（Table 36）：

> 0x4003 ObjectRemoved — Required? **Only if objects can be removed by external sources
> during session**

（来源：PIMA 15740:2000 Table 36，PDF p.107）

即：如果机身不打算允许「会话期间被外部改存储」，它就**没有义务**发这个事件。

**置信度**：高（规范原文 + 参考实现行为一致）。

### 1.5 会话状态、存储、写保护与 RAW+JPEG

**会话**：除少数明确说明的操作外，调用任何操作前都必须已 `OpenSession`（§10.4.2
Description：「Unless otherwise specified, an open session is required to invoke an
operation」）。会话不是强独占的——规范允许设备支持多会话，只要求「不支持多会话时，
重复 OpenSession 返回 `Session_Already_Open` 并带回已开会话的 SessionID」（§10.4.2），
同一会话内**同时只能有一个事务**（§9.3）。

**存储**：`DeleteObject` **不带 StorageID 参数**，只有 ObjectHandle。存储的可删性由
`StorageInfo.AccessCapability` 表达：

```
0x0000 Read-Write
0x0001 Read-Only without Object Deletion
0x0002 Read-Only with Object Deletion
```

**写保护**：`ObjectInfo.ProtectionStatus`（0x0000 No Protection / 0x0001 Read-Only），
且规范把两者绑在一起：

> If an attempt to delete an object is made, success will only occur if the
> **ProtectionStatus of the object is 0x0000** and the **AccessCapability of the store
> allows deletion**.

（来源：PIMA 15740:2000 §5.5.2 ProtectionStatus / §5.5.3 AccessCapability，PDF p.21–22）

所以只读卡有两种命运：`AccessCapability = 0x0001` 时删除必然失败
（`Store_Read_Only`/`Access_Denied`）；`0x0002`（只读但允许删）时**应当成功**——§10.4.11
Description 原文："If the store is read-only with object deletion, this operation should
succeed unless other factors prevent it from succeeding."。

**RAW+JPEG**：PTP 的对象模型里**一个文件就是一个对象、一个 ObjectHandle**，
`ObjectInfo` 只有单个 `Filename`/`ObjectFormatCode`，标准里**没有「配对」概念**。
因此：

- 机身若把 RAW+JPEG 存成两个文件，主机看到两个 handle，删除其中一个不会自动删另一个
  （标准层没有任何机制保证）；
- 厂商在**自己的 SDK 层**补了配对信息。Canon EDSDK 的 `EdsDirectoryItemInfo.groupID`：

  > A non-zero integer. **The same group ID is assigned to files that belong to the same
  > group, such as RAW+JPEG images or RAW+AVI images.**

  （来源：EDSDK 3.4 API Programming Reference §3.1.11 `EdsGetDirectoryItemInfo`）

  即 EOS 侧确实是「两个 directory item + 一个 groupID」，而不是一个对象。

**置信度**：会话/存储/保护 高（规范原文）；RAW+JPEG「两对象」高（规范对象模型 +
Canon SDK 文档）；「删一个是否连带删兄弟」**未能验证**——标准不保证，厂商文档也未写，
只能实机测。

---

## 2. 厂商实现（Canon EOS / Nikon / Sony + libgphoto2 证据）

先给一个贯穿三家的事实：libgphoto2 对**所有**厂商只有一个删除实现，没有按厂商分派。

```c
/* library.c:9402 */
static int
delete_file_func (CameraFilesystem *fs, const char *folder,
                  const char *filename, void *data, GPContext *context)
{
	...
	if (!ptp_operation_issupported(params, PTP_OC_DeleteObject))   /* library.c:9430 */
		return GP_ERROR_NOT_SUPPORTED;
	...
	C_PTP (ptp_deleteobject(params, handle, 0));                  /* library.c:9437 */
```

`camera->functions->del_file_func` 只注册了这一个（`library.c:9781`）。因此「某厂商是否
支持 0x100B」在参考实现里等价于「该机身是否在 DeviceInfo 里声明 0x100B」。

### 2.1 Canon EOS

**操作码**：`ptp.h` 同时定义了标准码与 EOS 专用码：

```c
/* ptp.h:234 */  #define PTP_OC_DeleteObject             0x100B
/* ptp.h:379 */  #define PTP_OC_CANON_EOS_DeleteObject		0x9105
```

但 **`0x9105` 在 libgphoto2 里从未被调用**——全仓库只有定义与操作码名字表：

```
ptp.h:379  #define PTP_OC_CANON_EOS_DeleteObject 0x9105
ptp.c:9663 {PTP_OC_CANON_EOS_DeleteObject,"EOS_DeleteObject"},
```

（`grep -rn "9105\|EOS_DeleteObject"` 在整个 libgphoto2 仓库只有这两处。）

结论：**EOS 的删除走的是通用 `0x100B`**，EOS 专用码是「知道存在但用不上」。
反过来说，若 EOS 机身不在 `OperationsSupported` 里声明 `0x100B`，gphoto 的删除在 EOS 上
会直接不可用——而事实上它是可用的（`delete_file_func` 没有 EOS 分支，也没有 EOS 专属
`del_file_func`）。这是**推断**，不是厂商文档。

**移除事件**：EOS 有一条**与标准 PTP 事件队列完全分离**的厂商事件通道。参考实现里写得
很直白：

```c
/* ptp.c:3640（ptp_check_event 内部） */
/* should not get here ... EOS has no normal PTP events and another queue handling. */
if ((params->deviceinfo.VendorExtensionID == PTP_VENDOR_CANON) &&
    ptp_operation_issupported(params, PTP_OC_CANON_EOS_GetEvent)) {
	return PTP_RC_OK;
}
```

也就是说：**EOS 机身不通过 USB 中断端点投递标准 PTP 事件**，主机必须主动调
`EOS_GetEvent`（`0x9116`）把事件「拉」回来：

```c
/* ptp.c:3774 */
ptp_check_eos_events (PTPParams *params)
{
	PTPCanonEOSEvents events = {0};
	while (1) { /* call it repeatedly until the camera does not report any */
		CHECK_PTP_RC(ptp_canon_eos_getevent (params, &events));
		...
```

`0xC101 EosEventRequestGetEvent` 是机身给主机的「我有事，来取」信号（`ptp.h:1295`；
本仓库同样定义于 `EosEventConstants.kt:29`）。

删除事件码与载荷：

```c
/* ptp.h:1298 */  #define PTP_EC_CANON_EOS_ObjectRemoved			0xc182
/* ptp-pack.c:2708（解析 EOS 事件数组） */
case PTP_EC_CANON_EOS_ObjectRemoved:
	e[i].type = PTP_EOSEvent_ObjectRemoved;
	e[i].u.object.Handle = dtoh32a(curdata+8);
```

**载荷只有 4 字节 ObjectHandle，没有文件名、没有路径。** 上层只能这样处理：

```c
/* library.c:7050 */
case PTP_EOSEvent_ObjectRemoved:
	GP_LOG_D ("object removed: handle 0x%x", eos_event.u.object.Handle);
	ptp_remove_object_from_cache(params, eos_event.u.object.Handle);
	gp_filesystem_reset (camera->fs);
	...
	*eventtype = GP_EVENT_UNKNOWN;
	*eventdata = aprintf("ObjectRemoved");
```

注意：`gp_filesystem_reset` + `GP_EVENT_UNKNOWN`，即 gphoto2 前端**拿不到「哪个文件被删了」**，
只能整体重扫。这是 EOS 移除事件的真实信息量上限。

本仓库已有对应常量：`EosEventConstants.kt:30` `EosEventObjectAddedEx=0xC181`、
`:31` `EosEventObjectRemoved=0xC182`，但 `EosEvent.kt:96` 只用于 `toString()`，没有消费方。

**“删除后是否回事件”**：EOS 机型里被标 `PTPBUG_DELETE_SENDS_EVENT` 的有 10D、450D/40D、
1000D、5D Mark II、7D、50D、5D Mark III、4000D（`library.c:2050/2136/2140/2199/2228/
2230/2232/2345/2550`），而 350D 明确「不发」（`library.c:9442` 注释）。
但该标志检查的是**标准** `0x4003`，而 EOS 的 `ptp_check_event` 提前 return，
所以这条分支在 EOS 上实际是空转——**这层代码对 EOS 不具证明力**，只能说明「Canon 固件
行为按机型分裂」。

**SDK 层佐证**：Canon 自家 EDSDK 提供删除与移除事件，但同样是「只给句柄/需要重扫」：

- `EdsDeleteDirectoryItem`：「Deletes a camera folder or file. If folders with
  subdirectories are designated, all files are deleted **except protected files**.」并附警告
  「Be careful when deleting files on the remote camera … **Lock the UI**, for example.」
  （EDSDK 3.4 API Ref §3.1.21）
- `kEdsObjectEvent_DirItemRemoved = 0x00000205`：「Because objects are **not indicated for
  type 1 protocol standard cameras**, you must again retrieve child objects under the
  camera object to identify deleted objects.」（EDSDK.h）

**置信度**：`0x9105` 定义但未使用 高（代码事实）；「EOS 用 0x100B 删除」中（推断）；
「0xC182 载荷只有 handle」高（解析代码）；「机身删除时 0xC182 是否必定发出」**未能验证**；
「主机删除时 EOS 是否也回 0xC182」**未能验证**（这一点对双向联动的歧义判断很关键）。

### 2.2 Nikon

**操作码**：`ptp.h` 里 Nikon 的删除相关码只有 SDRAM（内存缓冲）图像删除，**没有**「删除卡上
文件」的厂商码：

```c
/* ptp.h:584 */
#define PTP_OC_NIKON_DelImageSDRAM	0x90C3	/* 1 param (0x0: all, others: cancel this image) ,  no data */
```

它只在「拍摄到 SDRAM 后清理内存」的路径里用：**先试通用 `0x100B`，失败再退化到 Nikon
`0x90C3`**（`library.c:4570-4579`）。**卡上文件仍然走通用 `0x100B`**，与 Canon 相同。

**移除事件**：Nikon 的事件表里**没有** ObjectRemoved。`ptp.h` 的 Nikon 事件段从
`PTP_EC_Nikon_ObjectAddedInSDRAM 0xC101` 到 `PTP_EC_Nikon_SBGroupAttrChanged 0xC123`
（`ptp.h:1348-1367`），全部是「新增/录像/白平衡/闪光灯」类，**没有删除类事件**。
标准 `0x4003` 若真到达，会落到通用分支：

```c
/* library.c:7721 */
case PTP_EC_ObjectRemoved:
	ptp_remove_object_from_cache(params, event.Param1);
	gp_filesystem_reset (camera->fs);
	*eventtype = GP_EVENT_UNKNOWN;
	*eventdata = aprintf("PTP ObjectRemoved, Param1 %08x", event.Param1);
```

但 libgphoto2 **没有任何 Nikon 专属的删除事件处理**，也没有为 Nikon 合成删除事件。

**事件通道**：Nikon 必须**主动轮询** `GetEvent`，不能等中断端点：

```c
/* ptp.c:3611（ptp_check_event） */
if (params->deviceinfo.VendorExtensionID == PTP_VENDOR_NIKON) {
	PTPEvents events = {0};
	if (ptp_operation_issupported(params, PTP_OC_NIKON_GetEventEx)) {
		ret = ptp_nikon_check_eventex(params, &events.val, &events.len);
	} else if (ptp_operation_issupported(params, PTP_OC_NIKON_GetEvent)) {
		/* Method offered by Nikon DSLR, Nikon 1, and some older Nikon Coolpix P*
		 * The Nikon Coolpix P2 however does not return anything. So if we never get
		 * events from here, use the ptp "interrupt" method */
		ret = ptp_nikon_check_event(params, &events.val, &events.len);
```

```c
/* ptp.h:588 / ptp.h:637 */
#define PTP_OC_NIKON_GetEvent		0x90C7	/* no params, data in */
#define PTP_OC_NIKON_GetEventEx		0x941c	/* can do multiparameter events */
```

`params->event90c7works` 一旦置位，后续就只走这条路径（`ptp.c:3634`）。也就是说：
**Nikon 的事件可靠性依赖 `0x90C7`/`0x941C` 是否可用，且 Coolpix 世代有「调了也返回空」的
已知情况**（同上注释）。

**SDK 层佐证（Nikon 自家 MAID）**：卡上对象的删除对应 `kNkMAIDCapability_Remove`
（枚举注释「Remove an object from device storage」）；内存图像的删除是厂商扩展
`kNkMAIDCapability_DeleteDramImage`（`Maid3d1.h`）。Nikon 的 SDK 把「删卡上文件」当成
**对象级能力**，而不是「相机命令」，这与 PTP 侧「按 ObjectHandle 删」是一致的。

**置信度**：无 Nikon 删除专用码 高（代码事实）；Nikon 无 ObjectRemoved 事件 高（事件表 +
无专属处理）；「Nikon 机身删除必定发某个事件」**未能验证**（更可能是**不发**，见 §3.3）；
「Nikon 卡上 0x100B 一定可用」中（依赖 DeviceInfo，未见厂商文档确认）。

### 2.3 Sony

**操作码**：libgphoto2 的 Sony 操作码表（`ptp.h:702-732`）里**没有任何删除操作**，
连 Sony 专用的删除码都没有定义：

```
0x9201 SDIO_Connect, 0x9202 SDIO_GetExtDeviceInfo, 0x9203 GetDevicePropdesc,
0x9204 GetDevicePropertyValue, 0x9205 SDIO_SetExtDevicePropValue,
0x9206 GetControlDeviceDesc, 0x9207 SDIO_ControlDevice, 0x9209 GetExtDevicePropInfo,
0x9210 OpenSession, 0x9211 GetPartialLargeObject, 0x9212 SetContentsTransferMode, ...
```

**事件**：Sony 有删除事件码，但 libgphoto2 只把它登记进名字表，**没有处理逻辑**：

```c
/* ptp.h:1370-1371 */
#define PTP_EC_Sony_ObjectAdded			0xC201
#define PTP_EC_Sony_ObjectRemoved		0xC202
/* ptp.c:10044（仅名字表） */
{PTP_EC_Sony_ObjectRemoved, PTP_VENDOR_SONY, "Sony_ObjectRemoved"},
```

在 `camera_wait_for_event` 的 Sony 分支里，`PTP_VENDOR_SONY` 的预处理只映射
`ObjectAdded`（还是 `#if 0` 掉的）和 `DevicePropChanged`（`library.c:7591-7616`），
`0xC202` 会落到 `default:` → `GP_EVENT_UNKNOWN`（`library.c:7740-7742`）。

值得注意的是 Sony 自己的 PTP 扩展表（Sony 官方文档的表格，被 gphoto issue #978 逐条抄录）
里，`SDIE_ObjectRemoved = 0xC202` 的说明是「Notify that a shot file is deleted」，但紧接着
一句「The device sends this event when the shot file is **transferred**」——语义本身是含混的，
而 Sony 在同一张表里列出的 **PC Control 模式操作集只有**：

```
GetDeviceInfo 0x1001, OpenSession 0x1002, CloseSession 0x1003,
GetObject 0x1009, GetThumb 0x100A, GetPartialObject 0x101B,
SDIO_Connect 0x9201, SDIO_GetExtDeviceInfo 0x9202, SDIO_SetExtDevicePropValue 0x9205,
SDIO_ControlDevice 0x9207, SDIO_GetAllExtDevicePropInfo 0x9209
```

**没有 `DeleteObject`。** 这是「Sony 在 PC Remote/PC Control 模式下不提供删除」的
厂商侧证据（Sony 文档表格，经二手转录）。

**实机观测（二手，但含设备 `--summary` 原始输出）**：

- ILCE-5000：`gphoto2 --summary` 输出 `File Download, No File Deletion, No File Upload`，
  且 store 的 `Access Capability: Read-Only`；删除返回
  `*** Error (-6: 'Unsupported operation') ***`。维护者在 issue 里确认
  「Neither the Delete command is available, nor does the store report deletability」。
- ILCE-7R III（A7R III）：`gphoto2 -a` 显示 `Delete selected files on camera: yes`
  （即 DeviceInfo 里**有** `0x100B`），但实际删除在任何工具里都失败；同一用户观察到
  「PC Control 模式能拍摄但列不出文件，MTP 模式能列文件但拍不了」，
  有回复指出「this is a limitation of the Sony alpha firmware I fear」（社区回复，非厂商）。

（来源：gphoto issues #375、#230 — 二手/观测，不作承重结论）

**Sony 自家 SDK 的时间线**也印证这一点：Camera Remote SDK 直到 **Ver 2.00.00
（2025-06-23）** 才「Support for deleting data stored on the camera」，且仅限
ILCE-7CR / 7SM3 / 7M4 / 7CM2 / 6700 五款；早期的 Camera Remote API（Wi-Fi HTTP）
则通过 `avContent` 的 `deleteContent (v1.1)` 提供删除。**删除在 Sony 生态里是「新加的、
分机型的」能力，而不是基线。**

**置信度**：libgphoto2 无 Sony 删除码 / 不处理 0xC202 高（代码事实）；Sony 官方 PC Control
操作集不含 DeleteObject 中（二手转录 Sony 文档）；多款 Alpha 机身不支持 PTP 删除 高
（设备原始输出）；「Sony 会不会在机身删除时发 0xC202」**未能验证**（低）。

### 2.4 三家速查

| 问题 | Canon EOS | Nikon | Sony（Alpha / PC Remote） |
|---|---|---|---|
| 标准 `0x100B` | 参考实现走此路（推断机身声明） | 参考实现走此路 | 多机型不声明；PC Control 操作集无此码 |
| 厂商删除码 | `EOS_DeleteObject 0x9105` 已定义但**从未使用** | 仅 `DelImageSDRAM 0x90C3`（内存图像） | 无（libgphoto2 未定义任何 Sony 删除码） |
| 删除事件 | `0xC182 ObjectRemoved`（仅 handle）；部分机型另发标准 `0x4003` | **无**删除类事件 | `0xC202 SDIE_ObjectRemoved` 存在但语义含混，参考实现不处理 |
| 事件投递方式 | `EOS_GetEvent 0x9116` 主动拉；标准 PTP 事件通道对 EOS 关闭 | `GetEvent 0x90C7` / `GetEventEx 0x941C` 主动拉 | USB 中断端点（通用 `ptp_check_event`），PC Control 模式下能力本身受限 |
| 主机是否必须轮询 | 是 | 是 | 是（且很多机型根本没有可删对象） |

**置信度**：高（除已标注的推断项）。

---

## 3. 实践约束（机身是否还能操作、事件可靠性）

### 3.1 会话打开时，摄影师还能在机身上删照片吗？

**规范层的答案：厂商有权禁用。** PTP 1.0 在事件强制性表之前写了一段常被忽略的话：

> A Responder may not need to issue many events **if it disables manual or external object
> creation and device property manipulation while a session is open**. While a session is
> open, a Responder must send an event whenever an image, ancillary data, or store is added
> or removed …

（来源：PIMA 15740:2000，Table 36 前正文，PDF p.107）

即：**「PC 连接时锁住机身操作」是规范明确允许的实现策略**，而且一旦这么做，
`ObjectRemoved` 的强制性也随之消失。这一句同时解释了 §3.3 的现象。

**Canon（一手，厂商文档）**：EDSDK 把这件事做成了显式状态机：

> (1) UI Lock — In this state, **all operations of the camera unit are disabled** and only
> operations from the host PC are accepted. This allows data and instructions to be safely
> sent from the host PC to the camera.
> (2) UI Lock Release — In this state, operations of the camera unit are enabled. Although
> data and instructions can be sent from the host PC to the camera in this state,
> **conflicts may arise**.

（来源：EDSDK 3.4 API Ref §2.7「Camera Status」；对应 `kEdsCameraStatusCommand_UILock` /
`_UIUnLock`，§3.1.15）

配合 §2.1 引用的删除注意事项「…avoid doing so when the camera is not in the right mode.
**Lock the UI**, for example.」，可以认为：**EOS 在 PC 连接（EOS Utility 类）模式下，
机身 UI 默认被主机锁住，摄影师无法在机身上浏览/删除**；要恢复必须显式 `UIUnLock`，
但那样会「conflicts may arise」。这是**厂商文档级**的结论。

**Nikon（部分一手、部分未验证）**：Nikon 官方手册确实写过「远程拍摄时机身按键禁用」：

> The buttons of the camera are **disabled during remote photography**. To operate the
> buttons of the camera, exit remote photography.

（来源：Nikon KeyMission 参考手册「故障排除」；同系列 Quick Start Guide 亦同）

但这是**运动相机 + SnapBridge 远程拍摄**语境，**不能直接外推到 DSLR 的 USB PTP 会话**。
DSLR 在「连接至计算机」时的具体锁定行为，我没有找到 Nikon 官方原文，**未能验证**。

**Sony（未验证）**：Sony 帮助指南把 `PC Remote` 定义成一个**独立的 USB 连接模式**
（`USB Connection Mode` 的选项之一：Mass Storage / MTP / PC Remote），进入该模式后由
Imaging Edge Desktop 控制；文档没有一句话说明机身按键是否被禁用。**未能验证**。
不过从 §2.3 的实机观测看，PC Control 模式下连文件列表都拿不到，机身侧删除即使发生，
主机也无从知晓。

**置信度**：规范层「允许禁用」高；Canon「UI 被锁」高（厂商文档）；Nikon DSLR 低
（只有邻类产品手册）；Sony 低（无文档）。

### 3.2 机身删除后，主机多快能看到

分两条路径，**都没有「推送保证」**：

1. **事件路径**（仅当机身真的发事件）：延迟 = 主机的下一次事件轮询周期。
   libgphoto2 的循环是「查事件 → 处理 → `gp_context_idle()` → 退避等待」，全程受调用方
   传入的 `timeout` 约束（`library.c:6850-6878` EOS 分支、`library.c:7130-7136` Nikon 分支），
   机身没有任何「定时上报」的机制。本仓库同理：Nikon 走 `checkEvents()`
   （`NikonInitiator.kt:104`），EOS 走 `checkEvents()` → `EosGetEvent`
   （`EosInitiator.kt:64`）。
2. **句柄列表路径**（无事件时的唯一办法）：延迟 = 下一次 `GetObjectHandles`
   （`0x1007`）。本仓库已经是这个模型：`BaselineInitiator.kt:1089-1107` 每 **2000 ms**
   取一次全量句柄并做差集，但 `getAllNewAddedObjectHandles`（`BaselineInitiator.kt:1161`）
   **只算「新增」**，删除被静默丢弃（旧列表整体被新列表覆盖，`BaselineInitiator.kt:1122`）。

因此对「机身删除」的可见性上限是：**要么事件里有 handle，要么等到下一次 `GetObjectHandles`；
后者只能得到「某个 handle 不在了」，拿不到时间戳、拿不到原因。**

**置信度**：机制 高（代码路径）；「不同机型的具体延迟数字」**未能验证**（依赖固件与主机
轮询频率，无公开数据）。

### 3.3 有没有「完全不发事件」的已知情况

有，而且是被写进参考实现的：

**Fuji（明确的「无事件」案例）**：

```c
/* library.c:7399 */
/* current strategy ... as the camera (currently) does not send us ObjectAdded events for some reason...
 * we just synthesize them for the generic PTP event handler code */
do {
	PTPObjectHandles	handles = {0};
	if (ptp_get_one_event (params, &event))
		goto handleregular;
	C_PTP (ptp_getobjecthandles (params, PTP_HANDLER_SPECIAL, 0x000000, 0x000000, &handles));
```

即「某些机身根本不发对象增删事件，主机只能**轮询句柄表**并自己造事件」。
这是 §3.2 第 2 条路径存在的直接原因。

其他证据链：

- **Canon**：`DELETE_SENDS_EVENT` 是**按机型**登记的（`library.c:2005-2558` 一长串），
  等价于承认「是否发事件因固件而异」，且默认假设是**不发**。
- **Nikon**：事件表无删除类事件（`ptp.h:1348-1367`），也没有任何合成逻辑。
- **Sony**：`0xC202` 虽在名字表里，但参考实现从不消费；且 PC Control 模式下列表本身不可用。
- **规范**：Table 36 把 `ObjectRemoved` 定为「仅当会话期间允许外部删除」才必需，
  而 §3.1 那句允许设备直接禁掉外部删除。**「不发」是合规的。**

**置信度**：高（代码 + 规范双向支持）。

---

## 4. 对 PhotoSync 的含义

> 只给可行性与约束，不给方案。

### (a) 能不能删掉相机上指定的那张照片

**能做，但只在「机身声明了 `0x100B`」的机型上，且必须先把下表的检查做完。**

当前仓库**完全没有删除实现**：`Command.kt:67` 有 `DeleteObject = 0x100b`、
`Command.kt:111` 有 `EosDeleteObject = 0x9105`，但全项目 `grep -rn "DeleteObject"` 除了
常量定义与名字映射（`Command.kt:335/356`）之外**没有任何调用点**。

可行的前提条件（缺一即应放弃，而不是发了再看返回码）：

| 检查项 | 依据 | 失败表现 |
|---|---|---|
| 已 `OpenSession` | 1.0 §10.4.2 | `0x2003 Session_Not_Open` |
| `DeviceInfo.OperationsSupported` 含 `0x100B` | 1.0 §5.5.2 / Table 35 | `0x2005 Operation_Not_Supported` |
| `StorageInfo.AccessCapability != 0x0001` | 1.0 §5.5.3 | `0x200E Store_Read_Only` / `0x200F Access_Denied` |
| `ObjectInfo.ProtectionStatus == 0x0000` | 1.0 §5.5.2 | `0x200D Object_Write_Protected` |
| handle 来自**本会话最近一次** `GetObjectHandles` | handle 只在本会话内有效 | `0x2009 Invalid_ObjectHandle` |

按机型的可行性：

- **Canon EOS**：可行。参考实现用通用 `0x100B` 删 EOS 文件（`library.c:9402-9464`，调用点在 `:9437`），
  Canon 自家 SDK 也有 `EdsDeleteDirectoryItem`。风险点是**写保护与 UI 锁状态**
  （EDSDK 的删除 API 文档就写了「Lock the UI, for example」，§3.1）。
- **Nikon**：可行但依赖 DeviceInfo；Nikon 没有专用删除码，SDRAM 专用码
  `0x90C3` 只适用于内存图像，**不能拿来删卡上文件**。
- **Sony**：**多数 Alpha 机型不可行**。官方 PC Control 操作集不含 `DeleteObject`
  （§2.3），ILCE-5000 实机 `--summary` 直接报 `No File Deletion` + store `Read-Only`。
  个别机型（A7R III）虽在能力表里报了 `0x100B`，实测删除仍失败。

必须接受的行为不确定性：

- **RAW+JPEG 是两次删除**（两个 handle），标准不保证联动（§1.5）；
- 删除**不可撤销**，且 `0x2012 Partial_Deletion` 意味着「部分成功」是合法结果，
  不能把「返回 OK」当成「全部删掉了」。

**置信度**：Canon 可行 中（删除路径明确，但「EOS 声明 0x100B」是推断）；Nikon 可行 中；
Sony 基本不可行 高。

### (b) 机身侧的删除能不能回传到 App

**有条件地能，但没有一条路径是可靠的，而且没有一条路径能告诉你「这是用户手动删的」。**

三条可能的回传路径，按可靠性排序：

1. **厂商/标准删除事件**（Canon `0xC182`；标准 `0x4003`）：
   - 只携带 **ObjectHandle**，不携带文件名（`ptp-pack.c:2708-2711`；1.0 §12.5.3 的
     Parameter1 也只有 handle）。所以要落到 App 的记录上，**必须先在删除前建立
     handle → 记录 的映射**（本仓库目前没有这种持久映射：`BaselineInitiator.kt:1161`
     只比对新旧 handle 集合，不做持久化关联）。
   - 触发条件被规范限定为「外部来源导致的删除」（§12.5.3），且 Table 36 允许机身
     干脆不发。
   - **动作归属无法区分**：规范说主机自己删不必发事件，但 Canon 部分固件**确实会发**
     （`PTPBUG_DELETE_SENDS_EVENT`）。也就是说 App 收到一个移除事件时，**无法仅凭事件
     判断这是「用户按了机身删除」还是「App 自己刚删的」**——除非 App 维护「自己发起的
     删除」的待确认集合。
2. **句柄表差集**（无事件时的兜底）：可行，本仓库已有 2 秒轮询
   （`BaselineInitiator.kt:1100-1107`），但当前只处理「新增」。要感知删除，需要额外算
   `old - new`，并且要接受「handle 消失 ≠ 用户删除」——换卡、格式化、卡拔出都会造成
   同样的差集。
3. **vendor SDK 通道**（Canon EDSDK `DirItemRemoved` 0x205）：Canon 自己在文档里就写了
   「type 1 protocol standard cameras 不提供对象标识，必须重新枚举子对象」——
   即连厂商 SDK 都存在「只知道少了东西，不知道少了哪个」的机型。

对 Sony 来说这条方向基本不成立：PC Control 模式下列表都拿不到，MTP 模式下删除本身被拒。

**置信度**：事件携带的信息量 高；「能可靠回传」低-中；「能区分删除发起方」低。

### (c) 两个方向里，哪个是危险的

**危险的是「机身 → App」这个方向（回传），不是「App → 机身」。**

理由，逐条都有出处：

1. **它可能根本不存在。** `ObjectRemoved` 的强制性以「允许外部删除」为前提
   （Table 36），而规范同时允许设备在会话期间禁用外部删除（§3.1 引文）。厂商实践中
   这确实发生了：libgphoto2 把「Canon 删除后还发事件」登记为机型级 bug 标志
   （`device-flags.h:216`），Fuji 干脆不发事件、由主机轮询句柄（`library.c:7399`），
   Nikon 事件表里没有删除类事件（`ptp.h:1348-1367`）。
   **依赖它 = 依赖一个规范允许缺席、厂商实现分裂的信号。**
2. **它给不出足够的标识。** 只有 handle，没有文件名；EOS 侧连 gphoto 都只能
   `gp_filesystem_reset` 后整体重扫（`library.c:7050-7058`）。要把 handle 映射回
   App 的记录，就必须维护跨会话的 handle 映射——而 handle 的有效性只在本会话内
   （§1.5，`0x2009` 的语义就是「这个会话里没这个 handle」）。
3. **它可能把 App 自己的动作误判成用户的动作。** 见 (b) 第 1 条与
   `PTPBUG_DELETE_SENDS_EVENT`。
4. **它的失败模式是「静默」。** App 不会收到错误码，只会「什么都没发生」；
   而 (a) 方向的失败是显式的：`0x2005`/`0x200D`/`0x200E`/`0x2009`/`0x2019` 都能分辨，
   可以给用户明确的反馈与重试策略。
5. **它可能锁死对端。** Canon 的 UI Lock 意味着「PC 连接时机身本来就不能删」
   （EDSDK §2.7），那么这条回传路径在 EOS 上大概率**永远收不到东西**——为一个
   结构上很少发生的场景付出复杂度，收益存疑。

反过来说，(a) 方向（App 删机身）的危险性是**可控的**：单次请求-响应、返回码明确、
可在发送前做完 §4(a) 的前置检查。它真正的风险不是「不可靠」，而是**不可逆**
（删了就是删了，PTP 没有回收站），这属于产品决策，不属于协议风险。

一句话总结：**(a) 是「可能被拒绝但结果是确定的」；(b) 是「可能根本没有、有了也不完整」。**

**置信度**：高（每条都由前文的规范条文或代码路径支撑）；
唯一保留：**「特定机型在机身删除时是否发事件」我没有实机数据**，这会影响 (b) 的
具体概率，但不影响「(b) 在结构上不可靠」这个结论。

---

## 5. 引用清单

### 一手：规范

| 编号 | 来源 | 用途 |
|---|---|---|
| S1 | PIMA 15740:2000（PTP 1.0 全文，147 页）<br>https://people.ece.cornell.edu/land/courses/ece4760/FinalProjects/f2012/jmv87/site/files/pima15740-2000.pdf | §10.4.11 DeleteObject（p.55–56）、§11.2/§11.3 响应码、§12.5.3 ObjectRemoved（p.82）、Table 35（p.106）、Table 36 及其前正文（p.107）、§5.5.2/§5.5.3（p.15–22）、§9.3/§10.4.2 会话 |
| S2 | ISO 15740:2013（PTP 1.1）官方页（付费，仅前言可读）<br>https://www.iso.org/standard/63602.html | 确认 1.1 与 1.0 的向后兼容关系、版本命名 |
| S3 | ISO 15740:2013 预览样本（含 §10.5.29–37 与 §11.3.x 正文）<br>https://www.normsplash.com/Samples/ISO/143791977/ISO-15740-2013-en-2.pdf | §11.3.6 Parameter not supported 原文（用于 1.1 的第二参数语义） |

> **未能取得**：ISO 15740:2013 的 DeleteObject 条款正文（付费墙）。因此「1.1 是否修改了
> format 过滤语义」只能给出「未发现变更证据」，不能给出「未变更」。

### 一手：参考实现（libgphoto2，commit `31a2f40`，2026-11-13）

仓库：https://github.com/gphoto/libgphoto2 （路径相对 `camlibs/ptp2/`）

| 位置 | 内容 |
|---|---|
| `ptp.h:234` | `PTP_OC_DeleteObject 0x100B` |
| `ptp.h:379` | `PTP_OC_CANON_EOS_DeleteObject 0x9105`（全仓库仅定义 + 名字表） |
| `ptp.h:584` | `PTP_OC_NIKON_DelImageSDRAM 0x90C3`（1 参数） |
| `ptp.h:588` / `ptp.h:637` | `PTP_OC_NIKON_GetEvent 0x90C7` / `GetEventEx 0x941C` |
| `ptp.h:1268` | `PTP_EC_ObjectRemoved 0x4003` |
| `ptp.h:1298` | `PTP_EC_CANON_EOS_ObjectRemoved 0xC182` |
| `ptp.h:1348-1367` | Nikon 事件表（无删除类事件） |
| `ptp.h:1370-1371` | `PTP_EC_Sony_ObjectAdded 0xC201` / `ObjectRemoved 0xC202` |
| `ptp.h:702-732` | Sony 操作码表（无任何删除操作） |
| `ptp.c:2619-2630` | `ptp_deleteobject(handle, ofc)` 实际报文 `(0x100B, handle, ofc)` |
| `ptp.c:3611-3635` | `ptp_check_event` 的 Nikon 分支（GetEvent/GetEventEx + `event90c7works` 回退） |
| `ptp.c:3640` | 「EOS has no normal PTP events and another queue handling」 |
| `ptp.c:3774-3787` | `ptp_check_eos_events` 循环调用 `EOS_GetEvent` |
| `ptp.c:10044` | `PTP_EC_Sony_ObjectRemoved` 仅出现在名字表 |
| `ptp-pack.c:2708-2711` | EOS `0xC182` 解析：`u.object.Handle = dtoh32a(curdata+8)`，无文件名 |
| `library.c:9402-9464` | `delete_file_func`：唯一删除入口，能力检查 + `ptp_deleteobject(handle,0)` |
| `library.c:9441-9456` | `DELETE_SENDS_EVENT` 分支 + 「IXUS II / A85 发，350D 不发」注释 |
| `library.c:7721-7726` | 标准 `0x4003` 的通用处理（丢缓存 + 重置 FS + `GP_EVENT_UNKNOWN`） |
| `library.c:7050-7058` | EOS `ObjectRemoved` 处理（只记 handle，无 path） |
| `library.c:7399-7405` | Fuji：机身不发对象事件，主机轮询 `GetObjectHandles` 合成事件 |
| `library.c:5136` | Sony 内存对象路径注释「`PTP_OC_DeleteObject` is not supported」 |
| `library.c:8038-8042` | `--summary` 的「File Deletion / No File Deletion」由能力表生成 |
| `device-flags.h:216` | `DEVICE_FLAG_DELETE_SENDS_EVENT 0x00020000` |
| `ptp-bugs.h:27-38` | `PTPBUG_DELETE_SENDS_EVENT` / `DELETE_SENDS_EVENT()` |

### 一手：厂商文档

| 来源 | 用途 |
|---|---|
| Canon EDSDK 3.4 API Programming Reference（Canon 官方 PDF 的公开镜像，含 §2.7 Camera Status、§3.1.11 `EdsGetDirectoryItemInfo` groupID、§3.1.15 `EdsSendStatusCommand`、§3.1.21 `EdsDeleteDirectoryItem`）<br>https://downloads.triprism.com/public/[%20Steve%20]/forTyler/canon%203.4sdk/EDSDK_API%203.4.pdf | Canon 的 UI Lock 语义、groupID（RAW+JPEG）、删除 API 与警告 |
| `EDSDK.h`（第三方仓库中的头文件副本，含 `kEdsObjectEvent_DirItemRemoved 0x205` 注释）<br>https://github.com/kylemcdonald/ofxEdsdk/blob/master/libs/EDSDK/include/EDSDK.h | 「type 1 protocol standard cameras 不提供对象标识」 |
| Nikon `Maid3.h` / `Maid3d1.h`（Nikon SDK 头文件副本）<br>https://github.com/discordj/QNikonCamera/blob/master/nikon/Maid3.h<br>https://github.com/discordj/QNikonCamera/blob/master/nikon/Maid3d1.h | `kNkMAIDCapability_Remove`、`kNkMAIDCapability_DeleteDramImage` |
| Nikon KeyMission 参考手册（远程拍摄时机身按键禁用）<br>https://nikonimglib.com/snbrkm/onlinehelp/en/km360/reference_manual/07_appendix_05.html | Nikon 官方「按键禁用」表述（机型语境有限） |
| Sony Help Guide：PC Remote Function / USB Connection Mode<br>https://helpguide.sony.net/ilc/2060/v1/en/contents/TP0002728092.html<br>https://helpguide.sony.net/ilc/2360/v1/en/contents/0601_usb_connection.html | PC Remote 是独立 USB 模式；未提机身锁定 |
| Sony Camera Remote SDK 发布说明（Ver 2.00.00 新增「删除相机内数据」，仅 5 款机型）<br>https://support-d.imaging.sony.co.jp/app/sdk/en/ | Sony 删除能力的机型/时间边界 |

### 二手：观测与社区（**不支撑承重结论**，仅作现象记录）

| 来源 | 用途与限制 |
|---|---|
| gphoto issue #375「Please support file deletion on Sony ILCE-5000」<br>https://github.com/gphoto/libgphoto2/issues/375 | ILCE-5000 `--summary` 原始输出 `No File Deletion` + store `Read-Only`；维护者判断。**单一机型**，不能外推到全部 Sony |
| gphoto issue #230「Trying to use with Sony A7RIII」<br>https://github.com/gphoto/libgphoto2/issues/230 | A7R III 能力表报 `Delete selected files on camera: yes` 但删除失败；PC Control vs MTP 的取舍。**用户报告** |
| gphoto issue #978「Sony PTP Extensions」<br>https://github.com/gphoto/libgphoto2/issues/978 | Sony 官方扩展表的逐条转录（含 `SDIE_ObjectRemoved 0xC202` 与 PC Control 操作集）。**转录，非原始文档** |
| gphoto issue #442（Nikon D7200）<br>https://github.com/gphoto/libgphoto2/issues/442 | 日志显示 `PTP_OC 0x100b` 返回 `0x2009`（针对 SDRAM 对象）。仅证明 Nikon 会解析该操作码 |
| Stack Overflow「photo shoot not working with camera connected to the computer」<br>https://stackoverflow.com/questions/60271562/ | 「EDSDK 连接时会自动 UI Lock」的旁证。**二手** |

---

### 附：本仓库现状（供后续决策引用，非结论）

| 事实 | 位置 |
|---|---|
| 有 `DeleteObject` / `EosDeleteObject` 常量，**无任何调用点** | `Command.kt:67/111`；`grep -rn "DeleteObject"` 仅命中常量与名字映射 |
| 有 `ObjectRemoved 0x4003` 常量，**无消费方** | `Event.kt:14/32` |
| 有 EOS `0xC182` 常量，仅用于 `toString()` | `EosEventConstants.kt:31`；`EosEvent.kt:96` |
| Nikon 事件常量里**没有**删除类事件 | `NikonEventConstants.kt:125-127` |
| 已有 2 秒句柄轮询，但只算「新增」 | `BaselineInitiator.kt:1100-1107`、`:1161-1169` |
| EOS 事件靠主动 `EosGetEvent` 拉取 | `EosInitiator.kt:64` |
| Nikon 事件靠 `checkEvents()` 轮询 | `NikonInitiator.kt:104` |

**置信度**：高（`grep` 与逐行阅读）。

---

## 6. 本仓库的缺口清单（代码级）

> 与 §4 互补：§4 判断可行性，这里只回答「要动哪些既有代码」。不含方案取舍。

| 缺口 | 现状 | 卡住哪个方向 |
|---|---|---|
| `PhotoEntity` 没有 PTP `ObjectHandle`（只有 `path/name/uriString/createdAt`） | `data/entity/PhotoEntity.kt` | 两个方向都需要 handle → 本地记录 的映射 |
| 落盘文件名可能被改写（重名时 `getAvailableOutputFile()` 加后缀），**用文件名反查 handle 不可靠** | `BaselineInitiator.processFileAddEvent` | App → 机身 |
| 删除只发生在本地（缓存 + 文件 + DB 行） | `MainActivity.deleteCurrent` | App → 机身 |
| `DeleteObject 0x100B` / `EosDeleteObject 0x9105` 常量无人调用 | `Command.kt:67`、`Command.kt:111` | App → 机身 |
| 能力探测已有但没用在这里：`DeviceInfo.supportsOperation()` / `supportsEvent()` | `DeviceInfo.kt:35-38` | 两个方向的前置检查 |
| 轮询只算 `new - old`；`old - new` 算了就丢（`oldObjectHandles = newObjectHandles` 直接覆盖） | `BaselineInitiator.runPollListPoll` | 机身 → App（兜底路径） |
| `SyncDevice.syncIdList` 只在**有新文件**时回写，机身删除不会剪枝 | `BaselineInitiator.kt`（`if (newAdded.size > 0)` 分支）+ `SyncDeviceManager.updateIdList` | 机身 → App |
| EOS 事件解析对未登记的码抛 `PTPUnsupportedException`；`0xC182` 正好落在 `parseParameters` 的 `else` 分支，会中断这一批事件的后续解析 | `eos/EosEventParser.parseParameters` | 机身 → App（事件路径） |
| Nikon 事件常量表里没有任何删除类事件 | `nikon/NikonEventConstants.kt` | 机身 → App |
| 基线 `Event.ObjectRemoved 0x4003` 有常量、有名字映射，无消费方 | `Event.kt:14`、`Event.kt:32` | 机身 → App |

已具备、可直接复用的：

- `DeviceInfo` 已解析 `operationsSupported` / `eventsSupported`，`supportsOperation` / `supportsEvent` 现成。
- 已有 2 秒一轮的全量 handle 读取（`getObjectHandlesByStorageIds`），删除检测不需要新开线程。
- `SyncDevice.syncIdList` 已有持久化承载；Room 目前 `version = 2` 且 `fallbackToDestructiveMigration()`，加列的**迁移代价低但会清库**，这一点要在方案里显式决定。

**置信度**：高（逐行核对）。
