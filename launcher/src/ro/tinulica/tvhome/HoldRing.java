package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The "keep holding" indicator for Back: a ring in the bottom-left corner that fills while the
 * key is down. Under it the screen is frosted (the blurred capture, when there is one) and
 * darkened, both fading out from the corner to a little past the ring, so it reads over
 * anything without a hard edge.
 */
final class HoldRing extends View {

    private final float dp;
    private final Bitmap behind;
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private float appear;
    private float popped;
    private long readyAt;

    HoldRing(Context context, float dp, Bitmap behind) {
        super(context);
        this.dp = dp;
        this.behind = behind;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(2.5f * dp);
        track.setColor(0x2EFFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(2.5f * dp);
        arc.setStrokeCap(Paint.Cap.ROUND);
        head.setColor(0xFFFFFFFF);
        head.setShadowLayer(5f * dp, 0, 0, 0xCCFFFFFF);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** 0..1: how long Back has been held, relative to the hold that opens the switcher. */
    void setProgress(float value) {
        float t = Math.max(0f, Math.min(1f, value));
        // Ease in and out: a gentle start, a steady middle, a soft landing at full.
        progress = t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
        appear = Math.min(1f, t * 5f);
        invalidate();
    }

    /** Full and still held: breathe, so it is clear the key can be let go. */
    void setReady() {
        progress = 1f;
        appear = 1f;
        readyAt = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    /** Full: a short pulse outwards while it fades, then {@code done}. */
    void pop(final Runnable done) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(260);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        a.addUpdateListener(v -> {
            popped = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                done.run();
            }
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float size = 26f * dp;
        float cx = 64f * dp;
        float cy = h - 64f * dp;
        float reach = 150f * dp; // from the corner to a little past the ring
        int alpha = Math.round(255 * appear * (1f - popped));

        // Frosted corner: the blurred screen, faded out radially from the corner.
        if (behind != null) {
            int saved = canvas.saveLayer(0, h - reach * 1.4f, reach * 1.4f, h, null);
            BitmapShader shader = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(w / (float) behind.getWidth(), h / (float) behind.getHeight());
            shader.setLocalMatrix(m);
            frost.setShader(shader);
            frost.setAlpha(alpha);
            canvas.drawRect(0, h - reach * 1.4f, reach * 1.4f, h, frost);
            mask.setShader(new RadialGradient(0, h, reach * 1.35f,
                    new int[]{0xFF000000, 0xFF000000, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, h - reach * 1.4f, reach * 1.4f, h, mask);
            canvas.restoreToCount(saved);
        }
        // A soft dark pool so the white ring reads over bright pictures too.
        shade.setShader(new RadialGradient(0, h, reach * 1.35f,
                new int[]{0x8C000000, 0x59000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        shade.setAlpha(alpha);
        canvas.drawRect(0, h - reach * 1.4f, reach * 1.4f, h, shade);

        // The ring: grows in as it appears, breathes while full and held, pulses out when released.
        float breathe = 0f;
        if (readyAt > 0 && popped == 0f) {
            double t = (android.os.SystemClock.uptimeMillis() - readyAt) / 900.0 * Math.PI * 2;
            breathe = (float) (0.5 - 0.5 * Math.cos(t)); // 0..1, starting at rest
            postInvalidateOnAnimation();
        }
        float r = size / 2f * (0.85f + 0.15f * appear) * (1f + 0.12f * breathe) * (1f + 0.35f * popped);
        ring.set(cx - r, cy - r, cx + r, cy + r);
        track.setAlpha(Math.round(0x2E * appear * (1f - popped)));
        canvas.drawOval(ring, track);
        if (progress > 0.002f) {
            // A tail that brightens towards the moving head, like a loader.
            float sweep = 360f * progress;
            android.graphics.SweepGradient tail = new android.graphics.SweepGradient(cx, cy,
                    new int[]{0x26FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF}, new float[]{0f, progress, 1f});
            Matrix rot = new Matrix();
            rot.setRotate(-90f, cx, cy);
            tail.setLocalMatrix(rot);
            arc.setShader(tail);
            arc.setAlpha(alpha);
            canvas.drawArc(ring, -90f, sweep, false, arc);
            if (readyAt > 0) {
                // A soft glow around the whole ring that swells with each breath.
                arc.setShader(null);
                arc.setColor(0xFFFFFFFF);
                arc.setShadowLayer((4f + 8f * breathe) * dp, 0, 0, 0xCCFFFFFF);
                arc.setAlpha(alpha);
                canvas.drawOval(ring, arc);
                arc.setShadowLayer(0, 0, 0, 0);
            } else {
                double angle = Math.toRadians(sweep - 90f);
                head.setAlpha(alpha);
                canvas.drawCircle(cx + (float) Math.cos(angle) * r, cy + (float) Math.sin(angle) * r, 2.6f * dp, head);
            }
        }
    }
}
