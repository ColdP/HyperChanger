// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.liquidglass.hook

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.AppColorMode
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop as drawMiuixBackdrop
import top.yukonga.miuix.kmp.blur.textureBlurEffect
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.abs
import kotlin.math.sign

data class HostTab(
    val label: String,
    val className: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit
)

data class MusicMiniPlayerState(
    val visible: Boolean = false,
    val title: String = "",
    val artwork: ImageBitmap? = null,
    val isPlaying: Boolean = false
)

private class NativeViewMiuixBackdrop(
    private val sourceView: View
) : MiuixBackdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(
        density: androidx.compose.ui.unit.Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int
    ) {
        val surfacePosition = coordinates?.positionInWindow() ?: return
        if (!sourceView.isAttachedToWindow || sourceView.width <= 0 || sourceView.height <= 0) return
        val sourcePosition = IntArray(2).also(sourceView::getLocationInWindow)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        try {
            val scale = 1f / downscaleFactor.coerceAtLeast(1)
            canvas.scale(scale, scale)
            canvas.translate(
                sourcePosition[0] - surfacePosition.x,
                sourcePosition[1] - surfacePosition.y
            )
            sourceView.draw(canvas)
        } finally {
            canvas.restore()
        }
    }
}

@Composable
fun CustomNavigation(
    sourceView: View,
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    blurRadius: Int,
    labelMode: String,
    navigationStyle: String,
    advancedMaterial: Boolean,
    colorMode: String,
    liquidBottomSpacingDp: Int = 8,
    concealHostBottomBar: Boolean = false,
    forceFloatingGlass: Boolean = false,
    accentColorOverride: Color? = null,
    redrawNativeText: Boolean = false,
    onHostPreDraw: () -> Unit,
    backdropOverride: Backdrop? = null,
    tabImageVector: ((Int) -> ImageVector)? = null,
    tabIconContent: (@Composable (Int, Color) -> Unit)? = null
) {
    if (tabs.isEmpty()) return
    val style = NavigationStyle.fromPreference(navigationStyle)
    val requestedMode = LabelMode.fromPreference(labelMode)
    val effectiveMode = requestedMode
    val systemIsDarkTheme = sourceView.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val hostIsDarkTheme = when (AppColorMode.fromPreference(colorMode)) {
        AppColorMode.SYSTEM -> systemIsDarkTheme
        AppColorMode.LIGHT -> false
        AppColorMode.DARK -> true
    }
    val backdrop = backdropOverride ?: rememberNativeViewBackdrop(sourceView, onHostPreDraw, redrawNativeText)
    val density = LocalDensity.current
    if (style != NavigationStyle.LIQUID_GLASS) {
        Box(Modifier.fillMaxSize()) {
            if (concealHostBottomBar) {
                HostBottomBarConcealment(backdrop, hostIsDarkTheme, density)
            }
            HyperNavigation(
                sourceView = sourceView,
                tabs = tabs,
                selectedIndex = selectedIndex,
                floating = style == NavigationStyle.HYPER_OS_FLOATING || forceFloatingGlass,
                backdrop = backdrop,
                blurRadius = blurRadius,
                showIcons = effectiveMode != LabelMode.TEXT_ONLY,
                showLabels = effectiveMode != LabelMode.ICON_ONLY,
                textOnly = effectiveMode == LabelMode.TEXT_ONLY,
                advancedMaterial = advancedMaterial,
                hostIsDarkTheme = hostIsDarkTheme,
                accentColorOverride = accentColorOverride,
                tabImageVector = tabImageVector,
                tabIconContent = tabIconContent
            )
        }
        return
    }
    val view = LocalView.current

    Box(Modifier.fillMaxSize()) {
        if (concealHostBottomBar) {
            HostBottomBarConcealment(backdrop, hostIsDarkTheme, density)
        }
        Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 6.dp,
                    bottom = liquidBottomSpacingDp.dp
                )
        ) {
            LiquidBottomTabs(
                selectedTabIndex = { selectedIndex.intValue.coerceIn(0, tabs.lastIndex) },
                onTabSelected = { index ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    tabs[index].onClick()
                },
                backdrop = backdrop,
                tabsCount = tabs.size,
                showIcons = effectiveMode != LabelMode.TEXT_ONLY,
                showLabels = effectiveMode != LabelMode.ICON_ONLY,
                // Keep the existing preference scale while mapping its default 18 to the
                // official LiquidBottomTabs blur radius of 8dp.
                blurRadius = (blurRadius * 8f / 18f).dp,
                advancedMaterial = advancedMaterial,
                isDarkTheme = hostIsDarkTheme,
                accentColorOverride = accentColorOverride,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.53f * 0.82f * 1.10f)
                    .height(64.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    LiquidBottomTab(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            tab.onClick()
                        }
                    ) {
                        if (effectiveMode != LabelMode.TEXT_ONLY) {
                            if (tabImageVector != null) {
                                Image(
                                    imageVector = tabImageVector(index),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(LocalLiquidContentColor.current),
                                    modifier = Modifier.size(28.dp)
                                )
                            } else if (tabIconContent != null) {
                                tabIconContent(index, LocalLiquidContentColor.current)
                            } else {
                                TabIcon(tab.label, LocalLiquidContentColor.current)
                            }
                        }
                        if (effectiveMode != LabelMode.ICON_ONLY && tab.label != "+") {
                            BasicText(
                                text = tab.label,
                                style = TextStyle(
                                    color = LocalLiquidContentColor.current,
                                    fontSize = if (effectiveMode == LabelMode.TEXT_ONLY) 15.75.sp else 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostBottomBarConcealment(
    backdrop: Backdrop,
    hostIsDarkTheme: Boolean,
    density: androidx.compose.ui.unit.Density
) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = {
                    vibrancy()
                    blur(with(density) { 40.dp.toPx() })
                },
                highlight = null,
                onDrawSurface = {
                    drawRect(if (hostIsDarkTheme) Color.Black else Color.White)
                }
            )
    )
}

@Composable
fun MiniPlayerBackground(
    sourceView: View,
    blurRadius: Int,
    navigationStyle: String,
    advancedMaterial: Boolean,
    colorMode: String,
    redrawNativeText: Boolean = false,
    onHostPreDraw: () -> Unit
) {
    val style = NavigationStyle.fromPreference(navigationStyle)
    val systemIsDarkTheme = sourceView.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val isDarkTheme = when (AppColorMode.fromPreference(colorMode)) {
        AppColorMode.SYSTEM -> systemIsDarkTheme
        AppColorMode.LIGHT -> false
        AppColorMode.DARK -> true
    }
    val backdrop = rememberNativeViewBackdrop(sourceView, onHostPreDraw, redrawNativeText)
    val density = LocalDensity.current
    val blur = with(density) { blurRadius.coerceIn(0, 40).dp.toPx() }
    val liquidBlur = with(density) { (blurRadius.coerceIn(0, 40) * 8f / 18f).dp.toPx() }
    val useEffects = advancedMaterial && isRuntimeShaderSupported()
    val shape = when (style) {
        NavigationStyle.LIQUID_GLASS -> RoundedCornerShape(555.dp)
        NavigationStyle.HYPER_OS -> androidx.compose.ui.graphics.RectangleShape
        NavigationStyle.HYPER_OS_FLOATING -> RoundedCornerShape(555.dp)
    }
    val horizontalPadding = when (style) {
        NavigationStyle.LIQUID_GLASS -> 14.dp
        NavigationStyle.HYPER_OS -> 0.dp
        NavigationStyle.HYPER_OS_FLOATING -> 14.dp
    }
    val surfaceColor = when (style) {
        NavigationStyle.LIQUID_GLASS -> if (isDarkTheme) {
            Color.Black.copy(alpha = 0.42f)
        } else {
            Color.White.copy(alpha = 0.48f)
        }
        NavigationStyle.HYPER_OS,
        NavigationStyle.HYPER_OS_FLOATING -> if (isDarkTheme) {
            Color.Black.copy(alpha = 0.36f)
        } else {
            Color.White.copy(alpha = 0.38f)
        }
    }
    val material = if (useEffects) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(if (style == NavigationStyle.LIQUID_GLASS) liquidBlur else blur)
                if (style == NavigationStyle.LIQUID_GLASS) {
                    lens(18.dp.toPx(), 18.dp.toPx())
                }
            },
            highlight = if (style == NavigationStyle.HYPER_OS) null else ({
                Highlight.Default.copy(alpha = if (isDarkTheme) 0.72f else 0.92f)
            }),
            onDrawSurface = { drawRect(surfaceColor) }
        )
    } else {
        Modifier
            .clip(shape)
            .background(surfaceColor.copy(alpha = if (isDarkTheme) 0.94f else 0.9f))
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding)
            .then(material)
    )
}

