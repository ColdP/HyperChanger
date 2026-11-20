// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val IOS_LAUNCH_VISIBILITY_THRESHOLD = 0.0002f
private const val IOS_OPEN_STIFFNESS = 340f
private const val IOS_CLOSE_STIFFNESS = 390f

private val iosOpenSpring = spring<Float>(
    dampingRatio = 0.94f,
    stiffness = IOS_OPEN_STIFFNESS,
    visibilityThreshold = IOS_LAUNCH_VISIBILITY_THRESHOLD,
)

private val iosCloseSpring = spring<Float>(
    dampingRatio = 0.96f,
    stiffness = IOS_CLOSE_STIFFNESS,
    visibilityThreshold = IOS_LAUNCH_VISIBILITY_THRESHOLD,
)

private val iosOffsetSpring = spring<Offset>(
    dampingRatio = 0.92f,
    stiffness = IOS_CLOSE_STIFFNESS,
)

internal enum class AppLaunchOrigin { MAIN, DISABLED }

internal class AppLaunchSource(
    val unscaledBounds: () -> Rect?,
    val backgroundScale: () -> Float,
    val layer: GraphicsLayer,
    val cornerRadiusPx: Float,
    val isGridIcon: Boolean,
    val isSquircle: Boolean,
)

internal class IosAppLaunchSession(
    val id: Long,
    val packageName: String,
    val origin: AppLaunchOrigin,
    var source: AppLaunchSource,
    val fallbackUnscaledBounds: Rect,
) {
    val progress = Animatable(0f, visibilityThreshold = IOS_LAUNCH_VISIBILITY_THRESHOLD)
    val gestureOffset = Animatable(Offset.Zero, Offset.VectorConverter)
    var closing by mutableStateOf(false)
    var gesturing by mutableStateOf(false)
    var releaseVelocity = 0f
    private var activeJob: Job? = null

    fun stopAnimations() {
        activeJob?.cancel()
        activeJob = null
    }

    fun launch(scope: CoroutineScope) {
        val currentVelocity = progress.velocity
        stopAnimations()
        closing = false
        gesturing = false
        activeJob = scope.launch {
            coroutineScope {
                launch { progress.animateTo(1f, iosOpenSpring, initialVelocity = currentVelocity) }
                launch { gestureOffset.animateTo(Offset.Zero, iosOffsetSpring) }
            }
        }
    }

    fun close(scope: CoroutineScope, initialVelocity: Float = 0f, onFinished: (IosAppLaunchSession) -> Unit) {
        val velocity = if (initialVelocity != 0f) initialVelocity else progress.velocity
        stopAnimations()
        closing = true
        gesturing = false
        activeJob = scope.launch {
            val clampedVelocity = velocity.coerceIn(
                -sqrt(IOS_CLOSE_STIFFNESS) * progress.value.coerceAtLeast(0f),
                sqrt(IOS_CLOSE_STIFFNESS) * 0.25f,
            )
            coroutineScope {
                launch { progress.animateTo(0f, iosCloseSpring, initialVelocity = clampedVelocity) }
                launch { gestureOffset.animateTo(Offset.Zero, iosOffsetSpring) }
            }
            if (closing) onFinished(this@IosAppLaunchSession)
        }
    }

    suspend fun recoverAfterCancelledGesture() {
        if (closing) return
        val velocity = releaseVelocity.coerceIn(-1f, sqrt(IOS_OPEN_STIFFNESS) * 0.3f)
        coroutineScope {
            launch { progress.animateTo(1f, iosOpenSpring, initialVelocity = velocity) }
            launch { gestureOffset.animateTo(Offset.Zero, iosOffsetSpring) }
        }
    }
}

private data class IosScreenCorners(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
)

@Composable
private fun rememberIosScreenCorners(): IosScreenCorners {
    val view = LocalView.current
    val fallback = with(LocalDensity.current) { 28.dp.toPx() }
    fun read(): IosScreenCorners {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return IosScreenCorners(fallback, fallback, fallback, fallback)
        }
        val insets = view.rootWindowInsets ?: return IosScreenCorners(fallback, fallback, fallback, fallback)
        fun radius(position: Int) = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
        return IosScreenCorners(
            radius(RoundedCorner.POSITION_TOP_LEFT),
            radius(RoundedCorner.POSITION_TOP_RIGHT),
            radius(RoundedCorner.POSITION_BOTTOM_RIGHT),
            radius(RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }
    var corners by remember(view, fallback) { mutableStateOf(read()) }
    DisposableEffect(view, fallback) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { corners = read() }
        val observer = view.viewTreeObserver
        observer.addOnGlobalLayoutListener(listener)
        corners = read()
        onDispose { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener) }
    }
    return corners
}

