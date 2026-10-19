# Lockscreen hook migration audit

## 本轮交付和验证

仅迁移锁屏底部提示文本组：充电、勿扰、通知计数。完整清单、共享依赖及暂留理由见下文。
`LockscreenHooks.kt` 中四个私有方法、九个私有常量和进程级安装标志一并迁移，
`HyperSystemUiModule.kt` 保留生命周期入口、目标包分发和原位置的单次委托。
新类仅使用原模块已有的 Hook/日志 API 和原 TAG；不访问原类私有字段，不扩大可见性，
不建立新的通知、控制中心或媒体桥调用路径。

结构迁移完成后先独立构建：`BUILD SUCCESSFUL in 55s`。逐方法逆向还原接收者和日志 tag
后，四个完整方法与原文一致；九个常量、安装标志写入点及安装顺序一致；原文件剩余内容
与审定的纯迁移结果完全一致。其余 266 个基线文件以 SHA256 核对未变。

用户随后追加授权修复灰色遮罩，以下属于明确的行为修复，不属于锁屏结构迁移：

- `IosShadeMaterial.java`：系统对整屏 `NotificationPanelView` 的 `setMiBackgroundBlurMode(0)`
  原先会在 iOS 材质启用期间被替换为 1；现在保留该关闭请求。反编译的
  `ShadeBlendBlurController$start$2` 表明系统缓存 `BlurProvider.enabled`，只有状态变化时才写
  此 setter；覆盖关闭请求后，不能假设下一帧会再写一次 0。整屏面板也参与悬浮通知渲染。
- 同一文件：原先 getter 缺失/失败时仍会写入 iOS 属性，但退出只恢复非 null 原值。
  现在不主动写入未知原值的属性；后续收到系统显式 setter 请求时，仍可记录请求并进行
  可恢复的接管。不猜测默认值，不增加反射 API 或缓存。
- `ShadeLayer.java`：现有退出路径清空壁纸 `RenderEffect` 后同步使 `sLastWpBlur` 失效，
  防止下次相同半径被误判为已经应用。原有退出透明度、可见性、裁剪和位移清理仍保留。

回归命令：`.\tests\ios-shade-material\run.ps1`，直接编译生产 `IosShadeMaterial.java`。
修复前分别复现“系统关闭请求被覆盖”和“原值未知仍被改写”两个断言失败；修复后
265 项检查通过，包含 20 轮开关、退出中的布局回调、悬浮通知原生 recipe、
最新系统参数恢复、重复清理及 getter 失败。测试使用 Android/Xposed 合约桩，不能代替
ROM 的 RenderNode/合成器实测。

追加修复后再次核对：只有上述两个 iOS Java 源文件相对独立构建快照有变化，其余 260 个
app 源文件（含已迁移 `LockscreenHooks.kt`）哈希不变；通知/控制中心 Kotlin 拆分文件、
共享媒体链、设置及三份语言 JSON 未改动。三个 JSON 均可解析，`git diff --check` 通过。
最终执行 `.\gradlew.bat :app:assembleDebug`：`BUILD SUCCESSFUL in 11s`，40 个任务，
7 个执行、33 个复用；有既有弃用 API/Gradle 警告，无编译或打包失败。
APK：`app/build/outputs/apk/debug/app-debug.apk`。

尚未实机复测：各 mask/旧配置回退、充电/勿扰/通知计数刷新及动画；iOS 通知中心和控制中心
快速切换/退出、横竖屏、悬浮通知、主题/ROM getter 差异。getter 不可读取时对应属性保留
系统材质，视觉结果需在目标 ROM 确认。上述静态缺陷已修正，不能仅凭单元检查断言灰罩
在设备上已彻底消失。本轮不继续迁移其他类别，不宣称性能提升。

## Scope and installation order

`HyperSystemUiModule.onPackageLoaded` keeps the existing `SYSTEM_UI`/`SYSTEM_UI_PLUGIN`
dispatch and installs lockscreen work in this order: lockscreen notification/FOD position,
the complete media notification chain, legacy date-follow state (currently deliberately marked
installed without registering), SystemUI clock colon/width/horizontal geometry, carrier hiding,
legacy native clock scaler (also deliberately marked installed), fingerprint visual/animation,
SystemUI depth, charging and bottom indication text, white-bar visibility, gesture handle,
shortcut glass (which schedules mini-player and widget retries), widget scene visibility,
legacy Kotlin music-lockscreen (deliberately marked installed), PIN circles, then shared shade
and material hooks. AOD and ThemeManager retain their own clock/editor/wallpaper hooks.

## Inventory and ownership

