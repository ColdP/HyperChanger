# OthersHooks 最终分类迁移审计

## 后续功能增量：背屏左滑手势

本次为用户请求的功能新增，不进行分类迁移。`OthersHooks` 增加独立的 `installRearScreenLeftSwipeHook`，由模块背屏包分发委托；原迁移的安装入口、共享依赖和 framework 边界保持原有实现。目标、ClassLoader、设置存储、回调行为、测试与 APK 结果见 [背屏左滑手势审计](rear-screen-left-swipe.md)。

## 最终交付与验证结果

本轮仅修改/新增三个正式文件：

- `app/src/main/java/btm/m/os4/systemuihook/HyperSystemUiModule.kt`：原 13 个应用安装位置委托到 OthersHooks，生命周期、分发、共享基础设施及 framework 实现继续原位。
- `app/src/main/java/btm/m/os4/systemuihook/OthersHooks.kt`：28 个完整方法、2 项原实例状态、11 项原 companion 声明，保留 13 个明确的应用/功能安装入口，其他辅助均 private。
- `docs/others-hook-migration.md`：本审计、完整成员/目标/调用/依赖清单、最终保留清单和验证记录。

**静态检查通过：**

- 28 个完整方法、2 项实例状态和 11 项 companion 声明，逆向还原唯一审定的接收者/module/TAG/原锁表达式替换后，与基线全文相同。
- 340 个保留原成员、所有未移动 companion 声明以及原模块全文符合审定的纯删除/持有/委托结果；五分类原有安装调用保持不变。
- framework 安装、两项加载器辅助、耳机判断、`onSystemServerStarting` 和 `hookDeoptimized` 与原文一致；`system` 包条件及两条原安装调用保留，没有添加 `android` 或 `system_server` 包判断。
- 所有 Hook ID 表达式的多重集合与基线一致；原生命周期完整对照确认安装位置/顺序及默认 ClassLoader 未重排。13 个委托各一处，没有残留原实现、重复状态或新增安装标志/重试路径。
- Settings loader 缓存保持 companion/Volatile 和同一个原 Class 监视器；widget guard 仍在 2500ms 任务提交前写入。原未调用的 Hongkong profile 仍未被调用。
- 新类只依赖 XposedModule 基类和类型明确的反优化回调，不依赖具体模块类型或五个 Hook 类，不增加循环类型依赖；原模块保留成员的可见性不变，新类仅开放 13 个必要安装入口，其他迁移辅助仍为 private。
- 其他 336 个已跟踪基线文件 SHA256 未变，包含五个已拆分类、`IosShadeMaterial.java`、`ShadeLayer.java`、设置/序列化/资源/独立 Xposed 入口；三份语言 JSON 可解析，没有文案或配置变更。

**构建验证通过：**执行 `.\gradlew.bat :app:assembleDebug`，`BUILD SUCCESSFUL in 25s`，
40 tasks：6 executed、34 up-to-date；存在原有 Kotlin/Gradle 警告，无编译或打包失败。
构建后 `git diff --check` 通过，新增文件另行检查行尾空白通过。

APK ZIP 完整性通过，12 个 DEX 的实际类定义无重复；OthersHooks、原模块和五个既有分类
均在 `classes9.dex` 中具有实际类定义。

APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
大小：122561613 字节。
SHA256：`7c7c6ff1d17cb5319b1b4f7cdf331119986607d5fc65aff4d6a7f8f31fe05506`。

**未进行实机验证。** 各目标应用的 ROM 类签名、资源加载/重建、线程回调与跨进程实际行为
仍需前述设备回归；静态同源和构建成功不能代替实测。没有运行 ADB 安装或重启/终止系统服务。
本轮到此停止，没有性能优化、公共 Hook 架构重写、设置改造或功能修复。

## 修改前基线与范围

基线：`f77466ae`（Migrate Other SystemUi Hooks），工作区干净。
当前 `HyperSystemUiModule.kt` 为 13,407 行，370 个类成员（包含方法、属性和内部类型）。
已读取此前 ControlCenter、Notification、Lockscreen、StatusBar/SystemUi 四份迁移审计，
对照五个已拆分类的实际接口/状态所有者，以及剩余原模块的调用、类加载和状态引用。
337 个已跟踪文件保存 SHA256；完整原模块另存快照。本文的归属表先于 Kotlin 修改形成。

用户本轮补充：系统框架目标包为 **`system`**，不改成 `android` 或 `system_server`；
尽量不动 framework Hook。因此 **framework 整组不迁移、不调整**：原 `system` 分支、
`onSystemServerStarting()`、两处调用及其参数/异常边界均保留。进程和包名不能混为一谈，
源码中的旧注释也不作为新增包分发的依据。本轮没有新增、合并或禁用其中任何入口。

## 真实目标、调用位置与唯一归属

以下原行号均对应本轮快照。所有应用安装继续接收原 `param.defaultClassLoader`；
不增加主进程限制、不改变原有提前 return 或配置读取位置。

| 原调用位置 / 目标包 | 实际功能、实现边界 | 本轮决定与风险 |
| --- | --- | --- |
| `onPackageLoaded` 1490；`com.miui.misound` | `installSoundEffectHooks`：SystemProperties BOSE、`z.m.B`、TypedArray/Resources Drawable、ImageView setter、PreferenceFragmentCompat 视图；logo 查询、选择、badge 和 AtomicInteger 缓存 | 完整迁往 Others；每次原路径仅委托原方法，保留六个安装块顺序、无日志异常块、原 tint/布局和配置读取 |
| 1502；`com.xiaomi.misettings` | `installMiSettingsHooks`：FeatureParser.getIntArray 的 fpsList | 完整迁往 Others；保留安装时开关和原列表顺序 |
| 1505 `system`；213 `onSystemServerStarting` | framework 安装、MIUI loader 解析、loadFrameworkClass、耳机判断 | 按用户补充要求全部留原位；不增加防重复标志，不调整两条既有路径，不重分类音量/锁屏等 framework 行为 |
| 1510；`com.miui.home` | 原模块仅读取配置并调用 `syncHomeRecentsNativeConfig`；实现已经属于 HomeRecentsSettings / native Launcher | 原分发和公共配置同步保留；不复制已有实现，不伪造新的 Launcher Hook |
| 1527；七个应用（下表） | `installAppNavigation` 调用已有 AppBottomNavHooks / LuckinAntiDetectionHooks，并包含 Xiaomi Health 表盘试用旁路 | 完整边界迁往 Others；保留方法内第二次包筛选、仅此组原有主进程判断、ScopedSettings 读序以及各应用安装顺序；已有导航实现不复制 |
| 1531–1541；`com.android.settings` | scope（旧控制中心入口/设备名）、模块入口、标题三项按原顺序单独 runCatching | 三个安装方法及专属反射/资源加载器一并迁移；Settings 中的 control panel gate 实际属于 Settings，已拆出的 ControlCenterHooks 不受影响 |
| 1545；`com.miui.mishare.connectivity` | availability `c/e`、CTA `R0.p(Context)`、本进程 property/Build 身份 | 完整迁往 Others；保留开关为 false 时提前返回，profile→availability→CTA 顺序不变 |
| 1549；`com.miui.miwallpaper` | 仅提前 return，WallpaperProbe 独立 Java Xposed 入口拥有 GL 上传链 | 原位保留；不重复安装 |
| 1554；`com.xiaomi.xmsf` | 原 lazy `UnlockFocusAuthHook` 已有专属独立实现 | 保留原 owner/分发，避免为了归档新建包装器或改变 lazy 时机 |
| 1559；`com.miui.voiceassist` | `SuperXiaoAiWindowHook` 已独立拥有悬浮窗实现 | 原委托位置保留；无可搬迁的原地 Hook 实现 |
| 1566–1568；`com.xiaomi.type`、`com.miui.phrase` | `installSuperXiaoAiHooks` 仅向已有 SuperXiaoAiInputHook 转发模块/loader/phraseProcess | 属于纯分发包装，保留原位；不重复实现已独立的小爱 Hook |
| 1756–1791；`com.miui.aod` | 锁屏编辑器、时钟材质/宽度/横向布局、景深、视频玻璃、模板上限与编辑背景 | 实际职责是锁屏/AOD 编辑链，沿用前批锁屏审计的暂留决定；部分安装器与 SystemUI/ThemeManager 共用，包含同一偏移弱映射/时钟配置、反射、原位安装标志。不能按非 SystemUI 包名一律归 Others |
| 1792–1798；`com.xiaomi.subscreencenter` | app-widget capability/property/Secure gates、o2.C0673j 静态状态、背屏音乐白名单 | 三个原安装边界完整迁往 Others；原可选 state 分支及先 gates→state→music 的顺序原位保留 |
| 1799–1807；`com.miui.personalassistant` | 请求范围 ThreadLocal、JSON/form 参数、OS 版本、catalog/default/安装过滤和缓存刷新 | 完整迁往 Others；保留原始对象/副作用、ThreadLocal finally 清理，以及现存空条件分支；未调用的 Hongkong profile 方法也迁移但不激活 |
| 1808–1826；`com.android.thememanager` | 背屏 feature guards（含导入/应用文件清理、AI guards、preset/网络/category 修正）→锁屏时钟/模板/视频→可选导入 widget 2500ms 注册 | 仅迁移背屏 guards 和 widget 注册；其间锁屏调用仍原位，委托顺序不变。清理路径、原方法次数和注册延迟不动，绝不把整包合成一个 install |
| SystemUI/plugin 主分支及共享发现器 | 控制中心、通知、锁屏、状态栏、动态岛、全局主题/材质、音量、电源菜单、手势条等前批已明确暂留链 | 不属于本轮 Others；保留原唯一状态与全部安装顺序 |

应用导航目标映射：`com.xiaomi.shop`→XiaomiStore；`com.mipay.wallet`→XiaomiWallet；
`com.mi.health`→表盘试用（原条件）→XiaomiHealth→WatchFaceMarket；
`com.apple.android.music`→AppleMusic；`com.sina.weibo`→Weibo；`com.xingin.xhs`→Xiaohongshu；
`com.lucky.luckyclient`→LuckinAntiDetection→LuckinCoffee。这七个目标沿用原 getProcessName 等值判断。

## 迁移成员、状态和依赖

选定 **28 个完整方法**，按包/功能保持独立入口；不新增巨型安装器。
完整逐项方法清单、原引用位置、目标/签名/配置和状态引用见文末全文件附录。

- 应用导航/表盘：`installAppNavigation`、`installXiaomiHealthWatchFaceTrialBypass`。
- MiShare：`installAirDropForceEnableHooks`、`installAirDropDeviceProfileHooks`。
- Settings：`installSettingsAppScopeHooks`、`installSettingsAppEntryHook`、`installSettingsEntryLabelHook`；`settingsField`、`getLongField`、`getIntField`、`setLongField`、`setIntField`、`setObjectField`、`attachModuleResources`。
- MiSettings：`installMiSettingsHooks`。
- MiSound：`installSoundEffectHooks`、`audioLogoDrawable`、`audioLogoFor`、`attachAudioBadge`。
- 背屏：`installMusicControlWhitelistHook`、`installRearScreenAppWidgetUnlockHooks`、`installPersonalAssistantRearScreenAppCardHooks`、`containsRearScreenRequestUrl`、`installPersonalAssistantHongkongBuildProfile`、`installSubScreenCenterAppWidgetState`、`installThemeManagerRearScreenFeatureGuards`、`setStaticFieldValue`、`installCustomRearScreenWidgetRegistration`。

两项实例状态迁移后仍为实例状态：`customRearScreenWidgetRegistrationInstalled`（原先在
postDelayed 之前置 true，继续保持）、`boseLogoResId`（原 AtomicInteger，不改共享范围）。

11 个 companion 声明一并迁移：`AIRDROP_AVAILABILITY_CLASS`、`SETTINGS_HEADER_ID`、
`MIUI_SETTINGS_HEADER_ADAPTER`、`settingsModuleResourcesLoader`（保持 Volatile）、
六个 `REAR_SCREEN_*` property/Secure setting 常量，以及 `personalAssistantRearScreenRequest`。
原 companion 的进程内共享范围保留，不复制到实例，不新建安装标志或缓存。

共享 TAG 由原模块不可变注入。`hookDeoptimized` 仍归原模块，通过类型明确的函数引用使用，
不改实现/可见性；其余原私有反射方法不暴露、不复制。
原 `getRemotePreferences` 仍在原方法的执行位置通过同一个 module 调用；配置键及设置定义仍归原设置文件，
不修改 HookSettings 或序列化路径，不新增任何配置。

Settings 资源加载器为专属缓存，可以迁移；但原 `attachModuleResources` 使用
`synchronized(HyperSystemUiModule::class.java)`。为精确保留锁对象身份，将原 Class 对象
作为只读 `settingsResourcesLock` 传入新类，仍同步同一个监视器，不改锁粒度、不反射访问原类字段。
影响范围只包含 Settings 首次模块资源加载，原 provider/ParcelFileDescriptor.use/addLoaders/
runCatching 以及 loader 缓存仍为完整单一实现。新类依赖 XposedModule 基类，无具体模块类型依赖。

新持有者在原 `customRearScreenWidgetRegistrationInstalled` 字段位置直接构造，构造器只保存依赖，
初始化原专属状态；不读取偏好、不注册 Hook、不加载宿主类、不增加 lazy、Handler、延迟任务或重试。
五个已拆分类、iOS 材质/壁纸清理修复和独立 Xposed 入口不调整。

## 验证计划与风险

逐方法逆向还原接收者、应用安装 module 参数、TAG 和原锁表达式后，与基线完整声明/方法体比较；
逐项核对状态和常量，并对原模块全文及所有保留成员独立比较。
确认原两条 framework 调用和全部分发条件、ClassLoader、异常边界、五分类入口均未改变。
Hook ID 多重集合核对不能替代注册顺序审查，故同时比较原 lifecycle 和剩余实现。
未调用方法保留未调用状态，不能因迁移激活它。

实施后执行唯一构建命令 `.\gradlew.bat :app:assembleDebug` 及 `git diff --check`，
核对五类与两个修复文件哈希，检查 APK ZIP 和 OthersHooks 实际 DEX 定义。
不安装 APK、不重启/终止 SystemUI。构建与静态检查不能代替各目标进程的实机回归。

待实机验证：Settings header 重建/资源加载/标题/设备名、MiSettings fps、MiSound BOSE 和 badge、
MiShare 可用性/CTA/身份、七个导航作用域及表盘、背屏目录/请求范围/静态 final 修改、
ThemeManager 导入/应用清理及 2500ms widget 注册。框架本轮保持源码原样，不声称已在设备验证。

## 修改前完整成员附录

附录从当前完整源文件交叉提取原位置、成员/状态引用及安装目标语句，与上面的真实分发和
完整方法阅读一起使用；字符串/名称匹配本身不构成迁移依据。未标记迁移的成员全部保留原模块。

### `controlCenterHooks` — 原 170 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`readContext`。

companion 引用：`systemUiApplicationContext`。


### `notificationHooks` — 原 177 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`findDynamicIslandField`, `readInstanceField`。

companion 引用：`TAG`, `SYSTEM_UI`, `EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS`, `SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS`, `KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR`。


### `lockscreenHooks` — 原 187 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`。


### `statusBarHooks` — 原 188 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`hookDeoptimized`, `readInstanceField`, `readInheritedField`。

companion 引用：`TAG`, `SYSTEM_UI`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `KEY_HIDE_STATUS_BAR_CLOCK_TEXT`, `KEY_MOBILE_NETWORK_TYPE_MODE`, `stackedMobileDualContainerId`。


### `systemUiHooks` — 原 200 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`hookDeoptimized`, `findDynamicIslandField`。

companion 引用：`TAG`, `SYSTEM_UI_PLUGIN`, `DYNAMIC_ISLAND_BASE_CONTENT_CLASS`。


### `islandSmoothingHook` — 原 208 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `lockscreenFaceUnlockHook` — 原 209 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `unlockFocusAuthHook` — 原 210 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installHook` — 原 211 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `onSystemServerStarting` — 原 213 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`installFrameworkHooks`。

companion 引用：`TAG`。


### `customRearScreenWidgetRegistrationInstalled` — 原 222 行；Others 专属状态

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `installAppNavigation` — 原 224 行；应用导航与表盘

原文件引用位置：1526。

成员依赖：`installXiaomiHealthWatchFaceTrialBypass`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：




### `installAirDropForceEnableHooks` — 原 274 行；MiShare AirDrop

原文件引用位置：1542。

成员依赖：`installAirDropDeviceProfileHooks`, `hookDeoptimized`。

companion 引用：`AIRDROP_AVAILABILITY_CLASS`, `TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `if (!preferences.getBoolean(KEY_AIRDROP_FORCE_ENABLE, false)) return`
- `val availability = param.defaultClassLoader.loadClass(AIRDROP_AVAILABILITY_CLASS)`
- `val method = availability.getDeclaredMethod(methodName).apply { isAccessible = true }`
- `.setId("hyperchanger:airdrop-force-enable:$methodName")`
- `val ctaGate = param.defaultClassLoader.loadClass("com.miui.mishare.connectivity.R0")`
- `val ctaMethod = ctaGate.getDeclaredMethod("p", android.content.Context::class.java)`
- `.setId("hyperchanger:airdrop-force-enable:cta")`


### `installAirDropDeviceProfileHooks` — 原 300 行；MiShare AirDrop

原文件引用位置：277。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val properties = Class.forName("android.os.SystemProperties", false, null)`
- `method.name in setOf("get", "getBoolean", "getInt", "getLong") &&`
- `method.parameterTypes.firstOrNull() == String::class.java`
- `.setId("hyperchanger:mishare-device-profile:${method.name}:${method.parameterTypes.size}")`


### `installXiaomiHealthWatchFaceTrialBypass` — 原 353 行；应用导航与表盘

