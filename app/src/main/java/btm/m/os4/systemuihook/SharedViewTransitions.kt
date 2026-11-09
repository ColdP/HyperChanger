// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import btm.m.os4.systemuihook.InterfaceArrowPreference as ArrowPreference
import kotlin.math.min
import kotlin.math.sqrt

private const val SEAMLESS_STIFFNESS = 170f
// Progress maps to a screen-sized rect. The default 0.01 threshold can snap tens of pixels.
private const val SEAMLESS_VISIBILITY_THRESHOLD = .0001f
private val seamlessSpring = spring<Float>(dampingRatio = 1f, stiffness = SEAMLESS_STIFFNESS,
    visibilityThreshold = SEAMLESS_VISIBILITY_THRESHOLD)

/** One source and one animation owner per navigation entry, within the existing Activity. */
internal class SeamlessSource(val bounds: () -> Rect?, val layer: GraphicsLayer, val card: SeamlessCardGeometry?)

private data class SeamlessCorners(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float) {
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
        return SeamlessCorners(radius(RoundedCorner.POSITION_TOP_LEFT), radius(RoundedCorner.POSITION_TOP_RIGHT),
            radius(RoundedCorner.POSITION_BOTTOM_RIGHT), radius(RoundedCorner.POSITION_BOTTOM_LEFT))
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

private fun mix(start: Rect, end: Rect, progress: Float) = Rect(mix(start.left, end.left, progress),
    mix(start.top, end.top, progress), mix(start.right, end.right, progress), mix(start.bottom, end.bottom, progress))

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

internal class SeamlessEntry(val source: SeamlessSource, val initialBounds: Rect) {
    val progress = Animatable(0f, visibilityThreshold = SEAMLESS_VISIBILITY_THRESHOLD)
    var gesturing by mutableStateOf(false)
    var closing by mutableStateOf(false)
    var returnBounds by mutableStateOf<Rect?>(null)
    var releaseVelocity = 0f
    var returnEffectOrigin by mutableStateOf<Float?>(null)

    suspend fun close(initialVelocity: Float = 0f, effectOrigin: Float? = null) {
        // Retain the gesture's target through commit; do not retarget the final approach.
        if (effectOrigin == null || returnBounds == null) returnBounds = source.bounds()
        // Geometry already follows the finger. Start visual exit at fully clear/opaque
        // on release, rather than jumping to the opacity of the partially closed rect.
        returnEffectOrigin = effectOrigin?.coerceAtLeast(.001f)
        closing = true
        progress.stop()
        // A critically damped spring can still cross its target with excessive initial velocity.
        val closingVelocity = initialVelocity.coerceIn(-sqrt(SEAMLESS_STIFFNESS) * progress.value.coerceAtLeast(0f), 0f)
        progress.animateTo(0f, seamlessSpring, initialVelocity = closingVelocity)
    }
}

internal class SeamlessNavigationState {
    val entries = mutableStateMapOf<Int, SeamlessEntry>()
    var pendingSource: SeamlessSource? = null
    var rootWasSeamless = false

