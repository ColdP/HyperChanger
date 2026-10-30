// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class HookFramework(val managerPackage: String, val managerActivity: String) {
    VECTOR("org.matrix.vector.manager", "org.matrix.vector.manager.ui.MainActivity"),
    LSPOSED("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity"),
}

internal data class HookFrameworkInfo(val name: String?, val version: String?, val api: Int?) {
    val framework: HookFramework? get() = identifyHookFramework(name)
}

internal fun identifyHookFramework(name: String?): HookFramework? = when {
    name?.trim()?.equals("Vector", ignoreCase = true) == true -> HookFramework.VECTOR
    name?.trim()?.startsWith("Vector ", ignoreCase = true) == true -> HookFramework.VECTOR
    name?.contains("LSPosed", ignoreCase = true) == true -> HookFramework.LSPOSED
    else -> null
}

/** Read the connected service, never an installed manager's unrelated APK version. */
internal fun readHookFrameworkInfo(service: XposedService?): HookFrameworkInfo? {
    if (service == null) return null
    return HookFrameworkInfo(
        runCatching { service.frameworkName.trim().takeIf(String::isNotBlank) }.getOrNull(),
        runCatching { service.frameworkVersion.trim().takeIf(String::isNotBlank) }.getOrNull(),
        runCatching { service.apiVersion }.getOrNull(),
    )
}

internal fun hookFrameworkSummary(info: HookFrameworkInfo?): String =
    tr("framework_summary", "%s %s（API %s）").format(
        info?.name ?: tr("framework_unknown_name", "Xposed 框架"),
        info?.version ?: tr("未检测到", "未检测到"),
        info?.api?.toString() ?: tr("未检测到", "未检测到"),
    )

// Used only to choose an offline manager, never to infer that this module is connected.
internal val activeHookFrameworkProbe = """
    for directory in /data/adb/modules/*; do
        [ -f "${'$'}directory/module.prop" ] || continue
        [ -f "${'$'}directory/disable" ] && continue
        [ -f "${'$'}directory/remove" ] && continue
        id=''; name=''
        while IFS='=' read -r key value; do
            case "${'$'}key" in id) id="${'$'}value" ;; name) name="${'$'}value" ;; esac
        done < "${'$'}directory/module.prop"
        case "${'$'}id:${'$'}name" in
            zygisk_vector:*|*:Vector) printf 'VECTOR\n' ;;
            *lsposed*|*LSPosed*|*LSPOSED*) printf 'LSPOSED\n' ;;
        esac
    done
""".trimIndent()

internal fun offlineHookFramework(output: String): HookFramework? =
    output.lineSequence().map(String::trim).mapNotNull { line ->
        HookFramework.entries.firstOrNull { it.name == line }
    }.distinct().toList().singleOrNull()

internal fun hookManagerIntents(context: Context, framework: HookFramework): List<Intent> {
    val pm = context.packageManager
    val standalone = runCatching { pm.getLaunchIntentForPackage(framework.managerPackage) }
        .getOrNull() ?: Intent(Intent.ACTION_MAIN).setClassName(framework.managerPackage, framework.managerActivity)
    // Like Vector's ManagerService, resolve INFO/LAUNCHER before the main-process fallback.
    val shellComponent = runCatching { pm.getLaunchIntentForPackage("com.android.shell")?.component }.getOrNull()
        ?: runCatching {
            pm.getPackageInfo("com.android.shell", PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
                .activities?.firstOrNull { it.exported && it.enabled && it.processName == it.packageName }
                ?.let { android.content.ComponentName(it.packageName, it.name) }
        }.getOrNull()
    return buildList {
        add(standalone)
        shellComponent?.let { add(Intent(Intent.ACTION_MAIN).setComponent(it)) }
    }.map { intent ->
        intent.apply {
            categories?.toList()?.forEach(::removeCategory)
            addCategory("${framework.managerPackage}.LAUNCH_MANAGER")
            setPackage(component?.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

internal suspend fun openHookFrameworkManager(context: Context) {
    val service = HookApplication.service.value
    val framework = withContext(Dispatchers.IO) {
        if (service != null) readHookFrameworkInfo(service)?.framework
        else runCatching {
            val result = ModuleRoot.run(activeHookFrameworkProbe, 8192)
            if (result.code == 0 && !result.truncated) offlineHookFramework(result.output) else null
        }.getOrNull()
    }
    withContext(Dispatchers.Main) {
        val intents = if (framework != null) hookManagerIntents(context, framework) else if (service == null) {
            // An unknown offline parasitic category can launch an ordinary Shell screen. Only
            // try installed standalone managers when no framework could be identified.
            HookFramework.entries.mapNotNull { runCatching {
                context.packageManager.getLaunchIntentForPackage(it.managerPackage)
            }.getOrNull() }
        } else emptyList()
        if (intents.any { runCatching { context.startActivity(it) }.isSuccess }) return@withContext
        Toast.makeText(context, tr("framework_manager_open_failed", "无法打开框架管理器，请确认 Vector 或 LSPosed 已安装并启用。"), Toast.LENGTH_SHORT).show()
    }
}
