package ro.tinulica.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;

/**
 * TV Home's Cinema sign, drawn in code: a ring of glass that fills with silver light, white to
 * a soft grey, around a white play sign. It fills like a loading circle, its light slowly travels round, and
 * the play sign pops; the same sign sits in the Control Center, the "Cinema mode" capsule and
 * the logo.
 */
final class CinemaMark {

    /** Silver light: white to a soft grey and back, like glass catching the light. */
    static final int[] COLOURS = {0xFFFFFFFF, 0xFFE4E7EB, 0xFFB6BCC5, 0xFFF4F5F7, 0xFFCBD0D7, 0xFFFFFFFF};

    private static final Paint RING = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint GLASS = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint RIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF ARC = new RectF();
    private static final Paint PLAY = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path TRIANGLE = new Path();
    private static final RectF BOX = new RectF();
    private static final Matrix TURN = new Matrix();

    private CinemaMark() {
    }

    /**
     * The sign centred at (cx, cy) with radius `r`: `ring` 0..1 how much of the ring is drawn
     * (from the top, clockwise), `turn` how far its colours have turned (degrees), `play` 0..1
     * the size of the play sign.
     */
    static void draw(Canvas canvas, float cx, float cy, float r, float ring, float turn, float play, int alpha) {
        draw(canvas, cx, cy, r, ring, turn, play, alpha, false);
    }

