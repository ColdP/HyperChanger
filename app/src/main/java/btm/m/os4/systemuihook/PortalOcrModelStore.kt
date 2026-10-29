// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal enum class PortalModelPhase { CHECKING, MISSING, DOWNLOADING, CANCELLING, READY, DELETING, ERROR }
internal data class PortalModelState(val phase: PortalModelPhase, val downloaded: Long = 0, val deleteFailed: Boolean = false)

/** One owner in the module app; switching the feature off never removes its model. */
internal class PortalOcrModelStore private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val noBackupFilesDir = context.noBackupFilesDir
    private val directory = File(context.noBackupFilesDir, "portal-ocr-multilingual-v1")
    private val installedModels = File(directory, "models")
    private val staging = File(directory, "staging")
    private val model = File(directory, PORTAL_OCR_MODEL_NAME)
    private val partial = File(model.parentFile, "$PORTAL_OCR_MODEL_NAME.part")
    private val files = ReentrantReadWriteLock()
    private val gate = Any()
    private class Attempt {
        val cancelled = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
    }
    private var attempt: Attempt? = null
    private val mutableState = MutableStateFlow(PortalModelState(PortalModelPhase.CHECKING))
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            val ready = files.write { runCatching {
                // One-way migration: the old Japanese-only file must never be loaded.
                if (!removeLegacyPortalOcrModel(noBackupFilesDir)) ModuleLog.w("PortalOcr", "Legacy model cleanup failed")
                partial.delete() // An app/process exit may have left an incomplete download.
                staging.deleteRecursively()
                validPortalOcrModel(model) && validPortalOcrLanguages(installedModels)
            }.getOrDefault(false) }
            mutableState.value = PortalModelState(if (ready) PortalModelPhase.READY else PortalModelPhase.MISSING)
        }
    }

    fun startDownload() = synchronized(gate) {
        if (attempt != null || mutableState.value.phase !in setOf(PortalModelPhase.MISSING, PortalModelPhase.ERROR)) return@synchronized
        val current = Attempt()
        attempt = current
        mutableState.value = PortalModelState(PortalModelPhase.DOWNLOADING)
        scope.launch {
            var ready = false
            try {
                check(requireNotNull(model.parentFile).mkdirs() || model.parentFile!!.isDirectory)
                val connection = URL(PORTAL_OCR_MODEL_URL).openConnection() as HttpURLConnection
                current.connection = connection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "HyperChanger-OCR-Model")
                check(!current.cancelled.get())
                check(connection.responseCode == HttpURLConnection.HTTP_OK)
                val length = connection.contentLengthLong
                check(length == -1L || length == PORTAL_OCR_MODEL_BYTES)
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        transferPortalOcrModel(input, output, current.cancelled::get) { count ->
                            synchronized(gate) {
                                if (!current.cancelled.get()) mutableState.value = PortalModelState(PortalModelPhase.DOWNLOADING, count)
                            }
                        }
                        output.fd.sync()
                    }
                }
                staging.deleteRecursively()
                unpackPortalOcrModel(partial, staging, current.cancelled::get)
                // Cancellation and final commit share the same lock: a cancelled transfer
                // can never subsequently publish a ready model.
                synchronized(gate) {
                    check(!current.cancelled.get())
                    files.write {
                        check(!installedModels.exists() || installedModels.deleteRecursively())
                        Files.move(staging.toPath(), installedModels.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        Files.move(partial.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                    ready = true
                }
            } catch (_: Exception) {
                // Show a localized retry state, never raw connection errors to the user.
            } finally {
                current.connection?.disconnect()
                partial.delete()
                staging.deleteRecursively()
                synchronized(gate) {
                    attempt = null
                    mutableState.value = PortalModelState(when {
                        ready -> PortalModelPhase.READY
                        current.cancelled.get() -> PortalModelPhase.MISSING
                        else -> PortalModelPhase.ERROR
                    })
                }
            }
        }
    }

    fun cancelDownload() = synchronized(gate) {
        val current = attempt ?: return@synchronized
        current.cancelled.set(true)
        mutableState.value = PortalModelState(PortalModelPhase.CANCELLING, mutableState.value.downloaded)
        scope.launch { current.connection?.disconnect() }
    }

    fun deleteModel() = synchronized(gate) {
        if (attempt != null || mutableState.value.phase != PortalModelPhase.READY) return@synchronized
        mutableState.value = PortalModelState(PortalModelPhase.DELETING)
        scope.launch {
            val deleted = files.write { runCatching {
                val removedLanguages = !installedModels.exists() || installedModels.deleteRecursively()
                val removedArchive = !model.exists() || model.delete()
                removedLanguages && removedArchive
            }.getOrDefault(false) }
            mutableState.value = PortalModelState(if (deleted) PortalModelPhase.MISSING else PortalModelPhase.READY, deleteFailed = !deleted)
            // A failed delete leaves the READY state; the UI lets the user retry.
        }
    }

    fun <T> withModel(action: (File) -> T): T? = files.read {
        if (!runCatching { validPortalOcrModel(model) && validPortalOcrLanguages(installedModels) }.getOrDefault(false)) return@read null
        action(installedModels)
    }

    companion object {
        @Volatile private var instance: PortalOcrModelStore? = null
        fun get(context: Context): PortalOcrModelStore = instance ?: synchronized(this) {
            instance ?: PortalOcrModelStore(context.applicationContext).also { instance = it }
        }
    }
}
