package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
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
 * A message from Claude on the TV: what is inside the drop of Liquid Glass that flows out of the
 * left edge (SourcesService puts it in a LiquidDrop, with Clawd standing on top): Claude's mark
 * by the name, the title and the text.
 *
 * It comes out small, the name and the title only, and once the drop has settled it opens,
 * wider and down: the text, then the details ("- " lines are bullets, **words** bold), then a
 * question's choices as glass capsules (the remote picks one) with a QR code to answer by phone.
 */
final class ClaudeCard extends FrameLayout {

    interface Choice {
        void chosen(int index, String label);
    }

    /** How much wider and taller than its first size the card has grown (opening). */
    interface Growth {
        void grew(float wider, float taller);
    }

    static final int CORAL = 0xFFD97757;
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
    private final Paint veil = new Paint();
    private final LinearLayout column;
    private final LinearLayout head;
    private final TextView titleView;
    /** All but the name and the title: what opens once the small drop has settled. */
    private final LinearLayout rest;
    private final float[] restAlpha = new float[3];
    private final LinearLayout choices;
    private final LinearLayout chips;
    private final Spring open;
    private final long born = SystemClock.uptimeMillis();
    private boolean waiting;
    private int restHeight;
    private int compactW;
    private int compactH;
    private int extraW;
    private int extraH;
    private Growth growth;
    private View phone;

