// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.app
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The stack island's picture when its notifications are from more than one app: their pictures
 * together, newest first, laid out as a folder lays out its apps - two side by side, three two
 * over one, four and more two by two. The island shows its picture in a circle (see
 * pill-picture-circle-and-jaggies), so the layout is kept inside the circle the bounds hold.
 *
 * Copies of the pictures are drawn, not the notes' own: those are drawn elsewhere at their own
 * bounds, and a draw here would move them.
 */
internal class AppsIcon(icons: List<Drawable>) : Drawable() {

    private val cells: List<Drawable> =
        icons.take(4).map { it.constantState?.newDrawable()?.mutate() ?: it }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val d = minOf(b.width(), b.height()).toFloat()
        if (d <= 0f || cells.isEmpty()) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        // Cell and gap as parts of the circle's diameter: the outer corners stay on the circle.
        val two = cells.size == 2
        val c = d * (if (two) 0.42f else 0.33f)
        val g = d * (if (two) 0.06f else 0.05f)
        val rows = when (cells.size) {
            1 -> listOf(1)
            2 -> listOf(2)
            3 -> listOf(2, 1)
            else -> listOf(2, 2)
        }
        var top = cy - (rows.size * c + (rows.size - 1) * g) / 2f
        var i = 0
        for (inRow in rows) {
            var left = cx - (inRow * c + (inRow - 1) * g) / 2f
            repeat(inRow) {
                val cell = cells[i++]
                cell.setBounds(left.toInt(), top.toInt(), (left + c).toInt(), (top + c).toInt())
                cell.draw(canvas)
                left += c + g
            }
            top += c + g
        }
    }

    override fun setAlpha(alpha: Int) = cells.forEach { it.alpha = alpha }

    override fun setColorFilter(colorFilter: ColorFilter?) = cells.forEach { it.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