| Current hook group | Guards/state/constants/helpers | Dependencies and decision |
| --- | --- | --- |
| `installLiftToWakeScheduleHook` | installation-local `AtomicReference`, `AtomicBoolean`, main-thread `Handler` and 20-second tick; `applyLiftToWakeSchedule`, `currentMinuteOfDay` | Independent candidate for a later batch; it runs before the main SystemUI branch and has no global installation guard. Preserve that lifetime unchanged. |
| Existing `lockscreenFaceUnlockHook.install` / `.onClassLoaded` | lazy owner already in the module; `LockscreenFaceUnlockHook` owns its installed/initialized flags, discovery set and face/keyguard/UI helpers | Already a separate implementation, also called by shared class discovery. Keep its holder, installation position and discovery callbacks unchanged. |
| `installLockscreenNotificationHook` (FOD shelf/position flow) | `lockscreenNotificationHookInstalled`, `fodEnrollmentFlowOverrides`, FOD flow class discovery and position helpers | Shares notification stack flows, `readContext`, and notification settings; retain in the module. |
| `installLockscreenMediaNotificationHook` and its manager/controller/header/row/pipeline/tiny-panel/lyrics/artwork/effect helpers | `lockscreenMediaNotificationHookInstalled`; media rows/headers/controllers, transition generations and animations, lyric button maps, media interactors/guards, keyguard state, bridge callbacks and presentation constants | Uses shared notification material, `LockscreenMediaBridge`, `LockscreenMediaPresentationBridge`, imported HyperMusicCover `Main`, and shared Keyguard detection. No direct call to `NotificationHooks`; its existing preceding installation position must still be preserved. Retain the entire chain. |
| HyperMusicCover gesture bridge and dispatch (`installHyperMusicCoverGestureBridge`, state/lyrics/card commands) | preference listener, application context, active clock container, presentation and lyric sync state, delayed card reset | Owns cross-module callback timing and media cover lifecycle; retain in the module. |
| Clock colon, width, editor width, horizontal rect/editor/touch and material-limit/capability hooks | clock install guards, width depth/thread-local, requested width, weak offset/drag/editor caches, shared clock constants | AOD/ThemeManager variants share methods and caches; retain. |
| Carrier hide | `lockscreenCarrierHideHookInstalled`, carrier mode keys and slot/data-subscription checks | Uses Control Center carrier classes and keyguard layout state; retain with shared carrier implementation. |
| Fingerprint icon/animation visuals | `fingerprintIconHookInstalled`, `fingerprintHideMode`, FOD hide-mode compatibility keys and animation constants | A coherent later candidate, but the global icon option also affects non-lockscreen biometric prompts. Keep the global/keyguard distinction and nested optional failure handling unchanged; defer this batch. |
| SystemUI depth and wallpaper depth | `systemUiDepthHookInstalled`, depth threshold/avoidance state helpers | Shares Keyguard panel/depth interactor and AOD/ThemeManager wallpaper paths; retain. |
| Charging indication and bottom notification/DND text | `lockscreenChargingHookInstalled`; `KeyguardIndicationController`, `NotificationNumStateView`, animation extension; mask keys and three bit constants; `readViewField`/visibility helper | The two install methods and helpers are exclusively used by this group. No media, notification-material, or Control Center state is needed. **Independent first migration group.** |
| White bar and global gesture handle/controller | white-bar/gesture guards, navigation and overlay material state | Gesture controller and status/navigation behavior are shared with later SystemUI work; retain. |
| Shortcut glass, mini-player, widget, PIN circles | shortcut guard, mini-player/widget controller maps, PIN constants, shared material helpers | Shortcut hook schedules mini-player/widget retries and uses shared material implementation; PIN uses the same material helpers. Retain. |
| Widget scene visibility | `LockscreenWidgetSceneState` callbacks and scene methods | Shared with widget controller lifecycle; retain. |
| Legacy Kotlin music-lockscreen and native scaler | guards are intentionally set without registration; scaler object is referenced by `LockscreenMusicLockscreenController` in `LockscreenMiniPlayer.kt` | Imported HyperMusicCover owns the active media cover/clock lifecycle; retain inactive Kotlin paths and their compatibility state. |
| AOD/ThemeManager/editor wallpaper/template hooks | AOD/ThemeManager guards and editor caches | Different package dispatch and shared clock/material capabilities; retain. |

## Lock-screen media chain

After the notification hook, `installLockscreenMediaNotificationHook` registers manager bridge,
controller visibility, header construction/keyguard callback/View visibility, media-data and holder
rebinding, layout/artwork capture, lyric button/status callbacks, customization menu, visibility
providers, pipeline filter, tiny panel, and finally row visibility. Runtime callbacks update
`LockscreenMediaBridge`. Separately, presentation changes call the HyperMusicCover gesture bridge;
that bridge sends cover/lyrics commands and refreshes lyric buttons. Header pre-draw guards and
short/settle delayed reapplications repair vendor writes. This chain shares notification entries,
material effect interactors, Keyguard state, and imported `Main.java`; extracting one method would
duplicate state or change callback order, so it remains in `HyperSystemUiModule.kt`.

