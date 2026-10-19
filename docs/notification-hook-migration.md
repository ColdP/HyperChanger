# NotificationHooks 迁移审计（第二批）

后续功能变更：按用户要求以通知视图级的可选文字颜色替代旧资源级固定白字，并加入手电筒模板颜色统一。生命周期、包分发及共享材质链仍由原所有者管理。实现范围、原方法调用次数、ClassLoader、安装去重与验证结果见 [notification-text-and-flashlight.md](notification-text-and-flashlight.md)。下文保留原迁移时的基线记录。

## 基线及范围

本轮基线是第一阶段拆分后的工作区，而非 Git HEAD；`HyperSystemUiModule.kt` 共 16,120 行，SHA-256 为 `26a6ac875f9dccfd4b347793a111e3f71bb8fdf9a7f546087e9704cfed93f42f`。第一阶段尚未提交，不能用 HEAD 代替本轮输入。本文原行号均指这一工作区基线。本表在修改 Kotlin 之前形成。

沿包分支、真实调用、缓存消费者及注册位置核对后，选择通知限制移除、小窗条隐藏和通知文字颜色三个完整组。原有目标、默认值、日志、反射查找行为、proceed 次数、注册顺序和异常边界全部保留；不新增延迟加载，不增加重试、不删除旧实现路径、不改变配置机制，不改变 ControlCenterHooks。

## 迁移方法与状态对照

| 原位置 | 迁移目的地 | 独立性及兼容要求 |
| --- | --- | --- |
| 9332–9512 `installNotificationRestrictionHooks` | `NotificationHooks` private 方法 | 同一个完整安装方法，保留 Toast makeText/show → shouldHideNotification → ExpandedNotification / SettingsManager → importance icons → FoldCoordinator / MiuiBaseNotifUtil 顺序及各 runCatching；通知在锁屏上的权限仍属于通知限制组，不搬锁屏渲染实现 |
| 6599–6630 `installNotificationMiniWindowBarHook` | `NotificationHooks` private 方法 | 单独的 updateMiniWindowBar Hook，原方法先执行一次，然后按原条件隐藏；不搬全局 View.setVisibility 的兜底支路 |
| 13813–13853 `installNotificationColorHooks` | `NotificationHooks.installNotificationColorHooks` | 四个 Resources overload 按原 list 顺序安装，保留 getColor / getColorStateList 的不同返回类型及异常路径 |
| 13855–13860 `isHeadsUpNotificationColorResource` | 新类 private 方法 | 只有颜色 Hook 调用，不涉及 Heads-up 材质状态 |
| 15463–15471 `HEADS_UP_NOTIFICATION_TEXT_COLOR` / `HEADS_UP_NOTIFICATION_COLOR_NAMES` | 新类 private companion 常量 / 集合 | 唯一消费者随方法一起迁移，原文件不重复定义 |
| 15978 `notificationRestrictionHooksInstalled` | 新类 private companion 状态 | 原为进程内所有模块实例共享的 companion 状态，不能改成实例字段；仍在两个安装方法均返回后置 true，异常逃逸时不提前写入 |
| 原包入口 1734–1738 | 原位置调用 `installNotificationRestrictions` | SYSTEM_UI 包判断仍由原模块执行；新方法原样承接安装标志判断、两个顺序调用和最后置位，不移动其他 Hook |
| 原包入口 1687 | 原位置调用新类颜色方法 | 继续受共享 resourceHooksInstalled 保护，紧跟 installDimensionHooks；颜色注册失败时仍阻止原 resourceHooksInstalled 置位，不吞异常 |

新类由原模块直接持有，初始化只接收模块、现有只读方法引用和常量。构造器不读取配置、不加载目标类、不创建 Handler、不注册 Hook，也不引入 lazy。所有方法仅由原安装点调用。

## 保留在原文件的共享实现

