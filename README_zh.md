<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperchanger_full.png" alt="HyperChanger 图标" width="128">
</p>

<h1 align="center"><strong>HyperChanger</strong></h1>

<p align="center">随心塑造你的 HyperOS 4。</p>

<p align="center">
  <a href="README.md">English</a> |
  <a href="https://github.com/ColdP/HyperChanger/releases">发布版本</a> |
  <a href="https://t.me/HyperChanger">Telegram 群组</a> |
  <a href="https://qm.qq.com/q/rQhbQbiYLe">QQ群</a>
</p>

<p align="center"><strong>加入 Telegram 群组：</strong> <a href="https://t.me/HyperChanger">https://t.me/HyperChanger</a></p>
<p align="center"><strong>加入 QQ 群：</strong> <a href="https://qm.qq.com/q/rQhbQbiYLe">群号：429188055</a></p>

<p align="center">
  <img src="https://img.shields.io/github/v/release/ColdP/HyperChanger?style=flat-square" alt="发布版本">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="平台">
  <img src="https://img.shields.io/badge/minSdk-33-blue?style=flat-square" alt="最低 SDK">
  <img src="https://img.shields.io/badge/License-Apache--2.0-yellow?style=flat-square" alt="许可证">
</p>

---

## 项目简介

HyperChanger 是一个面向部分小米 HyperOS 4 Beta 系统的 LSPosed 模块。它在同一个配套应用中提供系统界面、通知与控制中心、锁屏及相机相关的自定义选项。模块需要设备已获取 Root 权限；它不会替换系统界面或小米相机应用。

本项目处于 Beta 阶段。HyperOS 的框架类与资源会因设备、地区和系统构建版本而不同，因此某项功能在一台设备上可用，并不代表在另一台设备上具有相同行为。

## 免责声明

HyperChanger 是一个实验性的社区开发 LSPosed 模块，与小米无任何隶属、授权或官方支持关系。模块需要 Root 权限和兼容的 LSPosed 环境，并依赖可能随 HyperOS 版本变化的厂商实现细节。Hook 可能不生效，也可能导致系统界面不稳定，甚至需要通过 Recovery 禁用模块。使用本模块的风险由用户自行承担；测试前请保留可用的恢复途径并备份重要数据。项目不保证兼容任何特定设备，也不保证功能持续可用或适用于特定用途。

## 功能

### 通知与控制中心

- 分别调整通知和控制中心元素的视觉材质。
- 独立配置通知中心与控制中心背景。
- 调整控制中心圆角和展示相关选项。
- 保存用户预设、使用内置预设，并以 JSON 或二维码交换预设。

### 系统界面与锁屏

- 自定义兼容的灵动岛与焦点通知行为。
- 在目标系统支持时调整状态栏展示效果。
- 控制部分锁屏效果，包括充电文本和快捷方式外观。
- 在应用全局主题后保持系统界面的柔光玻璃效果。
- 解除息屏时钟“玻璃”和“叠加”材质被 OTA 转换限制。
- 新增锁屏小组件编辑器，可组合天气、电量、日出日落、步数、闹钟、日程和自定义签名图片，支持拖拽排序及布局、颜色、材质调节。
- 自定义状态栏双排信号、网络类型文本、图标显示逻辑、缩放、位置和边距。
- 修改设置后，可从应用内重启各个已选择作用域的进程。

### 超级小爱输入法

- 解除部分黑名单词库、剪贴板、AI 与语音安全限制。
- 调节键盘柔光玻璃外观、颜色、圆角、透明度和模糊度。

### 系统更新与兼容性

- 禁用系统更新、移除 OTA 限制，并可自定义系统版本与 SOTA 版本显示。
- 启用 Hook 前检测 HyperOS 兼容性，并在应用内查看免责声明与许可证信息。

### 多语言与预设

- 内置中文、英文和日文语言包。
- 支持语言包导入、导出与编辑，并可通过 JSON 或二维码交换完整设置预设。

### 相机与相册

- 配置兼容的小米相机行为。
- 在相关包存在时启用部分相册和媒体编辑器集成。
- 对未配置作用域以外的包保持不激活，避免无关进程加载 Hook。

## 兼容性与要求

| 要求 | 说明 |
| --- | --- |
| Android | Android 13（API 33）或更高版本 |
| 系统 | 兼容的小米 HyperOS 4 Beta 系统 |
| Root 框架 | Root 权限与 LSPosed API 101 或更高版本 |
| 架构 | Release APK 为通用包；实际兼容性取决于目标 HyperOS 构建版本 |

