// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.graphics.Bitmap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The OEM's wallpaper engine, read by SHAPE instead of by name.
 *
 * Every member this module reaches into on the wallpaper engine is R8-obfuscated, and the names
 * move between builds. Only the pending-surface flag is acted on from here; the rest is resolved
 * for the report, which is the thing a port to a phone nobody here owns actually runs on. They moved wholesale on the foldable: MiWallpaper 8.0.8-flip-q18 reparents
 * ImageEngineImpl onto MultiDisplayEngineService, which pushes the inherited fields down and
 * renames almost everything that was left. The renderer field went i -> t, the bitmap hand-off
 * X(Bitmap) -> a0(Bitmap), the frame request T/U(Z) -> Z(Z), the pending-surface flag b -> w.
 *
 * Reading them back by name is not just fragile, it is unsafe: `X` exists in BOTH builds with
 * opposite meanings - it takes the Bitmap on 7.0.7 and returns the renderer on 8.0.8 - so a call
 * by name does not fail, it silently does the wrong thing. And `b`, the flag this module used to
 * set to true, is a boolean on 7.0.7 but an int on 8.0.8, where it holds `which` (the value
 * MiuiWallpaperManager.isLockWhich reads to tell the lock engine from the desktop one). Writing a
 * flag onto that would corrupt the engine's own identity.
 *
 * So: anything that can be identified by its SIGNATURE is looked up that way, because those are
 * unique in both builds and need no table. What a signature cannot separate - one boolean flag
 * among several, one (Z)V method among several - is keyed off the architecture instead, which is
 * a structural question (is MultiDisplayEngineService in the hierarchy?) rather than a name.
 *
 * Nothing here throws. A member that cannot be resolved is left null and named in {@link #report},
 * because the phone this has to be ported on is usually not the one in front of us, and what
 * matters then is that one unresolved name does not take the rest down with it.
 */
final class EngineNames {

    private EngineNames() {
    }

    private static final String TAG = "[MCWall] ";

    /** The engine class these names were read off; null until an engine has been seen. */
    private static volatile Class<?> resolvedFor;

    // The three below have no caller today: the renderer is taken from the `this` of the hook on
    // its own lambda, and the bitmap is swapped inside that lambda rather than handed to the
    // engine. They are resolved anyway because they are what a reader of the report needs to tell
    // a build where the names merely moved from one where the PATH moved - and because the day
    // one of them is needed, it should not be a fresh dive through a dex.

    /** ImageEngineImpl's renderer field - `i` on 7.0.7, `t` on 8.0.8-flip. Reported, not used. */
    static volatile String rendererField;
    /** No-arg getter returning the renderer - `P()` / `X()`. Reported, not used. */
    static volatile String rendererGetter;
    /** The (Bitmap)V hand-off - `X` / `a0`. Reported, not used. */
    static volatile String bitmapSetter;
    /**
     * The "surface needs creating" flag the preRender step reads - `b` on 7.0.7, `w` on
     * 8.0.8-flip. Only ever set when it really is a boolean; see the class comment for what
     * writing it blind would cost on a build where that name belongs to an int.
     */
    static volatile String pendingFlag;
    /** True when the engine sits on MultiDisplayEngineService, i.e. the foldable's multi-screen build. */
    static volatile boolean multiDisplay;

    private static volatile String reportText = "no engine seen yet";

    /**
     * Reads the names off an engine instance, once per engine class.
     *
     * Called from the engine's constructor hook, which is the first moment an instance exists.
     */
    static void resolve(Object eng) {
        if (eng == null) return;
        Class<?> c = eng.getClass();
        if (c == resolvedFor) return;
        StringBuilder r = new StringBuilder();
        try {
            resolveInto(c, r);
        } catch (Throwable t) {
            r.append("\n  resolve failed: ").append(t);
        }
        resolvedFor = c;
        reportText = r.toString();
        for (String line : reportText.split("\n")) Xp.log(TAG + line);
    }

    private static void resolveInto(Class<?> c, StringBuilder r) {
        r.append("engine names for ").append(c.getName());

        multiDisplay = false;
        StringBuilder chain = new StringBuilder();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            if (chain.length() > 0) chain.append(" -> ");
            chain.append(k.getSimpleName());
            if (k.getName().endsWith("MultiDisplayEngineService")) multiDisplay = true;
        }
        r.append("\n  hierarchy: ").append(chain);
        r.append("\n  multiDisplay: ").append(multiDisplay);

        // By signature. Both are unique in both builds, so no table and no build check.
        rendererField = fieldOfType(c, "ImageWallpaperRenderer", r, "renderer field");
        rendererGetter = methodNamed(c, "renderer getter", r, new MethodTest() {
            @Override
            public boolean ok(Method m) {
                return m.getParameterTypes().length == 0
                        && m.getReturnType().getSimpleName().equals("ImageWallpaperRenderer");
            }
        });
        bitmapSetter = methodNamed(c, "bitmap setter", r, new MethodTest() {
            @Override
            public boolean ok(Method m) {
                Class<?>[] p = m.getParameterTypes();
                return p.length == 1 && p[0] == Bitmap.class && m.getReturnType() == void.class;
            }
        });

        // Not separable by signature: several booleans, several (Z)V methods. Keyed off the
        // architecture, and the type is checked before the name is kept.
        pendingFlag = booleanFieldNamed(c, multiDisplay ? "w" : "b", r);

        r.append("\n  boolean fields (candidates if the flag above is wrong): ")
                .append(booleanFields(c));
        r.append("\n  (Z)V methods (candidates for the frame request): ").append(oneBoolMethods(c));
    }

    private interface MethodTest {
        boolean ok(Method m);
    }

    /** The single field of a type, by simple name so the class need not be loadable here. */
    private static String fieldOfType(Class<?> c, String typeSimpleName, StringBuilder r,
                                      String what) {
        String found = null;
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().getSimpleName().equals(typeSimpleName)) continue;
                n++;
                if (found == null) found = f.getName();
            }
        }
        r.append("\n  ").append(what).append(": ").append(found == null ? "NOT FOUND" : found);
        // More than one would make the pick a guess; say so rather than pretend.
        if (n > 1) r.append(" (").append(n).append(" of this type - ambiguous)");
        return found;
    }

    private static String methodNamed(Class<?> c, String what, StringBuilder r, MethodTest test) {
        String found = null;
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                if (!test.ok(m)) continue;
                // The same method is declared again on a subclass on some builds; one name is
                // one match as far as the caller is concerned.
                if (found != null && found.equals(m.getName())) continue;
                n++;
                if (found == null) found = m.getName();
            }
        }
        r.append("\n  ").append(what).append(": ").append(found == null ? "NOT FOUND" : found);
        if (n > 1) r.append(" (").append(n).append(" matches - ambiguous)");
        return found;
    }

    /** The named field, kept only if it really is a boolean on this build. */
    private static String booleanFieldNamed(Class<?> c, String name, StringBuilder r) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!f.getName().equals(name) || Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == boolean.class) {
                    r.append("\n  pending-surface flag: ").append(name)
                            .append(" (on ").append(k.getSimpleName()).append(')');
                    return name;
                }
                r.append("\n  pending-surface flag: '").append(name).append("' on ")
                        .append(k.getSimpleName()).append(" is ")
                        .append(f.getType().getSimpleName())
                        .append(", NOT boolean - not written");
                return null;
            }
        }
        r.append("\n  pending-surface flag: '").append(name).append("' NOT FOUND");
        return null;
    }

    private static String booleanFields(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != boolean.class || Modifier.isStatic(f.getModifiers())) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(k.getSimpleName()).append('.').append(f.getName());
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String oneBoolMethods(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != boolean.class) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(k.getSimpleName()).append('.').append(m.getName()).append("(Z)");
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static String report() {
        return reportText;
    }
}