## First migration boundary

Only the charging indication and bottom indication text group is moved in this batch. The module
retains the package lifecycle entry, process dispatch, and the single call site order. All other
lockscreen, media, AOD, StatusBar, SystemUi, and Others groups remain in place for later audited
batches. This is a structural move only; no performance or initialization strategy changes.

## 审计结论与本轮精确迁移对照

本轮基线为工作区快照，Git 基线 `b1af0581` 已包含此前通过测试的控制中心/通知拆分。
审计不仅检查名称，还检查原方法调用前后时机、原结果保留、位掩码兼容默认值、动画拦截范围、
失败时标志写入、主线程任务和外部控制器引用。

| 原方法/成员 | 新所有者 | 行为边界 |
| --- | --- | --- |
| `installLockscreenChargingTextHook` | `LockscreenHooks` 私有方法 | `KeyguardIndicationController.updateDeviceEntryIndication(boolean)`；先调用原方法，再按 mask 调用 `hideIndication(int)`，仍返回原结果；内部安装/回调异常边界及日志原样保留 |
| `installLockscreenBottomTextViewHook` | `LockscreenHooks` 私有方法 | 原声明顺序枚举 `updateZenViewText`、`updateNotificationCountView`、`onFinishInflate`；原方法之后修改指定子视图；随后安装可选 `NumStateViewAnimateExt.animateUpdateViewVisibility` 全部既有匹配重载 |
| `applyLockscreenBottomTextVisibility` | `LockscreenHooks` 私有方法 | mask 为 0 提前返回、遍历原父类字段、通知文字/辅助描述清空、透明度及分割线处理全部保留 |
| `readViewField` | `LockscreenHooks` 私有方法 | 仅被底部文本动画回调使用；仍按原顺序逐级反射目标 ROM 对象的字段，无新增缓存 |
| `lockscreenChargingHookInstalled` | `LockscreenHooks` 私有 companion 状态 | 仍是同一进程多模块实例共用的一个标志；两个安装方法按原顺序返回之后才写 true；不新增成功判断或重试 |
| 9 个功能专属常量 | `LockscreenHooks` 私有 companion 常量 | 三个类名、`CHARGING_INDICATION_TYPE = 3`、两个配置键、三个 mask 位（1/2/4）一并迁移；原文件删除对应定义 |
| 原生命周期安装块 | 原文件中单次委托 `installBottomTextHooks` | 原 `SYSTEM_UI` 判断、`param.defaultClassLoader`、`preferences` 实例以及景深之后/白条之前的位置不变；异常仍落入原外层 runCatching |
| `TAG` | 原文件唯一共享来源，构造时传值 | 新类使用原日志 tag，不复制共享日志常量 |
| `hook`、`hookDeoptimized`、`log` | 原模块/既有 API | 接收原 module 实例；继续使用既有 `internal hookDeoptimized`，不扩大其他可见性 |

底部文本的三个 OEM 类虽然含 notification 字样，作用域是锁屏指示 UI。动画扩展本身可共享，
但现有拦截以父节点类名和子 View 身份筛选，仅抑制既有指定视图；这段筛选完整保留。
没有专属全局视图缓存或监听器需要另建：旋转文本控制器字段、目标 Class、Method 列表仍由
原安装作用域和 Hook 闭包持有。这里没有 Handler/post、配置监听或依赖注入回调。

可供后续独立审计的候选还有冒号显示（独立的 SystemUI/AOD guard）、抬起唤醒定时、
成组的时钟宽度/编辑器逻辑、模板数量和编辑器背景。它们本轮均不修改。运营商钩子虽然只在
Keyguard 布局应用，但目标类也用于控制中心，不能依据方法名直接搬运。白条的目标是共享
NavigationBar/ButtonDispatcher，且邻接全局手势钩子，应单独审计其范围后再迁移。

必须暂留的共享实现包括 `notificationOnKeyguard`、`isExpandedNotificationShade`、
`mediaHeaderOnActiveKeyguard`、`isLockscreenMediaView`、`readInstanceField`、`readContext`、
`readInheritedField`、`findField`、`applyNormalMediaNotificationEffect`、
`applyNormalNotificationRowEffect`、`notificationTuningFor`、`materialTuningFor`、
`applyLegacyBackdropMaterial`、`applySystemGlassMaterial`，以及它们现有缓存和类加载器所有者。
其中 `applySystemGlassMaterial` 读取 `controlCenterButtonsLock` 下的控制中心/SystemUI
ClassLoader，顺序为传入 loader → 已发现 loader → View Context loader；本轮不动此链。

## 媒体生命周期与共享状态的补充核对

