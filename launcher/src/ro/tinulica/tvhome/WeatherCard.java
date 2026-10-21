package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * What a weather notice says (the temperature, the sky, the place), and the weather itself as a
 * scene seen through its Liquid Glass (LiquidDrop draws it behind the frosted surface): rain
 * streaks past and drops slide down the glass, lightning lights it up twice, the sun glows in
 * from the corner with slow beams, snow drifts, clouds and fog pass, wind flows. A warning
 * tints the glass where it clings to the edge with its colour, breathing.
 */
final class WeatherCard extends LinearLayout {

    static final int SUN = 0;
    static final int MOON = 1;
    static final int PARTLY = 2;
    static final int CLOUDS = 3;
    static final int FOG = 4;
    static final int DRIZZLE = 5;
    static final int RAIN = 6;
    static final int SNOW = 7;
    static final int STORM = 8;
    static final int WIND = 9;
    static final int HEAT = 10;

    private final float dp;

    /** `big` is the temperature (or null for a warning). */
    WeatherCard(Context context, float dp, String big, String title, String detail) {
        super(context);
        this.dp = dp;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(px(18), 0, px(16), 0);
        if (big != null) {
            TextView temp = text(24, "sans-serif-light");
            temp.setText(big);
            LayoutParams tlp = new LayoutParams(-2, -2);
            tlp.rightMargin = px(10);
            addView(temp, tlp);
        }
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(VERTICAL);
        TextView head = text(12.5f, "sans-serif-medium");
        head.setText(title);
        words.addView(head);
        if (detail != null && !detail.isEmpty()) {
            TextView body = text(10.5f, "sans-serif");
            body.setText(detail);
            body.setAlpha(0.85f);
            LayoutParams blp = new LayoutParams(-1, -2);
            blp.topMargin = px(2);
            words.addView(body, blp);
        }
        addView(words, new LayoutParams(0, -2, 1f));
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(float sp, String family) {
        TextView t = new TextView(getContext());
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(px(4), 0, px(1), 0x80000000);
        return t;
    }

    /** The weather behind the glass, drawn in the capsule's box every frame. */
    static final class Scene implements LiquidDrop.Backdrop {
        private final int sky;
        private final boolean day;
        private final int accent;
        private final float dp;
        private final Random random = new Random();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float w;
        private float h;
        private float t;
        // Streaks (rain, snow, stars) and drops on the glass.
        private float[] sx = new float[0];
        private float[] sy;
        private float[] sv;
        private float[] ss;
        private float[] sp;
        private float[] dx = new float[0];
        private float[] dy;
        private float[] dv;
        private float[] dr;
        private float flash;
        private float nextStrike = 0.7f;
        private float boltUntil;
        private final float[] bolt = new float[14];

        Scene(int sky, boolean day, int accent, float dp) {
            this.sky = sky;
            this.day = day;
            this.accent = accent;
            this.dp = dp;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
        }

        private float px(float v) {
            return v * dp;
        }

        private void setUp(float width, float height) {
            w = width;
            h = height;
            int n = sky == RAIN ? 34 : sky == STORM ? 40 : sky == DRIZZLE ? 18 : sky == SNOW ? 28
                    : sky == MOON ? 14 : sky == WIND ? 6 : 0;
            sx = new float[n];
            sy = new float[n];
            sv = new float[n];
            ss = new float[n];
            sp = new float[n];
            for (int i = 0; i < n; i++) {
                sx[i] = random.nextFloat() * w;
                sy[i] = random.nextFloat() * h;
                sp[i] = random.nextFloat() * 6.28f;
                if (sky == SNOW) {
                    boolean near = i % 3 == 0;
                    sv[i] = px(near ? 26 + random.nextFloat() * 14 : 10 + random.nextFloat() * 10);
                    ss[i] = px(near ? 2.4f + random.nextFloat() * 1.4f : 0.9f + random.nextFloat() * 0.7f);
                } else if (sky == MOON) {
                    ss[i] = px(0.6f + random.nextFloat() * 0.9f);
                    sv[i] = 1f + random.nextFloat() * 2.2f;
                } else if (sky == WIND) {
                    sy[i] = h * (0.2f + 0.6f * random.nextFloat());
                    sv[i] = px(110 + random.nextFloat() * 90);
                    ss[i] = px(50 + random.nextFloat() * 60);
                } else {
                    boolean light = sky == DRIZZLE;
                    sv[i] = px((light ? 180 : 300) + random.nextFloat() * 140);
                    ss[i] = px((light ? 7 : 12) + random.nextFloat() * 10);
                }
            }
            // Drops resting on the glass, sliding down now and then.
            int d = sky == RAIN || sky == STORM ? 8 : sky == DRIZZLE ? 5 : 0;
            dx = new float[d];
            dy = new float[d];
            dv = new float[d];
            dr = new float[d];
            for (int i = 0; i < d; i++) {
                dx[i] = random.nextFloat() * w;
                dy[i] = random.nextFloat() * h;
                dr[i] = px(1.4f + random.nextFloat() * 1.8f);
                dv[i] = random.nextFloat() < 0.4f ? px(4 + random.nextFloat() * 10) : 0;
            }
        }

        @Override
        public void step(float dt, float width, float height) {
            if (width != w || height != h || (sx.length == 0 && dx.length == 0 && w == 0)) {
                setUp(width, height);
            }
            t += dt;
            for (int i = 0; i < sx.length; i++) {
                if (sky == SNOW) {
                    sy[i] += sv[i] * dt;
                    if (sy[i] - ss[i] > h) {
                        sy[i] = -ss[i];
                        sx[i] = random.nextFloat() * w;
                    }
                } else if (sky == WIND) {
                    sx[i] += sv[i] * dt;
                    if (sx[i] - ss[i] > w) {
                        sx[i] = -random.nextFloat() * px(40);
                        sy[i] = h * (0.2f + 0.6f * random.nextFloat());
                    }
                } else if (sky != MOON) {
                    sy[i] += sv[i] * dt;
                    sx[i] += sv[i] * dt * 0.18f;
                    if (sy[i] - ss[i] > h) {
                        sy[i] = -random.nextFloat() * px(14);
                        sx[i] = random.nextFloat() * (w + px(20)) - px(20);
                    }
                }
            }
            for (int i = 0; i < dx.length; i++) {
                dy[i] += dv[i] * dt;
                if (dy[i] - dr[i] > h) {
                    dy[i] = -dr[i];
                    dx[i] = random.nextFloat() * w;
                }
                if (random.nextFloat() < dt * 0.25f) {
                    dv[i] = dv[i] > 0 ? 0 : px(5 + random.nextFloat() * 12);
                }
            }
            if (sky == STORM) {
                flash = Math.max(0f, flash - dt * 3.4f);
                if (t >= nextStrike) {
                    flash = 0.7f;
                    boltUntil = t + 0.26f;
                    float bx = w * (0.4f + 0.45f * random.nextFloat());
                    float by = -px(4);
                    for (int i = 0; i < 7; i++) {
                        bolt[i * 2] = bx;
                        bolt[i * 2 + 1] = by;
                        bx += (random.nextFloat() - 0.5f) * px(16);
                        by += h / 6f;
                    }
                    nextStrike = t + 2f + random.nextFloat() * 2.6f;
                } else if (flash < 0.25f && flash > 0.18f && boltUntil > t - 0.1f) {
                    flash = 0.95f; // the second, brighter flash
                }
            }
        }

        @Override
        public void draw(Canvas c, float left, float top) {
            c.save();
            c.translate(left, top);
            wash(c);
            switch (sky) {
                case SUN:
                    sunlight(c, 1f);
                    break;
                case HEAT:
                    sunlight(c, 1.3f);
                    shimmer(c);
                    break;
                case MOON:
                    night(c);
                    break;
                case PARTLY:
                    if (day) {
                        sunlight(c, 0.7f);
                    } else {
                        night(c);
                    }
                    clouds(c, 2, 0x47);
                    break;
                case CLOUDS:
                    clouds(c, 3, 0x40);
                    break;
                case FOG:
                    fog(c);
                    break;
                case DRIZZLE:
                case RAIN:
                    clouds(c, 2, 0x2E);
                    rain(c, sky == DRIZZLE ? 0x47 : 0x5C);
                    drops(c);
                    break;
                case STORM:
                    clouds(c, 3, 0x33);
                    rain(c, 0x66);
                    lightning(c);
                    drops(c);
                    break;
                case SNOW:
                    clouds(c, 2, 0x2E);
                    snow(c);
                    break;
                case WIND:
                    clouds(c, 2, 0x38);
                    wind(c);
                    break;
                default:
                    break;
            }
            if (accent != 0) {
                float k = 0.55f + 0.3f * (float) Math.sin(t * 2.6f);
                paint.setShader(new LinearGradient(0, 0, w * 0.6f, 0,
                        (Math.round(170 * k) << 24) | (accent & 0xFFFFFF), accent & 0xFFFFFF, Shader.TileMode.CLAMP));
                c.drawRect(0, -px(30), w * 0.6f, h + px(30), paint);
                paint.setShader(null);
            }
            c.restore();
        }

        /** The sky's colour, seen through frosted glass. */
        private void wash(Canvas c) {
            int a;
            int b;
            switch (sky) {
                case SUN: a = 0x663D8DEB; b = 0x1F8CC8FF; break;
                case HEAT: a = 0x73FF7A3C; b = 0x26FFB15C; break;
                case MOON: a = 0x800B1C45; b = 0x4D243C78; break;
                case PARTLY: a = day ? 0x5C4C86C8 : 0x80152648; b = 0x268AAED4; break;
                case FOG: a = 0x599AA3AE; b = 0x33C2C8CF; break;
                case SNOW: a = 0x598FA4BC; b = 0x2EC6D3E0; break;
                case STORM: a = 0x8C1E1C30; b = 0x4D3A3856; break;
                case RAIN: case DRIZZLE: a = 0x7333465C; b = 0x335A6C82; break;
                default: a = 0x595D748E; b = 0x2E8FA4BA; break;
            }
            paint.setShader(new LinearGradient(0, 0, w, h, a, b, Shader.TileMode.CLAMP));
            c.drawRect(-px(20), -px(30), w, h + px(30), paint);
            paint.setShader(null);
        }

        /** Warm light pouring in from the top right corner, with slow beams and a few sparkles. */
        private void sunlight(Canvas c, float strength) {
            float cx = w * 0.94f;
            float cy = -h * 0.25f;
            float r = h * 2.2f * (1f + 0.04f * (float) Math.sin(t * 1.3f));
            paint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{(Math.round(210 * Math.min(1f, strength)) << 24) | 0xFFE6A0, 0x4DFFC864, 0x00FFC864},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
            // The sun itself, soft through the frost, half out of the corner.
            float sunR = h * 0.42f;
            float sunX = w * 0.9f;
            float sunY = h * 0.12f;
            paint.setShader(new RadialGradient(sunX, sunY, sunR * 1.6f,
                    new int[]{0xF2FFFBEA, 0xCCFFE08A, 0x00FFD060}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(sunX, sunY, sunR * 1.6f, paint);
            for (int i = 0; i < 3; i++) {
                double a = Math.toRadians(150 + i * 16 + 5 * Math.sin(t * 0.5f + i));
                float len = w * 0.9f;
                float spread = (float) Math.toRadians(3.5f);
                path.reset();
                path.moveTo(cx, cy);
                path.lineTo(cx + (float) Math.cos(a - spread) * len, cy + (float) Math.sin(a - spread) * len);
                path.lineTo(cx + (float) Math.cos(a + spread) * len, cy + (float) Math.sin(a + spread) * len);
                path.close();
                paint.setShader(new RadialGradient(cx, cy, len, 0x73FFF2C8, 0x00FFF2C8, Shader.TileMode.CLAMP));
                c.drawPath(path, paint);
            }
            paint.setShader(null);
            for (int i = 0; i < 4; i++) {
                float k = Math.abs((float) Math.sin(t * (0.9f + i * 0.4f) + i * 1.7f));
                paint.setColor((Math.round(200 * k) << 24) | 0xFFF6DC);
                float x = w * (0.55f + 0.1f * i) + px(6) * (float) Math.sin(t * 0.7f + i);
                float y = h * (0.25f + 0.17f * (i % 3));
                c.drawCircle(x, y, px(1.1f + 0.6f * k), paint);
            }
        }

        private void shimmer(Canvas c) {
            line.setShader(null);
            line.setPathEffect(null);
            line.setStrokeWidth(px(1.2f));
            line.setColor(0x33FFFFFF);
            for (int i = 0; i < 3; i++) {
                float base = h * (0.55f + 0.15f * i);
                path.reset();
                for (int k = 0; k <= 20; k++) {
                    float x = w * k / 20f;
                    float y = base + (float) Math.sin(k * 0.9f + t * 3.2f + i) * px(2);
                    if (k == 0) {
                        path.moveTo(x, y);
                    } else {
                        path.lineTo(x, y);
                    }
                }
                c.drawPath(path, line);
            }
        }

        private void night(Canvas c) {
            float cx = w * 0.9f;
            float cy = h * 0.1f;
            paint.setShader(new RadialGradient(cx, cy, h * 1.4f, 0x4DDCE4FF, 0x00DCE4FF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, h * 1.4f, paint);
            paint.setShader(null);
            for (int i = 0; i < sx.length; i++) {
                float k = 0.2f + 0.8f * Math.abs((float) Math.sin(t * sv[i] + sp[i]));
                paint.setColor((Math.round(235 * k) << 24) | 0xFFFFFF);
                c.drawCircle(sx[i], sy[i], ss[i], paint);
            }
        }

        /** Soft clouds drifting by behind the glass. */
        private void clouds(Canvas c, int count, int alpha) {
            for (int i = 0; i < count; i++) {
                float s = h * (0.55f + 0.18f * i);
                float span = w + s * 3;
                float x = ((t * px(5 + 4 * i) + i * span / count) % span) - s * 1.5f;
                float y = h * (0.15f + 0.35f * (i % 2));
                for (int k = 0; k < 3; k++) {
                    float bx = x + (k - 1) * s * 0.55f;
                    float by = y + (k == 1 ? -s * 0.12f : s * 0.06f);
                    float r = s * (k == 1 ? 0.62f : 0.48f);
                    paint.setShader(new RadialGradient(bx, by, r, (alpha << 24) | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawCircle(bx, by, r, paint);
                }
            }
            paint.setShader(null);
        }

        /** Rain behind the glass: faint, fast streaks. */
        private void rain(Canvas c, int alpha) {
            line.setPathEffect(null);
            line.setStrokeWidth(px(1.1f));
            for (int i = 0; i < sx.length; i++) {
                float x1 = sx[i] - ss[i] * 0.18f;
                float y1 = sy[i] - ss[i];
                line.setShader(new LinearGradient(x1, y1, sx[i], sy[i], 0x00FFFFFF, (alpha << 24) | 0xE6EEFF,
                        Shader.TileMode.CLAMP));
                c.drawLine(x1, y1, sx[i], sy[i], line);
            }
            line.setShader(null);
        }

        /** Drops on the glass itself: a bead of light, lit on top, a shade underneath. */
        private void drops(Canvas c) {
            for (int i = 0; i < dx.length; i++) {
                float r = dr[i];
                if (dv[i] > 0) {
                    // A sliding drop leaves a faint trail.
                    line.setShader(new LinearGradient(dx[i], dy[i] - px(14), dx[i], dy[i], 0x00FFFFFF, 0x26FFFFFF,
                            Shader.TileMode.CLAMP));
                    line.setStrokeWidth(r * 0.9f);
                    c.drawLine(dx[i], dy[i] - px(14), dx[i], dy[i], line);
                    line.setShader(null);
                }
                paint.setColor(0x1FFFFFFF);
                c.drawCircle(dx[i], dy[i], r, paint);
                paint.setColor(0x26000000);
                c.drawCircle(dx[i] + r * 0.15f, dy[i] + r * 0.3f, r * 0.8f, paint);
                paint.setColor(0x33FFFFFF);
                c.drawCircle(dx[i], dy[i], r * 0.85f, paint);
                paint.setColor(0xCCFFFFFF);
                c.drawCircle(dx[i] - r * 0.35f, dy[i] - r * 0.38f, r * 0.32f, paint);
            }
        }

        /** The strike: a bolt glowing through the glass, and the whole pane lit, twice. */
        private void lightning(Canvas c) {
            if (t < boltUntil) {
                path.reset();
                path.moveTo(bolt[0], bolt[1]);
                for (int i = 1; i < 7; i++) {
                    path.lineTo(bolt[i * 2], bolt[i * 2 + 1]);
                }
                line.setShader(null);
                line.setPathEffect(null);
                line.setStrokeWidth(px(7));
                line.setColor(0x40C8D2FF);
                c.drawPath(path, line);
                line.setStrokeWidth(px(1.6f));
                line.setColor(0xE6FFFFFF);
                c.drawPath(path, line);
            }
            if (flash > 0) {
                paint.setShader(null);
                paint.setColor((Math.round(150 * flash) << 24) | 0xE4E9FF);
                c.drawRect(-px(20), -px(30), w, h + px(30), paint);
            }
        }

        private void snow(Canvas c) {
            for (int i = 0; i < sx.length; i++) {
                float x = sx[i] + (float) Math.sin(t * 1.1f + sp[i]) * px(6);
                float r = ss[i];
                if (r > px(2)) {
                    paint.setShader(new RadialGradient(x, sy[i], r * 1.6f, 0xE6FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawCircle(x, sy[i], r * 1.6f, paint);
                    paint.setShader(null);
                } else {
                    paint.setColor(0x99FFFFFF);
                    c.drawCircle(x, sy[i], r, paint);
                }
            }
        }

        private void fog(Canvas c) {
            for (int i = 0; i < 3; i++) {
                float y = h * (0.25f + 0.25f * i);
                float shift = (float) Math.sin(t * 0.4f + i * 1.9f) * w * 0.2f;
                paint.setShader(new LinearGradient(-w * 0.2f + shift, 0, w * 1.2f + shift, 0,
                        new int[]{0x00FFFFFF, 0x52FFFFFF, 0x52FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.3f, 0.7f, 1f},
                        Shader.TileMode.CLAMP));
                c.drawRect(-w * 0.2f + shift, y - px(7), w * 1.2f + shift, y + px(7), paint);
            }
            paint.setShader(null);
        }

        private void wind(Canvas c) {
            line.setShader(null);
            line.setStrokeWidth(px(1.4f));
            line.setColor(0x80FFFFFF);
            for (int i = 0; i < sx.length; i++) {
                float head = sx[i];
                float tail = head - ss[i];
                float bend = (float) Math.sin(t * 2 + sp[i]) * px(4);
                path.reset();
                path.moveTo(tail, sy[i]);
                path.quadTo((head + tail) / 2, sy[i] + bend, head, sy[i]);
                line.setShader(new LinearGradient(tail, 0, head, 0, 0x00FFFFFF, 0x99FFFFFF, Shader.TileMode.CLAMP));
                c.drawPath(path, line);
            }
            line.setShader(null);
        }
    }
}
