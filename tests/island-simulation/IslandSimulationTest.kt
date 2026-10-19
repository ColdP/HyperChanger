// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import org.json.JSONObject

private fun payload(type: IslandSimulationType, settings: IslandSimulationSettings, terminal: Boolean = false): JSONObject =
    JSONObject(buildIslandSimulationPayload(type, settings, if (terminal) 100 else 37, terminal,
        "已完成", 1_000_000L, 1_065_000L, "公里", "凭证码")).getJSONObject("param_v2")

fun main() {
    val draft = IslandSimulationSettings("测试任务", "测试内容", "测试", "123456", "查看", secondaryAction = "拒绝")
    for (type in IslandSimulationType.entries) {
        val json = payload(type, draft)
        // Regression for the device crash in ModuleTextButton5ViewHolder.bind:
        // Html.fromHtml reads actionTitle from JSON, without using Notification.Action.title.
        fun checkActionTitles(value: Any) {
            when (value) {
                is JSONObject -> {
                    if (value.has("action")) {
                        val expected = if (type == IslandSimulationType.FILE_SHARE && value.getString("action") == ISLAND_SIM_CLOSE)
                            draft.secondaryAction else draft.action
                        check(value.getString("actionTitle") == expected) { "Missing/wrong JSON button title for $type" }
                    }
                    value.keys().forEach { checkActionTitles(value.get(it)) }
                }
                is org.json.JSONArray -> (0 until value.length()).forEach { checkActionTitles(value.get(it)) }
            }
        }
        checkActionTitles(json)
        check(json.getInt("protocol") == 1)
        check(!json.getBoolean("enableFloat"))
        val island = json.getJSONObject("param_island")
        val big = island.getJSONObject("bigIslandArea")
        check(big.getJSONObject("imageTextInfoLeft").getJSONObject("picInfo").getString("pic") == ISLAND_SIM_ICON)
        val small = island.getJSONObject("smallIslandArea")
        when (type) {
            IslandSimulationType.TEXT -> {
                check(json.getJSONObject("baseInfo").getInt("type") == 1)
                check(json.getJSONObject("picInfo").getInt("type") == 3)
                check(json.getJSONObject("picInfo").getString("pic") == ISLAND_SIM_ICON)
                val imported = JSONObject(buildIslandSimulationPayload(type, draft, 0, false,
                    "已完成", 0L, 0L, "公里", "凭证码", images = setOf(IslandSimulationImageSlot.PRIMARY))).getJSONObject("param_v2")
                check(imported.getJSONObject("picInfo").getString("pic") == ISLAND_SIM_IMAGE)
                check(imported.getJSONObject("picInfo").getString("picDark") == ISLAND_SIM_IMAGE)
                check(imported.getJSONObject("param_island").getJSONObject("smallIslandArea")
                    .getJSONObject("picInfo").getString("pic") == ISLAND_SIM_ICON)
                check(big.getJSONObject("textInfo").getString("title") == draft.summary)
            }
            IslandSimulationType.CALL -> {
                check(json.getJSONObject("chatInfo").getString("picProfile") == ISLAND_SIM_ICON)
                check(json.getJSONObject("picInfo").getInt("type") == 2)
                check(json.getJSONObject("picInfo").getString("pic") == ISLAND_SIM_FUNCTION)
                check(json.getJSONObject("picInfo").getString("picDark") == ISLAND_SIM_FUNCTION_DARK)
                check(json.getJSONObject("picInfo").getJSONObject("actionInfo").getString("action") == ISLAND_SIM_CLOSE)
                val timer = big.getJSONObject("sameWidthDigitInfo").getJSONObject("timerInfo")
                check(timer.getInt("timerType") == 1)
                check(timer.getLong("timerSystemCurrent") - timer.getLong("timerWhen") == 65_000L)
            }
            IslandSimulationType.DELIVERY, IslandSimulationType.PROGRESS -> {
                val bar = json.getJSONObject("progressInfo")
                check(bar.getInt("progress") == 37)
                check(bar.has("colorProgress") && bar.has("colorProgressEnd"))
                val bigRing = big.getJSONObject("progressTextInfo").getJSONObject("progressInfo")
                val smallRing = small.getJSONObject("combinePicInfo").getJSONObject("progressInfo")
                for (ring in listOf(bigRing, smallRing)) {
                    check(ring.getInt("progress") == 37)
                    check(ring.has("colorReach") && ring.has("colorUnReach"))
                }
                if (type == IslandSimulationType.DELIVERY) {
                    check(json.getJSONObject("baseInfo").getInt("type") == 2)
                    check(listOf("picForward", "picMiddle", "picMiddleUnselected", "picEnd", "picEndUnselected").all(bar::has))
                } else {
                    check(json.getJSONObject("chatInfo").getString("title") == draft.title)
                    check(!json.getBoolean("islandFirstFloat") && json.getBoolean("updatable"))
                    val done = payload(type, draft, terminal = true)
                    check(!done.getBoolean("updatable"))
                    check(done.getJSONObject("chatInfo").getString("content") == "已完成")
                    check(done.getJSONObject("param_island").getJSONObject("smallIslandArea")
                        .getJSONObject("combinePicInfo").getJSONObject("progressInfo").getInt("progress") == 100)
                }
            }
            IslandSimulationType.MOVIE_LOGISTICS -> {
                check(json.getJSONObject("hintInfo").getInt("type") == 2)
                check(payload(type, draft.copy(logistics = true)).getJSONObject("hintInfo").getInt("type") == 1)
                check(json.getJSONObject("hintInfo").getJSONObject("actionInfo").getString("action") == ISLAND_SIM_OPEN)
            }
            IslandSimulationType.HEALTH -> {
                check(json.getJSONObject("highlightInfo").getString("picFunction") == ISLAND_SIM_FUNCTION)
                check(json.getJSONObject("hintInfo").getString("title") == draft.detail)
            }
            IslandSimulationType.PHONE -> {
                check(json.has("chatInfo") && !json.has("picInfo"))
                check(json.getJSONArray("actions").length() == 1)
                check(json.getJSONArray("actions").getJSONObject(0).getInt("type") == 0)
                check(json.getJSONArray("actions").getJSONObject(0).getString("action") == ISLAND_SIM_CLOSE)
                check(big.getJSONObject("sameWidthDigitInfo").getJSONObject("timerInfo").getLong("timerWhen") == 1_000_000L)
            }
            IslandSimulationType.RESERVATION -> {
                check(!json.has("iconTextInfo") && !json.has("baseInfo"))
                check(json.getJSONObject("coverInfo").getString("picCover") == ISLAND_SIM_ICON)
                check(json.getJSONObject("coverInfo").getString("subContent") == draft.subContent)
                check(json.getJSONObject("highlightInfoV3").getString("primaryText") == draft.detail)
                check(json.getJSONObject("highlightInfoV3").getJSONObject("actionInfo").getString("action") == ISLAND_SIM_OPEN)
            }
            IslandSimulationType.FILE_SHARE -> {
                check(json.getJSONObject("iconTextInfo").getJSONObject("animIconInfo").getInt("type") == 0)
                check(json.getJSONArray("textButton").length() == 2)
                check(json.getJSONArray("textButton").getJSONObject(0).getString("action") == ISLAND_SIM_CLOSE)
                check(json.getJSONArray("textButton").getJSONObject(1).getString("action") == ISLAND_SIM_OPEN)
                check(!json.has("highlightInfoV3"))
            }
            IslandSimulationType.PAYMENT -> {
                check(json.getJSONObject("iconTextInfo").getJSONObject("animIconInfo").getString("src") == ISLAND_SIM_ICON)
                check(json.getJSONObject("highlightInfoV3").getString("primaryText") == draft.detail)
                check(json.getJSONObject("highlightInfoV3").getJSONObject("actionInfo").getInt("type") == 0)
                check(!json.has("textButton"))
            }
            IslandSimulationType.VERIFICATION -> {
                check(json.has("iconTextInfo") && !json.has("picInfo"))
                check(json.getJSONArray("actions").length() == 1)
                check(json.getJSONArray("actions").getJSONObject(0).getInt("type") == 2)
                check(json.getJSONArray("actions").getJSONObject(0).getString("action") == ISLAND_SIM_COPY)
            }
        }
        // The longest permitted Chinese input must fit SystemUI's UTF-8 payload limit.
        payload(type, draft.copy(title = "测".repeat(40), content = "测".repeat(80), summary = "测".repeat(8),
            detail = "测".repeat(24), action = "测".repeat(12), subContent = "测".repeat(40), secondaryAction = "测".repeat(12)))
        val slots = islandSimulationImageSlots(type).toSet()
        check(slots.size == islandSimulationImageSlots(type).size)
        val custom = JSONObject(buildIslandSimulationPayload(type, draft, 37, false,
            "已完成", 0L, 0L, "公里", "凭证码", slots)).getJSONObject("param_v2")
        check(custom.getString("aodPic") == ISLAND_SIM_SUMMARY)
        val customIsland = custom.getJSONObject("param_island")
        check(customIsland.getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
            .getJSONObject("picInfo").getString("pic") == ISLAND_SIM_SUMMARY)
        if (IslandSimulationImageSlot.PRIMARY in slots) {
            val primaryKey = when (type) {
                IslandSimulationType.TEXT -> custom.getJSONObject("picInfo").getString("pic")
                IslandSimulationType.CALL, IslandSimulationType.PHONE, IslandSimulationType.PROGRESS -> custom.getJSONObject("chatInfo").getString("picProfile")
                IslandSimulationType.RESERVATION -> custom.getJSONObject("coverInfo").getString("picCover")
                else -> custom.getJSONObject("iconTextInfo").getJSONObject("animIconInfo").getString("src")
            }
            check(primaryKey == ISLAND_SIM_IMAGE)
        }
        if (IslandSimulationImageSlot.IDENTIFICATION in slots) {
            check(custom.getJSONObject("picInfo").getString("pic") == ISLAND_SIM_IDENTIFICATION)
            check(custom.getJSONObject("picInfo").getString("picDark") == ISLAND_SIM_IDENTIFICATION)
        }
        for (logistics in listOf(false, true)) {
            val expected = type == IslandSimulationType.DELIVERY || (type == IslandSimulationType.MOVIE_LOGISTICS && logistics)
            check(supportsIslandSimulationRearScreen(type, logistics) == expected)
            check(buildIslandSimulationRearPayload(type, draft.copy(logistics = logistics)) == null)
            val rear = buildIslandSimulationRearPayload(type, draft.copy(logistics = logistics, rearScreen = true))
            check((rear != null) == expected)
            if (rear != null) {
                check(JSONObject(rear).getString("business") == if (type == IslandSimulationType.DELIVERY) "foodDelivery" else "Logistics")
                check(!JSONObject(rear).has("maml_config"))
            }
        }
    }
    check(ISLAND_SIMULATION_DURATIONS == listOf(5, 10, 15, 30, 60, 90, 120, 180))
    for (seconds in ISLAND_SIMULATION_DURATIONS) for (start in listOf(0, 37, 99)) {
        val duration = seconds * 1000L
        check(islandSimulationProgress(start, -1, duration) == start)
        check(islandSimulationProgress(start, 0, duration) == start)
        check(islandSimulationProgress(start, duration - 1, duration) < 100)
        check(islandSimulationProgress(start, duration, duration) == 100)
        check(islandSimulationProgress(start, duration + 50_000, duration) == 100)
        val samples = (0..seconds * 2).map { islandSimulationProgress(start, it * 500L, duration) }
        check(samples.zipWithNext().all { (a, b) -> b >= a })
    }
    println("PASS: 11 expanded/summary templates, JSON button-title crash regression, per-role image/fallback contracts, Call Kit theme images, native rear-screen eligibility, UTF-8 limit, timers, completion, all countdowns")
}
