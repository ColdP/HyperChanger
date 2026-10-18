// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.liquidglass.hook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Paths extracted from Google's Material Symbols Rounded variable TTF. */
object MaterialRoundedPlayerIcons {
    val Play: ImageVector by lazy {
        icon(
            "MaterialRoundedPlay",
            "M320 687V273Q320 256 332 244.5Q344 233 360 233Q365 233 370.5 234.5Q376 236 381 239L707 446Q716 452 720.5 461Q725 470 725 480Q725 490 720.5 499Q716 508 707 514L381 721Q376 724 370.5 725.5Q365 727 360 727Q344 727 332 715.5Q320 704 320 687ZM400 346 610 480 400 614ZM400 614 610 480 400 346Z"
        )
    }

    val Pause: ImageVector by lazy {
        icon(
            "MaterialRoundedPause",
            "M640 760Q607 760 583.5 736.5Q560 713 560 680V280Q560 247 583.5 223.5Q607 200 640 200Q673 200 696.5 223.5Q720 247 720 280V680Q720 713 696.5 736.5Q673 760 640 760ZM320 760Q287 760 263.5 736.5Q240 713 240 680V280Q240 247 263.5 223.5Q287 200 320 200Q353 200 376.5 223.5Q400 247 400 280V680Q400 713 376.5 736.5Q353 760 320 760Z"
        )
    }

    val SkipNext: ImageVector by lazy {
        icon(
            "MaterialRoundedSkipNext",
            "M660 680V280Q660 263 671.5 251.5Q683 240 700 240Q717 240 728.5 251.5Q740 263 740 280V680Q740 697 728.5 708.5Q717 720 700 720Q683 720 671.5 708.5Q660 697 660 680ZM220 645V315Q220 297 232 286Q244 275 260 275Q265 275 271 276Q277 277 282 281L530 447Q539 453 543.5 461.5Q548 470 548 480Q548 490 543.5 498.5Q539 507 530 513L282 679Q277 683 271 684Q265 685 260 685Q244 685 232 674Q220 663 220 645ZM300 390 436 480 300 570ZM300 570 436 480 300 390Z"
        )
    }

    val SkipPrevious: ImageVector by lazy {
        icon(
            "MaterialRoundedSkipPrevious",
            "M220 680V280Q220 263 231.5 251.5Q243 240 260 240Q277 240 288.5 251.5Q300 263 300 280V680Q300 697 288.5 708.5Q277 720 260 720Q243 720 231.5 708.5Q220 697 220 680ZM678 679 430 513Q421 507 416.5 498.5Q412 490 412 480Q412 470 416.5 461.5Q421 453 430 447L678 281Q683 277 689 276Q695 275 700 275Q716 275 728 286Q740 297 740 315V645Q740 663 728 674Q716 685 700 685Q695 685 689 684Q683 683 678 679ZM660 390V570L524 480ZM660 570V390L524 480Z"
        )
    }

    private fun icon(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black)
        )
    }.build()
}
