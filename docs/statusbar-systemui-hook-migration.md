# StatusBar / SystemUi 分阶段迁移审计

## 修改前基线和边界

本轮开始时 `git status --short`、`git diff --stat` 均为空，HEAD 为 `7574f118`
（Migrate LockScreen Hooks）。已阅读此前三份审计：`systemui-hook-migration.md`、
`notification-hook-migration.md`、`lockscreen-hook-migration.md`，并对照当前源文件的完整
状态栏信号/电池/图标实现、动态岛安装及发现链、共享可见性、主题、电源菜单、音量、
手势条、尺寸与圆角入口。下面的选择先于本轮 Kotlin 修改确定。

269 个已跟踪 app 源码/资源、语言和文档文件已保存 SHA256，模块保存完整快照。
上一轮 `IosShadeMaterial.java`、`ShadeLayer.java` 修复是受保护基线；不得回滚、覆盖或
顺便改写。没有新增设置、文案、按需初始化或性能优化；不迁移 Others。

## 唯一归属与保留决定

| 当前功能 / 目标 | 所有者与本轮处理 | 状态、配置及依赖 |
| --- | --- | --- |
| `MobileSignalController`、`NetworkControllerImpl`、现代移动图标 binder/view/interactor；双排信号、原信号隐藏、网络制式文字、大小/偏移/间距 | 阶段一迁往 `StatusBarHooks` | 完整迁移订阅/类型/活动 subId 缓存、presentation 弱映射、锁、网络回调与偏好监听标志、ThreadLocal 和 Drawable；共享父类反射通过类型明确的回调复用 |
| `MiuiPhoneStatusBarView`、`DarkIconManager`、`StatusBarRootFactory`、`HomeStatusBarViewBinderImpl`；图标居左及布局 | 阶段一迁往 `StatusBarHooks` | 构造器/参数/factory 缓存、tag 常量及安装标志一起移动；保留构造器捕获→attach→post→manager-ready→bind 后排序 |
| BatteryMeterView、三个电池 icon 类、TextView.setText、hollow onDraw、ImageView drawable 历史、状态栏 Wi-Fi/移动图标主题回调 | 阶段一迁往 `StatusBarHooks` | 电池 Drawable 历史、首个资源刷新记录、恢复 ThreadLocal 完整迁移；绘制时临时 paint alpha 与 finally 恢复不变；未使用的相关 helper 也保留实现，不删除 |
| 全局 `View.setVisibility` 与 edit-button attach | **原模块唯一注册点** | 同时处理控制中心编辑按钮、通知 `mini_window_bar`、状态栏文字与信号；分支顺序、短路求值和参数替换不动。仅三个信号只读判断委托新类；`statusBarVisibilityHookInstalled` 仍覆盖整段混合安装组 |
| 状态栏高度、信号/电池/Wi-Fi 尺寸资源 | **原模块** | `installDimensionHooks`/`replacementDp` 还处理音量、通知圆角、动态岛；不能复制 Resources 拦截或拆出新安装标志 |
| 锁屏运营商文字 | **原模块，Lockscreen** | 目标虽是 `ControlCenterCarrierText`，回调先判断 Keyguard layout，再应用 SIM/data subscription 配置；不是状态栏网络制式标签 |
| 灵动岛 `DynamicIslandBaseContentView` / `IslandIconViewHolder` 的外观、轮廓、小媒体图标，mini-bar 可见性/原生位移、布局回调、GlowEffect | 阶段二迁往 `SystemUiHooks` | 外观 provider 专属 `baseRadiusPx` 缓存随 inner class 移动；专属方法/类名/列表/配置常量一起移动。共享 `findDynamicIslandField` 保持原实现，通过回调调用；位移方法查找与布尔写入仅为 mini-bar 使用，可以完整迁移 |
| `installDynamicIslandHooks` 协调器、背景/原生材质/self-blur 与共享白名单 | **原模块** | `dynamicIslandHooksInstalled` 同时约束材质和白名单，不能给阶段二子组复制标志。保留原协调器、安装标志、插件发现时先置 true/失败回 false 的语义；仅替换选定子组调用 |
| `IslandSmoothingHook` / `IslandSpacingHook` 等已有独立类 | 原有所有者 | 不再迁移/重复实现；保持已存在的 lazy 持有、安装、定时补装和发现回调 |
| `MiuiMediaViewControllerImpl` 与 `MiuiIslandMediaViewBinderImpl` 媒体来源图标/标题/封面调整 | **原模块，通知/媒体共享** | 同一方法修改系统媒体通知和岛媒体，不能因包名含 statusbar 或功能名含 island 强归状态栏/SystemUi；保留 `mediaSourceIconHooksInstalled` 和原调用次数 |
| GlobalActionsPlugin / Component / Impl / CommandQueue | **原模块，SystemUi 与控制中心共享** | 顶部电源按钮读取同一个 `globalActionsLock`、manager/plugin/component/impl/queue 缓存；发现器和 show 尝试顺序共同构成协议，暂留 |
| 物理音量、AOSP fallback、MiBackgroundStyle/Util、长按展开 | **原模块，SystemUi 共享链** | `volumeGlassRuntime`、独立/全局配方、surface roots、参数/基线缓存、圆角类发现及 `volumeLongPress` 回调互用；`VolumePanelLongPress` 已是独立实现，不复制 |
| 全局手势条 controller/overlay/material | **原模块，SystemUi 共享链** | controller 由手势条安装器在首个位置调用，接着 View draw/visibility 与材质覆盖层；共享 `applySystemGlassMaterial`/加载器/反射。保留完整组，不只搬 controller 留下跨类的半条链 |
| 全局主题保护、共享 shade/material、焦点白名单、圆角/尺寸与类加载发现器 | **原模块，跨功能基础设施** | 多功能共享状态唯一持有；保持 ThreadLocal 重入、刷新 Handler、延迟任务、先后顺序与异常边界，不扩大私有成员可见性 |
| 锁屏媒体/歌词/封面、FOD、时钟/AOD/editor、景深/指纹、快捷入口/widget/PIN | **原模块和既有独立锁屏类** | 沿用上轮暂留决定；本轮不把其余锁屏功能归入 SystemUi |
| framework/system_server、Settings、MiSound、MiShare、第三方应用、背屏与 ThemeManager 应用功能 | **原模块，Others 或原专属分类** | 保留目标进程分支和提前 return；不创建 OthersHooks |

## 进程、加载器及安装次序

`onSystemServerStarting`、`onPackageLoaded` 继续属于 `HyperSystemUiModule`；所有包判断、
OsCompatibility 判断、远程 preferences 读取与提前 return 不变。仅 SYSTEM_UI 和
SYSTEM_UI_PLUGIN 共用的主分支发生局部委托替换，原有 AOD、ThemeManager、应用分支不变。

主分支仍按原顺序：控制中心头部→平滑岛→SystemUI 人脸→磁贴→记录 SystemUI loader/
媒体桥/主题激活→尺寸/通知颜色→圆角发现→控制中心编辑及定时补装→电源菜单→共享
插件发现→插件动态岛→SystemUI 媒体图标→音量→AOSP fallback→焦点白名单→通知限制→
插件白名单 fallback→锁屏/FOD/媒体/时钟/指纹/景深/底部文本/手势/快捷入口等→共享材质/
悬浮通知→全局主题保护→**共享 visibility（内部电池链）→双排信号→图标居左**→时钟材质
及插件主题保护。

状态栏三个安装组只在原 SYSTEM_UI 条件下执行，继续传 `param.defaultClassLoader` 和同一个
preferences。电池链位于原共享可见性方法的 runCatching 内：两项 View 钩子之后，
battery-theme→internal-text→drawable-history 顺序不变；该混合组 guard 留原处。
双排信号和居左的两个 companion guard 随新类迁移，仍在原安装方法返回后才置 true。
局部失败处理、hookCount 与“失败后仍置 true”的旧语义一律保留。

动态岛阶段二继续从原协调器分别在 layout、bottom-glow、mini-bar、appearance 原位置委托；
各方法保留原 loader，不新增注册路径。共享发现器仍先通知 control-center header、smoothing、
face unlock，再处理控制中心、电源菜单、动态岛和白名单，并保持 ThreadLocal finally 清理。
六个控制中心补装延迟、原媒体图标独立分支与动态岛内部调用次数全部保留。

## 依赖接口与初始化

新增两类依赖 `XposedModule` 基类及原 `hookDeoptimized` 的类型明确回调，不依赖具体
`HyperSystemUiModule` 类型，不相互调用，不通过反射读模块私有字段。
原通用反射方法不合并、不复制、不改变可见性。共享 TAG、包名和配置键以不可变参数传入，
专属 companion 状态仍按进程持有，实例不复制缓存。

特别保留 `stackedMobileDualContainerId = View.generateViewId()` 在原 companion 的原位置：
ID 生成是有副作用的类初始化操作，不因搬状态而改变它与模块其他初始化的顺序；新类
接受此原始 ID 作为不可变输入，实际 presentation/注册状态仅由 StatusBarHooks 持有。
新类的构造器只保存依赖，不读取配置、不注册 Hook，不增加 lazy 或重试机制。

## 分阶段验证门槛

阶段一完成后，先做方法/状态/常量逆向对照、整个剩余模块差异核对、受保护文件哈希检查，
再执行 `.\gradlew.bat :app:assembleDebug` 和 `git diff --check`；记录迁移和暂留清单。
成功后才创建 SystemUiHooks 和执行阶段二代码修改。

阶段二重复上述验证，并核对阶段一新增文件未变、新类 DEX 类定义及 APK ZIP 完整性。
本轮没有设备安装或 SystemUI 重启，构建/静态检查不得当成实机测试。

以下附录列出当前模块全部剩余安装方法的目标选择语句、直接成员调用和状态/常量引用。
附录是上述人工阅读与边界审查的交叉检查；字符串/方法名匹配本身不构成迁移依据。


## 修改前完整安装方法附录

### `installHook` (baseline line 322)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: 

Companion references: 

### `installAppNavigation` (baseline line 335)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `installXiaomiHealthWatchFaceTrialBypass`

Companion references: `TAG`

### `installAirDropForceEnableHooks` (baseline line 385)

Targets / selectors (read with the complete method, not standalone classification):

- `val availability = param.defaultClassLoader.loadClass(AIRDROP_AVAILABILITY_CLASS)`
- `val method = availability.getDeclaredMethod(methodName).apply { isAccessible = true }`
- `val ctaGate = param.defaultClassLoader.loadClass("com.miui.mishare.connectivity.R0")`
- `val ctaMethod = ctaGate.getDeclaredMethod("p", android.content.Context::class.java)`

Direct local calls: `installAirDropDeviceProfileHooks`, `hookDeoptimized`

Companion references: `AIRDROP_AVAILABILITY_CLASS`, `TAG`

### `installAirDropDeviceProfileHooks` (baseline line 411)

Targets / selectors (read with the complete method, not standalone classification):

