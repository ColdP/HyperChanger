// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedCornerStyle
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import com.materialkolor.palettes.CorePalette
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.blur as backdropGaussianBlur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.drawBackdrop as miuixDrawBackdrop
import top.yukonga.miuix.kmp.blur.blur as miuixGaussianBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ContributorGalleryEntry(
    val name: String,
    val avatarUrl: String,
    val githubLink: String,
    val description: String,
)

internal data class ContributorArtwork(val bitmap: Bitmap?, val seed: Int)

internal class ContributorGalleryState {
    val artwork = mutableStateMapOf<String, ContributorArtwork>()
    var centeredIndex by mutableIntStateOf(0)
}

internal data class ContributorAmbientColors(val primary: Color, val secondary: Color, val tertiary: Color, val base: Color)

/** Axial hex coordinates, filled from the origin outwards in complete rings. */
private fun contributorHexPositions(count: Int): List<Offset> = buildList {
    if (count == 0) return@buildList
    add(Offset.Zero)
    val directions = listOf(1 to 0, 0 to 1, -1 to 1, -1 to 0, 0 to -1, 1 to -1)
    var ring = 1
    while (size < count) {
        var q = 0
        var r = -ring
        for ((dq, dr) in directions) {
            repeat(ring) {
                if (size < count) add(Offset(q + r * .5f, r * sqrt(3f) * .5f))
                q += dq
                r += dr
            }
        }
        ring++
    }
}

private fun contributorMonetSeed(bitmap: Bitmap?): Int {
    val fallback = 0xFF6750A4.toInt()
    if (bitmap == null) return fallback
    val pixels = ArrayList<Int>(4096)
    for (y in 0 until 64) for (x in 0 until 64) {
        val pixel = bitmap.getPixel(x * bitmap.width / 64, y * bitmap.height / 64)
        if (android.graphics.Color.alpha(pixel) >= 128) pixels.add(pixel or 0xFF000000.toInt())
    }
    if (pixels.isEmpty()) return fallback
    return Score.score(QuantizerCelebi.quantize(pixels.toIntArray(), 128), 1, fallback, true).firstOrNull() ?: fallback
}

@Composable
internal fun rememberContributorGalleryState(
    contributors: List<ContributorGalleryEntry>,
    loadAvatar: suspend (String) -> Bitmap?,
): ContributorGalleryState {
    val state = remember(contributors) { ContributorGalleryState() }
    val currentLoader by rememberUpdatedState(loadAvatar)
    LaunchedEffect(contributors) {
        // Keep requests bounded, including for a large remote contributor list.
        val permits = kotlinx.coroutines.sync.Semaphore(4)
        contributors.distinctBy { it.avatarUrl }.forEach { contributor ->
            launch {
                permits.acquire()
                try {
                    val bitmap = currentLoader(contributor.avatarUrl)
                    val seed = withContext(Dispatchers.Default) { contributorMonetSeed(bitmap) }
                    state.artwork[contributor.avatarUrl] = ContributorArtwork(bitmap, seed)
                } finally {
                    permits.release()
                }
            }
        }
    }
    return state
}

@Composable
internal fun contributorAmbientColors(
    contributors: List<ContributorGalleryEntry>,
    state: ContributorGalleryState,
): ContributorAmbientColors {
    val active = contributors.getOrNull(state.centeredIndex)
    val seed = active?.let { state.artwork[it.avatarUrl]?.seed } ?: 0xFF6750A4.toInt()
    val palette = remember(seed) { CorePalette.of(seed) }
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val tone = if (dark) 70 else 80
    val primary by animateColorAsState(Color(palette.a1.tone(tone)), tween(1000), label = "ambientMonetPrimary")
    val secondary by animateColorAsState(Color(palette.a2.tone(tone)), tween(1000), label = "ambientMonetSecondary")
    val tertiary by animateColorAsState(Color(palette.a3.tone(tone)), tween(1000), label = "ambientMonetTertiary")
    val base by animateColorAsState(Color(palette.n1.tone(if (dark) 8 else 97)), tween(1000), label = "ambientMonetBase")
    return ContributorAmbientColors(primary, secondary, tertiary, base)
}

