package ro.tinulica.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A pane of Liquid Glass drawn the way Apple describes it: the backdrop behind the pane
 * is shown through a lens whose bezel bends the picture near the rim and leaves the
 * middle flat, a thin specular rim catches the light from the top-left, and a light
 * tint plus a soft shadow along the bottom give the sheet thickness.
 *
 * The lens is a mesh warp (Canvas.drawBitmapMesh) of a small pre-blurred copy of the
 * wallpaper: the whole copy is mapped onto a screen-sized grid of quads, translated so
 * the pane's region lands under the view, and the grid points near the pane's rim are
 * pulled inward. The displacement follows a convex squircle profile (largest at the
 * edge, zero at the inner end of the bezel; see kube.io's analysis of the material), so
 * the rim looks like thick glass magnifying what lies just beyond it. No per-frame
 * bitmap work: moving the pane only rebuilds about a thousand vertices.
 */
public class LiquidGlassView extends View {

    private final float density;
    private final float radius;
    private final int screenW;
    private final int screenH;
    private final int cols;
    private final int rows;
    private float bezel;        // width of the refracting band, px
    private float margin;       // how far outside the pane the lens still pulls, px
    private float maxShift;     // displacement at the rim, px
    private int tint;
    private int dim;

    private Bitmap front;
    private Bitmap back;
    private float mix = 1f;
    private ValueAnimator fade;

    private final int[] location = new int[2];
    private int originX = Integer.MIN_VALUE;
    private int originY = Integer.MIN_VALUE;
    private int meshW;
    private int meshH;
    private int meshOriginX = Integer.MIN_VALUE;
    private int meshOriginY = Integer.MIN_VALUE;

    private float[] verts;
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final float[] normal = new float[2];
    private final Paint mesh = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LiquidGlassView(Context context, float radius, float density, int screenW, int screenH,
                           int tint, int dim) {
        super(context);
        this.radius = radius;
        this.density = density;
        this.screenW = screenW;
        this.screenH = screenH;
        this.tint = tint;
        this.dim = dim;
        bezel = 30 * density;
        margin = 22 * density;
        maxShift = 16 * density;
        // Cells about half the bezel wide, so the lens profile has a few steps across the band.
        cols = Math.max(24, Math.round(screenW / (bezel * 0.5f)));
        rows = Math.max(14, Math.round(screenH / (bezel * 0.5f)));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, density * 0.9f));
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(Math.max(1f, density * 0.75f));
        fill.setColor(tint);
        setWillNotDraw(false);
        // The lens is a few thousand vertices: render it to a texture once and reuse it every
        // frame; it is redrawn only when the backdrop or the pane's position changes.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Lens strength: 0 flat, 1 default, 2 very thick glass. */
    public void setStrength(float strength) {
        maxShift = Math.min(margin - density, 16 * density * strength);
        meshOriginX = Integer.MIN_VALUE;
        invalidate();
    }

    /** Dims the specular rim and inner highlight (1 = full). */
    public void setRimAlpha(float alpha) {
        rim.setAlpha(Math.round(255 * alpha));
        inner.setAlpha(Math.round(255 * alpha));
        invalidate();
    }

    public void setTint(int tint) {
        this.tint = tint;
        fill.setColor(tint);
        invalidate();
    }

    /** New backdrop (already blurred, screen aspect). Cross-fades like the wallpaper does. */
    public void setBackdrop(Bitmap next, long fadeMs) {
        if (next == front) {
            return;
        }
        if (fade != null) {
            fade.cancel();
            fade = null;
        }
        if (front != null && fadeMs > 0) {
            back = front;
            front = next;
            mix = 0f;
            fade = ValueAnimator.ofFloat(0f, 1f);
            fade.setDuration(fadeMs);
            fade.setInterpolator(new LinearInterpolator());
            fade.addUpdateListener(a -> {
                mix = (float) a.getAnimatedValue();
                invalidate();
            });
            fade.start();
        } else {
            front = next;
            back = null;
            mix = 1f;
        }
        invalidate();
    }

    /** Call once per frame from an OnPreDrawListener: samples the backdrop where the pane now is. */
    public void sync() {
        getLocationOnScreen(location);
        if (location[0] != originX || location[1] != originY) {
            originX = location[0];
            originY = location[1];
            invalidate();
        }
    }

    /** Takes no space of its own: sized by its siblings (match_parent inside a wrap_content panel). */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec) : 0;
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clip.reset();
        box.set(0, 0, w, h);
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        // Light from the top-left (Apple's default specular angle is about -60 degrees).
        rim.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xC8FFFFFF, 0x4DFFFFFF, 0x24FFFFFF, 0x24FFFFFF, 0x73FFFFFF},
                new float[]{0f, 0.2f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        inner.setShader(new LinearGradient(0, 0, 0, Math.min(h, radius * 2.2f),
                0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        depth.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                0x00000000, dim, Shader.TileMode.CLAMP));
        originX = Integer.MIN_VALUE;
        meshOriginX = Integer.MIN_VALUE;
    }

    /** Signed distance (positive inside) and outward normal of the rounded rectangle at (x, y). */
    private float distance(float x, float y, float w, float h) {
        float r = Math.min(radius, Math.min(w, h) / 2f);
        float px = x - w / 2f;
        float py = y - h / 2f;
        float qx = Math.abs(px) - (w / 2f - r);
        float qy = Math.abs(py) - (h / 2f - r);
        float outside;
        if (qx > 0 && qy > 0) {
            float len = (float) Math.sqrt(qx * qx + qy * qy);
            outside = len - r;
            normal[0] = Math.signum(px) * qx / len;
            normal[1] = Math.signum(py) * qy / len;
        } else if (qx > qy) {
            outside = qx - r;
            normal[0] = Math.signum(px);
            normal[1] = 0;
        } else {
            outside = qy - r;
            normal[0] = 0;
            normal[1] = Math.signum(py);
        }
        return -outside;
    }

    /** How far a sample is pulled inward, by its signed distance to the rim. */
    private float shift(float d) {
        if (d <= -margin) {
            return 0f;
        }
        if (d < 0) {
            return maxShift * (1f + d / margin);
        }
        if (d < bezel) {
            float t = d / bezel;
            float k = 1f - t;
            return maxShift * k * k * k;
        }
        return 0f;
    }

    /** The whole backdrop as a screen-sized grid in view coordinates, warped near this pane's rim. */
    private void rebuildMesh(int w, int h) {
        if (verts == null || meshW != w || meshH != h) {
            verts = new float[(cols + 1) * (rows + 1) * 2];
            meshW = w;
            meshH = h;
        }
        int k = 0;
        for (int i = 0; i <= rows; i++) {
            float sy = (float) screenH * i / rows - originY;
            for (int j = 0; j <= cols; j++) {
                float sx = (float) screenW * j / cols - originX;
                float s = 0f;
                // Only points within reach of the rim move; the rest stays a plain texture.
                if (sx > -margin - bezel && sx < w + margin + bezel
                        && sy > -margin - bezel && sy < h + margin + bezel) {
                    s = shift(distance(sx, sy, w, h));
                }
                verts[k++] = s == 0f ? sx : sx - normal[0] * s;
                verts[k++] = s == 0f ? sy : sy - normal[1] * s;
            }
        }
        meshOriginX = originX;
        meshOriginY = originY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (originX == Integer.MIN_VALUE) {
            getLocationOnScreen(location);
            originX = location[0];
            originY = location[1];
        }
        if (meshOriginX != originX || meshOriginY != originY || meshW != w || meshH != h) {
            rebuildMesh(w, h);
        }
        int saved = canvas.save();
        canvas.clipPath(clip);
        if (back != null && mix < 1f && !back.isRecycled()) {
            mesh.setAlpha(255);
            canvas.drawBitmapMesh(back, cols, rows, verts, 0, null, 0, mesh);
        }
        if (front != null && !front.isRecycled()) {
            mesh.setAlpha(back != null && mix < 1f ? Math.round(255 * mix) : 255);
            canvas.drawBitmapMesh(front, cols, rows, verts, 0, null, 0, mesh);
        } else {
            canvas.drawColor(0x66202428);
        }
        canvas.drawRect(0, 0, w, h, fill);
        canvas.drawRect(0, 0, w, h, depth);
        float inset = rim.getStrokeWidth() * 1.6f;
        box.set(inset, inset, w - inset, h - inset);
        canvas.drawRoundRect(box, Math.max(0, radius - inset), Math.max(0, radius - inset), inner);
        canvas.restoreToCount(saved);
        float half = rim.getStrokeWidth() / 2f;
        box.set(half, half, w - half, h - half);
        canvas.drawRoundRect(box, radius, radius, rim);
    }
}
