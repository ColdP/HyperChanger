# Expanded island glass parameters — 2026-10-23

Requested behavior extension, no category migration. Baseline: clean `main`.
Keep lifecycle, package/process dispatch, plugin class discovery and shared material
APIs in HyperSystemUiModule. Existing installDynamicIslandHooks still installs at
the same package callback/discovered plugin ClassLoader boundaries and order.

Plugin evidence: 18.3.2.22.0 DynamicIslandBaseContentView.updateBackgroundBg(View,
boolean) handles expanded and fake-expanded layers, including material-mode changes.
EXPANDED_GLASS_TOKEN supplies the 42-slot recipe. DynamicIslandWindowView.updateWindowBlur
owns native sample radii via MiBackgroundStyle.setBackgroundBlurRadius(View, int,
int, int): classic backdrop 100/0, bionics glass samples 50/500 or zero. The original
updateBackgroundBg still runs once, preserving its arguments, return value, outline,
solid/classic branches and black-to-glass animation. The after-hook changes the glass
recipe only while attached and isBionicsActive. It also invokes the native window
blur update once so edits/disabling apply on the next expansion; that update derives
its enabled state from the vendor's current material-open getter.

Final scope: fifteen current controls (glass blur plus fourteen optical parameters),
independent opacity and global/custom selection. Remove old background blur,
reflection, highlight and self-blur overrides, including their fields/serialization
in NotificationIslandSettings. Existing stored keys are ignored, not migrated into
new controls. Keep the existing opacity key, range 0..100 and default 97. Compose its
percentage with the tuned native alpha so the new optical-alpha control still works.
No backdrop blur/scale/opacity from the global store is used by this feature.

New ExpandedIslandGlassSettings.kt owns new keys, loading, normalization, persistence,
local-authoritative remote recovery and synchronization. Root service reconnection
syncs this store even when the page is closed. HookSettings remains unchanged.
Read current global values independently of the global master switch; switching
back retains all custom values. Reuse SoftGlassMaterialSliders(includeBackdrop=false),
readSoftGlassSettings, softGlassBlur and tuneSoftGlass without changing their mappings.
The new weak surface set and scoped ThreadLocal prevent a second global setter pass;
no view tags, shared configuration copies or registration caches are added.

Only the enabled parent switch exposes the background detail-page entry. It uses
the requested new label. The detail page has the global switch, fifteen custom
controls and independent opacity. New/changed text is synchronized across all three
language JSON files. Restart snapshots include getter defaults for the new store.

No class extraction or new package dispatch path. Existing SystemUiHooks categories,
framework hooks, LockscreenMiniPlayer/capsule material, IosShadeMaterial.java and
ShadeLayer.java are untouched. No performance claim. The weak surface set is the
only new retained target state; vendor tokens are copied before every tune and never
mutated. Blur interception preserves backdrop radius and both zero samples, changes
only glass radii using the same shared mapping, and calls the original once. The
removed self-blur path no longer overrides content/animation blur.

Validation passed: tests/soft-glass/run.ps1, including the new real-store persistence,
stale-remote/fresh-store recovery, global master independence, custom retention,
obsolete-value isolation, fifteen-parameter mapping against the 18.3.2.22.0 token,
alpha/opacity composition and repeated-tune nonmutation checks. Existing shared
soft-glass/preset/QR/capsule/desktop regressions passed. All three language JSONs parse
and include all fifteen control labels plus the new page/switch and opacity labels.
git diff --check passed. Bare .\gradlew.bat :app:assembleDebug passed in 1m 33s.
Generated APK: D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk.
No device commands were run. Device appearance, fake-expanded animation, material-mode
transitions and vendor rendering compatibility remain untested. Settings take effect
on a new material update/expansion; no live observer was added.


## Device crash repair — 2026-10-23

Read-only ADB collection from connected device 7610f98 (pandora, OS4.0.0.37.XBLCNXM).
Ordinary logcat did not include the Java crash, but dumpsys dropbox --print
system_app_crash contained four matching SystemUI crashes at 20:25:48, 20:25:53,
20:25:59 and 20:26:04. Each failed while constructing DynamicIslandWindowView:
InflateException -> InvocationTargetException -> NullPointerException:
"thisObject cannot be null", at installDynamicIslandGlassBlurHook line 3728.
Evidence: .reverse-tmp/island-crash-20261023/systemui-expanded-island-crash.txt.

Root cause: MiBackgroundStyle.setBackgroundBlurRadius is static. The new callback
used Chain.proceedWith(chain.thisObject, args), which requests receiver replacement
and rejects the static method's null thisObject. libxposed API 102 declares separate
proceed(Object[] args) and proceedWith(Object receiver, Object[] args) overloads.
Use chain.proceed(args) to replace arguments while preserving the static receiver.
The fix is confined to this call and its explanatory comment. The fifteen controls,
opacity, original-method call count, ThreadLocal cleanup, target checks and all
unrelated hook implementations remain unchanged.

Added tests/expanded-island-glass-hook/run.ps1: compiles the actual installer extracted
from HyperSystemUiModule against the real libxposed 102 API with a null-receiver chain
contract. Checks the original failure contract, modified blur arguments, preserved
backdrop/view arguments and return value, exactly one native call, disabled/foreign
bypass, zero samples and cleanup when the original throws. Contract regression passed.
Bare .\gradlew.bat :app:assembleDebug passed in 26s; git diff --check passed.
Rebuilt APK: D:/ReverseProject/HyperChanger/app/build/outputs/apk/debug/app-debug.apk.
No installation, process termination or reboot was performed. The rebuilt APK has
not been loaded on the device, so runtime confirmation after replacement is pending.