private fun lerpFloat(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

private fun lerpRect(start: Rect, end: Rect, progress: Float): Rect = Rect(
    lerpFloat(start.left, end.left, progress),
    lerpFloat(start.top, end.top, progress),
    lerpFloat(start.right, end.right, progress),
    lerpFloat(start.bottom, end.bottom, progress),
)

internal fun scaleRectAroundCenter(rect: Rect, center: Offset, scale: Float): Rect {
    if (scale == 1f) return rect
    return Rect(
        center.x + (rect.left - center.x) * scale,
        center.y + (rect.top - center.y) * scale,
        center.x + (rect.right - center.x) * scale,
        center.y + (rect.bottom - center.y) * scale,
    )
}

internal fun unscaleRectAroundCenter(rect: Rect, center: Offset, scale: Float): Rect {
    if (scale == 1f || scale <= 0.05f) return rect
    return Rect(
        center.x + (rect.left - center.x) / scale,
        center.y + (rect.top - center.y) / scale,
        center.x + (rect.right - center.x) / scale,
        center.y + (rect.bottom - center.y) / scale,
    )
}

private fun smoothStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun Path.iosMorphMask(rect: Rect, corners: IosScreenCorners, squircle: Float) {
    reset()
    val limit = min(rect.width, rect.height).coerceAtLeast(0f) / 2f
    val extension = lerpFloat(1f, 1.1f, squircle)
    val control = lerpFloat(0.5522848f, 0.643f, squircle)
    val tl = (corners.topLeft * extension).coerceIn(0f, limit)
    val tr = (corners.topRight * extension).coerceIn(0f, limit)
    val br = (corners.bottomRight * extension).coerceIn(0f, limit)
    val bl = (corners.bottomLeft * extension).coerceIn(0f, limit)
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    moveTo(l + tl, t)
    lineTo(r - tr, t)
    cubicTo(r - tr * (1f - control), t, r, t + tr * (1f - control), r, t + tr)
    lineTo(r, b - br)
    cubicTo(r, b - br * (1f - control), r - br * (1f - control), b, r - br, b)
    lineTo(l + bl, b)
    cubicTo(l + bl * (1f - control), b, l, b - bl * (1f - control), l, b - bl)
    lineTo(l, t + tl)
    cubicTo(l, t + tl * (1f - control), l + tl * (1f - control), t, l + tl, t)
    close()
}

@Composable
internal fun IosAppLaunchPredictiveBack(
    activeSession: IosAppLaunchSession?,
    enabled: Boolean,
    maxProgress: Float = 0.9f,
    onCommitDismiss: (IosAppLaunchSession) -> Unit,
) {
    val currentSession by rememberUpdatedState(activeSession)
    val currentCommit by rememberUpdatedState(onCommitDismiss)
    val density = LocalDensity.current
    val maxDx = with(density) { 26.dp.toPx() }
    val maxDy = with(density) { 28.dp.toPx() }
    PredictiveBackHandler(enabled = enabled && activeSession != null && !activeSession.closing) { events ->
        val session = currentSession ?: return@PredictiveBackHandler
        session.stopAnimations()
        val startProgress = session.progress.value.coerceIn(0.05f, 1f)
        session.releaseVelocity = 0f
        session.gesturing = true
        var firstTouchY: Float? = null
        var lastProgress = startProgress
        var lastTimeNs = 0L
        try {
            events.collect { event ->
                val baseTouchY = firstTouchY ?: event.touchY
                if (firstTouchY == null) firstTouchY = baseTouchY
                val p = event.progress.coerceIn(0f, 1f)
                val next = (startProgress * (1f - 0.44f * maxProgress.coerceIn(0.1f, 1f) * p)).coerceIn(0.05f, 1f)
                val dir = if (event.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1f else -1f
                val dx = dir * p * maxDx
                val dy = ((event.touchY - baseTouchY) * 0.22f).coerceIn(-maxDy, maxDy)
                val now = System.nanoTime()
                if (lastTimeNs != 0L) {
                    val seconds = (now - lastTimeNs) / 1_000_000_000f
                    if (seconds > 0.001f) {
                        val inst = (next - lastProgress) / seconds
                        session.releaseVelocity = (session.releaseVelocity * 0.6f + inst * 0.4f).coerceIn(-4f, 4f)
                    }
                }
                lastTimeNs = now
                lastProgress = next
                session.progress.snapTo(next)
                session.gestureOffset.snapTo(Offset(dx, dy))
            }
            session.gesturing = false
            currentCommit(session)
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                session.gesturing = false
                session.recoverAfterCancelledGesture()
            }
        } finally {
            session.gesturing = false
        }
    }
}

@Composable
internal fun IosAppLaunchOverlay(
    session: IosAppLaunchSession,
    rootSize: androidx.compose.ui.unit.IntSize,
    coveredBlurDp: Float,
    content: @Composable () -> Unit,
) {
    if (rootSize.width <= 0 || rootSize.height <= 0) return
    val p = session.progress.value.coerceIn(0f, 1f)
    if (p <= 0.0005f && session.closing) return

    val density = LocalDensity.current
    val screenCorners = rememberIosScreenCorners()
    val fallbackCardRadius = with(density) { 26.dp.toPx() }
    val clipPath = remember { Path() }
    val surfaceColor = MiuixTheme.colorScheme.surface

    val fullBounds = Rect(0f, 0f, rootSize.width.toFloat(), rootSize.height.toFloat())
    val center = Offset(fullBounds.width * 0.5f, fullBounds.height * 0.5f)
    val baseUnscaled = session.source.unscaledBounds() ?: session.fallbackUnscaledBounds
    val targetSourceBounds = scaleRectAroundCenter(baseUnscaled, center, session.source.backgroundScale())
    val rect = lerpRect(targetSourceBounds, fullBounds, p).translate(session.gestureOffset.value * p)

    val minFlightRadius = if (p < 0.992f) fallbackCardRadius * (1f - p) else 0f
    val corners = IosScreenCorners(
        lerpFloat(session.source.cornerRadiusPx, maxOf(screenCorners.topLeft, minFlightRadius), p),
        lerpFloat(session.source.cornerRadiusPx, maxOf(screenCorners.topRight, minFlightRadius), p),
        lerpFloat(session.source.cornerRadiusPx, maxOf(screenCorners.bottomRight, minFlightRadius), p),
        lerpFloat(session.source.cornerRadiusPx, maxOf(screenCorners.bottomLeft, minFlightRadius), p),
    )

    val containerSurfaceAlpha = if (session.source.isGridIcon) smoothStep(0f, 0.08f, p) else 1f
    val detailAlpha = smoothStep(0.06f, 0.38f, p)
    val detailBlur = 14f * (1f - smoothStep(0.06f, 0.56f, p))
    val iconAlpha = 1f - smoothStep(0.02f, 0.30f, p)
    val iconBlur = 6f * smoothStep(0.04f, 0.30f, p)

    // iOS 26 / macOS-inspired 3D perspective tilt peaking mid-flight around the window center.
    val normX = ((targetSourceBounds.center.x - center.x) / (fullBounds.width * 0.5f)).coerceIn(-1f, 1f)
    val normY = ((targetSourceBounds.center.y - center.y) / (fullBounds.height * 0.5f)).coerceIn(-1f, 1f)
    val tiltEnvelope = sin(PI * p.toDouble().pow(0.85)).toFloat().coerceIn(0f, 1f)
    val maxGestureDx = with(density) { 26.dp.toPx() }.coerceAtLeast(1f)
    val maxGestureDy = with(density) { 28.dp.toPx() }.coerceAtLeast(1f)
    val gestureNormX = (session.gestureOffset.value.x / maxGestureDx).coerceIn(-1f, 1f)
    val gestureNormY = (session.gestureOffset.value.y / maxGestureDy).coerceIn(-1f, 1f)
    val tiltX = normY * tiltEnvelope * 5.5f - gestureNormY * 3.5f * p
    val tiltY = normX * tiltEnvelope * 7.5f + gestureNormX * 4.5f * p
    val tiltZ = gestureNormX * 1.4f * p

    Box(
        Modifier
            .fillMaxSize()
            .then(if (coveredBlurDp > 0.05f) Modifier.blur(coveredBlurDp.dp) else Modifier)
            .graphicsLayer {
                val pivotX = (rect.center.x / fullBounds.width).coerceIn(0f, 1f)
                val pivotY = (rect.center.y / fullBounds.height).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(pivotX, pivotY)
                rotationX = tiltX
                rotationY = tiltY
                rotationZ = tiltZ
                cameraDistance = 22f
            }
            .drawWithContent {
                clipPath.iosMorphMask(
                    rect = rect,
                    corners = corners,
                    squircle = if (session.source.isSquircle) 1f - p * 0.65f else 0f,
                )
                drawContext.canvas.save()
                drawContext.canvas.clipPath(clipPath)
                if (containerSurfaceAlpha > 0.005f) {
                    drawRect(
                        color = surfaceColor.copy(alpha = containerSurfaceAlpha),
                        topLeft = rect.topLeft,
                        size = rect.size,
                    )
                }
                drawContent()
                drawContext.canvas.restore()
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = (rect.width / fullBounds.width).coerceAtLeast(0.01f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = rect.left
                    translationY = rect.top
                    alpha = detailAlpha
                }
                .then(if (detailBlur > 0.05f) Modifier.blur(detailBlur.dp) else Modifier),
        ) {
            content()
        }
        if (iconAlpha > 0.005f) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = iconAlpha }
                    .then(if (iconBlur > 0.05f) Modifier.blur(iconBlur.dp) else Modifier),
            ) {
                val layer = session.source.layer
                if (layer.size.width > 0 && layer.size.height > 0 && !layer.isReleased) {
                    val scale = rect.width / layer.size.width
                    val scaledHeight = layer.size.height * scale
                    val drawTop = if (session.source.isGridIcon) {
                        rect.top + (rect.height - scaledHeight) * 0.5f * smoothStep(0f, 0.30f, p)
                    } else {
                        rect.top
                    }
                    drawContext.canvas.save()
                    drawContext.canvas.translate(rect.left, drawTop)
                    drawContext.canvas.scale(scale, scale)
                    drawLayer(layer)
                    drawContext.canvas.restore()
                }
            }
        }
    }
}
