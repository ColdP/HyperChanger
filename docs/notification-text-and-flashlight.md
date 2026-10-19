# Notification text and flashlight material

## Scope and ownership (before implementation)

- Restore the volume bar and expanded panel to the existing shared `VolumePanelLayoutSettings.corner` offset. Remove the separate expanded-corner preference consumer; retain the footer visibility fix.
- New text modes live in `NotificationTextColorSettings.kt`, outside `HookSettings`, with local and remote synchronization. Default mode passes through system colors. Each menu is gated by its associated material switch.
- `NotificationHooks` owns notification wrapper/content lifecycle text updates, weak view baselines, status-bar dark intensity observation, and flashlight notification payload normalization. HyperSystemUiModule retains package/process dispatch, resource guard, and plugin class discovery. No hooks are moved between owners.
- SystemUI targets: notification wrappers (`onContentUpdated`, `updateTransparentBgAndTextColor`), NotificationContentView child/layout callbacks, and `DarkIconDispatcherImpl.applyDarkIntensity(float)`. Use the SystemUI package ClassLoader. Apply only text colors, preserving full ColorStateLists for restoration; do not tint notification artwork.
- Flashlight target: `miui.systemui.flashlight.NotificationManager.getExtraMiuiFocusParam(Context)` in the plugin ClassLoader, discovered through the existing plugin class discovery and package callback. Keep a per-Class installation guard. Preserve the original method call and JSON payload, clearing only explicit background/text color overrides while unification is enabled.
- Reference: HyperLight `r71.c`, `q81`, `ze2.E/F`, `jz0` case 18; SystemUI `MiuiNotificationTemplateViewWrapper`, `NotificationContentView`, `DarkIconDispatcherImpl`; plugin NotificationManager encodes `param_v2.bgInfo.colorBg = #1A1A1A`.
- Shared glass setters, focus-background enforcement, iOS Shade material/overlay fixes, framework dispatch, and volume sizing stay unchanged. Risks needing device verification: custom RemoteViews, async notification rebinds, theme/status-bar changes, plugin versions, and focus-template parsing.

## Verification

- Removed the old process-wide Resources color override and hard-coded white resource list. Text is scoped to each wrapper view tree; heads-up and regular content use separate modes. The shared resource guard and dimension installation remain in HyperSystemUiModule. Original notification methods execute once; no new glass setter, overlay or blur recipe is installed.
- NotificationHooks observes preferences and the status-bar intensity threshold, updates tracked views on their UI threads, and preserves full original ColorStateLists with weak keys. Rebinding restores the baseline before the original callback, then captures the system result. Identical colors are not repeatedly assigned during layout callbacks. Artwork is not tinted.
- Flashlight registration is deduplicated by target Class across direct package installation and existing class discovery. Disabled unification returns the original payload; malformed/unknown payloads pass through. Actions, island configuration, float behavior and additional protocol fields are retained.
- Deleted VolumePanelExpandedCornerSettings.kt and all consumers. Existing cornerEnabled/corner settings now cover both bar and panel resource names; 0 means system default. The footer visibility fix and size constraints remain intact.
- Three language JSON files parse successfully; git diff --check passed. HookSettings.kt, ShadeLayer.java, and all pre-existing uncommitted iOS Shade changes match their pre-task hashes.
- Passed: tests/notification-text/run.ps1 (color modes, status-bar threshold, inverse system theme, persistence/remote sync, flashlight protocol preservation); tests/volume-panel-controls/run.ps1 (footer visibility, shared corners, default/bounds); tests/soft-glass/run.ps1; tests/restart-recommendations/run.ps1.
- Required command `.\gradlew.bat :app:assembleDebug` completed: BUILD SUCCESSFUL in 1m 11s. APK: `D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`, version 1.2.1 RC1. ZIP integrity passed; classes9.dex contains the new settings class and the packaged language asset contains the new labels.
- APK SHA-256: 534668bf40c27c900fb600026d9407d2d2ba72945778d9102f3b6908438cd662.
- No device testing, installation or SystemUI restart performed. Theme transitions, live status-bar tint changes, custom notifications and flashlight rendering require device verification. Build and pure-function checks do not establish runtime results.
