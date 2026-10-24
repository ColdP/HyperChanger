// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

internal data class RearScreenshotDisplay(val physicalId: String, val width: Int, val height: Int)

/** screenrecord takes an unsigned physical ID, not DisplayManager's logical display ID. */
internal fun resolveRearScreenshotDisplay(displayDump: String, surfaceFlingerDump: String): RearScreenshotDisplay? {
    val physicalIds = Regex("(?m)^\\s*Display (\\d+) \\(HWC display ")
        .findAll(surfaceFlingerDump).map { it.groupValues[1] }.toSet()
    val viewports = Regex("DisplayViewport\\{([^}]+)}").findAll(displayDump).map { it.groupValues[1] }.toList()
    val mainPhysicalIds = viewports.filter { Regex("\\bdisplayId=0\\b").containsMatchIn(it) }
        .mapNotNull { Regex("uniqueId=['\"]local:(\\d+)['\"]").find(it)?.groupValues?.get(1) }.toSet()
    // Rear-screen activities in HyperOS use logical display 1. Map it through uniqueId,
    // including when the rear screen is dozing; never substitute an unrelated display.
    val rear = viewports.singleOrNull {
        Regex("\\bdisplayId=1\\b").containsMatchIn(it) && it.contains("type=INTERNAL") && it.contains("valid=true")
    } ?: return null
    val physicalId = Regex("uniqueId=['\"]local:(\\d+)['\"]").find(rear)?.groupValues?.get(1) ?: return null
    if (physicalId !in physicalIds || physicalId in mainPhysicalIds || physicalId.toULongOrNull() == null) return null
    val width = Regex("\\bdeviceWidth=(\\d+)").find(rear)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val height = Regex("\\bdeviceHeight=(\\d+)").find(rear)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return RearScreenshotDisplay(physicalId, width, height)
}