@Composable
fun AppleMusicMiniPlayer(
    sourceView: View,
    state: MusicMiniPlayerState,
    blurRadius: Int,
    navigationStyle: String,
    advancedMaterial: Boolean,
    colorMode: String,
    onOpenPlayer: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.visible) return
    val style = NavigationStyle.fromPreference(navigationStyle)
    val systemIsDarkTheme = sourceView.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val isDarkTheme = when (AppColorMode.fromPreference(colorMode)) {
        AppColorMode.SYSTEM -> systemIsDarkTheme
        AppColorMode.LIGHT -> false
        AppColorMode.DARK -> true
    }
    val backdrop = rememberNativeViewBackdrop(sourceView, {}, true)
    val density = LocalDensity.current
    val docked = style == NavigationStyle.HYPER_OS
    val shape = if (docked) {
        androidx.compose.ui.graphics.RectangleShape
    } else {
        RoundedCornerShape(555.dp)
    }
    val blur = with(density) {
        if (style == NavigationStyle.LIQUID_GLASS) {
            (blurRadius.coerceIn(0, 40) * 8f / 18f).dp.toPx()
        } else {
            blurRadius.coerceIn(0, 40).dp.toPx()
        }
    }
    val surfaceColor = if (isDarkTheme) {
        Color(0xFF171717).copy(alpha = 0.58f)
    } else if (docked) {
        Color.White.copy(alpha = 0.38f)
    } else {
        Color.White.copy(alpha = 0.32f)
    }
    val material = if (advancedMaterial && isRuntimeShaderSupported()) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blur)
                if (style == NavigationStyle.LIQUID_GLASS) lens(14.dp.toPx(), 14.dp.toPx())
            },
            highlight = if (docked) null else ({
                Highlight.Default.copy(alpha = if (isDarkTheme) 0.7f else 0.9f)
            }),
            onDrawSurface = { drawRect(surfaceColor) }
        )
    } else {
        Modifier.clip(shape).background(
            if (isDarkTheme) Color(0xFF242424).copy(alpha = 0.94f)
            else Color.White.copy(alpha = 0.9f)
        )
    }
    val contentColor = if (isDarkTheme) Color.White else Color.Black

    Row(
        modifier
            .height(64.dp)
            .then(material)
            .clip(shape)
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        state.artwork?.let { artwork ->
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
            )
        } ?: Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                .background(contentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val strokeWidth = 2.2.dp.toPx()
                drawLine(
                    color = contentColor,
                    start = Offset(size.width * 0.62f, size.height * 0.16f),
                    end = Offset(size.width * 0.62f, size.height * 0.72f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = contentColor,
                    start = Offset(size.width * 0.62f, size.height * 0.16f),
                    end = Offset(size.width * 0.88f, size.height * 0.10f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = contentColor,
                    radius = size.width * 0.18f,
                    center = Offset(size.width * 0.44f, size.height * 0.78f)
                )
            }
        }
        BasicText(
            text = state.title,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            maxLines = 1,
            style = TextStyle(
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        MiniPlayerControlButton(contentColor, PlayerControl.Previous, onPrevious)
        MiniPlayerControlButton(
            contentColor,
            if (state.isPlaying) PlayerControl.Pause else PlayerControl.Play,
            onPlayPause
        )
        MiniPlayerControlButton(contentColor, PlayerControl.Next, onNext)
    }
}

@Composable
fun NativeBackdropMusicMiniPlayer(
    sourceView: View,
    state: MusicMiniPlayerState,
    backdropRefreshKey: Int,
    useLiquidGlassMaterial: Boolean,
    blurRadius: Int,
    navigationStyle: String,
    advancedMaterial: Boolean,
    colorMode: String,
    onOpenPlayer: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.visible) return
    val style = NavigationStyle.fromPreference(navigationStyle)
    val systemIsDarkTheme = sourceView.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val isDarkTheme = when (AppColorMode.fromPreference(colorMode)) {
        AppColorMode.SYSTEM -> systemIsDarkTheme
        AppColorMode.LIGHT -> false
        AppColorMode.DARK -> true
    }
    val docked = style == NavigationStyle.HYPER_OS
    val shape = if (docked) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(555.dp)
    val surfaceColor = if (isDarkTheme) {
        Color(0xFF171717).copy(alpha = 0.58f)
    } else if (docked) {
        Color.White.copy(alpha = 0.38f)
    } else {
        Color.White.copy(alpha = 0.32f)
    }
    val fallbackColor = if (isDarkTheme) Color(0xFF242424).copy(alpha = 0.94f)
    else Color.White.copy(alpha = 0.9f)
    var backdropRefreshEpoch by remember { mutableIntStateOf(0) }
    val backdrop = remember(sourceView, backdropRefreshEpoch) {
        NativeViewMiuixBackdrop(sourceView)
    }
    val liquidBackdrop = rememberNativeViewBackdrop(sourceView, {}, true)
    val density = LocalDensity.current
    LaunchedEffect(backdropRefreshKey) {
        // The host pager changes its native drawing after the tab click is dispatched.
        // Refresh once immediately and once after its page animation has settled.
        backdropRefreshEpoch++
        delay(220)
        backdropRefreshEpoch++
    }
    val miuixBlurRadius = if (style == NavigationStyle.LIQUID_GLASS) {
        blurRadius.coerceIn(0, 40) * 8f / 18f
    } else {
        blurRadius.coerceIn(0, 40).toFloat()
    }
    val material = if (advancedMaterial && isRuntimeShaderSupported()) {
        if (useLiquidGlassMaterial && style == NavigationStyle.LIQUID_GLASS) {
            Modifier.drawBackdrop(
                backdrop = liquidBackdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(with(density) { miuixBlurRadius.dp.toPx() })
                    lens(14.dp.toPx(), 14.dp.toPx())
                },
                highlight = {
                    Highlight.Default.copy(alpha = if (isDarkTheme) 0.7f else 0.9f)
                },
                onDrawSurface = { drawRect(surfaceColor) }
            )
        } else {
            Modifier.drawMiuixBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    textureBlurEffect(
                        blurRadiusX = miuixBlurRadius,
                        blurRadiusY = miuixBlurRadius
                    )
                },
                onDrawSurface = {
                    if (backdropRefreshEpoch >= 0) drawRect(surfaceColor)
                }
            )
        }
    } else {
        Modifier.clip(shape).background(fallbackColor)
    }
    val contentColor = if (isDarkTheme) Color.White else Color.Black

    key(backdropRefreshEpoch) {
        Row(
            modifier
                .height(64.dp)
                .then(material)
                .clip(shape)
                .clickable(onClick = onOpenPlayer)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        state.artwork?.let { artwork ->
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
            )
        } ?: Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                .background(contentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val strokeWidth = 2.2.dp.toPx()
                drawLine(
                    color = contentColor,
                    start = Offset(size.width * 0.62f, size.height * 0.16f),
                    end = Offset(size.width * 0.62f, size.height * 0.72f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = contentColor,
                    start = Offset(size.width * 0.62f, size.height * 0.16f),
                    end = Offset(size.width * 0.88f, size.height * 0.10f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = contentColor,
                    radius = size.width * 0.18f,
                    center = Offset(size.width * 0.44f, size.height * 0.78f)
                )
            }
        }
        BasicText(
            text = state.title,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            maxLines = 1,
            style = TextStyle(
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
            MiniPlayerControlButton(contentColor, PlayerControl.Previous, onPrevious)
            MiniPlayerControlButton(
                contentColor,
                if (state.isPlaying) PlayerControl.Pause else PlayerControl.Play,
                onPlayPause
            )
            MiniPlayerControlButton(contentColor, PlayerControl.Next, onNext)
        }
    }
}

private enum class PlayerControl(val contentDescription: String) {
    Previous("Previous"),
    Play("Play"),
    Pause("Pause"),
    Next("Next")
}

@Composable
private fun MiniPlayerControlButton(
    contentColor: Color,
    control: PlayerControl,
    onClick: () -> Unit
) {
    val icon = when (control) {
        PlayerControl.Previous -> MaterialRoundedPlayerIcons.SkipPrevious
        PlayerControl.Play -> MaterialRoundedPlayerIcons.Play
        PlayerControl.Pause -> MaterialRoundedPlayerIcons.Pause
        PlayerControl.Next -> MaterialRoundedPlayerIcons.SkipNext
    }
    Box(
        Modifier
            .size(46.dp)
            .clip(Capsule())
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            imageVector = icon,
            contentDescription = control.contentDescription,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun HyperNavigation(
    sourceView: View,
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    floating: Boolean,
    backdrop: Backdrop,
    blurRadius: Int,
    showIcons: Boolean,
    showLabels: Boolean,
    textOnly: Boolean,
    advancedMaterial: Boolean,
    hostIsDarkTheme: Boolean,
    accentColorOverride: Color? = null,
    tabImageVector: ((Int) -> ImageVector)? = null,
    tabIconContent: (@Composable (Int, Color) -> Unit)? = null
) {
    val controller = remember(hostIsDarkTheme) {
        ThemeController(
            if (hostIsDarkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light
        )
    }
    val view = LocalView.current
    MiuixTheme(controller = controller) {
        val isDarkTheme = hostIsDarkTheme
        val useAdvancedMaterial = advancedMaterial && isRuntimeShaderSupported()
        val density = LocalDensity.current
        val materialBlurRadius = with(density) {
            blurRadius.coerceIn(0, 40).dp.toPx()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            if (floating) {
                HyperFloatingNavigationBar(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    backdrop = backdrop,
                    blurRadius = blurRadius.coerceIn(0, 40).toFloat(),
                    showIcons = showIcons,
                    showLabels = showLabels,
                    advancedMaterial = useAdvancedMaterial,
                    isDarkTheme = isDarkTheme,
                    accentColorOverride = accentColorOverride,
                    tabImageVector = tabImageVector,
                    tabIconContent = tabIconContent,
                    onTabSelected = { index ->
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        tabs[index].onClick()
                    }
                )
            } else {
                val standardBarMaterial = if (useAdvancedMaterial) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { androidx.compose.ui.graphics.RectangleShape },
                        effects = {
                            vibrancy()
                            blur(materialBlurRadius)
                        },
                        highlight = null,
                        onDrawSurface = {
                            drawRect(
                                if (isDarkTheme) Color.Black.copy(alpha = 0.58f)
                                else Color.White.copy(alpha = 0.62f)
                            )
                        }
                    )
                } else {
                    Modifier
                }
                NavigationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer {
                            shape = androidx.compose.ui.graphics.RectangleShape
                            clip = true
                            shadowElevation = 0f
                            ambientShadowColor = Color.Transparent
                            spotShadowColor = Color.Transparent
                        }
                        .then(standardBarMaterial),
                    color = if (useAdvancedMaterial) Color.Transparent else MiuixTheme.colorScheme.surface,
                    showDivider = false,
                    defaultWindowInsetsPadding = true,
                    mode = if (showLabels) {
                        NavigationBarDisplayMode.IconAndText
                    } else {
                        NavigationBarDisplayMode.IconOnly
                    }
                ) {
                    tabs.forEachIndexed { index, tab ->
                        if (textOnly) {
                            FontNavigationItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); tab.onClick() },
                                label = tab.label,
                                modifier = Modifier.weight(1f),
                                textOnly = true,
                                selectedColor = accentColorOverride,
                                icon = {}
                            )
                        } else if (tabIconContent != null) {
                            FontNavigationItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); tab.onClick() },
                                label = if (showLabels) tab.label else "",
                                modifier = Modifier.weight(1f),
                                selectedColor = accentColorOverride,
                                icon = { color ->
                                    if (showIcons) tabIconContent(index, color)
                                }
                            )
                        } else {
                            NavigationBarItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); tab.onClick() },
                                icon = if (showIcons) {
                                    tab.icon ?: tabImageVector?.invoke(index) ?: hyperIcon(tab.label)
                                } else {
                                    HyperIcons.Empty
                                },
                                label = tab.label
                            )
                        }
                    }
                }
            }
        }
    }
}

