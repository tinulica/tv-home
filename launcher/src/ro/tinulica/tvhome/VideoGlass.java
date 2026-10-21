package ro.tinulica.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Clear Liquid Glass over live video. Real Liquid Glass bends and blurs what is behind it, but
 * live TV is a protected hardware layer no app may read, so nothing behind can be refracted.
 * This is Apple's "clear" variant instead: an almost transparent pane with a faint dimming
 * layer (so white text reads), a specular sheen across the top, a reflection along the bottom,
 * a bright hairline rim strongest at the top-left and a light inner edge for thickness.
 */
final class VideoGlass extends Drawable {

    private static Bitmap grain;

    private final float radius;
    private final float stroke;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path clip = new Path();
    // Like the dock: a light white tint (over a faint dark veil so white text still reads).
    // Clear Liquid Glass: almost no body, a faint dimming so white text reads over any picture.
    private int top = 0x1FFFFFFF;
    private int bottom = 0x0FFFFFFF;
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int veilColor = 0x2E000000;

    VideoGlass(float radius, float density) {
        this.radius = radius;
        this.stroke = Math.max(1f, density * 0.8f);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);
        depth.setStyle(Paint.Style.STROKE);
        depth.setStrokeWidth(stroke * 2.5f);
        depth.setColor(0x24FFFFFF); // a light inner edge, the thickness of clear glass
        if (grain == null) {
            grain = makeGrain();
        }
        BitmapShader shader = new BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        frost.setShader(shader);
    }

    /** Fine monochrome noise, tiled: the frost. */
    private static Bitmap makeGrain() {
        int n = 96;
        int[] px = new int[n * n];
        java.util.Random r = new java.util.Random(11);
        for (int i = 0; i < px.length; i++) {
            int a = 6 + r.nextInt(16);
            px[i] = (a << 24) | (r.nextInt(3) == 0 ? 0x000000 : 0xFFFFFF);
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    /** A lighter or denser body (a darker panel where text must read, an opaque cover). */
    /** A denser veil where more text sits on the glass (lists, dialogs). */
    void setVeil(int color) {
        veilColor = color;
        invalidateSelf();
    }

    void setBody(int topColor, int bottomColor) {
        top = topColor;
        bottom = bottomColor;
        onBoundsChange(getBounds());
        invalidateSelf();
    }

    /** The outline that gives the view its shadow (with setElevation) and clips it. */
    ViewOutlineProvider outline() {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                outline.setAlpha(0.85f);
            }
        };
    }

    @Override
    protected void onBoundsChange(Rect b) {
        if (b.isEmpty()) {
            return;
        }
        body.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(b.height() * 0.5f, radius * 3f),
                new int[]{0x4DFFFFFF, 0x17FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        reflection.setShader(new LinearGradient(0, b.bottom - Math.min(b.height() * 0.3f, radius * 2f), 0, b.bottom,
                0x00FFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xE6FFFFFF, 0x59FFFFFF, 0x1FFFFFFF, 0x26FFFFFF, 0x80FFFFFF},
                new float[]{0f, 0.22f, 0.5f, 0.8f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float half = stroke / 2f;
        box.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        int saved = canvas.save();
        canvas.clipPath(clip);
        veil.setColor(veilColor);
        canvas.drawRect(box, veil);
        canvas.drawRect(box, body);
        canvas.drawRect(box, sheen);
        canvas.drawRect(box, reflection);
        // Inner shadow: a soft dark line just inside the rim.
        float in = stroke * 2f;
        canvas.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in,
                radius - in, radius - in, depth);
        canvas.restoreToCount(saved);
        canvas.drawRoundRect(box, radius, radius, rim);
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
