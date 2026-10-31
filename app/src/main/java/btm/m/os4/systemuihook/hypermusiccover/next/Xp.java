// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.content.SharedPreferences;
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
public final class Xp {

    private Xp() {
    }

    /** Set once, from the module entry point, before any hook can run. */
    private static volatile XposedInterface sApi;

    static void attach(XposedInterface api) {
        sApi = api;
        if (!sLevelAsked) readLevel(api);
    }

    public static XposedInterface api() {
        XposedInterface api = sApi;
        if (api == null) throw new IllegalStateException("Xposed framework not attached yet");
        return api;
    }

    // ---------------------------------------------------------------- logging

    /** LogLevel.NORMAL or VERBOSE, as the app last set it; followed live. */
    private static volatile int sLevel = LogLevel.NORMAL;
    private static volatile boolean sLevelAsked;
    /**
     * Held for the listener's sake: a SharedPreferences may keep its listeners weakly, and one
     * collected would leave this process on the level it started with.
     */
    private static SharedPreferences sLevelPrefs;
    private static final SharedPreferences.OnSharedPreferenceChangeListener LEVEL_LISTENER =
            (prefs, key) -> {
                if (key == null || LogLevel.KEY.equals(key)) {
                    sLevel = prefs.getInt(LogLevel.KEY, LogLevel.NORMAL);
                }
            };

    /**
     * Once per process. A framework without remote preferences, or a group the app never wrote,
     * leaves the level at NORMAL - the module logs as a fresh install does.
     */
    private static synchronized void readLevel(XposedInterface api) {
        if (sLevelAsked) return;
        sLevelAsked = true;
        try {
            SharedPreferences prefs = api.getRemotePreferences(LogLevel.GROUP);
            sLevel = prefs.getInt(LogLevel.KEY, LogLevel.NORMAL);
            Gen.prefListener(prefs, LEVEL_LISTENER);
            sLevelPrefs = prefs;
        } catch (Throwable t) {
            write(Log.WARN, "[MCLog] log level unavailable, logging at normal: " + t);
        }
    }

    /** Whether 详细 is on: for a caller whose message costs something to build. */
    static boolean verbose() {
        return sLevel >= LogLevel.VERBOSE;
    }

    /**
     * The framework's log, which is where LSPosed collects module output. Tagged per line the
     * way the old XposedBridge.log was, so `logcat | grep MCProbe` still works.
     */
    public static void log(String msg) {
        write(Log.INFO, msg);
    }

    /**
     * Only with 详细 on. Still written at INFO: logd on these phones keeps nothing under it
     * (persist.logd.limit), so a DEBUG line would be asked for and then dropped. The level is
     * decided here, not by logd.
     */
    public static void d(String msg) {
        if (sLevel < LogLevel.VERBOSE) return;
        write(Log.INFO, msg);
    }

    /** Something that did not work: a hook, a lookup, a request. Always written. */
    public static void w(String msg) {
        write(Log.WARN, msg);
    }

    private static void write(int priority, String msg) {
        remember(msg);
        XposedInterface api = sApi;
        if (api != null) {
            btm.m.os4.systemuihook.HookModuleLogger.log(api, priority, "HyperMusicCover", msg);
        } else {
            Log.println(priority, "LSPosed-Bridge", msg);
        }
    }

    /**
     * The last lines this process logged, kept in memory.
     *
     * On the test phone logd keeps nothing below error level, so the framework log above reaches
     * nobody - see the note on the probe ops that answer with setResultData. This is what `op tail`
     * reads back instead, in whichever process was asked: SystemUI through PROBE, the wallpaper
     * process through WPROBE. Bounded, and cheap enough to leave on.
     */
    private static final int TAIL_LINES = 160;
    private static final java.util.ArrayDeque<String> sTail = new java.util.ArrayDeque<>();

    private static void remember(String msg) {
        String line = android.os.SystemClock.uptimeMillis() + " " + msg;
        synchronized (sTail) {
            if (sTail.size() >= TAIL_LINES) sTail.pollFirst();
            sTail.addLast(line);
        }
    }

