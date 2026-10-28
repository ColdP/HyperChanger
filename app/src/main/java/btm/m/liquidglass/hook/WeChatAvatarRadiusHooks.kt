package btm.m.liquidglass.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import java.util.concurrent.atomic.AtomicBoolean

/** Hooks WeChat's avatar bitmap loader without changing HookSettings serialization. */
internal object WeChatAvatarRadiusHooks {
    private const val CLASS = "com.tencent.mm.pluginsdk.ui.u"
    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, loader: ClassLoader) {
        if (installClass(module, runCatching { Class.forName(CLASS, false, loader) }.getOrNull())) return
        // Some WeChat builds load pluginsdk.ui.u after the package callback.
        runCatching {
            val loadClass = ClassLoader::class.java.getDeclaredMethod(
                "loadClass", String::class.java, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            module.hook(loadClass)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("wechat-avatar-radius:class-loader")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (chain.getArg(0) == CLASS && result is Class<*>) installClass(module, result)
                    result
                }
        }.onFailure {
            logFailure(module, it)
        }
    }

    private fun installClass(module: XposedModule, type: Class<*>?): Boolean {
        if (type == null || !installed.compareAndSet(false, true)) return false
        runCatching {
            val methods = type.declaredMethods.filter { method ->
                method.returnType == Void.TYPE && method.parameterTypes.firstOrNull()?.name == "android.widget.ImageView" &&
                    method.parameterTypes.count { it == Float::class.javaPrimitiveType } == 1 &&
                    method.parameterTypes.drop(1).all { it == String::class.java || it == Float::class.javaPrimitiveType || it == Boolean::class.javaPrimitiveType }
            }
            if (methods.isEmpty()) error("No avatar loader overloads found")
            methods.forEach { method ->
                method.isAccessible = true
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wechat-avatar-radius:${method.name}:${method.parameterTypes.size}")
                    .intercept { chain ->
                        val offset = WeChatAvatarRadiusSettings.getOffset(
                            module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
                        )
                        if (offset == WeChatAvatarRadiusSettings.DEFAULT) return@intercept chain.proceed()
                        val args = method.parameterTypes.indices.map { chain.getArg(it) }.toTypedArray()
                        val floatIndex = method.parameterTypes.indexOfFirst { it == Float::class.javaPrimitiveType }
                        if (floatIndex >= 0) {
                            args[floatIndex] = WeChatAvatarRadiusSettings.radiusFactor(offset)
                        }
                        chain.proceed(args)
                    }
            }
        }.onFailure { installed.set(false); logFailure(module, it) }
        return installed.get()
    }

    private fun logFailure(module: XposedModule, error: Throwable) {
        btm.m.os4.systemuihook.HookModuleLogger.log(
            module, btm.m.os4.systemuihook.ModuleLog.WARN, "WeChatAvatar", "Avatar radius hook unavailable", error
        )
    }
}
