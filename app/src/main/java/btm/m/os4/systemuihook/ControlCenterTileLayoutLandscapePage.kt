// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import btm.m.os4.systemuihook.InterfaceArrowPreference as ArrowPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ControlCenterTileLayoutLandscapePage(back: () -> Unit) = AppPage(
    tr("cc_layout_landscape_title", "自定义横屏布局"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { ControlCenterTileLayoutLandscapeSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    val listState = rememberLazyListState()
    val local = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var available by remember { mutableStateOf(CcTileCatalog.available(local).map { it.id }) }
    val serviceIcons = remember(context) { CcTileCatalog.previewIcons(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewMedia by remember { mutableStateOf<CcPreviewMedia?>(null) }
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    try {
                        previewMedia = CcPreviewMediaReader.read(context)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        previewMedia = null
                        break
                    }
                    delay(5000)
                }
            } finally {
                previewMedia = null
            }
        }
    }
    DisposableEffect(local) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_CONTROL_CENTER_5G_TILE_MODE || key == KEY_CONTROL_CENTER_GMS_TILE_ENABLED) {
                available = CcTileCatalog.available(local).map { it.id }
            }
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        onDispose { local.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var confirmSyncPortrait by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scheme = MiuixTheme.colorScheme
    val tileColor = lerp(scheme.surfaceContainer, scheme.primary, .08f)
    val editorColors = CcEditorColors(
        tile = tileColor.toArgb(), activeTile = lerp(tileColor, scheme.primary, .14f).toArgb(),
        foreground = scheme.onSurface.toArgb(), media = scheme.onSurfaceVariantSummary.toArgb(),
        guide = scheme.onSurface.copy(alpha = .12f).toArgb(), drop = scheme.primary.copy(alpha = .55f).toArgb(),
        handle = scheme.onSurface.toArgb(), badge = scheme.onSurface.toArgb(), badgeText = scheme.surface.toArgb(),
        card = scheme.surfaceContainer.toArgb(),
    )
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }
    AppList(padding, scroll, 28, state = listState) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                ArrowPreference(
                    title = tr("cc_layout_landscape_sync_portrait", "同步竖屏布局"),
                    onClick = { confirmSyncPortrait = true },
                )
                ArrowPreference(
                    title = tr("cc_layout_reset", "还原初始布局"),
                    onClick = { confirmReset = true },
                )
            }
        }
        item {
            AndroidView(
                factory = { CcLayoutEditorView(it) },
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                update = { editor ->
                    editor.setColors(editorColors)
                    editor.setMedia(previewMedia)
                    editor.onAutoScroll = { listState.dispatchRawDelta(it) }
                    editor.onCommit = { tiles, sizes ->
                        settings = settings.copy(tiles = tiles, rememberedSizes = sizes)
                        store.update(service, settings)
                    }
                    editor.setContent(settings.tiles, available, settings.rememberedSizes, serviceIcons)
                },
                onRelease = { it.release() },
            )
        }
    }

    if (confirmSyncPortrait) WindowDialog(show = true, onDismissRequest = { confirmSyncPortrait = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                tr("cc_layout_landscape_sync_confirm", "即将使用当前竖屏布局覆盖横屏布局，是否确认？"),
                modifier = Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.title3, textAlign = TextAlign.Start,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = { confirmSyncPortrait = false }, modifier = Modifier.weight(1f)) {
                    Text(tr("取消", "取消"))
                }
                GlassDialogButton(onClick = {
                    val portrait = readCcTileLayoutSettings(local)
                    settings = settings.copy(tiles = portrait.tiles, rememberedSizes = portrait.rememberedSizes)
                    store.update(service, settings)
                    confirmSyncPortrait = false
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr("确认", "确认"))
                }
            }
        }
    }

    if (confirmReset) WindowDialog(show = true, onDismissRequest = { confirmReset = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                tr("cc_layout_landscape_reset_confirm", "即将恢复初始横屏控制中心布局，是否确认？"),
                modifier = Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.title3, textAlign = TextAlign.Start,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = { confirmReset = false }, modifier = Modifier.weight(1f)) {
                    Text(tr("取消", "取消"))
                }
                GlassDialogButton(onClick = {
                    settings = settings.copy(tiles = CcTileLayouts.initial, rememberedSizes = emptyMap())
                    store.update(service, settings)
                    confirmReset = false
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr("确认", "确认"))
                }
            }
        }
    }
}