1. SystemUI 分支最早在保存 `systemUiClassLoader` 后安装手势桥，先写桥安装标志、赋值
   `onPresentationChanged`、注册并强持有偏好监听，再 Hook 时钟容器 attach。attach 先原调用，
   再保存 application context/弱引用并 post 同步歌词与展示命令。通知资源和媒体链在其后安装。
2. 媒体安装顺序严格是 manager bridge → controller visibility → header（内部先构造器、
   Keyguard 回调、View.setVisibility、媒体数据、holder、layout，再 artwork bridge）→ lyric
   button → 可选 status-bar state → effect refresh → customization menu → visibility providers
   → pipeline → tiny panel → rows。前述头部安装还立即执行一次 presentation apply。
3. header 包装现有 `onPresentationChanged`：先调用旧的手势桥回调，再应用媒体展示。
   `LockscreenMediaPresentationBridge.setPresentation` 本身先通知 listeners，再执行该回调；
   manager update 只更新媒体桥，不直接触发展示状态改变，不能将二者当成线性调用链。
4. manager 公有方法及异步 synthetic Runnable 提交后更新 controller/key；歌词按钮在 bind、
   attach、top-media 提交及 120ms 重绑恢复 action4；回调和 pre-draw 共用同一按钮/header map。
   holder 重建、通知 shade 归属变化、Keyguard 显示变化均可更新同一 header。
5. 展示应用先同步控制器可见性/通知栈 add-remove 副作用，再保留 post、96ms、320ms 的补偿；
   头部动画依旧为隐藏 300ms、显示 420ms，带 generation 去重/取消保护。回到普通卡片的
   HyperMusicCover 命令仍在 720ms 后复查展示状态再复位 artwork/text。
6. `lockscreenMediaInteractors` 和 `lockscreenMediaHeaderGuards` 是模块实例弱映射；
   `lockscreenMediaHeaders/Controllers/Rows`、动画目标/世代/基础位移/运行集合、歌词按钮集合/
   header 映射、`lockscreenMediaKeyguardShowing`、一次性 drawable 错误日志标志及翻译缓存
   仍在原 companion。不能把这些状态统一改成实例变量或复制到新类。
7. `LockscreenMediaBridge` 的 controller/key/artwork/pendingArtwork/generation/listeners、
   PresentationBridge 的 presentation/listeners、CustomizationMenuBridge、WidgetSceneState
   均继续由 `LockscreenMiniPlayer.kt`/`LockscreenWidget.kt` 中的既有对象拥有。控制器 destroy
   的反注册、View 生命周期、共享表和线程行为不变。
8. 旧 date-follow、Kotlin 全屏 host、native size interceptor 入口目前不注册，仅把原标志
   设 true；保留方法、缩放对象、弱缓存及原兼容 no-op `installLockscreenMediaArtworkClick`。
   HyperMusicCover Java `Main` 独立 Xposed 入口、壁纸 WallpaperProbe、iOS 通知中心及 ShadeLayer
   保持原样，本轮不接管其生命周期。

## 逐方法清单与成员引用附录（迁移前行号）

以下范围先按入口、回调和所有者人工审阅，再枚举方法及成员引用以检查遗漏。成员引用是保守清单，包含共享成员，并不表示这些成员均应迁移。目标常量的值以本轮源码快照为准；非首批组全部保留原实现及成员。

### 抬起唤醒定时

方法：

- `installLiftToWakeScheduleHook`（原 618 行）
- `applyLiftToWakeSchedule`（原 643 行）
- `currentMinuteOfDay`（原 676 行）

原文件成员引用（常量、状态及缓存）：

`active`, `TAG`

### 封面与手势桥

方法：

