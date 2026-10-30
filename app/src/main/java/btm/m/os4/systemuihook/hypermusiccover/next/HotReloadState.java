// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.content.Context;
import android.os.Bundle;

/**
 * What the retiring generation hands the new one through
 * {@code setSavedInstanceState}: an {@code Object[]} of primitives, strings, and host or
 * framework objects only. Anything of ours is refused here, while refusing is still free - the
 * framework refuses it too, and that throw would land in the commit step, which may not fail.
 *
 * <p>Shape, versioned. A reader ignores what it does not know and takes defaults for what is
 * missing; a schema it does not recognise makes [decode] return null, which the adopter turns
 * into "adopt without state" rather than a failure. Persistent settings never travel here -
 * they come from the state file, so an unreadable handoff costs runtime state, not settings.
 */
final class HotReloadState {

    private HotReloadState() {
    }

    static final String MAGIC = "HMC.HotReloadState";
    static final int SCHEMA = 1;
    private static final int MIN_SCHEMA = 1;

    /** [0] magic, [1] schema, [2] build, [3] process, [4] host loader, [5] context, [6] features,
     *  [7] how many generations this process has been through, [8] the retiring build's version
     *  code (appended within schema 1: a reader that predates it ignores it, and one that finds
     *  it missing reads 0). */
    static Object[] encode(String build, String process, ClassLoader hostLoader, Context context,
                           Object[][] features, int reloadCount, int fromVersionCode) {
        if (features != null) {
            for (Object[] feature : features) {
                check(feature[0]);
                check(feature[1]);
            }
        }
        return new Object[]{MAGIC, SCHEMA, build, process, hostLoader, context, features,
                reloadCount, fromVersionCode};
    }

    /** Null when [saved] is not ours or from a schema this build does not know. */
    static State decode(Object saved) {
        if (!(saved instanceof Object[])) return null;
        Object[] a = (Object[]) saved;
        if (a.length < 2 || !MAGIC.equals(a[0]) || !(a[1] instanceof Integer)) return null;
        int schema = (Integer) a[1];
        if (schema < MIN_SCHEMA || schema > SCHEMA) return null;
        return new State(
                schema,
                str(a, 2),
                str(a, 3),
                a.length > 4 && a[4] instanceof ClassLoader ? (ClassLoader) a[4] : null,
                a.length > 5 && a[5] instanceof Context ? (Context) a[5] : null,
                a.length > 6 && a[6] instanceof Object[][] ? (Object[][]) a[6] : new Object[0][],
                a.length > 7 && a[7] instanceof Integer ? (Integer) a[7] : 1,
                a.length > 8 && a[8] instanceof Integer ? (Integer) a[8] : 0);
    }

    /**
     * The framework's own rule, applied before the commit step rather than found there: a value
     * whose class came out of this module's loader cannot travel to the next generation.
     */
    static void check(Object value) {
        if (value == null) return;
        Class<?> cls = value.getClass();
        if (cls.isArray()) {
            if (value instanceof Object[]) {
                for (Object element : (Object[]) value) check(element);
            }
            return;
        }
        if (value instanceof Bundle) {
            Bundle b = (Bundle) value;
            for (String key : b.keySet()) check(b.get(key));
            return;
        }
        if (cls.getClassLoader() == HotReloadState.class.getClassLoader()) {
            throw new IllegalArgumentException("module class in saved state: " + cls.getName());
        }
    }

    private static String str(Object[] a, int i) {
        return i < a.length && a[i] instanceof String ? (String) a[i] : null;
    }

    /** A decoded handoff. [features] is always non-null; each entry is {key, value}. */
    static final class State {
        final int schema;
        final String fromBuild;
        final String process;
        final ClassLoader hostLoader;
        final Context context;
        final Object[][] features;
        final int reloadCount;
        /** 0 from a build that did not send it. */
        final int fromVersionCode;

        State(int schema, String fromBuild, String process, ClassLoader hostLoader,
              Context context, Object[][] features, int reloadCount, int fromVersionCode) {
            this.schema = schema;
            this.fromBuild = fromBuild;
            this.process = process;
            this.hostLoader = hostLoader;
            this.context = context;
            this.features = features;
            this.reloadCount = reloadCount;
            this.fromVersionCode = fromVersionCode;
        }
    }
}
