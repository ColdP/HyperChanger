// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import btm.m.os4.systemuihook.InterfaceArrowPreference as ArrowPreference
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Progress maps to a screen-sized rect; keep threshold tight so the final pixels never snap.
private const val SEAMLESS_VISIBILITY_THRESHOLD = 0.0002f
private const val SEAMLESS_OPEN_STIFFNESS = 340f
private const val SEAMLESS_CLOSE_STIFFNESS = 390f

private val seamlessOpenSpring = spring<Float>(
    dampingRatio = 0.94f,
    stiffness = SEAMLESS_OPEN_STIFFNESS,
    visibilityThreshold = SEAMLESS_VISIBILITY_THRESHOLD,
)

private val seamlessCloseSpring = spring<Float>(
    dampingRatio = 0.96f,
    stiffness = SEAMLESS_CLOSE_STIFFNESS,
    visibilityThreshold = SEAMLESS_VISIBILITY_THRESHOLD,
)

private val seamlessOffsetSpring = spring<Offset>(
    dampingRatio = 0.92f,
    stiffness = SEAMLESS_CLOSE_STIFFNESS,
)

/** One source and one animation owner per navigation entry, within the existing Activity. */
internal class SeamlessSource(
    val level: Int,
    val unscaledBounds: () -> Rect?,
    val unscaledCardBounds: () -> Rect?,
    val backgroundScale: () -> Float,
    val layer: GraphicsLayer,
    val card: SeamlessCardGeometry?,
)

internal data class RegisteredSeamlessTarget(
    val level: Int,
    val source: SeamlessSource,
    val onClick: () -> Unit,
)

private data class SeamlessCorners(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    constructor(radius: Float) : this(radius, radius, radius, radius)
}