    /** The remembered lines containing `grep` (all of them for null), oldest first, at most `max`. */
    public static String tail(String grep, int max) {
        java.util.ArrayList<String> hits = new java.util.ArrayList<>();
        synchronized (sTail) {
            for (String line : sTail) {
                if (grep == null || line.contains(grep)) hits.add(line);
            }
        }
        int from = Math.max(0, hits.size() - Math.max(1, max));
        StringBuilder out = new StringBuilder();
        for (int i = from; i < hits.size(); i++) out.append(hits.get(i)).append('\n');
        return out.toString();
    }

    // ---------------------------------------------------------------- lookup

    public static Class<?> findClass(String name, ClassLoader loader) {
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
    static List<XposedInterface.HookHandle> hookAllLambdas(String id, Class<?> cls, String name,
                                                           XposedInterface.Hooker hooker) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        String mangled = name + "$";
        for (Method m : cls.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals(name) || n.startsWith(mangled)) {
                // The plain and the mangled body can share a signature; the name is part of the
                // id so the pair stays two hooks rather than one replacing the other.
                handles.add(hook(id + "#" + n + "(" + signature(m) + ")", m, hooker));
            }
        }
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("no method " + cls.getName() + "." + name
                    + " (nor " + mangled + "<class>)");
        }
        return handles;
    }

    /**
     * A hook carrying an identity, for hot reload. Every hook in the module goes through here.
     *
     * <p>The id is the handle's name across versions. When adoption already holds an old handle
     * with this id and this exact executable, the hook replaces it in place; otherwise it is
     * installed new with the id attached, so the next generation can find it. Replacement
     * failing is not fatal: the old handle goes back to the pending list (it will be unhooked
     * at the end of adoption) and the hook is installed alongside.
     */
    public static XposedInterface.HookHandle hook(String id, Executable target,
                                                  XposedInterface.Hooker hooker) {
        // The one behavioural edge of the ledger: once this generation is retired or inert,
        // every hooker it installed is a pass-through. Neither flag is set yet, so this is a
        // boolean today; it is what closes the window between retirement and adoption.
        XposedInterface.Hooker guarded =
                chain -> Gen.live() && btm.m.os4.systemuihook.HyperMusicCoverRuntimeBridge.allowsHook(id)
                        ? hooker.intercept(chain) : chain.proceed();
        XposedInterface.HookHandle old = HotReload.takeOld(id, target);
        if (old != null) {
            try {
                XposedInterface.HookHandle replaced = old.replaceHook(guarded);
                HotReload.onReplaced(id, replaced);
                Gen.onHook(replaced);
                return replaced;
            } catch (Throwable t) {
                w("[MCHotReload] replaceHook failed for " + id + ": " + t);
                HotReload.keepOld(old);
            }
        }
        XposedInterface.HookHandle h = api().hook(target).setId(id).intercept(guarded);
        HotReload.onAdded(id, h);
        Gen.onHook(h);
        return h;
    }

    /**
     * Every overload of a name, the way XposedBridge.hookAllMethods worked, with an id per
     * overload: {@code id + "#" + parameter simple names}. The suffix depends only on the OEM
     * signature, which R8 does not touch, so it survives our own obfuscation.
     *
     * <p>Declared methods only, no walk up the hierarchy - the same rule the classic
     * hookAllMethods followed, and here it is load-bearing: onAttachedToWindow and onDraw are
     * declared on the OEM classes we mean, but if one ever were not, falling through to View's
     * would hook every view in SystemUI.
     */
    public static List<XposedInterface.HookHandle> hookAll(String id, Class<?> cls, String name,
                                                    XposedInterface.Hooker hooker) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) handles.add(hook(id + "#" + signature(m), m, hooker));
        }
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("no method " + cls.getName() + "." + name);
        }
        return handles;
    }

    /**
     * An OEM class or method name folded into an id segment: lower case, and an underscore
     * where a capital was. Those names are literals in our source or come off the target class,
     * neither of which R8 touches, so the segment is as stable as the hook itself.
     */
    public static String slug(String name) {
        StringBuilder b = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (b.length() > 0) b.append('_');
                b.append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    public static String signature(Executable e) {
        StringBuilder b = new StringBuilder();
        for (Class<?> p : e.getParameterTypes()) {
            if (b.length() > 0) b.append(',');
            b.append(p.getSimpleName());
        }
        return b.length() == 0 ? "()" : b.toString();
    }

    /** Every constructor, id per signature: {@code id + "#<init>(" + parameters + ")"}. */
    public static List<XposedInterface.HookHandle> hookAllConstructors(String id, Class<?> cls,
                                                                XposedInterface.Hooker hooker) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            handles.add(hook(id + "#<init>(" + signature(c) + ")", c, hooker));
        }
        return handles;
    }

    // ---------------------------------------------------------------- fields

    /**
     * The classes either ClassValue has computed an entry for. A ClassValue value is held on the
     * Class, and its key is an instance of ours - so every calculated class pins this
     * generation's class loader until the entry is removed. Weak, and only read at retirement.
     */
    private static final java.util.Set<Class<?>> FIELD_CLASSES =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private static final java.util.Set<Class<?>> METHOD_CLASSES =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** The declared fields of a class by name, for the same reason the methods are cached. */
    private static final ClassValue<java.util.concurrent.ConcurrentHashMap<String, Field>>
            DECLARED_FIELDS =
            new ClassValue<java.util.concurrent.ConcurrentHashMap<String, Field>>() {
                @Override
                protected java.util.concurrent.ConcurrentHashMap<String, Field> computeValue(
                        Class<?> type) {
                    FIELD_CLASSES.add(type);
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
            Field f = cached(DECLARED_FIELDS, FIELD_CLASSES, c).get(name);
            if (f != null) return f;
        }
        throw new IllegalArgumentException("no field " + obj.getClass().getName() + "." + name);
    }

    public static Object getObjectField(Object obj, String name) {
        try {
            return field(obj, name).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setObjectField(Object obj, String name, Object value) {
        try {
            field(obj, name).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setBooleanField(Object obj, String name, boolean value) {
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
    public static Object callMethod(Object obj, String name, Object... args) {
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
                    METHOD_CLASSES.add(type);
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
     * [value]'s entry for [c], not left on the class once this generation is no longer live.
     *
     * The drop below runs on the binder thread as the retirement returns, while a thread of
     * ours can still be finishing on the old code (GL, a lookup); an entry it computed after the
     * drop would pin this loader again. The drop comes after retired is set, and both sides go
     * through [classes]' lock, so an entry added after the drop sees retired here and goes.
     */
    private static <T> T cached(ClassValue<T> value, java.util.Set<Class<?>> classes, Class<?> c) {
        T v = value.get(c);
        if (!Gen.live()) {
            synchronized (classes) {
                try {
                    value.remove(c);
                } catch (Throwable ignored) {
                }
                classes.remove(c);
            }
        }
        return v;
    }

    /**
     * Drops both ClassValue entries, so the classes stop pointing at this generation. Called
     * at retirement, after retired is set; what is asked after that is not cached ([cached]).
     */
    static void dropClassValues() {
        dropClassValues(DECLARED_FIELDS, FIELD_CLASSES);
        dropClassValues(DECLARED_METHODS, METHOD_CLASSES);
    }

    private static void dropClassValues(ClassValue<?> value, java.util.Set<Class<?>> classes) {
        synchronized (classes) {
            for (Class<?> c : classes) {
                try {
                    value.remove(c);
                } catch (Throwable ignored) {
                }
            }
            classes.clear();
        }
    }

    /**
     * The order is the one the scan had: nearest class first, and within a class the order
     * getDeclaredMethods() reported, so a name with several overloads still picks the same one.
     */
    private static Method findMethod(Class<?> cls, String name, Object[] args) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            Overload[] cands = cached(DECLARED_METHODS, METHOD_CLASSES, c).get(name);
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
