package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;

/**
 * At the top left of the home screen, on glass: Claude's mark and how much of Claude's plan is
 * used, as a bar and a number, with when it resets. Claude writes files/claude/usage.json when it
 * checks (`tvtool usage`); the pill hides when there is nothing recent.
 */
final class ClaudeUsage extends LinearLayout {

    private final float dp;
    private final TextView label;
    private final Bar bar;

    ClaudeUsage(Context context, float dp) {
        super(context);
        this.dp = dp;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(px(11), 0, px(14), 0);
        addView(new Mark(context), new LayoutParams(px(16), px(16)));
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(VERTICAL);
        label = new TextView(context);
        label.setTextColor(Color.WHITE);
        label.setTextSize(10.5f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        label.setShadowLayer(px(3), 0, px(1), 0x66000000);
        words.addView(label);
        bar = new Bar(context);
        LayoutParams blp = new LayoutParams(px(96), px(5));
        blp.topMargin = px(4);
        words.addView(bar, blp);
        LayoutParams wlp = new LayoutParams(-2, -2);
        wlp.leftMargin = px(8);
        addView(words, wlp);
        setVisibility(GONE);
    }

    /** Reads usage.json again; true when there is something to show. */
    boolean refresh() {
        File dir = getContext().getExternalFilesDir("claude");
        File f = dir == null ? null : new File(dir, "usage.json");
        if (f == null || !f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) {
            setVisibility(GONE);
            return false;
        }
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            int used = Math.max(0, Math.min(100, o.optInt("percent", 0)));
            String resets = o.optString("resets", "");
            label.setText("Claude  ·  " + used + "% folosit" + (resets.isEmpty() ? "" : "  ·  " + resets));
            bar.set(used / 100f);
            setVisibility(VISIBLE);
            return true;
        } catch (Exception e) {
            setVisibility(GONE);
            return false;
        }
    }

    /** The bar: a groove of glass, filled in Claude's warm colours (red as it nears the limit). */
    private final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Spring level = new Spring(0f, 0.002f, v -> invalidate());

        Bar(Context context) {
            super(context);
        }

        void set(float v) {
            level.to(v, 0.8f, 0.85f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            r.set(0, 0, getWidth(), h);
            paint.setShader(null);
            paint.setColor(0x40FFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, paint);
            float v = Math.max(0f, Math.min(1f, level.value()));
            if (v > 0.01f) {
                r.set(0, 0, Math.max(h, getWidth() * v), h);
                int end = v > 0.85f ? 0xFFFF6B5E : 0xFFF0A58A;
                paint.setShader(new LinearGradient(0, 0, getWidth(), 0, 0xFFD97757, end, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, h / 2, h / 2, paint);
            }
        }
    }

    /** Claude's mark (the same path as on the message cards). */
    private static final class Mark extends View {
        private static Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix fit = new Matrix();
        private final Path drawn = new Path();

        Mark(Context context) {
            super(context);
            paint.setColor(ClaudeCard.CORAL);
            if (path == null) {
                path = ClaudeCard.svg(ClaudeCard.MARK_PATH);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 100f;
            fit.setScale(s, s);
            path.transform(fit, drawn);
            c.drawPath(drawn, paint);
        }
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
