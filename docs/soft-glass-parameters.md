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
- 统一入口与页面标题为“柔光玻璃参数调整”。默认关闭；关闭后保留数值，恢复默认时保留开关状态。
- 仅模糊/缩放分为元素模糊度、背景模糊度、元素缩放程度、背景缩放程度，范围 0…200%，100% 保持系统效果。缩放修改的是系统已有的背景采样缩放，不改变 View 或文字尺寸；系统没有调用缩放或原值为零时，不主动制造缩放效果。
- 光学参数统一作用于通知、控制中心、悬浮通知的系统玻璃配方，范围为相对调整 -100…200%，0% 保持原值；常用 11 项，高级 19 项。所有滑块支持点击数值精确输入、单项默认值以及整页恢复默认。

## 槽位与易用性改进

| 分组 | MiGlass 槽位 | 参数 |
| --- | --- | --- |
| 常用 | 4, 5, 6, 7, 14 | 明度混合、材质饱和度、混合亮度、压暗、内层不透明度 |
| 常用 | 32, 21, 24 | 折射率、光学厚度、反射强度 |
| 常用 | 33, 34, 35 | 采样背景饱和度、亮度、烧灼 |
| 高级 | 0…3 | 明度曲线采样点 |
| 高级 | 10, 15…17 | 内层底部、白色混合、颜色混合、颜色曲线 |
| 高级 | 19, 20, 22, 23 | 边缘宽度、边缘曲线、反射偏移、反射提亮 |
| 高级 | 25…31 | 方向光 RGB、强度、白色混合、颜色混合、颜色曲线 |

保留 HyperLight 的相对原值调整思路，将百分比明确化。非零值使用 `original × (1 + percent / 100)`；原值为零使用 `percent / 100` 的细微偏移，避免 HyperLight 零值分支的大幅跳变。仅发生调整的槽位按对应物理范围限制，默认数值不裁剪系统原配方。

没有照搬 HyperLight 将 25…27 标为方向光 X/Y/Z、29…31 标为角度等文案；`p72.m` 的明确映射是颜色及颜色混合曲线。没有暴露固定槽位 18、保留位 36…39 和按压遮罩 40…41，也没有提供会被误认为整个 View 透明度的“整体 Alpha”。

## 悬浮通知与运行时保护

通过唯一的 `View.setMiGlass` 拦截点处理系统/Compat 调用，包括悬浮通知的浅色和深色配方。与 HyperModifier 的背景视图目标一致，避免在 effect 返回后对已调整数组重复乘算。

保留现有“悬浮通知启用柔光玻璃材质”开关对 BlurEffect 的转换能力；原本已使用 GlassEffect 的通知保留其明暗配方。通知材质统一功能不在悬浮通知 GlassEffect 调用链中替换普通通知配方。删除旧版依靠全局 attach/visibility/background 回调强制重建普通通知玻璃的行为。

数组采用副本；不足 42 项、非有限数值和全零 clear 数组原样传递。small/big 模糊半径分别缩放，保留动画中的零值与两个半径的关系。运行时缓存独立设置并监听偏好变化，避免在每帧反序列化。偏好变化不强制重建所有已经显示的表面，系统下一次应用效果时采用新值；重启提示由现有界面管理。

## 验证

`tests/soft-glass/run.ps1` 使用真实悬浮通知明暗配方运行 JVM 回归检查，涵盖默认值、关闭、全零清除、短数组、扩展数组、非有限值、每项边界、零值细调、共享数组不被修改，以及模糊/缩放语义。

三份语言 JSON 同步并校验。APK 按仓库要求使用 ` .\gradlew.bat :app:assembleDebug` 构建。设备效果需要实际 HyperOS 验证；本次不执行安装、设备重启或 SystemUI 重启。
