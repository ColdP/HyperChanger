package btm.m.os4.systemuihook.newnotificationcenter;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/** Owns the opt-in wallpaper transition and its glass underlay on the UI thread. */
public final class IosShadeWallpaperReveal {
    private final ImageView wallpaper;
    private final IosShadeGlassView glass;
    private final IosShadeRevealState state = new IosShadeRevealState();
    private ValueAnimator animator;
    private float animatorTarget;
    private boolean landscape;
    private float previousProgress;
    private boolean exiting;
    private boolean stagedMode;
    private int lastBlur = -1;
    private ViewGroup root;

    public IosShadeWallpaperReveal(ImageView wallpaper, IosShadeGlassView glass) {
        this.wallpaper = wallpaper;
        this.glass = glass;
        glass.setOnBackdropReady(() -> {
            if (!stagedMode) return;
            apply();
            animateToTarget();
        });
    }

    public void setRefractionEnabled(boolean enabled) {
        glass.setRefractionEnabled(enabled);
    }

    public void update(boolean enabled, boolean showWallpaper, float progress, boolean switching, float switchFraction,
                       boolean landscape, ViewGroup root, float sheetOffset, float radius) {
        this.landscape = landscape;
        this.root = root;
        stagedMode = enabled;
        if (progress <= 0f || !enabled) exiting = false;
        else if (progress < previousProgress) exiting = true;
        else if (progress > previousProgress) exiting = false;
        previousProgress = progress;
        boolean full = showWallpaper && progress >= 1f && switchFraction >= 1f && !switching;
        boolean start = state.update(enabled, progress, full, showWallpaper && switching);
        if (!enabled || !start || animatorTarget != state.targetAlpha) cancel();
        if (!enabled) {
            wallpaper.setAlpha(1f);
            lastBlur = -1; // The ordinary animation owns the effect in this mode.
            IosNotificationCenterPresentation.setWallpaperReveal(1f, 0);
            glass.reset();
            return;
        }
        // Retain the scene while fully revealed; an upward gesture needs it immediately.
        glass.update(root, sheetOffset, radius, progress > 0f);
        glass.setExiting(exiting);
        apply();
        if (start) animateToTarget();
    }

    private void animateToTarget() {
        if (!stagedMode || animator != null || state.alpha == state.targetAlpha) return;
        // A failed/delayed capture must not remove the last valid local sampling source.
        // Keep the wallpaper on exit until the scene is ready, or until native close.
        if (state.targetAlpha < state.alpha && !glass.hasBackdrop()) return;
        animatorTarget = state.targetAlpha;
        ValueAnimator next = ValueAnimator.ofFloat(state.alpha, animatorTarget);
        next.setDuration(Math.max(1L, Math.round(420f * Math.abs(animatorTarget - state.alpha))));
        next.setInterpolator(new DecelerateInterpolator(1.5f));
        animator = next;
        next.addUpdateListener(animation -> {
            if (animator != animation) return;
            state.advance((Float) animation.getAnimatedValue());
            apply();
        });
        next.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (animator == animation) animator = null;
            }
        });
        next.start();
    }

    private void apply() {
        float alpha = state.alpha;
        IosShadeBackdropBlur.setWallpaperReveal(root, alpha);
        if (wallpaper.getAlpha() != alpha) wallpaper.setAlpha(alpha);
        float density = wallpaper.getResources().getDisplayMetrics().density;
        int blur = Math.round((landscape ? 22f + 14f * (1f - alpha) : 28f * (1f - alpha)) * density);
        blur = (blur + 1) / 2 * 2;
        if (lastBlur != blur) {
            wallpaper.setRenderEffect(IosShadeBlurEffects.blur(blur));
            lastBlur = blur;
        }
        // The existing foreground remains above the clock, with its original geometry.
        IosNotificationCenterPresentation.setWallpaperReveal(alpha, landscape ? 0 : blur);
        // The stationary scene below this sheet remains a populated local sampling source.
        // Crossfade the artwork, never retire/recreate a native sampler during the gesture.
        glass.setAlpha(1f - alpha);
    }

    private void cancel() {
        ValueAnimator previous = animator;
        animator = null;
        if (previous != null) previous.cancel();
    }

    public void reset() {
        stagedMode = false;
        cancel();
        IosShadeBackdropBlur.setWallpaperReveal(root, 0f);
        state.reset();
        previousProgress = 0f;
        exiting = false;
        wallpaper.setAlpha(1f);
        lastBlur = -1;
        glass.reset();
        root = null;
        IosNotificationCenterPresentation.setWallpaperReveal(1f, 0);
    }
}
