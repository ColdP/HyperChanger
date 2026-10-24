package btm.m.liquidglass.hook

import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

private fun item(label: String): ViewGroup = ViewGroup().apply {
    listener = true
    addView(ImageView().apply { id = 4 })
    addView(TextView().apply { id = 5; text = label })
}

fun main() {
    val root = ViewGroup()
    val host = ViewGroup().also(root::addView)
    val page = ViewGroup().apply { id = 2 }.also(host::addView)
    val background = ImageView().apply { id = 3 }.also(host::addView)
    val row = ViewGroup().apply { id = 1 }.also(host::addView)
    val items = listOf("首页", "财富", "生活", "借钱", "我的").map(::item)
    items.forEach(row::addView)
    check(CmbBankNavigation.detect(root) == null) { "Never guess a selected tab" }
    items[0].isSelected = true
    val detected = checkNotNull(CmbBankNavigation.detect(root))
    check(detected.view === row && detected.page === page && detected.background === background)
    check(detected.selectedIndex == 0 && detected.items.map { it.label } == listOf("首页", "财富", "生活", "借钱", "我的"))
    detected.items[2].clickTarget.performClick()
    check(items[2].clicks == 1) { "Forward a native click exactly once" }
    row.alpha = 0f
    check(CmbBankNavigation.detect(root) != null) { "The module's transparent row remains detectable" }
    items[0].isSelected = false
    (items[3].getChildAt(0) as ImageView).drawable!!.state = intArrayOf(android.R.attr.state_selected)
    check(CmbBankNavigation.detect(root)?.selectedIndex == 3) { "Read the host drawable selection" }
    items[1].isSelected = true
    check(CmbBankNavigation.detect(root) == null) { "Ambiguous selection preserves native navigation" }
    items[1].isSelected = false
    row.visibility = 8
    check(CmbBankNavigation.detect(root) == null)
    row.visibility = 0
    items[4].getChildAt(0).visibility = 8
    check(CmbBankNavigation.detect(root) == null) { "Avatar layouts require their own icon contract" }
    items[4].getChildAt(0).visibility = 0
    host.removeView(background)
    check(CmbBankNavigation.detect(root) == null) { "A similarly named row without its siblings is unsafe" }
    host.addView(background)
    items[1].listener = false
    val inner = ViewGroup().apply { listener = true }.also(items[1]::addView)
    check(CmbBankNavigation.detect(root)?.items?.get(1)?.clickTarget === inner)
    items[1].removeView(inner)
    check(CmbBankNavigation.detect(root) == null) { "Do not replace tabs without native click targets" }
    items[1].listener = true
    row.removeView(items[4])
    check(CmbBankNavigation.detect(root)?.items?.size == 4) { "Accept host-provided tab count and order" }
    host.removeView(row)
    check(CmbBankNavigation.detect(root) == null) { "Never keep a detached native bar" }
    println("CMB Bank navigation detector regression checks passed")
}
