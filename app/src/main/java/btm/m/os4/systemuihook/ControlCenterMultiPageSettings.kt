// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

internal const val CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED = false

internal const val KEY_CC_MULTI_PAGE_ENABLED = "control_center_multi_page_enabled"
internal const val KEY_CC_MULTI_PAGE_MEDIA = "control_center_multi_page_media"
internal const val KEY_CC_MULTI_PAGE_CONNECTIVITY = "control_center_multi_page_connectivity"
internal const val KEY_CC_MULTI_PAGE_TOOLS = "control_center_multi_page_tools"
internal const val KEY_CC_MULTI_PAGE_ORDER = "control_center_multi_page_order"
internal const val KEY_CC_MULTI_PAGE_INDICATOR = "control_center_multi_page_indicator"
internal const val KEY_CC_MULTI_PAGE_RESET_ON_CLOSE = "control_center_multi_page_reset_on_close"
internal const val KEY_CC_MULTI_PAGE_LAST_PAGE_DISMISS = "control_center_multi_page_last_page_dismiss"

internal enum class CcMultiPageId(val key: String) {
    FAVORITES("favorites"),
    MEDIA("media"),
    CONNECTIVITY("connectivity"),
    TOOLS("tools"),
    ;

    companion object {
        val defaultSecondaryOrder: List<CcMultiPageId> = listOf(MEDIA, CONNECTIVITY, TOOLS)

        fun normalizeOrder(raw: String?): List<CcMultiPageId> {
            val parsed = raw.orEmpty()
                .split(',')
                .map(String::trim)
                .mapNotNull { token -> defaultSecondaryOrder.firstOrNull { it.key == token } }
                .distinct()
            return parsed + defaultSecondaryOrder.filterNot { it in parsed }
        }

        fun encodeOrder(order: List<CcMultiPageId>): String =
            normalizeOrder(order.joinToString(",") { it.key }).joinToString(",") { it.key }
    }
}

data class ControlCenterMultiPageSettings(
    val enabled: Boolean = false,
    val mediaPage: Boolean = true,
    val connectivityPage: Boolean = true,
    val toolsPage: Boolean = true,
    val pageOrder: String = CcMultiPageId.encodeOrder(CcMultiPageId.defaultSecondaryOrder),
    val showIndicator: Boolean = true,
    val resetOnClose: Boolean = true,
    val lastPageSwipeUpDismiss: Boolean = true,
) {
    internal fun orderedSecondaryPages(): List<CcMultiPageId> = CcMultiPageId.normalizeOrder(pageOrder)

    internal fun isPageEnabled(page: CcMultiPageId): Boolean = when (page) {
        CcMultiPageId.FAVORITES -> true
        CcMultiPageId.MEDIA -> mediaPage
        CcMultiPageId.CONNECTIVITY -> connectivityPage
        CcMultiPageId.TOOLS -> toolsPage
    }

    internal fun activePages(): List<CcMultiPageId> = buildList {
        add(CcMultiPageId.FAVORITES)
        orderedSecondaryPages().forEach { page ->
            if (isPageEnabled(page)) add(page)
        }
    }

    internal fun isEffective(): Boolean = enabled && activePages().size > 1
}

internal fun readControlCenterMultiPageSettings(preferences: SharedPreferences) =
    ControlCenterMultiPageSettings(
        enabled = CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED &&
            preferences.getBoolean(KEY_CC_MULTI_PAGE_ENABLED, false),
        mediaPage = preferences.getBoolean(KEY_CC_MULTI_PAGE_MEDIA, true),
        connectivityPage = preferences.getBoolean(KEY_CC_MULTI_PAGE_CONNECTIVITY, true),
        toolsPage = preferences.getBoolean(KEY_CC_MULTI_PAGE_TOOLS, true),
        pageOrder = CcMultiPageId.encodeOrder(
            CcMultiPageId.normalizeOrder(preferences.getString(KEY_CC_MULTI_PAGE_ORDER, null)),
        ),
        showIndicator = preferences.getBoolean(KEY_CC_MULTI_PAGE_INDICATOR, true),
        resetOnClose = preferences.getBoolean(KEY_CC_MULTI_PAGE_RESET_ON_CLOSE, true),
        lastPageSwipeUpDismiss = preferences.getBoolean(KEY_CC_MULTI_PAGE_LAST_PAGE_DISMISS, true),
    )

