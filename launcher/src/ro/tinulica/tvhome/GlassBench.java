package ro.tinulica.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Benchmark: real per-pixel Liquid Glass in OpenGL ES 2.0 (works on Android 12, no AGSL).
 * Draws the wallpaper, then glass panes that refract it at their rim (squircle bezel),
 * blur it (mip-level bias), tint it and add a specular rim, and moves them every frame so
 * nothing can be cached. Runs four stages and writes the frame times to files/glassbench.txt.
 *
 *   adb shell am start -n ro.tinulica.tvhome/.GlassBench
 */
public class GlassBench extends Activity {

    private GLSurfaceView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(new Bench());
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(view);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    private static final String VERTEX =
            "attribute vec2 aPos;\n"
            + "varying vec2 vUv;\n"
            + "void main() { vUv = aPos * 0.5 + 0.5; vUv.y = 1.0 - vUv.y; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    private static final String PLAIN =
            "precision mediump float;\n"
            + "varying vec2 vUv; uniform sampler2D uTex;\n"
            + "void main() { gl_FragColor = texture2D(uTex, vUv); }\n";

    /** One glass pane: rounded-rect SDF, squircle bezel refraction, mip blur, tint, rim light. */
    private static final String GLASS =
            "precision highp float;\n"
            + "varying vec2 vUv;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uRes;\n"
            + "uniform vec4 uRect;\n"      // x, y, w, h in pixels
            + "uniform float uRadius;\n"
            + "uniform float uBezel;\n"
            + "uniform float uShift;\n"
            + "float sdBox(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
            + "void main() {\n"
            + "  vec2 px = vUv * uRes;\n"
            + "  vec2 c = uRect.xy + uRect.zw * 0.5;\n"
            + "  vec2 p = px - c;\n"
            + "  float d = sdBox(p, uRect.zw * 0.5, uRadius);\n"
            + "  if (d > 1.0) discard;\n"
            + "  float inside = -d;\n"
            + "  float e = 0.5;\n"
            + "  vec2 n = normalize(vec2(sdBox(p + vec2(e, 0.0), uRect.zw * 0.5, uRadius) - sdBox(p - vec2(e, 0.0), uRect.zw * 0.5, uRadius),\n"
            + "                          sdBox(p + vec2(0.0, e), uRect.zw * 0.5, uRadius) - sdBox(p - vec2(0.0, e), uRect.zw * 0.5, uRadius)) + 1e-5);\n"
            + "  float t = clamp(inside / uBezel, 0.0, 1.0);\n"
            + "  float k = 1.0 - t;\n"
            + "  float shift = uShift * k * k * k;\n"            // squircle-like falloff
            + "  vec2 uv = (px + n * shift) / uRes;\n"           // sample from outside the pane: lens
            + "  vec3 col = texture2D(uTex, uv, 3.0).rgb;\n"     // mip bias = soft blur
            + "  col = mix(vec3(dot(col, vec3(0.299, 0.587, 0.114))), col, 1.3);\n"  // saturation
            + "  col = mix(col, vec3(1.0), 0.14);\n"             // light tint
            + "  float rim = smoothstep(1.5, 0.0, abs(inside - 0.75));\n"
            + "  float light = max(0.0, dot(n, normalize(vec2(-0.6, -0.8)))) + 0.35 * max(0.0, dot(n, normalize(vec2(0.6, 0.8))));\n"
            + "  col += rim * (0.15 + 0.55 * light);\n"
            + "  col += 0.10 * smoothstep(uBezel * 1.6, 0.0, inside) * max(0.0, -n.y);\n"  // inner top highlight
            + "  float alpha = clamp(1.0 - d, 0.0, 1.0);\n"
            + "  gl_FragColor = vec4(col, alpha);\n"
            + "}\n";

