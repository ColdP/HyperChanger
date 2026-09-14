// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.leicaunlocker.hook;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import btm.m.leicaunlocker.shared.ModuleConfig;
import io.github.libxposed.api.XposedModule;

public final class LeicaUnlockHook extends XposedModule {
    private static final String TAG = "LeicaUnlocker";
    private static final Object AI_PROFILE_LOCK = new Object();
    private static final String CAMERA_CONFIG_FACTORY = "Je.e";
    private static final String LEGENDARY_VENDOR_TAG = "com.xiaomi.sessionparams.legendMode";
    private static final int LEGENDARY_MODE_M9 = 1;
    private static final int LEGENDARY_MODE_M3 = 2;
    private static final Set<String> EXCLUSIVE_LEICA_WATERMARK_IDS =
            Set.of("88", "89", "90", "91", "92", "111");
    private static final String[] FOCAL_CONFIG_METHODS = {
            "e1", "K0", "v1", "y0", "A1", "C1", "x1", "q0"
    };

    private volatile SharedPreferences preferences;
    private volatile boolean targetProcess;
    /** True while this module instance is running inside Gallery/MediaEditor. */
    private volatile boolean galleryProcess;
    /** Gallery class loaders are not safe to hook while ActivityThread is binding. */
    private volatile boolean galleryWatermarkHooksScheduled;
    /** True only while an AI capability/provider call is evaluated as madrid. */
    private volatile boolean galleryAiProfileActive;
    private final Map<String, String> galleryOriginalBuildValues = new HashMap<>();
    private volatile boolean galleryOriginalBuildCaptured;
    private volatile Object nativeCameraConfig;
    private volatile String nativeDefaultFocal;
    private volatile Object nezhaCameraConfig;
    private final Map<String, Method> nativeFocalMethods = new ConcurrentHashMap<>();
    private final Map<CaptureRequest.Builder, Integer> legendaryBuilders =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Class<?>> galleryWatermarkManagers = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkCapabilityClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkUsageClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkRestrictionClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryModernWatermarkClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryModernCapabilityClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkFragmentClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryPaletteUnlockClasses = ConcurrentHashMap.newKeySet();
    /** MediaEditor AI capability gates that use the madrid device profile. */
    private final Set<Class<?>> galleryAiUnlockClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryAiProviderClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryLimitationPredicateClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkFilterClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkSupportedListClasses = ConcurrentHashMap.newKeySet();
    /** Lookup fallback for IDs removed by the 2.10.40 catalog filters. */
    private final Set<Class<?>> galleryWatermarkLookupClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryCloudWatermarkMakerClasses = ConcurrentHashMap.newKeySet();
    /** MediaEditor watermark EXIF/parameter validators (o80.j in 2.10.40). */
    private final Set<Class<?>> galleryWatermarkExifClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkDataLoaderClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkAvailabilityClasses = ConcurrentHashMap.newKeySet();
    /** Cloud watermark config filter (version/device/time restrictions). */
    private final Set<Class<?>> galleryWatermarkConfigFilterClasses = ConcurrentHashMap.newKeySet();
    /** Final per-item parameter/device restriction result (w60.x0). */
    private final Set<Class<?>> galleryWatermarkSelectionRestrictionClasses = ConcurrentHashMap.newKeySet();
    /** New 2.10.40 capability/brand gate (p382nt.C11802a). */
    private final Set<Class<?>> galleryWatermarkBrandCapabilityClasses = ConcurrentHashMap.newKeySet();
    /** Classes found by structural watermark signatures (survives obfuscation/package moves). */
    private final Set<Class<?>> galleryGenericWatermarkClasses = ConcurrentHashMap.newKeySet();
    /** Device capability helpers used by the watermark catalog. */
    private final Set<Class<?>> galleryWatermarkDeviceClasses = ConcurrentHashMap.newKeySet();
    /** Additional device predicates used by the 2.10.40 watermark feature dex. */
    private final Set<Class<?>> galleryWatermarkDevicePredicateClasses = ConcurrentHashMap.newKeySet();
    /** Per-item kl0 predicates (version/device/region filters) in newer dexes. */
    private final Set<Class<?>> galleryWatermarkItemPredicateClasses = ConcurrentHashMap.newKeySet();
    /** k0.g() may run before the feature-dex predicate hooks are installed. */
    private final Set<Class<?>> galleryWatermarkInitClasses = ConcurrentHashMap.newKeySet();
    private Method cameraConfigGetter;
    private Field cameraConfigCacheField;
    private Class<?> deviceSelectorClass;
    private Object deviceConfigLazy;
    private Map<Field, Object> deviceConfigEvaluatedState;
    private Field deviceConfigValueField;
    private Field modernConfigCacheField;
    private Field modernConfigDeviceField;
    private boolean modernConfigProvider;
    private volatile boolean modernDeviceSelectorOverride;
    private boolean cameraFactoryTouched;
    private volatile boolean galleryWatermarkClassLoadHookInstalled;
    private volatile boolean galleryWatermarkJsonHookInstalled;
    private volatile CaptureRequest.Key<Integer> legendaryVendorKey;
    private volatile boolean nativeFocalDefaultHookInstalled;
    private volatile boolean nativeFocalComponentHookInstalled;
    private volatile boolean nativeFocalPreferenceHookInstalled;
    private volatile boolean nativeFocalRuntimeProbeAttempted;
    private volatile boolean nativeFocalRetryScheduled;
    private volatile boolean nativeFocalCaptureFailureLogged;
    private volatile boolean nativeFocalHookFailureLogged;
    private volatile boolean cameraClassLoadHookInstalled;
    private final Set<ClassLoader> cameraClassLoaders =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        targetProcess = ModuleConfig.isSupportedProcess(param.getProcessName());
        if (!targetProcess) {
            detach();
            return;
        }

