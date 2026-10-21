package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Security cameras, like the Home tab of the tvOS Control Center: up to four RTSP / HTTP
 * streams in a 2x2 grid, one of them full screen on OK. The list comes from
 * files/cameras.txt ("Name=rtsp://user:pass@host/stream" per line), pushed by tvtool.
 */
final class Cameras extends FrameLayout {

    static final class Camera {
        final String name;
        final String url;

        Camera(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    interface Listener {
        void closed();
    }

    private final List<Camera> cameras;
    private final List<TextureView> views = new ArrayList<>();
    private final List<MediaPlayer> players = new ArrayList<>();
    private final List<FrameLayout> cells = new ArrayList<>();
    private final float dp;
    private int single = -1;
    private final Listener listener;

    static List<Camera> load(File dir) {
        List<Camera> out = new ArrayList<>();
        File file = dir != null ? new File(dir, "cameras.txt") : null;
        if (file == null || !file.exists()) {
            return out;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) {
                    out.add(new Camera(line.substring(0, eq).trim(), line.substring(eq + 1).trim()));
                }
            }
        } catch (IOException ignored) {
            // No cameras.
        }
        return out;
    }

    Cameras(Context context, List<Camera> cameras, float dp, Listener listener) {
        super(context);
        this.cameras = cameras.size() > 4 ? cameras.subList(0, 4) : cameras;
        this.dp = dp;
        this.listener = listener;
        setBackgroundColor(Color.BLACK);
        setClipChildren(false);
        int n = this.cameras.size();
        int columns = n <= 1 ? 1 : 2;
        int rows = n <= 2 ? 1 : 2;
        for (int i = 0; i < n; i++) {
            final int index = i;
            Camera camera = this.cameras.get(i);
            FrameLayout cell = new FrameLayout(context);
            cell.setBackgroundColor(0xFF101214);
            cell.setFocusable(true);
            TextureView video = new TextureView(context);
            cell.addView(video, new LayoutParams(-1, -1));
            TextView name = new TextView(context);
            name.setText(camera.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(13);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setShadowLayer(px(6), 0, px(1), 0xB3000000);
            name.setPadding(px(14), px(10), px(14), px(10));
            cell.addView(name, new LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
            TextView status = new TextView(context);
            status.setText("Se conectează…");
            status.setTextColor(0xB3FFFFFF);
            status.setTextSize(12);
            status.setTag("status");
            cell.addView(status, new LayoutParams(-2, -2, Gravity.CENTER));
            final GlassDrawable ring = new GlassDrawable(0, dp, 0x00000000);
            cell.setOnFocusChangeListener((v, has) -> {
                v.setForeground(has ? ring : null);
                ring.setTint(0x00000000);
            });
            cell.setOnClickListener(v -> toggleSingle(index));
            views.add(video);
            cells.add(cell);
            addView(cell);
            video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                    start(index, surface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                    View s = cell.findViewWithTag("status");
                    if (s != null && s.getVisibility() == VISIBLE) {
                        s.setVisibility(GONE);
                    }
                }
            });
            players.add(null);
        }
        layoutGrid(columns, rows);
        if (!cells.isEmpty()) {
            cells.get(0).requestFocus();
        }
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void layoutGrid(int columns, int rows) {
        int gap = px(4);
        post(() -> {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < cells.size(); i++) {
                int c = i % columns;
                int r = i / columns;
                int cw = (w - gap * (columns - 1)) / columns;
                int ch = (h - gap * (rows - 1)) / rows;
                LayoutParams lp = new LayoutParams(cw, ch);
                lp.leftMargin = c * (cw + gap);
                lp.topMargin = r * (ch + gap);
                cells.get(i).setLayoutParams(lp);
            }
        });
    }

    private void start(int index, SurfaceTexture surface) {
        try {
            MediaPlayer player = new MediaPlayer();
            player.setSurface(new Surface(surface));
            player.setDataSource(getContext(), android.net.Uri.parse(cameras.get(index).url));
            player.setVolume(0f, 0f);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnErrorListener((mp, what, extra) -> {
                TextView s = cells.get(index).findViewWithTag("status");
                if (s != null) {
                    s.setVisibility(VISIBLE);
                    s.setText("Fără semnal (" + what + "/" + extra + ")");
                }
                return true;
            });
            player.prepareAsync();
            players.set(index, player);
        } catch (Exception e) {
            TextView s = cells.get(index).findViewWithTag("status");
            if (s != null) {
                s.setText("Adresă invalidă");
            }
        }
    }

    private void toggleSingle(int index) {
        single = single == index ? -1 : index;
        int n = cells.size();
        for (int i = 0; i < n; i++) {
            View cell = cells.get(i);
            if (single < 0) {
                cell.setVisibility(VISIBLE);
                cell.animate().scaleX(1f).scaleY(1f).translationX(0).translationY(0).setDuration(260).start();
            } else if (i == single) {
                cell.bringToFront();
                float sx = getWidth() / (float) cell.getWidth();
                float sy = getHeight() / (float) cell.getHeight();
                cell.setPivotX(0);
                cell.setPivotY(0);
                cell.animate().scaleX(sx).scaleY(sy).translationX(-cell.getLeft()).translationY(-cell.getTop())
                        .setDuration(260).start();
            } else {
                cell.setVisibility(INVISIBLE);
            }
        }
    }

    /** Back: leaves the single view first, then closes the whole pane. */
    boolean back() {
        if (single >= 0) {
            toggleSingle(single);
            return true;
        }
        close();
        return true;
    }

    void close() {
        for (MediaPlayer player : players) {
            if (player != null) {
                try {
                    player.stop();
                } catch (IllegalStateException ignored) {
                    // Not started.
                }
                player.release();
            }
        }
        players.clear();
        listener.closed();
    }
}
