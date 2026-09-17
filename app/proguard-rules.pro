-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# HyperMusicCover's Java runtime reaches this Kotlin bridge by its binary names.
# Keep the small reflection boundary stable without changing any gesture handling.
-keep class btm.m.os4.systemuihook.LockscreenMediaPresentationBridge { *; }
-keep enum btm.m.os4.systemuihook.LockscreenMediaPresentation { *; }
