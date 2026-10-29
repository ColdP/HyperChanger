package android.graphics

import java.io.InputStream

class Bitmap {
    var isRecycled = false
    enum class Config { ARGB_8888 }
    fun copy(config: Config, mutable: Boolean) = Bitmap()
    companion object {
        fun createBitmap(width: Int, height: Int, config: Config) = Bitmap()
    }
}
object BitmapFactory { fun decodeStream(stream: InputStream): Bitmap? = Bitmap() }
class Canvas(val bitmap: Bitmap)
data class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 24, var bottom: Int = 24) {
    constructor(other: Rect) : this(other.left, other.top, other.right, other.bottom)
}
