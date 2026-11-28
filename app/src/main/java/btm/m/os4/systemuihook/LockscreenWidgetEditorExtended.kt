// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.os4.systemuihook.SeamlessCard as Card
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal enum class LockscreenWidgetCategory {
    ALL,
    WEATHER,
    DEVICE,
    TIME,
    HEALTH,
    CONTROLS,
}

internal fun LockscreenWidgetCategory.label(): String = when (this) {
    LockscreenWidgetCategory.ALL -> tr("lockscreenWidgetCategoryAll", "全部")
    LockscreenWidgetCategory.WEATHER -> tr("lockscreenWidgetCategoryWeather", "天气")
    LockscreenWidgetCategory.DEVICE -> tr("lockscreenWidgetCategoryDevice", "设备")
    LockscreenWidgetCategory.TIME -> tr("lockscreenWidgetCategoryTime", "时间与日程")
    LockscreenWidgetCategory.HEALTH -> tr("lockscreenWidgetCategoryHealth", "健康")
    LockscreenWidgetCategory.CONTROLS -> tr("lockscreenWidgetCategoryControls", "快捷与个性")
}

internal data class LockscreenWidgetPresetSpec(
    val id: String,
    val title: String,
    val widgetIds: List<String>,
)

internal fun lockscreenWidgetQuickPresets(): List<LockscreenWidgetPresetSpec> = listOf(
    LockscreenWidgetPresetSpec(
        id = "classic",
        title = tr("lockscreenWidgetPresetClassic", "经典双栏"),
        widgetIds = listOf(
            LockscreenWidgetIds.DETAIL_WEATHER,
            LockscreenWidgetIds.DETAIL_BATTERY,
        ),
    ),
    LockscreenWidgetPresetSpec(
        id = "ios_rings",
        title = tr("lockscreenWidgetPresetIosRings", "iOS 实用四环"),
        widgetIds = listOf(
            LockscreenWidgetIds.BATTERY_RING,
            LockscreenWidgetIds.UV_INDEX,
            LockscreenWidgetIds.CALENDAR_DATE,
            LockscreenWidgetIds.WORLD_CLOCK,
        ),
    ),
    LockscreenWidgetPresetSpec(
        id = "oneui_health",
        title = tr("lockscreenWidgetPresetOneUiHealth", "OneUI 健康日程"),
        widgetIds = listOf(
            LockscreenWidgetIds.ACTIVITY_RINGS,
            LockscreenWidgetIds.SCHEDULE,
        ),
    ),
    LockscreenWidgetPresetSpec(
        id = "weather_pro",
        title = tr("lockscreenWidgetPresetWeatherPro", "气象仪表盘"),
        widgetIds = listOf(
            LockscreenWidgetIds.COMPACT_WEATHER,
            LockscreenWidgetIds.RAIN_PROB,
            LockscreenWidgetIds.UV_INDEX,
        ),
    ),
    LockscreenWidgetPresetSpec(
        id = "quick_controls",
        title = tr("lockscreenWidgetPresetQuickControls", "设备与快捷"),
        widgetIds = listOf(
            LockscreenWidgetIds.BLUETOOTH_BATTERY,
            LockscreenWidgetIds.SOUND_MODE,
            LockscreenWidgetIds.FLASHLIGHT,
        ),
    ),
)

