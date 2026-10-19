# HyperSystemUiModule 分批拆分审计与迁移表

## 后续功能修复：物理音量面板

物理音量面板的共享安装器、资源拦截与材质快照继续保留在原模块；本轮不做分类迁移。用户要求的静音/勿扰收起隐藏修复和独立展开圆角，以及目标、反编译依据、状态与验证结果记录于 [音量展开面板审计](volume-panel-expanded-controls.md)。

## 基线与约束

审计基线：`e8a39dfeaedf5019df864f470b6bea454ab161af`，原 `HyperSystemUiModule.kt` 共 16,294 行。下文行号均指该基线，便于在后续批次中追溯。本表先于第一批 Kotlin 修改形成。

本阶段只整理职责，不作性能优化，不修复原有逻辑，不新增偏好，不改变文案、Hook ID、反射目标、异常策略、调用顺序、进程范围或重试机制。没有改动 `HookSettings.kt`、语言资源、Xposed 入口注册、版本号及其他五类实现。

审计先追踪 `onSystemServerStarting`、`onPackageLoaded` 的进程分支与提前返回，再核对延迟任务、两个类加载发现入口、偏好监听、拦截回调的 `proceed` 路径，以及实例字段和 companion 状态的消费者。以下按实际调用关系归类，不能把名称中出现 controlcenter 的所有内容直接搬走。

## 全文件职责对照

