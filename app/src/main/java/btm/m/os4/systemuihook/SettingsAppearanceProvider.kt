// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.CalendarContract
import android.content.res.Configuration
import java.io.File

class SettingsAppearanceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val slot = uri.pathSegments.firstOrNull().orEmpty()
        if (slot == LOCKSCREEN_SCHEDULE_PATH) return queryNextSchedule(projection)
        if (slot == LYRIC_LIBRARY_PATH) return queryLyrics(projection)
        val file = appearanceFile(slot)
        val prefs = requireContext().getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, 0)
        val columns = projection ?: COLUMNS
        val row = MatrixCursor(columns)
        val values = mapOf(
            COLUMN_MIME to prefs.getString(mimeKey(slot), "").orEmpty(),
            COLUMN_SIZE to if (file.isFile) file.length() else -1L,
            COLUMN_MODIFIED to if (file.isFile) file.lastModified() else -1L,
            // MatrixCursor is read through Cursor.getInt() in the Settings process.
            // Store a numeric flag so the value survives the provider IPC boundary.
            COLUMN_ENABLED to if (enabled(prefs, slot)) 1 else 0,
            COLUMN_OPACITY to opacity(prefs, slot),
            COLUMN_BLUR to blur(prefs, slot),
            COLUMN_FONT to font(prefs, slot),
            COLUMN_SCALE to prefs.getInt("logo_scale", 100),
            COLUMN_LOGO_MODE to prefs.getInt("logo_mode", LOGO_MODE_SYSTEM),
            COLUMN_LIGHT_CARD_OPACITY to prefs.getInt("light_card_opacity", 100),
            COLUMN_TUTORIAL_CARD_ENABLED to if (prefs.getBoolean("tutorial_card_enabled", false)) 1 else 0,
            COLUMN_TUTORIAL_CARD_TITLE to prefs.getString("tutorial_card_title", "").orEmpty(),
            COLUMN_TUTORIAL_CARD_SLOGAN to prefs.getString("tutorial_card_slogan", "").orEmpty(),
            COLUMN_TUTORIAL_CARD_AUTHOR to prefs.getString("tutorial_card_author", "").orEmpty(),
            COLUMN_TUTORIAL_CARD_IMAGE_SCALE to prefs.getInt("tutorial_card_image_scale", 100),
            COLUMN_TUTORIAL_CARD_LOGO_SCALE to prefs.getInt("tutorial_card_logo_scale", 100),
            COLUMN_TUTORIAL_CARD_LOGO_VERTICAL_OFFSET to prefs.getInt("tutorial_card_logo_vertical_offset", 0),
            COLUMN_TUTORIAL_CARD_IMAGE_LOGO_SPACING to prefs.getInt("tutorial_card_image_logo_spacing", 0),
            COLUMN_TUTORIAL_CARD_TEXT_SPACING to prefs.getInt("tutorial_card_text_spacing", 0),
            COLUMN_TUTORIAL_CARD_INFO_CARDS_ENABLED to if (prefs.getBoolean("tutorial_card_info_cards_enabled", false)) 1 else 0,
            COLUMN_STYLE1_BACKGROUND_BLUR to prefs.getFloatCompat("tutorial_card_background_blur", 0f),
            COLUMN_STYLE1_BACKGROUND_VERTICAL_OFFSET to prefs.getInt("tutorial_card_background_vertical_offset", 0),
            COLUMN_STYLE1_BACKGROUND_HORIZONTAL_OFFSET to prefs.getInt("tutorial_card_background_horizontal_offset", 0),
            COLUMN_STYLE1_BACKGROUND_SCALE to prefs.getInt("tutorial_card_background_scale", 100),
            COLUMN_DEVICE_INTERFACE_STYLE to prefs.getInt("device_interface_style", if (prefs.getBoolean("tutorial_card_enabled", false)) DEVICE_INTERFACE_STYLE_ONE else DEVICE_INTERFACE_STYLE_SYSTEM),
            COLUMN_STYLE2_IMAGE_SCALE to prefs.getInt("style2_image_scale", 100),
            COLUMN_STYLE2_LOGO_VERTICAL_OFFSET to style2LogoOffset(prefs, "vertical"),
            COLUMN_STYLE2_IMAGE_LOGO_SPACING to prefs.getInt("style2_image_logo_spacing", 0),
            COLUMN_STYLE2_LOGO_HORIZONTAL_OFFSET to style2LogoOffset(prefs, "horizontal"),
            COLUMN_STYLE2_LOGO_ALIGNMENT to prefs.getInt("style2_logo_alignment", 1),
            COLUMN_STYLE2_LOGO_VERSION_SPACING to prefs.getInt("style2_logo_version_spacing", 0),
            COLUMN_STYLE2_TEXT_ENABLED to if (prefs.getBoolean("style2_text_enabled", false)) 1 else 0,
            COLUMN_STYLE2_TEXT to prefs.getString("style2_text", "").orEmpty(),
            COLUMN_STYLE2_TEXT_INDEPENDENT to if (prefs.getBoolean("style2_text_independent", false)) 1 else 0,
            COLUMN_STYLE2_TEXT_SCALE to prefs.getInt("style2_text_scale", 100),
            COLUMN_STYLE2_TEXT_POSITION to prefs.getInt("style2_text_position", 0),
            COLUMN_STYLE2_TEXT_SPACING_ABOVE to prefs.getInt("style2_text_spacing_above", 0),
            COLUMN_STYLE2_TEXT_SPACING_BELOW to prefs.getInt("style2_text_spacing_below", 0),
            COLUMN_STYLE2_TEXT_ALIGNMENT to prefs.getInt("style2_text_alignment", 0),
            COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET to style2TextOffset(prefs, "horizontal"),
            COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_LEFT to prefs.getInt("style2_text_horizontal_offset_left", prefs.getInt("style2_text_horizontal_offset", 0)),
            COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_CENTER to prefs.getInt("style2_text_horizontal_offset_center", prefs.getInt("style2_text_horizontal_offset", 0)),
            COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_RIGHT to prefs.getInt("style2_text_horizontal_offset_right", prefs.getInt("style2_text_horizontal_offset", 0)),
            COLUMN_STYLE2_TEXT_VERTICAL_OFFSET to style2TextOffset(prefs, "vertical"),
            COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_LEFT to prefs.getInt("style2_text_vertical_offset_left", prefs.getInt("style2_text_vertical_offset", 0)),
            COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_CENTER to prefs.getInt("style2_text_vertical_offset_center", prefs.getInt("style2_text_vertical_offset", 0)),
            COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_RIGHT to prefs.getInt("style2_text_vertical_offset_right", prefs.getInt("style2_text_vertical_offset", 0)),
            COLUMN_STYLE2_LOGO_COLOR_MODE to prefs.getInt("style2_logo_color_mode", 0),
            COLUMN_STYLE2_VERSION_COLOR_MODE to prefs.getInt("style2_version_color_mode", 0),
            COLUMN_STYLE2_TEXT_COLOR_MODE to prefs.getInt("style2_text_color_mode", 0),
            COLUMN_STYLE2_BACKGROUND_BLUR to prefs.getFloatCompat("style2_background_blur", 0f),
            COLUMN_STYLE2_BACKGROUND_VERTICAL_OFFSET to prefs.getInt("style2_background_vertical_offset", 0),
            COLUMN_STYLE2_BACKGROUND_HORIZONTAL_OFFSET to prefs.getInt("style2_background_horizontal_offset", 0),
            COLUMN_STYLE2_BACKGROUND_SCALE to prefs.getInt("style2_background_scale", 100),
        )
        row.addRow(columns.map { values[it] ?: 0 })
        return row
    }

    override fun getType(uri: Uri): String? = requireContext()
        .getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, 0)
        .getString(mimeKey(uri.pathSegments.firstOrNull().orEmpty()), null)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.pathSegments.firstOrNull() == LYRIC_LIBRARY_PATH) {
            val id = uri.pathSegments.getOrNull(1) ?: return null
            val file = runCatching { LyricLibraryStore(requireContext()).lyricFile(id) }.getOrNull()
                ?: return null
            if (!file.isFile) return null
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val file = appearanceFile(uri.pathSegments.firstOrNull().orEmpty())
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun queryLyrics(projection: Array<out String>?): Cursor {
        val columns = projection ?: LYRIC_COLUMNS
        return MatrixCursor(columns).apply {
            LyricLibraryStore(requireContext()).entries().forEach { entry ->
                val values = mapOf(
                    COLUMN_LYRIC_ID to entry.id,
                    COLUMN_LYRIC_TITLE to entry.title,
                    COLUMN_LYRIC_ARTIST to entry.artist,
                    COLUMN_LYRIC_ALIASES to entry.aliases,
                    COLUMN_LYRIC_TYPE to entry.type,
                )
                addRow(columns.map { values[it].orEmpty() })
            }
            setNotificationUri(requireContext().contentResolver, LYRIC_LIBRARY_URI)
        }
    }

    private fun queryNextSchedule(projection: Array<out String>?): Cursor {
        val columns = projection ?: SCHEDULE_COLUMNS
        val result = MatrixCursor(columns)
        runCatching {
            val now = System.currentTimeMillis()
            val end = now + SCHEDULE_LOOKAHEAD_MS
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(now.toString())
                .appendPath(end.toString())
                .build()
            val calendarProjection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
            )
            requireContext().contentResolver.query(
                uri,
                calendarProjection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val values = mapOf(
                        COLUMN_SCHEDULE_TITLE to cursor.getString(0).orEmpty(),
                        COLUMN_SCHEDULE_BEGIN to cursor.getLong(1),
                        COLUMN_SCHEDULE_ALL_DAY to cursor.getInt(2),
                    )
                    result.addRow(columns.map { values[it] ?: 0 })
                }
            }
        }
        return result
    }

    private fun appearanceFile(slot: String): File {
        require(slot == APPEARANCE_SLOT_HOME || slot == APPEARANCE_SLOT_DEVICE || slot == APPEARANCE_SLOT_LOGO || slot == APPEARANCE_SLOT_DEVICE_IMAGE || slot == APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO || slot == APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND || slot == APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE || slot == APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO || slot == APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND || slot == LOCKSCREEN_WIDGET_SIGNATURE_SLOT)
        if (slot == LOCKSCREEN_WIDGET_SIGNATURE_SLOT) {
            return File(File(requireContext().filesDir, "lockscreen_widget"), "signature.bin")
        }
        val prefs = requireContext().getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, 0)
        val effective = if (prefs.getBoolean("color_mode_assets_enabled", false) && slot in COLOR_MODE_ASSET_SLOTS) {
            colorModeAssetKey(slot, isNight())
        } else slot
        return File(File(requireContext().filesDir, "settings_appearance"), "$effective.bin")
    }

    private fun enabled(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getBoolean("home_enabled", false)
        APPEARANCE_SLOT_DEVICE -> prefs.getBoolean("device_enabled", false)
        APPEARANCE_SLOT_LOGO -> prefs.getInt("logo_mode", LOGO_MODE_SYSTEM) != LOGO_MODE_SYSTEM
        APPEARANCE_SLOT_DEVICE_IMAGE, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> prefs.getInt("device_interface_style", if (prefs.getBoolean("tutorial_card_enabled", false)) DEVICE_INTERFACE_STYLE_ONE else DEVICE_INTERFACE_STYLE_SYSTEM) == DEVICE_INTERFACE_STYLE_ONE
        APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> prefs.getInt("device_interface_style", DEVICE_INTERFACE_STYLE_SYSTEM) == DEVICE_INTERFACE_STYLE_TWO
        APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> prefs.getInt("device_interface_style", DEVICE_INTERFACE_STYLE_SYSTEM) == DEVICE_INTERFACE_STYLE_TWO
        else -> false
    }

    private fun opacity(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getInt("home_opacity", 100)
        APPEARANCE_SLOT_DEVICE -> prefs.getInt("device_opacity", 100)
        else -> 100
    }

    private fun blur(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getFloatCompat("home_blur", 0f)
        APPEARANCE_SLOT_DEVICE -> prefs.getFloatCompat("device_blur", 0f)
        else -> 0f
    }

    private fun android.content.SharedPreferences.getFloatCompat(key: String, default: Float): Float =
        runCatching { getFloat(key, default) }.getOrElse {
            runCatching { getInt(key, default.toInt()).toFloat() }.getOrDefault(default)
        }

    private fun font(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getInt("home_font", 0)
        APPEARANCE_SLOT_DEVICE -> prefs.getInt("device_font", 0)
        else -> 0
    }

    private fun style2LogoOffset(prefs: android.content.SharedPreferences, axis: String): Int {
        val alignment = prefs.getInt("style2_logo_alignment", 1).coerceIn(0, 2)
        val legacyKey = "style2_logo_${axis}_offset"
        val suffix = when (alignment) {
            0 -> "left"
            2 -> "right"
            else -> "center"
        }
        return prefs.getInt("style2_logo_${axis}_offset_$suffix", prefs.getInt(legacyKey, 0))
    }

    private fun style2TextOffset(prefs: android.content.SharedPreferences, axis: String): Int {
        val alignment = prefs.getInt("style2_text_alignment", 0).coerceIn(0, 2)
        val legacyKey = "style2_text_${axis}_offset"
        val suffix = when (alignment) {
            0 -> "left"
            2 -> "right"
            else -> "center"
        }
        return prefs.getInt("style2_text_${axis}_offset_$suffix", prefs.getInt(legacyKey, 0))
    }

    private fun mimeKey(slot: String): String {
        val prefs = requireContext().getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, 0)
        if (prefs.getBoolean("color_mode_assets_enabled", false) && slot in COLOR_MODE_ASSET_SLOTS) {
            return "${colorModeAssetKey(slot, isNight())}_mime"
        }
        return when (slot) {
        APPEARANCE_SLOT_HOME -> "home_mime"
        APPEARANCE_SLOT_DEVICE -> "device_mime"
        APPEARANCE_SLOT_LOGO -> "logo_mime"
        APPEARANCE_SLOT_DEVICE_IMAGE -> "tutorial_card_image_mime"
        APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO -> "tutorial_card_logo_mime"
        APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> "tutorial_card_background_mime"
        APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE -> "style2_image_mime"
        APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> "style2_logo_mime"
        APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> "style2_background_mime"
            else -> ""
        }
    }

    private fun isNight(): Boolean = requireContext().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    companion object {
        const val COLUMN_MIME = "mime_type"
        const val COLUMN_SIZE = "size"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_ENABLED = "enabled"
        const val COLUMN_OPACITY = "opacity"
        const val COLUMN_BLUR = "blur"
        const val COLUMN_FONT = "font"
        const val COLUMN_SCALE = "scale"
        const val COLUMN_LOGO_MODE = "logo_mode"
        const val COLUMN_LIGHT_CARD_OPACITY = "light_card_opacity"
        const val COLUMN_TUTORIAL_CARD_ENABLED = "tutorial_card_enabled"
        const val COLUMN_TUTORIAL_CARD_TITLE = "tutorial_card_title"
        const val COLUMN_TUTORIAL_CARD_SLOGAN = "tutorial_card_slogan"
        const val COLUMN_TUTORIAL_CARD_AUTHOR = "tutorial_card_author"
        const val COLUMN_TUTORIAL_CARD_IMAGE_SCALE = "tutorial_card_image_scale"
        const val COLUMN_TUTORIAL_CARD_LOGO_SCALE = "tutorial_card_logo_scale"
        const val COLUMN_TUTORIAL_CARD_LOGO_VERTICAL_OFFSET = "tutorial_card_logo_vertical_offset"
        const val COLUMN_TUTORIAL_CARD_IMAGE_LOGO_SPACING = "tutorial_card_image_logo_spacing"
        const val COLUMN_TUTORIAL_CARD_TEXT_SPACING = "tutorial_card_text_spacing"
        const val COLUMN_TUTORIAL_CARD_INFO_CARDS_ENABLED = "tutorial_card_info_cards_enabled"
        const val COLUMN_DEVICE_INTERFACE_STYLE = "device_interface_style"
        const val COLUMN_STYLE2_IMAGE_SCALE = "style2_image_scale"
        const val COLUMN_STYLE2_LOGO_VERTICAL_OFFSET = "style2_logo_vertical_offset"
        const val COLUMN_STYLE2_IMAGE_LOGO_SPACING = "style2_image_logo_spacing"
        const val COLUMN_STYLE2_LOGO_HORIZONTAL_OFFSET = "style2_logo_horizontal_offset"
        const val COLUMN_STYLE2_LOGO_ALIGNMENT = "style2_logo_alignment"
        const val COLUMN_STYLE2_LOGO_VERSION_SPACING = "style2_logo_version_spacing"
        const val COLUMN_STYLE2_TEXT_ENABLED = "style2_text_enabled"
        const val COLUMN_STYLE2_TEXT = "style2_text"
        const val COLUMN_STYLE2_TEXT_INDEPENDENT = "style2_text_independent"
        const val COLUMN_STYLE2_TEXT_SCALE = "style2_text_scale"
        const val COLUMN_STYLE2_TEXT_POSITION = "style2_text_position"
        const val COLUMN_STYLE2_TEXT_SPACING_ABOVE = "style2_text_spacing_above"
        const val COLUMN_STYLE2_TEXT_SPACING_BELOW = "style2_text_spacing_below"
        const val COLUMN_STYLE2_TEXT_ALIGNMENT = "style2_text_alignment"
        const val COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET = "style2_text_horizontal_offset"
        const val COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_LEFT = "style2_text_horizontal_offset_left"
        const val COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_CENTER = "style2_text_horizontal_offset_center"
        const val COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_RIGHT = "style2_text_horizontal_offset_right"
        const val COLUMN_STYLE2_TEXT_VERTICAL_OFFSET = "style2_text_vertical_offset"
        const val COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_LEFT = "style2_text_vertical_offset_left"
        const val COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_CENTER = "style2_text_vertical_offset_center"
        const val COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_RIGHT = "style2_text_vertical_offset_right"
        const val COLUMN_STYLE2_LOGO_COLOR_MODE = "style2_logo_color_mode"
        const val COLUMN_STYLE2_VERSION_COLOR_MODE = "style2_version_color_mode"
        const val COLUMN_STYLE2_TEXT_COLOR_MODE = "style2_text_color_mode"
        const val COLUMN_STYLE2_BACKGROUND_BLUR = "style2_background_blur"
        const val COLUMN_STYLE2_BACKGROUND_VERTICAL_OFFSET = "style2_background_vertical_offset"
        const val COLUMN_STYLE2_BACKGROUND_HORIZONTAL_OFFSET = "style2_background_horizontal_offset"
        const val COLUMN_STYLE2_BACKGROUND_SCALE = "style2_background_scale"
        const val LOCKSCREEN_SCHEDULE_PATH = "lockscreen_schedule"
        const val COLUMN_SCHEDULE_TITLE = "title"
        const val COLUMN_SCHEDULE_BEGIN = "begin"
        const val COLUMN_SCHEDULE_ALL_DAY = "all_day"
        const val COLUMN_LYRIC_ID = "id"
        const val COLUMN_LYRIC_TITLE = "title"
        const val COLUMN_LYRIC_ARTIST = "artist"
        const val COLUMN_LYRIC_ALIASES = "aliases"
        const val COLUMN_LYRIC_TYPE = "type"
        private const val SCHEDULE_LOOKAHEAD_MS = 30L * 24L * 60L * 60L * 1000L
        private val SCHEDULE_COLUMNS = arrayOf(COLUMN_SCHEDULE_TITLE, COLUMN_SCHEDULE_BEGIN, COLUMN_SCHEDULE_ALL_DAY)
        private val LYRIC_COLUMNS = arrayOf(
            COLUMN_LYRIC_ID,
            COLUMN_LYRIC_TITLE,
            COLUMN_LYRIC_ARTIST,
            COLUMN_LYRIC_ALIASES,
            COLUMN_LYRIC_TYPE,
        )
        const val COLUMN_STYLE1_BACKGROUND_BLUR = "style1_background_blur"
        const val COLUMN_STYLE1_BACKGROUND_VERTICAL_OFFSET = "style1_background_vertical_offset"
        const val COLUMN_STYLE1_BACKGROUND_HORIZONTAL_OFFSET = "style1_background_horizontal_offset"
        const val COLUMN_STYLE1_BACKGROUND_SCALE = "style1_background_scale"
        private val COLUMNS = arrayOf(COLUMN_MIME, COLUMN_SIZE, COLUMN_MODIFIED, COLUMN_ENABLED, COLUMN_OPACITY, COLUMN_BLUR, COLUMN_FONT, COLUMN_SCALE, COLUMN_LOGO_MODE, COLUMN_LIGHT_CARD_OPACITY, COLUMN_TUTORIAL_CARD_ENABLED, COLUMN_TUTORIAL_CARD_TITLE, COLUMN_TUTORIAL_CARD_SLOGAN, COLUMN_TUTORIAL_CARD_AUTHOR, COLUMN_TUTORIAL_CARD_IMAGE_SCALE, COLUMN_TUTORIAL_CARD_LOGO_SCALE, COLUMN_TUTORIAL_CARD_LOGO_VERTICAL_OFFSET, COLUMN_TUTORIAL_CARD_IMAGE_LOGO_SPACING, COLUMN_TUTORIAL_CARD_TEXT_SPACING, COLUMN_TUTORIAL_CARD_INFO_CARDS_ENABLED, COLUMN_STYLE1_BACKGROUND_BLUR, COLUMN_STYLE1_BACKGROUND_VERTICAL_OFFSET, COLUMN_STYLE1_BACKGROUND_HORIZONTAL_OFFSET, COLUMN_STYLE1_BACKGROUND_SCALE, COLUMN_DEVICE_INTERFACE_STYLE, COLUMN_STYLE2_IMAGE_SCALE, COLUMN_STYLE2_LOGO_VERTICAL_OFFSET, COLUMN_STYLE2_IMAGE_LOGO_SPACING, COLUMN_STYLE2_LOGO_HORIZONTAL_OFFSET, COLUMN_STYLE2_LOGO_ALIGNMENT, COLUMN_STYLE2_LOGO_VERSION_SPACING, COLUMN_STYLE2_TEXT_ENABLED, COLUMN_STYLE2_TEXT, COLUMN_STYLE2_TEXT_INDEPENDENT, COLUMN_STYLE2_TEXT_SCALE, COLUMN_STYLE2_TEXT_POSITION, COLUMN_STYLE2_TEXT_SPACING_ABOVE, COLUMN_STYLE2_TEXT_SPACING_BELOW, COLUMN_STYLE2_TEXT_ALIGNMENT, COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET, COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_LEFT, COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_CENTER, COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_RIGHT, COLUMN_STYLE2_TEXT_VERTICAL_OFFSET, COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_LEFT, COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_CENTER, COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_RIGHT, COLUMN_STYLE2_LOGO_COLOR_MODE, COLUMN_STYLE2_VERSION_COLOR_MODE, COLUMN_STYLE2_TEXT_COLOR_MODE, COLUMN_STYLE2_BACKGROUND_BLUR, COLUMN_STYLE2_BACKGROUND_VERTICAL_OFFSET, COLUMN_STYLE2_BACKGROUND_HORIZONTAL_OFFSET, COLUMN_STYLE2_BACKGROUND_SCALE)
    }
}
