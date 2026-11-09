// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur as contentBlur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import btm.m.liquidglass.hook.DampedDragAnimation
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search

internal val SettingsSearchShape = Capsule(RoundedCornerStyle.Continuous)

/** One continuous outline for the compositor, foreground, preview and outer shadow. */
internal fun settingsSearchPath(size: Size, density: Density, direction: LayoutDirection): Path =
    when (val outline = SettingsSearchShape.createOutline(size, direction, density)) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }

/** Live content is transparent over MiBlur; only the module preview uses Backdrop. */
@Composable
internal fun SettingsFloatingSearchSurface(
    settings: SettingsFloatingSearchSettings,
    dark: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    enabled: Boolean = true,
    motionAlpha: Float = 1f,
    motionBlur: Dp = 0.dp,
    onClick: () -> Unit = {},
    onPressScale: (Float) -> Unit = {},
) {
    val colorStyle = LocalAppInterfaceStyle.current == AppInterfaceStyle.COLOR
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val drag = remember(scope) {
        DampedDragAnimation(scope, 0f, -1f..1f, .001f, 1f, 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) })
    }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, label = "settingsSearchPress")
    val scale = 1f + .015f * press
    SideEffect { onPressScale(scale) }
    val foreground = if (dark) Color.White else Color.Black
    val previewMaterial = if (backdrop == null) Modifier else Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { SettingsSearchShape },
        // Pure advanced material: no lens, refraction, dispersion or vibrancy pass.
        effects = { if (settings.blurRadius > 0) blur(settings.blurRadius.toFloat()) },
        highlight = if (settings.highlight) ({ Highlight.Default.copy(alpha = if (colorStyle) .85f else Highlight.Default.alpha) }) else null,
        shadow = null,
        onDrawSurface = {
            drawRect((if (dark) Color(0xFF17181C) else Color.White).copy(alpha = if (colorStyle) maxOf(settings.opacity / 100f, .72f) else settings.opacity / 100f))
        },
    )
    // Give the alpha/blur recording real space for the outer shadow. Keep the outer
    // anchor and the clickable Row at their original dimensions and center.
    val shadowSpace = 64.dp
    BoxWithConstraints(modifier) {
        Box(
            Modifier.matchParentSize().wrapContentSize(Alignment.Center, unbounded = true)
                .requiredSize(maxWidth + shadowSpace * 2, maxHeight + shadowSpace * 2)
                .graphicsLayer { alpha = motionAlpha; scaleX = scale * drag.scaleX; scaleY = scale * drag.scaleY; translationX = drag.value * 5.dp.toPx() }
                .contentBlur(motionBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .padding(shadowSpace),
        ) {
            Row(
                Modifier.fillMaxSize()
                    .drawWithCache {
                        val path = settingsSearchPath(size, this, layoutDirection)
                        val strokes = (16 downTo 1).map { Stroke(it * density) }
                        // Draw only outside the continuous outline, within the padded layer.
                        onDrawBehind {
                            clipPath(path, ClipOp.Difference) {
                                translate(top = 3.dp.toPx()) {
                                    strokes.forEach { drawPath(path, Color.Black.copy(alpha = .012f), style = it) }
                                }
                            }
                        }
                    }
                    .then(drag.modifier).then(previewMaterial)
                    .clip(SettingsSearchShape)
                    .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                    .semantics { contentDescription = text }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(MiuixIcons.Regular.Search, null, Modifier.size((22f * (1f + settings.iconScale / 100f)).dp), colorFilter = ColorFilter.tint(foreground))
                Spacer(Modifier.width(settings.iconTextSpacing.dp))
                BasicText(text, Modifier.weight(1f), style = TextStyle(color = foreground, fontSize = (17f * (1f + settings.textScale / 100f)).sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Percentages use the same linear convention as navigation sizing, bounded to the window. */
internal fun settingsSearchWidth(available: Float, density: Float, scale: Int): Float =
    (minOf((available - 48f * density).coerceAtLeast(1f), 560f * density) *
        (1f + scale.coerceIn(-100, 100) / 100f)).coerceIn(1f, (available - 16f * density).coerceAtLeast(1f))

internal fun settingsSearchHeight(density: Float, scale: Int): Float =
    (56f * density * (1f + scale.coerceIn(-100, 100) / 100f)).coerceAtLeast(1f)