| 原位置 / 功能组 | 后续归属 | 本轮处理与依赖依据 |
| --- | --- | --- |
| 89–257：通知类型、时钟拖动、音量快照、移动信号状态 / Drawable、原生锁屏时钟缩放器 | 分别为 Notification / Lockscreen / SystemUi / StatusBar | 保留；顶层声明分别服务其消费方，不能跟随邻近的控制中心 Drawable 迁移 |
| 258–298：控制中心 SVG Drawable 与加号、电源路径 | ControlCenter | 保留，专用于尚未迁移的顶部按钮组 |
| 299：手势材质覆盖层 | SystemUi | 保留，与控制中心无专属关系 |
| 301–322、1747–2117：模块实例、系统与应用生命周期入口、安装标志 | 原模块入口；状态随具体功能迁移 | 保留入口；仅委托本批安装调用，不能新建统一 install 后重排原顺序 |
| 324–473：应用导航、MiShare 设备伪装 / AirDrop、小米健康表盘 | Others | 保留；目标是各应用进程，并非 SystemUI |
| 474–626：磁贴上下文 / 反射、5G 和 GMS 磁贴工厂、插件注入、代理状态 | ControlCenter；`readContext` 是共享反射工具 | 迁移完整磁贴组；保留锁屏也使用的 `readContext`，经只读回调注入 |
| 628–750、814–990、1622–1720：设置入口、设置范围、音效与标识 | Others | 保留；由 Settings / MiSettings / MiSound 分支安装 |
| 751–812：定时抬起亮屏 | Lockscreen | 保留，运行于 SystemUI，依赖传感器注册状态 |
| 991–1620：服务加载器、反优化工具、framework Hook、耳机判断 | Others；通用 Hook 工具共享 | 保留；system_server 两个入口、framework 安装保护及进程作用域不能改变 |
| 1722–1745：旧版控制中心 | ControlCenter | 本轮迁移，独立构造器 Hook；设置页偏好读取仍沿用原实现 |
| 2119–2337：HyperMusicCover 命令 / 手势 / 歌词同步、小爱输入 | Lockscreen / Others | 保留；广播桥与应用输入进程不并入控制中心 |
| 2339–2361：控制中心定时补装 | 共享安装编排，最终拆分前留在入口 | 保留六次定时任务；只把三项已有头部 Hook 调用委托到新类，灵动岛调用保留原位置 |
| 2363–3535：编辑按钮、顶部按钮、触摸分发、展开收起、电源菜单捕获 | ControlCenter；电源菜单捕获涉及 SystemUi | 本轮保留完整耦合组，详见风险表 |
| 3537–4680：背屏音乐白名单、背屏组件、负一屏商店、主题背屏保护 | Others | 保留；SubScreenCenter / PersonalAssistant / ThemeManager 分支，不因“panel”或“widget”名称而归入控制中心 |
| 4682–4909：全局主题后软玻璃保护、插件主题状态和材质守卫 | SystemUi，共享控制中心材质 | 保留；系统与插件延迟初始化、主题激活及类加载守卫是一组 |
| 4911–5362：全局可见性、电池文字 / Drawable、状态栏主题保护 | StatusBar，夹有 ControlCenter 和 Notification 分支 | 保留；同一个 `View.setVisibility` interceptor 含编辑按钮、通知小窗条及信号隐藏，不能重复注册或移动分支顺序 |
| 5364–5410：锁屏时钟材质限制 | Lockscreen | 保留；另有 AOD / ThemeManager 对应实现，不能只按所在位置归入状态栏 |
| 5412–5940：灵动岛外观、媒体卡片、迷你条、焦点白名单 | SystemUi / Notification | 保留；媒体来源图标也消费系统媒体卡片，白名单跨插件与 SystemUI |
| 5942–6070：插件类加载发现 | 原模块共享编排 | 保留一个发现器及原 ThreadLocal 重入保护；仅三项头部类发现转交新类 |
| 6072–6267：灵动岛背景、布局、原生材质、自模糊、底部光效 | SystemUi | 保留；沿用自身安装标志与材质状态 |
| 6269–6568：全局软玻璃状态 / 刷新、通知 / 控制中心 / 音量共享材质拦截 | SystemUi 共享基础设施 | 保留，避免多份基线缓存和重复全局拦截 |
| 6570–6688：锁屏时钟冒号、运营商隐藏 | Lockscreen | 保留；不能与已有 `ControlCenterCarrierHook` 的实例一起迁移 |
| 6690–6771、7525–8957：状态栏图标居左、移动网络标签、双卡堆叠信号及反射 / 布局辅助 | StatusBar；部分工具共享 | 保留；弱引用展示状态、网络回调与全局可见性共同消费 |
| 6773–6809：通知小窗条 | Notification | 保留，另被全局 visibility Hook 兜底 |
| 6811–7523：锁屏大时钟宽度 / 横向位置、编辑器预览、AOD / 主题材质能力 | Lockscreen | 保留；包含编辑器触摸及位移恢复，不能按系统界面包名误归类 |
| 8959–9732：焦点 / 普通 / 媒体通知材质、通知限制、锁屏 / 通知中心判断 | Notification；含 ControlCenter 材质选择支路 | 保留；`materialTuningFor` 按通知 / 控制中心调用栈选择配方，不能仅迁移 `controlCenterTuningFor` |
| 9734–9826：面板配方选择、调用栈判断、玻璃与混色变换 | SystemUi 共享材质工具 | 保留；同时处理两种下拉面板，并非控制中心专属 |
| 9828–10532、10559–10584：景深、快捷入口、PIN 背景、组件、旧迷你播放器 / 音乐锁屏安装 | Lockscreen | 保留；未启用的旧安装路径本轮也不删除或重新启用 |
| 10534–10557：信号颜色 / tint / filter 解析 | StatusBar | 保留；虽位于锁屏方法之间，实际由移动信号展示消费 |
| 10586–11463：锁屏快捷入口、播放器外观、组件材质、日期跟随及通用反射工具 | Lockscreen；底层材质共享 | 保留；`applySystemGlassMaterial` 还读取控制中心加载器缓存 |
| 11465–13215：锁屏通知位置、媒体显示 / 歌词按钮 / artwork / pipeline、翻译缓存 | Lockscreen，与 Notification 交叉 | 保留；锁屏媒体与通知行存在共同生命周期，不随控制中心媒体面板迁移 |
| 13217–13570：指纹、景深阈值、充电 / 底部文字、锁屏白条 | Lockscreen | 保留 |
| 13572–13947：全局手势条、材质覆盖层与控制器 | SystemUi | 保留；与锁屏白条是不同安装组 |
| 13949–14045：资源尺寸、通知颜色、悬浮通知识别 / 状态 | StatusBar / Notification / SystemUi 共享 | 保留；尺寸转换还覆盖音量与灵动岛 |
| 14047–14141：悬浮通知软玻璃 | Notification | 保留；共享材质再入保护不拆 |
| 14143–14202：圆角类发现和预加载目标 | ControlCenter + SystemUi 音量共享编排 | 保留；同一次 ClassLoader 回调安装两类功能 |
| 14204–14327：锁屏底部文字生命周期、View 反射 | Lockscreen / 共享工具 | 保留 |
| 14329–14651：第三方 / 视频壁纸景深、AOD 模板与编辑器背景 | Lockscreen | 保留；AOD / ThemeManager 两个进程路径继续共用原实现 |
| 14653–15168：音量面板、长按、原生参数、fallback、反射 setter | SystemUi | 保留；`controlcenter` 插件中的二级音量圆角与系统音量面板不能混同 |
| 15170–15349、15418–15459、15468–15478：控制中心圆角、基线 / 轮廓、滑条识别 | ControlCenter | 保留；依赖上述共享发现器与原生背景拦截 |
| 15351–15416、15461–15466、15480–15534：尺寸替换、资源名、音量 surface、轮廓等工具 | 多类共享 | 保留，不为迁移扩大可见性 |
| 15536–16294：常量、安装标志、锁 / 弱引用集合 / 缓存 | 随真实消费者归属 | 本轮选中组没有此处专属常量或 companion 状态；原全部保留 |

