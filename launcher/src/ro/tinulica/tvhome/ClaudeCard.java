package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A message from Claude on the TV: a larger pane of glass than the notices, its edge an animated
 * gradient in Claude's warm colours, Claude's mark sitting on its top edge and moving (its rays
 * breathing in a wave, faster while it waits for an answer).
 *
 * It comes in from the left like the notices; when there are details, it opens up after a moment
 * to show them (lines starting with "- " become bullets, **bold** is bold). A question shows its
 * choices as glass capsules (the remote can pick one) and a QR code to answer from the phone.
 */
final class ClaudeCard extends FrameLayout implements Choreographer.FrameCallback {

    interface Choice {
        void chosen(int index, String label);
    }

    static final int CORAL = 0xFFD97757;
    private static final int[] EDGE = {0xFFD97757, 0xFFF0A58A, 0xFFFBE3D5, 0xFFE8916F, 0xFFC15F3C, 0xFFD97757};

    private final float dp;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path shape = new Path();
    private final Matrix map = new Matrix();
    private final Matrix spin = new Matrix();
    private final int[] where = new int[2];
    private final LinearLayout column;
    private final TextView details;
    private final LinearLayout choices;
    private final Mark mark;
    private final Spring open;
    private final long born = SystemClock.uptimeMillis();
    private Bitmap behind;
    private int screenW;
    private int screenH;
    private boolean waiting;
    private boolean running;
    private int detailsHeight;