HyperChanger 依赖小米系统包的实现细节。测试新的系统构建版本前，请确保可以通过 LSPosed 或 Recovery 禁用模块。

## 模块作用域

在 LSPosed 中启用 HyperChanger 后，请只选择你实际需要的作用域。模块声明支持以下包：

| 作用域 | 包名 |
| --- | --- |
| 系统界面 | `com.android.systemui` |
| 系统界面插件 | `miui.systemui.plugin` |
| 息屏显示 | `com.miui.aod` |
| 小米相机 | `com.android.camera` |
| 小米相册 | `com.miui.gallery` |
| Hyper 相册插件 | `com.hyper.gallery.plugin` |
| 媒体编辑器 | `com.miui.mediaeditor` |
| 超级小爱输入法 | `com.xiaomi.type` |
| 小爱短语 | `com.miui.phrase` |
| 系统更新 | `com.android.updater` |

部分包在某些设备上并不存在。LSPosed 只会在已安装且已选中的作用域内激活模块。

## 安装

1. 从 [发布版本](https://github.com/ColdP/HyperChanger/releases) 下载最新的已签名 APK。
2. 正常安装 APK。如 Android 阻止安装，请按提示允许安装来源。
3. 在 LSPosed 中启用 HyperChanger，并选择所需配置对应的作用域。
4. 打开 HyperChanger，设置需要的选项。
5. 修改系统界面设置后，使用应用内重启功能，或重启受影响的系统界面进程。

不要为了方便而启用所有作用域。只启用实际使用的包，可以减少加载模块的进程数量。

## 使用预设

通知与控制中心预设页面支持内置预设和用户预设。长按预设可访问导出选项，然后选择 JSON 或二维码分享。导入时会先校验 HyperChanger 预设格式与版本，再应用设置。

导入后的设置可能会同时修改多个视觉选项。请检查结果，并重启系统界面以应用基于 Hook 的改动。

## 故障排除

| 现象 | 建议操作 |
| --- | --- |
| 修改未生效 | 确认已启用所需的 LSPosed 作用域，然后重启受影响进程。 |
| 系统界面反复重启 | 在 LSPosed 中禁用 HyperChanger，重启设备后再逐项启用设置。 |
| 某个页面在设备上没有效果 | 对应的 HyperOS 构建可能没有兼容实现。保持该选项关闭，并在 Issue 中提供设备与系统信息。 |
| 导入失败 | 确认文件或二维码来自兼容版本的 HyperChanger。 |

反馈问题时，请提供设备型号、Android 与 HyperOS 版本、HyperChanger 版本、启用的作用域、复现步骤，以及已移除个人信息的相关 Logcat 输出。

## 从源码构建

### 环境要求

- Android Studio Narwhal（2025.1）或更高版本
- JDK 17 或更高版本
- Android SDK 37
- 用于安装测试的 Android 13（API 33）或更高版本设备

```bash
git clone https://github.com/ColdP/HyperChanger.git
cd HyperChanger
./gradlew assembleDebug
```

Debug APK 输出至 `app/build/outputs/apk/debug/`。

### Release 构建与签名

使用存放在仓库外的密钥构建优化后的 Release 变体：

```bash
./gradlew assembleRelease \
  -PreleaseStoreFile=/absolute/path/to/keystore \
  -PreleaseStorePassword=your-store-password \
  -PreleaseKeyAlias=your-key-alias \
  -PreleaseKeyPassword=your-key-password
```

已签名 APK 输出至 `app/build/outputs/apk/release/`。不要将密钥、密码或生成的 APK 提交到仓库。

## 目录结构

```text
HyperChanger/
|-- app/
|   |-- src/main/java/       Kotlin 与 Java 模块源码
|   |-- src/main/res/        Android 资源
|   `-- src/main/resources/  LSPosed 元数据与作用域
|-- gradle/                  Gradle Wrapper 文件
|-- CONTRIBUTING_zh.md
|-- NOTICE
`-- LICENSE
```

## 贡献

欢迎参与贡献。提交 Issue 或 Pull Request 前，请阅读 [CONTRIBUTING_zh.md](CONTRIBUTING_zh.md)。

## 许可证

HyperChanger 基于 [Apache License 2.0](LICENSE) 开源；历史 MIT 授权致敬说明请参阅 [NOTICE](NOTICE)。

锁屏音乐封面运行时直接引入并适配自 [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover)（Copyright 2026 zyl6932，Apache License 2.0）。保留的来源与许可证声明见 [NOTICE](NOTICE)。
