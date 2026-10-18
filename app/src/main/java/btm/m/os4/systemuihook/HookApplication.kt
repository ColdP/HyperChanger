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
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
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
