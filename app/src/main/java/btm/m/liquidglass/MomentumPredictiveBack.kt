// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.liquidglass

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private val momentumRecoverSpring = spring<Float>(
    dampingRatio = 0.88f,
    stiffness = 360f,
    visibilityThreshold = 0.0005f,
)

@Stable
internal class MomentumBackState {
    private val animProgress = Animatable(0f, visibilityThreshold = 0.0005f)
    private val animVerticalOffset = Animatable(0f)

    val progress: Float
        get() = animProgress.value
    var gestureProgress by mutableFloatStateOf(0f)
        private set
    val verticalOffsetPx: Float
        get() = animVerticalOffset.value
    var isSwiping by mutableStateOf(false)
        private set
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set
    private var hasGestureEvent = false
    private var firstTouchY: Float? = null
    private var activeJob: Job? = null

    // A button/key back can complete with an empty event flow. It must retain
    // the default exit instead of reusing a previous gesture's edge.
    val exitDirection: Int
        get() = if (isSwiping && hasGestureEvent && swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1

    internal fun stopAnimations() {
        activeJob?.cancel()
        activeJob = null
    }

    internal suspend fun reset() {
        stopAnimations()
        animProgress.snapTo(0f)
        animVerticalOffset.snapTo(0f)
        gestureProgress = 0f
        isSwiping = false
        hasGestureEvent = false
        firstTouchY = null
    }

    internal fun begin() {
        stopAnimations()
        hasGestureEvent = false
        firstTouchY = null
        isSwiping = true
    }

    internal suspend fun update(event: BackEventCompat, maxProgress: Float) {
        hasGestureEvent = true
        val baseY = firstTouchY ?: event.touchY
        if (firstTouchY == null) firstTouchY = baseY
        val p = min(event.progress, maxProgress).coerceIn(0f, 1f)
        animProgress.snapTo(p)
        animVerticalOffset.snapTo(event.touchY - baseY)
        gestureProgress = p
        swipeEdge = event.swipeEdge
    }

    internal fun commit(scope: CoroutineScope) {
        stopAnimations()
        activeJob = scope.launch {
            animVerticalOffset.animateTo(0f, momentumRecoverSpring)
        }
    }

    internal suspend fun recoverAfterCancel() {
        stopAnimations()
        try {
            coroutineScope {
                launch { animProgress.animateTo(0f, momentumRecoverSpring) }
                launch { animVerticalOffset.animateTo(0f, momentumRecoverSpring) }
            }
        } finally {
            gestureProgress = 0f
            isSwiping = false
            hasGestureEvent = false
            firstTouchY = null
        }
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
    val scope = rememberCoroutineScope()

    PredictiveBackHandler(enabled = enabled) { events ->
        state.begin()
        var committed = false
        try {
            events.collect { event -> state.update(event, maxProgress.coerceIn(0.1f, 1f)) }
            committed = true
            state.commit(scope)
            latestOnBack()
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                if (!committed) state.recoverAfterCancel()
            }
        } finally {
            if (!committed && !state.isSwiping) {
                withContext(NonCancellable) { state.reset() }
            }
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
    val maxFollowY = with(density) { 80.dp.toPx() }.coerceAtLeast(1f)
    val clampedFollowY = state.verticalOffsetPx.coerceIn(-maxFollowY, maxFollowY)
    val followNormY = (clampedFollowY / maxFollowY).coerceIn(-1f, 1f)
    val translationX = direction * progress * with(density) { 44.dp.toPx() }
    val translationY = progress * with(density) { 8.dp.toPx() } + clampedFollowY * 0.45f
    val tiltY = direction * progress * 7.5f
    val tiltX = -followNormY * 4.5f * (0.35f + 0.65f * progress)
    val tiltZ = direction * progress * 1.4f + followNormY * direction * 0.8f

    return graphicsLayer {
        transformOrigin = TransformOrigin.Center
        scaleX = scale
        scaleY = scale
        this.translationX = translationX
        this.translationY = translationY
        rotationX = tiltX
        rotationY = tiltY
        rotationZ = tiltZ
        cameraDistance = 22f
    }.clip(RoundedCornerShape(deviceCorner * progress))
}
