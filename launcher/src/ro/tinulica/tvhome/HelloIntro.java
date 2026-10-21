package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

/**
 * "hello" when the TV comes on, written the way Cinema mode writes "activ".
 *
 * Behind it, the home screen's own wallpaper, frosted and a little lighter, a soft light coming
 * in from the left and a band of light passing; when it fades the home screen comes into
 * focus and arrives (MainActivity.arrive). In the middle, "hell" comes out of a blur in the
 * silver of Cinema mode's ring (white into light grey, flowing); then the "o" slides out from
 * behind the last "l" with a speed ramp (fast, a little past its place, settling) while the
 * word centres itself; a gleam passes over it and it leaves out of focus again.
 *
 * Kept light for this TV: the frosted wallpaper is drawn once (and blurred once); the light and
 * the band are separate panes that only move or fade; the word redraws a small area.
 * Its sound is assets/sounds/hello.ogg (tools/hello_sound.py). Shown by SourcesService.
 */
final class HelloIntro extends FrameLayout {

    /** The whole play, in milliseconds. */
    static final long LENGTH = 3800;
    /** When it starts to fade (and the home screen to arrive). */
    static final long LEAVING = LENGTH - 650;

    /** The home screen's wallpaper, blurred small (set by MainActivity), or null. */
    static volatile Bitmap wallpaper;

    private final float dp;
    private final Word word;
    private final View light;
    private final View band;
    private long start;

    HelloIntro(Context context, float dp) {
        super(context);
        this.dp = dp;
        setBackgroundColor(0xFF1B1D22);
        View back = new Back(context);
        addView(back, new LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 31) {
            back.setRenderEffect(RenderEffect.createBlurEffect(26 * dp, 26 * dp, Shader.TileMode.CLAMP));
        }
        light = new Glow(context, true);
        addView(light, new LayoutParams(-1, -1));
        band = new Glow(context, false);
        addView(band, new LayoutParams(-1, -1));
        word = new Word(context);
        addView(word, new LayoutParams(-1, -1));
    }

    /** Plays it; `leaving` runs as it starts to fade, `done` at the end (the caller removes the view). */
    void play(final Runnable leaving, final Runnable done) {
        start = SystemClock.uptimeMillis();
        setAlpha(0f);
        animate().alpha(1f).setDuration(420).start();
        // The light from the left comes in and drifts a little; the band passes once.
        light.setAlpha(0f);
        light.setTranslationX(-60 * dp);
        light.animate().alpha(1f).translationX(0).setInterpolator(new DecelerateInterpolator(1.6f))
                .setDuration(1600).start();
        post(() -> {
            float w = getWidth();
            band.setTranslationX(-0.75f * w);
            band.animate().translationX(0.75f * w).setInterpolator(new PathInterpolator(0.45f, 0f, 0.25f, 1f))
                    .setStartDelay(200).setDuration(2300).start();
        });
        word.postInvalidateOnAnimation();
        postDelayed(() -> {
            if (leaving != null) {
                leaving.run();
            }
            animate().alpha(0f).setDuration(650).withEndAction(done).start();
        }, LEAVING);
    }

