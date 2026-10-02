package btm.m.os4.systemuihook.newnotificationcenter;

/** Gesture state independent of the header's delayed Folme animation and render caches. */
public final class IosShadeState {
    private static final float GESTURE_DISTANCE_SCALE = 1.2f;
    private boolean dragging;
    private float nativeFraction;
    private float dragStartNative;
    private float dragStartProgress;
    public float progress;
    public boolean controlCenter;
    public boolean switching;
    public float switchFraction = 1f;

    public float gestureTarget(float height, float threshold, boolean tracking) {
        // Keep overscroll until AFTER the gesture distance is scaled. Clamping here
        // parks the sheet at 1 / scale even though the finger keeps moving.
        float next = Float.isFinite(height) && Float.isFinite(threshold) && threshold > 0f
                ? height / threshold : 0f;
        if (tracking) {
            if (!dragging) {
                dragStartNative = nativeFraction;
                dragStartProgress = progress;
            }
            dragging = true;
            nativeFraction = next;
            return fraction(dragStartProgress + (next - dragStartNative) / GESTURE_DISTANCE_SCALE, 1f);
        }
        dragging = false;
        nativeFraction = fraction(next, 1f);
        return nativeFraction; // Release still reaches the native open/closed endpoint.
    }

    public boolean switchProgress(float value, boolean inSwitch) {
        float next = fraction(value, 1f);
        if (!inSwitch && !switching && next == switchFraction) return false;
        switching = inSwitch;
        switchFraction = next;
        controlCenter(!inSwitch && next <= 0f);
        if (inSwitch) {
            dragging = false;
            nativeFraction = 1f;
            move(1f);
        }
        return true;
    }

    static float fraction(float height, float threshold) {
        if (!Float.isFinite(height) || !Float.isFinite(threshold) || threshold <= 0f) return 0f;
        return Math.max(0f, Math.min(1f, height / threshold));
    }

    public void move(float value) {
        progress = controlCenter ? 0f : fraction(value, 1f);
    }

    public void controlCenter(boolean visible) {
        controlCenter = visible;
        if (visible) {
            progress = 0f;
            dragging = false;
            nativeFraction = 0f;
        }
    }

    public void notification() {
        controlCenter = false;
    }

    public void clear() {
        progress = 0f;
        controlCenter = false;
        switching = false;
        switchFraction = 1f;
        dragging = false;
        nativeFraction = 0f;
    }
}
