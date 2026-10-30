# HyperMusicCover snapshot integration — 2026-10-30

## Baseline and proposed ownership (before implementation)

HyperChanger HEAD: 34f5ce62. Existing uncommitted custom-depth wallpaper work in
HookApplication, HyperSystemUiModule, MainActivity, RestartRecommendationTracker,
the three language JSON files and two new CustomDepthWallpaper files is retained.
The supplied D:/ReverseProject/HyperMusicCover-main is an unpacked source snapshot,
without .git. Its local default versionName (0.0.1) is not a verified release tag;
identify this import by source hashes rather than claiming a release/commit.
Pre-edit files and the source hash inventory are preserved on D: outside the repo.

The requested update changes behavior, not just classification. The new runtime
will live under hypermusiccover/next with upstream root/island/lyrics/immersive/
transit ownership. HyperSystemUiModule keeps lifecycle/package dispatch and the
host default ClassLoader. Its SystemUI call installs the new lockscreen runtime;
MiWallpaper installs its paired wallpaper engine; Apple Music and Amap receive
only their associated lyric/motion/navigation hooks. Framework `system` and
onSystemServerStarting boundaries are unchanged. AOD/ThemeManager restrictions,
colon, depth, fingerprint and global material policies remain with existing
HyperChanger owners rather than installing HyperTweaks a second time.

The old Main remains the owner of existing notification-center registration and
its shared helpers. Preserve ShadeLayer.java and IosShadeMaterial.java verbatim.
Only the legacy notification installer remains active; old music, wallpaper and
capsule installers must be disconnected. Do not install upstream shade logic.
Keep one shade state owner and pass borrowed cover artwork through a narrowly
typed bridge without changing its bitmap lifetime/retirement rules.

Existing capsule/music/lyric keys keep their values. New options belong in a new
dedicated Kotlin store, including normalization and local/remote sync; no edits
to HookSettings. The host UI uses tr and synchronized zh/en/ja dictionaries.
The capsule material bridge evaluates the existing appearance live and reuses
HyperSystemUiModule's material implementation; it must not copy shared glass code.

Dependencies: existing libxposed 102, lyricon 0.1.70 and lyrics-core 0.4.7 match
upstream. Inventory additional runtime/resource dependencies before building.
Use a namespaced action/cache/state/preference identity to avoid collision with
the standalone module. Do not enable upstream hot reload, pickup-code, transit
rear-screen, installer, update or settings application flows as incidental work.

Risks: vendor clock/wallpaper/notification signatures and animation order changed
upstream; old presentation callbacks must not fight the new state machine.
Verify registrations, original-call counts, guards, ClassLoader sources, retained
customizations, local lyric import and APK contents separately from runtime.
No device installation, restart, kill or reboot is authorized.

## Follow-up behavior repair plan

Keep the existing owners and package/ClassLoader dispatch. The host bridge will
separate media observation (music/lyrics/capsule enabled) from automatic scene
entry, and preserve lyric-only scenes with the user's wallpaper. Main/MediaCard
retain the single session and scene owner; the lyric button must work on the plain
OEM card too. MiniPlayerView and ShortcutDisc will consume the existing artwork
radius, including the cover flight endpoint, without adding settings or controls.
Remove legacy notification receiver's obsolete music lifecycle side effects and
exclude hosted shade clocks from the imported clock hooks. Preserve both protected
material files verbatim. Vendor runtime/rendering remains a device-testing risk.

## Implemented ownership and behavior

- Imported 152 source files under `hypermusiccover/next/`; the manifest records each
  source path/hash and adapted file hash. Added four runtime layouts, with the
  pickup/transit layouts retained only for referenced dependency classes.
- `HyperMusicCoverRuntimeBridge` is called by `HyperSystemUiModule.onPackageLoaded`
  with its existing default ClassLoader. The SystemUI process owns the one imported
  media/card/clock/lyrics/capsule runtime. MiWallpaper receives `WallpaperProbe`;
  Apple Music receives its lyric/motion bridge; Amap receives navigation sharing.
  `java_init.list` is unchanged and has no second entry for the new Main.
- Legacy Main's package installer remains SystemUI notification-center only. Its
  former music/wallpaper installers, clock/media lifecycle callbacks, old host
  gesture bridge, old media visibility installer and old capsule installation
  call site are disconnected. Coupled inactive legacy helpers are retained rather
  than extracted or reactivated. Framework `system` dispatch and the original
  `onSystemServerStarting` boundary remain unchanged.
- `LegacyShadeBridge` forwards to the single existing shade configuration/artwork
  owner. Borrowed media artwork is composed into a shade-owned bitmap before the
  old owner may retire/recycle it. `ShadeLayer.java` and `IosShadeMaterial.java`
  are byte-identical to the pre-edit baseline. Hosted shade clocks bypass the new
  container/axis/glyph/color/brightness/palette/notification-Y/glass adjustments.