        try {
            preferences = getRemotePreferences(ModuleConfig.PREFERENCE_GROUP);
            galleryProcess = isGalleryProcess(param.getProcessName());
            log(Log.INFO, TAG, "Loaded in " + param.getProcessName() + " with API " + getApiVersion());
        } catch (RuntimeException error) {
            log(Log.WARN, TAG, "Remote preferences are unavailable; using enabled defaults", error);
        }
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!btm.m.os4.systemuihook.OsCompatibility.areHooksAllowed()) {
            return;
        }
        if (!isTargetPackage(param.getPackageName(), param.isFirstPackage())) {
            return;
        }
        if (ModuleConfig.isGalleryPackage(param.getPackageName())) {
            galleryProcess = true;
            if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                    || pref(ModuleConfig.KEY_GALLERY_PALETTE_UNLOCKED, false)) {
                if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                    applyGalleryWatermarkBuildProfile();
                }
                installSystemPropertyHooks();
            }
            installGalleryWatermarkHooksForChain(param.getDefaultClassLoader());
            return;
        }
        if (ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            installLeicaUiHooks(param.getDefaultClassLoader());
            installDeferredCameraClassHook(param.getDefaultClassLoader());
        }
        if (!isEnabled()) {
            return;
        }
        if (!ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        boolean preserveNativeFocalLengths = pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true);

        if (preserveNativeFocalLengths) {
            captureNativeFocalLengthConfig(param.getDefaultClassLoader());
            captureNativeFocalPreference(param.getDefaultClassLoader());
            installNativeFocalPreferenceHook(param.getDefaultClassLoader());
        }
        installSystemPropertyHooks();
        if (pref(ModuleConfig.KEY_LEICA_UI, true)) {
            applyBuildProfile();
        }
        if (preserveNativeFocalLengths
                && nativeCameraConfig != null
                && activateNezhaCameraConfig()) {
            installNativeFocalLengthHooks();
            installNativeWatermarkLabelHook();
        } else if (cameraFactoryTouched) {
            resetCameraFactoryForNezha();
        }
        // Install before Application.onCreate/first camera mode initialization;
        // PackageReady can arrive after v0 has already populated its focal array.
        installNativeFocalDefaultValueHook(param.getDefaultClassLoader());
        installNativeFocalComponentHook(param.getDefaultClassLoader());
        scheduleCameraFocalHookRetries(param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!isTargetPackage(param.getPackageName(), param.isFirstPackage())) {
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        if (ModuleConfig.isGalleryPackage(param.getPackageName())) {
            galleryProcess = true;
            if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                    || pref(ModuleConfig.KEY_GALLERY_PALETTE_UNLOCKED, false)) {
                if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                    applyGalleryWatermarkBuildProfile();
                }
                installSystemPropertyHooks();
            }
            installGalleryWatermarkHooksForChain(classLoader);
            return;
        }
        if (!ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        installLeicaUiHooks(classLoader);
        installDeferredCameraClassHook(classLoader);
        installNativeFocalDefaultValueHook(classLoader);
        installNativeFocalComponentHook(classLoader);
        if (pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
            installNativeFocalPreferenceHook(classLoader);
        }
        scheduleCameraFocalHookRetries(classLoader);
        // Watermark catalog and model-limit hooks are independent of the camera
        // master switch. The switch only gates the camera profile/security hooks.
        installSecurityCompatibilityHooks(classLoader);
        installExclusiveWatermarkFilterHook(classLoader);
        installCameraWatermarkCatalogHooks(classLoader);
        installLegendaryFallbackHook();
        installCameraFeatureHooks(classLoader);
    }

    private boolean isTargetPackage(String packageName, boolean firstPackage) {
        // PackageReady can be delivered after another package initialized the
        // process (notably MediaEditor :photo_editor/:editor_service). Requiring
        // firstPackage here silently skipped the watermark module.
        return targetProcess && ModuleConfig.isSupportedPackage(packageName);
    }

    private boolean isGalleryProcess(String processName) {
        return processName != null && (
                processName.equals(ModuleConfig.GALLERY_PACKAGE)
                        || processName.startsWith(ModuleConfig.GALLERY_PACKAGE + ":")
                        || processName.equals(ModuleConfig.GALLERY_PLUGIN_PACKAGE)
                        || processName.startsWith(ModuleConfig.GALLERY_PLUGIN_PACKAGE + ":")
                        || processName.equals(ModuleConfig.MEDIA_EDITOR_PACKAGE)
                        || processName.startsWith(ModuleConfig.MEDIA_EDITOR_PACKAGE + ":"));
    }

    private boolean isEnabled() {
        return pref(ModuleConfig.KEY_MASTER_ENABLED, true);
    }

    private boolean pref(String key, boolean defaultValue) {
        SharedPreferences current = preferences;
        return current == null ? defaultValue : current.getBoolean(key, defaultValue);
    }

    private int prefInt(String key, int defaultValue) {
        SharedPreferences current = preferences;
        return current == null ? defaultValue : current.getInt(key, defaultValue);
    }

    private void installSystemPropertyHooks() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties", false, null);
            hookPropertyGetter(systemProperties.getDeclaredMethod("get", String.class), "system_property_get");
            hookPropertyGetter(
                    systemProperties.getDeclaredMethod("get", String.class, String.class),
                    "system_property_get_default"
            );
            log(Log.INFO, TAG, "System property profile hooks installed");
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, TAG, "Unable to install SystemProperties hooks", error);
        }
    }

    private void installLeicaUiHooks(ClassLoader classLoader) {
        try {
            Class<?> config = Class.forName("Te.b", false, classLoader);
            Method method = config.getDeclaredMethod("W");
            method.setAccessible(true);
            hook(method)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("camera_leica_ui_gate")
                    .intercept(chain -> !isEnabled() || !pref(ModuleConfig.KEY_LEICA_UI, true)
                            ? Boolean.FALSE : Boolean.TRUE);
            log(Log.INFO, TAG, "Camera Leica UI gate hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook Te.b.W Leica UI gate", error);
        }
    }

    private void hookPropertyGetter(Method method, String id) {
        method.setAccessible(true);
        hook(method)
                .setPriority(PRIORITY_HIGHEST)
                .setId(id)
                .intercept(chain -> {
                    if (!isEnabled()) {
                        return chain.proceed();
                    }
                    String key = (String) chain.getArg(0);
                    String replacement = ModuleConfig.propertyOverride(
                            key,
                            pref(ModuleConfig.KEY_LEICA_UI, true)
                    );
                    if (galleryProcess) {
                        String galleryReplacement = galleryAiProfileActive
                                ? ModuleConfig.aiPropertyOverride(key)
                                : (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                ? ModuleConfig.galleryWatermarkPropertyOverride(key) : null);
                        if (galleryReplacement != null) {
                            replacement = galleryReplacement;
                        }
                    }
                    return replacement != null ? replacement : chain.proceed();
                });
    }

    /** AI-only identity used while MediaEditor evaluates capability gates. */
    private void applyGalleryAiBuildProfile() {
        captureGalleryOriginalBuildValues();
        galleryAiProfileActive = true;
        Map<String, String> values = Map.of(
                "DEVICE", "madrid",
                "PRODUCT", "madrid",
                "MODEL", "Xiaomi 18 Pro Max",
                "BRAND", "Xiaomi",
                "MANUFACTURER", "Xiaomi"
        );
        for (Map.Entry<String, String> entry : values.entrySet()) {
            setStaticStringField(Build.class, entry.getKey(), entry.getValue());
        }
        log(Log.INFO, TAG, "Gallery AI build profile applied: Xiaomi 18 Pro Max / madrid");
    }

    /**
     * The working 2.10.40.3.1 mod replaces Build.DEVICE with lhasa in the
     * watermark code. Keep this profile independent from the hongkong palette
     * switch: watermark availability is not tied to the new editor palette.
     */
    private void applyGalleryWatermarkBuildProfile() {
        captureGalleryOriginalBuildValues();
        galleryAiProfileActive = false;
        setStaticStringField(Build.class, "DEVICE", "lhasa");
        setStaticStringField(Build.class, "PRODUCT", "lhasa");
        restoreGalleryOriginalBuildValue("MODEL");
        restoreGalleryOriginalBuildValue("BRAND");
        restoreGalleryOriginalBuildValue("MANUFACTURER");
        log(Log.INFO, TAG, "Gallery watermark build profile applied: lhasa");
    }

    private void captureGalleryOriginalBuildValues() {
        if (galleryOriginalBuildCaptured) {
            return;
        }
        synchronized (galleryOriginalBuildValues) {
            if (galleryOriginalBuildCaptured) {
                return;
            }
            for (String name : new String[]{"DEVICE", "PRODUCT", "MODEL", "BRAND", "MANUFACTURER"}) {
                String value = readStaticStringField(Build.class, name);
                if (value != null) {
                    galleryOriginalBuildValues.put(name, value);
                }
            }
            galleryOriginalBuildCaptured = true;
        }
    }

    private void restoreGalleryOriginalBuildValue(String name) {
        String value;
        synchronized (galleryOriginalBuildValues) {
            value = galleryOriginalBuildValues.get(name);
        }
        if (value != null) {
            setStaticStringField(Build.class, name, value);
        }
    }

    private void restoreGalleryOriginalBuildProfile() {
        galleryAiProfileActive = false;
        for (String name : new String[]{"DEVICE", "PRODUCT", "MODEL", "BRAND", "MANUFACTURER"}) {
            restoreGalleryOriginalBuildValue(name);
        }
    }

    private void installCameraFeatureHooks(ClassLoader classLoader) {
        try {
            Class<?> configUtil = Class.forName("com.android.camera.data.data.o", false, classLoader);
            Method motionSupport = configUtil.getDeclaredMethod("H", int.class);
            motionSupport.setAccessible(true);
            hook(motionSupport)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("motion_capture_support")
                    .intercept(chain -> isEnabled() || Boolean.TRUE.equals(chain.proceed()));
            log(Log.INFO, TAG, "Camera motion-capture capability hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook motion-capture capability", error);
        }
        try {
            // H() calls U() again from the slow-motion shutter path.  On 6.7
            // U() is the device/component gate, so bypass it as well or the
            // feature is still rejected after the capability check succeeds.
            Class<?> configUtil = Class.forName("com.android.camera.data.data.o", false, classLoader);
            Method motionSwitch = configUtil.getDeclaredMethod("U", int.class);
            motionSwitch.setAccessible(true);
            hook(motionSwitch)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("motion_capture_switch")
                    .intercept(chain -> isEnabled() || Boolean.TRUE.equals(chain.proceed()));
            log(Log.INFO, TAG, "Camera motion-capture switch hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook motion-capture switch", error);
        }
        try {
            // The request writer checks the camera-characteristics key map
            // before emitting xiaomi.motiondetection.enabled.  Some 6.7
            // devices omit that vendor key even though the implementation is
            // present, so expose this one capability without changing any
            // other characteristic checks.
            Class<?> capabilities = Class.forName("p468n9.C4678f", false, classLoader);
            Method hasKey = capabilities.getDeclaredMethod("S0", String.class);
            hasKey.setAccessible(true);
            hook(hasKey)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("motion_capture_vendor_capability")
                    .intercept(chain -> chain.proceed());
            log(Log.INFO, TAG, "Camera motion-capture vendor capability hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook motion-capture vendor capability", error);
        }
        try {
            Class<?> liveSupport = Class.forName("Wr.C2887n", false, classLoader);
            Method support = liveSupport.getDeclaredMethod("a");
            support.setAccessible(true);
            hook(support)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("live_photo_support")
                    .intercept(chain -> chain.proceed());
            log(Log.INFO, TAG, "Camera Live Photo capability hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook Live Photo capability", error);
        }
    }

    private void applyBuildProfile() {
        Map<String, String> values = Map.of(
                "DEVICE", "nezha",
                "PRODUCT", "nezha",
                "MODEL", "25128PNA1C",
                "BRAND", "Xiaomi",
                "MANUFACTURER", "Xiaomi"
        );

        for (Map.Entry<String, String> entry : values.entrySet()) {
            setStaticStringField(Build.class, entry.getKey(), entry.getValue());
        }
        log(Log.INFO, TAG, "Build profile applied: nezha / 25128PNA1C");
    }

    private void captureNativeFocalLengthConfig(ClassLoader classLoader) {
        try {
            Class.forName(CAMERA_CONFIG_FACTORY, true, classLoader);
            captureLegacyNativeFocalLengthConfig(classLoader);
            // Je.e is still present in 6.7, but it is an unrelated utility
            // class.  Only stop here when the legacy capture actually worked.
            if (nativeCameraConfig != null) {
                return;
            }
            log(Log.INFO, TAG, "Legacy camera configuration capture produced no config; trying modern provider");
        } catch (ClassNotFoundException ignored) {
            log(Log.INFO, TAG, "Legacy camera configuration factory is absent; trying modern provider");
        } catch (LinkageError error) {
            log(Log.INFO, TAG, "Legacy camera configuration factory is unavailable; trying modern provider", error);
        }

        captureModernNativeFocalLengthConfig(classLoader);
    }

    /**
     * Camera 6.7 initializes the main-camera focal preference from the real
     * CameraCharacteristics.  Capture it before any device/profile spoofing
     * so a first-run empty preference cannot be initialized from the Nezha
     * profile instead.
     */
    private void captureNativeFocalPreference(ClassLoader classLoader) {
        try {
            Class<?> deviceRegistry = Class.forName("p827x6.e", true, classLoader);
            Object registry = deviceRegistry.getDeclaredMethod("V").invoke(null);
            // v0.getDefaultValue() uses Vr.c.c(), which includes the 6.7
            // fallback for devices whose registry has not selected a camera yet.
            Class<?> cameraIdResolver = Class.forName("Vr.c", true, classLoader);
            int cameraId = ((Number) cameraIdResolver.getDeclaredMethod("c").invoke(null)).intValue();
            if (cameraId < 0) {
                cameraId = ((Number) deviceRegistry.getDeclaredMethod("g").invoke(registry)).intValue();
            }
            Object capabilities = deviceRegistry.getDeclaredMethod("Q", int.class).invoke(registry, cameraId);
            Class<?> capabilitiesUtil = Class.forName("p468n9.C4681g", true, classLoader);
            float focalRatio = ((Number) capabilitiesUtil
                    .getDeclaredMethod("s", Class.forName("p468n9.C4678f", false, classLoader))
                    .invoke(null, capabilities)).floatValue();
            Class<?> focalUtil = Class.forName("Bl.l", true, classLoader);
            float focalMillimeters = ((Number) focalUtil
                    .getDeclaredMethod("k1", float.class)
                    .invoke(null, focalRatio)).floatValue();
            int rounded = Math.round(focalMillimeters);
            if (rounded >= 5 && rounded <= 200) {
                nativeDefaultFocal = String.valueOf(rounded);
                installNativeFocalPreferenceHook(classLoader);
                log(Log.INFO, TAG, "Captured native default focal preference: " + nativeDefaultFocal);
            } else {
                log(Log.WARN, TAG, "Ignoring invalid native focal value: " + rounded);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (!nativeFocalCaptureFailureLogged) {
                nativeFocalCaptureFailureLogged = true;
                log(Log.INFO, TAG, "Native focal preference is not ready yet; deferred retry will continue");
            }
        }
    }

    private void scheduleCameraFocalHookRetries(ClassLoader initialLoader) {
        if (nativeFocalRetryScheduled || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
            return;
        }
        nativeFocalRetryScheduled = true;
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] task = new Runnable[1];
        task[0] = new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (!targetProcess || !isEnabled()
                        || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                    return;
                }
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                if (loader == null) {
                    loader = initialLoader;
                }
                rememberThreadContextClassLoaders();
                if (nativeDefaultFocal == null) {
                    captureNativeFocalPreference(loader);
                }
                installNativeFocalPreferenceHook(loader);
                installNativeFocalDefaultValueHook(loader);
                installNativeFocalComponentHook(loader);
                if (nativeFocalDefaultHookInstalled && nativeFocalComponentHookInstalled
                        && nativeFocalPreferenceHookInstalled) {
                    return;
                }
                if (++attempts < 120) {
                    handler.postDelayed(task[0], 250L);
                } else {
                    log(Log.INFO, TAG, "Camera focal hook retry window expired");
                }
            }
        };
        handler.post(task[0]);
    }

    private void installNativeFocalPreferenceHook(ClassLoader classLoader) {
        if (nativeFocalPreferenceHookInstalled) {
            return;
        }
        try {
            Class<?> settings = Class.forName("com.android.camera.data.data.z", false, classLoader);
            Method getter = settings.getDeclaredMethod("o");
            getter.setAccessible(true);
            hook(getter)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_default_focal_preference")
                    .intercept(chain -> {
                        ClassLoader runtimeLoader = settings.getClassLoader();
                        rememberCameraClassLoader(runtimeLoader);
                        if (nativeDefaultFocal == null && !nativeFocalRuntimeProbeAttempted) {
                            nativeFocalRuntimeProbeAttempted = true;
                            captureNativeFocalPreference(runtimeLoader);
                        }
                        installNativeFocalDefaultValueHook(runtimeLoader);
                        installNativeFocalComponentHook(runtimeLoader);
                        Object result = chain.proceed();
                        String captured = nativeDefaultFocal;
                        String current = result == null ? "" : String.valueOf(result);
                        if (isEnabled()
                                && pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)
                                && captured != null
                                && (current.isEmpty() || "23".equals(current))) {
                            return captured;
                        }
                        return result;
                    });
            nativeFocalPreferenceHookInstalled = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            logFocalHookFailure("Unable to hook native default focal preference", error);
        }
    }

    /** Directly fixes the 6.7 focal selector, whose empty preference is
     * initialized after the camera profile has already been spoofed. */
    private void installNativeFocalDefaultValueHook(ClassLoader classLoader) {
        if (nativeFocalDefaultHookInstalled) {
            return;
        }
        try {
            Class<?> focalData = resolveCameraClass("p785w2.v0", classLoader);
            Method getter = focalData.getDeclaredMethod("getDefaultValue", int.class);
            getter.setAccessible(true);
            hook(getter)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_default_focal_value_67")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!isEnabled() || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                            return result;
                        }
                        int mode = ((Number) chain.getArg(0)).intValue();
                        if (mode != 163 && mode != 168 && mode != 231 && mode != 256) {
                            return result;
                        }
                        String current = result == null ? "" : String.valueOf(result);
                        if (!current.isEmpty() && !"1.0".equals(current) && !"23".equals(current)) {
                            return result;
                        }
                        String captured = nativeDefaultFocal;
                        if (captured == null) {
                            captureNativeFocalPreference(classLoader);
                            captured = nativeDefaultFocal;
                        }
                        if (captured == null) {
                            try {
                                Class<?> resolver = Class.forName("Vr.c", true, classLoader);
                                int cameraId = ((Number) resolver.getDeclaredMethod("c").invoke(null)).intValue();
                                Float focal = readNativeFocalMillimeters(classLoader, cameraId);
                                if (focal != null) {
                                    captured = String.valueOf(Math.round(focal));
                                    nativeDefaultFocal = captured;
                                }
                            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                                // Keep the original result when the registry is not ready yet.
                            }
                        }
                        if (captured == null) {
                            return result;
                        }
                        // v0.getDefaultValue() returns a zoom ratio (1.0, 1.1, ...),
                        // while the captured preference is the native focal length
                        // in millimeters (23, 24, ...).
                        return nativeFocalZoomRatio(captured, result);
                    });
            nativeFocalDefaultHookInstalled = true;
            log(Log.INFO, TAG, "Camera 6.7 native focal default hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            logFocalHookFailure("Unable to hook p785w2.v0.getDefaultValue", error);
        }
    }

    /** Camera 6.7 keeps obfuscated classes in a secondary dex that is loaded
     * after both package lifecycle callbacks. Resolve focal hooks when the
     * actual camera ClassLoader loads those classes. */
    private void installDeferredCameraClassHook(ClassLoader initialLoader) {
        rememberCameraClassLoader(initialLoader);
        if (cameraClassLoadHookInstalled) {
            return;
        }
        synchronized (this) {
            if (cameraClassLoadHookInstalled) {
                return;
            }
            try {
                Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
                loadClass.setAccessible(true);
                hook(loadClass)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("camera_dynamic_class_load2")
                        .intercept(chain -> {
                            Object loaded = chain.proceed();
                            rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                    ? (ClassLoader) chain.getThisObject() : null);
                            installDeferredCameraClass(loaded, chain.getArg(0));
                            return loaded;
                        });

                Method loadClassSimple = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
                loadClassSimple.setAccessible(true);
                hook(loadClassSimple)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("camera_dynamic_class_load1")
                        .intercept(chain -> {
                            Object loaded = chain.proceed();
                            rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                    ? (ClassLoader) chain.getThisObject() : null);
                            installDeferredCameraClass(loaded, chain.getArg(0));
                            return loaded;
                        });
                try {
                    Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, null);
                    Method findClass = baseDex.getDeclaredMethod("findClass", String.class);
                    findClass.setAccessible(true);
                    hook(findClass)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("camera_dynamic_class_find")
                            .intercept(chain -> {
                                Object loaded = chain.proceed();
                                rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                        ? (ClassLoader) chain.getThisObject() : null);
                                installDeferredCameraClass(loaded, chain.getArg(0));
                                return loaded;
                            });
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    log(Log.INFO, TAG, "BaseDexClassLoader.findClass hook unavailable for camera");
                }
                // Camera 6.7 creates a split DexClassLoader after package callbacks;
                // retain it so focal classes can be resolved even if they are
                // initialized before the ClassLoader.loadClass hook sees them.
                try {
                    Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, null);
                    for (Constructor<?> constructor : baseDex.getDeclaredConstructors()) {
                        constructor.setAccessible(true);
                        hook(constructor)
                                .setPriority(PRIORITY_HIGHEST)
                                .setId("camera_dynamic_loader_ctor_"
                                        + Integer.toHexString(constructor.toGenericString().hashCode()))
                                .intercept(chain -> {
                                    Object created = chain.proceed();
                                    rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                            ? (ClassLoader) chain.getThisObject() : null);
                                    return created;
                                });
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    log(Log.INFO, TAG, "BaseDexClassLoader constructor hook unavailable for camera", error);
                }
                cameraClassLoadHookInstalled = true;
                log(Log.INFO, TAG, "Camera dynamic class load hook installed");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Unable to install camera dynamic class load hook", error);
            }
        }
    }

    private void rememberCameraClassLoader(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        synchronized (cameraClassLoaders) {
            cameraClassLoaders.add(loader);
        }
    }

    private void installDeferredCameraClass(Object loadedClass, Object name) {
        if (!(loadedClass instanceof Class<?> loaded) || !(name instanceof String className)) {
            return;
        }
        if (className.startsWith("p785w2") || className.startsWith("p827x6")
                || className.contains("ComponentRunningSwitchZoom")) {
            log(Log.INFO, TAG, "Camera class resolved: " + className + " via " + loaded.getClassLoader());
        }
        if (!"p785w2.v0".equals(className)
                && !"p827x6.e".equals(className)
                && !"com.android.camera.data.data.z".equals(className)) {
            return;
        }
        ClassLoader loader = loaded.getClassLoader();
        rememberCameraClassLoader(loader);
        log(Log.INFO, TAG, "Camera focal class loaded: " + className + " via " + loader);
        if ("p827x6.e".equals(className) || "p785w2.v0".equals(className)) {
            captureNativeFocalPreference(loader);
        }
        installNativeFocalDefaultValueHook(loader);
        installNativeFocalComponentHook(loader);
    }

    /**
     * Keeps the focal values used to build the zoom selector tied to the real
     * CameraCharacteristics even after the Nezha profile is selected.
     */
    private void installNativeFocalComponentHook(ClassLoader classLoader) {
        if (nativeFocalComponentHookInstalled) {
            return;
        }
        try {
            Class<?> focalData = resolveCameraClass("p785w2.v0", classLoader);
            Method focalReader = focalData.getDeclaredMethod("p", int.class);
            focalReader.setAccessible(true);
            hook(focalReader)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_focal_component_67")
                    .intercept(chain -> {
                        if (!isEnabled() || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                            return chain.proceed();
                        }
                        int cameraId = ((Number) chain.getArg(0)).intValue();
                        Float nativeFocal = readNativeFocalMillimeters(classLoader, cameraId);
                        return nativeFocal == null ? chain.proceed() : nativeFocal;
                    });
            nativeFocalComponentHookInstalled = true;
            log(Log.INFO, TAG, "Camera 6.7 native focal component hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            logFocalHookFailure("Unable to hook p785w2.v0.p", error);
        }
    }

    private void logFocalHookFailure(String message, Throwable error) {
        if (!nativeFocalHookFailureLogged) {
            nativeFocalHookFailureLogged = true;
            log(Log.INFO, TAG, message + "; waiting for camera Dex loader");
        }
    }

    private Class<?> resolveCameraClass(String name, ClassLoader preferred)
            throws ClassNotFoundException {
        rememberThreadContextClassLoaders();
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>();
        addLoaderChain(candidates, preferred);
        addLoaderChain(candidates, Thread.currentThread().getContextClassLoader());
        synchronized (cameraClassLoaders) {
            for (ClassLoader loader : cameraClassLoaders) {
                addLoaderChain(candidates, loader);
            }
        }
        ClassNotFoundException last = null;
        for (ClassLoader loader : candidates) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ClassNotFoundException(name);
    }

    private void rememberThreadContextClassLoaders() {
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread != null) {
                    rememberCameraClassLoader(thread.getContextClassLoader());
                }
            }
        } catch (SecurityException ignored) {
            // Thread enumeration can be restricted on some vendor builds.
        }
    }

    private void addLoaderChain(Set<ClassLoader> candidates, ClassLoader loader) {
        ClassLoader current = loader;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (!candidates.add(current)) {
                break;
            }
            current = current.getParent();
        }
    }

    private Float readNativeFocalMillimeters(ClassLoader classLoader, int cameraId) {
        try {
            Class<?> deviceRegistry = Class.forName("p827x6.e", true, classLoader);
            Object registry = deviceRegistry.getDeclaredMethod("V").invoke(null);
            Object capabilities = deviceRegistry.getDeclaredMethod("Q", int.class).invoke(registry, cameraId);
            if (capabilities == null) {
                return null;
            }
            Class<?> capabilitiesUtil = Class.forName("p468n9.C4681g", true, classLoader);
            Class<?> capabilitiesType = Class.forName("p468n9.C4678f", false, classLoader);
            float focalRatio = ((Number) capabilitiesUtil
                    .getDeclaredMethod("s", capabilitiesType)
                    .invoke(null, capabilities)).floatValue();
            Class<?> focalUtil = Class.forName("Bl.l", true, classLoader);
            float focalMillimeters = ((Number) focalUtil
                    .getDeclaredMethod("k1", float.class)
                    .invoke(null, focalRatio)).floatValue();
            return focalMillimeters >= 5.0f && focalMillimeters <= 200.0f
                    ? (float) Math.round(focalMillimeters) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to read native focal length for camera " + cameraId, error);
            return null;
        }
    }

    private String nativeFocalZoomRatio(String millimeters, Object fallback) {
        try {
            float focal = Float.parseFloat(millimeters);
            if (focal < 5.0f || focal > 200.0f) {
                return fallback == null ? millimeters : String.valueOf(fallback);
            }
            float ratio = Math.round((focal / 23.0f) * 10.0f) / 10.0f;
            return String.valueOf(ratio);
        } catch (RuntimeException error) {
            return fallback == null ? millimeters : String.valueOf(fallback);
        }
    }

    private void captureLegacyNativeFocalLengthConfig(ClassLoader classLoader) {
        Map<Field, Object> lazyState = null;
        Object lazy = null;
        Field cachedConfig = null;
        try {
            Class<?> selectorClass = Class.forName("Je.a", true, classLoader);
            lazy = findDeviceConfigLazy(selectorClass);
            lazyState = snapshotMutableFields(lazy);
            Class<?> factoryClass = Class.forName(CAMERA_CONFIG_FACTORY, true, classLoader);
            Method getConfig = factoryClass.getDeclaredMethod("G0");
            getConfig.setAccessible(true);
            cachedConfig = factoryClass.getDeclaredField("b");
            cachedConfig.setAccessible(true);

            cameraFactoryTouched = true;
            Object localConfig = getConfig.invoke(null);
            if (localConfig == null) {
                throw new IllegalStateException("The native camera configuration is null");
            }

            Map<Field, Object> evaluatedState = snapshotMutableFields(lazy);
            Field valueField = findEvaluatedStringField(evaluatedState);
            cachedConfig.set(null, null);
            restoreMutableFields(lazy, lazyState);
            cameraConfigGetter = getConfig;
            cameraConfigCacheField = cachedConfig;
            modernConfigProvider = false;
            deviceSelectorClass = selectorClass;
            deviceConfigLazy = lazy;
            deviceConfigEvaluatedState = evaluatedState;
            deviceConfigValueField = valueField;
            nativeCameraConfig = localConfig;
            nativeFocalMethods.clear();
            log(Log.INFO, TAG, "Captured native focal configuration " + localConfig.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            clearFactoryCache(cachedConfig);
            restoreMutableFields(lazy, lazyState);
            nativeCameraConfig = null;
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(
                    Log.WARN,
                    TAG,
                    "Unable to preserve native focal configuration; continuing with the Nezha profile",
                    error
            );
        }
    }

    private void captureModernNativeFocalLengthConfig(ClassLoader classLoader) {
        Field providerCache = null;
        try {
            Class<?> providerClass = Class.forName("Ag.f", true, classLoader);
            Method getConfig = providerClass.getDeclaredMethod("k");
            getConfig.setAccessible(true);
            providerCache = providerClass.getDeclaredField("b");
            providerCache.setAccessible(true);
            Field providerDevice = providerClass.getDeclaredField("c");
            providerDevice.setAccessible(true);
            Class<?> selectorClass = Class.forName("Je.a", true, classLoader);
            Field selectorField = selectorClass.getDeclaredField("c");
            selectorField.setAccessible(true);
            Object deviceSelector = selectorField.get(null);

            Object localConfig = getConfig.invoke(null);
            if (localConfig == null) {
                throw new IllegalStateException("Ag.f returned a null camera configuration");
            }

            cameraFactoryTouched = true;
            modernConfigProvider = true;
            cameraConfigGetter = getConfig;
            cameraConfigCacheField = providerCache;
            modernConfigCacheField = providerCache;
            modernConfigDeviceField = providerDevice;
            deviceConfigLazy = deviceSelector;
            nativeCameraConfig = localConfig;
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            installModernDeviceSelectorHook(deviceSelector);
            log(Log.INFO, TAG, "Captured native camera configuration via Ag.f: "
                    + localConfig.getClass().getName()
                    + ", device=" + providerDevice.get(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            clearFactoryCache(providerCache);
            nativeCameraConfig = null;
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(Log.WARN, TAG, "Unable to capture the modern native focal configuration", error);
        }
    }

    private void installModernDeviceSelectorHook(Object deviceSelector) {
        if (deviceSelector == null) {
            throw new IllegalStateException("Je.a device selector Lazy is null");
        }
        try {
            Method getValue = deviceSelector.getClass().getMethod("getValue");
            getValue.setAccessible(true);
            hook(getValue)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("modern_device_selector")
                    .intercept(chain -> {
                        if (modernDeviceSelectorOverride
                                && chain.getThisObject() == deviceSelector
                                && isEnabled()) {
                            return "nezha";
                        }
                        return chain.proceed();
                    });
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Unable to hook the modern device selector Lazy", error);
        }
    }

    private Object findDeviceConfigLazy(Class<?> selectorClass) throws ReflectiveOperationException {
        for (Field field : selectorClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null) {
                continue;
            }
            try {
                value.getClass().getMethod("getValue");
                return value;
            } catch (NoSuchMethodException ignored) {
                // Continue until the Kotlin Lazy used for the device selector is found.
            }
        }
        throw new NoSuchFieldException("Device configuration Lazy field");
    }

    private Map<Field, Object> snapshotMutableFields(Object owner) throws IllegalAccessException {
        Map<Field, Object> state = new HashMap<>();
        for (Field field : owner.getClass().getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }
            field.setAccessible(true);
            state.put(field, field.get(owner));
        }
        return state;
    }

    private Field findEvaluatedStringField(Map<Field, Object> state) throws NoSuchFieldException {
        for (Map.Entry<Field, Object> entry : state.entrySet()) {
            if (entry.getValue() instanceof String) {
                return entry.getKey();
            }
        }
        throw new NoSuchFieldException("Evaluated Camera device selector value");
    }

    private void restoreMutableFields(Object owner, Map<Field, Object> state) {
        if (owner == null || state == null) {
            return;
        }
        for (Map.Entry<Field, Object> entry : state.entrySet()) {
            try {
                entry.getKey().set(owner, entry.getValue());
            } catch (IllegalAccessException | RuntimeException error) {
                log(Log.WARN, TAG, "Unable to restore Camera device selector state", error);
            }
        }
    }

    private boolean activateNezhaCameraConfig() {
        if (modernConfigProvider) {
            return activateModernNezhaCameraConfig();
        }
        try {
            if (!resetCameraFactoryForNezha()) {
                return false;
            }
            Object replacementConfig = cameraConfigGetter.invoke(null);
            if (replacementConfig == null) {
                throw new IllegalStateException("The Nezha camera configuration is null");
            }
            if (replacementConfig.getClass() == nativeCameraConfig.getClass()) {
                throw new IllegalStateException("Camera factory returned the native configuration after spoofing");
            }

            nezhaCameraConfig = replacementConfig;
            nativeFocalMethods.clear();
            for (String methodName : FOCAL_CONFIG_METHODS) {
                try {
                    Method delegate = nativeCameraConfig.getClass().getMethod(methodName);
                    delegate.setAccessible(true);
                    replacementConfig.getClass().getMethod(methodName).setAccessible(true);
                    nativeFocalMethods.put(methodName, delegate);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log(Log.WARN, TAG, "Unable to map focal configuration method " + methodName, error);
                }
            }
            log(Log.INFO, TAG, "Activated Nezha configuration " + replacementConfig.getClass().getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(Log.WARN, TAG, "Unable to activate the Nezha camera configuration", error);
            return false;
        }
    }

    private boolean activateModernNezhaCameraConfig() {
        try {
            if (cameraConfigGetter == null
                    || modernConfigCacheField == null
                    || modernConfigDeviceField == null) {
                throw new IllegalStateException("Modern camera configuration provider state is incomplete");
            }

            modernDeviceSelectorOverride = true;
            modernConfigDeviceField.set(null, "nezha");
            clearFactoryCache(modernConfigCacheField);
            Object replacementConfig = cameraConfigGetter.invoke(null);
            if (replacementConfig == null) {
                throw new IllegalStateException("Ag.f returned a null Nezha camera configuration");
            }
            if (replacementConfig.getClass() == nativeCameraConfig.getClass()) {
                throw new IllegalStateException("Ag.f returned the native configuration after switching to Nezha");
            }

            nezhaCameraConfig = replacementConfig;
            nativeFocalMethods.clear();
            for (String methodName : FOCAL_CONFIG_METHODS) {
                try {
                    Method delegate = nativeCameraConfig.getClass().getMethod(methodName);
                    delegate.setAccessible(true);
                    replacementConfig.getClass().getMethod(methodName).setAccessible(true);
                    nativeFocalMethods.put(methodName, delegate);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log(Log.WARN, TAG, "Unable to map modern focal configuration method " + methodName, error);
                }
            }
            log(Log.INFO, TAG, "Activated modern Nezha configuration " + replacementConfig.getClass().getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(Log.WARN, TAG, "Unable to activate the modern Nezha camera configuration", error);
            return false;
        }
    }

    private boolean resetCameraFactoryForNezha() {
        if (modernConfigProvider) {
            try {
                modernDeviceSelectorOverride = true;
                modernConfigDeviceField.set(null, "nezha");
                clearFactoryCache(modernConfigCacheField);
                return true;
            } catch (IllegalAccessException | RuntimeException error) {
                log(Log.WARN, TAG, "Unable to reset the modern Camera configuration provider", error);
                return false;
            }
        }
        try {
            if (deviceSelectorClass == null
                    || deviceConfigLazy == null
                    || deviceConfigEvaluatedState == null
                    || deviceConfigValueField == null) {
                throw new IllegalStateException("Camera device selector state is incomplete");
            }
            String nezhaConfigKey = computeDeviceConfigKey(deviceSelectorClass, "nezha");
            Map<Field, Object> nezhaState = new HashMap<>(deviceConfigEvaluatedState);
            nezhaState.put(deviceConfigValueField, nezhaConfigKey);
            clearFactoryCache(cameraConfigCacheField);
            restoreMutableFields(deviceConfigLazy, nezhaState);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to reset the Camera configuration factory for Nezha", error);
            return false;
        }
    }

    private String computeDeviceConfigKey(Class<?> selectorClass, String device)
            throws ReflectiveOperationException {
        Object defaultRule = null;
        Object matchingRule = null;
        for (Field field : selectorClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map<?, ?> rules) {
                Object candidate = rules.get(device);
                if (candidate != null) {
                    matchingRule = candidate;
                }
            } else if (value != null && hasDeviceRuleMethod(value.getClass())) {
                defaultRule = value;
            }
        }
        Object rule = matchingRule != null ? matchingRule : defaultRule;
        if (rule == null) {
            throw new NoSuchFieldException("Camera device selector rule for " + device);
        }
        Method transform = rule.getClass().getMethod("a", StringBuilder.class);
        transform.setAccessible(true);
        Object result = transform.invoke(rule, new StringBuilder(device));
        return String.valueOf(result);
    }

    private boolean hasDeviceRuleMethod(Class<?> type) {
        try {
            type.getMethod("a", StringBuilder.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private void clearFactoryCache(Field field) {
        if (field == null) {
            return;
        }
        try {
            field.set(null, null);
        } catch (IllegalAccessException | RuntimeException error) {
            log(Log.WARN, TAG, "Unable to clear Camera configuration cache", error);
        }
    }

    private void installNativeFocalLengthHooks() {
        Object replacementConfig = nezhaCameraConfig;
        if (replacementConfig == null) {
            return;
        }
        Class<?> nezhaConfigClass = replacementConfig.getClass();
        int installed = 0;
        for (String methodName : FOCAL_CONFIG_METHODS) {
            try {
                Method target = nezhaConfigClass.getMethod(methodName);
                target.setAccessible(true);
                hook(target)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("native_focal_" + methodName)
                        .intercept(chain -> {
                            Object localConfig = nativeCameraConfig;
                            Method delegate = nativeFocalMethods.get(methodName);
                            if (chain.getThisObject() != nezhaCameraConfig
                                    || localConfig == null
                                    || delegate == null
                                    || !isEnabled()
                                    || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                                return chain.proceed();
                            }
                            try {
                                return delegate.invoke(localConfig);
                            } catch (Throwable error) {
                                nativeFocalMethods.remove(methodName);
                                log(
                                        Log.WARN,
                                        TAG,
                                        "Native focal method failed; falling back to Nezha: " + methodName,
                                        error
                                );
                                return chain.proceed();
                            }
                        });
                installed++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                nativeFocalMethods.remove(methodName);
                log(Log.WARN, TAG, "Unable to hook focal configuration method " + methodName, error);
            }
        }
        log(Log.INFO, TAG, "Native focal configuration hooks installed: " + installed);
    }

    private void installNativeWatermarkLabelHook() {
        Object replacementConfig = nezhaCameraConfig;
        Object localConfig = nativeCameraConfig;
        if (replacementConfig == null || localConfig == null) {
            return;
        }
        try {
            Method target = replacementConfig.getClass().getMethod("d");
            Method delegate = localConfig.getClass().getMethod("d");
            target.setAccessible(true);
            delegate.setAccessible(true);
            hook(target)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_watermark_label")
                    .intercept(chain -> {
                        Object nativeConfig = nativeCameraConfig;
                        if (chain.getThisObject() != nezhaCameraConfig
                                || nativeConfig == null
                                || !isEnabled()
                                || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                            return chain.proceed();
                        }
                        try {
                            return delegate.invoke(nativeConfig);
                        } catch (Throwable error) {
                            log(Log.WARN, TAG, "Native watermark label lookup failed", error);
                            return chain.proceed();
                        }
                    });
            log(Log.INFO, TAG, "Native watermark label hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook native watermark label configuration", error);
        }
    }

    private void installLegendaryFallbackHook() {
        try {
            Method set = CaptureRequest.Builder.class.getDeclaredMethod(
                    "set",
                    CaptureRequest.Key.class,
                    Object.class
            );
            set.setAccessible(true);
            hook(set)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("legendary_camera2_fallback")
                    .intercept(chain -> {
                        if (!(chain.getThisObject() instanceof CaptureRequest.Builder builder)
                                || !(chain.getArg(0) instanceof CaptureRequest.Key<?> key)) {
                            return chain.proceed();
                        }
                        if (!isEnabled()) {
                            legendaryBuilders.remove(builder);
                            return chain.proceed();
                        }

                        // Camera 6.7 only emits the vendor tag after the mode is
                        // selected. Inject it proactively when Legendary mode
                        // is selected in the module settings.
                        if (prefInt(ModuleConfig.KEY_INSTANT_MODE, 0) == 1
                                && !legendaryBuilders.containsKey(builder)
                                && !LEGENDARY_VENDOR_TAG.equals(key.getName())) {
                            try {
                                CaptureRequest.Key<Integer> vendorKey = legendaryVendorKey;
                                if (vendorKey == null) {
                                    vendorKey = new CaptureRequest.Key<>(LEGENDARY_VENDOR_TAG, Integer.class);
                                    legendaryVendorKey = vendorKey;
                                }
                                builder.set(vendorKey, LEGENDARY_MODE_M9);
                            } catch (RuntimeException | LinkageError error) {
                                log(Log.WARN, TAG, "Unable to inject Legendary mode vendor tag", error);
                            }
                        }

                        String keyName = key.getName();
                        if (LEGENDARY_VENDOR_TAG.equals(keyName)) {
                            Integer legendaryMode = getLegendaryMode(chain.getArg(1));
                            if (legendaryMode == null) {
                                legendaryBuilders.remove(builder);
                            } else {
                                legendaryBuilders.put(builder, legendaryMode);
                            }
                            Object result = chain.proceed();
                            if (legendaryMode != null) {
                                applyLegendaryCamera2Fallback(builder, legendaryMode);
                            }
                            return result;
                        }

                        Integer legendaryMode = legendaryBuilders.get(builder);
                        if (legendaryMode == null) {
                            return chain.proceed();
                        }
                        if (CaptureRequest.CONTROL_EFFECT_MODE.getName().equals(keyName)) {
                            return chain.proceed(new Object[]{
                                    key,
                                    legendaryMode == LEGENDARY_MODE_M3
                                            ? CaptureRequest.CONTROL_EFFECT_MODE_MONO
                                            : CaptureRequest.CONTROL_EFFECT_MODE_OFF
                            });
                        }
                        if (CaptureRequest.CONTROL_AWB_MODE.getName().equals(keyName)) {
                            return chain.proceed(new Object[]{
                                    key,
                                    legendaryMode == LEGENDARY_MODE_M3
                                            ? CaptureRequest.CONTROL_AWB_MODE_AUTO
                                            : CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                            });
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Legendary Camera2 compatibility hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to install Legendary Camera2 compatibility hook", error);
        }
    }

    private void installExclusiveWatermarkFilterHook(ClassLoader classLoader) {
        installExclusiveWatermarkFilterHook(
                classLoader,
                "Gg.B",
                "exclusive_leica_watermark_property_filter"
        );
        installExclusiveWatermarkFilterHook(
                classLoader,
                "Gg.C0313w",
                "exclusive_leica_watermark_supported_list_filter"
        );
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C", "exclusive_leica_watermark_theme_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0314x", "exclusive_leica_watermark_device_allow_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0315y", "exclusive_leica_watermark_device_deny_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0312v", "exclusive_leica_watermark_region_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0316z", "exclusive_leica_watermark_device_type_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.A", "exclusive_leica_watermark_name_length_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.E", "exclusive_leica_watermark_custom_property_filter");
    }

    private void installCameraWatermarkCatalogHooks(ClassLoader classLoader) {
        try {
            Class<?> manager = Class.forName("Gg.P", false, classLoader);
            Method filterData = manager.getDeclaredMethod("d", boolean.class);
            filterData.setAccessible(true);
            hook(filterData)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("camera_watermark_catalog_limitations")
                    .intercept(chain -> {
                        // Preserve the manager's original result. A null here
                        // makes newer camera builds treat the entire catalog as
                        // unavailable instead of merely skipping restrictions.
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Camera watermark catalog limitation hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to install Camera watermark catalog limitation hook", error);
        }

        try {
            Class<?> jsonObject = Class.forName("org.json.JSONObject", false, null);
            Method optJSONObject = jsonObject.getDeclaredMethod("optJSONObject", String.class);
            optJSONObject.setAccessible(true);
            hook(optJSONObject)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("camera_watermark_json_limitations")
                    .intercept(chain -> {
                        Object name = chain.getArg(0);
                        if (name instanceof String key
                                && key.toLowerCase(java.util.Locale.ROOT).contains("limitation")) {
                            return null;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Camera watermark JSON limitation hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to install Camera watermark JSON limitation hook", error);
        }
    }

    private void installExclusiveWatermarkFilterHook(
            ClassLoader classLoader,
            String filterClassName,
            String hookId
    ) {
        try {
            Class<?> filterClass = Class.forName(filterClassName, false, classLoader);
            Method invoke = filterClass.getDeclaredMethod("invoke", Object.class);
            invoke.setAccessible(true);
            hook(invoke)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId(hookId)
                    .intercept(chain -> {
                        if (isExclusiveLeicaWatermark(chain.getArg(0))) {
                            // This filter removes templates when the app's property cache is stale.
                            return Boolean.FALSE;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Exclusive Leica watermark filter hook installed: " + filterClassName);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(
                    Log.WARN,
                    TAG,
                    "Unable to install exclusive Leica watermark filter hook: " + filterClassName,
                    error
            );
        }
    }

    private boolean isExclusiveLeicaWatermark(Object watermark) {
        if (watermark == null) {
            return false;
        }
        try {
            Method idGetter;
            try {
                idGetter = watermark.getClass().getMethod("U");
            } catch (NoSuchMethodException ignored) {
                idGetter = watermark.getClass().getDeclaredMethod("U");
                idGetter.setAccessible(true);
            }
            Object id = idGetter.invoke(watermark);
            return EXCLUSIVE_LEICA_WATERMARK_IDS.contains(String.valueOf(id));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    private void installGalleryWatermarkHooks(ClassLoader classLoader) {
        if (pref(ModuleConfig.KEY_GALLERY_PALETTE_UNLOCKED, false)) {
            installGalleryAiUnlockHooks(classLoader);
        }
        // Newer MediaEditor builds reject images without camera EXIF before
        // the watermark renderer is reached.  Install this independently of
        // the catalog/device hooks so every image can be watermarked.
        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
            installGalleryAiUnlockHooks(classLoader);
            installGalleryWatermarkExifBypass(classLoader);
            installGalleryWatermarkDeviceBypass(classLoader);
            // The catalog is assembled lazily in a feature dex. These hooks
            // only set the manager's own skip-filter flag and preserve all
            // original return values, so they are safe during UI layout.
            installGalleryWatermarkFilterBypass(classLoader);
            installGalleryWatermarkInitBypass(classLoader);
            installCloudWatermarkMakerBypass(classLoader);
            installGalleryWatermarkDataLoaderHook(classLoader);
            installGalleryWatermarkAvailabilityHook(classLoader);
            installGalleryWatermarkBrandCapabilityHook(classLoader);
            installGalleryWatermarkItemPredicateHooks(classLoader);
            installGalleryWatermarkConfigFilterHook(classLoader);
            installGalleryWatermarkSelectionRestrictionHook(classLoader);
            installGalleryWatermarkJsonLimitationHook();
        }
        if (!pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
            return;
        }
    }

    /**
     * Bypass the photo-parameter checks introduced in MediaEditor 2.10.40.
     * o80.j.x/y validate EXIF fields and o80.j.a is the aggregate validator
     * that produces the "未识别到拍摄参数" error for ordinary images.
     */
    private boolean installGalleryWatermarkExifBypass(ClassLoader classLoader) {
        try {
            Class<?> validator = Class.forName("o80.j", false, classLoader);
            if (!galleryWatermarkExifClasses.add(validator)) {
                return true;
            }
            int installed = 0;
            for (Method candidate : validator.getDeclaredMethods()) {
                String name = candidate.getName();
                Class<?> returnType = candidate.getReturnType();
                if (!isBooleanReturn(returnType)) {
                    continue;
                }
                int count = candidate.getParameterCount();
                // 2.10.40.4.8 is emitted by JADX as m17672x/m17673y and
                // m17674a, while the runtime DEX names may still be x/y/a.
                // Match both forms and keep the final aggregate validator
                // open so images without Leica EXIF remain usable.
                if (("x".equals(name) || "y".equals(name)
                        || "m17672x".equals(name) || "m17673y".equals(name)) && count == 1
                        && Modifier.isStatic(candidate.getModifiers())) {
                    candidate.setAccessible(true);
                    hook(candidate)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("gallery_watermark_exif_" + name)
                            .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                    ? Boolean.TRUE : chain.proceed());
                    installed++;
                } else if (("a".equals(name) || "m17674a".equals(name)) && count == 2
                        && !Modifier.isStatic(candidate.getModifiers())) {
                    candidate.setAccessible(true);
                    hook(candidate)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("gallery_watermark_param_validator")
                            .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                    ? Boolean.TRUE : chain.proceed());
                    installed++;
                }
            }
            log(Log.INFO, TAG, "Gallery watermark EXIF bypass installed: " + installed);
            return installed > 0;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (RuntimeException | LinkageError error) {
            galleryWatermarkExifClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark EXIF bypass", error);
            return false;
        }
    }

    /** bv0.a.i()/m3791i() is the explicit lhasa device capability gate. */
    private boolean installGalleryWatermarkDeviceBypass(ClassLoader classLoader) {
        try {
            Class<?> device = Class.forName("bv0.a", false, classLoader);
            if (!galleryWatermarkDeviceClasses.add(device)) {
                return true;
            }
            Method isLhasa = null;
            for (String name : new String[]{"i", "m3791i"}) {
                try {
                    Method candidate = device.getDeclaredMethod(name);
                    if (Modifier.isStatic(candidate.getModifiers())
                            && candidate.getParameterCount() == 0
                            && isBooleanReturn(candidate.getReturnType())) {
                        isLhasa = candidate;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try the JADX-generated name below.
                }
            }
            if (isLhasa == null) {
                return false;
            }
            isLhasa.setAccessible(true);
            hook(isLhasa)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_device_lhasa")
                    .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                            ? Boolean.TRUE : chain.proceed());
            log(Log.INFO, TAG, "Gallery watermark device capability bypass installed");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (RuntimeException | LinkageError error) {
            galleryWatermarkDeviceClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark device bypass", error);
            return false;
        }
    }

    /**
     * C11802a is the 2.10.40 feature-dex capability table.  Its methods are
     * static boolean predicates, and several of them are consulted before any
     * catalog item is created.  Keep the original class and resources intact,
     * but expose every category when the explicit all-watermarks option is on.
     */
    private boolean installGalleryWatermarkBrandCapabilityHook(ClassLoader classLoader) {
        try {
            Class<?> capability = resolveFirstClass(classLoader, "p382nt.C11802a", "nt.a");
            if (!galleryWatermarkBrandCapabilityClasses.add(capability)) {
                return true;
            }
            int installed = 0;
            for (Method method : capability.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() > 1
                        || !isBooleanReturn(method.getReturnType())) {
                    continue;
                }
                // The one-argument variants receive the EXIF parameter object;
                // zero-argument variants are brand/device capability checks.
                if (method.getParameterCount() == 1
                        && !method.getParameterTypes()[0].getName().contains("C12495b")
                        && !method.getParameterTypes()[0].getName().contains("pc.b")) {
                    continue;
                }
                method.setAccessible(true);
                hook(method)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_brand_capability_" + method.getName())
                        .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                ? Boolean.TRUE : chain.proceed());
                installed++;
            }
            log(Log.INFO, TAG, "Gallery watermark brand capability hooks installed: " + installed);
            return installed > 0;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (RuntimeException | LinkageError error) {
            galleryWatermarkBrandCapabilityClasses.removeIf(
                    type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark brand capability hooks", error);
            return false;
        }
    }

    /** Install per-item version/device/region predicates, including renamed
     * Kotlin lambda classes in future feature dexes. */
    private boolean installGalleryWatermarkItemPredicateHooks(ClassLoader classLoader) {
        String[] names = {
                "kl0.C9119a0", "kl0.C9146o", "kl0.C9154s", "kl0.C9156t",
                "kl0.C9157u", "kl0.C9158v", "kl0.C9159w", "kl0.C9160x",
                "kl0.C9161y", "kl0.C9162z", "kl0.t", "kl0.u", "kl0.v",
                "kl0.w", "kl0.x", "kl0.y", "kl0.z", "kl0.a0"
        };
        int installed = 0;
        for (String name : names) {
            try {
                if (installGalleryWatermarkItemPredicateHook(
                        Class.forName(name, false, classLoader))) {
                    installed++;
                }
            } catch (ClassNotFoundException ignored) {
                // Feature classes are loaded lazily and are handled by the
                // class-load observer below.
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Gallery watermark item predicate hooks installed: " + installed);
        }
        return installed > 0;
    }

    private boolean installGalleryWatermarkItemPredicateHook(Class<?> type) {
        if (type == null || !type.getName().startsWith("kl0.")
                || !galleryWatermarkItemPredicateClasses.add(type)) {
            return false;
        }
        int count = 0;
        try {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!isBooleanReturn(method.getReturnType()) || method.getParameterCount() != 1) {
                        continue;
                    }
                    String parameterName = method.getParameterTypes()[0].getName();
                    if (!(parameterName.startsWith("com.xiaomi.cam.watermark.")
                            || parameterName.endsWith("C4104a"))) {
                        continue;
                    }
                    method.setAccessible(true);
                    hook(method)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("gallery_watermark_item_predicate_"
                                    + type.getName().replace('.', '_') + "_" + method.getName())
                            .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                    ? Boolean.FALSE : chain.proceed());
                    count++;
                }
            }
            if (count == 0) {
                galleryWatermarkItemPredicateClasses.remove(type);
            }
            return count > 0;
        } catch (RuntimeException | LinkageError error) {
            galleryWatermarkItemPredicateClasses.remove(type);
            log(Log.WARN, TAG, "Unable to install watermark item predicate hook: " + type, error);
            return false;
        }
    }

    /**
     * The 2.10.40 AI entry points gate support on Build.DEVICE.  Evaluate the
     * provider call as the Xiaomi 18 Pro Max (madrid), then restore lhasa so
     * watermark code in the same process keeps its established identity.
     */
    private boolean installGalleryAiUnlockHooks(ClassLoader classLoader) {
        int installed = 0;
        installed += installGalleryAiGateHooks(classLoader) ? 1 : 0;
        for (String className : new String[]{
                "com.miui.mediaeditor.provider.AiActionProvider",
                "com.miui.mediaeditor.provider.MediaEditorProviderForGallery"
        }) {
            try {
                Class<?> provider = Class.forName(className, false, classLoader);
                if (!galleryAiProviderClasses.add(provider)) {
                    installed++;
                    continue;
                }
                Method call = provider.getDeclaredMethod(
                        "call", String.class, String.class, Bundle.class);
                call.setAccessible(true);
                hook(call)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_ai_provider_" + className.replace('.', '_'))
                        .intercept(chain -> {
                            if (!pref(ModuleConfig.KEY_GALLERY_PALETTE_UNLOCKED, false)) {
                                return chain.proceed();
                            }
                            if (!isAiProviderCall(className, chain.getArg(0))) {
                                return chain.proceed();
                            }
                            synchronized (AI_PROFILE_LOCK) {
                                applyGalleryAiBuildProfile();
                                try {
                                    return chain.proceed();
                                } finally {
                                    if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                                        applyGalleryWatermarkBuildProfile();
                                    } else {
                                        restoreGalleryOriginalBuildProfile();
                                    }
                                }
                            }
                        });
                installed++;
                if (className.endsWith("AiActionProvider")) {
                    for (String methodName : new String[]{"m7213a", "a"}) {
                        try {
                            Method actionSupport = provider.getDeclaredMethod(methodName, String.class);
                            actionSupport.setAccessible(true);
                            hook(actionSupport)
                                    .setPriority(PRIORITY_HIGHEST)
                                    .setId("gallery_ai_action_support_" + methodName)
                                    .intercept(chain -> {
                                        Object action = chain.getArg(0);
                                        if (!pref(ModuleConfig.KEY_GALLERY_PALETTE_UNLOCKED, false)
                                                || !isAiAction(action)) {
                                            return chain.proceed();
                                        }
                                        synchronized (AI_PROFILE_LOCK) {
                                            applyGalleryAiBuildProfile();
                                            try {
                                                return Boolean.TRUE;
                                            } finally {
                                                if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                                                    applyGalleryWatermarkBuildProfile();
                                                } else {
                                                    restoreGalleryOriginalBuildProfile();
                                                }
                                            }
                                        }
                                    });
                            installed++;
                            break;
                        } catch (NoSuchMethodException ignored) {
                            // Try the next obfuscation name.
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // Providers may be loaded from the feature dex later.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                galleryAiProviderClasses.removeIf(type -> type.getClassLoader() == classLoader);
                log(Log.WARN, TAG, "Unable to install Gallery AI provider hook: " + className, error);
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Gallery AI provider hooks installed: " + installed);
        }
        return installed > 0;
    }

    private boolean isAiProviderCall(String providerClass, Object method) {
        if (!(method instanceof String name)) {
            return false;
        }
        if (providerClass.endsWith("AiActionProvider")) {
            return "action_support".equals(name);
        }
        return name.equals("method_is_device_support_magic_matting")
                || name.equals("method_is_outpaint_available")
                || name.equals("method_is_remover_available")
                || name.equals("method_is_remove_glare_available")
                || name.equals("method_is_art_still_available")
                || name.equals("method_is_magic_sky_available")
                || name.equals("method_is_id_photo_available")
                || name.equals("method_is_mishow_magic_matting_available")
                || name.equals("method_is_auto_adjust_available")
                || name.equals("method_is_magic_matting_available")
                || name.equals("method_is_device_support_capabilities");
    }

    private boolean isAiAction(Object value) {
        if (!(value instanceof String action)) {
            return false;
        }
        return action.contains("/photo/auto-beauty]")
                || action.contains("/photo/face-beauty]")
                || action.contains("/photo/face-beauty-acne]")
                || action.contains("/photo/face-beauty-skin]")
                || action.contains("/photo/face-shape]")
                || action.contains("/photo/face-shape-extra]")
                || action.contains("/photo/body-beauty]")
                || action.contains("/photo/body-beauty-slim]")
                || action.contains("/photo/body-beauty-extra]")
                || action.contains("/photo/virtualization-portrait]")
                || action.contains("/photo/magic-sky]")
                || action.contains("/photo/reflection-eliminate]")
                || action.contains("/photo/super-portrait]")
                || action.contains("/photo/image-quality-restoration]")
                || action.contains("/photo/ai-cloud-function]")
                || action.contains("/photo/ai-cloud-function-super-editor]")
                || action.contains("/photo/hsl]")
                || action.contains("/photo/enhance]")
                || action.contains("/photo/de-noise]")
                || action.contains("/photo/posterize]")
                || action.contains("/photo/enhance-extra]")
                || action.contains("/photo/beautification]");
    }

    /** Static feature gates used by the 2.10.40 AI menu and provider APIs. */
    private boolean installGalleryAiGateHooks(ClassLoader classLoader) {
        int installed = 0;
        for (String className : new String[]{
                "p179ft.C6418f", "p179ft.C6411b0", "p179ft.C6420g", "p179ft.C6423h0",
                "ft.f", "ft.b0", "ft.g", "ft.h0"
        }) {
            try {
                Class<?> type = Class.forName(className, false, classLoader);
                if (!galleryAiUnlockClasses.add(type)) {
                    installed++;
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())
                            || method.getParameterCount() != 0
                            || method.getReturnType() != boolean.class) {
                        continue;
                    }
                    method.setAccessible(true);
                    hook(method)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("gallery_ai_gate_" + className.replace('.', '_') + "_" + method.getName())
                            .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_PALETTE_UNLOCKED, false)
                                    ? Boolean.TRUE : chain.proceed());
                    installed++;
                }
            } catch (ClassNotFoundException ignored) {
                // Feature dex classes are loaded lazily.
            } catch (RuntimeException | LinkageError error) {
                galleryAiUnlockClasses.removeIf(type -> type.getClassLoader() == classLoader);
                log(Log.WARN, TAG, "Unable to install Gallery AI gate hook: " + className, error);
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Gallery AI gate hooks installed: " + installed);
        }
        return installed > 0;
    }

    /**
     * The cloud catalog is normalized before it reaches kl0.k0.  In 2.10.40
     * this pass is w60.r0.a(C2114c, List), emitted as
     * C16644r0.m22218a by JADX.  It removes entries using version, device,
     * region, time and support-list checks, so bypassing the later menu
     * predicates alone still leaves the catalog incomplete.
     */
    private boolean installGalleryWatermarkConfigFilterHook(ClassLoader classLoader) {
        int installed = 0;
        for (String className : new String[]{"w60.r0", "w60.C16644r0"}) {
            try {
                Class<?> type = Class.forName(className, false, classLoader);
                if (!galleryWatermarkConfigFilterClasses.add(type)) {
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())
                            || method.getParameterCount() != 2
                            || !List.class.isAssignableFrom(method.getParameterTypes()[1])
                            || method.getParameterTypes()[0] != method.getReturnType()) {
                        continue;
                    }
                    String methodName = method.getName();
                    if (!("a".equals(methodName) || "m22218a".equals(methodName)
                            || methodName.startsWith("m"))) {
                        continue;
                    }
                    method.setAccessible(true);
                    hook(method)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("gallery_watermark_config_filter_"
                                    + type.getName().replace('.', '_') + "_" + methodName)
                            .intercept(chain -> {
                                Object config = chain.getArg(0);
                                if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                        && config != null) {
                                    return config;
                                }
                                return chain.proceed();
                            });
                    installed++;
                }
                if (installed == 0) {
                    galleryWatermarkConfigFilterClasses.remove(type);
                }
            } catch (ClassNotFoundException ignored) {
                // The watermark feature dex is loaded lazily.
            } catch (RuntimeException | LinkageError error) {
                log(Log.WARN, TAG,
                        "Unable to install Gallery watermark config filter hook: " + className,
                        error);
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Gallery watermark config filter hook installed: " + installed);
        }
        return installed > 0;
    }

    /**
     * Bypass the final item validation used when a watermark is selected.
     * C16656x0.m22225a (runtime name w60.x0.a) returns
     * AbstractC5396a.b(photo_editor_gallery_frame_no_exif_v2) when any
     * required EXIF field, model, or device capability is absent.  Returning
     * the library's success singleton keeps the normal render path intact and
     * removes the "unrecognized shooting parameters" rejection for every
     * watermark type.
     */
    private boolean installGalleryWatermarkSelectionRestrictionHook(ClassLoader classLoader) {
        for (String className : new String[]{"w60.C16656x0", "w60.x0"}) {
            try {
                Class<?> checker = Class.forName(className, false, classLoader);
                if (!galleryWatermarkSelectionRestrictionClasses.add(checker)) {
                    continue;
                }
                Method target = null;
                for (Method method : checker.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())
                            || method.getParameterCount() != 4
                            || !method.getReturnType().getName().startsWith("e70.")) {
                        continue;
                    }
                    String name = method.getName();
                    if ("a".equals(name) || "m22225a".equals(name) || name.startsWith("m")) {
                        target = method;
                        break;
                    }
                }
                if (target == null) {
                    galleryWatermarkSelectionRestrictionClasses.remove(checker);
                    continue;
                }
                Object success = findSuccessResult(target.getReturnType(), classLoader);
                if (success == null) {
                    galleryWatermarkSelectionRestrictionClasses.remove(checker);
                    log(Log.WARN, TAG, "Watermark selection success result is unavailable");
                    continue;
                }
                target.setAccessible(true);
                hook(target)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_selection_restriction_"
                                + checker.getName().replace('.', '_'))
                        .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                ? success : chain.proceed());
                log(Log.INFO, TAG, "Gallery watermark selection restriction bypass installed: "
                        + checker.getName());
                return true;
            } catch (ClassNotFoundException ignored) {
                // Feature dex may be loaded after the editor starts.
            } catch (RuntimeException | LinkageError error) {
                log(Log.WARN, TAG,
                        "Unable to install Gallery watermark selection restriction hook: "
                                + className, error);
            }
        }
        return false;
    }

    private static Object findSuccessResult(Class<?> resultType, ClassLoader classLoader) {
        for (Class<?> nested : resultType.getDeclaredClasses()) {
            for (Field field : nested.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !resultType.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return value;
                    }
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // Try another nested result holder.
                }
            }
        }
        for (String name : new String[]{"e70.AbstractC5396a$a", "e70.a$a"}) {
            try {
                Class<?> holder = Class.forName(name, false, classLoader);
                for (Field field : holder.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())
                            || !resultType.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return value;
                    }
                }
            } catch (ClassNotFoundException | IllegalAccessException | RuntimeException ignored) {
                // Continue with the structural result lookup.
            }
        }
        return null;
    }

    /** HyperCeiler's generic cloud-data bypass: the editor stores time/device
     * restrictions under a JSON object named "limitation". Returning null for
     * that optional node leaves the watermark definition and bitmap paths
     * untouched while preventing the menu builder from dropping the item. */
    private void installGalleryWatermarkJsonLimitationHook() {
        if (galleryWatermarkJsonHookInstalled) {
            return;
        }
        synchronized (this) {
            if (galleryWatermarkJsonHookInstalled) {
                return;
            }
            try {
                Class<?> jsonObject = Class.forName("org.json.JSONObject", false, null);
                Method optJSONObject = jsonObject.getDeclaredMethod("optJSONObject", String.class);
                optJSONObject.setAccessible(true);
                hook(optJSONObject)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_json_limitation_bypass")
                        .intercept(chain -> {
                            if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                    && chain.getArg(0) instanceof String key
                                    && key.toLowerCase(java.util.Locale.ROOT).contains("limitation")) {
                                return null;
                            }
                            return chain.proceed();
                        });
                galleryWatermarkJsonHookInstalled = true;
                log(Log.INFO, TAG, "Gallery watermark JSON limitation hook installed");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Unable to install Gallery watermark JSON limitation hook", error);
            }
        }
    }

    /**
     * 2.10.40 moved the old bt.k gate into several tiny synthetic helpers.
     * They are evaluated while the watermark feature catalog is assembled,
     * before kl0.k0 receives the list.  Hooking only bv0.a.i therefore leaves
     * the catalog empty on devices which are not in Xiaomi's allow-list.
     */
    private boolean installGalleryWatermarkDevicePredicateHooks(ClassLoader classLoader) {
        int installed = 0;
        for (String className : new String[]{"bt.k", "ft.d0", "ft.j0", "k30.f", "ah0.c"}) {
            try {
                Class<?> type = Class.forName(className, false, classLoader);
                if (!galleryWatermarkDevicePredicateClasses.add(type)) {
                    installed++;
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    method.setAccessible(true);
                    if ("bt.k".equals(className) && "a".equals(method.getName())
                            && method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                        hook(method).setPriority(PRIORITY_HIGHEST)
                                .setId("gallery_watermark_bt_k")
                                .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                        ? Boolean.TRUE : chain.proceed());
                        installed++;
                    } else if (("ft.d0".equals(className) || "ft.j0".equals(className))
                            && "a".equals(method.getName()) && method.getParameterCount() == 0
                            && method.getReturnType() == boolean.class) {
                        hook(method).setPriority(PRIORITY_HIGHEST)
                                .setId("gallery_watermark_device_" + className.replace('.', '_'))
                                .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                        ? Boolean.TRUE : chain.proceed());
                        installed++;
                    } else if (("k30.f".equals(className) || "ah0.c".equals(className))
                            && "c".equals(method.getName()) && method.getParameterCount() == 0
                            && method.getReturnType() == Object.class) {
                        hook(method).setPriority(PRIORITY_HIGHEST)
                                .setId("gallery_watermark_device_lambda_" + className.replace('.', '_'))
                                .intercept(chain -> {
                                    if (!pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                                        return chain.proceed();
                                    }
                                    // These Kotlin lambdas use case 0 for
                                    // construction and the default branch for
                                    // the device allow-list Boolean.
                                    try {
                                        Field selector = type.getDeclaredField(
                                                "k30.f".equals(className) ? "f33812a" : "f765a");
                                        selector.setAccessible(true);
                                        if (selector.getInt(chain.getThisObject()) != 0) {
                                            return Boolean.TRUE;
                                        }
                                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                                        return Boolean.TRUE;
                                    }
                                    return chain.proceed();
                                });
                        installed++;
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // Feature classes are loaded lazily; retry from the class-load hook.
            } catch (RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Unable to install watermark device predicate hook: " + className, error);
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Gallery watermark device predicate hooks installed: " + installed);
        }
        return installed > 0;
    }

    /**
     * Bypass the model/device predicates used by Adjust2's menu builder
     * (sw.z1).  The upstream implementation creates every AdjustData item,
     * then removes ids 21/22/31/32/33 and friends on unsupported devices.
     * Returning false from those predicates keeps the original items intact;
     * the actual rendering implementation is already bundled in the APK.
     */
    private boolean installGalleryPaletteUnlockHooks(ClassLoader classLoader) {
        String[] predicateNames = {
                "sw.t1", "sw.u1", "sw.v1", "sw.w1", "sw.x1",
                "sw.n1", "sw.o1", "sw.p1", "sw.q1", "hr.a", "hr.b"
        };
        int installed = 0;
        for (String className : predicateNames) {
            try {
                Class<?> predicateClass = Class.forName(className, false, classLoader);
                if (!galleryPaletteUnlockClasses.add(predicateClass)) {
                    installed++;
                    continue;
                }
                Method test = predicateClass.getDeclaredMethod("test", Object.class);
                test.setAccessible(true);
                final String selectorFieldName = "hr.a".equals(className) ? "f29175a" : "f29176a";
                hook(test)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_palette_predicate_" + className.replace('.', '_'))
                        .intercept(chain -> {
                            if (!pref(ModuleConfig.KEY_GALLERY_PALETTE_UNLOCKED, false)) {
                                return chain.proceed();
                            }
                            // hr.a/hr.b are shared switch predicates; only
                            // their synthetic variant with constructor value 1
                            // removes an AdjustData item (35 or 15).
                            if ("hr.a".equals(className) || "hr.b".equals(className)) {
                                try {
                                    Field selector = predicateClass.getDeclaredField(selectorFieldName);
                                    selector.setAccessible(true);
                                    if (selector.getInt(chain.getThisObject()) == 1) {
                                        return Boolean.FALSE;
                                    }
                                } catch (ReflectiveOperationException | RuntimeException ignored) {
                                    // Fall through to the original predicate.
                                }
                                return chain.proceed();
                            }
                            return Boolean.FALSE;
                        });
                installed++;
            } catch (ClassNotFoundException ignored) {
                // Feature classes are loaded lazily; the class-loader hook
                // below will retry when each class is defined.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                galleryPaletteUnlockClasses.removeIf(type -> type.getClassLoader() == classLoader);
                log(Log.WARN, TAG, "Unable to install Adjust2 palette predicate hook: " + className, error);
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Adjust2 palette unlock hooks installed: " + installed);
        }
        return installed == predicateNames.length;
    }

    /**
     * MediaEditor 2.10.x moved the final watermark filtering into kl0's
     * Kotlin predicate classes. Each predicate returns true when an item is
     * invalid (device, region, theme, system properties or name length).
     * Returning false here keeps the complete catalog visible.
     */
    private boolean installGalleryLimitationPredicateHooks(ClassLoader classLoader) {
        int installed = 0;
        for (String className : new String[]{
                "kl0.t", "kl0.v", "kl0.u", "kl0.s", "kl0.w", "kl0.x", "kl0.y", "kl0.z", "kl0.a0"
        }) {
            try {
                Class<?> predicate = Class.forName(className, false, classLoader);
                if (!galleryLimitationPredicateClasses.add(predicate)) {
                    installed++;
                    continue;
                }
                Method test = findBooleanPredicateMethod(predicate);
                if (test == null) {
                    throw new NoSuchMethodException("boolean predicate method in " + className);
                }
                test.setAccessible(true);
                hook(test)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_limit_" + className.replace('.', '_'))
                        .intercept(chain -> {
                            if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        });
                installed++;
            } catch (ClassNotFoundException ignored) {
                // Watermark feature dex is loaded lazily; the class-load hook retries it.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                galleryLimitationPredicateClasses.removeIf(type -> type.getClassLoader() == classLoader);
                log(Log.WARN, TAG, "Unable to install watermark limitation hook: " + className, error);
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Gallery watermark limitation predicate hooks installed: " + installed);
        }
        return installed == 9;
    }

    /**
     * k0.c() returns the server-side supported_watermark_list.  The stock
     * manager feeds that list into kl0.t and removes every catalog entry not
     * present in it.  For the explicit all-watermarks switch the local catalog
     * is already bundled in the APK, so this list must not act as a hard gate.
     */
    private boolean installGalleryWatermarkSupportedListBypass(ClassLoader classLoader) {
        try {
            Class<?> manager = resolveFirstClass(classLoader, "kl0.k0", "kl0.AbstractC9139k0");
            if (!galleryWatermarkSupportedListClasses.add(manager)) {
                return true;
            }
            Method supported = findMethod(manager, new String[]{"c", "m14230c"}, 0, null);
            if (supported == null) {
                throw new NoSuchMethodException("supported watermark list");
            }
            supported.setAccessible(true);
            // Do not replace this with an empty list: kl0.t treats an empty
            // supported list as "nothing is supported" and removes the whole
            // catalog when its predicate hook is not yet installed. Keep the
            // original list and bypass kl0.t itself instead.
            hook(supported)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_supported_list_passthrough")
                    .intercept(chain -> chain.proceed());
            log(Log.INFO, TAG, "Gallery watermark supported-list bypass installed (kl0.k0.c)");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkSupportedListClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark supported-list bypass", error);
            return false;
        }
    }

    /**
     * The renderer asks kl0.k0.e(id) and aborts with "cannot get watermark
     * item" when a server/device filter removed that id.  Returning a real
     * bundled item keeps the renderer's configuration object valid (unlike a
     * fabricated null/empty object) and is only used as a last-resort lookup
     * fallback when the all-watermarks switch is enabled.
     */
    private boolean installGalleryWatermarkLookupFallback(ClassLoader classLoader) {
        try {
            Class<?> manager = resolveFirstClass(classLoader, "kl0.k0", "kl0.AbstractC9139k0");
            if (!galleryWatermarkLookupClasses.add(manager)) {
                return true;
            }
            Method lookup = findMethod(manager, new String[]{"e", "m14232e"}, 1, String.class);
            if (lookup == null) {
                throw new NoSuchMethodException("watermark lookup");
            }
            lookup.setAccessible(true);
            hook(lookup)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_lookup_fallback")
                    .intercept(chain -> {
                        Object value = chain.proceed();
                        if (value != null || !pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return value;
                        }
                        try {
                            Method groupsMethod = findMethod(manager, new String[]{"d", "m14231d"}, 1, boolean.class);
                            Object groups = groupsMethod == null ? null
                                    : groupsMethod.invoke(chain.getThisObject(), true);
                            if (groups instanceof Iterable<?>) {
                                for (Object group : (Iterable<?>) groups) {
                                    Field items = findIterableField(group.getClass());
                                    if (items == null) {
                                        continue;
                                    }
                                    items.setAccessible(true);
                                    Object list = items.get(group);
                                    if (list instanceof Iterable<?>) {
                                        for (Object item : (Iterable<?>) list) {
                                            if (item != null) {
                                                return item;
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // Preserve the original null result if the catalog
                            // is not initialized yet; later calls retry.
                        }
                        return null;
                    });
            log(Log.INFO, TAG, "Gallery watermark lookup fallback installed (kl0.k0.e)");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkLookupClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark lookup fallback", error);
            return false;
        }
    }

    /** CloudWatermarkMaker.l(boolean) is the real initialization entry. Set
     * k0's skip-filter flag before it invokes h()/g(), covering feature
     * classloaders where the abstract-manager hook is installed too late. */
    private boolean installCloudWatermarkMakerBypass(ClassLoader classLoader) {
        try {
            Class<?> maker = Class.forName(
                    "com.miui.mediaeditor.photo.watermask.CloudWatermarkMaker", false, classLoader);
            if (!galleryCloudWatermarkMakerClasses.add(maker)) {
                return true;
            }
            Method init = findMethod(maker, new String[]{"l", "m7180l"}, 1, boolean.class);
            if (init == null) {
                throw new NoSuchMethodException("watermark manager init");
            }
            init.setAccessible(true);
            hook(init)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_cloud_watermark_init_bypass")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            try {
                                Class<?> manager = resolveFirstClass(classLoader, "kl0.m0", "kl0.C9143m0");
                                Object instance = findStaticSingleton(manager);
                                Field skip = findWatermarkBypassField(manager);
                                if (instance != null && skip != null) {
                                    skip.setAccessible(true);
                                    skip.setBoolean(instance, true);
                                }
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                                // k0.g()/b() hooks remain as fallback.
                            }
                        }
                        return chain.proceed();
                    });
            installCloudWatermarkItemVersionBypass(maker, classLoader);
            log(Log.INFO, TAG, "CloudWatermarkMaker initialization bypass installed");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryCloudWatermarkMakerClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install CloudWatermarkMaker initialization bypass", error);
            return false;
        }
    }

    /**
     * CloudWatermarkMaker.k rejects templates whose metadata declares a
     * manager version newer than Xiaomi's hard-coded 2.13 runtime.  The
     * renderer bundled in the editor can still consume those templates, so
     * recover the real catalog item after the stock method returns null.
     */
    private void installCloudWatermarkItemVersionBypass(Class<?> maker, ClassLoader classLoader) {
        try {
            Class<?> item = resolveFirstClass(classLoader, "com.xiaomi.cam.watermark.C4104a");
            Method target = null;
            for (Class<?> current = maker; current != null && target == null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (method.getReturnType() == item && params.length == 2
                            && params[0] == byte[].class && params[1] == boolean.class) {
                        target = method;
                        break;
                    }
                }
            }
            if (target == null) {
                return;
            }
            target.setAccessible(true);
            hook(target)
                    .setPriority(PRIORITY_LOWEST)
                    .setId("gallery_cloud_watermark_item_version_bypass")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result != null || !pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return result;
                        }
                        try {
                            Class<?> manager = resolveFirstClass(classLoader, "kl0.m0", "kl0.C9143m0");
                            Object managerObject = findStaticSingleton(manager);
                            if (managerObject == null) {
                                return null;
                            }
                            Method decode = findMethodByReturnAndParams(manager, String.class, byte[].class);
                            if (decode == null) {
                                return null;
                            }
                            decode.setAccessible(true);
                            String id = (String) decode.invoke(managerObject, chain.getArg(0));
                            if (id == null || id.isEmpty()) {
                                return null;
                            }
                            Method lookup = findMethod(manager, new String[]{"e", "m14232e"}, 1, String.class);
                            if (lookup == null) {
                                return null;
                            }
                            lookup.setAccessible(true);
                            return lookup.invoke(managerObject, id);
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            return null;
                        }
                    });
        } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
            // Optional on older editor builds.
        }
    }

    private static Method findMethodByReturnAndParams(Class<?> type, Class<?> returnType,
                                                       Class<?>... params) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getReturnType() != returnType) {
                    continue;
                }
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != params.length) {
                    continue;
                }
                boolean matches = true;
                for (int i = 0; i < params.length; i++) {
                    if (actual[i] != params[i]) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * The 2.10.40 manager (kl0.k0) removes invalid watermark items in one
     * centralized pass. Bypass that pass when the all-watermarks switch is on;
     * this remains effective even if a feature dex loads before its predicate
     * classes and avoids relying on obfuscated predicate signatures.
     */
    private boolean installGalleryWatermarkFilterBypass(ClassLoader classLoader) {
        try {
            Class<?> manager = resolveFirstClass(classLoader, "kl0.k0", "kl0.AbstractC9139k0");
            if (!galleryWatermarkFilterClasses.add(manager)) {
                return true;
            }
            Method filter = findMethod(manager, new String[]{"b", "m14229b"}, 1, boolean.class);
            if (filter == null) {
                throw new NoSuchMethodException("watermark filter");
            }
            filter.setAccessible(true);
            hook(filter)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_filter_bypass")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            Field bypass = findWatermarkBypassField(manager);
                            if (bypass != null) {
                                try {
                                    bypass.setAccessible(true);
                                    bypass.setBoolean(chain.getThisObject(), true);
                                } catch (IllegalAccessException | RuntimeException ignored) {
                                    // Predicate hooks below still cover the pass.
                                }
                            }
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark catalog filter bypass installed (kl0.k0.b)");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkFilterClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark catalog filter bypass", error);
            return false;
        }
    }

    /**
     * Prevent the manager's one-shot initData() pass from deleting catalog
     * entries before the runtime predicate hooks become available.  In
     * MediaEditor 2.10.40, k0.g() calls b(true) immediately after loading the
     * catalog; setting f34772k makes b() return without filtering.  This is
     * deliberately done only while the all-watermarks switch is enabled.
     */
    private boolean installGalleryWatermarkInitBypass(ClassLoader classLoader) {
        try {
            Class<?> manager = resolveFirstClass(classLoader, "kl0.k0", "kl0.AbstractC9139k0");
            if (!galleryWatermarkInitClasses.add(manager)) {
                return true;
            }
            Method init = findMethod(manager, new String[]{"g", "m14234g"}, 0, null);
            if (init == null) {
                throw new NoSuchMethodException("watermark data init");
            }
            init.setAccessible(true);
            Field bypass = findWatermarkBypassField(manager);
            if (bypass == null) {
                throw new NoSuchFieldException("watermark filter bypass flag");
            }
            bypass.setAccessible(true);
            hook(init)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_init_bypass")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            try {
                                bypass.setBoolean(chain.getThisObject(), true);
                            } catch (IllegalAccessException | RuntimeException ignored) {
                                // The b(boolean) hook below remains active.
                            }
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark init filter bypass installed (kl0.k0.g)");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkInitClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark init filter bypass", error);
            return false;
        }
    }

    private void installGalleryWatermarkHooksForChain(ClassLoader classLoader) {
        ClassLoader current = classLoader;
        int depth = 0;
        while (current != null && depth++ < 8) {
            log(Log.INFO, TAG, "Trying Gallery watermark hooks in ClassLoader " + current);
            installGalleryWatermarkHooks(current);
            current = current.getParent();
        }
        // Feature dexes are loaded on demand. Install the class-load observer
        // only after the application has bound its first activity; callbacks
        // are restricted to watermark packages and never mutate UI lists.
        scheduleGalleryWatermarkHooksForChain(classLoader);
    }

    /**
     * PackageLoaded/PackageReady can run from LoadedApk's class-loader setup,
     * before ActivityThread has finished binding the application. Installing
     * a global ClassLoader hook (or changing Build/SystemProperties) there can
     * interfere with framework services such as ConnectivityManager. Defer
     * the gallery-only hooks until the main loop has completed binding.
     */
    private void scheduleGalleryWatermarkHooksForChain(ClassLoader classLoader) {
        if (galleryWatermarkHooksScheduled) {
            return;
        }
        synchronized (this) {
            if (galleryWatermarkHooksScheduled) {
                return;
            }
            galleryWatermarkHooksScheduled = true;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!btm.m.os4.systemuihook.OsCompatibility.areHooksAllowed()) {
                    return;
                }
                installDeferredGalleryWatermarkManagerHook();
                installGalleryWatermarkHooksForChain(classLoader);
                // Feature classes may already have been resolved before the
                // class-load observer was installed. Retry resolution while
                // the editor is starting, without touching its adapters.
                for (long delay : new long[]{250L, 750L, 1500L, 3000L, 5000L}) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> installGalleryWatermarkHooksForChain(classLoader), delay);
                }
            } catch (RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Deferred Gallery watermark hooks failed", error);
            }
        }, 1500L);
    }

    /**
     * MediaEditor 2.10.39.x moved all photo-watermark checks into the
     * watermark feature module. Keep the cloud catalog intact and make the
     * render capability check succeed when the all-watermarks preference is on.
     */
    private boolean installModernGalleryWatermarkHooks(ClassLoader classLoader) {
        try {
            Class<?> configClass = Class.forName(
                    "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkConfigData",
                    false,
                    classLoader
            );
            Class<?> f0Class = Class.forName("xy.f0", false, classLoader);
            if (!galleryModernWatermarkClasses.add(f0Class)) {
                return true;
            }

            Method filter = f0Class.getDeclaredMethod("a", configClass, List.class);
            filter.setAccessible(true);
            hook(filter)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_cloud_watermark_filter")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                && chain.getArg(0) != null) {
                            // f0.a removes entries by device, region, version,
                            // time window, and name length. Return the source
                            // object so every cloud template reaches the menu.
                            return chain.getArg(0);
                        }
                        return chain.proceed();
                    });

            Class<?> j0Class = Class.forName("xy.j0", false, classLoader);
            Method capability = findModernCapabilityMethod(j0Class);
            if (capability == null) {
                throw new NoSuchMethodException("xy.j0.a capability method");
            }
            capability.setAccessible(true);
            Object supported = findModernWatermarkSuccess(classLoader);
            if (supported == null) {
                throw new IllegalStateException("Modern watermark success result is unavailable");
            }
            hook(capability)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_capability")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return supported;
                        }
                        return chain.proceed();
                    });
            galleryModernCapabilityClasses.add(j0Class);

            Class<?> serviceClass = Class.forName("xy.d", false, classLoader);
            Class<?> renderDataClass = Class.forName("nx.g", false, classLoader);
            Class<?> verificationClass = Class.forName("yr.e$b", false, classLoader);
            Field verificationField = verificationClass.getDeclaredField("f54669a");
            verificationField.setAccessible(true);
            Object verificationSuccess = verificationField.get(null);
            Method verify = serviceClass.getDeclaredMethod("b", renderDataClass);
            verify.setAccessible(true);
            hook(verify)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_render_capability")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return verificationSuccess;
                        }
                        return chain.proceed();
                    });

            Class<?> photoInfoClass = Class.forName("w8.b", false, classLoader);
            Class<?> displayConfigClass = Class.forName("m00.g", false, classLoader);
            Method availability;
            try {
                availability = Class.forName("xy.i", false, classLoader).getDeclaredMethod(
                        "e", Map.class, photoInfoClass,
                        Class.forName("m00.k", false, classLoader), displayConfigClass);
            } catch (NoSuchMethodException missingAvailability) {
                availability = findWatermarkAvailabilityMethod(Class.forName("xy.i", false, classLoader));
                if (availability == null) {
                    throw missingAvailability;
                }
            }
            availability.setAccessible(true);
            hook(availability)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_availability")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            forceWatermarkItemsAvailable(chain.getArg(0));
                        }
                        return result;
                    });

            log(Log.INFO, TAG, "Modern MediaEditor watermark hooks installed (xy.f0/xy.j0/xy.d/xy.i)");
            return true;
        } catch (ClassNotFoundException error) {
            galleryModernWatermarkClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.INFO, TAG, "Modern watermark classes not ready in " + classLoader + ": " + error.getMessage());
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryModernWatermarkClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install modern MediaEditor watermark hooks", error);
            return false;
        }
    }

    /** Installs the EXIF/model result hook independently of the cloud catalog. */
    private boolean installModernWatermarkCapabilityOnly(ClassLoader classLoader) {
        try {
            Class<?> j0Class = Class.forName("xy.j0", false, classLoader);
            if (!galleryModernCapabilityClasses.add(j0Class)) {
                return true;
            }
            Method capability = findModernCapabilityMethod(j0Class);
            if (capability == null) {
                galleryModernCapabilityClasses.remove(j0Class);
                return false;
            }
            capability.setAccessible(true);
            Object supported = findModernWatermarkSuccess(classLoader);
            if (supported == null) {
                galleryModernCapabilityClasses.remove(j0Class);
                return false;
            }
            hook(capability)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_capability_only")
                    .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                            ? supported : chain.proceed());
            log(Log.INFO, TAG, "Modern watermark capability-only hook installed (xy.j0.a)");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryModernCapabilityClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install capability-only watermark hook", error);
            return false;
        }
    }

    private Method findModernCapabilityMethod(Class<?> j0Class) {
        for (Method method : j0Class.getDeclaredMethods()) {
            if ("a".equals(method.getName())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 4
                    && "dz.a".equals(method.getReturnType().getName())) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private Method findWatermarkAvailabilityMethod(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if ("e".equals(method.getName())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 4
                    && Map.class.isAssignableFrom(method.getParameterTypes()[0])) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private Object findModernWatermarkSuccess(ClassLoader classLoader)
            throws ClassNotFoundException, ReflectiveOperationException {
        Class<?> resultBase = Class.forName("dz.a", false, classLoader);
        try {
            Class<?> named = Class.forName("dz.a$C0282a", false, classLoader);
            for (Field field : named.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && resultBase.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Obfuscation can rename the singleton class in another build.
        }
        for (Class<?> nested : resultBase.getDeclaredClasses()) {
            if (nested == null || nested.getName().endsWith(".b")) {
                continue;
            }
            for (Field field : nested.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && resultBase.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private boolean installGalleryWatermarkFragmentHook(ClassLoader classLoader) {
        try {
            Class<?> fragment = Class.forName(
                    "com.miui.mediaeditor.photo.watermark.PhotoWatermarkFragment",
                    false,
                    classLoader
            );
            if (!galleryWatermarkFragmentClasses.add(fragment)) {
                return true;
            }
            Method click = fragment.getDeclaredMethod("G0", fragment, int.class);
            click.setAccessible(true);
            hook(click)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_fragment_click")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            // The feature module may be loaded lazily after the
                            // fragment appears. Re-run resolution at click time.
                            installModernGalleryWatermarkHooks(fragment.getClassLoader());
                            installModernWatermarkCapabilityOnly(fragment.getClassLoader());
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark fragment click hook installed");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkFragmentClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark fragment click hook", error);
            return false;
        }
    }

    private void forceWatermarkItemsAvailable(Object map) {
        if (!(map instanceof Map<?, ?> watermarkMap)) {
            return;
        }
        for (Object value : watermarkMap.values()) {
            if (!(value instanceof Iterable<?> items)) {
                continue;
            }
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                try {
                    Field available = null;
                    for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass()) {
                        for (String fieldName : new String[]{"f17713d", "f15804d", "available", "isAvailable"}) {
                            try {
                                Field candidate = type.getDeclaredField(fieldName);
                                if (candidate.getType() == boolean.class || candidate.getType() == Boolean.class) {
                                    available = candidate;
                                    break;
                                }
                            } catch (NoSuchFieldException ignored) {
                                // Continue through intermediate item implementations.
                            }
                        }
                        if (available != null) {
                            break;
                        }
                    }
                    if (available == null) {
                        continue;
                    }
                    available.setAccessible(true);
                    available.setBoolean(item, true);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Some local item implementations inherit through an
                    // intermediate class; their normal availability remains.
                }
            }
        }
    }

    private boolean installGalleryWatermarkCapabilityHooks(ClassLoader classLoader) {
        try {
            // In MediaEditor 2.10.37.9, zn.a.g/h/i are the renamed equivalents of
            // the three feature gates modified by the 2.4.0.4.3 reference build.
            Class<?> capabilityClass = Class.forName("zn.a", false, classLoader);
            if (!galleryWatermarkCapabilityClasses.add(capabilityClass)) {
                return true;
            }

            int installed = 0;
            for (String methodName : new String[]{"g", "h", "i"}) {
                Method gate = capabilityClass.getDeclaredMethod(methodName);
                gate.setAccessible(true);
                hook(gate)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_capability_" + methodName)
                        .intercept(chain -> {
                            if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        });
                installed++;
            }
            log(
                    Log.INFO,
                    TAG,
                    "Gallery watermark capability hooks installed: " + installed + " (zn.a.g/h/i)"
            );
            return installed == 3;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkCapabilityClasses.removeIf(
                    capabilityClass -> capabilityClass.getClassLoader() == classLoader
            );
            log(Log.WARN, TAG, "Unable to install Gallery watermark capability hooks", error);
            return false;
        }
    }

    private boolean installGalleryWatermarkUsageHook(ClassLoader classLoader) {
        try {
            // MediaEditor 2.10.37.9 rejects a visible watermark in vy.m0.a when
            // the source photo lacks one of its device/EXIF/cloud parameters.
            Class<?> checker = Class.forName("vy.m0", false, classLoader);
            if (!galleryWatermarkUsageClasses.add(checker)) {
                return true;
            }
            Class<?> itemClass = Class.forName("fz.d", false, classLoader);
            Class<?> photoInfoClass = Class.forName("v8.b", false, classLoader);
            Class<?> configClass = Class.forName("k00.g", false, classLoader);
            Method check = checker.getDeclaredMethod(
                    "a",
                    itemClass,
                    String.class,
                    photoInfoClass,
                    configClass
            );
            check.setAccessible(true);

            Class<?> successClass = Class.forName("bz.a$a", false, classLoader);
            Field successField = successClass.getDeclaredField("a");
            successField.setAccessible(true);
            Object success = successField.get(null);
            if (success == null) {
                throw new IllegalStateException("Watermark success result is null");
            }

            hook(check)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_usage_restrictions")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return success;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark usage restriction hook installed (vy.m0.a)");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkUsageClasses.removeIf(
                    usageClass -> usageClass.getClassLoader() == classLoader
            );
            log(Log.WARN, TAG, "Unable to install Gallery watermark usage restriction hook", error);
            return false;
        }
    }

    /**
     * MediaEditor 2.4.x performs the final device/EXIF/model restriction in
     * vg.t.a before a watermark item is selected. The reference modified APK
     * bypasses this exact result branch, so return the library's success value
     * while the all-watermarks preference is enabled.
     */
    private boolean installGalleryWatermarkRestrictionHook(ClassLoader classLoader) {
        try {
            Class<?> checker = Class.forName("vg.t", false, classLoader);
            if (!galleryWatermarkRestrictionClasses.add(checker)) {
                return true;
            }
            Class<?> itemClass = Class.forName("Fg.b", false, classLoader);
            Class<?> photoInfoClass = Class.forName("S3.b", false, classLoader);
            Class<?> contextClass = Class.forName("p286kh.e", false, classLoader);
            Method check = checker.getDeclaredMethod(
                    "a",
                    itemClass,
                    String.class,
                    photoInfoClass,
                    contextClass
            );
            check.setAccessible(true);

            Class<?> successClass = Class.forName("Bg.a$C0013a", false, classLoader);
            Field successField = successClass.getDeclaredField("f675a");
            successField.setAccessible(true);
            Object success = successField.get(null);
            if (success == null) {
                throw new IllegalStateException("Watermark restriction success result is null");
            }

            hook(check)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_legacy_restrictions")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return success;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark restriction hook installed (vg.t.a)");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkRestrictionClasses.removeIf(
                    restrictionClass -> restrictionClass.getClassLoader() == classLoader
            );
            log(Log.WARN, TAG, "Unable to install Gallery watermark restriction hook", error);
            return false;
        }
    }

    private boolean installGalleryWatermarkManagerHook(ClassLoader classLoader) {
        try {
            Class<?> manager = Class.forName("tb0.o0", false, classLoader);
            if (!galleryWatermarkManagers.add(manager)) {
                return true;
            }
            Method filterData = manager.getDeclaredMethod("b", boolean.class);
            filterData.setAccessible(true);
            hook(filterData)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_all_watermark_limitations")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            // Keep the original return type/value. Older builds
                            // use this method for both filtering and catalog
                            // loading, so returning null can empty the menu.
                            return chain.proceed();
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery all-watermark limitation hook installed");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkManagers.removeIf(manager -> manager.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery all-watermark limitation hook", error);
            return false;
        }
    }

    private void installDeferredGalleryWatermarkManagerHook() {
        if (galleryWatermarkClassLoadHookInstalled) {
            return;
        }
        synchronized (this) {
            if (galleryWatermarkClassLoadHookInstalled) {
                return;
            }
            try {
                Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
                loadClass.setAccessible(true);
                hook(loadClass)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_dynamic_watermark_manager_load2")
                        .intercept(chain -> {
                            Object loadedClass = chain.proceed();
                            installDeferredWatermarkClass(loadedClass, chain.getArg(0));
                            return loadedClass;
                        });

                Method loadClassSimple = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
                loadClassSimple.setAccessible(true);
                hook(loadClassSimple)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_dynamic_watermark_manager_load1")
                        .intercept(chain -> {
                            Object loadedClass = chain.proceed();
                            installDeferredWatermarkClass(loadedClass, chain.getArg(0));
                            return loadedClass;
                        });

                try {
                    Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, null);
                    Method findClass = baseDex.getDeclaredMethod("findClass", String.class);
                    findClass.setAccessible(true);
                    hook(findClass)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("gallery_dynamic_watermark_manager_find")
                            .intercept(chain -> {
                                Object loadedClass = chain.proceed();
                                installDeferredWatermarkClass(loadedClass, chain.getArg(0));
                                return loadedClass;
                            });
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    log(Log.INFO, TAG, "BaseDexClassLoader.findClass hook unavailable");
                }
                galleryWatermarkClassLoadHookInstalled = true;
                log(Log.INFO, TAG, "Gallery dynamic watermark manager hook installed");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Unable to install Gallery dynamic watermark manager hook", error);
            }
        }
    }

    private void installDeferredWatermarkClass(Object loadedClass, Object name) {
        if (!(loadedClass instanceof Class<?> loaded) || !(name instanceof String className)) {
            return;
        }
        ClassLoader loader = loaded.getClassLoader();
        if ("xy.f0".equals(className) || "xy.j0".equals(className)
                || "xy.d".equals(className) || "xy.i".equals(className)) {
            log(Log.INFO, TAG, "Watermark class loaded: " + className + " via " + loader);
            installModernGalleryWatermarkHooks(loader);
            if ("xy.j0".equals(className)) {
                installModernWatermarkCapabilityOnly(loader);
            }
        } else if ("tb0.o0".equals(className)) {
            installGalleryWatermarkManagerHook(loader);
        } else if ("zn.a".equals(className)) {
            installGalleryWatermarkCapabilityHooks(loader);
        } else if ("vy.m0".equals(className)) {
            installGalleryWatermarkUsageHook(loader);
        } else if ("vg.t".equals(className)) {
            installGalleryWatermarkRestrictionHook(loader);
        } else if ("com.miui.mediaeditor.photo.watermark.PhotoWatermarkFragment".equals(className)) {
            installGalleryWatermarkFragmentHook(loader);
        } else if ("com.miui.mediaeditor.photo.watermask.CloudWatermarkMaker".equals(className)
                || className.endsWith(".CloudWatermarkMaker")) {
            installCloudWatermarkMakerBypass(loader);
        } else if ("o80.j".equals(className)) {
            installGalleryWatermarkExifBypass(loader);
        } else if ("bv0.a".equals(className)) {
            installGalleryWatermarkDeviceBypass(loader);
        } else if ("p382nt.C11802a".equals(className) || "nt.a".equals(className)) {
            installGalleryWatermarkBrandCapabilityHook(loader);
        } else if ("w60.r0".equals(className) || "w60.C16644r0".equals(className)) {
            installGalleryWatermarkConfigFilterHook(loader);
        } else if ("w60.x0".equals(className) || "w60.C16656x0".equals(className)) {
            installGalleryWatermarkSelectionRestrictionHook(loader);
        }
        if ("a70.C0082b".equals(className) || "a70.b".equals(className)) {
            installGalleryWatermarkDataLoaderHook(loader);
        }
        if ("w60.m".equals(className) || "w60.C16633m".equals(className)
                || "w60.C16633m0".equals(className)) {
            installGalleryWatermarkAvailabilityHook(loader);
        }
        if (className.startsWith("o80.")) {
            installGenericWatermarkExifHook(loaded);
        }
        if ("bt.k".equals(className) || "ft.d0".equals(className)
                || "ft.j0".equals(className) || "k30.f".equals(className)
                || "ah0.c".equals(className)) {
            installGalleryWatermarkDevicePredicateHooks(loader);
        }
        if (className.startsWith("kl0.")) {
            installGalleryLimitationPredicateHooks(loader);
            installGalleryWatermarkItemPredicateHook(loaded);
            installGalleryWatermarkFilterBypass(loader);
            installGalleryWatermarkSupportedListBypass(loader);
            installGalleryWatermarkInitBypass(loader);
            installCloudWatermarkMakerBypass(loader);
            installGenericWatermarkManagerHook(loaded);
        }
        if (className.startsWith("p179ft.") || className.startsWith("ft.")) {
            installGalleryAiGateHooks(loader);
        }
        if (className.equals("com.miui.mediaeditor.provider.AiActionProvider")
                || className.equals("com.miui.mediaeditor.provider.MediaEditorProviderForGallery")) {
            installGalleryAiUnlockHooks(loader);
        }
    }

    /**
     * Structural fallback for renamed manager classes. The MediaEditor
     * manager has an ArrayList catalog, several boolean state fields, and a
     * one-boolean void filtering method. The skip flag is deliberately
     * selected by its stable trailing "k" field shape, avoiding assumptions
     * about JADX's generated class/method names.
     */
    private void installGenericWatermarkManagerHook(Class<?> type) {
        if (type == null || !type.getName().startsWith("kl0.")) {
            return;
        }
        try {
            Class<?> managerType = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (findIterableField(current) != null && countBooleanFields(current) >= 3) {
                    managerType = current;
                    break;
                }
            }
            if (managerType == null || !galleryGenericWatermarkClasses.add(managerType)) {
                return;
            }
            type = managerType;
            Field skip = null;
            int boolFields = 0;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                        boolFields++;
                        if ("k".equals(field.getName()) || field.getName().endsWith("k")) {
                            skip = field;
                        }
                    }
                }
            }
            Field catalog = findIterableField(type);
            if (skip == null || catalog == null || boolFields < 3) {
                return;
            }
            skip.setAccessible(true);
            Method filter = null;
            Method init = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getReturnType() == void.class && method.getParameterCount() == 1
                            && method.getParameterTypes()[0] == boolean.class && filter == null) {
                        filter = method;
                    } else if (method.getReturnType() == void.class && method.getParameterCount() == 0
                            && init == null && !Modifier.isStatic(method.getModifiers())) {
                        init = method;
                    }
                }
            }
            if (filter == null) {
                return;
            }
            final Field bypass = skip;
            Method filterMethod = filter;
            filterMethod.setAccessible(true);
            hook(filterMethod).setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_generic_filter_" + type.getName().replace('.', '_'))
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            try {
                                bypass.setBoolean(chain.getThisObject(), true);
                            } catch (IllegalAccessException | RuntimeException ignored) {
                                // Keep the original path if a vendor runtime
                                // prevents reflective field access.
                            }
                        }
                        return chain.proceed();
                    });
            if (init != null && init != filterMethod) {
                init.setAccessible(true);
                hook(init).setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_generic_init_" + type.getName().replace('.', '_'))
                        .intercept(chain -> {
                            if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                                try {
                                    bypass.setBoolean(chain.getThisObject(), true);
                                } catch (IllegalAccessException | RuntimeException ignored) {
                                    // Filter hook remains active.
                                }
                            }
                            return chain.proceed();
                        });
            }
            log(Log.INFO, TAG, "Generic Gallery watermark manager hook installed: " + type.getName());
        } catch (RuntimeException | LinkageError error) {
            galleryGenericWatermarkClasses.remove(type);
            log(Log.WARN, TAG, "Unable to install generic Gallery watermark manager hook", error);
        }
    }

    private static int countBooleanFields(Class<?> type) {
        int count = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Structural fallback for the EXIF validator when its package/class is renamed. */
    private void installGenericWatermarkExifHook(Class<?> type) {
        if (type == null || !galleryWatermarkExifClasses.add(type)
                || !pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
            return;
        }
        try {
            int installed = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (!isBooleanReturn(method.getReturnType())) {
                    continue;
                }
                int count = method.getParameterCount();
                boolean candidate = (Modifier.isStatic(method.getModifiers()) && count == 1
                        && looksLikeExifParameter(method.getParameterTypes()[0]))
                        || (!Modifier.isStatic(method.getModifiers()) && count == 2
                        && looksLikeExifParameter(method.getParameterTypes()[0]));
                if (!candidate) {
                    continue;
                }
                method.setAccessible(true);
                hook(method)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_generic_exif_" + type.getName().replace('.', '_')
                                + "_" + method.getName())
                        .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                ? Boolean.TRUE : chain.proceed());
                installed++;
            }
            if (installed > 0) {
                log(Log.INFO, TAG, "Generic Gallery watermark EXIF hooks installed: "
                        + type.getName() + " (" + installed + ")");
            }
        } catch (RuntimeException | LinkageError error) {
            galleryWatermarkExifClasses.remove(type);
            log(Log.WARN, TAG, "Unable to install generic Gallery watermark EXIF hook", error);
        }
    }

    /**
     * Final cloud-watermark data loader. It must run first so the render data
     * object is populated, then its boolean result can be relaxed for photos
     * without matching model/EXIF metadata.
     */
    private boolean installGalleryWatermarkDataLoaderHook(ClassLoader classLoader) {
        try {
            Class<?> loader = resolveFirstClass(classLoader, "a70.b", "a70.C0082b");
            Class<?> info = resolveFirstClass(classLoader, "i70.d", "i70.AbstractC7785d");
            Class<?> exif = resolveFirstClass(classLoader, "pc.b", "p434pc.C12495b");
            Method target = null;
            for (Method method : loader.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers())
                        && isBooleanReturn(method.getReturnType())
                        && ((params.length == 4 && params[1] == exif && params[3] == info)
                        || (params.length == 2 && params[0] == info))) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException("watermark data loader");
            }
            if (!galleryWatermarkDataLoaderClasses.add(loader)) {
                return true;
            }
            target.setAccessible(true);
            hook(target).setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_data_loader_success")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        return pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                ? Boolean.TRUE : result;
                    });
            log(Log.INFO, TAG, "Gallery watermark data-loader result hook installed");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkDataLoaderClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark data-loader hook", error);
            return false;
        }
    }

    /** Mark generated cloud-watermark entries as available after the stock
     * visibility pass has evaluated device and parameter predicates. */
    private boolean installGalleryWatermarkAvailabilityHook(ClassLoader classLoader) {
        try {
            Class<?> helper = resolveFirstClass(classLoader, "w60.m", "w60.C16633m", "w60.C16633m0");
            Class<?> exif = resolveFirstClass(classLoader, "pc.b", "p434pc.C12495b");
            Class<?> location = resolveFirstClass(classLoader, "o80.m", "o80.C11989m");
            Class<?> locationData = resolveFirstClass(classLoader, "o80.h", "o80.C11984h");
            Method target = null;
            for (Method method : helper.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                        && params.length == 4 && Map.class.isAssignableFrom(params[0])
                        && params[1] == exif && params[2] == location && params[3] == locationData) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException("watermark availability pass");
            }
            if (!galleryWatermarkAvailabilityClasses.add(helper)) {
                return true;
            }
            target.setAccessible(true);
            hook(target).setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_availability_success")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            forceWatermarkItemsAvailable(chain.getArg(0));
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "Gallery watermark availability hook installed");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkAvailabilityClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark availability hook", error);
            return false;
        }
    }

    private static boolean looksLikeExifParameter(Class<?> type) {
        if (type == null || type.isPrimitive()) {
            return false;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getReturnType() == String.class && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == String.class) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBooleanReturn(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static Class<?> resolveFirstClass(ClassLoader loader, String... names)
            throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        throw last == null ? new ClassNotFoundException("watermark class") : last;
    }

    /** Find the Kotlin lambda bridge used by both old and new feature dexes. */
    private static Method findBooleanPredicateMethod(Class<?> type) {
        Method fallback = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 || !isBooleanReturn(method.getReturnType())) {
                    continue;
                }
                if ("mo339g".equals(method.getName()) || "g".equals(method.getName())) {
                    return method;
                }
                if (fallback == null) {
                    fallback = method;
                }
            }
        }
        return fallback;
    }

    private static Method findBooleanMethodByNames(Class<?> type, String... names) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!isBooleanReturn(method.getReturnType()) || method.getParameterCount() != 1) {
                    continue;
                }
                for (String name : names) {
                    if (name.equals(method.getName())) {
                        return method;
                    }
                }
            }
        }
        return null;
    }

    private static Method findMethod(
            Class<?> type,
            String[] names,
            int parameterCount,
            Class<?> exactParameter
    ) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != parameterCount) {
                    continue;
                }
                if (exactParameter != null
                        && (parameterCount != 1 || method.getParameterTypes()[0] != exactParameter)) {
                    continue;
                }
                for (String name : names) {
                    if (name.equals(method.getName())) {
                        return method;
                    }
                }
            }
        }
        return null;
    }

    private static Field findBooleanField(Class<?> type, String... preferredNames) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : preferredNames) {
                try {
                    Field field = current.getDeclaredField(name);
                    if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                        return field;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Continue through the manager hierarchy.
                }
            }
        }
        return null;
    }

    private static Field findWatermarkBypassField(Class<?> type) {
        Field preferred = findBooleanField(type, "f37743k", "f34772k", "k");
        if (preferred != null) {
            return preferred;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if ((field.getType() == boolean.class || field.getType() == Boolean.class)
                        && ("k".equals(field.getName()) || field.getName().endsWith("k"))) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Object findStaticSingleton(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.get(null);
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // Try another static manager field.
                }
            }
        }
        return null;
    }

    private static Field findIterableField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (Iterable.class.isAssignableFrom(fieldType)
                        || Collection.class.isAssignableFrom(fieldType)) {
                    return field;
                }
            }
        }
        return null;
    }

    private Integer getLegendaryMode(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        int mode = number.intValue();
        return mode == LEGENDARY_MODE_M9 || mode == LEGENDARY_MODE_M3 ? mode : null;
    }

    private void applyLegendaryCamera2Fallback(CaptureRequest.Builder builder, int legendaryMode) {
        try {
            builder.set(
                    CaptureRequest.CONTROL_EFFECT_MODE,
                    legendaryMode == LEGENDARY_MODE_M3
                            ? CaptureRequest.CONTROL_EFFECT_MODE_MONO
                            : CaptureRequest.CONTROL_EFFECT_MODE_OFF
            );
            builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    legendaryMode == LEGENDARY_MODE_M3
                            ? CaptureRequest.CONTROL_AWB_MODE_AUTO
                            : CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            );
        } catch (RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to apply Legendary Camera2 compatibility parameters", error);
        }
    }

    private void setStaticStringField(Class<?> type, String name, String value) {
        Field field = null;
        try {
            field = type.getDeclaredField(name);
            if (!Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException(name + " is not static");
            }
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Build.DEVICE/PRODUCT/BRAND are static final fields on recent
            // Android releases and Field#set may be rejected even after
            // setAccessible(true).  Use Unsafe as a fallback so code that
            // reads Build constants (instead of SystemProperties) observes
            // the same spoofed identity.
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field singleton = unsafeClass.getDeclaredField("theUnsafe");
                singleton.setAccessible(true);
                Object unsafe = singleton.get(null);
                Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
                Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
                Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
                Object base = staticFieldBase.invoke(unsafe, field);
                long offset = ((Number) staticFieldOffset.invoke(unsafe, field)).longValue();
                putObject.invoke(unsafe, base, offset, value);
                log(Log.INFO, TAG, "Set static final Build." + name + " via Unsafe");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError fallback) {
                log(Log.WARN, TAG, "Unable to set Build." + name, error);
            }
        }
    }

    private String readStaticStringField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private void installSecurityCompatibilityHooks(ClassLoader classLoader) {
        try {
            Class<?> guard = Class.forName("com.camera.LSsdQFvLalapDwvA", false, classLoader);
            hookBooleanResult(guard, "RitIeKoenwCSqcPf", true, "security_valid");
            hookBooleanResult(guard, "QiVkoLmEuZWFFHiA", false, "security_reject_a");
            hookBooleanResult(guard, "qkPDndbXdHyDtWXd", false, "security_reject_b");
            log(Log.INFO, TAG, "Nezha security compatibility hooks installed");
        } catch (ClassNotFoundException | RuntimeException error) {
            log(Log.ERROR, TAG, "Camera security class was not found", error);
        }
    }

    private void hookBooleanResult(Class<?> owner, String methodName, boolean result, String id) {
        try {
            Method method = owner.getDeclaredMethod(methodName);
            method.setAccessible(true);
            hook(method)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId(id)
                    .intercept(chain -> {
                        if (!isEnabled()) {
                            return chain.proceed();
                        }
                        return result;
                    });
        } catch (NoSuchMethodException | RuntimeException error) {
            log(Log.ERROR, TAG, "Camera security method was not found: " + methodName, error);
        }
    }

}
