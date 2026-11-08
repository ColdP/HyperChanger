// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.view.View

internal data class NativeDeviceCardSnapshot(
        val item: Any,
        val index: Int,
        val title: String,
        val value: String,
        val first: String,
        val second: String,
        val key: String,
        val iconRes: Int,
        val listener: View.OnClickListener?,
    )

internal object NativeDeviceCards {
    fun read(adapter: Any): List<NativeDeviceCardSnapshot> {
        val raw = field(adapter, "cardInfos") ?: field(adapter, "mCardInfos") ?: return emptyList()
        val iterable = when (raw) {
            is Iterable<*> -> raw.asSequence()
            is Array<*> -> raw.asSequence()
            else -> return emptyList()
        }
        return iterable.mapNotNull { item ->
            item ?: return@mapNotNull null
            runCatching {
                NativeDeviceCardSnapshot(
                    item = item,
                    index = call(item, "getIndex") as? Int ?: -1,
                    title = (call(item, "getTitle2") as? String).orEmpty().ifBlank { (call(item, "getTitle") as? String).orEmpty() },
                    value = call(item, "getValue") as? String ?: "",
                    first = call(item, "getFirstValue") as? String ?: "",
                    second = call(item, "getSecondValue") as? String ?: "",
                    key = call(item, "getKey") as? String ?: "",
                    iconRes = call(item, "getIconResId") as? Int ?: 0,
                    listener = call(item, "getListener") as? View.OnClickListener,
                )
            }.getOrNull()
        }.toList()
    }

    private fun field(target: Any, name: String): Any? = runCatching {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return@runCatching field.get(target)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    private fun call(target: Any, name: String): Any? = runCatching {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) {
                method.isAccessible = true
                return@runCatching method.invoke(target)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

}

internal fun openDeviceMemoryExtension(context: android.content.Context) {
        runCatching {
            val loader = context.classLoader
            val controller = loader.loadClass("com.android.settings.special.ExternalRamController")
            if (controller.getMethod("isShow").invoke(null) != true) return@runCatching
            loader.loadClass("com.android.settings.special.ExternalRamFragment")
            val launcherType = loader.loadClass("com.android.settings.core.SubSettingLauncher")
            val launcher = launcherType.getConstructor(Context::class.java).newInstance(context)
            launcherType.getMethod("setDestination", String::class.java)
                .invoke(launcher, "com.android.settings.special.ExternalRamFragment")
            val title = context.resources.getIdentifier("external_ram_title", "string", context.packageName)
            launcherType.getMethod("setTitleRes", Int::class.javaPrimitiveType).invoke(launcher, title)
            launcherType.getMethod("launch").invoke(launcher)
        }.onFailure { error ->
            ModuleLog.e("HyperChangerDeviceDetails", "Could not open native memory extension settings", error)
        }
    }