/** OS4-style floating navigation: one capsule, a moving selected pill, and a damped drag. */
@Composable
private fun HyperFloatingNavigationBar(
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    backdrop: Backdrop,
    blurRadius: Float,
    showIcons: Boolean,
    showLabels: Boolean,
    advancedMaterial: Boolean,
    isDarkTheme: Boolean,
    accentColorOverride: Color?,
    tabImageVector: ((Int) -> ImageVector)?,
    tabIconContent: (@Composable (Int, Color) -> Unit)?,
    onTabSelected: (Int) -> Unit,
) {
    // Keep the OS4 floating bar compact without changing the other navigation styles.
    val floatingScale = 0.82f
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val tabCount = tabs.size.coerceAtLeast(1)
    val initialIndex = selectedIndex.intValue.coerceIn(0, tabCount - 1)
    val dragAnimation = remember(tabCount) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = initialIndex.toFloat(),
            valueRange = 0f..(tabCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // OS4 compresses the floating pill while it is held, then springs back on release.
            pressedScale = 0.9f,
            onDragStopped = {
                val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
                animateToValue(target.toFloat())
                if (target != selectedIndex.intValue) onTabSelected(target)
            },
            onDrag = { size, amount ->
                val slotWidth = size.width.toFloat() / tabCount.coerceAtLeast(1)
                if (slotWidth > 0f) {
                    updateValue((targetValue + amount.x / slotWidth).fastCoerceIn(0f, (tabCount - 1).toFloat()))
                }
            },
            // OS4 restores the pressed scale as soon as the finger is released.
            awaitTargetBeforeRelease = false,
        )
    }
    LaunchedEffect(selectedIndex.intValue) {
        dragAnimation.animateToValue(selectedIndex.intValue.coerceIn(0, tabCount - 1).toFloat())
    }
    val contentColor = if (isDarkTheme) Color.White else Color.Black
    val selectedContentColor = accentColorOverride ?: contentColor
    val pressedIndex = if (dragAnimation.pressProgress > 0.001f) {
        dragAnimation.value.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
    } else {
        -1
    }
    val selectedSurface = if (isDarkTheme) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    val containerSurface = if (isDarkTheme) {
        Color.Black.copy(alpha = 0.30f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = 0.42f)
    }
    val fallbackSurface = if (isDarkTheme) {
        Color(0xFF242424).copy(alpha = 0.88f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = 0.82f)
    }
    val capsule = Capsule()
    val shadowColor = Color.Black.copy(alpha = 0.35f)

    val widthFraction = (0.25f + tabCount * 0.135f).coerceIn(0.53f, 0.92f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth(widthFraction)
            .navigationBarsPadding()
            .padding(bottom = 8.dp * floatingScale)
            .height(72.dp * floatingScale),
        contentAlignment = Alignment.CenterStart,
    ) {
        val innerPadding = 4.dp * floatingScale
        val innerWidthPx = with(density) { (maxWidth - innerPadding * 2).toPx() }
        val tabWidthPx = innerWidthPx / tabCount
        val tabWidth = with(density) { tabWidthPx.toDp() }
        val selectedTranslation = dragAnimation.value * tabWidthPx
        Box(
            Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 6.dp,
                    shape = capsule,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                .then(
                    if (advancedMaterial) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { capsule },
                            effects = {
                                vibrancy()
                                blur(with(density) { (blurRadius * 0.24f).dp.toPx() })
                                // Keep the lens pronounced while avoiding an overly strong refraction.
                                lens(
                                    refractionHeight = 16.dp.toPx(),
                                    refractionAmount = 32.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = false,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = if (isDarkTheme) 0.78f else 0.96f)
                            },
                            onDrawSurface = {
                                drawRect(containerSurface)
                            },
                        )
                    } else Modifier.clip(capsule).background(fallbackSurface)
                )
        )
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // Draw the selection behind labels, while a transparent overlay below captures drag.
            Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .clip(capsule)
                    .background(selectedSurface)
            )
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val pressedProgress = if (pressedIndex == index) dragAnimation.pressProgress else 0f
                    val color = if (pressedProgress > 0f) {
                        Color.Gray
                    } else {
                        // OS4 uses the hover capsule, rather than a different tint, to show selection.
                        selectedContentColor
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                val scale = lerp(1f, 0.85f, pressedProgress)
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                            ) {
                                if (index != selectedIndex.intValue) onTabSelected(index)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (showIcons) {
                            val icon = tab.icon ?: tabImageVector?.invoke(index) ?: hyperIcon(tab.label)
                            if (tabIconContent != null) {
                                tabIconContent(index, color)
                            } else {
                                Image(icon, null, Modifier.size(28.dp * floatingScale), colorFilter = ColorFilter.tint(color))
                            }
                        }
                        if (showLabels && tab.label != "+") {
                            BasicText(
                                tab.label,
                                style = TextStyle(
                                    color = color,
                                    fontSize = if (showIcons) 15.sp * floatingScale else 17.sp * floatingScale,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                modifier = if (showIcons) Modifier.padding(top = 2.dp * floatingScale) else Modifier,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .then(dragAnimation.modifier)
            )
        }
    }
}

