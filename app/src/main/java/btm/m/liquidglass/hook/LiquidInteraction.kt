// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.liquidglass.hook

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

// Adapted from the Backdrop catalog's Apache-2.0 LiquidBottomTabs interaction helpers.
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedRange<Float>,
    visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStopped: DampedDragAnimation.() -> Unit,
    private val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    private val awaitTargetBeforeRelease: Boolean = true,
    private val followFingerImmediately: Boolean = false,
    private val onTap: (DampedDragAnimation.(size: IntSize, position: Offset) -> Unit)? = null,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)
    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)
    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            ignoreConsumed = followFingerImmediately,
            onDragStart = {
                velocityTracker.resetTracking()
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onTap = { change ->
                onTap?.invoke(this@DampedDragAnimation, size, change.position)
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
            onDrag = { _, dragAmount -> onDrag(size, dragAmount) }
        )
    }

    private fun press() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    private fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (awaitTargetBeforeRelease && value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            if (followFingerImmediately) {
                valueAnimation.snapTo(target)
                return@launch
            }
            valueAnimation.animateTo(target, valueAnimationSpec) {
                velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(this.value, 0f))
                val range = valueRange.endInclusive - valueRange.start
                val targetVelocity = velocityTracker.calculateVelocity().x / range
                animationScope.launch {
                    velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec)
                }
            }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                launch { valueAnimation.animateTo(value.coerceIn(valueRange), valueAnimationSpec) }
                if (velocity != 0f) launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                release()
            }
        }
    }
}

internal class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val radiusMultiplier: Float = 1.5f,
    private val surfaceAlpha: Float = 0.08f,
    private val falloffMultiplier: Float = 0.5f,
    private val position: (size: Size, offset: Offset) -> Offset,
) {
    private val pressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero
    private val shader = if (isRuntimeShaderSupported()) RuntimeShader(
        """
        uniform float2 size;
        layout(color) uniform half4 color;
        uniform float radius;
        uniform float falloffMultiplier;
        uniform float2 position;
        half4 main(float2 coord) {
            float dist = distance(coord, position);
            float intensity = smoothstep(radius, radius * falloffMultiplier, dist);
            return color * intensity;
        }
        """.trimIndent()
    ) else null

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressAnimation.value
        if (progress > 0f) {
            if (shader != null) {
                drawRect(Color.White.copy(alpha = surfaceAlpha * progress), blendMode = BlendMode.Plus)
                val highlightPosition = position(size, positionAnimation.value)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setColorUniform("color", Color.White.copy(alpha = 0.15f * progress))
                shader.setFloatUniform("radius", size.minDimension * radiusMultiplier)
                shader.setFloatUniform("falloffMultiplier", falloffMultiplier)
                shader.setFloatUniform(
                    "position",
                    highlightPosition.x.fastCoerceIn(0f, size.width),
                    highlightPosition.y.fastCoerceIn(0f, size.height)
                )
                drawRect(ShaderBrush(shader.asComposeShader()), blendMode = BlendMode.Plus)
            } else {
                drawCircle(
                    color = Color.White.copy(alpha = 0.15f * progress),
                    radius = size.minDimension * radiusMultiplier,
                    center = position(size, positionAnimation.value),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            // Tab clickables consume the gesture in the Main pass. This observer must still
            // receive the stream so the touch highlight can follow every press.
            ignoreConsumed = true,
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressAnimation.animateTo(1f, pressSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
            onDrag = { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        )
    }

    private fun release() {
        animationScope.launch {
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { positionAnimation.animateTo(startPosition, positionSpec) }
        }
    }
}

private suspend fun PointerInputScope.inspectDragGestures(
    ignoreConsumed: Boolean = false,
    onDragStart: (PointerInputChange) -> Unit,
    onDragEnd: (PointerInputChange) -> Unit,
    onTap: (PointerInputChange) -> Unit = onDragEnd,
    onDragCancel: () -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        var totalDrag = Offset.Zero
        val up = drag(initialDown.id, ignoreConsumed) {
            val delta = if (ignoreConsumed) it.position - it.previousPosition else it.positionChange()
            totalDrag += delta
            onDrag(it, delta)
        }
        if (up == null) {
            onDragCancel()
        } else if (totalDrag.getDistance() < viewConfiguration.touchSlop) {
            onTap(up)
        } else {
            onDragEnd(up)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    ignoreConsumed: Boolean = false,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (!ignoreConsumed && change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) {
            val other = event.changes.fastFirstOrNull { it.pressed }
            if (other == null) return change
            pointer = other.id
        } else if (change.previousPosition != change.position) {
            onDrag(change)
            pointer = change.id
        }
    }
}
