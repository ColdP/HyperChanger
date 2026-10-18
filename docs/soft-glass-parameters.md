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
- 背景模糊度、缩放程度、不透明度、压暗均为 0…200%，100% 保持系统值。上一版标为“元素”的模糊/缩放实际上控制采样背景，本版保留这两个值并改名；丢弃上一版另外两个背景模糊/缩放值。更早的四组 MaterialOverride 不迁移。
- 模糊和缩放仅调整系统背景采样，不改变 View 或文字大小。不透明度缩放面板混色/纯色的原始 Alpha；压暗只作用于独立的窗口压暗层。两者在压暗层相乘，保留系统展开动画与透明零值，不修改内容视图的整体 Alpha。
- 玻璃光学参数统一作用于通知、控制中心、悬浮通知的系统玻璃配方，范围为相对调整 -100…200%，0% 保持原值，共 15 项。边缘宽度、边缘羽化、反射偏移、高光强度直接列于光学参数，不再提供高级参数分组。所有滑块支持点击数值精确输入、单项默认值以及整页恢复默认。
- “玻璃模糊”位于光学参数卡片最顶部，独立调节玻璃采样的双模糊半径，范围 0…200%，默认 100%。已删除之前对该滑块的单独说明，保留底层 Hook 和设置键。玻璃压暗强度仍为光学参数最后一项；内置预设均为 -100%。

### 玻璃模糊依据

`miuix/theme/token/GlassToken.java` 的 `Refract` 只有 `ior`，不存在单独的折射模糊字段；`BlurRadius` 则明确区分 `smallBlurRadius` 和 `bigBlurRadius`。HyperLight 的 `p72.t` 从系统 Token 读取这两个值，`ul1` 的 case 22 对 `View.setMiGlassBlurRadius` 的两个实参按相同比例缩放。两个系统界面反编译结果中的 `ShadeBlendBlurController.BlurProvider.applyGlassBlurBlurRatio` 同样将 small/big 半径传给该接口，普通背景模糊另走 `setMiBackgroundBlurRadius`。通知路径的 `NotificationUtil.applyElementViewBlend` 和 `BlurUtilsImpl` 也设置双半径，因此可覆盖悬浮通知。

这组参数是柔光玻璃本身的采样模糊，页面以“玻璃模糊”命名。双半径均按原始值缩放，保留零值和系统动画，背景模糊继续独立控制普通背景模糊与窗口 self blur。内部的 `refractionBlur`、`refraction_blur` 和 `SoftGlassRefractionBlurSettings.kt` 名称为兼容已有设置与预设而保留，底层 Hook 不变。首次升级从原先共享的模糊比例初始化，以保持已有自定义数值；恢复默认后为 100%。

### 边缘羽化依据

两个系统反编译结果中的 `miuix/theme/token/GlassToken.Shape` 均包含 `edge`、`edgePow`、`thickness` 和 `reflectOffset`。HyperLight 的 `p72.m` 将它们依次映射到 42 项 MiGlass 配方的 19、20、21、22 项，`q32.c` 对第 20 项做相对调整，`d32` 及资源将其称为 Shape Edge Power / 形状边缘幂。未在所查 Java 层发现独立的边缘高斯模糊或 feather API。

“边缘羽化”直接对应第 20 项 `shape.edgePow`，紧跟边缘宽度，调节原生边缘过渡曲线，不附加整个视图模糊。默认 0% 保留原值，使用与其他光学项相同的 -100…200% 相对调整，发生调整时将幂限制在 0.1…12，避免退化为零。它不改变边缘宽度（19）和光学厚度（21），复用现有配方 Hook、独立设置存储与远端同步。内置预设暂保留系统羽化值；底层着色器公式未包含在这些 Java 反编译结果中，因此具体软硬方向与观感需要真机确认。

## 预设

`SoftGlassPresets.kt` 定义独立的带版本 JSON 格式和自定义预设库；存储、远端同步仍由 `SoftGlassSettings.kt` 负责，不接入旧版材质预设序列化。

内置八档：通透玻璃、清澈玻璃、轻柔玻璃、均衡柔光、柔雾玻璃、磨砂玻璃、深度磨砂、高斯模糊。背景模糊比例依次为 0、15、30、55、80、110、150、200%；玻璃模糊依次为 70、85、100、120、140、160、180、200%；折射率调整依次为 +5、0、-10、-25、-40、-60、-80、-100%。通透档保留适度玻璃采样模糊，降低原先的折射增益，沿途逐步降低折射、厚度、边缘与高光。所有档位的玻璃压暗强度固定 -100%。在系统原始材质上相对计算，不覆盖明暗配方，因此具体效果随系统主题变化，名称表示调参方向，不代表已经完成设备观感标定。