@Composable
 private fun FontNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier,
    textOnly: Boolean = false,
    selectedColor: Color? = null,
    icon: @Composable (Color) -> Unit
) {
    val color = if (selected && selectedColor != null) {
        selectedColor
    } else {
        MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (selected) 1f else 0.55f)
    }
    Column(
        modifier = modifier.height(56.dp).clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon(color)
        if (label.isNotEmpty()) {
            BasicText(label, style = TextStyle(color = color, fontSize = if (textOnly) 16.5.sp else 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
        }
    }
}

private fun hyperIcon(label: String): ImageVector = when {
    label == "+" -> HyperIcons.Add
    label.contains("\u9996\u9875") -> HyperIcons.Home
    label.contains("\u5206\u7c7b") || label.contains("\u6d4f\u89c8") ||
        label.contains("\u8d44\u6599\u5e93") -> HyperIcons.Category
    label.contains("\u670d\u52a1") -> HyperIcons.Service
    label.contains("\u8d2d\u7269\u8f66") -> HyperIcons.Cart
    label.contains("\u89c6\u9891") || label.contains("\u77ed\u5267") -> HyperIcons.Video
    label.contains("\u7701\u94b1") -> HyperIcons.Savings
    label.contains("\u501f\u94b1") -> HyperIcons.Loan
    label.contains("\u53d1\u73b0") || label.contains("\u5e7f\u573a") ||
        label.contains("\u641c\u7d22") || label.contains("\u96f7\u8fbe") -> HyperIcons.Search
    label.contains("\u6211") || label.contains("\u4e2a\u4eba") -> HyperIcons.Profile
    label.contains("\u6d88\u606f") || label.contains("\u804a\u5929") ||
        label.contains("\u6536\u4ef6\u7bb1") -> HyperIcons.Messages
    label.contains("\u8054\u7cfb") || label.contains("\u793e\u533a") -> HyperIcons.Contacts
    label.contains("\u9891\u9053") || label.contains("\u5e7f\u64ad") ||
        label.contains("\u7acb\u5373\u8046\u542c") -> HyperIcons.Channels
    else -> HyperIcons.Dynamic
}

fun appleMusicTabIcon(index: Int): ImageVector = when (index) {
    0 -> AppleMusicIcons.ListenNow
    1 -> AppleMusicIcons.Browse
    2 -> AppleMusicIcons.Radio
    3 -> AppleMusicIcons.Library
    else -> AppleMusicIcons.Search
}

private object AppleMusicIcons {
    val ListenNow = appleMusicVector(
        name = "AppleMusicListenNow",
        pathData = "M11.035,50C9.436,50 8.197,49.574 7.318,48.722C6.439,47.87 6,46.686 6,45.17L6,25.242C6,24.344 6.14,23.598 6.419,23.005C6.698,22.412 7.187,21.828 7.886,21.254L25.189,7.097C25.642,6.732 26.097,6.457 26.554,6.274C27.011,6.091 27.493,6 28,6C28.507,6 28.989,6.091 29.446,6.274C29.903,6.457 30.359,6.732 30.812,7.097L48.114,21.254C48.813,21.828 49.302,22.412 49.581,23.005C49.86,23.598 50,24.344 50,25.242L50,45.17C50,46.686 49.561,47.87 48.682,48.722C47.803,49.574 46.564,50 44.965,50L35.709,50C35.279,50 34.921,49.863 34.637,49.59C34.353,49.316 34.211,48.966 34.211,48.539L34.211,35.506C34.211,35.057 34.068,34.693 33.782,34.414C33.496,34.135 33.117,33.995 32.646,33.995L23.354,33.995C22.89,33.995 22.513,34.135 22.223,34.414C21.934,34.693 21.789,35.057 21.789,35.506L21.789,48.539C21.789,48.966 21.648,49.316 21.368,49.59C21.088,49.863 20.729,50 20.291,50L11.035,50Z"
    )

    val Browse = appleMusicVector(
        name = "AppleMusicBrowse",
        pathData = "M43.6693,26.2536C46.5564,26.2536 48,24.843 48,21.8539L48,12.3829C48,9.3938 46.5564,8 43.6693,8L34.068,8C31.1809,8 29.7373,9.3938 29.7373,12.3829L29.7373,21.8539C29.7373,24.843 31.1809,26.2536 34.068,26.2536L43.6693,26.2536ZM21.9152,26.2536C24.8191,26.2536 26.2459,24.843 26.2459,21.8539L26.2459,12.3829C26.2459,9.3938 24.8191,8 21.9152,8L12.3307,8C9.4436,8 8,9.3938 8,12.3829L8,21.8539C8,24.843 9.4436,26.2536 12.3307,26.2536L21.9152,26.2536ZM21.9152,48C24.8191,48 26.2459,46.6062 26.2459,43.6003L26.2459,34.1461C26.2459,31.1402 24.8191,29.7464 21.9152,29.7464L12.3307,29.7464C9.4436,29.7464 8,31.1402 8,34.1461L8,43.6003C8,46.6062 9.4436,48 12.3307,48L21.9152,48ZM43.6693,48C46.5564,48 48,46.6062 48,43.6003L48,34.1461C48,31.1402 46.5564,29.7464 43.6693,29.7464L34.068,29.7464C31.1809,29.7464 29.7373,31.1402 29.7373,34.1461L29.7373,43.6003C29.7373,46.6062 31.1809,48 34.068,48L43.6693,48Z"
    )

    val Radio = appleMusicVector(
        name = "AppleMusicRadio",
        viewportWidth = 58f,
        fillType = PathFillType.EvenOdd,
        pathData = "M46.6772,45.3217C56.4988,35.4566 56.2474,19.1738 46.7062,9.6496C45.7524,8.7179 45.6396,7.4714 46.5386,6.5949C47.4086,5.7185 48.6138,5.8321 49.5418,6.793C60.5943,17.9306 61.0422,36.5895 49.5418,48.2076C48.6138,49.1392 47.4086,49.2528 46.5386,48.3731C45.6396,47.4707 45.7524,46.2534 46.6772,45.3217M40.8706,39.4689C47.4634,32.8564 47.2669,21.9753 40.8706,15.5284C39.9716,14.5675 39.8298,13.3502 40.703,12.4478C41.5988,11.5421 42.833,11.6849 43.79,12.6166C51.7008,20.5307 52.0649,34.0153 43.79,42.3839C42.862,43.2864 41.6278,43.4292 40.7288,42.5495C39.8588,41.6471 39.9426,40.4038 40.8706,39.4689M35.1188,33.6453C38.4861,30.253 38.4281,24.6891 35.1188,21.3228C34.1617,20.3879 34.0522,19.1738 34.948,18.2422C35.818,17.3657 37.0521,17.4793 37.9802,18.4402C42.862,23.3581 43.0005,31.5288 37.9802,36.5603C37.0521,37.492 35.847,37.6056 34.977,36.7031C34.0522,35.8234 34.1907,34.5802 35.1188,33.6453M24.9298,27.5132C24.9298,25.2799 26.7536,23.3874 28.9996,23.3874C31.2455,23.3874 33.0984,25.2799 33.0984,27.5132C33.0984,29.7466 31.2455,31.6099 28.9996,31.6099C26.7536,31.6099 24.9298,29.7466 24.9298,27.5132M20.0222,36.5603C15.1372,31.6683 15.0277,23.4718 20.0222,18.4694C20.947,17.5345 22.1522,17.3917 23.0512,18.3006C23.9502,19.1738 23.8085,20.4171 22.8837,21.352C19.5163,24.7443 19.5711,30.3407 22.8837,33.6745C23.8375,34.6386 23.9502,35.8234 23.0802,36.7551C22.1812,37.6348 20.947,37.5212 20.0222,36.5603M14.2414,42.4132C6.2984,34.4665 5.9343,20.9852 14.2414,12.6166C15.1372,11.7109 16.4004,11.5713 17.2704,12.477C18.1694,13.3794 18.0566,14.6227 17.1318,15.5284C10.5358,22.17 10.7613,33.0252 17.1318,39.4689C18.0566,40.433 18.1984,41.6471 17.2994,42.5787C16.4004,43.4584 15.1952,43.3156 14.2414,42.4132M8.4606,48.2076C-2.5951,37.0959 -3.043,18.411 8.4606,6.8222C9.3854,5.8905 10.6228,5.7477 11.4928,6.6242C12.3596,7.5266 12.2468,8.7731 11.322,9.7048C1.5294,19.5439 1.755,35.8234 11.2962,45.3509C12.2468,46.2826 12.3596,47.5291 11.4638,48.4023C10.6228,49.282 9.3854,49.1684 8.4606,48.2076"
    )

    val Library = appleMusicVector(
        name = "AppleMusicLibrary",
        pathData = "M37.3076,2.402C37.2092,0.8448 36.3236,0 34.6671,0L19.9721,0C18.3156,0 17.43,0.8448 17.3316,2.402L37.3076,2.402ZM40.7846,8.0341C40.5386,6.3942 39.7349,5.4334 37.9473,5.4334L16.5443,5.4334C14.7566,5.4334 13.953,6.3942 13.707,8.0341L40.7846,8.0341ZM40.1121,49C43.8515,49 46,46.83 46,42.6058L46,18.0064C46,13.7989 43.8351,11.6288 39.6037,11.6288L15.3799,11.6288C11.1485,11.6288 9,13.7657 9,18.0064L9,42.6058C9,46.8465 11.1485,49 15.3799,49L40.1121,49ZM20.2673,41.9763C18.4304,41.9763 17.1348,40.7671 17.1348,39.0609C17.1348,37.3215 18.2172,36.1951 20.3657,35.7644L22.055,35.4165C22.465,35.3337 22.6618,35.0686 22.6618,34.5717L22.6126,22.2637C22.6126,21.6011 23.039,21.187 23.777,21.0213L33.9947,18.8844C35.2411,18.6193 35.766,18.9838 35.766,20.0605L35.766,35.2508C35.766,38.4645 33.3387,39.5909 31.7642,39.5909C29.9109,39.5909 28.566,38.3651 28.566,36.6755C28.566,34.9858 29.6649,33.9422 31.8626,33.4452L33.4699,33.0974C33.9455,32.998 34.1915,32.6667 34.1915,32.1863L34.1423,25.3283C34.1423,24.9141 33.9455,24.8147 33.4535,24.9141L24.8103,26.7197C24.3675,26.8191 24.3183,26.8854 24.3183,27.3824L24.3183,37.5865C24.3183,40.7671 21.8418,41.9763 20.2673,41.9763Z"
    )

    val Search = appleMusicVector(
        name = "AppleMusicSearch",
        pathData = "M46.2722,48C47.862,48 49,46.7892 49,45.2799C49,44.5502 48.7657,43.8701 48.2302,43.3394L38.2396,33.4377C40.298,30.6679 41.5196,27.2678 41.5196,23.6023C41.5196,14.4802 33.9722,7 24.7514,7C15.5473,7 8,14.4636 8,23.6023C8,32.7411 15.5473,40.2213 24.7514,40.2213C28.2824,40.2213 31.5624,39.1266 34.2735,37.2524L44.3143,47.2205C44.8331,47.7346 45.5359,48 46.2722,48ZM24.7514,36.39C17.6727,36.39 11.849,30.6181 11.849,23.6023C11.849,16.5866 17.6727,10.8147 24.7514,10.8147C31.8469,10.8147 37.6539,16.5866 37.6539,23.6023C37.6539,30.6181 31.8469,36.39 24.7514,36.39Z"
    )
}

private fun appleMusicVector(
    name: String,
    pathData: String,
    viewportWidth: Float = 56f,
    fillType: PathFillType = PathFillType.NonZero
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 28.dp,
    defaultHeight = 28.dp,
    viewportWidth = viewportWidth,
    viewportHeight = 56f
).addPath(
    pathData = PathParser().parsePathString(pathData).toNodes(),
    pathFillType = fillType,
    fill = SolidColor(Color.Black)
).build()

private object HyperIcons {
    val Empty: ImageVector = lineIcon("Empty") {}
    val Add: ImageVector = lineIcon("Add") {
        moveTo(12f, 4f); lineTo(12f, 20f)
        moveTo(4f, 12f); lineTo(20f, 12f)
    }
    val Home: ImageVector = lineIcon("Home") {
        moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
        lineTo(19f, 11f); lineTo(19f, 21f); lineTo(5f, 21f)
        lineTo(5f, 11f); close()
        moveTo(9f, 21f); lineTo(9f, 14f); lineTo(15f, 14f); lineTo(15f, 21f)
    }
    val Category: ImageVector = lineIcon("GridView") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }
    val Service: ImageVector = lineIcon("Store") {
        moveTo(4f, 10f); lineTo(20f, 10f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(3f, 10f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(21f, 10f)
        moveTo(8f, 10f); curveTo(8f, 12f, 10f, 13f, 12f, 13f)
        curveTo(14f, 13f, 16f, 12f, 16f, 10f)
    }
    val Cart: ImageVector = lineIcon("ShoppingCart") {
        moveTo(3f, 4f); lineTo(5f, 4f); lineTo(7.5f, 16f); lineTo(19f, 16f); lineTo(21f, 8f); lineTo(6f, 8f)
        moveTo(9f, 20f); curveTo(9f, 20.6f, 8.6f, 21f, 8f, 21f); curveTo(7.4f, 21f, 7f, 20.6f, 7f, 20f); curveTo(7f, 19.4f, 7.4f, 19f, 8f, 19f); curveTo(8.6f, 19f, 9f, 19.4f, 9f, 20f)
        moveTo(19f, 20f); curveTo(19f, 20.6f, 18.6f, 21f, 18f, 21f); curveTo(17.4f, 21f, 17f, 20.6f, 17f, 20f); curveTo(17f, 19.4f, 17.4f, 19f, 18f, 19f); curveTo(18.6f, 19f, 19f, 19.4f, 19f, 20f)
    }
    val Video: ImageVector = lineIcon("Video") {
        moveTo(4f, 6f); lineTo(15f, 6f); curveTo(16.1f, 6f, 17f, 6.9f, 17f, 8f)
        lineTo(17f, 16f); curveTo(17f, 17.1f, 16.1f, 18f, 15f, 18f)
        lineTo(4f, 18f); curveTo(2.9f, 18f, 2f, 17.1f, 2f, 16f)
        lineTo(2f, 8f); curveTo(2f, 6.9f, 2.9f, 6f, 4f, 6f); close()
        moveTo(17f, 10f); lineTo(22f, 7f); lineTo(22f, 17f); lineTo(17f, 14f)
    }
    val Savings: ImageVector = lineIcon("Savings") {
        moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
        moveTo(8f, 9f); lineTo(12f, 12f); lineTo(16f, 9f)
        moveTo(8f, 14f); lineTo(16f, 14f)
        moveTo(12f, 12f); lineTo(12f, 18f)
    }
    val Loan: ImageVector = lineIcon("Loan") {
        moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(15f, 12f); curveTo(15f, 13.7f, 13.7f, 15f, 12f, 15f)
        curveTo(10.3f, 15f, 9f, 13.7f, 9f, 12f); curveTo(9f, 10.3f, 10.3f, 9f, 12f, 9f)
        curveTo(13.7f, 9f, 15f, 10.3f, 15f, 12f); close()
        moveTo(6f, 12f); lineTo(6.1f, 12f)
        moveTo(18f, 12f); lineTo(18.1f, 12f)
    }
    val Search: ImageVector = lineIcon("Search") {
        moveTo(19f, 11f); curveTo(19f, 15.4f, 15.4f, 19f, 11f, 19f)
        curveTo(6.6f, 19f, 3f, 15.4f, 3f, 11f); curveTo(3f, 6.6f, 6.6f, 3f, 11f, 3f)
        curveTo(15.4f, 3f, 19f, 6.6f, 19f, 11f); close()
        moveTo(17f, 17f); lineTo(22f, 22f)
    }
    val Profile: ImageVector = lineIcon("Profile") {
        moveTo(16f, 8f); curveTo(16f, 10.2f, 14.2f, 12f, 12f, 12f)
        curveTo(9.8f, 12f, 8f, 10.2f, 8f, 8f); curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
        curveTo(14.2f, 4f, 16f, 5.8f, 16f, 8f); close()
        moveTo(4f, 21f); curveTo(4.8f, 16.8f, 7.5f, 14.5f, 12f, 14.5f)
        curveTo(16.5f, 14.5f, 19.2f, 16.8f, 20f, 21f)
    }
    val Messages: ImageVector = ImageVector.Builder(
        name = "Messages",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(5f, 5f)
            curveTo(3.9f, 5f, 3f, 5.9f, 3f, 7f)
            lineTo(3f, 15f)
            curveTo(3f, 16.1f, 3.9f, 17f, 5f, 17f)
            lineTo(8f, 17f)
            lineTo(6f, 21f)
            lineTo(12f, 17f)
            lineTo(19f, 17f)
            curveTo(20.1f, 17f, 21f, 16.1f, 21f, 15f)
            lineTo(21f, 7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
        }
    }.build()

    val Contacts: ImageVector = ImageVector.Builder(
        name = "Contacts",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(16f, 8f)
            curveTo(16f, 10.2f, 14.2f, 12f, 12f, 12f)
            curveTo(9.8f, 12f, 8f, 10.2f, 8f, 8f)
            curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
            curveTo(14.2f, 4f, 16f, 5.8f, 16f, 8f)
            close()
            moveTo(4f, 21f)
            curveTo(4.8f, 16.8f, 7.5f, 14.5f, 12f, 14.5f)
            curveTo(16.5f, 14.5f, 19.2f, 16.8f, 20f, 21f)
        }
    }.build()

    val Channels: ImageVector = ImageVector.Builder(
        name = "Channels",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            close()
            moveTo(14.8f, 8.2f)
            lineTo(12.7f, 13.1f)
            lineTo(8.2f, 15.8f)
            lineTo(10.3f, 10.9f)
            close()
        }
    }.build()

    val Dynamic: ImageVector = ImageVector.Builder(
        name = "Dynamic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            lineTo(14.2f, 9.8f)
            lineTo(22f, 12f)
            lineTo(14.2f, 14.2f)
            lineTo(12f, 22f)
            lineTo(9.8f, 14.2f)
            lineTo(2f, 12f)
            lineTo(9.8f, 9.8f)
            close()
        }
    }.build()
}

