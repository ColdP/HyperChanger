// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Collect at export time so the report reflects LSPosed's current checked scope. */
internal fun collectModuleLogEnvironment(context: Context): String {
    val unknown = tr("logEnvironmentUnknown", "未知")
    val service = HookApplication.service.value
    val api = runCatching { service?.apiVersion?.toString() }.getOrNull() ?: unknown
    val scopes = runCatching { service?.scope?.toList() }.getOrNull()
    val marketName = OsCompatibility.systemProperty("ro.product.marketname").ifBlank { Build.MODEL }
    val osCode = OsCompatibility.systemProperty("ro.mi.os.version.code").ifBlank { unknown }
    val osIncremental = OsCompatibility.systemProperty("ro.mi.os.version.incremental").ifBlank { unknown }
    val lines = mutableListOf(
        tr("logEnvironmentModule", "HyperChanger %s (%s)").format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        tr("logEnvironmentAndroid", "Android %s (SDK %s)").format(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
        tr("logEnvironmentDevice", "%s (%s)").format(marketName, Build.MODEL),
        Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { unknown },
        tr("logEnvironmentFramework", "LSPosed API %s / %s").format(api, readLogZygiskVersion()
            ?: tr("safetyZygiskUnknown", "Zygisk (Next)：未检测到版本")),
        tr("logEnvironmentHyperOs", "HyperOS %s / %s").format(osCode, osIncremental),
    )
    if (scopes == null) {
        lines += tr("logEnvironmentScopeUnavailable", "无法读取 LSPosed 已勾选的作用域")
    } else {
        val pm = context.packageManager
        scopes.distinct().sorted().forEach { scope ->
            // LSPosed calls the framework scope "system"; Android's package owns its metadata.
            val metadataPackage = if (scope == "system") "android" else scope
            val info = runCatching {
                pm.getPackageInfo(metadataPackage, PackageManager.PackageInfoFlags.of(0))
            }.getOrNull()
            val name = runCatching {
                val app = info?.applicationInfo ?: pm.getApplicationInfo(metadataPackage, PackageManager.ApplicationInfoFlags.of(0))
                pm.getApplicationLabel(app).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: unknown
            lines += tr("logEnvironmentScope", "%s / %s / %s (%s)").format(
                name, scope, info?.versionName?.takeIf { it.isNotBlank() } ?: unknown,
                info?.longVersionCode?.toString() ?: unknown,
            )
        }
    }
    // An application label must not insert extra rows into the exported report.
    return lines.joinToString("\n", postfix = "\n") { it.replace(Regex("[\\r\\n]+"), " ") }
}

private fun readLogZygiskVersion(): String? = runCatching {
    val result = ModuleRoot.run("""
        for directory in /data/adb/modules/*; do
          [ -f "${'$'}directory/module.prop" ] || continue
          [ ! -f "${'$'}directory/disable" ] || continue
          [ ! -f "${'$'}directory/remove" ] || continue
          case "${'$'}directory" in *zygisk*|*rezygisk*)
            name=''; version=''
            while IFS='=' read -r key value; do
              case "${'$'}key" in name) name="${'$'}value";; version) version="${'$'}value";; esac
            done < "${'$'}directory/module.prop"
            [ -n "${'$'}name" ] || name=Zygisk
            printf '%s %s\n' "${'$'}name" "${'$'}version";;
          esac
        done
    """.trimIndent(), 8192)
    if (result.code != 0 || result.truncated) return@runCatching null
    result.output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
        .joinToString("; ").takeIf { it.isNotBlank() }
}.getOrNull()
