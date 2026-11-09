// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.os4.systemuihook.SeamlessCard as Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Bundled documents use "## " for section headings in every language.
// Keep undated/older custom translations readable without dropping their text.
private data class LegalSection(val heading: String, val paragraphs: List<String>)
private val legalDateLine = Regex("^(生效日期|最近修订|Effective date|Last revised|施行日|最終改定日)[:：]")

internal fun LazyListScope.legalDocumentItems(content: String) {
    val lines = content.trim().lines()
    val title = lines.firstOrNull().orEmpty()
    val dates = mutableListOf<String>()
    var bodyStart = 1
    while (bodyStart < lines.size) {
        val line = lines[bodyStart].trim()
        if (line.isNotEmpty() && !legalDateLine.containsMatchIn(line)) break
        if (line.isNotEmpty()) dates += line
        bodyStart++
    }
    val blocks = lines.drop(bodyStart).joinToString("\n").split(Regex("\n[ \\t]*\n"))
    val sections = mutableListOf<LegalSection>()
    var sectionTitle = ""
    var paragraphs = mutableListOf<String>()
    blocks.filter(String::isNotBlank).forEach { block ->
        if (block.startsWith("## ")) {
            if (sectionTitle.isNotEmpty() || paragraphs.isNotEmpty()) {
                sections += LegalSection(sectionTitle, paragraphs.toList())
            }
            sectionTitle = block.removePrefix("## ").trim()
            paragraphs = mutableListOf()
        } else {
            paragraphs += block.trim()
        }
    }
    if (sectionTitle.isNotEmpty() || paragraphs.isNotEmpty()) {
        sections += LegalSection(sectionTitle, paragraphs.toList())
    }
    item {
        LegalReadingCard {
            Text(
                title,
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            if (dates.isNotEmpty()) {
                Text(
                    dates.joinToString("\n"),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 22.sp,
                )
            }
        }
    }
    sections.forEach { section ->
        item {
            LegalReadingCard {
                if (section.heading.isNotEmpty()) {
                    Text(
                        section.heading,
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                section.paragraphs.forEach { paragraph ->
                    Text(paragraph, style = MiuixTheme.textStyles.body1, lineHeight = 26.sp)
                }
            }
        }
    }
}

@Composable
internal fun LegalReadingCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Card(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
            cornerRadius = 22.5.dp,
            insideMargin = PaddingValues(20.dp),
        ) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    content()
                }
            }
        }
    }
}