- `val properties = Class.forName("android.os.SystemProperties", false, null)`
- `method.name in setOf("get", "getBoolean", "getInt", "getLong") &&`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installXiaomiHealthWatchFaceTrialBypass` (baseline line 464)

Targets / selectors (read with the complete method, not standalone classification):

- `val moduleClass = loader.loadClass("com.xiaomi.wearable.yrn.modules.WatchFaceModule")`
- `val promiseClass = loader.loadClass("com.facebook.react.bridge.Promise")`
- `val method = moduleClass.getDeclaredMethod(`
- `val resolve = promiseClass.getMethod("resolve", Any::class.java)`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installSettingsAppEntryHook` (baseline line 527)

Targets / selectors (read with the complete method, not standalone classification):

- `val settingsClass = runCatching { loader.loadClass("com.android.settings.MiuiSettings") }.getOrNull() ?: return`
- `it.name == "updateHeaderList" && it.parameterTypes.size == 1 && java.util.List::class.java.isAssignableFrom(it.parameterTypes[0])`
- `loader.loadClass("com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header")`

Direct local calls: `getLongField`, `getIntField`, `setLongField`, `setIntField`, `setObjectField`, `attachModuleResources`

Companion references: `SETTINGS_HEADER_ID`

### `installLiftToWakeScheduleHook` (baseline line 619)

Targets / selectors (read with the complete method, not standalone classification):

- `val cls = loader.loadClass("com.android.keyguard.injector.KeyguardSensorInjector")`

Direct local calls: `applyLiftToWakeSchedule`

Companion references: `TAG`

### `installSettingsEntryLabelHook` (baseline line 682)

Targets / selectors (read with the complete method, not standalone classification):

- `val adapter = runCatching { loader.loadClass(MIUI_SETTINGS_HEADER_ADAPTER) }.getOrNull() ?: return`
- `it.name == "onBindViewHolder" && it.parameterCount == 2 &&`
- `val getItem = runCatching { adapter.getMethod("getItem", Int::class.javaPrimitiveType) }.getOrNull() ?: return`

Direct local calls: 

Companion references: `MIUI_SETTINGS_HEADER_ADAPTER`

### `installSoundEffectHooks` (baseline line 710)

Targets / selectors (read with the complete method, not standalone classification):

- `val getBoolean = Class.forName("android.os.SystemProperties").getDeclaredMethod(`
- `val gate = loader.loadClass("z.m")`
- `gate.methods.firstOrNull { it.name == "B" && it.parameterCount == 0 }?.let { target ->`
- `val taGet = android.content.res.TypedArray::class.java.getDeclaredMethod(`
- `val getDrawable = Resources::class.java.getDeclaredMethod(`
- `val setImage = ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType)`
- `val fragCls = loader.loadClass("androidx.preference.PreferenceFragmentCompat")`
- `val onCreateView = fragCls.getMethod(`

Direct local calls: `audioLogoDrawable`, `attachAudioBadge`, `hookDeoptimized`

Companion references: 

### `installFrameworkHooks` (baseline line 932)

Targets / selectors (read with the complete method, not standalone classification):

- `it.name == "getMaxMiuiFreeFormStackCount" \|\|`
- `it.name == "getMaxMiuiFreeFormStackCountForFlashBack"`
- `cls.declaredMethods.filter { it.name == "onMiuiFreeFormStasckAdded" }.forEach { method ->`
- `val cls = loader.loadClass("android.app.ActivityTaskManager")`
- `cls.declaredMethods.filter { it.name == "supportsSplitScreen" }.forEach { method ->`
- `cls.declaredMethods.filter { it.name == "isResizeable" }.forEach { method ->`
- `cls.declaredMethods.filter { it.name == methodName }.forEach { method ->`
- `cls.declaredMethods.filter { it.name == methodName }.forEach { method ->`
- `val cls = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")`
- `cls.declaredMethods.filter { it.name == "retrieveSettings" }.forEach { method ->`
- `val cls = loader.loadClass("com.android.server.wm.WindowManagerService\$SettingsObserver")`
- `cls.declaredMethods.filter { it.name == "updateDevEnableNonResizableMultiWindow" }.forEach { method ->`
- `val target = cls.declaredMethods.firstOrNull { it.name == "needForegroundPin" && it.parameterCount == 1 }`
- `it.name == "safeMediaVolumeIndex" && it.parameterCount == 1`
- `it.name == "updateSafeMediaVolumeIndex" && it.parameterCount == 1`
- `val cls = loader.loadClass("com.android.server.audio.AudioService\$VolumeController")`
- `val target = cls.declaredMethods.firstOrNull { it.name == "suppressAdjustment" && it.parameterCount == 3 }`
- `cls.declaredMethods.filter { it.name == "getDarkModeAppList" }.forEach { method ->`
- `cls.declaredMethods.filter { it.name == "shouldShowInSettings" && it.parameterCount == 1 }.forEach { method ->`
- `val cls = loader.loadClass("android.content.Intent")`
- `cls.declaredMethods.filter { it.name == "setFlags" && it.parameterCount == 1 }.forEach { method ->`
- `cls.declaredMethods.firstOrNull { it.name == "isCTS" && it.parameterCount == 0 }?.let { method ->`
- `cls.declaredMethods.filter { it.name == name }.forEach { method ->`
- `val cls = loader.loadClass("com.android.server.locksettings.LockSettingsStrongAuth")`
- `val target = cls.declaredMethods.firstOrNull { it.name == "rescheduleStrongAuthTimeoutAlarm" }`
- `loader.loadClass(name).declaredMethods`
- `.filter { it.name in methods }`
- `val cls = loader.loadClass("com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$index")`
- `cls.declaredMethods.filter { it.name == "accept" }.forEach { deoptimize(it) }`
- `val cls = loader.loadClass("com.android.server.wm.DisplayContent\$$index")`
- `cls.declaredMethods.filter { it.name == "test" }.forEach { deoptimize(it) }`
- `val cls = loader.loadClass("com.android.server.input.KeyGestureController")`
- `it.name == "getScreenshotChordLongPressDelay" && it.parameterCount == 0`
- `val cls = loader.loadClass("com.android.server.wm.WindowState")`
- `val target = cls.declaredMethods.firstOrNull { it.name == "isSecureLocked" && it.parameterCount == 0 }`
- `cls.declaredMethods.filter { it.name == "notAllowCaptureDisplay" }.forEach { method ->`
- `val cls = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")`
- `cls.declaredMethods.filter { it.name == "registerScreenCaptureObserver" }.forEach { method ->`
- `val cls = loader.loadClass("com.android.server.wm.WindowManagerService")`
- `cls.declaredMethods.filter { it.name == "registerScreenRecordingCallback" }.forEach { method ->`
- `val cls = wifiLoader.loadClass("com.android.server.wifi.Utils")`
- `cls.declaredMethods.filter { it.name == "checkDeviceNameIsIllegalSync" }.forEach { method ->`
- `val manager = loader.loadClass("com.android.server.SystemServiceManager")`
- `manager.declaredMethods.filter { it.name == "loadClassFromLoader" && it.parameterCount == 2 }`
- `val cls = loader.loadClass(captureName)`
- `val argsCls = loader.loadClass(argsName)`
- `cls.declaredMethods.filter { it.name == name }.forEach { method ->`
- `val cls = loader.loadClass("com.android.server.display.DisplayControl")`
- `cls.declaredMethods.filter { it.name == "createVirtualDisplay" }.forEach { method ->`
- `val cls = loader.loadClass("com.android.server.display.VirtualDisplayAdapter")`
- `cls.declaredMethods.filter { it.name == "createVirtualDisplayLocked" }.forEach { method ->`

Direct local calls: `resolveMiuiServicesLoader`, `loadFrameworkClass`, `hookDeoptimized`, `hasHeadsetOutput`

Companion references: 

### `installMiSettingsHooks` (baseline line 1490)

Targets / selectors (read with the complete method, not standalone classification):

- `val cls = loader.loadClass("miui.util.FeatureParser")`
- `it.name == "getIntArray" && it.parameterCount == 1 &&`

Direct local calls: `hookDeoptimized`

Companion references: 

### `installSettingsAppScopeHooks` (baseline line 1512)

Targets / selectors (read with the complete method, not standalone classification):

- `val cls = loader.loadClass("com.android.settings.utils.StatusBarUtils")`
- `it.name == "isForceUseControlPanel" && it.parameterCount == 1`
- `loader.loadClass("miui.os.Build").getDeclaredField("IS_INTERNATIONAL_BUILD")`
- `loader.loadClass(className).declaredMethods`
- `.filter { it.name == "onSave" && it.parameterCount == 0 }`
- `loader.loadClass("com.android.settings.DeviceNameCheckManager").declaredMethods`
- `.filter { it.name == "getDeviceNameCheckResult" && it.parameterCount == 4 }`
- `val cls = loader.loadClass("com.android.settings.bluetooth.MiuiBTUtils")`
- `cls.declaredMethods.filter { it.name == "isInternationalBuild" && it.parameterCount == 0 }`
- `it.name == "isSupportNameComplianceCheck" && it.parameterCount == 1`

Direct local calls: `hookDeoptimized`

Companion references: 

### `installHyperMusicCoverGestureBridge` (baseline line 1955)

Targets / selectors (read with the complete method, not standalone classification):

- `val clockContainer = classLoader.loadClass("com.android.keyguard.clock.KeyguardClockContainer")`
- `.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }`

Direct local calls: `dispatchHyperMusicCoverState`, `syncHyperMusicCoverLyrics`, `refreshLockscreenLyricButtons`, `setLockscreenLyricsShowing`

Companion references: `TAG`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_HDR_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_KEEP_SCREEN_ON`, `syncedHyperMusicLyricsEnabled`, `hyperMusicCoverGestureBridgeInstalled`, `hyperMusicCoverPreferenceChangeListener`, `systemUiApplicationContext`, `activeLockscreenClockContainer`

### `installSuperXiaoAiHooks` (baseline line 2163)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: 

Companion references: `SUPER_XIAOAI_PHRASE`

### `installControlCenterEditButtonHook` (baseline line 2197)

Targets / selectors (read with the complete method, not standalone classification):

- `?.takeIf { it.name == CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS }`
- `?: runCatching { classLoader.loadClass(CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS) }.getOrNull())`
- `method.name == "available" &&`
- `method.name == "getListItems" &&`
- `method.name == "onBindViewHolder" && method.parameterCount == 0`
- `?.takeIf { it.name == CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS }`
- `?: runCatching { classLoader.loadClass(CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS) }.getOrNull())`
- `method.name == "getChildControllers" &&`

Direct local calls: `hookDeoptimized`, `installControlCenterMainPanelHook`, `installControlCenterHeaderLifecycleHook`, `hideBoundControlCenterEditButton`, `installControlCenterTouchHook`, `installControlCenterExpandLifecycleHook`, `installControlCenterTopButtons`

Companion references: `TAG`, `CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS`, `CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS`, `ADD_CONTROL_CENTER_TOP_BUTTONS_KEY`, `KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE`, `KEY_CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_MODE`, `KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_OPACITY`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_OPACITY`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_LUMINANCE`, `KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON`, `controlCenterEditButtonHookInstalled`, `controlCenterContentDistributorHookInstalled`, `controlCenterTopButtonsHookInstalled`, `controlCenterPreferenceListenerInstalled`, `controlCenterButtonsLock`, `controlCenterControllerClassLoader`, `controlCenterEditController`

### `installControlCenterMainPanelHook` (baseline line 2375)

Targets / selectors (read with the complete method, not standalone classification):

- `?.takeIf { it.name == CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS))`
- `method.name == "onCreate" && method.parameterCount == 0`
- `it.name == "getView" && it.parameterCount == 0`

Direct local calls: `hookDeoptimized`, `installControlCenterTopButtonsIntoRoot`

Companion references: `TAG`, `CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS`, `controlCenterMainPanelHookInstalled`, `controlCenterClassDiscoveryInProgress`

### `installControlCenterHeaderLifecycleHook` (baseline line 2421)

Targets / selectors (read with the complete method, not standalone classification):

- `?.takeIf { it.name == CONTROL_CENTER_HEADER_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_HEADER_CONTROLLER_CLASS)`
- `method.name in setOf("onCreate", "onMainPanelVisibleChanged", "onModeChanged")`

Direct local calls: `hookDeoptimized`, `hideControlCenterTopButtons`, `updateControlCenterTopButtonsProgress`, `installControlCenterTopButtons`

Companion references: `TAG`, `CONTROL_CENTER_HEADER_CONTROLLER_CLASS`, `controlCenterHeaderLifecycleHookInstalled`, `controlCenterClassDiscoveryInProgress`, `controlCenterButtonsLock`, `controlCenterEditController`

### `installControlCenterTouchHook` (baseline line 2478)

Targets / selectors (read with the complete method, not standalone classification):

- `?.takeIf { it.name == CONTROL_CENTER_TOUCH_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_TOUCH_CONTROLLER_CLASS)`
- `method.name == "onInterceptTouchEvent" && method.parameterCount == 1 &&`

Direct local calls: `hookDeoptimized`, `findControlCenterTopButtonHit`

Companion references: `TAG`, `CONTROL_CENTER_TOUCH_CONTROLLER_CLASS`, `controlCenterTouchHookInstalled`, `controlCenterClassDiscoveryInProgress`, `controlCenterButtonsLock`, `controlCenterActiveTouchButton`

### `installControlCenterEventHandlerHook` (baseline line 2531)

Targets / selectors (read with the complete method, not standalone classification):

- `val handlerClass = classLoader.loadClass(CONTROL_CENTER_EVENT_HANDLER_CLASS)`
- `method.name == "handleExpandEvent" && method.parameterCount >= 1 &&`

Direct local calls: `hookDeoptimized`, `findControlCenterTopButtonHit`, `performControlCenterTopButtonAction`

Companion references: `TAG`, `CONTROL_CENTER_EVENT_HANDLER_CLASS`, `controlCenterEventHandlerHookInstalled`, `controlCenterButtonsLock`, `controlCenterActiveTouchButton`

### `installControlCenterRootDispatchHook` (baseline line 2588)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `installControlCenterRootClickHook`

Companion references: 

### `installControlCenterRootClickHook` (baseline line 2596)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(View::class.java.getMethod("performClick"))`

Direct local calls: `startControlCenterTopButtonsCollapse`

Companion references: `TAG`, `controlCenterRootClickHookInstalled`, `controlCenterButtonsLock`, `controlCenterRoot`

### `installControlCenterExpandLifecycleHook` (baseline line 2618)

Targets / selectors (read with the complete method, not standalone classification):

- `?.takeIf { it.name == CONTROL_CENTER_EXPAND_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_EXPAND_CONTROLLER_CLASS)`
- `(method.name == "onExpandChange" && method.parameterCount == 3 &&`
- `(method.name == "hidePanel" && method.parameterCount == 2 &&`
- `(method.name == "onStop" && method.parameterCount == 0) \|\|`
- `(method.name == "onExpandFinish" && method.parameterCount == 1)`
- `if (method.name == "hidePanel") {`
- `it.name == "getAppearance" && it.parameterCount == 0`

