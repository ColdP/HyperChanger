# iOS shade: dark-mode overlay follow-up

The user reports that the earlier overlay fix works in light mode but the gray overlay
remains in dark mode. This change is an explicitly requested behavior fix, not a migration.

## Source evidence and remaining defect

The inspected OEM `ShadeBlurController.start()` constructs a notification blur provider on
`NotificationPanelView`, separate from the root-window provider and blend backgrounds.
`ShadeBlendBlurController$start$2` updates its mode only when the provider's cached enabled
state changes. Radius and pass-window state have their own update paths and caches.
`ShadeBlendBlurController$configListener$1.onUiModeChanged()` refreshes blend colors; it
does not unconditionally rebuild the complete blur recipe.

The earlier fix passed through `setMiBackgroundBlurMode(0)` but `IosShadeMaterial.apply()`
could still turn an initially disabled full-screen panel on, assign a local type and a
fallback radius of 80, and disable pass-window blur. Subsequent zero-radius and pass-window
requests were also still overridden. A shutdown-only exception therefore did not establish
native ownership of the full-screen recipe across all callback orders.

This is a source-level defect and a plausible contributor to the reported theme-dependent
overlay. No device trace or compositor capture was obtained to prove it is the only cause.

## Change and ownership

- `IosShadeMaterial` passes the full-screen panel's mode/type/radius/pass setters through
  unchanged, including while iOS material is active.
- Those four panel properties are no longer snapshotted, overwritten on activation, or
  replayed on cleanup. This prevents stale restoration as well as initial forced activation.
- Temporary wallpaper-containment handling on that panel remains in the same owner.
  Card, media-background, clear-button and clock handling retain their existing boundaries.
- No new hooks, state owners, settings, lifecycle paths or registration paths were added.
  `ShadeLayer.java`, shared glass tuning, framework hooks and the six category classes are
  unchanged. Earlier wallpaper RenderEffect/transform/clip cleanup is retained.

## Verification

- The new activation assertion failed against the pre-fix production source: the disabled
  full-screen panel was forcibly enabled with local blur.
- `.\tests\ios-shade-material\run.ps1`: **276 checks passed**, compiling production Java
  against Android/Xposed contract fixtures. Coverage includes 20 open/close cycles, native
  panel recipes, zero-radius transitions, heads-up during an active exit, cleanup, latest
  native requests, card restoration and unreadable getters. Recipe variants are synthetic;
  they do not execute Android night-mode resources or the OEM compositor.
- `.\gradlew.bat :app:assembleDebug`: **BUILD SUCCESSFUL in 10s**; 40 tasks, 7 executed,
  33 up-to-date. Existing deprecated API/Gradle warnings remain.
- APK: `D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`.
- `git diff --check`: passed. Hash comparison against the clean pre-change workspace
  confirms the production change is limited to `IosShadeMaterial.java`; remaining changes
  are the regression test and documentation.

## Device verification still required

No APK installation, service restart or device test was performed. Verify both light and
dark mode: notification/control-center exit, heads-up appearance/disappearance, theme
switches with the panel open and closed, rapid cross-panel switching, and reopening.
Since the full-screen recipe now remains native, also verify the appearance of the open
iOS wallpaper, clock, notification cards and media cards with the user's glass settings.
Build success and contract tests do not establish that the reported visual issue is gone
on the target ROM. No performance improvement is claimed.

## Follow-up: overlay recurred in both themes

The user reported the same brief exit overlay and persistent overlay while a heads-up is
visible, after additional changes. The workspace contained unrelated edits and new files;
all existing tracked and non-ignored untracked files were hashed before this follow-up.
The previous full-screen-panel fix was still present in both source and the installed APK.
Read-only device queries confirmed iOS wallpaper, global soft glass, heads-up soft glass,
and unified notification material were enabled. No settings were changed.

A separate cleanup defect was found in the OEM View/ViewRootImpl contract:

1. `View.setPassWindowBlurEnabled(true)` calls `ViewRootImpl.addTextureView`, which inserts
   `true` into its texture-state map regardless of the View's background blur mode.
2. `View.setMiBackgroundBlurMode(0)` updates the texture entry to invisible when the mode
   changes and pass-window blur is enabled.
3. iOS cleanup previously restored background mode first and pass-window blur afterward.
   Thus a card/clear/media background could have correct restored getter values (mode zero,
   pass-window true) while its window texture entry had been reactivated. A heads-up redraw
   exposes a window that would otherwise not be drawn. This defect is theme independent.

Only the material restore order changes in production: restore pass-window blur before
background mode, then retain the original type/radius/containment relative order. The new
order is setter slots `3, 0, 1, 2, 4`. No setter is added or removed, no hooks or scheduling
paths are added, and the previous panel exclusion, unknown-getter handling, and wallpaper
cleanup are retained. Framework hook implementation and dispatch are untouched; framework
source was read solely to establish these API side effects.

The existing View test fixture now models texture registration separately from getter
state. Before the order fix, the new assertion failed with:
`cleanup must not re-register disabled surfaces as visible window blur samplers`.
After the fix, `.\tests\ios-shade-material\run.ps1` passes **332 checks**. Added checks
cover repeated cleanup/heads-up redraw, native enabled/disabled modes with pass-window
on/off, native shutdown before exit completion, and replacement of the shade root.

`.\gradlew.bat :app:assembleDebug`: **BUILD SUCCESSFUL in 11s**, 40 tasks (7 executed,
33 up-to-date), with existing deprecated API/Gradle warnings. The debug APK remains at
`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`.
`git diff --check` passes. Hash comparison limits this follow-up to `IosShadeMaterial.java`,
its regression test/fixture, and this document plus the test README. All unrelated user
changes and `ShadeLayer.java` remain byte-for-byte unchanged from the follow-up baseline.

The device was inspected read-only; no APK was installed and no service/device was
restarted. The new APK's actual light/dark exit and heads-up rendering still requires
device validation. The contract regression proves the stale-registration defect, not
that every possible compositor source of an overlay has been eliminated.
