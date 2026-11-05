// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.view.RoundedCorner
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared concentric-corner calculation used by contributor cards and search controls. */
@Composable
internal fun rememberBottomScreenCornerRadius(inset: Dp, position: Int? = null): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    var radius by remember(view) { mutableStateOf(28.dp) }
    DisposableEffect(view, density, orientation, inset, position) {
        var disposed = false
        fun updateRadius() {
            if (disposed) return
            val insets = view.rootWindowInsets
            val corners = if (position != null) listOfNotNull(insets?.getRoundedCorner(position)) else listOfNotNull(
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT),
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT),
            )
            val pixels = corners.maxOfOrNull { it.radius }
            if (pixels != null) {
                // Concentric with the device corner after accounting for the card's side inset.
                radius = with(density) { pixels.toDp() }.minus(inset).coerceAtLeast(0.dp)
            }
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRadius() }
        view.addOnLayoutChangeListener(listener)
        view.post { updateRadius() }
        onDispose {
            disposed = true
            view.removeOnLayoutChangeListener(listener)
        }
    }
    return radius
}

