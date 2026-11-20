// SPDX-License-Identifier: GPL-3.0-only
// Adapted from HyperModifier; see licenses/HyperModifier-GPL-3.0.txt and NOTICE.
package btm.m.os4.systemuihook;

import android.graphics.drawable.Drawable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Explicit state for locally installed AppWidgets; never used to modify remote products. */
final class LocalWidgetCatalogModels {
    private final Constructor<?> wrapper, tip;
    private final Method setPay, setBought, setAuthorityPass;

    LocalWidgetCatalogModels(Class<?> wrapperType, Class<?> detailType, Class<?> tipType)
            throws ReflectiveOperationException {
        wrapper = wrapperType.getConstructor(List.class, String.class, String.class, String.class,
                int.class, String.class, String.class, String.class, String.class, String.class,
                tipType, ArrayList.class, boolean.class, int.class, Drawable.class,
                boolean.class, Long.class, boolean.class, boolean.class);
        tip = tipType.getConstructor(String.class, String.class);
        setPay = detailType.getMethod("setPay", boolean.class);
        setBought = detailType.getMethod("setBought", boolean.class);
        setAuthorityPass = detailType.getMethod("setAuthorityPass", boolean.class);
    }

    Object wrap(List<?> rows, String pkg, String name) throws ReflectiveOperationException {
        // The short overload defaults to isPay=true, priceInCent=null. The stock UI
        // force-unboxes the price for unpaid purchases, so local state must be explicit.
        return wrapper.newInstance(rows, pkg, "", name, 0, "", "", "", "", "",
                tip.newInstance("", ""), new ArrayList<String>(), false, 0, null,
                false, Long.valueOf(0), false, false);
    }

    void prepareLocalDetail(Object newlyCreatedLocalRow) throws ReflectiveOperationException {
        setPay.invoke(newlyCreatedLocalRow, false);
        setBought.invoke(newlyCreatedLocalRow, false);
        setAuthorityPass.invoke(newlyCreatedLocalRow, true);
    }
}
