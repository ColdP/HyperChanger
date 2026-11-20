// SPDX-License-Identifier: GPL-3.0-only
// Adapted from HyperModifier; see licenses/HyperModifier-GPL-3.0.txt and NOTICE.
package btm.m.os4.systemuihook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolve Job from a typed, retained semantic anchor; never assume its R8 class/Key names. */
final class LocalWidgetCatalogJob {
    final Method contextGet, active;
    final Object key;

    LocalWidgetCatalogJob(Class<?> context, Class<?> job) throws ReflectiveOperationException {
        Method get = null;
        for (Method method : context.getMethods()) {
            if (method.getName().equals("get") && method.getParameterCount() == 1) {
                if (get != null) throw new NoSuchMethodException("Ambiguous CoroutineContext.get");
                get = method;
            }
        }
        if (get == null || !job.isInterface() || !get.getReturnType().isAssignableFrom(job))
            throw new NoSuchMethodException("Expected Job to be a CoroutineContext element");
        active = job.getMethod("isActive");
        if (active.getReturnType() != boolean.class)
            throw new NoSuchMethodException("Expected Job.isActive(): boolean");
        Class<?> keyType = get.getParameterTypes()[0];
        Object found = null;
        for (Class<?> member : job.getDeclaredClasses()) {
            if (!keyType.isAssignableFrom(member)) continue;
            for (Field field : member.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                        || field.getType() != member) continue;
                field.setAccessible(true);
                Object candidate = field.get(null);
                if (candidate == null) continue;
                if (found != null && found != candidate) throw new NoSuchFieldException("Ambiguous Job.Key");
                found = candidate;
            }
        }
        if (found == null) throw new NoSuchFieldException("Job.Key singleton");
        contextGet = get;
        key = found;
    }
}