- `installHyperMusicCoverGestureBridge`（原 1956 行）
- `dispatchHyperMusicCoverState`（原 2036 行）
- `syncHyperMusicCoverLyrics`（原 2108 行）
- `sendHyperMusicCoverMediaCardCommand`（原 2133 行）
- `sendHyperMusicCoverCommand`（原 2151 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `SYSTEM_UI`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_HDR_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_KEEP_SCREEN_ON`, `HYPER_MUSIC_COVER_ACTION`, `HYPER_MUSIC_COVER_CARD_RESET_DELAY_MS`, `syncedHyperMusicLyricsEnabled`, `syncedHyperMusicLyricsHdrEnabled`, `syncedHyperMusicLyricsKeepScreenOn`, `hyperMusicCoverGestureBridgeInstalled`, `hyperMusicCoverPreferenceChangeListener`, `systemUiApplicationContext`, `activeLockscreenClockContainer`

### 时钟与编辑器/材质能力

方法：

- `installClockMaterialLimitHook`（原 5199 行）
- `installLockscreenClockColonHook`（原 6403 行）
- `installLockscreenBigClockWidthHook`（原 6612 行）
- `installLockscreenBigClockEditorWidthHook`（原 6676 行）
- `installLockscreenClockHorizontalRectHook`（原 6704 行）
- `installLockscreenClockHorizontalEditorHook`（原 6755 行）
- `handleLockscreenClockHorizontalTouch`（原 6877 行）
- `applyEditorClockHorizontalPreview`（原 6931 行）
- `clearEditorClockHorizontalPreview`（原 6948 行）
- `findAllInOneEditorForWallpaper`（原 6952 行）
- `findAllInOneEditorInObject`（原 6961 行）
- `applyEditorClockHorizontalOffset`（原 6983 行）
- `forEachEditorClock`（原 7008 行）
- `isAllInOneClock`（原 7023 行）
- `findField`（原 7032 行）
- `moveEditorFrame`（原 7043 行）
- `widenedClockRect`（原 7053 行）
- `installAodClockMaterialLimitHook`（原 7064 行）
- `installClockMaterialCapabilityHook`（原 7174 行）
- `installThemeManagerEditorClockMaterialHook`（原 7198 行）
- `restoreRequestedClockWidth`（原 7241 行）
- `installThemeManagerClockMaterialLimitHook`（原 7282 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `AOD`, `CLOCK_UTILITY_CLASS`, `CLOCK_UTILITY_METHOD`, `THEME_MANAGER_TEMPLATE_API_CLASS`, `THEME_MANAGER_TEMPLATE_API_METHOD`, `CLOCK_BEAN_CLASS`, `CLOCK_BEAN_IS_COLON_SHOW_METHOD`, `CLOCK_DEPTH_AVOID_RULE_UTILS_CLASS`, `ALL_IN_ONE_UTIL_CLASS`, `ALL_IN_ONE_TEMPLATE_VIEW_CLASS`, `BIG_CLOCK_WIDTH_ENVELOPE_MULTIPLIER`, `bigClockWidthCalculationDepth`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`, `clockHorizontalRectOffsets`, `clockHorizontalLiveOffsets`, `clockHorizontalEditorShifts`, `clockHorizontalEditorClockShifts`, `clockHorizontalDrags`, `activeLockscreenClockEditor`, `lockscreenClockCombinedTouchHookInstalled`

### 锁屏运营商

方法：

- `installLockscreenCarrierHideHook`（原 6428 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`

### 景深、避让及第三方壁纸

方法：

- `installDepthEffectHook`（原 9447 行）
- `installDepthAvoidanceBypass`（原 9495 行）
- `isUserHierarchyEnabled`（原 9528 行）
- `installSystemUiDepthHooks`（原 12871 行）
- `forceDepthImageThreshold`（原 12923 行）
- `installDepthDisplayStateHook`（原 12939 行）
- `clearDepthAvoidState`（原 12968 行）
- `installThirdPartyWallpaperDepthHook`（原 13899 行）
- `installAodThirdPartyWallpaperDepthHook`（原 13943 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `AOD`, `DEPTH_EVALUATOR_CLASS`, `DEPTH_THRESHOLD_CLASS`, `HIERARCHY_AVOID_CONTROLLER_CLASS`, `USER_OPEN_HIERARCHY_FIELD`, `KEYGUARD_DEPTH_INTERACTOR_CLASS`, `KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS`, `WALLPAPER_INFO_CLASS`, `LARGE_SCREEN_HIERARCHY_ENABLE_CLASS`, `AOD_WALLPAPER_INFO_CLASS`, `AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS`, `WALLPAPER_CONTROLLER_CLASS`, `WALLPAPER_CONTROLLER_COMPANION_CLASS`, `IMAGE_THRESHOLD_FIELD`, `THRESHOLD_RATE_FIELD`, `DEFAULT_DEPTH_IMAGE_THRESHOLD`, `UNLIMITED_DEPTH_IMAGE_THRESHOLD`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`

### 快捷入口、小播放器、组件及共享材质

方法：

