package android.graphics
class Bitmap { enum class Config { ARGB_8888 }; companion object { fun createBitmap(w: Int, h: Int, c: Config) = Bitmap() } }
class Canvas(val bitmap: Bitmap)
data class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 10, var bottom: Int = 10) {
    constructor(other: Rect): this(other.left,other.top,other.right,other.bottom)
}