    final class Bench implements GLSurfaceView.Renderer {
        private int plain;
        private int glass;
        private int texture;
        private FloatBuffer quad;
        private int w;
        private int h;
        private long stageStart;
        private int stage;
        private int frames;
        private long last;
        private final long[] times = new long[2000];
        private final StringBuilder report = new StringBuilder();
        // stage: covered fraction of the screen by glass
        private final String[] names = {"doar fundal", "sticlă 10% (bara)", "sticlă 30% (bara + Control Center)", "sticlă 100%"};

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            plain = program(VERTEX, PLAIN);
            glass = program(VERTEX, GLASS);
            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
            texture = loadWallpaper();
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            report.append(String.format(Locale.ROOT, "GPU: %s / %s%n",
                    GLES20.glGetString(GLES20.GL_RENDERER), GLES20.glGetString(GLES20.GL_VERSION)));
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            w = width;
            h = height;
            GLES20.glViewport(0, 0, w, h);
            report.append(String.format(Locale.ROOT, "Suprafață: %dx%d%n", w, h));
            stageStart = SystemClock.elapsedRealtime();
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            long now = System.nanoTime();
            if (last != 0 && frames < times.length) {
                times[frames++] = now - last;
            }
            last = now;
            float phase = (SystemClock.elapsedRealtime() % 4000) / 4000f * 6.2832f;
            draw(plain, null);
            float s = (float) Math.sin(phase) * 30f;
            if (stage == 1 || stage == 2) {
                pane(w * 0.04f + s, h * 0.77f, w * 0.92f, h * 0.18f, h * 0.035f);
            }
            if (stage == 2) {
                pane(w * 0.74f, h * 0.12f + s, w * 0.22f, h * 0.22f, h * 0.03f);
                pane(w * 0.74f, h * 0.36f - s, w * 0.22f, h * 0.30f, h * 0.03f);
            }
            if (stage == 3) {
                pane(s, 0, w, h, h * 0.04f);
            }
            if (SystemClock.elapsedRealtime() - stageStart > 6000) {
                finishStage();
            }
        }

        private void pane(float x, float y, float pw, float ph, float radius) {
            GLES20.glUseProgram(glass);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(glass, "uRes"), w, h);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(glass, "uRect"), x, y, pw, ph);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(glass, "uRadius"), radius);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(glass, "uBezel"), h * 0.03f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(glass, "uShift"), h * 0.018f);
            // Only the pane's bounding box is rasterised.
            float x0 = x / w * 2f - 1f;
            float x1 = (x + pw) / w * 2f - 1f;
            float y0 = 1f - (y + ph) / h * 2f;
            float y1 = 1f - y / h * 2f;
            FloatBuffer box = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            box.put(new float[]{x0, y0, x1, y0, x0, y1, x1, y1}).position(0);
            draw(glass, box);
        }

        private void draw(int program, FloatBuffer vertices) {
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0);
            int pos = GLES20.glGetAttribLocation(program, "aPos");
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertices != null ? vertices : quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void finishStage() {
            long[] sorted = java.util.Arrays.copyOf(times, frames);
            java.util.Arrays.sort(sorted);
            double avg = 0;
            for (long t : sorted) {
                avg += t;
            }
            avg = frames > 0 ? avg / frames / 1e6 : 0;
            double p90 = frames > 0 ? sorted[(int) (frames * 0.9)] / 1e6 : 0;
            report.append(String.format(Locale.ROOT, "%-36s cadre=%4d  medie=%5.1f ms (%4.1f fps)  p90=%5.1f ms%n",
                    names[stage], frames, avg, avg > 0 ? 1000 / avg : 0, p90));
            Log.d("GlassBench", report.toString());
            frames = 0;
            last = 0;
            stage++;
            stageStart = SystemClock.elapsedRealtime();
            if (stage >= names.length) {
                try (FileWriter out = new FileWriter(new File(getExternalFilesDir(null), "glassbench.txt"))) {
                    out.write(report.toString());
                } catch (Exception ignored) {
                    // Logcat has it too.
                }
                runOnUiThread(GlassBench.this::finish);
            }
        }

        private int loadWallpaper() {
            Bitmap bitmap = null;
            try {
                File dir = getExternalFilesDir("wallpapers");
                File[] files = dir != null ? dir.listFiles() : null;
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith(".jpg")) {
                            bitmap = BitmapFactory.decodeFile(f.getAbsolutePath());
                            break;
                        }
                    }
                }
                if (bitmap == null) {
                    String[] assets = getAssets().list("wallpapers");
                    try (InputStream in = getAssets().open("wallpapers/" + assets[0])) {
                        bitmap = BitmapFactory.decodeStream(in);
                    }
                }
            } catch (Exception ignored) {
                bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            }
            // Power-of-two so GLES 2.0 can build mipmaps (the blur comes from them).
            Bitmap pot = Bitmap.createScaledBitmap(bitmap, 2048, 1024, true);
            int[] id = new int[1];
            GLES20.glGenTextures(1, id, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pot, 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            return id[0];
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
                report.append("link: ").append(GLES20.glGetProgramInfoLog(p)).append(System.lineSeparator());
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
                report.append("compile: ").append(GLES20.glGetShaderInfoLog(s)).append(System.lineSeparator());
            }
            return s;
        }
    }
}
