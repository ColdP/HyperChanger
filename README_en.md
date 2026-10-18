<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperchanger_full.png" alt="HyperChanger Icon" width="128">
</p>

<h1 align="center"><strong>HyperChanger</strong></h1>

<p align="center">Shape HyperOS 4 your way.</p>

<p align="center">
  <a href="README.md">简体中文</a> |
  <strong>English</strong> |
  <a href="README_ja.md">日本語</a>
</p>

<p align="center">
  <a href="https://github.com/ColdP/HyperChanger/releases">Releases</a> |
  <a href="https://t.me/HyperChanger">Telegram group</a> |
  <a href="https://qm.qq.com/q/rQhbQbiYLe">QQ group: 429188055</a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/v/release/ColdP/HyperChanger?style=flat-square" alt="Release">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-35-blue?style=flat-square" alt="Minimum SDK">
  <img src="https://img.shields.io/badge/targetSdk-37-blue?style=flat-square" alt="Target SDK">
  <img src="https://img.shields.io/badge/License-AGPL--3.0--or--later-yellow?style=flat-square" alt="License">
</p>

## Overview

HyperChanger is an LSPosed module for Xiaomi HyperOS 4. Its companion app configures the appearance and behavior of System UI, notifications, Control Center, the lock screen, the launcher, Settings, the rear screen, and supported apps. The module requires root and modifies existing components through hooks.

