# Desktop native soft glass — 2026-10-23

## Environment / evidence before implementation

- STATICALLY_VERIFIED: com.miui.home APK 8.01.02.8583, versionCode 801028583; arm64 ELF `.reverse-tmp/launcher-8.01.02.8583/extracted/lib/arm64-v8a/libapp.so`, SHA256 6F5274F435CC07AAB165CE5315D1D35AFAB559A659AFD6200379D309C9D08B91. The separately named device-libapp.so differs and is NOT the source of these RVAs.
- STATICALLY_VERIFIED: material implementation is Dart AOT in libapp.so, not a Rust ABI. Existing C++ native_init loader and folder library own patch installation. NDK 28.2.13676358 / CMake 3.22.1 / C++20; Rust not required. Runtime host page-size allocation remains unchanged.
- UNKNOWN: device model/build, running PID, installed APK identity, actual device ABI/page size and framework version, library loading and visual behavior. No device commands run. Static and APK checks cannot confirm these.
- Reference: HomeTweaks 3.4.261021-2050 Features 40..66 establish the ordered 27 native shader values; existing full V7 shape uniquely identifies RVA 0x17a052c in this ELF.

## Proposed ownership / implementation contract

No category migration. HyperSystemUiModule retains com.miui.home package callback and ClassLoader/process dispatch. HomeRecentsSettings retains shared atomic config transport. New HomeGlass settings files own new keys/local/remote storage and full-version native payload. MainActivity reuses SoftGlassMaterialSliders and existing visibility animations. Existing Dock motion/layout/radius hooks remain untouched.

Native folder_spacing.cpp owns scalar patch registration and lifetime, with a new narrow home_full_glass.h helper. A complete guarded shape validates `_GlassImageFilter` constructor 0x117a2fc (including all fields and epilogue). Its only BL caller is factory 0x117a210. The factory's caller return PC is at [[X29]+8]. Dock PCs 0x1179980 / 0x117ad10 uniquely identify dark/light builder callers. All PCs are image-relative to this version; runtime installation derives the base from the unique existing V7 shape and validates constructor/factory/call-site bytes before patching. Unsupported images fail closed.

Patch 15 scalar stores: fourteen optical controls plus glass blur. Replay one original store exactly once, substituting a temporary adjusted value only for that store, restoring all input GPR/vector registers, SP and NZCV. No Dart calls, allocation, retained heap pointers, callbacks or locks at material evaluation. Separate permanent RX islands and RW data; existing mapping ownership/write checks and disable restoration retained. Dock and desktop select independent records; disabled Dock customization falls back to desktop customization. Following global uses global parameter values regardless of the SystemUI master gate, like other per-surface followers. Custom values persist when toggling global.

New versioned HGLS tail follows MSPL at exact structural boundary; strict length/value validation. Old MTRL adjustment is disabled when HGLS is present, preventing double application. Old payloads stay readable. Global slider/preset changes restage launcher config when a follower is enabled.

## Scope confirmed by the user

The requested complete glass controls are glass blur plus all fourteen optical adjustments. The user explicitly excluded independent background blur, scale and background opacity; these are not glass shader parameters. Both desktop and Dock reuse the complete glass-only component. No inert backdrop sliders or invented mappings are introduced.

Risks: material filters cache until desktop restart/reconstruction; runtime visual and crash behavior require device verification. Scope reads only the verified constructor/factory stack frames. No general stack walking. No changes to unrelated SystemUI material code or HookSettings serialization.


## Implemented / retained boundaries

Added `HomeGlassSettings.kt` (dedicated keys, persistence, remote merge, surface/global resolution), `HomeGlassNativeConfig.kt` (15-value wire records), and `home_full_glass.h` (8583 shape, scalar contract, ARM64 program). Existing launcher blob/transport now carries HGLS v1. `SoftGlassSettingsStore` republishes launcher followers after slider/preset updates; restart tracking includes Desktop only when a global follower is active. MainActivity reuses existing localized glass controls and visibility transitions. Existing `HookSettings.kt`, Dock motion/geometry installers, framework and SystemUI hook owners are unchanged. No extraction or shared state duplication was performed.