Direct local calls: `hookDeoptimized`, `hideControlCenterTopButtons`, `updateControlCenterTopButtonsProgress`, `startControlCenterTopButtonsCollapse`, `controlCenterExpansionProgress`

Companion references: `TAG`, `CONTROL_CENTER_EXPAND_CONTROLLER_CLASS`, `controlCenterExpandLifecycleHookInstalled`, `controlCenterClassDiscoveryInProgress`, `controlCenterButtonsLock`, `controlCenterExpandController`

### `installControlCenterTopButtons` (baseline line 2876)

Targets / selectors (read with the complete method, not standalone classification):

- `it.name == "getEditButton" && it.parameterCount == 0`
- `mainPanelRef.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(mainPanelRef)`
- `mainPanel.javaClass.methods.firstOrNull { it.name == "getView" && it.parameterCount == 0 }?.invoke(mainPanel)`

Direct local calls: `findControlCenterWindowRoot`, `installControlCenterTopButtonsIntoRoot`, `readInstanceField`

Companion references: `TAG`, `controlCenterButtonsLock`, `controlCenterEditTarget`, `controlCenterControllerClassLoader`

### `installControlCenterTopButtonsIntoRoot` (baseline line 2933)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `installControlCenterRootDispatchHook`, `updateControlCenterTopButtonsProgress`, `controlCenterExpansionProgress`, `performControlCenterTopButtonAction`, `applyControlCenterTopButtonBackground`, `applyControlCenterTopButtonIconScale`, `controlCenterTopButtonsMaterialClassLoader`

Companion references: `TAG`, `ADD_CONTROL_CENTER_TOP_BUTTONS_KEY`, `KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE`, `CONTROL_CENTER_PLUS_TAG`, `CONTROL_CENTER_POWER_TAG`, `controlCenterButtonsLock`, `controlCenterRoot`, `controlCenterPlusButton`, `controlCenterPowerButton`, `controlCenterExpandController`, `controlCenterButtonsHiddenByCollapse`

### `installGlobalActionsHook` (baseline line 3295)

Targets / selectors (read with the complete method, not standalone classification):

- `val targetClass = alreadyLoadedClass?.takeIf { it.name == className }`
- `?: classLoader.loadClass(className)`
- `GLOBAL_ACTIONS_IMPL_CLASS -> method.name == "showGlobalActions" && method.parameterCount == 1`
- `GLOBAL_ACTIONS_COMPONENT_CLASS -> method.name in setOf("handleShowGlobalActionsMenu", "handleShowOrHideGlobalActionsMenu", "start")`
- `COMMAND_QUEUE_CLASS -> method.name == "showGlobalActionsMenu" && method.parameterCount == 0`

Direct local calls: `hookDeoptimized`, `readInstanceField`

Companion references: `TAG`, `GLOBAL_ACTIONS_PLUGIN_CLASS`, `GLOBAL_ACTIONS_COMPONENT_CLASS`, `GLOBAL_ACTIONS_IMPL_CLASS`, `COMMAND_QUEUE_CLASS`, `globalActionsHookedClasses`, `globalActionsHookInstalled`, `globalActionsLock`, `globalActionsPlugin`, `globalActionsManager`, `globalActionsComponent`, `globalActionsImpl`, `commandQueue`

### `installMusicControlWhitelistHook` (baseline line 3371)

Targets / selectors (read with the complete method, not standalone classification):

