package ro.tinulica.tvhome;

import android.view.Choreographer;

/**
 * A damped spring stepped once per display frame, parameterised like UIKit / SwiftUI springs:
 * `response` is roughly the time to settle (seconds), `dampingRatio` 1 means no overshoot,
 * below 1 a gentle settle. Retargeting keeps the current value and velocity, so motion that is
 * interrupted half-way turns around smoothly instead of restarting from rest; that, more than
 * any curve, is why tvOS motion feels continuous ("Designing Fluid Interfaces", WWDC18).
 */
final class Spring implements Choreographer.FrameCallback {

    interface Listener {
        void onUpdate(float value);
    }

    private final Listener listener;
    private final float precision;
    private float value;
    private float velocity;
    private float target;
    private float stiffness;
    private float damping;
    private boolean running;
    private long lastFrame;
    private Runnable onRest;

    /** `precision` is the distance (in the value's own units) below which it counts as settled. */
    Spring(float value, float precision, Listener listener) {
        this.value = value;
        this.target = value;
        this.precision = precision;
        this.listener = listener;
        configure(0.35f, 0.85f);
    }

    Spring configure(float response, float dampingRatio) {
        float omega = (float) (2 * Math.PI / Math.max(0.04f, response));
        stiffness = omega * omega;
        damping = 2f * dampingRatio * omega;
        return this;
    }

    float value() {
        return value;
    }

    float target() {
        return target;
    }

    boolean isRunning() {
        return running;
    }

    void to(float newTarget) {
        target = newTarget;
        start();
    }

    void to(float newTarget, float response, float dampingRatio) {
        configure(response, dampingRatio);
        to(newTarget);
    }

    /** Adds velocity (units per second), e.g. for a nudge or a bump. */
    void kick(float addedVelocity) {
        velocity += addedVelocity;
        start();
    }

    /** Jumps to a value without animating. */
    void snap(float newValue) {
        stop();
        value = newValue;
        target = newValue;
        velocity = 0;
        listener.onUpdate(value);
    }

    /** Sets the current value but keeps animating towards the target from there. */
    void from(float newValue) {
        value = newValue;
        start();
    }

    void onRest(Runnable action) {
        onRest = action;
    }

    void stop() {
        if (running) {
            Choreographer.getInstance().removeFrameCallback(this);
            running = false;
        }
    }

    private void start() {
        if (!running) {
            running = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        float dt = lastFrame == 0 ? 1f / 60f : Math.min((frameTimeNanos - lastFrame) / 1e9f, 1f / 20f);
        lastFrame = frameTimeNanos;
        // Semi-implicit Euler in small steps: stable for stiff springs at 30-60 fps.
        int steps = 4;
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float acceleration = -stiffness * (value - target) - damping * velocity;
            velocity += acceleration * h;
            value += velocity * h;
        }
        if (Math.abs(value - target) < precision && Math.abs(velocity) < precision * 10f) {
            value = target;
            velocity = 0;
            running = false;
            listener.onUpdate(value);
            if (onRest != null) {
                onRest.run();
            }
            return;
        }
        listener.onUpdate(value);
        Choreographer.getInstance().postFrameCallback(this);
    }
}
