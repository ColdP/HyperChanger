package btm.m.liquidglass.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

// These enum types deliberately have no relationship to Weibo's obfuscated classes.
private enum class RenamedTab { Home, Video, Discover, Message, Me }
private enum class ObfuscatedTab { a, b, c, d }

private fun content() = ViewGroup().apply { height = 800; y = 0 }

private fun bar(captions: List<String>, tags: List<Any?> = List(captions.size) { null }): ViewGroup =
    ViewGroup().apply {
        captions.forEachIndexed { index, caption ->
            val cellWidth = width / captions.size
            addView(ViewGroup().apply {
                width = cellWidth; x = index * cellWidth
                addView(ViewGroup().apply {
                    width = cellWidth; x = index * cellWidth
                    isClickable = true; tag = tags[index]
                    addView(TextView().apply { text = caption; width = cellWidth; x = index * cellWidth })
                })
            })
        }
    }

fun main() {
    val root = content()
    val four = bar(listOf("首页", "发现", "消息", "我"), ObfuscatedTab.entries)
    root.addView(four)
    check(WeiboBottomBarDetector.detect(root, null)?.tabs?.size == 4) {
        "Renamed resources and obfuscated enum names must fall back to visible captions"
    }

    val five = bar(List(5) { "" }, RenamedTab.entries)
    root.removeView(four); root.addView(five)
    val detectedFive = checkNotNull(WeiboBottomBarDetector.detect(root, four))
    check(detectedFive.container === five && detectedFive.tabs.size == 5)
    check(detectedFive.tabs[1].feature.kind == WeiboTabFeatures.Kind.VIDEO)
    check(detectedFive.tabs[1].clickTarget === (five.getChildAt(1) as ViewGroup).getChildAt(0))

    val six = bar(listOf("Home", "Live", "Videos", "Discover", "Messages", "Profile"))
    root.removeView(five); root.addView(six)
    val detectedSix = checkNotNull(WeiboBottomBarDetector.detect(root, five))
    check(detectedSix.tabs.size == 6)
    check(detectedSix.tabs[1].feature.kind == WeiboTabFeatures.Kind.OTHER)
    check(detectedSix.tabs[1].feature.hostLabel == "Live")
    val messageIdentity = detectedSix.tabs[4].feature.identity

    val reordered = bar(listOf("Profile", "Messages", "Home", "Live"))
    root.removeView(six); root.addView(reordered)
    val newTabs = checkNotNull(WeiboBottomBarDetector.detect(root, six)).tabs
    check(newTabs.indexOfFirst { it.feature.identity == messageIdentity } == 1)
    check(newTabs.none { it.clickTarget === detectedSix.tabs[4].clickTarget }) {
        "A rebuilt bar must bind the new native target"
    }

    // Hidden video cells must not force a phantom fifth tab.
    val hidden = bar(listOf("首页", "视频", "发现", "消息", "我的"))
    hidden.getChildAt(1).visibility = View.GONE
    root.removeView(reordered); root.addView(hidden)
    check(WeiboBottomBarDetector.detect(root, null)?.tabs?.size == 4)

    root.removeView(hidden)
    val japanese = bar(listOf("ホーム", "メッセージ", "プロフィール"))
    root.addView(japanese)
    check(WeiboBottomBarDetector.detect(root, null)?.tabs?.size == 3)

    root.removeView(japanese)
    val accessible = bar(List(4) { "" })
    listOf("首页，已选中", "发现", "消息", "我").forEachIndexed { index, text ->
        (accessible.getChildAt(index) as ViewGroup).getChildAt(0).contentDescription = text
    }
    root.addView(accessible)
    check(WeiboBottomBarDetector.detect(root, null)?.tabs?.size == 4)

    // Never hide a row after recognizing only some of its interactive cells.
    root.removeView(accessible)
    val partial = bar(listOf("首页", "", "消息", "我的"))
    root.addView(partial)
    check(WeiboBottomBarDetector.detect(root, null) == null)

    root.removeView(partial)
    val duplicate = bar(listOf("首页", "首页", "消息", "我的"))
    root.addView(duplicate)
    check(WeiboBottomBarDetector.detect(root, null) == null)

    root.removeView(duplicate)
    val topTabs = bar(listOf("首页", "发现", "消息", "我的")).apply { y = 0 }
    root.addView(topTabs)
    check(WeiboBottomBarDetector.detect(root, null) == null)

    root.removeView(topTabs)
    val vertical = bar(listOf("首页", "发现", "消息", "我的"))
    vertical.getChildAt(1).y -= 20
    root.addView(vertical)
    check(WeiboBottomBarDetector.detect(root, null) == null)

    root.removeView(vertical)
    val overlap = bar(listOf("首页", "发现", "消息", "我的"))
    overlap.getChildAt(1).x = 50
    root.addView(overlap)
    check(WeiboBottomBarDetector.detect(root, null) == null)

    root.removeView(overlap)
    val pageActions = bar(listOf("首页", "视频", "分享", "收藏"))
    root.addView(pageActions)
    check(WeiboBottomBarDetector.detect(root, null) == null)
    check(WeiboBottomBarDetector.detect(root, null, searchHierarchy = false) == null)

    val ambiguousResource = WeiboTabFeatures.resolve(emptyList(), emptyList(), listOf("homepage_banner"))
    check(ambiguousResource == null)
    check(WeiboTabFeatures.resolve(emptyList(), listOf("99+"), emptyList()) == null)
    check(WeiboTabFeatures.resolve(emptyList(), listOf("消息，未读3条"), emptyList())?.kind ==
        WeiboTabFeatures.Kind.MESSAGES)
    check(WeiboTabFeatures.resolve(emptyList(), emptyList(), listOf("tab_message_icon"))?.kind ==
        WeiboTabFeatures.Kind.MESSAGES)
    println("Weibo navigation regression checks passed (synthetic view trees).")
}
