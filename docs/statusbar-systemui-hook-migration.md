# StatusBar / SystemUi 分阶段迁移审计

## 2026-10-20 状态栏调整与图标导入：修改前边界

本次不是新的分类迁移。`HyperSystemUiModule` 继续拥有 SystemUI 包/进程分发、
`installDimensionHooks` 及 `Resources` 尺寸替换；既有尺寸配置键、默认值和读取时机不变。
现有状态栏尺寸 slider 仅从 `Status` 页面移动到新的“状态栏调整”二级页面，仍通过原
`HookSettings` 更新路径保存，不迁移或复制旧配置。新增图标导入配置、文件名、导入文件和
远程偏好同步全部由新的专用 Kotlin 文件持有，不进入 `HookSettings`。

时钟修复归 `StatusBarHooks`：安装点仍位于原 SYSTEM_UI 分支，使用同一个
`param.defaultClassLoader` 和远程 preferences。目标是
`MiuiStatusBarClockController.onMiuiThemeChanged(boolean)` 和
`onDensityOrFontScaleChanged()`；每个原方法保持调用一次，回调结束后
只在时钟尺寸开关启用时遍历其既有 `mClockListeners`，重新应用配置的 sp 字号。该补偿针对
主题回调在主题资源环境中重新执行 `FontSizeUtils.updateFontSize` 后偶发覆盖尺寸的问题；
不改变时钟文字、字体、颜色、监听器注册、主题回调次序或资源尺寸 hook。

图标替换也归 `StatusBarHooks`。模块入口只调用新的安装方法，不接管资源状态。
导入器识别 MTZ 外层组件、普通 ZIP 和独立 `com.android.systemui` ZIP，抽取
`com.android.systemui` 与可选 `miui.systemui.plugin` 中受支持的状态栏及快捷按钮位图，
按资源包、日/夜模式和资源名建立单一索引。SystemUI 进程通过模块应用的只读 Provider
读取归一化图标包；`StatusBarHooks` 在 `Resources.getDrawable*` / raw 资源读取链中按实际
resource package/type/entry name 查找替换。安装 guard、缓存和缓存锁只由 `StatusBarHooks`
持有；导入 revision 变化时重载缓存。资源安装入口覆盖既有 SYSTEM_UI 和 SYSTEM_UI_PLUGIN
两个包分支，进程内 guard 避免重复注册；插件资源仍使用其实际 `Resources` 和 ClassLoader，
不新增插件发现路径。图标尺寸继续由原模块共享资源入口处理，除 dimension 外，同时覆盖
`getValue` 和 `getFloat` 读取的 `status_bar_icon_scale_factor`。

最终复核补强仍限于导入功能：解析器跳过无关 MTZ 组件，仅读取支持的位图与已识别的
SystemUI/plugin 组件或 ZIP/MTZ 包装；预览按当前 BatteryIcon 的密度帧高切取电池 sprite，
优先使用主信号/主 WiFi 素材。Provider 尚不可读时不提交空缓存 revision，保留后续读取机会。
这不改变既有主题保护、插件发现器或分类迁移的状态。

依赖限定为模块应用 Provider URI、Android `Resources`/drawable API 与类型明确的偏好键。
不复制主题保护实现，不修改全局主题、iOS Shade、共享材质或控制中心安装器。主要风险是
厂商资源名在其他 SystemUI/插件版本变化、主题含 XML 而非位图 drawable、以及宽幅电池
sprite 的 raw 解码路径差异；未知资源保持原结果，解析失败不启用导入。构建只能验证编译与
打包，主题切换、深浅色、状态栏和控制中心实际替换仍需设备测试。

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

## 阶段一结果（阶段二源文件创建前）

最终验证：`.\gradlew.bat :app:assembleDebug` 成功（19s，40 tasks：6 executed、34 up-to-date）；随后 `git diff --check` 通过。

完整逆向对照通过：67 个方法、3 个顶层类型、35 个 companion 声明；其他 268 个基线文件 SHA256 未变。原锁屏时钟缩放对象保持原位，生成 View ID 的时机保持原位。无实机验证。

迁移方法（保留原方法名；新类另外提供两个包装入口，承接原安装 guard）：

