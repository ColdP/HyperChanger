Run `.\tests\ios-shade-material\run.ps1` from the repository root with a JDK available.
Outputs and JVM temporary files stay under `.reverse-tmp` on the repository drive.

The harness compiles the **production** `IosShadeMaterial.java` with small Android/Xposed
fixtures. It checks native panel shutdown during an active exit, repeated open/close cycles,
layout callbacks, native recipes after close (including heads-up), preservation of the latest
native requests, and getter failures. The fixtures model setter interception and view identity;
they cannot verify OEM RenderNode/compositor rendering or the visual result on a device.
