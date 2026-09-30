// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.liquidglass

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

@Stable
internal class MomentumBackState {
    var progress by mutableFloatStateOf(0f)
        private set
    var gestureProgress by mutableFloatStateOf(0f)
        private set
    var isSwiping by mutableStateOf(false)
        private set
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set

    internal fun reset() {
        progress = 0f
        gestureProgress = 0f
        isSwiping = false
    }

    internal fun begin() {
        isSwiping = true
    }

    internal fun update(event: BackEventCompat, maxProgress: Float) {
        progress = min(event.progress, maxProgress)
        gestureProgress = progress
        swipeEdge = event.swipeEdge
    }

    internal fun commit() {
        progress = 1f
    }
}

@Composable
internal fun rememberMomentumPredictiveBack(
    enabled: Boolean,
    onBack: () -> Unit,
    maxProgress: Float = 0.9f,
    resetDelayMillis: Long = 280L
): MomentumBackState {
    val state = remember { MomentumBackState() }
    val latestOnBack by rememberUpdatedState(onBack)

    PredictiveBackHandler(enabled = enabled) { events ->
        state.begin()
        var committed = false
        try {
            events.collect { event -> state.update(event, maxProgress.coerceIn(0.1f, 1f)) }
            committed = true
            state.commit()
            latestOnBack()
        } catch (_: CancellationException) {
            state.reset()
        } finally {
            if (!committed) state.reset()
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            delay(resetDelayMillis)
            state.reset()
        }
    }
    return state
}

@Composable
private fun rememberDeviceCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var radius by remember { mutableStateOf(28.dp) }
    LaunchedEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var insets = view.rootWindowInsets
            if (insets == null) {
                delay(100)
                insets = view.rootWindowInsets
            }
            val pixels = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            if (pixels > 0) radius = with(density) { pixels.toDp() }
        }
    }
    return radius
}

@Composable
internal fun Modifier.momentumBackTransform(state: MomentumBackState): Modifier {
    val density = LocalDensity.current
    val deviceCorner = rememberDeviceCornerRadius()
    val progress = if (state.isSwiping) state.progress.coerceIn(0f, 1f) else 0f
    val direction = if (state.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
    val scale = 1f - progress * 0.12f

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = direction * progress * with(density) { 48.dp.toPx() }
        translationY = progress * with(density) { 16.dp.toPx() }
    }.clip(RoundedCornerShape(deviceCorner * progress))
}
