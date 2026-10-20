# 超级岛模拟

2026-10-19：将“超级岛&焦点通知模拟”升级为带标题栏的独立二级页面。

## 页面与存储

- 第一张卡片包含十一类超级岛下拉菜单及胶囊形“发送超级岛”按钮；第二张卡片随类型切换设置，类型名称使用卡片上方的 `SmallTitle`。
- 类型下拉菜单正下方新增“为模拟超级岛添加外圈光效”开关，默认关闭，对全部模拟类型生效。`IslandSimulationEffectsSettings.kt` 独立保存偏好文件 `island_simulation_effects` 的 `outer_glow` 键；仅由模块应用发送通知时读取并编码到 extras/JSON，不需要远程偏好同步，不扩展 `HookSettings`。
- 进度完成倒计时：5 / 10 / 15 / 30 / 60 / 90 / 120 / 180 秒，支持设置初始进度。
- `IslandSimulationPage.kt` 负责页面，`MainActivity.kt` 仅新增页面路由和入口，并将原弹窗收窄为普通通知模拟。
- `IslandSimulationSettings.kt` 独立拥有偏好文件 `island_simulation`、按类型隔离的草稿键及读写；从原 `notification_simulator` 读取用户自定义文本作为初始值，旧默认文案替换为新默认文案。
- 这些参数只由模块应用进程生成测试通知时消费，无 hook 端消费者，因此不需要远程偏好同步，不改动 `HookSettings` 或它的序列化。
- 三份语言文件同步新增 95 个翻译键；简体中文使用 `tr(...)` 的内置回退。
- 文本字段改为普通设置行，点击后弹窗显示字段标题、输入框、全宽恢复默认/保存/取消按钮。恢复默认仅修改弹窗草稿，保存才写入设置，取消放弃草稿。
- 支持的图片项目打开独立弹窗：左对齐圆角预览及文件名，未导入时仅显示对应文字；选择图片和清除并排，清除在无图时禁用，保存和取消为全宽按钮。选择/清除不立即写盘，保存后才生效；预约封面使用竖向预览。
- `IslandSimulationImageSettings.kt` 按类型和图片用途独立保存图片及文件名：文本图片、IM 头像、新图文图片、预约封面、识别图片、运动功能图标及各类型的摘要态图标。未导入时使用模块应用图标或原功能图标，Call Kit 仍以 CallRecording 为默认。保留此前文本图片的路径与文件名键兼容。
- 图片复制到应用私有目录并限制最长边 384px；不依赖源文件的长期 URI 权限。图片读写失败保留弹窗供重试。发送时转为共享 Bitmap，避免多张解码图片直接占用同一 Binder 消息。图片偏好没有 hook 端读取需求，无需远程偏好同步；背屏从通知 extras 获取图片。
- 导入图片发送前统一以抗锯齿 BitmapShader 绘制透明圆角，半径按官方规范取短边的 1/4，覆盖展开态、摘要态与背屏使用的自定义图片。原图片保持完整，已有导入图片重新发送即可生效，无需再次导入；弹窗预览同步采用短边 1/4 圆角。

## 协议与归属

参考：[展开态设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2142)、[摘要态设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2143)、[开发指南及其模板库](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)。模板库使用 2026-01-29 版本，检查了图形、按钮、计时器和进度组件字段。

