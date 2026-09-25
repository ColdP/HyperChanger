// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.concurrent.thread

object SystemUiRestarter {
    fun restart(context: Context, targets: Set<ScopeApplication>) {
        thread(name = "scope-app-restart", isDaemon = true) {
            if (targets.isEmpty()) {
                showToast(context, "\u8bf7\u81f3\u5c11\u9009\u62e9\u4e00\u4e2a\u4f5c\u7528\u57df\u5e94\u7528")
                return@thread
            }

            val rootCheck = runSu("id")
            if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
                showToast(
                    context,
                    "\u91cd\u542f\u5931\u8d25\uff1a\u672a\u83b7\u5f97 Root \u6743\u9650\uff08su \u88ab\u62d2\u7edd\u3001\u672a\u6388\u6743\u6216\u4e0d\u53ef\u7528\uff09",
                )
                return@thread
            }

            val failures = buildList {
                targets.forEach { target ->
                    val packagePath = runSu("cmd package path ${target.packageName}")
                    if (packagePath.exitCode != 0 || !packagePath.output.contains("package:")) {
                        add("${target.title}\uff1a\u5e94\u7528\u672a\u5b89\u88c5\u6216\u5305\u540d\u4e0d\u5b58\u5728")
                        return@forEach
                    }

                    val pidResult = runSu("pidof ${target.packageName}")
                    val processIds = pidResult.output.trim()
                    if (pidResult.exitCode != 0 || processIds.isBlank()) {
                        add("${target.title}\uff1a\u5e94\u7528\u672a\u5728\u8fd0\u884c")
                        return@forEach
                    }

                    val killResult = runSu("kill -15 $processIds")
                    if (killResult.exitCode != 0) {
                        add("${target.title}\uff1a${restartFailureReason(killResult)}")
                    }
                }
            }
            if (failures.isEmpty()) {
                showToast(context, "\u5df2\u91cd\u542f\u9009\u4e2d\u7684\u4f5c\u7528\u57df\u5e94\u7528")
            } else {
                val restartedCount = targets.size - failures.size
                val prefix = if (restartedCount > 0) {
                    "\u5df2\u91cd\u542f $restartedCount \u4e2a\uff0c\u4ee5\u4e0b\u5931\u8d25\uff1a"
                } else {
                    "\u91cd\u542f\u5931\u8d25\uff1a"
                }
                showToast(context, "$prefix\n${failures.joinToString("\n")}")
            }
        }
    }

    private fun restartFailureReason(result: CommandResult): String {
        val output = result.output.lowercase()
        return when {
            "permission denied" in output || "operation not permitted" in output ->
                "\u6ca1\u6709\u7ed3\u675f\u8fdb\u7a0b\u7684\u6743\u9650"
            "not found" in output -> "\u7ed3\u675f\u8fdb\u7a0b\u547d\u4ee4\u4e0d\u53ef\u7528"
            result.errorMessage != null -> "\u6267\u884c\u547d\u4ee4\u5931\u8d25\uff1a${result.errorMessage}"
            else -> "\u7ed3\u675f\u8fdb\u7a0b\u5931\u8d25\uff08\u9000\u51fa\u7801 ${result.exitCode}\uff09"
        }
    }

    private fun runSu(command: String): CommandResult = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        CommandResult(exitCode, output, null)
    }.getOrElse { error -> CommandResult(-1, "", error.message ?: error.javaClass.simpleName) }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val errorMessage: String?,
    )
}

enum class ScopeApplication(val title: String, val packageName: String) {
    SYSTEM_UI("\u7cfb\u7edf\u754c\u9762", "com.android.systemui"),
    WALLPAPER("\u58c1\u7eb8", "com.miui.miwallpaper"),
    AOD("\u606f\u5c4f\u4e0e\u9501\u5c4f\u7f16\u8f91", "com.miui.aod"),
    SUBSCREEN_CENTER("\u80cc\u5c4f", "com.xiaomi.subscreencenter"),
    THEME_MANAGER("\u4e3b\u9898\u58c1\u7eb8", "com.android.thememanager"),
    PERSONAL_ASSISTANT("\u667a\u80fd\u52a9\u7406", "com.miui.personalassistant"),
    GALLERY("\u76f8\u518c", "com.miui.gallery"),
    CAMERA("\u76f8\u673a", "com.android.camera"),
    MEDIA_EDITOR("\u5c0f\u7c73\u76f8\u518c-\u7f16\u8f91", "com.miui.mediaeditor"),
    SUPER_XIAOAI_IME("\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5", "com.xiaomi.type"),
    SETTINGS("\u8bbe\u7f6e", "com.android.settings"),
    SYSTEM_UPDATE("\u7cfb\u7edf\u66f4\u65b0", "com.android.updater"),
    SCREEN_RECORDER("\u5c4f\u5e55\u5f55\u5236", "com.miui.screenrecorder"),
    XIAOMI_STORE("小米商城", "com.xiaomi.shop"),
    XIAOMI_WALLET("小米钱包", "com.mipay.wallet"),
}
