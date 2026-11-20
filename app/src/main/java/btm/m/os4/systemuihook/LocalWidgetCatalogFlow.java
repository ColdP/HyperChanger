// SPDX-License-Identifier: GPL-3.0-only
// Adapted from HyperModifier; see licenses/HyperModifier-GPL-3.0.txt and NOTICE.
package btm.m.os4.systemuihook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Adapter for this assistant version's immutable flowOf(value), preserving native collect. */
final class LocalWidgetCatalogFlow {
    interface Mapper { Object map(Object value) throws Throwable; }
    private final Class<?> singletonType;
    private final Constructor<?> constructor;
    private final Field value;

    LocalWidgetCatalogFlow(Class<?> singletonType, Class<?> flowType, Class<?> collectorType,
                           Class<?> continuationType) throws ReflectiveOperationException {
        if (!flowType.isAssignableFrom(singletonType)) throw new IllegalArgumentException("Expected Flow implementation");
        singletonType.getDeclaredMethod("d", collectorType, continuationType);
        this.singletonType = singletonType;
        constructor = singletonType.getConstructor(Object.class);
        value = singletonType.getDeclaredField("a");
        if (value.getType() != Object.class || !Modifier.isFinal(value.getModifiers()))
            throw new IllegalArgumentException("Expected immutable flowOf payload");
        value.setAccessible(true);
    }

    boolean supports(Object flow) { return flow != null && flow.getClass() == singletonType; }

    Object map(Object flow, Mapper mapper) throws Throwable {
        // Unknown flows (including another module's wrappers) and coroutine failures stay intact.
        if (!supports(flow)) return flow;
        Object original = value.get(flow);
        Object mapped = mapper.map(original);
        return mapped == original ? flow : constructor.newInstance(mapped);
    }
}
