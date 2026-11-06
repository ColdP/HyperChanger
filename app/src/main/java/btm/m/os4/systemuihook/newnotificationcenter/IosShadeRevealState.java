package btm.m.os4.systemuihook.newnotificationcenter;

/** Wallpaper time is independent of sheet distance once the sheet is fully open. */
public final class IosShadeRevealState {
    private boolean visible;
    private boolean staged;
    public float alpha;
    public float targetAlpha;

    public boolean update(boolean enabled, float progress, boolean fullyOpen, boolean horizontalSwitch) {
        if (!enabled) {
            staged = false;
            visible = progress > 0f;
            alpha = 1f;
            targetAlpha = 1f;
            return false;
        }
        if (!staged || !visible || progress <= 0f) alpha = 0f;
        staged = true;
        visible = progress > 0f;
        // A panel switch presents an already expanded notification page. Its
        // wallpaper is ready before the page slides in, without another reveal.
        if (visible && horizontalSwitch) {
            alpha = targetAlpha = 1f;
            return false;
        }
        targetAlpha = visible && fullyOpen && progress >= 1f ? 1f : 0f;
        return visible && alpha != targetAlpha;
    }

    public void advance(float value) {
        if (staged && visible && Float.isFinite(value)) {
            alpha = Math.max(0f, Math.min(1f, value));
        }
    }

    public void reset() {
        visible = false;
        staged = false;
        alpha = 0f;
        targetAlpha = 0f;
    }
}
