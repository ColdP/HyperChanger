// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RestartFailureDialog() {
    val failure by SystemUiRestarter.failure.collectAsStateWithLifecycle()
    var lastFailure by remember { mutableStateOf<SystemUiRestarter.Failure?>(null) }
    // Retain the text while WindowDialog finishes its dismissal animation.
    SideEffect { if (failure != null) lastFailure = failure }
    val displayed = failure ?: lastFailure
    val dismiss = { displayed?.let(SystemUiRestarter::dismissFailure); Unit }
    WindowDialog(
        show = failure != null,
        title = displayed?.title,
        titleTextAlign = TextAlign.Start,
        onDismissRequest = dismiss,
    ) {
        key(displayed) {
            Text(
                text = displayed?.message.orEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Start,
            )
        }
        Spacer(Modifier.height(16.dp))
        GlassDialogButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) {
            Text(tr("确定", "确定"))
        }
    }
}
