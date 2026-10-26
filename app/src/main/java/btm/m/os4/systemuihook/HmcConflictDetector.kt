// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.pm.PackageManager
import java.util.Locale

/**
 * Best-effort detection for installed standalone HyperMusicCover/HMC forks.
 *
 * Android does not expose a reliable public API for asking whether an arbitrary
 * LSPosed module is enabled. This detector therefore checks installed app
 * identity (package name + visible application label), not LSPosed activation.
 */
internal object HmcConflictDetector {
    private val hmcVariantPattern =
        Regex("""(?i)(?<![a-z0-9])hmc(?:[\s._-]*(?:e|plus|mod|fork|beta|alpha|pro|lite))?(?![a-z0-9])""")

    fun isHmcVariantInstalled(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        apps.any { app ->
            if (app.packageName == context.packageName) {
                false
            } else {
                val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault("")
                val identity = "${app.packageName} $label"
                val compact = identity.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
                val hasFullName = compact.contains("hypermusiccover")
                val hasHmcVariant = hmcVariantPattern.containsMatchIn(identity)
                hasFullName || hasHmcVariant
            }
        }
    }.getOrDefault(false)
}
