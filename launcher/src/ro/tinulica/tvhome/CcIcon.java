package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The living symbols of Control Center's switches, which turn from one state into the other
 * the way SF Symbols' "replace" does, rather than swapping:
 *
 * - MOON (Do Not Disturb). Switching on, the outlined moon shrinks and drops away, Apple's
 *   Focus indigo fills the disc from the middle with a ring of light running out to its edge,
 *   the filled moon springs up out of the middle (turning upright, a little past full size),
 *   then two stars pop out one after the other and keep twinkling. Switching off plays it
 *   backwards: the stars go, the moon shrinks, the colour drains, the outline springs back.
 * - TIMER (the sleep timer). On, the disc turns amber the same way, the clock hands fade and
 *   the arc of the time still to run sweeps round; off, the arc runs back and the hands return.
 *
 * On a focused (white) tile the symbol turns dark while it is off, as the tile's other symbols do.
 */
final class CcIcon extends View {

    static final int MOON = 0;
    static final int TIMER = 1;

    private static final int INDIGO = 0xFF5E5CE6;
    private static final int AMBER = 0xFFFF9F0A;

    private final int kind;
    private final Spring dark;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path moon = new Path();
    private final Path bite = new Path();
    private final Path star = new Path();
    private final RectF box = new RectF();
    private boolean active;
    private long changedAt;
    private long timerAt;
    private long timerLength;

    CcIcon(Context context, float dp, int kind, boolean active) {
        super(context);
        this.kind = kind;
        this.active = active;
        dark = new Spring(0f, 0.004f, v -> invalidate());
    }

