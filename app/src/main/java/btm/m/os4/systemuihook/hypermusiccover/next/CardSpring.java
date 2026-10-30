// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

/** Frame-rate-independent, lightly underdamped scale response for the square cover. */
final class CardSpring {
    static final float PLAYING = 1f;
    static final float PAUSED = 0.90f;
    /**
     * The response it had before it followed the app's 缩放动画阻尼 - 2pi/18 - kept for callers
     * that do not pass one. The damping stays its own: it is what gives the card its rebound.
     */
    static final float DEFAULT_RESPONSE = (float) (2.0 * Math.PI / 18.0);
    private static final double DAMPING = 0.56;

    float value = PLAYING;
    float velocity;

    void step(float target, float dt) {
        step(target, dt, DEFAULT_RESPONSE);
    }

    /** @param response seconds per undamped cycle - Main.sClockResponse, like the other springs */
    void step(float target, float dt, float response) {
        if (dt <= 0f) return;
        double frequency = 2.0 * Math.PI / Math.max(0.12f, response);
        double damped = frequency * Math.sqrt(1.0 - DAMPING * DAMPING);
        double x = value - target;
        double v = velocity;
        double phase = damped * dt;
        double decay = Math.exp(-DAMPING * frequency * dt);
        double a = x;
        double b = (v + DAMPING * frequency * x) / damped;
        double cos = Math.cos(phase), sin = Math.sin(phase);
        double wave = a * cos + b * sin;
        value = (float) (target + decay * wave);
        velocity = (float) (decay * (-DAMPING * frequency * wave
                + damped * (-a * sin + b * cos)));
        if (atRest(target)) snap(target);
    }

    boolean atRest(float target) {
        return Math.abs(value - target) < 0.0005f && Math.abs(velocity) < 0.005f;
    }

    void snap(float target) {
        value = target;
        velocity = 0f;
    }
}
