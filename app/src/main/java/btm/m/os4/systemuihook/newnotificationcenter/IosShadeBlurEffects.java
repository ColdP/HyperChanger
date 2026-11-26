package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.RenderEffect;
import android.graphics.Shader;

/** UI-thread cache for the shade's focus steps, shared by its visual layers. */
public final class IosShadeBlurEffects {
    private static final RenderEffect[] EFFECTS = new RenderEffect[257];

    private IosShadeBlurEffects() {}

    /**
     * Progressive step quantization: 2px precision near crisp focus, coarser buckets at heavy
     * blur radii where sub-step changes are imperceptible, cutting full-screen RenderNode rebuilds.
     */
    public static int quantize(float radius) {
        if (!Float.isFinite(radius) || radius <= 0.5f) return 0;
        int r = Math.round(radius);
        if (r <= 16) return ((r + 1) / 2) * 2;
        if (r <= 40) return ((r + 2) / 4) * 4;
        return ((r + 4) / 8) * 8;
    }

    public static RenderEffect blur(float radius) {
        int quantized = quantize(radius);
        if (quantized <= 0) return null;
        int step = quantized / 2;
        if (step >= EFFECTS.length) {
            return RenderEffect.createBlurEffect(quantized, quantized, Shader.TileMode.CLAMP);
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
