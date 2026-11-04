package btm.m.liquidglass.hook

import java.util.Locale

/** No resource integers, obfuscated classes or numeric tab routes are part of the contract. */
internal object XiaohongshuTabFeatures {
    enum class Kind { HOME, MARKET, PUBLISH, MESSAGES, PROFILE, VIDEO, OTHER }
    data class Tab(val kind: Kind, val identity: String, val caption: String?)

    fun resolve(signals: List<String>, captions: List<String>): Tab? {
        val caption = captions.firstOrNull(::isCaption)?.trim()
        for (signal in signals + captions) {
            val parts = listOf(signal.trim()) + signal.split(Regex("[,_\\s，、:：]+"))
            val kind = parts.firstNotNullOfOrNull { part ->
                when (part.lowercase(Locale.ROOT)) {
                    "home", "首页", "首頁", "ホーム" -> Kind.HOME
                    "shop", "store", "market", "mall", "市集", "购物", "購物", "商城", "逛逛", "ショップ" -> Kind.MARKET
                    "release", "publish", "post", "create", "+", "发布", "發佈", "發表", "投稿" -> Kind.PUBLISH
                    "message", "messages", "消息", "訊息", "メッセージ" -> Kind.MESSAGES
                    "me", "mine", "profile", "我", "我的", "マイページ", "プロフィール" -> Kind.PROFILE
                    "video", "videos", "视频", "視頻", "動画" -> Kind.VIDEO
                    else -> null
                }
            }
            if (kind != null) return Tab(kind, kind.name, caption)
        }
        return caption?.let { Tab(Kind.OTHER, "host:${it.lowercase(Locale.ROOT)}", it) }
    }

    private fun isCaption(value: String): Boolean = value.trim().let {
        it.isNotEmpty() && it.length <= 24 && it.any(Char::isLetter) &&
            it.none { c -> c in "/:\n\r" } && !it.matches(Regex(".*\\d+\\s*[条條件].*"))
    }

    fun isNavigation(tabs: List<Tab>): Boolean = tabs.size in 4..7 &&
        tabs.map { it.identity }.distinct().size == tabs.size &&
        tabs.map { it.kind }.containsAll(listOf(Kind.HOME, Kind.PUBLISH, Kind.MESSAGES, Kind.PROFILE))
}
