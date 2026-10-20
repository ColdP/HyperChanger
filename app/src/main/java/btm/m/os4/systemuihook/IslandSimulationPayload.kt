// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import org.json.JSONObject
import org.json.JSONArray

internal const val ISLAND_SIM_ICON = "miui.focus.pic_sim_app"
internal const val ISLAND_SIM_FUNCTION = "miui.focus.pic_sim_function"
internal const val ISLAND_SIM_FUNCTION_DARK = "miui.focus.pic_sim_function_dark"
internal const val ISLAND_SIM_IMAGE = "miui.focus.pic_sim_image"
internal const val ISLAND_SIM_IDENTIFICATION = "miui.focus.pic_sim_identification"
internal const val ISLAND_SIM_SUMMARY = "miui.focus.pic_sim_summary"
internal const val ISLAND_SIM_FORWARD = "miui.focus.pic_sim_forward"
internal const val ISLAND_SIM_MIDDLE = "miui.focus.pic_sim_middle"
internal const val ISLAND_SIM_MIDDLE_PENDING = "miui.focus.pic_sim_middle_pending"
internal const val ISLAND_SIM_END = "miui.focus.pic_sim_end"
internal const val ISLAND_SIM_END_PENDING = "miui.focus.pic_sim_end_pending"
internal const val ISLAND_SIM_CLOSE = "miui.focus.action_sim_close"
internal const val ISLAND_SIM_OPEN = "miui.focus.action_sim_open"
internal const val ISLAND_SIM_COPY = "miui.focus.action_sim_copy"
private const val ACCENT = "#3482FF"

