Run `.\tests\ios-shade-material\run.ps1` from the repository root with a JDK available.
Outputs and JVM temporary files stay under `.reverse-tmp` on the repository drive.

The harness compiles the **production** `IosShadeMaterial.java` with small Android/Xposed
fixtures. It checks native panel shutdown during an active exit, repeated open/close cycles,
layout callbacks, native recipes after close (including heads-up), preservation of the latest
native requests, and getter failures. Full-screen panel mode/type/radius/pass properties stay
native during activation, zero-radius transitions, heads-up during an unfinished exit, and
cleanup; only the panel's wallpaper containment remains under temporary iOS ownership.
The recipe variants are synthetic contract checks, not measurements of light/dark ROM themes.
The View fixture also models the OEM pass-window texture registration: enabling pass-window
blur registers a visible texture regardless of background mode, and a subsequent mode change
updates its visibility. Checks cover sampler retirement at close/root replacement, native
on/off recipes, and heads-up redraws after cleanup; matching getter flags alone is insufficient.
The fixtures model setter interception and view identity;
they cannot verify OEM RenderNode/compositor rendering or the visual result on a device.
