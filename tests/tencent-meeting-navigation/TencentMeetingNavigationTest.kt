package btm.m.liquidglass.hook

import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.tencent.wemeet.sdk.meeting.premeeting.home.view.*

private fun item(title: String, floating: Boolean = false): ViewGroup =
    (if(floating) HomeTabItemViewV2() else HomeTabItemView()).apply {
        listener = true
        addView(TextView().apply { id = 1; text = title })
        addView(ImageView().apply { id = 2 })
    }

fun main() {
    val detector = TencentMeetingNavigation()
    val root = ViewGroup()
    val home = HomeTabView()
    root.addView(home)
    val pager = Pager().apply { id = 4 }
    home.addView(pager)
    check(detector.detect(root) == null) { "Full-screen HomeTabView must never be a detected bar" }
    val regular = HomeTabContainer()
    home.addView(regular)
    listOf("会议", "通讯录", "我的").forEach { regular.addView(item(it)) }
    pager.current = 2
    val three = checkNotNull(detector.detect(root))
    check(three.view === regular && three.items.map { it.label } == listOf("会议", "通讯录", "我的"))
    check(three.selectedIndex == 2 && detector.collapsedHeight(regular) == 0)
    val icon = (three.items[0].view as ViewGroup).getChildAt(1) as ImageView
    check(icon.drawable!!.bounds.right == 10) { "Capturing an icon must restore native drawable bounds" }
    check(detector.detect(root)!!.items[0].icon === three.items[0].icon)
    check(icon.drawable!!.draws == 1) { "Do not rasterize unchanged icons on every frame" }
    three.items[1].view.performClick()
    check(three.items[1].view.clicks == 1) { "Clicks must use the actual native item once" }
    regular.visibility = 8
    check(detector.detect(root) == null) { "Host-hidden navigation must remain hidden" }
    val floating = FloatingTabContainer()
    home.addView(floating)
    val row = ViewGroup().apply { id = 3 }
    floating.addView(row)
    listOf("Meetings", "Contacts", "Messages", "Record", "Me").forEach { row.addView(item(it, true)) }
    val search = ImageView().apply { id = 5; listener = true }
    floating.addView(search)
    pager.current = 3
    val five = checkNotNull(detector.detect(root))
    check(five.view === floating && five.items.size == 5 && five.selectedIndex == 3)
    check(five.trailingAction?.view === search)
    check(detector.collapsedHeight(floating) == 40) { "Floating height report must subtract shadow to reach zero" }
    search.visibility = 8
    check(detector.detect(root)!!.items.size == 5)
    search.visibility = 0
    val withSearch = checkNotNull(detector.detect(root))
    check(withSearch.items.size == 5) { "Search must remain outside the tab row" }
    check(withSearch.trailingAction?.view === search) { "Search must be exposed as a separate trailing action" }
    search.performClick()
    check(search.clicks == 1) { "Search must invoke its native click target once" }
    row.addView(ViewGroup())
    check(detector.detect(root) == null) { "Unknown row child must fail closed, leaving native navigation usable" }
    println("Tencent Meeting detector regression checks passed")
}
