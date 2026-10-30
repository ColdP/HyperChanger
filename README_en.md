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

HyperChanger is a modern Xposed module for Xiaomi HyperOS 4. Its companion app configures the appearance and behavior of System UI, notifications, Control Center, the lock screen, the launcher, Settings, the rear screen, and supported apps. The module requires root and modifies existing components through hooks.

The source version is defined by `versionName` in [app/build.gradle.kts](app/build.gradle.kts); the README does not duplicate the version string. See [Releases](https://github.com/ColdP/HyperChanger/releases) for downloads and release notes.

The project is still in testing and primarily targets **HyperOS 4 (Android 17)**. Device, region, system build, and app version differences can affect features. Meeting the minimum installation version does not imply full feature support.

## Features

### Notifications, Control Center, and volume panel

- Configure notification-shade and Control Center backgrounds independently, plus materials, blur, opacity, and corners for regular, media, and focus notifications.
- iOS-style notification center with a rounded wallpaper panel, lock-screen clock, and depth effects. Choose lock-screen wallpaper or no wallpaper, hide the clear button, and adjust the bottom notification offset. Live wallpapers are currently unsupported.
- Customize header icons, text, scale, and spacing, plus the clear button's style and material. Add the lock screen's left and right shortcuts to the notification center with their existing actions and drag gestures; shortcut mode saves its notification bottom offset separately.
- Customize the Control Center large clock, date spacing, red “1”, carrier text, network indicators, and header height.
- Adjust icon scale, landscape visibility, and backgrounds under “Status bar & Control center → Control center top buttons”. Choose solid color, advanced material, or soft glass, with independent or global soft-glass parameters.
- Adjust volume-panel blur, glass strength, background opacity, corners, and volume-slider width and height.
- Use built-in or user presets and share notification-shade and Control Center settings through JSON or QR codes.

### Super Island, status bar, and gesture handle

- Adjust Super Island width, spacing between large and small islands, collapsed contour smoothing, and background materials.
- Tune expanded-island soft-glass parameters independently or follow the global soft-glass settings.
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
- Adjust glass blur and optical parameters independently for the launcher and Dock, or use global parameters; Dock also offers system defaults. Folders and recents have their own material settings. Customize folder columns, icon row spacing, and Dock icon vertical offset.
- Hide the recent-apps clear button while either retaining or disabling its action.
- The Dock requires both System Framework and launcher scopes, plus a manual device restart on first use.

### Settings and rear screen

- Customize Settings home and device pages with backgrounds, device cards, logos, device images, and displayed specifications; add a module shortcut.
- Customize Settings entry labels, unlock additional auto-lock and refresh-rate options, show Google services and saved Wi-Fi passwords, and configure BOSE audio effects and badges.
- Unlock selected rear-screen AI features, manage imported rear-screen apps, and choose apps for the rear-screen music widget.
- Swipe left from the rear screen's right edge to launch a selected app, and optionally allow all Activities to open on the rear screen. Apps may still need their own adaptations.
- Customize the rear-screen wallpaper count limit and fix conflicts between theme modifications and rear-screen theme/wallpaper authorization. These options require the Themes scope.
- Launch the native NetEase Cloud Music mini player on rear display 1 (supported baseline 9.5.37). Select both NetEase Cloud Music and Rear-screen scopes and unlock the device before casting.
- Customize focus and full rear-screen screenshot shells in the Screenshot app. Import images or ZIP archives, preview, name, save, and apply styles, and export PNG images.

### Camera, Gallery, Super XiaoAi, and input method

- Configure Leica LCC UI, preservation of native focal lengths, and instant modes in compatible Camera versions.
- Unlock selected Gallery editing watermarks and Xiaomi 18-series editing features, subject to Gallery and media-editor version compatibility.
- Add soft glass to the Super XiaoAi floating input box and adjust its material parameters independently.
- Remove selected blacklist, clipboard, AI, and voice safety restrictions in Super XiaoAi input method.
- Adjust keyboard glass appearance, colors, corners, opacity, and blur.

### Dialer, content extension, and environment detection

- Customize dialpad background blur, opacity, border width and opacity, and corner offset; requires the Dialer and calls scope.
- Adjust the content extension (Portal) background material, corners, expanded height, floating panel size, and icon size. Remove its full-screen scrim or keep developer mode enabled. Soft glass can use independent or global parameters.
- Enhance Chinese, Japanese, and English text recognition in Portal, with in-app download, management, and deletion of local OCR models. Download the models before using enhanced recognition.
- Other → Environment detection provides an experimental master switch and related detection adjustments for Xiaomi Trust Service and Security. The master switch defaults to off and reveals the individual options when enabled; confirm the on-screen notices before first enabling it. Select the required scopes. These options do not guarantee changes to every app's environment checks.

### Other apps and system updates

- Customize bottom navigation in QQ, Apple Music, Weibo, Xiaohongshu, Xiaomi Store, Mi Wallet, Mi Fitness, Luckin Coffee, Tencent Meeting, China Merchants Bank, WeChat, Pinduoduo, Instagram, X, and GitHub, including styles, labels, colors, and badges. Available effects vary by app. China Merchants Bank support is based on 14.4.6; other versions require verification.
- Remove the 10-minute watch-face trial limit in Mi Fitness.
- Override MiShare's AirDrop availability checks. Actual transfers still depend on system components, hardware, and the receiving device.
- Add screen-recording frame-rate and bitrate options and configure a custom output directory.
- Add quick and anywhere-screenshot options.
- Configure System Framework options for multiple or forced freeform windows, remembered window state and position, background activity for edge-docked windows, immediate volume adjustment, removal of high-volume warnings, forced dark mode, the native installer, and disabling the mandatory PIN entry every 72 hours.
- Disable system updates, remove selected OTA restrictions, and customize displayed system and SOTA versions.

### Module app, languages, and updates

- A MIUIX settings interface with light, dark, and system themes, multiple bottom navigation styles, and predictive back animations. Enable Monet colors and choose system colors, one of 10 built-in themes, or a custom color.
- Search feature entries by setting name, description, or scope.
- Built-in Simplified Chinese, English, and Japanese, with language-pack import, export, and editing.
- Import and export module settings as JSON and manage scoped app restarts from the app.
- Check for module updates through GitHub or Gitee, download them, and install manually or silently with root.
- Provide a module master switch, per-section safety disable policy, and module log levels, filters, retention, and ZIP export.
- Settings → Module safety lists running targets, sends manual hot reload requests, and reports per-process results. Module-side hot reload support is not complete, so this interface does not replace required restarts.
- Log ZIP archives include `environment.txt` with device, system, module, and framework information and app versions for currently selected scopes.

## Compatibility and requirements

| Item | Details |
| --- | --- |
| Primary target | Xiaomi HyperOS 4 (Android 17) |
| Minimum installation version | Android 15 (API 35); does not imply full feature compatibility |
| Compile / target SDK | API 37 |
| Module framework | Root and Vector / LSPosed with modern libxposed API support; minimum API 102, target API 102 |
| Native library architecture | Builds target `arm64-v8a` only; prebuilt `libhyperpatch.so` is in `jniLibs`, while Dock and folder patches are built from `src/main/cpp` |
| Optional components | Each feature requires a compatible target app or system component to be installed |

The app checks `ro.mi.os.version.code`: it exits when HyperOS is not detected and shows a warning on non-HyperOS 4 versions. Passing this check does not guarantee hook compatibility with the current system.

Framework integration is based on the public source of [Vector canary-3114](https://github.com/JingMatrix/Vector/releases/tag/canary-3114) (API 102). Home, Module safety, and log environment reports show the connected framework name and version; tapping the framework card opens its manager. Older builds depend on their API implementation. Vector device testing is still pending.

Framework identity comes from the connected libxposed service. An installed manager or enabled root module does not mean HyperChanger is connected. Tap the Home framework card or the framework information in Module safety to open its standalone or parasitic manager. When disconnected, the app tries to identify the enabled framework; if it cannot identify the correct parasitic manager, it only tries standalone managers.

## Module scopes

Enable the module in Vector / LSPosed and select the scopes required by your features. This list matches [scope.list](app/src/main/resources/META-INF/xposed/scope.list):

| Scope | Package / identifier |
| --- | --- |
| System UI | `com.android.systemui` |
| System Framework | `system` |
| System launcher | `com.miui.home` |
| Wallpaper | `com.miui.miwallpaper` |
| System UI plugin | `miui.systemui.plugin` |
| Always-on display | `com.miui.aod` |
| Xiaomi Camera | `com.android.camera` |
| Xiaomi Gallery | `com.miui.gallery` |
| Hyper Gallery plugin | `com.hyper.gallery.plugin` |
| Media editor | `com.miui.mediaeditor` |
| Screen recorder | `com.miui.screenrecorder` |
| Screenshot | `com.miui.screenshot` |
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
| NetEase Cloud Music | `com.netease.cloudmusic` |
| Weibo | `com.sina.weibo` |
| Xiaohongshu | `com.xingin.xhs` |
| Luckin Coffee | `com.lucky.luckyclient` |
| AMap | `com.autonavi.minimap` |
| MiShare | `com.miui.mishare.connectivity` |
| Xiaomi interconnect service | `com.xiaomi.xmsf` |
| XiaoAi voice | `com.miui.voiceassist` |
| Xiaomi sound | `com.miui.misound` |
| Xiaomi Settings | `com.xiaomi.misettings` |
| Tencent Meeting | `com.tencent.wemeet.app` |
| China Merchants Bank | `cmb.pb` |
| WeChat | `com.tencent.mm` |
| QQ | `com.tencent.mobileqq` |
| Pinduoduo | `com.xunmeng.pinduoduo` |
| Xiaomi Trust Service | `com.xiaomi.trustservice` |
| Security | `com.miui.securitycenter` |
| Instagram | `com.instagram.android` |
| Dialer and calls | `com.android.contacts` |
| Content extension (Portal) | `com.miui.contentextension` |
| X | `com.twitter.android` |
| GitHub | `com.github.android` |

Some packages are absent on certain devices. Select only the scopes you use. A declared scope does not guarantee support for every version of that app.

## Installation and usage

1. Download a signed APK from [Releases](https://github.com/ColdP/HyperChanger/releases) and install it.
2. Enable HyperChanger in Vector / LSPosed and select the required scopes.
3. Open the companion app, complete its environment checks, and read the first-use instructions and agreements.
4. Enable the features you need. Follow the app's prompts and manually use its scope restart controls to apply settings.
5. When enabling the launcher Dock for the first time, confirm that System Framework and launcher scopes are selected, then manually restart the device.

The Process hot reload switch under Settings → Module safety defaults to on and controls access to manual requests. Requests require a connected framework with libxposed API 102 or later and the relevant capabilities. The current module entries do not implement callbacks to accept a reload and rebuild their hooks, so this is not complete module hot reload support. Continue following the existing app or device restart instructions. Inspect per-process results for unsupported, failed, or timed-out requests, and avoid resubmitting after a timeout.

## Presets and language packs

- **Notification-shade and Control Center presets:** use built-in or user presets. Long-press to export JSON or a QR code; import from a file or QR-code image.
- **Soft-glass presets:** use built-in or custom parameter presets and share them through JSON or QR codes. Surfaces configured to follow global parameters use the corresponding global soft-glass values.
- **Module settings presets:** import and export multiple configuration groups as JSON from the app's Settings page. Imports validate format and version. Exports do not bundle image files and also skip selected image-reference fields; prepare assets separately when moving to another device.
- **Language packs:** choose Chinese, English, Japanese, or the system language. Use [languages/example.json](languages/example.json) as a template and import, edit, or export packs in the app.

Use the matching import entry for each preset type. Review imported settings and follow the prompts for options that require a restart.

## Troubleshooting

| Symptom | Recommended action |
| --- | --- |
| A change does not take effect | Check the feature toggle, required Vector / LSPosed scopes, and target app version; manually restart the affected app as prompted. |
| Dock does not appear | Check System Framework and launcher scopes, confirm a compatible Rust launcher, and ensure you manually restarted the device after first enabling it. |
| iOS-style notification center has no wallpaper | Check the wallpaper source setting; live wallpapers are currently unsupported. |
| Framework disconnected or manager does not open | Confirm Vector / LSPosed is running and HyperChanger is enabled. Offline parasitic manager detection may require root; if detection fails, open the manager from its notification or an existing shortcut. |
| Hot reload is unavailable or unsuccessful | Module-side support is not complete. Check the framework connection, API 102 capabilities, target process, and reported result. Follow the existing restart instructions and avoid resubmitting after a timeout. |
| System UI repeatedly crashes | Disable the module through Vector / LSPosed or recovery. After restoring normal operation, check individual settings and conflicts with other modules. |
| A feature is unavailable | The system or app implementation may be incompatible. Report device, system, and target app versions. |
| Preset import fails | Check that the preset type, import entry, and exporting version match, and that the file or QR code is intact. |

When opening an issue, include the device model, Android / HyperOS versions, module and Vector / LSPosed versions, target app versions, scopes, and reproduction steps. Export a log ZIP from Settings → Module safety and attach relevant Logcat output. Review logs and environment information and remove personal information before sharing.

## Build from source

Requirements: JDK 21 (matching CI), Android SDK 37, NDK r28c (28.2.13676358), CMake 3.22.1, and Android Studio with Android Gradle Plugin 9.2.1 support or the project Gradle Wrapper (9.6.1). [app/build.gradle.kts](app/build.gradle.kts) fixes the NDK path to `.build-tmp/android-ndk-r28c` inside the repository. Place the NDK there or create a directory link to an installed copy before building; see the [CI configuration](.github/workflows/android.yml).

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

Targeted regression scripts are in `tests/`; `tests/vector-compatibility/run.ps1` checks framework identity and offline detection edge cases. Scripts require local build output and their respective toolchains; consult each script for paths. Host tests and APK builds do not replace device testing.

Configure release signing with the Gradle properties `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`, and `releaseKeyPassword`, keeping the keystore outside the repository. Signed builds are output to `app/build/outputs/apk/release/`. Name the published file `HyperChanger-v<VersionName>.apk`, replacing spaces in the version with hyphens, for example `HyperChanger-v1.2.1.apk`. Do not commit keystores, passwords, or build outputs.

## Project structure

```text
HyperChanger/
|-- app/
|   |-- src/main/java/       Kotlin / Java module and UI sources
|   |-- src/main/assets/     Language packs, legal documents, and third-party notices
|   |-- src/main/cpp/        ARM64 native sources for Dock, folder, and related patches
|   |-- src/main/jniLibs/    Prebuilt ARM64 libhyperpatch.so
|   |-- src/main/res/        Android resources
|   `-- src/main/resources/  libxposed entries, metadata, and scopes
|-- languages/example.json   Language-pack template
|-- gradle/                  Gradle Wrapper
|-- docs/                    Feature implementation and verification notes
|-- tests/                   Focused regression tests and runner scripts
|-- README.md                Primary Chinese README
|-- README_en.md             English README
|-- README_ja.md             Japanese README
|-- CONTRIBUTING_zh.md
|-- NOTICE
`-- LICENSE
```

## Contributing

Issues and improvements are welcome. Read the [English contributing guide](CONTRIBUTING.md) or [Chinese contributing guide](CONTRIBUTING_zh.md) before contributing.

## Disclaimer

HyperChanger is an experimental community module with no affiliation with or official authorization from Xiaomi. System hooks can cause visual issues, app crashes, or boot failures, and compatibility can change with system and app updates. Back up important data and keep a way to disable the module through Vector / LSPosed or recovery before testing. Use it at your own risk; the project does not guarantee compatibility with any particular device or future version.

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
