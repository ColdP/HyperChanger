// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val FOCUS_LIST_HEADER_COUNT = 2
internal var pendingFocusModeEditId: String? by mutableStateOf(null)

private val FocusDragHandleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "FocusDragHandle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(Color.Black)
        for (y in floatArrayOf(7f, 12f, 17f)) {
            path(
                stroke = stroke,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(4.5f, y)
                lineTo(19.5f, y)
            }
        }
    }.build()
}

private val FocusChevronRightIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "FocusChevronRight",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9.5f, 6.5f)
            lineTo(15f, 12f)
            lineTo(9.5f, 17.5f)
        }
    }.build()
}

private fun focusIconLabel(iconKey: String): String = when (iconKey) {
    FOCUS_ICON_MOON -> tr("cc_focus_icon_moon", "月亮")
    FOCUS_ICON_BED -> tr("cc_focus_icon_bed", "睡眠")
    FOCUS_ICON_CAR -> tr("cc_focus_icon_car", "驾驶")
    FOCUS_ICON_SPARKLES -> tr("cc_focus_icon_sparkles", "减少干扰")
    FOCUS_ICON_PERSON -> tr("cc_focus_icon_person", "个人")
    FOCUS_ICON_WORK -> tr("cc_focus_icon_work", "工作")
    FOCUS_ICON_BOOK -> tr("cc_focus_icon_book", "阅读")
    FOCUS_ICON_GAME -> tr("cc_focus_icon_game", "游戏")
    FOCUS_ICON_HEART -> tr("cc_focus_icon_heart", "生活")
    FOCUS_ICON_STAR -> tr("cc_focus_icon_star", "星标")
    FOCUS_ICON_CUSTOM -> tr("cc_focus_icon_custom", "自定义图标")
    else -> tr("cc_focus_icon_moon", "月亮")
}

