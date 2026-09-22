// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal object UpdateInstaller {
    private const val TAG = "HyperChangerUpdate"
    private const val PREFS = "software_update"
    private const val METHOD = "root_pm_staged"
    private const val ROOT_TIMEOUT_SECONDS = 8L

    internal data class Request(
        val downloadId: Long,
        val expectedVersionCode: Long,
        val expectedVersionName: String,
    )

    internal enum class Failure {
        DOWNLOAD_NOT_FOUND,
        FILE_MISSING,
        FILE_EMPTY,
        APK_UNREADABLE,
        PACKAGE_MISMATCH,
        ROOT_UNAVAILABLE,
        STAGING_FAILED,
        PM_FAILED,
        PROCESS_FAILED,
    }

    internal data class ApkDetails(
        val file: File,
        val temporaryCopy: Boolean,
        val size: Long,
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
    )

    internal data class Result(
        val success: Boolean,
        val failure: Failure?,
        val detail: String,
        val stage: String,
        val suStarted: Boolean,
        val suExitCode: Int?,
        val pmExitCode: Int?,
        val stdout: String,
        val stderr: String,
        val apkPath: String,
        val apkSize: Long,
        val versionCode: Long,
        val versionName: String,
        val method: String = METHOD,
    ) {
        fun diagnostic(): String = buildString {
            appendLine(if (success) "Install succeeded" else "Install failed")
            appendLine("stage=$stage")
            appendLine("suStarted=$suStarted")
            appendLine("suExitCode=${suExitCode ?: "not_available"}")
            appendLine("pmExitCode=${pmExitCode ?: "not_available"}")
            appendLine("stdout=${stdout.ifBlank { "<empty>" }}")
            appendLine("stderr=${stderr.ifBlank { "<empty>" }}")
            appendLine("apk=$apkPath")
            appendLine("size=$apkSize")
            appendLine("versionCode=$versionCode")
            appendLine("versionName=$versionName")
            append("method=$method")
        }
    }

    internal data class RootStatus(
        val available: Boolean,
        val suStarted: Boolean,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
    )

    private data class CommandResult(
        val started: Boolean,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val exception: Throwable? = null,
    )

    internal fun checkRoot(): RootStatus {
        val result = runSu("id; command -v pm")
        val available = result.started && result.exitCode == 0 &&
            Regex("(?:^|\\s)uid=0(?:\\D|$)").containsMatchIn(result.stdout) &&
            result.stdout.lineSequence().any { it.trim().endsWith("/pm") || it.trim() == "pm" }
        if (!available) {
            Log.w(TAG, "Root probe failed: started=${result.started}, exitCode=${result.exitCode}, stdout=${result.stdout}, stderr=${result.stderr}", result.exception)
        }
        return RootStatus(available, result.started, result.exitCode, result.stdout, result.stderr)
    }

    internal fun validate(context: Context, request: Request): Pair<ApkDetails?, Result?> {
        val resolved = resolveDownloadedFile(context, request.downloadId)
            ?: return null to failure(request, Failure.DOWNLOAD_NOT_FOUND, "resolve_download", "DownloadManager has no readable file")
        val file = resolved.first
        val temporaryCopy = resolved.second
        fun fail(reason: Failure, stage: String, detail: String): Pair<ApkDetails?, Result?> {
            if (temporaryCopy) file.delete()
            return null to failure(request, reason, stage, detail, file)
        }
        if (!file.exists() || !file.isFile || !file.canRead()) return fail(Failure.FILE_MISSING, "file_check", "APK does not exist or is not readable")
        val size = file.length()
        if (size <= 0L) return fail(Failure.FILE_EMPTY, "file_check", "APK size is zero")
        val archive = archivePackageInfo(context.packageManager, file)
            ?: return fail(Failure.APK_UNREADABLE, "apk_parse", "PackageManager could not parse the APK")
        if (archive.packageName != context.packageName) {
            return fail(Failure.PACKAGE_MISMATCH, "package_check", "Expected ${context.packageName}, got ${archive.packageName}")
        }
        val archiveVersion = archive.longVersionCode
        return ApkDetails(
            file = file,
            temporaryCopy = temporaryCopy,
            size = size,
            packageName = archive.packageName,
            versionCode = archiveVersion,
            versionName = archive.versionName.orEmpty(),
        ) to null
    }

    internal fun install(context: Context, request: Request): Result {
        val (apk, validationFailure) = validate(context, request)
        if (validationFailure != null) return log(validationFailure)
        apk ?: return log(failure(request, Failure.PROCESS_FAILED, "validation", "Validation returned no APK"))
        try {
            val root = checkRoot()
            if (!root.available) {
                return log(Result(false, Failure.ROOT_UNAVAILABLE, "Root shell or pm is unavailable", "root_probe", root.suStarted, root.exitCode, null, root.stdout, root.stderr, apk.file.absolutePath, apk.size, apk.versionCode, apk.versionName))
            }
            markPending(context, apk)
            val paths = rootPaths(context)
            val command = buildInstallCommand(apk.file.absolutePath, paths)
            Log.i(TAG, "Starting silent install: apk=${apk.file.absolutePath}, size=${apk.size}, versionCode=${apk.versionCode}, versionName=${apk.versionName}, method=$METHOD")
            val shell = runSu(command)
            val pmExitCode = readPmExitCode(shell.stdout)
            val stdout = stripInternalMarkers(shell.stdout)
            val failureText = extractPmFailure(stdout, shell.stderr)
            val success = shell.started && shell.exitCode == 0 && pmExitCode == 0 && failureText == null &&
                stdout.lineSequence().any { it.trim() == "Success" }
            val stage = when {
                !shell.started -> "su_start"
                shell.stdout.contains(INTERNAL_STAGE_COPY_FAILED) -> "stage_copy"
                else -> "pm_install"
            }
            val failure = when {
                success -> null
                !shell.started -> Failure.PROCESS_FAILED
                stage == "stage_copy" -> Failure.STAGING_FAILED
                else -> Failure.PM_FAILED
            }
            val detail = failureText ?: shell.exception?.message ?: if (success) "Success" else "Installer returned no success result"
            val result = Result(success, failure, detail, stage, shell.started, shell.exitCode, pmExitCode, stdout, shell.stderr, apk.file.absolutePath, apk.size, apk.versionCode, apk.versionName)
            if (!success) {
                clearPending(context)
                cleanupRootArtifacts(context)
            }
            return log(result)
        } finally {
            if (apk.temporaryCopy) apk.file.delete()
        }
    }

    internal fun reconcilePreviousUpdate(context: Context): Result? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val target = prefs.getLong("pending_version_code", 0L)
        if (target <= 0L) return null
        val versionName = prefs.getString("pending_version_name", "").orEmpty()
        val apkPath = prefs.getString("pending_apk_path", "").orEmpty()
        val apkSize = prefs.getLong("pending_apk_size", 0L)
        readPersistedResult(context, apkPath, apkSize, target, versionName)?.let { persisted ->
            if (persisted.success) prefs.edit().putString("last_success_version_name", versionName).commit()
            clearPending(context)
            cleanupRootArtifacts(context)
            return log(persisted)
        }
        if (BuildConfig.VERSION_CODE.toLong() >= target) {
            prefs.edit().putString("last_success_version_name", versionName).commit()
            clearPending(context)
            cleanupRootArtifacts(context)
            return log(Result(true, null, "Installed version is now active", "post_restart_verify", true, 0, 0, "Success", "", apkPath, apkSize, target, versionName))
        }
        return null
    }

    private fun readPersistedResult(
        context: Context,
        apkPath: String,
        apkSize: Long,
        versionCode: Long,
        versionName: String,
    ): Result? {
        val paths = rootPaths(context)
        val command = """
            if [ -f ${paths.exitCode} ]; then
              echo $PERSIST_EXIT
              cat ${paths.exitCode}
              echo $PERSIST_STDOUT
              cat ${paths.stdout} 2>/dev/null
              echo $PERSIST_STDERR
              cat ${paths.stderr} 2>/dev/null
              echo $PERSIST_END
            fi
        """.trimIndent()
        val shell = runSu(command)
        if (!shell.started || !shell.stdout.contains(PERSIST_EXIT)) return null
        val pmExit = section(shell.stdout, PERSIST_EXIT, PERSIST_STDOUT).trim().toIntOrNull() ?: return null
        val stdout = section(shell.stdout, PERSIST_STDOUT, PERSIST_STDERR).trim()
        val stderr = section(shell.stdout, PERSIST_STDERR, PERSIST_END).trim()
        val failureText = extractPmFailure(stdout, stderr)
        val success = pmExit == 0 && failureText == null && stdout.lineSequence().any { it.trim() == "Success" }
        val stage = if (pmExit in setOf(125, 126)) "stage_copy" else "pm_install"
        return Result(
            success = success,
            failure = if (success) null else if (stage == "stage_copy") Failure.STAGING_FAILED else Failure.PM_FAILED,
            detail = failureText ?: if (success) "Success" else "Installer returned no success result",
            stage = "post_restart_$stage",
            suStarted = true,
            suExitCode = pmExit,
            pmExitCode = pmExit,
            stdout = stdout,
            stderr = stderr,
            apkPath = apkPath,
            apkSize = apkSize,
            versionCode = versionCode,
            versionName = versionName,
        )
    }

    private fun section(value: String, start: String, end: String): String =
        value.substringAfter(start, "").substringBefore(end, "")

    internal fun consumeSuccessNotice(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val versionName = prefs.getString("last_success_version_name", null) ?: return null
        prefs.edit().remove("last_success_version_name").apply()
        return versionName
    }

    internal fun discardTemporaryCopy(apk: ApkDetails?) {
        if (apk?.temporaryCopy == true) apk.file.delete()
    }

    private fun resolveDownloadedFile(context: Context, downloadId: Long): Pair<File, Boolean>? {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return null
        val localUri = manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        } ?: return null
        val uri = Uri.parse(localUri)
        if (uri.scheme == "file") return uri.path?.let { File(it) to false }
        val copy = File(context.cacheDir, "pending-update.apk")
        return runCatching {
            manager.openDownloadedFile(downloadId).use { descriptor ->
                FileOutputStream(copy).use { output ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> input.copyTo(output) }
                }
            }
            copy to true
        }.getOrNull()
    }

    private fun archivePackageInfo(pm: PackageManager, file: File): PackageInfo? =
        pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0L))

    private fun buildInstallCommand(sourcePath: String, paths: RootPaths): String {
        val source = shellQuote(sourcePath)
        return """
            rm -f ${paths.stagedApk} ${paths.stdout} ${paths.stderr} ${paths.exitCode}
            if ! cp $source ${paths.stagedApk} 2>${paths.stderr}; then
              if ! cat $source > ${paths.stagedApk} 2>>${paths.stderr}; then
                echo $INTERNAL_STAGE_COPY_FAILED
                echo 125 > ${paths.exitCode}
                exit 125
              fi
            fi
            chmod 0644 ${paths.stagedApk} 2>>${paths.stderr} || { echo $INTERNAL_STAGE_COPY_FAILED; echo 126 > ${paths.exitCode}; rm -f ${paths.stagedApk}; exit 126; }
            pm install -r ${paths.stagedApk} >${paths.stdout} 2>>${paths.stderr}
            pm_exit=${'$'}?
            echo ${'$'}pm_exit > ${paths.exitCode}
            rm -f ${paths.stagedApk}
            echo $INTERNAL_PM_EXIT${'$'}pm_exit
            cat ${paths.stdout}
            cat ${paths.stderr} >&2
            exit ${'$'}pm_exit
        """.trimIndent()
    }

    private fun runSu(command: String): CommandResult = try {
        val process = ProcessBuilder("su", "-c", command).start()
        var stdout = ""
        var stderr = ""
        val stdoutReader = thread(name = "update-su-stdout") { stdout = process.inputStream.bufferedReader().use { it.readText() } }
        val stderrReader = thread(name = "update-su-stderr") { stderr = process.errorStream.bufferedReader().use { it.readText() } }
        val finished = if (command == "id; command -v pm") process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS) else {
            process.waitFor()
            true
        }
        if (!finished) process.destroy()
        stdoutReader.join()
        stderrReader.join()
        CommandResult(true, if (finished) process.exitValue() else null, stdout.trim(), stderr.trim())
    } catch (error: Throwable) {
        CommandResult(false, null, "", "", error)
    }

    private fun readPmExitCode(stdout: String): Int? = Regex("(?m)^${Regex.escape(INTERNAL_PM_EXIT)}(-?\\d+)${'$'}")
        .find(stdout)?.groupValues?.get(1)?.toIntOrNull()

    private fun stripInternalMarkers(stdout: String): String = stdout.lineSequence()
        .filterNot { it.startsWith(INTERNAL_PM_EXIT) || it == INTERNAL_STAGE_COPY_FAILED }
        .joinToString("\n").trim()

    private fun extractPmFailure(stdout: String, stderr: String): String? {
        val combined = sequenceOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
        Regex("(?im)^Failure\\s*\\[([^]]+)]").find(combined)?.let { return it.groupValues[1].trim() }
        Regex("(?im)\\b(INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z0-9_]+)(?::\\s*([^\\r\\n]+))?").find(combined)?.let {
            return listOf(it.groupValues[1], it.groupValues.getOrNull(2).orEmpty()).filter { value -> value.isNotBlank() }.joinToString(": ")
        }
        Regex("(?im)^(?:Error:|Exception:|java\\.|Permission denied|Security exception).*$").find(combined)?.let { return it.value.trim() }
        return if (Regex("(?im)^Failure(?:\\s|$)").containsMatchIn(combined)) combined.trim() else null
    }

    private fun markPending(context: Context, apk: ApkDetails) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("pending_version_code", apk.versionCode)
            .putString("pending_version_name", apk.versionName)
            .putString("pending_apk_path", apk.file.absolutePath)
            .putLong("pending_apk_size", apk.size)
            .commit()
    }

    private fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("pending_version_code")
            .remove("pending_version_name")
            .remove("pending_apk_path")
            .remove("pending_apk_size")
            .apply()
    }

    private fun cleanupRootArtifacts(context: Context) {
        val paths = rootPaths(context)
        runSu("rm -f ${paths.stagedApk} ${paths.stdout} ${paths.stderr} ${paths.exitCode}")
    }

    private data class RootPaths(val stagedApk: String, val stdout: String, val stderr: String, val exitCode: String)

    private fun rootPaths(context: Context): RootPaths {
        val prefix = "/data/local/tmp/hyperchanger-update-${context.packageName.hashCode().toUInt()}"
        return RootPaths("$prefix.apk", "$prefix.stdout", "$prefix.stderr", "$prefix.exit")
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun failure(request: Request, reason: Failure, stage: String, detail: String, file: File? = null) = Result(
        false, reason, detail, stage, false, null, null, "", "", file?.absolutePath.orEmpty(), file?.length() ?: 0L,
        request.expectedVersionCode, request.expectedVersionName,
    )

    private fun log(result: Result): Result {
        if (result.success) Log.i(TAG, result.diagnostic()) else Log.e(TAG, result.diagnostic())
        return result
    }

    private const val INTERNAL_PM_EXIT = "__HYPERCHANGER_PM_EXIT__="
    private const val INTERNAL_STAGE_COPY_FAILED = "__HYPERCHANGER_STAGE_COPY_FAILED__"
    private const val PERSIST_EXIT = "__HYPERCHANGER_EXIT_BEGIN__"
    private const val PERSIST_STDOUT = "__HYPERCHANGER_STDOUT_BEGIN__"
    private const val PERSIST_STDERR = "__HYPERCHANGER_STDERR_BEGIN__"
    private const val PERSIST_END = "__HYPERCHANGER_RESULT_END__"
}
