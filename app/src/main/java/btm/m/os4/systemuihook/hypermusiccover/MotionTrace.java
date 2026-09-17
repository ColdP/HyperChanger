package btm.m.os4.systemuihook.hypermusiccover;

import android.view.Choreographer;
import android.view.View;

/**
 * Read-only probe: what the OEM's own lock screen views do, frame by frame, through a wake.
 *
 * Armed from adb (`op motiontrace --ei n 3`), it records the next n wakes - and, for comparison,
 * the next n cover toggles - as one line per frame: scale, pivot, translation, alpha and screen
 * position of each keyguard layer the wake is known to animate. Nothing is written to any view.
 * The lines are kept in memory while the trace runs and logged when it ends, so the logging
 * itself cannot cost the frames being measured.
 */
final class MotionTrace {

    private MotionTrace() {
    }

    private static final String TAG = "[MCProbe] ";

    /** Layers by resource id, the ones KeyguardPanelViewController groups as animationViews. */
    private static final String[] IDS = {
            "keyguard_root_view", "notification_panel", "notification_container_parent",
            "shared_notification_container", "notification_stack_scroller", "keyguard_info_layer",
            "keyguard_foreground_layer", "miui_keyguard_clock_container", "clock_animation_container",
            "time_group", "text_area", "mi_media_controls", "keyguard_header",
    };

    private static volatile int sRemaining;
    private static boolean sRunning;

    static void arm(int count) {
        sRemaining = Math.max(0, count);
        Xp.log(TAG + "motiontrace armed for " + sRemaining + " events");
    }

    /** Called on the main thread at the start of a wake or a toggle. */
    static void start(final String what) {
        if (sRemaining <= 0 || sRunning) return;
        View base = Main.sContainer;
        if (base == null) return;
        sRemaining--;
        sRunning = true;
        final View root = base.getRootView();
        final View[] views = new View[IDS.length];
        for (int i = 0; i < IDS.length; i++) {
            int id = root.getResources().getIdentifier(IDS[i], "id", "com.android.systemui");
            views[i] = id == 0 ? null : root.findViewById(id);
        }
        final StringBuilder sb = new StringBuilder();
        final long t0 = System.nanoTime();
        final int[] loc = new int[2];
        sb.append("motiontrace ").append(what).append(" views:");
        for (int i = 0; i < IDS.length; i++) {
            sb.append(' ').append(i).append('=').append(IDS[i]).append(views[i] == null ? "(none)" : "");
        }
        sb.append('\n');
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long ms = (System.nanoTime() - t0) / 1000000L;
                sb.append("t=").append(ms).append(ClockCollapse.traceLine());
                for (int i = 0; i < views.length; i++) {
                    View v = views[i];
                    if (v == null) continue;
                    v.getLocationOnScreen(loc);
                    sb.append(" |").append(i)
                      .append(" s=").append(r3(v.getScaleX())).append(',').append(r3(v.getScaleY()))
                      .append(" p=").append(Math.round(v.getPivotX())).append(',').append(Math.round(v.getPivotY()))
                      .append(" ty=").append(r1(v.getTranslationY()))
                      .append(" a=").append(r2(v.getAlpha())).append('/').append(r2(v.getTransitionAlpha()))
                      .append(" y=").append(loc[1]).append(" h=").append(v.getHeight())
                      .append(v.getVisibility() == View.VISIBLE ? "" : " vis=" + v.getVisibility());
                }
                sb.append('\n');
                if (ms < 1200) {
                    Choreographer.getInstance().postFrameCallback(this);
                    return;
                }
                sRunning = false;
                // Logcat truncates long entries; one entry per frame line.
                for (String line : sb.toString().split("\n")) Xp.log(TAG + line);
            }
        });
    }

    private static String r1(float v) {
        return String.valueOf(Math.round(v * 10f) / 10f);
    }

    private static String r2(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    private static String r3(float v) {
        return String.valueOf(Math.round(v * 1000f) / 1000f);
    }
}
