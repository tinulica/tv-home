package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * The volume, Apple TV style: a thin Liquid Glass capsule with a white level rising from the
 * bottom and the number under it. Over the home screen the capsule shows the blurred screen
 * behind it (like the dock); over video, smoked glass. Everything moves on springs: the level
 * and the number glide between steps, each step makes the capsule bulge a little (Liquid Glass
 * controls swell when touched) and settle, and pushing past the top or bottom stretches it.
 */
final class VolumeBar extends View {

    private final float dp;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF capsule = new RectF();
    private final RectF inner = new RectF();
    private final Path clip = new Path();
    private final Matrix map = new Matrix();
    private final int[] where = new int[2];
    private final Spring value;
    private final Spring bulge;
    private final Spring stretch;
    private Bitmap behind;
    private boolean muted;
    private int max = 100;
    private boolean fresh = true;
    private boolean plain;

    /** Only the capsule and the number (the pill around it is drawn by its parent). */
    void setPlain(boolean value) {
        plain = value;
        invalidate();
    }

    VolumeBar(Context context, float dp) {
        super(context);
        this.dp = dp;
        tint.setColor(0x33FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 0.9f * dp));
        fill.setColor(0xF5FFFFFF);
        number.setColor(0xFFFFFFFF);
        number.setTextAlign(Paint.Align.CENTER);
        number.setTextSize(12.5f * dp);
        number.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        number.setShadowLayer(3 * dp, 0, dp, 0x80000000);
        value = new Spring(0f, 0.0005f, v -> invalidate());
        value.configure(0.24f, 0.9f);
        bulge = new Spring(0f, 0.001f, v -> invalidate());
        bulge.configure(0.30f, 0.6f);
        stretch = new Spring(0f, 0.001f, v -> invalidate());
        stretch.configure(0.30f, 0.5f);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** New level (0..1) of {@code steps}; {@code limit} when the key pushed past the end. */
    void set(float target, int steps, boolean isMuted, int limit) {
        muted = isMuted;
        max = Math.max(1, steps);
        if (fresh) {
            value.snap(target);
            fresh = false;
        } else {
            value.to(target);
        }
        bulge.kick(6f);
        bulge.to(0f);
        if (limit != 0) {
            stretch.kick(limit > 0 ? 1.2f : -1.2f);
            stretch.to(0f);
        }
        invalidate();
    }

    void hidden() {
        fresh = true;
    }

    /** The blurred screen behind (as under the dock), or null over video. */
    void setBehind(Bitmap bitmap) {
        behind = bitmap;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        float baseW = 14 * dp;
        float baseH = 128 * dp;
        float b = Math.max(0f, bulge.value());
        float s = stretch.value();
        float cw = baseW * (1f + 0.45f * b);
        float ch = baseH * (1f + Math.abs(s) * 0.08f);
        float cx = w / 2f;
        float top = 6 * dp - (s > 0 ? baseH * s * 0.08f : 0f);
        capsule.set(cx - cw / 2f, top, cx + cw / 2f, top + ch);
        float r = cw / 2f;

        if (plain) {
            // no glass of its own
        } else if (behind != null) {
            getLocationOnScreen(where);
            BitmapShader shader = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            map.setScale(getResources().getDisplayMetrics().widthPixels / (float) behind.getWidth(),
                    getResources().getDisplayMetrics().heightPixels / (float) behind.getHeight());
            map.postTranslate(-where[0], -where[1]);
            shader.setLocalMatrix(map);
            frost.setShader(shader);
            canvas.drawRoundRect(capsule, r, r, frost);
            canvas.drawRoundRect(capsule, r, r, tint);
        } else {
            body.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom, 0x73464650, 0xA6202026, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(capsule, r, r, body);
        }
        if (plain) {
            body.setShader(null);
            body.setColor(0x40000000);
            canvas.drawRoundRect(capsule, r, r, body);
        }
        sheen.setShader(new LinearGradient(0, capsule.top, 0, capsule.top + ch * 0.5f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(capsule, r, r, sheen);

        // The level, inside a slightly smaller capsule.
        float inset = 2.5f * dp;
        inner.set(capsule.left + inset, capsule.top + inset, capsule.right - inset, capsule.bottom - inset);
        float ir = inner.width() / 2f;
        clip.reset();
        clip.addRoundRect(inner, ir, ir, Path.Direction.CW);
        int saved = canvas.save();
        canvas.clipPath(clip);
        float v = Math.max(0f, Math.min(1f, value.value()));
        fill.setAlpha(muted ? 0x4D : 0xF5);
        canvas.drawRect(inner.left, inner.bottom - inner.height() * v, inner.right, inner.bottom, fill);
        canvas.restoreToCount(saved);

        rim.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom,
                new int[]{0xE6FFFFFF, 0x33FFFFFF, 0x26FFFFFF, 0x73FFFFFF}, new float[]{0f, 0.3f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        float half = rim.getStrokeWidth() / 2f;
        capsule.inset(half, half);
        canvas.drawRoundRect(capsule, r - half, r - half, rim);

        // The number, gliding with the level.
        String text = muted ? "—" : String.valueOf(Math.round(v * max));
        canvas.drawText(text, cx, top + ch + 22 * dp, number);
        if (bulge.isRunning() || stretch.isRunning() || value.isRunning()) {
            postInvalidateOnAnimation();
        }
    }
}
