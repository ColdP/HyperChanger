package android.graphics.drawable
import android.graphics.Rect
import android.graphics.Canvas
open class Drawable {
    var bounds = Rect()
    var draws = 0
    fun setBounds(l: Int,t: Int,r: Int,b: Int) { bounds = Rect(l,t,r,b) }
    fun draw(canvas: Canvas) { draws++ }
}
