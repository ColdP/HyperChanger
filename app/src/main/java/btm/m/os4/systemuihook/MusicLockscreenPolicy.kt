// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

/** Separate feature ownership: saved preferences are never rewritten by a conflict. */
internal data class MusicLockscreenPolicy(
    val checked: Boolean = false,
    val coverConflict: Boolean = false,
    val lyricsConflict: Boolean = false,
    val capsuleConflict: Boolean = false,
) {
    val musicBlocked get() = coverConflict && lyricsConflict
    fun cover(master: Boolean, requested: Boolean) = checked && master && requested && !coverConflict
    fun lyrics(master: Boolean, requested: Boolean) = checked && master && requested && !lyricsConflict
    fun capsule(requested: Boolean) = checked && requested && !capsuleConflict
    val sceneLayout get() = checked && !coverConflict && !lyricsConflict
}