    /** With `colouredPlay` the play sign takes the ring's colours (for white tiles). */
    static void draw(Canvas canvas, float cx, float cy, float r, float ring, float turn, float play, int alpha,
                     boolean colouredPlay) {
        // A tube of glass: its frosted body, the warm light filling it (the part that loads),
        // a hairline rim inside and out, a bright highlight at the top left where the light
        // comes from and a faint reflection at the bottom right.
        float stroke = r * 0.26f;
        float inset = stroke / 2f;
        BOX.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset);
        GLASS.setStyle(Paint.Style.STROKE);
        GLASS.setStrokeWidth(stroke);
        GLASS.setColor(Color.WHITE);
        GLASS.setAlpha(Math.round(0x26 * alpha / 255f));
        canvas.drawOval(BOX, GLASS);
        RING.setStyle(Paint.Style.STROKE);
        RING.setStrokeWidth(stroke * 0.86f);
        RING.setStrokeCap(Paint.Cap.ROUND);
        SweepGradient colours = new SweepGradient(cx, cy, COLOURS, null);
        TURN.setRotate(turn - 90f, cx, cy);
        colours.setLocalMatrix(TURN);
        RING.setShader(colours);
        RING.setAlpha(Math.round(0xD9 * alpha / 255f));
        if (ring >= 0.999f) {
            canvas.drawOval(BOX, RING);
        } else if (ring > 0.001f) {
            canvas.drawArc(BOX, -90f, 360f * ring, false, RING);
        }
        float hair = Math.max(1f, stroke * 0.1f);
        RIM.setStyle(Paint.Style.STROKE);
        RIM.setStrokeWidth(hair);
        RIM.setColor(Color.WHITE);
        RIM.setAlpha(Math.round(0x8C * alpha / 255f));
        float outer = r - hair / 2f;
        float inner = r - stroke + hair / 2f;
        ARC.set(cx - outer, cy - outer, cx + outer, cy + outer);
        canvas.drawOval(ARC, RIM);
        RIM.setAlpha(Math.round(0x4D * alpha / 255f));
        ARC.set(cx - inner, cy - inner, cx + inner, cy + inner);
        canvas.drawOval(ARC, RIM);
        SHINE.setStyle(Paint.Style.STROKE);
        SHINE.setStrokeCap(Paint.Cap.ROUND);
        SHINE.setColor(Color.WHITE);
        SHINE.setStrokeWidth(stroke * 0.3f);
        SHINE.setAlpha(Math.round(0xE6 * alpha / 255f));
        float high = r - stroke * 0.33f;
        ARC.set(cx - high, cy - high, cx + high, cy + high);
        canvas.drawArc(ARC, 196f, 52f, false, SHINE);
        SHINE.setStrokeWidth(stroke * 0.2f);
        SHINE.setAlpha(Math.round(0x59 * alpha / 255f));
        float low = r - stroke * 0.66f;
        ARC.set(cx - low, cy - low, cx + low, cy + low);
        canvas.drawArc(ARC, 18f, 40f, false, SHINE);
        if (play > 0.001f) {
            // A rounded triangle pointing right, nudged right so it looks centred.
            float s = r * 0.46f * play;
            float ox = cx + s * 0.16f;
            TRIANGLE.reset();
            TRIANGLE.moveTo(ox - s * 0.6f, cy - s * 0.78f);
            TRIANGLE.lineTo(ox + s * 0.78f, cy);
            TRIANGLE.lineTo(ox - s * 0.6f, cy + s * 0.78f);
            TRIANGLE.close();
            PLAY.setShader(null);
            PLAY.setColor(Color.WHITE);
            PLAY.setAlpha(alpha);
            PLAY.setPathEffect(new CornerPathEffect(s * 0.28f));
            canvas.drawPath(TRIANGLE, PLAY);
        }
    }

    /** The finished sign as a bitmap (for tiles). */
    static Bitmap bitmap(int size) {
        // On a dark glass disc, so the silver ring shows on white (focused) tiles too.
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setShader(new android.graphics.LinearGradient(0, 0, 0, size, 0xF23A3B40, 0xF2141416,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, disc);
        draw(c, size / 2f, size / 2f, size / 2f * 0.72f, 1f, 30f, 1f, 255, false);
        return b;
    }

    /**
     * An iOS-style switch in glass and silver: a frosted track that turns light when on, a
     * white knob with a soft shadow that slides over on a spring, stretching as it goes.
     */
    static final class Toggle extends android.view.View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float dp;
        private float pos;
        private float stretch;

        Toggle(Context context, float dp, boolean on) {
            super(context);
            this.dp = dp;
            pos = on ? 1f : 0f;
            track.setColor(0x40FFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, dp * 0.7f));
            edge.setColor(0x59FFFFFF);
            knob.setColor(Color.WHITE);
            knob.setShadowLayer(3f * dp, 0, dp, 0x59000000);
        }

        /** Slides to on or off. */
        void set(boolean on) {
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            a.setDuration(480);
            a.setInterpolator(new android.view.animation.OvershootInterpolator(1.4f));
            a.addUpdateListener(v -> {
                pos = (float) v.getAnimatedValue();
                stretch = (float) Math.sin(Math.PI * Math.min(1f, v.getAnimatedFraction()));
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            lit.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFFF4F5F7, 0xFFC4C9D0,
                    android.graphics.Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            float p = Math.max(0f, Math.min(1f, pos));
            box.set(0, 0, w, h);
            canvas.drawRoundRect(box, r, r, track);
            lit.setAlpha(Math.round(255 * p));
            canvas.drawRoundRect(box, r, r, lit);
            box.inset(edge.getStrokeWidth() / 2f, edge.getStrokeWidth() / 2f);
            canvas.drawRoundRect(box, r, r, edge);
            float inset = 2.2f * dp;
            float d = h - 2 * inset;
            float kw = d * (1f + 0.28f * stretch);
            float x = inset + (w - 2 * inset - kw) * pos;
            box.set(x, inset, x + kw, inset + d);
            canvas.drawRoundRect(box, d / 2f, d / 2f, knob);
        }
    }

    /** The sign as a view: its colours slowly turning; pulse() makes it pop once. */
    static final class Spin extends android.view.View {
        private final ValueAnimator clock = ValueAnimator.ofFloat(0f, 360f);
        private float pulse;
        private float ring = 1f;
        private float play = 1f;
        private float speed = 1f;
        private float angle;
        private long last;

        Spin(Context context) {
            super(context);
            clock.setDuration(1000);
            clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(null);
            clock.addUpdateListener(a -> {
                long now = android.os.SystemClock.uptimeMillis();
                if (last != 0) {
                    angle = (angle + (now - last) * 0.09f * speed) % 360f;   // a turn every four seconds
                }
                last = now;
                invalidate();
            });
        }

        void setRing(float value) {
            ring = value;
            invalidate();
        }

        void setPlay(float value) {
            play = value;
            invalidate();
        }

        void setSpeed(float value) {
            speed = value;
        }

        /** The ring fills like a loading circle, then the play sign pops and it swells once. */
        void load() {
            ring = 0f;
            play = 0f;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(900);
            a.setInterpolator(new android.view.animation.PathInterpolator(0.45f, 0f, 0.2f, 1f));
            a.addUpdateListener(v -> {
                ring = (float) v.getAnimatedValue();
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    ValueAnimator p = ValueAnimator.ofFloat(0f, 1f);
                    p.setDuration(320);
                    p.setInterpolator(new android.view.animation.OvershootInterpolator(2.6f));
                    p.addUpdateListener(v -> {
                        play = (float) v.getAnimatedValue();
                        invalidate();
                    });
                    p.start();
                    pulse();
                }
            });
            a.start();
        }

        /** A quick swell and settle, as when the capsule opens. */
        void pulse() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(650);
            a.addUpdateListener(v -> {
                float t = (float) v.getAnimatedValue();
                pulse = (float) Math.sin(Math.PI * t) * (1f - t * 0.3f);
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            last = 0;
            clock.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            clock.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f * 0.86f * (1f + 0.14f * pulse);
            CinemaMark.draw(canvas, getWidth() / 2f, getHeight() / 2f, r, ring, angle, play * (1f + 0.2f * pulse), 255);
        }
    }
}
