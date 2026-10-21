package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * The sleep timer's clock (Control Center > Somn): two wheels, hours and minutes, like the iOS
 * time picker. Up and down roll the wheel (held, it rolls on), left and right move between
 * hours and minutes, OK starts the timer. Underneath, in words, how long until the TV switches
 * itself off.
 */
final class SleepClock extends View {

    interface Chosen {
        void at(long when);
    }

    private final float dp;
    private final Chosen chosen;
    private int hour;
    private int minute;
    private int column;
    private final Spring rollHours;
    private final Spring rollMinutes;
    private final Spring capsule;
    private final Spring lit;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** `current`: the time already set (0 = none: an hour from now is offered). */
    SleepClock(Context context, float dp, long current, Chosen chosen) {
        super(context);
        this.dp = dp;
        this.chosen = chosen;
        setFocusable(true);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(current > 0 ? current : System.currentTimeMillis() + 60 * 60_000L);
        hour = c.get(Calendar.HOUR_OF_DAY);
        minute = (c.get(Calendar.MINUTE) + 4) / 5 * 5;
        if (minute == 60) {
            minute = 0;
            hour = (hour + 1) % 24;
        }
        rollHours = new Spring(0f, 0.002f, v -> invalidate()).configure(0.34f, 0.8f);
        rollMinutes = new Spring(0f, 0.002f, v -> invalidate()).configure(0.34f, 0.8f);
        capsule = new Spring(0f, 0.002f, v -> invalidate()).configure(0.36f, 0.78f);
        lit = new Spring(0f, 0.004f, v -> invalidate());
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** The moment chosen: today at that time, or tomorrow if it has passed. */
    long target() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? Math.round(174 * dp) : MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(150 * dp));
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        lit.to(gained ? 1f : 0f, 0.22f, 1f);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                roll(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                roll(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                int to = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                if (to != column) {
                    column = to;
                    capsule.to(to);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    chosen.at(target());
                }
                return true;
            default:
                return super.onKeyDown(code, event);
        }
    }

    /** One step of the wheel in use: the new number rolls into the middle. */
    private void roll(int step) {
        if (column == 0) {
            hour = (hour + step + 24) % 24;
            rollHours.from(rollHours.value() + step);
        } else {
            minute = (minute + step * 5 + 60) % 60;
            rollMinutes.from(rollMinutes.value() + step);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float cx = w / 2f;
        float f = Math.max(0f, Math.min(1f, lit.value()));

        long minutes = Math.max(1, Math.round((target() - System.currentTimeMillis()) / 60_000.0));

        // ---- the wheels
        float row = 20 * dp;
        float wy = 6 * dp + 2.5f * row;
        float hx = cx - 34 * dp;
        float mx = cx + 34 * dp;
        // The capsule behind the number in use: it slides between the wheels; white while the
        // clock has focus (the number in it turns dark, as on every focused row).
        float px = hx + (mx - hx) * capsule.value();
        box.set(px - 28 * dp, wy - 15 * dp, px + 28 * dp, wy + 15 * dp);
        paint.setColor(mix(0x24FFFFFF, 0xF2FFFFFF, f));
        canvas.drawRoundRect(box, 15 * dp, 15 * dp, paint);
        wheel(canvas, hx, wy, row, hour, 24, 1, rollHours.value(), column == 0 ? f : 0f);
        wheel(canvas, mx, wy, row, minute / 5, 12, 5, rollMinutes.value(), column == 1 ? f : 0f);
        text.setTypeface(light);
        text.setTextSize(20 * dp);
        text.setColor(0xCCFFFFFF);
        canvas.drawText(":", cx, wy + 7 * dp, text);

        // ---- in words
        long h = minutes / 60;
        long m = minutes % 60;
        String span = (h > 0 ? h + " h" : "") + (h > 0 && m > 0 ? " " : "") + (m > 0 || h == 0 ? m + " min" : "");
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(11 * dp);
        text.setColor(0xD9FFFFFF);
        float ty = wy + 2.5f * row + 16 * dp;
        canvas.drawText(t("Se oprește peste ", "Turns off in ") + span, cx, ty, text);
        text.setTextSize(9 * dp);
        text.setColor(0x80FFFFFF);
        canvas.drawText(t("↑ ↓ alegi   ← → ore / minute   OK pornește", "↑ ↓ choose   ← → hours / minutes   OK starts"),
                cx, ty + 14 * dp, text);
        removeCallbacks(tick);
        postDelayed(tick, 15_000); // the arc follows the time
    }

    private final Runnable tick = this::invalidate;

    /**
     * One wheel: the chosen number in the middle, large; its neighbours above and below,
     * smaller and fainter the further they are, as on a drum. `roll` is how far the wheel still
     * has to turn (it springs to 0), `dark` how much the middle number takes the focused look.
     */
    private void wheel(Canvas canvas, float x, float y, float row, int index, int count, int step, float roll,
                       float dark) {
        for (int i = -3; i <= 3; i++) {
            float d = i + roll;
            float a = Math.abs(d);
            if (a > 2.6f) {
                continue;
            }
            int value = ((index + i) % count + count) % count * step;
            float k = Math.min(1f, a);
            float size = (22f - 8f * k) * dp;
            float yy = y + d * row * (1f - 0.07f * a);
            int colour = mix(0xFFFFFFFF, 0xFF111111, dark * (1f - k));
            int alpha = Math.round(255 * Math.max(0f, 1f - 0.36f * a));
            text.setTypeface(a < 0.5f ? medium : light);
            text.setTextSize(size);
            text.setColor(colour);
            text.setAlpha(alpha);
            canvas.drawText(String.format(Locale.ROOT, "%02d", value), x, yy + size * 0.35f, text);
        }
    }

    private static String t(String ro, String en) {
        return "ro".equals(Locale.getDefault().getLanguage()) ? ro : en;
    }

    private static int mix(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int aa = a >>> 24;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int ba = b >>> 24;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
