package com.miui.keyguard.shortcuts.manager;

public final class MiuiShortcutManager {
    public static final MiuiShortcutManager INSTANCE = new MiuiShortcutManager();
    public boolean keyguardGoingAway = true;
    public boolean inAppOpening;
    public boolean selected;
    public boolean succeeds = true;
    public int launches, removals;
    public boolean startShortcutActivity() {
        if (!selected) throw new AssertionError("native selection lost before launch");
        launches++;
        return succeeds;
    }
    public void setInAppOpening(boolean value) { inAppOpening = value; }
    public void resetOccludedMovedShortcut() { selected = false; }
    public void removeWindowIfNeed() {
        if (inAppOpening) throw new AssertionError("window removed before reset");
        removals++;
    }
}