- `isBatteryPercentageView`
- `installBatteryThemeAndTextHooks`
- `hideBatteryText`
- `shouldSkipBatteryThemeRefresh`
- `installBatteryInternalTextHooks`
- `captureBatteryDrawables`
- `restoreBatteryDrawables`
- `installBatteryDrawableHistoryHook`
- `installStatusBarIconThemeGuards`
- `captureImageViewDrawables`
- `restoreImageViewDrawables`
- `findViewsByResourceNames`
- `installStatusBarIconsLeftHook`
- `applyStatusBarIconsLeft`
- `positionStatusBarLeftIconGroup`
- `installStackedMobileSignalHook`
- `updateStackedMobileSubscription`
- `updateStackedMobileSubscriptions`
- `captureMobileNetworkTypeSource`
- `captureMobileSignalLayout`
- `resolveDataSim`
- `ensureIndependentMobileType`
- `captureHorizontalMargins`
- `applyHorizontalMargins`
- `restoreIndependentMobileType`
- `applyIndependentMobileTypeTypeface`
- `applyIndependentMobileType`
- `formatIndependentMobileType`
- `isUsingMobileData`
- `anchorSystemMobileTypeToDualSignal`
- `restoreSystemMobileType`
- `readCurrentFlowValue`
- `registerStackedMobilePresentation`
- `registerMobileNetworkStateCallback`
- `isStackedMobilePresentationSignal`
- `isIndependentMobileTypeView`
- `enforceSystemMobileSignalVisibility`
- `shouldHideSystemMobileSignal`
- `isDescendantOf`
- `isStackedSecondaryMobileRoot`
- `shouldSuppressStackedMobileIcon`
- `refreshStackedMobilePresentations`
- `refreshStackedMobilePresentationForView`
- `scheduleStackedMobilePresentationRefresh`
- `applyStackedMobilePresentation`
- `isStackedMobileDualSim`
- `stackedMobileRenderOrder`
- `ensureDualMobileSignal`
- `resolveDualMobileSignalWidth`
- `updateDualMobileSignalLayout`
- `configureDualMobileSignalConstraints`
- `copyLayoutParams`
- `getLayoutParamInt`
- `setLayoutParamInt`
- `setDualMobileSignalVisibility`
- `restoreDualMobileSignal`
- `hideSecondaryMobileRoot`
- `restoreSecondaryMobileRoot`
- `requestStackedMobileParentLayout`
- `loadFirstClass`
- `setStackedMobileViewVisibility`
- `invokeInt`
- `findViewByEntryName`
- `resolveMobileSignalColor`
- `resolveMobileSignalColorFilter`
- `resolveDrawableTintList`
- `resolveColorFilterColor`

迁移类型：`StackedMobileSubscription`, `StackedMobilePresentation`, `StackedMobileDrawable`。

迁移 companion 声明：`BATTERY_HOLLOW_ICON_CLASS`, `BATTERY_ICON_CLASS`, `BATTERY_INDICATOR_CLASS`, `BATTERY_METER_VIEW_CLASS`, `INDEPENDENT_MOBILE_TYPE_TAG`, `KEY_MOBILE_NETWORK_TYPE_BOLD`, `KEY_MOBILE_NETWORK_TYPE_CUSTOM_TEXT`, `KEY_MOBILE_NETWORK_TYPE_DISPLAY_LOGIC`, `KEY_MOBILE_NETWORK_TYPE_POSITION`, `KEY_MOBILE_NETWORK_TYPE_SHRINK_5GA_A`, `LEFT_STATUS_BAR_MANAGER_TAG`, `MOBILE_ICON_BINDER_CLASS`, `MOBILE_ICON_BINDER_CLASSES`, `MODERN_MOBILE_VIEW_CLASSES`, `MODERN_STATUS_BAR_VIEW_CLASS`, `WIFI_VIEW_BINDER_CLASS`, `batteryDrawableHistory`, `batteryResourceRefreshSeen`, `restoringBatteryDrawable`, `stackedMobileActiveSubscriptionIds`, `stackedMobileApplying`, `stackedMobileNetworkCallbackRegistered`, `stackedMobileNetworkController`, `stackedMobileNetworkTypes`, `stackedMobilePreferenceChangeListener`, `stackedMobilePreferenceListenerRegistered`, `stackedMobilePreferences`, `stackedMobilePresentations`, `stackedMobileSignalHookInstalled`, `stackedMobileSignalLock`, `stackedMobileSubscriptions`, `statusBarDarkIconManagerArgs`, `statusBarDarkIconManagerConstructor`, `statusBarDarkIconManagerFactory`, `statusBarIconsLeftHookInstalled`。

暂留：全局 View 可见性混合 Hook/安装标志、共享 Resources 尺寸拦截、锁屏运营商文字；通用反射与反优化、共享配置键仍由原模块唯一持有。三个信号判断和电池安装在原调用点委托。两个专属安装 guard 的写入点与异常语义不变。

## 阶段二结果

迁移 8 个方法和 1 个内部类；保持每个完整实现，仅替换 Xposed API 接收者、共享不可变输入名称及 4 个委托入口的可见性。