- `installLockscreenShortcutGlassHook`（原 9534 行）
- `installLockscreenMiniPlayer`（原 9936 行）
- `scheduleLockscreenMiniPlayerInstallation`（原 9997 行）
- `installLockscreenWidget`（原 10018 行）
- `scheduleLockscreenWidgetInstallation`（原 10073 行）
- `resolveLockscreenShortcutViews`（原 10205 行）
- `miniPlayerAppearance`（原 10219 行）
- `applyMiniPlayerMaterial`（原 10308 行）
- `commonShortcutParent`（原 10339 行）
- `installShortcutGlassBackgrounds`（原 10354 行）
- `findShortcutContainers`（原 10484 行）
- `applyLockscreenShortcutGeometry`（原 10499 行）
- `applyShortcutIconColorMode`（原 10536 行）
- `applyLockscreenWidgetBatteryMaterial`（原 10553 行）
- `applyLockscreenWidgetShortcutSurfaceMaterial`（原 10635 行）
- `View.idName`（原 10784 行）
- `applyLegacyBackdropMaterial`（原 10793 行）
- `applySystemGlassMaterial`（原 10830 行）
- `shortcutBackgroundMode`（原 10880 行）
- `shortcutIconColorMode`（原 10889 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `MIUI_SHORTCUT_CONTROLLER_CLASS`, `MI_GLASS_COMPAT_CLASS`, `DEFAULT_SHORTCUT_GLASS_RADIUS`, `MIN_SHORTCUT_GLASS_RADIUS`, `MAX_SHORTCUT_GLASS_RADIUS`, `SHORTCUT_GLASS_MATERIAL_TYPE`, `SHORTCUT_GLASS_BLUR_MODE`, `SHORTCUT_GLASS_BLEND_MODE`, `SHORTCUT_PURE_COLOR`, `MINI_PLAYER_PURE_COLOR`, `SHORTCUT_ICON_LIGHT_COLOR`, `SHORTCUT_ICON_DARK_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_SOFT_GLASS_COLOR`, `MAX_SHORTCUT_OPACITY`, `MAX_SHORTCUT_BACKDROP_BLUR_RADIUS`, `MAX_SHORTCUT_GLASS_BLUR_RADIUS`, `MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS`, `MAX_SHORTCUT_GLASS_LUMINANCE`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_OPACITY`, `DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`, `GLASS_LUMINANCE_AMOUNT_INDEX`, `SHORTCUT_BACKGROUND_NONE`, `SHORTCUT_BACKGROUND_PURE_COLOR`, `SHORTCUT_BACKGROUND_ADVANCED_MATERIAL`, `SHORTCUT_BACKGROUND_SOFT_GLASS`, `SHORTCUT_ICON_COLOR_AUTO`, `SHORTCUT_ICON_COLOR_LIGHT`, `SHORTCUT_ICON_COLOR_DARK`, `SHORTCUT_GLASS_TAG`, `LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS`, `LOCKSCREEN_SHORTCUT_CONTAINER_IDS`, `SHORTCUT_GLASS_PARAMETERS`, `SHORTCUT_BLOOM_STROKE_PARAMETERS`, `KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED`, `KEY_LOCKSCREEN_MINI_PLAYER_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED`, `KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE`, `KEY_LOCKSCREEN_MINI_PLAYER_WIDTH`, `KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS`, `KEY_MINI_PLAYER_PURE_COLOR`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_MINI_PLAYER_SOFT_GLASS_COLOR`, `KEY_MINI_PLAYER_SOFT_GLASS_OPACITY`, `KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS`, `KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE`, `KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS`, `KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_SPACING`, `KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE`, `KEY_SHORTCUT_ICON_COLOR_MODE`, `KEY_SHORTCUT_PURE_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY`, `KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_SHORTCUT_SOFT_GLASS_COLOR`, `KEY_SHORTCUT_SOFT_GLASS_OPACITY`, `KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_LUMINANCE`, `lockscreenMiniPlayerControllers`, `lockscreenWidgetControllers`, `controlCenterButtonsLock`, `systemUiClassLoader`, `controlCenterControllerClassLoader`

### 组件场景

方法：

- `installLockscreenWidgetSceneVisibilityHooks`（原 9589 行）

原文件成员引用（常量、状态及缓存）：

`TAG`, `AOD`, `KEYGUARD_EDITOR_HELPER_CLASS`, `MIUI_CHARGE_ANIMATION_VIEW_CLASS`, `CONTROL_CENTER_EXPAND_LISTENER_CLASS`

### PIN 键盘

方法：

- `installLockscreenPinCircleBackgroundHook`（原 9779 行）
- `installLockscreenPinCircleBackgrounds`（原 9805 行）
- `configureLockscreenPinLabels`（原 9902 行）
- `applyLockscreenPinRowSpacing`（原 9919 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `KEYGUARD_PIN_VIEW_CLASS`, `LOCKSCREEN_PIN_KEY_IDS`, `LOCKSCREEN_PIN_ROW_IDS`, `LOCKSCREEN_PIN_CIRCLE_TAG`, `LOCKSCREEN_PIN_CIRCLE_RIPPLE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`

### 未安装的旧 Kotlin 音乐锁屏

方法：

- `installLockscreenMusicLockscreenHook`（原 10092 行）
- `installLockscreenMusicLockscreen`（原 10178 行）

原文件成员引用（常量、状态及缓存）：

`tint`, `preferences`, `TAG`, `KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS`, `STATUS_BAR_STATE_KEYGUARD`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `lockscreenMusicLockscreenControllers`

### 未安装的旧日期跟随

方法：

- `installLockscreenClockDateFollowHook`（原 10900 行）
- `syncLockscreenClockDate`（原 10945 行）
- `scheduleLockscreenDateFollow`（原 10983 行）
- `syncLockscreenDateViews`（原 11007 行）
- `findLockscreenClockGlyphTop`（原 11040 行）
- `getClockPart`（原 11055 行）
- `readInheritedField`（原 11063 行）
- `isViewDescendantOf`（原 11075 行）