- `.mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }`
- `method.name == "a" &&`
- `method.name == "b" &&`
- `method.name == "c" &&`
- `.mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }`
- `method.name == "k" &&`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installRearScreenAppWidgetUnlockHooks` (baseline line 3494)

Targets / selectors (read with the complete method, not standalone classification):

- `val deviceUtils = classLoader.loadClass("com.android.thememanager.basemodule.utils.DeviceUtils")`
- `it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType`
- `val deviceHelper = classLoader.loadClass("miuix.os.DeviceHelper")`
- `it.name == "toq" && it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType`
- `val rearFunction = classLoader.loadClass("com.rearScreen.manager.RearScreenFunction")`
- `method.name in setOf("q", "toq")`
- `val supportGuard = classLoader.loadClass("com.rearScreen.miclaw.appfunction.common.DeviceSupportGuard")`
- `method.name == "isSupported" && method.parameterTypes.isEmpty() &&`
- `val localSettingUtils = classLoader.loadClass("o2.AbstractC0666c")`
- `method.name == "g" && method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&`
- `val properties = Class.forName("android.os.SystemProperties", false, null)`
- `method.name in setOf("get", "getBoolean", "getInt", "getLong") &&`
- `Class.forName("miuix.os.SystemProperties", false, null)`
- `method.name in setOf("get", "getBoolean", "getInt", "toq", "zy", "k") &&`
- `method.name in setOf("getInt", "getString") &&`
- `if (method.name == "getString") "1" else 1`
- `classLoader.loadClass("com.personalizedEditor.helper.settings.SettingRepository")`
- `method.name == "zy" && method.parameterTypes.size == 1 && method.returnType == Boolean::class.javaPrimitiveType`
- `?.firstOrNull { it.name in setOf("getKey", "k") && it.parameterTypes.isEmpty() }`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `REAR_SCREEN_APP_WIDGET_PROPERTY`, `REAR_SCREEN_APP_WIDGET_SETTING`, `REAR_SCREEN_THEME_WIDGET_SETTING`, `REAR_SCREEN_AOD_SETTING`, `REAR_SCREEN_AOD_MODE_SETTING`, `REAR_SCREEN_STOCK_REMINDER_SETTING`

### `installPersonalAssistantRearScreenAppCardHooks` (baseline line 3723)

Targets / selectors (read with the complete method, not standalone classification):

- `val wrapper = classLoader.loadClass("c8.d\$c")`
- `method.name == "a" && method.parameterTypes.size == 1 &&`
- `val commonParams = classLoader.loadClass("com.miui.personalassistant.network.util.a")`
- `method.name == "a" &&`
- `method.name == "put" &&`
- `val deviceInfo = classLoader.loadClass(`
- `method.name == "b" && method.parameterTypes.isEmpty() &&`
- `val systemInfo = classLoader.loadClass("com.miui.personalassistant.utils.z1")`
- `method.name == "b" && method.parameterTypes.isEmpty() &&`
- `val storeViewModel = classLoader.loadClass(`
- `method.name == "a" && method.parameterTypes.size == 2 &&`
- `val installedCache = classLoader.loadClass("com.miui.personalassistant.utils.n0")`
- `method.name == "d" && method.parameterTypes.isEmpty()`
- `val appBaseInfo = classLoader.loadClass("com.miui.personalassistant.utils.AppBaseInfo")`
- `method.name == "b" && method.parameterTypes.isEmpty() &&`
- `val packageUtils = classLoader.loadClass("com.miui.personalassistant.utils.m1")`
- `method.name == "i" && method.parameterTypes.contentEquals(`
- `val defaultConfig = classLoader.loadClass(`
- `method.name == "c" && method.parameterTypes.size == 2 &&`
- `?.firstOrNull { it.name == "getServiceKey" && it.parameterTypes.isEmpty() }`

Direct local calls: `hookDeoptimized`, `containsRearScreenRequestUrl`, `setStaticFieldValue`

Companion references: `TAG`, `personalAssistantRearScreenRequest`

### `installPersonalAssistantHongkongBuildProfile` (baseline line 4055)

Targets / selectors (read with the complete method, not standalone classification):

- `val unsafeClass = Class.forName("sun.misc.Unsafe")`
- `val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)`
- `val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long`
- `unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)`
- `val miuiBuild = Class.forName("miui.os.Build")`
- `val unsafeClass = Class.forName("sun.misc.Unsafe")`
- `val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)`
- `val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long`
- `unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)`

Direct local calls: 

Companion references: `TAG`

### `installSubScreenCenterAppWidgetState` (baseline line 4110)

Targets / selectors (read with the complete method, not standalone classification):

- `val config = classLoader.loadClass("o2.C0673j")`

Direct local calls: `setStaticFieldValue`

Companion references: `TAG`

### `installThemeManagerRearScreenFeatureGuards` (baseline line 4122)

Targets / selectors (read with the complete method, not standalone classification):

- `val addDao = classLoader.loadClass("com.rearScreen.aiapp.db.RearScreenAiAddDao_Impl")`
- `method.name == "zy" && method.parameterTypes.size == 2 &&`
- `val appliedRepository = classLoader.loadClass("com.rearScreen.aiapp.repository.AiAppAppliedRepository")`
- `method.name == "zy" && method.parameterTypes.size == 2 &&`
- `val type = classLoader.loadClass(className)`
- `method.name == methodName && method.returnType == Boolean::class.javaPrimitiveType`
- `val usage = classLoader.loadClass("com.android.thememanager.util.ai.AiUsageRepository")`
- `method.name == "isDeviceTrusted" && method.parameterTypes.size == 1 &&`
- `val guard = classLoader.loadClass("com.rearScreen.aiapp.manager.RearScreenAiAppGenerateGuard")`
- `method.name == "q" && method.parameterTypes.size == 1 &&`
- `method.name == "zy" && method.parameterTypes.size == 1 &&`
- `val router = classLoader.loadClass("com.android.thememanager.activity.ai.viewmodel.AiRouterVM")`
- `method.name == "zsr0" && method.parameterTypes.size == 1 && method.returnType == Any::class.java`
- `val appUtils = classLoader.loadClass("com.android.thememanager.library.util.app.AppUtils")`
- `method.name == "toq" && method.parameterTypes.isEmpty() &&`
- `val miuiUtils = classLoader.loadClass("com.android.thememanager.basemodule.utils.MiuiUtils")`
- `method.name == "zurt" && method.parameterTypes.isEmpty() &&`
- `val presetRepository = classLoader.loadClass("com.rearScreen.repository.RearScreenPresetRepository")`
- `method.name == "cdj" && method.parameterTypes.size == 1 &&`
- `val miuiVersion = classLoader.loadClass("com.android.thememanager.basemodule.utils.MiuiUtils\$MiuiVersion")`
- `method.name == "toq" && method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&`
- `val settingModule = classLoader.loadClass("com.personalizedEditor.helper.settings.RearScreenSettingModule\$Companion")`
- `method.name == "k" && method.parameterTypes.isEmpty() &&`
- `val interceptor = classLoader.loadClass(`
- `val listVm = classLoader.loadClass("com.rearScreen.viewModel.RearScreenListViewModel")`
- `method.name == "gbni" && method.parameterTypes.size == 1 &&`
- `method.name == "wo" && method.parameterTypes.size == 2 &&`
- `?.firstOrNull { it.name == "getCategoryType" && it.parameterTypes.isEmpty() }`
- `?.firstOrNull { it.name == "setShield" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }`
- `?.firstOrNull { it.name == "getItemList" && it.parameterTypes.isEmpty() }`
- `it.name == "getInnerTags" && it.parameterTypes.isEmpty()`
- `it.name == "setInnerTags" && it.parameterTypes.size == 1`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installCustomRearScreenWidgetRegistration` (baseline line 4468)

Targets / selectors (read with the complete method, not standalone classification):

- `val contextManager = classLoader.loadClass("com.android.thememanager.basemodule.context.AppContextManager")`
- `val context = contextManager.getMethod("q").invoke(null) as? Context ?: return@runCatching`
- `val widgetClass = classLoader.loadClass("com.xiaomi.subscreencenter.service.Widget")`
- `val subScreenClass = classLoader.loadClass("com.xiaomi.subscreencenter.service.SubScreen")`
- `val widgetFactory = widgetClass.methods.firstOrNull { it.name == "q" && it.parameterTypes.size == 7 }`
- `val subScreen = subScreenClass.getMethod("g", Context::class.java).invoke(null, context)`
- `val insert = subScreen.javaClass.methods.firstOrNull { it.name == "x2" && it.parameterTypes.size == 1 }`

Direct local calls: `customRearScreenWidgetRegistrationInstalled`

Companion references: `TAG`

### `installSoftGlassThemeSystemUiHook` (baseline line 4528)

Targets / selectors (read with the complete method, not standalone classification):

- `val materialUtils = classLoader.loadClass(MIUI_MATERIAL_UTILS_CLASS)`
- `hook(materialUtils.getMethod("onDefaultThemeChanged", Boolean::class.javaPrimitiveType))`

Direct local calls: 

Companion references: `TAG`, `MIUI_MATERIAL_UTILS_CLASS`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `themeOverrideReady`

### `installSoftGlassThemePluginHook` (baseline line 4552)

Targets / selectors (read with the complete method, not standalone classification):

- `val themeUtils = classLoader.loadClass(MIUI_THEME_UTILS_CLASS)`
- `hook(themeUtils.getMethod(methodName))`
- `hook(themeUtils.getMethod(methodName))`

Direct local calls: `forceThemeUtilsFlags`, `installSoftGlassThemePluginMaterialGuards`

Companion references: `TAG`, `MIUI_THEME_UTILS_CLASS`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `themeOverrideReady`

### `installSoftGlassThemeClassLoadGuard` (baseline line 4596)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(ClassLoader::class.java.getMethod("loadClass", String::class.java))`

Direct local calls: `installSoftGlassThemePluginClass`

Companion references: `TAG`, `MIUI_THEME_UTILS_CLASS`

### `installSoftGlassThemePluginClass` (baseline line 4614)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(themeUtils.getMethod(methodName))`
- `hook(themeUtils.getMethod(methodName))`

Direct local calls: `forceThemeUtilsFlags`, `installSoftGlassThemePluginMaterialGuards`

Companion references: `TAG`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `dynamicPluginThemeHookInstalled`, `themeOverrideReady`

### `installDefaultThemeStateGuard` (baseline line 4658)

Targets / selectors (read with the complete method, not standalone classification):

- `val themeClass = classLoader.loadClass("com.miui.utils.MiuiThemeUtils")`
- `hook(classLoader.loadClass("com.android.systemui.statusbar.phone.ConfigurationControllerImpl")`
- `.getMethod("onConfigurationChanged", android.content.res.Configuration::class.java))`

Direct local calls: 

Companion references: `TAG`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `themeOverrideReady`

### `installSoftGlassThemePluginMaterialGuards` (baseline line 4701)

Targets / selectors (read with the complete method, not standalone classification):

- `val blurCompat = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)`
- `hook(blurCompat.getMethod(`
- `val defaultThemeController = classLoader.loadClass(MIUI_DEFAULT_THEME_CONTROLLER_IMPL_CLASS)`
- `hook(defaultThemeController.getMethod("isDefaultTheme"))`

Direct local calls: 

Companion references: `TAG`, `MI_BLUR_COMPAT_CLASS`, `MIUI_DEFAULT_THEME_CONTROLLER_IMPL_CLASS`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `softGlassThemePluginMaterialHooksInstalled`, `themeOverrideReady`

### `installStatusBarVisibilityHook` (baseline line 4745)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType))`
- `hook(View::class.java.getDeclaredMethod("onAttachedToWindow"))`

Direct local calls: `isControlCenterEditButtonView`, `logControlCenterEditButtonHidden`, `installBatteryThemeAndTextHooks`, `installBatteryInternalTextHooks`, `installBatteryDrawableHistoryHook`, `isIndependentMobileTypeView`, `shouldHideSystemMobileSignal`, `isStackedSecondaryMobileRoot`, `hideSecondaryMobileRoot`

Companion references: `TAG`, `KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR`, `KEY_HIDE_STATUS_BAR_WIFI_STANDARD`, `KEY_HIDE_STATUS_BAR_CLOCK_TEXT`, `KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY`, `KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON`, `KEY_MOBILE_NETWORK_TYPE_MODE`

### `installBatteryThemeAndTextHooks` (baseline line 4849)

Targets / selectors (read with the complete method, not standalone classification):

- `val batteryViewClass = classLoader.loadClass(BATTERY_METER_VIEW_CLASS)`
- `.filter { it.name == "onMiuiThemeChanged" \|\| it.name == "updateResources" }`
- `it.name == "updateChargeAndText" \|\| it.name == "updateAll" \|\|`
- `it.name == "updateAll\u00241" \|\| it.name == "onBatteryStyleChanged"`
- `val iconClass = classLoader.loadClass(className)`
- `.filter { it.name == "onMiuiThemeChanged" \|\| it.name == "updateResources" }`

Direct local calls: `hookDeoptimized`, `hideBatteryText`, `shouldSkipBatteryThemeRefresh`, `captureBatteryDrawables`, `restoreBatteryDrawables`, `installStatusBarIconThemeGuards`

Companion references: `TAG`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `KEY_HIDE_STATUS_BAR_CLOCK_TEXT`, `BATTERY_METER_VIEW_CLASS`, `BATTERY_ICON_CLASS`, `BATTERY_INDICATOR_CLASS`, `BATTERY_HOLLOW_ICON_CLASS`

### `installBatteryInternalTextHooks` (baseline line 4970)

Targets / selectors (read with the complete method, not standalone classification):

- `.filter { it.name == "setText" && it.parameterTypes.isNotEmpty() }`
- `val hollowClass = classLoader.loadClass(`
- `.filter { it.name == "onDraw" && it.parameterTypes.size == 1 &&`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `KEY_HIDE_STATUS_BAR_CLOCK_TEXT`

### `installBatteryDrawableHistoryHook` (baseline line 5083)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java))`

Direct local calls: 

Companion references: `TAG`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `batteryDrawableHistory`, `restoringBatteryDrawable`

### `installStatusBarIconThemeGuards` (baseline line 5104)

Targets / selectors (read with the complete method, not standalone classification):

- `val root = classLoader.loadClass(className)`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `MODERN_STATUS_BAR_VIEW_CLASS`, `WIFI_VIEW_BINDER_CLASS`, `MOBILE_ICON_BINDER_CLASS`

### `installClockMaterialLimitHook` (baseline line 5198)

Targets / selectors (read with the complete method, not standalone classification):

- `val utilityClass = classLoader.loadClass(CLOCK_UTILITY_CLASS)`
- `it.name == CLOCK_UTILITY_METHOD && it.parameterCount == 3`
- `candidate.javaClass.getMethod("getClockEffect")`
- `bean?.javaClass?.getMethod("getClockEffect")?.invoke(bean) as? Int`
- `bean?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)`

Direct local calls: 

Companion references: `TAG`, `CLOCK_UTILITY_CLASS`, `CLOCK_UTILITY_METHOD`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`

### `installDynamicIslandHooks` (baseline line 5246)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `installDynamicIslandAppearanceHooks`, `installMediaSourceIconHooks`, `installDynamicIslandMiniBarHook`, `installFocusIslandWhitelistPluginHooks`, `installDynamicIslandBackgroundHooks`, `installDynamicIslandLayoutHooks`, `installDynamicIslandSelfBlurHook`, `installDynamicIslandExpandedMaterialHook`, `installDynamicIslandBottomGlowHook`

Companion references: `TAG`, `focusIslandWhitelistPluginHooksInstalled`

### `installDynamicIslandAppearanceHooks` (baseline line 5266)

Targets / selectors (read with the complete method, not standalone classification):

- `val baseContent = classLoader.loadClass(DYNAMIC_ISLAND_BASE_CONTENT_CLASS)`
- `it.name == "updateBackgroundBg" && it.parameterTypes.size == 2 && it.parameterTypes[0] == View::class.java`
- `val holder = classLoader.loadClass("miui.systemui.dynamicisland.module.IslandIconViewHolder")`
- `holder.declaredMethods.filter { it.name == "setSmallIcon" && it.parameterCount == 1 }.forEachIndexed { index, method ->`
- `holder.getMethod("getPicInfo").invoke(chain.thisObject)`
- `picInfo?.javaClass?.getMethod("getPic")?.invoke(picInfo) as? String`

Direct local calls: `hookDeoptimized`, `ExpandedIslandCornerProvider`, `isExpandedIslandCornerTarget`, `applySmallMediaIconCorner`, `findDynamicIslandField`

Companion references: `TAG`, `DYNAMIC_ISLAND_BASE_CONTENT_CLASS`

### `installMediaSourceIconHooks` (baseline line 5378)

Targets / selectors (read with the complete method, not standalone classification):

- `val targetClass = classLoader.loadClass(className)`
- `it.name == "bindMediaData" && it.parameterTypes.size == 1`

Direct local calls: `hookDeoptimized`, `applyMediaCardAdjustments`

Companion references: `TAG`, `KEY_HIDE_SYSTEM_MEDIA_SOURCE_ICON`, `KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON`, `KEY_SYSTEM_MEDIA_INFO_VERTICAL_OFFSET`, `KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET`

### `installDynamicIslandMiniBarHook` (baseline line 5457)

Targets / selectors (read with the complete method, not standalone classification):

- `val baseClass = classLoader.loadClass(DYNAMIC_ISLAND_BASE_CONTENT_CLASS)`
- `method.name == DYNAMIC_ISLAND_UPDATE_MINI_BAR_METHOD &&`

Direct local calls: `hookDeoptimized`, `findDynamicIslandField`, `findDynamicIslandMethod`, `setDynamicIslandBooleanField`

Companion references: `TAG`, `DYNAMIC_ISLAND_BASE_CONTENT_CLASS`, `DYNAMIC_ISLAND_CONTENT_CLASS`, `DYNAMIC_ISLAND_UPDATE_MINI_BAR_METHOD`, `DYNAMIC_ISLAND_UPDATE_MINI_BAR_TRANSLATION_METHOD`, `KEY_REMOVE_DYNAMIC_ISLAND_MEDIA_MINI_BAR_WHITELIST_LIMIT`

### `installFocusIslandWhitelistPluginHooks` (baseline line 5549)

Targets / selectors (read with the complete method, not standalone classification):

- `classLoader.loadClass("miui.systemui.notification.focus.SignatureChecker")`
- `it.name == "checkSignatures" && it.returnType == Boolean::class.javaPrimitiveType`
- `val settingsClass = classLoader.loadClass(PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS)`
- `hook(settingsClass.getMethod(name, String::class.java))`
- `hook(settingsClass.getMethod("canShowFocus", android.content.Context::class.java, String::class.java))`
- `val focusUtilsClass = classLoader.loadClass(FOCUS_NOTIFICATION_UTILS_CLASS)`
- `it.name == "canShowFocus" && it.parameterCount == 3`
- `val controllerClass = classLoader.loadClass(FOCUS_NOTIFICATION_CONTROLLER_CLASS)`
- `it.name == "fetchAuthResult" && it.parameterCount == 5`
- `sbn?.javaClass?.getMethod("getKey")?.invoke(sbn) as? String`
- `it.name == "onAuthSuccess" && it.parameterCount == 2`
- `val coordinatorClass = classLoader.loadClass(DYNAMIC_ISLAND_EVENT_COORDINATOR_CLASS)`
- `hook(coordinatorClass.getMethod("mediaIslandSupportMiniWindow", String::class.java))`
- `val stateCallbackClass = classLoader.loadClass(ISLAND_STATE_CALLBACK_CONTROLLER_CLASS)`
- `it.name == "buildPendingStateCallback" && it.parameterCount == 3`

Direct local calls: `hookDeoptimized`, `isFocusWhitelistBypassEnabled`, `allowIslandStateCallbackPackage`

Companion references: `TAG`, `PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS`, `FOCUS_NOTIFICATION_UTILS_CLASS`, `FOCUS_NOTIFICATION_CONTROLLER_CLASS`, `DYNAMIC_ISLAND_EVENT_COORDINATOR_CLASS`, `ISLAND_STATE_CALLBACK_CONTROLLER_CLASS`

### `installFocusIslandWhitelistSystemUiHooks` (baseline line 5715)

Targets / selectors (read with the complete method, not standalone classification):

- `val settingsClass = classLoader.loadClass(SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS)`
- `hook(settingsClass.getMethod(name, android.content.Context::class.java, String::class.java))`
- `val providerClass = classLoader.loadClass(NOTIFICATION_PROVIDER_PUBLIC_CLASS)`
- `providerClass.getMethod(`

Direct local calls: `isFocusWhitelistBypassEnabled`

Companion references: `TAG`, `SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS`, `NOTIFICATION_PROVIDER_PUBLIC_CLASS`

### `installDynamicIslandClassDiscovery` (baseline line 5776)

Targets / selectors (read with the complete method, not standalone classification):

- `val loadClassMethod = ClassLoader::class.java.getMethod("loadClass", String::class.java)`
- `// ClassLoader.loadClass(String, boolean) is protected on Android.  Looking it up`
- `// with getMethod() throws before either discovery hook is installed, which leaves`
- `val loadClassWithResolveMethod = ClassLoader::class.java.getDeclaredMethod(`
- `runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS) }`
- `runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS) }`
- `runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_EXPAND_CONTROLLER_CLASS) }`

Direct local calls: `controlCenterHooks`, `islandSmoothingHook`, `lockscreenFaceUnlockHook`, `installControlCenterEditButtonHook`, `installControlCenterMainPanelHook`, `installControlCenterHeaderLifecycleHook`, `installControlCenterTouchHook`, `installControlCenterEventHandlerHook`, `installControlCenterExpandLifecycleHook`, `installGlobalActionsHook`, `installDynamicIslandHooks`, `installFocusIslandWhitelistPluginHooks`

Companion references: `TAG`, `CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS`, `CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS`, `CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS`, `CONTROL_CENTER_EXPAND_CONTROLLER_CLASS`, `CONTROL_CENTER_EVENT_HANDLER_CLASS`, `GLOBAL_ACTIONS_PLUGIN_CLASS`, `GLOBAL_ACTIONS_COMPONENT_CLASS`, `GLOBAL_ACTIONS_IMPL_CLASS`, `COMMAND_QUEUE_CLASS`, `CONTROL_CENTER_TOUCH_CONTROLLER_CLASS`, `CONTROL_CENTER_HEADER_CONTROLLER_CLASS`, `DYNAMIC_ISLAND_BACKGROUND_CLASS`, `PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS`, `dynamicIslandHooksInstalled`, `focusIslandWhitelistPluginHooksInstalled`, `focusIslandWhitelistPluginInstalling`, `globalActionsHookedClasses`, `controlCenterEditButtonHookInstalled`, `controlCenterContentDistributorHookInstalled`, `controlCenterTopButtonsHookInstalled`, `controlCenterExpandLifecycleHookInstalled`, `controlCenterTouchHookInstalled`, `controlCenterHeaderLifecycleHookInstalled`, `controlCenterClassDiscoveryInProgress`, `systemUiClassLoader`

### `installDynamicIslandBackgroundHooks` (baseline line 5904)

Targets / selectors (read with the complete method, not standalone classification):

- `val backgroundClass = classLoader.loadClass(DYNAMIC_ISLAND_BACKGROUND_CLASS)`
- `"setDrawable" -> backgroundClass.getMethod(name, Drawable::class.java)`
- `else -> backgroundClass.getMethod(name, Int::class.javaPrimitiveType)`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `DYNAMIC_ISLAND_BACKGROUND_CLASS`, `expandedIslandMaterialSettings`

### `installDynamicIslandLayoutHooks` (baseline line 5930)

Targets / selectors (read with the complete method, not standalone classification):

- `val baseClass = classLoader.loadClass(DYNAMIC_ISLAND_BASE_CONTENT_CLASS)`
- `val reset = baseClass.getDeclaredMethod("reset").apply { isAccessible = true }`
- `val layoutClass = classLoader.loadClass(className)`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `DYNAMIC_ISLAND_BASE_CONTENT_CLASS`, `DYNAMIC_ISLAND_LAYOUT_CLASSES`

### `installDynamicIslandSelfBlurHook` (baseline line 5983)

Targets / selectors (read with the complete method, not standalone classification):

- `val blurClass = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)`
- `hook(blurClass.getMethod("setMiSelfBlur", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))`

Direct local calls: `expandedIslandBlurRadius`, `isDynamicIslandView`

Companion references: `TAG`, `MI_BLUR_COMPAT_CLASS`, `KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED`

### `installDynamicIslandExpandedMaterialHook` (baseline line 6009)

Targets / selectors (read with the complete method, not standalone classification):

- `val baseClass = classLoader.loadClass(DYNAMIC_ISLAND_BASE_CONTENT_CLASS)`
- `val method = baseClass.getDeclaredMethod("updateBackgroundBg", View::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }`

Direct local calls: `hookDeoptimized`, `applyExpandedIslandNativeMaterial`

Companion references: `TAG`, `DYNAMIC_ISLAND_BASE_CONTENT_CLASS`

### `installDynamicIslandBottomGlowHook` (baseline line 6054)

Targets / selectors (read with the complete method, not standalone classification):

- `val glowClass = classLoader.loadClass(DYNAMIC_ISLAND_GLOW_EFFECT_CLASS)`
- `glowClass.declaredMethods.filter { it.name == "startGlowEffect\$miui_dynamicisland_release" \|\| it.name == "setAlphaOfGlowEffect\$miui_dynamicisland_release" }.forEachIndexed { index, method ->`
- `runCatching { chain.thisObject?.javaClass?.getMethod("getMGlowEffectBottomView")?.invoke(chain.thisObject) }.getOrNull()?.let { (it as? View)?.alpha = 0f }`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `DYNAMIC_ISLAND_GLOW_EFFECT_CLASS`, `DYNAMIC_ISLAND_EXPANDED_VIEW_CLASS`, `KEY_DISABLE_MEDIA_ISLAND_BOTTOM_GLOW`

### `installShadeMaterialHooks` (baseline line 6198)

Targets / selectors (read with the complete method, not standalone classification):

- `val method = View::class.java.getMethod("setMiGlass", FloatArray::class.java)`
- `val method = View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)`
- `val method = View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)`
- `val method = View::class.java.getMethod("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType)`
- `val method = View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java)`
- `val clear = View::class.java.getMethod("clearMiBackgroundBlendColor")`
- `val add = View::class.java.getMethod("addMiBackgroundBlendColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)`
- `softGlassBaselines[view]?.keys?.removeAll { it.name == "setMiBackgroundBlendColors" }`
- `val method = View::class.java.getMethod("setMiSelfBlur", Int::class.javaPrimitiveType, ArrayList::class.java)`
- `val method = View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)`

Direct local calls: `hookDeoptimized`, `softGlassRuntime`, `volumeGlobalRecipes`, `softGlassBaselines`, `softGlassLegacyBlends`, `softGlassArrayPairs`, `softGlassRefreshHandler`, `softGlassRefreshing`, `softGlassBlendDispatching`, `softGlassRefresh`, `rememberSoftGlassBaseline`, `volumeGlobalGlassSelected`, `isSoftGlassSurface`, `isSoftGlassBlendSurface`, `usesStandaloneSoftGlass`, `updateStandaloneSoftGlass`, `softGlassBlendPoints`, `installFocusNotificationMaterialEnforcementHooks`, `installFocusNotificationBackgroundHook`, `shouldUseNormalNotificationMaterial`, `normalNotificationGlassParams`, `normalNotificationBlendPoints`, `isNotificationRowBackground`, `stackContainsClass`, `isHeadsUpNotificationSurface`, `isVolumePanelSurface`

Companion references: `TAG`

### `installLockscreenClockColonHook` (baseline line 6402)

Targets / selectors (read with the complete method, not standalone classification):

- `val method = classLoader.loadClass(CLOCK_BEAN_CLASS)`
- `.getDeclaredMethod(CLOCK_BEAN_IS_COLON_SHOW_METHOD)`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `CLOCK_BEAN_CLASS`, `CLOCK_BEAN_IS_COLON_SHOW_METHOD`

### `installLockscreenCarrierHideHook` (baseline line 6427)

Targets / selectors (read with the complete method, not standalone classification):

- `val carrierClass = classLoader.loadClass("com.android.systemui.controlcenter.shade.ControlCenterCarrierText")`
- `val method = carrierClass.getDeclaredMethod("shouldShow").apply { isAccessible = true }`
- `val slot = runCatching { carrier.javaClass.getMethod("getSlotId").invoke(carrier) as Int }`
- `val layoutClass = classLoader.loadClass("com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout")`
- `val onMeasure = layoutClass.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)`
- `layout.javaClass.getDeclaredMethod("getKeyguardHeaderLayout").apply { isAccessible = true }`
- `val slot = runCatching { child.javaClass.getMethod("getSlotId").invoke(child) as Int }`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installStatusBarIconsLeftHook` (baseline line 6522)

Targets / selectors (read with the complete method, not standalone classification):

- `val utils = classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiIconManagerUtils")`
- `val darkManagerClass = classLoader.loadClass("com.android.systemui.statusbar.phone.ui.DarkIconManager")`
- `val rootFactory = classLoader.loadClass("com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootFactory")`
- `val viewClass = classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")`
- `val attached = viewClass.methods.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }`
- `viewClass.methods.filter { it.name == "setDarkIconManager" && it.parameterCount == 1 }.forEach { setter ->`
- `val binderClass = classLoader.loadClass(`
- `method.name == "bind" && method.parameterCount > 0 &&`

Direct local calls: `hookDeoptimized`, `applyStatusBarIconsLeft`, `positionStatusBarLeftIconGroup`

Companion references: `TAG`, `statusBarDarkIconManagerFactory`, `statusBarDarkIconManagerConstructor`, `statusBarDarkIconManagerArgs`

### `installLockscreenBigClockWidthHook` (baseline line 6611)

Targets / selectors (read with the complete method, not standalone classification):

- `val allInOneUtilClass = classLoader.loadClass(ALL_IN_ONE_UTIL_CLASS)`
- `method.name == "computeForScreen"`
- `val method = classLoader.loadClass(CLOCK_DEPTH_AVOID_RULE_UTILS_CLASS)`
- `.single { it.name == "createEditRect\$default" && it.returnType == Rect::class.java }`

Direct local calls: `hookDeoptimized`, `widenedClockRect`, `restoreRequestedClockWidth`

Companion references: `TAG`, `CLOCK_DEPTH_AVOID_RULE_UTILS_CLASS`, `ALL_IN_ONE_UTIL_CLASS`, `bigClockWidthCalculationDepth`

### `installLockscreenBigClockEditorWidthHook` (baseline line 6675)

Targets / selectors (read with the complete method, not standalone classification):

- `val method = classLoader.loadClass(ALL_IN_ONE_TEMPLATE_VIEW_CLASS)`
- `.getDeclaredMethod("getMaxDragAllowedRect")`

Direct local calls: `hookDeoptimized`, `widenedClockRect`

Companion references: `TAG`, `ALL_IN_ONE_TEMPLATE_VIEW_CLASS`

### `installLockscreenClockHorizontalRectHook` (baseline line 6703)

Targets / selectors (read with the complete method, not standalone classification):

- `val baseClass = classLoader.loadClass("com.miui.clock.allInOne.AllInOneBase")`
- `.getDeclaredMethod("setClockViewRect", Rect::class.java)`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `clockHorizontalRectOffsets`, `clockHorizontalLiveOffsets`

### `installLockscreenClockHorizontalEditorHook` (baseline line 6754)

Targets / selectors (read with the complete method, not standalone classification):

- `val wallpaperClass = classLoader.loadClass(`
- `.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)`
- `wallpaperClass.getDeclaredMethod(`
- `classLoader.loadClass("com.miui.keyguard.editor.edit.wallpaper.ObserverClickListener"),`
- `val editorClass = classLoader.loadClass(ALL_IN_ONE_TEMPLATE_VIEW_CLASS)`
- `val touchMethod = editorClass.getDeclaredMethod("onObservedTouchEvent", MotionEvent::class.java)`
- `editorClass.declaredMethods.filter { it.name == "onMiuiClockViewCreated" }.forEachIndexed { index, method ->`
- `editorClass.methods.firstOrNull { it.name == "generateEditFrames" && it.parameterCount == 0 }`
- `hook(editorClass.getDeclaredMethod("getFramesComplete"))`

Direct local calls: `hookDeoptimized`, `handleLockscreenClockHorizontalTouch`, `findAllInOneEditorForWallpaper`, `findAllInOneEditorInObject`, `applyEditorClockHorizontalOffset`

Companion references: `TAG`, `ALL_IN_ONE_TEMPLATE_VIEW_CLASS`, `clockHorizontalEditorShifts`, `clockHorizontalEditorClockShifts`, `activeLockscreenClockEditor`, `lockscreenClockCombinedTouchHookInstalled`

### `installAodClockMaterialLimitHook` (baseline line 7063)

Targets / selectors (read with the complete method, not standalone classification):

- `val companionClass = classLoader.loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel\$Companion")`
- `it.name == "computeSupportedClockEffect" && it.parameterCount == 2 &&`
- `it.name == "shouldDisableGlassEffect" && it.parameterCount == 2`
- `val filterClass = classLoader.loadClass("com.miui.keyguard.editor.data.preset.FontFilterKt")`
- `val filterMethod = filterClass.getDeclaredMethod("getFILTER_SUPPORT_INFO").apply { isAccessible = true }`
- `val clockViewClass = classLoader.loadClass("com.miui.keyguard.editor.edit.base.BaseClockView")`
- `it.name == "effectDisable" && it.parameterCount == 1`
- `val effect = runCatching { filter?.javaClass?.getMethod("getFilterId")?.invoke(filter) as? Int }.getOrNull()`
- `val apiClass = classLoader.loadClass("com.miui.keyguard.editor.data.template.TemplateApiImpl")`
- `it.name == "processDepthVideo" && it.parameterCount == 3`
- `config?.javaClass?.getMethod("getLockscreenInfo")?.invoke(config)`
- `?.let { it.javaClass.getMethod("getClockInfo").invoke(it) }`
- `val effect = runCatching { clock?.javaClass?.getMethod("getClockEffect")?.invoke(clock) as? Int }.getOrNull()`
- `clock?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)?.invoke(clock, effect)`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`

### `installClockMaterialCapabilityHook` (baseline line 7173)

Targets / selectors (read with the complete method, not standalone classification):

- `val config = classLoader.loadClass("com.miui.clock.utils.DeviceConfig")`
- `it.name in setOf("supportGlassEffect", "supportDiffEffect", "supportDiffEffectAndGradientEffect") + aliases`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`

### `installThemeManagerEditorClockMaterialHook` (baseline line 7197)

Targets / selectors (read with the complete method, not standalone classification):

- `val companion = classLoader.loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel\$Companion")`
- `val configClass = classLoader.loadClass("com.miui.keyguard.editor.data.bean.CommonConfig")`
- `val method = companion.getDeclaredMethod("k", configClass, Int::class.javaPrimitiveType)`
- `companion.getDeclaredMethod("f7l8", Int::class.javaPrimitiveType, configClass).also { disable ->`
- `val filters = classLoader.loadClass("com.miui.keyguard.editor.data.preset.FontFilterKt")`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`

### `installThemeManagerClockMaterialLimitHook` (baseline line 7281)

Targets / selectors (read with the complete method, not standalone classification):

- `val templateApi = classLoader.loadClass(THEME_MANAGER_TEMPLATE_API_CLASS)`
- `it.name == THEME_MANAGER_TEMPLATE_API_METHOD && it.parameterCount == 3`
- `templateConfig?.javaClass?.getMethod("getLockscreenInfo")?.invoke(templateConfig)`
- `?.let { it.javaClass.getMethod("getClockInfo").invoke(it) }`
- `clockInfo?.javaClass?.getMethod("getClockEffect")?.invoke(clockInfo) as? Int`
- `clockInfo?.javaClass?.getMethod(`

Direct local calls: 

Companion references: `TAG`, `THEME_MANAGER_TEMPLATE_API_CLASS`, `THEME_MANAGER_TEMPLATE_API_METHOD`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`

### `installStackedMobileSignalHook` (baseline line 7425)

Targets / selectors (read with the complete method, not standalone classification):

- `val controllerClass = classLoader.loadClass(`
- `method.name == "notifyListeners" && method.parameterCount == 1`
- `method.name == "updateConnectivity" && method.parameterCount == 2`
- `val networkControllerClass = classLoader.loadClass(`
- `method.name == "setCurrentSubscriptionsLocked" && method.parameterCount == 1`
- `method.name == "bind" && method.parameterCount > 0 &&`
- `val interactorClass = classLoader.loadClass(`
- `method.name == "getMobileTypeName" && method.parameterCount == 1`
- `method.name == "constructAndBind"`
- `method.name == "isIconVisible" && method.parameterCount == 0 &&`
- `hook(ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java))`
- `hook(ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType))`
- `hook(ImageView::class.java.getMethod("setImageTintList", ColorStateList::class.java))`
- `.filter { it.name == "setColorFilter" }`
- `.firstOrNull { it.name == "clearColorFilter" && it.parameterCount == 0 }`
- `hook(View::class.java.getMethod("refreshDrawableState"))`
- `hook(TextView::class.java.getMethod("setText", CharSequence::class.java))`
- `.filter { it.name == "setTextColor" }`
- `method.name == "setSubId" && method.parameterTypes.contentEquals(`

Direct local calls: `hookDeoptimized`, `updateStackedMobileSubscription`, `updateStackedMobileSubscriptions`, `captureMobileNetworkTypeSource`, `registerStackedMobilePresentation`, `isStackedMobilePresentationSignal`, `shouldSuppressStackedMobileIcon`, `refreshStackedMobilePresentations`, `refreshStackedMobilePresentationForView`, `loadFirstClass`, `resolveMobileSignalColor`, `readInheritedField`

Companion references: `TAG`, `KEY_MOBILE_NETWORK_TYPE_MODE`, `KEY_MOBILE_NETWORK_TYPE_POSITION`, `KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC`, `KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT`, `KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A`, `KEY_MOBILE_NETWORK_TYPE_BOLD`, `INDEPENDENT_MOBILE_TYPE_TAG`, `MOBILE_ICON_BINDER_CLASSES`, `MODERN_MOBILE_VIEW_CLASSES`, `stackedMobileSignalLock`, `stackedMobilePreferences`, `stackedMobilePreferenceChangeListener`, `stackedMobilePreferenceListenerRegistered`, `stackedMobileNetworkController`, `stackedMobileNetworkTypes`, `stackedMobilePresentations`, `stackedMobileApplying`

### `installFocusNotificationMaterialEnforcementHooks` (baseline line 8759)

Targets / selectors (read with the complete method, not standalone classification):

- `val effectClass = classLoader.loadClass(className)`
- `method.name == "apply" && method.parameterCount == 2 &&`
- `val injectorClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS)`
- `.filter { it.name == "updateFullAodAnimState" }`
- `val effectClass = classLoader.loadClass(className)`
- `it.name == "apply" && it.parameterCount == 2 &&`

Direct local calls: `hookDeoptimized`, `applyNormalMediaNotificationEffect`, `applyNormalNotificationRowEffect`, `isFocusNotificationRow`, `isMediaNotificationView`, `readInstanceField`

Companion references: `TAG`, `EXPANDABLE_NOTIFICATION_ROW_CLASS`, `EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS`, `FOCUS_NOTIFICATION_EFFECT_CLASSES`, `MEDIA_NOTIFICATION_EFFECT_CLASSES`, `KEY_UNIFY_NOTIFICATION_MATERIAL`, `mediaMaterialApplying`

### `installFocusNotificationBackgroundHook` (baseline line 8966)

Targets / selectors (read with the complete method, not standalone classification):

- `val backgroundClass = classLoader.loadClass(NOTIFICATION_BACKGROUND_VIEW_CLASS)`
- `method.name == "setCustomBackground" && method.parameterCount == 1 &&`

Direct local calls: `hookDeoptimized`, `notificationTypeFor`, `isNotificationRowBackground`

Companion references: `TAG`, `SYSTEM_UI`, `NOTIFICATION_BACKGROUND_VIEW_CLASS`, `KEY_UNIFY_NOTIFICATION_MATERIAL`

### `installDepthEffectHook` (baseline line 9446)

Targets / selectors (read with the complete method, not standalone classification):

- `val thresholdClass = classLoader.loadClass(DEPTH_THRESHOLD_CLASS)`
- `val evaluatorClass = classLoader.loadClass(DEPTH_EVALUATOR_CLASS)`

Direct local calls: `installDepthAvoidanceBypass`

Companion references: `TAG`, `DEPTH_EVALUATOR_CLASS`, `DEPTH_THRESHOLD_CLASS`, `IMAGE_THRESHOLD_FIELD`, `THRESHOLD_RATE_FIELD`, `DEFAULT_DEPTH_IMAGE_THRESHOLD`, `UNLIMITED_DEPTH_IMAGE_THRESHOLD`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`

### `installDepthAvoidanceBypass` (baseline line 9494)

Targets / selectors (read with the complete method, not standalone classification):

- `val controllerClass = classLoader.loadClass(HIERARCHY_AVOID_CONTROLLER_CLASS)`
- `hook(controllerClass.getMethod("isHierarchyEnable"))`
- `controllerClass.getMethod(`

Direct local calls: `isUserHierarchyEnabled`

Companion references: `TAG`, `HIERARCHY_AVOID_CONTROLLER_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`

### `installLockscreenShortcutGlassHook` (baseline line 9533)

Targets / selectors (read with the complete method, not standalone classification):

- `val controllerClass = classLoader.loadClass(MIUI_SHORTCUT_CONTROLLER_CLASS)`
- `.filter { it.name == "addShortcutViews" && it.parameterCount == 1 }`

Direct local calls: `hookDeoptimized`, `scheduleLockscreenMiniPlayerInstallation`, `scheduleLockscreenWidgetInstallation`, `installShortcutGlassBackgrounds`, `applyLockscreenShortcutGeometry`

Companion references: `TAG`, `MIUI_SHORTCUT_CONTROLLER_CLASS`

### `installLockscreenWidgetSceneVisibilityHooks` (baseline line 9588)

Targets / selectors (read with the complete method, not standalone classification):

- `val mediatorInjector = classLoader.loadClass(`
- `it.name == "keyguardGoingAway" && it.parameterCount == 0`
- `val updateMonitor = classLoader.loadClass("com.android.keyguard.KeyguardUpdateMonitor")`
- `it.name == "setKeyguardGoingAway" && it.parameterCount == 1 &&`
- `val stateController = classLoader.loadClass(`
- `it.name == "notifyKeyguardState" && it.parameterCount == 2 &&`
- `val statusBarStateClass = classLoader.loadClass(`
- `it.name == "setIsDozing" && it.parameterCount == 1 &&`
- `val editorClass = classLoader.loadClass(KEYGUARD_EDITOR_HELPER_CLASS)`
- `it.name == "setEditorState" && it.parameterCount == 1`
- `val chargeClass = classLoader.loadClass(MIUI_CHARGE_ANIMATION_VIEW_CLASS)`
- `it.name == "addChargeView" && it.parameterCount == 0`
- `val hideMethods = chargeClass.declaredMethods.filter { it.name == "removeChargeView" }`
- `val listenerClass = classLoader.loadClass(CONTROL_CENTER_EXPAND_LISTENER_CLASS)`
- `it.name == "onExpandStateChanged" && it.parameterCount == 1`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`, `KEYGUARD_EDITOR_HELPER_CLASS`, `MIUI_CHARGE_ANIMATION_VIEW_CLASS`, `CONTROL_CENTER_EXPAND_LISTENER_CLASS`

### `installLockscreenPinCircleBackgroundHook` (baseline line 9778)

Targets / selectors (read with the complete method, not standalone classification):

- `val pinViewClass = classLoader.loadClass(KEYGUARD_PIN_VIEW_CLASS)`
- `val onFinishInflate = pinViewClass.getDeclaredMethod("onFinishInflate")`

Direct local calls: `installLockscreenPinCircleBackgrounds`

Companion references: `TAG`, `KEYGUARD_PIN_VIEW_CLASS`

### `installLockscreenPinCircleBackgrounds` (baseline line 9804)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `configureLockscreenPinLabels`, `applyLockscreenPinRowSpacing`, `idName`, `applyLegacyBackdropMaterial`, `applySystemGlassMaterial`

Companion references: `TAG`, `LOCKSCREEN_PIN_KEY_IDS`, `LOCKSCREEN_PIN_CIRCLE_TAG`, `LOCKSCREEN_PIN_CIRCLE_RIPPLE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`

### `installLockscreenMiniPlayer` (baseline line 9935)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `resolveLockscreenShortcutViews`, `miniPlayerAppearance`, `applyMiniPlayerMaterial`, `commonShortcutParent`, `findShortcutContainers`, `idName`, `lockscreenMediaNotificationMode`

Companion references: `TAG`, `KEY_LOCKSCREEN_MINI_PLAYER_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED`, `lockscreenMiniPlayerControllers`

### `installLockscreenWidget` (baseline line 10017)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `resolveLockscreenShortcutViews`, `commonShortcutParent`, `findShortcutContainers`, `applyLockscreenWidgetBatteryMaterial`, `applyLockscreenWidgetShortcutSurfaceMaterial`, `idName`

Companion references: `TAG`, `lockscreenWidgetControllers`

### `installLockscreenMusicLockscreenHook` (baseline line 10091)

Targets / selectors (read with the complete method, not standalone classification):

- `val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)`
- `it.name == "onKeyguardViewBind" && it.parameterCount == 1`
- `val updateVisibility = panelClass.getMethod("updateKeyguardElementsVisibility")`

Direct local calls: `hookDeoptimized`, `installLockscreenMusicLockscreen`

Companion references: `TAG`, `KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS`, `STATUS_BAR_STATE_KEYGUARD`, `lockscreenMusicLockscreenControllers`

### `installLockscreenMusicLockscreen` (baseline line 10177)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: 

Companion references: `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `lockscreenMusicLockscreenControllers`

### `installShortcutGlassBackgrounds` (baseline line 10353)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `findShortcutContainers`, `applyShortcutIconColorMode`, `applyLegacyBackdropMaterial`, `applySystemGlassMaterial`, `shortcutBackgroundMode`, `shortcutIconColorMode`

Companion references: `TAG`, `DEFAULT_SHORTCUT_GLASS_RADIUS`, `MIN_SHORTCUT_GLASS_RADIUS`, `MAX_SHORTCUT_GLASS_RADIUS`, `SHORTCUT_PURE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_SOFT_GLASS_COLOR`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_OPACITY`, `DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`, `SHORTCUT_BACKGROUND_NONE`, `SHORTCUT_BACKGROUND_PURE_COLOR`, `SHORTCUT_BACKGROUND_ADVANCED_MATERIAL`, `SHORTCUT_BACKGROUND_SOFT_GLASS`, `SHORTCUT_GLASS_TAG`, `KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS`, `KEY_SHORTCUT_PURE_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY`, `KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_SHORTCUT_SOFT_GLASS_COLOR`, `KEY_SHORTCUT_SOFT_GLASS_OPACITY`, `KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_LUMINANCE`

### `installLockscreenClockDateFollowHook` (baseline line 10899)

Targets / selectors (read with the complete method, not standalone classification):

- `val notificationTopChangeType = classLoader.loadClass(`
- `val animationClass = classLoader.loadClass(className)`
- `method.name == "notifStateChange" &&`

Direct local calls: `hookDeoptimized`, `syncLockscreenClockDate`

Companion references: `TAG`

### `installLockscreenNotificationHook` (baseline line 11083)

Targets / selectors (read with the complete method, not standalone classification):

- `val legacyFlowClass = classLoader.loadClass(FOD_SHELF_SPACE_FLOW_CLASS)`
- `hook(legacyFlowClass.getDeclaredMethod("invokeSuspend", Any::class.java))`
- `method.name == "collect" && method.parameterCount == 2`

Direct local calls: `findFodNotificationPositionFlows`, `installFodEnrollmentFlowOverride`, `createLockscreenNotificationPositionCollector`, `isNotificationFodPositionLimitRemoved`

Companion references: `TAG`, `FOD_SHELF_SPACE_FLOW_CLASS`

### `installFodEnrollmentFlowOverride` (baseline line 11177)

Targets / selectors (read with the complete method, not standalone classification):

- `val flowClass = classLoader.loadClass("kotlinx.coroutines.flow.Flow")`

Direct local calls: `createFodEnrollmentFlowOverride`

Companion references: `fodEnrollmentFlowOverrides`

### `installLockscreenMediaNotificationHook` (baseline line 11294)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `installLockscreenMediaRowVisibilityHook`, `installLockscreenMediaControllerVisibilityHook`, `installLockscreenMediaManagerBridgeHooks`, `installLockscreenMediaHeaderHook`, `installLockscreenLyricButtonHook`, `installLockscreenLyricButtonStatusBarStateHook`, `installLockscreenCustomizationMenuHook`, `installLockscreenMediaVisibilityProviderHooks`, `installLockscreenMediaPipelineHook`, `installTinyLockscreenMediaHook`, `installLockscreenMediaEffectRefreshHook`

Companion references: `TAG`

### `installLockscreenMediaRowVisibilityHook` (baseline line 11329)

Targets / selectors (read with the complete method, not standalone classification):

- `val rowClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_CLASS)`
- `val getEntry = rowClass.getMethod("getEntry")`
- `val setOnKeyguard = rowClass.getMethod("setOnKeyguard", Boolean::class.javaPrimitiveType)`
- `it.name == "setVisibility" && it.parameterTypes.contentEquals(`

Direct local calls: `shouldHideLockscreenMedia`, `isMediaRow`

Companion references: `TAG`, `EXPANDABLE_NOTIFICATION_ROW_CLASS`, `lockscreenRows`, `lockscreenHiddenRows`, `lockscreenMediaRows`

### `installLockscreenMediaControllerVisibilityHook` (baseline line 11394)

Targets / selectors (read with the complete method, not standalone classification):

- `val controllerClass = classLoader.loadClass(`
- `val setVisibility = controllerClass.getMethod(`

Direct local calls: `mediaHeaderOnActiveKeyguard`, `readInstanceField`, `shouldHideLockscreenMedia`, `installLockscreenMediaHeaderGuard`

Companion references: `TAG`, `lockscreenMediaHeaders`, `lockscreenMediaControllers`

### `installLockscreenMediaManagerBridgeHooks` (baseline line 11435)

Targets / selectors (read with the complete method, not standalone classification):

- `val managerClass = classLoader.loadClass("com.android.systemui.media.NotificationMediaManager")`
- `(it.name == "findAndUpdateMediaNotifications" \|\| it.name == "dispatchUpdateMediaMetaData") &&`
- `val updateRunnable = classLoader.loadClass(`
- `hook(updateRunnable.getMethod("run"))`

Direct local calls: `hookDeoptimized`, `readInstanceField`

Companion references: `TAG`

### `installLockscreenMediaHeaderHook` (baseline line 11489)

Targets / selectors (read with the complete method, not standalone classification):

- `val headerClass = classLoader.loadClass(`
- `val callbackClass = classLoader.loadClass(`
- `val viewVisibility = View::class.java.getMethod(`
- `method.name == "onMediaDataChanged" && method.parameterCount > 0`
- `val mediaViewHolderClass = classLoader.loadClass(`
- `val setMediaViewHolder = headerClass.getMethod(`
- `val controllerClass = classLoader.loadClass(`
- `it.name == "updateLayout\$1" && it.parameterCount == 0`

Direct local calls: `hookDeoptimized`, `mediaHeaderOnActiveKeyguard`, `readInstanceField`, `installLockscreenIslandArtworkBridge`, `bindLockscreenLyricButton`, `refreshLockscreenLyricButtons`, `shouldHideLockscreenMedia`, `applyLockscreenMediaPresentation`, `updateLockscreenMediaHeaderVisibility`, `scheduleLockscreenMediaPresentation`, `installLockscreenMediaHeaderGuard`, `installLockscreenMediaArtworkClick`

Companion references: `TAG`, `MIUI_MEDIA_HEADER_VIEW_CLASS`, `lockscreenMediaKeyguardShowing`, `lockscreenMediaHeaders`

### `installLockscreenIslandArtworkBridge` (baseline line 11694)

Targets / selectors (read with the complete method, not standalone classification):

- `val controllerClass = classLoader.loadClass(`
- `val mediaDataClass = classLoader.loadClass(`
- `val bindMediaData = controllerClass.getMethod("bindMediaData", mediaDataClass)`

Direct local calls: `readInstanceField`, `scheduleLockscreenMediaPresentation`, `installLockscreenMediaHeaderGuard`, `findLockscreenMediaHeader`

Companion references: `TAG`, `lockscreenMediaHeaders`

### `installLockscreenLyricButtonHook` (baseline line 11733)

Targets / selectors (read with the complete method, not standalone classification):

- `val controllerClass = classLoader.loadClass(`
- `val mediaDataClass = classLoader.loadClass(`
- `val bindMediaData = controllerClass.getMethod("bindMediaData", mediaDataClass)`
- `val holderClass = classLoader.loadClass(`
- `val attach = controllerClass.getMethod("attach", holderClass)`
- `val notificationControllerClass = classLoader.loadClass(`
- `val topMediaChanged = notificationControllerClass.getDeclaredMethod(`
- `val mediaActionClass = classLoader.loadClass(`
- `val bindButtonCommon = controllerClass.getDeclaredMethod(`
- `val utilsClass = classLoader.loadClass(`
- `val setSemanticButton = utilsClass.getDeclaredMethod(`

Direct local calls: `readInstanceField`, `bindLockscreenLyricButtonForHolder`, `bindLockscreenLyricButtonForController`, `shouldOwnLockscreenLyricSlot`

Companion references: `TAG`

### `installLockscreenLyricButtonStatusBarStateHook` (baseline line 12031)

Targets / selectors (read with the complete method, not standalone classification):

- `runCatching { classLoader.loadClass(name) }.getOrNull()`
- `method.name == "setState" && method.parameterTypes.firstOrNull() ==`

Direct local calls: `hookDeoptimized`, `refreshLockscreenLyricButtons`

Companion references: `TAG`

### `installLockscreenCustomizationMenuHook` (baseline line 12153)

Targets / selectors (read with the complete method, not standalone classification):

- `val interactorClass = classLoader.loadClass(`
- `val onLongPress = interactorClass.getMethod("onLongPress")`
- `val hideMenu = interactorClass.getMethod("hideMenu")`

Direct local calls: 

Companion references: `TAG`

### `installLockscreenMediaVisibilityProviderHooks` (baseline line 12182)

Targets / selectors (read with the complete method, not standalone classification):

- `val entryClass = classLoader.loadClass(`
- `val providerClass = classLoader.loadClass(className)`
- `method.name == "shouldHideNotification" &&`

Direct local calls: `hookDeoptimized`, `shouldFilterLockscreenMedia`, `isMediaEntry`

Companion references: `TAG`

### `installLockscreenMediaPipelineHook` (baseline line 12234)

Targets / selectors (read with the complete method, not standalone classification):

- `val filterClass = classLoader.loadClass(`
- `val shouldFilterOut = filterClass.getDeclaredMethod(`
- `classLoader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry"),`

Direct local calls: `shouldFilterLockscreenMedia`, `isMediaEntry`, `isKeyguardCoordinatorOnKeyguard`

Companion references: `TAG`

### `installTinyLockscreenMediaHook` (baseline line 12271)

Targets / selectors (read with the complete method, not standalone classification):

- `val dataStoreClass = classLoader.loadClass(`
- `it.name == "onMediaUpdate" && it.parameterCount == 1`
- `it.name == "merge" && it.parameterCount == 3`

Direct local calls: `shouldFilterLockscreenMedia`

Companion references: `TAG`

### `installLockscreenNativeClockScalerHook` (baseline line 12567)

Targets / selectors (read with the complete method, not standalone classification):

- `val timeView = classLoader.loadClass("com.miui.clock.allInOne.TimeView")`
- `method.name == "setSizeInternal" &&`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installLockscreenMediaEffectRefreshHook` (baseline line 12600)

Targets / selectors (read with the complete method, not standalone classification):

- `val binder = classLoader.loadClass(`
- `val bind = binder.getMethod(`
- `classLoader.loadClass(`
- `classLoader.loadClass(`

Direct local calls: `reapplyNativeMediaEffect`, `lockscreenMediaInteractors`

Companion references: `TAG`, `MIUI_MEDIA_HEADER_VIEW_CLASS`

### `installLockscreenMediaHeaderGuard` (baseline line 12680)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: `mediaHeaderOnActiveKeyguard`, `maintainLockscreenLyricButton`, `shouldHideLockscreenMedia`, `updateLockscreenMediaHeaderVisibility`, `lockscreenMediaHeaderGuards`

Companion references: 

### `installLockscreenMediaArtworkClick` (baseline line 12698)

Targets / selectors (read with the complete method, not standalone classification):



Direct local calls: 

Companion references: 

### `installFingerprintIconVisualHook` (baseline line 12835)

Targets / selectors (read with the complete method, not standalone classification):

- `val iconClass = classLoader.loadClass(MIUI_GXZW_ICON_VIEW_CLASS)`
- `val dismissIcon = iconClass.getMethod(FOD_DISMISS_ICON_METHOD)`
- `(method.name == "show" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))) \|\|`
- `(method.name == "showFingerprintIcon" && method.parameterCount == 0) \|\|`
- `(method.name == "setGxzwIconOpaque" && method.parameterCount == 0)`

Direct local calls: `hookDeoptimized`, `fingerprintHideMode`

Companion references: `TAG`, `MIUI_GXZW_ICON_VIEW_CLASS`, `FOD_DISMISS_ICON_METHOD`, `FINGERPRINT_HIDE_GLOBAL`

### `installSystemUiDepthHooks` (baseline line 12870)

Targets / selectors (read with the complete method, not standalone classification):

- `val thresholdClass = classLoader.loadClass(DEPTH_THRESHOLD_CLASS)`
- `val evaluatorClass = classLoader.loadClass(DEPTH_EVALUATOR_CLASS)`
- `val interactorClass = classLoader.loadClass(KEYGUARD_DEPTH_INTERACTOR_CLASS)`
- `hook(interactorClass.getMethod("updateAvoidStatus"))`
- `it.name == "setDepthTransitionAlpha" && it.parameterCount == 3`

Direct local calls: `forceDepthImageThreshold`, `installDepthDisplayStateHook`, `clearDepthAvoidState`, `installThirdPartyWallpaperDepthHook`

Companion references: `TAG`, `DEPTH_EVALUATOR_CLASS`, `DEPTH_THRESHOLD_CLASS`, `KEYGUARD_DEPTH_INTERACTOR_CLASS`, `DEFAULT_DEPTH_IMAGE_THRESHOLD`, `UNLIMITED_DEPTH_IMAGE_THRESHOLD`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`

### `installDepthDisplayStateHook` (baseline line 12938)

Targets / selectors (read with the complete method, not standalone classification):

- `val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)`
- `val updateElements = panelClass.getMethod("updateKeyguardElementsVisibility")`
- `hook(panelClass.getMethod("updateShowDepthState"))`

Direct local calls: 

Companion references: `KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`

### `installLockscreenFingerprintAnimationHook` (baseline line 13008)

Targets / selectors (read with the complete method, not standalone classification):

- `val managerClass = classLoader.loadClass(MIUI_GXZW_ANIM_MANAGER_CLASS)`
- `val iconResources = methods.filter { it.name == MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD }`
- `val recognizingItems = methods.filter { it.name == MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD }`

Direct local calls: `hookDeoptimized`, `fingerprintHideMode`

Companion references: `TAG`, `MIUI_GXZW_ANIM_MANAGER_CLASS`, `MIUI_GXZW_KEYGUARD_AUTHEN_FIELD`, `MIUI_GXZW_ANIMATION_ITEMS_FIELD`, `MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD`, `MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD`, `FINGERPRINT_HIDE_LOCKSCREEN`, `LOCKSCREEN_HIDDEN_FINGERPRINT_ICON_RESOURCE`

### `installLockscreenWhiteBarHook` (baseline line 13084)

Targets / selectors (read with the complete method, not standalone classification):

- `val navigationBarViewClass = classLoader.loadClass(NAVIGATION_BAR_VIEW_CLASS)`
- `val updateNavButtonIcons = navigationBarViewClass.getDeclaredMethod("updateNavButtonIcons")`
- `val getHomeHandle = navigationBarViewClass.getDeclaredMethod("getHomeHandle")`
- `val setHandleVisibility = dispatcherClass.getDeclaredMethod(`
- `val homeHandleId = classLoader.loadClass("com.android.systemui.R\$id")`

Direct local calls: 

Companion references: `TAG`, `KEY_LOCKSCREEN_WHITE_BAR_ENABLED`, `NAVIGATION_BAR_VIEW_CLASS`

### `installGlobalGestureHandleHook` (baseline line 13154)

Targets / selectors (read with the complete method, not standalone classification):

- `val handleClass = classLoader.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandle")`
- `val onDraw = handleClass.getDeclaredMethod("onDraw", Canvas::class.java)`
- `hook(handleClass.getDeclaredMethod("onAttachedToWindow"))`
- `hook(handleClass.getDeclaredMethod("onDetachedFromWindow"))`
- `val rotatedClass = classLoader.loadClass(`
- `hook(rotatedClass.getDeclaredMethod("onDraw", Canvas::class.java))`

Direct local calls: `drawCustomGestureHandle`

Companion references: `TAG`

### `installGlobalGestureControllerHook` (baseline line 13489)

Targets / selectors (read with the complete method, not standalone classification):

- `val controllerClass = classLoader.loadClass(`
- `controllerClass.declaredMethods.filter { it.name == "createNavigationBar" }.forEach { method ->`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installDimensionHooks` (baseline line 13531)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(Resources::class.java.getMethod("getDimension", Int::class.javaPrimitiveType))`
- `hook(Resources::class.java.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType))`
- `hook(Resources::class.java.getMethod("getDimensionPixelOffset", Int::class.javaPrimitiveType))`

Direct local calls: `replaceDimensionIfNeeded`, `replaceDimensionPixelIfNeeded`

Companion references: 

### `installHeadsUpNotificationSoftGlassHooks` (baseline line 13580)

Targets / selectors (read with the complete method, not standalone classification):

- `val blur = classLoader.loadClass(prefix + "HeadsUpNotificationBlurEffect")`
- `val rowBlur = classLoader.loadClass(prefix + "NotificationRowBlurEffect")`
- `val injector = row.javaClass.getMethod("getInjector").invoke(row)`
- `injector?.javaClass?.getMethod("getBackgroundNormal")?.invoke(injector) as? View`
- `val glassCompat = Class.forName(MI_GLASS_COMPAT_CLASS, false, classLoader)`
- `glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)`
- `glassCompat.getMethod(`
- `it.name == (if (clear) "clear" else "apply") && it.parameterCount == (if (clear) 1 else 2)`
- `blur.declaredMethods.filter { it.name == "apply" && it.parameterCount == 2 }.forEach { method ->`
- `blur.declaredMethods.filter { it.name == "clear" && it.parameterCount == 1 }.forEach { method ->`
- `val effect = classLoader.loadClass(prefix + className)`
- `effect.declaredMethods.filter { it.name == "apply" && it.parameterCount == 2 }.forEach { method ->`
- `effect.declaredMethods.filter { it.name == "clear" && it.parameterCount == 1 }.forEach { method ->`

Direct local calls: `hookDeoptimized`, `normalNotificationGlassParams`, `stackContainsClass`, `headsUpGlassDispatching`, `convertedHeadsUpRows`

Companion references: `TAG`, `MI_GLASS_COMPAT_CLASS`

### `installCornerRadiusHooks` (baseline line 13676)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(ClassLoader::class.java.getMethod("loadClass", String::class.java))`
- `hook(View::class.java.getMethod("setBackground", Drawable::class.java))`

Direct local calls: `controlCenterRadiusRuntime`, `installLoadedVolumePanelHook`, `installLoadedCornerRadiusHook`, `applyControlCenterDrawableRadius`

Companion references: 

### `installKnownCornerRadiusHooks` (baseline line 13706)

Targets / selectors (read with the complete method, not standalone classification):

- `classLoader.loadClass(className).also { targetClass ->`

Direct local calls: `installLoadedCornerRadiusHook`

Companion references: `TAG`, `TOP_BUTTONS_CLASS`, `MEDIA_PANEL_CLASS`, `SLIDER_VIEW_HOLDER_CLASS`, `BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS`, `SECONDARY_PANEL_CONTROLLER_BASE_CLASS`, `SECONDARY_VOLUME_PANEL_CLASS`, `QS_ITEM_VIEW_HOLDER_CLASS`, `DEVICE_CENTER_ENTRY`

### `installThirdPartyWallpaperDepthHook` (baseline line 13732)

Targets / selectors (read with the complete method, not standalone classification):

- `val wallpaperInfoClass = classLoader.loadClass(WALLPAPER_INFO_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getSupportSubject"))`
- `val hierarchyClass = classLoader.loadClass(LARGE_SCREEN_HIERARCHY_ENABLE_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getLargeScreenHierarchyEnable"))`

Direct local calls: 

Companion references: `TAG`, `WALLPAPER_INFO_CLASS`, `LARGE_SCREEN_HIERARCHY_ENABLE_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`

### `installAodThirdPartyWallpaperDepthHook` (baseline line 13776)

Targets / selectors (read with the complete method, not standalone classification):

- `val wallpaperInfoClass = classLoader.loadClass(AOD_WALLPAPER_INFO_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getSupportSubject"))`
- `val hierarchyClass = classLoader.loadClass(AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getLargeScreenHierarchyEnable"))`
- `val companionClass = classLoader.loadClass(WALLPAPER_CONTROLLER_COMPANION_CLASS)`
- `val pickColorMethod = companionClass.getMethod(`
- `result?.javaClass?.getMethod("setClockInfoStyle", Integer::class.java)`
- `result?.javaClass?.getMethod("setSignatureAlignment", Integer::class.java)`
- `val controllerClass = classLoader.loadClass(WALLPAPER_CONTROLLER_CLASS)`
- `hook(controllerClass.getMethod("needResetMagicType"))`

Direct local calls: 

Companion references: `TAG`, `AOD_WALLPAPER_INFO_CLASS`, `AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS`, `WALLPAPER_CONTROLLER_CLASS`, `WALLPAPER_CONTROLLER_COMPANION_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`

### `installVideoWallpaperGlassSupportHook` (baseline line 13837)

Targets / selectors (read with the complete method, not standalone classification):

- `val companionClass = classLoader.loadClass(`
- `(method.name == "isVideoSupportGlassFilter" \|\| method.name == "kja0")`

Direct local calls: `hookDeoptimized`

Companion references: `TAG`

### `installAodLockscreenTemplateLimitHook` (baseline line 13864)

Targets / selectors (read with the complete method, not standalone classification):

- `val historyDaoClass = classLoader.loadClass(`
- `val oldestHistoryMethod = historyDaoClass.getMethod("getOldestHistory", Int::class.javaPrimitiveType)`
- `val modelClass = classLoader.loadClass(`

Direct local calls: 

Companion references: `TAG`

### `installAodEditorBackgroundHook` (baseline line 13937)

Targets / selectors (read with the complete method, not standalone classification):

- `val activityClass = classLoader.loadClass("com.miui.keyguard.editor.EditorActivity")`
- `val init = activityClass.getDeclaredMethod("initContentView").apply { isAccessible = true }`
- `val wallpaperClass = classLoader.loadClass("com.miui.keyguard.editor.edit.wallpaper.CombinedWallpaperView")`
- `wallpaperClass.declaredMethods.filter { it.name == "switchWallpaperView" }.forEach { method ->`

Direct local calls: `hookDeoptimized`, `applyAodEditorBackground`, `captureCurrentEditorWallpaper`

Companion references: `TAG`

### `installVolumePanelHooks` (baseline line 14056)

Targets / selectors (read with the complete method, not standalone classification):

- `installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_PANEL_CONTROLLER_CLASS), preferences)`
- `installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_COLUMN_CLASS), preferences)`
- `runCatching { installLoadedVolumePanelHook(classLoader.loadClass(name), preferences) }`
- `runCatching { installLoadedVolumePanelHook(classLoader.loadClass("com.android.systemui.miui.volume.Util"), preferences) }`
- `runCatching { installLoadedVolumePanelHook(classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS), preferences) }`

Direct local calls: `volumeGlassRuntime`, `installBackgroundBlurRadiusDispatchHook`, `installVolumeNativeParameterHooks`, `installLoadedVolumePanelHook`

Companion references: `TAG`, `VOLUME_PANEL_CONTROLLER_CLASS`, `VOLUME_COLUMN_CLASS`, `MI_BACKGROUND_STYLE_CLASS`

### `installBackgroundBlurRadiusDispatchHook` (baseline line 14081)

Targets / selectors (read with the complete method, not standalone classification):

- `val styleClass = classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS)`
- `val method = styleClass.getMethod(`

Direct local calls: `hookDeoptimized`, `volumeGlobalGlassSelected`, `stackContainsClass`, `isVolumePanelSurface`

Companion references: `TAG`, `MI_BACKGROUND_STYLE_CLASS`, `KEY_VOLUME_PANEL_BLUR_RADIUS`, `KEY_VOLUME_PANEL_GLASS_STRENGTH`, `KEY_VOLUME_PANEL_MATERIAL_ENABLED`

### `installVolumeNativeParameterHooks` (baseline line 14130)

Targets / selectors (read with the complete method, not standalone classification):

- `hook(View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType))`
- `hook(View::class.java.getMethod(`
- `hook(View::class.java.getMethod("setMiGlass", FloatArray::class.java))`
- `hook(View::class.java.getMethod("setBackgroundBlurAlpha", Float::class.javaPrimitiveType))`

Direct local calls: `volumeGlassRuntime`, `logVolumeNativeParameterHit`, `invokeTwoIntSetter`, `isVolumePanelSurface`

Companion references: `TAG`, `KEY_VOLUME_PANEL_BLUR_RADIUS`, `KEY_VOLUME_PANEL_GLASS_STRENGTH`, `KEY_VOLUME_PANEL_BACKGROUND_OPACITY`, `volumeNativeParameterHooksInstalled`

### `installLoadedVolumePanelHook` (baseline line 14256)

Targets / selectors (read with the complete method, not standalone classification):

- `val bionics = targetClass.classLoader.loadClass("miui.systemui.util.MaterialMode\$Bionics")`
- `hookDeoptimized(targetClass.getDeclaredMethod("getMaterialMode"))`
- `it.name == "setMiBackgroundStyle" && it.parameterTypes.firstOrNull() == View::class.java`
- `it.name in setOf("isBionicsAdvancedMaterialEnabled", "isAdvancedMaterialEffective") &&`
- `.filter { it.name in VOLUME_PANEL_REFRESH_METHODS }`
- `if (!volumeGlobalGlassSelected() \|\| method.name in VOLUME_PANEL_LAYOUT_METHODS) {`
- `targetClass.declaredMethods.filter { it.name in setOf("onFinishInflate", "updateFooterVisibility") }.forEach { method ->`
- `it.name == "onTouchEvent" && it.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))`

Direct local calls: `hookDeoptimized`, `softGlassRuntime`, `volumeGlassRuntime`, `volumeMaterialDispatching`, `volumeGlobalGlassSelected`, `stackContainsClass`, `volumeLongPress`, `applyVolumePanelNativeTuning`, `readField`, `isVolumePanelSurface`

Companion references: `TAG`, `VOLUME_PANEL_CONTROLLER_CLASS`, `VOLUME_PANEL_REFRESH_METHODS`, `VOLUME_PANEL_LAYOUT_METHODS`, `MI_BACKGROUND_STYLE_CLASS`, `volumePanelHookedClasses`, `volumePanelSurfaceRoots`

### `installAospVolumePanelFallback` (baseline line 14350)

Targets / selectors (read with the complete method, not standalone classification):

- `val dialogClass = classLoader.loadClass(AOSP_VOLUME_DIALOG_CLASS)`
- `.filter { it.name == "onCreate" \|\| it.name == "show" }`
- `val window = chain.thisObject?.javaClass?.getMethod("getWindow")`

Direct local calls: `hookDeoptimized`, `applyVolumePanelRootTuning`

Companion references: `TAG`, `AOSP_VOLUME_DIALOG_CLASS`

### `installLoadedCornerRadiusHook` (baseline line 14573)

Targets / selectors (read with the complete method, not standalone classification):

- `hookDeoptimized(targetClass.getDeclaredMethod("updateContentBg", Boolean::class.javaPrimitiveType))`
- `val view = runCatching { target.javaClass.getMethod("getContentBg").invoke(target) as? View }.getOrNull()`

Direct local calls: `hookDeoptimized`, `readInstanceField`, `controlRadiusSettings`, `hookUnifiedRadiusSetter`, `hookMaterialStyle`, `hookDeviceCenterOutline`

Companion references: `TOP_BUTTONS_CLASS`, `MEDIA_PANEL_CLASS`, `SLIDER_VIEW_HOLDER_CLASS`, `BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS`, `SECONDARY_PANEL_CONTROLLER_BASE_CLASS`, `SECONDARY_VOLUME_PANEL_CLASS`, `MI_BACKGROUND_STYLE_CLASS`, `DEVICE_CENTER_ENTRY`, `cornerTargetClasses`