| 类型 | 展开态模板 | 摘要态 |
| --- | --- | --- |
| 文本 | 1：文本组件 1 + 识别图形组件 3 | 图标 + 短文本 |
| Call Kit | 3：IM 图文组件 + 识别图形组件 2 | 图文 + 正计时 |
| 外卖 | 4：文本组件 2 + 识别图形组件 1 + 节点进度 | 图文 + 进度文本 / 图标进度环 |
| 进度 | 7：IM 图文组件 + 识别图形组件 1 + 横向进度 | 图文 + 进度文本 / 图标进度环 |
| 电影 / 物流 | 9 / 10：文本组件 2 + 图标 + 按钮组件 2 / 3 | 图标 + 短文本 |
| 运动健康 | 11：强调图文组件 + 图标 + 按钮组件 2 | 图文 + 数据文本 |
| 通话 | 12：IM 图文组件 + 按钮组件 1（普通按钮） | 图文 + 正计时 |
| 预约 | 19：封面组件 + 识别图形组件 1 + 按钮组件 5 | 图标 + 短文本 |
| 文件分享 | 18：新图文组件 + 识别图形组件 1 + 按钮组件 4 | 图标 + 短文本 |
| 订单待支付 | 17：新图文组件 + 识别图形组件 1 + 按钮组件 5 | 图标 + 短文本 |
| 验证码 | 16：新图文组件 + 按钮组件 1（文字按钮） | 图标 + 短文本 |

- `IslandSimulationPayload.kt` 负责纯 JSON 构造，沿用系统要求的 `miui.focus.param` / `param_v2` / `param_island` 协议键；界面不再使用旧名称。图片键和动作键分别由通知中的 `miui.focus.pics` 与 `miui.focus.actions` Bundle 解析。
- `IslandSimulator.kt` 在模块应用进程内调用原生 `NotificationManager`，由目标设备的 SystemUI 消费通知数据。背屏接入另见 [背屏审计](island-simulation-rear-screen.md)，没有迁移任何现有 hook 分类。
- `IslandSimulationReceiver` 为不导出的应用内接收器，用于结束通话/运动模拟、拒绝文件分享模拟和复制模拟验证码。预约/支付/文件接收/电影/物流按钮通过 Activity PendingIntent 打开模块应用，不发起真实订单、预约、支付或文件传输。
- 通话只模拟显示与计时，不建立真实通话；配送只模拟设置的进度，不产生真实订单。文本展开态与各类型摘要态使用对应的自定义图片，未导入时回退应用图标；运动功能图标和通话关闭图标使用模块已有资源。Call Kit 右侧识别图使用用户提供的 `D:\Downloads\CallRecording.svg`，原始路径和 Y 轴变换保留为 Android 矢量资源，通过 `pic` / `picDark` 分别提供黑色/白色图标。配送节点按官方 4 倍画布尺寸绘制。
- 所有按钮同时在 JSON 中提供 `actionTitle`，动作仍引用 `miui.focus.actions` 中的原 PendingIntent。设备插件的 V3 按钮 5 直接读取 JSON 标题，不能依赖 `Notification.Action.title` 补齐；详见 [崩溃修复记录](island-simulation-crash-fix.md)。

### 外圈光效

参考 `D:\HyperIsland-main\android\app\src\main\kotlin\io\github\hyperisland\xposed\template\renderer\IslandRenderer.kt:injectOuterGlow` 与 `islanddispatch\invoke\IslandDispatcherNotifier.kt`。开启时同时设置 `param_v2.outEffectSrc`、`param_v2.param_island.outEffectSrc` 和通知 extras 的 `miui.effect.src`、`miui.bigIsland.effect.src` 为 `outer_glow`，分别由系统展开态和大岛态消费，颜色沿用系统默认。关闭时不写光效字段。

`IslandSimulator.send` 每次发送读取一次开关，进度更新和完成态沿用同一值；修改开关后重新发送生效。实现只影响模块自身生成的模拟通知，SystemUI 侧仅补充以下字段透传，不修改全局光效策略。

#### 大岛光效透传修复：编辑前证据与归属

真机反馈展开态有光效、大岛态没有。同版本插件 `MiuiSystemUIPlugin-18.3.2.22.0` 的 `FocusNotificationController.setUpDynamicIslandDataBundle(StatusBarNotification): Bundle` 新建 Bundle 时只复制 `miui.effect.src`，没有复制 `miui.bigIsland.effect.src`；`DynamicIslandUtils.isGlowEffectEnabledForBigState$miui_dynamicisland_release` 只读取后者。`param_island.outEffectSrc` 不能代替该运行时字段。

