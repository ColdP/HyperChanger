# 柔光玻璃参数调整

## 反编译与依据

分析 APK：`D:\ReverseProject\apks\HyperLight-1.2.2.API.102.-LiquidGlass.apk`。
结果按现有版本目录模式存放在 `.reverse-tmp/HyperLight-1.2.2.API.102-LiquidGlass/jadx/`，其中 `sources/` 为代码，`resources/` 为资源；同级保留 `jadx.log`、`temp/`、`cache/` 和 `config/`。

首次使用 JADX 1.5.3 输出全部源文件与资源，报告 16 处反编译错误；随后使用本地 JADX 1.5.6 的 `--show-bad-code` 补充输出，报告 103 处错误。当前结果包含反编译器的错误标记，不能当作可直接重新编译的源码。下列关键参数公式、槽位映射与系统调用链可以直接读取，未将无法还原的方法当作实现依据。

| 依据 | 已确认的行为 |
| --- | --- |
| HyperLight `defpackage/q32.java` 的 `b`、`c`、`v` | 全局参数；非零原值乘以 `1 + adjustment / 10`，零原值直接使用 adjustment；槽位按独立参数修改 |
| HyperLight `defpackage/d32.java` | 常用/高级分组；默认偏移 0、模糊比例默认 100；多个高级滑块共用很大的 -50…50 范围 |
| HyperLight `defpackage/p72.java` 的 `m` | 42 项 MiGlass 数组对应 blend、inner、shape、reflect、directionalLight、refract、blurBg 和 mask；18 固定为 1 |
| `systemui-jadx2/.../HeadsUpNotificationGlassEffect.java` 和 `HeadsUpNotificationGlassDarkEffect.java` | 浅色/深色使用不同的 42 项配方；均通过背景视图的 MiGlass setter 应用 |
| `systemui-jadx2/.../MiGlassCompat.java` | `setMiGlassCompat` 最终反射调用 `View.setMiGlass` |
| `systemui-jadx2/.../ShadeBlendBlurController.java` 的 `BlurProvider` | 玻璃有 small/big 两个模糊半径；背景模糊和背景采样缩放走另外两个 View setter |
| `systemui-jadx2/.../ShadeBlurController.java` 的 `start` | 面板背景提供器也可能以 shadeWindow、notificationPanel、controlCenterContainer 为接收者，不能只按 background 资源 ID 匹配 |
| `D:\HyperModifier-master/.../runtime/SystemUiRuntimeHooks.java` 的 `applyHeadsUpCustomizations` | 悬浮通知应修改 `row.getInjector().getBackgroundNormal()` 的玻璃，保留通知内容与交互 |

`systemui-jadx` 的同名 Compat 与悬浮通知类用于交叉确认。以上省略号分别对应 `sources/com/android/systemui/statusbar/notification/style/vieweffect/`、`sources/com/miui/systemui/util/` 和 `sources/com/miui/systemui/shade/blur/`。

## 新设置与界面

- 全部设置使用独立的 `soft_glass_parameters_v3_` 命名空间，由 `SoftGlassSettings.kt` 负责加载、保存和远端同步；不修改 `HookSettings.kt`。
- 不读取旧版四组 `MaterialOverride`，不迁移旧预设，移除旧预设入口和分元素/背景、通知中心/控制中心的材质编辑入口。
- “柔光玻璃参数调整”卡片位于“系统界面”页顶部，总开关在卡片首行。默认关闭；关闭时通过项目已有的淡入缩放动画隐藏参数和预设入口，Hook 直接透传系统值。关闭后保留数值，恢复默认时保留开关状态。
- 参数页面使用 `AppPage.header`，在标题栏组件底部显示带 Backdrop 的 `TabRowWithContour`，分为“玻璃参数调整”和“背景调整”。
- 背景模糊度、缩放程度、不透明度均为 0…200%，100% 保持系统值。上一版标为“元素”的模糊/缩放实际上控制采样背景，本版保留这两个值并改名；丢弃上一版另外两个背景模糊/缩放值。更早的四组 MaterialOverride 不迁移。
- 模糊和缩放仅调整系统背景采样，不改变 View 或文字大小。不透明度缩放面板混色/纯色的原始 Alpha，保留系统展开动画与透明零值，不修改内容视图的整体 Alpha。删除重复的背景压暗控制及其存储值，窗口层仅保留原始混色和不透明度缩放；玻璃光学参数里的“压暗强度”继续保留。
- 玻璃光学参数统一作用于通知、控制中心、悬浮通知的系统玻璃配方，普通光学项范围为相对调整 -100…200%，边缘羽化范围为 -200…100%，0% 保持原值，共 14 项。边缘宽度、边缘羽化、反射偏移、高光强度直接列于光学参数，不再提供高级参数分组。所有滑块支持点击数值精确输入、单项默认值以及整页恢复默认。
- “玻璃模糊”位于光学参数卡片最顶部，独立调节玻璃采样的双模糊半径，范围 0…200%，默认 100%。已删除之前对该滑块的单独说明，保留底层 Hook 和设置键。玻璃压暗强度仍为光学参数最后一项；内置预设均为 -100%。

