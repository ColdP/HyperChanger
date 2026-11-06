// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Reuses the OEM full-span edit row immediately after QSListController's last tile. */
internal class ControlCenterTopButtonsPlacement {
    private var rootRef = WeakReference<ViewGroup>(null)
    private var retained = emptyList<WeakReference<View>>()
    private val topParams = WeakHashMap<View, FrameLayout.LayoutParams>()
    private var rowRef = WeakReference<ViewGroup>(null)
    private var hostRef = WeakReference<FrameLayout>(null)
    private var touchRef = WeakReference<View>(null)
    private var originalHeight = ViewGroup.LayoutParams.WRAP_CONTENT
    private var originalMarginTop = 0
    private var originalVisibility = View.VISIBLE
    private var originalRowVisibility = View.VISIBLE
    private var originalRowTag: Any? = null
    private var nativeEditHidden = false

    fun retainedButton(root: ViewGroup, tag: String): View? =
        if (rootRef.get() === root) retained.firstNotNullOfOrNull { it.get()?.takeIf { v -> v.tag == tag } } else null

    fun clear(hideNativeEdit: Boolean) {
        nativeEditHidden = hideNativeEdit
        restoreRow()
        retained.forEach { it.get()?.let { button -> (button.parent as? ViewGroup)?.removeView(button) } }
        retained = emptyList()
        rootRef.clear()
    }

    @JvmOverloads
    fun update(root: ViewGroup, controller: Any?, buttons: List<View>, settings: ControlCenterTopButtonsPlacementSettings,
        hideNativeEdit: Boolean, spacingDp: Int = 0) {
        nativeEditHidden = hideNativeEdit
        if (rootRef.get() !== root) {
            restoreRow()
            rootRef = WeakReference(root)
        }
        retained = buttons.map(::WeakReference)
        buttons.forEach { button ->
            if (button.parent === root && button.layoutParams is FrameLayout.LayoutParams)
                topParams.putIfAbsent(button, FrameLayout.LayoutParams(button.layoutParams as FrameLayout.LayoutParams))
        }
        val panel = invoke(field(controller, "mainPanelController"), "get")
        val style = invoke(panel, "getStyle")?.toString()
        val mode = invoke(invoke(panel, "getModeController"), "getMode")?.toString()
        // Keep OEM mode availability, including the existing optional landscape top buttons.
        val dock = settings.atBottom && (controller == null ||
            (mode == "NORMAL" && style !in setOf("COMPACT", "HORIZONTAL", "WIDE_FOLD_COMPACT")))
        if (!dock) {
            restoreRow()
            buttons.forEach { button ->
                button.clipBounds = null
                if (button.parent !== root) {
                    (button.parent as? ViewGroup)?.removeView(button)
                    root.addView(button, topParams[button]?.let { FrameLayout.LayoutParams(it) }
                        ?: FrameLayout.LayoutParams(button.layoutParams))
                }
                val baseline = topParams[button]
                val params = button.layoutParams as? FrameLayout.LayoutParams
                if (baseline != null && params != null) {
                    val offset = controlCenterTopButtonsSideOffsetPx(spacingDp, root.resources.displayMetrics.density)
                    params.leftMargin = baseline.leftMargin + offset
                    params.rightMargin = baseline.rightMargin + offset
                    button.layoutParams = params
                }
            }
            return
        }
        val holder = invoke(controller, "getHolder")
        val row = field(holder, "itemView") as? ViewGroup
        if (row == null) {
            // A recycled footer remains offscreen with its buttons. Before the first bind,
            // prevent a flash at the old top position; the bind callback retries placement.
            if (hostRef.get() == null) buttons.forEach { it.clipBounds = Rect() }
            return
        }
        if (rowRef.get() !== row) {
            restoreRow()
            rowRef = WeakReference(row)
            originalRowVisibility = row.visibility
            originalRowTag = row.tag
            originalHeight = row.layoutParams.height
            originalMarginTop = (row.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            val touchId = row.resources.getIdentifier("touch_container", "id", "miui.systemui.plugin")
            val touch = if (touchId == 0) null else row.findViewById<View>(touchId)
            touchRef = WeakReference(touch)
            originalVisibility = touch?.visibility ?: View.VISIBLE
        }
        // The legacy hide-edit-button hooks identify this entire root row by id.
        // Mark it before restoring visibility so those hooks preserve our button host.
        row.tag = BOTTOM_ROW_TAG
        row.visibility = View.VISIBLE
        touchRef.get()?.visibility = View.GONE
        val size = (44f * root.resources.displayMetrics.density).roundToInt()
        val host = hostRef.get() ?: FrameLayout(row.context).apply {
            clipChildren = false
            clipToPadding = false
            row.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size))
            hostRef = WeakReference(this)
        }
        row.layoutParams = row.layoutParams.apply {
            height = size
            (this as? ViewGroup.MarginLayoutParams)?.topMargin =
                controlCenterTopButtonsBottomGapPx(settings.gapDp, root.resources.displayMetrics.density)
        }
        buttons.forEachIndexed { index, button ->
            button.clipBounds = null
            if (button.parent !== host) {
                (button.parent as? ViewGroup)?.removeView(button)
                host.addView(button, FrameLayout.LayoutParams(size, size,
                    Gravity.TOP or if (index == 0) Gravity.START else Gravity.END))
            }
            (button.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                val offset = controlCenterTopButtonsSideOffsetPx(spacingDp, root.resources.displayMetrics.density)
                params.leftMargin = offset
                params.rightMargin = offset
                button.layoutParams = params
            }
        }
    }

    private fun restoreRow() {
        hostRef.get()?.let { host ->
            host.removeAllViews()
            (host.parent as? ViewGroup)?.removeView(host)
        }
        rowRef.get()?.let { row ->
            row.tag = originalRowTag
            row.visibility = if (nativeEditHidden) View.GONE else originalRowVisibility
            row.layoutParams = row.layoutParams.apply {
                height = originalHeight
                (this as? ViewGroup.MarginLayoutParams)?.topMargin = originalMarginTop
            }
        }
        touchRef.get()?.visibility = if (nativeEditHidden) View.GONE else originalVisibility
        hostRef.clear(); rowRef.clear(); touchRef.clear()
    }

    private fun invoke(target: Any?, name: String): Any? = runCatching { target?.let {
        generateSequence(it.javaClass) { type -> type.superclass }.firstNotNullOfOrNull { type ->
            type.declaredMethods.firstOrNull { m -> m.name == name && m.parameterCount == 0 }
        }?.apply { isAccessible = true }?.invoke(it)
    } }.getOrNull()

    private fun field(target: Any?, name: String): Any? = runCatching { target?.let {
        generateSequence(it.javaClass) { type -> type.superclass }.firstNotNullOfOrNull { type ->
            runCatching { type.getDeclaredField(name) }.getOrNull()
        }?.apply { isAccessible = true }?.get(it)
    } }.getOrNull()

    companion object {
        private const val BOTTOM_ROW_TAG = "hyperchanger.control-center.bottom-buttons-row"
        fun isBottomButtonsRow(view: View?): Boolean = view?.tag == BOTTOM_ROW_TAG
    }
}