| 实际迁移成员 | 目标 / 职责 |
| --- | --- |
| `installDynamicIslandAppearanceHooks` | `DynamicIslandBaseContentView.updateBackgroundBg` 的原参数筛选、`IslandIconViewHolder.setSmallIcon`；原有专属轮廓实现原样移动，不改写共享材质或圆角分发 |
| `ExpandedIslandCornerProvider` | 原 outline provider 包装及 `baseRadiusPx` 缓存，缓存仍属于每个 provider 实例 |
| `isExpandedIslandCornerTarget` | expanded/fake expanded 资源名和展开视图类判断 |
| `applySmallMediaIconCorner` | 原小媒体图标轮廓偏移、clip 和 invalidate |
| `installDynamicIslandMiniBarHook` | `DynamicIslandBaseContentView.updateMiniBar`，保持参数/返回类型筛选、配置条件、两项布尔字段、miniBar 可见性及原生 translation 调用次数 |
| `findDynamicIslandMethod` | mini-bar 唯一使用的继承链方法查询，未新增缓存 |
| `setDynamicIslandBooleanField` | mini-bar 专属字段写入，继续通过原模块的字段查询实现 |
| `installDynamicIslandLayoutHooks` | 原 reset 高度对齐及三个 content 类的 `updateBigIslandLayout*` 回调；原有无可见副作用的回调也保留 |
| `installDynamicIslandBottomGlowHook` | `DynamicGlowEffectView` 的两个原光效方法及原返回值后处理 |

迁移 8 个专属 companion 声明：`DYNAMIC_ISLAND_GLOW_EFFECT_CLASS`、
`DYNAMIC_ISLAND_EXPANDED_VIEW_CLASS`、`DYNAMIC_ISLAND_CONTENT_CLASS`、
`DYNAMIC_ISLAND_UPDATE_MINI_BAR_METHOD`、`DYNAMIC_ISLAND_UPDATE_MINI_BAR_TRANSLATION_METHOD`、
`DYNAMIC_ISLAND_LAYOUT_CLASSES`、`KEY_REMOVE_DYNAMIC_ISLAND_MEDIA_MINI_BAR_WHITELIST_LIMIT`、
`KEY_DISABLE_MEDIA_ISLAND_BOTTOM_GLOW`。配置值/默认值未变；外观设置仍由原设置文件持有。

原模块仍唯一持有 `installDynamicIslandHooks`、`dynamicIslandHooksInstalled`、
`installDynamicIslandClassDiscovery`、`findDynamicIslandField` 及共享包名、基础内容类名。
不为四个委托子组新设安装标志。原协调器保留
background → layout → expanded material → bottom glow → self blur → media source → mini bar → appearance → whitelist
的顺序；原发现器置位/失败复位和 plugin 分支安装后置位语义不变。媒体来源图标的两条原安装调用路径完整保留。

两个新增类仅依赖 `XposedModule` 基类及类型明确的原实现回调；不相互引用，也不依赖具体
`HyperSystemUiModule` 类型。原私有反射方法未扩大可见性，不通过反射访问模块私有字段。
阶段一的 `StatusBarHooks.kt` 在整个阶段二 SHA256 不变。工作期间检测到外部已生成阶段一提交
`61b93c29`（Migrate Status Bar Hooks），核实其源码与已验证的阶段一内容一致，未重置或覆盖该提交。

第二次独立执行 `.\gradlew.bat :app:assembleDebug`：**BUILD SUCCESSFUL in 19s**，
40 tasks：6 executed、34 up-to-date。仍有原有 Kotlin/Gradle 警告，没有编译或打包错误。
构建后 `git diff --check` 通过。

## 最终静态与产物核验

- 75 个迁移方法、3 个顶层类型、1 个内部类和 43 个 companion 声明与修改前完整源码逆向对照通过。
- 所有剩余原模块成员，撤销明确的委托替换后，与基线逐项一致；未迁移的常量/状态声明也一致。
- 三份源码的全部 `.setId(...)` 表达式多重集合与原文件一致；两个状态栏专属 guard 仅保留一个写入路径；动态岛共享 guard 和原生命周期/进程分发不变。
- 其余 268 个基线文件 SHA256 未变，包括 `IosShadeMaterial.java`、`ShadeLayer.java`、既有控制中心/通知/锁屏实现和设置文件。三个语言 JSON 均可解析，没有文案或配置变更。
- APK ZIP 完整性通过；12 个 DEX 中类定义无重复，确认原模块、两新增类、三个既有拆分类及新的 provider 均有实际类定义；旧模块名下的 provider 类定义已不存在。

生成 APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
大小：122561613 字节。
SHA256：`97884ed30ceb72da6d9190aa46983cb656e272e96196dee80b8ba7fadcc34753`。

本轮修改文件：`HyperSystemUiModule.kt`、新增 `StatusBarHooks.kt`、新增 `SystemUiHooks.kt`、
本审计文档（其中阶段一已由外部提交保存）。未修改其他分类或此前行为修复。

