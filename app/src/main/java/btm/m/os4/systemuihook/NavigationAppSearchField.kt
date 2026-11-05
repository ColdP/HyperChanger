// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import btm.m.liquidglass.hook.InteractiveHighlight
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Lives in AppPage's floating slot, outside the content layer it samples. */
@Composable
internal fun NavigationAppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = tr("appNavigationSearchHint", "搜索应用名或包名"),
    floating: Boolean = true,
    drawSurface: Boolean = true,
    requestFocus: Boolean = false,
    enabled: Boolean = true,
) {
    val backdrop = LocalToolbarBackdrop.current
    val shape = remember { Capsule(RoundedCornerStyle.Continuous) }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val light = surface.luminance() > .5f
    val glassAvailable = backdrop != null && isRuntimeShaderSupported()
    val tint = (if (light) Color(0xFFF8F8F8) else surface)
        .copy(alpha = if (glassAvailable) .30f else .90f)
    val glass = if (!drawSurface) Modifier else if (glassAvailable) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                vibrancy()
                blur(SEARCH_GLASS_BLUR.toPx())
                lens(18.dp.toPx(), SEARCH_GLASS_REFRACTION.toPx(), depthEffect = true, chromaticAberration = false)
            },
            highlight = { Highlight.Default.copy(alpha = .25f) },
            shadow = {
                Shadow.Default.copy(radius = 12.dp, color = if (light) Color.Gray else Color.Black, alpha = .25f)
            },
            innerShadow = null,
            onDrawSurface = { drawRect(tint) },
        )
    } else Modifier.clip(shape).background(tint)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            androidx.compose.ui.geometry.Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Box(
        modifier.then(if (floating) Modifier
            // Move only the capsule; keeping its measured slot stable avoids an IME-sized
            // toolbar container and makes empty/nonempty input use the same placement.
            .offset {
                IntOffset(0, -(imeInsets.getBottom(density) - navigationInsets.getBottom(density)).coerceAtLeast(0))
            }
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth() else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(shape).then(glass)
                .then(highlight.gestureModifier).then(highlight.modifier)
                .clickable(enabled = enabled, interactionSource = null, indication = null) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MiuixIcons.Regular.Search, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .focusRequester(focusRequester).semantics { contentDescription = hint },
                singleLine = true,
                enabled = enabled,
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
                decorationBox = { input ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                hint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        input()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { if (enabled) onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        MiuixIcons.Regular.Close, tr("category_search_clear", "清除"), Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else Spacer(Modifier.width(12.dp))
        }
    }
}
