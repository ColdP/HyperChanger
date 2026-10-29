// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HookApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        ModuleLogger.attachApp(this)
        ModuleLogRepository.scheduleCleanup(this)
        Thread { runCatching { ModuleLogRepository(this).cleanup() } }.start()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        runCatching { PortalSettings.syncRemote(this, service) }
            .onFailure { ModuleLog.w("Portal", "Could not sync Portal settings", it) }
        runCatching { ContactsDialerSettings.syncRemote(this, service) }
            .onFailure { ModuleLog.w("ContactsDialer", "Could not sync dialer setting", it) }
        runCatching { btm.m.liquidglass.NavigationBadgeSettings.syncRemote(this, service) }
            .onFailure { ModuleLog.w("NavigationBadges", "Could not sync navigation badge setting", it) }
        runCatching { ModuleHotReloadSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("HotReload", "Could not sync hot reload setting", it) }
        runCatching { ModuleSafetySettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("ModuleSafety", "Could not sync safety settings", it) }
        serviceState.value = service
    }

    override fun onServiceDied(service: XposedService) {
        if (serviceState.value === service) serviceState.value = null
    }

    companion object {
        private val serviceState = MutableStateFlow<XposedService?>(null)
        val service = serviceState.asStateFlow()
    }
}
