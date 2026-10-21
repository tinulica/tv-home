package ro.tinulica.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/** What is seen through the notice drops that are not about the weather (see WeatherCard.Scene). */
final class DropScenes {

    private DropScenes() {
    }

    /** An app's notice: the app's own colour glowing out of the edge, breathing slowly. */
    static final class AppGlow implements LiquidDrop.Backdrop {
        private final int colour;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private float t;
        private float w;
        private float h;

        AppGlow(Drawable icon) {
            colour = vivid(icon);
        }

        @Override
        public void step(float dt, float width, float height) {
            t += dt;
            w = width;
            h = height;
        }

        @Override
        public void draw(Canvas c, float left, float top) {
            if (w <= 0 || h <= 0) {
                return;
            }
            float breathe = 0.8f + 0.2f * (float) Math.sin(t * 1.3f);
            int rgb = colour & 0xFFFFFF;
            paint.setShader(new RadialGradient(left + h * 0.55f, top + h * 0.5f, h * 2.4f,
                    new int[]{(Math.round(0x70 * breathe) << 24) | rgb, (Math.round(0x26 * breathe) << 24) | rgb, rgb},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(left, top, left + w, top + h, paint);
            // A second, fainter light drifting slowly along the glass.
            float x = left + w * (0.62f + 0.1f * (float) Math.sin(t * 0.45f));
            float y = top + h * (0.2f + 0.15f * (float) Math.cos(t * 0.6f));
            paint.setShader(new RadialGradient(x, y, h * 1.3f, (0x2E << 24) | rgb, rgb, Shader.TileMode.CLAMP));
            c.drawRect(left, top, left + w, top + h, paint);
            paint.setShader(null);
        }

        /** The icon's most vivid colour (a soft grey when it has none). */
        private static int vivid(Drawable icon) {
            if (icon == null) {
                return 0xFF8E8E93;
            }
            try {
                Bitmap small = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(small);
                icon.setBounds(0, 0, 12, 12);
                icon.draw(canvas);
                float best = 0.18f;
                int pick = 0xFF8E8E93;
                float[] hsv = new float[3];
                for (int y = 0; y < 12; y++) {
                    for (int x = 0; x < 12; x++) {
                        int p = small.getPixel(x, y);
                        if ((p >>> 24) < 160) {
                            continue;
                        }
                        android.graphics.Color.colorToHSV(p, hsv);
                        float score = hsv[1] * hsv[2];
                        if (score > best) {
                            best = score;
                            hsv[2] = Math.max(hsv[2], 0.85f);
                            pick = android.graphics.Color.HSVToColor(hsv);
                        }
                    }
                }
                small.recycle();
                return pick;
            } catch (RuntimeException e) {
                return 0xFF8E8E93;
            }
        }
    }

    /**
     * Cinema mode: a room with a projector's soft white beam and dust drifting in it; switching
     * on, the room darkens (the lights go down), switching off it brightens again.
     */
    static final class Projector implements LiquidDrop.Backdrop {
        private final boolean dimming;
        private final float dp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Path beam = new Path();
        private final float[] dust = new float[18 * 3];
        private final Random random = new Random();
        private float t;
        private float w;
        private float h;

        Projector(boolean dimming, float dp) {
            this.dimming = dimming;
            this.dp = dp;
            for (int i = 0; i < dust.length; i += 3) {
                dust[i] = random.nextFloat();
                dust[i + 1] = random.nextFloat();
                dust[i + 2] = 0.4f + random.nextFloat() * 0.8f;
            }
        }

        @Override
        public void step(float dt, float width, float height) {
            t += dt;
            w = width;
            h = height;
            for (int i = 0; i < dust.length; i += 3) {
                dust[i] -= dt * 0.03f * dust[i + 2];
                dust[i + 1] += dt * 0.05f * (float) Math.sin(t * 0.7f + i);
                if (dust[i] < 0f) {
                    dust[i] += 1f;
                }
            }
        }

        @Override
        public void draw(Canvas c, float left, float top) {
            if (w <= 0 || h <= 0) {
                return;
            }
            float p = Math.min(1f, t / 2f);
            p = p * p * (3f - 2f * p);
            float darkness = dimming ? 0.12f + 0.5f * p : 0.62f - 0.5f * p;
            paint.setShader(null);
            paint.setColor(Math.round(255 * darkness) << 24);
            c.drawRect(left, top, left + w, top + h, paint);
            // The beam: from above the right end, widening to the left.
            float flicker = 0.93f + 0.07f * (float) (Math.sin(t * 21f) * Math.sin(t * 6.3f));
            float light = (dimming ? 0.45f + 0.55f * p : 1f - 0.6f * p) * flicker;
            beam.reset();
            beam.moveTo(left + w * 0.98f, top - h * 0.1f);
            beam.lineTo(left + w * 1.02f, top + h * 0.12f);
            beam.lineTo(left + w * 0.18f, top + h * 1.15f);
            beam.lineTo(left + w * 0.02f, top + h * 0.35f);
            beam.close();
            paint.setShader(new LinearGradient(left + w, top, left + w * 0.1f, top + h * 0.8f,
                    (Math.round(0x47 * light) << 24) | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawPath(beam, paint);
            paint.setShader(null);
            for (int i = 0; i < dust.length; i += 3) {
                float x = left + dust[i] * w;
                float y = top + (dust[i + 1] - (float) Math.floor(dust[i + 1])) * h;
                paint.setColor((Math.round(0x8C * light * (0.5f + 0.5f * dust[i])) << 24) | 0xFFFFFF);
                c.drawCircle(x, y, 0.7f * dp * dust[i + 2], paint);
            }
        }
    }
}