## 延期与未实测范围

继续保留共享 visibility/resources/radius/theme/material、动态岛背景与材质、焦点白名单、
通知/岛共享媒体图标、全局电源菜单、音量和全局手势条完整链路。具体耦合原因见前面的归属表；
本轮不以搬迁数量为目标扩展接口，不继续拆分 Others 或既有控制中心/通知/锁屏组。

**没有实机测试。** 构建和静态核验不能证明设备运行行为：仍需验证双 SIM 切换、网络类型变更、
偏好实时更新、图标重建/居左、深浅色与主题切换后的电池/信号恢复；动态岛 plugin 首次加载及重建、
mini-bar 原生位移、图标/展开轮廓和底部光效；以及通知、控制中心、锁屏、电源菜单、音量及共享材质的交互。
此前灰色遮罩修复源码保持不变，其设备回归也未在本轮执行。未安装 APK、重启或终止 SystemUI。
本轮仅完成结构迁移，不宣称性能提升。

### 本次新增功能的版本证据与调用边界

- 当前反编译 `ControlCenterStatusBarIcon` 明确调用 `setLayoutFromTag(6)`，
  `ControlCenterFakeStatusIcons` 使用 5，`MiuiPhoneStatusBarView` 使用 0，锁屏使用 1。
  百分比覆盖作用于状态栏 0、控制中心 6 及其过渡副本 5，不更改锁屏 1。
  `MiuiBatteryMeterView.onBatteryStyleChanged(int)` 当前样式 1 为内显、3 为外显、0 为无百分比；
  2 会隐藏电池本体，因此不用作“不显示百分比”。原方法继续调用一次，保留系统动画与布局。
- 新增 `StatusBarAdjustmentSettings.kt` 唯一持有图标尺寸与两处百分比配置/同步。
  图标尺寸接入现有资源尺寸分发；`status_bar_icon_height` 是当前电池、移动信号布局输入，
  `status_bar_icon_size` 用于状态栏图标；`statusbar_signal_size` 是网络类型文字尺寸，故不替换。
- 当前 `BatteryIcon$$ExternalSyntheticLambda0.run()` 使用
  `Resources.openRawResource(int, TypedValue)` 解码电池 sprite，并根据 TypedValue.density 切帧。
  导入包保留 density 目录，raw 替换必须一并填写选中素材密度，不替换电池异步任务本身。
- Resource hook 在 SystemUI/plugin 两个既有包分支安装，覆盖实际 SystemUI/plugin Resources；仅通过新增的
  applicationContext 类型回调复用原模块已拥有的上下文，不反射模块私有字段。
  新缓存、缓存锁与安装 guard 专属于导入功能，不改动现有电池主题保护状态。

### 2026-10-20 状态栏调整与图标导入：最终实现与验证

- `MainActivity.kt` 新增“状态栏调整”二级页，迁入原时钟、左右/上边距、状态栏高度
  slider，并新增图标大小 slider；入口下一项为图标导入，保留现有控制中心和信号专属页面。
  状态栏、控制中心分别提供四种百分比显示选项。
- 新增 `StatusBarAdjustmentSettings.kt` 持有图标大小与百分比配置、保存和远程同步；
  新增 `StatusBarIconImport.kt` 持有 MTZ/ZIP/独立组件解析、归一化文件、预览、只读 Provider
  与导入偏好同步；新增 `StatusBarIconImportDialog.kt` 持有选择/恢复/保存/取消及随颜色模式
  变化的信号、WiFi、电池预览。Manifest 注册该 Provider。新增设置未进入 `HookSettings.kt`。
- `StatusBarHooks.kt` 新增主题/密度时钟尺寸补偿、按 layout tag 分流的百分比覆盖和导入图标
  resource hooks。`HyperSystemUiModule.kt` 保留生命周期、包/进程分发及共享资源入口，
  仅添加调用点、原上下文的类型明确回调和图标尺寸资源处理。没有新的分类迁移或共享实现复制。
- 已对照当前 SystemUI 的时钟回调、BatteryMeterView 样式/tag、BatteryIcon raw sprite 路径，
  以及插件快捷按钮资源名；读取参考 MTZ 与两个独立组件，确认信号/WiFi、电池 raw 和
  `ic_cc_qs_*` / `ic_qs_*` 位图结构。参考电池为 86×6000，xxhdpi 每帧高 60，预览取末帧。
- 最终执行 `.\gradlew.bat :app:assembleDebug`：**BUILD SUCCESSFUL in 37s**，
  40 tasks：6 executed、34 up-to-date。编译及打包成功，保留既有 Kotlin/Gradle 警告。
