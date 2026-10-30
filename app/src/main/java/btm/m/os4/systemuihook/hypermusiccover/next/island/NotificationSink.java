// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island;

import btm.m.os4.systemuihook.hypermusiccover.next.Xp;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Adds the module's policy to the OEM combine's inputs, so it emits through its own coroutine. */
public final class NotificationSink {
    private final List<WeakReference<Object>> policies = new ArrayList<>();
    /**
     * The combines whose input array holds one of our policies. The combine is stateIn(Eagerly):
     * it is collected once, with the array it held then, so what a later generation writes into
     * the field is never read. Kept for the handover's older half (see {@link #adopt}).
     */
    private final List<WeakReference<Object>> owners = new ArrayList<>();
    private int requestedMode;
    private long emissions;
    private final List<String> history = new ArrayList<>();
    private String hookStatus = "not installed";

    public synchronized void hookStatus(String status) {
        hookStatus = status;
    }

    public synchronized void record(Object[] original, Object[] effective, String bound) {
        emissions++;
        if (original.length < 7) return;
        String state = "inputs=" + original.length + " policy="
                + (original.length == 8 ? original[7] : "absent")
                + " fingerprint=" + original[5] + "/" + original[6]
                + " effective=" + effective[5] + "/" + effective[6] + " bound=" + bound;
        if (history.isEmpty() || !history.get(history.size() - 1).equals(state)) {
            history.add(state);
            if (history.size() > 8) history.remove(0);
        }
    }

    /**
     * The combines this generation injected into, for the next generation. Host objects only.
     * Nothing is put back on retirement: the running collection holds our 8-element array
     * whatever the field says, so restoring the field only made the next generation inject a
     * policy no one collects.
     */
    public synchronized Object[] owners() {
        return live(owners);
    }

    /**
     * The policy flows themselves - kotlinx's MutableStateFlow from SystemUI's own loader, so a
     * host object that may cross a generation. The one the running combine collects is in here,
     * and it is the only input the next generation can still move the combine with.
     */
    public synchronized Object[] policies() {
        return live(policies);
    }

    private static Object[] live(List<WeakReference<Object>> refs) {
        List<Object> out = new ArrayList<>();
        for (WeakReference<Object> r : refs) {
            Object o = r.get();
            if (o != null) out.add(o);
        }
        return out.toArray();
    }

    /**
     * Takes the live combines over after a hot reload. [handed] are the previous generation's
     * policy flows, the ones actually collected: they become ours, and refresh() writes the mode
     * into them. [owners] are its combines; a previous build put the OEM's 7-element array back
     * into them on retirement, and those are injected into again - which only reaches a
     * collection that starts later, but is all that can be done for a build that did not hand
     * its flows over.
     */
    public synchronized void adopt(Object[] owners, Object[] handed, int mode) {
        int taken = 0;
        if (handed != null) {
            for (Object policy : handed) {
                if (policy == null || contains(policies, policy)) continue;
                policies.add(new WeakReference<>(policy));
                taken++;
            }
        }
        int attached = 0;
        int total = owners == null ? 0 : owners.length;
        if (owners != null) {
            for (Object owner : owners) {
                if (owner == null) continue;
                try {
                    if (attach(owner, mode)) attached++;
                    else if (!contains(this.owners, owner)) this.owners.add(new WeakReference<>(owner));
                } catch (Throwable t) {
                    Xp.log("[MCNotify] re-attach to a live combine failed: " + t);
                }
            }
        }
        Xp.log("[MCNotify] adopted " + taken + " policy flow(s), re-attached to " + attached + "/"
                + total + " combine(s), mode=" + mode);
    }

    private static boolean contains(List<WeakReference<Object>> refs, Object o) {
        for (WeakReference<Object> r : refs) if (r.get() == o) return true;
        return false;
    }

    public synchronized String describe() {
        int live = 0;
        for (WeakReference<Object> policy : policies) if (policy.get() != null) live++;
        return "hook=v4 " + hookStatus + "\npolicyFlows=" + live + " requestedMode=" + requestedMode + " emissions=" + emissions
                + "\n" + String.join("\n", history);
    }

    public synchronized boolean attach(Object combinedFlow, int mode) throws ReflectiveOperationException {
        ClassLoader loader = combinedFlow.getClass().getClassLoader();
        for (Field field : combinedFlow.getClass().getDeclaredFields()) {
            if (!field.getType().isArray()) continue;
            Class<?> flowClass = field.getType().getComponentType();
            // The caller is restricted to the OEM combine and its array factory.
            // Avoid even comparing a Kotlin binary-name literal: R8 adapts that too.
            if (!flowClass.isInterface()) continue;
            field.setAccessible(true);
            Object[] original = (Object[]) field.get(combinedFlow);
            if (original == null || original.length != 7) continue;
            // Derive the host name at runtime. A literal Class.forName Kotlin name is
            // rewritten by our R8 to the module's obfuscated class, absent in SystemUI.
            Object policy = Class.forName(flowClass.getPackage().getName() + ".StateFlowKt", false, loader)
                    .getMethod("MutableStateFlow", Object.class).invoke(null, mode);
            Object[] inputs = (Object[]) Array.newInstance(flowClass, 8);
            System.arraycopy(original, 0, inputs, 0, 7);
            inputs[7] = policy;
            field.set(combinedFlow, inputs);
            policies.add(new WeakReference<>(policy));
            owners.add(new WeakReference<>(combinedFlow));
            return true;
        }
        return false;
    }

    public synchronized Object[] attachToCombine(Object factory, Object[] sources, int mode)
            throws ReflectiveOperationException {
        if (sources.length != 7) return sources;
        if (!attach(factory, mode)) return sources;
        for (Field field : factory.getClass().getDeclaredFields()) {
            if (field.getType() != sources.getClass()) continue;
            field.setAccessible(true);
            Object[] inputs = (Object[]) field.get(factory);
            if (inputs != null && inputs.length == 8) return inputs;
        }
        throw new IllegalStateException("policy attached without matching combine array");
    }

    public static Object[] withAvoidance(Object[] values, int fallbackMode) {
        Object[] result = values.clone();
        // The first seven inputs remain exactly the OEM's. Input eight wakes the combine
        // and carries the effective policy used for THIS emission, not a later global value.
        int mode = result.length == 8 && result[7] instanceof Integer ? (Integer) result[7] : fallbackMode;
        if ((result.length == 7 || result.length == 8) && mode != 0) {
            result[5] = mode == 2;
            result[6] = mode == 2;
        }
        return result;
    }

    public synchronized boolean refresh(int mode) throws ReflectiveOperationException {
        requestedMode = mode;
        boolean updated = false;
        Iterator<WeakReference<Object>> it = policies.iterator();
        while (it.hasNext()) {
            Object policy = it.next().get();
            if (policy == null) { it.remove(); continue; }
            Method setValue = Class.forName(policy.getClass().getPackage().getName()
                    + ".MutableStateFlow", false, policy.getClass().getClassLoader())
                    .getMethod("setValue", Object.class);
            setValue.invoke(policy, mode);
            updated = true;
        }
        return updated;
    }
}
