package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.RenderEffect;
import android.graphics.Shader;

/** UI-thread cache for the shade's two-pixel focus steps, shared by its visual layers. */
public final class IosShadeBlurEffects {
    private static final RenderEffect[] EFFECTS = new RenderEffect[257];

    private IosShadeBlurEffects() {}

    public static RenderEffect blur(float radius) {
        if (!Float.isFinite(radius) || radius <= 0f) return null;
        int step = Math.max(1, Math.round(radius / 2f));
        if (step >= EFFECTS.length) {
            return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);
        }
        RenderEffect effect = EFFECTS[step];
        if (effect == null) {
            effect = RenderEffect.createBlurEffect(step * 2f, step * 2f, Shader.TileMode.CLAMP);
            EFFECTS[step] = effect;
        }
        return effect;
    }

    static void clear() {
        java.util.Arrays.fill(EFFECTS, null);
    }
}