@Composable
private fun FocusModeIconBitmap(
    mode: CcFocusModeItem,
    tintColor: Int,
    sizeDp: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(mode.iconKey, mode.customIconBase64, mode.tintCustomIcon, tintColor, sizeDp) {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val drawable = CcFocusIcons.modeDrawable(context, mode, tintColor)
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { bmp ->
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(bmp))
        }
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier.size(sizeDp.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun ControlCenterFocusModePage(
    back: () -> Unit,
    openEditor: (String?) -> Unit,
) = AppPage(
    tr("cc_focus_mode_title", "专注模式"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { ControlCenterFocusModeSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var modes by remember { mutableStateOf(store.settings.modes) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var settlingId by remember { mutableStateOf<String?>(null) }
    val settlingOffset = remember { Animatable(0f) }

    fun update(transform: (ControlCenterFocusModeSettings) -> ControlCenterFocusModeSettings) {
        store.update(service, transform)
        settings = store.settings
        if (draggingId == null) {
            modes = store.settings.modes
        }
    }

    LaunchedEffect(Unit, service) {
        service?.let(store::syncRemote)
        val fresh = ControlCenterFocusModeSettingsStore(context).settings
        settings = fresh
        if (draggingId == null) {
            modes = fresh.modes
        }
    }

    val itemSpacing = 12.dp
    val itemSpacingPx = with(density) { itemSpacing.toPx() }
    val currentModes by rememberUpdatedState(modes)

    fun startDrag(id: String) {
        settlingId = null
        scope.launch { settlingOffset.snapTo(0f) }
        draggingId = id
        draggingOffsetY = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun updateDrag(id: String, deltaY: Float) {
        if (draggingId != id) return
        draggingOffsetY += deltaY
        val activeModes = currentModes
        val currentIndex = activeModes.indexOfFirst { it.id == id }
        if (currentIndex < 0) return

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val itemKey = "focus:$id"
        val draggedInfo = visibleItems.firstOrNull { it.key == itemKey } ?: return
        // Wait for Compose layout to catch up with the latest swap before checking the next boundary.
        val expectedLazyIndex = FOCUS_LIST_HEADER_COUNT + currentIndex
        if (draggedInfo.index != expectedLazyIndex) return

        val draggedCenterY = draggedInfo.offset + draggingOffsetY + draggedInfo.size / 2f

        if (draggingOffsetY > 0f && currentIndex < activeModes.lastIndex) {
            val nextMode = activeModes[currentIndex + 1]
            val nextInfo = visibleItems.firstOrNull { it.key == "focus:${nextMode.id}" }
            if (nextInfo != null && draggedCenterY > nextInfo.offset + nextInfo.size / 2f) {
                val mutable = activeModes.toMutableList()
                val moved = mutable.removeAt(currentIndex)
                mutable.add(currentIndex + 1, moved)
                modes = mutable
                draggingOffsetY -= (nextInfo.size + itemSpacingPx)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                return
            }
        }

        if (draggingOffsetY < 0f && currentIndex > 0) {
            val prevMode = activeModes[currentIndex - 1]
            val prevInfo = visibleItems.firstOrNull { it.key == "focus:${prevMode.id}" }
            if (prevInfo != null && draggedCenterY < prevInfo.offset + prevInfo.size / 2f) {
                val mutable = activeModes.toMutableList()
                val moved = mutable.removeAt(currentIndex)
                mutable.add(currentIndex - 1, moved)
                modes = mutable
                draggingOffsetY += (prevInfo.size + itemSpacingPx)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun finishDrag(id: String) {
        if (draggingId != id) return
        val settledFrom = draggingOffsetY
        val committed = currentModes
        draggingId = null
        draggingOffsetY = 0f
        update { it.copy(modes = committed) }
        if (abs(settledFrom) > 0.5f) {
            settlingId = id
            scope.launch {
                settlingOffset.snapTo(settledFrom)
                settlingOffset.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
                if (settlingId == id) settlingId = null
            }
        }
    }

    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val cardRadius = 22.5.dp
    val cardShape = RoundedCornerShape(cardRadius)
    val surfaceBg = MiuixTheme.colorScheme.surface
    val cardContainerColor = MiuixTheme.colorScheme.surfaceContainer
    val listBackdrop = rememberLayerBackdrop {
        drawRect(surfaceBg)
        drawContent()
    }
    var containerWindowPos by remember { mutableStateOf(Offset.Zero) }
    var activeSlotWindowPos by remember { mutableStateOf(Offset.Zero) }
    var activeSlotWidthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerWindowPos = it.positionInWindow() },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceBg)
                .nestedScroll(scroll.nestedScrollConnection)
                .layerBackdrop(listBackdrop),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            userScrollEnabled = draggingId == null,
        ) {
            item(key = "__enable_card__") {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = cardRadius) {
                    SwitchPreference(
                        title = tr("cc_focus_mode_enable", "启用专注模式"),
                        summary = tr(
                            "cc_focus_mode_enable_desc",
                            "当控制中心勿扰模式磁贴调整为 2×1 或更宽时，显示为类似 iOS 专注模式的胶囊样式与选择面板；所有专注模式均对应系统勿扰模式，仅区分图标与文本。",
                        ),
                        checked = settings.enabled,
                        onCheckedChange = { checked -> update { it.copy(enabled = checked) } },
                    )
                }
            }

            item(key = "__section_header__") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = tr("cc_focus_mode_list_group", "专注模式列表"),
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = tr(
                            "cc_focus_mode_drag_hint",
                            "点击卡片可进入二级编辑页，拖动左侧手柄或长按卡片可调整顺序。",
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            items(modes, key = { "focus:${it.id}" }) { mode ->
                val isDragging = draggingId == mode.id
                val isSettling = settlingId == mode.id
                val isFloating = isDragging || isSettling
                val isDefaultActive = mode.id == settings.activeId

                val placementModifier = if (isDragging) {
                    Modifier
                } else {
                    Modifier.animateItem(
                        fadeInSpec = tween(220),
                        fadeOutSpec = tween(200),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }

                var cardCoords by remember(mode.id) { mutableStateOf<LayoutCoordinates?>(null) }
                var handleCoords by remember(mode.id) { mutableStateOf<LayoutCoordinates?>(null) }
                var lastCardWindowY by remember(mode.id) { mutableFloatStateOf(0f) }
                var lastHandleWindowY by remember(mode.id) { mutableFloatStateOf(0f) }

                SeamlessCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(placementModifier)
                        .graphicsLayer { alpha = if (isFloating) 0f else 1f }
                        .clip(cardShape)
                        .onGloballyPositioned {
                            cardCoords = it
                            if (isFloating) {
                                activeSlotWindowPos = it.positionInWindow()
                                activeSlotWidthPx = it.size.width
                            }
                        }
                        .clickable {
                            if (draggingId == null) {
                                openEditor(mode.id)
                            }
                        }
                        .pointerInput(mode.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val coords = cardCoords
                                    if (coords != null && coords.isAttached) {
                                        activeSlotWindowPos = coords.positionInWindow()
                                        activeSlotWidthPx = coords.size.width
                                        lastCardWindowY = coords.localToWindow(startOffset).y
                                    } else {
                                        lastCardWindowY = startOffset.y
                                    }
                                    startDrag(mode.id)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val coords = cardCoords
                                    val deltaY = if (coords != null && coords.isAttached) {
                                        val windowY = coords.localToWindow(change.position).y
                                        val d = windowY - lastCardWindowY
                                        lastCardWindowY = windowY
                                        d
                                    } else {
                                        dragAmount.y
                                    }
                                    updateDrag(mode.id, deltaY)
                                },
                                onDragEnd = { finishDrag(mode.id) },
                                onDragCancel = { finishDrag(mode.id) },
                            )
                        },
                    cornerRadius = cardRadius,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    FocusModeOrderCardRow(
                        mode = mode,
                        isCurrentActive = isDefaultActive,
                        handleModifier = Modifier
                            .onGloballyPositioned { handleCoords = it }
                            .pointerInput(mode.id) {
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        val slotCoords = cardCoords
                                        if (slotCoords != null && slotCoords.isAttached) {
                                            activeSlotWindowPos = slotCoords.positionInWindow()
                                            activeSlotWidthPx = slotCoords.size.width
                                        }
                                        val coords = handleCoords
                                        lastHandleWindowY = if (coords != null && coords.isAttached) {
                                            coords.localToWindow(startOffset).y
                                        } else {
                                            startOffset.y
                                        }
                                        startDrag(mode.id)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val coords = handleCoords
                                        val deltaY = if (coords != null && coords.isAttached) {
                                            val windowY = coords.localToWindow(change.position).y
                                            val d = windowY - lastHandleWindowY
                                            lastHandleWindowY = windowY
                                            d
                                        } else {
                                            dragAmount.y
                                        }
                                        updateDrag(mode.id, deltaY)
                                    },
                                    onDragEnd = { finishDrag(mode.id) },
                                    onDragCancel = { finishDrag(mode.id) },
                                )
                            },
                    )
                }
            }

            item(key = "__actions__") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GlassDialogButton(
                            onClick = { openEditor(null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(tr("cc_focus_mode_add", "添加专注模式"))
                        }
                        GlassDialogButton(
                            onClick = {
                                val resetModes = CcFocusModeDefaults.createLocalizedDefaultModes()
                                update {
                                    it.copy(
                                        modes = resetModes,
                                        activeId = "dnd",
                                    )
                                }
                                modes = resetModes
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(tr("cc_focus_mode_reset", "恢复默认"))
                        }
                    }
                    Text(
                        text = tr(
                            "cc_focus_mode_list_hint",
                            "点击左侧图标按钮可直接开关勿扰模式，点击右侧名称或箭头区域可展开专注模式选择列表。",
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }

        val activeId = draggingId ?: settlingId
        val activeMode = activeId?.let { id -> modes.firstOrNull { it.id == id } }
        if (activeMode != null && activeSlotWidthPx > 0) {
            val isDragging = draggingId == activeMode.id
            val activeOffsetY = if (isDragging) draggingOffsetY else settlingOffset.value
            val dragScale by animateFloatAsState(
                targetValue = if (isDragging) 1.025f else 1f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
                label = "focus_floating_scale",
            )
            val dragElevation by animateDpAsState(
                targetValue = if (isDragging) 12.dp else 0.dp,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                label = "focus_floating_elevation",
            )
            val relX = (activeSlotWindowPos.x - containerWindowPos.x).roundToInt()
            val relY = (activeSlotWindowPos.y - containerWindowPos.y + activeOffsetY).roundToInt()
            val slotWidthDp = with(density) { activeSlotWidthPx.toDp() }
            val glassTint = cardContainerColor.copy(alpha = if (dark) 0.64f else 0.76f)

            SeamlessCard(
                modifier = Modifier
                    .offset { IntOffset(relX, relY) }
                    .width(slotWidthDp)
                    .zIndex(10f)
                    .graphicsLayer {
                        scaleX = dragScale
                        scaleY = dragScale
                        shape = cardShape
                        clip = true
                        shadowElevation = dragElevation.toPx()
                    }
                    .clip(cardShape)
                    .drawPlainBackdrop(
                        backdrop = listBackdrop,
                        shape = { cardShape },
                        effects = {
                            vibrancy()
                            backdropBlur(22.dp.toPx())
                        },
                        onDrawSurface = { drawRect(glassTint) },
                    )
                    .border(
                        0.75.dp,
                        MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.14f else 0.08f),
                        cardShape,
                    ),
                cornerRadius = cardRadius,
                insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = Color.Transparent),
            ) {
                FocusModeOrderCardRow(
                    mode = activeMode,
                    isCurrentActive = activeMode.id == settings.activeId,
                    handleModifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun FocusModeOrderCardRow(
    mode: CcFocusModeItem,
    isCurrentActive: Boolean,
    handleModifier: Modifier,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .then(handleModifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            Image(
                imageVector = FocusDragHandleIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (dark) Color(mode.accentColor).copy(alpha = 0.22f) else Color(mode.accentColor).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            FocusModeIconBitmap(
                mode = mode,
                tintColor = mode.accentColor,
                sizeDp = 21,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = mode.name,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrentActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(mode.accentColor).copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = tr("cc_focus_mode_current_tag", "当前"),
                            style = MiuixTheme.textStyles.footnote1,
                            color = Color(mode.accentColor),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            if (mode.subtitle.isNotBlank()) {
                Text(
                    text = mode.subtitle,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Image(
            imageVector = FocusChevronRightIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)),
        )
    }
}

@Composable
internal fun ControlCenterFocusModeEditorPage(
    modeId: String?,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context, modeId) { ControlCenterFocusModeSettingsStore(context) }
    val currentSettings = store.settings
    val existing = remember(modeId, currentSettings) {
        modeId?.let { id -> currentSettings.modes.firstOrNull { it.id == id } }
    }
    val isNew = existing == null
    val initial = remember(modeId, existing) {
        existing ?: CcFocusModeItem(
            id = "custom_${System.currentTimeMillis()}",
            name = "",
            subtitle = "",
            iconKey = FOCUS_ICON_STAR,
            accentColor = CcFocusModeDefaults.accentPalette[currentSettings.modes.size % CcFocusModeDefaults.accentPalette.size],
        )
    }
    val canDelete = !isNew && currentSettings.modes.size > 1
    var isCurrentActive by remember(modeId, currentSettings.activeId) {
        mutableStateOf(!isNew && currentSettings.activeId == initial.id)
    }

    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var subtitle by remember(initial.id) { mutableStateOf(initial.subtitle) }
    var iconKey by remember(initial.id) { mutableStateOf(initial.iconKey) }
    var accentColor by remember(initial.id) { mutableStateOf(initial.accentColor) }
    var customIconBase64 by remember(initial.id) { mutableStateOf(initial.customIconBase64) }
    var customIconName by remember(initial.id) { mutableStateOf(initial.customIconName) }
    var tintCustomIcon by remember(initial.id) { mutableStateOf(initial.tintCustomIcon) }
    var showImportDialog by remember(initial.id) { mutableStateOf(false) }

    val previewItem = CcFocusModeItem(
        id = initial.id,
        name = name.ifBlank { tr("cc_focus_mode_name_placeholder", "专注模式名称") },
        subtitle = subtitle,
        iconKey = iconKey,
        accentColor = accentColor,
        customIconBase64 = customIconBase64,
        customIconName = customIconName,
        tintCustomIcon = tintCustomIcon,
    )

    AppPage(
        title = if (isNew) tr("cc_focus_mode_add", "添加专注模式") else tr("cc_focus_mode_edit", "编辑专注模式"),
        onBack = back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI),
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(32.dp))
                                .background(Color(0xFF282B36))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                FocusModeIconBitmap(previewItem, accentColor, 22)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = previewItem.name,
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = Color.White.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Group(tr("cc_focus_mode_basic_group", "基本信息")) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = tr("cc_focus_mode_name_label", "名称"),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = subtitle,
                            onValueChange = { subtitle = it },
                            label = tr("cc_focus_mode_subtitle_label", "描述文本（选填）"),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                Group(tr("cc_focus_mode_appearance_group", "图标与主题色")) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = tr("cc_focus_mode_icon_section", "图标"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        CcFocusModeDefaults.builtInIcons.chunked(5).forEach { rowIcons ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowIcons.forEach { (key, _) ->
                                    val selected = iconKey == key
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (selected) Color(accentColor).copy(alpha = 0.18f)
                                                else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                            )
                                            .border(
                                                width = if (selected) 1.5.dp else 0.dp,
                                                color = if (selected) Color(accentColor) else Color.Transparent,
                                                shape = RoundedCornerShape(14.dp),
                                            )
                                            .clickable { iconKey = key },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        FocusModeIconBitmap(
                                            mode = CcFocusModeItem(id = key, name = "", iconKey = key, accentColor = accentColor),
                                            tintColor = if (selected) accentColor else 0xFF888888.toInt(),
                                            sizeDp = 22,
                                        )
                                    }
                                }
                            }
                        }

                        // Reuses the exact About-page image import dialog
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .border(
                                    width = if (iconKey == FOCUS_ICON_CUSTOM && customIconBase64.isNotBlank()) 1.5.dp else 0.dp,
                                    color = if (iconKey == FOCUS_ICON_CUSTOM && customIconBase64.isNotBlank()) Color(accentColor) else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .clickable { showImportDialog = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("cc_focus_icon_custom", "自定义图标"),
                                    style = MiuixTheme.textStyles.body1,
                                )
                                Text(
                                    text = if (customIconBase64.isNotBlank()) {
                                        customIconName.ifBlank { tr("cc_focus_icon_imported", "已导入") }
                                    } else {
                                        tr("未导入", "未导入")
                                    },
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = focusIconLabel(iconKey),
                                style = MiuixTheme.textStyles.footnote1,
                                color = Color(accentColor),
                            )
                        }

                        if (iconKey == FOCUS_ICON_CUSTOM && customIconBase64.isNotBlank()) {
                            SwitchPreference(
                                title = tr("cc_focus_icon_custom_tint", "自定义图标随主题着色"),
                                checked = tintCustomIcon,
                                onCheckedChange = { tintCustomIcon = it },
                            )
                        }

                        Text(
                            text = tr("cc_focus_mode_color_section", "主题色"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            CcFocusModeDefaults.accentPalette.forEach { colorInt ->
                                val selected = accentColor == colorInt
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt))
                                        .border(
                                            width = if (selected) 2.5.dp else 0.dp,
                                            color = if (selected) MiuixTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape,
                                        )
                                        .clickable { accentColor = colorInt },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!isNew && !isCurrentActive) {
                        GlassDialogButton(
                            onClick = {
                                store.update(service) { it.copy(activeId = initial.id) }
                                isCurrentActive = true
                                Toast.makeText(
                                    context,
                                    tr("cc_focus_mode_set_current_done", "已设为当前默认专注模式"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(tr("cc_focus_mode_set_current", "设为当前默认模式"))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (canDelete) {
                            GlassDialogButton(
                                onClick = {
                                    store.update(service) { current ->
                                        val remaining = current.modes.filterNot { it.id == initial.id }
                                        current.copy(
                                            modes = remaining,
                                            activeId = if (current.activeId == initial.id) remaining.firstOrNull()?.id ?: "dnd" else current.activeId,
                                        )
                                    }
                                    back()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(tr("cc_focus_mode_delete", "删除"))
                            }
                        }

                        GlassDialogButton(
                            onClick = {
                                val trimmed = name.trim()
                                if (trimmed.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        tr("cc_focus_mode_name_empty", "请输入专注模式名称"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@GlassDialogButton
                                }
                                val effectiveIconKey = if (iconKey == FOCUS_ICON_CUSTOM && customIconBase64.isBlank()) {
                                    FOCUS_ICON_MOON
                                } else {
                                    iconKey
                                }
                                val saved = initial.copy(
                                    name = trimmed,
                                    subtitle = subtitle.trim(),
                                    iconKey = effectiveIconKey,
                                    accentColor = accentColor,
                                    customIconBase64 = customIconBase64,
                                    customIconName = customIconName,
                                    tintCustomIcon = tintCustomIcon,
                                )
                                store.update(service) { current ->
                                    val list = if (isNew) {
                                        current.modes + saved
                                    } else {
                                        current.modes.map { if (it.id == saved.id) saved else it }
                                    }
                                    current.copy(modes = list)
                                }
                                back()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(tr("保存", "保存"))
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        FocusIconImportDialog(
            title = tr("cc_focus_icon_custom", "自定义图标"),
            existingBase64 = customIconBase64,
            existingName = customIconName,
            onSaved = { newBase64, newName ->
                customIconBase64 = newBase64
                customIconName = newName
                if (newBase64.isNotBlank()) {
                    iconKey = FOCUS_ICON_CUSTOM
                } else if (iconKey == FOCUS_ICON_CUSTOM) {
                    iconKey = FOCUS_ICON_MOON
                }
                showImportDialog = false
            },
            onDismiss = { showImportDialog = false },
        )
    }
}

/**
 * Reuses the exact About-page image import WindowDialog structure (AssetPreview, previewBitmap,
 * appearance_import_hint, and appearance_replace_confirm) for importing Focus Mode icons.
 */
@Composable
private fun FocusIconImportDialog(
    title: String,
    existingBase64: String,
    existingName: String,
    onSaved: (base64: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Uri?>(null) }
    var replacement by remember { mutableStateOf<Uri?>(null) }
    var cleared by remember { mutableStateOf(false) }
    val imported = existingBase64.isNotBlank()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if ((imported && !cleared) || selected != null) {
                replacement = uri
            } else {
                selected = uri
                cleared = false
            }
        }
    }

    val preview = remember(selected, cleared, existingBase64) {
        when {
            cleared -> null
            selected != null -> previewBitmap(context, selected!!)
            imported -> CcFocusIcons.decodeBase64ToBitmap(context, existingBase64, maxPx = 256)
            else -> null
        }
    }
    val displayName = selected?.let { appearanceAssetDisplayName(context, it) }
        ?: if (imported && !cleared) existingName else ""

    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, style = MiuixTheme.textStyles.title3)
            AssetPreview(preview)
            Text(
                displayName.ifBlank {
                    tr("appearance_import_hint", "点击下方的按钮进行导入，可以导入：%s")
                        .format("PNG / JPG / WebP / SVG / XML")
                },
                style = MiuixTheme.textStyles.body2,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassDialogButton(
                    onClick = {
                        picker.launch(arrayOf("image/*", "image/svg+xml", "application/xml", "text/xml"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("选择文件", "选择文件"))
                }
                GlassDialogButton(
                    onClick = {
                        selected = null
                        cleared = true
                    },
                    modifier = Modifier.weight(1f),
                    enabled = selected != null || (imported && !cleared),
                ) {
                    Text(tr("清除", "清除"))
                }
            }
            GlassDialogButton(
                onClick = {
                    runCatching {
                        when {
                            cleared -> onSaved("", "")
                            selected != null -> {
                                val uri = selected!!
                                val encoded = CcFocusIcons.encodeCustomIconFromUri(context, uri)
                                    ?: error("Cannot decode icon")
                                val fileName = appearanceAssetDisplayName(context, uri)
                                onSaved(encoded, fileName)
                            }
                            else -> onDismiss()
                        }
                    }.onFailure {
                        Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("保存", "保存"))
            }
            GlassDialogButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("取消", "取消"))
            }
        }
    }

    replacement?.let { uri ->
        val nextPreview = remember(uri) { previewBitmap(context, uri) }
        WindowDialog(show = true, onDismissRequest = { replacement = null }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(tr("appearance_replace_confirm", "替换已导入的文件？"), style = MiuixTheme.textStyles.title3)
                AssetPreview(nextPreview)
                Text(appearanceAssetDisplayName(context, uri), style = MiuixTheme.textStyles.body2)
                GlassDialogButton(
                    onClick = {
                        selected = uri
                        cleared = false
                        replacement = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("确认", "确认"))
                }
                GlassDialogButton(
                    onClick = { replacement = null },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("取消", "取消"))
                }
            }
        }
    }
}
