package ro.tinulica.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The soft black shadow under a focused app in the grid, like tvOS: wide, a little below the
 * card, densest under its middle, no colour. One pre-blurred bitmap drawn stretched under the
 * tile's current (scaled) bounds; its opacity follows how lifted the tile is. Cheap: one
 * bitmap draw per frame, no shadow rendering by the system.
 */
final class FocusShadow extends Drawable {

    private static Bitmap shared;
    private final View tile;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private float amount;

    FocusShadow(View tile) {
        this.tile = tile;
        if (shared == null) {
            shared = render();
        }
    }

    /** 0 = resting, 1 = fully focused. */
    void setAmount(float value) {
        float v = Math.max(0f, Math.min(1f, value));
        if (v != amount) {
            amount = v;
            invalidateSelf();
        }
    }

    /** A 128x80 black rounded rectangle blurred into a soft pool, with transparent margins. */
    private static Bitmap render() {
        int w = 128;
        int h = 80;
        int[] px = new int[w * h];
        // Rounded rect of 64x40 centred, then a separable blur (radius 14) done three times.
        float[] a = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = Math.max(0, Math.abs(x - w / 2f + 0.5f) - 28);
                float dy = Math.max(0, Math.abs(y - h / 2f + 0.5f) - 16);
                a[y * w + x] = Math.sqrt(dx * dx + dy * dy) <= 4 ? 1f : 0f;
            }
        }
        float[] t = new float[w * h];
        for (int pass = 0; pass < 3; pass++) {
            blur(a, t, w, h, 9, true);
            blur(t, a, w, h, 9, false);
        }
        for (int i = 0; i < px.length; i++) {
            int alpha = Math.round(Math.min(1f, a[i]) * 255);
            px[i] = alpha << 24;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void blur(float[] src, float[] dst, int w, int h, int r, boolean horizontal) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = horizontal ? x + k : x;
                    int yy = horizontal ? y : y + k;
                    if (xx >= 0 && xx < w && yy >= 0 && yy < h) {
                        sum += src[yy * w + xx];
                    }
                    n++;
                }
                dst[y * w + x] = sum / n;
            }
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (amount <= 0.01f || tile.getWidth() == 0) {
            return;
        }
        // The tile's bounds inside this drawable's view (its parent cell), with its current scale.
        float cx = tile.getLeft() + tile.getWidth() / 2f + tile.getTranslationX();
        float cy = tile.getTop() + tile.getHeight() / 2f + tile.getTranslationY();
        float tw = tile.getWidth() * tile.getScaleX();
        float th = tile.getHeight() * tile.getScaleY();
        // tvOS: the lifted icon casts its shadow downwards only. The dark core (half the bitmap)
        // is a little narrower than the icon and hidden behind its lower half; what shows is a
        // dense band under the bottom edge fading downwards, barely anything at the sides.
        float coreW = tw * 0.80f;
        float coreH = th * 0.62f;
        float coreBottom = cy + th / 2f + th * 0.16f * amount;
        float coreCy = coreBottom - coreH / 2f;
        dst.set(cx - coreW, coreCy - coreH, cx + coreW, coreCy + coreH);
        paint.setAlpha(Math.round(235 * amount));
        canvas.drawBitmap(shared, null, dst, paint);
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
