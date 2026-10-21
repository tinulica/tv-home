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
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A notice as a drop of Liquid Glass at the left edge of the screen, moving like water:
 *
 * 1. a thin film of glass appears along the edge, tall and bright-rimmed, like water on a pane;
 * 2. it gathers into a bead that bulges out of the edge;
 * 3. the bead flows out into a capsule, tip first, squashing a little as it stretches, then
 *    wobbles and settles, still joined to the edge by two curved fillets (a drop clinging on).
 *
 * Leaving, the capsule flows back into a bead and spreads out into the thin film, which fades.
 * The glass shows the screen behind, blurred, with a light rim and a soft shadow; over video
 * (which cannot be read) it is dark glass.
 */
final class LiquidDrop extends FrameLayout implements android.view.Choreographer.FrameCallback {

    /** Something seen through the glass (the weather), animated frame by frame. */
    interface Backdrop {
        void step(float dt, float width, float height);

        /** Draws in a box of the capsule's full size whose top-left is (left, top). */
        void draw(Canvas canvas, float left, float top);
    }

    private Backdrop backdrop;
    private long lastFrame;
    private boolean animating;

    private final float dp;
    private final View content;
    private final int fullW;
    private final int fullH;
    private final Spring height;
    private final Spring width;
    private final Spring film;
    private final Path shape = new Path();
    private final RectF box = new RectF();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refract = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix map = new Matrix();
    private final Matrix lens = new Matrix();
    private final int[] where = new int[2];
    private Bitmap behind;
    private int screenW;
    private int screenH;
    private boolean leaving;
    private float corner = Float.MAX_VALUE;
    private Runnable whenGone;

