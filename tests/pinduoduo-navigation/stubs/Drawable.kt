package android.graphics.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

open class Drawable {
    var bounds = Rect()
    val intrinsicWidth = 24
    val intrinsicHeight = 24
    var draws = 0
    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) { bounds = Rect(left, top, right, bottom) }
    fun draw(canvas: Canvas) { draws++ }
}
class BitmapDrawable(val bitmap: Bitmap) : Drawable()
