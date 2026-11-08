// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

/**
 * Camera and Gallery Editor Xposed entry point.
 *
 * The lifecycle is intentionally inherited from [LeicaUnlockHookRuntime] so
 * the package/process dispatch and delayed ClassLoader installation retain
 * their original ordering while the public entry point lives in the module's
 * Kotlin namespace. Version-specific hook implementations are being moved
 * behind this Kotlin owner as Camera 6.8 signatures are verified.
 */
class LeicaUnlockHook : LeicaUnlockHookRuntime()
