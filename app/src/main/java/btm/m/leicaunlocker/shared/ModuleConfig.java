// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.leicaunlocker.shared;

import java.util.Map;

public final class ModuleConfig {
    public static final String TARGET_PACKAGE = "com.android.camera";
    public static final String GALLERY_PACKAGE = "com.miui.gallery";
    public static final String GALLERY_PLUGIN_PACKAGE = "com.hyper.gallery.plugin";
    public static final String MEDIA_EDITOR_PACKAGE = "com.miui.mediaeditor";
    public static final String PREFERENCE_GROUP = "settings";

    public static final String KEY_INITIALIZED = "initialized";
    public static final String KEY_MASTER_ENABLED = "master_enabled";
    public static final String KEY_LEICA_UI = "leica_ui";
    public static final String KEY_PRESERVE_NATIVE_FOCAL_LENGTHS = "preserve_native_focal_lengths";
    public static final String KEY_GALLERY_ALL_WATERMARKS = "gallery_all_watermarks";
    /** Enables the Xiaomi 18 Pro Max (madrid) gated MediaEditor AI features. */
    public static final String KEY_GALLERY_PALETTE_UNLOCKED = "gallery_palette_unlocked";
    public static final String KEY_INSTANT_MODE = "instant_mode";

    public static final String[] TARGET_PACKAGES = {
            TARGET_PACKAGE,
            GALLERY_PACKAGE,
            GALLERY_PLUGIN_PACKAGE,
            MEDIA_EDITOR_PACKAGE
    };

    private static final Map<String, String> NEZHA_PROPERTIES = Map.ofEntries(
            Map.entry("ro.product.device", "nezha"),
            Map.entry("ro.build.product", "nezha"),
            Map.entry("ro.product.name", "nezha"),
            Map.entry("ro.product.system.device", "nezha"),
            Map.entry("ro.product.vendor.device", "nezha"),
            Map.entry("ro.product.odm.device", "nezha"),
            Map.entry("ro.product.model", "25128PNA1C"),
            Map.entry("ro.product.marketname", "Xiaomi 17 Ultra by Leica"),
            Map.entry("ro.product.mod_device", "nezha"),
            Map.entry("ro.miui.build.region", "cn"),
            Map.entry("ro.miui.region", "CN")
    );

    /** AI feature identity; watermark identity remains in WATERMARK_PROPERTIES. */
    private static final Map<String, String> AI_PROPERTIES = Map.ofEntries(
            Map.entry("ro.product.device", "madrid"),
            Map.entry("ro.build.product", "madrid"),
            Map.entry("ro.product.name", "madrid"),
            Map.entry("ro.product.system.device", "madrid"),
            Map.entry("ro.product.vendor.device", "madrid"),
            Map.entry("ro.product.odm.device", "madrid"),
            Map.entry("ro.product.mod_device", "madrid"),
            Map.entry("ro.product.model", "Xiaomi 18 Pro Max"),
            Map.entry("ro.product.marketname", "Xiaomi 18 Pro Max"),
            Map.entry("ro.miui.build.region", "cn"),
            Map.entry("ro.miui.region", "CN")
    );

    private static final Map<String, String> WATERMARK_PROPERTIES = Map.ofEntries(
            Map.entry("ro.product.device", "lhasa"),
            Map.entry("ro.build.product", "lhasa"),
            Map.entry("ro.product.name", "lhasa"),
            Map.entry("ro.product.system.device", "lhasa"),
            Map.entry("ro.product.vendor.device", "lhasa"),
            Map.entry("ro.product.odm.device", "lhasa"),
            Map.entry("ro.product.mod_device", "lhasa")
    );

    private ModuleConfig() {
    }

    public static boolean isSupportedPackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName)
                || GALLERY_PACKAGE.equals(packageName)
                || GALLERY_PLUGIN_PACKAGE.equals(packageName)
                || MEDIA_EDITOR_PACKAGE.equals(packageName);
    }

    public static boolean isGalleryPackage(String packageName) {
        return GALLERY_PACKAGE.equals(packageName)
                || GALLERY_PLUGIN_PACKAGE.equals(packageName)
                || MEDIA_EDITOR_PACKAGE.equals(packageName);
    }

    public static boolean isSupportedProcess(String processName) {
        for (String packageName : TARGET_PACKAGES) {
            if (processName.equals(packageName) || processName.startsWith(packageName + ":")) {
                return true;
            }
        }
        return false;
    }

    public static String propertyOverride(String key, boolean leicaUi) {
        if ("ro.product.camera.livephoto.support".equals(key)) {
            return "63";
        }
        if (!leicaUi) {
            return null;
        }
        if ("ro.theme_customize".equals(key)) {
            return "LCC";
        }
        if ("ro.boot.product.theme_customize".equals(key)) {
            return "lcc";
        }
        return NEZHA_PROPERTIES.get(key);
    }

    public static String aiPropertyOverride(String key) {
        if ("ro.product.camera.livephoto.support".equals(key)) {
            return "63";
        }
        return AI_PROPERTIES.get(key);
    }

    public static String galleryWatermarkPropertyOverride(String key) {
        return WATERMARK_PROPERTIES.get(key);
    }
}
