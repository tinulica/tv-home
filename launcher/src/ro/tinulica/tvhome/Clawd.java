package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Locale;
import java.util.Random;

/**
 * Claude Code's mascot, Clawd, standing on the top edge of a message from Claude. The creature is
 * the 12 x 8 grid of its logo (as ClawdMoji, MIT, recovered it): a wide body, two eyes, arms out
 * at the sides, four legs.
 *
 *     ..########..
 *     ..#O####O#..
 *     ############
 *     ############
 *     ..########..
 *     ..########..
 *     ..#.#..#.#..
 *     ..#.#..#.#..
 *
 * It drops onto the glass once the message has flowed out, and what it does there depends on the
 * message, so it is seldom the same twice: it waves hello, hops with happy eyes when something is
 * done (or throws confetti), walks to and fro under a question mark while a question waits, holds
 * its arms up under an exclamation mark when it needs you, works under Claude's spinning mark,
 * sends a heart, or sleeps. Answered, it jumps; before the message leaves it hops off.
 */
final class Clawd extends View implements Choreographer.FrameCallback {

    enum Mood { HELLO, DONE, PARTY, QUESTION, ATTENTION, WORKING, LOVE, SLEEP }

    /** Clawd's colour, sampled from the logo. */
    static final int ORANGE = 0xFFDA7758;
    private static final int COLUMNS = 12;
    private static final int ROWS = 8;
    /** Above the head: room for a jump and for what floats over it. */
    private static final int HEADROOM = 10;

    private static final String[] QUESTION_MARK = {".###.", "#...#", "....#", "..##.", "..#..", ".....", "..#.."};
    private static final String[] BANG = {"#", "#", "#", "#", "#", ".", "#"};
    private static final String[] HEART = {".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."};
    private static final String[] ZED = {"####", "..#.", ".#..", "####"};
    private static final String[] SPARK = {".#.", "###", ".#."};
    private static final int[] CONFETTI = {0xFFDA7758, 0xFFFBE3D5, 0xFFFFFFFF, 0xFFF0A58A, 0xFFFFD27A};
    private static Mood lastMood;

    private final float dp;
    private final float cell;
    private final Mood mood;
    private final Paint body = new Paint();
    private final Paint ink = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path markPath = ClaudeCard.svg(ClaudeCard.MARK_PATH);
    private final Path drawnMark = new Path();
    private final Matrix fit = new Matrix();
    private final long born = SystemClock.uptimeMillis();
    private final Random random = new Random();
    private final float[] confetti = new float[5 * 14];   // x, y, speed, colour, spin per piece
    private final Spring drop;
    private boolean walking;
    private boolean running;
    private float x;              // where it stands along the edge (0..1 of the free width)
    private float dir;
    private float jumpAt = -10f;
    private float leftAt = -10f;
    private float arrivedAt = 1e9f;
    private float pauseUntil;
    private long last;

