// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.liquidglass

enum class NavigationStyle(val preferenceValue: String, val displayName: String) {
    LIQUID_GLASS("liquid_glass", "液态玻璃底栏"),
    HYPER_OS("hyper_os", "HyperOS 底栏"),
    HYPER_OS_FLOATING("hyper_os_floating", "HyperOS 悬浮底栏"),
    HARMONY_OS_FLOATING("harmony_os_floating", "HarmonyOS 悬浮底栏");

    companion object {
        const val PREFERENCE_KEY = "nav_style"
        const val DEFAULT_VALUE = "hyper_os"

        @JvmStatic
        fun fromPreference(value: String?): NavigationStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: HYPER_OS
    }
}

enum class AppColorMode(val preferenceValue: String, val displayName: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色模式"),
    DARK("dark", "深色模式");

    companion object {
        const val DEFAULT_VALUE = "system"

        @JvmStatic
        fun fromPreference(value: String?): AppColorMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

enum class LabelMode(val preferenceValue: String, val displayName: String) {
    ICON_AND_TEXT("icon_and_text", "图标和文本"),
    ICON_ONLY("icon_only", "仅图标"),
    TEXT_ONLY("text_only", "仅文本");

    companion object {
        const val DEFAULT_VALUE = "icon_and_text"

        @JvmStatic
        fun fromPreference(value: String?): LabelMode =
            entries.firstOrNull { it.preferenceValue == value } ?: ICON_AND_TEXT
    }
}
