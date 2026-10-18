package btm.m.liquidglass.hook

/** Semantic signals only: enum class names, resource IDs and tab positions may change. */
internal object WeiboTabFeatures {
    enum class Kind { HOME, VIDEO, SUPERGROUP, FIND, FEATURED, SHORT_DRAMA, DISCOVER, MESSAGES, PROFILE, OTHER }

    data class Tab(val kind: Kind, val identity: String, val hostLabel: String?)

    private val whitespace = Regex("[\\s_\\-]")
    private val nonCaption = Regex("[/:\\n\\r]")
    private val unreadCount = Regex("\\d+\\s*[条件]")
    private val separators = Regex("[,_\\-\\s，、:：]+")

    fun resolve(tags: List<String>, labels: List<String>, resources: List<String>): Tab? {
        val caption = labels.firstOrNull(::isCaption)?.trim()
        for (signal in tags + labels + resources) {
            val kind = kind(signal) ?: continue
            return Tab(kind, kind.name, caption)
        }
        // Keep a new server-provided tab if it has an actual caption. Never invent its
        // position or forward a click to a different, previously known tab.
        return caption?.let { Tab(Kind.OTHER, "host:${normalize(it)}", it) }
    }

    private fun isCaption(value: String): Boolean {
        val text = value.trim()
        return text.isNotEmpty() && text.length <= 24 && text.any(Char::isLetter) &&
            !text.contains(nonCaption) && !text.contains(unreadCount)
    }

    private fun normalize(value: String): String = value.lowercase(java.util.Locale.ROOT)
        .replace(whitespace, "")

    private fun kind(value: String): Kind? {
        // Resource tokens must be whole words, so 'homepage_banner' cannot become a
        // Home tab merely because it contains 'home'. Accessibility state is optional.
        val token = value.trim()
        val candidates = listOf(token) + token.split(separators)
        return candidates.firstNotNullOfOrNull { part ->
            when (normalize(part)) {
                "home", "首页", "首頁", "主页", "主頁", "ホーム" -> Kind.HOME
                "video", "videos", "视频", "視頻", "動画", "ビデオ" -> Kind.VIDEO
                "supergroup", "超话", "超話" -> Kind.SUPERGROUP
                "find", "找人", "ユーザー検索" -> Kind.FIND
                "featured", "精选", "精選", "おすすめ" -> Kind.FEATURED
                "playlet", "shortdrama", "短剧", "短劇", "ショートドラマ" -> Kind.SHORT_DRAMA
                "discover", "discovery", "发现", "發現", "発見" -> Kind.DISCOVER
                "message", "messages", "消息", "訊息", "メッセージ" -> Kind.MESSAGES
                "me", "mine", "profile", "我", "我的", "マイページ", "プロフィール" -> Kind.PROFILE
                else -> null
            }
        }
    }

    fun isNavigation(tabs: List<Tab>): Boolean =
        tabs.map { it.identity }.distinct().size == tabs.size &&
            tabs.map { it.kind }.containsAll(listOf(Kind.HOME, Kind.MESSAGES, Kind.PROFILE))
}
