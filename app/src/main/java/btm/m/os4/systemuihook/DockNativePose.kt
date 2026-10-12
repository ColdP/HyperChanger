// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

internal const val DOCK_POSE_TRANSACTION = 0x00484374
internal const val DOCK_POSE_MAX_AGE_NS = 200_000_000L

internal data class DockNativePose(
    val uid: Int, val pid: Int, val sequence: Long, val timestamp: Long,
    val scale: Float, val alpha: Float, val x: Float, val y: Float, val publishHits: Long,
)

internal fun decodeDockNativePose(
    uid: Int, pid: Int, wire: LongArray, previousSequence: Long, now: Long,
): DockNativePose? {
    if (wire.size != 8 || uid < 10000 || pid <= 0 || wire[0] <= previousSequence ||
        wire[1] < 0 || wire[1] > now || now - wire[1] > DOCK_POSE_MAX_AGE_NS ||
        wire[6] < 0 || wire[7] <= 0 || wire[7] > wire[6]) return null
    val values = (2..5).map { Double.fromBits(wire[it]) }
    if (values.any { !it.isFinite() } || values[0] !in 0.0..2.0 || values[1] !in 0.0..1.2 ||
        values[2] !in -4096.0..4096.0 || values[3] !in -4096.0..4096.0) return null
    return DockNativePose(uid, pid, wire[0], wire[1], values[0].toFloat(),
        values[1].toFloat().coerceIn(0f, 1f), values[2].toFloat(), values[3].toFloat(), wire[7])
}