private fun lineIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}.build()

private val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }
private val LocalLiquidContentColor = staticCompositionLocalOf { Color.Black }

@Composable
private fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        Modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    showIcons: Boolean,
    showLabels: Boolean,
    blurRadius: androidx.compose.ui.unit.Dp,
    advancedMaterial: Boolean,
    isDarkTheme: Boolean,
    accentColorOverride: Color? = null,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isDarkTheme
    val accentColor = accentColorOverride ?:
        if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val containerColor = if (isLightTheme) {
        Color(0xFFFAFAFA).copy(alpha = 0.4f)
    } else {
        Color(0xFF121212).copy(alpha = 0.4f)
    }
    val tabsBackdrop = rememberKyantLayerBackdrop()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8.dp.toPx()) / tabsCount
        }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth)
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedTabIndex()) }
        val dragAnimation = remember(animationScope, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, amount ->
                    updateValue(
                        (targetValue + amount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + amount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }.collectLatest { currentIndex = it }
        }
        LaunchedEffect(dragAnimation) {
            snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
                dragAnimation.animateToValue(index.toFloat())
                onTabSelected(index)
            }
        }
        val interactiveHighlight = remember(animationScope, isLtr, tabWidth) {
            InteractiveHighlight(animationScope) { size, _ ->
                Offset(
                    if (isLtr) (dragAnimation.value + 0.5f) * tabWidth + panelOffset
                    else size.width - (dragAnimation.value + 0.5f) * tabWidth + panelOffset,
                    size.height / 2f
                )
            }
        }

        CompositionLocalProvider(LocalLiquidContentColor provides contentColor) {
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            if (advancedMaterial) {
                                vibrancy()
                                blur(blurRadius.toPx())
                                lens(24.dp.toPx(), 24.dp.toPx())
                            }
                        },
                        highlight = if (advancedMaterial) ({ Highlight.Default }) else null,
                        layerBlock = {
                            val progress = dragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(64.dp)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dragAnimation.pressProgress)
            },
            LocalLiquidContentColor provides contentColor
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            if (advancedMaterial) {
                                val progress = dragAnimation.pressProgress
                                vibrancy()
                                blur(blurRadius.toPx())
                                lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                            }
                        },
                        highlight = if (advancedMaterial) ({
                            Highlight.Default.copy(alpha = dragAnimation.pressProgress)
                        }) else null,
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        dragAnimation.value * tabWidth + panelOffset
                    } else {
                        size.width - (dragAnimation.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dragAnimation.pressProgress
                        lens(
                            10.dp.toPx() * progress,
                            14.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = if (advancedMaterial) ({
                        Highlight.Default.copy(alpha = dragAnimation.pressProgress)
                    }) else null,
                    layerBlock = {
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                        val velocity = dragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(alpha = 0.1f)
                            else Color.White.copy(alpha = 0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

@Composable
private fun TabIcon(label: String, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when {
            label == "+" -> {
                drawRoundRect(
                    color = Color(0xFFFF2442),
                    topLeft = Offset(center.x - 11.dp.toPx(), center.y - 8.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(22.dp.toPx(), 16.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                )
                drawLine(
                    Color.White,
                    center - Offset(5.dp.toPx(), 0f),
                    center + Offset(5.dp.toPx(), 0f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    Color.White,
                    center - Offset(0f, 5.dp.toPx()),
                    center + Offset(0f, 5.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            label.contains("\u9996\u9875") -> {
                drawPath(Path().apply {
                    moveTo(center.x - 9.dp.toPx(), center.y - 1.dp.toPx())
                    lineTo(center.x, center.y - 9.dp.toPx())
                    lineTo(center.x + 9.dp.toPx(), center.y - 1.dp.toPx())
                    lineTo(center.x + 7.dp.toPx(), center.y - 1.dp.toPx())
                    lineTo(center.x + 7.dp.toPx(), center.y + 9.dp.toPx())
                    lineTo(center.x - 7.dp.toPx(), center.y + 9.dp.toPx())
                    lineTo(center.x - 7.dp.toPx(), center.y - 1.dp.toPx())
                    close()
                }, color, style = stroke)
            }
            label.contains("\u5206\u7c7b") || label.contains("\u6d4f\u89c8") ||
                label.contains("\u8d44\u6599\u5e93") -> {
                val cell = 6.dp.toPx()
                val gap = 3.dp.toPx()
                for (row in 0..1) for (column in 0..1) {
                    drawRoundRect(
                        color,
                        Offset(center.x - cell - gap / 2 + column * (cell + gap), center.y - cell - gap / 2 + row * (cell + gap)),
                        androidx.compose.ui.geometry.Size(cell, cell),
                        androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                        style = stroke
                    )
                }
            }
            label.contains("\u670d\u52a1") -> {
                drawRoundRect(
                    color,
                    Offset(center.x - 8.dp.toPx(), center.y - 2.dp.toPx()),
                    androidx.compose.ui.geometry.Size(16.dp.toPx(), 11.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawLine(color, center - Offset(6.dp.toPx(), 2.dp.toPx()), center - Offset(4.dp.toPx(), 7.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, center + Offset(6.dp.toPx(), 2.dp.toPx()), center + Offset(4.dp.toPx(), 7.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            label.contains("\u8d2d\u7269\u8f66") -> {
                drawLine(color, center - Offset(9.dp.toPx(), 7.dp.toPx()), center - Offset(6.dp.toPx(), 7.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, center - Offset(6.dp.toPx(), 7.dp.toPx()), center - Offset(3.dp.toPx(), 2.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawRoundRect(color, Offset(center.x - 3.dp.toPx(), center.y - 5.dp.toPx()), androidx.compose.ui.geometry.Size(11.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), style = stroke)
                drawCircle(color, 1.5.dp.toPx(), center + Offset(0f, 7.dp.toPx()), style = stroke)
                drawCircle(color, 1.5.dp.toPx(), center + Offset(7.dp.toPx(), 7.dp.toPx()), style = stroke)
            }
            label.contains("\u89c6\u9891") || label.contains("\u77ed\u5267") -> {
                drawRoundRect(
                    color,
                    Offset(center.x - 9.dp.toPx(), center.y - 7.dp.toPx()),
                    androidx.compose.ui.geometry.Size(14.dp.toPx(), 14.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    style = stroke
                )
                drawPath(Path().apply {
                    moveTo(center.x + 5.dp.toPx(), center.y - 4.dp.toPx())
                    lineTo(center.x + 10.dp.toPx(), center.y - 7.dp.toPx())
                    lineTo(center.x + 10.dp.toPx(), center.y + 7.dp.toPx())
                    lineTo(center.x + 5.dp.toPx(), center.y + 4.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u7701\u94b1") -> {
                drawCircle(color, 9.dp.toPx(), center, style = stroke)
                drawLine(
                    color,
                    center - Offset(4.dp.toPx(), 3.dp.toPx()),
                    center + Offset(4.dp.toPx(), 3.dp.toPx()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    center - Offset(4.dp.toPx(), -3.dp.toPx()),
                    center + Offset(4.dp.toPx(), -3.dp.toPx()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    center - Offset(0f, 6.dp.toPx()),
                    center + Offset(0f, 6.dp.toPx()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round
                )
            }
            label.contains("\u501f\u94b1") -> {
                drawRoundRect(
                    color,
                    Offset(center.x - 10.dp.toPx(), center.y - 7.dp.toPx()),
                    androidx.compose.ui.geometry.Size(20.dp.toPx(), 14.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawCircle(color, 3.5.dp.toPx(), center, style = stroke)
                drawCircle(color, 1.dp.toPx(), center - Offset(7.dp.toPx(), 0f))
                drawCircle(color, 1.dp.toPx(), center + Offset(7.dp.toPx(), 0f))
            }
            label.contains("\u53d1\u73b0") || label.contains("\u5e7f\u573a") ||
                label.contains("\u641c\u7d22") || label.contains("\u96f7\u8fbe") -> {
                drawCircle(color, 8.dp.toPx(), center - Offset(1.dp.toPx(), 1.dp.toPx()), style = stroke)
                drawLine(
                    color,
                    center + Offset(5.dp.toPx(), 5.dp.toPx()),
                    center + Offset(10.dp.toPx(), 10.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            label.contains("\u6211") || label.contains("\u4e2a\u4eba") -> {
                drawCircle(color, 4.dp.toPx(), center - Offset(0f, 5.dp.toPx()), style = stroke)
                drawPath(Path().apply {
                    moveTo(center.x - 8.dp.toPx(), center.y + 9.dp.toPx())
                    quadraticTo(center.x, center.y, center.x + 8.dp.toPx(), center.y + 9.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u6d88\u606f") || label.contains("\u804a\u5929") ||
                label.contains("\u6536\u4ef6\u7bb1") -> {
                val rect = Rect(
                    center.x - 9.dp.toPx(), center.y - 7.dp.toPx(),
                    center.x + 9.dp.toPx(), center.y + 6.dp.toPx()
                )
                drawRoundRect(
                    color, rect.topLeft, rect.size,
                    androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = stroke
                )
                drawPath(Path().apply {
                    moveTo(center.x - 3.dp.toPx(), center.y + 6.dp.toPx())
                    lineTo(center.x - 6.dp.toPx(), center.y + 10.dp.toPx())
                    lineTo(center.x + 1.dp.toPx(), center.y + 6.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u8054\u7cfb") || label.contains("\u793e\u533a") -> {
                drawCircle(color, 4.dp.toPx(), center - Offset(0f, 5.dp.toPx()), style = stroke)
                drawPath(Path().apply {
                    moveTo(center.x - 8.dp.toPx(), center.y + 9.dp.toPx())
                    quadraticTo(center.x, center.y, center.x + 8.dp.toPx(), center.y + 9.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u9891\u9053") || label.contains("\u5e7f\u64ad") ||
                label.contains("\u7acb\u5373\u8046\u542c") -> {
                drawCircle(color, 9.dp.toPx(), center, style = stroke)
                drawPath(Path().apply {
                    moveTo(center.x - 3.dp.toPx(), center.y + 4.dp.toPx())
                    lineTo(center.x + 2.dp.toPx(), center.y - 5.dp.toPx())
                    lineTo(center.x + 4.dp.toPx(), center.y - 1.dp.toPx())
                    close()
                }, color, style = stroke)
            }
            else -> drawPath(Path().apply {
                moveTo(center.x, center.y - 10.dp.toPx())
                lineTo(center.x + 2.5.dp.toPx(), center.y - 2.5.dp.toPx())
                lineTo(center.x + 10.dp.toPx(), center.y)
                lineTo(center.x + 2.5.dp.toPx(), center.y + 2.5.dp.toPx())
                lineTo(center.x, center.y + 10.dp.toPx())
                lineTo(center.x - 2.5.dp.toPx(), center.y + 2.5.dp.toPx())
                lineTo(center.x - 10.dp.toPx(), center.y)
                lineTo(center.x - 2.5.dp.toPx(), center.y - 2.5.dp.toPx())
                close()
            }, color, style = stroke)
        }
    }
}
