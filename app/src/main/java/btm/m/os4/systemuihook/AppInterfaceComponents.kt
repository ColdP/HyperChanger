// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Item
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.text.CouiTextKind
import com.itos.coui.theme.CouiPresets
import com.itos.coui.theme.CouiColorScheme
import com.itos.coui.theme.CouiTheme
import com.itos.coui.menu.Menu
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private class ColorDividerPosition { var coordinates: LayoutCoordinates? = null }

@Composable
private fun ColorPreferenceDivider() {
    val card = LocalSeamlessCard.current
    val position = remember { ColorDividerPosition() }
    com.itos.coui.layout.Divider(Modifier.onGloballyPositioned { position.coordinates = it }
        .drawWithContent {
            val cardBottom = card?.bounds()?.bottom?.minus(card.bottomPaddingPx)
            val dividerBottom = position.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.bottom
            if (cardBottom != null && dividerBottom != null && dividerBottom < cardBottom - 1f) {
                drawContent()
            }
        })
}

/** COUI owns Color controls; Miuix colors bridge the remaining custom app content. */
@Composable
internal fun AppInterfaceTheme(controller: ThemeController, monetEnabled: Boolean, content: @Composable () -> Unit) {
    val style = rememberAppInterfaceStyle()
    CompositionLocalProvider(LocalAppInterfaceStyle provides style) {
        MiuixTheme(controller = controller) {
            if (style == AppInterfaceStyle.HYPER) content()
            else {
                val base = MiuixTheme.colorScheme
                val dark = base.surface.luminance() < .5f
                val native = if (dark) CouiPresets.Dark else CouiPresets.Light
                // Map the complete Miuix palette into COUI.  Keeping the native surface
                // roles here is important: Backdrop must sample the real page surface,
                // otherwise Color mode turns sampled components grey.
                val colors = if (monetEnabled) couiColorSchemeFromMiuix(base) else native
                CouiTheme(darkTheme = dark, colorScheme = colors) {
                    ProvideCouiMenuBackdrop { menuModifier ->
                        Box(menuModifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }
}

private fun couiColorSchemeFromMiuix(base: top.yukonga.miuix.kmp.theme.Colors) = CouiColorScheme(
    accent = base.primary,
    onAccent = base.onPrimary,
    surface = base.surface,
    container = base.surfaceContainer,
    onSurface = base.onSurface,
    onSurfaceVariant = base.onSurfaceVariantSummary,
    outlineVariant = base.dividerLine,
    accentOnSurface = base.primary,
    menuSurface = base.surfaceContainer,
    checkboxAccent = base.primary,
    menuCheckboxAccent = base.primary,
    background = base.background,
    card = base.surfaceContainer,
)

@Composable
internal fun ColorDropdownPreference(title: String, items: List<String>, selectedIndex: Int,
    modifier: Modifier, enabled: Boolean, showValueOnEnd: Boolean, disabledIndices: Set<Int>,
    onSelectedIndexChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var openRequested by remember { mutableStateOf(false) }
    val anchor = rememberCouiMenuAnchor()
    LaunchedEffect(openRequested) {
        if (!openRequested) return@LaunchedEffect
        // Let COUI's pointer observer finish its Final-pass cleanup. With no
        // pending touch, the menu provider uses the arrow Box bounds itself,
        // keeping the popup fixed even when the row was clicked elsewhere.
        withFrameNanos { }
        anchor.open { expanded = true }
        openRequested = false
    }
    Box(modifier) {
        ColorPreferenceRow(title, items.getOrNull(selectedIndex).takeUnless { showValueOnEnd },
            Modifier, enabled, onClick = { openRequested = true },
            trailing = {
            if (showValueOnEnd) com.itos.coui.text.Text(text = items.getOrNull(selectedIndex).orEmpty(),
                kind = CouiTextKind.PreferenceSummary, color = CouiTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.couiMenuAnchor(anchor).padding(start = 8.dp).size(18.dp),
                contentAlignment = Alignment.Center) {
                Image(MiuixIcons.Basic.ArrowUpDown, null, Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(CouiTheme.colorScheme.onSurfaceVariant))
                // Menu is composed inside this fixed-size arrow box and does not
                // receive a touch anchor, so COUI uses this box's bounds rather
                // than the row's pointer position for popup placement.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * 1.15f, density.fontScale)) {
                    Menu(expanded = expanded, onDismissRequest = { expanded = false }, anchor = anchor,
                        items = items.mapIndexed { i, text -> CouiMenuItem(i, text, selected = i == selectedIndex,
                            enabled = enabled && i !in disabledIndices) },
                        onItemClick = { item -> expanded = false; onSelectedIndexChange(item.value) })
                }
            }
            })
    }
}

/** Same value mapping and commit callbacks as the Hyper slider, with COUI input/rendering. */
@Composable
internal fun InterfaceSliderPreference(value: Float, onValueChange: (Float) -> Unit,
            modifier: Modifier = Modifier, title: String? = null, valueText: String? = null,
    onClick: (() -> Unit)? = null, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null) {
    if (LocalAppInterfaceStyle.current == AppInterfaceStyle.HYPER) {
        top.yukonga.miuix.kmp.preference.SliderPreference(value, onValueChange, modifier = modifier,
            title = title, valueText = valueText, onClick = onClick, enabled = enabled,
            valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished)
    } else Column(modifier.fillMaxWidth()) {
      Column(Modifier.fillMaxWidth()) {
        ColorPreferenceRow(title.orEmpty(), enabled = enabled, onClick = onClick, showDivider = false, increaseHeight = false,
            trailing = { valueText?.let { com.itos.coui.text.Text(text = it,
                kind = CouiTextKind.PreferenceSummary, color = CouiTheme.colorScheme.onSurfaceVariant) } })
        com.itos.coui.slider.Slider(value = value, onValueChange = onValueChange,
            valueRange = valueRange, steps = steps, enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp),
            onValueChangeFinished = { onValueChangeFinished?.invoke() })
      }
        ColorPreferenceDivider()
    }
}

@Composable
internal fun ColorPreferenceRow(
    title: String, summary: String? = null, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: (() -> Unit)? = null,
    startAction: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    showDivider: Boolean = true,
    increaseHeight: Boolean = true,
    toggleState: Boolean? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
) {
    val colors = CouiTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val rowInteraction = when {
        toggleState != null && onToggleChange != null -> Modifier
            .couiButtonFeedback(interactionSource = interactions, enabled = enabled, list = true)
            .toggleable(toggleState, interactions, null, enabled, Role.Switch, onToggleChange)
        onClick != null -> Modifier
            .couiButtonFeedback(interactionSource = interactions, enabled = enabled, list = true)
            .clickable(interactions, null, enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Column(modifier.fillMaxWidth()) {
        // 125% (previous rows) * 92% = 115% of the COUI native minimum.
        // Grow the Item itself so feedback fills the complete row.
        Item(modifier = Modifier.fillMaxWidth().heightIn(min = if (increaseHeight) 55.2.dp else 36.dp)
            .then(rowInteraction),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = when {
                !increaseHeight -> 4.dp
                summary != null -> 14.8.dp
                else -> 13.6.dp
            }), enabled = enabled, icon = startAction,
            summary = summary?.let { { com.itos.coui.text.Text(text = it, kind = CouiTextKind.PreferenceSummary,
                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)) } },
            trailing = trailing) {
            com.itos.coui.text.Text(text = title, kind = CouiTextKind.PreferenceTitle,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else .38f))
        }
        if (showDivider) ColorPreferenceDivider()
    }
}

@Composable
internal fun InterfaceSwitchPreference(
    checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: (@Composable () -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    switchColors: SwitchColors = SwitchDefaults.switchColors(),
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    holdDownState: Boolean = false, enabled: Boolean = true,
) {
    if (LocalAppInterfaceStyle.current == AppInterfaceStyle.HYPER) {
        top.yukonga.miuix.kmp.preference.SwitchPreference(checked, onCheckedChange, title, modifier, titleColor,
            summary, summaryColor, startAction, endActions, bottomAction, switchColors, insideMargin, holdDownState, enabled)
    } else Column {
        ColorPreferenceRow(title, summary, modifier, enabled, startAction = startAction,
            toggleState = checked, onToggleChange = onCheckedChange,
            showDivider = bottomAction == null,
            trailing = {
                Row { endActions() }
                com.itos.coui.switch.Switch(checked = checked, onCheckedChange = null, enabled = enabled,
                    onDragCheckedChange = onCheckedChange)
            })
        bottomAction?.invoke()
        if (bottomAction != null) ColorPreferenceDivider()
    }
}

@Composable
internal fun InterfaceArrowPreference(
    title: String, modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: (@Composable () -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null, holdDownState: Boolean = false, enabled: Boolean = true,
) {
    if (LocalAppInterfaceStyle.current == AppInterfaceStyle.HYPER) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(title, modifier, titleColor, summary, summaryColor,
            startAction, endActions, bottomAction, insideMargin, onClick, holdDownState, enabled)
    } else Column {
        ColorPreferenceRow(title, summary, modifier, enabled, onClick, startAction,
            trailing = {
            Row { endActions() }
            Image(MiuixIcons.Regular.ChevronForward, null, Modifier.padding(start = 8.dp).size(18.dp),
                colorFilter = ColorFilter.tint(CouiTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)))
            }, showDivider = bottomAction == null)
        bottomAction?.invoke()
        if (bottomAction != null) ColorPreferenceDivider()
    }
}