/** Keep exported and imported presets complete even when multi-page settings were never edited. */
internal fun completeControlCenterMultiPagePreset(values: JSONObject) {
    fun putBoolIfMissing(key: String, defaultValue: Boolean) {
        if (!values.has(key)) {
            values.put(key, JSONObject().put("type", "boolean").put("value", defaultValue))
        }
    }
    if (!CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED) {
        values.put(KEY_CC_MULTI_PAGE_ENABLED, JSONObject().put("type", "boolean").put("value", false))
    } else {
        putBoolIfMissing(KEY_CC_MULTI_PAGE_ENABLED, false)
    }
    putBoolIfMissing(KEY_CC_MULTI_PAGE_MEDIA, true)
    putBoolIfMissing(KEY_CC_MULTI_PAGE_CONNECTIVITY, true)
    putBoolIfMissing(KEY_CC_MULTI_PAGE_TOOLS, true)
    if (!values.has(KEY_CC_MULTI_PAGE_ORDER)) {
        values.put(
            KEY_CC_MULTI_PAGE_ORDER,
            JSONObject()
                .put("type", "string")
                .put("value", CcMultiPageId.encodeOrder(CcMultiPageId.defaultSecondaryOrder)),
        )
    }
    putBoolIfMissing(KEY_CC_MULTI_PAGE_INDICATOR, true)
    putBoolIfMissing(KEY_CC_MULTI_PAGE_RESET_ON_CLOSE, true)
    putBoolIfMissing(KEY_CC_MULTI_PAGE_LAST_PAGE_DISMISS, true)
}

/** Kept outside HookSettings, including offline edits and remote synchronization. */
class ControlCenterMultiPageSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: ControlCenterMultiPageSettings = readControlCenterMultiPageSettings(local)
        private set

    init {
        if (!CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED &&
            local.getBoolean(KEY_CC_MULTI_PAGE_ENABLED, false)
        ) {
            local.edit().putBoolean(KEY_CC_MULTI_PAGE_ENABLED, false).apply()
        }
    }

    fun syncRemote(service: XposedService) {
        if (!CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED &&
            local.getBoolean(KEY_CC_MULTI_PAGE_ENABLED, false)
        ) {
            local.edit().putBoolean(KEY_CC_MULTI_PAGE_ENABLED, false).apply()
        }
        settings = readControlCenterMultiPageSettings(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(
        service: XposedService?,
        transform: (ControlCenterMultiPageSettings) -> ControlCenterMultiPageSettings,
    ) {
        val next = transform(settings)
        settings = next.copy(
            enabled = if (CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED) next.enabled else false,
            pageOrder = CcMultiPageId.encodeOrder(CcMultiPageId.normalizeOrder(next.pageOrder)),
        )
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(preferences: SharedPreferences, value: ControlCenterMultiPageSettings) {
        preferences.edit()
            .putBoolean(
                KEY_CC_MULTI_PAGE_ENABLED,
                if (CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED) value.enabled else false,
            )
            .putBoolean(KEY_CC_MULTI_PAGE_MEDIA, value.mediaPage)
            .putBoolean(KEY_CC_MULTI_PAGE_CONNECTIVITY, value.connectivityPage)
            .putBoolean(KEY_CC_MULTI_PAGE_TOOLS, value.toolsPage)
            .putString(KEY_CC_MULTI_PAGE_ORDER, value.pageOrder)
            .putBoolean(KEY_CC_MULTI_PAGE_INDICATOR, value.showIndicator)
            .putBoolean(KEY_CC_MULTI_PAGE_RESET_ON_CLOSE, value.resetOnClose)
            .putBoolean(KEY_CC_MULTI_PAGE_LAST_PAGE_DISMISS, value.lastPageSwipeUpDismiss)
            .apply()
    }
}
