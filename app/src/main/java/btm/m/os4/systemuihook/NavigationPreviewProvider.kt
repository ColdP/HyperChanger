package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import btm.m.liquidglass.ScopedSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Only a scoped app's own UID may replace its bounded, local-only preview snapshot. */
class NavigationPreviewProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        require(method == "publish" && ScopedSettings.apps.any { it.packageName == arg })
        require(ctx.packageManager.getPackagesForUid(Binder.getCallingUid())?.contains(arg) == true)
        val payload = extras?.getString("payload") ?: return null
        require(payload.length <= 512 * 1024)
        val data = JSONObject(payload)
        val tabs = data.getJSONArray("tabs")
        require(tabs.length() in 2..8)
        for (i in 0 until tabs.length()) validate(tabs.getJSONObject(i))
        data.optJSONObject("action")?.let(::validate)
        val target = AtomicFile(navigationPreviewFile(ctx, requireNotNull(arg)))
        target.baseFile.parentFile?.mkdirs()
        val out = target.startWrite()
        try { out.write(payload.toByteArray()); target.finishWrite(out) }
        catch (error: Throwable) { target.failWrite(out); throw error }
        return Bundle.EMPTY
    }
    private fun validate(item: JSONObject) {
        require(item.getString("label").length <= 80 && item.getString("kind").length <= 80)
        val icon = item.optString("icon")
        if (icon.isNotEmpty()) {
            require(icon.length <= 96 * 1024)
            val bytes = Base64.decode(icon, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..192 && bounds.outHeight in 1..192)
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

internal fun navigationPreviewFile(context: Context, packageName: String): File {
    require(ScopedSettings.apps.any { it.packageName == packageName })
    return File(context.filesDir, "navigation_preview/$packageName.json")
}

internal data class NavigationPreviewTab(val label: String, val kind: String, val image: ImageBitmap?, val position: Int = -1, val preserveColor: Boolean = false)

/** Run on IO only, once per topology/icon-availability change; never from pre-draw. */
internal fun publishNavigationPreview(context: Context, packageName: String, tabs: List<NavigationPreviewTab>, action: NavigationPreviewTab?) {
    if (tabs.size !in 2..8) return
    fun encode(tab: NavigationPreviewTab): JSONObject = JSONObject().put("label", tab.label.take(80)).put("kind", tab.kind.take(80)).put("position", tab.position).put("preserveColor", tab.preserveColor).apply {
        tab.image?.let { image ->
            val source = image.asAndroidBitmap()
            val scale = 96f / maxOf(source.width, source.height).coerceAtLeast(1)
            val bitmap = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
            try {
                val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                if (bytes.size <= 64 * 1024) put("icon", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } finally { if (bitmap !== source) bitmap.recycle() }
        }
    }
    val payload = JSONObject().put("tabs", JSONArray().apply { tabs.forEach { put(encode(it)) } })
    if (action != null) payload.put("action", encode(action))
    context.contentResolver.call(Uri.parse("content://btm.m.os4.systemuihook.navigationpreview"), "publish", packageName,
        Bundle().apply { putString("payload", payload.toString()) })
}
