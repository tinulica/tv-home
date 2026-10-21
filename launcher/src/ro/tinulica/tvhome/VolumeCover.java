package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.widget.FrameLayout;

/**
 * TV Home's volume, always the same: a Liquid Glass pill drawn exactly over the spot where
 * TCL's system draws its own volume bar (which no app can switch off), with our capsule and
 * the number inside. Over the home screen the pill is frosted from the blurred wallpaper, over
 * video (which cannot be read) it is dark glass; both are opaque so TCL's bar never shows.
 */
final class VolumeCover extends FrameLayout {

    private final float dp;
    private final VolumeBar bar;
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Matrix map = new Matrix();
    private final int[] where = new int[2];
    private Bitmap behind;

    VolumeCover(Context context, float dp) {
        super(context);
        this.dp = dp;
        setWillNotDraw(false);
        tint.setColor(0x38FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 0.9f * dp));
        bar = new VolumeBar(context, dp);
        bar.setPlain(true);
        addView(bar, new LayoutParams(Math.round(40 * dp), Math.round(172 * dp), Gravity.CENTER));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    VolumeBar bar() {
        return bar;
    }

    /** The blurred wallpaper (home screen), or null over video. */
    void setPatch(Bitmap ignoredSharp, Bitmap blurred) {
        behind = blurred;
        invalidate();
    }

    void setBare(boolean ignored) {
    }

    void setIdle(boolean value) {
        bar.setVisibility(value ? INVISIBLE : VISIBLE);
    }

    boolean hasPatch() {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // TCL's box sits 10..98 x 30..430 (1080p px) inside this view; the pill reaches past
        // it so even its rounded corners cover TCL's square ones.
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        pill.set(4 * k, 4 * k, 104 * k, 456 * k);
        float r = 30 * k;
        if (behind != null && !behind.isRecycled()) {
            getLocationOnScreen(where);
            BitmapShader shader = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            map.setScale(getResources().getDisplayMetrics().widthPixels / (float) behind.getWidth(),
                    getResources().getDisplayMetrics().heightPixels / (float) behind.getHeight());
            map.postTranslate(-where[0], -where[1]);
            shader.setLocalMatrix(map);
            frost.setShader(shader);
            canvas.drawRoundRect(pill, r, r, frost);
            canvas.drawRoundRect(pill, r, r, tint);
        } else {
            dark.setShader(new LinearGradient(0, pill.top, 0, pill.bottom, 0xFF3A3A42, 0xFF1C1C21, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(pill, r, r, dark);
        }
        sheen.setShader(new LinearGradient(0, pill.top, 0, pill.top + pill.height() * 0.4f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(pill, r, r, sheen);
        rim.setShader(new LinearGradient(0, pill.top, 0, pill.bottom,
                new int[]{0xD9FFFFFF, 0x33FFFFFF, 0x26FFFFFF, 0x66FFFFFF}, new float[]{0f, 0.3f, 0.75f, 1f},
                Shader.TileMode.CLAMP));
        float half = rim.getStrokeWidth() / 2f;
        pill.inset(half, half);
        canvas.drawRoundRect(pill, r - half, r - half, rim);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int right, int bottom) {
        super.onLayout(changed, l, t, right, bottom);
        // Centre the capsule in the pill, not in the (wider) view.
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        int cx = Math.round(54 * k);
        int cy = Math.round(222 * k);
        int bw = bar.getMeasuredWidth();
        int bh = bar.getMeasuredHeight();
        bar.layout(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
    }
}
