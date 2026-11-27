// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import btm.m.os4.systemuihook.InterfaceArrowPreference as ArrowPreference
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun multiPageLabel(page: CcMultiPageId): String = when (page) {
    CcMultiPageId.FAVORITES -> tr("cc_multi_page_favorites", "常用页面（主控制中心）")
    CcMultiPageId.MEDIA -> tr("cc_multi_page_media", "媒体播放页")
    CcMultiPageId.CONNECTIVITY -> tr("cc_multi_page_connectivity", "连接控制页")
    CcMultiPageId.TOOLS -> tr("cc_multi_page_tools", "快捷控制页")
}

private fun multiPageSummary(page: CcMultiPageId): String = when (page) {
    CcMultiPageId.FAVORITES -> tr("cc_multi_page_favorites_desc", "保留原有控制中心与自定义磁贴布局")
    CcMultiPageId.MEDIA -> tr("cc_multi_page_media_desc", "大封面播放器、进度拖动、切歌与媒体音量")
    CcMultiPageId.CONNECTIVITY -> tr("cc_multi_page_connectivity_desc", "飞行模式、移动数据、WLAN、蓝牙、热点、互传、NFC 等")
    CcMultiPageId.TOOLS -> tr("cc_multi_page_tools_desc", "手电筒、静音、勿扰、方向锁定、截屏、录屏、省电等")
}

@Composable
internal fun ControlCenterMultiPagePage(back: () -> Unit) = AppPage(
    tr("cc_multi_page_title", "多页控制中心"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { ControlCenterMultiPageSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }

    fun update(transform: (ControlCenterMultiPageSettings) -> ControlCenterMultiPageSettings) {
        store.update(service, transform)
        settings = store.settings
    }

    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }

    val secondaryOrder = settings.orderedSecondaryPages()

    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("cc_multi_page_enable", "启用多页控制中心"),
                    summary = tr("cc_multi_page_enable_desc", "上下滑动切换多个页面，并在右侧显示可点按与滑动的页面指示条"),
                    checked = CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED && settings.enabled,
                    enabled = CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED,
                    onCheckedChange = { checked ->
                        if (CONTROL_CENTER_MULTI_PAGE_FEATURE_ENABLED) {
                            update { it.copy(enabled = checked) }
                        }
                    },
                )
            }
        }
        item {
            Group(tr("cc_multi_page_pages_group", "页面内容与顺序")) {
                SwitchPreference(
                    title = multiPageLabel(CcMultiPageId.MEDIA),
                    summary = multiPageSummary(CcMultiPageId.MEDIA),
                    checked = settings.mediaPage,
                    enabled = settings.enabled,
                    onCheckedChange = { checked -> update { it.copy(mediaPage = checked) } },
                )
                SwitchPreference(
                    title = multiPageLabel(CcMultiPageId.CONNECTIVITY),
                    summary = multiPageSummary(CcMultiPageId.CONNECTIVITY),
                    checked = settings.connectivityPage,
                    enabled = settings.enabled,
                    onCheckedChange = { checked -> update { it.copy(connectivityPage = checked) } },
                )
                SwitchPreference(
                    title = multiPageLabel(CcMultiPageId.TOOLS),
                    summary = multiPageSummary(CcMultiPageId.TOOLS),
                    checked = settings.toolsPage,
                    enabled = settings.enabled,
                    onCheckedChange = { checked -> update { it.copy(toolsPage = checked) } },
                )
            }
        }
        item {
            Group(tr("cc_multi_page_order_group", "扩展页面排序")) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tr("cc_multi_page_order_hint", "第 1 页固定为主控制中心，以下为向下翻页时的扩展页顺序"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    secondaryOrder.forEachIndexed { index, pageId ->
                        val active = settings.isPageEnabled(pageId)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${index + 2}. ${multiPageLabel(pageId)}",
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium,
                                    color = if (active && settings.enabled) {
                                        MiuixTheme.colorScheme.onSurface
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    text = tr("cc_multi_page_move_up", "上移"),
                                    enabled = settings.enabled && index > 0,
                                    onClick = {
                                        val mutable = secondaryOrder.toMutableList()
                                        val prev = mutable[index - 1]
                                        mutable[index - 1] = mutable[index]
                                        mutable[index] = prev
                                        update { it.copy(pageOrder = CcMultiPageId.encodeOrder(mutable)) }
                                    },
                                )
                                TextButton(
                                    text = tr("cc_multi_page_move_down", "下移"),
                                    enabled = settings.enabled && index < secondaryOrder.lastIndex,
                                    onClick = {
                                        val mutable = secondaryOrder.toMutableList()
                                        val next = mutable[index + 1]
                                        mutable[index + 1] = mutable[index]
                                        mutable[index] = next
                                        update { it.copy(pageOrder = CcMultiPageId.encodeOrder(mutable)) }
                                    },
                                )
                            }
                        }
                    }
                }
                ArrowPreference(
                    title = tr("cc_multi_page_reset_order", "恢复默认页面顺序"),
                    enabled = settings.enabled,
                    onClick = {
                        update {
                            it.copy(pageOrder = CcMultiPageId.encodeOrder(CcMultiPageId.defaultSecondaryOrder))
                        }
                    },
                )
            }
        }
        item {
            Group(tr("cc_multi_page_behavior_group", "手势与显示")) {
                SwitchPreference(
                    title = tr("cc_multi_page_indicator", "显示右侧纵向图标指示条"),
                    summary = tr("cc_multi_page_indicator_desc", "点按图标快速跳转，按住上下拖动可连续切换页面"),
                    checked = settings.showIndicator,
                    enabled = settings.enabled,
                    onCheckedChange = { checked -> update { it.copy(showIndicator = checked) } },
                )
                SwitchPreference(
                    title = tr("cc_multi_page_reset_on_close", "收起控制中心后回到第 1 页"),
                    checked = settings.resetOnClose,
                    enabled = settings.enabled,
                    onCheckedChange = { checked -> update { it.copy(resetOnClose = checked) } },
                )
                SwitchPreference(
                    title = tr("cc_multi_page_last_page_dismiss", "末页继续上滑收起控制中心"),
                    summary = tr("cc_multi_page_last_page_dismiss_desc", "在任何页面均可通过底部边缘上滑或点击空白处收起，左右滑动仍可切换通知中心"),
                    checked = settings.lastPageSwipeUpDismiss,
                    enabled = settings.enabled,
                    onCheckedChange = { checked -> update { it.copy(lastPageSwipeUpDismiss = checked) } },
                )
            }
        }
    }
}
