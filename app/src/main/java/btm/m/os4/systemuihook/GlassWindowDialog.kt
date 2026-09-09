// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.os.Build
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.captionBarPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.DialogLayout

/**
 * MIUIX dialog rendered in the current Compose tree.
 *
 * The moving surface and drawBackdrop share this content layer. An Android
 * Dialog would sample the backdrop from a different window and visibly lag.
 */
@Composable
fun WindowDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(),
    backgroundColor: Color = DialogDefaults.backgroundColor(),
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    includeImePadding: Boolean = true,
    imeBottomSafetyFraction: Float = 0f,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalDialogBackdrop.current
    val activeBackdrop = if (Build.VERSION.SDK_INT >= 33) backdrop else null
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isLargeScreen = windowInfo.containerDpSize.height >= 480.dp && windowInfo.containerDpSize.width >= 840.dp
    val animationProgress = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    val dimProgress = remember { Animatable(0f) }
    val internalVisible = remember { mutableStateOf(false) }
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val imeInsets = WindowInsets.ime

    LaunchedEffect(show) {
        if (show) {
            internalVisible.value = true
            if (enableWindowDim) {
                launch { dimProgress.animateTo(1f, tween(340, easing = DecelerateEasing(1.5f))) }
            }
            animationProgress.animateTo(
                1f,
                if (isLargeScreen) {
                    spring(dampingRatio = 0.9f, stiffness = 438.6f, visibilityThreshold = 0.0001f)
                } else {
                    spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f)
                },
            )
        } else {
            if (!internalVisible.value) return@LaunchedEffect
            if (imeInsets.getBottom(density) > 0) keyboardController?.hide()
            if (enableWindowDim) {
                launch { dimProgress.animateTo(0f, tween(340, easing = DecelerateEasing(1.5f))) }
            }
            animationProgress.animateTo(0f, tween(340, easing = DecelerateEasing(1.5f)))
            dimProgress.snapTo(0f)
            internalVisible.value = false
            currentOnDismissFinished?.invoke()
        }
    }

    if (!show && !internalVisible.value) return

    val coroutineScope = rememberCoroutineScope()
    val dimAlpha = remember { Animatable(1f) }
    val dialogHeightPx = remember { mutableIntStateOf(0) }
    val backProgress = remember { Animatable(0f) }
    val dialogOffsetPx = with(density) { 6.dp.toPx() }
    val navigationBottomInsetPx = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
    }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val requestDismiss: () -> Unit = remember {
        { currentOnDismissRequest?.invoke() }
    }
    val resetGesture: suspend () -> Unit = remember {
        {
            backProgress.animateTo(0f, tween(150))
            dimAlpha.animateTo(1f, tween(150))
        }
    }

    DialogLayout(
        visible = internalVisible,
        enableWindowDim = false,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableAutoLargeScreen = false,
    ) {
        val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = show,
            onBackCancelled = { coroutineScope.launch { resetGesture() } },
            onBackCompleted = requestDismiss,
        )
        LaunchedEffect(Unit) {
            snapshotFlow { navigationEventState.transitionState }.collect { transitionState ->
                if (
                    transitionState is NavigationEventTransitionState.InProgress &&
                    transitionState.direction == NavigationEventTransitionState.TRANSITIONING_BACK
                ) {
                    val progress = transitionState.latestEvent.progress
                    backProgress.snapTo(progress)
                    dimAlpha.snapTo(1f - progress)
                }
            }
        }

        if (enableWindowDim) {
            val baseColor = MiuixTheme.colorScheme.windowDimming
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(baseColor.copy(alpha = baseColor.alpha * 0.28f * dimAlpha.value * dimProgress.value))
                    },
            )
        }

        val topInset = maxOf(
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            WindowInsets.captionBar.asPaddingValues().calculateTopPadding(),
            WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
        )
        val dialogShape = RoundedCornerShape(40.dp)
        val contentAlignment = if (isLargeScreen) Alignment.Center else Alignment.BottomCenter
        val imeBottomSafetyOffsetPx = if (imeInsets.getBottom(density) > 0) {
            with(density) {
                (windowInfo.containerDpSize.height * imeBottomSafetyFraction.coerceIn(0f, 1f)).toPx()
            }
        } else {
            0f
        }
        val contentModifier = modifier
            .widthIn(max = DialogDefaults.MaxWidth)
            .heightIn(
                max = if (isLargeScreen) windowInfo.containerDpSize.height * (2f / 3f)
                else androidx.compose.ui.unit.Dp.Unspecified,
            )
            .onGloballyPositioned { dialogHeightPx.intValue = it.size.height }
            .graphicsLayer {
                val progress = animationProgress.value
                if (isLargeScreen) {
                    val scale = 0.8f + 0.2f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = progress
                    val backScale = 1f - backProgress.value * 0.2f
                    scaleX *= backScale
                    scaleY *= backScale
                    translationY = dialogOffsetPx
                } else {
                    val maxOffset = if (dialogHeightPx.intValue > 0) {
                        dialogHeightPx.intValue.toFloat() + with(density) {
                            navigationBottomInsetPx + outsideMargin.height.toPx()
                        }
                    } else {
                        windowInfo.containerDpSize.height.toPx()
                    }
                    translationY = (1f - progress) * windowInfo.containerDpSize.height.toPx() +
                        backProgress.value * maxOffset + dialogOffsetPx + imeBottomSafetyOffsetPx
                }
            }
            .pointerInput(Unit) { detectTapGestures { /* Consume taps inside the dialog. */ } }
            .clip(dialogShape)
            .then(
                if (activeBackdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = activeBackdrop,
                        shape = { dialogShape },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(16.dp.toPx(), 16.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = .70f)
                        },
                    )
                } else Modifier
            )
            .background(
                color = backgroundColor.copy(alpha = 0.87f),
                shape = dialogShape,
            )
            .padding(horizontal = insideMargin.width, vertical = insideMargin.height)

        Box(
            Modifier
                .then(
                    if (defaultWindowInsetsPadding) {
                        Modifier
                            .then(if (includeImePadding) Modifier.imePadding() else Modifier)
                            .navigationBarsPadding()
                            .captionBarPadding()
                    } else Modifier
                )
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { requestDismiss() }) }
                .semantics {
                    onClick {
                        requestDismiss()
                        true
                    }
                }
                .padding(horizontal = outsideMargin.width)
                .padding(top = topInset, bottom = outsideMargin.height),
        ) {
            Column(modifier = contentModifier.align(contentAlignment)) {
                title?.let {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        text = it,
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = titleColor,
                    )
                }
                summary?.let {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        text = it,
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        textAlign = TextAlign.Center,
                        color = summaryColor,
                    )
                }
                CompositionLocalProvider(LocalDismissState provides requestDismiss) { content() }
            }
        }
    }
}

@Composable
fun GlassDialogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    top.yukonga.miuix.kmp.basic.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = 999.dp,
        colors = colors,
        content = content,
    )
}
