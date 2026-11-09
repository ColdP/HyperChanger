// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal class SeamlessCardGeometry {
    var coordinates: LayoutCoordinates? = null
    var radius by mutableFloatStateOf(0f)
    var color by mutableStateOf(Color.Transparent)
    var squircle by mutableStateOf(false)
    var bottomPaddingPx = 0f
    fun bounds() = coordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false)
}

internal val LocalSeamlessCard = staticCompositionLocalOf<SeamlessCardGeometry?> { null }

private val ColorCardCornerRadius = 22.5.dp

/** Forward the original card behavior while making its actual clip available to row snapshots. */
@Composable
internal fun SeamlessCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val geometry = remember { SeamlessCardGeometry() }
    val effectiveCornerRadius = if (LocalAppInterfaceStyle.current == AppInterfaceStyle.COLOR) {
        ColorCardCornerRadius
    } else cornerRadius
    geometry.radius = with(LocalDensity.current) { effectiveCornerRadius.toPx() }
    geometry.bottomPaddingPx = with(LocalDensity.current) { insideMargin.calculateBottomPadding().toPx() }
    geometry.color = colors.color
    geometry.squircle = isSquircleEnabled()
    CompositionLocalProvider(LocalSeamlessCard provides geometry) {
        val tracked = modifier.onGloballyPositioned { geometry.coordinates = it }
        if (LocalAppInterfaceStyle.current == AppInterfaceStyle.COLOR) {
            val shape = com.itos.coui.shape.CouiShape(ColorCardCornerRadius, 3f)
            Column(tracked.fillMaxWidth().clip(shape).background(colors.color)
                .then(if (onClick != null || onLongPress != null) Modifier.combinedClickable(
                    onClick = { onClick?.invoke() }, onLongClick = onLongPress) else Modifier)
                .padding(insideMargin), content = content)
            return@CompositionLocalProvider
        }
        if (onClick == null && onLongPress == null && pressFeedbackType == PressFeedbackType.None &&
            !showIndication && !holdDownState) {
            MiuixCard(tracked, cornerRadius, insideMargin, colors, content)
        } else {
            MiuixCard(tracked, cornerRadius, insideMargin, colors, pressFeedbackType, showIndication,
                holdDownState, onClick, onLongPress, content)
        }
    }
}