- 三份语言 JSON 解析与新增翻译键检查通过；`git diff --check` 通过，仅有既有文件的
  LF/CRLF 提示。APK ZIP CRC 完整性通过，新增设置、store、dialog 和 Provider 的 DEX
  类定义各出现一次。`HookSettings.kt`、受保护的材质修复及其他独立 hook 所有者无差异。

最终 Debug APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
大小：122561792 字节。
SHA256：`d54f004e6363d0c27e8463e9aa1dc639e8d3931776612ebc75e7bd3bbc0a5392`。

未进行设备测试、APK 安装、ADB 操作或 SystemUI 重启。全局主题切换后的字号、图标大小、
深浅色资源替换、控制中心插件加载/重建和两处百分比显示仍需设备验证。
导入支持位图资源，XML/vector 主题素材和不同固件的资源名兼容性不在本次静态/构建验证结论内。

### 2026-10-20 运行反馈修复

- 信号、WiFi 与控制中心快捷按钮通过 `ImageView.setImageResource(int)` 装载资源，未稳定经过此前拦截的
  `Resources.getDrawable*` 公共入口。新增进程级 `setImageResource` 后处理：仅对 SystemUI/plugin 中能在
  已导入归一化包内精确命中的资源，用相同 density 解码后覆盖当前 drawable；电池 raw sprite 路径保留。
- 图标大小改为独立键 `status_bar_icon_size_percent`，范围 `-100%..100%`，`0%` 表示原系统值。
  不迁移旧绝对 dp 键，避免旧值被误解为百分比。尺寸资源按各自原值乘以系数，同时覆盖
  `status_bar_icon_height`、`status_bar_icon_size`、`status_bar_icon_size_sp`、
  `status_bar_icon_drawing_size(_dark)` 与 `status_bar_icon_scale_factor`。
- 当前 `MiuiBatteryMeterView` 在构造时可能先收到 `onBatteryStyleChanged`，父视图随后才调用
  `setLayoutFromTag(0/5/6)`。新增 tag 设置后的样式重放，并保存系统原始 style；状态栏 tag 0 与控制中心
  tag 5/6 分别应用配置，锁屏 tag 1 不变，重放后恢复 `mStoreRealStyle`，供系统后续内部刷新使用。
