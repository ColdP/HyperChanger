// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HookApplication : Application(), XposedServiceHelper.OnServiceListener {
    internal val nativeBoseSupport by lazy { NativeBoseSupportStore(this) }

    override fun onCreate() {
        super.onCreate()
        ModuleLogger.attachApp(this)
        ModuleLogRepository.scheduleCleanup(this)
        Thread { runCatching { ModuleLogRepository(this).cleanup() } }.start()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        runCatching { nativeBoseSupport.syncRemote(service) }
            .onFailure { ModuleLog.w("NativeBose", "Could not sync native BOSE device list", it) }
        runCatching { PersonalAssistantSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("PersonalAssistant", "Could not sync personal assistant settings", it) }
        runCatching { PowerBatterySettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("PowerBattery", "Could not sync battery settings", it) }
        runCatching { SidebarFeatureSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("SidebarFeatures", "Could not sync temperature settings", it) }
        runCatching { WidgetGlassSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("WidgetGlass", "Could not sync widget settings", it) }
        runCatching { SidebarSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("Sidebar", "Could not sync sidebar settings", it) }
        runCatching { SidebarAppearanceSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("SidebarAppearance", "Could not sync sidebar appearance settings", it) }
        runCatching { SidebarShapeSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("SidebarShape", "Could not sync sidebar shape settings", it) }
        runCatching { SidebarColumnSpacingSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("SidebarSpacing", "Could not sync sidebar column spacing", it) }
        runCatching { SidebarIconCountSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("SidebarIconCount", "Could not sync sidebar icon count settings", it) }
        runCatching { PortalSettings.syncRemote(this, service) }
            .onFailure { ModuleLog.w("Portal", "Could not sync Portal settings", it) }
        runCatching { ContactsDialerSettings.syncRemote(this, service) }
            .onFailure { ModuleLog.w("ContactsDialer", "Could not sync dialer setting", it) }
        runCatching { AppNavigationSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("AppNavigation", "Could not sync app navigation settings", it) }
        runCatching { btm.m.liquidglass.NavigationBadgeSettings.syncRemote(this, service) }
            .onFailure { ModuleLog.w("NavigationBadges", "Could not sync navigation badge setting", it) }
        runCatching { ModuleHotReloadSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("HotReload", "Could not sync hot reload setting", it) }
        runCatching { ModuleSafetySettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("ModuleSafety", "Could not sync safety settings", it) }
        runCatching { CustomDepthWallpaperSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("CustomDepthWallpaper", "Could not sync custom depth wallpaper settings", it) }
        runCatching { HyperMusicCoverSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("HyperMusicCover", "Could not sync music settings", it) }
        runCatching { LockscreenCapsuleNotificationSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("HyperMusicCover", "Could not sync capsule notification types", it) }
        runCatching { LockscreenCapsuleBehaviorSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("HyperMusicCover", "Could not sync capsule behavior", it) }
        runCatching { MusicLockscreenSettingsStore(this).syncRemote(service) }
            .onFailure { ModuleLog.w("HyperMusicCover", "Could not sync music lockscreen master", it) }
        serviceState.value = service
    }

    override fun onServiceDied(service: XposedService) {
        nativeBoseSupport.onServiceDied(service)
        if (serviceState.value === service) serviceState.value = null
    }

    companion object {
        private val serviceState = MutableStateFlow<XposedService?>(null)
        val service = serviceState.asStateFlow()
    }
}
