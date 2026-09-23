-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# HyperMusicCover's Java runtime reaches this Kotlin bridge by its binary names.
# Keep the small reflection boundary stable without changing any gesture handling.
-keep class btm.m.os4.systemuihook.LockscreenMediaPresentationBridge { *; }
-keep enum btm.m.os4.systemuihook.LockscreenMediaPresentation { *; }
# HyperMusicCover is an Xposed entry point and also uses reflection for its
# SystemUI gesture bridge. Keep the implementation members intact in release
# builds so R8 cannot remove or rename callback helpers used by the runtime.
-keep class btm.m.os4.systemuihook.hypermusiccover.Main { *; }