    /** `content` is laid out `w` x `h` inside the capsule. */
    LiquidDrop(Context context, float dp, View content, int w, int h) {
        super(context);
        this.dp = dp;
        this.content = content;
        this.fullW = w;
        this.fullH = h;
        setWillNotDraw(false);
        setClipChildren(true);
        addView(content, new LayoutParams(w, h, Gravity.CENTER_VERTICAL | Gravity.START));
        content.setAlpha(0f);
        shadow.setColor(0x01000000);
        shadow.setShadowLayer(14 * dp, 0, 4 * dp, 0x66000000);
        tint.setColor(0x1AFFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        refract.setStyle(Paint.Style.STROKE);
        spec.setStyle(Paint.Style.STROKE);
        height = new Spring(0f, 0.3f, v -> invalidate());
        width = new Spring(0f, 0.2f, v -> {
            content.setAlpha(Math.max(0f, Math.min(1f, (v / fullW - 0.62f) / 0.3f)));
            invalidate();
        });
        // 1 = the thin film, 0 = solid glass (how bright and how thin the rim is).
        film = new Spring(0f, 0.002f, v -> invalidate());
    }

    void setBackdrop(Backdrop b) {
        backdrop = b;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (backdrop != null) {
            animating = true;
            lastFrame = 0;
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        animating = false;
        android.view.Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long now) {
        if (!animating) {
            return;
        }
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        backdrop.step(dt, fullW, fullH);
        invalidate();
        android.view.Choreographer.getInstance().postFrameCallback(this);
    }

    /** The screen behind (blurred, screen-sized proportions), or null over video. */
    void setBehind(Bitmap blurred, int screenW, int screenH) {
        this.behind = blurred;
        this.screenW = screenW;
        this.screenH = screenH;
        invalidate();
    }

    /** The film, the bead, the capsule. */
    void appear() {
        leaving = false;
        width.snap(2.5f * dp);
        height.snap(fullH * 2.3f);
        film.snap(1f);
        setAlpha(0f);
        animate().alpha(1f).setDuration(140).start();
        postDelayed(() -> {
            // Gathering: the film draws together into a bead.
            height.to(fullH * 1.06f, 0.36f, 0.62f);
            width.to(fullH * 0.44f, 0.36f, 0.58f);
            film.to(0f, 0.4f, 1f);
        }, 160);
        postDelayed(() -> {
            // Flowing out: tip first, a little squashed while it stretches, then a wobble.
            width.to(fullW, 0.56f, 0.6f);
            height.to(fullH * 0.9f, 0.22f, 0.9f);
        }, 420);
        postDelayed(() -> height.to(fullH, 0.5f, 0.5f), 600);
    }

    /** Back into a bead, then the film, which fades. */
    void disappear(Runnable gone) {
        if (leaving) {
            return;
        }
        leaving = true;
        whenGone = gone;
        width.to(fullH * 0.44f, 0.34f, 0.85f);
        height.to(fullH * 1.06f, 0.34f, 0.85f);
        postDelayed(() -> {
            width.to(2.5f * dp, 0.36f, 1f);
            height.to(fullH * 2.2f, 0.4f, 1f);
            film.to(1f, 0.3f, 1f);
            animate().alpha(0f).setStartDelay(180).setDuration(260).withEndAction(() -> {
                if (whenGone != null) {
                    Runnable r = whenGone;
                    whenGone = null;
                    r.run();
                }
            }).start();
        }, 300);
    }

    /** The drop: a capsule (or a bead, or a film) joined to the left edge by two fillets. */
    private void buildShape() {
        shape.reset();
        float h = Math.max(0f, height.value());
        float w = Math.max(0.5f, width.value());
        if (h < 1f) {
            return;
        }
        float cy = getHeight() / 2f;
        float top = cy - h / 2f;
        float bottom = cy + h / 2f;
        // The tip: a half ellipse while the drop is short (a bead), a round end once it is long.
        float rx = Math.min(Math.min(h / 2f, w), corner);
        // The fillets reach along the edge in proportion to how much the drop bulges out.
        float f = Math.min(h * 0.3f, rx * 0.7f);
        shape.moveTo(0, top - f);
        shape.quadTo(0, top, Math.min(f, w - rx), top);
        if (w - rx > f) {
            shape.lineTo(w - rx, top);
        }
        box.set(w - 2 * rx, top, w, bottom);
        shape.arcTo(box, 270f, 180f, false);
        shape.lineTo(Math.min(f, w - rx), bottom);
        shape.quadTo(0, bottom, 0, bottom + f);
        shape.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        buildShape();
        if (shape.isEmpty()) {
            return;
        }
        float k = Math.max(0f, Math.min(1f, film.value()));
        float h = height.value();
        float top = getHeight() / 2f - h / 2f;
        if (k < 0.9f) {
            shadow.setShadowLayer(14 * dp * (1 - k), 0, 4 * dp * (1 - k), 0x66000000);
            canvas.drawPath(shape, shadow);
        }
        if (behind != null && !behind.isRecycled()) {
            getLocationOnScreen(where);
            BitmapShader shader = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            map.setScale(screenW / (float) behind.getWidth(), screenH / (float) behind.getHeight());
            map.postTranslate(-where[0], -where[1]);
            shader.setLocalMatrix(map);
            glass.setShader(shader);
            canvas.drawPath(shape, glass);
            // The thick rim bends the light: just inside the edge the screen behind is seen
            // magnified towards the middle, as through the rounded edge of a lens (two strokes,
            // so the band fades into the flat middle instead of ending in a seam).
            float bulge = (1f - k) * Math.min(1f, width.value() / (fullH * 0.6f));
            if (bulge > 0.05f) {
                BitmapShader bent = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                lens.set(map);
                lens.postScale(1.24f, 1.24f, width.value() * 0.5f, getHeight() / 2f);
                bent.setLocalMatrix(lens);
                refract.setShader(bent);
                int saved = canvas.save();
                canvas.clipPath(shape);
                refract.setStrokeWidth(10 * dp);
                refract.setAlpha(Math.round(130 * bulge));
                canvas.drawPath(shape, refract);
                refract.setStrokeWidth(5 * dp);
                canvas.drawPath(shape, refract);
                canvas.restoreToCount(saved);
            }
        } else {
            glass.setShader(null);
            glass.setColor(0xCC1C1C1E);
            canvas.drawPath(shape, glass);
        }
        if (backdrop != null) {
            // The weather, behind the frosted surface; it comes in as the drop flows out.
            float seen = Math.max(0f, Math.min(1f, (width.value() / fullW - 0.3f) / 0.45f)) * (1f - k);
            if (seen > 0.01f) {
                int saved = seen < 0.99f
                        ? canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * seen))
                        : canvas.save();
                canvas.clipPath(shape);
                backdrop.draw(canvas, 0, getHeight() / 2f - fullH / 2f);
                canvas.restoreToCount(saved);
            }
        }
        canvas.drawPath(shape, tint);
        // Light from above: a sheen over the upper half.
        sheen.setShader(new LinearGradient(0, top, 0, top + h * 0.6f, 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, sheen);
        // The rim: a fine bright line while it is a film, the glass's hairline once solid.
        rim.setStrokeWidth(Math.max(1f, (1.1f + 0.9f * k) * dp));
        int a = Math.round(0x99 + (0xFF - 0x99) * k);
        rim.setShader(new LinearGradient(0, top, 0, top + h,
                new int[]{(a << 24) | 0xFFFFFF, (Math.round(0x33 + 0x99 * k) << 24) | 0xFFFFFF,
                        (Math.round(0x1F + 0x80 * k) << 24) | 0xFFFFFF, (Math.round(0x4D + 0x80 * k) << 24) | 0xFFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, rim);
        // Light catching the glass: bright at the top-left, a fainter glint at the bottom-right.
        if (k < 0.95f) {
            spec.setStrokeWidth(Math.max(1f, 1.5f * dp));
            spec.setShader(new LinearGradient(0, top, Math.max(1f, width.value()), top + h,
                    new int[]{0xD9FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x66FFFFFF},
                    new float[]{0f, 0.28f, 0.74f, 1f}, Shader.TileMode.CLAMP));
            spec.setAlpha(Math.round(255 * (1f - k)));
            canvas.drawPath(shape, spec);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        buildShape();
        if (shape.isEmpty()) {
            return;
        }
        int saved = canvas.save();
        canvas.clipPath(shape);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(saved);
    }

    /** A tall drop (a panel rather than a capsule): its corners no rounder than `r`. */
    void setCorner(float r) {
        corner = r;
    }

    /** The capsule's height once settled. */
    int capsuleHeight() {
        return fullH;
    }

    boolean isLeaving() {
        return leaving;
    }

    /** Tall enough for the film, wide enough for the capsule and its shadow. */
    int viewHeight() {
        return Math.round(fullH * 2.5f + 20 * dp);
    }

    int viewWidth() {
        return fullW + Math.round(24 * dp);
    }
}
