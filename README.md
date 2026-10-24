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
  <img src="https://img.shields.io/badge/License-AGPL--3.0--or--later-yellow?style=flat-square" alt="许可证">
</p>

## 项目简介

HyperChanger 是面向小米 HyperOS 4 的 LSPosed 模块，通过配套应用配置系统界面、通知与控制中心、锁屏、桌面、系统设置、背屏及受支持应用的外观和行为。模块需要 Root 权限，通过 Hook 修改现有组件。

当前源码版本以 [app/build.gradle.kts](app/build.gradle.kts) 中的 `versionName` 为准，README 不重复维护版本号。下载版本与更新说明请查看 [Releases](https://github.com/ColdP/HyperChanger/releases)。

项目仍处于测试阶段，主要适配 **HyperOS 4（Android 17）**。设备、地区、系统构建和应用版本的差异均可能影响功能；最低安装版本并不代表所有功能均受支持。

## 功能

### 通知、控制中心与音量面板

- 分别调整通知中心和控制中心背景，以及普通通知、媒体通知、焦点通知等元素的材质、模糊、透明度和圆角。
- iOS 风格通知中心：圆角壁纸面板、锁屏时钟与景深效果，可选择锁屏壁纸或不显示壁纸、隐藏清除按钮、调整通知底部偏移；暂不支持动态壁纸。
- 自定义控制中心大时钟、日期间距、红色“1”、运营商文本与网络标识，以及顶部区域高度。
- 调整音量面板的模糊、玻璃强度、背景透明度与圆角。
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
- 隐藏最近任务清除按钮，可选择保留或移除按钮功能。
- Dock 需要同时启用系统框架和桌面作用域，初次使用需手动重启设备。

### 系统设置与背屏

- 自定义系统设置主页与设备页的背景、设备卡片、LOGO、机型图片及显示的设备参数，并添加模块入口。
- 解锁部分背屏 AI 功能，管理导入的自定义背屏应用，选择背屏音乐控件可用的应用。
- 自定义背屏壁纸数量上限，修复主题修改与背屏主题、壁纸授权流程的冲突；相关选项需要主题壁纸作用域。
- 在背屏启动网易云音乐原生迷你播放器（适配 9.5.37），支持固定的显示屏 1 投放流程；需要网易云音乐和背屏作用域，并在设备解锁后使用。
- 自定义截屏应用的背屏带壳截图聚焦版与完整样式，支持图片、压缩包和带附件二维码的导入导出。

### 相机、相册与超级小爱输入法

- 配置兼容相机的 Leica LCC UI、原生焦段保留及“一瞬”模式。
- 解锁部分相册编辑水印及 Xiaomi 18 系列编辑功能，实际可用性取决于相册与媒体编辑器版本。
- 移除超级小爱输入法的部分黑名单、剪贴板、AI 与语音安全限制。
- 调节输入法玻璃外观、颜色、圆角、透明度与模糊。

### 其他应用与系统更新

- 自定义 Apple Music、微博、小红书、小米商城、小米钱包、小米运动健康、瑞幸咖啡的底部导航样式、标签与颜色。
- 移除小米运动健康表盘的 10 分钟试用限制。
- 强制启用小米互传中的隔空投送可用性检查；实际传输仍依赖系统组件、硬件及对端支持。
- 增加屏幕录制帧率与码率选项，自定义录制输出目录。
- 提供快速截屏、任意界面截屏，以及腾讯会议底部导航适配。
- 禁用系统更新、移除部分 OTA 限制，并自定义显示的系统与 SOTA 版本。

### 模块应用、语言与更新

- 基于 MIUIX 的设置界面，支持浅色、深色、跟随系统、多种底部导航样式与预测性返回动画。
- 内置简体中文、英文和日文，支持语言包导入、导出与编辑。
- 通过 JSON 导入、导出模块设置，并在应用内管理需要重启的作用域应用。
- 从 GitHub 或 Gitee 检查模块更新，支持下载后手动安装或使用 Root 静默安装。
- 提供模块总开关、按功能分区的安全停用策略，以及模块日志级别、筛选、保留和 ZIP 导出。

## 兼容性与要求

| 项目 | 说明 |
| --- | --- |
| 主要适配环境 | 小米 HyperOS 4（Android 17） |
| 最低安装版本 | Android 15（API 35）；不等同于完整功能兼容 |
| 编译 / 目标 SDK | API 37 |
| 模块框架 | Root 与支持现代 libxposed API 的 LSPosed；最低 API 101，目标 API 102 |
| 原生库架构 | 当前仓库包含 ARM64 原生库（`libhyperpatch.so`、Dock 与文件夹补丁），桌面原生补丁依赖 `arm64-v8a` |
| 可选组件 | 对应功能需要设备上存在兼容版本的目标应用或系统组件 |

应用会检测 `ro.mi.os.version.code`：未检测到 HyperOS 时退出，非 HyperOS 4 时提示风险。检测通过不代表所有 Hook 均与当前系统兼容。

## 模块作用域

在 LSPosed 中启用模块，并按功能选择所需作用域。以下列表与 [scope.list](app/src/main/resources/META-INF/xposed/scope.list) 对应：

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
| 小米妙享服务 | `com.xiaomi.xmsf` |
| 小爱语音 | `com.miui.voiceassist` |
| 小米声音 | `com.miui.misound` |
| 小米设置 | `com.xiaomi.misettings` |
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
| 腾讯会议 | `com.tencent.wemeet.app` |

部分包并非每台设备都有。仅选择实际使用的作用域；声明作用域不保证目标应用的所有版本均受支持。

## 安装与使用

1. 从 [Releases](https://github.com/ColdP/HyperChanger/releases) 下载已签名 APK 并安装。
2. 在 LSPosed 中启用 HyperChanger，选择所需作用域。
3. 打开模块应用，完成环境检查并阅读首次使用说明与协议。
4. 按需开启功能。根据应用提示，手动使用应用内的作用域重启功能使设置生效。
5. 首次启用桌面 Dock 时，确认已选择系统框架与桌面作用域，并手动重启设备。

## 预设与语言包

- **通知与控制中心预设**：支持内置和用户预设，长按预设可导出 JSON 或二维码，也可从文件或二维码图片导入。
- **模块设置预设**：在模块“设置”页面通过 JSON 导入、导出多个配置组。导入时校验格式与版本；导出的配置不包含图片文件本体，部分图片引用字段也会跳过，迁移设备时请单独准备素材。
- **语言包**：内置中文、英文、日文及跟随系统选项。可参考 [languages/example.json](languages/example.json) 创建语言包，在应用内导入、编辑和导出。

不同类型预设使用各自的导入入口。导入后请检查配置，并按提示应用需要重启才能生效的选项。

## 故障排除

| 现象 | 建议操作 |
| --- | --- |
| 修改未生效 | 检查功能开关、对应 LSPosed 作用域与目标应用版本，按应用提示手动重启相关应用。 |
| Dock 未显示 | 检查系统框架和桌面作用域，确认使用兼容的 Rust 桌面，并已在首次启用后手动重启设备。 |
| iOS 风格通知中心无壁纸 | 检查壁纸来源设置；该功能暂不支持动态壁纸。 |
| 系统界面反复崩溃 | 在 LSPosed 或 Recovery 中禁用模块，恢复后逐项排查设置及其他模块冲突。 |
| 某项功能不可用 | 系统或应用实现可能不兼容，请提供设备、系统及目标应用版本反馈。 |
| 预设导入失败 | 确认预设类型、导入入口与导出版本匹配，且文件或二维码完整。 |

提交 Issue 时请附上设备型号、Android / HyperOS 版本、模块与 LSPosed 版本、目标应用版本、作用域、复现步骤，以及移除个人信息后的相关 Logcat 日志。

## 从源码构建

环境要求：JDK 21（与 CI 一致）、Android SDK 37，以及支持 Android Gradle Plugin 9.2.1 的 Android Studio 或项目 Gradle Wrapper（9.6.1）。项目还在 [app/build.gradle.kts](app/build.gradle.kts) 配置了本地 NDK r28c 路径，构建前请确认该路径在本机可用。

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

Release 签名通过 Gradle 属性 `releaseStoreFile`、`releaseStorePassword`、`releaseKeyAlias`、`releaseKeyPassword` 配置，密钥应存放在仓库外。签名构建输出至 `app/build/outputs/apk/release/`，发布文件命名为 `HyperChanger-v<VersionName>.apk`，将版本号中的空格替换为连字符，例如 `HyperChanger-v1.2.1-RC2.apk`。不要提交密钥、密码或构建产物。

## 目录结构

```text
HyperChanger/
|-- app/
|   |-- src/main/java/       Kotlin / Java 模块与界面源码
|   |-- src/main/assets/     语言包、法律文档与第三方声明
|   |-- src/main/jniLibs/    ARM64 原生补丁库
|   |-- src/main/res/        Android 资源
|   `-- src/main/resources/  LSPosed 入口、元数据与作用域
|-- languages/example.json   语言包模板
|-- gradle/                  Gradle Wrapper
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

HyperChanger 是社区开发的实验性模块，与小米无隶属或官方授权关系。系统级 Hook 可能造成界面异常、应用崩溃或无法正常启动，且兼容性会随系统与应用更新变化。测试前请备份重要数据，保留通过 LSPosed 或 Recovery 禁用模块的途径。使用风险由用户自行承担，项目不保证兼容任何特定设备或未来版本。

## 许可证与致谢

项目依据 [AGPL-3.0-or-later](LICENSE) 开源，可选择 GNU Affero 通用公共许可证第 3 版或任何后续版本。历史 MIT、Apache-2.0 授权说明及第三方来源声明见 [NOTICE](NOTICE)。

- 锁屏音乐封面运行时引入并适配自 [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover)（Copyright 2026 zyl6932，Apache License 2.0）。
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