原文件成员引用（常量、状态及缓存）：

`TAG`, `lockscreenDateAppliedOffsets`, `lockscreenDateNativeOffsets`, `lockscreenDateFollowGenerations`, `LOCKSCREEN_DATE_FOLLOW_DURATION_MS`, `LOCKSCREEN_DATE_TO_GLYPH_GAP_DP`

### 通知位置/FOD 流

方法：

- `installLockscreenNotificationHook`（原 11084 行）
- `findFodNotificationPositionFlows`（原 11152 行）
- `installFodEnrollmentFlowOverride`（原 11178 行）
- `createFodEnrollmentFlowOverride`（原 11202 行）
- `createLockscreenNotificationPositionCollector`（原 11236 行）
- `adjustLockscreenNotificationPosition`（原 11259 行）
- `isNotificationFodPositionLimitRemoved`（原 12982 行）
- `fingerprintHideMode`（原 12993 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `FOD_SHELF_SPACE_FLOW_CLASS`, `FOD_NOTIFICATION_POSITION_FLOW_PREFIX`, `FOD_NOTIFICATION_POSITION_FLOW_SUFFIX`, `FOD_NOTIFICATION_POSITION_FLOW_ORDINAL_RANGE`, `FOD_MODE_DEFAULT`, `FOD_MODE_HIDE_ICON`, `FOD_MODE_KEEP_ICON`, `FINGERPRINT_HIDE_NONE`, `FINGERPRINT_HIDE_GLOBAL`, `KEY_NOTIFICATION_FOD_MODE`, `KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED`, `KEY_FINGERPRINT_HIDE_MODE`, `KEY_NOTIFICATIONS_IGNORE_FOD`, `systemUiApplicationContext`, `fodEnrollmentFlowOverrides`

### 媒体通知、歌词、动画及旧缩放链

方法：

- `installLockscreenMediaNotificationHook`（原 11295 行）
- `installLockscreenMediaRowVisibilityHook`（原 11330 行）
- `installLockscreenMediaControllerVisibilityHook`（原 11395 行）
- `installLockscreenMediaManagerBridgeHooks`（原 11436 行）
- `installLockscreenMediaHeaderHook`（原 11490 行）
- `installLockscreenIslandArtworkBridge`（原 11695 行）
- `installLockscreenLyricButtonHook`（原 11734 行）
- `bindLockscreenLyricButton`（原 11857 行）
- `bindLockscreenLyricButtonForHolder`（原 11937 行）
- `bindLockscreenLyricButtonForController`（原 11949 行）
- `findRegisteredLockscreenMediaHeader`（原 11963 行）
- `applyEmptyLockscreenMediaButton`（原 11972 行）
- `refreshLockscreenLyricButtons`（原 11986 行）
- `maintainLockscreenLyricButton`（原 12001 行）
- `shouldShowLockscreenLyricButton`（原 12029 行）
- `installLockscreenLyricButtonStatusBarStateHook`（原 12032 行）
- `shouldOwnLockscreenLyricSlot`（原 12069 行）
- `findMiuiMediaHeaderAncestor`（原 12081 行）
- `lockscreenLyricsShowing`（原 12091 行）
- `setLockscreenLyricsShowing`（原 12095 行）
- `loadLockscreenLyricButtonDrawable`（原 12103 行）
- `tr`（原 12128 行）
- `installLockscreenCustomizationMenuHook`（原 12154 行）
- `installLockscreenMediaVisibilityProviderHooks`（原 12183 行）
- `installLockscreenMediaPipelineHook`（原 12235 行）
- `installTinyLockscreenMediaHook`（原 12272 行）
- `lockscreenMediaNotificationMode`（原 12312 行）
- `shouldHideLockscreenMedia`（原 12323 行）
- `shouldFilterLockscreenMedia`（原 12344 行）
- `applyLockscreenMediaPresentation`（原 12349 行）
- `updateLockscreenMediaHeaderVisibility`（原 12382 行）
- `animateLockscreenMediaHeaderContents`（原 12472 行）
- `centeredMediaTextTranslation`（原 12521 行）
- `requestLockscreenMediaControllerVisibility`（原 12535 行）
- `scheduleLockscreenMediaPresentation`（原 12554 行）
- `installLockscreenNativeClockScalerHook`（原 12568 行）
- `installLockscreenMediaEffectRefreshHook`（原 12601 行）
- `reapplyNativeMediaEffect`（原 12635 行）
- `readStateFlowBoolean`（原 12665 行）
- `installLockscreenMediaHeaderGuard`（原 12681 行）
- `installLockscreenMediaArtworkClick`（原 12699 行）
- `findLockscreenMediaHeader`（原 12706 行）
- `drawableBitmap`（原 12715 行）
- `isLockscreenMediaView`（原 12733 行）
- `isMiuiMediaHeaderView`（原 12740 行）
- `isMediaRow`（原 12749 行）
- `isMediaEntry`（原 12769 行）
- `isKeyguardCoordinatorOnKeyguard`（原 12821 行）

