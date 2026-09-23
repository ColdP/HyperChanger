// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import btm.m.liquidglass.hook.InteractiveHighlight
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberListPopupLayoutInfo
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope

private val GlassMenuCornerRadius = 25.dp
private val GlassMenuShadowPadding = 28.dp
private val GlassMenuHorizontalOffset = 18.dp

@Composable
fun GlassDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    showValueOnEnd: Boolean = false,
    disabledIndices: Set<Int> = emptySet(),
    onSelectedIndexChange: (Int) -> Unit,
) {
    val selected = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    GlassDropdownMenu(
        entry = DropdownEntry(
            items = items.mapIndexed { index, text ->
                DropdownItem(
                    text = text,
                    selected = index == selected,
                    enabled = index !in disabledIndices,
                    onClick = { onSelectedIndexChange(index) },
                )
            },
        ),
        title = title,
        modifier = modifier,
        enabled = enabled,
        backdrop = backdrop,
        showValueOnEnd = showValueOnEnd,
    )
}

@Composable
fun GlassDropdownMenu(
    entry: DropdownEntry,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    maxHeight: Dp? = null,
    showValueOnEnd: Boolean = false,
) {
    val expanded = remember { mutableStateOf(false) }
    val entries = remember(entry) { listOf(entry) }
    val available = enabled && entry.items.isNotEmpty()
    val colors = glassDropdownColors()
    val selectedSummary = entry.items.firstOrNull { it.selected }?.text

    Box(modifier) {
        BasicComponent(
            modifier = Modifier.fillMaxWidth(),
            interactionSource = remember { MutableInteractionSource() },
            title = title,
            summary = selectedSummary.takeUnless { showValueOnEnd },
            endActions = {
                if (showValueOnEnd && selectedSummary != null) {
                    Text(
                        selectedSummary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantActions)
            },
            onClick = { if (available) expanded.value = !expanded.value },
            role = Role.DropdownList,
            holdDownState = false,
            enabled = available,
        )
        GlassDropdownPopup(
            entries = entries,
            show = expanded.value,
            onDismiss = { expanded.value = false },
            maxHeight = maxHeight,
            dropdownColors = colors,
            backdrop = backdrop,
        )
    }
}

@Composable
fun GlassIconDropdownMenu(
    entry: DropdownEntry,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trigger: (@Composable (onClick: () -> Unit, enabled: Boolean) -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val expanded = remember { mutableStateOf(false) }
    val available = enabled && entry.items.isNotEmpty()
    Box(modifier) {
        val toggleExpanded = { if (available) expanded.value = !expanded.value }
        if (trigger != null) {
            trigger(toggleExpanded, available)
        } else {
            IconButton(
                onClick = toggleExpanded,
                enabled = available,
                holdDownState = false,
                content = content,
            )
        }
        GlassDropdownPopup(
            entries = listOf(entry),
            show = expanded.value,
            onDismiss = { expanded.value = false },
            dropdownColors = glassDropdownColors(),
            backdrop = backdrop,
            alignment = PopupPositionProvider.Align.End,
            horizontalOffset = 0.dp,
            useAnchorVerticalPosition = true,
        )
    }
}

@Composable
private fun glassDropdownColors(): DropdownColors = DropdownDefaults.dropdownColors(
    containerColor = Color.Transparent,
    selectedContainerColor = Color.Transparent,
)

@Composable
private fun GlassDropdownPopup(
    entries: List<DropdownEntry>,
    show: Boolean,
    onDismiss: () -> Unit,
    dropdownColors: DropdownColors,
    backdrop: Backdrop?,
    maxHeight: Dp? = null,
    alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.End,
    horizontalOffset: Dp = GlassMenuHorizontalOffset,
    useAnchorVerticalPosition: Boolean = false,
) {
    val fraction = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var hostVisible by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    var parentBounds by remember { mutableStateOf(IntRect.Zero) }
    var hostPosition by remember { mutableStateOf(Offset.Zero) }
    var popupSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(show) {
        if (show) {
            hostVisible = true
            launch {
                fraction.animateTo(
                    1f,
                    spring(dampingRatio = .78f, stiffness = 232f, visibilityThreshold = .0001f),
                )
            }
            launch { alpha.animateTo(1f, tween(120)) }
        } else if (hostVisible) {
            launch {
                fraction.animateTo(
                    0f,
                    spring(dampingRatio = .78f, stiffness = 400f, visibilityThreshold = .0001f),
                )
            }
            alpha.animateTo(0f, tween(320))
            fraction.snapTo(0f)
            hostVisible = false
        }
    }

    if (!show && !hostVisible) return

    androidx.compose.foundation.layout.Spacer(
        Modifier.onGloballyPositioned { coordinates ->
            coordinates.parentLayoutCoordinates?.let { parent ->
                val position = parent.positionInWindow()
                parentBounds = IntRect(
                    position.x.toInt(),
                    position.y.toInt(),
                    position.x.toInt() + parent.size.width,
                    position.y.toInt() + parent.size.height,
                )
            }
        },
    )
    if (parentBounds == IntRect.Zero) return

    val positionProvider = ListPopupDefaults.DropdownPositionProvider
    val shadowPadding = with(LocalDensity.current) { GlassMenuShadowPadding.roundToPx() }
    val horizontalOffsetPx = with(LocalDensity.current) { horizontalOffset.roundToPx() }
    val layoutInfo = rememberListPopupLayoutInfo(
        alignment = alignment,
        popupPositionProvider = positionProvider,
        parentBounds = parentBounds,
        popupContentSize = popupSize,
    )

    MiuixPopupUtils.Companion.PopupLayout(
        visible = remember(hostVisible) { mutableStateOf(hostVisible) }.also { it.value = hostVisible },
        enableWindowDim = false,
        enableBackHandler = false,
        enterTransition = androidx.compose.animation.EnterTransition.None,
        exitTransition = androidx.compose.animation.ExitTransition.None,
        renderInRootScaffold = true,
    ) {
        BackHandler(enabled = show) { currentDismiss() }
        Box(Modifier.fillMaxSize()) {
            Layout(
                content = {
                    GlassPopupSurface(
                        fraction = fraction.value,
                        alpha = alpha.value,
                        backdrop = backdrop,
                        onSizeChanged = { popupSize = it },
                    ) {
                        GlassDropdownEntries(entries, dropdownColors) { entryIndex, itemIndex ->
                            entries[entryIndex].items[itemIndex].onClick?.invoke()
                            currentDismiss()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { hostPosition = it.positionInWindow() }
                    .pointerInput(Unit) { detectTapGestures(onTap = { currentDismiss() }) },
            ) { measurables, constraints ->
                val measurable = measurables.single()
                val maxPopupHeight = maxHeight?.roundToPx()
                    ?: (layoutInfo.windowBounds.height - layoutInfo.popupMargin.top - layoutInfo.popupMargin.bottom)
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, minHeight = 0, maxHeight = maxPopupHeight.coerceAtLeast(1)),
                )
                val result = positionProvider.calculatePosition(
                    parentBounds,
                    layoutInfo.windowBounds,
                    layoutDirection,
                    IntSize(placeable.width, placeable.height),
                    layoutInfo.popupMargin,
                    alignment,
                )
                val popupY = if (useAnchorVerticalPosition) {
                    result.y
                } else {
                    val safeInset = (parentBounds.height * .10f).roundToInt()
                    val minY = layoutInfo.windowBounds.top + layoutInfo.popupMargin.top
                    val maxY = (layoutInfo.windowBounds.bottom - placeable.height - layoutInfo.popupMargin.bottom)
                        .coerceAtLeast(minY)
                    (parentBounds.top + safeInset - shadowPadding).coerceIn(minY, maxY)
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        result.x + horizontalOffsetPx - hostPosition.x.toInt(),
                        popupY - hostPosition.y.toInt(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassPopupSurface(
    fraction: Float,
    alpha: Float,
    backdrop: Backdrop?,
    onSizeChanged: (IntSize) -> Unit,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val isLightTheme = surface.luminance() > .5f
    val tint = (if (isLightTheme) Color(0xFFF8F8F8) else surface).copy(alpha = .80f)
    val shadowColor = if (isLightTheme) Color.Gray else Color.Black
    val shape = RoundedCornerShape(GlassMenuCornerRadius)
    val animationScope = rememberCoroutineScope()
    val touchHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            radiusMultiplier = .4f,
            surfaceAlpha = 0f,
            falloffMultiplier = .25f,
        ) { size, offset ->
            Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    Box(Modifier.padding(GlassMenuShadowPadding)) {
        Box(
            Modifier
                .sizeIn(minWidth = DropdownDefaults.MinWidth)
                .onGloballyPositioned { onSizeChanged(it.size) }
                .graphicsLayer {
                    val scale = .24f + .76f * fraction
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    transformOrigin = TransformOrigin(.5f, 0f)
                }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                vibrancy()
                                backdropBlur(4.dp.toPx())
                                lens(10.dp.toPx(), 28.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = { Highlight.Default.copy(alpha = .70f) },
                            shadow = {
                                Shadow.Default.copy(
                                    radius = 12.dp,
                                    color = shadowColor,
                                    alpha = .35f,
                                )
                            },
                            innerShadow = null,
                            onDrawSurface = { drawRect(tint) },
                        )
                    } else {
                        Modifier
                            .clip(shape)
                            .background(tint)
                    }
                )
                .then(touchHighlight.gestureModifier)
                .then(touchHighlight.modifier),
        ) {
            content()
        }
    }
}

@Composable
private fun GlassDropdownEntries(
    entries: List<DropdownEntry>,
    colors: DropdownColors,
    onItemClick: (Int, Int) -> Unit,
) {
    ListPopupColumn {
        entries.forEachIndexed { entryIndex, entry ->
            entry.items.forEachIndexed { itemIndex, item ->
                DropdownImpl(
                    item = item,
                    optionSize = entry.items.size,
                    isSelected = item.selected,
                    index = itemIndex,
                    dropdownColors = colors,
                    enabled = entry.enabled && item.enabled,
                    isFirst = entryIndex == 0 && itemIndex == 0,
                    isLast = entryIndex == entries.lastIndex && itemIndex == entry.items.lastIndex,
                    onSelectedIndexChange = { onItemClick(entryIndex, it) },
                )
            }
            if (entryIndex != entries.lastIndex) {
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), thickness = 1.5.dp)
            }
        }
    }
}