    ClaudeCard(Context context, float dp, String from, String title, String text, String detailText,
               List<String> options, Bitmap qr, String url, final Choice choice) {
        super(context);
        this.dp = dp;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        shadow.setColor(0x01000000);
        shadow.setShadowLayer(px(22), 0, px(8), 0x80000000);
        tint.setColor(0xC8141417);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(2f, 2.4f * dp));
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(px(7));
        waiting = options != null && !options.isEmpty();

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(24), px(30), px(24), px(20));
        // Who it is from, small, in Claude's colour.
        TextView who = text(context, 11.5f, true);
        who.setTextColor(0xFFF0A58A);
        who.setLetterSpacing(0.06f);
        who.setText((from == null || from.isEmpty() ? "Claude" : from).toUpperCase(java.util.Locale.ROOT));
        column.addView(who);
        TextView head = text(context, 19, true);
        head.setText(title == null ? "" : title);
        head.setMaxLines(3);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = px(6);
        column.addView(head, hlp);
        if (text != null && !text.isEmpty()) {
            TextView body = text(context, 14, false);
            body.setText(text);
            body.setMaxLines(4);
            body.setAlpha(0.92f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = px(6);
            column.addView(body, blp);
        }
        details = text(context, 13, false);
        details.setMaxLines(14);
        details.setAlpha(0.88f);
        details.setLineSpacing(px(3), 1f);
        details.setText(format(detailText));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0);
        dlp.topMargin = px(10);
        column.addView(details, dlp);

        choices = new LinearLayout(context);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        if (waiting) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout chips = new LinearLayout(context);
            chips.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < options.size(); i++) {
                final int index = i;
                final String label = options.get(i);
                TextView chip = capsule(context, (options.size() > 2 ? (i + 1) + "   " : "") + label);
                chip.setOnClickListener(v -> choice.chosen(index, label));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, px(40));
                clp.topMargin = i == 0 ? 0 : px(8);
                chips.addView(chip, clp);
            }
            row.addView(chips, new LinearLayout.LayoutParams(0, -2, 1f));
            if (qr != null) {
                LinearLayout phone = new LinearLayout(context);
                phone.setOrientation(LinearLayout.VERTICAL);
                phone.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView code = new ImageView(context);
                code.setImageBitmap(qr);
                code.setBackgroundColor(Color.WHITE);
                code.setPadding(px(6), px(6), px(6), px(6));
                code.setOutlineProvider(round(px(10)));
                code.setClipToOutline(true);
                phone.addView(code, new LinearLayout.LayoutParams(px(116), px(116)));
                TextView hint = text(context, 10.5f, false);
                hint.setText("Răspunde de pe telefon");
                hint.setAlpha(0.8f);
                hint.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
                tlp.topMargin = px(6);
                phone.addView(hint, tlp);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
                plp.leftMargin = px(18);
                row.addView(phone, plp);
            }
            choices.addView(row, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
            olp.topMargin = px(16);
            column.addView(choices, olp);
        }
        addView(column, new LayoutParams(-1, -2));

        mark = new Mark(context);
        LayoutParams mlp = new LayoutParams(px(58), px(58), Gravity.TOP | Gravity.START);
        mlp.leftMargin = px(14);
        mlp.topMargin = -px(26);   // sitting on the top edge
        addView(mark, mlp);

        open = new Spring(0f, 0.002f, v -> {
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) details.getLayoutParams();
            l.height = Math.max(0, Math.round(detailsHeight * v));
            details.setLayoutParams(l);
            details.setAlpha(0.88f * Math.max(0f, Math.min(1f, (v - 0.3f) / 0.7f)));
        });
    }

    boolean waiting() {
        return waiting;
    }

    /** The screen behind, blurred (screen-sized proportions), or null over video. */
    void setBehind(Bitmap blurred, int screenW, int screenH) {
        behind = blurred;
        this.screenW = screenW;
        this.screenH = screenH;
        invalidate();
    }

    /** In from the left with a spring; the details open a moment later. */
    void appear(int width) {
        details.measure(MeasureSpec.makeMeasureSpec(width - px(48), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        detailsHeight = details.getText().length() == 0 ? 0 : details.getMeasuredHeight();
        setAlpha(0f);
        setTranslationX(-px(80));
        setScaleX(0.94f);
        setScaleY(0.94f);
        Spring in = new Spring(0f, 0.002f, v -> {
            setAlpha(Math.max(0f, Math.min(1f, v * 1.6f)));
            setTranslationX(-px(80) * (1f - v));
            setScaleX(0.94f + 0.06f * v);
            setScaleY(0.94f + 0.06f * v);
        });
        in.to(1f, 0.55f, 0.72f);
        if (detailsHeight > 0) {
            postDelayed(() -> open.to(1f, 0.6f, 0.82f), 1300);
        }
        if (waiting && choices.getChildCount() > 0) {
            postDelayed(() -> {
                View first = findFirstFocusable(choices);
                if (first != null) {
                    first.requestFocus();
                }
            }, 400);
        }
    }

    /** The answer was given: a check mark, the mark slows, then the card leaves. */
    void answered(String label, Runnable gone) {
        waiting = false;
        choices.removeAllViews();
        TextView done = text(getContext(), 14, true);
        done.setTextColor(0xFFF0A58A);
        done.setText("✓  " + label);
        choices.addView(done);
        postDelayed(() -> disappear(gone), 1600);
    }

    void disappear(Runnable gone) {
        animate().alpha(0f).translationX(-px(60)).scaleX(0.96f).scaleY(0.96f).setDuration(380)
                .withEndAction(gone).start();
    }

    private static View findFirstFocusable(View v) {
        if (v.isFocusable()) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findFirstFocusable(g.getChildAt(i));
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long now) {
        if (!running) {
            return;
        }
        invalidate();
        mark.invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private float t() {
        return (SystemClock.uptimeMillis() - born) / 1000f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = px(26);
        box.set(0, 0, getWidth(), getHeight());
        shape.reset();
        shape.addRoundRect(box, r, r, Path.Direction.CW);
        canvas.drawPath(shape, shadow);
        if (behind != null && !behind.isRecycled()) {
            getLocationOnScreen(where);
            BitmapShader shader = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            map.setScale(screenW / (float) behind.getWidth(), screenH / (float) behind.getHeight());
            map.postTranslate(-where[0], -where[1]);
            shader.setLocalMatrix(map);
            glass.setShader(shader);
            canvas.drawPath(shape, glass);
        }
        canvas.drawPath(shape, tint);
        // Warm light from the mark's corner, and from above.
        tint.setShader(null);
        sheen.setShader(new RadialGradient(px(40), 0, getWidth() * 0.7f, 0x2AD97757, 0x00D97757, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, sheen);
        sheen.setShader(new LinearGradient(0, 0, 0, getHeight() * 0.5f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, sheen);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // The edge: Claude's colours going round, faster while it waits for an answer.
        float r = px(26);
        box.set(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2, getWidth() - edge.getStrokeWidth() / 2,
                getHeight() - edge.getStrokeWidth() / 2);
        SweepGradient sweep = new SweepGradient(getWidth() / 2f, getHeight() / 2f, EDGE, null);
        spin.setRotate((t() * (waiting ? 120f : 60f)) % 360f, getWidth() / 2f, getHeight() / 2f);
        sweep.setLocalMatrix(spin);
        glow.setShader(sweep);
        glow.setAlpha(70);
        canvas.drawRoundRect(box, r, r, glow);
        edge.setShader(sweep);
        canvas.drawRoundRect(box, r, r, edge);
    }

    // ------------------------------------------------------------------ pieces

    /** Claude's mark: a spark of rays from the middle, breathing in a wave, turning slowly. */
    private final class Mark extends View {
        private final Paint ray = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] base = {1.0f, 0.78f, 0.93f, 0.7f, 0.98f, 0.8f, 0.9f, 0.72f, 1.0f, 0.76f, 0.94f, 0.74f};

        Mark(Context context) {
            super(context);
            ray.setStrokeCap(Paint.Cap.ROUND);
            ray.setColor(CORAL);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float t = t();
            float speed = waiting ? 3.2f : 1.6f;
            // A soft glow behind it, pulsing.
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * speed);
            halo.setShader(new RadialGradient(cx, cy, s * 0.62f, (Math.round(60 + 50 * pulse) << 24) | 0xD97757,
                    0x00D97757, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, s * 0.62f, halo);
            // A dark disc so the spark reads over any picture.
            halo.setShader(null);
            halo.setColor(0xF0161618);
            c.drawCircle(cx, cy, s * 0.4f, halo);
            c.save();
            c.rotate(t * (waiting ? 28f : 12f), cx, cy);
            int n = base.length;
            ray.setStrokeWidth(s * 0.075f);
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n;
                float wave = 1f + 0.14f * (float) Math.sin(t * speed * 1.8f - i * 0.9f);
                float len = s * 0.3f * base[i] * wave;
                float r0 = s * 0.05f;
                c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                        cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, ray);
            }
            c.restore();
        }
    }

    /** Lines starting with "- " or "• " become bullets; **words** are bold. */
    private CharSequence format(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean bullet = line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ");
            int start = out.length();
            if (bullet) {
                out.append("•  ");
                line = line.substring(2);
            }
            int at = 0;
            while (true) {
                int b = line.indexOf("**", at);
                int e = b < 0 ? -1 : line.indexOf("**", b + 2);
                if (b < 0 || e < 0) {
                    out.append(line.substring(at));
                    break;
                }
                out.append(line, at, b);
                int s = out.length();
                out.append(line, b + 2, e);
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                at = e + 2;
            }
            if (bullet) {
                out.setSpan(new LeadingMarginSpan.Standard(0, px(14)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i + 1 < lines.length) {
                out.append("\n");
            }
        }
        return out;
    }

    private TextView capsule(Context context, String label) {
        final TextView b = text(context, 14, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(18), 0, px(18), 0);
        b.setFocusable(true);
        final GlassDrawable surface = new GlassDrawable(px(20), dp, 0x29FFFFFF);
        b.setBackground(surface);
        b.setOnFocusChangeListener((v, has) -> {
            surface.setTint(has ? 0xF2FFFFFF : 0x29FFFFFF);
            b.setTextColor(has ? 0xFF141414 : Color.WHITE);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(180).start();
        });
        b.setOnKeyListener((v, code, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                v.performClick();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        });
        return b;
    }

    private TextView text(Context context, float sp, boolean bold) {
        TextView v = new TextView(context);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setIncludeFontPadding(false);
        v.setShadowLayer(px(3), 0, px(1), 0x66000000);
        return v;
    }

    private static android.view.ViewOutlineProvider round(final float r) {
        return new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        };
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
