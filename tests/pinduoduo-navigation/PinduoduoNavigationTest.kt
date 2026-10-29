package btm.m.liquidglass.hook

import android.app.Activity
import android.os.SystemClock
import android.view.Root
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView
import com.xunmeng.pinduoduo.ui_home_activity.widget.tab.TabRelativeLayout

private fun item(label: String) = TabRelativeLayout().apply {
    isClickable = true
    contentDescription = label + "99+"
    addView(TextView().apply { text = label })
    addView(ImageView())
    addView(TextView().apply { text = "99+" })
}

private fun bar(labels: List<String>) = PddTabView().apply { labels.forEach { addView(item(it)) } }
private fun nextSearch() { SystemClock.now += 301 }

fun main() {
    val activity = Activity()
    val detector = PinduoduoNavigation.Detector(activity)
    val root = Root()
    val products = ViewGroup().apply {
        // The old detector accepted any three images, even with no tab keywords.
        listOf("新款毛衣女秋冬加厚", "首页推荐超值套装", "我的分类收纳架").forEach { label ->
            addView(ViewGroup().apply {
                isClickable = true
                addView(TextView().apply { text = label })
                addView(ImageView())
            })
        }
    }
    root.addView(products)
    check(detector.detect(root) == null) { "Product images and navigation keywords are not a tab row" }
    val reads = products.reads
    repeat(50) { check(detector.detect(root) == null) }
    check(products.reads == reads) { "Missing navigation must not cause per-frame product scans" }

    nextSearch()
    val original = bar(listOf("首页", "直播", "聊天", "个人中心"))
    root.addView(original)
    val first = checkNotNull(detector.detect(root))
    check(first.container === original && first.items.size == 4)
    check(first.items[2].label == "聊天") { "Unread counts must not enter tab captions" }
    check(first.items[2].clickTarget === original.getChildAt(2))
    val opened = activity.assets.opened.size
    val productReads = products.reads
    repeat(120) {
        val stable = checkNotNull(detector.detect(root))
        check(stable.items == first.items) { "Stable tabs must retain bitmap identity" }
    }
    check(activity.assets.opened.size == opened) { "Assets must not decode every pre-draw" }
    check(products.reads == productReads) { "Cached row detection must not traverse products" }

    original.height = 0
    original.alpha = 0f
    check(detector.detect(root)?.container === original) { "Module-collapsed navigation stays bound" }
    (original.getChildAt(2) as ViewGroup).getChildAt(0).isSelected = true
    check(detector.detect(root)?.selectedIndex == 2) { "Selection is read from the native title" }

    original.visibility = View.GONE
    nextSearch()
    check(detector.detect(root) == null) { "A hidden main bar must not fall back to products" }
    original.visibility = View.VISIBLE
    check(detector.detect(root)?.container === original) { "Native visibility must recover immediately" }

    // A partially rebuilt native row must not replace only some cells.
    val third = original.getChildAt(2)
    third.visibility = View.GONE
    check(detector.detect(root) == null)
    third.visibility = View.VISIBLE
    check(detector.detect(root)?.items?.size == 4)
    val title = (third as ViewGroup).getChildAt(0) as TextView
    title.text = ""
    third.contentDescription = ""
    check(detector.detect(root) == null) { "An unlabelled native cell must not become a phantom Tab" }
    title.text = "聊天"
    root.removeView(original)

    nextSearch()
    val malformed = PddTabView().apply {
        addView(item("首页")); addView(item("消息")); addView(products)
    }
    root.addView(malformed)
    check(detector.detect(root) == null) { "Even exact containers require native direct cell types" }
    root.removeView(malformed)

    for (count in 3..8) {
        nextSearch()
        val replacement = bar(List(count) { "频道${it + 1}" })
        root.addView(replacement)
        val dynamic = checkNotNull(detector.detect(root))
        check(dynamic.items.map { it.label } == List(count) { "频道${it + 1}" })
        check(dynamic.items.all { it.icon != null })
        val again = checkNotNull(detector.detect(root))
        check(again.items == dynamic.items) { "Unknown tab icons must reuse drawable snapshots" }
        check((replacement.getChildAt(0) as ViewGroup).getChildAt(1).let {
            (it as ImageView).drawable!!.draws == 1
        })
        root.removeView(replacement)
    }

    nextSearch()
    val redirected = bar(listOf("频道A", "频道B", "频道C"))
    root.addView(redirected)
    val redirectedItem = redirected.getChildAt(1) as ViewGroup
    redirectedItem.isClickable = false
    val clickConsumer = View().apply { isClickable = true }
    redirectedItem.addView(clickConsumer)
    check(detector.detect(root)?.items?.get(1)?.clickTarget === clickConsumer)
    check(detector.detect(Root()) == null) { "A cached row cannot leak into a different root" }

    detector.clear()
    activity.assets.missing = true
    val missingRoot = Root().apply { addView(bar(listOf("首页", "聊天", "个人中心"))) }
    val missing = checkNotNull(detector.detect(missingRoot))
    val attempts = activity.assets.opened.size
    check(missing.items.all { it.icon != null })
    repeat(20) { check(detector.detect(missingRoot)?.items == missing.items) }
    check(activity.assets.opened.size == attempts) { "Missing assets must not be retried per frame" }
    println("Pinduoduo navigation regression checks passed (synthetic view trees).")
}