- `HyperMusicCoverSettings.kt` owns all added preferences, normalization, storage
  and remote synchronization. Updates reread local storage before merging, so the
  cover/lyrics/capsule pages do not overwrite one another. `HookSettings.kt` is
  byte-identical to baseline. Existing master/capsule/appearance keys are reused.
- Host settings apply synchronously through a narrow same-process entry after
  runtime boot. Successful settings are deduplicated. Host-owned fields are not
  restored from the runtime's state file before host preferences are available.
  Lyrics source/appearance settings precede lyric activation. Runtime receivers
  retain peer/query operations but reject standalone settings/debug mutations.
- Media observation stays active when music, lyrics or the capsule is enabled.
  Automatic cover entry uses its own host policy; disabling it no longer disables
  session callbacks, metadata updates, manual artwork entry or lyrics lookup.
  Same-track updates preserve lyric payload refresh without repeated track-change
  side effects. Auto-entry preference changes also invalidate the dispatch cache.
- Lyric-only scenes retain the user's wallpaper. The page button is available on
  the ordinary keyguard media card, opens lyrics from that card and exits a
  lyric-only scene. Async lyric availability refreshes the button. Imported lyric
  parsing and source fallback remain in the new runtime; `HostLyricLibrary` adds
  the existing user library as first source, with provider-change invalidation.
- `MiniPlayerConfig`, `MiniPlayerView`, `ShortcutDisc`, `MiniCardMorph` and the
  controller's cover-flight endpoint consume the existing artwork-radius value.
  The radius persists through geometry refreshes and is capped at half the drawn
  artwork size. Notification icons and shortcut material outlines retain their
  existing shape. No duplicate radius control or new HookSettings field was added.
- Native media visibility stays with the new controller. Existing do-not-hide,
  always-hide and dynamic policies feed its suppression decision; visible native
  cards are not immediately reasserted over a requested suppression. Music scenes
  keep upstream transition ownership.
- The root lockscreen page has one Lockscreen media card, containing Music
  lockscreen and Lockscreen capsule entries. Capsule uses the existing
  `SoftGlassHeaderTabs` in the AppPage header for Appearance / Features. Music
  lockscreen reuses it for Music lockscreen / Lockscreen lyrics. The capsule
  master remains above both tab bodies. Tab choice uses rememberSaveable; stored
  settings remain authoritative when switching tabs.
- Active runtime labels and new UI labels use `tr`; 54 hmc/media-group keys are
  synchronized across en.json, ja.json and languages/example.json. Chinese text
  is supplied by source fallbacks, following the existing language architecture.
- The imported standalone application UI/updater/installer, hot reload, extra
  framework/depth/colon/fingerprint installers, pickup and transit rear-screen
  features are not activated. Transit is removed from ImmersiveHost registration,
  its focus/share installers and peer activation are disabled, and pickup/transit
  enabled state is not restored. Their coupled dependency code is retained.
- Added MaterialKolor material-color-utilities 5.0.0 for the imported HCT use.
  Updated README translations, root/APK NOTICE and in-app license information.
  The supplied snapshot is AGPL-3.0-only with identified GPLv3/Apache portions;
  bundled unmodified upstream NOTICE/AGPL and complete GPLv3 text. Original
  HyperChanger grants and historical Apache headers remain identified.

## Validation completed

