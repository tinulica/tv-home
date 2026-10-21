package ro.tinulica.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

/**
 * Liquid Glass for one view, drawn inside the window so it moves in the same frame as the
 * view. The pane shows the pre-blurred backdrop (wallpaper, banner, or the frosted grid
 * backdrop, cross-faded) through a lens: a vertex grid over the pane whose texture
 * coordinates are pushed outward near the rim along the edge normal (convex squircle profile),
 * so the rim magnifies what lies just beyond it and the middle stays flat.
 */
final class LensDrawable extends Drawable {

    /** What is behind the glass, shared by every pane; set by the activity. */
    static final class Backdrop {
        Bitmap wallFront;      // blurred, screen aspect
        Bitmap wallBack;
        float wallMix = 1f;    // 0 = back, 1 = front
        Bitmap hero;           // blurred composed banner
        float heroAlpha;
        Bitmap frost;          // the grid's frosted wallpaper (dimmed when drawn)
        float frostAlpha;
        int screenW = 1920;
        int screenH = 1080;
    }

    private static final int COLS = 16;
    private static final int ROWS = 8;

    private final View view;
    private final Backdrop backdrop;
    private final float radius;
    private final float strength;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final RectF screen = new RectF();
    private final float[] verts = new float[(COLS + 1) * (ROWS + 1) * 2];
    private final float[] texs = new float[(COLS + 1) * (ROWS + 1) * 2];
    private final short[] indices = new short[COLS * ROWS * 6];
    private final float[] normal = new float[2];
    private final Matrix shaderMatrix = new Matrix();
    private int builtW;
    private int builtH;

    LensDrawable(View view, Backdrop backdrop, float radius, float strength, float density) {
        this.view = view;
        this.backdrop = backdrop;
        this.radius = radius;
        this.strength = strength;
        this.density = density;
        int k = 0;
        for (int i = 0; i < ROWS; i++) {
            for (int j = 0; j < COLS; j++) {
                short a = (short) (i * (COLS + 1) + j);
                short b = (short) (a + 1);
                short c = (short) (a + COLS + 1);
                short d = (short) (c + 1);
                indices[k++] = a;
                indices[k++] = b;
                indices[k++] = c;
                indices[k++] = b;
                indices[k++] = d;
                indices[k++] = c;
            }
        }
    }

    /** The view's bounds after every transform up to the window, in window pixels. */
    private static void windowRect(View v, RectF out) {
        out.set(0, 0, v.getWidth(), v.getHeight());
        View cur = v;
        while (cur != null) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) {
                m.mapRect(out);
            }
            out.offset(cur.getLeft(), cur.getTop());
            ViewParent parent = cur.getParent();
            if (parent instanceof View) {
                View pv = (View) parent;
                out.offset(-pv.getScrollX(), -pv.getScrollY());
                cur = pv;
            } else {
                cur = null;
            }
        }
    }

    private float distance(float x, float y, float w, float h, float r) {
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

    @Override
    public void draw(Canvas canvas) {
        int w = getBounds().width();
        int h = getBounds().height();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Math.min(radius, Math.min(w, h) / 2f);
        if (builtW != w || builtH != h) {
            clip.reset();
            box.set(0, 0, w, h);
            clip.addRoundRect(box, r, r, Path.Direction.CW);
            for (int i = 0; i <= ROWS; i++) {
                for (int j = 0; j <= COLS; j++) {
                    int k = (i * (COLS + 1) + j) * 2;
                    verts[k] = w * j / (float) COLS;
                    verts[k + 1] = h * i / (float) ROWS;
                }
            }
            builtW = w;
            builtH = h;
        }
        // Where the pane is on screen right now (with its parents' scale and translation).
        windowRect(view, screen);
        float sx = screen.width() / w;
        float sy = screen.height() / h;
        float size = Math.min(w, h);
        float bezel = Math.max(6f * density, Math.min(28f * density, size * 0.35f));
        float shift = strength * Math.max(3f * density, Math.min(16f * density, size * 0.12f));
        for (int i = 0; i <= ROWS; i++) {
            for (int j = 0; j <= COLS; j++) {
                int k = (i * (COLS + 1) + j) * 2;
                float lx = verts[k];
                float ly = verts[k + 1];
                float d = distance(lx, ly, w, h, r);
                float t = Math.max(0f, Math.min(1f, d / bezel));
                float q = 1f - t;
                float s = shift * q * q * q;
                // Sample from just outside the pane near the rim: that is the lens.
                texs[k] = screen.left + (lx + normal[0] * s) * sx;
                texs[k + 1] = screen.top + (ly + normal[1] * s) * sy;
            }
        }
        int saved = canvas.save();
        canvas.clipPath(clip);
        Backdrop b = backdrop;
        if (b.wallMix < 1f && b.wallBack != null) {
            layer(canvas, b.wallBack, 1f, 1f);
        }
        if (b.wallFront != null) {
            layer(canvas, b.wallFront, b.wallBack != null ? b.wallMix : 1f, 1f);
        }
        if (b.heroAlpha > 0.01f && b.hero != null) {
            layer(canvas, b.hero, b.heroAlpha, 1f);
        }
        if (b.frostAlpha > 0.01f && b.frost != null) {
            layer(canvas, b.frost, b.frostAlpha, 0.57f);
        }
        canvas.restoreToCount(saved);
    }

    private void layer(Canvas canvas, Bitmap bitmap, float alpha, float brightness) {
        if (bitmap.isRecycled()) {
            return;
        }
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // Texture coordinates are in window pixels; the bitmap is a scaled copy of the screen.
        shaderMatrix.setScale(backdrop.screenW / (float) bitmap.getWidth(), backdrop.screenH / (float) bitmap.getHeight());
        shader.setLocalMatrix(shaderMatrix);
        paint.setShader(shader);
        paint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        if (brightness < 1f) {
            float f = brightness;
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                    f, 0, 0, 0, 0, 0, f, 0, 0, 0, 0, 0, f, 0, 0, 0, 0, 0, 1, 0}));
        } else {
            paint.setColorFilter(null);
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, verts.length, verts, 0, texs, 0, null, 0,
                indices, 0, indices.length, paint);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