## 本批精确迁移表

| 原实现 | 新位置 | 处理方式 / 行为不变量 |
| --- | --- | --- |
| 三个 lazy 字段：`controlCenterHeaderHeightHook`、`controlCenterCarrierHook`、`controlCenterClockHook`（302–304） | `ControlCenterHooks` 的三个 private lazy 字段 | 不改三份已有 Hook 文件；各自仍按原顺序独立初始化 |
| 三项头部 `.install` 调用（1840–1842、2348–2350） | `ControlCenterHooks.installHeaderHooks` | 在原包回调和每个定时补装点分别委托；不吞异常，不新增安装保护，不移动灵动岛 / 磁贴调用 |
| 三项头部 `.onClassLoaded` 调用（5960–5962） | `ControlCenterHooks.onHeaderClassLoaded` | 在原 ThreadLocal try/finally 内调用，不新建类加载 Hook，不合并其他发现支路 |
| `customTileHookInstalled`、`customPluginTileHookInstalled`、`customTileRetryScheduled`（319–321） | 新类 private 实例字段 | 原为模块实例状态，迁移后仍每个模块实例一份，不改为 object / companion |
| `invokeNoArgResult`（474–476） | 新类 private 方法 | 只有磁贴注入 / 状态构造使用；原文件删除 |
| `findTileInterface`（489–495） | 新类 private 方法 | 保留递归接口 / 父类查找 |
| `installCustomTileHooks`（497–544） | 新类安装入口 | 保留工厂成功 / 缺失路径、1500ms 递归补装、标志写入位置、stock 列表拦截 |
| `installPluginTileHook`、`injectPluginTile`（546–574） | 新类 private 方法 | 保留反优化后安装、Map 原地注入、宿主和类加载器选择 |
| `proxyTile`（576–619） | 新类 private 方法 | 保留状态实例、回调列表、刷新时机、代理返回值、点击 / 长按 / 图标 fallback；仅共享上下文读取改为回调 |
| `loadTileDrawable`、`hasGms`、`isGmsEnabled`、`toggleGms`、`isUserFiveGEnabled`、`setUserFiveGEnabled`（621–626） | 新类 private 方法 | 随完整功能迁移；不改包名、资源 ID、设置 key、默认值和系统 API fallback |
| `installOldControlCenterHook`（1722–1745） | 新类安装入口 | 保留设置判断、两个目标及字段、构造器 AFTER 修改、每类 runCatching；原生命周期外层 runCatching 不变 |

磁贴组需要的字符串 / 包名 / 反射名当前均为方法内字面量，会随方法一并迁移；没有遗留在原文件的磁贴专属常量。两个已有磁贴偏好 key 定义在 `HookSettings.kt`，同时被加载 / 保存消费，并非原模块专属常量，本轮不迁移或重复定义，也不修改其序列化路径。

## 保留依赖与延期风险