The four shape checks are unique in the supplied ELF. Scoping adds no original-function calls. Each replaced scalar store runs exactly once. The first wire value is an absolute blur multiplier (0..200%, with 100% as stock); the native publisher converts it to a delta once. The remaining fourteen values are signed optical adjustments. Inactive groups replay unchanged stock values, including out-of-range theme values, instead of clamping them unnecessarily. Active nonzero values scale stock; zero values use hundredths; feather reverses reflection strength. Darkening defaults/resets to -100% through the common parameter component.

## Validation (2026-10-23)

- `tests/soft-glass/run.ps1`: passed, including actual new preference stores, stale remote recovery, independent Dock/desktop values, global master independence, preserved custom values, global slider/preset publication callbacks, and sixteen Kotlin-generated HGLS wire fixtures.
- Host C++: `g++ -std=c++20 -O2 -Wall -Wextra -Werror -Iapp/src/main/cpp tests/native-home/HomeFullGlassTest.cpp -o .reverse-tmp/HomeFullGlassTest.exe`; run with supplied extracted libapp.so, `.reverse-tmp/home-full-glass-islands.bin`, `.reverse-tmp/soft-glass-tests/home-glass-wire.bin`: passed unique full shapes, all fifteen field contracts, the generated absolute-blur and optical-adjustment configurations, truncated/malformed-tail rejection. The compiler is not installed in this workspace, so this host test was not rerun here.
- `PYTHONPATH=.reverse-tmp/full-glass-python python tests/native-home/HomeFullGlassEmulation.py .reverse-tmp/home-full-glass-islands.bin`: 1800 production ARM64 store executions passed. Checks desktop, both Dock themes, disabled/foreign callers, zero fallback, clamping, X0-X30, full Q0-Q31, SP, NZCV.
- Existing `Dock8583StaticTest.cpp`: passed material/gate uniqueness, allocator chain, ABI, local layout and restore paths.
- All three localization JSON files parsed and contain all sixteen reused glass/switch labels. No new text or translation keys required.
- `git diff --check`: passed.
- Bare required build: `.\gradlew.bat :app:assembleDebug` passed. The first build caught a shared Compose trailing-lambda signature issue; fixed before successful final build. Existing Kotlin deprecation/nullability warnings remain.
- APK: `D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk`, SHA256 `bec2950ce55e45de279b864e407f2167db8e363d7a9668352a7c5fb7187a8e20`.
- Packaged `lib/arm64-v8a/libhyperchanger_folder.so`: SHA256 `9ba6480d1479c9eb1b86932c7cda6486275676ce01dae39870eed43ef10781f7`; ELF64 AArch64, exported native_init, all LOAD segments aligned to 16384. Dock native library packaged and verified likewise.
- DEVICE_VERIFIED: none. Framework loading and resulting visuals remain UNKNOWN; no install, restart, ADB or publishing action performed.

Rollback: disable the desktop material adjustment gate and/or switch Dock away from glass/disable Dock, then use the normal desktop restart flow. Native worker disables groups and restores only matching installed instructions. Permanent islands are retained for threads already executing them.

## Dock parameter source follow-up (2026-10-23)

Dock now selects System default / Global soft glass parameters / Custom. Only Custom shows the existing fifteen glass sliders, with the existing visibility animations. A new dedicated Dock source preference migrates the legacy global switch (true = Global, false = Custom); a fresh installation starts at System default. Local saved choices take precedence over stale remote sources, and switching modes retains custom values. Desktop material controls retain their existing global switch.

System default emits a disabled Dock glass record even when desktop material overrides are enabled, preserving the native Dock shader values. Global mode republishes after global slider/preset changes; System default and Custom do not follow those changes. Inactive Dock adjustment retains the existing desktop fallback. Native code, ABI, shader mappings, process dispatch and HookSettings serialization are unchanged. The preference regression harness covers all three sources, persistence, old local/remote migration, global independence and desktop isolation. Localization adds the source and global-option labels in all three JSON files. Device visual behavior remains unverified.

Follow-up validation: `tests/soft-glass/run.ps1`, all three localization JSON parses, and `git diff --check` passed. Bare `.\gradlew.bat :app:assembleDebug` succeeded (1m 6s); APK: `D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk`. No native changes were made for this follow-up, so previous native analysis is retained without claiming a new device verification.