参考 HyperIsland 的 `IslandOuterGlowHook.hookFocusExtrasBridge`：在上述原方法返回后透传大岛光效字段。由 `SystemUiHooks.kt` 拥有独立安装方法，在 `HyperSystemUiModule.installDynamicIslandHooks` 既有插件初始化路径调用，使用相同插件 ClassLoader，不新增包分发、发现器或定时补装。仅匹配模块包名、模拟通知 tag、类型标记与对应 ID，且源值必须为 `outer_glow`；关闭时不补写，不触及其他通知。原方法恰好调用一次，返回的原 Bundle 原地补充一个字段。安装去重按实际目标 Class 保留在该独立安装方法的所有者中。

此修复不读取远程设置、不改变全局 shader 开关、不持有通知/View、不改写动画与颜色，也不移动现有底部光效、材质、圆角或白名单实现。风险是插件私有方法签名随版本改变；匹配失败应记录日志并跳过，设备显示仍需后续验证。

实现核对：`SystemUiHooks.installIslandSimulationGlowBridge` 在原方法后补写同一个返回 Bundle 的 `miui.bigIsland.effect.src`，原方法调用一次；入口模块仅增加一条委托，按目标 Class 在锁内去重，注册成功后才记录已安装。`shouldBridgeIslandSimulationGlow` 从通知标记验证范围，hook 在读取标记前先排除其他包名/tag；没有迁移或重写其他已完成 hook。

验证：模拟测试通过，包含十一类型的允许路径、关闭/缺失/未知光效、其他应用、错误 tag、缺失/未知类型、类型与 ID 不符和 ID 越界；原有模板与 3 KB 上限回归通过。`git diff --check` 及 `.\gradlew.bat :app:assembleDebug` 通过（22 秒）；APK 路径同下方验证节。只读检查设备通知，未安装 APK 或重启 SystemUI；新增 hook 需模块重新加载后才能进行实际大岛光效验证。

## 进度更新

参考 `D:\ReverseProject\XThief\app\src\main\java\btm\m\xthief\x\IslandNotifier.kt` 的下载超级岛结构：横向进度使用 `colorProgress` / `colorProgressEnd`，大岛和小岛进度环使用 `colorReach` / `colorUnReach`。

每种类型使用隔离的通知 tag/id；进度在应用级协程中每 500 ms 按 `elapsedRealtime()` 计算进度，页面返回不会取消。初次发送和更新都不自动展开进度岛。重复发送先取消旧任务，清除通知或发布失败停止后续更新，完成后发布 100% 和“已完成”，不再调度。普通通知的 ID 分配、批次调度与图片处理保持原实现。

## 验证

- `tests/island-simulation/run.ps1`：通过。检查十一类模板必需字段、光效开启/关闭及完成态两层协议字段与内容一致性、各用途图片及默认资源回退、摘要态图标、Call Kit 深浅色图片键、两种电影/物流按钮模板、按钮 JSON 标题空值崩溃回归、背屏类型/场景/开关组合、通话计时基点、完成态三处进度、光效开关两种状态下最长允许中文输入的 UTF-8 数据量及全部 8 档倒计时的起点/终点/单调性。
- CallRecording 两个矢量资源的路径、Y 轴变换及黑白填色与源 SVG 核对通过；源图预览检查通过。
- 三份语言 JSON 解析通过，新增翻译均位于 `strings` 中，95 个键一致且无遗漏。
- `git diff --check`：通过。
- `.\gradlew.bat :app:assembleDebug`：通过；APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。

未执行设备安装或 SystemUI 重启，尚未验证 HyperOS 4 真机上的弹窗交互、文件选择器、模板渲染、岛权限/业务策略、后台调度与按钮交互。模拟计时依赖应用进程存活；进程被系统终止后不会持续更新，不新增前台服务或精确闹钟权限。
