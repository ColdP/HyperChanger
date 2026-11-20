// SPDX-License-Identifier: GPL-3.0-only
// Adapted from HyperModifier; see licenses/HyperModifier-GPL-3.0.txt and NOTICE.
package btm.m.os4.systemuihook;

/** Stable identities and the size vocabulary understood by the stock picker. */
final class LocalWidgetCatalogPolicy {
    static final String PREFIX = "hc-local-widget:";
    private LocalWidgetCatalogPolicy() {}
    static boolean local(String value) { return value != null && value.startsWith(PREFIX); }
    static String previewUri(String pkg, int previewImage, int widgetIcon, int appIcon) {
        int resource = previewImage != 0 ? previewImage : widgetIcon != 0 ? widgetIcon : appIcon;
        // Keep the native Glide request and success listener. An empty URL only displays
        // a placeholder and never makes ObserveGlideLoadStatusImageView ready to add.
        if (resource == 0) return "android.resource://android/" + android.R.drawable.sym_def_app_icon;
        return "android.resource://" + pkg + "/" + resource;
    }
    static String providerKey(String pkg, String name) {
        if (name.startsWith(".")) name = pkg + name;
        else if (name.indexOf('.') < 0) name = pkg + "." + name;
        return pkg + "/" + name;
    }
    static int style(int x, int y) {
        if (x == 1 && y == 1) return 9;
        if (x == 1 && y == 2) return 5;
        if (x == 2 && y == 1) return 6;
        if (x == 2 && y == 2) return 1;
        if (x == 2 && y == 3) return 7;
        if (x == 4 && y == 1) return 3;
        if (x == 4 && y == 2) return 2;
        if (x == 4 && y == 4) return 4;
        if (x == 8 && y == 4) return 8;
        return 0;
    }
}