原文件引用位置：243。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val moduleClass = loader.loadClass("com.xiaomi.wearable.yrn.modules.WatchFaceModule")`
- `val promiseClass = loader.loadClass("com.facebook.react.bridge.Promise")`
- `val method = moduleClass.getDeclaredMethod(`
- `val resolve = promiseClass.getMethod("resolve", Any::class.java)`
- `.setId("hyperchanger:mi-health-watch-face-unlimited-trial")`


### `readContext` — 原 374 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `settingsField` — 原 385 行；Settings

原文件引用位置：389, 393, 397, 398, 399。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `getLongField` — 原 389 行；Settings

原文件引用位置：425, 470。

成员依赖：`settingsField`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `getIntField` — 原 393 行；Settings

原文件引用位置：474。

成员依赖：`settingsField`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `setLongField` — 原 397 行；Settings

原文件引用位置：436。

成员依赖：`settingsField`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `setIntField` — 原 398 行；Settings

原文件引用位置：446, 474。

成员依赖：`settingsField`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `setObjectField` — 原 399 行；Settings

原文件引用位置：457, 458。

成员依赖：`settingsField`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `attachModuleResources` — 原 401 行；Settings

原文件引用位置：440。

成员依赖：无其他原模块成员引用。

companion 引用：`settingsModuleResourcesLoader`。

目标/签名/配置/Hook ID 交叉检查：




### `installSettingsAppEntryHook` — 原 416 行；Settings

原文件引用位置：1534。

成员依赖：`getLongField`, `getIntField`, `setLongField`, `setIntField`, `setObjectField`, `attachModuleResources`。

companion 引用：`SETTINGS_HEADER_ID`。

目标/签名/配置/Hook ID 交叉检查：

- `val settingsClass = runCatching { loader.loadClass("com.android.settings.MiuiSettings") }.getOrNull() ?: return`
- `it.name == "updateHeaderList" && it.parameterTypes.size == 1 && java.util.List::class.java.isAssignableFrom(it.parameterTypes[0])`
- `hook(update).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:settings-app-entry").intercept { chain ->`
- `val savedPosition = prefs.getInt(KEY_SETTINGS_APP_ENTRY_POSITION, 0).coerceIn(0, 3)`
- `loader.loadClass("com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header")`
- `val customTitle = prefs.getString(KEY_SETTINGS_APP_ENTRY_TITLE, "").orEmpty()`


### `installLiftToWakeScheduleHook` — 原 508 行；原模块保留

原文件引用位置：1482。

成员依赖：`applyLiftToWakeSchedule`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val cls = loader.loadClass("com.android.keyguard.injector.KeyguardSensorInjector")`
- `.setId("hyperchanger:lift-to-wake-instance")`


### `applyLiftToWakeSchedule` — 原 533 行；原模块保留

原文件引用位置：525。

成员依赖：`currentMinuteOfDay`。

companion 引用：`TAG`。


### `currentMinuteOfDay` — 原 566 行；原模块保留

原文件引用位置：541。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installSettingsEntryLabelHook` — 原 571 行；Settings

原文件引用位置：1537。

成员依赖：无其他原模块成员引用。

companion 引用：`MIUI_SETTINGS_HEADER_ADAPTER`。

目标/签名/配置/Hook ID 交叉检查：

- `val adapter = runCatching { loader.loadClass(MIUI_SETTINGS_HEADER_ADAPTER) }.getOrNull() ?: return`
- `it.name == "onBindViewHolder" && it.parameterCount == 2 &&`
- `it.parameterTypes[1] == Int::class.javaPrimitiveType`
- `val getItem = runCatching { adapter.getMethod("getItem", Int::class.javaPrimitiveType) }.getOrNull() ?: return`
- `hook(bind).setExceptionMode(ExceptionMode.PROTECTIVE).setId("hyperchanger:settings-entry-labels").intercept { chain ->`


### `installSoundEffectHooks` — 原 599 行；MiSound

原文件引用位置：1487。

成员依赖：`audioLogoDrawable`, `attachAudioBadge`, `hookDeoptimized`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `val getBoolean = Class.forName("android.os.SystemProperties").getDeclaredMethod(`
- `.setId("hyperchanger:sound-effect-props")`
- `if (key == AUDIO_PROP_BOSE) readSoundEffectPreferences(prefs).boseEnabled else chain.proceed()`
- `val gate = loader.loadClass("z.m")`
- `gate.methods.firstOrNull { it.name == "B" && it.parameterCount == 0 }?.let { target ->`
- `.setId("hyperchanger:sound-effect-gate")`
- `.intercept { readSoundEffectPreferences(prefs).boseEnabled }`
- `val taGet = android.content.res.TypedArray::class.java.getDeclaredMethod(`
- `.setId("hyperchanger:audio-logo-ta")`
- `val getDrawable = Resources::class.java.getDeclaredMethod(`
- `.setId("hyperchanger:audio-logo-res")`
- `val setImage = ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType)`
- `.setId("hyperchanger:audio-logo-setter")`
- `val fragCls = loader.loadClass("androidx.preference.PreferenceFragmentCompat")`
- `val onCreateView = fragCls.getMethod(`
- `.setId("hyperchanger:audio-logo-view")`


### `audioLogoDrawable` — 原 684 行；MiSound

原文件引用位置：633, 648, 660。

成员依赖：`audioLogoFor`, `boseLogoResId`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `val settings = readSoundEffectPreferences(prefs)`
- `Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)`


### `audioLogoFor` — 原 706 行；MiSound

原文件引用位置：700, 734。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `attachAudioBadge` — 原 719 行；MiSound

原文件引用位置：676。

成员依赖：`audioLogoFor`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `val settings = readSoundEffectPreferences(prefs)`
- `fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)`
- `screen.javaClass.getMethod("findPreference", CharSequence::class.java)`


### `boseLogoResId` — 原 748 行；Others 专属状态

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `resolveMiuiServicesLoader` — 原 764 行；原模块保留

原文件引用位置：822。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `loadFrameworkClass` — 原 787 行；原模块保留

原文件引用位置：829, 845, 865, 890, 902, 935, 975, 990, 1011, 1055, 1057, 1105, 1208, 1255。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `hookDeoptimized` — 原 817 行；原模块保留

原文件引用位置：281, 320, 362, 615, 835, 869, 883, 893, 925, 939, 949, 968, 979, 995, 1016, 1032, 1061, 1076, 1091, 1111, 1121, 1140, 1179, 1188, 1211, 1222, 1231, 1245, 1260, 1292, 1315, 1329, 1387, 1409, 1421, 1461, 1469, 2113, 2124, 2150, 2193, 2277, 2323, 2377, 2428, 2526, 3204, 3308, 3323, 3340, 3361, 3392, 3402, 3418, 3430, 3449, 3471, 3517, 3561, 3586, 3619, 3643, 3672, 3713, 3765, 3785, 3814, 3849, 3874, 3894, 4018, 4049, 4076, 4097, 4112, 4121, 4132, 4149, 4164, 4183, 4199, 4212, 4229, 4272, 4281, 4837, 4916, 5272, 5319, 5498, 5562, 5577, 5590, 5603, 5632, 5640, 5656, 5675, 5702, 5724, 5829, 5859, 5888, 5919, 6028, 6048, 6279, 6294, 6333, 6390, 6408, 6644, 6811, 7369, 7429, 7451, 7477, 7501, 7520, 7549, 7563, 7583, 7932, 8722, 9244, 9330, 9386, 9459, 9854, 10002, 10374, 10651, 10843, 10857, 11301, 11421, 11437, 11450, 11462, 11653, 11774, 11894, 12063, 12073, 12097, 12108, 12125, 12139, 12159, 12390, 12462, 12523, 12543。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installFrameworkHooks` — 原 821 行；原模块保留

原文件引用位置：216, 1506。

成员依赖：`resolveMiuiServicesLoader`, `loadFrameworkClass`, `hookDeoptimized`, `hasHeadsetOutput`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `return runCatching { block(readFrameworkPreferences(prefs)) }.getOrDefault(false)`
- `it.name == "getMaxMiuiFreeFormStackCount" \|\|`
- `it.name == "getMaxMiuiFreeFormStackCountForFlashBack"`
- `.setId("hyperchanger:fw-multiple-freeform-${method.name}")`
- `.setId("hyperchanger:fw-multiple-freeform-ctor")`
- `cls.declaredMethods.filter { it.name == "onMiuiFreeFormStasckAdded" }.forEach { method ->`
- `.setId("hyperchanger:fw-multiple-freeform-added")`
- `val cls = loader.loadClass("android.app.ActivityTaskManager")`
- `cls.declaredMethods.filter { it.name == "supportsSplitScreen" }.forEach { method ->`
- `.setId("hyperchanger:fw-force-freeform-split")`
- `cls.declaredMethods.filter { it.name == "isResizeable" }.forEach { method ->`
- `.setId("hyperchanger:fw-force-freeform-resizeable-${method.parameterCount}")`
- `cls.declaredMethods.filter { it.name == methodName }.forEach { method ->`
- `.setId("hyperchanger:fw-force-freeform-list-$methodName-${method.parameterCount}")`
- `cls.declaredMethods.filter { it.name == methodName }.forEach { method ->`
- `.setId("hyperchanger:fw-force-freeform-utils-$methodName-${method.parameterCount}")`
- `val cls = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")`
- `cls.declaredMethods.filter { it.name == "retrieveSettings" }.forEach { method ->`
- `.setId("hyperchanger:fw-force-freeform-settings")`
- `val cls = loader.loadClass("com.android.server.wm.WindowManagerService\$SettingsObserver")`
- `cls.declaredMethods.filter { it.name == "updateDevEnableNonResizableMultiWindow" }.forEach { method ->`
- `.setId("hyperchanger:fw-force-freeform-observer")`
- `val target = cls.declaredMethods.firstOrNull { it.name == "needForegroundPin" && it.parameterCount == 1 }`
- `.setId("hyperchanger:fw-freeform-background")`
- `it.name == "safeMediaVolumeIndex" && it.parameterCount == 1`
- `.setId("hyperchanger:fw-volume-safe")`
- `it.name == "updateSafeMediaVolumeIndex" && it.parameterCount == 1`
- `.setId("hyperchanger:fw-volume-safe-stub")`
- `val cls = loader.loadClass("com.android.server.audio.AudioService\$VolumeController")`
- `val target = cls.declaredMethods.firstOrNull { it.name == "suppressAdjustment" && it.parameterCount == 3 }`
- `.setId("hyperchanger:fw-volume-instant")`
- `cls.declaredMethods.filter { it.name == "getDarkModeAppList" }.forEach { method ->`
- `.setId("hyperchanger:fw-force-dark-list")`
- `cls.declaredMethods.filter { it.name == "shouldShowInSettings" && it.parameterCount == 1 }.forEach { method ->`
- `.setId("hyperchanger:fw-force-dark-show")`
- `val cls = loader.loadClass("android.content.Intent")`
- `cls.declaredMethods.filter { it.name == "setFlags" && it.parameterCount == 1 }.forEach { method ->`
- `.setId("hyperchanger:fw-recents-visibility")`
- `cls.declaredMethods.firstOrNull { it.name == "isCTS" && it.parameterCount == 0 }?.let { method ->`
- `.setId("hyperchanger:fw-native-installer-cts")`
- `cls.declaredMethods.filter { it.name == name }.forEach { method ->`
- `.setId("hyperchanger:fw-native-installer-$name")`
- `val cls = loader.loadClass("com.android.server.locksettings.LockSettingsStrongAuth")`
- `val target = cls.declaredMethods.firstOrNull { it.name == "rescheduleStrongAuthTimeoutAlarm" }`
- `.setId("hyperchanger:fw-pin-72h")`
- `loader.loadClass(name).declaredMethods`
- `.filter { it.name in methods }`
- `val cls = loader.loadClass("com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$index")`
- `cls.declaredMethods.filter { it.name == "accept" }.forEach { deoptimize(it) }`
- `val cls = loader.loadClass("com.android.server.wm.DisplayContent\$$index")`
- `cls.declaredMethods.filter { it.name == "test" }.forEach { deoptimize(it) }`
- `val cls = loader.loadClass("com.android.server.input.KeyGestureController")`
- `it.name == "getScreenshotChordLongPressDelay" && it.parameterCount == 0`
- `.setId("hyperchanger:fw-quick-screenshot")`
- `val cls = loader.loadClass("com.android.server.wm.WindowState")`
- `val target = cls.declaredMethods.firstOrNull { it.name == "isSecureLocked" && it.parameterCount == 0 }`
- `.setId("hyperchanger:fw-screenshot-anywhere")`
- `cls.declaredMethods.filter { it.name == "notAllowCaptureDisplay" }.forEach { method ->`
- `.setId("hyperchanger:fw-screenshot-anywhere-miui-gate-${method.parameterCount}")`
- `val cls = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")`
- `cls.declaredMethods.filter { it.name == "registerScreenCaptureObserver" }.forEach { method ->`
- `.setId("hyperchanger:fw-screenshot-observer-${method.parameterCount}")`
- `val cls = loader.loadClass("com.android.server.wm.WindowManagerService")`
- `cls.declaredMethods.filter { it.name == "registerScreenRecordingCallback" }.forEach { method ->`
- `.setId("hyperchanger:fw-screen-recording-observer-${method.parameterCount}")`
- `val cls = wifiLoader.loadClass("com.android.server.wifi.Utils")`
- `cls.declaredMethods.filter { it.name == "checkDeviceNameIsIllegalSync" }.forEach { method ->`
- `.setId("hyperchanger:fw-device-name-wifi-${method.parameterCount}")`
- `val manager = loader.loadClass("com.android.server.SystemServiceManager")`
- `manager.declaredMethods.filter { it.name == "loadClassFromLoader" && it.parameterCount == 2 }`
- `.setId("hyperchanger:fw-device-name-wifi-loader")`
- `val cls = loader.loadClass(captureName)`
- `val argsCls = loader.loadClass(argsName)`
- `cls.declaredMethods.filter { it.name == name }.forEach { method ->`
- `.setId("hyperchanger:fw-screen-capture-$captureName-$name")`
- `val cls = loader.loadClass("com.android.server.display.DisplayControl")`
- `cls.declaredMethods.filter { it.name == "createVirtualDisplay" }.forEach { method ->`
- `.setId("hyperchanger:fw-screenshot-virtual-display-${method.parameterCount}")`
- `val cls = loader.loadClass("com.android.server.display.VirtualDisplayAdapter")`
- `cls.declaredMethods.filter { it.name == "createVirtualDisplayLocked" }.forEach { method ->`
- `.setId("hyperchanger:fw-screenshot-virtual-display-locked-${method.parameterCount}")`


### `hasHeadsetOutput` — 原 1353 行；原模块保留

原文件引用位置：1001, 1022。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installMiSettingsHooks` — 原 1379 行；MiSettings

原文件引用位置：1498。

成员依赖：`hookDeoptimized`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `val cls = loader.loadClass("miui.util.FeatureParser")`
- `it.name == "getIntArray" && it.parameterCount == 1 &&`
- `it.parameterTypes[0] == String::class.java`
- `.setId("hyperchanger:mi-settings-fps")`


### `installSettingsAppScopeHooks` — 原 1401 行；Settings

原文件引用位置：1531。

成员依赖：`hookDeoptimized`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `val cls = loader.loadClass("com.android.settings.utils.StatusBarUtils")`
- `it.name == "isForceUseControlPanel" && it.parameterCount == 1`
- `.setId("hyperchanger:settings-old-control-center")`
- `if (readFrameworkPreferences(prefs).disableDeviceNameCheck) {`
- `loader.loadClass("miui.os.Build").getDeclaredField("IS_INTERNATIONAL_BUILD")`
- `.setId(id)`
- `loader.loadClass(className).declaredMethods`
- `.filter { it.name == "onSave" && it.parameterCount == 0 }`
- `loader.loadClass("com.android.settings.DeviceNameCheckManager").declaredMethods`
- `.filter { it.name == "getDeviceNameCheckResult" && it.parameterCount == 4 }`
- `val cls = loader.loadClass("com.android.settings.bluetooth.MiuiBTUtils")`
- `cls.declaredMethods.filter { it.name == "isInternationalBuild" && it.parameterCount == 0 }`
- `.setId("hyperchanger:fw-device-name-intl")`
- `it.name == "isSupportNameComplianceCheck" && it.parameterCount == 1`
- `.setId("hyperchanger:fw-device-name-compliance")`


### `onPackageLoaded` — 原 1478 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`controlCenterHooks`, `notificationHooks`, `lockscreenHooks`, `statusBarHooks`, `islandSmoothingHook`, `lockscreenFaceUnlockHook`, `unlockFocusAuthHook`, `installAppNavigation`, `installAirDropForceEnableHooks`, `installSettingsAppEntryHook`, `installLiftToWakeScheduleHook`, `installSettingsEntryLabelHook`, `installSoundEffectHooks`, `installFrameworkHooks`, `installMiSettingsHooks`, `installSettingsAppScopeHooks`, `installHyperMusicCoverGestureBridge`, `installSuperXiaoAiHooks`, `scheduleControlCenterHookRetries`, `installControlCenterEditButtonHook`, `installGlobalActionsHook`, `installMusicControlWhitelistHook`, `installRearScreenAppWidgetUnlockHooks`, `installPersonalAssistantRearScreenAppCardHooks`, `installSubScreenCenterAppWidgetState`, `installThemeManagerRearScreenFeatureGuards`, `installCustomRearScreenWidgetRegistration`, `scheduleSoftGlassThemeActivation`, `installSoftGlassThemeSystemUiHook`, `installSoftGlassThemePluginHook`, `installSoftGlassThemeClassLoadGuard`, `installDefaultThemeStateGuard`, `installStatusBarVisibilityHook`, `installClockMaterialLimitHook`, `installDynamicIslandHooks`, `installMediaSourceIconHooks`, `installFocusIslandWhitelistPluginHooks`, `installFocusIslandWhitelistSystemUiHooks`, `installDynamicIslandClassDiscovery`, `installShadeMaterialHooks`, `installLockscreenClockColonHook`, `installLockscreenCarrierHideHook`, `installLockscreenBigClockWidthHook`, `installLockscreenBigClockEditorWidthHook`, `installLockscreenClockHorizontalRectHook`, `installLockscreenClockHorizontalEditorHook`, `installAodClockMaterialLimitHook`, `installClockMaterialCapabilityHook`, `installThemeManagerEditorClockMaterialHook`, `installThemeManagerClockMaterialLimitHook`, `installDepthEffectHook`, `installLockscreenShortcutGlassHook`, `installLockscreenWidgetSceneVisibilityHooks`, `installLockscreenPinCircleBackgroundHook`, `installLockscreenNotificationHook`, `installLockscreenMediaNotificationHook`, `installFingerprintIconVisualHook`, `installSystemUiDepthHooks`, `installLockscreenFingerprintAnimationHook`, `installLockscreenWhiteBarHook`, `installGlobalGestureHandleHook`, `installGlobalGestureControllerHook`, `installDimensionHooks`, `installHeadsUpNotificationSoftGlassHooks`, `installCornerRadiusHooks`, `installKnownCornerRadiusHooks`, `installAodThirdPartyWallpaperDepthHook`, `installVideoWallpaperGlassSupportHook`, `installAodLockscreenTemplateLimitHook`, `installAodEditorBackgroundHook`, `installVolumePanelHooks`, `installAospVolumePanelFallback`。

companion 引用：`SETTINGS_PACKAGE`, `AIRDROP_PACKAGE`, `TAG`, `SYSTEM_UI`, `LOCKSCREEN_WALLPAPER`, `XMSF_PACKAGE`, `SYSTEM_UI_PLUGIN`, `AOD`, `SUPER_XIAOAI_IME`, `SUPER_XIAOAI_PHRASE`, `SUBSCREEN_CENTER`, `PERSONAL_ASSISTANT`, `THEME_MANAGER`, `SYSTEM_UI_TARGETS`, `resourceHooksInstalled`, `cornerHooksInstalled`, `volumePanelHooksInstalled`, `aospVolumePanelHooksInstalled`, `depthEffectHookInstalled`, `lockscreenNotificationHookInstalled`, `lockscreenClockDateFollowHookInstalled`, `systemUiNativeClockScalerHookInstalled`, `systemUiLockscreenClockColonHookInstalled`, `systemUiLockscreenClockWidthHookInstalled`, `systemUiLockscreenClockHorizontalHookInstalled`, `lockscreenCarrierHideHookInstalled`, `fingerprintIconHookInstalled`, `systemUiDepthHookInstalled`, `lockscreenWhiteBarHookInstalled`, `globalGestureHandleHookInstalled`, `lockscreenShortcutGlassHookInstalled`, `lockscreenWidgetSceneVisibilityHookInstalled`, `lockscreenMusicLockscreenHookInstalled`, `lockscreenMediaNotificationHookInstalled`, `lockscreenPinCircleBackgroundHookInstalled`, `shadeMaterialHooksInstalled`, `softGlassThemeSystemUiHookInstalled`, `systemUiClockMaterialLimitHookInstalled`, `aodClockMaterialLimitHookInstalled`, `themeManagerClockMaterialLimitHookInstalled`, `aodLockscreenClockColonHookInstalled`, `aodLockscreenClockWidthHookInstalled`, `aodLockscreenClockHorizontalHookInstalled`, `aodLockscreenTemplateLimitHookInstalled`, `statusBarVisibilityHookInstalled`, `softGlassThemePluginHookInstalled`, `softGlassThemePluginFallbackHookInstalled`, `dynamicIslandHooksInstalled`, `mediaSourceIconHooksInstalled`, `dynamicIslandClassDiscoveryInstalled`, `focusIslandWhitelistSystemUiHooksInstalled`, `focusIslandWhitelistPluginHooksInstalled`, `controlCenterEditButtonHookInstalled`, `controlCenterContentDistributorHookInstalled`, `controlCenterTopButtonsHookInstalled`, `globalActionsHookInstalled`, `controlCenterMainPanelHookInstalled`, `controlCenterButtonsLock`, `systemUiClassLoader`。


### `installHyperMusicCoverGestureBridge` — 原 1842 行；原模块保留

原文件引用位置：1581。

成员依赖：`dispatchHyperMusicCoverState`, `syncHyperMusicCoverLyrics`, `refreshLockscreenLyricButtons`, `setLockscreenLyricsShowing`。

companion 引用：`TAG`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_HDR_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_KEEP_SCREEN_ON`, `syncedHyperMusicLyricsEnabled`, `hyperMusicCoverGestureBridgeInstalled`, `hyperMusicCoverPreferenceChangeListener`, `systemUiApplicationContext`, `activeLockscreenClockContainer`。

目标/签名/配置/Hook ID 交叉检查：

- `!preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED, false)`
- `val clockContainer = classLoader.loadClass("com.android.keyguard.clock.KeyguardClockContainer")`
- `.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }`
- `.setId("hypermusiccover:gesture-bridge-ready")`


### `dispatchHyperMusicCoverState` — 原 1922 行；原模块保留

原文件引用位置：1854, 1861, 1908, 9727。

成员依赖：`sendHyperMusicCoverMediaCardCommand`, `sendHyperMusicCoverCommand`。

companion 引用：`KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `HYPER_MUSIC_COVER_CARD_RESET_DELAY_MS`, `syncedHyperMusicLyricsEnabled`, `systemUiApplicationContext`。


### `syncHyperMusicCoverLyrics` — 原 1994 行；原模块保留

原文件引用位置：1855, 1880, 1907, 9728。

成员依赖：`sendHyperMusicCoverCommand`, `lockscreenLyricsShowing`。

companion 引用：`KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_HDR_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LYRICS_KEEP_SCREEN_ON`, `syncedHyperMusicLyricsEnabled`, `syncedHyperMusicLyricsHdrEnabled`, `syncedHyperMusicLyricsKeepScreenOn`, `systemUiApplicationContext`。


### `sendHyperMusicCoverMediaCardCommand` — 原 2019 行；原模块保留

原文件引用位置：1947, 1958, 1968, 1984。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `SYSTEM_UI`, `HYPER_MUSIC_COVER_ACTION`。


### `sendHyperMusicCoverCommand` — 原 2037 行；原模块保留

原文件引用位置：1929, 1948, 1950, 1953, 1959, 1961, 1965, 1969, 1973, 1976, 2006, 2010, 2014。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `SYSTEM_UI`, `HYPER_MUSIC_COVER_ACTION`。


### `installSuperXiaoAiHooks` — 原 2050 行；原模块保留

原文件引用位置：1564。

成员依赖：无其他原模块成员引用。

companion 引用：`SUPER_XIAOAI_PHRASE`。

目标/签名/配置/Hook ID 交叉检查：




### `scheduleControlCenterHookRetries` — 原 2062 行；原模块保留

原文件引用位置：1604。

成员依赖：`controlCenterHooks`, `islandSmoothingHook`, `installControlCenterEditButtonHook`。

companion 引用：`TAG`, `controlCenterEditButtonHookInstalled`, `controlCenterTopButtonsHookInstalled`, `controlCenterMainPanelHookInstalled`, `controlCenterHookRetryScheduled`。


### `installControlCenterEditButtonHook` — 原 2084 行；原模块保留

原文件引用位置：1603, 2078, 5170。

成员依赖：`hookDeoptimized`, `installControlCenterMainPanelHook`, `installControlCenterHeaderLifecycleHook`, `hideBoundControlCenterEditButton`, `installControlCenterTouchHook`, `installControlCenterExpandLifecycleHook`, `installControlCenterTopButtons`。

companion 引用：`TAG`, `CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS`, `CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS`, `ADD_CONTROL_CENTER_TOP_BUTTONS_KEY`, `KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE`, `KEY_CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_MODE`, `KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_OPACITY`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_OPACITY`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_LUMINANCE`, `KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON`, `controlCenterEditButtonHookInstalled`, `controlCenterContentDistributorHookInstalled`, `controlCenterTopButtonsHookInstalled`, `controlCenterPreferenceListenerInstalled`, `controlCenterButtonsLock`, `controlCenterControllerClassLoader`, `controlCenterEditController`。

目标/签名/配置/Hook ID 交叉检查：

- `?.takeIf { it.name == CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS }`
- `?: runCatching { classLoader.loadClass(CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS) }.getOrNull())`
- `method.name == "available" &&`
- `method.parameterCount == 1 &&`
- `method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `method.name == "getListItems" &&`
- `method.parameterCount == 0 &&`
- `java.util.List::class.java.isAssignableFrom(method.returnType)`
- `.setId("control-center:hide-edit-button-$index")`
- `if (preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&`
- `!preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false)`
- `.setId("control-center:hide-edit-button-items-$index")`
- `if (preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&`
- `!preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false)`
- `method.name == "onBindViewHolder" && method.parameterCount == 0`
- `.setId("control-center:top-buttons-bind-$index")`
- `?.takeIf { it.name == CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS }`
- `?: runCatching { classLoader.loadClass(CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS) }.getOrNull())`
- `method.name == "getChildControllers" &&`
- `method.parameterCount == 0 &&`
- `java.util.List::class.java.isAssignableFrom(method.returnType)`
- `.setId("control-center:hide-edit-button-controllers")`
- `if (!preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) \|\|`
- `preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false)`


### `installControlCenterMainPanelHook` — 原 2262 行；原模块保留

原文件引用位置：2182, 5173。

成员依赖：`hookDeoptimized`, `installControlCenterTopButtonsIntoRoot`。

companion 引用：`TAG`, `CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS`, `controlCenterMainPanelHookInstalled`, `controlCenterClassDiscoveryInProgress`。

目标/签名/配置/Hook ID 交叉检查：

- `?.takeIf { it.name == CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS))`
- `method.name == "onCreate" && method.parameterCount == 0`
- `.setId("control-center:top-buttons-main-panel-$index")`
- `it.name == "getView" && it.parameterCount == 0`


### `installControlCenterHeaderLifecycleHook` — 原 2308 行；原模块保留

原文件引用位置：2184, 5179。

成员依赖：`hookDeoptimized`, `hideControlCenterTopButtons`, `updateControlCenterTopButtonsProgress`, `installControlCenterTopButtons`。

companion 引用：`TAG`, `CONTROL_CENTER_HEADER_CONTROLLER_CLASS`, `controlCenterHeaderLifecycleHookInstalled`, `controlCenterClassDiscoveryInProgress`, `controlCenterButtonsLock`, `controlCenterEditController`。

目标/签名/配置/Hook ID 交叉检查：

- `?.takeIf { it.name == CONTROL_CENTER_HEADER_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_HEADER_CONTROLLER_CLASS)`
- `method.name in setOf("onCreate", "onMainPanelVisibleChanged", "onModeChanged")`
- `.setId("control-center:top-buttons-header-$index")`
- `val visible = (0 until method.parameterCount)`


### `hideBoundControlCenterEditButton` — 原 2352 行；原模块保留

原文件引用位置：2160, 2245。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON`。


### `installControlCenterTouchHook` — 原 2365 行；原模块保留

原文件引用位置：2218, 5182。

成员依赖：`hookDeoptimized`, `findControlCenterTopButtonHit`。

companion 引用：`TAG`, `CONTROL_CENTER_TOUCH_CONTROLLER_CLASS`, `controlCenterTouchHookInstalled`, `controlCenterClassDiscoveryInProgress`, `controlCenterButtonsLock`, `controlCenterActiveTouchButton`。

目标/签名/配置/Hook ID 交叉检查：

- `?.takeIf { it.name == CONTROL_CENTER_TOUCH_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_TOUCH_CONTROLLER_CLASS)`
- `method.name == "onInterceptTouchEvent" && method.parameterCount == 1 &&`
- `method.parameterTypes[0] == MotionEvent::class.java &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("control-center:top-buttons-touch-$index")`


### `installControlCenterEventHandlerHook` — 原 2418 行；原模块保留

原文件引用位置：5185。

成员依赖：`hookDeoptimized`, `findControlCenterTopButtonHit`, `performControlCenterTopButtonAction`。

companion 引用：`TAG`, `CONTROL_CENTER_EVENT_HANDLER_CLASS`, `controlCenterEventHandlerHookInstalled`, `controlCenterButtonsLock`, `controlCenterActiveTouchButton`。

目标/签名/配置/Hook ID 交叉检查：

- `val handlerClass = classLoader.loadClass(CONTROL_CENTER_EVENT_HANDLER_CLASS)`
- `method.name == "handleExpandEvent" && method.parameterCount >= 1 &&`
- `method.parameterTypes.firstOrNull() == MotionEvent::class.java &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("control-center:top-buttons-event-handler-$index")`


### `installControlCenterRootDispatchHook` — 原 2475 行；原模块保留

原文件引用位置：2822。

成员依赖：`installControlCenterRootClickHook`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `installControlCenterRootClickHook` — 原 2483 行；原模块保留

原文件引用位置：2480。

成员依赖：`startControlCenterTopButtonsCollapse`。

companion 引用：`TAG`, `controlCenterRootClickHookInstalled`, `controlCenterButtonsLock`, `controlCenterRoot`。

目标/签名/配置/Hook ID 交叉检查：

- `hook(View::class.java.getMethod("performClick"))`
- `.setId("control-center:top-buttons-root-click")`


### `installControlCenterExpandLifecycleHook` — 原 2505 行；原模块保留

原文件引用位置：2183, 5176。

成员依赖：`hookDeoptimized`, `hideControlCenterTopButtons`, `updateControlCenterTopButtonsProgress`, `startControlCenterTopButtonsCollapse`, `controlCenterExpansionProgress`。

companion 引用：`TAG`, `CONTROL_CENTER_EXPAND_CONTROLLER_CLASS`, `controlCenterExpandLifecycleHookInstalled`, `controlCenterClassDiscoveryInProgress`, `controlCenterButtonsLock`, `controlCenterExpandController`。

目标/签名/配置/Hook ID 交叉检查：

- `?.takeIf { it.name == CONTROL_CENTER_EXPAND_CONTROLLER_CLASS }`
- `?: classLoader.loadClass(CONTROL_CENTER_EXPAND_CONTROLLER_CLASS)`
- `(method.name == "onExpandChange" && method.parameterCount == 3 &&`
- `method.parameterTypes[0] == Float::class.javaPrimitiveType &&`
- `method.parameterTypes[1] == Float::class.javaPrimitiveType &&`
- `method.parameterTypes[2] == Boolean::class.javaPrimitiveType) \|\|`
- `(method.name == "hidePanel" && method.parameterCount == 2 &&`
- `method.parameterTypes.all { it == Boolean::class.javaPrimitiveType }) \|\|`
- `(method.name == "onStop" && method.parameterCount == 0) \|\|`
- `(method.name == "onExpandFinish" && method.parameterCount == 1)`
- `.setId("control-center:top-buttons-expand-$index")`
- `if (method.name == "hidePanel") {`
- `it.name == "getAppearance" && it.parameterCount == 0`


### `hideControlCenterTopButtons` — 原 2568 行；原模块保留

原文件引用位置：2333, 2534, 2544, 2552。

成员依赖：无其他原模块成员引用。

companion 引用：`controlCenterButtonsLock`, `controlCenterPlusButton`, `controlCenterPowerButton`, `controlCenterActiveTouchButton`, `controlCenterButtonsExpansionProgress`, `controlCenterButtonsHiddenByCollapse`, `controlCenterButtonsCollapsing`。


### `updateControlCenterTopButtonsProgress` — 原 2583 行；原模块保留

原文件引用位置：2342, 2541, 2551, 2968。

成员依赖：无其他原模块成员引用。

companion 引用：`controlCenterButtonsLock`, `controlCenterPlusButton`, `controlCenterPowerButton`, `controlCenterButtonsExpansionProgress`, `controlCenterButtonsHiddenByCollapse`, `controlCenterButtonsCollapsing`。


### `startControlCenterTopButtonsCollapse` — 原 2612 行；原模块保留

原文件引用位置：2494, 2533。

成员依赖：无其他原模块成员引用。

companion 引用：`controlCenterButtonsLock`, `controlCenterPlusButton`, `controlCenterPowerButton`, `controlCenterActiveTouchButton`, `controlCenterButtonsHiddenByCollapse`, `controlCenterButtonsCollapsing`。


### `controlCenterExpansionProgress` — 原 2650 行；原模块保留

原文件引用位置：2542, 2968。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `findControlCenterTopButtonHit` — 原 2662 行；原模块保留

原文件引用位置：2388, 2439, 2688。

成员依赖：无其他原模块成员引用。

companion 引用：`controlCenterButtonsLock`, `controlCenterPlusButton`, `controlCenterPowerButton`。


### `isControlCenterDispatchOwner` — 原 2677 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isControlCenterTopButtonHit` — 原 2687 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`findControlCenterTopButtonHit`。

companion 引用：无。


### `performControlCenterTopButtonAction` — 原 2690 行；原模块保留

原文件引用位置：2455, 2852。

成员依赖：`showControlCenterEdit`, `showCachedGlobalActions`。

companion 引用：`CONTROL_CENTER_BUTTON_ACTION_DEBOUNCE_MS`, `controlCenterButtonsLock`, `controlCenterPlusButton`, `controlCenterPowerButton`, `controlCenterLastActionButton`, `controlCenterLastActionUptime`。


### `showControlCenterEdit` — 原 2709 行；原模块保留

原文件引用位置：2703。

成员依赖：`readInstanceField`。

companion 引用：`TAG`, `controlCenterButtonsLock`, `controlCenterEditTarget`, `controlCenterEditController`。


### `installControlCenterTopButtons` — 原 2763 行；原模块保留

原文件引用位置：2161, 2163, 2167, 2246, 2335。

成员依赖：`findControlCenterWindowRoot`, `installControlCenterTopButtonsIntoRoot`, `readInstanceField`。

companion 引用：`TAG`, `controlCenterButtonsLock`, `controlCenterEditTarget`, `controlCenterControllerClassLoader`。

目标/签名/配置/Hook ID 交叉检查：

- `it.name == "getEditButton" && it.parameterCount == 0`
- `mainPanelRef.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(mainPanelRef)`
- `mainPanel.javaClass.methods.firstOrNull { it.name == "getView" && it.parameterCount == 0 }?.invoke(mainPanel)`


### `findControlCenterWindowRoot` — 原 2807 行；原模块保留

原文件引用位置：2784, 2801。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installControlCenterTopButtonsIntoRoot` — 原 2820 行；原模块保留

原文件引用位置：2289, 2291, 2786, 2801, 2804。

成员依赖：`installControlCenterRootDispatchHook`, `updateControlCenterTopButtonsProgress`, `controlCenterExpansionProgress`, `performControlCenterTopButtonAction`, `applyControlCenterTopButtonBackground`, `applyControlCenterTopButtonIconScale`, `controlCenterTopButtonsMaterialClassLoader`。

companion 引用：`TAG`, `ADD_CONTROL_CENTER_TOP_BUTTONS_KEY`, `KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE`, `CONTROL_CENTER_PLUS_TAG`, `CONTROL_CENTER_POWER_TAG`, `controlCenterButtonsLock`, `controlCenterRoot`, `controlCenterPlusButton`, `controlCenterPowerButton`, `controlCenterExpandController`, `controlCenterButtonsHiddenByCollapse`。

目标/签名/配置/Hook ID 交叉检查：

- `val enabled = preferences.getBoolean(ADD_CONTROL_CENTER_TOP_BUTTONS_KEY, false) &&`
- `preferences.getBoolean(KEY_SHOW_CONTROL_CENTER_TOP_BUTTONS_IN_LANDSCAPE, false))`


### `applyControlCenterTopButtonBackground` — 原 2973 行；原模块保留

原文件引用位置：2945。

成员依赖：`applyControlCenterTopButtonMaterial`, `applyControlCenterTopButtonSoftGlass`。

companion 引用：`KEY_CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_MODE`, `KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_PURE_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BACKGROUND_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKGROUND_RADIUS`, `CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_DEFAULT_DP`, `CONTROL_CENTER_TOP_BUTTONS_BACKGROUND_RADIUS_MAX_DP`, `SHORTCUT_PURE_COLOR`。


### `applyControlCenterTopButtonIconScale` — 原 3055 行；原模块保留

原文件引用位置：2940。

成员依赖：无其他原模块成员引用。

companion 引用：`KEY_CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE`, `CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE_MIN`, `CONTROL_CENTER_TOP_BUTTONS_ICON_SCALE_MAX`。


### `controlCenterTopButtonsMaterialClassLoader` — 原 3065 行；原模块保留

原文件引用位置：2948。

成员依赖：无其他原模块成员引用。

companion 引用：`controlCenterButtonsLock`, `systemUiClassLoader`, `controlCenterControllerClassLoader`。


### `applyControlCenterTopButtonMaterial` — 原 3070 行；原模块保留

原文件引用位置：3042。

成员依赖：`applyControlCenterTopButtonSoftGlass`, `applyLegacyBackdropMaterial`。

companion 引用：`TAG`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_OPACITY`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_ADVANCED_MATERIAL_HIGHLIGHT`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`。


### `applyControlCenterTopButtonSoftGlass` — 原 3104 行；原模块保留

原文件引用位置：3049, 3099。

成员依赖：`applyLegacyBackdropMaterial`, `applySystemGlassMaterial`。

companion 引用：`TAG`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_COLOR`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_OPACITY`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_BLUR_RADIUS`, `KEY_CONTROL_CENTER_TOP_BUTTONS_SOFT_GLASS_LUMINANCE`, `DEFAULT_SOFT_GLASS_COLOR`, `DEFAULT_SOFT_GLASS_OPACITY`, `DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`。


### `showCachedGlobalActions` — 原 3139 行；原模块保留

原文件引用位置：2704。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `globalActionsLock`, `globalActionsPlugin`, `globalActionsManager`, `globalActionsComponent`, `globalActionsImpl`, `commandQueue`。


### `installGlobalActionsHook` — 原 3182 行；原模块保留

原文件引用位置：1607, 5197。

成员依赖：`hookDeoptimized`, `readInstanceField`。

companion 引用：`TAG`, `GLOBAL_ACTIONS_PLUGIN_CLASS`, `GLOBAL_ACTIONS_COMPONENT_CLASS`, `GLOBAL_ACTIONS_IMPL_CLASS`, `COMMAND_QUEUE_CLASS`, `globalActionsHookedClasses`, `globalActionsHookInstalled`, `globalActionsLock`, `globalActionsPlugin`, `globalActionsManager`, `globalActionsComponent`, `globalActionsImpl`, `commandQueue`。

目标/签名/配置/Hook ID 交叉检查：

- `val targetClass = alreadyLoadedClass?.takeIf { it.name == className }`
- `?: classLoader.loadClass(className)`
- `GLOBAL_ACTIONS_IMPL_CLASS -> method.name == "showGlobalActions" && method.parameterCount == 1`
- `GLOBAL_ACTIONS_COMPONENT_CLASS -> method.name in setOf("handleShowGlobalActionsMenu", "handleShowOrHideGlobalActionsMenu", "start")`
- `COMMAND_QUEUE_CLASS -> method.name == "showGlobalActionsMenu" && method.parameterCount == 0`
- `.setId("control-center:global-actions-${className.hashCode()}-$index")`


### `installMusicControlWhitelistHook` — 原 3258 行；背屏应用链

原文件引用位置：1797。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `if (!preferences.getBoolean(HOOK_MUSIC_CONTROLS_WHITELIST, true)) return`
- `.mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }`
- `val whitelist = preferences.getStringSet(MUSIC_CONTROLS_WHITELIST_APPS, emptySet()).orEmpty()`
- `method.name == "a" &&`
- `method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&`
- `method.returnType == String::class.java`
- `.setId("rear-music-whitelist:business")`
- `method.name == "b" &&`
- `method.parameterTypes.isEmpty() &&`
- `Set::class.java.isAssignableFrom(method.returnType)`
- `.setId("rear-music-whitelist:supported-apps")`
- `method.name == "c" &&`
- `method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-music-whitelist:is-music")`
- `.mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }`
- `method.name == "k" &&`
- `method.parameterTypes.size == 3 &&`
- `method.parameterTypes[0] == String::class.java &&`
- `Set::class.java.isAssignableFrom(method.parameterTypes[1]) &&`
- `Map::class.java.isAssignableFrom(method.parameterTypes[2]) &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-music-whitelist:is-enabled")`


### `installRearScreenAppWidgetUnlockHooks` — 原 3381 行；背屏应用链

原文件引用位置：1793。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `REAR_SCREEN_APP_WIDGET_PROPERTY`, `REAR_SCREEN_APP_WIDGET_SETTING`, `REAR_SCREEN_THEME_WIDGET_SETTING`, `REAR_SCREEN_AOD_SETTING`, `REAR_SCREEN_AOD_MODE_SETTING`, `REAR_SCREEN_STOCK_REMINDER_SETTING`。

目标/签名/配置/Hook ID 交叉检查：

- `val deviceUtils = classLoader.loadClass("com.android.thememanager.basemodule.utils.DeviceUtils")`
- `it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-app-widget:device-capability")`
- `val deviceHelper = classLoader.loadClass("miuix.os.DeviceHelper")`
- `it.name == "toq" && it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-app-widget:device-helper-capability")`
- `val rearFunction = classLoader.loadClass("com.rearScreen.manager.RearScreenFunction")`
- `method.parameterTypes.isEmpty() && method.returnType == Boolean::class.javaPrimitiveType &&`
- `method.name in setOf("q", "toq")`
- `.setId("rear-screen-app-widget:theme-rear-function:${method.name}")`
- `val supportGuard = classLoader.loadClass("com.rearScreen.miclaw.appfunction.common.DeviceSupportGuard")`
- `method.name == "isSupported" && method.parameterTypes.isEmpty() &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-app-widget:theme-device-support-guard")`
- `val localSettingUtils = classLoader.loadClass("o2.AbstractC0666c")`
- `method.name == "g" && method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&`
- `java.util.HashMap::class.java.isAssignableFrom(method.returnType)`
- `.setId("rear-screen-app-widget:subscreen-migrated-card-switches")`
- `val properties = Class.forName("android.os.SystemProperties", false, null)`
- `method.name in setOf("get", "getBoolean", "getInt", "getLong") &&`
- `method.parameterTypes.firstOrNull() == String::class.java`
- `.setId("rear-screen-app-widget:property:${method.name}:${method.parameterTypes.size}")`
- `Class.forName("miuix.os.SystemProperties", false, null)`
- `method.name in setOf("get", "getBoolean", "getInt", "toq", "zy", "k") &&`
- `method.parameterTypes.firstOrNull() == String::class.java`
- `.setId("rear-screen-app-widget:miui-property:${method.name}:${method.parameterTypes.size}")`
- `when (method.returnType) {`
- `when (method.returnType) {`
- `method.name in setOf("getInt", "getString") &&`
- `method.parameterTypes.getOrNull(1) == String::class.java`
- `.setId("rear-screen-app-widget:secure:${method.name}:${method.parameterTypes.size}")`
- `if (method.name == "getString") "1" else 1`
- `classLoader.loadClass("com.personalizedEditor.helper.settings.SettingRepository")`
- `method.name == "zy" && method.parameterTypes.size == 1 && method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-app-widget:theme-manager-setting-repository")`
- `?.firstOrNull { it.name in setOf("getKey", "k") && it.parameterTypes.isEmpty() }`


### `installPersonalAssistantRearScreenAppCardHooks` — 原 3610 行；背屏应用链

原文件引用位置：1804。

成员依赖：`hookDeoptimized`, `containsRearScreenRequestUrl`, `setStaticFieldValue`。

companion 引用：`TAG`, `personalAssistantRearScreenRequest`。

目标/签名/配置/Hook ID 交叉检查：

- `val wrapper = classLoader.loadClass("c8.d\$c")`
- `method.name == "a" && method.parameterTypes.size == 1 &&`
- `method.returnType.name == "okhttp3.v"`
- `.setId("rear-screen-app-card:request-scope")`
- `val commonParams = classLoader.loadClass("com.miui.personalassistant.network.util.a")`
- `method.name == "a" &&`
- `method.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java)) &&`
- `method.returnType == JSONObject::class.java`
- `.setId("rear-screen-app-card:environment-signal")`
- `method.name == "put" &&`
- `method.parameterTypes.contentEquals(arrayOf(String::class.java, Any::class.java)) &&`
- `method.returnType == JSONObject::class.java`
- `.setId("rear-screen-app-card:json-device-fields")`
- `val deviceInfo = classLoader.loadClass(`
- `method.name == "b" && method.parameterTypes.isEmpty() &&`
- `method.returnType.name == "okhttp3.o"`
- `.setId("rear-screen-app-card:device-form")`
- `constructor.parameterTypes.size == 2 && constructor.parameterTypes.all {`
- `val systemInfo = classLoader.loadClass("com.miui.personalassistant.utils.z1")`
- `method.name == "b" && method.parameterTypes.isEmpty() &&`
- `method.returnType == Int::class.javaPrimitiveType`
- `.setId("rear-screen-app-card:personal-assistant-os-version")`
- `val storeViewModel = classLoader.loadClass(`
- `method.name == "a" && method.parameterTypes.size == 2 &&`
- `method.parameterTypes[1] == List::class.java &&`
- `List::class.java.isAssignableFrom(method.returnType)`
- `.setId("rear-screen-app-card:personal-assistant-catalog-filter")`
- `val installedCache = classLoader.loadClass("com.miui.personalassistant.utils.n0")`
- `method.name == "d" && method.parameterTypes.isEmpty()`
- `.setId("rear-screen-app-card:augment-installed-package-list")`
- `val appBaseInfo = classLoader.loadClass("com.miui.personalassistant.utils.AppBaseInfo")`
- `method.name == "b" && method.parameterTypes.isEmpty() &&`
- `method.returnType == String::class.java`
- `.setId("rear-screen-app-card:refresh-installed-package-cache")`
- `val packageUtils = classLoader.loadClass("com.miui.personalassistant.utils.m1")`
- `method.name == "i" && method.parameterTypes.contentEquals(`
- `) && method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-app-card:personal-assistant-bound-app-installed")`
- `val defaultConfig = classLoader.loadClass(`
- `method.name == "c" && method.parameterTypes.size == 2 &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-app-card:default-service-filter")`
- `?.firstOrNull { it.name == "getServiceKey" && it.parameterTypes.isEmpty() }`


### `containsRearScreenRequestUrl` — 原 3912 行；背屏应用链

原文件引用位置：3623。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `installPersonalAssistantHongkongBuildProfile` — 原 3942 行；背屏应用链

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val unsafeClass = Class.forName("sun.misc.Unsafe")`
- `val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)`
- `val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long`
- `unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)`
- `val miuiBuild = Class.forName("miui.os.Build")`
- `val unsafeClass = Class.forName("sun.misc.Unsafe")`
- `val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)`
- `val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long`
- `unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)`


### `installSubScreenCenterAppWidgetState` — 原 3997 行；背屏应用链

原文件引用位置：1795。

成员依赖：`setStaticFieldValue`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val config = classLoader.loadClass("o2.C0673j")`


### `installThemeManagerRearScreenFeatureGuards` — 原 4009 行；背屏应用链

原文件引用位置：1811。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val addDao = classLoader.loadClass("com.rearScreen.aiapp.db.RearScreenAiAddDao_Impl")`
- `method.name == "zy" && method.parameterTypes.size == 2 &&`
- `method.parameterTypes[0] == String::class.java`
- `.setId("rear-screen-feature:remove-imported-ai-app-files")`
- `val appliedRepository = classLoader.loadClass("com.rearScreen.aiapp.repository.AiAppAppliedRepository")`
- `method.name == "zy" && method.parameterTypes.size == 2 &&`
- `method.parameterTypes[0] == String::class.java`
- `.setId("rear-screen-feature:remove-applied-ai-app-files")`
- `val type = classLoader.loadClass(className)`
- `method.name == methodName && method.returnType == Boolean::class.javaPrimitiveType`
- `hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId(id).intercept { false }`
- `val usage = classLoader.loadClass("com.android.thememanager.util.ai.AiUsageRepository")`
- `method.name == "isDeviceTrusted" && method.parameterTypes.size == 1 &&`
- `method.returnType == Any::class.java`
- `.setId("rear-screen-feature:theme-ai-device-trust-direct")`
- `val guard = classLoader.loadClass("com.rearScreen.aiapp.manager.RearScreenAiAppGenerateGuard")`
- `method.name == "q" && method.parameterTypes.size == 1 &&`
- `method.returnType == Any::class.java`
- `.setId("rear-screen-feature:ai-app-device-trust")`
- `method.name == "zy" && method.parameterTypes.size == 1 &&`
- `method.returnType == Any::class.java`
- `.setId("rear-screen-feature:ai-app-block-reason")`
- `val router = classLoader.loadClass("com.android.thememanager.activity.ai.viewmodel.AiRouterVM")`
- `method.name == "zsr0" && method.parameterTypes.size == 1 && method.returnType == Any::class.java`
- `.setId("rear-screen-feature:theme-ai-device-trust")`
- `val appUtils = classLoader.loadClass("com.android.thememanager.library.util.app.AppUtils")`
- `method.name == "toq" && method.parameterTypes.isEmpty() &&`
- `method.returnType == Int::class.javaPrimitiveType`
- `.setId("rear-screen-feature:theme-version-for-ai-preset-filter")`
- `val miuiUtils = classLoader.loadClass("com.android.thememanager.basemodule.utils.MiuiUtils")`
- `method.name == "zurt" && method.parameterTypes.isEmpty() &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-feature:theme-ai-preset-filter")`
- `val presetRepository = classLoader.loadClass("com.rearScreen.repository.RearScreenPresetRepository")`
- `method.name == "cdj" && method.parameterTypes.size == 1 &&`
- `java.util.ArrayList::class.java.isAssignableFrom(method.parameterTypes[0]) &&`
- `method.returnType == Void.TYPE`
- `.setId("rear-screen-feature:theme-ai-preset-sanitizer")`
- `val miuiVersion = classLoader.loadClass("com.android.thememanager.basemodule.utils.MiuiUtils\$MiuiVersion")`
- `method.name == "toq" && method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-feature:miui-version-capability")`
- `val settingModule = classLoader.loadClass("com.personalizedEditor.helper.settings.RearScreenSettingModule\$Companion")`
- `method.name == "k" && method.parameterTypes.isEmpty() &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-feature:setting-module-entry")`
- `val interceptor = classLoader.loadClass(`
- `method.parameterTypes.size == 3 &&`
- `method.parameterTypes[0].name == "okhttp3.Request" &&`
- `LinkedHashMap::class.java.isAssignableFrom(method.parameterTypes[1]) &&`
- `method.parameterTypes[2] == String::class.java &&`
- `method.returnType.name == "okhttp3.Request"`
- `.setId("rear-screen-feature:theme-final-network-profile")`
- `val listVm = classLoader.loadClass("com.rearScreen.viewModel.RearScreenListViewModel")`
- `method.name == "gbni" && method.parameterTypes.size == 1 &&`
- `method.returnType == Boolean::class.javaPrimitiveType`
- `.setId("rear-screen-feature:keep-ai-categories")`
- `method.name == "wo" && method.parameterTypes.size == 2 &&`
- `method.returnType == Void.TYPE`
- `.setId("rear-screen-feature:unshield-ai-categories")`
- `?.firstOrNull { it.name == "getCategoryType" && it.parameterTypes.isEmpty() }`
- `?.firstOrNull { it.name == "setShield" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }`
- `?.firstOrNull { it.name == "getItemList" && it.parameterTypes.isEmpty() }`
- `it.name == "getInnerTags" && it.parameterTypes.isEmpty()`
- `it.name == "setInnerTags" && it.parameterTypes.size == 1`


### `setStaticFieldValue` — 原 4327 行；背屏应用链

原文件引用位置：3855, 4000, 4001。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `val unsafeClass = Class.forName("sun.misc.Unsafe")`
- `val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)`
- `val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long`
- `is Boolean -> unsafeClass.getMethod("putBoolean", Any::class.java, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)`
- `is Int -> unsafeClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)`
- `else -> unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)`


### `installCustomRearScreenWidgetRegistration` — 原 4355 行；背屏应用链

原文件引用位置：1825。

成员依赖：`customRearScreenWidgetRegistrationInstalled`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val contextManager = classLoader.loadClass("com.android.thememanager.basemodule.context.AppContextManager")`
- `val context = contextManager.getMethod("q").invoke(null) as? Context ?: return@runCatching`
- `val widgetClass = classLoader.loadClass("com.xiaomi.subscreencenter.service.Widget")`
- `val subScreenClass = classLoader.loadClass("com.xiaomi.subscreencenter.service.SubScreen")`
- `val widgetFactory = widgetClass.methods.firstOrNull { it.name == "q" && it.parameterTypes.size == 7 }`
- `val subScreen = subScreenClass.getMethod("g", Context::class.java).invoke(null, context)`
- `val insert = subScreen.javaClass.methods.firstOrNull { it.name == "x2" && it.parameterTypes.size == 1 }`


### `scheduleSoftGlassThemeActivation` — 原 4403 行；原模块保留

原文件引用位置：1582。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `themeOverrideReady`, `themeActivationScheduled`。


### `installSoftGlassThemeSystemUiHook` — 原 4415 行；原模块保留

原文件引用位置：1728。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `MIUI_MATERIAL_UTILS_CLASS`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `themeOverrideReady`。

目标/签名/配置/Hook ID 交叉检查：

- `val materialUtils = classLoader.loadClass(MIUI_MATERIAL_UTILS_CLASS)`
- `hook(materialUtils.getMethod("onDefaultThemeChanged", Boolean::class.javaPrimitiveType))`
- `.setId("soft-glass-theme:systemui-default-theme")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`


### `installSoftGlassThemePluginHook` — 原 4439 行；原模块保留

原文件引用位置：1734, 1755。

成员依赖：`forceThemeUtilsFlags`, `installSoftGlassThemePluginMaterialGuards`。

companion 引用：`TAG`, `MIUI_THEME_UTILS_CLASS`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `themeOverrideReady`。

目标/签名/配置/Hook ID 交叉检查：

- `val themeUtils = classLoader.loadClass(MIUI_THEME_UTILS_CLASS)`
- `hook(themeUtils.getMethod(methodName))`
- `.setId("soft-glass-theme:plugin-$methodName")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`
- `hook(themeUtils.getMethod(methodName))`
- `.setId("soft-glass-theme:plugin-$methodName")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) && themeOverrideReady) {`


### `installSoftGlassThemeClassLoadGuard` — 原 4483 行；原模块保留

原文件引用位置：1735。

成员依赖：`installSoftGlassThemePluginClass`。

companion 引用：`TAG`, `MIUI_THEME_UTILS_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `hook(ClassLoader::class.java.getMethod("loadClass", String::class.java))`
- `.setId("soft-glass-theme:plugin-class-load")`


### `installSoftGlassThemePluginClass` — 原 4501 行；原模块保留

原文件引用位置：4491。

成员依赖：`forceThemeUtilsFlags`, `installSoftGlassThemePluginMaterialGuards`。

companion 引用：`TAG`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `dynamicPluginThemeHookInstalled`, `themeOverrideReady`。

目标/签名/配置/Hook ID 交叉检查：

- `hook(themeUtils.getMethod(methodName))`
- `.setId("soft-glass-theme:dynamic-$methodName")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`
- `hook(themeUtils.getMethod(methodName))`
- `.setId("soft-glass-theme:dynamic-$methodName")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) && themeOverrideReady) {`


### `installDefaultThemeStateGuard` — 原 4545 行；原模块保留

原文件引用位置：1736。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `themeOverrideReady`。

目标/签名/配置/Hook ID 交叉检查：

- `val themeClass = classLoader.loadClass("com.miui.utils.MiuiThemeUtils")`
- `hook(classLoader.loadClass("com.android.systemui.statusbar.phone.ConfigurationControllerImpl")`
- `.getMethod("onConfigurationChanged", android.content.res.Configuration::class.java))`
- `.setId("soft-glass-theme:systemui-state-guard")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`


### `forceThemeUtilsFlags` — 原 4576 行；原模块保留

原文件引用位置：4468, 4475, 4527, 4536。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installSoftGlassThemePluginMaterialGuards` — 原 4588 行；原模块保留

原文件引用位置：4473, 4533。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `MI_BLUR_COMPAT_CLASS`, `MIUI_DEFAULT_THEME_CONTROLLER_IMPL_CLASS`, `KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME`, `softGlassThemePluginMaterialHooksInstalled`, `themeOverrideReady`。

目标/签名/配置/Hook ID 交叉检查：

- `val blurCompat = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)`
- `hook(blurCompat.getMethod(`
- `.setId("soft-glass-theme:plugin-material-enabled")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`
- `val defaultThemeController = classLoader.loadClass(MIUI_DEFAULT_THEME_CONTROLLER_IMPL_CLASS)`
- `hook(defaultThemeController.getMethod("isDefaultTheme"))`
- `.setId("soft-glass-theme:plugin-default-theme-controller")`
- `if (preferences.getBoolean(KEY_KEEP_SOFT_GLASS_AFTER_GLOBAL_THEME, false) &&`


### `installStatusBarVisibilityHook` — 原 4632 行；原模块保留

原文件引用位置：1740。

成员依赖：`statusBarHooks`, `isControlCenterEditButtonView`, `logControlCenterEditButtonHidden`。

companion 引用：`TAG`, `KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR`, `KEY_HIDE_STATUS_BAR_WIFI_STANDARD`, `KEY_HIDE_STATUS_BAR_CLOCK_TEXT`, `KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY`, `KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON`, `KEY_MOBILE_NETWORK_TYPE_MODE`。

目标/签名/配置/Hook ID 交叉检查：

- `hook(View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType))`
- `.setId("status-bar:visibility")`
- `if (preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&`
- `val hideWifiStandard = preferences.getBoolean(KEY_HIDE_STATUS_BAR_WIFI_STANDARD, false)`
- `val hideClockText = preferences.getBoolean(KEY_HIDE_STATUS_BAR_CLOCK_TEXT, false)`
- `val hideNetworkActivity = preferences.getBoolean(KEY_HIDE_STATUS_BAR_NETWORK_ACTIVITY, false)`
- `statusBarHooks.shouldHideSystemMobileSignal(view, preferences.getInt(KEY_MOBILE_SIGNAL_HIDE_MODE, 0))`
- `preferences.getBoolean(KEY_HIDE_NOTIFICATION_MINI_WINDOW_BAR, false)) \|\|`
- `hook(View::class.java.getDeclaredMethod("onAttachedToWindow"))`
- `.setId("control-center:hide-edit-button-attach")`
- `if (preferences.getBoolean(KEY_HIDE_CONTROL_CENTER_EDIT_BUTTON, false) &&`


### `isControlCenterEditButtonView` — 原 4718 行；原模块保留

原文件引用位置：4644, 4699。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `logControlCenterEditButtonHidden` — 原 4725 行；原模块保留

原文件引用位置：4647, 4702。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`。


### `installClockMaterialLimitHook` — 原 4745 行；原模块保留

原文件引用位置：1750, 1767。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `CLOCK_UTILITY_CLASS`, `CLOCK_UTILITY_METHOD`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val utilityClass = classLoader.loadClass(CLOCK_UTILITY_CLASS)`
- `it.name == CLOCK_UTILITY_METHOD && it.parameterCount == 3`
- `.setId("clock-material-limit")`
- `if (!preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {`
- `val bean = (0 until applyMethod.parameterCount)`
- `candidate.javaClass.getMethod("getClockEffect")`
- `bean?.javaClass?.getMethod("getClockEffect")?.invoke(bean) as? Int`
- `bean?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)`


### `installDynamicIslandHooks` — 原 4793 行；原模块保留

原文件引用位置：1614, 5204。

成员依赖：`systemUiHooks`, `installMediaSourceIconHooks`, `installFocusIslandWhitelistPluginHooks`, `installDynamicIslandBackgroundHooks`, `installDynamicIslandSelfBlurHook`, `installDynamicIslandExpandedMaterialHook`。

companion 引用：`TAG`, `focusIslandWhitelistPluginHooksInstalled`。

目标/签名/配置/Hook ID 交叉检查：




### `installMediaSourceIconHooks` — 原 4816 行；原模块保留

原文件引用位置：1618, 4802。

成员依赖：`hookDeoptimized`, `applyMediaCardAdjustments`。

companion 引用：`TAG`, `KEY_HIDE_SYSTEM_MEDIA_SOURCE_ICON`, `KEY_HIDE_MEDIA_ISLAND_SOURCE_ICON`, `KEY_SYSTEM_MEDIA_INFO_VERTICAL_OFFSET`, `KEY_MEDIA_ISLAND_INFO_VERTICAL_OFFSET`。

目标/签名/配置/Hook ID 交叉检查：

- `val targetClass = classLoader.loadClass(className)`
- `it.name == "bindMediaData" && it.parameterTypes.size == 1`
- `.setId("media-card-adjustments:$sourceIconKey:$index")`


### `applyMediaCardAdjustments` — 原 4853 行；原模块保留

原文件引用位置：4844。

成员依赖：无其他原模块成员引用。

companion 引用：`SYSTEM_UI`, `KEY_MEDIA_TITLE_ARTIST_SPACING`, `KEY_MEDIA_COVER_CORNER_RADIUS_OFFSET`。


### `findDynamicIslandField` — 原 4890 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installFocusIslandWhitelistPluginHooks` — 原 4906 行；原模块保留

原文件引用位置：1641, 4807, 5219。

成员依赖：`hookDeoptimized`, `isFocusWhitelistBypassEnabled`, `allowIslandStateCallbackPackage`。

companion 引用：`TAG`, `PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS`, `FOCUS_NOTIFICATION_UTILS_CLASS`, `FOCUS_NOTIFICATION_CONTROLLER_CLASS`, `DYNAMIC_ISLAND_EVENT_COORDINATOR_CLASS`, `ISLAND_STATE_CALLBACK_CONTROLLER_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `classLoader.loadClass("miui.systemui.notification.focus.SignatureChecker")`
- `it.name == "checkSignatures" && it.returnType == Boolean::class.javaPrimitiveType`
- `.setId("unlock-all-focus:signature:$index")`
- `if (preferences.getBoolean(KEY_UNLOCK_ALL_FOCUS, false)) true else chain.proceed()`
- `val settingsClass = classLoader.loadClass(PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS)`
- `hook(settingsClass.getMethod(name, String::class.java))`
- `.setId("focus-island-whitelist-plugin:$name")`
- `hook(settingsClass.getMethod("canShowFocus", android.content.Context::class.java, String::class.java))`
- `.setId("focus-island-whitelist-plugin:canShowFocus")`
- `val focusUtilsClass = classLoader.loadClass(FOCUS_NOTIFICATION_UTILS_CLASS)`
- `it.name == "canShowFocus" && it.parameterCount == 3`
- `.setId("focus-island-whitelist-plugin:focus-permission")`
- `val controllerClass = classLoader.loadClass(FOCUS_NOTIFICATION_CONTROLLER_CLASS)`
- `it.name == "fetchAuthResult" && it.parameterCount == 5`
- `.setId("focus-island-whitelist-plugin:auth-result")`
- `sbn?.javaClass?.getMethod("getKey")?.invoke(sbn) as? String`
- `it.name == "onAuthSuccess" && it.parameterCount == 2`
- `val coordinatorClass = classLoader.loadClass(DYNAMIC_ISLAND_EVENT_COORDINATOR_CLASS)`
- `hook(coordinatorClass.getMethod("mediaIslandSupportMiniWindow", String::class.java))`
- `.setId("focus-island-whitelist-plugin:event-coordinator")`
- `val stateCallbackClass = classLoader.loadClass(ISLAND_STATE_CALLBACK_CONTROLLER_CLASS)`
- `it.name == "buildPendingStateCallback" && it.parameterCount == 3`
- `.setId("focus-island-whitelist-plugin:state-callback")`


### `isFocusWhitelistBypassEnabled` — 原 5044 行；原模块保留

原文件引用位置：4930, 4941, 4956, 4976, 5017, 5032, 5087, 5112。

成员依赖：无其他原模块成员引用。

companion 引用：`KEY_REMOVE_FOCUS_AND_ISLAND_WHITELIST_LIMIT`。


### `allowIslandStateCallbackPackage` — 原 5048 行；原模块保留

原文件引用位置：5033。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `DYNAMIC_ISLAND_SOURCE_PACKAGE_KEY`。


### `installFocusIslandWhitelistSystemUiHooks` — 原 5072 行；原模块保留

原文件引用位置：1631。

成员依赖：`isFocusWhitelistBypassEnabled`。

companion 引用：`TAG`, `SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS`, `NOTIFICATION_PROVIDER_PUBLIC_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val settingsClass = classLoader.loadClass(SYSTEM_UI_NOTIFICATION_SETTINGS_MANAGER_CLASS)`
- `hook(settingsClass.getMethod(name, android.content.Context::class.java, String::class.java))`
- `.setId("focus-island-whitelist-systemui:$name")`
- `val providerClass = classLoader.loadClass(NOTIFICATION_PROVIDER_PUBLIC_CLASS)`
- `providerClass.getMethod(`
- `.setId("focus-island-whitelist-systemui:provider-call")`


### `installDynamicIslandClassDiscovery` — 原 5133 行；原模块保留

原文件引用位置：1610。

成员依赖：`controlCenterHooks`, `islandSmoothingHook`, `lockscreenFaceUnlockHook`, `installControlCenterEditButtonHook`, `installControlCenterMainPanelHook`, `installControlCenterHeaderLifecycleHook`, `installControlCenterTouchHook`, `installControlCenterEventHandlerHook`, `installControlCenterExpandLifecycleHook`, `installGlobalActionsHook`, `installDynamicIslandHooks`, `installFocusIslandWhitelistPluginHooks`。

companion 引用：`TAG`, `CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS`, `CONTROL_CENTER_CONTENT_DISTRIBUTOR_CLASS`, `CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS`, `CONTROL_CENTER_EXPAND_CONTROLLER_CLASS`, `CONTROL_CENTER_EVENT_HANDLER_CLASS`, `GLOBAL_ACTIONS_PLUGIN_CLASS`, `GLOBAL_ACTIONS_COMPONENT_CLASS`, `GLOBAL_ACTIONS_IMPL_CLASS`, `COMMAND_QUEUE_CLASS`, `CONTROL_CENTER_TOUCH_CONTROLLER_CLASS`, `CONTROL_CENTER_HEADER_CONTROLLER_CLASS`, `DYNAMIC_ISLAND_BACKGROUND_CLASS`, `PLUGIN_NOTIFICATION_SETTINGS_MANAGER_CLASS`, `dynamicIslandHooksInstalled`, `focusIslandWhitelistPluginHooksInstalled`, `focusIslandWhitelistPluginInstalling`, `globalActionsHookedClasses`, `controlCenterEditButtonHookInstalled`, `controlCenterContentDistributorHookInstalled`, `controlCenterTopButtonsHookInstalled`, `controlCenterExpandLifecycleHookInstalled`, `controlCenterTouchHookInstalled`, `controlCenterHeaderLifecycleHookInstalled`, `controlCenterClassDiscoveryInProgress`, `systemUiClassLoader`。

目标/签名/配置/Hook ID 交叉检查：

- `val loadClassMethod = ClassLoader::class.java.getMethod("loadClass", String::class.java)`
- `// ClassLoader.loadClass(String, boolean) is protected on Android.  Looking it up`
- `// with getMethod() throws before either discovery hook is installed, which leaves`
- `val loadClassWithResolveMethod = ClassLoader::class.java.getDeclaredMethod(`
- `.setId("dynamic-island-class-discovery")`
- `.setId("dynamic-island-class-discovery-resolve")`
- `runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_EDIT_BUTTON_CONTROLLER_CLASS) }`
- `runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_MAIN_PANEL_CONTROLLER_CLASS) }`
- `runCatching { systemUiClassLoader.loadClass(CONTROL_CENTER_EXPAND_CONTROLLER_CLASS) }`


### `installDynamicIslandBackgroundHooks` — 原 5261 行；原模块保留

原文件引用位置：4797。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `DYNAMIC_ISLAND_BACKGROUND_CLASS`, `expandedIslandMaterialSettings`。

目标/签名/配置/Hook ID 交叉检查：

- `val backgroundClass = classLoader.loadClass(DYNAMIC_ISLAND_BACKGROUND_CLASS)`
- `"setDrawable" -> backgroundClass.getMethod(name, Drawable::class.java)`
- `else -> backgroundClass.getMethod(name, Int::class.javaPrimitiveType)`
- `.setId("dynamic-island-background-$name")`


### `installDynamicIslandSelfBlurHook` — 原 5288 行；原模块保留

原文件引用位置：4801。

成员依赖：`expandedIslandBlurRadius`, `isDynamicIslandView`。

companion 引用：`TAG`, `MI_BLUR_COMPAT_CLASS`, `KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED`。

目标/签名/配置/Hook ID 交叉检查：

- `val blurClass = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)`
- `hook(blurClass.getMethod("setMiSelfBlur", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))`
- `.setId("dynamic-island-self-blur")`
- `preferences.getBoolean(KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED, false)`


### `installDynamicIslandExpandedMaterialHook` — 原 5314 行；原模块保留

原文件引用位置：4799。

成员依赖：`hookDeoptimized`, `applyExpandedIslandNativeMaterial`。

companion 引用：`TAG`, `DYNAMIC_ISLAND_BASE_CONTENT_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val baseClass = classLoader.loadClass(DYNAMIC_ISLAND_BASE_CONTENT_CLASS)`
- `val method = baseClass.getDeclaredMethod("updateBackgroundBg", View::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }`
- `hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("dynamic-island-expanded-native-material").intercept { chain ->`


### `applyExpandedIslandNativeMaterial` — 原 5328 行；原模块保留

原文件引用位置：5321。

成员依赖：`expandedIslandBlurRadius`。

companion 引用：`TAG`, `MI_BACKGROUND_STYLE_CLASS`, `MIUI_BLUR_UTILS_CLASS`, `MIN_GLASS_PARAMS_SIZE`, `GLASS_ALPHA_INDEX`, `KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED`, `KEY_EXPANDED_ISLAND_BACKGROUND_OPACITY`, `KEY_EXPANDED_ISLAND_GLASS_REFLECTION`, `KEY_EXPANDED_ISLAND_SHOW_HIGHLIGHT`。


### `expandedIslandBlurRadius` — 原 5354 行；原模块保留

原文件引用位置：5302, 5330。

成员依赖：无其他原模块成员引用。

companion 引用：`KEY_EXPANDED_ISLAND_BACKGROUND_BLUR_RADIUS`, `KEY_EXPANDED_ISLAND_GLASS_BLUR_RADIUS`, `KEY_EXPANDED_ISLAND_GLASS_LARGE_BLUR_RADIUS`, `KEY_EXPANDED_ISLAND_SELF_BLUR_RADIUS`。


### `applyExpandedIslandBackground` — 原 5360 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：`DYNAMIC_ISLAND_BACKGROUND_CLASS`, `KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED`。


### `findDynamicIslandBackground` — 原 5367 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`findViewRoot`。

companion 引用：`DYNAMIC_ISLAND_BACKGROUND_CLASS`。


### `findViewRoot` — 原 5383 行；原模块保留

原文件引用位置：5368。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isDynamicIslandView` — 原 5389 行；原模块保留

原文件引用位置：5299。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassRuntime` — 原 5392 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `volumeGlassRuntime` — 原 5393 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `controlCenterRadiusRuntime` — 原 5394 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `volumeGlobalRecipes` — 原 5395 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `volumeResourceNames` — 原 5396 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassBaselines` — 原 5397 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassLegacyBlends` — 原 5398 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `volumeOriginalDrawableAlphas` — 原 5399 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassArrayPairs` — 原 5400 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassRefreshHandler` — 原 5401 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassRefreshing` — 原 5402 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassBlendDispatching` — 原 5403 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `volumeMaterialDispatching` — 原 5404 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `standaloneSoftGlassSurfaces` — 原 5405 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `softGlassRefresh` — 原 5406 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`softGlassRuntime`, `volumeGlobalRecipes`, `softGlassBaselines`, `softGlassLegacyBlends`, `softGlassArrayPairs`, `softGlassRefreshing`, `standaloneSoftGlassSurfaces`, `volumeGlobalGlassSelected`, `isVolumePanelSurface`。

companion 引用：无。


### `rememberSoftGlassBaseline` — 原 5441 行；原模块保留

原文件引用位置：5526, 5541, 5569, 5584, 5597, 5618, 5666, 5686。

成员依赖：`softGlassBaselines`, `softGlassRefreshing`。

companion 引用：无。


### `volumeGlobalGlassSelected` — 原 5446 行；原模块保留

原文件引用位置：5430, 5452, 5459, 5464, 5471, 5506, 11899, 12076, 12099, 12115。

成员依赖：`volumeGlassRuntime`。

companion 引用：无。


### `isSoftGlassSurface` — 原 5448 行；原模块保留

原文件引用位置：5533, 5568, 5583, 5596。

成员依赖：`softGlassRefreshing`, `volumeGlobalGlassSelected`, `isNotificationRowBackground`, `isMediaNotificationView`, `isControlCenterCall`, `isNotificationCenterCall`, `isShadeBackgroundView`, `stackContainsClass`, `isVolumePanelSurface`。

companion 引用：`volumePanelSurfaceRoots`。


### `isSoftGlassPanelLayer` — 原 5458 行；原模块保留

原文件引用位置：5466。

成员依赖：`volumeGlobalGlassSelected`, `isShadeBackgroundView`, `stackContainsClass`, `isVolumePanelSurface`。

companion 引用：无。


### `isSoftGlassBlendSurface` — 原 5463 行；原模块保留

原文件引用位置：5612, 5644。

成员依赖：`softGlassRefreshing`, `volumeGlobalGlassSelected`, `isSoftGlassPanelLayer`, `isNotificationRowBackground`, `isHeadsUpNotificationSurface`, `isVolumePanelSurface`。

companion 引用：无。


### `usesStandaloneSoftGlass` — 原 5469 行；原模块保留

原文件引用位置：5623, 5650。

成员依赖：`softGlassRuntime`, `standaloneSoftGlassSurfaces`, `volumeGlobalGlassSelected`, `isHeadsUpNotificationSurface`, `isVolumePanelSurface`。

companion 引用：无。


### `updateStandaloneSoftGlass` — 原 5473 行；原模块保留

原文件引用位置：5507, 5511, 5528, 5534, 5556。

成员依赖：`standaloneSoftGlassSurfaces`。

companion 引用：无。


### `softGlassBlendPoints` — 原 5484 行；原模块保留

原文件引用位置：5623, 5669。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installShadeMaterialHooks` — 原 5489 行；原模块保留

原文件引用位置：1723。

成员依赖：`hookDeoptimized`, `softGlassRuntime`, `volumeGlobalRecipes`, `softGlassBaselines`, `softGlassLegacyBlends`, `softGlassArrayPairs`, `softGlassRefreshHandler`, `softGlassRefreshing`, `softGlassBlendDispatching`, `softGlassRefresh`, `rememberSoftGlassBaseline`, `volumeGlobalGlassSelected`, `isSoftGlassSurface`, `isSoftGlassBlendSurface`, `usesStandaloneSoftGlass`, `updateStandaloneSoftGlass`, `softGlassBlendPoints`, `installFocusNotificationMaterialEnforcementHooks`, `installFocusNotificationBackgroundHook`, `shouldUseNormalNotificationMaterial`, `normalNotificationGlassParams`, `normalNotificationBlendPoints`, `isNotificationRowBackground`, `stackContainsClass`, `isHeadsUpNotificationSurface`, `isVolumePanelSurface`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val method = View::class.java.getMethod("setMiGlass", FloatArray::class.java)`
- `.setId("soft-glass-v4-recipe")`
- `val method = View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)`
- `.setId("soft-glass-v4-dual-radius")`
- `val method = View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)`
- `.setId("soft-glass-v4-background-radius")`
- `val method = View::class.java.getMethod("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType)`
- `.setId("soft-glass-v4-sampling-scale")`
- `val method = View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java)`
- `.setId("soft-glass-v4-blend-batch")`
- `val clear = View::class.java.getMethod("clearMiBackgroundBlendColor")`
- `val add = View::class.java.getMethod("addMiBackgroundBlendColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)`
- `hookDeoptimized(clear).setExceptionMode(ExceptionMode.PROTECTIVE).setId("soft-glass-v4-blend-clear").intercept { chain ->`
- `softGlassBaselines[view]?.keys?.removeAll { it.name == "setMiBackgroundBlendColors" }`
- `hookDeoptimized(add).setExceptionMode(ExceptionMode.PROTECTIVE).setId("soft-glass-v4-blend-add").intercept { chain ->`
- `val method = View::class.java.getMethod("setMiSelfBlur", Int::class.javaPrimitiveType, ArrayList::class.java)`
- `.setId("soft-glass-v4-window-self-blur")`
- `val method = View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)`
- `.setId("soft-glass-v4-panel-color")`


### `installLockscreenClockColonHook` — 原 5693 行；原模块保留

原文件引用位置：1660, 1773。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `CLOCK_BEAN_CLASS`, `CLOCK_BEAN_IS_COLON_SHOW_METHOD`。

目标/签名/配置/Hook ID 交叉检查：

- `val method = classLoader.loadClass(CLOCK_BEAN_CLASS)`
- `.getDeclaredMethod(CLOCK_BEAN_IS_COLON_SHOW_METHOD)`
- `.setId("lockscreen-clock-colon:$scope")`
- `if (preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_COLON_FORCE_VISIBLE, false)) {`


### `installLockscreenCarrierHideHook` — 原 5718 行；原模块保留

原文件引用位置：1672。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val carrierClass = classLoader.loadClass("com.android.systemui.controlcenter.shade.ControlCenterCarrierText")`
- `val method = carrierClass.getDeclaredMethod("shouldShow").apply { isAccessible = true }`
- `.setId("lockscreen-carrier-hide")`
- `val mode = preferences.getInt(KEY_LOCKSCREEN_CARRIER_HIDE_MODE, LOCKSCREEN_CARRIER_HIDE_NONE)`
- `val slot = runCatching { carrier.javaClass.getMethod("getSlotId").invoke(carrier) as Int }`
- `val layoutClass = classLoader.loadClass("com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout")`
- `val onMeasure = layoutClass.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)`
- `.setId("lockscreen-carrier-hide-layout")`
- `val mode = preferences.getInt(KEY_LOCKSCREEN_CARRIER_HIDE_MODE, LOCKSCREEN_CARRIER_HIDE_NONE)`
- `layout.javaClass.getDeclaredMethod("getKeyguardHeaderLayout").apply { isAccessible = true }`
- `val slot = runCatching { child.javaClass.getMethod("getSlotId").invoke(child) as Int }`


### `installLockscreenBigClockWidthHook` — 原 5816 行；原模块保留

原文件引用位置：1664, 1777。

成员依赖：`hookDeoptimized`, `widenedClockRect`, `restoreRequestedClockWidth`。

companion 引用：`TAG`, `CLOCK_DEPTH_AVOID_RULE_UTILS_CLASS`, `ALL_IN_ONE_UTIL_CLASS`, `bigClockWidthCalculationDepth`。

目标/签名/配置/Hook ID 交叉检查：

- `val allInOneUtilClass = classLoader.loadClass(ALL_IN_ONE_UTIL_CLASS)`
- `method.name == "computeForScreen"`
- `.setId("lockscreen-big-clock-compute:$scope:$index")`
- `if (!preferences.getBoolean(KEY_LOCKSCREEN_BIG_CLOCK_WIDTH_LIMIT_REMOVED, false)) {`
- `val method = classLoader.loadClass(CLOCK_DEPTH_AVOID_RULE_UTILS_CLASS)`
- `.single { it.name == "createEditRect\$default" && it.returnType == Rect::class.java }`
- `.setId("lockscreen-big-clock-width:$scope")`
- `if (!preferences.getBoolean(KEY_LOCKSCREEN_BIG_CLOCK_WIDTH_LIMIT_REMOVED, false) \|\|`


### `installLockscreenBigClockEditorWidthHook` — 原 5880 行；原模块保留

原文件引用位置：1778。

成员依赖：`hookDeoptimized`, `widenedClockRect`。

companion 引用：`TAG`, `ALL_IN_ONE_TEMPLATE_VIEW_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val method = classLoader.loadClass(ALL_IN_ONE_TEMPLATE_VIEW_CLASS)`
- `.getDeclaredMethod("getMaxDragAllowedRect")`
- `.setId("lockscreen-big-clock-editor-width")`
- `if (!preferences.getBoolean(KEY_LOCKSCREEN_BIG_CLOCK_WIDTH_LIMIT_REMOVED, false)) {`


### `installLockscreenClockHorizontalRectHook` — 原 5908 行；原模块保留

原文件引用位置：1668, 1782。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `clockHorizontalRectOffsets`, `clockHorizontalLiveOffsets`。

目标/签名/配置/Hook ID 交叉检查：

- `val baseClass = classLoader.loadClass("com.miui.clock.allInOne.AllInOneBase")`
- `.getDeclaredMethod("setClockViewRect", Rect::class.java)`
- `.setId("lockscreen-clock-horizontal-rect:$scope")`
- `val offset = if (preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false) &&`


### `installLockscreenClockHorizontalEditorHook` — 原 5959 行；原模块保留

原文件引用位置：1783。

成员依赖：`hookDeoptimized`, `handleLockscreenClockHorizontalTouch`, `findAllInOneEditorForWallpaper`, `findAllInOneEditorInObject`, `applyEditorClockHorizontalOffset`。

companion 引用：`TAG`, `ALL_IN_ONE_TEMPLATE_VIEW_CLASS`, `clockHorizontalEditorShifts`, `clockHorizontalEditorClockShifts`, `activeLockscreenClockEditor`, `lockscreenClockCombinedTouchHookInstalled`。

目标/签名/配置/Hook ID 交叉检查：

- `val wallpaperClass = classLoader.loadClass(`
- `.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)`
- `.setId("lockscreen-clock-horizontal-editor-wallpaper-touch")`
- `wallpaperClass.getDeclaredMethod(`
- `classLoader.loadClass("com.miui.keyguard.editor.edit.wallpaper.ObserverClickListener"),`
- `.setId("lockscreen-clock-horizontal-editor-listener")`
- `val editorClass = classLoader.loadClass(ALL_IN_ONE_TEMPLATE_VIEW_CLASS)`
- `val touchMethod = editorClass.getDeclaredMethod("onObservedTouchEvent", MotionEvent::class.java)`
- `.setId("lockscreen-clock-horizontal-editor-touch")`
- `editorClass.declaredMethods.filter { it.name == "onMiuiClockViewCreated" }.forEachIndexed { index, method ->`
- `.setId("lockscreen-clock-horizontal-editor-create:$index")`
- `if (view != null && preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)) {`
- `editorClass.methods.firstOrNull { it.name == "generateEditFrames" && it.parameterCount == 0 }`
- `.setId("lockscreen-clock-horizontal-editor-generate-frames")`
- `if (view != null && preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)) {`
- `hook(editorClass.getDeclaredMethod("getFramesComplete"))`
- `.setId("lockscreen-clock-horizontal-editor-frames")`
- `if (view != null && preferences.getBoolean(KEY_LOCKSCREEN_CLOCK_HORIZONTAL_MOVE, false)) {`


### `handleLockscreenClockHorizontalTouch` — 原 6081 行；原模块保留

原文件引用位置：5988, 6022。

成员依赖：`applyEditorClockHorizontalPreview`, `clearEditorClockHorizontalPreview`, `applyEditorClockHorizontalOffset`。

companion 引用：`TAG`, `clockHorizontalDrags`。


### `applyEditorClockHorizontalPreview` — 原 6135 行；原模块保留

原文件引用位置：6114, 6153。

成员依赖：`forEachEditorClock`, `findField`。

companion 引用：`clockHorizontalEditorClockShifts`。


### `clearEditorClockHorizontalPreview` — 原 6152 行；原模块保留

原文件引用位置：6093, 6120。

成员依赖：`applyEditorClockHorizontalPreview`。

companion 引用：无。


### `findAllInOneEditorForWallpaper` — 原 6156 行；原模块保留

原文件引用位置：5977, 5984。

成员依赖：`findAllInOneEditorInObject`。

companion 引用：无。


### `findAllInOneEditorInObject` — 原 6165 行；原模块保留

原文件引用位置：6001, 6162。

成员依赖：无其他原模块成员引用。

companion 引用：`ALL_IN_ONE_TEMPLATE_VIEW_CLASS`。


### `applyEditorClockHorizontalOffset` — 原 6187 行；原模块保留

原文件引用位置：6037, 6056, 6071, 6123。

成员依赖：`forEachEditorClock`, `findField`, `moveEditorFrame`。

companion 引用：`clockHorizontalEditorShifts`, `clockHorizontalEditorClockShifts`。


### `forEachEditorClock` — 原 6212 行；原模块保留

原文件引用位置：6147, 6204。

成员依赖：`isAllInOneClock`, `findField`。

companion 引用：无。


### `isAllInOneClock` — 原 6227 行；原模块保留

原文件引用位置：6223。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `findField` — 原 6236 行；原模块保留

原文件引用位置：6138, 6193, 6219, 6221。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `moveEditorFrame` — 原 6247 行；原模块保留

原文件引用位置：6193, 6195。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `widenedClockRect` — 原 6257 行；原模块保留

原文件引用位置：5871, 5900。

成员依赖：无其他原模块成员引用。

companion 引用：`BIG_CLOCK_WIDTH_ENVELOPE_MULTIPLIER`。


### `installAodClockMaterialLimitHook` — 原 6268 行；原模块保留

原文件引用位置：1769。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val companionClass = classLoader.loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel\$Companion")`
- `it.name == "computeSupportedClockEffect" && it.parameterCount == 2 &&`
- `it.parameterTypes[1] == Int::class.javaPrimitiveType`
- `.setId("aod-editor-clock-material-limit")`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&`
- `it.name == "shouldDisableGlassEffect" && it.parameterCount == 2`
- `.setId("aod-editor-glass-selection-limit")`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&`
- `val filterClass = classLoader.loadClass("com.miui.keyguard.editor.data.preset.FontFilterKt")`
- `val filterMethod = filterClass.getDeclaredMethod("getFILTER_SUPPORT_INFO").apply { isAccessible = true }`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {`
- `.setId("aod-editor-clock-filter-support")`
- `val clockViewClass = classLoader.loadClass("com.miui.keyguard.editor.edit.base.BaseClockView")`
- `it.name == "effectDisable" && it.parameterCount == 1`
- `.setId("aod-editor-clock-effect-selection")`
- `val effect = runCatching { filter?.javaClass?.getMethod("getFilterId")?.invoke(filter) as? Int }.getOrNull()`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&`
- `val apiClass = classLoader.loadClass("com.miui.keyguard.editor.data.template.TemplateApiImpl")`
- `it.name == "processDepthVideo" && it.parameterCount == 3`
- `.setId("aod-depth-video-clock-material-limit")`
- `config?.javaClass?.getMethod("getLockscreenInfo")?.invoke(config)`
- `?.let { it.javaClass.getMethod("getClockInfo").invoke(it) }`
- `val effect = runCatching { clock?.javaClass?.getMethod("getClockEffect")?.invoke(clock) as? Int }.getOrNull()`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&`
- `clock?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)?.invoke(clock, effect)`


### `installClockMaterialCapabilityHook` — 原 6378 行；原模块保留

原文件引用位置：1751, 1768, 1814。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val config = classLoader.loadClass("com.miui.clock.utils.DeviceConfig")`
- `it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType &&`
- `it.name in setOf("supportGlassEffect", "supportDiffEffect", "supportDiffEffectAndGradientEffect") + aliases`
- `.setId("clock-material-capability-${method.name}")`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) true else chain.proceed()`


### `installThemeManagerEditorClockMaterialHook` — 原 6402 行；原模块保留

原文件引用位置：1819。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val companion = classLoader.loadClass("com.miui.keyguard.editor.viewmodel.EditFragmentViewModel\$Companion")`
- `val configClass = classLoader.loadClass("com.miui.keyguard.editor.data.bean.CommonConfig")`
- `val method = companion.getDeclaredMethod("k", configClass, Int::class.javaPrimitiveType)`
- `.setId("theme-manager-editor-clock-material-limit")`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&`
- `companion.getDeclaredMethod("f7l8", Int::class.javaPrimitiveType, configClass).also { disable ->`
- `.setId("theme-manager-editor-glass-selection-limit")`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&`
- `val filters = classLoader.loadClass("com.miui.keyguard.editor.data.preset.FontFilterKt")`
- `if (preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {`


### `restoreRequestedClockWidth` — 原 6445 行；原模块保留

原文件引用位置：5848。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`。


### `installThemeManagerClockMaterialLimitHook` — 原 6486 行；原模块保留

原文件引用位置：1813。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `THEME_MANAGER_TEMPLATE_API_CLASS`, `THEME_MANAGER_TEMPLATE_API_METHOD`, `CLOCK_EFFECT_OVERLAY`, `CLOCK_EFFECT_GLASS`, `KEY_REMOVE_CLOCK_MATERIAL_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val templateApi = classLoader.loadClass(THEME_MANAGER_TEMPLATE_API_CLASS)`
- `it.name == THEME_MANAGER_TEMPLATE_API_METHOD && it.parameterCount == 3`
- `.setId("theme-manager-clock-material-limit")`
- `if (!preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {`
- `templateConfig?.javaClass?.getMethod("getLockscreenInfo")?.invoke(templateConfig)`
- `?.let { it.javaClass.getMethod("getClockInfo").invoke(it) }`
- `clockInfo?.javaClass?.getMethod("getClockEffect")?.invoke(clockInfo) as? Int`
- `clockInfo?.javaClass?.getMethod(`


### `installFocusNotificationMaterialEnforcementHooks` — 原 6585 行；原模块保留

原文件引用位置：5494。

成员依赖：`hookDeoptimized`, `applyNormalMediaNotificationEffect`, `applyNormalNotificationRowEffect`, `isFocusNotificationRow`, `isMediaNotificationView`, `readInstanceField`。

companion 引用：`TAG`, `EXPANDABLE_NOTIFICATION_ROW_CLASS`, `EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS`, `FOCUS_NOTIFICATION_EFFECT_CLASSES`, `MEDIA_NOTIFICATION_EFFECT_CLASSES`, `KEY_UNIFY_NOTIFICATION_MATERIAL`, `mediaMaterialApplying`。

目标/签名/配置/Hook ID 交叉检查：

- `val effectClass = classLoader.loadClass(className)`
- `method.name == "apply" && method.parameterCount == 2 &&`
- `method.parameterTypes[1] == Context::class.java`
- `it.parameterTypes[0].name.contains(EXPANDABLE_NOTIFICATION_ROW_CLASS)`
- `.setId("focus-notification-normal-material:${effectClass.simpleName}")`
- `if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&`
- `if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&`
- `val injectorClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_INJECTOR_CLASS)`
- `.filter { it.name == "updateFullAodAnimState" }`
- `.setId("focus-notification-normal-material:full-aod-$index")`
- `if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&`
- `val effectClass = classLoader.loadClass(className)`
- `it.name == "apply" && it.parameterCount == 2 &&`
- `Context::class.java.isAssignableFrom(it.parameterTypes[1])`
- `.setId("media-notification-normal-material:${effectClass.simpleName}")`
- `preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)`
- `if (preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false) &&`


### `applyNormalMediaNotificationEffect` — 原 6718 行；原模块保留

原文件引用位置：6688, 6698。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `MEDIA_NOTIFICATION_GLASS_EFFECT_CLASS`, `mediaMaterialApplying`, `focusMaterialEnforcementHits`。


### `applyNormalNotificationRowEffect` — 原 6748 行；原模块保留

原文件引用位置：6614, 6622, 6653。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `NOTIFICATION_ROW_GLASS_EFFECT_CLASS`, `focusMaterialEnforcementHits`。


### `isFocusNotificationRow` — 原 6774 行；原模块保留

原文件引用位置：6612, 6620, 6651。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installFocusNotificationBackgroundHook` — 原 6792 行；原模块保留

原文件引用位置：5495。

成员依赖：`hookDeoptimized`, `notificationTypeFor`, `isNotificationRowBackground`。

companion 引用：`TAG`, `SYSTEM_UI`, `NOTIFICATION_BACKGROUND_VIEW_CLASS`, `KEY_UNIFY_NOTIFICATION_MATERIAL`。

目标/签名/配置/Hook ID 交叉检查：

- `val backgroundClass = classLoader.loadClass(NOTIFICATION_BACKGROUND_VIEW_CLASS)`
- `method.name to method.parameterTypes.map { it.name }`
- `method.name == "setCustomBackground" && method.parameterCount == 1 &&`
- `(method.parameterTypes[0] == Drawable::class.java \|\|`
- `method.parameterTypes[0] == Int::class.javaPrimitiveType)`
- `.setId("focus-notification-transparent-background-$index")`
- `val unify = preferences.getBoolean(KEY_UNIFY_NOTIFICATION_MATERIAL, false)`
- `} else if (method.parameterTypes[0] == Drawable::class.java) {`


### `notificationTuningFor` — 原 6856 行；原模块保留

原文件引用位置：6891, 7133。

成员依赖：`notificationTypeFor`, `notificationOnKeyguard`, `isNotificationRowBackground`, `glassTuning`, `isHeadsUpNotificationSurface`。

companion 引用：`KEY_NOTIFICATION_CONTEXT_UNIFIED`, `KEY_UNIFY_NOTIFICATION_MATERIAL`, `KEY_NOTIFICATION_TYPE_UNIFIED`, `KEY_NOTIFICATION_CENTER_NORMAL`, `KEY_LOCKSCREEN_NORMAL`, `KEY_NOTIFICATION_CENTER_MEDIA`, `KEY_LOCKSCREEN_MEDIA`, `KEY_NOTIFICATION_CENTER_FOCUS`, `KEY_LOCKSCREEN_FOCUS`。


### `materialTuningFor` — 原 6887 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`notificationTuningFor`, `controlCenterTuningFor`, `isNotificationRowBackground`, `isMediaNotificationView`, `isControlCenterCall`, `isHeadsUpNotificationSurface`。

companion 引用：`KEY_UNIFY_NOTIFICATION_MATERIAL`。


### `notificationTypeFor` — 原 6896 行；原模块保留

原文件引用位置：6819, 6859, 6966, 7013。

成员依赖：`readInstanceField`。

companion 引用：无。


### `controlCenterTuningFor` — 原 6933 行；原模块保留

原文件引用位置：6892。

成员依赖：`hasAncestorClass`, `stackContainsClass`, `glassTuning`, `isControlCenterSliderPart`。

companion 引用：`TOP_BUTTONS_CLASS`, `SLIDER_VIEW_HOLDER_CLASS`, `KEY_CONTROL_CENTER_BUTTON`, `KEY_CONTROL_CENTER_SLIDER`。


### `logControlCenterMaterialHit` — 原 6946 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`stackContainsClass`。

companion 引用：`TAG`, `TOP_BUTTONS_CLASS`, `SLIDER_VIEW_HOLDER_CLASS`, `controlCenterMaterialHits`。


### `shouldUseNormalNotificationMaterial` — 原 6959 行；原模块保留

原文件引用位置：5532。

成员依赖：`notificationTypeFor`, `isNotificationRowBackground`, `isMediaNotificationView`, `isHeadsUpNotificationSurface`。

companion 引用：`KEY_UNIFY_NOTIFICATION_MATERIAL`。


### `normalNotificationGlassParams` — 原 6969 行；原模块保留

原文件引用位置：5519, 5532, 11394。

成员依赖：无其他原模块成员引用。

companion 引用：`SYSTEM_UI`, `MIN_GLASS_PARAMS_SIZE`, `NORMAL_NOTIFICATION_GLASS_PARAMS_ARRAY`, `normalNotificationGlassParamsCache`。


### `normalNotificationBlendColors` — 原 7004 行；原模块保留

原文件引用位置：7032。

成员依赖：`notificationTypeFor`, `getColorByName`, `getIntegerByName`, `isNotificationMaterialCall`, `isNotificationRowBackground`, `isMediaNotificationView`, `isHeadsUpNotificationSurface`。

companion 引用：`KEY_UNIFY_NOTIFICATION_MATERIAL`。


### `normalNotificationBlendPoints` — 原 7029 行；原模块保留

原文件引用位置：5610。

成员依赖：`normalNotificationBlendColors`。

companion 引用：无。


### `getColorByName` — 原 7040 行；原模块保留

原文件引用位置：7021, 7023。

成员依赖：无其他原模块成员引用。

companion 引用：`SYSTEM_UI`。


### `getIntegerByName` — 原 7043 行；原模块保留

原文件引用位置：7022, 7024。

成员依赖：无其他原模块成员引用。

companion 引用：`SYSTEM_UI`。


### `isNotificationMaterialCall` — 原 7046 行；原模块保留

原文件引用位置：7008。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `notificationOnKeyguard` — 原 7054 行；原模块保留

原文件引用位置：6858, 7126。

成员依赖：`isExpandedNotificationShade`, `isMediaNotificationView`, `readInstanceField`, `isLockscreenMediaView`。

companion 引用：`lockscreenMediaKeyguardShowing`。


### `isExpandedNotificationShade` — 原 7101 行；原模块保留

原文件引用位置：7097, 7116。

成员依赖：`readInstanceField`。

companion 引用：无。


### `mediaHeaderOnActiveKeyguard` — 原 7115 行；原模块保留

原文件引用位置：9222, 9369, 9405, 10160, 10356, 10439, 10485。

成员依赖：`notificationOnKeyguard`, `isExpandedNotificationShade`。

companion 引用：`activeLockscreenClockContainer`。


### `hasCustomizedNotificationTuning` — 原 7132 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`notificationTuningFor`。

companion 引用：无。


### `isNotificationRowBackground` — 原 7135 行；原模块保留

原文件引用位置：5454, 5465, 5542, 6818, 6857, 6889, 6966, 7007, 7210。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isMediaNotificationView` — 原 7143 行；原模块保留

原文件引用位置：5455, 6687, 6696, 6890, 6965, 7007, 7097。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `hasAncestorClass` — 原 7158 行；原模块保留

原文件引用位置：6942。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `readInstanceField` — 原 7163 行；原模块保留

原文件引用位置：2713, 2782, 3223, 3224, 3225, 3226, 3228, 3238, 3239, 3240, 3241, 6649, 6905, 6910, 7067, 7075, 7085, 7105, 7106, 7107, 9211, 9249, 9251, 9267, 9269, 9271, 9464, 9512, 9513, 9551, 9661, 9662, 9737, 9753, 9754, 9766, 9801, 9802, 10272, 10273, 10274, 10275, 10336, 10438, 10465, 10466, 10570, 10603, 10627, 12398, 12421。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `shadePanelTuning` — 原 7178 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`stackContainsClass`, `glassTuning`。

companion 引用：`KEY_NOTIFICATION_CENTER_BACKGROUND`, `KEY_CONTROL_CENTER_BACKGROUND`。


### `isControlCenterCall` — 原 7185 行；原模块保留

原文件引用位置：5455, 6892, 7229。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isNotificationCenterCall` — 原 7189 行；原模块保留

原文件引用位置：5455, 7229。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isShadeBlurProviderCall` — 原 7199 行；原模块保留

原文件引用位置：7229。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isShadeBackgroundView` — 原 7209 行；原模块保留

原文件引用位置：5454, 5460。

成员依赖：`isNotificationRowBackground`, `idName`。

companion 引用：`SLIDER_PART_IDS`, `SHADE_BACKGROUND_IDS`。


### `isShadePanelBackgroundCall` — 原 7228 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`isControlCenterCall`, `isNotificationCenterCall`, `isShadeBlurProviderCall`。

companion 引用：无。


### `stackContainsClass` — 原 7231 行；原模块保留

原文件引用位置：5449, 5459, 5460, 5461, 5461, 5663, 5683, 5684, 6937, 6938, 6940, 6948, 6949, 6950, 6951, 7179, 7180, 7180, 11373, 11428, 11903, 12077。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `stackContains` — 原 7234 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `glassTuning` — 原 7239 行；原模块保留

原文件引用位置：6865, 6867, 6870, 6872, 6874, 6876, 6877, 6878, 6939, 6942, 7179, 7181。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `applyGlassTuning` — 原 7249 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：`GLASS_TINT_RED_INDEX`, `GLASS_TINT_GREEN_INDEX`, `GLASS_TINT_BLUE_INDEX`, `GLASS_ALPHA_INDEX`。


### `applyBlendColorTuning` — 原 7260 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `scaleBlur` — 原 7269 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：`MAX_GLASS_BLUR_RADIUS`。


### `installDepthEffectHook` — 原 7272 行；原模块保留

原文件引用位置：1761。

成员依赖：`installDepthAvoidanceBypass`。

companion 引用：`TAG`, `DEPTH_EVALUATOR_CLASS`, `DEPTH_THRESHOLD_CLASS`, `IMAGE_THRESHOLD_FIELD`, `THRESHOLD_RATE_FIELD`, `DEFAULT_DEPTH_IMAGE_THRESHOLD`, `UNLIMITED_DEPTH_IMAGE_THRESHOLD`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val thresholdClass = classLoader.loadClass(DEPTH_THRESHOLD_CLASS)`
- `val evaluatorClass = classLoader.loadClass(DEPTH_EVALUATOR_CLASS)`
- `.setId("depth-image-threshold")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&`
- `.setId("depth-image-threshold-verification")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {`


### `installDepthAvoidanceBypass` — 原 7320 行；原模块保留

原文件引用位置：7313。

成员依赖：`isUserHierarchyEnabled`。

companion 引用：`TAG`, `HIERARCHY_AVOID_CONTROLLER_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val controllerClass = classLoader.loadClass(HIERARCHY_AVOID_CONTROLLER_CLASS)`
- `hook(controllerClass.getMethod("isHierarchyEnable"))`
- `.setId("depth-time-overlap-result")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&`
- `controllerClass.getMethod(`
- `.setId("depth-time-overlap-state")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&`


### `isUserHierarchyEnabled` — 原 7353 行；原模块保留

原文件引用位置：7328, 7343。

成员依赖：无其他原模块成员引用。

companion 引用：`USER_OPEN_HIERARCHY_FIELD`。


### `installLockscreenShortcutGlassHook` — 原 7359 行；原模块保留

原文件引用位置：1708。

成员依赖：`hookDeoptimized`, `scheduleLockscreenMiniPlayerInstallation`, `scheduleLockscreenWidgetInstallation`, `installShortcutGlassBackgrounds`, `applyLockscreenShortcutGeometry`。

companion 引用：`TAG`, `MIUI_SHORTCUT_CONTROLLER_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val controllerClass = classLoader.loadClass(MIUI_SHORTCUT_CONTROLLER_CLASS)`
- `.filter { it.name == "addShortcutViews" && it.parameterCount == 1 }`
- `.setId("lockscreen-shortcut-glass-$index")`


### `installLockscreenWidgetSceneVisibilityHooks` — 原 7414 行；原模块保留

原文件引用位置：1712。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`, `KEYGUARD_EDITOR_HELPER_CLASS`, `MIUI_CHARGE_ANIMATION_VIEW_CLASS`, `CONTROL_CENTER_EXPAND_LISTENER_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val mediatorInjector = classLoader.loadClass(`
- `it.name == "keyguardGoingAway" && it.parameterCount == 0`
- `.setId("lockscreen-widget:keyguard-going-away-early-$index")`
- `val updateMonitor = classLoader.loadClass("com.android.keyguard.KeyguardUpdateMonitor")`
- `it.name == "setKeyguardGoingAway" && it.parameterCount == 1 &&`
- `it.parameterTypes[0] == Boolean::class.javaPrimitiveType`
- `.setId("lockscreen-widget:keyguard-going-away-state-$index")`
- `val stateController = classLoader.loadClass(`
- `it.name == "notifyKeyguardState" && it.parameterCount == 2 &&`
- `it.parameterTypes.all { type -> type == Boolean::class.javaPrimitiveType }`
- `.setId("lockscreen-widget:keyguard-showing-state-$index")`
- `val statusBarStateClass = classLoader.loadClass(`
- `it.name == "setIsDozing" && it.parameterCount == 1 &&`
- `it.parameterTypes[0] == Boolean::class.javaPrimitiveType`
- `.setId("lockscreen-widget:aod-scene-$index")`
- `val editorClass = classLoader.loadClass(KEYGUARD_EDITOR_HELPER_CLASS)`
- `it.name == "setEditorState" && it.parameterCount == 1`
- `.setId("lockscreen-widget:editor-scene-$index")`
- `val chargeClass = classLoader.loadClass(MIUI_CHARGE_ANIMATION_VIEW_CLASS)`
- `it.name == "addChargeView" && it.parameterCount == 0`
- `val hideMethods = chargeClass.declaredMethods.filter { it.name == "removeChargeView" }`
- `.setId("lockscreen-widget:charging-scene-show-$index")`
- `.setId("lockscreen-widget:charging-scene-hide-$index")`
- `val listenerClass = classLoader.loadClass(CONTROL_CENTER_EXPAND_LISTENER_CLASS)`
- `it.name == "onExpandStateChanged" && it.parameterCount == 1`
- `.setId("lockscreen-widget:control-center-scene-$index")`


### `installLockscreenPinCircleBackgroundHook` — 原 7604 行；原模块保留

原文件引用位置：1719。

成员依赖：`installLockscreenPinCircleBackgrounds`。

companion 引用：`TAG`, `KEYGUARD_PIN_VIEW_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val pinViewClass = classLoader.loadClass(KEYGUARD_PIN_VIEW_CLASS)`
- `val onFinishInflate = pinViewClass.getDeclaredMethod("onFinishInflate")`
- `.setId("lockscreen-pin-circle-background")`
- `if (preferences.getBoolean(KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED, false)) {`


### `installLockscreenPinCircleBackgrounds` — 原 7630 行；原模块保留

原文件引用位置：7619。

成员依赖：`configureLockscreenPinLabels`, `applyLockscreenPinRowSpacing`, `idName`, `applyLegacyBackdropMaterial`, `applySystemGlassMaterial`。

companion 引用：`TAG`, `LOCKSCREEN_PIN_KEY_IDS`, `LOCKSCREEN_PIN_CIRCLE_TAG`, `LOCKSCREEN_PIN_CIRCLE_RIPPLE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`。

目标/签名/配置/Hook ID 交叉检查：

- `"${preferences.getFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, 0f)}dp",`


### `configureLockscreenPinLabels` — 原 7727 行；原模块保留

原文件引用位置：7698。

成员依赖：`idName`。

companion 引用：无。


### `applyLockscreenPinRowSpacing` — 原 7744 行；原模块保留

原文件引用位置：7718。

成员依赖：`idName`。

companion 引用：`LOCKSCREEN_PIN_ROW_IDS`。


### `installLockscreenMiniPlayer` — 原 7761 行；原模块保留

原文件引用位置：7830。

成员依赖：`resolveLockscreenShortcutViews`, `miniPlayerAppearance`, `applyMiniPlayerMaterial`, `commonShortcutParent`, `findShortcutContainers`, `idName`, `lockscreenMediaNotificationMode`。

companion 引用：`TAG`, `KEY_LOCKSCREEN_MINI_PLAYER_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED`, `lockscreenMiniPlayerControllers`。

目标/签名/配置/Hook ID 交叉检查：

- `if (!preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false)) {`
- `enabled = { preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) },`
- `preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false)`
- `preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_ENABLED, false)`


### `scheduleLockscreenMiniPlayerInstallation` — 原 7822 行；原模块保留

原文件引用位置：7388。

成员依赖：`installLockscreenMiniPlayer`。

companion 引用：`TAG`, `LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS`。


### `installLockscreenWidget` — 原 7843 行；原模块保留

原文件引用位置：7906。

成员依赖：`resolveLockscreenShortcutViews`, `commonShortcutParent`, `findShortcutContainers`, `applyLockscreenWidgetBatteryMaterial`, `applyLockscreenWidgetShortcutSurfaceMaterial`, `idName`。

companion 引用：`TAG`, `lockscreenWidgetControllers`。

目标/签名/配置/Hook ID 交叉检查：

- `if (!preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_ENABLED, false)) {`


### `scheduleLockscreenWidgetInstallation` — 原 7898 行；原模块保留

原文件引用位置：7394。

成员依赖：`installLockscreenWidget`。

companion 引用：`TAG`, `LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS`。


### `installLockscreenMusicLockscreenHook` — 原 7917 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`hookDeoptimized`, `installLockscreenMusicLockscreen`。

companion 引用：`TAG`, `KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS`, `STATUS_BAR_STATE_KEYGUARD`, `lockscreenMusicLockscreenControllers`。

目标/签名/配置/Hook ID 交叉检查：

- `val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)`
- `it.name == "onKeyguardViewBind" && it.parameterCount == 1`
- `.setId("lockscreen-music-lockscreen-bind-$index")`
- `val updateVisibility = panelClass.getMethod("updateKeyguardElementsVisibility")`
- `.setId("lockscreen-music-lockscreen-visibility")`


### `installLockscreenMusicLockscreen` — 原 7977 行；原模块保留

原文件引用位置：7940。

成员依赖：无其他原模块成员引用。

companion 引用：`KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`, `lockscreenMusicLockscreenControllers`。

目标/签名/配置/Hook ID 交叉检查：

- `if (!preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false)) {`
- `preferences.getBoolean(KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED, false)`


### `resolveLockscreenShortcutViews` — 原 8004 行；原模块保留

原文件引用位置：7777, 7855。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `miniPlayerAppearance` — 原 8018 行；原模块保留

原文件引用位置：7802。

成员依赖：`shortcutBackgroundMode`。

companion 引用：`SHORTCUT_PURE_COLOR`, `MINI_PLAYER_PURE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_SOFT_GLASS_COLOR`, `MAX_SHORTCUT_GLASS_LUMINANCE`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_SOFT_GLASS_OPACITY`, `DEFAULT_SOFT_GLASS_LUMINANCE`, `SHORTCUT_BACKGROUND_NONE`, `KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE`, `KEY_LOCKSCREEN_MINI_PLAYER_WIDTH`, `KEY_LOCKSCREEN_MINI_PLAYER_ARTWORK_CORNER_RADIUS`, `KEY_MINI_PLAYER_PURE_COLOR`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_MINI_PLAYER_SOFT_GLASS_COLOR`, `KEY_MINI_PLAYER_SOFT_GLASS_OPACITY`, `KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS`, `KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE`, `KEY_SHORTCUT_PURE_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY`, `KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_SHORTCUT_SOFT_GLASS_COLOR`, `KEY_SHORTCUT_SOFT_GLASS_OPACITY`, `KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_LUMINANCE`。


### `applyMiniPlayerMaterial` — 原 8107 行；原模块保留

原文件引用位置：7805。

成员依赖：`applyLegacyBackdropMaterial`, `applySystemGlassMaterial`。

companion 引用：无。


### `commonShortcutParent` — 原 8138 行；原模块保留

原文件引用位置：7780, 7858。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installShortcutGlassBackgrounds` — 原 8153 行；原模块保留

原文件引用位置：7379。

成员依赖：`findShortcutContainers`, `applyShortcutIconColorMode`, `applyLegacyBackdropMaterial`, `applySystemGlassMaterial`, `shortcutBackgroundMode`, `shortcutIconColorMode`。

companion 引用：`TAG`, `DEFAULT_SHORTCUT_GLASS_RADIUS`, `MIN_SHORTCUT_GLASS_RADIUS`, `MAX_SHORTCUT_GLASS_RADIUS`, `SHORTCUT_PURE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_SOFT_GLASS_COLOR`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_OPACITY`, `DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`, `SHORTCUT_BACKGROUND_NONE`, `SHORTCUT_BACKGROUND_PURE_COLOR`, `SHORTCUT_BACKGROUND_ADVANCED_MATERIAL`, `SHORTCUT_BACKGROUND_SOFT_GLASS`, `SHORTCUT_GLASS_TAG`, `KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS`, `KEY_SHORTCUT_PURE_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY`, `KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_SHORTCUT_SOFT_GLASS_COLOR`, `KEY_SHORTCUT_SOFT_GLASS_OPACITY`, `KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_LUMINANCE`。

目标/签名/配置/Hook ID 交叉检查：

- `val radius = preferences.getFloat(KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS, DEFAULT_SHORTCUT_GLASS_RADIUS)`
- `val backgroundRadiusEnabled = preferences.getBoolean(`
- `val backgroundRadius = preferences.getFloat(`
- `setBackgroundColor(preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR))`
- `opacity = preferences.getInt(`
- `blurRadius = preferences.getInt(`
- `color = preferences.getInt(`
- `showHighlight = preferences.getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),`
- `opacity = preferences.getInt(`
- `blurRadius = preferences.getInt(`
- `color = preferences.getInt(`
- `blurRadius = preferences.getInt(`
- `luminance = preferences.getFloat(`


### `findShortcutContainers` — 原 8283 行；原模块保留

原文件引用位置：7767, 7849, 8170, 8299。

成员依赖：`idName`。

companion 引用：`LOCKSCREEN_SHORTCUT_CONTAINER_IDS`。


### `applyLockscreenShortcutGeometry` — 原 8298 行；原模块保留

原文件引用位置：7384。

成员依赖：`findShortcutContainers`。

companion 引用：`SHORTCUT_GLASS_TAG`, `KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_SPACING`, `KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE`。


### `applyShortcutIconColorMode` — 原 8335 行；原模块保留

原文件引用位置：8274。

成员依赖：无其他原模块成员引用。

companion 引用：`SHORTCUT_ICON_LIGHT_COLOR`, `SHORTCUT_ICON_DARK_COLOR`, `SHORTCUT_ICON_COLOR_LIGHT`, `SHORTCUT_ICON_COLOR_DARK`, `SHORTCUT_GLASS_TAG`。


### `applyLockscreenWidgetBatteryMaterial` — 原 8352 行；原模块保留

原文件引用位置：7881。

成员依赖：`applyLegacyBackdropMaterial`, `applySystemGlassMaterial`。

companion 引用：`SHORTCUT_PURE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_SOFT_GLASS_COLOR`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_OPACITY`, `DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`, `KEY_SHORTCUT_PURE_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY`, `KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_COLOR`, `KEY_SHORTCUT_SOFT_GLASS_OPACITY`, `KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_LUMINANCE`。


### `applyLockscreenWidgetShortcutSurfaceMaterial` — 原 8434 行；原模块保留

原文件引用位置：7888。

成员依赖：`applyLegacyBackdropMaterial`, `applySystemGlassMaterial`, `shortcutBackgroundMode`。

companion 引用：`SHORTCUT_PURE_COLOR`, `DEFAULT_ADVANCED_MATERIAL_COLOR`, `DEFAULT_SOFT_GLASS_COLOR`, `MAX_SHORTCUT_GLASS_LUMINANCE`, `DEFAULT_ADVANCED_MATERIAL_OPACITY`, `DEFAULT_ADVANCED_MATERIAL_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_OPACITY`, `DEFAULT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_BLUR_RADIUS`, `DEFAULT_SOFT_GLASS_LUMINANCE`, `SHORTCUT_BACKGROUND_NONE`, `SHORTCUT_BACKGROUND_PURE_COLOR`, `SHORTCUT_BACKGROUND_ADVANCED_MATERIAL`, `SHORTCUT_BACKGROUND_SOFT_GLASS`, `KEY_SHORTCUT_PURE_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR`, `KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY`, `KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS`, `KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT`, `KEY_SHORTCUT_SOFT_GLASS_COLOR`, `KEY_SHORTCUT_SOFT_GLASS_OPACITY`, `KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS`, `KEY_SHORTCUT_SOFT_GLASS_LUMINANCE`。


### `idName` — 原 8583 行；原模块保留

原文件引用位置：7637, 7729, 7747, 7769, 7770, 7851, 7852, 8286。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `applyLegacyBackdropMaterial` — 原 8592 行；原模块保留

原文件引用位置：3078, 3109, 7700, 8113, 8121, 8224, 8244, 8389, 8410, 8494, 8530, 11059, 11078, 11230, 11259。

成员依赖：无其他原模块成员引用。

companion 引用：`SHORTCUT_GLASS_BLUR_MODE`, `SHORTCUT_GLASS_BLEND_MODE`, `MAX_SHORTCUT_OPACITY`, `MAX_SHORTCUT_BACKDROP_BLUR_RADIUS`, `SHORTCUT_BLOOM_STROKE_PARAMETERS`。


### `applySystemGlassMaterial` — 原 8629 行；原模块保留

原文件引用位置：3125, 7707, 8128, 8260, 8419, 8558, 11069, 11085, 11244, 11273。

成员依赖：无其他原模块成员引用。

companion 引用：`MI_GLASS_COMPAT_CLASS`, `SHORTCUT_GLASS_MATERIAL_TYPE`, `MAX_SHORTCUT_GLASS_BLUR_RADIUS`, `MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS`, `MAX_SHORTCUT_GLASS_LUMINANCE`, `GLASS_LUMINANCE_AMOUNT_INDEX`, `SHORTCUT_GLASS_PARAMETERS`, `controlCenterButtonsLock`, `systemUiClassLoader`, `controlCenterControllerClassLoader`。


### `shortcutBackgroundMode` — 原 8679 行；原模块保留

原文件引用位置：8062, 8158, 8449。

成员依赖：无其他原模块成员引用。

companion 引用：`SHORTCUT_BACKGROUND_NONE`, `SHORTCUT_BACKGROUND_SOFT_GLASS`, `KEY_LOCKSCREEN_SHORTCUT_GLASS_ENABLED`, `KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE`。


### `shortcutIconColorMode` — 原 8688 行；原模块保留

原文件引用位置：8274。

成员依赖：无其他原模块成员引用。

companion 引用：`SHORTCUT_ICON_COLOR_AUTO`, `SHORTCUT_ICON_COLOR_DARK`, `KEY_SHORTCUT_ICON_COLOR_MODE`。


### `installLockscreenClockDateFollowHook` — 原 8699 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`hookDeoptimized`, `syncLockscreenClockDate`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val notificationTopChangeType = classLoader.loadClass(`
- `val animationClass = classLoader.loadClass(className)`
- `method.name == "notifStateChange" &&`
- `method.parameterTypes.contentEquals(`
- `.setId("lockscreen-clock-date-follow:${className.substringAfterLast('.')}")`


### `syncLockscreenClockDate` — 原 8744 行；原模块保留

原文件引用位置：8729, 8731。

成员依赖：`scheduleLockscreenDateFollow`, `syncLockscreenDateViews`, `getClockPart`, `readInheritedField`, `isViewDescendantOf`。

companion 引用：无。


### `scheduleLockscreenDateFollow` — 原 8782 行；原模块保留

原文件引用位置：8779。

成员依赖：`syncLockscreenDateViews`。

companion 引用：`lockscreenDateFollowGenerations`, `LOCKSCREEN_DATE_FOLLOW_DURATION_MS`。


### `syncLockscreenDateViews` — 原 8806 行；原模块保留

原文件引用位置：8778, 8798。

成员依赖：`findLockscreenClockGlyphTop`。

companion 引用：`lockscreenDateAppliedOffsets`, `lockscreenDateNativeOffsets`, `LOCKSCREEN_DATE_TO_GLYPH_GAP_DP`。


### `findLockscreenClockGlyphTop` — 原 8839 行；原模块保留

原文件引用位置：8811。

成员依赖：`readInheritedField`。

companion 引用：无。


### `getClockPart` — 原 8854 行；原模块保留

原文件引用位置：8754, 8755, 8766。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `readInheritedField` — 原 8862 行；原模块保留

原文件引用位置：8749, 8750, 8757, 8763, 8845, 9079。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isViewDescendantOf` — 原 8874 行；原模块保留

原文件引用位置：8773, 8774。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installLockscreenNotificationHook` — 原 8883 行；原模块保留

原文件引用位置：1644。

成员依赖：`findFodNotificationPositionFlows`, `installFodEnrollmentFlowOverride`, `createLockscreenNotificationPositionCollector`, `isNotificationFodPositionLimitRemoved`。

companion 引用：`TAG`, `FOD_SHELF_SPACE_FLOW_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val legacyFlowClass = classLoader.loadClass(FOD_SHELF_SPACE_FLOW_CLASS)`
- `hook(legacyFlowClass.getDeclaredMethod("invokeSuspend", Any::class.java))`
- `.setId("lockscreen-notification-ignore-fod")`
- `method.name == "collect" && method.parameterCount == 2`
- `.setId("lockscreen-notification-fod-position-$index")`
- `val collectorType = collect.parameterTypes.firstOrNull()`


### `findFodNotificationPositionFlows` — 原 8951 行；原模块保留

原文件引用位置：8897。

成员依赖：无其他原模块成员引用。

companion 引用：`FOD_NOTIFICATION_POSITION_FLOW_ORDINAL_RANGE`。


### `installFodEnrollmentFlowOverride` — 原 8977 行；原模块保留

原文件引用位置：8911。

成员依赖：`createFodEnrollmentFlowOverride`。

companion 引用：`fodEnrollmentFlowOverrides`。

目标/签名/配置/Hook ID 交叉检查：

- `val flowClass = classLoader.loadClass("kotlinx.coroutines.flow.Flow")`


### `createFodEnrollmentFlowOverride` — 原 9001 行；原模块保留

原文件引用位置：8991。

成员依赖：`isNotificationFodPositionLimitRemoved`。

companion 引用：无。


### `createLockscreenNotificationPositionCollector` — 原 9035 行；原模块保留

原文件引用位置：8923。

成员依赖：`adjustLockscreenNotificationPosition`。

companion 引用：无。


### `adjustLockscreenNotificationPosition` — 原 9058 行；原模块保留

原文件引用位置：9048。

成员依赖：`readContext`, `readInheritedField`。

companion 引用：`systemUiApplicationContext`。


### `installLockscreenMediaNotificationHook` — 原 9094 行；原模块保留

原文件引用位置：1651。

成员依赖：`installLockscreenMediaRowVisibilityHook`, `installLockscreenMediaControllerVisibilityHook`, `installLockscreenMediaManagerBridgeHooks`, `installLockscreenMediaHeaderHook`, `installLockscreenLyricButtonHook`, `installLockscreenLyricButtonStatusBarStateHook`, `installLockscreenCustomizationMenuHook`, `installLockscreenMediaVisibilityProviderHooks`, `installLockscreenMediaPipelineHook`, `installTinyLockscreenMediaHook`, `installLockscreenMediaEffectRefreshHook`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：




### `installLockscreenMediaRowVisibilityHook` — 原 9129 行；原模块保留

原文件引用位置：9113。

成员依赖：`shouldHideLockscreenMedia`, `isMediaRow`。

companion 引用：`TAG`, `EXPANDABLE_NOTIFICATION_ROW_CLASS`, `lockscreenRows`, `lockscreenHiddenRows`, `lockscreenMediaRows`。

目标/签名/配置/Hook ID 交叉检查：

- `val rowClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_CLASS)`
- `val getEntry = rowClass.getMethod("getEntry")`
- `val setOnKeyguard = rowClass.getMethod("setOnKeyguard", Boolean::class.javaPrimitiveType)`
- `it.name == "setVisibility" && it.parameterTypes.contentEquals(`
- `.setId("lockscreen-hide-media-notification:row-keyguard")`
- `.setId("lockscreen-hide-media-notification:row-visibility")`


### `installLockscreenMediaControllerVisibilityHook` — 原 9194 行；原模块保留

原文件引用位置：9100。

成员依赖：`mediaHeaderOnActiveKeyguard`, `readInstanceField`, `shouldHideLockscreenMedia`, `installLockscreenMediaHeaderGuard`。

companion 引用：`TAG`, `lockscreenMediaHeaders`, `lockscreenMediaControllers`。

目标/签名/配置/Hook ID 交叉检查：

- `val controllerClass = classLoader.loadClass(`
- `val setVisibility = controllerClass.getMethod(`
- `.setId("lockscreen-hide-media-notification:media-controller-visibility")`


### `installLockscreenMediaManagerBridgeHooks` — 原 9235 行；原模块保留

原文件引用位置：9099。

成员依赖：`hookDeoptimized`, `readInstanceField`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val managerClass = classLoader.loadClass("com.android.systemui.media.NotificationMediaManager")`
- `(it.name == "findAndUpdateMediaNotifications" \|\| it.name == "dispatchUpdateMediaMetaData") &&`
- `it.parameterCount == 0`
- `.setId("lockscreen-media-manager-bridge-$index")`
- `val updateRunnable = classLoader.loadClass(`
- `hook(updateRunnable.getMethod("run"))`
- `.setId("lockscreen-media-manager-bridge:commit")`


### `installLockscreenMediaHeaderHook` — 原 9289 行；原模块保留

原文件引用位置：9101。

成员依赖：`hookDeoptimized`, `mediaHeaderOnActiveKeyguard`, `readInstanceField`, `installLockscreenIslandArtworkBridge`, `bindLockscreenLyricButton`, `refreshLockscreenLyricButtons`, `shouldHideLockscreenMedia`, `applyLockscreenMediaPresentation`, `updateLockscreenMediaHeaderVisibility`, `scheduleLockscreenMediaPresentation`, `installLockscreenMediaHeaderGuard`, `installLockscreenMediaArtworkClick`。

companion 引用：`TAG`, `MIUI_MEDIA_HEADER_VIEW_CLASS`, `lockscreenMediaKeyguardShowing`, `lockscreenMediaHeaders`。

目标/签名/配置/Hook ID 交叉检查：

- `val headerClass = classLoader.loadClass(`
- `.setId("lockscreen-media-presentation:header-construct-$index")`
- `val callbackClass = classLoader.loadClass(`
- `method.parameterTypes.any { it == Boolean::class.javaPrimitiveType }`
- `.setId("lockscreen-hide-media-notification:keyguard-callback-$index")`
- `val booleanIndex = method.parameterTypes.indexOfFirst {`
- `val viewVisibility = View::class.java.getMethod(`
- `.setId("lockscreen-hide-media-notification:media-header-visibility")`
- `method.name == "onMediaDataChanged" && method.parameterCount > 0`
- `.setId("lockscreen-hide-media-notification:media-header-data-$index")`
- `val mediaViewHolderClass = classLoader.loadClass(`
- `val setMediaViewHolder = headerClass.getMethod(`
- `.setId("lockscreen-media-presentation:media-view-holder")`
- `val controllerClass = classLoader.loadClass(`
- `it.name == "updateLayout\$1" && it.parameterCount == 0`
- `.setId("lockscreen-media-presentation:update-layout-$index")`


### `installLockscreenIslandArtworkBridge` — 原 9494 行；原模块保留

原文件引用位置：9481。

成员依赖：`readInstanceField`, `scheduleLockscreenMediaPresentation`, `installLockscreenMediaHeaderGuard`, `findLockscreenMediaHeader`。

companion 引用：`TAG`, `lockscreenMediaHeaders`。

目标/签名/配置/Hook ID 交叉检查：

- `val controllerClass = classLoader.loadClass(`
- `val mediaDataClass = classLoader.loadClass(`
- `val bindMediaData = controllerClass.getMethod("bindMediaData", mediaDataClass)`
- `.setId("lockscreen-media-artwork:controller-bind")`


### `installLockscreenLyricButtonHook` — 原 9533 行；原模块保留

原文件引用位置：9102。

成员依赖：`readInstanceField`, `bindLockscreenLyricButtonForHolder`, `bindLockscreenLyricButtonForController`, `shouldOwnLockscreenLyricSlot`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val controllerClass = classLoader.loadClass(`
- `val mediaDataClass = classLoader.loadClass(`
- `val bindMediaData = controllerClass.getMethod("bindMediaData", mediaDataClass)`
- `.setId("lockscreen-lyrics-button:media-data")`
- `val holderClass = classLoader.loadClass(`
- `val attach = controllerClass.getMethod("attach", holderClass)`
- `.setId("lockscreen-lyrics-button:attach")`
- `val notificationControllerClass = classLoader.loadClass(`
- `val topMediaChanged = notificationControllerClass.getDeclaredMethod(`
- `.setId("lockscreen-lyrics-button:top-media-data")`
- `val mediaActionClass = classLoader.loadClass(`
- `val bindButtonCommon = controllerClass.getDeclaredMethod(`
- `.setId("lockscreen-lyrics-button:block-custom")`
- `val utilsClass = classLoader.loadClass(`
- `val setSemanticButton = utilsClass.getDeclaredMethod(`
- `.setId("lockscreen-lyrics-button:block-semantic")`


### `bindLockscreenLyricButton` — 原 9656 行；原模块保留

原文件引用位置：9396, 9400, 9434, 9438, 9443, 9742, 9744, 9745, 9755, 9758, 9759, 9795, 9818。

成员依赖：`dispatchHyperMusicCoverState`, `syncHyperMusicCoverLyrics`, `readInstanceField`, `applyEmptyLockscreenMediaButton`, `refreshLockscreenLyricButtons`, `shouldShowLockscreenLyricButton`, `lockscreenLyricsShowing`, `setLockscreenLyricsShowing`, `loadLockscreenLyricButtonDrawable`, `tr`。

companion 引用：`LOCKSCREEN_LYRIC_BUTTON_TAG`, `LOCKSCREEN_LYRIC_BUTTON_STATE_TAG`, `LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `lyricReturnPresentation`, `syncedHyperMusicLyricsEnabled`, `systemUiApplicationContext`, `lockscreenLyricButtons`, `lockscreenLyricButtonHeaders`。


### `bindLockscreenLyricButtonForHolder` — 原 9736 行；原模块保留

原文件引用位置：9550, 9570。

成员依赖：`readInstanceField`, `bindLockscreenLyricButton`, `findRegisteredLockscreenMediaHeader`, `findMiuiMediaHeaderAncestor`。

companion 引用：`lockscreenMediaHeaders`。


### `bindLockscreenLyricButtonForController` — 原 9748 行；原模块保留

原文件引用位置：9596。

成员依赖：`readInstanceField`, `bindLockscreenLyricButton`。

companion 引用：无。


### `findRegisteredLockscreenMediaHeader` — 原 9762 行；原模块保留

原文件引用位置：9739。

成员依赖：`readInstanceField`。

companion 引用：`lockscreenMediaHeaders`。


### `applyEmptyLockscreenMediaButton` — 原 9771 行；原模块保留

原文件引用位置：9667, 9672, 9806。

成员依赖：无其他原模块成员引用。

companion 引用：`LOCKSCREEN_LYRIC_BUTTON_TAG`, `LOCKSCREEN_LYRIC_BUTTON_STATE_TAG`, `LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG`。


### `refreshLockscreenLyricButtons` — 原 9785 行；原模块保留

原文件引用位置：1856, 1881, 9341, 9729, 9860。

成员依赖：`bindLockscreenLyricButton`, `findMiuiMediaHeaderAncestor`。

companion 引用：`lockscreenLyricButtons`, `lockscreenLyricButtonHeaders`。


### `maintainLockscreenLyricButton` — 原 9800 行；原模块保留

原文件引用位置：10484。

成员依赖：`readInstanceField`, `bindLockscreenLyricButton`, `applyEmptyLockscreenMediaButton`, `shouldShowLockscreenLyricButton`, `lockscreenLyricsShowing`。

companion 引用：`LOCKSCREEN_LYRIC_BUTTON_TAG`, `LOCKSCREEN_LYRIC_BUTTON_STATE_TAG`, `LOCKSCREEN_LYRIC_BUTTON_DRAWABLE_TAG`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`。


### `shouldShowLockscreenLyricButton` — 原 9828 行；原模块保留

原文件引用位置：9671, 9697, 9804。

成员依赖：`isLockscreenMediaView`。

companion 引用：无。


### `installLockscreenLyricButtonStatusBarStateHook` — 原 9831 行；原模块保留

原文件引用位置：9104。

成员依赖：`hookDeoptimized`, `refreshLockscreenLyricButtons`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `runCatching { classLoader.loadClass(name) }.getOrNull()`
- `method.name == "setState" && method.parameterTypes.firstOrNull() ==`
- `.distinctBy { method -> method.parameterTypes.toList() }`
- `.setId("lockscreen-lyrics-button:status-bar-state-$index")`


### `shouldOwnLockscreenLyricSlot` — 原 9868 行；原模块保留

原文件引用位置：9617, 9641。

成员依赖：`findMiuiMediaHeaderAncestor`。

companion 引用：`SYSTEM_UI`, `KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED`, `lockscreenLyricButtons`, `lockscreenLyricButtonHeaders`。


### `findMiuiMediaHeaderAncestor` — 原 9880 行；原模块保留

原文件引用位置：9738, 9790, 9877。

成员依赖：`isMiuiMediaHeaderView`。

companion 引用：无。


### `lockscreenLyricsShowing` — 原 9890 行；原模块保留

原文件引用位置：2000, 9679, 9810。

成员依赖：无其他原模块成员引用。

companion 引用：`LOCKSCREEN_LYRIC_STATE_PREFS`, `LOCKSCREEN_LYRIC_STATE_SHOWING`。


### `setLockscreenLyricsShowing` — 原 9894 行；原模块保留

原文件引用位置：1851, 1871, 9714。

成员依赖：无其他原模块成员引用。

companion 引用：`LOCKSCREEN_LYRIC_STATE_PREFS`, `LOCKSCREEN_LYRIC_STATE_SHOWING`。


### `loadLockscreenLyricButtonDrawable` — 原 9902 行；原模块保留

原文件引用位置：9682。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `lockscreenLyricDrawableLoadFailureLogged`。


### `tr` — 原 9927 行；原模块保留

原文件引用位置：9688, 9690。

成员依赖：无其他原模块成员引用。

companion 引用：`systemUiTranslationCache`。


### `installLockscreenCustomizationMenuHook` — 原 9953 行；原模块保留

原文件引用位置：9109。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val interactorClass = classLoader.loadClass(`
- `val onLongPress = interactorClass.getMethod("onLongPress")`
- `.setId("lockscreen-mini-player-customization-menu:show")`
- `val hideMenu = interactorClass.getMethod("hideMenu")`
- `.setId("lockscreen-mini-player-customization-menu:hide")`


### `installLockscreenMediaVisibilityProviderHooks` — 原 9982 行；原模块保留

原文件引用位置：9110。

成员依赖：`hookDeoptimized`, `shouldFilterLockscreenMedia`, `isMediaEntry`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val entryClass = classLoader.loadClass(`
- `val providerClass = classLoader.loadClass(className)`
- `method.name == "shouldHideNotification" &&`
- `method.parameterTypes.isNotEmpty() &&`
- `method.parameterTypes[0].isAssignableFrom(entryClass)`
- `.setId("lockscreen-hide-media-notification:provider:${className.substringAfterLast('.')}:$index")`
- `val lockState = if (method.parameterCount > 1 &&`
- `method.parameterTypes[1] == Boolean::class.javaPrimitiveType`


### `installLockscreenMediaPipelineHook` — 原 10034 行；原模块保留

原文件引用位置：9111。

成员依赖：`shouldFilterLockscreenMedia`, `isMediaEntry`, `isKeyguardCoordinatorOnKeyguard`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val filterClass = classLoader.loadClass(`
- `val shouldFilterOut = filterClass.getDeclaredMethod(`
- `classLoader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry"),`
- `.setId("lockscreen-hide-media-notification:pipeline")`


### `installTinyLockscreenMediaHook` — 原 10071 行；原模块保留

原文件引用位置：9112。

成员依赖：`shouldFilterLockscreenMedia`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val dataStoreClass = classLoader.loadClass(`
- `it.name == "onMediaUpdate" && it.parameterCount == 1`
- `.setId("lockscreen-hide-media-notification:tiny-panel-update")`
- `it.name == "merge" && it.parameterCount == 3`
- `.setId("lockscreen-hide-media-notification:tiny-panel-merge")`


### `lockscreenMediaNotificationMode` — 原 10111 行；原模块保留

原文件引用位置：7801, 10133, 10146。

成员依赖：无其他原模块成员引用。

companion 引用：`KEY_LOCKSCREEN_MINI_PLAYER_HIDE_MEDIA_NOTIFICATION`, `KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE`。


### `shouldHideLockscreenMedia` — 原 10122 行；原模块保留

原文件引用位置：9157, 9172, 9223, 9367, 9404, 10149, 10359, 10486。

成员依赖：`lockscreenMediaNotificationMode`。

companion 引用：`KEY_LOCKSCREEN_MINI_PLAYER_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`。


### `shouldFilterLockscreenMedia` — 原 10143 行；原模块保留

原文件引用位置：10013, 10052, 10086, 10099。

成员依赖：`lockscreenMediaNotificationMode`。

companion 引用：`KEY_LOCKSCREEN_MINI_PLAYER_ENABLED`, `KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED`。


### `applyLockscreenMediaPresentation` — 原 10148 行；原模块保留

原文件引用位置：9300, 9488。

成员依赖：`mediaHeaderOnActiveKeyguard`, `shouldHideLockscreenMedia`, `updateLockscreenMediaHeaderVisibility`, `scheduleLockscreenMediaPresentation`, `isMiuiMediaHeaderView`。

companion 引用：`lockscreenRows`, `lockscreenHiddenRows`, `lockscreenMediaRows`, `lockscreenMediaHeaders`。


### `updateLockscreenMediaHeaderVisibility` — 原 10181 行；原模块保留

原文件引用位置：9372, 9407, 10161, 10357, 10488。

成员依赖：`animateLockscreenMediaHeaderContents`, `requestLockscreenMediaControllerVisibility`, `reapplyNativeMediaEffect`, `isMiuiMediaHeaderView`。

companion 引用：`lockscreenMediaTransitionTargets`, `lockscreenMediaTransitionGenerations`, `lockscreenMediaTransitionBaseTranslationY`, `lockscreenMediaTransitionsRunning`, `LOCKSCREEN_MEDIA_HIDE_DURATION_MS`, `LOCKSCREEN_MEDIA_SHOW_DURATION_MS`, `LOCKSCREEN_MEDIA_TRANSITION_TRAVEL_DP`, `LOCKSCREEN_MEDIA_TRANSITION_START_SCALE`, `LOCKSCREEN_MEDIA_TRANSITION_HIDDEN_ALPHA`, `LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_ALPHA`, `LOCKSCREEN_MEDIA_TRANSITION_COMPLETE_SCALE`。


### `animateLockscreenMediaHeaderContents` — 原 10271 行；原模块保留

原文件引用位置：10200。

成员依赖：`readInstanceField`, `centeredMediaTextTranslation`。

companion 引用：`lockscreenMediaTextBaseTranslations`, `LOCKSCREEN_MEDIA_HIDE_DURATION_MS`, `LOCKSCREEN_MEDIA_SHOW_DURATION_MS`, `LOCKSCREEN_MEDIA_TRANSITION_START_SCALE`, `LOCKSCREEN_MEDIA_TRANSITION_HIDDEN_ALPHA`。


### `centeredMediaTextTranslation` — 原 10320 行；原模块保留

原文件引用位置：10310。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `requestLockscreenMediaControllerVisibility` — 原 10334 行；原模块保留

原文件引用位置：10208, 10240, 10257。

成员依赖：`readInstanceField`。

companion 引用：`TAG`, `lockscreenMediaControllers`。


### `scheduleLockscreenMediaPresentation` — 原 10353 行；原模块保留

原文件引用位置：9397, 9401, 9439, 9444, 9468, 9521, 10166。

成员依赖：`mediaHeaderOnActiveKeyguard`, `shouldHideLockscreenMedia`, `updateLockscreenMediaHeaderVisibility`, `isMiuiMediaHeaderView`。

companion 引用：`LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SHORT_DELAY_MS`, `LOCKSCREEN_MEDIA_PRESENTATION_REAPPLY_SETTLE_DELAY_MS`。


### `installLockscreenNativeClockScalerHook` — 原 10367 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val timeView = classLoader.loadClass("com.miui.clock.allInOne.TimeView")`
- `method.name == "setSizeInternal" &&`
- `method.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))`
- `.setId("lockscreen-native-clock-size-$index")`


### `installLockscreenMediaEffectRefreshHook` — 原 10400 行；原模块保留

原文件引用位置：9108。

成员依赖：`reapplyNativeMediaEffect`, `lockscreenMediaInteractors`。

companion 引用：`TAG`, `MIUI_MEDIA_HEADER_VIEW_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val binder = classLoader.loadClass(`
- `val bind = binder.getMethod(`
- `classLoader.loadClass(`
- `classLoader.loadClass(`
- `.setId("lockscreen-media-effect-refresh")`


### `reapplyNativeMediaEffect` — 原 10434 行；原模块保留

原文件引用位置：10217, 10233, 10424。

成员依赖：`mediaHeaderOnActiveKeyguard`, `readInstanceField`, `readStateFlowBoolean`, `lockscreenMediaInteractors`, `isMiuiMediaHeaderView`。

companion 引用：`TAG`。


### `readStateFlowBoolean` — 原 10464 行；原模块保留

原文件引用位置：10440。

成员依赖：`readInstanceField`。

companion 引用：无。


### `lockscreenMediaInteractors` — 原 10471 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `lockscreenMediaHeaderGuards` — 原 10474 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installLockscreenMediaHeaderGuard` — 原 10480 行；原模块保留

原文件引用位置：9215, 9313, 9365, 9394, 9432, 9467, 9517。

成员依赖：`mediaHeaderOnActiveKeyguard`, `maintainLockscreenLyricButton`, `shouldHideLockscreenMedia`, `updateLockscreenMediaHeaderVisibility`, `lockscreenMediaHeaderGuards`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `installLockscreenMediaArtworkClick` — 原 10498 行；原模块保留

原文件引用位置：9395, 9399, 9433, 9437, 9442。

成员依赖：无其他原模块成员引用。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：




### `findLockscreenMediaHeader` — 原 10505 行；原模块保留

原文件引用位置：9514。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `drawableBitmap` — 原 10514 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isLockscreenMediaView` — 原 10532 行；原模块保留

原文件引用位置：7080, 7096, 9829。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isMiuiMediaHeaderView` — 原 10539 行；原模块保留

原文件引用位置：9884, 10152, 10182, 10355, 10436。

成员依赖：无其他原模块成员引用。

companion 引用：`MIUI_MEDIA_HEADER_VIEW_CLASS`。


### `isMediaRow` — 原 10548 行；原模块保留

原文件引用位置：9151, 9158, 9173。

成员依赖：`isMediaEntry`。

companion 引用：无。


### `isMediaEntry` — 原 10568 行；原模块保留

原文件引用位置：10014, 10054, 10550。

成员依赖：`readInstanceField`。

companion 引用：无。


### `isKeyguardCoordinatorOnKeyguard` — 原 10620 行；原模块保留

原文件引用位置：10053。

成员依赖：`readInstanceField`。

companion 引用：无。


### `installFingerprintIconVisualHook` — 原 10635 行；原模块保留

原文件引用位置：1681。

成员依赖：`hookDeoptimized`, `fingerprintHideMode`。

companion 引用：`TAG`, `MIUI_GXZW_ICON_VIEW_CLASS`, `FOD_DISMISS_ICON_METHOD`, `FINGERPRINT_HIDE_GLOBAL`。

目标/签名/配置/Hook ID 交叉检查：

- `val iconClass = classLoader.loadClass(MIUI_GXZW_ICON_VIEW_CLASS)`
- `val dismissIcon = iconClass.getMethod(FOD_DISMISS_ICON_METHOD)`
- `(method.name == "show" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))) \|\|`
- `(method.name == "showFingerprintIcon" && method.parameterCount == 0) \|\|`
- `(method.name == "setGxzwIconOpaque" && method.parameterCount == 0)`
- `.setId("lockscreen-fod-icon-transparent-$index")`


### `installSystemUiDepthHooks` — 原 10670 行；原模块保留

原文件引用位置：1692。

成员依赖：`forceDepthImageThreshold`, `installDepthDisplayStateHook`, `clearDepthAvoidState`, `installThirdPartyWallpaperDepthHook`。

companion 引用：`TAG`, `DEPTH_EVALUATOR_CLASS`, `DEPTH_THRESHOLD_CLASS`, `KEYGUARD_DEPTH_INTERACTOR_CLASS`, `DEFAULT_DEPTH_IMAGE_THRESHOLD`, `UNLIMITED_DEPTH_IMAGE_THRESHOLD`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val thresholdClass = classLoader.loadClass(DEPTH_THRESHOLD_CLASS)`
- `val evaluatorClass = classLoader.loadClass(DEPTH_EVALUATOR_CLASS)`
- `.setId("systemui-depth-image-threshold")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&`
- `val interactorClass = classLoader.loadClass(KEYGUARD_DEPTH_INTERACTOR_CLASS)`
- `hook(interactorClass.getMethod("updateAvoidStatus"))`
- `.setId("systemui-depth-time-overlap")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {`
- `it.name == "setDepthTransitionAlpha" && it.parameterCount == 3`
- `.setId("systemui-depth-alpha")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {`


### `forceDepthImageThreshold` — 原 10722 行；原模块保留

原文件引用位置：10715。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `IMAGE_THRESHOLD_FIELD`, `THRESHOLD_RATE_FIELD`, `UNLIMITED_DEPTH_IMAGE_THRESHOLD`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`。


### `installDepthDisplayStateHook` — 原 10738 行；原模块保留

原文件引用位置：10713。

成员依赖：无其他原模块成员引用。

companion 引用：`KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)`
- `val updateElements = panelClass.getMethod("updateKeyguardElementsVisibility")`
- `hook(panelClass.getMethod("updateShowDepthState"))`
- `.setId("systemui-depth-display-state")`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {`
- `if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {`


### `clearDepthAvoidState` — 原 10767 行；原模块保留

原文件引用位置：10695, 10709。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`。


### `isNotificationFodPositionLimitRemoved` — 原 10781 行；原模块保留

原文件引用位置：8890, 9026。

成员依赖：无其他原模块成员引用。

companion 引用：`FOD_MODE_DEFAULT`, `FOD_MODE_KEEP_ICON`, `KEY_NOTIFICATION_FOD_MODE`, `KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED`, `KEY_NOTIFICATIONS_IGNORE_FOD`。


### `fingerprintHideMode` — 原 10792 行；原模块保留

原文件引用位置：10656, 10847, 10861。

成员依赖：无其他原模块成员引用。

companion 引用：`FOD_MODE_DEFAULT`, `FOD_MODE_HIDE_ICON`, `FINGERPRINT_HIDE_NONE`, `FINGERPRINT_HIDE_GLOBAL`, `KEY_NOTIFICATION_FOD_MODE`, `KEY_FINGERPRINT_HIDE_MODE`。


### `installLockscreenFingerprintAnimationHook` — 原 10808 行；原模块保留

原文件引用位置：1685。

成员依赖：`hookDeoptimized`, `fingerprintHideMode`。

companion 引用：`TAG`, `MIUI_GXZW_ANIM_MANAGER_CLASS`, `MIUI_GXZW_KEYGUARD_AUTHEN_FIELD`, `MIUI_GXZW_ANIMATION_ITEMS_FIELD`, `MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD`, `MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD`, `FINGERPRINT_HIDE_LOCKSCREEN`, `LOCKSCREEN_HIDDEN_FINGERPRINT_ICON_RESOURCE`。

目标/签名/配置/Hook ID 交叉检查：

- `val managerClass = classLoader.loadClass(MIUI_GXZW_ANIM_MANAGER_CLASS)`
- `method.name to method.parameterTypes.toList()`
- `val iconResources = methods.filter { it.name == MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD }`
- `val recognizingItems = methods.filter { it.name == MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD }`
- `"getFingerIconResource was not found; methods=${methods.map { it.name }.filter { name ->`
- `"getRecognizingAnimItem was not found; methods=${methods.map { it.name }.filter { name ->`
- `.setId("lockscreen-fod-animation-icon-resource-$index")`
- `.setId("lockscreen-fod-animation-recognizing-item-$index")`


### `installLockscreenWhiteBarHook` — 原 10884 行；原模块保留

原文件引用位置：1700。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `KEY_LOCKSCREEN_WHITE_BAR_ENABLED`, `NAVIGATION_BAR_VIEW_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val navigationBarViewClass = classLoader.loadClass(NAVIGATION_BAR_VIEW_CLASS)`
- `val updateNavButtonIcons = navigationBarViewClass.getDeclaredMethod("updateNavButtonIcons")`
- `val getHomeHandle = navigationBarViewClass.getDeclaredMethod("getHomeHandle")`
- `val dispatcherClass = getHomeHandle.returnType`
- `val setHandleVisibility = dispatcherClass.getDeclaredMethod(`
- `val homeHandleId = classLoader.loadClass("com.android.systemui.R\$id")`
- `.setId("lockscreen-white-bar:visibility")`
- `preferences.getBoolean(KEY_LOCKSCREEN_WHITE_BAR_ENABLED, false) &&`
- `.setId("lockscreen-white-bar:update")`
- `if (preferences.getBoolean(KEY_LOCKSCREEN_WHITE_BAR_ENABLED, false)) {`


### `installGlobalGestureHandleHook` — 原 10954 行；原模块保留

原文件引用位置：1704。

成员依赖：`drawCustomGestureHandle`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val handleClass = classLoader.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandle")`
- `val onDraw = handleClass.getDeclaredMethod("onDraw", Canvas::class.java)`
- `.setId("global-gesture-handle:hidden")`
- `if (preferences.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false)) {`
- `val customEnabled = preferences.getBoolean(KEY_GESTURE_HANDLE_CUSTOM_ENABLED, false)`
- `hook(handleClass.getDeclaredMethod("onAttachedToWindow"))`
- `.setId("global-gesture-handle:material")`
- `hook(handleClass.getDeclaredMethod("onDetachedFromWindow"))`
- `.setId("global-gesture-handle:material-detach")`
- `val rotatedClass = classLoader.loadClass(`
- `hook(rotatedClass.getDeclaredMethod("onDraw", Canvas::class.java))`
- `.setId("global-gesture-handle:rotated-hidden")`
- `if (preferences.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false)) {`
- `val customEnabled = preferences.getBoolean(KEY_GESTURE_HANDLE_CUSTOM_ENABLED, false)`


### `isGlobalGestureHandle` — 原 11043 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `configureCustomGestureHandleMaterial` — 原 11050 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`applyLegacyBackdropMaterial`, `applySystemGlassMaterial`, `currentGestureHandleColor`。

companion 引用：`DEFAULT_SOFT_GLASS_LUMINANCE`。


### `drawCustomGestureHandle` — 原 11095 行；原模块保留

原文件引用位置：10974, 11026。

成员依赖：`currentGestureHandleColor`, `gestureHandleBottom`。

companion 引用：无。


### `currentGestureHandleColor` — 原 11149 行；原模块保留

原文件引用位置：11056, 11128, 11228, 11258。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `gestureHandleBottom` — 原 11154 行；原模块保留

原文件引用位置：11122, 11205。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `syncGestureMaterialOverlay` — 原 11159 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`applyLegacyBackdropMaterial`, `applySystemGlassMaterial`, `currentGestureHandleColor`, `gestureHandleBottom`。

companion 引用：`TAG`, `DEFAULT_SOFT_GLASS_LUMINANCE`。


### `installGlobalGestureControllerHook` — 原 11289 行；原模块保留

原文件引用位置：1705。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val controllerClass = classLoader.loadClass(`
- `controllerClass.declaredMethods.filter { it.name == "createNavigationBar" }.forEach { method ->`
- `.setId("global-gesture-handle:controller-create")`
- `val override = preferences.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false) &&`


### `installDimensionHooks` — 原 11331 行；原模块保留

原文件引用位置：1585。

成员依赖：`replaceDimensionIfNeeded`, `replaceDimensionPixelIfNeeded`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `hook(Resources::class.java.getMethod("getDimension", Int::class.javaPrimitiveType))`
- `.setId("resource-dimension")`
- `hook(Resources::class.java.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType))`
- `.setId("resource-dimension-pixel-size")`
- `hook(Resources::class.java.getMethod("getDimensionPixelOffset", Int::class.javaPrimitiveType))`
- `.setId("resource-dimension-pixel-offset")`


### `headsUpGlassDispatching` — 原 11369 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `convertedHeadsUpRows` — 原 11370 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isHeadsUpNotificationSurface` — 原 11372 行；原模块保留

原文件引用位置：5465, 5471, 5542, 6857, 6888, 6964, 7006。

成员依赖：`stackContainsClass`, `headsUpGlassDispatching`。

companion 引用：无。


### `installHeadsUpNotificationSoftGlassHooks` — 原 11380 行；原模块保留

原文件引用位置：1724。

成员依赖：`hookDeoptimized`, `normalNotificationGlassParams`, `stackContainsClass`, `headsUpGlassDispatching`, `convertedHeadsUpRows`。

companion 引用：`TAG`, `MI_GLASS_COMPAT_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val blur = classLoader.loadClass(prefix + "HeadsUpNotificationBlurEffect")`
- `val rowBlur = classLoader.loadClass(prefix + "NotificationRowBlurEffect")`
- `val injector = row.javaClass.getMethod("getInjector").invoke(row)`
- `injector?.javaClass?.getMethod("getBackgroundNormal")?.invoke(injector) as? View`
- `val glassCompat = Class.forName(MI_GLASS_COMPAT_CLASS, false, classLoader)`
- `glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)`
- `glassCompat.getMethod(`
- `it.name == (if (clear) "clear" else "apply") && it.parameterCount == (if (clear) 1 else 2)`
- `blur.declaredMethods.filter { it.name == "apply" && it.parameterCount == 2 }.forEach { method ->`
- `.setId("heads-up-soft-glass:platform-apply").intercept { chain ->`
- `preferences.getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false) &&`
- `blur.declaredMethods.filter { it.name == "clear" && it.parameterCount == 1 }.forEach { method ->`
- `.setId("heads-up-soft-glass:platform-clear").intercept { chain ->`
- `val effect = classLoader.loadClass(prefix + className)`
- `effect.declaredMethods.filter { it.name == "apply" && it.parameterCount == 2 }.forEach { method ->`
- `.setId("heads-up-soft-glass:resource-apply:$className")`
- `preferences.getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false) &&`
- `effect.declaredMethods.filter { it.name == "clear" && it.parameterCount == 1 }.forEach { method ->`
- `.setId("heads-up-soft-glass:resource-clear:$className")`
- `preferences.getBoolean(KEY_HEADS_UP_NOTIFICATION_SOFT_GLASS, false) &&`


### `installCornerRadiusHooks` — 原 11476 行；原模块保留

原文件引用位置：1590。

成员依赖：`controlCenterRadiusRuntime`, `installLoadedVolumePanelHook`, `installLoadedCornerRadiusHook`, `applyControlCenterDrawableRadius`。

companion 引用：无。

目标/签名/配置/Hook ID 交叉检查：

- `hook(ClassLoader::class.java.getMethod("loadClass", String::class.java))`
- `.setId("control-center-class-discovery")`
- `hook(View::class.java.getMethod("setBackground", Drawable::class.java))`
- `.setId("slider-background-radius")`


### `installKnownCornerRadiusHooks` — 原 11506 行；原模块保留

原文件引用位置：1594。

成员依赖：`installLoadedCornerRadiusHook`。

companion 引用：`TAG`, `TOP_BUTTONS_CLASS`, `MEDIA_PANEL_CLASS`, `SLIDER_VIEW_HOLDER_CLASS`, `BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS`, `SECONDARY_PANEL_CONTROLLER_BASE_CLASS`, `SECONDARY_VOLUME_PANEL_CLASS`, `QS_ITEM_VIEW_HOLDER_CLASS`, `DEVICE_CENTER_ENTRY`。

目标/签名/配置/Hook ID 交叉检查：

- `classLoader.loadClass(className).also { targetClass ->`


### `installThirdPartyWallpaperDepthHook` — 原 11532 行；原模块保留

原文件引用位置：10714。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `WALLPAPER_INFO_CLASS`, `LARGE_SCREEN_HIERARCHY_ENABLE_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return`
- `val wallpaperInfoClass = classLoader.loadClass(WALLPAPER_INFO_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getSupportSubject"))`
- `.setId("systemui-depth-wallpaper-subject")`
- `val hierarchyClass = classLoader.loadClass(LARGE_SCREEN_HIERARCHY_ENABLE_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getLargeScreenHierarchyEnable"))`
- `.setId("systemui-depth-wallpaper-hierarchy")`


### `installAodThirdPartyWallpaperDepthHook` — 原 11576 行；原模块保留

原文件引用位置：1762。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `AOD_WALLPAPER_INFO_CLASS`, `AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS`, `WALLPAPER_CONTROLLER_CLASS`, `WALLPAPER_CONTROLLER_COMPANION_CLASS`, `KEY_REMOVE_DEPTH_IMAGE_LIMIT`。

目标/签名/配置/Hook ID 交叉检查：

- `if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return`
- `val wallpaperInfoClass = classLoader.loadClass(AOD_WALLPAPER_INFO_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getSupportSubject"))`
- `.setId("aod-depth-wallpaper-subject")`
- `val hierarchyClass = classLoader.loadClass(AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS)`
- `hook(wallpaperInfoClass.getMethod("getLargeScreenHierarchyEnable"))`
- `.setId("aod-depth-wallpaper-hierarchy")`
- `val companionClass = classLoader.loadClass(WALLPAPER_CONTROLLER_COMPANION_CLASS)`
- `val pickColorMethod = companionClass.getMethod(`
- `.setId("aod-depth-wallpaper-clock-style")`
- `result?.javaClass?.getMethod("setClockInfoStyle", Integer::class.java)`
- `result?.javaClass?.getMethod("setSignatureAlignment", Integer::class.java)`
- `val controllerClass = classLoader.loadClass(WALLPAPER_CONTROLLER_CLASS)`
- `hook(controllerClass.getMethod("needResetMagicType"))`
- `.setId("aod-depth-wallpaper-reset")`


### `installVideoWallpaperGlassSupportHook` — 原 11637 行；原模块保留

原文件引用位置：1763, 1823。

成员依赖：`hookDeoptimized`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val companionClass = classLoader.loadClass(`
- `method.parameterTypes.contentEquals(arrayOf(contextClass)) &&`
- `method.returnType == Boolean::class.javaPrimitiveType &&`
- `(method.name == "isVideoSupportGlassFilter" \|\| method.name == "kja0")`
- `.setId("editor-video-wallpaper-glass-support-${method.name}")`


### `installAodLockscreenTemplateLimitHook` — 原 11664 行；原模块保留

原文件引用位置：1787, 1822。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val mode = preferences.getInt(`
- `LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM -> preferences.getInt(`
- `val historyDaoClass = classLoader.loadClass(`
- `val oldestHistoryMethod = historyDaoClass.getMethod("getOldestHistory", Int::class.javaPrimitiveType)`
- `.setId("aod-lockscreen-template-history-limit")`
- `val modelClass = classLoader.loadClass(`
- `.setId("aod-lockscreen-template-limit-$index")`


### `installAodEditorBackgroundHook` — 原 11737 行；原模块保留

原文件引用位置：1790。

成员依赖：`hookDeoptimized`, `applyAodEditorBackground`, `captureCurrentEditorWallpaper`。

companion 引用：`TAG`。

目标/签名/配置/Hook ID 交叉检查：

- `val activityClass = classLoader.loadClass("com.miui.keyguard.editor.EditorActivity")`
- `val init = activityClass.getDeclaredMethod("initContentView").apply { isAccessible = true }`
- `.setId("aod-editor-background")`
- `val mode = preferences.getInt(KEY_LOCKSCREEN_EDITOR_BACKGROUND_MODE, LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM)`
- `val wallpaperClass = classLoader.loadClass("com.miui.keyguard.editor.edit.wallpaper.CombinedWallpaperView")`
- `wallpaperClass.declaredMethods.filter { it.name == "switchWallpaperView" }.forEach { method ->`
- `.setId("aod-editor-background-sync-${method.parameterTypes.size}")`
- `preferences.getInt(KEY_LOCKSCREEN_EDITOR_BACKGROUND_MODE, LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM) == LOCKSCREEN_EDITOR_BACKGROUND_LOCKSCREEN`


### `applyAodEditorBackground` — 原 11799 行；原模块保留

原文件引用位置：11757, 11768, 11787。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `captureCurrentEditorWallpaper` — 原 11819 行；原模块保留

原文件引用位置：11756, 11786。

成员依赖：`findViewByClassName`。

companion 引用：无。


### `findViewByClassName` — 原 11832 行；原模块保留

原文件引用位置：11820。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `drawableToBitmap` — 原 11842 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installVolumePanelHooks` — 原 11856 行；原模块保留

原文件引用位置：1622。

成员依赖：`volumeGlassRuntime`, `installBackgroundBlurRadiusDispatchHook`, `installVolumeNativeParameterHooks`, `installLoadedVolumePanelHook`。

companion 引用：`TAG`, `VOLUME_PANEL_CONTROLLER_CLASS`, `VOLUME_COLUMN_CLASS`, `MI_BACKGROUND_STYLE_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_PANEL_CONTROLLER_CLASS), preferences)`
- `installLoadedVolumePanelHook(classLoader.loadClass(VOLUME_COLUMN_CLASS), preferences)`
- `runCatching { installLoadedVolumePanelHook(classLoader.loadClass(name), preferences) }`
- `runCatching { installLoadedVolumePanelHook(classLoader.loadClass("com.android.systemui.miui.volume.Util"), preferences) }`
- `runCatching { installLoadedVolumePanelHook(classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS), preferences) }`


### `installBackgroundBlurRadiusDispatchHook` — 原 11881 行；原模块保留

原文件引用位置：11862。

成员依赖：`hookDeoptimized`, `volumeGlobalGlassSelected`, `stackContainsClass`, `isVolumePanelSurface`。

companion 引用：`TAG`, `MI_BACKGROUND_STYLE_CLASS`, `KEY_VOLUME_PANEL_BLUR_RADIUS`, `KEY_VOLUME_PANEL_GLASS_STRENGTH`, `KEY_VOLUME_PANEL_MATERIAL_ENABLED`。

目标/签名/配置/Hook ID 交叉检查：

- `val styleClass = classLoader.loadClass(MI_BACKGROUND_STYLE_CLASS)`
- `val method = styleClass.getMethod(`
- `.setId("background-blur-radius-dispatch")`
- `val materialEnabled = preferences.getBoolean(KEY_VOLUME_PANEL_MATERIAL_ENABLED, true)`
- `val blur = preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24)`
- `val glass = preferences.getInt(KEY_VOLUME_PANEL_GLASS_STRENGTH, 50)`


### `installVolumeNativeParameterHooks` — 原 11930 行；原模块保留

原文件引用位置：11861。

成员依赖：`volumeGlassRuntime`, `logVolumeNativeParameterHit`, `invokeTwoIntSetter`, `isVolumePanelSurface`。

companion 引用：`TAG`, `KEY_VOLUME_PANEL_BLUR_RADIUS`, `KEY_VOLUME_PANEL_GLASS_STRENGTH`, `KEY_VOLUME_PANEL_BACKGROUND_OPACITY`, `volumeNativeParameterHooksInstalled`。

目标/签名/配置/Hook ID 交叉检查：

- `hook(View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType))`
- `.setId("volume-panel-native-background-blur")`
- `val radius = preferences.getInt(KEY_VOLUME_PANEL_BLUR_RADIUS, 24).coerceIn(0, 120)`
- `hook(View::class.java.getMethod(`
- `.setId("volume-panel-native-glass-blur")`
- `hook(View::class.java.getMethod("setMiGlass", FloatArray::class.java))`
- `.setId("volume-panel-native-glass-material")`
- `hook(View::class.java.getMethod("setBackgroundBlurAlpha", Float::class.javaPrimitiveType))`
- `.setId("volume-panel-native-background-opacity")`
- `val opacity = preferences.getInt(KEY_VOLUME_PANEL_BACKGROUND_OPACITY, 100)`


### `logVolumeNativeParameterHit` — 原 12025 行；原模块保留

原文件引用位置：11943, 11968, 11994, 12009。

成员依赖：无其他原模块成员引用。

companion 引用：`TAG`, `volumeNativeParameterHookHits`。


### `findVolumeLongPressPanel` — 原 12032 行；原模块保留

原文件引用位置：12043, 12050。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `volumeLongPress` — 原 12041 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：`volumeGlassRuntime`, `findVolumeLongPressPanel`, `readField`。

companion 引用：无。


### `installLoadedVolumePanelHook` — 原 12056 行；原模块保留

原文件引用位置：11486, 11864, 11865, 11870, 11872, 11873。

成员依赖：`hookDeoptimized`, `softGlassRuntime`, `volumeGlassRuntime`, `volumeMaterialDispatching`, `volumeGlobalGlassSelected`, `stackContainsClass`, `volumeLongPress`, `applyVolumePanelNativeTuning`, `readField`, `isVolumePanelSurface`。

companion 引用：`TAG`, `VOLUME_PANEL_CONTROLLER_CLASS`, `VOLUME_PANEL_REFRESH_METHODS`, `VOLUME_PANEL_LAYOUT_METHODS`, `MI_BACKGROUND_STYLE_CLASS`, `volumePanelHookedClasses`, `volumePanelSurfaceRoots`。

目标/签名/配置/Hook ID 交叉检查：

- `val bionics = targetClass.classLoader.loadClass("miui.systemui.util.MaterialMode\$Bionics")`
- `hookDeoptimized(targetClass.getDeclaredMethod("getMaterialMode"))`
- `.setId("volume-panel-global-material:mode").intercept { chain ->`
- `it.name == "setMiBackgroundStyle" && it.parameterTypes.firstOrNull() == View::class.java`
- `.setId("volume-panel-global-material:style:${method.parameterCount}").intercept { chain ->`
- `it.name in setOf("isBionicsAdvancedMaterialEnabled", "isAdvancedMaterialEffective") &&`
- `it.returnType == Boolean::class.javaPrimitiveType &&`
- `it.parameterTypes.contentEquals(arrayOf(Context::class.java))`
- `.setId("volume-panel-global-material:${method.name}").intercept { chain ->`
- `.filter { it.name in VOLUME_PANEL_REFRESH_METHODS }`
- `.setId("volume-panel:" + method.name + ":" + method.parameterCount)`
- `if (!volumeGlobalGlassSelected() \|\| method.name in VOLUME_PANEL_LAYOUT_METHODS) {`
- `targetClass.declaredMethods.filter { it.name in setOf("onFinishInflate", "updateFooterVisibility") }.forEach { method ->`
- `.setId("volume-panel-buttons:${method.name}").intercept { chain ->`
- `it.name == "onTouchEvent" && it.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))`
- `.setId("volume-panel-long-press").intercept { chain ->`


### `installAospVolumePanelFallback` — 原 12150 行；原模块保留

原文件引用位置：1626。

成员依赖：`hookDeoptimized`, `applyVolumePanelRootTuning`。

companion 引用：`TAG`, `AOSP_VOLUME_DIALOG_CLASS`。

目标/签名/配置/Hook ID 交叉检查：

- `val dialogClass = classLoader.loadClass(AOSP_VOLUME_DIALOG_CLASS)`
- `.filter { it.name == "onCreate" \|\| it.name == "show" }`
- `.setId("volume-panel:aosp:${method.name}:${method.parameterCount}")`
- `val window = chain.thisObject?.javaClass?.getMethod("getWindow")`


### `findVolumeControllerRoot` — 原 12178 行；原模块保留

原文件引用位置：12196。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `applyVolumePanelNativeTuning` — 原 12195 行；原模块保留

原文件引用位置：12116。

成员依赖：`findVolumeControllerRoot`, `applyVolumePanelRootTuning`。

companion 引用：无。


### `applyVolumePanelRootTuning` — 原 12200 行；原模块保留

原文件引用位置：12167, 12197。

成员依赖：`volumeGlassRuntime`, `volumeOriginalDrawableAlphas`, `idName`, `countViewTree`, `invokeIfPresent`, `invokeNumericSetter`, `invokeTwoIntSetter`, `invokeBooleanSetter`。

companion 引用：`TAG`, `KEY_VOLUME_PANEL_BLUR_RADIUS`, `KEY_VOLUME_PANEL_GLASS_STRENGTH`, `KEY_VOLUME_PANEL_BACKGROUND_OPACITY`, `volumePanelNativeApiLogged`, `volumePanelAppliedTuning`。


### `countViewTree` — 原 12287 行；原模块保留

原文件引用位置：12226。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `invokeMiBlurOutline` — 原 12294 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：`MI_BLUR_COMPAT_CLASS`。


### `invokeIfPresent` — 原 12315 行；原模块保留

原文件引用位置：12262。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `invokeNumericSetter` — 原 12321 行；原模块保留

原文件引用位置：12252, 12253, 12255。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `invokeTwoIntSetter` — 原 12342 行；原模块保留

原文件引用位置：11993, 12254, 12259。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `invokeBooleanSetter` — 原 12354 行；原模块保留

原文件引用位置：12256。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `readField` — 原 12363 行；原模块保留

原文件引用位置：12045, 12046, 12051, 12129, 12130, 12529, 12623, 12625, 12642, 12644。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `writeField` — 原 12367 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `installLoadedCornerRadiusHook` — 原 12373 行；原模块保留

原文件引用位置：11485, 11524。

成员依赖：`hookDeoptimized`, `readInstanceField`, `controlRadiusSettings`, `hookUnifiedRadiusSetter`, `hookMaterialStyle`, `hookDeviceCenterOutline`。

companion 引用：`TOP_BUTTONS_CLASS`, `MEDIA_PANEL_CLASS`, `SLIDER_VIEW_HOLDER_CLASS`, `BRIGHTNESS_PANEL_SLIDER_DELEGATE_CLASS`, `SECONDARY_PANEL_CONTROLLER_BASE_CLASS`, `SECONDARY_VOLUME_PANEL_CLASS`, `MI_BACKGROUND_STYLE_CLASS`, `DEVICE_CENTER_ENTRY`, `cornerTargetClasses`。

目标/签名/配置/Hook ID 交叉检查：

- `hookDeoptimized(targetClass.getDeclaredMethod("updateContentBg", Boolean::class.javaPrimitiveType))`
- `.setExceptionMode(ExceptionMode.PROTECTIVE).setId("control-center-expanded-background-radius")`
- `val view = runCatching { target.javaClass.getMethod("getContentBg").invoke(target) as? View }.getOrNull()`


### `controlRadiusDispatch` — 原 12410 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `controlRadiusBaselines` — 原 12411 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `controlDrawableBaselines` — 原 12412 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `controlOriginalOutlines` — 原 12413 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `controlRadiusSettings` — 原 12415 行；原模块保留

原文件引用位置：12395, 12467, 12499, 12528。

成员依赖：`controlCenterRadiusRuntime`。

companion 引用：无。


### `controlRadiusView` — 原 12418 行；原模块保留

原文件引用位置：12468。

成员依赖：`readInstanceField`, `resolveSliderView`。

companion 引用：无。


### `controlRadiusOffset` — 原 12429 行；原模块保留

原文件引用位置：12442, 12473, 12475。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `resolveControlRadius` — 原 12438 行；原模块保留

原文件引用位置：12478, 12531。

成员依赖：`controlRadiusBaselines`, `controlRadiusOffset`。

companion 引用：无。


### `updateControlOutline` — 原 12445 行；原模块保留

原文件引用位置：12483, 12506, 12515, 12532。

成员依赖：`controlOriginalOutlines`, `applyRoundedOutline`。

companion 引用：无。


### `hookUnifiedRadiusSetter` — 原 12458 行；原模块保留

原文件引用位置：12379, 12380, 12383, 12384, 12385, 12386, 12388。

成员依赖：`hookDeoptimized`, `controlRadiusDispatch`, `controlRadiusBaselines`, `controlRadiusSettings`, `controlRadiusView`, `controlRadiusOffset`, `resolveControlRadius`, `updateControlOutline`, `applySliderDrawableRadius`。

companion 引用：`TAG`。


### `applyControlCenterDrawableRadius` — 原 12498 行；原模块保留

原文件引用位置：11496, 12535, 12548。

成员依赖：`controlDrawableBaselines`, `controlRadiusSettings`, `updateControlOutline`, `isControlCenterSliderBackgroundPart`。

companion 引用：`DEVICE_CENTER_ENTRY`。


### `hookMaterialStyle` — 原 12519 行；原模块保留

原文件引用位置：12405。

成员依赖：`hookDeoptimized`, `readField`, `controlRadiusSettings`, `resolveControlRadius`, `updateControlOutline`, `applyControlCenterDrawableRadius`。

companion 引用：`TOP_BUTTONS_CLASS`。


### `hookDeviceCenterOutline` — 原 12541 行；原模块保留

原文件引用位置：12381。

成员依赖：`hookDeoptimized`, `applyControlCenterDrawableRadius`。

companion 引用：`TAG`。


### `replaceDimensionIfNeeded` — 原 12554 行；原模块保留

原文件引用位置：11336。

成员依赖：`replacementDp`, `entryName`。

companion 引用：无。


### `replaceDimensionPixelIfNeeded` — 原 12563 行；原模块保留

原文件引用位置：11348, 11360。

成员依赖：`replacementDp`, `entryName`。

companion 引用：无。


### `replacementDp` — 原 12572 行；原模块保留

原文件引用位置：12559, 12568。

成员依赖：`volumeGlassRuntime`。

companion 引用：`KEY_NOTIFICATION_CORNER_RADIUS_OFFSET`, `KEY_CLOCK_ENABLED`, `KEY_CLOCK_SIZE`, `KEY_PADDING_END_ENABLED`, `KEY_PADDING_END`, `KEY_PADDING_END_LEGACY_ABSOLUTE`, `KEY_PADDING_START_ENABLED`, `KEY_PADDING_START`, `KEY_HEIGHT_ENABLED`, `KEY_STATUS_BAR_HEIGHT`, `KEY_PADDING_TOP_ENABLED`, `KEY_PADDING_TOP`, `KEY_PADDING_TOP_LEGACY_ABSOLUTE`, `KEY_VOLUME_PANEL_BLUR_RADIUS`, `KEY_VOLUME_PANEL_GLASS_STRENGTH`, `VOLUME_PANEL_MATERIAL_DIMENSION_NAMES`, `KEY_VOLUME_PANEL_MATERIAL_ENABLED`。


### `resolveSliderView` — 原 12621 行；原模块保留

原文件引用位置：12422。

成员依赖：`readField`。

companion 引用：无。


### `applySliderDrawableRadius` — 原 12639 行；原模块保留

原文件引用位置：12487。

成员依赖：`readField`, `controlDrawableBaselines`。

companion 引用：无。


### `entryName` — 原 12664 行；原模块保留

原文件引用位置：12559, 12568。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `dpToPixels` — 原 12668 行；原模块保留

原文件引用位置：无方法调用（字段、外部接口或原未调用方法）。

成员依赖：无其他原模块成员引用。

companion 引用：无。


### `isControlCenterSliderPart` — 原 12671 行；原模块保留

原文件引用位置：6939, 12680。

成员依赖：`idName`。

companion 引用：`SLIDER_PART_IDS`。


### `isControlCenterSliderBackgroundPart` — 原 12678 行；原模块保留

原文件引用位置：12502。

成员依赖：`idName`, `isControlCenterSliderPart`。

companion 引用：无。


### `volumeResourceName` — 原 12683 行；原模块保留

原文件引用位置：12708。

成员依赖：`volumeResourceNames`。

companion 引用：无。


### `isVolumePanelSurface` — 原 12692 行；原模块保留

原文件引用位置：5449, 5459, 5464, 5471, 5505, 5610, 5662, 5682, 11902, 11940, 11963, 11988, 12005, 12077。

成员依赖：`idName`, `volumeResourceName`。

companion 引用：`VOLUME_PANEL_VIEW_CLASS`, `volumePanelSurfaceRoots`。


### `applyRoundedOutline` — 原 12722 行；原模块保留

原文件引用位置：12448。

成员依赖：无其他原模块成员引用。

companion 引用：无。


## 迁移后的原模块保留方法完整清单

当前原模块仍有 341 个类成员，其中 308 个方法；下表列出全部方法及当前行号。其他成员为既有状态/类型和五分类持有者，另增加 OthersHooks 的只读持有者。

框架整组按用户要求暂留；其他保留内容涵盖生命周期/纯分发、已明确的控制中心/通知/锁屏/状态栏/SystemUi 耦合链、共享反射/材质/缓存及 AOD/ThemeManager 锁屏编辑实现。表中原生 scaler/旧音乐链等原未安装路径不被激活。

| 保留方法 | 当前行号 |
| --- | --- |
| `installHook` | 211 |
| `onSystemServerStarting` | 213 |
| `readContext` | 234 |
| `installLiftToWakeScheduleHook` | 278 |
| `applyLiftToWakeSchedule` | 303 |
| `currentMinuteOfDay` | 336 |
| `resolveMiuiServicesLoader` | 361 |
| `loadFrameworkClass` | 384 |
| `hookDeoptimized` | 414 |
| `installFrameworkHooks` | 418 |
| `hasHeadsetOutput` | 950 |
| `onPackageLoaded` | 971 |
| `installHyperMusicCoverGestureBridge` | 1335 |
| `dispatchHyperMusicCoverState` | 1415 |
| `syncHyperMusicCoverLyrics` | 1487 |
| `sendHyperMusicCoverMediaCardCommand` | 1512 |
| `sendHyperMusicCoverCommand` | 1530 |
| `installSuperXiaoAiHooks` | 1543 |
| `scheduleControlCenterHookRetries` | 1555 |
| `installControlCenterEditButtonHook` | 1577 |
| `installControlCenterMainPanelHook` | 1755 |
| `installControlCenterHeaderLifecycleHook` | 1801 |
| `hideBoundControlCenterEditButton` | 1845 |
| `installControlCenterTouchHook` | 1858 |
| `installControlCenterEventHandlerHook` | 1911 |
| `installControlCenterRootDispatchHook` | 1968 |
| `installControlCenterRootClickHook` | 1976 |
| `installControlCenterExpandLifecycleHook` | 1998 |
| `hideControlCenterTopButtons` | 2061 |
| `updateControlCenterTopButtonsProgress` | 2076 |
| `startControlCenterTopButtonsCollapse` | 2105 |
| `controlCenterExpansionProgress` | 2143 |
| `findControlCenterTopButtonHit` | 2155 |
| `isControlCenterDispatchOwner` | 2170 |
| `isControlCenterTopButtonHit` | 2180 |
| `performControlCenterTopButtonAction` | 2183 |
| `showControlCenterEdit` | 2202 |
| `installControlCenterTopButtons` | 2256 |
| `findControlCenterWindowRoot` | 2300 |
| `installControlCenterTopButtonsIntoRoot` | 2313 |
| `applyControlCenterTopButtonBackground` | 2466 |
| `applyControlCenterTopButtonIconScale` | 2548 |
| `controlCenterTopButtonsMaterialClassLoader` | 2558 |
| `applyControlCenterTopButtonMaterial` | 2563 |
| `applyControlCenterTopButtonSoftGlass` | 2597 |
| `showCachedGlobalActions` | 2632 |
| `installGlobalActionsHook` | 2675 |
| `scheduleSoftGlassThemeActivation` | 2761 |
| `installSoftGlassThemeSystemUiHook` | 2773 |
| `installSoftGlassThemePluginHook` | 2797 |
| `installSoftGlassThemeClassLoadGuard` | 2841 |
| `installSoftGlassThemePluginClass` | 2859 |
| `installDefaultThemeStateGuard` | 2903 |
| `forceThemeUtilsFlags` | 2934 |
| `installSoftGlassThemePluginMaterialGuards` | 2946 |
| `installStatusBarVisibilityHook` | 2990 |
| `isControlCenterEditButtonView` | 3076 |
| `logControlCenterEditButtonHidden` | 3083 |
| `installClockMaterialLimitHook` | 3103 |
| `installDynamicIslandHooks` | 3151 |
| `installMediaSourceIconHooks` | 3174 |
| `applyMediaCardAdjustments` | 3211 |
| `findDynamicIslandField` | 3248 |
| `installFocusIslandWhitelistPluginHooks` | 3264 |
| `isFocusWhitelistBypassEnabled` | 3402 |
| `allowIslandStateCallbackPackage` | 3406 |
| `installFocusIslandWhitelistSystemUiHooks` | 3430 |
| `installDynamicIslandClassDiscovery` | 3491 |
| `installDynamicIslandBackgroundHooks` | 3619 |
| `installDynamicIslandSelfBlurHook` | 3646 |
| `installDynamicIslandExpandedMaterialHook` | 3672 |
| `applyExpandedIslandNativeMaterial` | 3686 |
| `expandedIslandBlurRadius` | 3712 |
| `applyExpandedIslandBackground` | 3718 |
| `findDynamicIslandBackground` | 3725 |
| `findViewRoot` | 3741 |
| `isDynamicIslandView` | 3747 |
| `rememberSoftGlassBaseline` | 3799 |
| `volumeGlobalGlassSelected` | 3804 |
| `isSoftGlassSurface` | 3806 |
| `isSoftGlassPanelLayer` | 3816 |
| `isSoftGlassBlendSurface` | 3821 |
| `usesStandaloneSoftGlass` | 3827 |
| `updateStandaloneSoftGlass` | 3831 |
| `softGlassBlendPoints` | 3842 |
| `installShadeMaterialHooks` | 3847 |
| `installLockscreenClockColonHook` | 4051 |
| `installLockscreenCarrierHideHook` | 4076 |
| `installLockscreenBigClockWidthHook` | 4174 |
| `installLockscreenBigClockEditorWidthHook` | 4238 |
| `installLockscreenClockHorizontalRectHook` | 4266 |
| `installLockscreenClockHorizontalEditorHook` | 4317 |
| `handleLockscreenClockHorizontalTouch` | 4439 |
| `applyEditorClockHorizontalPreview` | 4493 |
| `clearEditorClockHorizontalPreview` | 4510 |
| `findAllInOneEditorForWallpaper` | 4514 |
| `findAllInOneEditorInObject` | 4523 |
| `applyEditorClockHorizontalOffset` | 4545 |
| `forEachEditorClock` | 4570 |
| `isAllInOneClock` | 4585 |
| `findField` | 4594 |
| `moveEditorFrame` | 4605 |
| `widenedClockRect` | 4615 |
| `installAodClockMaterialLimitHook` | 4626 |
| `installClockMaterialCapabilityHook` | 4736 |
| `installThemeManagerEditorClockMaterialHook` | 4760 |
| `restoreRequestedClockWidth` | 4803 |
| `installThemeManagerClockMaterialLimitHook` | 4844 |
| `installFocusNotificationMaterialEnforcementHooks` | 4943 |
| `applyNormalMediaNotificationEffect` | 5076 |
| `applyNormalNotificationRowEffect` | 5106 |
| `isFocusNotificationRow` | 5132 |
| `installFocusNotificationBackgroundHook` | 5150 |
| `notificationTuningFor` | 5214 |
| `materialTuningFor` | 5245 |
| `notificationTypeFor` | 5254 |
| `controlCenterTuningFor` | 5291 |
| `logControlCenterMaterialHit` | 5304 |
| `shouldUseNormalNotificationMaterial` | 5317 |
| `normalNotificationGlassParams` | 5327 |
| `normalNotificationBlendColors` | 5362 |
| `normalNotificationBlendPoints` | 5387 |
| `getColorByName` | 5398 |
| `getIntegerByName` | 5401 |
| `isNotificationMaterialCall` | 5404 |
| `notificationOnKeyguard` | 5412 |
| `isExpandedNotificationShade` | 5459 |
| `mediaHeaderOnActiveKeyguard` | 5473 |
| `hasCustomizedNotificationTuning` | 5490 |
| `isNotificationRowBackground` | 5493 |
| `isMediaNotificationView` | 5501 |
| `hasAncestorClass` | 5516 |
| `readInstanceField` | 5521 |
| `shadePanelTuning` | 5536 |
| `isControlCenterCall` | 5543 |
| `isNotificationCenterCall` | 5547 |
| `isShadeBlurProviderCall` | 5557 |
| `isShadeBackgroundView` | 5567 |
| `isShadePanelBackgroundCall` | 5586 |
| `stackContainsClass` | 5589 |
| `stackContains` | 5592 |
| `glassTuning` | 5597 |
| `applyGlassTuning` | 5607 |
| `applyBlendColorTuning` | 5618 |
| `scaleBlur` | 5627 |
| `installDepthEffectHook` | 5630 |
| `installDepthAvoidanceBypass` | 5678 |
| `isUserHierarchyEnabled` | 5711 |
| `installLockscreenShortcutGlassHook` | 5717 |
| `installLockscreenWidgetSceneVisibilityHooks` | 5772 |
| `installLockscreenPinCircleBackgroundHook` | 5962 |
| `installLockscreenPinCircleBackgrounds` | 5988 |
| `configureLockscreenPinLabels` | 6085 |
| `applyLockscreenPinRowSpacing` | 6102 |
| `installLockscreenMiniPlayer` | 6119 |
| `scheduleLockscreenMiniPlayerInstallation` | 6180 |
| `installLockscreenWidget` | 6201 |
| `scheduleLockscreenWidgetInstallation` | 6256 |
| `installLockscreenMusicLockscreenHook` | 6275 |
| `installLockscreenMusicLockscreen` | 6335 |
| `resolveLockscreenShortcutViews` | 6362 |
| `miniPlayerAppearance` | 6376 |
| `applyMiniPlayerMaterial` | 6465 |
| `commonShortcutParent` | 6496 |
| `installShortcutGlassBackgrounds` | 6511 |
| `findShortcutContainers` | 6641 |
| `applyLockscreenShortcutGeometry` | 6656 |
| `applyShortcutIconColorMode` | 6693 |
| `applyLockscreenWidgetBatteryMaterial` | 6710 |
| `applyLockscreenWidgetShortcutSurfaceMaterial` | 6792 |
| `idName` | 6941 |
| `applyLegacyBackdropMaterial` | 6950 |
| `applySystemGlassMaterial` | 6987 |
| `shortcutBackgroundMode` | 7037 |
| `shortcutIconColorMode` | 7046 |
| `installLockscreenClockDateFollowHook` | 7057 |
| `syncLockscreenClockDate` | 7102 |
| `scheduleLockscreenDateFollow` | 7140 |
| `syncLockscreenDateViews` | 7164 |
| `findLockscreenClockGlyphTop` | 7197 |
| `getClockPart` | 7212 |
| `readInheritedField` | 7220 |
| `isViewDescendantOf` | 7232 |
| `installLockscreenNotificationHook` | 7241 |
| `findFodNotificationPositionFlows` | 7309 |
| `installFodEnrollmentFlowOverride` | 7335 |
| `createFodEnrollmentFlowOverride` | 7359 |
| `createLockscreenNotificationPositionCollector` | 7393 |
| `adjustLockscreenNotificationPosition` | 7416 |
| `installLockscreenMediaNotificationHook` | 7452 |
| `installLockscreenMediaRowVisibilityHook` | 7487 |
| `installLockscreenMediaControllerVisibilityHook` | 7552 |
| `installLockscreenMediaManagerBridgeHooks` | 7593 |
| `installLockscreenMediaHeaderHook` | 7647 |
| `installLockscreenIslandArtworkBridge` | 7852 |
| `installLockscreenLyricButtonHook` | 7891 |
| `bindLockscreenLyricButton` | 8014 |
| `bindLockscreenLyricButtonForHolder` | 8094 |
| `bindLockscreenLyricButtonForController` | 8106 |
| `findRegisteredLockscreenMediaHeader` | 8120 |
| `applyEmptyLockscreenMediaButton` | 8129 |
| `refreshLockscreenLyricButtons` | 8143 |
| `maintainLockscreenLyricButton` | 8158 |
| `shouldShowLockscreenLyricButton` | 8186 |
| `installLockscreenLyricButtonStatusBarStateHook` | 8189 |
| `shouldOwnLockscreenLyricSlot` | 8226 |
| `findMiuiMediaHeaderAncestor` | 8238 |
| `lockscreenLyricsShowing` | 8248 |
| `setLockscreenLyricsShowing` | 8252 |
| `loadLockscreenLyricButtonDrawable` | 8260 |
| `tr` | 8285 |
| `installLockscreenCustomizationMenuHook` | 8311 |
| `installLockscreenMediaVisibilityProviderHooks` | 8340 |
| `installLockscreenMediaPipelineHook` | 8392 |
| `installTinyLockscreenMediaHook` | 8429 |
| `lockscreenMediaNotificationMode` | 8469 |
| `shouldHideLockscreenMedia` | 8480 |
| `shouldFilterLockscreenMedia` | 8501 |
| `applyLockscreenMediaPresentation` | 8506 |
| `updateLockscreenMediaHeaderVisibility` | 8539 |
| `animateLockscreenMediaHeaderContents` | 8629 |
| `centeredMediaTextTranslation` | 8678 |
| `requestLockscreenMediaControllerVisibility` | 8692 |
| `scheduleLockscreenMediaPresentation` | 8711 |
| `installLockscreenNativeClockScalerHook` | 8725 |
| `installLockscreenMediaEffectRefreshHook` | 8758 |
| `reapplyNativeMediaEffect` | 8792 |
| `readStateFlowBoolean` | 8822 |
| `installLockscreenMediaHeaderGuard` | 8838 |
| `installLockscreenMediaArtworkClick` | 8856 |
| `findLockscreenMediaHeader` | 8863 |
| `drawableBitmap` | 8872 |
| `isLockscreenMediaView` | 8890 |
| `isMiuiMediaHeaderView` | 8897 |
| `isMediaRow` | 8906 |
| `isMediaEntry` | 8926 |
| `isKeyguardCoordinatorOnKeyguard` | 8978 |
| `installFingerprintIconVisualHook` | 8993 |
| `installSystemUiDepthHooks` | 9028 |
| `forceDepthImageThreshold` | 9080 |
| `installDepthDisplayStateHook` | 9096 |
| `clearDepthAvoidState` | 9125 |
| `isNotificationFodPositionLimitRemoved` | 9139 |
| `fingerprintHideMode` | 9150 |
| `installLockscreenFingerprintAnimationHook` | 9166 |
| `installLockscreenWhiteBarHook` | 9242 |
| `installGlobalGestureHandleHook` | 9312 |
| `isGlobalGestureHandle` | 9401 |
| `configureCustomGestureHandleMaterial` | 9408 |
| `drawCustomGestureHandle` | 9453 |
| `currentGestureHandleColor` | 9507 |
| `gestureHandleBottom` | 9512 |
| `syncGestureMaterialOverlay` | 9517 |
| `installGlobalGestureControllerHook` | 9647 |
| `installDimensionHooks` | 9689 |
| `isHeadsUpNotificationSurface` | 9730 |
| `installHeadsUpNotificationSoftGlassHooks` | 9738 |
| `installCornerRadiusHooks` | 9834 |
| `installKnownCornerRadiusHooks` | 9864 |
| `installThirdPartyWallpaperDepthHook` | 9890 |
| `installAodThirdPartyWallpaperDepthHook` | 9934 |
| `installVideoWallpaperGlassSupportHook` | 9995 |
| `installAodLockscreenTemplateLimitHook` | 10022 |
| `installAodEditorBackgroundHook` | 10095 |
| `applyAodEditorBackground` | 10157 |
| `captureCurrentEditorWallpaper` | 10177 |
| `findViewByClassName` | 10190 |
| `drawableToBitmap` | 10200 |
| `installVolumePanelHooks` | 10214 |
| `installBackgroundBlurRadiusDispatchHook` | 10239 |
| `installVolumeNativeParameterHooks` | 10288 |
| `logVolumeNativeParameterHit` | 10383 |
| `findVolumeLongPressPanel` | 10390 |
| `installLoadedVolumePanelHook` | 10414 |
| `installAospVolumePanelFallback` | 10508 |
| `findVolumeControllerRoot` | 10536 |
| `applyVolumePanelNativeTuning` | 10553 |
| `applyVolumePanelRootTuning` | 10558 |
| `countViewTree` | 10645 |
| `invokeMiBlurOutline` | 10652 |
| `invokeIfPresent` | 10673 |
| `invokeNumericSetter` | 10679 |
| `invokeTwoIntSetter` | 10700 |
| `invokeBooleanSetter` | 10712 |
| `readField` | 10721 |
| `writeField` | 10725 |
| `installLoadedCornerRadiusHook` | 10731 |
| `controlRadiusSettings` | 10773 |
| `controlRadiusView` | 10776 |
| `controlRadiusOffset` | 10787 |
| `resolveControlRadius` | 10796 |
| `updateControlOutline` | 10803 |
| `hookUnifiedRadiusSetter` | 10816 |
| `applyControlCenterDrawableRadius` | 10856 |
| `hookMaterialStyle` | 10877 |
| `hookDeviceCenterOutline` | 10899 |
| `replaceDimensionIfNeeded` | 10912 |
| `replaceDimensionPixelIfNeeded` | 10921 |
| `replacementDp` | 10930 |
| `resolveSliderView` | 10979 |
| `applySliderDrawableRadius` | 10997 |
| `entryName` | 11022 |
| `dpToPixels` | 11026 |
| `isControlCenterSliderPart` | 11029 |
| `isControlCenterSliderBackgroundPart` | 11036 |
| `volumeResourceName` | 11041 |
| `isVolumePanelSurface` | 11050 |
| `applyRoundedOutline` | 11080 |

顶层 `NotificationMaterialType`、`ClockHorizontalDrag`、`VolumeTuningSnapshot`、`LockscreenNativeClockScaler`、`ControlCenterSvgDrawable`、两个顶部按钮路径常量与 `gestureMaterialOverlays` 原样保留。未移动任何残余共享状态。