/** Insets are already adjusted for the current window; do not reuse one top corner for all four. */
@Composable
private fun rememberScreenCorners(): SeamlessCorners {
    val view = LocalView.current
    val fallback = with(LocalDensity.current) { 28.dp.toPx() }
    fun read(): SeamlessCorners {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return SeamlessCorners(fallback)
        val insets = view.rootWindowInsets ?: return SeamlessCorners(fallback)
        fun radius(position: Int) = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
        return SeamlessCorners(
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

private fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress

private fun mix(start: Rect, end: Rect, progress: Float) = Rect(
    mix(start.left, end.left, progress),
    mix(start.top, end.top, progress),
    mix(start.right, end.right, progress),
    mix(start.bottom, end.bottom, progress),
)

/** Match the card's continuous corners at the source and circular screen corners at the endpoint. */
private fun Path.seamlessMask(rect: Rect, corners: SeamlessCorners, squircle: Float) {
    reset()
    val limit = min(rect.width, rect.height).coerceAtLeast(0f) / 2f
    val extension = mix(1f, 1.1f, squircle)
    val control = mix(.5522848f, .643f, squircle)
    val tl = (corners.topLeft * extension).coerceIn(0f, limit)
    val tr = (corners.topRight * extension).coerceIn(0f, limit)
    val br = (corners.bottomRight * extension).coerceIn(0f, limit)
    val bl = (corners.bottomLeft * extension).coerceIn(0f, limit)
    val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom
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

internal class SeamlessEntry(
    val id: Long,
    var source: SeamlessSource,
    val fallbackUnscaledBounds: Rect,
) {
    val progress = Animatable(0f, visibilityThreshold = SEAMLESS_VISIBILITY_THRESHOLD)
    val gestureOffset = Animatable(Offset.Zero, Offset.VectorConverter)
    var gesturing by mutableStateOf(false)
    var closing by mutableStateOf(false)
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
                launch { progress.animateTo(1f, seamlessOpenSpring, initialVelocity = currentVelocity) }
                launch { gestureOffset.animateTo(Offset.Zero, seamlessOffsetSpring) }
            }
        }
    }

    fun close(scope: CoroutineScope, initialVelocity: Float = 0f, onFinished: (SeamlessEntry) -> Unit) {
        val velocity = if (initialVelocity != 0f) initialVelocity else progress.velocity
        stopAnimations()
        closing = true
        gesturing = false
        activeJob = scope.launch {
            val clampedVelocity = velocity.coerceIn(
                -sqrt(SEAMLESS_CLOSE_STIFFNESS) * progress.value.coerceAtLeast(0f),
                sqrt(SEAMLESS_CLOSE_STIFFNESS) * 0.25f,
            )
            coroutineScope {
                launch { progress.animateTo(0f, seamlessCloseSpring, initialVelocity = clampedVelocity) }
                launch { gestureOffset.animateTo(Offset.Zero, seamlessOffsetSpring) }
            }
            if (closing) onFinished(this@SeamlessEntry)
        }
    }

    suspend fun recoverAfterCancelledGesture() {
        if (closing) return
        val velocity = releaseVelocity.coerceIn(-1f, sqrt(SEAMLESS_OPEN_STIFFNESS) * 0.3f)
        coroutineScope {
            launch { progress.animateTo(1f, seamlessOpenSpring, initialVelocity = velocity) }
            launch { gestureOffset.animateTo(Offset.Zero, seamlessOffsetSpring) }
        }
    }
}

internal class SeamlessNavigationState {
    var rootCoordinates: LayoutCoordinates? = null
    val levelScales = mutableStateMapOf<Int, Float>()
    val entries = mutableStateMapOf<Int, SeamlessEntry>()
    val targets = mutableStateMapOf<Any, RegisteredSeamlessTarget>()
    var pendingSource: SeamlessSource? = null
    private var nextId = 1L

    fun rootBounds(): Rect? = rootCoordinates?.takeIf { it.isAttached }?.boundsInWindow()

    fun setLevelScale(level: Int, scale: Float) {
        if (levelScales[level] != scale) levelScales[level] = scale
    }

    fun scaleForLevel(level: Int): Float = levelScales[level] ?: 1f

    fun createEntry(enabled: Boolean): SeamlessEntry? {
        val source = pendingSource
        val bounds = if (enabled) source?.unscaledBounds() else null
        return if (source != null && bounds != null && bounds.width > 0f && bounds.height > 0f) {
            SeamlessEntry(nextId++, source, bounds)
        } else {
            null
        }
    }
}

internal val LocalSeamlessNavigation = compositionLocalOf<SeamlessNavigationState?> { null }
internal val LocalSeamlessLevel = compositionLocalOf { 0 }

/** Interpolate source rect into full page with iOS 26 / macOS tilt, blur, and interruptible motion. */
@Composable
internal fun SeamlessPageFrame(
    entry: SeamlessEntry?,
    rootSize: IntSize,
    coveredBlurDp: Float = 0f,
    coveredScale: Float = 1f,
    coveredCornerDp: Float = 0f,
    coveredDimAlpha: Float = 0f,
    content: @Composable () -> Unit,
) {
    if (entry == null) {
        content()
        return
    }
    if (rootSize.width <= 0 || rootSize.height <= 0) return
    val p = entry.progress.value.coerceIn(0f, 1f)
    if (p <= 0.0005f && entry.closing) return

    val density = LocalDensity.current
    val screenCorners = rememberScreenCorners()
    val fallbackCardRadius = with(density) { 26.dp.toPx() }
    val clipPath = remember { Path() }
    val surfaceColor = MiuixTheme.colorScheme.surface

    val fullBounds = Rect(0f, 0f, rootSize.width.toFloat(), rootSize.height.toFloat())
    val center = Offset(fullBounds.width * 0.5f, fullBounds.height * 0.5f)
    val bgScale = entry.source.backgroundScale()
    val baseUnscaled = entry.source.unscaledBounds() ?: entry.fallbackUnscaledBounds
    val targetSourceBounds = scaleRectAroundCenter(baseUnscaled, center, bgScale)
    val rect = mix(targetSourceBounds, fullBounds, p).translate(entry.gestureOffset.value * p)

    val card = entry.source.card
    val sourceRadius = card?.radius ?: with(density) { 18.dp.toPx() }
    val cardUnscaled = entry.source.unscaledCardBounds() ?: baseUnscaled
    val targetCardBounds = scaleRectAroundCenter(cardUnscaled, center, bgScale)
    // Start with the actual slice of the card: only its first/last rows intersect its corners.
    // Collapse the extra card area smoothly, so the other two corners emerge without a jump.
    val sourceMask = mix(targetCardBounds, targetSourceBounds, seamlessSmoothStep(0f, 0.2f, p))
    val maskBounds = mix(sourceMask, fullBounds, p).translate(entry.gestureOffset.value * p)

    val minFlightRadius = if (p < 0.992f) fallbackCardRadius * (1f - p) else 0f
    val corners = SeamlessCorners(
        mix(sourceRadius, maxOf(screenCorners.topLeft, minFlightRadius), p),
        mix(sourceRadius, maxOf(screenCorners.topRight, minFlightRadius), p),
        mix(sourceRadius, maxOf(screenCorners.bottomRight, minFlightRadius), p),
        mix(sourceRadius, maxOf(screenCorners.bottomLeft, minFlightRadius), p),
    )

    val detailAlpha = seamlessSmoothStep(0.05f, 0.44f, p)
    val detailBlur = 14f * (1f - seamlessSmoothStep(0.05f, 0.56f, p))
    val sourceAlpha = 1f - seamlessSmoothStep(0.02f, 0.34f, p)
    val sourceBlur = 6f * seamlessSmoothStep(0.04f, 0.34f, p)

    // iOS 26 / macOS-inspired 3D perspective tilt peaking mid-flight around the morphing card center.
    val normX = ((targetSourceBounds.center.x - center.x) / (fullBounds.width * 0.5f)).coerceIn(-1f, 1f)
    val normY = ((targetSourceBounds.center.y - center.y) / (fullBounds.height * 0.5f)).coerceIn(-1f, 1f)
    val tiltEnvelope = sin(PI * p.toDouble().pow(0.85)).toFloat().coerceIn(0f, 1f)
    val maxGestureDx = with(density) { 26.dp.toPx() }.coerceAtLeast(1f)
    val maxGestureDy = with(density) { 28.dp.toPx() }.coerceAtLeast(1f)
    val gestureNormX = (entry.gestureOffset.value.x / maxGestureDx).coerceIn(-1f, 1f)
    val gestureNormY = (entry.gestureOffset.value.y / maxGestureDy).coerceIn(-1f, 1f)
    val tiltX = normY * tiltEnvelope * 4.5f - gestureNormY * 3.5f * p
    val tiltY = normX * tiltEnvelope * 6.5f + gestureNormX * 4.5f * p
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
                scaleX = coveredScale
                scaleY = coveredScale
            }
            .drawWithContent {
                val effectiveCorners = if (coveredCornerDp > 0.5f) {
                    val c = coveredCornerDp.dp.toPx()
                    SeamlessCorners(
                        maxOf(corners.topLeft, c),
                        maxOf(corners.topRight, c),
                        maxOf(corners.bottomRight, c),
                        maxOf(corners.bottomLeft, c),
                    )
                } else corners
                clipPath.seamlessMask(maskBounds, effectiveCorners, if (card?.squircle == true) 1f - p * 0.65f else 0f)
                drawContext.canvas.save()
                drawContext.canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
                drawContext.canvas.clipPath(clipPath)
                drawRect(color = surfaceColor, topLeft = rect.topLeft, size = rect.size)
                drawContent()
                if (coveredDimAlpha > 0.002f) {
                    drawRect(color = androidx.compose.ui.graphics.Color.Black.copy(alpha = coveredDimAlpha))
                }
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
        if (sourceAlpha > 0.005f) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = sourceAlpha }
                    .then(if (sourceBlur > 0.05f) Modifier.blur(sourceBlur.dp) else Modifier),
            ) {
                val layer = entry.source.layer
                if (layer.size.width > 0 && layer.size.height > 0 && !layer.isReleased) {
                    val scale = rect.width / layer.size.width
                    drawContext.canvas.save()
                    drawContext.canvas.translate(rect.left, rect.top)
                    drawContext.canvas.scale(scale, scale)
                    drawLayer(layer)
                    drawContext.canvas.restore()
                }
            }
        }
    }
}

