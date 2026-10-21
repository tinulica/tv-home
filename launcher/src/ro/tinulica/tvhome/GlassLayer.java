package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The home screen's backdrop and all of its Liquid Glass, drawn per pixel in OpenGL ES
 * (Android 12 has no AGSL, but every GPU runs GLSL). Sits behind the window; the cards and
 * text are ordinary views on top.
 *
 * Backdrop: two wallpapers cross-fading (with the slow zoom of the entrance), the Top Shelf
 * banner over them, and the frosted, dimmed version used while the app grid is open.
 * Glass: every registered view gets a pane at its exact on-screen shape that samples a
 * pre-blurred copy of that same backdrop, bends it at the rim with a squircle bezel (strongest
 * at the edge, flat in the middle), lifts saturation, adds a light tint and a specular rim lit
 * from the top-left. Frames are drawn only when something moved, so an idle screen costs nothing.
 */
final class GlassLayer extends GLSurfaceView {

    /** A view that should be glass, with its corner radius and lens strength. */
    static final class Pane {
        final WeakReference<View> view;
        final float radius;
        final float strength;

        Pane(View view, float radius, float strength) {
            this.view = new WeakReference<>(view);
            this.radius = radius;
            this.strength = strength;
        }
    }

    static final int BUFFER_W = 3840;
    static final int BUFFER_H = 2160;
    private volatile int logicalW = 1920;
    private volatile int logicalH = 1080;
    private final List<Pane> panes = new ArrayList<>();
    private final Renderer renderer;
    private final float density;
    private final int[] rootOnScreen = new int[2];
    private float[] lastSnapshot = new float[0];

    // State written on the UI thread, read on the GL thread.
    private volatile float[] snapshot = new float[0];
    private volatile float heroAlpha;
    private volatile float frost;
    private volatile float wallScale = 1f;

