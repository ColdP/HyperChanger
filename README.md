<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperchanger_full.png" alt="HyperChanger 图标" width="128">
</p>

<h1 align="center"><strong>HyperChanger</strong></h1>

<p align="center">随心塑造你的 HyperOS 4。</p>

<p align="center">
  <strong>简体中文</strong> |
  <a href="README_en.md">English</a> |
  <a href="README_ja.md">日本語</a>
</p>

<p align="center">
  <a href="https://github.com/ColdP/HyperChanger/releases">发布版本</a> |
  <a href="https://t.me/HyperChanger">Telegram 群组</a> |
  <a href="https://qm.qq.com/q/rQhbQbiYLe">QQ 群：429188055</a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/v/release/ColdP/HyperChanger?style=flat-square" alt="发布版本">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="平台">
  <img src="https://img.shields.io/badge/minSdk-35-blue?style=flat-square" alt="最低 SDK">
  <img src="https://img.shields.io/badge/targetSdk-37-blue?style=flat-square" alt="目标 SDK">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-yellow?style=flat-square" alt="许可证">
</p>

## 项目简介

HyperChanger 是面向小米 HyperOS 4 的现代 Xposed 模块，通过配套应用配置系统界面、通知与控制中心、锁屏、桌面、系统设置、背屏及受支持应用的外观和行为。模块需要 Root 权限，通过 Hook 修改现有组件。