### 玻璃模糊依据

`miuix/theme/token/GlassToken.java` 的 `Refract` 只有 `ior`，不存在单独的折射模糊字段；`BlurRadius` 则明确区分 `smallBlurRadius` 和 `bigBlurRadius`。HyperLight 的 `p72.t` 从系统 Token 读取这两个值，`ul1` 的 case 22 对 `View.setMiGlassBlurRadius` 的两个实参按相同比例缩放。两个系统界面反编译结果中的 `ShadeBlendBlurController.BlurProvider.applyGlassBlurBlurRatio` 同样将 small/big 半径传给该接口，普通背景模糊另走 `setMiBackgroundBlurRadius`。通知路径的 `NotificationUtil.applyElementViewBlend` 和 `BlurUtilsImpl` 也设置双半径，因此可覆盖悬浮通知。

这组参数是柔光玻璃本身的采样模糊，页面以“玻璃模糊”命名。双半径均按原始值缩放，保留零值和系统动画，背景模糊继续独立控制普通背景模糊与窗口 self blur。内部的 `refractionBlur`、`refraction_blur` 和 `SoftGlassRefractionBlurSettings.kt` 名称为兼容已有设置与预设而保留，底层 Hook 不变。首次升级从原先共享的模糊比例初始化，以保持已有自定义数值；恢复默认后为 100%。

### 边缘羽化依据

两个系统反编译结果中的 `miuix/theme/token/GlassToken.Shape` 均包含 `edge`、`edgePow`、`thickness` 和 `reflectOffset`。HyperLight 的 `p72.m` 将它们依次映射到 42 项 MiGlass 配方的 19、20、21、22 项，`q32.c` 对第 20 项做相对调整，`d32` 及资源将其称为 Shape Edge Power / 形状边缘幂。未在所查 Java 层发现独立的边缘高斯模糊或 feather API。

“边缘羽化”直接复用原反射强度槽位（24），反向映射为 `systemReflection × (1 − feather / 100)`，移除重复的“反射强度”滑块。0% 保持系统反射，50% 将反射减半，100% 关闭边缘反射；-200% 对应原反射强度 +200%，保持原滑块的完整可调范围，结果按 0…2 限制。系统原值为零时仍沿用原反射强度的小幅偏移规则。

不改变第 17、20、31 项曲线、形状、折射率、玻璃模糊和背景采样参数。折射率仍独立控制第 32 项。此处“羽化”表示通过降低边缘反射获得柔和外观，不代表空间高斯模糊。内置预设使用当前六档配方，不再额外叠乘旧反射强度。

本地键为 `edge_feather_inverse`。首次读取时将旧反射强度及旧 `edge_softness` 的组合换算为新值，之后移除旧键；原 `edge_feather` 形状曲线调整丢弃。预设 v1-v3 的反射强度取反，v4 的反射强度与旧羽化合并后换算，均按新范围限制并四舍五入，避免直接复用不兼容数值。

## 预设

`SoftGlassPresets.kt` 定义独立的带版本 JSON 格式和自定义预设库；存储、远端同步仍由 `SoftGlassSettings.kt` 负责，不接入旧版材质预设序列化。

内置六档来自 `D:\Downloads\HyperChanger-SoftGlass-Presets\A.json` 至 `F.json`，顺序固定为澄光、薄纱、浮影、朦光、凝雾、云隐，名称不再沿用旧预设。参数按文件原值写入，所有档位的玻璃压暗强度固定 -100%。恢复柔光玻璃默认参数时同样使用 -100%，其余参数恢复系统原值。在系统原始材质上相对计算，不覆盖明暗配方，因此具体效果随系统主题变化，名称表示调参方向，不代表已经完成设备观感标定。