预设格式升级为 version 3，在 glass 部分保存 `edge_feather` 和原有的 `refraction_blur`。“仅玻璃参数”包含新控制，“仅背景参数”保持当前玻璃模糊与边缘羽化不变。兼容 version 1/2 的 JSON、二维码和已保存预设库，缺少的边缘羽化补为 0%。version 1 完整预设仍将背景模糊比例映射到玻璃模糊以保留旧版双半径效果；仅玻璃预设缺少该值时使用默认 100%。version 2/3 必须包含玻璃模糊，version 3 必须包含边缘羽化；缺失、非整数或越界字段拒绝导入。

支持“玻璃与背景 / 仅玻璃参数 / 仅背景参数”三种范围。内置预设按所选范围应用，自定义/导入预设保留自身范围；都不会修改总开关。保存当前参数后可直接导出 JSON 或二维码 PNG，长按已有预设可导出、应用或删除自定义项。JSON 与二维码导入统一验证格式、版本、范围、完整字段、整数类型和参数边界；旧版材质预设不能混用。JSON 文件读取最多 16 KiB 字符，二维码图片按尺寸采样，UTF-8 保留多语言名称。

## 槽位与易用性改进

| 分组 | MiGlass 槽位 | 参数 |
| --- | --- | --- |
| 光学 | 4, 5, 6, 7, 14 | 明度混合、材质饱和度、混合亮度、压暗、内层不透明度 |
| 光学 | 32, 21, 24 | 折射率、光学厚度、反射强度 |
| 光学 | 33, 34, 35 | 采样背景饱和度、亮度、烧灼 |
| 光学 | 19, 20, 22, 28 | 边缘宽度、边缘羽化、反射偏移、高光强度 |

保留 HyperLight 的相对原值调整思路，将百分比明确化。非零值使用 `original × (1 + percent / 100)`；原值为零使用 `percent / 100` 的细微偏移，避免 HyperLight 零值分支的大幅跳变。仅发生调整的槽位按对应物理范围限制，默认数值不裁剪系统原配方。

没有照搬 HyperLight 将 25…27 标为方向光 X/Y/Z、29…31 标为角度等文案；`p72.m` 的明确映射是颜色及颜色混合曲线。没有暴露固定槽位 18、保留位 36…39 和按压遮罩 40…41，也没有提供会被误认为整个 View 透明度的“整体 Alpha”。

## 悬浮通知与运行时保护

通过唯一的 `View.setMiGlass` 拦截点处理系统/Compat 调用，包括悬浮通知的浅色和深色配方。与 HyperModifier 的背景视图目标一致，避免在 effect 返回后对已调整数组重复乘算。

保留现有“悬浮通知启用柔光玻璃材质”开关对 BlurEffect 的转换能力；原本已使用 GlassEffect 的通知保留其明暗配方。通知材质统一功能不在悬浮通知 GlassEffect 调用链中替换普通通知配方。删除旧版依靠全局 attach/visibility/background 回调强制重建普通通知玻璃的行为。

数组采用副本；不足 42 项、非有限数值和全零 clear 数组原样传递。small/big 模糊半径分别缩放，保留动画中的零值与两个半径的关系。已经调整的同一个数组再次传入时使用原始副本，避免重复乘算。

背景覆盖批量 `setMiBackgroundBlendColors`、旧版 clear/add 混色接口、`ShadeWindowBlurController` 的 `setMiSelfBlur` 和窗口背景色，以及 `ShadeSolidBackgroundController` 的纯色背景。沿系统调用栈确认背景层，保留混色模式和 RGB，不把通知内容作为透明度目标。批量接口调用旧版 add 时使用线程保护避免二次调整。每个可选系统 API 独立安装，某个 ROM 缺少接口时不阻断其他接口。

运行时缓存独立设置并监听偏好变化，避免在每帧反序列化。弱引用保存视图最近的系统原始 setter 参数，合并同批偏好变更后在主线程重新应用；关闭时重放原始值并清空缓存。仅刷新已挂载且已经经过这些接口的视图，不主动创建玻璃或强制重启 SystemUI。初次启用等待系统下一次应用材质，模块首次加载仍遵循框架的作用域加载时机。

## 验证

`tests/soft-glass/run.ps1` 使用真实悬浮通知明暗配方运行 JVM 回归检查，涵盖默认值、关闭、全零清除、短数组、扩展数组、非有限值、每项边界、零值细调、共享数组不被修改、模糊/缩放/Alpha 语义，以及八档预设顺序、三种范围、多语言 JSON 往返、库往返及损坏/越界/错误版本拒绝。

三份语言 JSON 同步并校验。APK 按仓库要求使用 ` .\gradlew.bat :app:assembleDebug` 构建。设备效果需要实际 HyperOS 验证；本次不执行安装、设备重启或 SystemUI 重启。
