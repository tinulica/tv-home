package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A message from Claude on the TV: a compact pane of Liquid Glass at the top of the screen (its
 * background is the service's lens glass), with a thin edge of Claude's warm colours going round,
 * Claude's mark by the name, and Claude Code's mascot, Clawd, standing on the top edge: it bobs
 * and blinks, walks to and fro while a question waits, and jumps when it is answered.
 *
 * With details it opens up after a moment ("- " lines are bullets, **words** bold); a question
 * shows its choices as glass capsules (the remote picks one) and a QR code to answer by phone.
 */
final class ClaudeCard extends FrameLayout implements Choreographer.FrameCallback {

    interface Choice {
        void chosen(int index, String label);
    }

    static final int CORAL = 0xFFD97757;
    private static final int[] EDGE = {0xFFD97757, 0xFFF0A58A, 0xFFFBE3D5, 0xFFE8916F, 0xFFC15F3C, 0xFFD97757};
    /** Claude's mark (Wikimedia Commons, "Claude AI symbol.svg", CC0), in a 100 x 100 box. */
    static final String MARK_PATH = "m19.6 66.5 19.7-11 .3-1-.3-.5h-1l-3.3-.2-11.2-.3L14 53l-9.5-.5-2.4-.5L0 49l.2-1.5 "
            + "2-1.3 2.9.2 6.3.5 9.5.6 6.9.4L38 49.1h1.6l.2-.7-.5-.4-.4-.4L29 41l-10.6-7-5.6-4.1-3-2-1.5-2-.6-4.2 2.7-3 3.7.3"
            + ".9.2 3.7 2.9 8 6.1L37 36l1.5 1.2.6-.4.1-.3-.7-1.1L33 25l-6-10.4-2.7-4.3-.7-2.6c-.3-1-.4-2-.4-3l3-4.2L28 0l4.2"
            + ".6L33.8 2l2.6 6 4.1 9.3L47 29.9l2 3.8 1 3.4.3 1h.7v-.5l.5-7.2 1-8.7 1-11.2.3-3.2 1.6-3.8 3-2L61 2.6l2 2.9-.3 "
            + "1.8-1.1 7.7L59 27.1l-1.5 8.2h.9l1-1.1 4.1-5.4 6.9-8.6 3-3.5L77 13l2.3-1.8h4.3l3.1 4.7-1.4 4.9-4.4 5.6-3.7 4.7"
            + "-5.3 7.1-3.2 5.7.3.4h.7l12-2.6 6.4-1.1 7.6-1.3 3.5 1.6.4 1.6-1.4 3.4-8.2 2-9.6 2-14.3 3.3-.2.1.2.3 6.4.6 2.8"
            + ".2h6.8l12.6 1 3.3 2 1.9 2.7-.3 2-5.1 2.6-6.8-1.6-16-3.8-5.4-1.3h-.8v.4l4.6 4.5 8.3 7.5L89 80.1l.5 2.4-1.3 2-1.4"
            + "-.2-9.2-7-3.6-3-8-6.8h-.5v.7l1.8 2.7 9.8 14.7.5 4.5-.7 1.4-2.6 1-2.7-.6-5.8-8-6-9-4.7-8.2-.5.4-2.9 30.2-1.3 "
            + "1.5-3 1.2-2.5-2-1.4-3 1.4-6.2 1.6-8 1.3-6.4 1.2-7.9.7-2.6v-.2H49L43 72l-9 12.3-7.2 7.6-1.7.7-3-1.5.3-2.8L24 "
            + "86l10-12.8 6-7.9 4-4.6-.1-.5h-.3L17.2 77.4l-4.7.6-2-2 .2-3 1-1 8-5.5Z";

    private final float dp;
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Matrix spin = new Matrix();
    private final LinearLayout column;
    private final TextView details;
    private final LinearLayout choices;
    private final Clawd clawd;
    private final Spring open;
    private final long born = SystemClock.uptimeMillis();
    private boolean waiting;
    private boolean running;
    private int detailsHeight;
    private float radius;

    ClaudeCard(Context context, float dp, String from, String title, String text, String detailText,
               List<String> options, Bitmap qr, String url, final Choice choice) {
        super(context);
        this.dp = dp;
        radius = px(18);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1.5f, 1.6f * dp));
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(px(5));
        veil.setColor(0x2E000000);   // only enough for the words to read on bright pictures
        waiting = options != null && !options.isEmpty();

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(px(18), px(14), px(18), px(16));
        // Claude's mark, the name, "now".
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new Mark(context), new LinearLayout.LayoutParams(px(16), px(16)));
        TextView who = text(context, 11.5f, true);
        who.setText(from == null || from.isEmpty() ? "Claude" : from);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1f);
        wlp.leftMargin = px(7);
        head.addView(who, wlp);
        TextView now = text(context, 10.5f, false);
        now.setText("acum");
        now.setAlpha(0.7f);
        head.addView(now);
        column.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView titleView = text(context, 15.5f, true);
        titleView.setText(title == null ? "" : title);
        titleView.setMaxLines(2);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = px(7);
        column.addView(titleView, hlp);
        if (text != null && !text.isEmpty()) {
            TextView body = text(context, 13, false);
            body.setText(text);
            body.setMaxLines(3);
            body.setAlpha(0.92f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = px(4);
            column.addView(body, blp);
        }
        details = text(context, 12.5f, false);
        details.setMaxLines(12);
        details.setAlpha(0.9f);
        details.setLineSpacing(px(2), 1f);
        details.setText(format(detailText));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0);
        dlp.topMargin = px(8);
        column.addView(details, dlp);

        choices = new LinearLayout(context);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        choices.setClipChildren(false);
        choices.setClipToPadding(false);
        if (waiting) {
            LinearLayout chips = new LinearLayout(context);
            chips.setOrientation(LinearLayout.VERTICAL);
            chips.setClipChildren(false);
            chips.setClipToPadding(false);
            chips.setPadding(px(4), px(4), px(4), px(4));   // room for a focused capsule to grow
            for (int i = 0; i < options.size(); i++) {
                final int index = i;
                final String label = options.get(i);
                TextView chip = capsule(context, label);
                chip.setOnClickListener(v -> choice.chosen(index, label));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, px(34));
                clp.topMargin = i == 0 ? 0 : px(7);
                chips.addView(chip, clp);
            }
            choices.addView(chips, new LinearLayout.LayoutParams(0, -2, 1f));
            if (qr != null) {
                LinearLayout phone = new LinearLayout(context);
                phone.setOrientation(LinearLayout.VERTICAL);
                phone.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView code = new ImageView(context);
                code.setImageBitmap(qr);
                code.setBackgroundColor(Color.WHITE);
                code.setPadding(px(4), px(4), px(4), px(4));
                code.setOutlineProvider(round(px(8)));
                code.setClipToOutline(true);
                phone.addView(code, new LinearLayout.LayoutParams(px(88), px(88)));
                TextView hint = text(context, 9.5f, false);
                hint.setText("de pe telefon");
                hint.setAlpha(0.8f);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
                tlp.topMargin = px(4);
                phone.addView(hint, tlp);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
                plp.leftMargin = px(14);
                choices.addView(phone, plp);
            }
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
            olp.topMargin = px(10);
            column.addView(choices, olp);
        }
        addView(column, new LayoutParams(-1, -2));

        clawd = new Clawd(context);
        LayoutParams mlp = new LayoutParams(px(60), px(30), Gravity.TOP | Gravity.START);
        mlp.topMargin = -px(26);   // standing on the top edge
        addView(clawd, mlp);

        open = new Spring(0f, 0.002f, v -> {
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) details.getLayoutParams();
            l.height = Math.max(0, Math.round(detailsHeight * v));
            details.setLayoutParams(l);
            details.setAlpha(0.9f * Math.max(0f, Math.min(1f, (v - 0.3f) / 0.7f)));
        });
    }

    boolean waiting() {
        return waiting;
    }

    float radius() {
        return radius;
    }

    /** Down from above with a spring; the details open a moment later. */
    void appear(int width) {
        details.measure(MeasureSpec.makeMeasureSpec(width - px(36), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        detailsHeight = details.getText().length() == 0 ? 0 : details.getMeasuredHeight();
        setAlpha(0f);
        setTranslationY(-px(40));
        setScaleX(0.96f);
        setScaleY(0.96f);
        Spring in = new Spring(0f, 0.002f, v -> {
            setAlpha(Math.max(0f, Math.min(1f, v * 1.6f)));
            setTranslationY(-px(40) * (1f - v));
            setScaleX(0.96f + 0.04f * v);
            setScaleY(0.96f + 0.04f * v);
        });
        in.to(1f, 0.5f, 0.74f);
        if (detailsHeight > 0) {
            postDelayed(() -> open.to(1f, 0.6f, 0.84f), 1300);
        }
        if (waiting) {
            postDelayed(() -> {
                View first = findFirstFocusable(choices);
                if (first != null) {
                    first.requestFocus();
                }
            }, 350);
        }
    }

    /** The answer was given: a check mark, Clawd jumps, then the card leaves. */
    void answered(String label, Runnable gone) {
        waiting = false;
        clawd.jump();
        choices.removeAllViews();
        TextView done = text(getContext(), 13.5f, true);
        done.setTextColor(0xFFF8C9B4);
        done.setText("✓  " + label);
        choices.addView(done);
        postDelayed(() -> disappear(gone), 1500);
    }

    void disappear(Runnable gone) {
        animate().alpha(0f).translationY(-px(30)).scaleX(0.97f).scaleY(0.97f).setDuration(360)
                .withEndAction(gone).start();
    }

    private static View findFirstFocusable(View v) {
        if (v.isFocusable()) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
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
        clawd.step();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private float t() {
        return (SystemClock.uptimeMillis() - born) / 1000f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        box.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(box, radius, radius, veil);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // The edge: Claude's colours going round, faster while a question waits.
        float half = edge.getStrokeWidth() / 2;
        box.set(half, half, getWidth() - half, getHeight() - half);
        SweepGradient sweep = new SweepGradient(getWidth() / 2f, getHeight() / 2f, EDGE, null);
        spin.setRotate((t() * (waiting ? 110f : 55f)) % 360f, getWidth() / 2f, getHeight() / 2f);
        sweep.setLocalMatrix(spin);
        glow.setShader(sweep);
        glow.setAlpha(55);
        canvas.drawRoundRect(box, radius, radius, glow);
        edge.setShader(sweep);
        edge.setAlpha(230);
        canvas.drawRoundRect(box, radius, radius, edge);
    }

    // ------------------------------------------------------------------ Claude's mark and Clawd

    /** Claude's mark, in Claude's colour. */
    private static final class Mark extends View {
        private static Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix fit = new Matrix();
        private final Path drawn = new Path();

        Mark(Context context) {
            super(context);
            paint.setColor(CORAL);
            if (path == null) {
                path = svg(MARK_PATH);
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

    /**
     * Claude Code's mascot, Clawd, as in its terminal picture: an orange body with two eyes, arms
     * out at the sides and four short legs, drawn in pixels.
     */
    private final class Clawd extends View {
        private static final int W = 18;
        private final Paint body = new Paint();
        private final Paint eye = new Paint();
        private float x = 0.12f;      // where it stands along the edge (0..1 of the free width)
        private float dir = 1f;
        private float jumpAt = -10f;
        private long last;

        Clawd(Context context) {
            super(context);
            body.setColor(CORAL);
            eye.setColor(0xFF1A1A1A);
        }

        void jump() {
            jumpAt = t();
        }

        void step() {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            if (waiting) {
                x += dir * dt * 0.09f;
                if (x > 0.88f || x < 0.04f) {
                    dir = -dir;
                    x = Math.max(0.04f, Math.min(0.88f, x));
                }
            }
            View parent = (View) getParent();
            if (parent != null) {
                setTranslationX(x * Math.max(0, parent.getWidth() - getWidth()));
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float p = getWidth() / (float) W;      // one pixel
            float t = t();
            boolean walking = waiting;
            int frame = (int) (t * (walking ? 6 : 2)) % 2;
            float bob = walking ? (frame == 0 ? 0 : -p * 0.5f) : (float) Math.sin(t * 2.2f) * p * 0.35f;
            float j = t - jumpAt;
            if (j >= 0 && j < 0.5f) {
                bob -= (float) Math.sin(j / 0.5f * Math.PI) * p * 4;
            }
            boolean blink = (t % 3.4f) < 0.12f;
            boolean arms = j >= 0 && j < 1.2f && ((int) (j * 8) % 2 == 0);   // waving when answered
            float top = getHeight() - 5 * p + bob;
            // Body x3..14, rows 0..3.
            c.drawRect(3 * p, top, 15 * p, top + 4 * p, body);
            // Arms on row 2 (raised to row 1 when it waves).
            float ay = top + (arms ? 1 : 2) * p;
            c.drawRect(1 * p, ay, 3 * p, ay + p, body);
            c.drawRect(15 * p, ay, 17 * p, ay + p, body);
            // Eyes at (5,1) and (12,1), shut for a moment now and then.
            if (!blink) {
                c.drawRect(5 * p, top + p, 6 * p, top + 2 * p, eye);
                c.drawRect(12 * p, top + p, 13 * p, top + 2 * p, eye);
            }
            // Legs on row 4: x 4, 6, 11, 13; while walking the pairs step in turn.
            float ly = top + 4 * p;
            float liftA = walking && frame == 0 ? -p * 0.6f : 0;
            float liftB = walking && frame == 1 ? -p * 0.6f : 0;
            c.drawRect(4 * p, ly + liftA, 5 * p, ly + p, body);
            c.drawRect(6 * p, ly + liftB, 7 * p, ly + p, body);
            c.drawRect(11 * p, ly + liftA, 12 * p, ly + p, body);
            c.drawRect(13 * p, ly + liftB, 14 * p, ly + p, body);
        }
    }

    /** A path from SVG path data (the commands M L H V C Z, absolute and relative). */
    static Path svg(String d) {
        Path path = new Path();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[MmLlHhVvCcZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE]-?\\d+)?").matcher(d);
        java.util.List<String> tokens = new java.util.ArrayList<>();
        while (m.find()) {
            tokens.add(m.group());
        }
        float x = 0;
        float y = 0;
        float sx = 0;
        float sy = 0;
        char cmd = 'M';
        int i = 0;
        while (i < tokens.size()) {
            String tk = tokens.get(i);
            if (Character.isLetter(tk.charAt(0))) {
                cmd = tk.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    path.close();
                    x = sx;
                    y = sy;
                    continue;
                }
            }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float nx = Float.parseFloat(tokens.get(i++)) + (rel ? x : 0);
                    float ny = Float.parseFloat(tokens.get(i++)) + (rel ? y : 0);
                    path.moveTo(nx, ny);
                    x = sx = nx;
                    y = sy = ny;
                    cmd = rel ? 'l' : 'L';   // further pairs are line-tos
                    break;
                }
                case 'L': {
                    float nx = Float.parseFloat(tokens.get(i++)) + (rel ? x : 0);
                    float ny = Float.parseFloat(tokens.get(i++)) + (rel ? y : 0);
                    path.lineTo(nx, ny);
                    x = nx;
                    y = ny;
                    break;
                }
                case 'H': {
                    float nx = Float.parseFloat(tokens.get(i++)) + (rel ? x : 0);
                    path.lineTo(nx, y);
                    x = nx;
                    break;
                }
                case 'V': {
                    float ny = Float.parseFloat(tokens.get(i++)) + (rel ? y : 0);
                    path.lineTo(x, ny);
                    y = ny;
                    break;
                }
                case 'C': {
                    float[] v = new float[6];
                    for (int k = 0; k < 6; k++) {
                        v[k] = Float.parseFloat(tokens.get(i++)) + (rel ? (k % 2 == 0 ? x : y) : 0);
                    }
                    path.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5]);
                    x = v[4];
                    y = v[5];
                    break;
                }
                default:
                    i++;
                    break;
            }
        }
        return path;
    }

    // ------------------------------------------------------------------ pieces

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
                out.setSpan(new LeadingMarginSpan.Standard(0, px(13)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i + 1 < lines.length) {
                out.append("\n");
            }
        }
        return out;
    }

    private TextView capsule(Context context, String label) {
        final TextView b = text(context, 13, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(14), 0, px(14), 0);
        b.setFocusable(true);
        final GlassDrawable surface = new GlassDrawable(px(17), dp, 0x29FFFFFF);
        b.setBackground(surface);
        b.setOnFocusChangeListener((v, has) -> {
            surface.setTint(has ? 0xF2FFFFFF : 0x29FFFFFF);
            b.setTextColor(has ? 0xFF141414 : Color.WHITE);
            b.setShadowLayer(has ? 0 : px(3), 0, has ? 0 : px(1), 0x66000000);
            v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(160).start();
        });
        b.setOnKeyListener((v, code, event) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (ok && event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                v.performClick();
            }
            return ok;
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
        v.setShadowLayer(px(3), 0, px(1), 0x73000000);
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
