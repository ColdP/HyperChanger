// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context

internal enum class IslandSimulationType(val key: String, val labelKey: String, val label: String) {
    TEXT("text", "island_sim_text", "文本超级岛"),
    CALL("call", "island_sim_call", "Call Kit 超级岛"),
    DELIVERY("delivery", "island_sim_delivery", "外卖超级岛"),
    PROGRESS("progress", "island_sim_progress", "进度超级岛"),
    MOVIE_LOGISTICS("movie_logistics", "island_sim_movie_logistics", "电影&物流超级岛"),
    HEALTH("health", "island_sim_health", "运动健康超级岛"),
    PHONE("phone", "island_sim_phone", "通话超级岛"),
    RESERVATION("reservation", "island_sim_reservation", "预约超级岛"),
    FILE_SHARE("file_share", "island_sim_file_share", "文件分享超级岛"),
    PAYMENT("payment", "island_sim_payment", "订单待支付超级岛"),
    VERIFICATION("verification", "island_sim_verification", "验证码超级岛");

    fun title(): String = tr(labelKey, label)
}

internal val ISLAND_SIMULATION_DURATIONS = listOf(5, 10, 15, 30, 60, 90, 120, 180)

internal data class IslandSimulationSettings(
    val title: String,
    val content: String,
    val summary: String,
    val detail: String = "",
    val action: String = "",
    val progress: Int = 0,
    val durationSeconds: Int = 15,
    val callSeconds: Int = 65,
    val logistics: Boolean = false,
    val subContent: String = "",
    val secondaryAction: String = "",
    val rearScreen: Boolean = false,
)

