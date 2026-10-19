# 超级岛模拟与背屏接入

## 编辑前证据与边界（2026-10-19）

反编译来源：`.reverse-tmp/SubScreen-RELEASE-1.0.2609181659`。

- `sources/m2/AbstractC0593a.java` 的业务资源映射包含 `foodDelivery` → `food_delivery`、`Logistics` → `template_layout`，没有当前文本、Call Kit、下载、运动、电影、预约、文件分享、待支付、验证码业务对应的原生模板。
- `sources/m2/AbstractC0596d.java:r` 将 `food_delivery` / `food_Delivery` 归一化为 `foodDelivery`；`:m` 校验应用集合和用户开关，模块应用不在预置注册表。
- `resources/assets/smart_assistant/food_delivery` 的 `manifest.xml` 读取 `param_v2.baseInfo`、`progressInfo` 和摘要态文本；`template_layout` 读取 `baseInfo`、`hintInfo`。两者可直接消费当前外卖和物流模板。
- 原生 `phone` 模板读取 `miui.rear.param.maml_config`，按钮固定向 `com.android.incallui` 发送接听/拒接/挂断/免提广播，不使用通知提供的模拟按钮，因此不向模拟通话开放该模板。
- `SmartAssistantNotificationListenerService` 忽略已有 `miui.focus.param` 的普通通知，不能只补 JSON 就宣称已接入；其静态 `d()` 保留背屏总开关、初始化与用户解锁判断。
- `o2/AbstractC0666c.java:k(Bundle)` 解析 `miui.rear.param`（直接或 `rear_param_v1` 包装）和 `miui.focus.param`；`q2/n.java` 从后者读取内容，并可读取 `miui.focus.pics/actions`。
- `U1/C0126r0.java:r` 是现有卡片添加/更新入口，参数为复合键、包名、通知 ID、通知 key、业务、`I.g` 描述、extras、解析结果、hasRear；内部保留锁、排序、弹出与刷新。`:l` 是通知移除入口。`:f2832F` 是其现有调度 Handler，`U1/AbstractC0123p0.f2818a` 为原单例。

计划归属：页面与偏好留在独立 `IslandSimulation*.kt`；`OthersHooks.kt` 新增独立的模拟通知背屏安装方法，仅目标 `com.xiaomi.subscreencenter`，使用该包 `defaultClassLoader`。`HyperSystemUiModule.kt` 只在既有该包分发点增加调用，继续拥有生命周期、分发和共享初始化。不改动框架分发及其他 hook 分类。

接入仅识别模块包名、模拟通知 tag、类型标记及允许的背屏业务，观察原通知监听回调后使用原 Handler/卡片入口，不改变全局应用白名单，不伪装外部包名。关闭开关后的重发和通知移除走原移除入口。没有远程偏好消费者，开关值按类型保存在独立模拟设置，发送时写入 extras。

风险：反编译混淆名与方法签名绑定此背屏版本；其他版本若不匹配应记录日志并跳过。背屏总开关/锁定策略保留，未进行设备验证；构建成功不代表背屏实际显示。

## 实现核对

- 使用 JADX `renamed from` 注释恢复实际运行名：`U1.C0126r0` → `U1.r0`，`U1.AbstractC0123p0.f2818a` → `U1.p0.a`，Handler `f2832F` → `F`，`f2.C0407a` → `f2.a`，`o2.AbstractC0666c` → `o2.c`。
- `I.g` 的三参数构造器首参实际为 `java.io.Serializable`，非 `String`；反射按真实签名解析。
- 安装前解析全部签名，三个监听回调使用各自独立 hook ID；每次回调先执行原方法一次，再处理模块自己的通知。没有修改其他应用的业务筛选和通知数据。
- 原生卡片单例、锁和 Handler 均复用，调用原 `r` / `l`，不复制卡片状态或刷新逻辑。重连扫描仍存活的模块通知，移除回调清除对应卡片；隐藏开关的电影模式无法生成背屏数据。

## 验证结果（2026-10-19）

- `tests/island-simulation/run.ps1` 通过：覆盖十一类型在电影/物流两种场景及背屏开关开启/关闭时的资格与 JSON 输出，仅外卖和物流可生成背屏数据。
- `git diff --check` 通过；`.\gradlew.bat :app:assembleDebug` 通过。
- APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
- 本轮读取设备日志后修复的是 SystemUI 按钮 JSON 标题缺失，未更改背屏 hook。未安装 APK、未重启服务，背屏实际显示、回调签名匹配与原生移除行为仍需设备验证。
