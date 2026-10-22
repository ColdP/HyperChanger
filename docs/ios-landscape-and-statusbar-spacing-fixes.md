# iOS 横屏与状态栏间距修复（2026-10-20）

## 修改前核对与边界

- 已检查工作区差异，保留上一轮按 `239996a` 恢复的时钟缩放逻辑及已有状态栏功能。修改前源码备份位于 `.reverse-tmp/ios-landscape-statusbar-20261020/`。
- iOS 布局继续由 `IosNotificationCenterPresentation` 持有；`IosNotificationStackHooks` 沿现有安装入口、SystemUI ClassLoader，为原生列表的 `onMeasure` 提供横屏右列宽度。原测量仅调用一次；竖屏、锁屏及未启用 iOS 通知面板时透传。
- 本地反编译源码显示列表位于 `SharedNotificationContainer` 的约束布局中，原生 binder 会重新应用 start/end 和宽度约束。仅在 pre-draw 修改 LayoutParams 无法稳定约束通知行的测量宽度；同时需要按面板坐标校正右列位置。自定义通知标题横条也应使用右列宽度。
- 时钟宿主原来把壁纸的轮廓提供器直接应用于左列，提供器使用传入视图宽度生成圆角，因而在分栏内侧产生额外圆角。横屏改为读取整张壁纸的轮廓并转换坐标；保留展开时的纵向裁切及整屏圆角，不改共享材质或壁纸实现。
- 状态栏仍由 `StatusBarHooks` 持有，入口和 ClassLoader 不变。本地 `MiuiSystemUI.apk` 的 `status_bar_system_icon_spacing` 实际为 `0sp`，现有乘法无法调整。0% 保留系统值；原生零间隔时正向百分比使用 4dp 步进基准，负向保持零；原生非零时继续使用原生百分比语义。
- 放大时信号/电池边界继续独立于间距设置，为最靠近电池的可见图标预留绘制溢出，并增加 2dp 的可见安全间隔；不按每张 SIM 累加，不修改电池 padding、原生动画或电量样式。
- 不新增设置、文案或序列化；不移动 Hook 所有权；不修改 `IosShadeMaterial.java`、`ShadeLayer.java`、框架入口或已恢复的时钟缩放链。

## 验证

实现后核对限定文件差异、时钟驱动与 `239996a` 的一致性，执行 `git diff --check` 和规定的 Debug APK 构建，检查 APK 完整性。设备运行、旋转、RTL、隐私指示器、双 SIM/WiFi、极端图标大小、通知组和动态岛动画仍需实机验证。

## 实际结果

- 修改 `IosNotificationCenterPresentation.java` 的横屏列宽、右列坐标、通知标题栏宽度与时钟轮廓；新增窄接口 `landscapeStackWidth(View)`，仅由同包的列表测量 Hook 消费，不暴露模块私有状态。
- `IosNotificationStackHooks.java` 仅增加目标列表的 `onMeasure` 宽度参数覆盖。与备份逐段比较，原有 Hook 均保持不变，没有重复注册旧 Hook。
- `StatusBarHooks.kt` 的改动仅位于现有边界测量和间距安装方法，状态所有权、测量缓存补偿扣除、RTL 矩阵、隐私判断、设置文件、偏好同步、导入、双排信号和电量样式逻辑均未改变。
- 对照本轮修改前哈希，仅上述三个实现文件与本文改变；其余已有未提交文件保持原样。`Main.java` 和受保护的两份材质/壁纸文件未变。`syncNotifications()` 与 `239996a` 对应实现逐字一致，也没有重新引入手动时钟缩放。
- 规定命令 `.\gradlew.bat :app:assembleDebug`：**BUILD SUCCESSFUL in 7s**，40 tasks，7 executed、33 up-to-date。仅出现已有源码的弃用、类型与非空提示。
- APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`，122561792 字节，131 个 ZIP 条目的 CRC 检查通过，包含 Manifest 和 DEX；SHA256：`55d908caaa830b0e54bc8248cfeda196ed82cd43f1758a1f907a81ce27edd9a9`。

以上是静态核对与编译/打包结果；未进行设备安装、ADB 操作或 SystemUI 重启，不能据此认定所有固件上的运行效果已验证。