@Composable
internal fun ContributorsGallery(
    contributors: List<ContributorGalleryEntry>,
    state: ContributorGalleryState,
    ambientBackdrop: LayerBackdrop,
    openProfile: (String) -> Unit,
    availableHeight: Dp,
) {
    if (contributors.isEmpty()) return
    val positions = remember(contributors.size) { contributorHexPositions(contributors.size) }
    val active = contributors[state.centeredIndex]
    val density = LocalDensity.current
    val cardInset = maxOf(18.dp, with(density) { WindowInsets.navigationBars.getBottom(this).toDp() })
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(
            Modifier.widthIn(max = 512.dp).fillMaxWidth().height(availableHeight),
        ) {
            val previousCardHeight = (maxWidth - 32.dp).coerceAtLeast(1.dp) * (16f / 9f)
            val minimumInfoHeight = 92.dp + 72.dp * LocalDensity.current.fontScale
            val infoHeight = maxOf((previousCardHeight * .38f + 10.dp) * .62f, minimumInfoHeight)
            val galleryHeight = (maxHeight - infoHeight - cardInset * 2).coerceAtLeast(120.dp)
            Box(Modifier.fillMaxWidth().height(galleryHeight).clip(RectangleShape)) {
                ContributorHoneycomb(
                    contributors, positions, state.artwork, state.centeredIndex, ambientBackdrop,
                    onCentered = { state.centeredIndex = it }, modifier = Modifier.fillMaxSize(),
                )
            }
            ContributorDetails(
                active, ambientBackdrop, openProfile, cardInset,
                Modifier.align(Alignment.BottomCenter).padding(cardInset).fillMaxWidth()
                    .height(infoHeight),
            )
        }
    }
}

@Composable
internal fun ContributorAmbientBackground(colors: ContributorAmbientColors, modifier: Modifier = Modifier) {
    val flow = rememberInfiniteTransition(label = "contributorAmbientFlow")
    val phase by flow.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Restart), label = "ambientOrbit",
    )
    Canvas(modifier) {
        drawRect(colors.base)
        val centers = listOf(
            Offset(size.width * (.22f + .18f * sin(phase)), size.height * (.25f + .12f * cos(phase))),
            Offset(size.width * (.82f + .13f * cos(phase)), size.height * (.5f + .23f * sin(phase))),
            Offset(size.width * (.35f + .22f * cos(phase)), size.height * (.94f + .12f * sin(phase))),
        )
        val orbs = listOf(colors.primary, colors.secondary, colors.tertiary)
        centers.forEachIndexed { index, center ->
            drawCircle(
                Brush.radialGradient(
                    listOf(orbs[index].copy(alpha = .56f), Color.Transparent),
                    center = center, radius = size.width * .95f,
                ),
                radius = size.width * .95f, center = center,
            )
        }
    }
}

