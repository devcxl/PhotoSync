# 相机连接归用户意图所有，由进程持有，而不是挂在 Activity 上

**状态**: accepted

PhotoSync 的 USB PTP 会话挂在 `App` 的进程级控制器上，`Activity` 只是这份会话的一个视图：重建（分屏、主题、语言切换、`recreate()`）不触碰会话；只有用户点「断开相机」、拔掉数据线、进程结束，或 Activity **真正 finish**（`isFinishing && !isChangingConfigurations`）时才断开。原因是：会话打开期间相机无法拍照，所以释放时机必须是用户可见的；而把释放挂在「任何一次 `onDestroy`」上，会让系统的普通重建拆掉刚建立的会话——旧实例 `onDestroy` 关掉新实例正要用的 initiator，表现为「连上又掉」或「反复重连」。

## Considered Options

- **Activity 拥有会话（每次 `onDestroy` 都断开）**：本 ADR 要修掉的正是这个——系统重建会拆掉健康会话。
- **永不断开，只等进程死亡**：用户退出应用后相机仍被占着，只能靠系统回收进程才恢复拍摄。
- **切到后台即断开**：切到相册看刚导出的照片，回来就要重新握手。

## Consequences

- 任何后续新增的可重建宿主（新 Activity、Fragment、Compose 屏幕）都不得在 `onDestroy` 里调用 `disconnect()`。
- 会话出错后没有宿主替它重连：控制器自己停在 `Disconnected`，由用户再次点击「连接相机」——这是有意的显式语义，不是遗漏。