    /** Switched on or off: the symbol turns into the other one. */
    void set(boolean on) {
        if (on == active) {
            return;
        }
        active = on;
        changedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The tile has focus (it is white): the symbol goes dark while it is off. */
    void setFocusedLook(boolean focused) {
        dark.to(focused ? 1f : 0f, 0.2f, 1f);
    }

    /** TIMER: when the TV switches off and how long the timer was set for (0 = not set). */
    void setTimer(long at, long length) {
        timerAt = at;
        timerLength = length;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) {
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float e = changedAt == 0 ? 10f : (SystemClock.uptimeMillis() - changedAt) / 1000f;
        float d = clamp(dark.value());

        // Where each part of the change is (1 = fully the "on" look).
        float disc;
        float ring;
        float offSym;
        float onSym;
        float star1;
        float star2;
        if (active) {
            offSym = 1f - ease(e / 0.16f);
            disc = Math.min(1.06f, springy(e - 0.04f));
            ring = (e - 0.06f) / 0.55f;
            onSym = springy(e - 0.12f);
            star1 = springy(e - 0.3f);
            star2 = springy(e - 0.42f);
        } else {
            star1 = 1f - ease(e / 0.12f);
            star2 = star1;
            onSym = 1f - ease(e / 0.2f);
            disc = 1f - ease((e - 0.04f) / 0.28f);
            ring = -1f;
            offSym = springy(e - 0.14f);
        }
        int colour = kind == MOON ? INDIGO : AMBER;
        int ink = mix(0xFFFFFFFF, 0xFF1C1C1E, d);

        // The disc: faint glass, and the colour filling it from the middle.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mix(0x2EFFFFFF, 0x1F000000, d));
        canvas.drawCircle(cx, cy, s / 2f, paint);
        if (disc > 0.002f) {
            paint.setColor(colour);
            canvas.drawCircle(cx, cy, Math.min(s / 2f, s / 2f * disc), paint);
        }
        // A ring of light running out from the middle to the edge as the colour comes.
        if (ring > 0f && ring < 1f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.07f * (1f - ring) + 0.5f);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(150 * (1f - ring)));
            canvas.drawCircle(cx, cy, s / 2f * (0.3f + 0.66f * ease(ring)), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if (kind == MOON) {
            drawMoons(canvas, cx, cy, s, ink, offSym, onSym, star1, star2, e);
        } else {
            drawClock(canvas, cx, cy, s, ink, offSym, onSym);
        }
        if (e < 1.4f || (active && kind == MOON)) {
            postInvalidateOnAnimation(); // the change playing, then the stars twinkling
        } else if (active) {
            removeCallbacks(tick);
            postDelayed(tick, 1000); // the arc running down
        }
    }

    private final Runnable tick = this::invalidate;

    private void drawMoons(Canvas canvas, float cx, float cy, float s, int ink, float offSym, float onSym,
                           float star1, float star2, float e) {
        // The outline, while off: it shrinks and drops away as the colour comes.
        if (offSym > 0.01f) {
            float k = Math.min(1.1f, offSym);
            canvas.save();
            canvas.translate(0, (1f - clamp(offSym)) * s * 0.1f);
            canvas.scale(0.45f + 0.55f * k, 0.45f + 0.55f * k, cx, cy);
            moonPath(cx, cy, s);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(s * 0.065f);
            paint.setColor(ink);
            paint.setAlpha(Math.round(255 * clamp(offSym * 1.4f)));
            canvas.drawPath(moon, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }
        // The filled moon, while on: it springs up from the middle, turning upright.
        if (onSym > 0.01f) {
            canvas.save();
            canvas.rotate(-35f * (1f - onSym), cx, cy);
            float k = Math.max(0f, Math.min(1.15f, onSym));
            canvas.scale(k, k, cx, cy);
            moonPath(cx, cy, s);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(255 * clamp(onSym * 1.5f)));
            canvas.drawPath(moon, paint);
            canvas.restore();
        }
        float twinkle1 = e > 0.9f ? 0.65f + 0.35f * (float) Math.sin(e * 2.3f) : 1f;
        float twinkle2 = e > 0.9f ? 0.6f + 0.4f * (float) Math.sin(e * 1.7f + 2f) : 1f;
        sparkle(canvas, cx + s * 0.21f, cy - s * 0.21f, s * 0.09f * Math.max(0f, star1), clamp(star1) * twinkle1);
        sparkle(canvas, cx + s * 0.28f, cy + s * 0.03f, s * 0.06f * Math.max(0f, star2), clamp(star2) * twinkle2);
    }

    private void moonPath(float cx, float cy, float s) {
        moon.reset();
        moon.addCircle(cx - s * 0.03f, cy + s * 0.02f, s * 0.25f, Path.Direction.CW);
        bite.reset();
        bite.addCircle(cx + s * 0.1f, cy - s * 0.1f, s * 0.21f, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
    }

    private void drawClock(Canvas canvas, float cx, float cy, float s, int ink, float offSym, float onSym) {
        float r = s * 0.28f;
        float y = cy + s * 0.02f;
        // The ring and the crown, white once on, ink while off.
        int line = mix(ink, 0xFFFFFFFF, clamp(onSym));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(s * 0.075f);
        paint.setColor(line);
        paint.setAlpha(Math.round(255 * (1f - 0.6f * clamp(onSym))));
        canvas.drawCircle(cx, y, r, paint);
        paint.setAlpha(255);
        canvas.drawLine(cx, y - r - s * 0.07f, cx, y - r - s * 0.02f, paint);
        // The hands, while off.
        if (offSym > 0.01f) {
            paint.setStrokeWidth(s * 0.065f);
            paint.setColor(ink);
            paint.setAlpha(Math.round(255 * clamp(offSym)));
            float k = clamp(offSym);
            canvas.drawLine(cx, y, cx, y - r * 0.55f * k, paint);
            canvas.drawLine(cx, y, cx + r * 0.45f * k, y, paint);
        }
        // The time still to run, sweeping round when it starts.
        float left = 0f;
        if (timerAt > 0 && timerLength > 0) {
            left = clamp((timerAt - System.currentTimeMillis()) / (float) timerLength);
        }
        if (onSym > 0.01f && left > 0f) {
            box.set(cx - r, y - r, cx + r, y + r);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(255);
            paint.setStrokeWidth(s * 0.075f);
            canvas.drawArc(box, -90f, 360f * left * Math.max(0f, Math.min(1f, onSym)), false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** A four-pointed star of the given radius and opacity. */
    private void sparkle(Canvas canvas, float x, float y, float r, float alpha) {
        if (r <= 0.3f || alpha <= 0.01f) {
            return;
        }
        star.reset();
        star.moveTo(x, y - r);
        star.quadTo(x, y, x + r, y);
        star.quadTo(x, y, x, y + r);
        star.quadTo(x, y, x - r, y);
        star.quadTo(x, y, x, y - r);
        star.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255 * clamp(alpha)));
        canvas.drawPath(star, paint);
    }

    /** A spring's step response: 0 before it starts, past 1 a little, then settling on 1. */
    private static float springy(float x) {
        if (x <= 0f) {
            return 0f;
        }
        return (float) (1.0 - Math.exp(-6.5 * x) * Math.cos(10.5 * x));
    }

    private static float ease(float x) {
        x = clamp(x);
        return x * x * (3f - 2f * x);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Blends two ARGB colours, `f` of the way from a to b. */
    private static int mix(int a, int b, float f) {
        f = clamp(f);
        int aa = a >>> 24;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int ba = b >>> 24;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