原文件成员引用（常量、状态及缓存）：

`active`, `preferences`, `lockscreenMediaInteractors`, `lockscreenMediaHeaderGuards`, `TAG`, `SYSTEM_UI`, `EXPANDABLE_NOTIFICATION_ROW_CLASS`, `MIUI_MEDIA_HEADER_VIEW_CLASS`, `LOCKSCREEN_LYRIC_BUTTON_TAG`, `LOCKSCREEN_LYRIC_BUTTON_STATE_TAG`, `LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG`, `LOCKSCREEN_LYRIC_STATE_PREFS`, `LOCKSCREEN_LYRIC_STATE_SHOWING`, `LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SHORT_DELAY_MS`, `LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SETTLE_DELAY_MS`, `KEY_LOCKSCREEN_MINI_PLAYER_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `lyricReturnPresentation`, `syncedHyperMusicLyricsEnabled`, `systemUiTranslationCache`, `KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION`, `KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE`, `systemUiApplicationContext`, `lockscreenMediaKeyguardShowing`, `lockscreenLyricDrawableLoadFailureLogged`, `lockscreenRows`, `lockscreenHiddenRows`, `lockscreenMediaRows`, `lockscreenMediaHeaders`, `lockscreenLyricButtons`, `lockscreenLyricButtonHeaders`, `lockscreenMediaControllers`, `lockscreenMediaTransitionTargets`, `lockscreenMediaTransitionGenerations`, `lockscreenMediaTransitionBaseTranslationY`, `lockscreenMediaTextBaseTranslations`, `lockscreenMediaTransitionsRunning`, `LOCKSCREEN_MEDIA_HIDE_DURATION_MS`, `LOCKSCREEN_MEDIA_SHOW_DURATION_MS`, `LOCKSCREEN_MEDIA_TRANSITION_TRAVEL_DP`, `LOCKSCREEN_MEDIA_TRANSITION_START_SCALE`, `LOCKSCREEN_MEDIA_TRANSITION_HIDDEN_ALPHA`, `LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_ALPHA`, `LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_SCALE`

### 指纹视觉/动画

方法：

- `installFingerprintIconVisualHook`（原 12836 行）
- `installLockscreenFingerprintAnimationHook`（原 13009 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `MIUI_GXZW_ICON_VIEW_CLASS`, `MIUI_GXZW_ANIM_MANAGER_CLASS`, `FOD_DISMISS_ICON_METHOD`, `MIUI_GXZW_KEYGUARD_AUTHEN_FIELD`, `MIUI_GXZW_ANIMATION_ITEMS_FIELD`, `MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD`, `MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD`, `FINGERPRINT_HIDE_LOCKSCREEN`, `FINGERPRINT_HIDE_GLOBAL`, `LOCKSCREEN_HIDDEN_FINGERPRINT_ICON_RESOURCE`

### 首批底部文本

方法：

- `installLockscreenChargingTextHook`（原 13078 行）
- `installLockscreenBottomTextViewHook`（原 13774 行）
- `applyLockscreenBottomTextVisibility`（原 13850 行）
- `readViewField`（原 13887 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `KEYGUARD_INDICATION_CONTROLLER_CLASS`, `NOTIFICATION_NUM_STATE_VIEW_CLASS`, `NUM_STATE_VIEW_ANIMATE_EXT_CLASS`, `CHARGING_INDICATION_TYPE`, `KEY_HIDE_LOCKSCREEN_CHARGING_TEXT`, `KEY_LOCKSCREEN_BOTTOM_TEXT_MASK`, `LOCKSCREEN_TEXT_CHARGING`, `LOCKSCREEN_TEXT_DND`, `LOCKSCREEN_TEXT_NOTIFICATIONS`

### 白条

方法：

- `installLockscreenWhiteBarHook`（原 13121 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `KEY_LOCKSCREEN_WHITE_BAR_ENABLED`, `NAVIGATION_BAR_VIEW_CLASS`

### AOD/主题编辑器视频壁纸、模板及背景

方法：

- `installVideoWallpaperGlassSupportHook`（原 14004 行）
- `installAodLockscreenTemplateLimitHook`（原 14031 行）
- `installAodEditorBackgroundHook`（原 14104 行）
- `applyAodEditorBackground`（原 14166 行）
- `captureCurrentEditorWallpaper`（原 14186 行）
- `findViewByClassName`（原 14199 行）
- `drawableToBitmap`（原 14209 行）

原文件成员引用（常量、状态及缓存）：

`preferences`, `TAG`, `AOD`
