// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

/** Remember the system's visibility before suppression; expansion must not override
 * lockscreen and other stock conditions that already hide the footer.
 */
internal class VolumePanelRingerVisibility {
    private var systemVisibility: Int? = null
    private var suppressed = false

    fun resolve(current: Int, systemUpdated: Boolean, hide: Boolean, expanded: Boolean, controlCenter: Boolean): Int {
        if (systemUpdated || !suppressed) systemVisibility = current
        suppressed = hide && !expanded && !controlCenter
        return if (suppressed) 8 else systemVisibility ?: current
    }
}