预设格式为 version 5，固定保存 `glass` 与 `background` 两部分，取消范围字段和三种类型。`glass` 不再包含旧 `reflection` 字段，包含全部光学项、`edge_feather` 与兼容命名的 `refraction_blur`，`background` 仅含 `blur`、`scale`、`opacity`。应用时同时覆盖玻璃和背景，始终保留总开关状态。

兼容 version 1/2/3/4 的 JSON、二维码和已保存预设库。旧单独预设缺少的另一部分补系统默认值，随后按完整预设应用；旧版 `darkening` 验证后丢弃。旧版反射强度和羽化按上述规则迁移。version 1 完整预设仍将背景模糊比例映射到玻璃模糊；旧仅玻璃预设缺少该值时使用 100%。不兼容的旧版 MaterialOverride 预设不导入。

保存当前参数后可直接导出 JSON 或二维码 PNG，长按已有预设可导出、应用或删除自定义项。JSON 与二维码统一验证格式、版本、完整字段、整数类型和参数边界；新格式拒绝 `scope`、`darkening` 和旧 `reflection`。JSON 读取最多 16 KiB 字符，二维码图片按尺寸采样，UTF-8 保留多语言名称。

参数写入与自定义预设库写入独立：保留在导航栈中的参数页面不会将旧的预设快照写回。保存、删除和同步预设时重新读取本地库；本地已保存的库（包括删除后的空库）优先于可能滞后的远端副本，只有本地从未保存过库时才从远端恢复。参数变更也重新读取本地最新值，避免旧页面覆盖刚应用的预设参数。

## 槽位与易用性改进

| 分组 | MiGlass 槽位 | 参数 |
| --- | --- | --- |
| 光学 | 4, 5, 6, 7, 14 | 明度混合、材质饱和度、混合亮度、压暗、内层不透明度 |
| 光学 | 32, 21 | 折射率、光学厚度 |
| 光学 | 33, 34, 35 | 采样背景饱和度、亮度、烧灼 |
| 光学 | 19, 22, 28 | 边缘宽度、反射偏移、高光强度 |
| 光学柔化 | 24 | 边缘羽化（仅柔化反射，不改变形状或采样） |

保留 HyperLight 的相对原值调整思路，将百分比明确化。非零值使用 `original × (1 + percent / 100)`；原值为零使用 `percent / 100` 的细微偏移，避免 HyperLight 零值分支的大幅跳变。仅发生调整的槽位按对应物理范围限制，默认数值不裁剪系统原配方。

没有照搬 HyperLight 将 25…27 标为方向光 X/Y/Z、29…31 标为角度等文案；`p72.m` 的明确映射是颜色及颜色混合曲线。没有暴露固定槽位 18、保留位 36…39 和按压遮罩 40…41，也没有提供会被误认为整个 View 透明度的“整体 Alpha”。

## 悬浮通知与运行时保护

通过唯一的 `View.setMiGlass` 拦截点处理系统/Compat 调用。悬浮通知现在按提供的 SystemUI dex 采用简单路径：先调用 `NotificationRowBlurEffect`，再读取 `notification_glass_params_normal`，因此浅色和深色都会跟随系统普通通知的柔光玻璃参数；不再使用悬浮通知专用的两套硬编码配方。普通通知统一配方、统一混色及旧材质分类均排除悬浮通知，包括其消失动画与设置重放时的 `isHeadsUpState`。

“悬浮通知启用柔光玻璃材质”开关拦截普通 `HeadsUpNotificationBlurEffect` 以及两个 `GlassEffect`，将它们统一委托到上述 normal-resource glass 路径。清理时先清除 `NotificationRowBlurEffect` 的混色，再将同一参数数组设置为 material type 0，避免通知复用后残留玻璃。颜色资源 `notification_action_text_color`、`notification_time_color`、`optimized_heads_up_notification_text`、`notification_primary_text_color_light`、`notification_secondary_text_color_light` 和 `optimized_heads_up_notification_action_text` 在开关启用时统一返回 `#E8FFFFFF`。

数组采用副本；不足 42 项、非有限数值和全零 clear 数组原样传递。small/big 模糊半径分别缩放，保留动画中的零值与两个半径的关系。已经调整的同一个数组再次传入时使用原始副本，避免重复乘算。

背景覆盖批量 `setMiBackgroundBlendColors`、旧版 clear/add 混色接口、`ShadeWindowBlurController` 的 `setMiSelfBlur` 和窗口背景色，以及 `ShadeSolidBackgroundController` 的纯色背景。沿系统调用栈确认背景层，保留混色模式和 RGB，不把通知内容作为透明度目标。批量接口调用旧版 add 时使用线程保护避免二次调整。每个可选系统 API 独立安装，某个 ROM 缺少接口时不阻断其他接口。

