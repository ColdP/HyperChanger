// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover.next.island
import android.content.SharedPreferences
import org.json.JSONObject

/** Portable settings for the HyperChanger lockscreen mini player. */
object MiniPlayerConfig {
    /** Sent by SystemUI so the settings app can reject an older loaded module. */
    const val SCHEMA_VERSION = 9
    const val ENABLED = "enabled"
    const val WIDTH = "widthDp"
    const val HEIGHT_RADIUS = "heightRadiusDp"
    const val ART_RADIUS = "artRadiusDp"

    /** The row takes the room a switched-off torch or camera leaves (MiniPlayerRuntime.pillRest). */
    const val ADAPTIVE_WIDTH = "adaptiveWidth"
    /**
     * Hide the lock screen's quick functions in the full-screen AOD. Not a setting any more
     * (2026-10-09): it is written true and the normalizer does not read the key, so the shortcuts
     * always go with the doze.
     */
    const val HIDE_AOD_SHORTCUTS = "hideAodShortcuts"
    /** Always on and no longer a setting; see normalizedJson. */
    const val NOTIFICATION_MATERIAL = "notificationMaterial"
    const val MEDIA_COLLAPSED_DEFAULT = "mediaCollapsedDefault"
    const val GROUP_NOTIFICATIONS_BY_APP = "groupNotificationsByApp"
    /** Ordinary notifications stay in the OEM list; focus notifications still become islands. */
    const val NORMALS_IN_STACK = "normalsInStack"
    const val STYLE = "style"
    const val STYLE_ROW = 0
    const val STYLE_STACK = 1
    const val MIN_HEIGHT_DP = 20f
    const val MAX_HEIGHT_DP = 120f
    const val DEFAULT_HEIGHT_DP = 54f

    /** 灞忓箷甯镐寒 while a navigation page is up behind the lock screen (ImmersiveHost.holdScreen, #63). */
    const val NAV_KEEP_ON = "navKeepOn"

    /**
     * 鍕挎壈, charging and the like drawn after the date, clear of the pill (DateStatus).
     *
     * A switch again (2026-10-19): on by default, so the line stands after the date as it has
     * since 2026-10-09; off puts the whole line back at the bottom, where the OEM keeps it.
     */
    const val STATUS_AT_DATE = "statusAtDate"

    /** The row lifted off a low under-display fingerprint sensor (MiniPlayerRuntime.fingerprintArea, #66). */
    const val FOD_LIFT = "fodLift"

    private val switches = setOf(ENABLED, ADAPTIVE_WIDTH, NAV_KEEP_ON, FOD_LIFT,
        NORMALS_IN_STACK, STATUS_AT_DATE)

    private val defaults = linkedMapOf<String, Any>(
        ENABLED to false,
        WIDTH to 221f,
        HEIGHT_RADIUS to DEFAULT_HEIGHT_DP / 2f,
        ART_RADIUS to 12f,
        ADAPTIVE_WIDTH to false,
        HIDE_AOD_SHORTCUTS to true,
        NOTIFICATION_MATERIAL to true,
        MEDIA_COLLAPSED_DEFAULT to false,
        GROUP_NOTIFICATIONS_BY_APP to false,
        NORMALS_IN_STACK to true,
        STYLE to STYLE_ROW,
        NAV_KEEP_ON to false,
        STATUS_AT_DATE to true,
        FOD_LIFT to true,
    )

    @JvmStatic fun defaultJson(): String = normalizedJson(null)

    /**
     * Host switches and geometry are normalized together. Width and artwork radius retain
     * HyperChanger's existing controls; shortcut/AOD material policies stay upstream defaults.
     */
    @JvmStatic fun normalizedJson(raw: String?): String {
        val input = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        val out = JSONObject()
        defaults.forEach { (key, fallback) ->
            out.put(key, if (key == WIDTH || key == ART_RADIUS) {
                val value = runCatching { input.getDouble(key).toFloat() }.getOrDefault(fallback as Float)
                if (!value.isFinite()) fallback else if (key == WIDTH) value.coerceIn(160f, 360f) else value.coerceIn(0f, 60f)
            } else if (key == HEIGHT_RADIUS) {
                val value = runCatching { input.getDouble(key).toFloat() }.getOrDefault(fallback as Float)
                if (value.isFinite()) value.coerceIn(MIN_HEIGHT_DP / 2f, MAX_HEIGHT_DP / 2f)
                else fallback
            } else if (key == STYLE) {
                runCatching { input.getInt(key) }.getOrDefault(STYLE_ROW)
                    .takeIf { it == STYLE_ROW || it == STYLE_STACK } ?: STYLE_ROW
            } else if (key == ENABLED || key == ADAPTIVE_WIDTH ||
                key == MEDIA_COLLAPSED_DEFAULT ||
                key == GROUP_NOTIFICATIONS_BY_APP || key in switches) {
                runCatching { input.getBoolean(key) }.getOrDefault(fallback)
            } else {
                fallback
            })
        }
        return out.toString()
    }

    @JvmStatic fun fromPreferences(prefs: SharedPreferences): String =
        normalizedJson(prefs.getString("config", null))

    @JvmStatic fun apply(prefs: SharedPreferences, raw: String?): String {
        val normalized = normalizedJson(raw)
        // SystemUI may be restarted immediately after a setting changes. Commit the small JSON
        // before acknowledging the broadcast, so a pending async write cannot lose that change.
        check(prefs.edit().putString("config", normalized).commit()) {
            "Could not persist mini player settings"
        }
        return normalized
    }

    /** The stored radius sets both the pill's height and the shortcut glass diameter. */
    @JvmStatic fun visibleHeightDp(raw: String?): Float =
        MiniPlayerGeometry.heightDp(JSONObject(normalizedJson(raw))
            .getDouble(HEIGHT_RADIUS).toFloat())
}
