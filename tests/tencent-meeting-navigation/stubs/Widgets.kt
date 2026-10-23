package android.widget
import android.view.View
import android.graphics.drawable.Drawable
class TextView: View() { var text: CharSequence = "" }
open class ImageView: View() { var drawable: Drawable? = Drawable() }