internal fun lockscreenWidgetCategoryOf(id: String): LockscreenWidgetCategory = when (id) {
    LockscreenWidgetIds.DETAIL_WEATHER,
    LockscreenWidgetIds.DETAIL_WEATHER_BG,
    LockscreenWidgetIds.COMPACT_WEATHER,
    LockscreenWidgetIds.SUN,
    LockscreenWidgetIds.HUMIDITY,
    LockscreenWidgetIds.AQI,
    LockscreenWidgetIds.FEELS_LIKE,
    LockscreenWidgetIds.WIND,
    LockscreenWidgetIds.UV_INDEX,
    LockscreenWidgetIds.RAIN_PROB,
    LockscreenWidgetIds.PRESSURE,
    -> LockscreenWidgetCategory.WEATHER

    LockscreenWidgetIds.DETAIL_BATTERY,
    LockscreenWidgetIds.DETAIL_BATTERY_BG,
    LockscreenWidgetIds.BATTERY_RING,
    LockscreenWidgetIds.BATTERY_TEMP,
    LockscreenWidgetIds.BLUETOOTH_BATTERY,
    -> LockscreenWidgetCategory.DEVICE

    LockscreenWidgetIds.ALARM,
    LockscreenWidgetIds.SCHEDULE,
    LockscreenWidgetIds.WORLD_CLOCK,
    LockscreenWidgetIds.CALENDAR_DATE,
    LockscreenWidgetIds.COUNTDOWN,
    -> LockscreenWidgetCategory.TIME

    LockscreenWidgetIds.STEPS,
    LockscreenWidgetIds.STEPS_WIDE,
    LockscreenWidgetIds.STAND,
    LockscreenWidgetIds.SLEEP,
    LockscreenWidgetIds.ACTIVITY_RINGS,
    -> LockscreenWidgetCategory.HEALTH

    else -> LockscreenWidgetCategory.CONTROLS
}

@Composable
internal fun LockscreenWidgetSlotAndPresetCard(
    usedSlots: Int,
    selectedIds: List<String>,
    onApplyPreset: (List<String>) -> Unit,
) {
    val presets = lockscreenWidgetQuickPresets()
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.5.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("lockscreenWidgetSlotUsage", "已用 %d / 4 格").format(usedSlots),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = tr("lockscreenWidgetReorderHint", "拖动可排序 · 宽组件占 2 格"),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    val active = selectedIds == preset.widgetIds
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (active) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.secondaryContainer
                                },
                            )
                            .clickable { onApplyPreset(preset.widgetIds) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = preset.title,
                            style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium,
                            color = if (active) {
                                MiuixTheme.colorScheme.onPrimary
                            } else {
                                MiuixTheme.colorScheme.onSecondaryContainer
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LockscreenWidgetCategoryBar(
    selectedCategory: LockscreenWidgetCategory,
    onSelect: (LockscreenWidgetCategory) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LockscreenWidgetCategory.entries.forEach { category ->
            val selected = category == selectedCategory
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                        },
                    )
                    .clickable { onSelect(category) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = category.label(),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSecondaryContainer
                    },
                )
            }
        }
    }
}