运行时缓存独立设置并监听偏好变化，避免在每帧反序列化。弱引用保存视图最近的系统原始 setter 参数，合并同批偏好变更后在主线程重新应用；关闭时重放原始值并清空缓存。仅刷新已挂载且已经经过这些接口的视图，不主动创建玻璃或强制重启 SystemUI。初次启用等待系统下一次应用材质，模块首次加载仍遵循框架的作用域加载时机。

## 音量面板

独立的 `VolumePanelGlassSettings.kt` 存储并同步 `volume_panel_use_global_soft_glass`，不扩展 `HookSettings`。“启用材质调整”下新增“使用全局柔光玻璃参数”，启用后以已有淡入缩放动画隐藏音量自定义材质滑块，圆角继续独立显示和生效。

参考 HyperLight `r71.q` 仅对音量 `Util.isBionicsAdvancedMaterialEnabled` / `isAdvancedMaterialEffective` 返回值进行条件覆盖，让系统生成原生高级材质；不引用隐藏、尺寸、运动或布局 Hook。范围限定在音量组件，关闭音量材质或全局总开关时不强制开启高级材质。

全局模式保留 `MiBackgroundStyle.setBackgroundBlurRadius` 的系统实参，在最终 View setter 处统一应用玻璃配方、双模糊半径、背景模糊/缩放及混色不透明度。音量旧半径、透明度和玻璃强度 Hook 全部透传，控制器刷新只设置圆角；从自定义切换到全局时恢复此前改写的 Drawable Alpha，避免旧值残留。全局总开关关闭时使用系统原值，不退回隐藏的自定义数值。音量全局模式关闭后继续使用原来的独立音量材质设置。

全局配方读取 `notification_glass_params_normal`。音量 View 的资源属于 SystemUI 插件；插件无法解析该数组时，通过 `createPackageContext("com.android.systemui", 0)` 获取 SystemUI 资源并缓存配方，避免收起面板回退到带黑色内层的 `VOLUMPANEL_COLLAPSED_CLOSED_GLASS_TOKEN`（11…13 项为 0，14 项为 0.3）。展开面板原生使用白色内层，因此这一资源读取失败会造成收起状态额外偏暗。材质清除数组仍直接透传，退出全局模式恢复原始音量配方，而非通知配方。

全局模式下拖动音量的 `updateColumnH`、`updateVolumeColumnH`、`updateTempColumnH` 不再遍历视图树重设圆角。圆角只在初始化、显示、展开或原有列圆角回调中更新，面板遍历根据圆角与视图尺寸跳过相同的反射调用。音量设置使用偏好监听维护的内存快照；命中的音量 View 和资源名称使用弱引用缓存；已知音量的材质、混色、窗口背景等 setter 不再进入通知分类或调用栈检查。音量 42 项配方使用内容缓存，同一配方及设置复用结果；输入数组原地变化、设置变化或清除仍重新计算，不跳过系统 setter 或冻结音量动画。

## 验证

`tests/soft-glass/run.ps1` 使用真实悬浮通知明暗配方运行 JVM 回归检查，涵盖羽化全范围不改动反射强度以外的槽位、关闭反射不被重新开启、默认/关闭/全零清除、短数组/扩展数组/非有限值、边界、零值细调、共享数组保护、模糊/缩放/Alpha 语义，以及六档预设顺序、同时覆盖玻璃背景、v1-v4 迁移及反射反向映射、多语言 JSON/二维码往返、损坏/越界/错误版本拒绝。缓存测试验证 10,000 次相同配方复用数组、系统原地动画变化、设置变化、关闭和清除的正确性；这不是设备帧率测量。

三份语言 JSON 同步并校验，核对 APK 中打包的语言资源。APK 按仓库要求使用 `.\gradlew.bat :app:assembleDebug` 构建。原生 Hook 的设备兼容性、边缘柔化观感与悬浮通知动画需要实际 HyperOS 验证；不执行安装、设备重启或 SystemUI 重启。

`SoftGlassSettingsStoreTest.kt` 使用内存 SharedPreferences 驱动真实设置存储，覆盖保存和导入后由旧参数页写入、页面重建、滞后远端同步、跨页面删除、空库不复活及首次从远端恢复，预设库校验包含持久化后的重新读取。