当前源码版本以 [app/build.gradle.kts](app/build.gradle.kts) 中的 `versionName` 为准，README 不重复维护版本号。下载版本与更新说明请查看 [Releases](https://github.com/ColdP/HyperChanger/releases)。

项目仍处于测试阶段，主要适配 **HyperOS 4（Android 17）**。设备、地区、系统构建和应用版本的差异均可能影响功能；最低安装版本并不代表所有功能均受支持。

## 功能

### 通知、控制中心与音量面板

- 分别调整通知中心和控制中心背景，以及普通通知、媒体通知、焦点通知等元素的材质、模糊、透明度和圆角。
- iOS 风格通知中心：圆角壁纸面板、锁屏时钟与景深效果，可选择锁屏壁纸或不显示壁纸、隐藏清除按钮、调整通知底部偏移；暂不支持动态壁纸。
- 自定义通知中心标题区域的图标、文字、缩放与间距，以及清除按钮的样式和材质；可在通知中心添加锁屏左右快捷按钮，沿用原有操作与拖动手势，并单独保存快捷按钮模式的通知底部偏移。
- 自定义控制中心大时钟、日期间距、红色“1”、运营商文本与网络标识，以及顶部区域高度。
- 在“状态栏与控制中心 → 控制中心顶部按钮”中调整图标缩放、横屏显示及背景材质，支持纯色、高级材质和柔光玻璃；柔光玻璃可独立调参或跟随全局参数。
- 调整音量面板的模糊、玻璃强度、背景透明度、圆角与音量条宽高。
- 使用内置或用户预设，通过 JSON 或二维码分享通知与控制中心配置。

### 超级岛、状态栏与手势提示条

- 调整超级岛宽度、大小岛间距、收起状态的平滑轮廓和背景材质。
- 为展开的超级岛单独调整柔光玻璃参数，并可使用全局柔光玻璃设置。
- 移除部分焦点通知与超级岛白名单限制，调整通知背景和媒体卡片，并提供普通、焦点与媒体通知模拟。
- 自定义状态栏双排信号、网络类型文本、图标显示、缩放、位置和间距。
- 调整全局手势提示条的样式与尺寸。

### 锁屏、音乐与壁纸

- 调整锁屏时钟、水平位置、景深、充电文本、底部快捷方式、指纹图标与密码输入外观。
- 自定义锁屏编辑页背景、模板数量限制、锁屏胶囊及人脸解锁超级岛；提供高德地图导航与锁屏胶囊的联动。
- 编辑锁屏小组件：组合天气、电量、日出日落、步数、闹钟、日程和签名图片，支持排序及布局、颜色与材质调整。
- 音乐锁屏封面、迷你播放器与歌词显示；可在歌词库管理本地歌词。
- 配置趣味光栅动态壁纸，导出当前桌面与锁屏壁纸。
- 在应用全局主题后保留柔光玻璃效果，解除息屏时钟玻璃与叠加材质的 OTA 转换限制。

### 桌面与最近任务

- 为兼容的 HyperOS 4 Rust 桌面添加 Dock，支持纯色、高级材质、柔光玻璃与入场动画。
- 调整 Dock 高度、水平与底部边距、圆角、颜色、透明度和模糊。
- 桌面与 Dock 的柔光玻璃可调整玻璃模糊及光学参数，并可跟随全局参数；Dock 还可选择系统默认参数。文件夹、最近任务有各自的材质设置，可自定义文件夹列数、图标行间距及 Dock 图标垂直偏移。
- 隐藏最近任务清除按钮，可选择保留或移除按钮功能。
- Dock 需要同时启用系统框架和桌面作用域，初次使用需手动重启设备。

### 系统设置与背屏

- 自定义系统设置主页与设备页的背景、设备卡片、LOGO、机型图片及显示的设备参数，并添加模块入口。
- 自定义系统设置入口文案，解锁更多自动锁屏与刷新率选项，显示 Google 服务入口、已保存 Wi-Fi 的密码，并提供 BOSE 音效与徽标设置。
- 解锁部分背屏 AI 功能，管理导入的自定义背屏应用，选择背屏音乐控件可用的应用。
- 从背屏右侧向左滑动启动所选应用，并可强制允许所有 Activity 在背屏打开；应用本身仍可能需要适配。
- 自定义背屏壁纸数量上限，修复主题修改与背屏主题、壁纸授权流程的冲突；相关选项需要主题壁纸作用域。
- 在背屏启动网易云音乐原生迷你播放器（适配 9.5.37），支持固定的显示屏 1 投放流程；需要网易云音乐和背屏作用域，并在设备解锁后使用。
- 自定义截屏应用的背屏带壳截图聚焦版与完整样式，支持图片或 ZIP 压缩包导入、预览、命名保存与应用，以及 PNG 图片导出。

### 相机、相册、超级小爱与输入法

- 配置兼容相机的 Leica LCC UI、原生焦段保留及“一瞬”模式。
- 解锁部分相册编辑水印及 Xiaomi 18 系列编辑功能，实际可用性取决于相册与媒体编辑器版本。
- 为超级小爱悬浮输入框添加柔光玻璃效果，并独立调整材质参数。
- 移除超级小爱输入法的部分黑名单、剪贴板、AI 与语音安全限制。
- 调节输入法玻璃外观、颜色、圆角、透明度与模糊。

### 拨号、传送门与环境检测

- 自定义拨号盘背景模糊、不透明度、边框宽度与透明度，以及圆角偏移；需要“拨号与通话”作用域。
- 调整传送门背景材质、圆角、展开态高度、悬浮态整体与图标大小，可移除全屏半透明遮罩并保持开发者模式开启；柔光玻璃支持独立参数或跟随全局参数。
- 增强传送门中日英文本识别，支持在应用内下载、管理和删除本地 OCR 模型；使用增强识别前需完成模型下载。
- “其他 → 环境检测”提供实验性总开关，以及小米信任服务、手机管家中的相关检测调整。总开关默认关闭，子开关在其开启后显示；首次启用前需依次确认页面提示。相关选项需要对应作用域，不保证改变所有应用的环境检测结果。

### 其他应用与系统更新

- 自定义 QQ、Apple Music、微博、小红书、小米商城、小米钱包、小米运动健康、瑞幸咖啡、腾讯会议、招商银行、微信、拼多多、Instagram、X 和 GitHub 的底部导航，支持样式、标签、颜色及角标设置；可用效果依目标应用而异。招商银行适配基于 14.4.6，其他版本需验证。
- 移除小米运动健康表盘的 10 分钟试用限制。
- 强制启用小米互传中的隔空投送可用性检查；实际传输仍依赖系统组件、硬件及对端支持。
- 增加屏幕录制帧率与码率选项，自定义录制输出目录。
- 提供快速截屏与任意界面截屏。
- 提供多小窗、强制小窗、记住小窗状态与位置、贴边小窗后台活动、音量即时调整、移除高音量安全提示、强制深色模式、原生安装器及关闭每 72 小时强制输入锁屏密码等系统框架选项。
- 禁用系统更新、移除部分 OTA 限制，并自定义显示的系统与 SOTA 版本。

### 模块应用、语言与更新

- 基于 MIUIX 的设置界面，支持浅色、深色、跟随系统、多种底部导航样式与预测性返回动画；可启用 Monet 取色，并在系统配色、10 种内置主题和自定义颜色之间选择。
- 按设置项名称、描述或作用域搜索功能入口。
- 内置简体中文、英文和日文，支持语言包导入、导出与编辑。
- 通过 JSON 导入、导出模块设置，并在应用内管理需要重启的作用域应用。
- 从 GitHub 或 Gitee 检查模块更新，支持下载后手动安装或使用 Root 静默安装。
- 提供模块总开关、按功能分区的安全停用策略，以及模块日志级别、筛选、保留和 ZIP 导出。
- 在“设置 → 模块安全”提供运行中目标进程列表、手动热重载请求与逐进程结果。当前模块侧尚未完成热重载支持，此入口不能替代需要重启才能生效的操作。
- 日志 ZIP 附带 `environment.txt`，记录设备、系统、模块、框架信息及当前已勾选作用域中的应用版本，便于排查问题。

## 兼容性与要求

| 项目 | 说明 |
| --- | --- |
| 主要适配环境 | 小米 HyperOS 4（Android 17） |
| 最低安装版本 | Android 15（API 35）；不等同于完整功能兼容 |
| 编译 / 目标 SDK | API 37 |
| 模块框架 | Root 与支持现代 libxposed API 的 Vector / LSPosed；最低 API 102，目标 API 102 |
| 原生库架构 | 仅构建 `arm64-v8a`；预编译 `libhyperpatch.so` 位于 `jniLibs`，Dock 与文件夹补丁由 `src/main/cpp` 构建 |
| 可选组件 | 对应功能需要设备上存在兼容版本的目标应用或系统组件 |

应用会检测 `ro.mi.os.version.code`：未检测到 HyperOS 时退出，非 HyperOS 4 时提示风险。检测通过不代表所有 Hook 均与当前系统兼容。

框架适配依据为 [Vector canary-3114](https://github.com/JingMatrix/Vector/releases/tag/canary-3114)（API 102）的公开源码。首页、模块安全与日志环境显示实际连接框架的名称和版本；点击框架卡片可打开对应管理器。更旧版本是否兼容取决于其 API 实现，尚未完成 Vector 真机验证。

框架身份来自当前连接的 libxposed 服务；安装了管理器或启用了 Root 模块不代表 HyperChanger 已连接。点击首页框架卡片或模块安全页的框架信息，可打开对应的独立或寄生管理器。断开连接时会尝试识别启用中的框架；无法确定寄生管理器所属框架时只尝试独立管理器。

## 模块作用域

在 Vector / LSPosed 中启用模块，并按功能选择所需作用域。以下列表与 [scope.list](app/src/main/resources/META-INF/xposed/scope.list) 对应：

| 作用域 | 包名 / 标识 |
| --- | --- |
| 系统界面 | `com.android.systemui` |
| 系统框架 | `system` |
| 系统桌面 | `com.miui.home` |
| 壁纸 | `com.miui.miwallpaper` |
| 系统界面插件 | `miui.systemui.plugin` |
| 息屏显示 | `com.miui.aod` |
| 相机 | `com.android.camera` |
| 相册 | `com.miui.gallery` |
| 相册扩展插件 | `com.hyper.gallery.plugin` |
| 小米相册-编辑 | `com.miui.mediaeditor` |
| 屏幕录制 | `com.miui.screenrecorder` |
| 截屏 | `com.miui.screenshot` |
| 超级小爱输入法 | `com.xiaomi.type` |
| 剪切板与常用语 | `com.miui.phrase` |
| 设置 | `com.android.settings` |
| 系统更新 | `com.android.updater` |
| 背屏 | `com.xiaomi.subscreencenter` |
| 智能助理 | `com.miui.personalassistant` |
| 主题壁纸 | `com.android.thememanager` |
| 小米商城 | `com.xiaomi.shop` |
| 小米钱包 | `com.mipay.wallet` |
| 小米运动健康 | `com.mi.health` |
| Apple Music | `com.apple.android.music` |
| 网易云音乐 | `com.netease.cloudmusic` |
| 微博 | `com.sina.weibo` |
| 小红书 | `com.xingin.xhs` |
| 瑞幸咖啡 | `com.lucky.luckyclient` |
| 高德地图 | `com.autonavi.minimap` |
| 小米互传 | `com.miui.mishare.connectivity` |
| 小米妙享服务 | `com.xiaomi.xmsf` |
| 小爱语音 | `com.miui.voiceassist` |
| 小米声音 | `com.miui.misound` |
| 小米设置 | `com.xiaomi.misettings` |
| 腾讯会议 | `com.tencent.wemeet.app` |
| 招商银行 | `cmb.pb` |
| 微信 | `com.tencent.mm` |
| QQ | `com.tencent.mobileqq` |
| 拼多多 | `com.xunmeng.pinduoduo` |
| 小米信任服务 | `com.xiaomi.trustservice` |
| 手机管家 | `com.miui.securitycenter` |
| Instagram | `com.instagram.android` |
| 拨号与通话 | `com.android.contacts` |
| 传送门 | `com.miui.contentextension` |
| X | `com.twitter.android` |
| GitHub | `com.github.android` |

部分包并非每台设备都有。仅选择实际使用的作用域；声明作用域不保证目标应用的所有版本均受支持。

## 安装与使用

1. 从 [Releases](https://github.com/ColdP/HyperChanger/releases) 下载已签名 APK 并安装。
2. 在 Vector / LSPosed 中启用 HyperChanger，选择所需作用域。
3. 打开模块应用，完成环境检查并阅读首次使用说明与协议。
4. 按需开启功能。根据应用提示，手动使用应用内的作用域重启功能使设置生效。
5. 首次启用桌面 Dock 时，确认已选择系统框架与桌面作用域，并手动重启设备。

“设置 → 模块安全”中的“进程热重载”开关默认开启，用于控制手动请求入口。请求需要已连接且支持相应能力的 libxposed API 102 或更高版本框架。当前模块入口尚未实现允许重载与重建 Hook 的回调，因此不能将此功能视为完整可用的模块热重载；请继续按原提示重启相关应用或设备。不支持、失败或超时时请查看逐进程结果，超时后避免重复提交。

## 预设与语言包

- **通知与控制中心预设**：支持内置和用户预设，长按预设可导出 JSON 或二维码，也可从文件或二维码图片导入。
- **柔光玻璃预设**：支持内置与自定义参数预设、JSON 和二维码分享；选择跟随全局参数的表面会使用对应的全局柔光玻璃参数。
- **模块设置预设**：在模块“设置”页面通过 JSON 导入、导出多个配置组。导入时校验格式与版本；导出的配置不包含图片文件本体，部分图片引用字段也会跳过，迁移设备时请单独准备素材。
- **语言包**：内置中文、英文、日文及跟随系统选项。可参考 [languages/example.json](languages/example.json) 创建语言包，在应用内导入、编辑和导出。

不同类型预设使用各自的导入入口。导入后请检查配置，并按提示应用需要重启才能生效的选项。

## 故障排除

| 现象 | 建议操作 |
| --- | --- |
| 修改未生效 | 检查功能开关、对应 Vector / LSPosed 作用域与目标应用版本，按应用提示手动重启相关应用。 |
| Dock 未显示 | 检查系统框架和桌面作用域，确认使用兼容的 Rust 桌面，并已在首次启用后手动重启设备。 |
| iOS 风格通知中心无壁纸 | 检查壁纸来源设置；该功能暂不支持动态壁纸。 |
| 框架显示未连接或管理器无法打开 | 确认 Vector / LSPosed 已运行并启用本模块；离线识别寄生管理器可能需要 Root。无法识别时可从框架通知或已有快捷方式打开管理器。 |
| 热重载不可用或未成功 | 当前模块侧尚未完成热重载支持；确认框架连接、API 102 能力及目标进程状态，并查看结果。请按原提示重启，超时后避免重复提交。 |
| 系统界面反复崩溃 | 在 Vector / LSPosed 或 Recovery 中禁用模块，恢复后逐项排查设置及其他模块冲突。 |
| 某项功能不可用 | 系统或应用实现可能不兼容，请提供设备、系统及目标应用版本反馈。 |
| 预设导入失败 | 确认预设类型、导入入口与导出版本匹配，且文件或二维码完整。 |

提交 Issue 时请附上设备型号、Android / HyperOS 版本、模块与 Vector / LSPosed 版本、目标应用版本、作用域和复现步骤。可从“设置 → 模块安全”导出日志 ZIP，附上相关 Logcat 日志；分享前请检查并移除日志及环境信息中的个人信息。

## 从源码构建

环境要求：JDK 21（与 CI 一致）、Android SDK 37、NDK r28c（28.2.13676358）、CMake 3.22.1，以及支持 Android Gradle Plugin 9.2.1 的 Android Studio 或项目 Gradle Wrapper（9.6.1）。[app/build.gradle.kts](app/build.gradle.kts) 将 NDK 路径固定为仓库内的 `.build-tmp/android-ndk-r28c`；构建前需在该路径准备 NDK，或建立指向已安装 NDK 的目录链接。可参考 [CI 配置](.github/workflows/android.yml)。

```bash
git clone https://github.com/ColdP/HyperChanger.git
cd HyperChanger
./gradlew :app:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

专项回归脚本位于 `tests/`，其中 `tests/vector-compatibility/run.ps1` 检查框架身份与离线识别边界。脚本依赖本地构建输出及相应工具链，具体路径见各脚本；主机测试和 APK 构建均不能代替设备验证。

Release 签名通过 Gradle 属性 `releaseStoreFile`、`releaseStorePassword`、`releaseKeyAlias`、`releaseKeyPassword` 配置，密钥应存放在仓库外。签名构建输出至 `app/build/outputs/apk/release/`，发布文件命名为 `HyperChanger-v<VersionName>.apk`，将版本号中的空格替换为连字符，例如 `HyperChanger-v1.2.1.apk`。不要提交密钥、密码或构建产物。

## 目录结构

```text
HyperChanger/
|-- app/
|   |-- src/main/java/       Kotlin / Java 模块与界面源码
|   |-- src/main/assets/     语言包、法律文档与第三方声明
|   |-- src/main/cpp/        Dock、文件夹等 ARM64 原生补丁源码
|   |-- src/main/jniLibs/    预编译 ARM64 libhyperpatch.so
|   |-- src/main/res/        Android 资源
|   `-- src/main/resources/  libxposed 入口、元数据与作用域
|-- languages/example.json   语言包模板
|-- gradle/                  Gradle Wrapper
|-- docs/                    功能实现与验证记录
|-- tests/                   专项回归测试与运行脚本
|-- README.md                中文主版
|-- README_en.md             英文版
|-- README_ja.md             日文版
|-- CONTRIBUTING_zh.md
|-- NOTICE
`-- LICENSE
```

## 贡献

欢迎提交问题与改进。参与前请阅读 [中文贡献指南](CONTRIBUTING_zh.md) 或 [English contributing guide](CONTRIBUTING.md)。

## 免责声明

HyperChanger 是社区开发的实验性模块，与小米无隶属或官方授权关系。系统级 Hook 可能造成界面异常、应用崩溃或无法正常启动，且兼容性会随系统与应用更新变化。测试前请备份重要数据，保留通过 Vector / LSPosed 或 Recovery 禁用模块的途径。使用风险由用户自行承担，项目不保证兼容任何特定设备或未来版本。

## 许可证与致谢

本组合分发依据 [GNU AGPL 第 3 版](LICENSE) 提供：HyperChanger 原创文件保留 AGPL-3.0-or-later 授权，导入的新版 HyperMusicCover 为 AGPL-3.0-only，组合分发采用第 3 版。历史授权、第三方版权和修改说明见 [NOTICE](NOTICE)。

- 音乐锁屏、歌词与锁屏胶囊更新自 [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) 2026-10-30 源码快照（AGPL-3.0-only，部分 Apache-2.0 / GPL-3.0）；保留的旧通知中心代码继续适用原 Apache-2.0 授权。导入文件哈希与边界见 [同步审计](docs/hypermusiccover-upstream-sync.md)。
- 收起岛平滑轮廓、大小岛间距及人脸解锁岛相关实现适配自 [HyperIsland 3.1.8](https://github.com/1812z/HyperIsland)（Copyright 2026 1812z，MIT）；完整声明见 [HyperIsland-MIT.txt](app/src/main/assets/licenses/HyperIsland-MIT.txt)。
- 感谢 MIUIX、AndroidLiquidGlass / Backdrop 及其他依赖项目；更多信息见应用内开源代码声明。

## Star History

<a href="https://www.star-history.com/?repos=ColdP%2FHyperChanger&type=date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=ColdP/HyperChanger&type=date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=ColdP/HyperChanger&type=date" />
    <img alt="Star History 图表" src="https://api.star-history.com/chart?repos=ColdP/HyperChanger&type=date" />
  </picture>
</a>
