package ro.tinulica.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The surface of a pane of glass, drawn over a blurred copy of the wallpaper:
 * a tint (the dimming layer Apple recommends over bright photos), a hairline
 * specular rim that is brightest at the top-left where the light comes from,
 * and a faint inner highlight along the top edge. Both lines are deliberately
 * thin: a thick bright border reads as a plastic frame, not as glass.
 */
public class GlassDrawable extends Drawable {

    private final float radius;
    private final float stroke;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    public GlassDrawable(float radius, float density, int tint) {
        this.radius = radius;
        this.stroke = Math.max(1f, density * 0.75f);
        fill.setColor(tint);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(stroke);
    }

    public void setTint(int tint) {
        fill.setColor(tint);
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rim.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[]{0x8CFFFFFF, 0x30FFFFFF, 0x14FFFFFF, 0x14FFFFFF, 0x40FFFFFF},
                new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        inner.setShader(new LinearGradient(0, bounds.top, 0, bounds.top + Math.min(bounds.height(), radius * 1.6f),
                0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float half = stroke / 2f;
        box.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        canvas.drawRoundRect(box, radius, radius, fill);
        box.inset(stroke * 1.25f, stroke * 1.25f);
        float innerRadius = Math.max(0f, radius - stroke * 1.25f);
        canvas.drawRoundRect(box, innerRadius, innerRadius, inner);
        box.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        canvas.drawRoundRect(box, radius, radius, rim);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        rim.setAlpha(alpha);
        inner.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
