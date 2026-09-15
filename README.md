<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperchanger_full.png" alt="HyperChanger icon" width="128">
</p>

<h1 align="center"><strong>HyperChanger</strong></h1>

<p align="center">Shape HyperOS 4 your way.</p>

<p align="center">
  <a href="README_zh.md">中文</a> |
  <a href="https://github.com/ColdP/HyperChanger/releases">Releases</a> |
  <a href="https://t.me/HyperChanger">Telegram Group</a> |
  <a href="https://qm.qq.com/q/rQhbQbiYLe">QQ Group</a>
</p>

<p align="center"><strong>Join the community on Telegram:</strong> <a href="https://t.me/HyperChanger">https://t.me/HyperChanger</a></p>
<p align="center"><strong>Join the QQ group:</strong> <a href="https://qm.qq.com/q/rQhbQbiYLe">429188055</a></p>

<p align="center">
  <img src="https://img.shields.io/github/v/release/ColdP/HyperChanger?style=flat-square" alt="Release">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-33-blue?style=flat-square" alt="minSdk">
  <img src="https://img.shields.io/badge/License-Apache--2.0-yellow?style=flat-square" alt="License">
</p>

---

## Overview

HyperChanger is an LSPosed module for selected Xiaomi HyperOS 4 Beta builds. It exposes System UI, notification shade, lock-screen, and camera-related customization in one companion app. The module is intended for rooted devices and does not replace System UI or Xiaomi Camera applications.

The project is in beta. HyperOS framework classes and resources can differ by device, region, and system build. A setting that works on one build may be unavailable or behave differently on another.

## Disclaimer

HyperChanger is an experimental, community-developed LSPosed module and is not affiliated with or endorsed by Xiaomi. It requires root access and a compatible LSPosed environment, and it hooks vendor implementation details that may change between HyperOS builds. Hooks can be ineffective, cause System UI instability, or require recovery-based disabling. Use the module at your own risk, keep a recovery path available, and back up important data before testing. The project provides no guarantee of compatibility, availability, or fitness for a particular device.

## Features

### Notification shade and Control Center

- Adjust visual materials for notification and Control Center elements.
- Configure notification and Control Center backgrounds independently.
- Tune Control Center corner and presentation options.
- Save user presets, apply built-in presets, and exchange presets as JSON or QR codes.

### System UI and Lock Screen

- Customize compatible Dynamic Island and focus-notification behavior.
- Adjust status-bar presentation where supported by the target build.
- Control selected lock-screen effects, including charging text and shortcut appearance.
- Keep System UI soft-glass effects after applying a global theme.
- Remove the OTA conversion limit for glass and overlay AOD clock materials.
- Edit lock-screen widgets with weather, battery, sunrise/sunset, steps, alarms, schedules, and custom signature images; reorder cards and tune layout, colors, and materials.
- Customize dual-row status-bar signal indicators, network-type labels, visibility, scale, position, and spacing.
- Restart individual scoped processes from the app after changing settings.

### Super XiaoAi input method

- Remove selected blacklist, clipboard, AI, and voice safety restrictions.
- Customize the keyboard's glass appearance, colors, corner radius, opacity, and blur.

### System updates and compatibility

- Disable system updates, remove OTA restrictions, and optionally spoof displayed system and SOTA versions.
- Check HyperOS compatibility before enabling hooks and review the in-app disclaimer and license information.

### Languages and presets

- Use built-in English, Chinese, and Japanese language packs.
- Import, export, and edit language packs, and exchange complete settings as JSON or QR-code presets.

### Camera and Gallery

- Configure compatible Xiaomi Camera behavior.
- Enable selected Gallery and media-editor integrations when their packages are present.
- Keep unsupported packages inactive instead of applying hooks outside the configured scopes.

## Compatibility and Requirements

| Requirement | Details |
| --- | --- |
| Android | Android 13 (API 33) or later |
| System | Compatible Xiaomi HyperOS 4 Beta build |
| Root framework | Root access and LSPosed API 101 or later |
| Architecture | The release APK is universal; device compatibility is determined by the target HyperOS build |