The current source version is **1.2.0 RC2**, as defined by `versionName` in [app/build.gradle.kts](app/build.gradle.kts). See [Releases](https://github.com/ColdP/HyperChanger/releases) for downloads and release notes.

The project is still in testing and primarily targets **HyperOS 4 (Android 17)**. Device, region, system build, and app version differences can affect features. Meeting the minimum installation version does not imply full feature support.

## Features

### Notifications, Control Center, and volume panel

- Configure notification-shade and Control Center backgrounds independently, plus materials, blur, opacity, and corners for regular, media, and focus notifications.
- iOS-style notification center with a rounded wallpaper panel, lock-screen clock, and depth effects. Choose lock-screen wallpaper or no wallpaper, hide the clear button, and adjust the bottom notification offset. Live wallpapers are currently unsupported.
- Customize the Control Center large clock, date spacing, red “1”, carrier text, network indicators, and header height.
- Adjust volume-panel blur, glass strength, background opacity, and corners.
- Use built-in or user presets and share notification-shade and Control Center settings through JSON or QR codes.

### Super Island, status bar, and gesture handle

- Adjust Super Island width, spacing between large and small islands, collapsed contour smoothing, and background materials.
- Remove selected focus-notification and Super Island whitelist restrictions, customize notification backgrounds and media cards, and simulate regular, focus, and media notifications.
- Customize stacked mobile signal indicators, network-type labels, icon visibility, scale, position, and spacing.
- Adjust the global gesture handle's style and dimensions.

### Lock screen, music, and wallpapers

- Customize the lock-screen clock, horizontal position, depth effects, charging text, bottom shortcuts, fingerprint icon, and PIN entry appearance.
- Configure the lock-screen editor background, template-count limit, lock-screen capsule, and face-unlock island; integrate AMap navigation with the lock-screen capsule.
- Edit lock-screen widgets with weather, battery, sunrise/sunset, steps, alarms, schedules, and signature images. Reorder widgets and tune their layout, colors, and materials.
- Use music-cover lock screens, a mini player, and lyrics; manage local lyrics in the lyrics library.
- Configure raster-effect live wallpapers and export the current home and lock-screen wallpapers.
- Preserve soft-glass effects after applying global themes and remove OTA conversion restrictions on glass and overlay AOD clock materials.

### Launcher and recent apps

- Add a Dock to compatible HyperOS 4 Rust launchers, with solid-color, advanced-material, or soft-glass backgrounds and an entrance animation.
- Adjust Dock height, horizontal and bottom margins, corners, colors, opacity, and blur.
- Hide the recent-apps clear button while either retaining or disabling its action.
- The Dock requires both System Framework and launcher scopes, plus a manual device restart on first use.

### Settings and rear screen

- Customize Settings home and device pages with backgrounds, device cards, logos, device images, and displayed specifications; add a module shortcut.
- Unlock selected rear-screen AI features, manage imported rear-screen apps, and choose apps for the rear-screen music widget.
- Customize the rear-screen wallpaper count limit and fix conflicts between theme modifications and rear-screen theme/wallpaper authorization. These options require the Themes scope.

### Camera, Gallery, and Super XiaoAi input method

- Configure Leica LCC UI, preservation of native focal lengths, and instant modes in compatible Camera versions.
- Unlock selected Gallery editing watermarks and Xiaomi 18-series editing features, subject to Gallery and media-editor version compatibility.
- Remove selected blacklist, clipboard, AI, and voice safety restrictions in Super XiaoAi input method.
- Adjust keyboard glass appearance, colors, corners, opacity, and blur.

### Other apps and system updates

- Customize bottom navigation styles, labels, and colors in Apple Music, Weibo, Xiaohongshu, Xiaomi Shop, Mi Wallet, Mi Fitness, and Luckin Coffee.
- Remove the 10-minute watch-face trial limit in Mi Fitness.
- Override MiShare's AirDrop availability checks. Actual transfers still depend on system components, hardware, and the receiving device.
- Add screen-recording frame-rate and bitrate options and configure a custom output directory.
- Disable system updates, remove selected OTA restrictions, and customize displayed system and SOTA versions.

### Module app, languages, and updates

- MIUIX-based settings interface with light, dark, and system themes, multiple bottom navigation styles, and predictive back animations.
- Built-in Simplified Chinese, English, and Japanese, with language-pack import, export, and editing.
- Import and export module settings as JSON and manage scoped app restarts from the app.
- Check for module updates through GitHub or Gitee, download them, and install manually or silently with root.

## Compatibility and requirements

| Item | Details |
| --- | --- |
| Primary target | Xiaomi HyperOS 4 (Android 17) |
| Minimum installation version | Android 15 (API 35); does not imply full feature compatibility |
| Compile / target SDK | API 37 |
| Module framework | Root and LSPosed with modern libxposed API support; minimum API 101, target API 102 |
| Native library architecture | The repository includes `arm64-v8a/libhyperpatch.so`; native launcher patches require ARM64 |
| Optional components | Each feature requires a compatible target app or system component to be installed |

The app checks `ro.mi.os.version.code`: it exits when HyperOS is not detected and shows a warning on non-HyperOS 4 versions. Passing this check does not guarantee hook compatibility with the current system.

## Module scopes

Enable the module in LSPosed and select the scopes required by your features. This list matches [scope.list](app/src/main/resources/META-INF/xposed/scope.list):

| Scope | Package / identifier |
| --- | --- |
| System UI | `com.android.systemui` |
| System Framework (Dock) | `system` |
| System launcher | `com.miui.home` |
| Wallpaper | `com.miui.miwallpaper` |
| System UI plugin | `miui.systemui.plugin` |
| Always-on display | `com.miui.aod` |
| Xiaomi Camera | `com.android.camera` |
| Xiaomi Gallery | `com.miui.gallery` |
| Hyper Gallery plugin | `com.hyper.gallery.plugin` |
| Media editor | `com.miui.mediaeditor` |
| Screen recorder | `com.miui.screenrecorder` |
| Super XiaoAi input method | `com.xiaomi.type` |
| XiaoAi phrases | `com.miui.phrase` |
| Settings | `com.android.settings` |
| System updater | `com.android.updater` |
| Rear-screen center | `com.xiaomi.subscreencenter` |
| Personal assistant | `com.miui.personalassistant` |
| Themes | `com.android.thememanager` |
| Xiaomi Shop | `com.xiaomi.shop` |
| Mi Wallet | `com.mipay.wallet` |
| Mi Fitness | `com.mi.health` |
| Apple Music | `com.apple.android.music` |
| Weibo | `com.sina.weibo` |
| Xiaohongshu | `com.xingin.xhs` |
| Luckin Coffee | `com.lucky.luckyclient` |
| AMap | `com.autonavi.minimap` |
| MiShare | `com.miui.mishare.connectivity` |

Some packages are absent on certain devices. Select only the scopes you use. A declared scope does not guarantee support for every version of that app.

## Installation and usage

1. Download a signed APK from [Releases](https://github.com/ColdP/HyperChanger/releases) and install it.
2. Enable HyperChanger in LSPosed and select the required scopes.
3. Open the companion app, complete its environment checks, and read the first-use instructions and agreements.
4. Enable the features you need. Follow the app's prompts and manually use its scope restart controls to apply settings.
5. When enabling the launcher Dock for the first time, confirm that System Framework and launcher scopes are selected, then manually restart the device.

## Presets and language packs

- **Notification-shade and Control Center presets:** use built-in or user presets. Long-press to export JSON or a QR code; import from a file or QR-code image.
- **Module settings presets:** import and export multiple configuration groups as JSON from the app's Settings page. Imports validate format and version. Exports do not bundle image files and also skip selected image-reference fields; prepare assets separately when moving to another device.
- **Language packs:** choose Chinese, English, Japanese, or the system language. Use [languages/example.json](languages/example.json) as a template and import, edit, or export packs in the app.

Use the matching import entry for each preset type. Review imported settings and follow the prompts for options that require a restart.

## Troubleshooting

| Symptom | Recommended action |
| --- | --- |
| A change does not take effect | Check the feature toggle, required LSPosed scopes, and target app version; manually restart the affected app as prompted. |
| Dock does not appear | Check System Framework and launcher scopes, confirm a compatible Rust launcher, and ensure you manually restarted the device after first enabling it. |
| iOS-style notification center has no wallpaper | Check the wallpaper source setting; live wallpapers are currently unsupported. |
| System UI repeatedly crashes | Disable the module through LSPosed or recovery. After restoring normal operation, check individual settings and conflicts with other modules. |
| A feature is unavailable | The system or app implementation may be incompatible. Report device, system, and target app versions. |
| Preset import fails | Check that the preset type, import entry, and exporting version match, and that the file or QR code is intact. |

When opening an issue, include the device model, Android / HyperOS versions, module and LSPosed versions, target app versions, scopes, reproduction steps, and relevant Logcat output with personal information removed.

## Build from source

Requirements: JDK 21 (matching CI), Android SDK 37, and Android Studio with Android Gradle Plugin 9.2.1 support or the project Gradle Wrapper (9.6.1). [app/build.gradle.kts](app/build.gradle.kts) also configures a local NDK r28c path; confirm that it is available on your machine before building.

```bash
git clone https://github.com/ColdP/HyperChanger.git
cd HyperChanger
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Configure release signing with the Gradle properties `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`, and `releaseKeyPassword`, keeping the keystore outside the repository. Signed builds are output to `app/build/outputs/apk/release/`. Name the published file `HyperChanger-v<VersionName>.apk`, replacing spaces in the version with hyphens, for example `HyperChanger-v1.2.0-RC2.apk`. Do not commit keystores, passwords, or build outputs.

## Project structure

```text
HyperChanger/
|-- app/
|   |-- src/main/java/       Kotlin / Java module and UI sources
|   |-- src/main/assets/     Language packs, legal documents, and third-party notices
|   |-- src/main/jniLibs/    ARM64 native patch library
|   |-- src/main/res/        Android resources
|   `-- src/main/resources/  LSPosed entries, metadata, and scopes
|-- languages/example.json   Language-pack template
|-- gradle/                  Gradle Wrapper
|-- README.md                Primary Chinese README
|-- README_en.md             English README
|-- README_ja.md             Japanese README
|-- README_zh.md             Legacy Chinese link entry
|-- CONTRIBUTING_zh.md
|-- NOTICE
`-- LICENSE
```

## Contributing

Issues and improvements are welcome. Read the [English contributing guide](CONTRIBUTING.md) or [Chinese contributing guide](CONTRIBUTING_zh.md) before contributing.

## Disclaimer

HyperChanger is an experimental community module with no affiliation with or official authorization from Xiaomi. System hooks can cause visual issues, app crashes, or boot failures, and compatibility can change with system and app updates. Back up important data and keep a way to disable the module through LSPosed or recovery before testing. Use it at your own risk; the project does not guarantee compatibility with any particular device or future version.

## License and acknowledgments

The project is released under [AGPL-3.0-or-later](LICENSE): GNU Affero General Public License version 3 or, at your option, any later version. See [NOTICE](NOTICE) for historical MIT and Apache-2.0 licensing and third-party notices.

- The lock-screen music-cover runtime is imported and adapted from [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) (Copyright 2026 zyl6932, Apache License 2.0).
- Collapsed-island smoothing, large/small island spacing, and face-unlock island implementations are adapted from [HyperIsland 3.1.8](https://github.com/1812z/HyperIsland) (Copyright 2026 1812z, MIT). The full notice is in [HyperIsland-MIT.txt](app/src/main/assets/licenses/HyperIsland-MIT.txt).
- Thanks to MIUIX, AndroidLiquidGlass / Backdrop, and other dependencies. See the app's open-source notices for more information.

## Star History

<a href="https://www.star-history.com/?repos=ColdP%2FHyperChanger&type=date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=ColdP/HyperChanger&type=date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=ColdP/HyperChanger&type=date" />
    <img alt="Star History chart" src="https://api.star-history.com/chart?repos=ColdP/HyperChanger&type=date" />
  </picture>
</a>