| 保留项 | 真实依赖 / 不迁移原因 |
| --- | --- |
| `readInstanceField`、`findDynamicIslandField` | 多处通知、灵动岛、电源菜单、锁屏消费者共用；两个方法查父类与异常处理不同，不能合并。通过类型明确的回调复用，不改 private 可见性、不反射读取模块私有字段 |
| `hookDeoptimized` | 第一阶段已有的 internal 桥，直接复用，仍是尝试 deoptimize 后继续 hook；不增加桥或复制实现 |
| `SYSTEM_UI`、`SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS`、`EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS`、`KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR`、`TAG` | 原模块其他功能仍消费这些常量；以构造参数传递原值，保留原定义，不新增同值常量副本 |
| `resourceHooksInstalled`、`installDimensionHooks`、`replacementDp` | 同一个尺寸/颜色安装生命周期兼顾状态栏、音量、灵动岛和通知圆角；不能拆成两个独立标志 |
| `installStatusBarVisibilityHook` 的 mini_window_bar 分支 | 一个全局 interceptor 同时管控制中心编辑按钮、通知小窗条、信号等；本轮小窗条方法的边界不含这个共享 interceptor，保留注册点和链顺序 |
| `installShadeMaterialHooks`、`softGlassRuntime`、基线/数组/刷新缓存、`softGlassRefresh` | 通知、控制中心和音量共同使用，不迁移、不复制 |
| `installFocusNotificationMaterialEnforcementHooks`、`installFocusNotificationBackgroundHook`、普通/媒体效果辅助 | 与上述软玻璃、锁屏媒体状态、焦点效果再入保护相连，保留完整链 |
| `notificationTuningFor`、`materialTuningFor`、`notificationTypeFor`、`controlCenterTuningFor`、`normalNotificationGlassParams`、混色/资源辅助 | 同一配方和资源缓存被音量、通知及原生材质更新使用；不拆出重复缓存 |
| `notificationOnKeyguard`、`isExpandedNotificationShade`、`mediaHeaderOnActiveKeyguard`、`isNotificationMaterialCall`、`isNotificationCenterCall`、面板/调用栈辅助 | 判定共享的锁屏/通知中心媒体 header 归属及控制中心边界；原位置和调用关系不变 |
| `installHeadsUpNotificationSoftGlassHooks`、`headsUpGlassDispatching`、`convertedHeadsUpRows`、`isHeadsUpNotificationSurface` | shared soft-glass setter 同时读取再入状态和 surface 判断；颜色资源组不读取它们，故只迁移颜色组 |
| 焦点/灵动岛白名单与类发现器 | 同一安装组修改通知权限、灵动岛授权和回调包名单，并由共享类加载发现补装；本轮不搬也不拆方法内部支路 |
| 锁屏通知位置、FOD、锁屏媒体行/header/pipeline、歌词和 artwork 桥 | 与锁屏状态、音乐时钟和原生媒体展示生命周期高度耦合，保留，未提前迁移 LockscreenHooks |
| 第一阶段保留的全部控制中心顶部按钮、圆角、主题/材质、触摸与加载器状态 | 本轮没有独立性证据支持迁移，保持原样，ControlCenterHooks 文件及交互不变 |

原有偏好 key 继续使用现有设置文件，本轮不新增设置、不触碰 HookSettings 或三语文案。新类没有 Handler、调度任务或额外缓存；Toast 抑制 WeakHashMap 仍在原安装方法局部创建并由两个回调共同捕获。

## iOS 风格通知中心与普通通知中心审计

iOS 风格通知中心不在 HyperSystemUiModule 中。`META-INF/xposed/java_init.list` 分别注册 HyperSystemUiModule 和 `hypermusiccover.Main`。实际入口链：

1. `Main.onPackageLoaded` 先 `Xp.attach(this)`，处理地图/壁纸提前返回，再判断 SystemUI。
2. 同入口先 `IosShadeMaterial.install`、媒体注册 Hook、`ShadeLayer.bindPreferences`；接着锁屏容器发现可能提前 return。
3. 后续原位置安装 shade 窗口构造器、FloatFlowProperty 展开驱动，才调用 `IosNotificationCenterHooks.install(cl)`。
4. `IosNotificationCenterHooks` 内先装 StackHooks，StackHooks 先装 AnimationHooks，再安装手势、header、原始高度、延迟隐藏与控制中心切换所有权等；Main 后续仍装共享 blur/provider/touch/通知材质链。
5. `ShadeLayer` 负责偏好监听、壁纸、通知/控制中心进度和延迟关闭；presentation、exit state、material 和 Main 的媒体/锁屏代码互相回调。

若把 iOS 安装改到 NotificationHooks，会改变独立 Xposed 入口、Xp 绑定对象、锁屏容器缺失时的 return 行为、类加载时机及与主模块/控制中心的 Hook 链顺序。本轮保留 `newnotificationcenter/*`、Main、ShadeLayer 和入口清单的全部内容，不添加第二次安装调用，也不做空委托来假装完成迁移。普通通知中心的背景、配方和展开判断也属于前表的共享链，继续保留。

## 验证计划与未实测风险

以本轮工作区快照核对选定方法体、常量、状态与所有委托；原模块全文只允许审定的删除、实例持有和两处调用替换；对 ControlCenterHooks、iOS 全部实现与入口清单、HookSettings 和三语 JSON 核对哈希。只运行 `.\gradlew.bat :app:assembleDebug` 构建，再检查 APK 完整性和新类 DEX 打包。

未安排设备安装或 SystemUI 重启。实机仍需验证：各通知限制开关开/关、BLE Toast 识别、解锁后通知保留、重要性参数替换、历史折叠、原小窗条兜底、浅深色 Heads-up 文字、同进程重复包回调、防重复标志与安装异常后的恢复；并回归原生/iOS 通知中心、控制中心横向切换、锁屏媒体及两模块 Hook 链。编译和源码一致性不能替代这些运行时验证。

## 本轮验证记录

- 方法体对照通过：通知限制组、小窗条组、通知颜色组及颜色资源识别辅助，在接收者和共享依赖参数化后与工作区基线逐段一致。
- 安装状态、两处安装委托、方法顺序和原模块其余代码对照通过；原文件不再含选定组的实现、颜色常量或安装状态。
- `ControlCenterHooks.kt`、`HookSettings.kt`、iOS 风格通知中心 Java/Kotlin 文件、Xposed 入口清单及三份语言 JSON 均保持不变；三份 JSON 解析通过。
- `.\gradlew.bat :app:assembleDebug`：`BUILD SUCCESSFUL in 21s`，编译和 APK 打包通过。
- APK ZIP 完整性通过；`classes9.dex` 同时包含 `NotificationHooks` 和上一阶段的 `ControlCenterHooks`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，版本 `1.2.1 RC1`，122,561,613 字节。
- APK SHA-256：`ebc5080588c49f164a8f448965c09b6f28d957360f347c34bb2984a8507f6bd7`。