private fun seamlessSmoothStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Commit removes the page only after close(); cancellation or mid-flight tap re-expands seamlessly. */
@Composable
internal fun SeamlessPredictiveBack(
    entry: SeamlessEntry?,
    enabled: Boolean,
    maxProgress: Float,
    onBack: () -> Unit,
) {
    val currentEntry by rememberUpdatedState(entry)
    val currentBack by rememberUpdatedState(onBack)
    val density = LocalDensity.current
    val maxDx = with(density) { 26.dp.toPx() }
    val maxDy = with(density) { 28.dp.toPx() }
    PredictiveBackHandler(enabled = enabled && entry != null && !entry.closing) { events ->
        val active = currentEntry ?: return@PredictiveBackHandler
        active.stopAnimations()
        val startProgress = active.progress.value.coerceIn(0.05f, 1f)
        active.releaseVelocity = 0f
        active.gesturing = true
        var firstTouchY: Float? = null
        var lastProgress = startProgress
        var lastTime = 0L
        try {
            events.collect { event ->
                val baseTouchY = firstTouchY ?: event.touchY
                if (firstTouchY == null) firstTouchY = baseTouchY
                val p = event.progress.coerceIn(0f, 1f)
                val next = (startProgress * (1f - 0.44f * maxProgress.coerceIn(0.1f, 1f) * p)).coerceIn(0.05f, 1f)
                val dir = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                val dx = dir * p * maxDx
                val dy = ((event.touchY - baseTouchY) * 0.22f).coerceIn(-maxDy, maxDy)
                val now = System.nanoTime()
                if (lastTime != 0L) {
                    val seconds = (now - lastTime) / 1_000_000_000f
                    if (seconds > 0.001f) {
                        val inst = (next - lastProgress) / seconds
                        active.releaseVelocity = (active.releaseVelocity * 0.6f + inst * 0.4f).coerceIn(-4f, 4f)
                    }
                }
                lastTime = now
                lastProgress = next
                active.progress.snapTo(next)
                active.gestureOffset.snapTo(Offset(dx, dy))
            }
            active.gesturing = false
            currentBack()
        } catch (_: CancellationException) {
            // The gesture flow is cancelled on abort; recovery still needs animation frames.
            withContext(NonCancellable) {
                active.gesturing = false
                active.recoverAfterCancelledGesture()
            }
        } finally {
            active.gesturing = false
        }
    }
}