- 修复后执行 `\.\gradlew.bat :app:assembleDebug`：**BUILD SUCCESSFUL in 7s**，40 tasks：
  6 executed、34 up-to-date。Debug APK ZIP 完整性通过，共 131 个条目、12 个 DEX。
  产物：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`，
  大小 122561792 字节，SHA256 `092e873e0b5f992057a8a392d97b9abf8d771a57a336c3ebd8064cb2a775af1c`。
  未执行 ADB、安装、SystemUI 重启或设备运行验证。

### 2026-10-20 资源范围、相对图标尺寸与独立电量样式：修改前边界

- 已检查工作区差异；保留既有未提交的状态栏调整、导入、Provider、Manifest 和语言工作。
  修改前相关源码另存于 `D:/ReverseProject/.build-tmp/hyperchanger-statusbar-feedback-20261020/`，本轮不做分类迁移。
- `StatusBarIconImportDialog.kt` / `StatusBarIconImport.kt` 继续拥有导入 UI、范围键与远程同步；
  默认范围为状态栏和控制中心，空范围合法；QS 资源只归控制中心。
- `StatusBarHooks.kt` 继续拥有图标与电量功能。通过 SystemUI 的 defaultClassLoader 加载
  StatusBarIconView、ModernStatusBarView 和 MiuiBatteryMeterView；导入仍覆盖原 SystemUI/plugin 两包入口。
  `HyperSystemUiModule.kt` 保留生命周期、分发与共享 Resources hooks，不增加框架安装路径。
- 已阅读 `D:/XiaomiHelper-master/.../BatteryIndicatorStyle.kt`，并与当前反编译核对：
  tag 0 为状态栏，5/6 为控制中心，1 为锁屏。样式 1/3/0 对应内显/外显/无百分比。
  改为每次样式回调读取对应设置，在 tag 设置后重放并保存系统原样式；增加已存活视图的设置刷新。
- 原尺寸实现同时替换分子/分母资源、float 缩放和视图 scale，存在重复缩放、零值除法、
  early-return 后累乘，以及系统图标出入场动画将 scale 恢复为 1 的风险。
  计划使用独立 animationMatrix 对原尺寸应用一次相对缩放，布局变化时更新中心，
  保留系统 scaleX/scaleY 动画；撤除本功能的尺寸/float 资源替换。
- 新状态、弱引用视图集合、布局回调与偏好监听均归本功能所有；不扩大模块私有接口。
  未测试风险包括极端放大后的可用布局空间、其他固件的视图实现及动态岛过渡中的原生样式限制。

### 本轮实际实现及验证结果

- 未迁移方法；导入项目为“系统界面资源导入”，已导入时显示“资源应用范围”。Dialog
  按选择动态显示副标题，空范围不显示；两项复选框和行均可点击，下方依次为全宽全选、保存、取消。
  独立 scope 键缺省为 3，0 表示均不应用；`ic_qs_*` / `ic_cc_qs_*` 使用控制中心位，
  其余已支持资源使用状态栏位。文件、Provider 和导入 revision/cache/lock 仍为原所有者。
- `StatusBarHooks.kt` 用 animationMatrix 对普通图标、modern 信号/WiFi 及 tag 0 电池施加
  `1 + percent / 100`，范围 0..2。构造/初始化后跟踪，布局变更更新中心，偏好变更在 View
  所在线程刷新；modern 内部 overflow dot 不再重复缩放。默认清除矩阵，-100% 不修改资源
  分母；不改系统的 mIconScale 或 scaleX/scaleY，不影响其原有动画状态。
- `HyperSystemUiModule.kt` 撤掉本功能的 dimension/getFloat/getValue 替换；共享尺寸逻辑与
  其他功能保持原样。独立调整安装移至既有 SystemUI 分支的导入调用后，避免后续其他功能
  的异常跳过调整安装；仅一个 SystemUI 调用点，原默认 ClassLoader，不增加 plugin/框架入口。
- 电量样式回调每次读取当前 tag 的配置，保留原方法一次调用，用 finally 恢复 mStoreRealStyle。
  tag 设置后及 attach 后重放有效系统样式；设置变更刷新弱引用记录的电池视图，恢复默认亦走
  保存的真实系统样式。移除旧 batteryDesiredStyles 快照；tag 1 与未知 tag 始终原样调用。
- `StatusBarAdjustmentSettings.kt` 更新时读取最新本地设置再合并，避免两个页面的 store
  互相覆盖图标比例/百分比选项。新设置仍不进入 `HookSettings.kt`。
- 修复已有新增翻译放在 JSON 顶层而未被 tr 读取的问题，将本组 17 个键放入三份文件的
  strings 对象；“状态栏”“控制中心”等复用原有翻译。新增 0% 系统默认提示与有符号百分比。
  三份 JSON 解析通过，17 个键在 strings 内均恰好一次；原有其他重复键未作无关清理。
- 对照修改前备份，既有 StatusBarHooks 方法区除本功能 state 替换外完全一致；
  HookSettings、ControlCenterHooks、NotificationHooks、LockscreenHooks、SystemUiHooks、
  OthersHooks、IosShadeMaterial 和 ShadeLayer 相对 HEAD 无差异。
- 参考主题 systemui 组件含 482 个匹配资源，plugin 含 14 个；确认两包均有 QS 素材，
  范围判断不能仅使用包名。当前插件 QSTileItemView 同样使用 ic_qs_* 图标。
- 执行裸命令 `.\gradlew.bat :app:assembleDebug`：首轮源码构建 **BUILD SUCCESSFUL in 39s**；
  修正翻译位置后最终打包 **BUILD SUCCESSFUL in 5s**，40 tasks：5 executed、35 up-to-date。
  `git diff --check` 通过。APK 131 个 ZIP 条目均可解压且长度一致，包内 en/ja JSON 与源码一致。

最终 Debug APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
大小：122561792 字节。
SHA256：`d5c6544b7da4ac34c7fe77bc0e059704b10ef06f9ff8612073f8ef691a016022`。

未执行设备测试、安装、ADB、服务终止或重启。仍需实机确认范围变更后重建图标、控制中心加载、
深浅色主题切换、图标出入场动画与极端放大时的裁切/相邻间距，以及动态岛/AOD 原生转换对百分比
样式的影响。保留这些原生限制，不扩展到锁屏样式覆盖或 XML/vector 导入。本轮不宣称性能提升。

### 2026-10-20 图标放大后的布局占位：修改前边界

- 保留当前未提交工作，本次只修正 `StatusBarHooks.kt` 的图标缩放布局，不迁移分类、不新增配置。
- 反编译确认 `MiuiStatusIconContainer.onMeasure/onLayout` 按子视图 measuredWidth/width 排列，
  `MiuiStatusBatteryContainer` 按电池 measuredWidth 为信号容器预留空间且忽略子项 margin。
  因此仅 animationMatrix 放大、或增加 margin，均无法可靠修正边界重叠。
- 在现有 SystemUI defaultClassLoader 安装路径内，针对已跟踪普通图标、modern 信号/WiFi 与
  tag 0 电池补充测量宽度；放大时按真实比例向上取整预留宽度。缩小时保留原有布局占位，
  避免改动极窄宽度下系统图标的原生测量/省略行为。
- modern 与电池的原布局使用未放大的内容宽度，矩阵将原生内容居中缩放到新增占位内；
  普通 ImageView 保持原布局与基于高度的 drawable 适配。父容器获得新宽度，原间距继续由
  系统负责。布局回调、弱引用尺寸状态仍归 StatusBarHooks 唯一持有，原测量/布局方法各调用一次。
- 不改 lifecycle、包分发、ClassLoader、独立电量样式、导入、HookSettings 或共享资源；
  回归重点为双 SIM/WiFi 切换、电量外显宽度变化、恢复 0%、RTL 和密度变化。尚无实机结论。

追加授权：新增“状态栏图标间距”slider，用户指定 -30..64 dp，0 为系统默认间距的增量。
独立 `StatusBarIconSpacingSettings.kt` 保存新键/读取/同步；UI 与启动同步只增加对应 store 调用。
StatusBarHooks 在 tag 0 的 MiuiStatusIconContainer 测量/布局原调用期间覆盖 iconSpacing，结束恢复；
电池通过 start padding 将边界间距纳入原始测量，保留独立基线且不累加。负值压缩至零间距，
不人为制造图标重叠；控制中心/锁屏不应用新增间距。新增文案同步三份语言 JSON。

### 图标占位修复与间距 slider：实际结果

- `StatusBarHooks.kt` 新增测量与内容布局 hooks：普通 StatusBarIconView 的 onMeasure、
  modern 继承的 FrameLayout.onMeasure/onLayout、MiuiBatteryMeterView.onMeasure/onLayout。
  FrameLayout 的共享方法严格筛选已有 sizedIconViews；嵌套 dot 沿原去重条件保持 1 倍。
  每个找到的方法只注册一次，每次原测量/布局继续一次。没有改动 MiuiStatusBatteryContainer。
- 放大后的占位为 `ceil(nativeWidth * max(1, scale))`，保留原测量 state 与高度。
  modern/电池用原内容宽度布局，在新增占位中缩放；父容器现在可按新宽度排列图标及电池。
  缩小保持原占位，恢复 0% 后重新测量，宽度来自原方法，不在已放大宽度上累乘。
- 新增“状态栏图标间距”，范围 -30..64 dp，步长 1 dp，默认 0，显示有符号 dp 值。
  新文件 `StatusBarIconSpacingSettings.kt` 独立持有配置键、范围、读写和远程同步；
  MainActivity 的启动及该页 service 连接同步均已接入，离线更改以本地设置为准。
- tag 0 系统图标容器在测量/布局期间使用 `max(0, systemSpacing + dpOffset)`，finally
  恢复原 iconSpacing，因此密度/主题重载仍由系统持有基线。正向电池间距另加实际 px 占位，
  矩阵将内容锚定到布局 end（RTL 镜像），避免间距跟随图标比例二次放大；负向间距仅缩减
  电池原 start padding，保存原值并支持恢复到 0。两处间距最多压缩到零，不主动制造重叠。
- 沿用原偏好监听，在图标大小或间距变更时请求所有已跟踪图标与容器重新测量/布局。
  native width、容器集合、电池 padding 基线均为 feature 专属弱引用 state，未复制共享状态。
  既有电量样式、导入、信号自定义、主题和居左功能方法与本轮前备份一致。
- 增补英文、日文与 example 三份语言文件的三个 strings 键，各出现一次，JSON 解析通过。
  HookSettings、其他分类 Hooks、IosShadeMaterial 与 ShadeLayer 无差异；既有其他未提交改动保留。
- 单独占位修复执行 `.\gradlew.bat :app:assembleDebug`：**BUILD SUCCESSFUL in 9s**。
  追加间距 slider 后再次执行同一裸命令：**BUILD SUCCESSFUL in 37s**，40 tasks：
  10 executed、30 up-to-date。`git diff --check` 通过，原有 Kotlin/Gradle 警告仍在。
- 最终 APK 的 131 个 ZIP 条目均可完整解压且长度正确；包内 en/ja JSON 与源码完全一致。

最终 Debug APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
大小：122561792 字节。
SHA256：`aa1384226a8b58757eb05d38ef30cf20fe0830cfb70d6a6f7cd19b81e745997d`。

本轮仅完成静态与编译/打包验证，未安装 APK、运行 ADB 或重启/终止服务。待设备验证：
双 SIM/WiFi、内外电量文字切换后的宽度重算、RTL、密度/主题切换、动态岛及图标出入场动画。
屏幕空间不足时仍使用原系统的图标隐藏/省略规则；缩小图标不自动收缩原占位。

### 2026-10-20 独立百分比间距与信号/电池边界：修改前边界

- 用户进一步明确：图标间距为 -100%..100%，只控制图标之间的原生间隔；信号区与电池区的
  防重叠补偿由图标缩放自动控制，不随间距设置改变。此前逐个 SIM/modern 图标扩宽的方案撤除。
- 已读当前源码、反编译 MiuiStatusIconContainer/MiuiStatusBatteryContainer/MiuiBatteryMeterView
  及本审计，检查现有差异并保存本轮基线到 D:/ReverseProject/.build-tmp/hyperchanger-independent-spacing-20261020/。
  本轮是已授权行为修正，不做分类迁移。
- StatusBarHooks 仍是唯一实现和状态所有者，SystemUI defaultClassLoader 加载目标类；
  HyperSystemUiModule 保留原生命周期/分发/共享资源入口，不改插件、框架或其他 Hooks。
- 图标间距仅在 tag 0 图标容器 onMeasure/onLayout 原调用期间使用 systemSpacing*(1+percent/100)，
  finally 恢复字段。新百分比键/存储/同步留在 StatusBarIconSpacingSettings.kt，不重释旧 dp 值。
- 普通/modern 图标保持原测量宽度和内部布局，仅绘制缩放。电池保留自身缩放占位，
  MiuiStatusBatteryContainer 在原测量一次后，根据最靠近电池的有效图标半宽溢出，为电池额外预留边界；
  重新测量前扣除上次补偿，避免缓存尺寸累加。电池内部仍用原宽度布局，矩阵将内容锚到 end，支持 RTL。
- 新弱引用布局状态归本功能所有；偏好监听沿既有视图刷新，不扩大共享接口，不修改独立电量样式/导入。
  风险：极端放大与有限屏宽、双卡/WiFi/外显电量切换、RTL、动态岛动画及其他固件版本需实机验证。

### 独立百分比间距与边界补偿：实际实现及验证

- UI 和独立 store 改为 `status_bar_icon_spacing_percent`，-100..100、步长 1%，缺省 0。
  用户确认语义为 -100% 零间隔、0% 系统原间隔、+100% 两倍间隔。旧 dp 键不读取、不迁移，
  避免旧数值被当作百分比。HookSettings 及其序列化完全未改。
- 图标间距仍仅作用于 `_layoutFrom == 0` 的 MiuiStatusIconContainer，在原 onMeasure/onLayout
  调用期间按系统 iconSpacing 百分比计算且 finally 恢复。间距计算不读取尺寸设置，也不改电池 padding。
  资源/图标视图内部的原生留白不作额外压缩。
- 移除 StatusBarIconView 测量 hook 与 modern 继承 FrameLayout 测量/布局 hook；不再给每个 SIM
  分别增加测量宽度。普通/modern 图标保留原布局，只用已有 animationMatrix 一次绘制缩放。
- 电池保留自己的原内容宽度及放大占位，独立 onLayout 仍以原内容宽度调用一次，再向 end 锚定。
  新 MiuiStatusBatteryContainer.onMeasure hook 在原调用一次后，为电池增加最后有效图标的
  `ceil(edgeNativeWidth * max(scale - 1, 0) / 2)` 溢出补偿；此值不读取间距百分比。
  保留 EXACTLY/AT_MOST 约束、测量状态与高度；RTL 矩阵镜像额外空白。隐私区域显示、隐藏电池、
  非 tag 0 或没有有效图标时不补偿。下一轮原测量前扣除上次补偿，避免测量缓存累加。
- 尺寸状态收敛为电池专用弱引用 BatteryIconLayout，移除旧普通图标宽度表及电池 padding 表。
  不增加重复安装路径；原有偏好监听、布局监听和 Folme scaleX/scaleY 继续沿用。
- 与本轮前备份逐段比对：电量样式、导入、信号自定义、主题、居左实现未变；其余既有修改文件
  SHA256 与基线一致。模块入口、ClassLoader、框架分发及受保护通知/材质文件无本轮修改。
- en/ja/example 三份 JSON 解析通过，strings 下百分比格式一致；`git diff --check` 通过。
  执行规定裸命令 `.\gradlew.bat :app:assembleDebug`：**BUILD SUCCESSFUL in 19s**，
  40 tasks：8 executed、32 up-to-date。仅原有弃用/类型提示，无编译错误。
- APK 的 131 个 ZIP 条目可完整读取且长度匹配，包内 en/ja 与源码一致。
  产物 `D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`，122561792 字节；
  SHA256 `8e01d1d8c184971fe305b268cf2b48b7674fa41a7fe18cba85920a8f77fa7c36`。

以上为静态检查和编译/打包验证；未运行 ADB、安装或重启任何服务。双 SIM、WiFi、外显电量、RTL、
主题/密度切换及动态岛动画仍待设备确认，极端放大下原生内部留白和屏幕可用空间仍构成限制。