    GlassLayer(Context context, float density) {
        super(context);
        this.density = density;
        setEGLContextClientVersion(2);
        // A 3840x2160 buffer was tried: the TV composes every UI layer into a 1920x1080
        // framebuffer before the panel, so it only cost GPU time. The surface follows the window.
        renderer = new Renderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            logicalW = w;
            logicalH = h;
        }
    }

    void register(View view, float radius, float strength) {
        for (Pane p : panes) {
            if (p.view.get() == view) {
                return;
            }
        }
        panes.add(new Pane(view, radius, strength));
    }

    // Bitmaps to show; the GL thread uploads them when its context is ready (and again after a
    // context loss), so nothing is lost if they arrive before the surface exists.
    private final Object lock = new Object();
    private Bitmap wallFront;
    private Bitmap wallFrontBlur;
    private Bitmap wallBack;
    private Bitmap wallBackBlur;
    private Bitmap heroSharp;
    private Bitmap heroBlur;
    private Bitmap soft;
    private boolean wallDirty;
    private boolean heroDirty;
    private boolean softDirty;
    private long fadeStartMs;
    private long fadeDurationMs;

    void setWallpaper(Bitmap sharp, Bitmap blur, long fadeMs) {
        synchronized (lock) {
            wallBack = wallFront;
            wallBackBlur = wallFrontBlur;
            wallFront = sharp;
            wallFrontBlur = blur;
            wallDirty = true;
            fadeStartMs = SystemClock.uptimeMillis();
            fadeDurationMs = wallBack == null ? 1 : fadeMs;
        }
        requestRender();
    }

    void setHero(Bitmap sharp, Bitmap blur) {
        synchronized (lock) {
            heroSharp = sharp;
            heroBlur = blur;
            heroDirty = true;
        }
        requestRender();
    }

    void setFrostSource(Bitmap bitmap) {
        synchronized (lock) {
            soft = bitmap;
            softDirty = true;
        }
        requestRender();
    }

    // Focus shadows: drawn here, soft and wide, under the card and over the glass.
    private final java.util.WeakHashMap<View, float[]> shadows = new java.util.WeakHashMap<>();

    /** amount 0..1 (how lifted the card is) and the shadow's colour. */
    void shadow(View view, float amount, int color) {
        if (amount <= 0.01f) {
            if (shadows.remove(view) != null) {
                requestRender();
            }
            return;
        }
        shadows.put(view, new float[]{amount, color});
    }

    /**
     * Called before every window draw: reads where each glass view is (with its scale,
     * translation and fade), plus the backdrop state, and asks for a GL frame if any of it changed.
     */
    void sync(float hero, float frostAlpha, float scale) {
        getLocationOnScreen(rootOnScreen);
        List<Float> out = new ArrayList<>();
        Iterator<Pane> it = panes.iterator();
        RectF r = new RectF();
        while (it.hasNext()) {
            Pane p = it.next();
            View v = p.view.get();
            if (v == null) {
                it.remove();
                continue;
            }
            if (!v.isAttachedToWindow() || !v.isShown() || v.getWidth() == 0) {
                continue;
            }
            float alpha = effectiveAlpha(v);
            if (alpha < 0.01f) {
                continue;
            }
            float s = windowRect(v, r);
            out.add(r.left);
            out.add(r.top);
            out.add(r.width());
            out.add(r.height());
            out.add(Math.min(p.radius * s, Math.min(r.width(), r.height()) / 2f));
            out.add(alpha);
            out.add(p.strength);
        }
        List<Float> sh = new ArrayList<>();
        for (java.util.Map.Entry<View, float[]> e : shadows.entrySet()) {
            View v = e.getKey();
            if (v == null || !v.isAttachedToWindow() || !v.isShown()) {
                continue;
            }
            float alpha = effectiveAlpha(v) * e.getValue()[0];
            if (alpha < 0.01f) {
                continue;
            }
            float sc = windowRect(v, r);
            sh.add(r.left);
            sh.add(r.top);
            sh.add(r.width());
            sh.add(r.height());
            sh.add(12f * density * sc);
            sh.add(alpha);
            sh.add(e.getValue()[1]);
        }
        float[] snap = new float[1 + out.size() + sh.size()];
        snap[0] = out.size() / 7;
        for (int i = 0; i < out.size(); i++) {
            snap[1 + i] = out.get(i);
        }
        for (int i = 0; i < sh.size(); i++) {
            snap[1 + out.size() + i] = sh.get(i);
        }
        boolean changed = !java.util.Arrays.equals(snap, lastSnapshot)
                || hero != heroAlpha || frostAlpha != frost || scale != wallScale || renderer.animating();
        lastSnapshot = snap;
        snapshot = snap;
        heroAlpha = hero;
        frost = frostAlpha;
        wallScale = scale;
        if (changed) {
            requestRender();
        }
    }

    private static float effectiveAlpha(View v) {
        float a = 1f;
        View cur = v;
        while (cur != null) {
            if (cur.getVisibility() != VISIBLE) {
                return 0f;
            }
            a *= cur.getAlpha();
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return a;
    }

    /** The view's bounds after every transform up to the window, in window pixels; returns its scale. */
    private static float windowRect(View v, RectF out) {
        out.set(0, 0, v.getWidth(), v.getHeight());
        float scale = 1f;
        View cur = v;
        while (cur != null) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) {
                m.mapRect(out);
                scale *= cur.getScaleX();
            }
            out.offset(cur.getLeft(), cur.getTop());
            ViewParent parent = cur.getParent();
            if (parent instanceof View) {
                View pv = (View) parent;
                out.offset(-pv.getScrollX(), -pv.getScrollY());
                cur = pv;
            } else {
                cur = null;
            }
        }
        return scale;
    }

    // ------------------------------------------------------------------ renderer

    private static final String VERTEX =
            "attribute vec2 aPos;\n"
            + "uniform vec2 uRes;\n"
            + "varying vec2 vPx;\n"
            + "void main() { vPx = (aPos * 0.5 + 0.5) * uRes; vPx.y = uRes.y - vPx.y; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    /** The backdrop: two wallpapers (zoomed by uScale), the banner over them, then the grid frost. */
    private static final String BACKDROP =
            "precision highp float;\n"
            + "varying vec2 vPx;\n"
            + "uniform vec2 uRes;\n"
            + "uniform sampler2D uWallA; uniform sampler2D uWallB; uniform sampler2D uHero; uniform sampler2D uSoft;\n"
            + "uniform float uMix; uniform float uHeroAlpha; uniform float uFrost; uniform float uScale;\n"
            + "void main() {\n"
            + "  vec2 uv = vPx / uRes;\n"
            + "  vec2 wuv = (uv - 0.5) / uScale + 0.5;\n"
            + "  vec3 col = mix(texture2D(uWallA, wuv).rgb, texture2D(uWallB, wuv).rgb, uMix);\n"
            + "  if (uHeroAlpha > 0.0) col = mix(col, texture2D(uHero, uv).rgb, uHeroAlpha);\n"
            + "  if (uFrost > 0.0) col = mix(col, texture2D(uSoft, wuv).rgb * 0.57, uFrost);\n"
            + "  gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";

    /** One pane of glass over that backdrop. */
    private static final String GLASS =
            "precision highp float;\n"
            + "varying vec2 vPx;\n"
            + "uniform vec2 uRes;\n"
            + "uniform sampler2D uBlurA; uniform sampler2D uBlurB; uniform sampler2D uHeroBlur; uniform sampler2D uSoft;\n"
            + "uniform float uMix; uniform float uHeroAlpha; uniform float uFrost; uniform float uScale;\n"
            + "uniform vec4 uRect; uniform float uRadius; uniform float uBezel; uniform float uShift; uniform float uAlpha;\n"
            + "float sdBox(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
            + "vec3 backdrop(vec2 px) {\n"
            + "  vec2 uv = clamp(px / uRes, 0.0, 1.0);\n"
            + "  vec2 wuv = (uv - 0.5) / uScale + 0.5;\n"
            + "  vec3 col = mix(texture2D(uBlurA, wuv).rgb, texture2D(uBlurB, wuv).rgb, uMix);\n"
            + "  if (uHeroAlpha > 0.0) col = mix(col, texture2D(uHeroBlur, uv).rgb, uHeroAlpha);\n"
            + "  if (uFrost > 0.0) col = mix(col, texture2D(uSoft, wuv).rgb * 0.57, uFrost);\n"
            + "  return col;\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 hb = uRect.zw * 0.5;\n"
            + "  vec2 p = vPx - (uRect.xy + hb);\n"
            + "  float d = sdBox(p, hb, uRadius);\n"
            + "  if (d > 1.0) discard;\n"
            + "  float inside = -d;\n"
            + "  float e = 0.75;\n"
            + "  vec2 n = vec2(sdBox(p + vec2(e, 0.0), hb, uRadius) - sdBox(p - vec2(e, 0.0), hb, uRadius),\n"
            + "                sdBox(p + vec2(0.0, e), hb, uRadius) - sdBox(p - vec2(0.0, e), hb, uRadius));\n"
            + "  n = n / max(length(n), 1e-4);\n"
            + "  float bezel = min(uBezel, min(hb.x, hb.y));\n"
            + "  float t = clamp(inside / bezel, 0.0, 1.0);\n"
            + "  float k = 1.0 - t;\n"
            + "  vec2 spos = vPx + n * uShift * k * k * k;\n"   // the rim magnifies what lies just outside
            + "  vec3 col = backdrop(spos);\n"
            + "  float luma = dot(col, vec3(0.299, 0.587, 0.114));\n"
            + "  col = mix(vec3(luma), col, 1.35);\n"            // glass lifts saturation
            + "  col = mix(col, vec3(1.0), 0.10);\n"             // and the light a little
            + "  float rim = smoothstep(1.6, 0.0, abs(inside - 0.8));\n"
            + "  float light = max(0.0, dot(n, vec2(-0.6, -0.8))) + 0.4 * max(0.0, dot(n, vec2(0.6, 0.8)));\n"
            + "  col += rim * (0.12 + 0.6 * light);\n"
            + "  col += 0.08 * smoothstep(bezel * 1.5, 0.0, inside) * max(0.0, -n.y);\n"
            + "  gl_FragColor = vec4(col, clamp(1.0 - d, 0.0, 1.0) * uAlpha);\n"
            + "}\n";

    /**
     * A focused card's shadow, the way tvOS draws it: wide and soft, a little below the card,
     * densest under its middle; a rounded-rectangle distance field with a gaussian falloff.
     */
    private static final String SHADOW =
            "precision highp float;\n"
            + "varying vec2 vPx;\n"
            + "uniform vec4 uRect; uniform float uRadius; uniform float uBlur; uniform vec4 uColor;\n"
            + "float sdBox(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
            + "void main() {\n"
            + "  vec2 hb = uRect.zw * 0.5;\n"
            + "  float d = sdBox(vPx - (uRect.xy + hb), hb * vec2(0.92, 0.86), uRadius);\n"
            + "  float a = exp(-max(d, 0.0) * max(d, 0.0) / (uBlur * uBlur * 0.5));\n"
            + "  gl_FragColor = vec4(uColor.rgb, uColor.a * a);\n"
            + "}\n";

    final class Renderer implements GLSurfaceView.Renderer {
        private int backdropProgram;
        private int glassProgram;
        private int shadowProgram;
        private final int[] tex = new int[8];   // 0 wallA, 1 wallB, 2 blurA, 3 blurB, 4 hero, 5 heroBlur, 6 soft, 7 black
        private long fadeStart;
        private long fadeMs;
        private float mix = 1f;
        private int w;
        private int h;
        private FloatBuffer quad;
        private final FloatBuffer box = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        boolean animating() {
            return fadeMs > 0 && SystemClock.uptimeMillis() - fadeStart < fadeMs;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            backdropProgram = program(VERTEX, BACKDROP);
            glassProgram = program(VERTEX, GLASS);
            shadowProgram = program(VERTEX, SHADOW);
            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
            GLES20.glGenTextures(tex.length, tex, 0);
            Bitmap black = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            black.eraseColor(0xFF000000);
            for (int t : tex) {
                upload(t, black);
            }
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            // A new context has no textures: upload whatever is current again.
            synchronized (lock) {
                wallDirty = wallFront != null;
                heroDirty = heroSharp != null;
                softDirty = soft != null;
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            // Draw into the full 4K buffer, but keep every coordinate in window pixels.
            GLES20.glViewport(0, 0, width, height);
            w = logicalW;
            h = logicalH;
        }

        private void uploadPending() {
            synchronized (lock) {
                if (wallDirty) {
                    // Slot A holds the outgoing picture, slot B the incoming one; mix fades A to B.
                    upload(tex[0], wallBack != null ? wallBack : wallFront);
                    upload(tex[2], wallBackBlur != null ? wallBackBlur : wallFrontBlur);
                    upload(tex[1], wallFront);
                    upload(tex[3], wallFrontBlur);
                    fadeStart = fadeStartMs;
                    fadeMs = fadeDurationMs;
                    wallDirty = false;
                }
                if (heroDirty) {
                    upload(tex[4], heroSharp);
                    upload(tex[5], heroBlur);
                    heroDirty = false;
                }
                if (softDirty) {
                    upload(tex[6], soft);
                    softDirty = false;
                }
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            uploadPending();
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            mix = fadeMs > 0 ? Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) fadeMs) : 1f;
            float hero = heroAlpha;
            float frostNow = frost;
            float scale = wallScale;
            GLES20.glUseProgram(backdropProgram);
            common(backdropProgram, hero, frostNow, scale, false);
            bind(backdropProgram, "uWallA", 0, tex[0]);
            bind(backdropProgram, "uWallB", 1, tex[1]);
            bind(backdropProgram, "uHero", 2, tex[4]);
            bind(backdropProgram, "uSoft", 3, tex[6]);
            drawQuad(backdropProgram, quad);

            float[] all = snapshot;
            int glassCount = all.length > 0 ? (int) all[0] : 0;
            float[] snap = new float[glassCount * 7];
            if (glassCount > 0) {
                System.arraycopy(all, 1, snap, 0, snap.length);
            }
            if (snap.length > 0) {
                GLES20.glUseProgram(glassProgram);
                common(glassProgram, hero, frostNow, scale, true);
                bind(glassProgram, "uBlurA", 0, tex[2]);
                bind(glassProgram, "uBlurB", 1, tex[3]);
                bind(glassProgram, "uHeroBlur", 2, tex[5]);
                bind(glassProgram, "uSoft", 3, tex[6]);
                for (int i = 0; i + 6 < snap.length; i += 7) {
                    float x = snap[i] + 0f;
                    float y = snap[i + 1];
                    float pw = snap[i + 2];
                    float ph = snap[i + 3];
                    float radius = snap[i + 4];
                    float strength = snap[i + 6];
                    float size = Math.min(pw, ph);
                    GLES20.glUniform4f(loc(glassProgram, "uRect"), x, y, pw, ph);
                    GLES20.glUniform1f(loc(glassProgram, "uRadius"), radius);
                    // Bigger glass is thicker: wider bezel, stronger lens (WWDC25 "Meet Liquid Glass").
                    GLES20.glUniform1f(loc(glassProgram, "uBezel"), Math.max(6f * density, Math.min(28f * density, size * 0.35f)));
                    GLES20.glUniform1f(loc(glassProgram, "uShift"), strength * Math.max(3f * density, Math.min(16f * density, size * 0.12f)));
                    GLES20.glUniform1f(loc(glassProgram, "uAlpha"), snap[i + 5]);
                    float pad = 2f;
                    float x0 = (x - pad) / w * 2f - 1f;
                    float x1 = (x + pw + pad) / w * 2f - 1f;
                    float y0 = 1f - (y + ph + pad) / h * 2f;
                    float y1 = 1f - (y - pad) / h * 2f;
                    box.position(0);
                    box.put(x0).put(y0).put(x1).put(y0).put(x0).put(y1).put(x1).put(y1).position(0);
                    drawQuad(glassProgram, box);
                }
            }
            // Focus shadows over the glass, under the cards (the cards are in the window above).
            int shadowStart = 1 + glassCount * 7;
            if (all.length > shadowStart) {
                GLES20.glUseProgram(shadowProgram);
                GLES20.glUniform2f(loc(shadowProgram, "uRes"), w, h);
                for (int i = shadowStart; i + 6 < all.length; i += 7) {
                    float x = all[i];
                    float y = all[i + 1];
                    float pw = all[i + 2];
                    float ph = all[i + 3];
                    float alpha = all[i + 5];
                    int color = (int) all[i + 6];
                    float blur = ph * 0.30f;
                    float drop = ph * 0.15f;
                    GLES20.glUniform4f(loc(shadowProgram, "uRect"), x, y + drop, pw, ph);
                    GLES20.glUniform1f(loc(shadowProgram, "uRadius"), all[i + 4]);
                    GLES20.glUniform1f(loc(shadowProgram, "uBlur"), blur);
                    GLES20.glUniform4f(loc(shadowProgram, "uColor"),
                            ((color >> 16) & 0xFF) / 255f * 0.3f, ((color >> 8) & 0xFF) / 255f * 0.3f,
                            (color & 0xFF) / 255f * 0.3f, 0.6f * alpha);
                    float pad = blur * 2.2f;
                    float x0 = (x - pad) / w * 2f - 1f;
                    float x1 = (x + pw + pad) / w * 2f - 1f;
                    float y0 = 1f - (y + drop + ph + pad) / h * 2f;
                    float y1 = 1f - (y + drop - pad) / h * 2f;
                    box.position(0);
                    box.put(x0).put(y0).put(x1).put(y0).put(x0).put(y1).put(x1).put(y1).position(0);
                    drawQuad(shadowProgram, box);
                }
            }
            if (animating()) {
                requestRender();
            }
        }

        private void common(int program, float hero, float frostNow, float scale, boolean glass) {
            GLES20.glUniform2f(loc(program, "uRes"), w, h);
            GLES20.glUniform1f(loc(program, "uMix"), mix);
            GLES20.glUniform1f(loc(program, "uHeroAlpha"), hero);
            GLES20.glUniform1f(loc(program, "uFrost"), frostNow);
            GLES20.glUniform1f(loc(program, "uScale"), scale);
        }

        private void bind(int program, String name, int unit, int texture) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(loc(program, name), unit);
        }

        private int loc(int program, String name) {
            return GLES20.glGetUniformLocation(program, name);
        }

        private void drawQuad(int program, FloatBuffer vertices) {
            int pos = GLES20.glGetAttribLocation(program, "aPos");
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void upload(int texture, Bitmap bitmap) {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        }

        private int program(String vs, String fs) {
            int v = shader(GLES20.GL_VERTEX_SHADER, vs);
            int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                android.util.Log.e("TVHome", "glass link: " + GLES20.glGetProgramInfoLog(p));
            }
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                android.util.Log.e("TVHome", "glass compile: " + GLES20.glGetShaderInfoLog(s));
            }
            return s;
        }
    }
}
