package btm.m.os4.systemuihook.hypermusiccover;

import java.util.HashMap;
import java.util.Map;

public final class Xp {
    public interface Chain {
        Object getThisObject();
        Object proceed() throws Throwable;
    }
    public interface Hook { Object invoke(Chain chain) throws Throwable; }
    public static final Map<Class<?>, Hook> hooks = new HashMap<>();
    public static int registrations;
    public static Object hookAll(Class<?> type, String name, Hook hook) {
        if (hooks.put(type, hook) != null) throw new AssertionError("duplicate registration");
        registrations++;
        return null;
    }
    public static Class<?> findClass(String name, ClassLoader loader) throws Exception {
        return Class.forName(name, false, loader);
    }
    public static Object getObjectField(Object owner, String name) {
        try { return owner.getClass().getField(name).get(owner); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    public static Object callMethod(Object owner, String name, Object... args) {
        try {
            for (java.lang.reflect.Method method : owner.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    return method.invoke(owner, args);
                }
            }
            throw new NoSuchMethodException(name);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    public static void log(String message) { System.out.println(message); }
}