    ClaudeCard(Context context, float dp, String from, String title, String text, String detailText,
               List<String> options, Bitmap qr, String url, final Choice choice) {
        super(context);
        this.dp = dp;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        veil.setColor(0x24000000);   // only enough for the words to read on bright pictures
        waiting = options != null && !options.isEmpty();

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(px(20), px(14), px(18), px(14));
        // Claude's mark, the name, "now".
        head = new LinearLayout(context);
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
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.leftMargin = px(14);
        head.addView(now, nlp);
        column.addView(head, new LinearLayout.LayoutParams(-1, -2));
        titleView = text(context, 15.5f, true);
        titleView.setText(title == null ? "" : title);
        titleView.setMaxLines(2);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = px(6);
        column.addView(titleView, hlp);

        rest = new LinearLayout(context);
        rest.setOrientation(LinearLayout.VERTICAL);
        rest.setClipChildren(false);
        rest.setClipToPadding(false);
        if (text != null && !text.isEmpty()) {
            TextView body = text(context, 13, false);
            body.setText(text);
            body.setMaxLines(3);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = px(4);
            restAlpha[rest.getChildCount()] = 0.92f;
            rest.addView(body, blp);
        }
        CharSequence more = format(detailText);
        if (more.length() > 0) {
            TextView details = text(context, 12.5f, false);
            details.setMaxLines(12);
            details.setLineSpacing(px(2), 1f);
            details.setText(more);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.topMargin = px(8);
            restAlpha[rest.getChildCount()] = 0.9f;
            rest.addView(details, dlp);
        }

        choices = new LinearLayout(context);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        choices.setClipChildren(false);
        choices.setClipToPadding(false);
        chips = new LinearLayout(context);
        if (waiting) {
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
            for (int i = 0; i < chips.getChildCount(); i++) {
                View c = chips.getChildAt(i);
                c.setId(View.generateViewId());
            }
            for (int i = 0; i < chips.getChildCount(); i++) {
                View c = chips.getChildAt(i);
                c.setNextFocusUpId(chips.getChildAt(Math.max(0, i - 1)).getId());
                c.setNextFocusDownId(chips.getChildAt(Math.min(chips.getChildCount() - 1, i + 1)).getId());
                c.setNextFocusLeftId(c.getId());
                c.setNextFocusRightId(c.getId());
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
                this.phone = phone;
            }
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
            olp.topMargin = px(10);
            restAlpha[rest.getChildCount()] = 1f;
            rest.addView(choices, olp);
        }
        column.addView(rest, new LinearLayout.LayoutParams(-1, 0));
        addView(column, new LayoutParams(-1, -2));
        for (int i = 0; i < rest.getChildCount(); i++) {
            rest.getChildAt(i).setAlpha(0f);
        }

        // Opening: the drop widens and grows down, and what was hidden comes in line by line.
        open = new Spring(0f, 0.002f, v -> {
            float t = Math.max(0f, Math.min(1f, v));
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) rest.getLayoutParams();
            l.height = Math.round(restHeight * t);
            rest.setLayoutParams(l);
            for (int i = 0; i < rest.getChildCount(); i++) {
                float k = Math.max(0f, Math.min(1f, (t - 0.3f - 0.14f * i) / 0.4f));
                rest.getChildAt(i).setAlpha(restAlpha[i] * k);
            }
            if (growth != null) {
                growth.grew(extraW * Math.max(0f, v), extraH * Math.max(0f, v));
            }
        });
    }

    boolean waiting() {
        return waiting;
    }

    /**
     * Measures it small (the name and the title, only as wide as they need) and open
     * (`fullWidth` wide, everything showing).
     */
    void measureFor(int fullWidth) {
        int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) rest.getLayoutParams();
        l.height = LayoutParams.WRAP_CONTENT;
        rest.setLayoutParams(l);
        measure(MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY), free);
        int openH = getMeasuredHeight();
        restHeight = rest.getMeasuredHeight();
        l.height = 0;
        rest.setLayoutParams(l);
        int sides = column.getPaddingLeft() + column.getPaddingRight();
        int words = Math.round(titleView.getPaint().measureText(String.valueOf(titleView.getText()))) + sides + px(6);
        head.measure(free, free);
        int name = head.getMeasuredWidth() + sides;
        compactW = Math.max(px(250), Math.min(fullWidth, Math.max(words, name)));
        measure(MeasureSpec.makeMeasureSpec(compactW, MeasureSpec.EXACTLY), free);
        compactH = getMeasuredHeight();
        extraW = fullWidth - compactW;
        extraH = Math.max(0, openH - compactH);
    }

    int compactWidth() {
        return compactW;
    }

    int compactHeight() {
        return compactH;
    }

    int extraWidth() {
        return extraW;
    }

    int extraHeight() {
        return extraH;
    }

    void setGrowth(Growth g) {
        growth = g;
    }

    /** The small drop has flowed out: it opens, then the first choice takes focus. */
    void appear() {
        postDelayed(this::openNow, 760);
        if (waiting) {
            postDelayed(() -> {
                View first = findFirstFocusable(choices);
                if (first != null) {
                    first.requestFocus();
                }
            }, 1250);
        }
    }

    /** The answer was given: its capsule stays lit, with a check mark; the others fade. */
    void answered(int index, String label) {
        waiting = false;
        for (int i = 0; i < chips.getChildCount(); i++) {
            final View c = chips.getChildAt(i);
            if (i == index && c instanceof TextView) {
                ((TextView) c).setText("\u2713  " + label);
                c.animate().scaleX(1.05f).scaleY(1.05f).setDuration(140)
                        .withEndAction(() -> c.animate().scaleX(1f).scaleY(1f).setDuration(260).start()).start();
            } else {
                c.animate().alpha(0.3f).setDuration(260).start();
            }
        }
        if (phone != null) {
            phone.animate().alpha(0.3f).setDuration(260).start();
        }
    }

    /** Opens now (also when the remote chooses it among the notices). */
    void openNow() {
        if (growth != null && open.target() < 1f) {
            open.to(1f, 0.56f, 0.8f);
        }
    }

    /** Leaving: nothing more opens. */
    void closing() {
        waiting = false;
        growth = null;
        open.stop();
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

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), veil);
    }

    // ------------------------------------------------------------------ Claude's mark

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

    /** Claude's mark as a picture `size` pixels square, in `color`. */
    static Bitmap markBitmap(int size, int color) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        Path path = svg(MARK_PATH);
        Matrix fit = new Matrix();
        fit.setScale(size / 100f, size / 100f);
        path.transform(fit);
        new Canvas(b).drawPath(path, paint);
        return b;
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
        final boolean[] pressed = {false};
        b.setOnKeyListener((v, code, event) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (!ok) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && SystemClock.uptimeMillis() - born > 700) {
                pressed[0] = true;   // pressed here, after the card came in (not a press left over)
            } else if (event.getAction() == KeyEvent.ACTION_UP && pressed[0]) {
                pressed[0] = false;
                if (waiting) {
                    v.performClick();
                }
            }
            return true;
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
