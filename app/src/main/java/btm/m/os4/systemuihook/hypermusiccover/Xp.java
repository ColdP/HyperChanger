package btm.m.os4.systemuihook.hypermusiccover;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * The pieces of the classic XposedHelpers this module actually used, on plain reflection.
 *
 * The modern API (io.github.libxposed, API 102) ships no helpers on purpose: it gives you
 * {@link XposedInterface#hook(Executable)} over a java.lang.reflect.Executable and nothing else.
 * Everything this module does to SystemUI - reading a private field off a hooked instance,
 * calling a method by name, hooking every overload of a name - was XposedHelpers doing reflection
 * on our behalf, so it is reflection here instead.
 *
 * Failures throw. A field or method that has been renamed by an OS update is not something to
 * carry on past: the call sites already sit inside try/catch blocks that log and give up, and a
 * silent null would turn a rename into a much stranger bug much later.
 */
final class Xp {

    private Xp() {
    }

    /** Set once, from the module entry point, before any hook can run. */
    private static volatile XposedInterface sApi;

    static void attach(XposedInterface api) {
        sApi = api;
    }

    static XposedInterface api() {
        XposedInterface api = sApi;
        if (api == null) throw new IllegalStateException("Xposed framework not attached yet");
        return api;
    }

    // ---------------------------------------------------------------- logging

    /**
     * The framework's log, which is where LSPosed collects module output. Tagged per line the
     * way the old XposedBridge.log was, so `logcat | grep MCProbe` still works.
     */
    static void log(String msg) {
        XposedInterface api = sApi;
        if (api != null) {
            api.log(Log.INFO, "LSPosed-Bridge", msg);
        } else {
            Log.i("LSPosed-Bridge", msg);
        }
    }

    // ---------------------------------------------------------------- lookup

    static Class<?> findClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no class " + name, e);
        }
    }

    static Method findMethodExact(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new IllegalArgumentException("no method " + cls.getName() + "." + name);
    }

    // ---------------------------------------------------------------- hooking

    /**
     * Every overload of a name, the way XposedBridge.hookAllMethods worked. The OEM classes this
     * module hooks are obfuscated and their signatures move between OS versions, so hooking by
     * name and taking whatever is there has proven far more durable than naming the parameters.
     */
    static List<XposedInterface.HookHandle> hookAll(Class<?> cls, String name,
                                                    XposedInterface.Hooker hooker) {
        // Declared methods only, no walk up the hierarchy - the same rule the classic
        // hookAllMethods followed, and here it is load-bearing: onAttachedToWindow and onDraw
        // are declared on the OEM classes we mean, but if one ever were not, falling through to
        // View's would hook every view in SystemUI.
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) handles.add(api().hook(m).intercept(hooker));
        }
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("no method " + cls.getName() + "." + name);
        }
        return handles;
    }

    /**
     * A lambda body, under either name D8 may have given it.
     *
     * A lambda compiles to a synthetic method called lambda$enclosing$N, but when a subclass
     * has to reach it D8 appends the declaring class instead - lambda$onSurfaceCreated$0
     * becomes lambda$onSurfaceCreated$0$com-miui-miwallpaper-opengl-ImageWallpaperRenderer.
     * Which form an OEM's build carries is not something we get to know: OS4 here has the
     * plain name and a HyperOS 3 report had the mangled one. Matching the plain name exactly
     * threw there, and the caller logged it and carried on - with the one hook that replaces
     * the wallpaper texture not installed, so the cover did nothing at all on that phone.
     */
    static List<XposedInterface.HookHandle> hookAllLambdas(Class<?> cls, String name,
                                                           XposedInterface.Hooker hooker) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        String mangled = name + "$";
        for (Method m : cls.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals(name) || n.startsWith(mangled)) {
                handles.add(api().hook(m).intercept(hooker));
            }
        }
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("no method " + cls.getName() + "." + name
                    + " (nor " + mangled + "<class>)");
        }
        return handles;
    }

    static XposedInterface.HookHandle hook(Executable target, XposedInterface.Hooker hooker) {
        return api().hook(target).intercept(hooker);
    }

    static List<XposedInterface.HookHandle> hookAllConstructors(Class<?> cls,
                                                                XposedInterface.Hooker hooker) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            handles.add(api().hook(c).intercept(hooker));
        }
        return handles;
    }

    // ---------------------------------------------------------------- fields

    /** The declared fields of a class by name, for the same reason the methods are cached. */
    private static final ClassValue<java.util.concurrent.ConcurrentHashMap<String, Field>>
            DECLARED_FIELDS =
            new ClassValue<java.util.concurrent.ConcurrentHashMap<String, Field>>() {
                @Override
                protected java.util.concurrent.ConcurrentHashMap<String, Field> computeValue(
                        Class<?> type) {
                    java.util.concurrent.ConcurrentHashMap<String, Field> byName =
                            new java.util.concurrent.ConcurrentHashMap<>();
                    for (Field f : type.getDeclaredFields()) {
                        try {
                            f.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                        byName.put(f.getName(), f);
                    }
                    return byName;
                }
            };

    private static Field field(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            Field f = DECLARED_FIELDS.get(c).get(name);
            if (f != null) return f;
        }
        throw new IllegalArgumentException("no field " + obj.getClass().getName() + "." + name);
    }

    static Object getObjectField(Object obj, String name) {
        try {
            return field(obj, name).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setObjectField(Object obj, String name, Object value) {
        try {
            field(obj, name).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setBooleanField(Object obj, String name, boolean value) {
        try {
            field(obj, name).setBoolean(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- calling

    /**
     * Call by name, matching on arity and assignability the way XposedHelpers did. Overloads that
     * differ only in a numeric parameter type would be ambiguous here, but nothing this module
     * calls is: the OEM setters it drives take one argument of one type.
     */
    static Object callMethod(Object obj, String name, Object... args) {
        Method m = findMethod(obj.getClass(), name, args);
        try {
            return m.invoke(obj, args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(obj.getClass().getName() + "." + name + " threw", cause);
        }
    }

    /**
     * One declared method, with its parameter types kept alongside it.
     *
     * `Method.getParameterTypes()` clones its array on every call, which is the same reason this
     * cache exists at all: the matching below runs once per frame on the collapse's hot path and
     * cannot be allocating.
     */
    private static final class Overload {
        final Method method;
        final Class<?>[] params;

        Overload(Method m) {
            method = m;
            // Guarded: this now runs for every method a class declares rather than for the one
            // that was asked for, and one method a build refuses to open must not cost the rest
            // of the class its table. A method that cannot be opened is simply not offered, and
            // the lookup then throws exactly what it threw before there was a cache.
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }
            params = m.getParameterTypes();
        }
    }

    /**
     * The declared methods of a class, grouped by name.
     *
     * This is the whole point of the class, and it is a frame-rate fix rather than a tidiness.
     * `applyGlassMorph()` drives `updateGlassValue` through here on every frame of a cover-mode
     * transition, and the lookup used to be `getDeclaredMethods()` on every class between the
     * view and Object. That call ALLOCATES: it builds a fresh Method object for every method the
     * class declares, every time it is asked. On a View subclass that is hundreds of objects per
     * call, twice a frame, on top of the OEM recomputing a variable font on the same frames -
     * which is felt as jank in exactly the transition this module is for.
     *
     * ClassValue rather than a Map<Class, ...>: it holds the entry on the Class itself, so an
     * OEM class that is unloaded takes its table with it instead of being pinned by a static map
     * for the life of SystemUI.
     */
    private static final ClassValue<java.util.concurrent.ConcurrentHashMap<String, Overload[]>>
            DECLARED_METHODS =
            new ClassValue<java.util.concurrent.ConcurrentHashMap<String, Overload[]>>() {
                @Override
                protected java.util.concurrent.ConcurrentHashMap<String, Overload[]> computeValue(
                        Class<?> type) {
                    java.util.concurrent.ConcurrentHashMap<String, Overload[]> byName =
                            new java.util.concurrent.ConcurrentHashMap<>();
                    for (Method m : type.getDeclaredMethods()) {
                        String n = m.getName();
                        Overload o = new Overload(m);
                        Overload[] prev = byName.get(n);
                        if (prev == null) {
                            byName.put(n, new Overload[]{o});
                        } else {
                            Overload[] next = new Overload[prev.length + 1];
                            System.arraycopy(prev, 0, next, 0, prev.length);
                            next[prev.length] = o;
                            byName.put(n, next);
                        }
                    }
                    return byName;
                }
            };

    /**
     * The order is the one the scan had: nearest class first, and within a class the order
     * getDeclaredMethods() reported, so a name with several overloads still picks the same one.
     */
    private static Method findMethod(Class<?> cls, String name, Object[] args) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            Overload[] cands = DECLARED_METHODS.get(c).get(name);
            if (cands == null) continue;
            for (Overload o : cands) {
                Class<?>[] p = o.params;
                if (p.length != args.length) continue;
                boolean fits = true;
                for (int i = 0; i < p.length && fits; i++) {
                    fits = accepts(p[i], args[i]);
                }
                if (fits) return o.method;
            }
        }
        throw new IllegalArgumentException("no method " + cls.getName() + "." + name
                + "/" + args.length);
    }

    /** Reflection hands us boxed values for primitive parameters; treat the pairs as equal. */
    private static boolean accepts(Class<?> param, Object arg) {
        if (arg == null) return !param.isPrimitive();
        if (param.isPrimitive()) return boxed(param) == arg.getClass();
        return param.isAssignableFrom(arg.getClass());
    }

    private static Class<?> boxed(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        return primitive;
    }
}
