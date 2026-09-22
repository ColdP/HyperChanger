package btm.m.os4.systemuihook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons

private var regularCheck: ImageVector? = null

/** MIUIX system Check, kept as an icon extension like the bundled MIUIX icon set. */
val MiuixIcons.Regular.Check: ImageVector
    get() = regularCheck ?: ImageVector.Builder(
        name = "Check",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(9.15f, 18.1f)
            lineTo(3.7f, 12.65f)
            lineTo(5.45f, 10.9f)
            lineTo(9.15f, 14.6f)
            lineTo(18.55f, 5.2f)
            lineTo(20.3f, 6.95f)
            close()
        }
    }.build().also { regularCheck = it }