    fun open(index: Int, enabled: Boolean) {
        val source = pendingSource
        val bounds = if (enabled) source?.bounds() else null
        if (index == 0) rootWasSeamless = bounds != null
        if (source != null && bounds != null && bounds.width > 0f && bounds.height > 0f) {
            entries[index] = SeamlessEntry(source, bounds)
        } else entries.remove(index)
    }
}

internal val LocalSeamlessNavigation = compositionLocalOf<SeamlessNavigationState?> { null }

/** Interpolate source rect into full page, including the inverse path for predictive back. */
@Composable
internal fun SeamlessPageFrame(
    entry: SeamlessEntry?,
    content: @Composable () -> Unit,
) {
    if (entry == null) { content(); return }
    var frameBounds by remember { mutableStateOf<Rect?>(null) }
    val progress = entry.progress.value.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val screenCorners = rememberScreenCorners()
    val clipPath = remember { Path() }
    LaunchedEffect(entry) {
        entry.progress.animateTo(1f, seamlessSpring)
    }
    val sourceBounds = entry.returnBounds ?: entry.initialBounds
    val full = frameBounds
    val sourceLocalBounds = full?.let { sourceBounds.translate(Offset(-it.left, -it.top)) }
    val bounds = full?.let {
        Rect(
            (sourceBounds.left - it.left) * (1f - progress),
            (sourceBounds.top - it.top) * (1f - progress),
            (sourceBounds.right - it.left) * (1f - progress) + it.width * progress,
            (sourceBounds.bottom - it.top) * (1f - progress) + it.height * progress,
        )
    }
    val card = entry.source.card
    val sourceRadius = card?.radius ?: with(density) { 18.dp.toPx() }
    val cardBounds = card?.bounds() ?: sourceBounds
    // Start with the actual slice of the card: only its first/last rows intersect its corners.
    // Collapse the extra card area smoothly, so the other two corners emerge without a jump.
    val sourceMask = mix(cardBounds, sourceBounds, seamlessSmoothStep(0f, .2f, progress))
    val maskBounds = full?.let { mix(sourceMask, it, progress).translate(Offset(-it.left, -it.top)) }
    val corners = SeamlessCorners(mix(sourceRadius, screenCorners.topLeft, progress),
        mix(sourceRadius, screenCorners.topRight, progress), mix(sourceRadius, screenCorners.bottomRight, progress),
        mix(sourceRadius, screenCorners.bottomLeft, progress))
    val effectProgress = when {
        entry.closing && entry.returnEffectOrigin != null -> (progress / entry.returnEffectOrigin!!).coerceIn(0f, 1f)
        entry.gesturing -> 1f
        else -> progress
    }
    val sourceAlpha = 1f - seamlessSmoothStep(.05f, .38f, effectProgress)
    val detailAlpha = seamlessSmoothStep(0f, 1f, effectProgress)
    Box(Modifier.fillMaxSize().onGloballyPositioned { frameBounds = it.boundsInWindow() }
        .drawWithContent {
            val rect = bounds ?: return@drawWithContent
            val mask = maskBounds ?: return@drawWithContent
            clipPath.seamlessMask(mask, corners, if (card?.squircle == true) 1f - progress else 0f)
            drawContext.canvas.save()
            drawContext.canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
            drawContext.canvas.clipPath(clipPath)
            drawContent()
            drawContext.canvas.restore()
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val rect = bounds
            if (rect != null && full.width > 0f) {
                val scale = rect.width / full.width
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = rect.left
                translationY = rect.top
            }
            alpha = detailAlpha
        }.blur((18f * ((1f - effectProgress) / .45f).coerceIn(0f, 1f)).dp)) { content() }
        if (sourceAlpha > 0f) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = sourceAlpha }
                .blur((5f * (1f - sourceAlpha)).dp)) {
                // Crossfade in place with the real source row. Centering this layer inside
                // the shrinking page made it appear to land, then slide upward a second time.
                val rect = sourceLocalBounds
                if (rect != null) {
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
}

private fun seamlessSmoothStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Commit removes the page only after close(); cancellation expands the same page again. */
@Composable
internal fun SeamlessPredictiveBack(entry: SeamlessEntry?, enabled: Boolean, maxProgress: Float, onBack: () -> Unit) {
    val currentBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled && entry != null && !entry.closing) { events ->
        val active = entry ?: return@PredictiveBackHandler
        active.progress.stop()
        val startProgress = active.progress.value
        active.releaseVelocity = 0f
        var lastProgress = startProgress
        var lastTime = 0L
        active.returnBounds = active.source.bounds()
        active.gesturing = true
        try {
            events.collect { event ->
                // Use the entire gesture range, reserving the final collapse for commit.
                // Clamping raw gesture progress to maxProgress made motion stop early.
                val next = startProgress * (1f - .45f * maxProgress.coerceIn(.1f, 1f) * event.progress.coerceIn(0f, 1f))
                val now = System.nanoTime()
                if (lastTime != 0L) {
                    val seconds = (now - lastTime) / 1_000_000_000f
                    if (seconds > .001f) active.releaseVelocity =
                        (active.releaseVelocity * .65f + (next - lastProgress) / seconds * .35f).coerceIn(-3f, 3f)
                }
                lastTime = now
                lastProgress = next
                active.progress.snapTo(next)
            }
            currentBack()
        } catch (_: CancellationException) {
            // The gesture flow is cancelled on abort; recovery still needs animation frames.
            withContext(NonCancellable) {
                if (!active.closing) active.progress.animateTo(1f, seamlessSpring,
                    initialVelocity = active.releaseVelocity)
            }
        } finally { active.gesturing = false }
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
    val layer = rememberGraphicsLayer()
    val card = LocalSeamlessCard.current
    val cardColor = card?.color ?: CardDefaults.defaultColors().color
    val sourceClip = remember { Path() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val source = remember(layer, card) {
        SeamlessSource({ coordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false)?.takeIf { it.width > 0f && it.height > 0f } }, layer, card)
    }
    Box(Modifier.onGloballyPositioned { coordinates = it }.drawWithContent {
        layer.record {
            val rowBounds = source.bounds()
            val cardBounds = card?.bounds()
            drawContext.canvas.save()
            if (rowBounds != null && cardBounds != null) {
                sourceClip.seamlessMask(cardBounds.translate(Offset(-rowBounds.left, -rowBounds.top)),
                    SeamlessCorners(card.radius), if (card.squircle) 1f else 0f)
                drawContext.canvas.clipPath(sourceClip)
            }
            drawRect(cardColor)
            this@drawWithContent.drawContent()
            drawContext.canvas.restore()
        }
        drawLayer(layer)
    }) {
        ArrowPreference(title = title, summary = summary, enabled = enabled, startAction = startAction, onClick = {
            navigation.pendingSource = source
            try { onClick() } finally { navigation.pendingSource = null }
        })
    }
}