- Final command: `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL in 34s,
  40 tasks (7 executed, 33 up-to-date). Kotlin, Java compilation and APK packaging
  passed after the final runtime and dual-tab changes. No build was interrupted.
- APK: `D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk`
  (100,668,918 bytes). SHA-256:
  `66f8521ddaaab1f0782520202d052c435a68d13b1a1412d486c7782b4fd0387b`.
- APK inspection verified the NOTICE and three HyperMusicCover license assets
  against saved sources, unchanged Xposed entry-point list, and DEX presence of
  the new media owner, host settings bridge and local lyric-library adapter.
- `git diff --check` passed. New imported files were also scanned for trailing
  whitespace. All three language JSON files parse, and all added keys are present.
- All 152 original-source hashes match the supplied snapshot. Protected files and
  the two pre-existing CustomDepthWallpaper files match baseline byte-for-byte.
  All pre-existing added source lines and the 15 custom-depth translation changes
  in each dictionary remain present. Unrelated hook-category files are untouched.
- Existing `tests/ios-shade-material/run.ps1`: 332 material restore checks passed.
- Existing `tests/restart-recommendations/run.ps1`: passed after the build. An
  earlier attempt raced the build's class-output refresh and was rerun afterward.
- Adapted upstream portable Java tests: 41 passed (cover style, morph motion/route,
  lyric appearance, focus, transition and style). Portable Kotlin tests: 24 passed
  (host radius/width persistence and bounds, upstream config, geometry and media
  presentation policy). Scratch test sources/dependencies/results are on D: in
  `D:/ReverseProject/.hmc-sync-20261030/tests/`.
- Broader `tests/soft-glass/run.ps1` reaches an existing failure at
  `SoftGlassSettingsStoreTest.kt:238`: expected maximum volume width 60, while the
  current volume implementation clamps to 72. Both the test and implementation
  match HEAD and were not changed by this integration. This unrelated suite is
  not reported as passing.

## Remaining device verification and deferred work

No device installation, reboot, service restart, or SystemUI kill was performed.
Compilation, portable tests and static callback/ownership checks do not establish
vendor runtime behavior. Verify manual and automatic entry, lyric-only mode,
lyrics library edits/source fallback, AOD/wake/unlock, native-card visibility in
all three modes, both capsule arrangements and artwork flight radii on HyperOS.
Verify the two tab headers and vendor material transitions on device as well.
Dormant coupled pickup/transit source remains excluded rather than independently
localized or refactored. No claim of a performance improvement or full device
compatibility is made. No commits/tags/releases were pushed or published.


## Capsule background card follow-up

Moved the background selector, pure-color controls, advanced material controls and
soft-glass parameters into one separate Background material Group/card within the
Appearance tab. Layout/size/artwork radius remain in the Appearance card. Existing
settings, visibility conditions and material callbacks are unchanged. Reused the
existing translated background-material label; all three JSON dictionaries validate.
Only MainActivity UI composition changed; no hook or storage changes.

## Notification-center clock sampling repair

Compared with `origin/main`, the imported clock runtime had excluded the hosted
notification-center clock from HyperLight's `setMiGlass` repair. The hosted clock
now receives the same `glassData` restoration as the cloud implementation; its
notification-center position, notification-Y, cover color, brightness and palette
ownership remain isolated.

Validation after this repair: `tests/ios-shade-material/run.ps1` passed all 332
material restore checks; `git diff --check` passed; and
`./gradlew.bat :app:assembleDebug` completed successfully. Generated APK:
`D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk`.
Device rendering remains pending because no SystemUI restart or device test was
performed.

## Notification-center reopen and grouped-collapse material repair

Rapidly reopening the shade can reuse the existing hosted clock while the exit
animation is still being cancelled. The presentation now re-registers that clock
with `IosShadeMaterial` on every active update, so the wallpaper containment
override is restored before the first reopened frame.

Grouped notifications use the OEM `NotificationChildrenContainer` injector: its
`mCollapsedButton` is the system `collapse_button`, while `mMiuiGroupHeader` owns
the surrounding material. `IosShadeMaterial` now captures both fields, along with
the older `NotificationExpandButton` layout, so the system collapse control and
its header sample the lockscreen wallpaper instead of notification content.

The re-open path also reapplies the hosted clock containment setter even on ROMs
that expose no containment getter. Group controls are discovered through the
notification row/children-container injector fields with a bounded reflection walk;
resource IDs containing `collapse_button` remain a fallback for generic controls.

`git diff --check` passed. Bare `.\gradlew.bat :app:assembleDebug` passed in
1m 46s (40 tasks; 7 executed, 33 up-to-date). `tests/ios-shade-material/run.ps1`
passed 432 material restore checks. Latest APK:
`D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk`
(100673442 bytes), SHA-256
`D77DA83B520AD66F598BE63982A08809F69DD0FB8496322396E03B4BA5F9000B`. Device UI inspection was not performed.

## Notification-center finger-follow animation repair

The OEM schedules `MiuiNotificationPanelAnimControllerImpl`'s row expand animation
before its first `notifyExpandHeightChanged` callback reaches the iOS progress driver.
The animation hooks previously waited for a non-zero custom progress, allowing one
stock Folme entrance animation to run after the finger had already moved. Added an
early interception predicate for the enabled, unlocked notification-center surface;
the existing active predicate still controls custom material and row ownership after
the first frame. This keeps the native cleanup and non-notification animation paths
unchanged while making notification rows follow the custom sheet position from the
first drag frame. Device gesture verification remains pending.

Validation for this repair: `tests/ios-shade-material/run.ps1` passed 432 material
restore checks; `git diff --check` passed; and bare `\.\gradlew.bat :app:assembleDebug`
completed successfully in 4m 56s. APK:
`D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk`
(100673442 bytes), SHA-256
`236D7816CF2374673D76B0AAA06F0852E7975112B757B03A6E475A7A8F56EC87`.