    private float t() {
        return (SystemClock.uptimeMillis() - start) / 1000f;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float ease(float x) {
        x = clamp(x);
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    private static float easeOut(float v) {
        float k = 1f - clamp(v);
        return 1f - k * k * k;
    }

    /** A speed ramp: off fast, a little past its place, settling (a damped spring's step). */
    private static float ramp(float x) {
        if (x <= 0f) {
            return 0f;
        }
        if (x >= 1.4f) {
            return 1f;
        }
        return (float) (1.0 - Math.exp(-7.0 * x) * Math.cos(9.0 * x));
    }

    /** The wallpaper, frosted (blurred in front) and lighter; graphite if there is none. Drawn once. */
    private final class Back extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF full = new RectF();

        Back(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap wall = wallpaper;
            full.set(0, 0, getWidth(), getHeight());
            if (wall != null && !wall.isRecycled()) {
                c.drawBitmap(wall, null, full, paint);
                c.drawColor(0x29FFFFFF);
            } else {
                paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), 0xFF2E323A, 0xFF181A1F,
                        Shader.TileMode.CLAMP));
                c.drawRect(full, paint);
                paint.setShader(null);
            }
        }
    }

    /** The soft light from the left (`left`), or the tall band of light that passes. Drawn once. */
    private final class Glow extends View {
        private final boolean left;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        Glow(Context context, boolean left) {
            super(context);
            this.left = left;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (left) {
                paint.setShader(new RadialGradient(-0.04f * w, 0.5f * h, 0.66f * w, 0x5CFFFFFF, 0x00FFFFFF,
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new LinearGradient(0.5f * w - 0.2f * w, 0, 0.5f * w + 0.2f * w, 0,
                        new int[]{0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            }
            c.drawRect(0, 0, w, h, paint);
        }
    }

    /** "hello" in the middle, as Cinema mode writes "activ"; the "o" comes out from behind the "l". */
    private final class Word extends View {
        private static final String HELL = "hell";
        private static final String O = "o";
        private final Paint silver = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint soft = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix flow = new Matrix();
        private final RectF hellBox = new RectF();
        private final RectF wordBox = new RectF();
        private LinearGradient fill;
        private Bitmap blurHell;
        private Bitmap blurWord;
        private final float hellW;
        private final float allW;
        /** Where the "l" ends, from the start of "hell" (its ink, not its advance). */
        private final float lEnd;
        /** Where the ink of the "o" ends, from its own origin. */
        private final float oEnd;
        private float baseY;

        Word(Context context) {
            super(context);
            Typeface face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            for (Paint p : new Paint[]{silver, sheen}) {
                p.setTypeface(face);
                p.setTextSize(px(56));
                p.setLetterSpacing(-0.01f);
            }
            hellW = silver.measureText(HELL);
            allW = silver.measureText(HELL + O);
            android.graphics.Rect ink = new android.graphics.Rect();
            silver.getTextBounds(HELL, 0, HELL.length(), ink);
            lEnd = ink.right;
            silver.getTextBounds(O, 0, O.length(), ink);
            oEnd = ink.right;
            silver.setShadowLayer(px(10), 0, px(2), 0x40000000);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            Paint.FontMetrics fm = silver.getFontMetrics();
            baseY = h / 2f - (fm.ascent + fm.descent) / 2f;
            float finalX = (w - allW) / 2f;
            fill = new LinearGradient(finalX, 0, finalX + allW, 0, CinemaMark.COLOURS, null, Shader.TileMode.MIRROR);
            silver.setShader(fill);
            // The words as soft blurs, small and only around the word (drawn once in software,
            // where blur works), to sharpen from: "hell" where it first stands, "hello" at the end.
            blurHell = blurred((w - hellW) / 2f, HELL, hellBox);
            blurWord = blurred(finalX, HELL + O, wordBox);
        }

        private Bitmap blurred(float x, String text, RectF box) {
            float pad = px(48);
            Paint.FontMetrics fm = silver.getFontMetrics();
            box.set(x - pad, baseY + fm.ascent - pad, x + silver.measureText(text) + pad, baseY + fm.descent + pad);
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(box.width() / 2)), Math.max(1, Math.round(box.height() / 2)),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(0.5f, 0.5f);
            c.translate(-box.left, -box.top);
            Paint p = new Paint(silver);
            p.setShadowLayer(0, 0, 0, 0);
            p.setMaskFilter(new BlurMaskFilter(px(16), BlurMaskFilter.Blur.NORMAL));
            c.drawText(text, x, baseY, p);
            return b;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (fill == null) {
                return;
            }
            float t = t();
            float w = getWidth();
            float cy = getHeight() / 2f;
            float leave = ease((t - LEAVING / 1000f + 0.25f) / 0.7f);
            // "hell" out of the blur.
            float appear = clamp((t - 0.45f) / 0.3f);
            float focus = easeOut((t - 0.53f) / 0.7f);
            // The "o" out from behind the "l" (a mask at the end of the "l": none of it shows
            // before it), the word centring itself as it comes.
            float o = ramp((t - 1.05f) / 0.75f);
            float finalX = (w - allW) / 2f;
            float hellX = (w - hellW) / 2f + (finalX - (w - hellW) / 2f) * o;
            float mask = hellX + lEnd;
            float oX = (mask - oEnd) + (hellX + hellW - (mask - oEnd)) * o;
            float scale = 1.08f - 0.08f * focus + 0.05f * leave;
            canvas.save();
            canvas.scale(scale, scale, w / 2f, cy);
            flow.setTranslate(-(t * px(50)) % (allW * 2f), 0);
            fill.setLocalMatrix(flow);
            if (leave <= 0f) {
                if (appear > 0f) {
                    soft.setAlpha(Math.round(255 * appear * (1f - focus)));
                    canvas.save();
                    canvas.translate(hellX - (w - hellW) / 2f, 0);
                    canvas.drawBitmap(blurHell, null, hellBox, soft);
                    canvas.restore();
                    silver.setAlpha(Math.round(255 * appear * focus));
                    canvas.drawText(HELL, hellX, baseY, silver);
                }
                if (o > 0f) {
                    // Only what has come out from behind the "l" shows.
                    canvas.save();
                    canvas.clipRect(mask, 0, w, getHeight());
                    silver.setAlpha(255);
                    canvas.drawText(O, oX, baseY, silver);
                    canvas.restore();
                }
                // A band of light passing over the word once it is whole.
                float sweep = (t - 1.75f) / 0.75f;
                if (sweep > 0 && sweep < 1) {
                    float bx = finalX - px(50) + (allW + px(100)) * sweep;
                    sheen.setShader(new LinearGradient(bx - px(40), 0, bx + px(40), 0,
                            new int[]{0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                    canvas.drawText(HELL + O, finalX, baseY, sheen);
                }
            } else {
                // Leaving: back into the blur.
                soft.setAlpha(Math.round(255 * leave));
                canvas.drawBitmap(blurWord, null, wordBox, soft);
                silver.setAlpha(Math.round(255 * (1f - leave)));
                canvas.drawText(HELL + O, finalX, baseY, silver);
            }
            canvas.restore();
            if (t * 1000 < LENGTH) {
                postInvalidateOnAnimation();
            }
        }
    }
}