@Composable
internal fun LockscreenWidgetExtendedPreview(
    id: String,
    extSettings: LockscreenWidgetExtendedSettings,
    modifier: Modifier,
    textColor: ComposeColor,
) {
    when (id) {
        LockscreenWidgetIds.UV_INDEX -> {
            CircularGaugePreview(
                progress = 4f / 11f,
                valueText = "4",
                bottomLabel = "UV",
                textColor = textColor,
                modifier = modifier,
            )
        }
        LockscreenWidgetIds.RAIN_PROB -> {
            Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_widget_rain),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(Modifier.height(1.dp))
                Text("30%", color = textColor, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
            }
        }
        LockscreenWidgetIds.PRESSURE -> {
            CircularGaugePreview(
                progress = (1013f - 970f) / 80f,
                valueText = "1013",
                bottomLabel = "hPa",
                textColor = textColor,
                modifier = modifier,
                smallValue = true,
            )
        }
        LockscreenWidgetIds.BATTERY_RING -> {
            Box(modifier, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = textColor.copy(alpha = 0.28f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = textColor,
                        startAngle = -90f,
                        sweepAngle = 360f * 0.82f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.ic_widget_battery_bolt),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        colorFilter = ColorFilter.tint(textColor),
                    )
                    Text(
                        "82%",
                        color = textColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 13.sp,
                    )
                }
            }
        }
        LockscreenWidgetIds.BATTERY_TEMP -> {
            Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_widget_thermometer),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(Modifier.height(1.dp))
                Text("32.5°", color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        LockscreenWidgetIds.BLUETOOTH_BATTERY -> {
            Column(
                modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_widget_bluetooth),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        colorFilter = ColorFilter.tint(textColor),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("85%", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Buds Pro",
                    color = textColor.copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(textColor.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxWidth(0.85f).fillMaxHeight().clip(CircleShape).background(textColor))
                }
            }
        }
        LockscreenWidgetIds.WORLD_CLOCK -> {
            val snap = remember(extSettings.worldClockZoneId, extSettings.worldClockLabel) {
                buildWorldClockSnapshot(extSettings.worldClockZoneId, extSettings.worldClockLabel)
            }
            Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    snap.label,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    snap.timeText,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp,
                )
                Text(
                    snap.offsetLabel,
                    color = textColor.copy(alpha = 0.72f),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 10.sp,
                )
            }
        }
        LockscreenWidgetIds.CALENDAR_DATE -> {
            val now = remember { Calendar.getInstance() }
            val weekday = remember {
                SimpleDateFormat("EEE", Locale.getDefault()).format(now.time).uppercase(Locale.getDefault())
            }
            val day = remember { now.get(Calendar.DAY_OF_MONTH).toString() }
            Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    weekday,
                    color = textColor.copy(alpha = 0.82f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 12.sp,
                )
                Text(
                    day,
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 23.sp,
                )
            }
        }
        LockscreenWidgetIds.COUNTDOWN -> {
            val snap = remember(extSettings.countdownTitle, extSettings.countdownDate) {
                buildCountdownSnapshot(extSettings.countdownTitle, extSettings.countdownDate)
            }
            Row(
                modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_widget_countdown),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        snap.title,
                        color = textColor.copy(alpha = 0.78f),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        snap.daysLabel,
                        color = textColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LockscreenWidgetIds.CUSTOM_MEMO -> {
            val title = extSettings.memoTitle.ifBlank { tr("lockscreenWidgetMemoDefaultTitle", "便签") }
            val content = extSettings.memoContent.ifBlank { tr("lockscreenWidgetMemoDefaultContent", "保持热爱，奔赴山海") }
            Row(
                modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_widget_memo),
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        title,
                        color = textColor.copy(alpha = 0.78f),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        content,
                        color = textColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LockscreenWidgetIds.SLEEP -> {
            Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_widget_sleep),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(Modifier.height(1.dp))
                Text("7h30m", color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        LockscreenWidgetIds.ACTIVITY_RINGS -> {
            Row(
                modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(38.dp)) {
                    val stroke = 4.dp.toPx()
                    val outerInset = stroke / 2f + 1.dp.toPx()
                    val outerSize = Size(size.width - outerInset * 2, size.height - outerInset * 2)
                    drawArc(
                        textColor.copy(alpha = 0.28f), -90f, 360f, false,
                        Offset(outerInset, outerInset), outerSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        textColor, -90f, 360f * 0.74f, false,
                        Offset(outerInset, outerInset), outerSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    val innerInset = outerInset + stroke + 2.5.dp.toPx()
                    val innerSize = Size(size.width - innerInset * 2, size.height - innerInset * 2)
                    drawArc(
                        textColor.copy(alpha = 0.28f), -90f, 360f, false,
                        Offset(innerInset, innerInset), innerSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        textColor.copy(alpha = 0.84f), -90f, 360f * 0.67f, false,
                        Offset(innerInset, innerInset), innerSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        tr("lockscreenWidgetActivityPreviewSteps", "7256 步"),
                        color = textColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        tr("lockscreenWidgetActivityPreviewStand", "站立 8/12"),
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        LockscreenWidgetIds.SOUND_MODE -> {
            Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_widget_sound_ring),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    tr("lockscreenWidgetSoundPreview", "响铃"),
                    color = textColor,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        LockscreenWidgetIds.FLASHLIGHT -> {
            Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_widget_flashlight),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    tr("lockscreenWidgetFlashlightPreview", "手电筒"),
                    color = textColor,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CircularGaugePreview(
    progress: Float,
    valueText: String,
    bottomLabel: String,
    textColor: ComposeColor,
    modifier: Modifier,
    smallValue: Boolean = false,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = textColor.copy(alpha = 0.28f),
                startAngle = 130f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = textColor,
                startAngle = 130f,
                sweepAngle = 280f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = valueText,
            color = textColor,
            fontSize = if (smallValue) 11.5.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = (-2).dp),
        )
        Text(
            text = bottomLabel,
            color = textColor.copy(alpha = 0.82f),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
        )
    }
}

@Composable
internal fun LockscreenWidgetExtendedConfigCards(
    selectedIds: List<String>,
    extSettings: LockscreenWidgetExtendedSettings,
    onUpdate: ((LockscreenWidgetExtendedSettings) -> LockscreenWidgetExtendedSettings) -> Unit,
) {
    val context = LocalContext.current
    var showWorldLabelDialog by remember { mutableStateOf(false) }
    var showCountdownTitleDialog by remember { mutableStateOf(false) }
    var showCountdownDateDialog by remember { mutableStateOf(false) }
    var showMemoTitleDialog by remember { mutableStateOf(false) }
    var showMemoContentDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (LockscreenWidgetIds.WORLD_CLOCK in selectedIds) {
            val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
            val cityLabels = WORLD_CLOCK_CITY_OPTIONS.map { opt ->
                val name = when (lang) {
                    "zh" -> opt.zhName
                    "ja" -> opt.jaName
                    else -> opt.enName
                }
                "$name (${opt.code})"
            }
            val selectedZoneIndex = WORLD_CLOCK_CITY_OPTIONS
                .indexOfFirst { it.zoneId == extSettings.worldClockZoneId }
                .coerceAtLeast(0)
            Group(tr("lockscreenWidgetWorldClockConfig", "世界时钟设置")) {
                OverlayDropdownPreference(
                    title = tr("lockscreenWidgetWorldClockCity", "目标城市"),
                    items = cityLabels,
                    selectedIndex = selectedZoneIndex,
                    onSelectedIndexChange = { index ->
                        val picked = WORLD_CLOCK_CITY_OPTIONS.getOrNull(index) ?: return@OverlayDropdownPreference
                        onUpdate { it.copy(worldClockZoneId = picked.zoneId) }
                    },
                )
                SharedViewArrowPreference(
                    title = tr("lockscreenWidgetWorldClockLabel", "自定义城市简称"),
                    summary = extSettings.worldClockLabel.ifBlank {
                        resolveWorldClockShortLabel(extSettings.worldClockZoneId, "")
                    },
                    onClick = { showWorldLabelDialog = true },
                )
            }
        }
        if (LockscreenWidgetIds.COUNTDOWN in selectedIds) {
            val snap = buildCountdownSnapshot(extSettings.countdownTitle, extSettings.countdownDate)
            Group(tr("lockscreenWidgetCountdownConfig", "倒数日设置")) {
                SharedViewArrowPreference(
                    title = tr("lockscreenWidgetCountdownTitle", "倒数日标题"),
                    summary = snap.title,
                    onClick = { showCountdownTitleDialog = true },
                )
                SharedViewArrowPreference(
                    title = tr("lockscreenWidgetCountdownDate", "目标日期"),
                    summary = extSettings.countdownDate.ifBlank { defaultCountdownDateString() } + " · " + snap.daysLabel,
                    onClick = { showCountdownDateDialog = true },
                )
            }
        }
        if (LockscreenWidgetIds.CUSTOM_MEMO in selectedIds) {
            Group(tr("lockscreenWidgetMemoConfig", "便签寄语设置")) {
                SharedViewArrowPreference(
                    title = tr("lockscreenWidgetMemoTitle", "便签标题"),
                    summary = extSettings.memoTitle.ifBlank { tr("lockscreenWidgetMemoTitleEmpty", "留空则仅显示正文") },
                    onClick = { showMemoTitleDialog = true },
                )
                SharedViewArrowPreference(
                    title = tr("lockscreenWidgetMemoContent", "便签内容"),
                    summary = extSettings.memoContent.ifBlank { tr("lockscreenWidgetMemoDefaultContent", "保持热爱，奔赴山海") },
                    onClick = { showMemoContentDialog = true },
                )
            }
        }
    }

    LockscreenWidgetTextEditDialog(
        show = showWorldLabelDialog,
        title = tr("lockscreenWidgetWorldClockLabel", "自定义城市简称"),
        hint = tr("lockscreenWidgetWorldClockLabelHint", "留空使用默认城市简称"),
        initialValue = extSettings.worldClockLabel,
        allowBlank = true,
        onDismiss = { showWorldLabelDialog = false },
        onSave = { value ->
            onUpdate { it.copy(worldClockLabel = value.trim().take(12)) }
            showWorldLabelDialog = false
        },
    )
    LockscreenWidgetTextEditDialog(
        show = showCountdownTitleDialog,
        title = tr("lockscreenWidgetCountdownTitle", "倒数日标题"),
        hint = tr("lockscreenWidgetCountdownTitleHint", "例如：新年、高考、纪念日"),
        initialValue = extSettings.countdownTitle,
        allowBlank = true,
        onDismiss = { showCountdownTitleDialog = false },
        onSave = { value ->
            onUpdate { it.copy(countdownTitle = value.trim().take(24)) }
            showCountdownTitleDialog = false
        },
    )
    LockscreenWidgetTextEditDialog(
        show = showCountdownDateDialog,
        title = tr("lockscreenWidgetCountdownDate", "目标日期"),
        hint = tr("lockscreenWidgetCountdownDateHint", "格式：YYYY-MM-DD（如 2027-01-01）"),
        initialValue = extSettings.countdownDate.ifBlank { defaultCountdownDateString() },
        allowBlank = true,
        onDismiss = { showCountdownDateDialog = false },
        onSave = { value ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty() && parseCountdownDateMillis(trimmed) == null) {
                Toast.makeText(
                    context,
                    tr("lockscreenWidgetCountdownDateInvalid", "日期格式无效，请使用 YYYY-MM-DD"),
                    Toast.LENGTH_SHORT,
                ).show()
                return@LockscreenWidgetTextEditDialog
            }
            onUpdate { it.copy(countdownDate = trimmed) }
            showCountdownDateDialog = false
        },
    )
    LockscreenWidgetTextEditDialog(
        show = showMemoTitleDialog,
        title = tr("lockscreenWidgetMemoTitle", "便签标题"),
        hint = tr("lockscreenWidgetMemoTitleHint", "可选标题，留空则显示两行正文"),
        initialValue = extSettings.memoTitle,
        allowBlank = true,
        onDismiss = { showMemoTitleDialog = false },
        onSave = { value ->
            onUpdate { it.copy(memoTitle = value.trim().take(24)) }
            showMemoTitleDialog = false
        },
    )
    LockscreenWidgetTextEditDialog(
        show = showMemoContentDialog,
        title = tr("lockscreenWidgetMemoContent", "便签内容"),
        hint = tr("lockscreenWidgetMemoContentHint", "输入锁屏展示的短句或备忘提醒"),
        initialValue = extSettings.memoContent,
        allowBlank = true,
        onDismiss = { showMemoContentDialog = false },
        onSave = { value ->
            onUpdate { it.copy(memoContent = value.trim().take(48)) }
            showMemoContentDialog = false
        },
    )
}

@Composable
private fun LockscreenWidgetTextEditDialog(
    show: Boolean,
    title: String,
    hint: String,
    initialValue: String,
    allowBlank: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(show, initialValue) { mutableStateOf(initialValue) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = value,
                onValueChange = { value = it },
                label = hint,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onSave(value) },
                    enabled = allowBlank || value.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}
