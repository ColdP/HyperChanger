# Repository Instructions

## Application Bottom Navigation Adaptation

- Before adding, fixing, or refactoring an application's custom bottom navigation, read `docs/bottom-navigation-adaptation.md` and the relevant application-specific audit in `docs/`, and verify the guidance against the current HyperChanger implementation and target APK evidence.

## Settings Storage

- Do not add new settings, fields, or serialization logic to `HookSettings.kt`; expanding it has caused the module app to crash.
- Store all newly added settings in a new, dedicated Kotlin (`.kt`) file, including their preference keys, loading, saving, and remote preference synchronization.
- Keep new settings out of the `HookSettings` data class and its serialization paths.
- Whenever adding or changing a setting, add it to the module preset export and import paths at the same time so preset coverage remains complete.

## Localization

- All user-visible text must use `tr(...)`.
- Every added or changed string must be synchronized across Simplified Chinese, Japanese, and English.
- Update `app/src/main/assets/languages/en.json`, `app/src/main/assets/languages/ja.json`, and `languages/example.json` together.
- Validate all three JSON files after editing them.

## Hook Ownership and Refactoring

- Keep `HyperSystemUiModule.kt` as the owner of module lifecycle entry points, target-package/process dispatch, and shared initialization. Do not move these responsibilities merely to reduce its line count.
- Maintain these functional boundaries under `app/src/main/java/btm/m/os4/systemuihook/`:
  - `ControlCenterHooks.kt`: independent Control Center hooks.
  - `NotificationHooks.kt`: independent notification and notification-center hooks.
  - `LockscreenHooks.kt`: independent lockscreen and Keyguard hooks.
  - `StatusBarHooks.kt`: independent status-bar signal, network-label, battery, icon, and layout hooks.
  - `SystemUiHooks.kt`: independent SystemUI hooks outside the preceding four categories, including the migrated dynamic-island groups.
  - `OthersHooks.kt`: independent hooks for other target applications. Keep separate installation boundaries for each application or feature; do not combine them into one large installer.
- Classify hooks by their actual targets, behavior, consumers, and call relationships. Package paths, method names, and keyword matches alone do not determine ownership. AOD and ThemeManager lockscreen/editor functionality must not automatically be classified as Others just because it runs outside SystemUI.
- Before further migration, inspect Git status and existing differences, read the current source and the migration audits in `docs/`, and document the proposed ownership, call sites, target packages/processes, ClassLoaders, dependencies, and risks before editing implementation code.
- Preserve existing work, including uncommitted changes and previously completed fixes. Never reset, revert, or overwrite files to establish a convenient migration baseline.
- Treat classification migrations as structural refactoring unless the user separately requests a behavior change. Preserve target classes and method signatures, argument handling, return values, original-method call counts, callback side effects, registration order, conditions, lifecycle boundaries, ClassLoader sources, configuration keys/defaults, exception handling, and logging.
- Preserve the ownership, scope, and semantics of installation guards, locks, caches, listeners, retry mechanisms, Handlers, and delayed tasks. Move feature-exclusive state and helpers with their implementation; do not leave duplicate implementations, state copies, or registration paths behind.
- Shared functionality must retain one implementation and one state owner. Reuse existing interfaces or narrowly typed callbacks without changing evaluation order or snapshotting mutable state. Preserve lock identity and initialization side effects where observable.
- Do not resolve dependencies by copying shared code, broadly exposing private members, reflecting into the original module's private fields, or introducing circular dependencies. Explain the affected dependencies and scope before making any necessary minimal interface change.
- Leave tightly coupled groups in their current owner when a safe boundary is not established. Record the reason and deferred work rather than forcing extraction. Do not create empty placeholder classes or duplicate functionality already owned by independent classes.
- Keep the completed categories stable. Do not modify unrelated hook classes during another category's migration; any necessary shared-interface change must be explained and independently validated for the affected features.
- The framework package dispatch targets `system`; do not substitute `android` or `system_server`. Keep framework hooks and their helpers in `HyperSystemUiModule.kt` and avoid incidental framework changes. Preserve the existing `onSystemServerStarting()` and `system` package callback boundaries and call sites; do not consolidate, disable, or add installation paths as part of classification work.
- Preserve the existing behavior fixes in `newnotificationcenter/IosShadeMaterial.java` and `hypermusiccover/ShadeLayer.java`. Do not rewrite shared material, corner-radius, iOS Shade, wallpaper-blur cleanup, or media lifecycle logic as part of structural migration.
- Do not introduce on-demand initialization, delayed registration, reflection caches, configuration redesigns, or other performance changes during structural refactoring. Do not delete apparently unused code or activate previously inactive installation paths.
- Respect the requested category and phase boundaries. Validate each requested phase before starting the next, and stop after the authorized scope is complete.

