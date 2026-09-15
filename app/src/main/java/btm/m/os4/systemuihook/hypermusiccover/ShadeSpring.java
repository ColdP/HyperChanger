package btm.m.os4.systemuihook.hypermusiccover;

import android.view.Choreographer;

/**
 * A mass-spring-damper, integrated on the frame callback.
 *
 * This is an instance and not the static one in Main on purpose. Main's spring drives the clock
 * from a single set of static fields, which is fine while one thing moves at a time - but the
 * shade has two springs alive at once (the blur and the curtain), and they have different
 * stiffnesses and must be able to run together. Sharing one would have them fighting.
 *
 * Hand-rolled rather than androidx.dynamicanimation.SpringForce for the reason the module it is
 * ported from gives: the copy of that library in SystemUI lives in SystemUI's own classloader, so
 * reaching it means either reflecting into it or bundling a second copy, and this is forty lines.
 *
 * Mass is 1, so `c = 2 * zeta * sqrt(k)` - the standard critical-damping form, and `dampingRatio`
 * reads the way it does everywhere else: 1.0 is critically damped, below that overshoots.
 *
 * The time constant depends only on stiffness and dampingRatio, not on the units of the value, so
 * the same class runs in ratio space and in pixels. Only the two rest thresholds are unit-bound,
 * and both springs here run in ratio space 0..1.
 */
final class ShadeSpring implements Choreographer.FrameCallback {

    interface Listener {
        void onSpringUpdate(float value);
    }

    /**
     * The largest step the integrator is allowed to take.
     *
     * A variable frame time is subdivided into fixed steps this long before integrating: Euler
     * is only stable while h is small relative to the spring's period, and a dropped frame
     * arriving as one 40ms step is exactly the case that blows up.
     */
    private static final float MAX_STEP = 0.004f;

    /** Stop below these - the value, and the speed - so a spring settles instead of creeping. */
    private static final float REST_VALUE = 0.002f;
    private static final float REST_VELOCITY = 0.02f;

    /** A frame longer than this is treated as this; a stall must not fling the spring. */
    private static final float MAX_FRAME = 0.064f;

    private static Choreographer sChoreographer;

    /**
     * Main thread only, and lazily: Choreographer.getInstance() is bound to the calling thread's
     * looper, so resolving it in a static initialiser would silently bind it to whichever thread
     * happened to load this class first.
     */
    private static Choreographer choreographer() {
        Choreographer c = sChoreographer;
        if (c == null) {
            c = Choreographer.getInstance();
            sChoreographer = c;
        }
        return c;
    }

    // Tuning. Public by package because the shade's settings write them directly.
    float stiffness = 100f;
    float dampingRatio = 0.9f;
    float maxValue = 1f;

    private final Listener listener;
    private float value;
    private float velocity;
    private float target;
    private boolean running;
    /** Whether a frame callback is queued. The flag `running` cannot answer this. */
    private boolean posted;
    private long lastFrameNanos;

    ShadeSpring(Listener listener) {
        this.listener = listener;
    }

    float value() {
        return value;
    }

    /** Whether the frame callback is still posted, i.e. the spring is still on its way. */
    boolean running() {
        return running;
    }

    /** Spring toward `t`, starting the frame callback if it is not already running. */
    void animateTo(float t) {
        if (t < 0f) t = 0f;
        if (t > maxValue) t = maxValue;
        target = t;
        running = true;
        // Posted against a flag of its own rather than against `running`, and the time base is
        // kept rather than zeroed. Both halves are the fix for a spring that reports itself as
        // running and never moves - measured, value frozen at 0.06 with the target at 1.0 and the
        // spring's own flag saying "running", for ten seconds:
        //
        // - `if (running) return;` assumes the flag and a posted callback cannot disagree. When
        //   they do - a callback dropped or removed while the flag stayed set - nothing posts
        //   again and the spring is dead while claiming otherwise. Re-posting when nothing is
        //   pending costs nothing and cannot get stuck. Deliberately NOT remove-then-post: that
        //   would also discard a callback already queued for the current frame, delaying the
        //   spring by one frame on every call and making it crawl.
        // - `lastFrameNanos = 0L` made the first frame after a restart record the time base and
        //   return WITHOUT integrating. A caller that stops and restarts the spring every frame
        //   then throws away every frame and never advances at all. The clamp in doFrame already
        //   covers a time base that has gone stale.
        postFrame();
    }

    private void postFrame() {
        if (posted) return;
        posted = true;
        choreographer().postFrameCallback(this);
    }

    /**
     * Jump, without any spring at all.
     *
     * This is what keeps the curtain glued to the finger: while the panel is being dragged the
     * edge must not lag, and only on release does it hand back to {@link #animateTo}.
     */
    void snapTo(float t) {
        stop();
        value = clamp(t);
        // A teleport carries no velocity, and this line is not tidiness.
        //
        // stop() is the only path that clears `velocity`, and the spring is not the only thing
        // that moves the value: while the panel is being dragged the edge is snapTo()-ed every
        // frame, which assigns `value` without ever running the integrator - so `velocity` keeps
        // whatever it was left at by the last time the spring actually ran. The next animateTo()
        // then integrates the first step with that stale velocity and throws the value across the
        // screen. Measured before this: 0.162 -> 1.0 and 0.937 -> 0.0 in single frames, and the
        // edge parked at 0.83 over a closed panel.
        velocity = 0f;
        listener.onSpringUpdate(value);
    }

    void stop() {
        running = false;
        velocity = 0f;
        // Not guarded on `running`: a no-op for an unposted callback, and the case that matters -
        // stop() on an already-stopped spring - is exactly when a stale velocity is sitting there.
        choreographer().removeFrameCallback(this);
        posted = false;
    }

    private float clamp(float t) {
        if (t < 0f) return 0f;
        return t > maxValue ? maxValue : t;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        posted = false;
        if (!running) return;
        if (lastFrameNanos == 0L) {
            // The very first frame of this spring's life only establishes the time base - there
            // is no dt yet. Reached once, because animateTo() no longer zeroes it on a restart.
            lastFrameNanos = frameTimeNanos;
            postFrame();
            return;
        }
        float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = frameTimeNanos;
        if (dt <= 0f) {
            postFrame();
            return;
        }
        if (dt > MAX_FRAME) dt = MAX_FRAME;

        step(dt);
        value = clamp(value);
        listener.onSpringUpdate(value);

        if (Math.abs(value - target) < REST_VALUE && Math.abs(velocity) < REST_VELOCITY) {
            value = target;
            velocity = 0f;
            listener.onSpringUpdate(value);
            stop();
        } else {
            postFrame();
        }
    }

    /** Semi-implicit Euler, subdivided so the step size stays inside the stable range. */
    private void step(float dt) {
        int n = (int) Math.ceil(dt / MAX_STEP);
        if (n < 1) n = 1;
        final float h = dt / n;
        final float c = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        for (int i = 0; i < n; i++) {
            final float a = -stiffness * (value - target) - c * velocity;
            velocity += a * h;
            value += velocity * h;
        }
    }
}
