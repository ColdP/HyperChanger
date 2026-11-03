// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.util.UUID

internal const val ASSISTANT_BACKGROUND_DIRECTORY = "personal_assistant_background"
private const val ASSISTANT_BACKGROUND_AUTHORITY = "btm.m.os4.systemuihook.assistantbackground"
private val backgroundIdPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

internal fun assistantBackgroundUri(id: String): Uri = Uri.Builder().scheme("content")
    .authority(ASSISTANT_BACKGROUND_AUTHORITY).appendPath(id).build()

internal data class PersonalAssistantBackgroundImage(val bitmap: Bitmap, val name: String)

/** Only Save writes a new immutable PNG; picker and Clear operate on the dialog draft. */
internal class PersonalAssistantBackgroundImageStore(context: Context) {
    private val app = context.applicationContext

    fun load(settings: PersonalAssistantSettings): PersonalAssistantBackgroundImage? = runCatching {
        val image = assistantBackgroundFile(app, settings.backgroundImage) ?: return null
        val bitmap = image.inputStream().use(BitmapFactory::decodeStream) ?: return null
        PersonalAssistantBackgroundImage(bitmap, settings.backgroundName)
    }.getOrNull()

    fun read(uri: Uri): PersonalAssistantBackgroundImage {
        val bitmap = decodeAssistantBackground(ImageDecoder.createSource(app.contentResolver, uri))
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: tr("背景文件", "背景文件")
        return PersonalAssistantBackgroundImage(bitmap, name)
    }

    fun save(image: PersonalAssistantBackgroundImage?): String {
        if (image == null) return ""
        val id = UUID.randomUUID().toString()
        val directory = File(app.filesDir, ASSISTANT_BACKGROUND_DIRECTORY).apply { mkdirs() }
        val file = AtomicFile(File(directory, "$id.png"))
        val stream = file.startWrite()
        try {
            check(image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
        return id
    }

    fun removeUnused(savedId: String) {
        File(app.filesDir, ASSISTANT_BACKGROUND_DIRECTORY).listFiles()?.forEach {
            if (it.name != "$savedId.png" && it.extension == "png") it.delete()
        }
    }
}

/** Bound decoded memory and flatten transparency so desktop icons cannot show through a PNG. */
internal fun decodeAssistantBackground(source: ImageDecoder.Source): Bitmap {
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 2560) decoder.setTargetSize(
            (info.size.width.toLong() * 2560 / longest).toInt().coerceAtLeast(1),
            (info.size.height.toLong() * 2560 / longest).toInt().coerceAtLeast(1),
        )
    }
    if (!bitmap.hasAlpha()) return bitmap
    return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(Color.BLACK); drawBitmap(bitmap, 0f, 0f, null) }
        bitmap.recycle()
    }
}

private fun assistantBackgroundFile(context: Context, id: String): File? =
    if (backgroundIdPattern.matches(id)) File(File(context.filesDir, ASSISTANT_BACKGROUND_DIRECTORY), "$id.png") else null

/** Expose only a validated background ID, read-only, to App Vault and the module itself. */
class PersonalAssistantBackgroundProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" || uri.authority != ASSISTANT_BACKGROUND_AUTHORITY || uri.pathSegments.size != 1) return null
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && app.packageManager.getPackagesForUid(uid).orEmpty()
                .none { it == "com.miui.personalassistant" }) return null
        val file = assistantBackgroundFile(app, uri.pathSegments.single())?.takeIf(File::isFile) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
