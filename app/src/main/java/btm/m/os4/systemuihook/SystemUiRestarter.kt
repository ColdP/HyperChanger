// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

object SystemUiRestarter {
    internal data class Failure(val title: String, val message: String)
    private val failureState = MutableStateFlow<Failure?>(null)
    internal val failure = failureState.asStateFlow()

    internal fun dismissFailure(displayed: Failure) {
        failureState.compareAndSet(displayed, null)
    }

    private fun showFailure(message: String, partial: Boolean = false) {
        failureState.value = Failure(
            if (partial) tr("部分应用重启失败", "部分应用重启失败") else tr("重启失败", "重启失败"),
            message,
        )
    }

    fun restart(context: Context, targets: Set<ScopeApplication>) {
        thread(name = "scope-app-restart", isDaemon = true) {
            if (targets.isEmpty()) {
                showFailure(tr("请至少选择一个作用域应用", "请至少选择一个作用域应用"))
                return@thread
            }

            val rootCheck = runSu("id")
            if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
                showFailure(tr("未获得 Root 权限（su 被拒绝、未授权或不可用）", "未获得 Root 权限（su 被拒绝、未授权或不可用）"))
                return@thread
            }

            val failures = buildList {
                targets.forEach { target ->
                    val title = tr(target.title, target.title)
                    val packagePath = runSu("cmd package path ${target.packageName}")
                    if (packagePath.exitCode != 0 || !packagePath.output.contains("package:")) {
                        add("$title：${tr("应用未安装或包名不存在", "应用未安装或包名不存在")}")
                        return@forEach
                    }

                    if (target == ScopeApplication.SECURITY_CENTER) {
                        // The sidebar runs in :ui, and other services use independent process
                        // names. Force-stop the package even when its main PID is absent.
                        val stopResult = runSu("am force-stop --user current com.miui.securitycenter")
                        if (stopResult.exitCode != 0) {
                            val details = stopResult.errorMessage ?: stopResult.output.trim().take(200).ifBlank {
                                tr("命令退出码：%d", "命令退出码：%d").format(stopResult.exitCode)
                            }
                            add(tr("安全服务强制停止失败：%s", "安全服务强制停止失败：%s").format(details))
                        }
                        return@forEach
                    }

                    val pidResult = runSu("pidof ${target.packageName}")
                    val processIds = pidResult.output.trim()
                    if (pidResult.exitCode != 0 || processIds.isBlank()) {
                        add("$title：${tr("应用未在运行", "应用未在运行")}")
                        return@forEach
                    }

                    val killResult = runSu("kill -15 $processIds")
                    if (killResult.exitCode != 0) {
                        add("$title：${restartFailureReason(killResult)}")
                    }
                }
            }
            if (failures.isEmpty()) {
                showToast(context, tr("已重启选中的作用域应用", "已重启选中的作用域应用"))
            } else {
                val restartedCount = targets.size - failures.size
                val prefix = if (restartedCount > 0) {
                    tr("已重启 %d 个应用，以下应用重启失败：", "已重启 %d 个应用，以下应用重启失败：").format(restartedCount) + "\n\n"
                } else ""
                showFailure(prefix + failures.joinToString("\n"), partial = restartedCount > 0)
            }
        }
    }

    private fun restartFailureReason(result: CommandResult): String {
        val output = result.output.lowercase()
        return when {
            "permission denied" in output || "operation not permitted" in output ->
                tr("没有结束进程的权限", "没有结束进程的权限")
            "not found" in output -> tr("结束进程命令不可用", "结束进程命令不可用")
            result.errorMessage != null -> tr("执行命令失败：%s", "执行命令失败：%s").format(result.errorMessage)
            else -> tr("结束进程失败（退出码 %d）", "结束进程失败（退出码 %d）").format(result.exitCode)
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
    DESKTOP("\u684c\u9762", "com.miui.home"),
    WALLPAPER("\u58c1\u7eb8", "com.miui.miwallpaper"),
    AOD("\u606f\u5c4f\u4e0e\u9501\u5c4f\u7f16\u8f91", "com.miui.aod"),
    SUBSCREEN_CENTER("\u80cc\u5c4f", "com.xiaomi.subscreencenter"),
    THEME_MANAGER("\u4e3b\u9898\u58c1\u7eb8", "com.android.thememanager"),
    PERSONAL_ASSISTANT("\u667a\u80fd\u52a9\u7406", "com.miui.personalassistant"),
    GALLERY("\u76f8\u518c", "com.miui.gallery"),
    CAMERA("\u76f8\u673a", "com.android.camera"),
    MEDIA_EDITOR("\u5c0f\u7c73\u76f8\u518c-\u7f16\u8f91", "com.miui.mediaeditor"),
    SUPER_XIAOAI("超级小爱", "com.miui.voiceassist"),
    SUPER_XIAOAI_IME("\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5", "com.xiaomi.type"),
    SETTINGS("\u8bbe\u7f6e", "com.android.settings"),
    SYSTEM_UPDATE("\u7cfb\u7edf\u66f4\u65b0", "com.android.updater"),
    SCREEN_RECORDER("\u5c4f\u5e55\u5f55\u5236", "com.miui.screenrecorder"),
    SCREENSHOT("\u622a\u5c4f", "com.miui.screenshot"),
    APPLE_MUSIC("Apple Music", "com.apple.android.music"),
    NETEASE_MUSIC("网易云音乐", "com.netease.cloudmusic"),
    WEIBO("微博", "com.sina.weibo"),
    XIAOHONGSHU("小红书", "com.xingin.xhs"),
    XIAOMI_STORE("小米商城", "com.xiaomi.shop"),
    XIAOMI_WALLET("小米钱包", "com.mipay.wallet"),
    XIAOMI_HEALTH("小米运动健康", "com.mi.health"),
    LUCKIN_COFFEE("瑞幸咖啡", "com.lucky.luckyclient"),
    REDDIT("Reddit", "com.reddit.frontpage"),
    TENCENT_MEETING("腾讯会议", "com.tencent.wemeet.app"),
    DOUSHENGSHENG("抖省省", "com.ss.android.ugc.lifeservices"),
    MEITUAN("美团", "com.sankuai.meituan"),
    MEITUAN_TAKEOUT("美团外卖", "com.sankuai.meituan.takeoutnew"),
    CMB_BANK("招商银行", "cmb.pb"),
    WECHAT("微信", "com.tencent.mm"),
    QQ("QQ", "com.tencent.mobileqq"),
    PINDUODUO("拼多多", "com.xunmeng.pinduoduo"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    PINTEREST("Pinterest", "com.pinterest"),
    X("X", "com.twitter.android"),
    GITHUB("GitHub", "com.github.android"),
    CONTACTS_DIALER("拨号与通话", "com.android.contacts"),
    SECURITY_CENTER("安全服务", "com.miui.securitycenter"),
    PORTAL("传送门", "com.miui.contentextension"),
    SOUND_EFFECT("音质音效", "com.miui.misound"),
    MI_SETTINGS("小米设置", "com.xiaomi.misettings"),
    AIRDROP("小米互传", "com.miui.mishare.connectivity"),
    HTML_VIEWER("HTML 查看器", "com.android.htmlviewer"),
    JOYOSE("Joyose", "com.xiaomi.joyose"),
    SYSTEM_HELPER("系统助手", "com.mobiletools.systemhelper"),
    GUARD_PROVIDER("系统安全组件", "com.miui.guardprovider"),
}