@Composable
private fun ContributorHoneycomb(
    contributors: List<ContributorGalleryEntry>,
    positions: List<Offset>,
    artwork: Map<String, ContributorArtwork>,
    centeredIndex: Int,
    ambientBackdrop: LayerBackdrop,
    onCentered: (Int) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    var translation by remember(contributors) { mutableStateOf(Offset.Zero) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val currentOnCentered by rememberUpdatedState(onCentered)
    val spacing = with(density) { 150.dp.toPx() }
    val avatarSize = 78.dp * 1.3f
    val avatarRadius = with(density) { avatarSize.toPx() / 2 }
    val points = remember(positions, spacing) { positions.map { it * spacing } }
    fun nearest(): Int = points.indices.minBy { (points[it] + translation).getDistanceSquared() }
    fun center(index: Int, velocity: Offset = Offset.Zero) {
        settling?.cancel()
        settling = scope.launch {
            Animatable(translation, Offset.VectorConverter).animateTo(
                -points[index],
                spring(dampingRatio = .82f, stiffness = 240f, visibilityThreshold = Offset(.4f, .4f)),
                initialVelocity = velocity,
            ) { translation = value }
        }
    }
    // The selected contributor follows the actual nearest avatar, including during a spring.
    val nearestIndex by remember(points, contributors) { derivedStateOf { nearest() } }
    LaunchedEffect(nearestIndex) { currentOnCentered(nearestIndex) }
    DisposableEffect(contributors, points) { onDispose { settling?.cancel() } }
    val sceneBackdrop = rememberMiuixLayerBackdrop()
    val capsuleShape = remember { Capsule(RoundedCornerStyle.Continuous) }
    val capsuleTint = MiuixTheme.colorScheme.surface.copy(alpha = .12f)
    val textMeasurer = rememberTextMeasurer()
    val nameStyle = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(modifier.clip(RectangleShape)) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val capsuleMaxWidth = maxWidth * .75f
        // Keep some breathing room between the centered name capsule and the details card.
        val viewportCenter = Offset(viewportWidth / 2f, viewportHeight * .48f)
        Box(Modifier.fillMaxSize().contributorSoftEdges()) {
            Box(Modifier.fillMaxSize().miuixLayerBackdrop(sceneBackdrop)) {
                Box(Modifier.matchParentSize().drawPlainBackdrop(ambientBackdrop, shape = { RectangleShape }, effects = {}))
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(points, viewportCenter, contributors) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                settling?.cancel()
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                var travel = 0f
                                var lastPosition = down.position
                                var cancelled = false
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || change.isConsumed) { cancelled = true; break }
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        lastPosition = change.position
                                        if (!change.pressed) break
                                        val delta = change.positionChange()
                                        // Direct pixel displacement: no easing, resistance or coroutine lag while dragging.
                                        translation += delta
                                        travel += delta.getDistance()
                                        if (delta != Offset.Zero) change.consume()
                                    }
                                } finally {
                                    val tapped = if (!cancelled && travel < touchSlop) {
                                        points.indices.minByOrNull {
                                            (points[it] + translation + viewportCenter - lastPosition).getDistanceSquared()
                                        }?.takeIf {
                                            (points[it] + translation + viewportCenter - lastPosition).getDistance() <= avatarRadius
                                        }
                                    } else null
                                    val velocity = tracker.calculateVelocity()
                                    center(tapped ?: nearest(), if (tapped != null || cancelled) Offset.Zero else
                                        Offset(velocity.x.coerceIn(-3500f, 3500f), velocity.y.coerceIn(-3500f, 3500f)))
                                }
                            }
                        },
                ) {
                    contributors.forEachIndexed { index, contributor ->
                        val displacement = points[index] + translation
                        val distance = displacement.getDistance() / spacing
                        val focus = (1f - distance / 2.4f).coerceIn(0f, 1f)
                        val smoothFocus = focus * focus * (3f - 2f * focus)
                        val scale = .6f + .4f * smoothFocus
                        val blurRadius = (1f - smoothFocus) * 9f
                        // Avoid allocating blur layers for avatars completely outside the clipped viewport.
                        if (abs(displacement.x) < viewportWidth / 2f + avatarRadius &&
                            abs(displacement.y) < viewportHeight * .6f + avatarRadius) {
                            Box(
                                Modifier.size(avatarSize).graphicsLayer {
                                    translationX = viewportCenter.x + displacement.x - avatarRadius
                                    translationY = viewportCenter.y + displacement.y - avatarRadius
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = .2f + .8f * smoothFocus
                                }.clip(CircleShape)
                                    .semantics {
                                        role = Role.Button
                                        selected = index == centeredIndex
                                        contentDescription = tr("contributors_focus", "居中查看 %s").format(contributor.name)
                                        onClick { center(index); true }
                                    }.focusable(),
                            ) {
                                MiuixBlurredContent(blurRadius) {
                                    Box(
                                        Modifier.size(avatarSize).background(MiuixTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val bitmap = artwork[contributor.avatarUrl]?.bitmap
                                        if (bitmap != null) {
                                            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Text(tr(contributor.name, contributor.name).take(1).uppercase(), fontSize = (26f * 1.3f).sp)
                                        }
                                    }
                                }
                                Box(Modifier.matchParentSize().border(1.5.dp, Color.White.copy(alpha = .25f), CircleShape))
                            }
                        }
                    }
                }
            }
            // Smooth radial masks blend MIUIX Gaussian levels over the entire scene.
            // There are no wedge boundaries or rectangular blur bands for avatars to cross.
            listOf(3f, 6f, 10f).forEachIndexed { level, radius ->
                Box(Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val start = .28f + level * .16f
                        val end = .62f + level * .16f
                        val mask = Brush.radialGradient(
                            0f to Color.Transparent, start to Color.Transparent,
                            end to Color.White, 1f to Color.White,
                            center = viewportCenter, radius = size.minDimension * .8f,
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(mask, blendMode = BlendMode.DstIn)
                        }
                    }.textureBlur(sceneBackdrop, RectangleShape, blurRadius = radius, noiseCoefficient = 0f))
            }
        }
        val name = tr(contributors[centeredIndex].name, contributors[centeredIndex].name)
        val targetNameWidth = with(density) { textMeasurer.measure(name, style = nameStyle).size.width.toDp() } + 32.dp
        val capsuleWidth by animateDpAsState(
            targetNameWidth.coerceAtMost(capsuleMaxWidth),
            spring(dampingRatio = .9f, stiffness = 260f), label = "contributorNameCapsuleWidth",
        )
        Box(Modifier.fillMaxWidth().offset(y = maxHeight * .48f + avatarSize / 2 + 13.dp), contentAlignment = Alignment.TopCenter) {
            // One persistent glass shell; only the text crossfades/blurs while its width springs.
            Box(Modifier.width(capsuleWidth).height(34.dp)
                .clip(capsuleShape).drawBackdrop(
                    ambientBackdrop, shape = { capsuleShape },
                    effects = { lens(14.dp.toPx(), 26.dp.toPx(), depthEffect = true, chromaticAberration = true) },
                    highlight = { Highlight.Default.copy(alpha = .5f) },
                    shadow = null, innerShadow = null,
                    onDrawSurface = { drawRect(capsuleTint) },
                ).padding(horizontal = 16.dp, vertical = 7.dp)) {
                ContributorCrossfade(centeredIndex, Modifier.fillMaxSize(), Alignment.Center) { index, blur ->
                    BackdropBlurredText(blur, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                tr(contributors[index].name, contributors[index].name),
                                style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.contributorSoftEdges(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    val vertical = Brush.verticalGradient(
        0f to Color.Transparent, .14f to Color.White, .86f to Color.White, 1f to Color.Transparent,
    )
    val horizontal = Brush.horizontalGradient(
        0f to Color.Transparent, .12f to Color.White, .88f to Color.White, 1f to Color.Transparent,
    )
    onDrawWithContent {
        drawContent()
        drawRect(vertical, blendMode = BlendMode.DstIn)
        drawRect(horizontal, blendMode = BlendMode.DstIn)
    }
}

/** Capture foreground content without drawing a sharp copy underneath the MIUIX Gaussian pass. */
@Composable
private fun MiuixBlurredContent(radius: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val source = rememberGraphicsLayer()
    val backdrop = rememberMiuixLayerBackdrop { drawLayer(source) }
    val supported = isRuntimeShaderSupported()
    // The transparent guard prevents clamped glyph-edge pixels from filling the capture rectangle.
    // Report the original content size so neither text nor its layout scales during a transition.
    Box(modifier.then(Modifier.layout { measurable, constraints ->
        val guard = 12.dp.roundToPx()
        val placeable = measurable.measure(constraints.offset(guard * 2, guard * 2))
        layout((placeable.width - guard * 2).coerceAtLeast(0), (placeable.height - guard * 2).coerceAtLeast(0)) {
            placeable.place(-guard, -guard)
        }
    })) {
        Box(Modifier.miuixLayerBackdrop(backdrop).drawWithContent {
            // DrawScope.record retargets drawContent's canvas into the source layer.
            source.record {
                this@drawWithContent.drawContent()
            }
            if (!supported) drawLayer(source)
        }.padding(12.dp)) { content() }
        if (supported) Box(Modifier.matchParentSize().miuixDrawBackdrop(
            backdrop, shape = { RectangleShape }, effects = { miuixGaussianBlur(radius.coerceAtLeast(0f).dp.toPx()) },
        ))
    }
}

/** Native Backdrop Gaussian blur with transparent edges; no texture downsampling or color effects. */
@Composable
private fun BackdropBlurredText(radius: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val source = rememberGraphicsLayer()
    val backdrop = rememberLayerBackdrop { drawLayer(source) }
    Box(modifier.then(Modifier.layout { measurable, constraints ->
        val guard = 12.dp.roundToPx()
        val placeable = measurable.measure(constraints.offset(guard * 2, guard * 2))
        layout((placeable.width - guard * 2).coerceAtLeast(0), (placeable.height - guard * 2).coerceAtLeast(0)) {
            placeable.place(-guard, -guard)
        }
    })) {
        Box(Modifier.layerBackdrop(backdrop).drawWithContent {
            source.record { this@drawWithContent.drawContent() }
        }.padding(12.dp)) { content() }
        Box(Modifier.matchParentSize().drawPlainBackdrop(
            backdrop = backdrop, shape = { RectangleShape },
            effects = { backdropGaussianBlur(radius.coerceAtLeast(0f).dp.toPx(), TileMode.Decal) },
        ))
    }
}

@Composable
private fun <T> ContributorCrossfade(
    target: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable (T, Float) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            (fadeIn(tween(320)) togetherWith fadeOut(tween(320))).using(null)
        },
        label = "contributorCrossfade",
    ) { value ->
        val blur by transition.animateFloat(transitionSpec = { tween(320) }, label = "contributorMiuixBlur") {
            if (it == EnterExitState.Visible) 0f else 3f
        }
        content(value, blur)
    }
}

@Composable
private fun rememberContributorCardRadius(inset: Dp): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    var radius by remember(view) { mutableStateOf(28.dp) }
    DisposableEffect(view, density, orientation, inset) {
        var disposed = false
        fun updateRadius() {
            if (disposed) return
            val insets = view.rootWindowInsets
            val corners = listOfNotNull(
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

@Composable
private fun ContributorDetails(
    contributor: ContributorGalleryEntry,
    backdrop: LayerBackdrop,
    openProfile: (String) -> Unit,
    cardInset: Dp,
    modifier: Modifier,
) {
    val cardRadius = rememberContributorCardRadius(cardInset)
    val shape = remember(cardRadius) { RoundedRectangle(cardRadius, RoundedCornerStyle.Continuous) }
    val buttonShape = remember { Capsule(RoundedCornerStyle.Continuous) }
    val surface = MiuixTheme.colorScheme.surface
    val foreground = MiuixTheme.colorScheme.onSurface
    BoxWithConstraints(
        modifier.clip(shape).drawBackdrop(
            backdrop = backdrop, shape = { shape },
            // Backdrop supplies the liquid glass lens; foreground/edge blur uses MIUIX.
            effects = { vibrancy(); lens(16.dp.toPx(), 30.dp.toPx(), depthEffect = true, chromaticAberration = true) },
            highlight = { Highlight.Default.copy(alpha = .65f) },
            shadow = { Shadow.Default.copy(radius = 14.dp, color = Color.Black, alpha = .12f) },
            innerShadow = null,
            onDrawSurface = { drawRect(surface.copy(alpha = .24f)) },
        ),
    ) {
        val descriptionLines = if (maxHeight >= 160.dp) 2 else 1
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
            ContributorCrossfade(contributor.name to contributor.description, Modifier.weight(1f).fillMaxWidth()) { entry, blur ->
                BackdropBlurredText(blur, Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Text(tr(entry.first, entry.first), fontSize = 21.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                            Text(tr(entry.second, entry.second), fontSize = 12.sp, lineHeight = 17.sp,
                                color = foreground.copy(alpha = .7f), maxLines = descriptionLines, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // This button persists across contributors; only its destination changes.
            UpdateToolbarButton(
                onClick = { openProfile(contributor.githubLink) },
                text = tr("contributors_github", "前往 GitHub"),
                modifier = Modifier.fillMaxWidth(), enabled = contributor.githubLink.isNotBlank(),
                backdrop = backdrop, shape = buttonShape, surfaceColor = surface.copy(alpha = .18f), blurRadius = 0.dp,
                allowDrag = false,
                leadingIcon = {
                    Image(painterResource(R.drawable.github_octicons_mark), null, Modifier.size(18.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(foreground))
                },
            )
        }
    }
}
