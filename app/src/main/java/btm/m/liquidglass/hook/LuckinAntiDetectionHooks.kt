package btm.m.liquidglass.hook

import android.content.pm.PackageManager
import android.os.Debug
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.io.IOException
import java.lang.reflect.Method

/**
 * Masks Java level hook indicators in the protected Luckin process.
 * Jiagu's native checks are outside the reach of ordinary Java hooks.
 */
object LuckinAntiDetectionHooks {
    private const val TAG = "CustomNav-Luckin"
    private const val HOST_PACKAGE = "btm.m.os4.systemuihook"

    private val hiddenClassTokens = listOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.libxposed",
        "edxp",
        "sandhook",
        "exposed",
        "frida"
    )

    private val hiddenPackageNames = setOf(
        HOST_PACKAGE,
        "de.robv.android.xposed.installer",
        "org.lsposed.manager",
        "org.meowcat.edxposed.manager",
        "io.github.libxposed.manager"
    )

    @JvmStatic
    fun install(module: XposedModule) {
        hookClassLoading(module)
        hookDebugState(module)
        hookSensitiveFiles(module)
        hookPackageQueries(module)
        hookProcessCommands(module)
        hookStackTraces(module)
        module.log(
            Log.INFO,
            TAG,
            "Luckin Java-level hook detection masking installed"
        )
    }

    private fun hookClassLoading(module: XposedModule) {
        val methods = listOf(
            ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java),
            ClassLoader::class.java.getDeclaredMethod(
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
        )
        methods.forEach { method ->
            hook(module, method) { chain ->
                val className = chain.getArg(0) as? String
                if (className != null && isHiddenClass(className)) {
                    throw ClassNotFoundException(className)
                }
                chain.proceed()
            }
        }
    }

    private fun hookDebugState(module: XposedModule) {
        hook(module, Debug::class.java.getDeclaredMethod("isDebuggerConnected")) { _: XposedInterface.Chain ->
            false
        }
        hook(module, Debug::class.java.getDeclaredMethod("waitingForDebugger")) { _: XposedInterface.Chain ->
            false
        }
    }

    private fun hookSensitiveFiles(module: XposedModule) {
        hook(module, File::class.java.getDeclaredMethod("exists")) { chain ->
            val file = chain.thisObject as? File
            if (file != null && isSensitivePath(file.path)) false else chain.proceed()
        }
        hook(module, File::class.java.getDeclaredMethod("canRead")) { chain ->
            val file = chain.thisObject as? File
            if (file != null && isSensitivePath(file.path)) false else chain.proceed()
        }
    }

    private fun hookPackageQueries(module: XposedModule) {
        val managerClass = runCatching {
            Class.forName("android.app.ApplicationPackageManager")
        }.getOrNull() ?: return
        val method = managerClass.getDeclaredMethod(
            "getPackageInfo",
            String::class.java,
            Int::class.javaPrimitiveType
        )
        hook(module, method) { chain ->
            val packageName = chain.getArg(0) as? String
            if (packageName != null && packageName in hiddenPackageNames) {
                throw PackageManager.NameNotFoundException(packageName)
            }
            chain.proceed()
        }
    }

    private fun hookProcessCommands(module: XposedModule) {
        hook(module, Runtime::class.java.getDeclaredMethod("exec", String::class.java)) { chain ->
            val command = chain.getArg(0) as? String
            if (command != null && isSensitiveCommand(command)) {
                throw IOException("command unavailable")
            }
            chain.proceed()
        }
        hook(module, ProcessBuilder::class.java.getDeclaredMethod("start")) { chain ->
            val process = chain.thisObject as? ProcessBuilder
            val command = process?.command()?.joinToString(" ")
            if (command != null && isSensitiveCommand(command)) {
                throw IOException("command unavailable")
            }
            chain.proceed()
        }
    }

    private fun hookStackTraces(module: XposedModule) {
        hook(module, Throwable::class.java.getDeclaredMethod("getStackTrace")) { chain ->
            filterStack(chain.proceed() as? Array<StackTraceElement>)
        }
        hook(module, Thread::class.java.getDeclaredMethod("getStackTrace")) { chain ->
            filterStack(chain.proceed() as? Array<StackTraceElement>)
        }
    }

    private fun filterStack(value: Array<StackTraceElement>?): Array<StackTraceElement>? {
        if (value == null) return null
        return value.filterNot { element ->
            val name = element.toString().lowercase()
            hiddenClassTokens.any(name::contains) || name.contains(HOST_PACKAGE)
        }.toTypedArray()
    }

    private fun isHiddenClass(name: String): Boolean {
        val normalized = name.lowercase()
        return hiddenClassTokens.any(normalized::contains)
    }

    private fun isSensitivePath(path: String): Boolean {
        val normalized = path.lowercase()
        return normalized.contains("/data/adb/lspd") ||
            normalized.contains("/data/adb/modules") ||
            normalized.contains(".magisk") ||
            normalized.contains("libxposed") ||
            normalized.contains("liblsposed") ||
            normalized.contains("frida")
    }

    private fun isSensitiveCommand(command: String): Boolean {
        val normalized = command.lowercase()
        return normalized.contains("/proc/") &&
            (normalized.contains("/maps") || normalized.contains("/status")) ||
            normalized.contains("frida") ||
            normalized.contains("xposed") ||
            normalized.contains("lsposed")
    }

    private fun hook(
        module: XposedModule,
        method: Method,
        interceptor: (XposedInterface.Chain) -> Any?
    ) {
        method.isAccessible = true
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(interceptor)
    }

}