/** Record a clicked row without an asynchronous screenshot or any ROM capability gate. */
@Composable
internal fun SharedViewArrowPreference(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val activity = LocalContext.current as? MainActivity
    val navigation = LocalSeamlessNavigation.current
    if (navigation == null || activity?.sharedViewAnimations?.enabled != true) {
        ArrowPreference(title = title, summary = summary, enabled = enabled, startAction = startAction, onClick = onClick)
        return
    }
    val level = LocalSeamlessLevel.current
    val layer = rememberGraphicsLayer()
    val card = LocalSeamlessCard.current
    val cardColor = card?.color ?: CardDefaults.defaultColors().color
    val sourceClip = remember { Path() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun unscaledWindowRect(windowRect: Rect?): Rect? {
        val target = windowRect ?: return null
        if (target.width <= 0f || target.height <= 0f) return null
        val root = navigation.rootBounds() ?: return target
        val local = target.translate(Offset(-root.left, -root.top))
        val center = Offset(root.width * 0.5f, root.height * 0.5f)
        return unscaleRectAroundCenter(local, center, navigation.scaleForLevel(level))
    }

    val source = remember(navigation, level, layer, card) {
        SeamlessSource(
            level = level,
            unscaledBounds = { unscaledWindowRect(coordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false)) },
            unscaledCardBounds = { unscaledWindowRect(card?.bounds()) },
            backgroundScale = { navigation.scaleForLevel(level) },
            layer = layer,
            card = card,
        )
    }

    val targetKey = remember { Any() }
    val currentOnClick by rememberUpdatedState(onClick)
    val invokeClick: () -> Unit = remember(navigation, source) {
        {
            navigation.pendingSource = source
            try { currentOnClick() } finally { navigation.pendingSource = null }
        }
    }

    DisposableEffect(navigation, level, source, enabled) {
        if (enabled) {
            navigation.targets[targetKey] = RegisteredSeamlessTarget(level, source, invokeClick)
        } else {
            navigation.targets.remove(targetKey)
        }
        onDispose { navigation.targets.remove(targetKey) }
    }

    Box(
        Modifier
            .onGloballyPositioned { coordinates = it }
            .drawWithContent {
                layer.record {
                    val rowBounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false)
                    val cardBounds = card?.bounds()
                    drawContext.canvas.save()
                    if (rowBounds != null && cardBounds != null) {
                        sourceClip.seamlessMask(
                            cardBounds.translate(Offset(-rowBounds.left, -rowBounds.top)),
                            SeamlessCorners(card.radius),
                            if (card.squircle) 1f else 0f,
                        )
                        drawContext.canvas.clipPath(sourceClip)
                    }
                    drawRect(cardColor)
                    this@drawWithContent.drawContent()
                    drawContext.canvas.restore()
                }
                drawLayer(layer)
            },
    ) {
        ArrowPreference(
            title = title,
            summary = summary,
            enabled = enabled,
            startAction = startAction,
            onClick = invokeClick,
        )
    }
}
