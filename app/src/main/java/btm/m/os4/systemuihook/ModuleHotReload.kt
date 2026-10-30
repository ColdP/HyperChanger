// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal enum class ProcessReloadOutcome {
    SUCCEEDED, FAILED, UNSUPPORTED, IN_PROGRESS, PROCESS_DIED, SERVICE_ERROR, TIMED_OUT,
}

/** Ignore duplicate/late callbacks and bound requests whose framework never replies. */
internal suspend fun awaitProcessReload(
    timeoutMillis: Long = 15_000,
    request: ((ProcessReloadOutcome) -> Unit) -> Unit,
): ProcessReloadOutcome = withTimeoutOrNull(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val delivered = AtomicBoolean(false)
        val complete: (ProcessReloadOutcome) -> Unit = { outcome ->
            if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(outcome)
        }
        continuation.invokeOnCancellation { delivered.set(true) }
        try {
            request(complete)
        } catch (_: UnsupportedOperationException) {
            complete(ProcessReloadOutcome.UNSUPPORTED)
        } catch (_: Exception) {
            complete(ProcessReloadOutcome.SERVICE_ERROR)
        } catch (_: LinkageError) {
            complete(ProcessReloadOutcome.UNSUPPORTED)
        }
    }
} ?: ProcessReloadOutcome.TIMED_OUT

internal suspend fun reloadModuleProcesses(
    service: XposedService,
    targets: List<HookedTarget>,
): List<Pair<HookedTarget, ProcessReloadOutcome>> = supervisorScope {
    targets.distinctBy { Triple(it.uid, it.pid, it.processName) }.map { target ->
        async(Dispatchers.IO) {
            target to awaitProcessReload { complete ->
                service.hotReloadModule(target, null) { _, result ->
                    complete(when (result.status()) {
                        HotReloadResult.Status.SUCCEEDED -> ProcessReloadOutcome.SUCCEEDED
                        HotReloadResult.Status.FAILED -> ProcessReloadOutcome.FAILED
                        HotReloadResult.Status.UNSUPPORTED -> ProcessReloadOutcome.UNSUPPORTED
                        HotReloadResult.Status.IN_PROGRESS -> ProcessReloadOutcome.IN_PROGRESS
                        HotReloadResult.Status.PROCESS_DIED -> ProcessReloadOutcome.PROCESS_DIED
                    })
                }
            }
        }
    }.awaitAll()
}

internal fun processReloadOutcomeText(outcome: ProcessReloadOutcome): String = when (outcome) {
    ProcessReloadOutcome.SUCCEEDED -> tr("hotReloadSucceeded", "重载成功")
    ProcessReloadOutcome.FAILED -> tr("hotReloadFailed", "重载失败或被模块拒绝")
    ProcessReloadOutcome.UNSUPPORTED -> tr("hotReloadUnsupported", "当前框架或模块不支持热重载")
    ProcessReloadOutcome.IN_PROGRESS -> tr("hotReloadInProgress", "此进程已有重载请求，请稍后重试")
    ProcessReloadOutcome.PROCESS_DIED -> tr("hotReloadProcessDied", "目标进程已退出")
    ProcessReloadOutcome.SERVICE_ERROR -> tr("hotReloadServiceError", "框架通信失败，请检查框架连接")
    ProcessReloadOutcome.TIMED_OUT -> tr("hotReloadTimedOut", "等待结果超时，无法确认是否完成，请勿重复提交")
}