    Clawd(Context context, float dp, Mood mood) {
        super(context);
        this.dp = dp;
        this.mood = mood;
        cell = 4 * dp;
        body.setColor(ORANGE);
        ink.setColor(0xFF000000);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFF000000);
        mark.setColor(ClaudeCard.CORAL);
        walking = mood == Mood.QUESTION;
        x = 0.1f + random.nextFloat() * 0.8f;
        dir = random.nextBoolean() ? 1f : -1f;
        for (int i = 0; i < confetti.length; i += 5) {
            confetti[i] = random.nextFloat();
            confetti[i + 1] = -random.nextFloat();
            confetti[i + 2] = 0.35f + random.nextFloat() * 0.4f;
            confetti[i + 3] = random.nextInt(CONFETTI.length);
            confetti[i + 4] = random.nextFloat() * 6f;
        }
        setAlpha(0f);
        drop = new Spring(0f, 0.002f, v -> {
            setAlpha(Math.max(0f, Math.min(1f, v * 2.5f)));
            setTranslationY(-16 * dp * (1f - v));
        });
    }

    /**
     * What Clawd does for a message: the one asked for ("mood"), else read from the words, else
     * whatever it did not do last time.
     */
    static Mood moodFor(String asked, String title, String text, boolean question) {
        Mood m = null;
        if (asked != null) {
            try {
                m = Mood.valueOf(asked.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Read from the words.
            }
        }
        String w = ((title == null ? "" : title) + " " + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
        if (m == null && question) {
            m = has(w, "aprob", "permisiune", "approve", "permission", "urgent") ? Mood.ATTENTION : Mood.QUESTION;
        }
        if (m == null) {
            if (has(w, "eroare", "nu am putut", "nu merge", "problem", "atenț", "atent", "error", "fail",
                    "ai nevoie", "am nevoie", "trebuie să", "need")) {
                m = Mood.ATTENTION;
            } else if (has(w, "mulțum", "multum", "thank", "❤", "♥")) {
                m = Mood.LOVE;
            } else if (has(w, "noapte bună", "somn", "good night", "sleep", "pauză", "pauza")) {
                m = Mood.SLEEP;
            } else if (has(w, "lucrez", "în lucru", "in lucru", "pornesc", "încep", "incep", "generez",
                    "construiesc", "working", "building", "starting", "în curs", "durează")) {
                m = Mood.WORKING;
            } else if (has(w, "gata", "terminat", "am terminat", "reușit", "reusit", "done", "finished",
                    "complet", "✓", "e pe televizor", "rulează", "merge", "instalat")) {
                m = Mood.DONE;
            } else if (has(w, "salut", "bună", "buna ", "hello", "hi ", "hey")) {
                m = Mood.HELLO;
            }
        }
        if (m == null) {
            Mood[] any = {Mood.HELLO, Mood.DONE, Mood.WORKING, Mood.PARTY};
            m = any[new Random().nextInt(any.length)];
        }
        // Not the same twice in a row when there is a near enough other.
        if (m == lastMood) {
            if (m == Mood.DONE) {
                m = Mood.PARTY;
            } else if (m == Mood.PARTY) {
                m = Mood.DONE;
            } else if (m == Mood.HELLO) {
                m = Mood.LOVE;
            }
        }
        lastMood = m;
        return m;
    }

    private static boolean has(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Lands on the glass after `delay` ms, with a little bounce. */
    void arrive(long delay) {
        postDelayed(() -> {
            arrivedAt = t();
            drop.to(1f, 0.42f, 0.55f);
        }, delay);
    }

    void jump() {
        walking = false;
        jumpAt = t();
    }

    /** Hops off upwards and fades, before the message flows away. */
    void leave() {
        walking = false;
        leftAt = t();
        animate().alpha(0f).setStartDelay(140).setDuration(220).start();
    }

    /** How tall its view needs to be (the feet at the bottom). */
    static int viewHeight(float dp) {
        return Math.round((ROWS + HEADROOM) * 4 * dp);
    }

    private float t() {
        return (SystemClock.uptimeMillis() - born) / 1000f;
    }

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
        long ms = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (ms - last) / 1000f);
        last = ms;
        float t = t();
        if (walking && t > pauseUntil) {
            x += dir * dt * 0.1f;
            if (x > 0.96f || x < 0.03f) {
                dir = -dir;
                x = Math.max(0.03f, Math.min(0.96f, x));
                pauseUntil = t + 1.1f;   // stops at the end and looks at you
            }
        }
        for (int i = 0; i < confetti.length; i += 5) {
            confetti[i + 1] += dt * confetti[i + 2];
            if (confetti[i + 1] > 1f) {
                confetti[i] = random.nextFloat();
                confetti[i + 1] = 0f;
            }
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = t();
        float here = t - arrivedAt;                       // since it landed
        boolean stepping = walking && t > pauseUntil;
        int frame = (int) (t * 6) % 2;
        float lift = 0;
        // Hops: on landing for DONE (two) and PARTY (all the time), when answered, when leaving.
        if (mood == Mood.DONE && here >= 0 && here < 1.1f) {
            lift += hop(here % 0.55f, 0.55f) * cell * 2.2f;
        } else if (mood == Mood.PARTY && here >= 0) {
            lift += hop(here % 0.7f, 0.5f) * cell * 2f;
        }
        float j = t - jumpAt;
        if (j >= 0 && j < 0.55f) {
            lift += hop(j, 0.55f) * cell * 2.6f;
        }
        float l = t - leftAt;
        if (l >= 0) {
            lift += Math.min(1f, l / 0.35f) * cell * 3f;
        }
        if (stepping) {
            lift += frame == 0 ? 0 : cell * 0.25f;
        }
        float shake = 0;
        if (mood == Mood.ATTENTION && here >= 0 && (here % 1.6f) < 0.36f) {
            shake = (float) Math.sin(here * 60) * cell * 0.35f;
        }
        float sway = mood == Mood.LOVE ? (float) Math.sin(t * 2.2f) * cell * 0.4f : 0;
        float breathe = mood == Mood.SLEEP ? (float) (0.5 + 0.5 * Math.sin(t * 1.6)) * cell * 0.35f : 0;

        float left = x * Math.max(0, getWidth() - COLUMNS * cell) + shake + sway;
        float feet = getHeight();
        float top = feet - ROWS * cell - lift;
        float legTop = feet - 2 * cell - lift;
        float bodyTop = top - breathe;

        // Body: columns 2..9 from row 0 to row 5; arms rows 2..3 at columns 0..1 and 10..11.
        c.drawRect(left + 2 * cell, bodyTop, left + 10 * cell, legTop, body);
        boolean happy = mood == Mood.DONE || mood == Mood.PARTY || mood == Mood.LOVE || (j >= 0 && j < 1.4f);
        float armY = top + 2 * cell;
        float leftUp = 0;
        float rightUp = 0;
        if (mood == Mood.ATTENTION || mood == Mood.PARTY) {
            float beat = (int) (t * (mood == Mood.PARTY ? 5 : 3)) % 2;
            leftUp = cell * (1.5f + 0.5f * beat);
            rightUp = cell * (2f - 0.5f * beat);
        } else if (mood == Mood.HELLO && here >= 0 && (here < 2.2f || (here % 5f) < 1.2f)) {
            rightUp = (int) (t * 7) % 2 == 0 ? cell * 1.6f : cell * 0.6f;
        } else if (j >= 0 && j < 1.4f) {
            leftUp = (int) (j * 7) % 2 == 0 ? cell * 1.2f : 0;
            rightUp = cell * 1.2f - leftUp;
        }
        c.drawRect(left, armY - leftUp, left + 2 * cell, armY - leftUp + 2 * cell, body);
        c.drawRect(left + 10 * cell, armY - rightUp, left + 12 * cell, armY - rightUp + 2 * cell, body);

        // Eyes: the cells (3, 1) and (8, 1).
        float ey = top + cell;
        float look = 0;
        if (stepping) {
            look = dir * cell * 0.25f;
        } else if (mood == Mood.QUESTION) {
            look = (float) Math.sin(t * 1.3f) * cell * 0.25f;
        }
        if (mood == Mood.SLEEP) {
            shutEye(c, left + 3 * cell, ey);
            shutEye(c, left + 8 * cell, ey);
        } else if (happy) {
            happyEye(c, left + 3 * cell, ey);
            happyEye(c, left + 8 * cell, ey);
        } else {
            boolean blink = (t % 3.7f) < 0.12f;
            float h = blink ? cell * 0.25f : mood == Mood.ATTENTION ? cell * 1.35f : cell;
            float down = mood == Mood.WORKING ? cell * 0.3f : 0;
            float y = ey + (cell - h) / 2f + down;
            c.drawRect(left + 3 * cell + look, y, left + 4 * cell + look, y + h, ink);
            c.drawRect(left + 8 * cell + look, y, left + 9 * cell + look, y + h, ink);
        }

        // Legs: columns 2, 4, 7 and 9, two rows; walking, the pairs step in turn; working, they tap.
        float upA = 0;
        float upB = 0;
        if (stepping) {
            upA = frame == 0 ? cell * 0.6f : 0;
            upB = frame == 1 ? cell * 0.6f : 0;
        } else if (mood == Mood.WORKING) {
            upB = (int) (t * 4) % 2 == 0 ? cell * 0.5f : 0;
        }
        c.drawRect(left + 2 * cell, legTop, left + 3 * cell, feet - lift - upA, body);
        c.drawRect(left + 4 * cell, legTop, left + 5 * cell, feet - lift - upB, body);
        c.drawRect(left + 7 * cell, legTop, left + 8 * cell, feet - lift - upA, body);
        c.drawRect(left + 9 * cell, legTop, left + 10 * cell, feet - lift - upB, body);

        // What floats over the head.
        float cx = left + 6 * cell;
        float above = bodyTop - 2 * cell;
        float g = 2 * dp;                                  // a pixel of the small signs
        float bob = (float) Math.sin(t * 3f) * dp * 1.5f;
        switch (mood) {
            case QUESTION:
                sign(c, QUESTION_MARK, cx, above + bob, g, 0xFFFFFFFF, 1f);
                break;
            case ATTENTION:
                sign(c, BANG, cx, above + bob, g, 0xFFFFD27A, (t % 0.9f) < 0.6f ? 1f : 0.35f);
                break;
            case LOVE: {
                float beat = 1f + 0.12f * (float) Math.max(0, Math.sin(t * 7));
                sign(c, HEART, cx, above + bob, g * beat, 0xFFFF7B8A, 1f);
                break;
            }
            case SLEEP:
                for (int k = 0; k < 3; k++) {
                    float p = ((t * 0.45f) + k / 3f) % 1f;
                    sign(c, ZED, cx + cell * (1.5f + 2.5f * p), above - p * cell * 5, g * (0.7f + 0.5f * p),
                            0xFFFFFFFF, 1f - p);
                }
                break;
            case WORKING: {
                float s = 15 * dp * (1f + 0.08f * (float) Math.sin(t * 5));
                fit.setScale(s / 100f, s / 100f);
                fit.postTranslate(-s / 2f, -s / 2f);
                fit.postRotate((t * 160f) % 360f);
                fit.postTranslate(cx, above - s / 2f + bob);
                markPath.transform(fit, drawnMark);
                c.drawPath(drawnMark, mark);
                break;
            }
            case DONE: {
                // Four glints round the head, each twinkling in its turn.
                float[][] at = {{-1f, 1f}, {13f, 1.5f}, {0.5f, -2.5f}, {11.5f, -2f}};
                for (int k = 0; k < at.length; k++) {
                    float p = (t * 1.2f + k * 0.27f) % 1f;
                    sign(c, SPARK, left + at[k][0] * cell, top + at[k][1] * cell, g, 0xFFFFE6A8,
                            (float) Math.sin(p * Math.PI));
                }
                break;
            }
            case PARTY:
                for (int i = 0; i < confetti.length; i += 5) {
                    float px = confetti[i] * getWidth();
                    float py = confetti[i + 1] * (getHeight() - cell);
                    ink.setColor(CONFETTI[(int) confetti[i + 3]]);
                    float w = (float) Math.abs(Math.cos(t * 4 + confetti[i + 4])) * g * 1.5f + g * 0.4f;
                    c.drawRect(px - w / 2, py, px + w / 2, py + g * 1.5f, ink);
                }
                ink.setColor(0xFF000000);
                break;
            default:
                break;
        }
    }

    /** 0..1..0 over `length` seconds: a hop. */
    private static float hop(float at, float length) {
        if (at < 0 || at > length) {
            return 0;
        }
        return (float) Math.sin(at / length * Math.PI);
    }

    /** A happy eye: "^" filling the eye's cell. */
    private void happyEye(Canvas c, float l, float t) {
        line.setStrokeWidth(Math.max(1.5f, cell * 0.36f));
        Path p = new Path();
        p.moveTo(l - cell * 0.1f, t + cell * 0.85f);
        p.lineTo(l + cell * 0.5f, t + cell * 0.2f);
        p.lineTo(l + cell * 1.1f, t + cell * 0.85f);
        c.drawPath(p, line);
    }

    /** A sleeping eye: a lid curving down, in the eye's cell. */
    private void shutEye(Canvas c, float l, float t) {
        line.setStrokeWidth(Math.max(1.5f, cell * 0.32f));
        Path p = new Path();
        p.moveTo(l - cell * 0.1f, t + cell * 0.35f);
        p.quadTo(l + cell * 0.5f, t + cell * 1.05f, l + cell * 1.1f, t + cell * 0.35f);
        c.drawPath(p, line);
    }

    /** A small pixel sign, its bottom centred at (cx, bottom). */
    private void sign(Canvas c, String[] rows, float cx, float bottom, float g, int color, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        int w = rows[0].length();
        float left = cx - w * g / 2f;
        float top = bottom - rows.length * g;
        ink.setColor(color);
        ink.setAlpha(Math.round(Math.max(0f, Math.min(1f, alpha)) * (color >>> 24)));
        for (int r = 0; r < rows.length; r++) {
            for (int k = 0; k < w; k++) {
                if (rows[r].charAt(k) == '#') {
                    c.drawRect(left + k * g, top + r * g, left + (k + 1) * g, top + (r + 1) * g, ink);
                }
            }
        }
        ink.setColor(0xFF000000);
    }
}