| 依赖 | 当前事实与风险 | 本轮决策 |
| --- | --- | --- |
| `readContext`（478–487） | 磁贴和 `adjustLockscreenNotificationPosition`（11662）共用同一反射方法 | 原实现保留 private，通过 `(Any) -> Context?` 只读回调注入；不复制反射实现 |
| `systemUiApplicationContext`（16101） | companion 的 volatile 状态，由媒体 header 绑定更新（12281），锁屏 / 音乐广播也读取 | 使用 `() -> Context?` 实时读取；不能在新类构造时拍快照，否则首次为空后永远取不到后续值 |
| `hookDeoptimized`（1060–1062） | framework、通知、锁屏、状态栏均用同一个“尝试 deoptimize 后仍 hook”的工具 | 仅此方法由 private 改为 internal，供新类复用；不复制工具，不暴露状态，不改变实现 |
| 顶部按钮与电源菜单 | 编辑控制器、按钮 View、触摸状态、展开进度、debounce、global-actions 目标共用锁和发现器 | 暂不迁移，先保持完整运行链，避免只搬安装方法造成双重状态 |
| 编辑按钮 visibility 兜底 | 与状态栏 / 通知小窗隐藏共处 `View.setVisibility` Hook；还有 attach 拦截 | 暂不迁移，不能拆出第二个全局拦截而改变链的次序 |
| `controlCenterClassDiscoveryInProgress` | 一个 ThreadLocal 同时保护头部 Hook、灵动岛、锁屏人脸和其他插件发现 | 原 try/finally 与外层注册全部保留；新类只接收原位置的三项头部通知 |
| 控制中心加载器缓存 | 顶部按钮写入，`applySystemGlassMaterial`（11219–11221）用于锁屏快捷入口、组件、手势及按钮材质 | 暂不迁移状态；直接扩大锁 / 字段可见性会把原模块内部的状态协议变成跨类协议 |
| 圆角 / 材质 / 尺寸 | `installCornerRadiusHooks` 同时发现控制中心圆角与音量面板；配方、背景 setter、资源尺寸由多类消费 | 本轮不搬，后续应先设计保持单次注册、调用顺序和基线所有权的分发边界 |

因此首批选择旧版控制中心 + 完整磁贴组，并统一已独立头部 Hook 的持有与委托。其他五类不创建占位文件，控制中心剩余耦合组也明确留待后续批次。

## 验证与实机回归边界

实施后核对：被迁移的方法无原文件残留实现；所有原调用点都有委托；三项 lazy / 三项实例标志只存在于新类；移动的方法体除模块 Hook 接收者与两项上下文依赖外保持一致；原模块其余代码无改动。构建只运行 `.\gradlew.bat :app:assembleDebug`。

编译与静态一致性不等于实机功能验证。设备回归应覆盖：旧版控制中心开关；5G 的各图标模式、状态刷新、点击与长按；GMS 已安装 / 未安装与启停；插件回调缺失 / 延迟加载、1500ms 补装；宿主拿不到 Context 后媒体绑定更新的 fallback；头部高度 / 运营商 / 大时钟在冷启动和插件延迟加载时的行为。不得为验证自行安装 APK 或重启 SystemUI。

## 本批验证记录

- 方法体对照通过：完整旧版控制中心组、完整磁贴组、专属反射辅助、三个状态字段和三个 lazy 持有者，除表中明确的接收者 / 依赖替换外与 Git 基线逐段一致。
- 将审定的删除、委托与唯一共享工具可见性调整应用到基线后，与当前原模块全文一致（仅归一化 CRLF / LF）；其余五类和尚未迁移的控制中心代码没有夹带修改。
- 已核对原文件不再含选中组的安装 / 代理 / 反射 / 5G / GMS 实现或专属状态。`HookSettings.kt`、三份已有头部 Hook、三份语言 JSON 均与基线一致；三份 JSON 解析通过。
- `.\gradlew.bat :app:assembleDebug`：`BUILD SUCCESSFUL in 23s`，编译与 APK 打包通过。迁移代码仍有原有未检查转换 / Java 空值互操作诊断，本轮未借机改变相关逻辑。
- `git diff --check` 通过；APK ZIP 完整性检查通过，`classes9.dex` 包含 `ControlCenterHooks` 类描述符。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，版本 `1.2.1 RC1`，122,561,613 字节。
- APK SHA-256：`a62ef6888d429dcc7f017142158913181c1603634832487bc0c650d59721ce8d`。
- 未执行设备安装或实机回归；本轮到此停止，不延伸到其他五类。

第二批通知拆分的独立审计、iOS 通知中心保留原因、迁移方法清单和验证记录见 [notification-hook-migration.md](notification-hook-migration.md)。
