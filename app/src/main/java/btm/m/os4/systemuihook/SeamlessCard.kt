// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.ColumnScope
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
    fun bounds() = coordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false)
}

internal val LocalSeamlessCard = staticCompositionLocalOf<SeamlessCardGeometry?> { null }

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
    geometry.radius = with(LocalDensity.current) { cornerRadius.toPx() }
    geometry.color = colors.color
    geometry.squircle = isSquircleEnabled()
    CompositionLocalProvider(LocalSeamlessCard provides geometry) {
        val tracked = modifier.onGloballyPositioned { geometry.coordinates = it }
        if (onClick == null && onLongPress == null && pressFeedbackType == PressFeedbackType.None &&
            !showIndication && !holdDownState) {
            MiuixCard(tracked, cornerRadius, insideMargin, colors, content)
        } else {
            MiuixCard(tracked, cornerRadius, insideMargin, colors, pressFeedbackType, showIndication,
                holdDownState, onClick, onLongPress, content)
        }
    }
}
