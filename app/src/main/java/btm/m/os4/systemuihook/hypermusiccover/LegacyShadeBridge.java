// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.hypermusiccover;

import android.graphics.Bitmap;

/** Narrow bridge to the single existing notification-center state owner. */
public final class LegacyShadeBridge {
    private LegacyShadeBridge() {}
    public static final String[] CFG_KEYS = ShadeLayer.CFG_KEYS;
    public static void configure(String key, int value) { ShadeLayer.configure(key, value); }
    public static int cfgInt(String key) { return ShadeLayer.cfgInt(key); }
    public static String dumpCfg() { return ShadeLayer.dumpCfg(); }
    public static int mode() { return ShadeLayer.mode(); }
    public static float expansion() { return ShadeLayer.ratio(); }
    public static boolean hasArt() { return Main.hasSharedShadeArt(); }
    public static boolean keepsArt() { return false; }
    public static void setArt(Bitmap borrowed) { Main.acceptMusicArt(borrowed); }
}