## Migration Verification and Reporting

- Compare migrated implementations and remaining entry points against the pre-change baseline. Check targets, signatures, arguments/results, process dispatch, ClassLoaders, installation order, guard write points, state ownership, retries, scheduling, and original-method call counts.
- Check for duplicate registrations, stale calls, shared-state copies, circular dependencies, and unrelated changes to previously completed hook classes or protected fixes.
- Run `git diff --check` and the required APK build after implementation changes. Keep static review, build/package verification, and device testing distinct; a successful build does not prove runtime behavior in every target process.
- Update the migration audit with the actual moved methods/files, retained shared implementations and owners, deferred groups and reasons, build results, generated APK path, and risks that have not been tested on a device. Do not claim a performance improvement from structural extraction.

## Build Verification

- Build processes are exempt from the requirement to keep temporary files on drive D.
- Build a test APK after implementation changes. At minimum, run `./gradlew :app:assembleDebug` (or `gradlew.bat :app:assembleDebug` on Windows) and report the generated APK path.
- Only use the bare command `.\gradlew.bat :app:assembleDebug` for build testing; strictly prohibit specifying or redirecting paths to any directories such as `gradle-user-home`, `build-tmp`, or similar.
- Do not claim completion when compilation or APK packaging fails.
- Do not stop, terminate, or otherwise interrupt an in-progress build unless the user explicitly authorizes it.

## Release APK Naming

- Before delivering a release build, rename `app-release.apk` to `HyperChanger-v<VersionName>.apk`.
- Use the `versionName` from `app/build.gradle.kts`, replacing spaces with hyphens so the filename contains no spaces.
- Examples: `HyperChanger-v1.1.2.apk`, `HyperChanger-v1.1.3-RC1.apk`, and `HyperChanger-v1.1.4-Fix-2.apk`.

## Release Workflow

- **Release Signing Configuration**:
  - Keystore path: `D:\Documents\android-key\btm-m\btm-m`
  - Key alias: `btm_m`
  - Keep signing passwords in local, ignored configuration; never commit signing secrets.
- **Pre-Publishing Verification**:
  - Do not push commits, tags, or releases to GitHub (`ColdP/HyperChanger`) immediately after generating the release APK.
  - Draft the new release notes matching the style and format of previous releases in the repository.
  - Submit the drafted release notes to the user for review and approval.
- **Publishing to GitHub**:
  - Wait for explicit user confirmation before taking any publishing action.
  - Upon user approval, push the code and tags to `ColdP/HyperChanger`.
  - Create the GitHub Release with the approved release notes and upload the release APK (`HyperChanger-v<VersionName>.apk`).

## ADB & Device Restrictions

- Do not run ADB commands to install packages or reboot/restart services/devices unless explicitly requested by the user.
- Strictly prohibit executing `adb reboot` (including fastboot/recovery reboots).
- Strictly prohibit executing any commands that restart or kill SystemUI (e.g., restarting/killing `com.android.systemui`).

## Storage & File Operations

- The `docs/` directory and every file inside it are local-only and must never be uploaded to any cloud repository. Preserve the `/docs/` ignore rule; do not force-add these files or include them in cloud commits, pull request attachments, or release uploads.

- **No Temporary Files on Drive C**:
  - Do NOT create, store, or output temporary files, caches, intermediate build artifacts, or test scripts on the `C:` drive (including `%TEMP%` or `C:\Users\...`).

## Commit Message Style

- Write commit messages in English.
- Capitalize The First Letter Of Every Significant Word In Commit Descriptions; Keep Articles, Short Prepositions, And Conjunctions Such As `a`, `an`, `the`, `of`, `to`, `in`, `on`, `for`, `and`, `or`, And `as` Lowercase Unless They Begin The Description.
