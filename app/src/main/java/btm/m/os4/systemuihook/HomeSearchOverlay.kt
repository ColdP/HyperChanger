// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur as contentBlur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import btm.m.liquidglass.hook.InteractiveHighlight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.sin

internal val SEARCH_GLASS_BLUR = 8.dp
internal val SEARCH_GLASS_REFRACTION = 40.dp

internal data class HomeSearchResult(
    val key: String,
    val title: String,
    val description: String,
    val scopes: String,
    val requiresService: Boolean = true,
    val onClick: () -> Unit,
)

private data class SearchResultsState(val query: String, val searching: Boolean, val results: List<HomeSearchResult>)

/** Only the result layer is recorded. The header and exit control consume that sibling. */
@Composable
internal fun HomeSearchOverlay(
    expanded: Boolean,
    primaryButton: Boolean = false,
    navigationStyle: String,
    interactionEnabled: Boolean = true,
    anchorInWindow: Rect,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<HomeSearchResult>,
    searching: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    onProgress: (Float) -> Unit,
) {
    val motion = remember { Animatable(0f) }
    val latestOnClosed by rememberUpdatedState(onClosed)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(expanded) {
        if (!expanded) { focus.clearFocus(); keyboard?.hide() }
        motion.animateTo(if (expanded) 1f else 0f, tween(480, easing = FastOutSlowInEasing))
        if (!expanded) latestOnClosed()
    }
    BackHandler(enabled = interactionEnabled) { onDismiss() }
    val base = LocalToolbarBackdrop.current ?: rememberLayerBackdrop()
    val resultsBackdrop = rememberLayerBackdrop()
    val foregroundBackdrop = rememberCombinedBackdrop(base, resultsBackdrop)
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val searchTitle = tr("category_search_title", "搜索")
    val progress = motion.value
    SideEffect { onProgress(progress) }
    val contentAlpha = searchPhase(progress, .08f, .95f)
    val tint = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .30f)
    val deviceCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
            .semantics { paneTitle = searchTitle },
    ) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val fieldTop = topInset + 64.dp
        val fieldWidth = minOf(maxWidth - 32.dp, 560.dp)
        val fieldLeft = (maxWidth - fieldWidth) / 2
        val source = anchorInWindow.translate(-origin)
        val buttonSize = with(density) { source.minDimension.toDp() }
        // Same concentric-radius rule as the contributor card, applied to a circle.
        // Preserve a safe inset when the window has square corners or no corner metadata.
        val cornerInset = maxOf(18.dp, navInset, deviceCorner - buttonSize / 2)
        val targetLeft = maxWidth - cornerInset - buttonSize
        val targetTop = maxHeight - cornerInset - buttonSize
        val sourceLeft = with(density) { source.center.x.toDp() } - buttonSize / 2
        val sourceTop = with(density) { source.center.y.toDp() } - buttonSize / 2
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .10f * progress))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            .clearAndSetSemantics {})
        // The scroll viewport starts at the screen top. Padding sets the initial position;
        // scrolling cards continue behind the header instead of hitting a hard viewport edge.
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = contentAlpha
                translationY = (1f - contentAlpha) * 18.dp.toPx()
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .contentBlur(((1f - contentAlpha) * 6f).dp)
            .drawWithContent {
                drawContent()
                val start = with(density) { (fieldTop - 24.dp).toPx() } / size.height
                val end = with(density) { (fieldTop + 72.dp).toPx() } / size.height
                drawRect(Brush.verticalGradient(
                    0f to Color.Transparent,
                    start.coerceIn(0f, 1f) to Color.Transparent,
                    end.coerceIn(0f, 1f) to Color.White,
                    1f to Color.White,
                ), blendMode = BlendMode.DstIn)
            }
            // Capture before the outer fade mask. Glass sees the cards as they
            // travel behind the header while the visible list fades out softly.
            .layerBackdrop(resultsBackdrop)) {
            AnimatedContent(
                targetState = SearchResultsState(query, searching, results),
                contentKey = { Triple(it.query, it.searching, it.results.map { result -> result.key }) },
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { with(density) { 18.dp.roundToPx() } })
                        .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { with(density) { -8.dp.roundToPx() } })
                        .using(SizeTransform(clip = false))
                }, label = "searchResults",
            ) { snapshot ->
                val listState = rememberLazyListState()
                val resultBlur by transition.animateFloat(transitionSpec = { tween(260) }, label = "searchResultBlur") {
                    if (it == EnterExitState.Visible) 0f else 6f
                }
                LazyColumn(
                    Modifier.fillMaxSize().imePadding().contentBlur(resultBlur.dp),
                    state = listState,
                    userScrollEnabled = expanded && contentAlpha > .98f,
                    contentPadding = PaddingValues(start = fieldLeft, end = fieldLeft,
                        top = fieldTop + 76.dp, bottom = 144.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (snapshot.query.isBlank() || (!snapshot.searching && snapshot.results.isEmpty())) {
                        item("message") {
                            SearchGlassCard(base) {
                                Text(
                                    when {
                                        snapshot.query.isBlank() -> tr("category_search_start", "输入功能名称、关键词或应用包名")
                                        else -> tr("category_search_no_results", "没有找到匹配的设置项")
                                    }, modifier = Modifier.padding(20.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    } else if (!snapshot.searching) items(snapshot.results, key = { it.key }) { result ->
                        SearchGlassCard(base) {
                            ArrowPreference(result.title, summary = result.description,
                                enabled = interactionEnabled && (enabled || !result.requiresService) && expanded && contentAlpha > .98f && snapshot.query == query, onClick = result.onClick)
                            Text(result.scopes,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
        // Unmasked cards remain available to the field and progressive header material.
        Box(Modifier.fillMaxWidth().height(fieldTop + 112.dp)
            .graphicsLayer { alpha = contentAlpha }
            .drawPlainBackdrop(
                backdrop = foregroundBackdrop, shape = { RectangleShape },
                effects = {
                    blur(3.6.dp.toPx())
                    runtimeShaderEffect("search-header-fade", SearchHeaderFadeShader, "content") {
                        setFloatUniform("height", size.height)
                        setFloatUniform("fadeHeight", 104.dp.toPx())
                    }
                },
            ))
        Text(searchTitle,
            modifier = Modifier.offset(y = topInset + 12.dp).fillMaxWidth().height(40.dp)
                .searchContentMotion(contentAlpha).wrapContentSize(Alignment.Center),
            style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.onSurface,
        )
        NavigationAppSearchField(
            query = query, onQueryChange = onQueryChange,
            modifier = Modifier.offset(fieldLeft, fieldTop).width(fieldWidth)
                .searchContentMotion(contentAlpha)
                .searchGlass(foregroundBackdrop, RoundedCornerShape(50), tint),
            hint = tr("category_search_placeholder", "搜索设置项名称、描述或作用域"),
            floating = false, drawSurface = false,
            requestFocus = interactionEnabled && expanded && progress > .96f, enabled = interactionEnabled && expanded && progress > .96f,
        )
        // One fixed circle moves between the two anchors; it never stretches or fuses.
        // Render it last so both its background and icon stay above every card.
        val buttonMotion = searchPhase(progress, 0f, 1f)
        val transitionBlur = sin(PI.toFloat() * progress).coerceAtLeast(0f)
        SearchRoundButton(
            backdrop = foregroundBackdrop, size = buttonSize, primary = primaryButton, navigationStyle = navigationStyle,
            closeProgress = searchPhase(progress, .15f, .85f),
            motionAlpha = 1f - .25f * transitionBlur,
            motionBlur = (transitionBlur * 4f).dp,
            modifier = Modifier.offset(
                sourceLeft + (targetLeft - sourceLeft) * buttonMotion,
                sourceTop + (targetTop - sourceTop) * buttonMotion,
            ),
            enabled = interactionEnabled && expanded, onClick = onDismiss,
        )
    }
}

private fun Modifier.searchContentMotion(alpha: Float): Modifier =
    graphicsLayer { this.alpha = alpha; translationY = (1f - alpha) * 18.dp.toPx() }
        .contentBlur(((1f - alpha) * 6f).dp)

/** Fixed square measurement and one circular surface for every search/close action. */
@Composable
internal fun SearchRoundButton(
    backdrop: Backdrop,
    size: Dp,
    primary: Boolean,
    closeProgress: Float,
    navigationStyle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    motionAlpha: Float = 1f,
    motionBlur: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val material = rememberModuleNavigationMaterial(navigationStyle)
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(120), label = "searchActionHighlight")
    val highlightAlpha = material.light(when (navigationStyle) {
        "liquid_glass" -> Highlight.Default.alpha
        "harmony_os_floating" -> (if (dark) .78f else .96f) * (.72f + .28f * press)
        "hyper_os_floating" -> if (dark) .78f else .96f
        else -> .70f
    })
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) { size, offset ->
        Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
    } }
    val liquid = navigationStyle == "liquid_glass"
    val harmony = navigationStyle == "harmony_os_floating"
    val floating = harmony || navigationStyle == "hyper_os_floating"
    val useEffects = isRuntimeShaderSupported()
    // Match CustomNavigation's container, including the material preference multipliers.
    // Search fields/cards deliberately keep their own searchGlass recipe.
    val baseTint = when {
        primary -> MiuixTheme.colorScheme.primary.copy(alpha = .42f)
        liquid -> (if (dark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha = .4f)
        !useEffects -> (if (dark) Color(0xFF242424) else Color(0xFFF5F5F5))
            .copy(alpha = if (dark) .88f else .82f)
        else -> (if (dark) Color.Black else Color(0xFFF5F5F5))
            .copy(alpha = if (harmony) { if (dark) .36f else .48f } else { if (dark) .30f else .42f })
    }
    val tint = baseTint.copy(alpha = material.alpha(baseTint.alpha))
    val surface = if (useEffects) Modifier.drawBackdrop(
        backdrop = backdrop, shape = { CircleShape },
        effects = {
            vibrancy()
            blur((if (liquid) 3f * 8f / 18f else 18f * if (harmony) .29f else .24f).dp.toPx() * material.blur / 100f)
            val boost = if (harmony) 1f + .3f * press * material.pressBoost / 100f else 1f
            lens((if (liquid) 24.dp else 16.dp).toPx() * boost * material.thickness / 100f,
                (if (liquid) 24.dp else 32.dp).toPx() * boost * material.refraction / 100f,
                depthEffect = !liquid, chromaticAberration = material.dispersion ?: false)
        },
        highlight = { Highlight.Default.copy(alpha = highlightAlpha) },
        shadow = { Shadow.Default }, innerShadow = null,
        onDrawSurface = { drawRect(tint) },
    ) else Modifier.background(tint, CircleShape)
    val outerShadow = if (floating) Modifier.shadow(
        elevation = 6.dp, shape = CircleShape, clip = false,
        ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f),
    ) else Modifier
    val iconColor = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
    val iconBlur = sin(PI.toFloat() * closeProgress).coerceAtLeast(0f) * 5f
    // Unbounded blur still records its input within the layer's rectangular bounds.
    // Reserve space BEFORE that recording for Shadow.Default (24dp blur, 4dp offset)
    // and the animated blur. Keep the measured anchor and interactive circle unchanged.
    val shadowSpace = 64.dp
    Box(modifier.requiredSize(size)) {
        Box(
            Modifier.matchParentSize().wrapContentSize(Alignment.Center, unbounded = true)
                .requiredSize(size + shadowSpace * 2)
                .graphicsLayer { alpha = motionAlpha }
                .contentBlur(motionBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .padding(shadowSpace)
                .then(outerShadow).then(surface)
                .clip(CircleShape).then(highlight.gestureModifier).then(highlight.modifier)
                .clickable(enabled = enabled, interactionSource = interactions, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MiuixIcons.Regular.Search, if (closeProgress < .5f) tr("category_search_open", "搜索设置") else null,
                Modifier.size(24.dp).graphicsLayer { alpha = 1f - closeProgress }.contentBlur(iconBlur.dp), tint = iconColor)
            Icon(MiuixIcons.Regular.Close, if (closeProgress >= .5f) tr("category_search_close", "关闭搜索") else null,
                Modifier.size(24.dp).graphicsLayer { alpha = closeProgress }.contentBlur(iconBlur.dp), tint = iconColor)
        }
    }
}

private fun Modifier.searchGlass(backdrop: Backdrop, shape: Shape, tint: Color, highlightAlpha: Float = .25f): Modifier =
    if (isRuntimeShaderSupported()) drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = { vibrancy(); blur(SEARCH_GLASS_BLUR.toPx()); lens(18.dp.toPx(), SEARCH_GLASS_REFRACTION.toPx(),
            depthEffect = true, chromaticAberration = false) },
        highlight = { Highlight.Default.copy(alpha = highlightAlpha) },
        shadow = { Shadow.Default.copy(radius = 8.dp, alpha = .12f) }, innerShadow = null,
        onDrawSurface = { drawRect(tint) },
    ) else background(tint, shape)

@Composable
private fun SearchGlassCard(backdrop: Backdrop, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.5.dp)
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) { size, offset ->
        Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
    } }
    val tint = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .34f)
    Card(modifier = Modifier.fillMaxWidth().searchGlass(backdrop, shape, tint)
        .clip(shape).then(highlight.gestureModifier).then(highlight.modifier),
        cornerRadius = 22.5.dp, colors = CardDefaults.defaultColors(color = Color.Transparent), content = content)
}

private const val SearchHeaderFadeShader = """
uniform shader content;
uniform float height;
uniform float fadeHeight;
half4 main(float2 coord) {
    return content.eval(coord) * half(smoothstep(height, height - fadeHeight, coord.y));
}
"""

internal fun searchPhase(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