/** App-only simulation drafts. No hook process consumes these preferences, so no remote mirror is needed. */
internal class IslandSimulationSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("island_simulation", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("notification_simulator", Context.MODE_PRIVATE)

    fun selectedType(): IslandSimulationType = IslandSimulationType.entries.firstOrNull {
        it.key == prefs.getString("selected_type", null)
    } ?: IslandSimulationType.TEXT

    fun select(type: IslandSimulationType) {
        prefs.edit().putString("selected_type", type.key).apply()
    }

    fun load(type: IslandSimulationType): IslandSimulationSettings {
        val defaults = defaults(type).let { builtin ->
            if (type != IslandSimulationType.TEXT) builtin else builtin.copy(
                title = legacy.getString("focus_title", null)?.takeUnless { it == "焦点通知测试" } ?: builtin.title,
                content = legacy.getString("focus_content", null)
                    ?.takeUnless { it == "这是一条用于测试小米超级岛的焦点通知" } ?: builtin.content,
            )
        }
        fun text(key: String, default: String): String = prefs.getString("${type.key}_$key", default) ?: default
        return defaults.copy(
            title = text("title", defaults.title),
            content = text("content", defaults.content),
            summary = text("summary", defaults.summary),
            detail = text("detail", defaults.detail),
            action = text("action", defaults.action),
            subContent = text("sub_content", defaults.subContent),
            secondaryAction = text("secondary_action", defaults.secondaryAction),
            rearScreen = prefs.getBoolean("${type.key}_rear_screen", false),
            progress = prefs.getInt("${type.key}_progress", defaults.progress).coerceIn(0, 99),
            durationSeconds = prefs.getInt("${type.key}_duration", 15).takeIf { it in ISLAND_SIMULATION_DURATIONS } ?: 15,
            callSeconds = prefs.getInt("${type.key}_call_seconds", 65).coerceIn(0, 3599),
            logistics = prefs.getBoolean("${type.key}_logistics", false),
        )
    }

    fun save(type: IslandSimulationType, value: IslandSimulationSettings) {
        prefs.edit()
            .putString("${type.key}_title", value.title)
            .putString("${type.key}_content", value.content)
            .putString("${type.key}_summary", value.summary)
            .putString("${type.key}_detail", value.detail)
            .putString("${type.key}_action", value.action)
            .putString("${type.key}_sub_content", value.subContent)
            .putString("${type.key}_secondary_action", value.secondaryAction)
            .putBoolean("${type.key}_rear_screen", value.rearScreen)
            .putInt("${type.key}_progress", value.progress.coerceIn(0, 99))
            .putInt("${type.key}_duration", value.durationSeconds.takeIf { it in ISLAND_SIMULATION_DURATIONS } ?: 15)
            .putInt("${type.key}_call_seconds", value.callSeconds.coerceIn(0, 3599))
            .putBoolean("${type.key}_logistics", value.logistics)
            .apply()
    }

    fun defaults(type: IslandSimulationType): IslandSimulationSettings = when (type) {
        IslandSimulationType.TEXT -> IslandSimulationSettings(
            tr("island_sim_default_title", "超级岛测试"),
            tr("island_sim_default_content", "这是一条用于测试超级岛的消息"),
            tr("island_sim_default_summary", "新消息"),
        )
        IslandSimulationType.CALL -> IslandSimulationSettings(
            tr("island_sim_default_caller", "小米同学"),
            tr("island_sim_default_call_state", "语音通话中"),
            tr("island_sim_default_call_summary", "通话"),
            action = tr("island_sim_hang_up", "挂断"),
        )
        IslandSimulationType.DELIVERY -> IslandSimulationSettings(
            tr("island_sim_default_delivery_title", "骑手正在配送"),
            tr("island_sim_default_delivery_content", "预计 10 分钟后送达"),
            tr("island_sim_default_delivery_summary", "配送中"),
        ).copy(progress = 50)
        IslandSimulationType.PROGRESS -> IslandSimulationSettings(
            tr("island_sim_default_progress_title", "文件下载"),
            "HyperChanger-test.apk",
            tr("island_sim_default_progress_summary", "下载中"),
        )
        IslandSimulationType.MOVIE_LOGISTICS -> IslandSimulationSettings(
            tr("island_sim_default_movie_title", "电影即将开场"),
            tr("island_sim_default_movie_content", "1 号厅 · 8 排 6 座"),
            tr("island_sim_default_movie_summary", "待入场"),
            detail = "123456",
            action = tr("island_sim_view_details", "查看详情"),
        )
        IslandSimulationType.HEALTH -> IslandSimulationSettings(
            "3.25",
            tr("island_sim_default_health_content", "户外跑步"),
            tr("island_sim_default_health_summary", "跑步中"),
            detail = "00:18:36",
            action = tr("island_sim_finish", "结束"),
        )
        IslandSimulationType.PHONE -> IslandSimulationSettings(
            tr("island_sim_default_caller", "小米同学"),
            tr("island_sim_default_call_state", "语音通话中"),
            tr("island_sim_default_call_summary", "通话"),
            action = tr("island_sim_hang_up", "挂断"),
        )
        IslandSimulationType.RESERVATION -> IslandSimulationSettings(
            tr("island_sim_default_reservation_title", "音乐会预约"),
            tr("island_sim_default_reservation_time", "明天 19:30"),
            tr("island_sim_default_reservation_summary", "待预约"),
            detail = tr("island_sim_default_reservation_highlight", "即将开售"),
            action = tr("island_sim_reserve", "立即预约"),
            subContent = tr("island_sim_default_reservation_location", "城市音乐厅"),
        )
        IslandSimulationType.FILE_SHARE -> IslandSimulationSettings(
            "HyperChanger-test.apk",
            tr("island_sim_default_share_content", "来自小米同学 · 20 MB"),
            tr("island_sim_default_share_summary", "待接收"),
            action = tr("island_sim_accept", "接收"),
            secondaryAction = tr("island_sim_decline", "拒绝"),
        )
        IslandSimulationType.PAYMENT -> IslandSimulationSettings(
            tr("island_sim_default_payment_title", "订单待支付"),
            tr("island_sim_default_payment_content", "请在订单关闭前完成支付"),
            tr("island_sim_default_payment_summary", "待支付"),
            detail = tr("island_sim_default_payment_amount", "¥ 29.90"),
            action = tr("island_sim_pay", "去支付"),
        )
        IslandSimulationType.VERIFICATION -> IslandSimulationSettings(
            "123456",
            tr("island_sim_default_verification_content", "HyperChanger 登录验证码"),
            tr("island_sim_default_verification_summary", "验证码"),
            action = tr("island_sim_copy_code", "复制验证码"),
        )
    }
}