/** Official expanded templates and separate big/small summary data. */
internal fun buildIslandSimulationPayload(
    type: IslandSimulationType,
    settings: IslandSimulationSettings,
    progress: Int,
    terminal: Boolean,
    completedText: String,
    callStartedAt: Long,
    now: Long = System.currentTimeMillis(),
    kilometerText: String = tr("island_sim_km", "公里"),
    codeText: String = tr("island_sim_code", "凭证码"),
    images: Set<IslandSimulationImageSlot> = emptySet(),
    outerGlow: Boolean = false,
): String {
    val percent = progress.coerceIn(0, 100)
    val summary = if (terminal) completedText else settings.summary
    val primary = if (IslandSimulationImageSlot.PRIMARY in images) ISLAND_SIM_IMAGE else ISLAND_SIM_ICON
    val identification = if (IslandSimulationImageSlot.IDENTIFICATION in images) ISLAND_SIM_IDENTIFICATION else ISLAND_SIM_ICON
    val summaryIcon = if (IslandSimulationImageSlot.SUMMARY in images) ISLAND_SIM_SUMMARY else ISLAND_SIM_ICON
    fun pic(key: String = identification, size: Int = 1) = JSONObject()
        .put("type", size).put("pic", key).put("picDark", key)
    fun text(value: String) = JSONObject().put("title", value).put("showHighlightColor", false)
    fun ring() = JSONObject().put("progress", percent)
        .put("colorReach", ACCENT).put("colorUnReach", "#333333").put("isCCW", false)
    fun bar() = JSONObject().put("progress", percent)
        .put("colorProgress", ACCENT).put("colorProgressEnd", ACCENT)
    // V3 text-button holders read JSON directly, including Html.fromHtml(actionTitle).
    // A title in miui.focus.actions alone does not populate this nullable model field.
    fun action(key: String, title: String = settings.action) = JSONObject()
        .put("action", key).put("actionTitle", title)
    fun base(component: Int) = JSONObject().put("type", component)
        .put("title", settings.title).put("content", settings.content)
    fun chat() = JSONObject().put("picProfile", primary).put("picProfileDark", primary)
        .put("title", settings.title).put("content", settings.content)
    fun iconText() = JSONObject().put("title", settings.title).put("content", settings.content)
        .put("animIconInfo", JSONObject().put("type", 0).put("src", primary).put("srcDark", primary))
    fun highlight() = JSONObject().put("primaryText", settings.detail)
        .put("primaryColor", ACCENT).put("primaryColorDark", ACCENT)
        .put("actionInfo", action(ISLAND_SIM_OPEN).put("type", 0))
    fun timer() = JSONObject().put("timerType", 1).put("timerWhen", callStartedAt).put("timerSystemCurrent", now)
    val big = JSONObject().put("imageTextInfoLeft", JSONObject()
        .put("type", 1).put("picInfo", pic(summaryIcon)))
    val small = JSONObject().put("picInfo", pic(summaryIcon))
    val param = JSONObject()
        .put("protocol", 1)
        .put("business", when (type) {
            IslandSimulationType.TEXT -> "weather"
            IslandSimulationType.CALL -> "voip"
            IslandSimulationType.DELIVERY -> "food_delivery"
            IslandSimulationType.PROGRESS -> "file_download"
            IslandSimulationType.MOVIE_LOGISTICS -> if (settings.logistics) "express" else "movie"
            IslandSimulationType.HEALTH -> "sport"
            IslandSimulationType.PHONE -> "phone"
            IslandSimulationType.RESERVATION -> "reservation"
            IslandSimulationType.FILE_SHARE -> "file_share"
            IslandSimulationType.PAYMENT -> "order_payment"
            IslandSimulationType.VERIFICATION -> "verification_code"
        })
        .put("islandFirstFloat", type != IslandSimulationType.PROGRESS)
        .put("enableFloat", false)
        .put("updatable", type == IslandSimulationType.PROGRESS && !terminal)
        .put("reopen", "reopen")
        .put("aodTitle", summary)
        .put("aodPic", summaryIcon)
        .put("ticker", summary)
        .put("tickerPic", summaryIcon)
    if (outerGlow) param.put("outEffectSrc", ISLAND_SIMULATION_OUTER_GLOW_EFFECT)
    when (type) {
        IslandSimulationType.TEXT -> {
            param.put("baseInfo", base(1)).put("picInfo", pic(primary, 3))
            big.put("textInfo", text(summary))
        }
        IslandSimulationType.CALL -> {
            val custom = IslandSimulationImageSlot.IDENTIFICATION in images
            param.put("chatInfo", chat())
                .put("picInfo", pic(if (custom) identification else ISLAND_SIM_FUNCTION, 2)
                    .put("picDark", if (custom) identification else ISLAND_SIM_FUNCTION_DARK)
                    .put("actionInfo", action(ISLAND_SIM_CLOSE)))
            big.getJSONObject("imageTextInfoLeft").put("textInfo", text(summary))
            big.put("sameWidthDigitInfo", JSONObject().put("timerInfo", timer()))
        }
        IslandSimulationType.DELIVERY, IslandSimulationType.PROGRESS -> {
            if (type == IslandSimulationType.DELIVERY) {
                param.put("baseInfo", base(2)).put("picInfo", pic())
                    .put("progressInfo", bar()
                        .put("picForward", ISLAND_SIM_FORWARD)
                        .put("picMiddle", ISLAND_SIM_MIDDLE)
                        .put("picMiddleUnselected", ISLAND_SIM_MIDDLE_PENDING)
                        .put("picEnd", ISLAND_SIM_END)
                        .put("picEndUnselected", ISLAND_SIM_END_PENDING))
            } else {
                param.put("chatInfo", chat().put("content", if (terminal) completedText else settings.content))
                    .put("picInfo", pic()).put("progressInfo", bar())
            }
            big.getJSONObject("imageTextInfoLeft").put("textInfo", text(summary))
            big.put("progressTextInfo", JSONObject().put("progressInfo", ring())
                .put("textInfo", text("$percent%")))
            small.remove("picInfo")
            small.put("combinePicInfo", JSONObject().put("picInfo", pic(summaryIcon)).put("progressInfo", ring()))
        }
        IslandSimulationType.MOVIE_LOGISTICS -> {
            param.put("baseInfo", base(2)).put("picInfo", pic())
                .put("hintInfo", JSONObject().put("type", if (settings.logistics) 1 else 2)
                    .put("title", settings.detail).put("content", codeText)
                    .put("actionInfo", action(ISLAND_SIM_OPEN)))
            big.put("textInfo", text(summary))
        }
        IslandSimulationType.HEALTH -> {
            param.put("highlightInfo", JSONObject().put("type", 0)
                .put("title", settings.title).put("content", kilometerText)
                .put("subContent", settings.content).put("picFunction", ISLAND_SIM_FUNCTION))
                .put("picInfo", pic())
                .put("hintInfo", JSONObject().put("type", 2)
                    .put("title", settings.detail).put("content", settings.content)
                    .put("actionInfo", action(ISLAND_SIM_CLOSE)))
            big.getJSONObject("imageTextInfoLeft").put("textInfo", text(summary))
            big.put("textInfo", text(settings.title).put("content", kilometerText))
        }
        IslandSimulationType.PHONE -> {
            param.put("chatInfo", chat())
                .put("actions", JSONArray().put(action(ISLAND_SIM_CLOSE).put("type", 0)))
            big.getJSONObject("imageTextInfoLeft").put("textInfo", text(summary))
            big.put("sameWidthDigitInfo", JSONObject().put("timerInfo", timer()))
        }
        IslandSimulationType.RESERVATION -> {
            param.put("coverInfo", JSONObject().put("picCover", primary)
                .put("title", settings.title).put("content", settings.content).put("subContent", settings.subContent))
                .put("picInfo", pic()).put("highlightInfoV3", highlight())
            big.put("textInfo", text(summary))
        }
        IslandSimulationType.FILE_SHARE -> {
            param.put("iconTextInfo", iconText()).put("picInfo", pic())
                .put("textButton", JSONArray().put(action(ISLAND_SIM_CLOSE, settings.secondaryAction)).put(action(ISLAND_SIM_OPEN)))
            big.put("textInfo", text(summary))
        }
        IslandSimulationType.PAYMENT -> {
            param.put("iconTextInfo", iconText()).put("picInfo", pic()).put("highlightInfoV3", highlight())
            big.put("textInfo", text(summary))
        }
        IslandSimulationType.VERIFICATION -> {
            param.put("iconTextInfo", iconText())
                .put("actions", JSONArray().put(action(ISLAND_SIM_COPY).put("type", 2)))
            big.put("textInfo", text(summary))
        }
    }
    param.put("param_island", JSONObject().put("islandProperty", 1)
        .put("islandTimeout", 3600).put("bigIslandArea", big).put("smallIslandArea", small)
        .apply { if (outerGlow) put("outEffectSrc", ISLAND_SIMULATION_OUTER_GLOW_EFFECT) })
    return JSONObject().put("param_v2", param).toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= 3072) { "Island payload exceeds 3 KB" }
    }
}
