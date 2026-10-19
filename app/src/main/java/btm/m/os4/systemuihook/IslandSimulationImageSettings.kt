// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import btm.m.os4.systemuihook.IslandSimulationImageSlot.*

internal data class IslandSimulationImage(val bitmap: Bitmap, val name: String)

internal enum class IslandSimulationImageSlot(val key: String, val pictureKey: String) {
    PRIMARY("primary", ISLAND_SIM_IMAGE),
    IDENTIFICATION("identification", ISLAND_SIM_IDENTIFICATION),
    FUNCTION("function", ISLAND_SIM_FUNCTION),
    SUMMARY("summary", ISLAND_SIM_SUMMARY);

    fun title(type: IslandSimulationType): String = when (this) {
        PRIMARY -> when (type) {
            IslandSimulationType.CALL, IslandSimulationType.PHONE, IslandSimulationType.PROGRESS -> tr("island_sim_avatar_image", "头像图片")
            IslandSimulationType.RESERVATION -> tr("island_sim_cover_image", "封面图片")
            else -> tr("island_sim_import_image", "导入图片")
        }
        IDENTIFICATION -> tr("island_sim_identification_image", "识别图片")
        FUNCTION -> tr("island_sim_function_image", "功能图标")
        SUMMARY -> tr("island_sim_summary_image", "摘要态图标")
    }
}

internal fun islandSimulationImageSlots(type: IslandSimulationType): List<IslandSimulationImageSlot> =
    when (type) {
            IslandSimulationType.TEXT, IslandSimulationType.PHONE, IslandSimulationType.VERIFICATION -> listOf(PRIMARY, SUMMARY)
            IslandSimulationType.CALL, IslandSimulationType.PROGRESS, IslandSimulationType.RESERVATION,
            IslandSimulationType.FILE_SHARE, IslandSimulationType.PAYMENT -> listOf(PRIMARY, IDENTIFICATION, SUMMARY)
            IslandSimulationType.DELIVERY, IslandSimulationType.MOVIE_LOGISTICS -> listOf(IDENTIFICATION, SUMMARY)
            IslandSimulationType.HEALTH -> listOf(IDENTIFICATION, FUNCTION, SUMMARY)
    }

/** Private app storage per template and image slot; selecting/clearing a draft never writes here. */
internal class IslandSimulationImageStore(
    context: Context,
    type: IslandSimulationType = IslandSimulationType.TEXT,
    slot: IslandSimulationImageSlot = IslandSimulationImageSlot.PRIMARY,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("island_simulation_image", Context.MODE_PRIVATE)
    // Keep the original text image and filename key readable after adding per-type slots.
    private val originalTextImage = type == IslandSimulationType.TEXT && slot == IslandSimulationImageSlot.PRIMARY
    private val fileNameKey = if (originalTextImage) "file_name" else "file_name_${type.key}_${slot.key}"
    private val image = AtomicFile(File(app.filesDir,
        if (originalTextImage) "island_simulation_text_image.png" else "island_simulation_${type.key}_${slot.key}.png"))

    fun load(): IslandSimulationImage? = runCatching {
        val bitmap = image.openRead().use(BitmapFactory::decodeStream) ?: return null
        IslandSimulationImage(bitmap, prefs.getString(fileNameKey, null) ?: tr("island_sim_image", "超级岛图片"))
    }.getOrNull()

    fun read(uri: Uri): IslandSimulationImage {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longest = maxOf(width, height)
            if (longest > MAX_SIDE) decoder.setTargetSize(
                (width.toLong() * MAX_SIDE / longest).toInt().coerceAtLeast(1),
                (height.toLong() * MAX_SIDE / longest).toInt().coerceAtLeast(1),
            )
        }
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: tr("island_sim_image", "超级岛图片")
        return IslandSimulationImage(bitmap, name)
    }

    fun save(value: IslandSimulationImage?) {
        if (value == null) {
            image.delete()
            check(!image.baseFile.exists())
            prefs.edit().remove(fileNameKey).apply()
            return
        }
        val output = image.startWrite()
        try {
            check(value.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            image.finishWrite(output)
        } catch (error: Throwable) {
            image.failWrite(output)
            throw error
        }
        prefs.edit().putString(fileNameKey, value.name).apply()
    }

    private companion object {
        // Above the template's recommended 224px, bounded for notification parcel size.
        const val MAX_SIDE = 384
    }
}