HyperChanger relies on implementation details of Xiaomi system packages. Update the module only after keeping a way to disable it through LSPosed or recovery if you are testing a new system build.

## Module Scopes

Enable HyperChanger in LSPosed and select only the scopes required for the features you use. The module declares the following supported packages:

| Scope | Package |
| --- | --- |
| System UI | `com.android.systemui` |
| System UI plugin | `miui.systemui.plugin` |
| Always-on display | `com.miui.aod` |
| Xiaomi Camera | `com.android.camera` |
| Xiaomi Gallery | `com.miui.gallery` |
| Hyper Gallery plugin | `com.hyper.gallery.plugin` |
| Media editor | `com.miui.mediaeditor` |
| Super XiaoAi input method | `com.xiaomi.type` |
| XiaoAi phrases | `com.miui.phrase` |
| System updater | `com.android.updater` |

Some packages are optional and may not be installed on every device. LSPosed will only activate hooks for installed, selected scopes.

## Installation

1. Download the latest signed APK from [Releases](https://github.com/ColdP/HyperChanger/releases).
2. Install the APK normally. If Android blocks the install, allow the installer source when prompted.
3. In LSPosed, enable HyperChanger and select the scopes required for your configuration.
4. Open HyperChanger and set the desired options.
5. Use the in-app restart action, or restart the affected System UI process, after changing System UI settings.

Do not enable every scope solely for convenience. Enabling only the packages used by your configuration reduces the number of processes that load the module.

## Using Presets

The notification-shade preset page supports built-in and user-defined presets. Long-press a preset to access export options, then choose JSON or QR code sharing. Importing validates the HyperChanger preset format and version before settings are applied.

Imported settings can affect several visual options at once. Review the result and restart System UI to apply hook-based changes.

## Troubleshooting

| Symptom | Recommended action |
| --- | --- |
| A change does not take effect | Confirm the required LSPosed scope is enabled, then restart the affected process. |
| System UI repeatedly restarts | Disable HyperChanger in LSPosed, reboot, and re-enable settings one at a time. |
| A page has no effect on a device | The corresponding HyperOS build may not expose a compatible implementation. Leave the option disabled and include device and system details in an issue. |
| Import fails | Confirm the file or QR code was exported by a compatible HyperChanger release. |

When reporting an issue, include the device model, Android and HyperOS versions, HyperChanger version, enabled scopes, reproduction steps, and relevant Logcat output with personal data removed.

## Build From Source

### Prerequisites

- Android Studio Narwhal (2025.1) or later
- JDK 17 or later
- Android SDK 37
- A device running Android 13 (API 33) or later for installation testing

```bash
git clone https://github.com/ColdP/HyperChanger.git
cd HyperChanger
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

### Release Build and Signing

Build the optimized release variant with a keystore stored outside the repository:

```bash
./gradlew assembleRelease \
  -PreleaseStoreFile=/absolute/path/to/keystore \
  -PreleaseStorePassword=your-store-password \
  -PreleaseKeyAlias=your-key-alias \
  -PreleaseKeyPassword=your-key-password
```

The signed APK is written to `app/build/outputs/apk/release/`. Never commit a keystore, its password, or a generated APK to the repository.

## Project Structure

```text
HyperChanger/
|-- app/
|   |-- src/main/java/       Kotlin and Java module sources
|   |-- src/main/res/        Android resources
|   `-- src/main/resources/  LSPosed metadata and scopes
|-- gradle/                  Gradle wrapper files
|-- CONTRIBUTING.md
|-- NOTICE
`-- LICENSE
```

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening an issue or pull request.

## License

HyperChanger is released under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for historical attribution to the original MIT-licensed release.

The lock-screen music-cover runtime is directly imported and adapted from [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) (Copyright 2026 zyl6932, Apache License 2.0). Its retained attribution and license notice are in [NOTICE](NOTICE).
