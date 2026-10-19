// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.LocaleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList

internal fun applicationLabelLocales(context: Context): LocaleList =
    context.getSystemService(LocaleManager::class.java)?.systemLocales?.takeUnless { it.isEmpty }
        ?: Resources.getSystem().configuration.locales.takeUnless { it.isEmpty }
        ?: LocaleList.getDefault()

/** Resolve another app's label using the device language, independent of module resources
 * and PackageManager's label cache. Never change the target's shared Resources configuration.
 */
internal fun localizedApplicationLabel(context: Context, info: ApplicationInfo, locales: LocaleList): String {
    info.nonLocalizedLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (info.labelRes != 0) {
        runCatching {
            val packageContext = context.createPackageContext(info.packageName, 0)
            val configuration = Configuration(packageContext.resources.configuration).apply { setLocales(locales) }
            packageContext.createConfigurationContext(configuration).resources.getText(info.labelRes).toString().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return runCatching { info.loadLabel(context.packageManager).toString().trim() }
        .getOrNull()?.takeIf { it.isNotEmpty() } ?: info.packageName
}
