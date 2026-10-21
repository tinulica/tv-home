package ro.tinulica.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.LinearGradient;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal home screen modelled on the tvOS 26 home screen.
 *
 * - The wallpaper fills the screen like the Top Shelf.
 * - Favourite apps sit in a floating glass tray; the first row of all apps
 *   peeks in at the bottom edge.
 * - D-pad down slides the tray to the top, frosts and dims the wallpaper and
 *   shows every app. Up from the first row (or Back) goes back.
 * - A focused tile lifts, casts a shadow tinted by its banner, sways gently while
 *   light moves across it, sinks when pressed and bumps at the end of a row.
 * - Top right: "Surse" (HDMI / antenna) and the clock. Either one, or the Menu
 *   key, opens a glass Control Center.
 *
 * Configure from adb:
 *   am start -n ro.tinulica.tvhome/.MainActivity --es dock "com.netflix.ninja,com.google.android.youtube.tv"
 *   am start -n ro.tinulica.tvhome/.MainActivity --ei wallpaper_seconds 300 --ez show_place true
 */
public class MainActivity extends Activity {

    private static final String PREFS = "home";
    private static final String KEY_DOCK = "dock";
    private static final String KEY_INTERVAL = "wallpaper_seconds";
    private static final String KEY_SHOW_PLACE = "show_place";
    private static final String KEY_SOUNDS = "sounds";
    private static final String KEY_BUILTIN = "builtin_wallpapers";

    private static final List<String> DEFAULT_DOCK = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "com.disney.disneyplus");

    /** Never shown as tiles: system helpers, and Live TV apps (they are under "Surse"). */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.tcl.tv", "com.tcl.suspension", "com.tcl.t_solo", "com.tcl.dashboard",
            "com.android.tv.settings", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx"));
    private static final List<String> LIVE_TV = Arrays.asList(
            "com.tcl.tv", "com.mediatek.wwtv.tvcenter", "com.google.android.tv", "com.sony.dtv.tvx");
    private static final String INPUT_PICKER = "com.tcl.suspension";
    private static final String SETTINGS_TILE = "ro.tinulica.tvhome.settings";

    private static final int COLUMNS = 6;
    private static final float FOCUS_SCALE = 1.12f;
    /** tvOS-like easing: quick start, long soft landing. */
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.25f, 1f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private float dp;
    private int screenW;
    private int screenH;

    // Geometry in px, computed once.
    private int side;
    private int trayH;
    private int trayClosedTop;
    private int trayOpenTop;
    private int gridTop;
    private int gridClosedOffset;
    private int tileW;
    private int tileH;
    private int gap;
    private int controlW;
    private int controlMargin;

    private final List<ImageView[]> wallLayers = new ArrayList<>();
    private int front;
    private View blurLayer;
    private TextView placeTitle;
    private LinearLayout pills;
    private TextView inputsPill;
    private TextView clockPill;
    private FrameLayout tray;
    private LiquidGlassView trayPane;
    private LinearLayout dock;
    private ScrollView gridScroll;
    private LinearLayout grid;
    private FrameLayout control;
    private LiquidGlassView controlPane;
    private ScrollView controlScroll;
    private LinearLayout controlList;
    private FrameLayout home;

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final List<App> sortedApps = new ArrayList<>();
    private final Map<String, Integer> tintCache = new HashMap<>();
    private final Map<String, String> captions = new HashMap<>();
    private final List<String> wallpapers = new ArrayList<>();
    private int wallpaperIndex = -1;
    private long wallpaperShownAt;
    private boolean resumed;
    /** The home screen while it is in front (hello brings it in when the TV comes on). */
    static volatile MainActivity current;
    private boolean arriving;
    private boolean open;
    private int packageSequence;
    private int lastDockIndex;
    private ValueAnimator sway;
    private View swayTile;
    private Spring openSpring;
    private View dockHolder;
    private GlassLayer glassLayer;
    private final LensDrawable.Backdrop backdrop = new LensDrawable.Backdrop();
    private final List<View> lensViews = new ArrayList<>();
    private float lastHeroAlpha = -1f;
    private float lastFrostAlpha = -1f;
    private final Runnable heroCycle = this::cycleHero;
    /** Diagnostics only (adb --ei perf N): 1 no tilt, 2 no lift shadow, 4 no tile clip, 8 no grid, 16 no banner, 32 no tray glass. */
    private static int PERF;
    private Spring gridScrollSpring;
    private Spring shelfScrollSpring;
    private final java.util.WeakHashMap<View, Motion> motions = new java.util.WeakHashMap<>();
    private int moveDx;
    private int moveDy;
    private long moveAt;
    private long lastFocusAt;
    private View returnTile;
    private boolean returning;
    private android.media.SoundPool sounds;
    private final Map<String, Integer> soundIds = new HashMap<>();
    private boolean soundsReady;
    private boolean rebuilding;

    // Top shelf: the focused app's Watch Next / preview programs, like the tvOS top shelf.
    private int shelfH;
    private int shelfTop;
    private FrameLayout shelf;
    private HorizontalScrollView shelfScroll;
    private LinearLayout shelfRow;
    private String shelfPkg;
    private FrameLayout hero;
    private ImageView heroImage;
    private android.view.TextureView heroVideo;
    private android.media.MediaPlayer heroPlayer;
    private android.webkit.WebView heroWeb;
    private View controlReturn;
    private TextView heroTitle;
    private TextView heroApp;
    private Previews.Item heroItem;
    private final Runnable heroPlay = this::startHeroVideo;
    private final Runnable heroShow = this::showPendingHero;
    private Previews.Item pendingHero;
    private boolean browsing;
    private LinkedHashMap<String, List<String>> folders = new LinkedHashMap<>();
    private FrameLayout folderPane;
    private String openFolder;
    private View folderReturn;
    private Cameras camerasPane;
    private static final String KEY_FOLDER_SET = "folder_set";
    private static final String KEY_FOLDER_REMOVE = "folder_remove";
    private Bitmap wallpaperLens;
    private View wallSharp;
    private View heroShade;
    private View heroLeft;
    private int wallGen;
    private Bitmap wallpaperSoft;
    private boolean centerDown;
    private boolean swallowOkUp;
    private long lastDpadDownAt;
    private long lastDpadUpAt;
    private long lastDpadRepeatAt;
    private int lastDpadCode;
    private boolean swallowDpadUp;
    private long startedAt;
    private static final long HERO_DELAY = 1500;     // ms from focusing an app to its banner
    private static final long TRAILER_DELAY = 2000;  // ms from the banner to its trailer
    private Previews previews;
    private Recommender recommender;
    private final Runnable refreshTick = this::refreshRecommendations;
    private static final long REFRESH_EVERY = 15 * 60_000L;
    private final Map<String, List<Previews.Item>> previewCache = new HashMap<>();
    private final Map<String, Long> previewTime = new HashMap<>();
    private final ExecutorService posterWorker = Executors.newFixedThreadPool(2);
    private static final long PREVIEW_TTL = 10 * 60_000L;

    // Profile picture next to the clock; full-screen mode; dock editing; volume HUD.
    private ImageView avatar;
    private TextView hint;
    private boolean fullscreen;
    private View editing;
    private ValueAnimator jiggle;
    private AudioManager audio;
    private View volumeHud;
    private View volumeFill;
    private int volumeH;

    /**
     * TV Home's own TV tile: a deep night-blue glass with a soft light from the top, the
     * screen symbol and "TV"; when the tile is focused, the channel itself plays on it.
     */
    private Bitmap tvBanner() {
        int w = 400;
        int h = 240;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFF1B2A4E, 0xFF0A0F1E, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new android.graphics.RadialGradient(w * 0.3f, -h * 0.1f, w * 0.9f,
                new int[]{0x665B8CFF, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glow);
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.5f, sheen);
        Bitmap glyph = Glyphs.symbol(this, "tv", 92);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(74);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float tw = text.measureText("TV");
        float total = glyph.getWidth() + 22 + tw;
        float x = (w - total) / 2f;
        c.drawBitmap(glyph, x, (h - glyph.getHeight()) / 2f, null);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText("TV", x + glyph.getWidth() + 22, h / 2f - (fm.ascent + fm.descent) / 2f, text);
        return b;
    }

    static final class App {
        String pkg;
        CharSequence label;
        Drawable banner;
        Drawable icon;
        Intent intent;
    }

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (getIntent() != null && getIntent().hasExtra("perf")) {
            prefs.edit().putInt("perf", getIntent().getIntExtra("perf", 0)).apply();
        }
        PERF = prefs.getInt("perf", 0);
        folders = Folders.load(prefs);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        dp = metrics.density;
        screenW = Math.max(metrics.widthPixels, metrics.heightPixels);
        screenH = Math.min(metrics.widthPixels, metrics.heightPixels);
        computeGeometry();
        startedAt = SystemClock.elapsedRealtime();
        loadSounds();
        previews = new Previews(this);
        recommender = new Recommender(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        buildUi();
        applyConfig(getIntent());
        loadApps();
        rebuildTiles();
        playEntrance();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
        try {
            getContentResolver().registerContentObserver(
                    android.net.Uri.parse("content://android.media.tv/"), true, providerObserver);
        } catch (RuntimeException ignored) {
            // No TV provider.
        }
        // tvOS clicks softly on every focus move; a lower tock on select, a falling one on Back.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((before, now) -> {
            if (before != null && now != null && resumed && !rebuilding) {
                play("move");
            }
            screenChanged();
        });
    }

    /**
     * TCL's auto-start blocker turns our accessibility service off after updates and restarts.
     * If the user allowed it (tvtool surse grants WRITE_SECURE_SETTINGS), switch it back on;
     * the launcher's process is running now, so the system may bind it again.
     */
    private void keepSourcesServiceOn() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            String me = getPackageName() + "/" + SourcesService.class.getName();
            String list = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            if (list == null || "null".equals(list)) {
                list = "";
            }
            if (!(":" + list + ":").contains(":" + me + ":")) {
                Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services",
                        list.isEmpty() ? me : list + ":" + me);
                Settings.Secure.putInt(getContentResolver(), "accessibility_enabled", 1);
                previews.note("sources service switched back on");
            } else {
                // Listed but not running (TCL's blocker refused to start it after an update):
                // switch it off and on, which makes Android connect it again now that we run.
                final String full = list;
                handler.postDelayed(() -> {
                    if (SourcesService.instance != null && SourcesService.instance.connected()) {
                        return;
                    }
                    try {
                        String without = (":" + full + ":").replace(":" + me + ":", ":")
                                .replaceAll("^:+|:+$", "");
                        Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services", without);
                        handler.postDelayed(() -> {
                            Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services", full);
                            Settings.Secure.putInt(getContentResolver(), "accessibility_enabled", 1);
                            previews.note("sources service restarted");
                        }, 400);
                    } catch (RuntimeException ignored) {
                        // No permission any more.
                    }
                }, 2500);
            }
        } catch (RuntimeException e) {
            previews.note("sources service: " + e);
        }
    }

    private void dismissTclPanel() {
        try {
            startService(new Intent("com.tcl.quickmenu.dismiss")
                    .setClassName("com.tcl.suspension", "com.tcl.suspension.DismissService"));
        } catch (RuntimeException e) {
            previews.note("tcl dismiss: " + e);
        }
    }

    // ================================================================ sounds

    private void loadSounds() {
        android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        sounds = new android.media.SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        sounds.setOnLoadCompleteListener((pool, id, status) -> soundsReady = true);
        File custom = getExternalFilesDir("sounds");
        for (String name : new String[]{"move", "select", "back", "limit", "open"}) {
            File own = custom != null ? new File(custom, name + ".wav") : null;
            if (own != null && own.exists()) {
                soundIds.put(name, sounds.load(own.getAbsolutePath(), 1));
                continue;
            }
            try {
                soundIds.put(name, sounds.load(getAssets().openFd("sounds/" + name + ".wav"), 1));
            } catch (java.io.IOException ignored) {
                // No sound file: stay silent.
            }
        }
    }

    private void play(String name) {
        Integer id = soundIds.get(name);
        if (!"select".equals(name)) {
            return; // only the selection sound is kept; the others were too much
        }
        if (id != null && soundsReady && prefs.getBoolean(KEY_SOUNDS, true)) {
            // Quiet, like tvOS: the clicks are felt more than heard.
            float volume = "move".equals(name) ? 0.28f : 0.4f;
            sounds.play(id, volume, volume, 1, 0, 1f);
        }
    }

    /** Held volume keys: the accessibility service repeats them here while home is in front. */
    static volatile java.util.function.IntConsumer volumeSink;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Recording the presentation (adb only): starts what needs OK held, which adb cannot do
        // (and leaves the screen as it is, rather than going back to the first app).
        final String demo = intent.getStringExtra("demo");
        if (demo != null) {
            handler.postDelayed(() -> demo(demo), 300);
            return;
        }
        if (applyConfig(intent)) {
            rebuildTiles();
        }
        // Home while already home: close everything and go to the first app.
        hideControl();
        endEdit();
        exitBrowse();
        if (camerasPane != null) {
            camerasPane.close();
        }
        closeFolder();
        setFullscreen(false);
        setOpen(false);
        focusNothing();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        current = this;
        volumeSink = code -> handler.post(() -> onVolumeKey(code));
        keepSourcesServiceOn();
        askAppsForPrograms();
        screenChanged();
        try {
            // TCL's auto-start blocker can leave the notification listener unbound; ask again.
            android.service.notification.NotificationListenerService.requestRebind(
                    new android.content.ComponentName(this, NoticeListener.class));
        } catch (RuntimeException ignored) {
            // Notification access not granted.
        }
        if (glassLayer != null) {
            glassLayer.onResume();
        }
        refreshPackagesIfChanged();
        updateClock();
        loadAvatar();
        previewTime.clear();   // apps may have published new content while we were away
        previews.refresh();
        handler.removeCallbacks(refreshTick);
        handler.postDelayed(refreshTick, REFRESH_EVERY);
        playReturn();
        View focused = getCurrentFocus();
        if (focused != null && focused.getParent() == dock) {
            showShelf((String) focused.getTag());
        }
        View back = getCurrentFocus();
        if (back != null && LIVE_TILE.equals(back.getTag()) && back.getParent() == dock) {
            handler.postDelayed(() -> startFirstCardTv(back), 600);
        }
        if (CinemaActivity.left) {
            CinemaActivity.left = false;
            lightsOn(true);
        } else {
            lightsOn(false);
        }
        cinemaReturn.setVisibility(CinemaActivity.alive ? View.VISIBLE : View.GONE);
        loadWallpaperList();
        // Safety net: if the wallpaper went missing, bring one back.
        if (!wallLayers.isEmpty() && wallLayers.get(0)[front].getDrawable() == null) {
            handler.postDelayed(this::showNextWallpaper, 300);
        }
        long interval = prefs.getInt(KEY_INTERVAL, 300) * 1000L;
        long shownFor = SystemClock.elapsedRealtime() - wallpaperShownAt;
        if (wallpaperIndex < 0 || shownFor >= interval) {
            showNextWallpaper();
        } else {
            handler.postDelayed(wallpaperTick, interval - shownFor);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        volumeSink = null;
        resumed = false;
        if (current == this) {
            current = null;
        }
        if (glassLayer != null) {
            glassLayer.onPause();
        }
        stopHeroVideo();
        stopPeekTv();
        stopSway();
        handler.removeCallbacksAndMessages(null);
        if (enteringCinema) {
            abortCinemaEntry();
        }
    }

    /** The way into Cinema was cut short: the lights come back on at once, nothing left behind. */
    private void abortCinemaEntry() {
        enteringCinema = false;
        home.animate().cancel();
        home.setAlpha(1f);
        home.setScaleX(1f);
        home.setScaleY(1f);
        pills.animate().cancel();
        pills.setAlpha(cinemaPillsAlpha);
        ViewGroup content = findViewById(android.R.id.content);
        if (cinemaCurtain != null) {
            content.removeView(cinemaCurtain);
            cinemaCurtain = null;
        }
        if (cinemaPillView != null) {
            lensViews.remove(cinemaPillView);
            content.removeView(cinemaPillView);
            cinemaPillView = null;
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        getContentResolver().unregisterContentObserver(providerObserver);
        worker.shutdownNow();
        posterWorker.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
            rebuildTiles();
        }
    };

    /** The TV provider tells us when any app changes its rows: refresh within a few seconds. */
    private final android.database.ContentObserver providerObserver =
            new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    handler.removeCallbacks(refreshTick);
                    handler.postDelayed(refreshTick, 5000);
                }
            };

    /** Catches installs that happened while another app was in front (missed broadcasts). */
    private void refreshPackagesIfChanged() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        if (changed != null) {
            packageSequence = changed.getSequenceNumber();
            loadApps();
            rebuildTiles();
        }
    }

    /** Reads optional adb extras. Returns true when the dock changed. */
    private boolean applyConfig(Intent intent) {
        if (intent == null) {
            return false;
        }
        SharedPreferences.Editor edit = prefs.edit();
        boolean changed = false;
        String dockExtra = intent.getStringExtra(KEY_DOCK);
        if (dockExtra != null) {
            edit.putString(KEY_DOCK, dockExtra.trim());
            changed = true;
        }
        int seconds = intent.getIntExtra(KEY_INTERVAL, 0);
        if (seconds >= 10) {
            edit.putInt(KEY_INTERVAL, seconds);
        }
        if (intent.hasExtra(KEY_SHOW_PLACE)) {
            edit.putBoolean(KEY_SHOW_PLACE, intent.getBooleanExtra(KEY_SHOW_PLACE, false));
        }
        if (intent.hasExtra(KEY_SOUNDS)) {
            edit.putBoolean(KEY_SOUNDS, intent.getBooleanExtra(KEY_SOUNDS, true));
        }
        if (intent.hasExtra(KEY_BUILTIN)) {
            edit.putBoolean(KEY_BUILTIN, intent.getBooleanExtra(KEY_BUILTIN, true));
        }
        String profileName = intent.getStringExtra("profile_name");
        if (profileName != null && !profileName.trim().isEmpty()) {
            Profiles.rename(prefs, Profiles.current(prefs), profileName);
            handler.post(this::loadAvatar);
        }
        String folderSet = intent.getStringExtra(KEY_FOLDER_SET);
        String folderRemove = intent.getStringExtra(KEY_FOLDER_REMOVE);
        if (folderSet != null || folderRemove != null) {
            edit.apply();
            folders = Folders.load(prefs);
            if (folderRemove != null) {
                folders.remove(folderRemove.trim());
            }
            if (folderSet != null && folderSet.contains("=")) {
                String name = folderSet.substring(0, folderSet.indexOf('=')).trim();
                List<String> apps = new ArrayList<>();
                for (String pkg : folderSet.substring(folderSet.indexOf('=') + 1).split(",")) {
                    if (!pkg.trim().isEmpty()) {
                        Folders.move(folders, pkg.trim(), null);
                        apps.add(pkg.trim());
                    }
                }
                folders.put(name, apps);
            }
            Folders.save(prefs, folders);
            changed = true;
        }
        edit.apply();
        placeTitle.setVisibility(prefs.getBoolean(KEY_SHOW_PLACE, false) ? View.VISIBLE : View.GONE);
        return changed;
    }

    // ================================================================ layout

    private int px(float value) {
        return Math.round(value * dp);
    }

    private void computeGeometry() {
        // tvOS on a 1920x1080 screen: 90 pt side margins, five 300x180 app icons per row
        // (370x222 when focused), the rest is air between them.
        float pt = screenW / 1920f;
        CC_SCALE = CC_WIDTH_FRACTION * screenW / (px(TILE_W) * 2 + px(6));
        side = Math.round(90 * pt) - px(11);
        int inner0 = screenW - 2 * side - 2 * px(11);
        gap = Math.max(px(12), inner0 / COLUMNS - Math.round(250 * pt));
        shelfH = px(112);
        shelfTop = screenH - px(46) - (px(0) + 0) - px(112) - px(150);
        int inner = screenW - 2 * side - 2 * px(11);
        tileW = inner / COLUMNS - gap;
        tileH = tileW * 3 / 5;              // tvOS app icons are 400x240: 5:3, not 16:9
        trayH = tileH + px(22);
        trayClosedTop = screenH - px(26) - trayH;   // tvOS keeps the dock close to the bottom edge
        trayOpenTop = px(34);
        gridTop = trayOpenTop + trayH + px(22);
        gridClosedOffset = (screenH - px(14)) - gridTop - px(16);
        controlW = px(292);
        controlMargin = px(22);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        // The GL layer is a separate surface the TV cannot keep in step with the window
        // (Android 12 has no buffer sync): glass and cards drifted apart while moving.
        if (prefs.getBoolean("gl_glass", false) && (PERF & 64) == 0) {
            // Backdrop and Liquid Glass drawn per pixel on the GPU (see GlassLayer).
            glassLayer = new GlassLayer(this, dp);
            root.addView(glassLayer, full());
        }

        wallSharp = wallLayer(0f, 1f);
        root.addView(wallSharp, full());

        // (The top/bottom scrim is baked into each wallpaper bitmap; see vivid().)

        // Full-screen frosted layer, faded in while browsing all apps (35% dimming, as Apple advises).
        FrameLayout blur = new FrameLayout(this);
        blur.addView(wallLayer(36f, 1.3f), full());
        View dim = new View(this);
        dim.setBackgroundColor(0x6E000000);
        blur.addView(dim, full());
        blur.setAlpha(0f);
        blurLayer = blur;
        root.addView(blur, full());
        root.addView(buildHero(), full());
        if (glassLayer != null) {
            // The GL layer draws the wallpaper, the frost and the banner; these views keep
            // animating their alpha and scale, which the layer reads every frame.
            wallSharp.setVisibility(View.INVISIBLE);
            for (int i = 0; i < blur.getChildCount(); i++) {
                blur.getChildAt(i).setVisibility(View.INVISIBLE);
            }
            heroImage.setImageAlpha(0);
        }

        placeTitle = text(40, Typeface.create("sans-serif-condensed", Typeface.BOLD));
        placeTitle.setShadowLayer(px(16), 0, px(2), 0x99000000);
        placeTitle.setVisibility(View.GONE);
        root.addView(placeTitle, at(-2, -2, Gravity.TOP | Gravity.START, side, px(30), 0, 0));

        pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setClipChildren(false);
        inputsPill = pill("Surse");
        inputsPill.setOnClickListener(v -> showControl(true));
        clockPill = pill("");
        // tvOS 26: the time sits in a longer capsule and the profile picture overlaps its right end.
        clockPill.setPadding(px(16), 0, px(16) + px(30), 0);
        clockPill.setOnClickListener(v -> showControl(false));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, px(30));
        plp.leftMargin = px(10);
        pills.addView(inputsPill, plp);
        // While Cinema stays open behind a film's app, this takes you back to it.
        cinemaReturn = pill("Cinema");
        cinemaReturn.setPadding(px(10), 0, px(14), 0);
        cinemaReturn.setCompoundDrawablePadding(px(6));
        cinemaReturn.setCompoundDrawablesWithIntrinsicBounds(
                new android.graphics.drawable.BitmapDrawable(getResources(), CinemaMark.bitmap(px(18))), null, null, null);
        cinemaReturn.setOnClickListener(v -> returnToCinema());
        // Hold OK on it: leave Cinema mode (with its goodbye), without going back in first.
        cinemaReturn.setOnLongClickListener(v -> {
            swallowOkUp = true;
            play("select");
            try {
                startActivity(CinemaActivity.back(this, true));
                overridePendingTransition(0, 0);
            } catch (RuntimeException ignored) {
                cinemaReturn.setVisibility(View.GONE);
            }
            return true;
        });
        cinemaReturn.setVisibility(View.GONE);
        cinemaReturn.setId(View.generateViewId());
        pills.addView(cinemaReturn, new LinearLayout.LayoutParams(plp));
        pills.addView(clockPill, new LinearLayout.LayoutParams(plp));
        // The Google account's picture, in a glass ring like the tvOS profile button.
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setOutlineProvider(roundOutline(px(15)));
        avatar.setClipToOutline(true);
        final GlassDrawable ring = new GlassDrawable(px(15), dp, 0x47202226);
        avatar.setBackground(ring);
        glass(avatar, px(15), 0.6f);
        avatar.setImageBitmap(placeholderAvatar());
        avatar.setId(View.generateViewId());
        avatar.setFocusable(true);
        avatar.setOnClickListener(v -> showControl(false));
        avatar.setOnFocusChangeListener((v, has) -> {
            ring.setTint(has ? 0xF2FFFFFF : 0x47202226);
            avatar.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f)
                    .setInterpolator(EASE).setDuration(220).start();
            showHint(has);
        });
        // Sources live in the Control Center (and on the remote's Source key), like tvOS.
        inputsPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(30), px(30));
        alp.leftMargin = -px(32);
        pills.addView(avatar, alp);
        root.addView(pills, at(-2, -2, Gravity.TOP | Gravity.END, 0, px(26), side, 0));

        // "Up for full screen", shown above the tray while a pill is focused (tvOS 26 hint).
        hint = text(12, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setAlpha(0f);
        hint.setGravity(Gravity.CENTER);
        hint.setCompoundDrawablePadding(px(4));
        hint.setText("Sus: Control Center  ·  Play: ecran complet");
        hint.setTextColor(0xE6FFFFFF);
        hint.setCompoundDrawablesWithIntrinsicBounds(null, chevron(), null, null);

        home = new FrameLayout(this);
        home.setClipChildren(false);
        home.setClipToPadding(false);
        // Holds focus when nothing is selected (arriving home, Back from the row): invisible.
        idle = new View(this);
        // Focusable only while it holds focus: otherwise Up/Left from the grid or the bar would
        // find it as a place to go, and the selection would vanish.
        idle.setFocusable(false);
        idle.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                v.setFocusable(false);
            }
        });
        home.addView(idle, new FrameLayout.LayoutParams(1, 1));

        // The tray: a blurred, saturated copy of the wallpaper lined up with the screen,
        // under a glass surface with a specular rim.
        tray = new FrameLayout(this);
        final float trayRadius = px(14) + px(11);   // concentric with the icons inside it
        tray.setOutlineProvider(roundOutline(trayRadius));
        tray.setClipToOutline(true);
        // tvOS 26 Liquid Glass over media: a soft blur that keeps the wallpaper's hues,
        // lifted slightly towards white rather than dimmed to brown.
        trayPane = new LiquidGlassView(this, trayRadius, dp, screenW, screenH, 0x24FFFFFF, 0x1C000000);
        tray.addView(trayPane, full());
        trayPane.setVisibility(View.GONE);
        tray.setBackground(new GlassDrawable(trayRadius, dp, 0x24FFFFFF));
        glass(tray, trayRadius, 1f);
        buildShelf();
        home.addView(tray, at(screenW - 2 * side, trayH, Gravity.TOP | Gravity.START, side, trayClosedTop, 0, 0));
        home.addView(hint, at(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, trayClosedTop - px(44), 0, 0));

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(px(11), 0, px(11), 0);
        // Five apps fit across; more scroll sideways inside the tray, like the tvOS top row.
        final HorizontalScrollView dockScroll = new HorizontalScrollView(this) {
            private Spring spring;

            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                int max = Math.max(0, dock.getWidth() - getWidth());
                int left = focused.getLeft() - px(11) - gap / 2;
                int right = focused.getRight() + px(11) + gap / 2;
                float at = spring != null ? spring.target() : getScrollX();
                float target = at;
                if (left < at) {
                    target = Math.max(0, left);
                } else if (right > at + getWidth()) {
                    target = Math.min(max, right - getWidth());
                }
                if (spring == null) {
                    final HorizontalScrollView self = this;
                    spring = new Spring(getScrollX(), 0.5f, v -> self.scrollTo(Math.round(v), 0));
                    spring.configure(0.3f, 1f);
                }
                spring.to(target);
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }

            @Override
            public boolean executeKeyEvent(KeyEvent event) {
                return false;
            }
        };
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setFocusable(false);
        // Cards scrolled past the tray's ends are cut at its edges; the extra height above and
        // below leaves room for the focused card to grow without being clipped.
        int lift = px(40);
        dockScroll.setClipChildren(true);
        dockScroll.setClipToPadding(true);
        // ViewGroup clipping did not hold for the rotated, outlined cards: clip the whole render node.
        dockScroll.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        dockScroll.setClipToOutline(true);
        dockScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dockScroll.addView(dock, new FrameLayout.LayoutParams(-2, trayH, Gravity.CENTER_VERTICAL));
        dockHolder = dockScroll;
        home.addView(dockScroll, at(screenW - 2 * side, trayH + 2 * lift, Gravity.TOP | Gravity.START, side,
                trayClosedTop - lift, 0, 0));

        gridScroll = new ScrollView(this) {
            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                // Scroll only as far as needed: a row that is already fully visible stays put,
                // the first row sits a little below the top, the last row never leaves a gap.
                // The spring keeps its speed when focus moves again before it arrives.
                View row = (View) focused.getParent().getParent();
                int top = row.getTop() - px(16);
                int bottom = row.getBottom() + px(16);
                int visible = getHeight();
                float at = gridScrollSpring != null ? gridScrollSpring.target() : getScrollY();
                if (top < at) {
                    scrollGridTo(Math.max(0, top));
                } else if (bottom > at + visible) {
                    scrollGridTo(Math.min(bottom - visible, Math.max(0, grid.getHeight() - visible)));
                }
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;   // no instant jump before our spring starts
            }

            @Override
            public boolean executeKeyEvent(KeyEvent event) {
                return false;   // focus moves by the D-pad; we scroll; no page jumps at the last row
            }
        };
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setFocusable(false);
        gridScroll.setClipChildren(false);
        gridScroll.setClipToPadding(false);
        gridScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(side + px(11), px(16), side + px(11), px(40));
        gridScroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        gridScroll.setTranslationY(gridClosedOffset);
        home.addView(gridScroll, at(-1, screenH - gridTop, Gravity.TOP, 0, gridTop, 0, 0));
        root.addView(home, full());

        control = buildControl();
        root.addView(control, full());
        root.addView(buildVolumeHud(), at(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, side, 0));

        // Keep the blurred copies inside the tray and the Control Center lined up with the
        // wallpaper behind them while those panes move.
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            float heroA = hero.getAlpha() * heroImage.getAlpha();
            float frostA = blurLayer.getAlpha();
            if (heroA != lastHeroAlpha || frostA != lastFrostAlpha) {
                lastHeroAlpha = heroA;
                lastFrostAlpha = frostA;
                backdrop.heroAlpha = heroA;
                backdrop.frostAlpha = frostA;
                invalidateGlass();
            }
            if (glassLayer != null) {
                float scale = wallLayers.isEmpty() ? 1f : wallLayers.get(0)[front].getScaleX();
                glassLayer.sync(hero.getAlpha() * heroImage.getAlpha(), blurLayer.getAlpha(), scale);
            } else {
                trayPane.sync();
                controlPane.sync();
            }
            return true;
        });

        inputsPill.setId(View.generateViewId());
        clockPill.setId(View.generateViewId());
        if (glassLayer != null) {
            // A translucent window is cleared every frame, so nothing smears over the GL layer.
            getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        }
        setContentView(root);
        if ((PERF & 8) != 0) {
            gridScroll.setVisibility(View.INVISIBLE);
        }
        if ((PERF & 32) != 0) {
            trayPane.setVisibility(View.INVISIBLE);
        }
    }

    private FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static FrameLayout.LayoutParams at(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private static ViewOutlineProvider roundOutline(final float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    /** Two stacked wallpaper views (for cross-fading), optionally blurred and saturated like glass. */
    private FrameLayout wallLayer(float blurDp, float saturation) {
        FrameLayout layer = new FrameLayout(this);
        ImageView[] pair = new ImageView[2];
        boolean glass = blurDp > 0;
        for (int i = 0; i < 2; i++) {
            ImageView view = new ImageView(this);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            // Colour is baked into the bitmaps once per wallpaper (see vivid() and frosted()):
            // a colour filter here would be re-applied to two million pixels on every frame.

            view.setTag(glass ? "glass" : "sharp");
            layer.addView(view, full());
            pair[i] = view;
        }
        wallLayers.add(pair);
        return layer;
    }

    /** Pixels for a bitmap shown inside Control Center, which is drawn CC_SCALE larger. */
    private int ccPx(float value) {
        return Math.round(px(value) * CC_SCALE);
    }

    /**
     * Single-line text that gets smaller (down to minSp) rather than cut off with "...", and
     * back to its size when there is room again (a panel that grows out of a tile).
     */
    private static void shrinkToFit(final TextView view, final float minSp) {
        final float original = view.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity;
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int room = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
                if (room <= 0) {
                    return;
                }
                float density = view.getResources().getDisplayMetrics().scaledDensity;
                float sp = view.getTextSize() / density;
                String text = view.getText().toString();
                android.text.TextPaint paint = new android.text.TextPaint(view.getPaint());
                float fitted = original;
                paint.setTextSize(fitted * density);
                while (fitted > minSp && paint.measureText(text) > room) {
                    fitted -= 0.5f;
                    paint.setTextSize(fitted * density);
                }
                if (Math.abs(fitted - sp) > 0.01f) {
                    final float size = fitted;
                    view.post(() -> view.setTextSize(size));
                }
            }
        });
    }

    private TextView text(float sp, Typeface typeface) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(typeface);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** A small glass capsule; focused, it turns white like tvOS buttons. */
    /** Makes the view a pane of Liquid Glass: a lens of the backdrop under its own surface. */
    private void glass(View view, float radius, float strength) {
        if (glassLayer != null) {
            glassLayer.register(view, radius, strength);
            return;
        }
        android.graphics.drawable.Drawable surface = view.getBackground();
        LensDrawable lens = new LensDrawable(view, backdrop, radius, strength, dp);
        view.setBackground(surface == null ? lens
                : new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{lens, surface}));
        lensViews.add(view);
    }

    /** Redraws every pane of glass (the backdrop behind them changed). */
    private void invalidateGlass() {
        java.util.Iterator<View> it = lensViews.iterator();
        while (it.hasNext()) {
            View v = it.next();
            if (!v.isAttachedToWindow() && v.getParent() == null) {
                it.remove();
            } else {
                v.invalidate();
            }
        }
    }

    private TextView pill(String label) {
        final TextView pill = text(13, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setText(label);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(px(14), 0, px(14), 0);
        pill.setShadowLayer(0, 0, 0, 0);
        final GlassDrawable glass = new GlassDrawable(px(15), dp, 0x47202226);
        pill.setBackground(glass);
        glass(pill, px(15), 0.6f);
        pill.setFocusable(true);
        pill.setOnFocusChangeListener((v, has) -> {
            glass.setTint(has ? 0xF2FFFFFF : 0x47202226);
            pill.setTextColor(has ? 0xFF111111 : Color.WHITE);
            pill.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(EASE).setDuration(220).start();
            showHint(has);
        });
        return pill;
    }

    // ================================================================ tiles and focus

    /** A rounded 16:9 tile with the app's TV banner (or icon and name) and a specular sheen. */
    private FrameLayout tile(final App app) {
        FrameLayout tile = new FrameLayout(this);
        int tint = tintOf(app);
        final float radius = px(14);        // tvOS 26 icons: about 11% of the tile height
        GradientDrawable bg = new GradientDrawable();
        // Banners often carry their own transparent corners; filling the tile with the
        // banner's average colour hides the step that a dark background would show there.
        if (app.banner != null) {
            bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            bg.setColors(new int[]{edgeOf(app, true), edgeOf(app, false)});
        } else {
            bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            bg.setColors(new int[]{0xFF4A505A, 0xFF2A2E35});
        }
        bg.setCornerRadius(radius);
        tile.setBackground(bg);
        tile.setOutlineProvider(roundOutline(radius));
        tile.setClipToOutline((PERF & 4) == 0);
        tile.setTag(app.pkg);
        applyShadow(tile, tint);
        // A far camera keeps the tilt from turning the rectangle into a trapezoid, and a
        // hardware layer makes scale/tilt/lift animations a texture transform only.
        tile.setCameraDistance(12000 * dp);

        ImageView image = new ImageView(this);
        if (app.banner != null) {
            // Android banners are 16:9, our tiles 5:3 like tvOS: show the whole banner (logos
            // such as Spotify's run edge to edge) and let the banner's own edge colour fill the
            // thin bands above and below.
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(app.banner);
            tile.addView(image, full());
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(app.icon);
            int size = tileH * 9 / 20;
            tile.addView(image, at(size, size, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, tileH / 7, 0, 0));
            TextView name = text(11, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setShadowLayer(0, 0, 0, 0);
            name.setText(app.label);
            name.setGravity(Gravity.CENTER);
            tile.addView(name, at(-1, -2, Gravity.BOTTOM, px(6), 0, px(6), tileH / 9));
        }

        GradientDrawable sheen = new GradientDrawable();
        sheen.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        sheen.setColors(new int[]{0x26FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheen.setGradientRadius(tileW * 0.85f);
        sheen.setGradientCenter(0.25f, 0.1f);
        View shine = new View(this);
        shine.setBackground(sheen);
        shine.setAlpha(0f);
        shine.setTag("shine");
        tile.addView(shine, full());

        tile.setFocusable(true);
        tile.setOnClickListener(v -> launchFrom(v, app));
        tile.setOnKeyListener((v, keyCode, event) -> {
            boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
            if (editing != null) {
                return false;
            }
            if (ok && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                // Pressed: the tile sinks back towards the glass, fast.
                motion(v).focus.to(0.35f, 0.12f, 1f);
            } else if (ok && event.getAction() == KeyEvent.ACTION_UP && v.hasFocus()) {
                // Released: springs back up a little past its focused size.
                Motion m = motion(v);
                m.focus.configure(0.32f, 0.55f);
                m.focus.to(1f);
            }
            return false;
        });
        return tile;
    }

    /**
     * Per-tile motion state. One spring drives "how focused" the tile is (scale, lift, sheen),
     * one the parallax tilt, one a sideways nudge. Springs keep their velocity when retargeted,
     * so fast navigation never snaps.
     */
    final class Motion {
        final View tile;
        final View shine;
        final GradientDrawable sheen;
        final Spring focus;
        final Spring tiltY;
        final Spring tiltX;
        final Spring nudge;

        Motion(View tile) {
            this.tile = tile;
            shine = tile.findViewWithTag("shine");
            sheen = shine != null ? (GradientDrawable) shine.getBackground() : null;
            focus = new Spring(0f, 0.002f, value -> apply());
            tiltY = new Spring(0f, 0.02f, value -> apply());
            tiltX = new Spring(0f, 0.02f, value -> apply());
            nudge = new Spring(0f, 0.3f, value -> tile.setTranslationX(value));
            tiltY.configure(0.5f, 0.5f);
            tiltX.configure(0.5f, 0.5f);
            nudge.configure(0.3f, 0.4f);
        }

        void apply() {
            float p = focus.value();
            float scale = 1f + (FOCUS_SCALE - 1f) * p;
            tile.setScaleX(scale);
            tile.setScaleY(scale);
            // Only a lifted tile casts a shadow: a shadow per resting tile costs the GPU dearly.
            // Only apps in the grid cast a shadow (a soft black pool drawn by their cell);
            // the dock cards sit on the glass tray without one, as on tvOS.
            tile.setTranslationZ(0f);
            if (tile.getParent() instanceof View) {
                android.graphics.drawable.Drawable bg = ((View) tile.getParent()).getBackground();
                if (bg instanceof FocusShadow) {
                    ((FocusShadow) bg).setAmount(p);
                }
            }
            tile.setRotationY((PERF & 1) != 0 ? 0f : tiltY.value());
            tile.setRotationX((PERF & 1) != 0 ? 0f : tiltX.value());
            if (shine != null) {
                shine.setAlpha(Math.max(0f, Math.min(1f, p)));
                if (sheen != null) {
                    sheen.setGradientCenter(0.3f + tiltY.value() * 0.06f, 0.12f - tiltX.value() * 0.06f);
                }
            }
        }

        void stopAll() {
            focus.stop();
            tiltY.stop();
            tiltX.stop();
            nudge.stop();
        }
    }

    private Motion motion(View tile) {
        Motion m = motions.get(tile);
        if (m == null) {
            m = new Motion(tile);
            motions.put(tile, m);
        }
        return m;
    }

    /**
     * tvOS focus: the newly focused tile pops up quickly with a slight overshoot, the one left
     * behind sinks slower and softer; moving fast shortens both (WWDC17 "Focus Interaction").
     * A D-pad move also tilts the arriving tile towards where focus came from, then it settles,
     * which is what the parallax does on a remote without a touch surface.
     */
    private void focusEffect(View tile, boolean on) {
        Motion m = motion(tile);
        long now = SystemClock.uptimeMillis();
        boolean fast = now - lastFocusAt < 170;
        if (on) {
            lastFocusAt = now;
            m.focus.to(1f, fast ? 0.16f : 0.30f, fast ? 1f : 0.72f);
            if (now - moveAt < 250 && !fast) {
                m.tiltY.from(-moveDx * 3.5f);
                m.tiltY.to(0f);
                m.tiltX.from(moveDy * 3f);
                m.tiltX.to(0f);
            }
        } else {
            m.focus.to(0f, fast ? 0.2f : 0.42f, 1f);
            m.tiltY.to(0f);
            m.tiltX.to(0f);
        }
    }

    /** Kept for edit mode; the endless sway redrew the whole screen 60 times a second for nothing. */
    private void startSway(final View tile) {
        if (tile != null) {
            return;
        }
        stopSway();
        final View shine = tile.findViewWithTag("shine");
        final GradientDrawable sheen = shine != null ? (GradientDrawable) shine.getBackground() : null;
        swayTile = tile;
        sway = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        sway.setDuration(7000);
        sway.setRepeatCount(ValueAnimator.INFINITE);
        sway.setInterpolator(new LinearInterpolator());
        sway.setStartDelay(350);
        sway.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Subtle: tvOS parallax tilts by a degree or so; more than that bends the tile.
            float rx = (float) Math.sin(t) * 0.9f;
            float ry = (float) Math.cos(t * 2f) * 1.2f;
            tile.setRotationX(rx);
            tile.setRotationY(ry);
            if (sheen != null) {
                sheen.setGradientCenter(0.3f + ry * 0.09f, 0.12f - rx * 0.08f);
            }
        });
        sway.start();
    }

    private void stopSway() {
        if (sway != null) {
            sway.cancel();
            sway = null;
        }
        swayTile = null;
    }

    /** At the end of a row, nudge the tile instead of doing nothing (tvOS edge bounce). */
    private void bump(final View view, int direction) {
        play("limit");
        // A push in the direction pressed; the spring pulls it back with one soft wobble.
        motion(view).nudge.kick(direction * px(260));
    }

    /** The colour along the banner's border (opaque pixels only), for the bands around it. */
    private int edgeOf(App app, boolean top) {
        String key = (top ? "top:" : "bottom:") + app.pkg;
        Integer cached = tintCache.get(key);
        if (cached != null) {
            return cached;
        }
        int color = tintOf(app);
        try {
            int w = 64;
            int h = 36;
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            app.banner.setBounds(0, 0, w, h);
            app.banner.draw(new Canvas(small));
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int x = 0; x < w; x++) {
                for (int y : top ? new int[]{1, 2} : new int[]{h - 3, h - 2}) {
                    int c = small.getPixel(x, y);
                    if (Color.alpha(c) > 200) {
                        r += Color.red(c);
                        g += Color.green(c);
                        b += Color.blue(c);
                        n++;
                    }
                }
            }
            if (n > w / 2) {
                color = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            }
            small.recycle();
        } catch (RuntimeException ignored) {
            // Keep the average colour.
        }
        tintCache.put(key, color);
        return color;
    }

    /** Average colour of the banner, used to tint the focus shadow. */
    private int tintOf(App app) {
        Integer cached = tintCache.get(app.pkg);
        if (cached != null) {
            return cached;
        }
        int color = Color.BLACK;
        Drawable d = app.banner != null ? app.banner : app.icon;
        if (d != null) {
            try {
                // Sample the middle of the banner: the edges are often transparent or a frame.
                Bitmap small = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(small);
                d.setBounds(0, 0, 16, 9);
                d.draw(canvas);
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int y = 2; y < 7; y++) {
                    for (int x = 3; x < 13; x++) {
                        int c = small.getPixel(x, y);
                        if (Color.alpha(c) > 128) {
                            r += Color.red(c);
                            g += Color.green(c);
                            b += Color.blue(c);
                            n++;
                        }
                    }
                }
                if (n > 0) {
                    color = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
                }
                small.recycle();
            } catch (RuntimeException ignored) {
                // Keep the default shadow.
            }
        }
        tintCache.put(app.pkg, color);
        return color;
    }

    // ================================================================ apps

    private void loadApps() {
        PackageManager pm = getPackageManager();
        apps.clear();
        addLaunchables(pm, Intent.CATEGORY_LEANBACK_LAUNCHER);
        addLaunchables(pm, Intent.CATEGORY_LAUNCHER);
        // Settings as its own tile, drawn like the tvOS Settings icon (a gear on grey).
        App settings = new App();
        settings.pkg = SETTINGS_TILE;
        settings.label = "Setări";
        settings.banner = new android.graphics.drawable.BitmapDrawable(getResources(), gearBanner());
        settings.icon = settings.banner;
        // Its own task: the home task clears itself on every launch and would close Settings at once.
        settings.intent = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        apps.put(settings.pkg, settings);
        // The TV: the antenna/cable channels, played by TV Home's own channel screen.
        App tv = new App();
        tv.pkg = LIVE_TILE;
        tv.label = "TV";
        tv.banner = new android.graphics.drawable.BitmapDrawable(getResources(), tvBanner());
        tv.icon = tv.banner;
        tv.intent = new Intent(this, ChannelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        apps.put(tv.pkg, tv);
        sortedApps.clear();
        sortedApps.addAll(apps.values());
        Collections.sort(sortedApps, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
    }

    private void addLaunchables(PackageManager pm, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || HIDDEN.contains(pkg) || apps.containsKey(pkg)) {
                continue;
            }
            App app = new App();
            app.pkg = pkg;
            app.label = ri.loadLabel(pm);
            app.banner = ri.activityInfo.loadBanner(pm);
            if (app.banner == null) {
                app.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            app.icon = ri.loadIcon(pm);
            app.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.put(pkg, app);
        }
    }

    private boolean launch(App app) {
        return launch(app.intent, app.label);
    }

    /** Like tvOS: the app grows out of the tile that was pressed, and shrinks back into it later. */
    private boolean launchFrom(View tile, App app) {
        returnTile = tile;
        try {
            Intent intent = new Intent(app.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            play("select");
            android.app.ActivityOptions options = android.app.ActivityOptions.makeScaleUpAnimation(
                    tile, 0, 0, tile.getWidth(), tile.getHeight());
            startActivity(intent, options.toBundle());
            return true;
        } catch (RuntimeException e) {
            returnTile = null;
            return launch(app);
        }
    }

    private boolean launch(Intent intent, CharSequence label) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            play("select");
            startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            SourcesService.say(this, "Nu pot deschide " + label);
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) {
            intent = getPackageManager().getLaunchIntentForPackage(pkg);
        }
        if (intent != null) {
            launch(intent, pkg);
        }
    }

    // ================================================================ dock and grid

    private List<String> dockPackages() {
        String saved = prefs.getString(Profiles.scoped(prefs, KEY_DOCK), null);
        List<String> list = new ArrayList<>();
        if (saved == null) {
            list.addAll(DEFAULT_DOCK);
        } else {
            for (String pkg : saved.split(",")) {
                if (!pkg.trim().isEmpty()) {
                    list.add(pkg.trim());
                }
            }
        }
        list.removeAll(hiddenApps());
        // The TV tile joins the row once (first place); after that the row is the user's.
        String added = Profiles.scoped(prefs, "tv_tile_added");
        if (!prefs.getBoolean(added, false)) {
            if (!list.contains(LIVE_TILE)) {
                list.add(0, LIVE_TILE);
            }
            prefs.edit().putBoolean(added, true)
                    .putString(Profiles.scoped(prefs, KEY_DOCK), TextUtils.join(",", list)).apply();
        }
        return list;
    }

    private static final String KEY_HIDDEN = "hidden_apps";

    /** Apps the current profile chose not to see (they stay installed for everyone). */
    private java.util.Set<String> hiddenApps() {
        return new java.util.HashSet<>(prefs.getStringSet(Profiles.scoped(prefs, KEY_HIDDEN), new java.util.HashSet<>()));
    }

    private void setHidden(String pkg, boolean hide) {
        java.util.Set<String> set = hiddenApps();
        if (hide) {
            set.add(pkg);
        } else {
            set.remove(pkg);
        }
        prefs.edit().putStringSet(Profiles.scoped(prefs, KEY_HIDDEN), set).apply();
        rebuildTiles();
    }

    private void saveDock(List<String> list) {
        prefs.edit().putString(Profiles.scoped(prefs, KEY_DOCK), TextUtils.join(",", list)).apply();
        rebuildTiles();
    }

    private void toggleDock(App app) {
        List<String> list = dockPackages();
        if (list.remove(app.pkg)) {
            SourcesService.say(this, app.label + " a fost scos din rândul de sus");
        } else {
            list.add(app.pkg);
            SourcesService.say(this, app.label + " a fost pus în rândul de sus");
        }
        saveDock(list);
    }

    private void rebuildTiles() {
        // While the rows are rebuilt no stray view may take focus, or the grid would open by itself.
        rebuilding = true;
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stopSway();
        dock.removeAllViews();
        grid.removeAllViews();
        List<String> docked = dockPackages();

        for (String pkg : docked) {
            final App app = apps.get(pkg);
            if (app == null) {
                continue;
            }
            FrameLayout tile = tile(app);
            tile.setId(View.generateViewId());
            tile.setOnFocusChangeListener((v, has) -> {
                focusEffect(v, has);
                if (has) {
                    lastDockIndex = dock.indexOfChild(v);
                    setOpen(false);
                    showShelf(app.pkg);
                    if (LIVE_TILE.equals(app.pkg)) {
                        startFirstCardTv(v);
                    }
                } else if (LIVE_TILE.equals(app.pkg) && !peekFocused()) {
                    stopPeekTv();
                }
            });
            // Hold OK: the tile starts to wobble and left/right move it, like tvOS.
            tile.setOnLongClickListener(v -> {
                startEdit(v);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.setMargins(gap / 2, 0, gap / 2, 0);
            dock.addView(tile, lp);
        }

        LinearLayout row = null;
        int column = 0;
        List<Object> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> folder : folders.entrySet()) {
            boolean any = false;
            for (String pkg : folder.getValue()) {
                any |= apps.containsKey(pkg) && !docked.contains(pkg);
            }
            if (any) {
                entries.add(folder.getKey());
            }
        }
        java.util.Set<String> hidden = hiddenApps();
        for (App app : sortedApps) {
            if (!docked.contains(app.pkg) && Folders.of(folders, app.pkg) == null && !hidden.contains(app.pkg)) {
                entries.add(app);
            }
        }
        for (final Object entry : entries) {
            final App app = entry instanceof App ? (App) entry : null;
            final String folderName = entry instanceof String ? (String) entry : null;
            if (row == null || column == COLUMNS) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, new LinearLayout.LayoutParams(-1, tileH + px(40)));
                column = 0;
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout tile = app != null ? tile(app) : folderTile(folderName);
            cell.setBackground(new FocusShadow(tile));
            tile.setId(View.generateViewId());
            final TextView name = text(12, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setText(app != null ? app.label : folderName);
            name.setGravity(Gravity.CENTER);
            name.setAlpha(0f);
            tile.setOnFocusChangeListener((v, has) -> {
                focusEffect(v, has);
                // tvOS shows the name under the focused app only.
                name.animate().alpha(has ? 1f : 0f).translationY(has ? px(4) : 0).setDuration(200).start();
                if (has) {
                    setOpen(true);
                }
            });
            tile.setOnLongClickListener(v -> {
                if (app != null) {
                    organiseMenu(app, v);
                } else {
                    folderMenu(folderName);
                }
                return true;
            });
            cell.addView(tile, new LinearLayout.LayoutParams(tileW, tileH));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(tileW + gap, -2);
            nlp.topMargin = px(10);
            cell.addView(name, nlp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(tileW, -1);
            clp.setMargins(gap / 2, 0, gap / 2, 0);
            row.addView(cell, clp);
            column++;
        }
        wireFocus();
        for (String pkg : docked) {
            if (apps.containsKey(pkg)) {
                prefetchPreviews(pkg);
            }
        }
        rebuilding = false;
        if (control.getVisibility() != View.VISIBLE) {
            home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            setOpen(false);
            View now = getCurrentFocus();
            if (now == null || now == idle) {
                focusNothing();
            } else {
                focusDock(lastDockIndex);
            }
        }
    }

    private View gridTile(int row, int column) {
        ViewGroup r = (ViewGroup) grid.getChildAt(row);
        ViewGroup cell = (ViewGroup) r.getChildAt(Math.min(column, r.getChildCount() - 1));
        return cell.getChildAt(0);
    }

    /** Deterministic D-pad paths between the pills, the dock and the grid. */
    private void wireFocus() {
        boolean hasGrid = grid.getChildCount() > 0;
        int dockCount = dock.getChildCount();
        for (int i = 0; i < dockCount; i++) {
            View tile = dock.getChildAt(i);
            tile.setNextFocusUpId(avatar.getId());
            tile.setNextFocusDownId(hasGrid ? gridTile(0, i).getId() : tile.getId());
        }
        if (hasGrid) {
            ViewGroup firstRow = (ViewGroup) grid.getChildAt(0);
            for (int i = 0; i < firstRow.getChildCount(); i++) {
                View tile = gridTile(0, i);
                tile.setNextFocusUpId(dockCount > 0
                        ? dock.getChildAt(Math.min(i, dockCount - 1)).getId() : avatar.getId());
            }
        }
        int below = dockCount > 0 ? dock.getChildAt(Math.min(lastDockIndex, dockCount - 1)).getId()
                : hasGrid ? gridTile(0, 0).getId() : View.NO_ID;
        avatar.setNextFocusDownId(below);
        avatar.setNextFocusUpId(avatar.getId());
        avatar.setNextFocusLeftId(avatar.getId());
        avatar.setNextFocusRightId(avatar.getId());
    }

    private View idle;

    /** Nothing selected: the row of apps rests until Down (or Left/Right/OK) picks one. */
    private void focusNothing() {
        if (idle != null) {
            idle.setFocusable(true);
            idle.requestFocus();
        }
        shelfPkg = null;
        hidePeek();
    }

    // ---------------------------------------------------------------- preview cards over the bar
    //
    // Each app shows only what that app publishes; its filters come from the app's own data
    // (its rows, the real type of each item, its genres, "continue watching"). The TV tile shows
    // the channels, and the focused channel plays live, muted, inside its card.

    static final String LIVE_TILE = "ro.tinulica.tvhome.live";
    private static final int PEEK_H = 214;   // dp, the cards' height
    private FrameLayout peekLayer;
    private HorizontalScrollView peekScroll;
    private LinearLayout peek;
    private LinearLayout chips;
    private Spring peekSpring;
    private String peekPkg;
    private String chipsPkg;
    private String chipsKey;
    private String pendingTab;
    private final Runnable peekShow = () -> renderPeek(peekPkg);
    private final Runnable tabApply = this::applyTab;
    private final Map<String, Integer> peekTurn = new HashMap<>();
    private long lastVerticalAt;
    private String lastPeekSig;
    private String lastPeekPkg;
    private final Map<String, List<LiveNow.Entry>> liveCache = new HashMap<>();
    private final Map<String, Long> liveCacheAt = new HashMap<>();
    private android.media.tv.TvView peekTv;
    private Runnable peekTvStart;

    /** One card: its picture, title, what OK does, and the channel it can play live (or none). */
    private static final class Card {
        int width;
        String title;
        String hideKey;
        BitmapSource picture;
        Runnable open;
        ChannelActivity.Channel channel;
    }

    interface BitmapSource {
        Bitmap load();
    }

    /** After the selection rests on an app for a moment, its newest programmes rise over the bar. */
    private void schedulePeek(String pkg) {
        peekPkg = pkg;
        peekAskedAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(peekShow);
        handler.postDelayed(peekShow, 450);
    }

    private long peekAskedAt;
    /** Up was pressed on the bar before the cards were up: go into them as soon as they are. */
    private boolean enterPeekWhenReady;

    // -- filters -------------------------------------------------------------------------------

    /** The kind of an item from its real program type. */
    private static String kindOf(Previews.Item it) {
        if (it.live || it.type == 5) {
            return "Live";
        }
        switch (it.type) {
            case 0: return "Filme";
            case 1:
            case 2:
            case 3: return "Seriale";
            case 4: return "Clipuri";
            case 6: return "Canale";
            case 7:
            case 8:
            case 9:
            case 10:
            case 11: return "Muzică";
            case 12: return "Jocuri";
            default: return null;
        }
    }

    private static String genreLabel(String g) {
        switch (g) {
            case "FAMILY_KIDS": return "Copii";
            case "SPORTS": return "Sport";
            case "MOVIES": return "Filme";
            case "COMEDY": return "Comedie";
            case "DRAMA": return "Dramă";
            case "NEWS": return "Știri";
            case "GAMING": return "Gaming";
            case "MUSIC": return "Muzică";
            case "ANIMAL_WILDLIFE": return "Natură";
            case "TECH_SCIENCE": return "Știință";
            case "EDUCATION": return "Educație";
            case "TRAVEL": return "Călătorii";
            case "ENTERTAINMENT": return "Divertisment";
            case "LIFE_STYLE": return "Stil de viață";
            case "ARTS": return "Artă";
            case "PREMIER": return "Premiere";
            case "SHOPPING": return "Shopping";
            default: return g.charAt(0) + g.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private static List<String> genresOf(Previews.Item it) {
        List<String> out = new ArrayList<>();
        if (it.genre != null) {
            for (String g : it.genre.split(",")) {
                g = g.trim().replace("\"", "");
                if (!g.isEmpty()) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    private static boolean keeps(String tab, Previews.Item it) {
        if ("all".equals(tab)) {
            return true;
        }
        if ("go".equals(tab)) {
            return it.watchNext;
        }
        if (tab.startsWith("row:")) {
            return !it.watchNext && tab.substring(4).equals(it.channel);
        }
        if ("top10".equals(tab)) {
            return it.channel != null && it.channel.startsWith("Top 10");
        }
        if (tab.startsWith("kind:")) {
            return tab.substring(5).equals(kindOf(it));
        }
        if (tab.startsWith("genre:")) {
            return genresOf(it).contains(tab.substring(6));
        }
        return false;
    }

    /**
     * An app's real filters, from what it published: "for you", "continue watching" (if any),
     * its own rows (when it has more than one), the kinds of items (when they differ) and the
     * genres that several items share. Each entry is {id, label}.
     */
    private static List<String[]> tabsFor(List<Previews.Item> items) {
        List<String[]> tabs = new ArrayList<>();
        tabs.add(new String[]{"all", "Pentru tine"});
        boolean going = false;
        Map<String, Integer> rows = new LinkedHashMap<>();
        Map<String, Integer> kinds = new LinkedHashMap<>();
        Map<String, Integer> genres = new LinkedHashMap<>();
        for (Previews.Item it : items) {
            going |= it.watchNext;
            if (!it.watchNext && it.channel != null && !it.channel.trim().isEmpty()) {
                rows.merge(it.channel, 1, Integer::sum);
            }
            String k = kindOf(it);
            if (k != null) {
                kinds.merge(k, 1, Integer::sum);
            }
            for (String g : genresOf(it)) {
                genres.merge(g, 1, Integer::sum);
            }
        }
        if (going) {
            tabs.add(new String[]{"go", "Continuă"});
        }
        if (rows.size() > 1) {
            boolean top = false;
            for (String r : rows.keySet()) {
                if (r.startsWith("Top 10")) {
                    if (!top) {
                        tabs.add(new String[]{"top10", "Top 10"}); // all the Top 10 rows in one
                        top = true;
                    }
                } else {
                    tabs.add(new String[]{"row:" + r, shortLabel(r)});
                }
            }
        }
        if (kinds.size() > 1) {
            for (String k : kinds.keySet()) {
                tabs.add(new String[]{"kind:" + k, k});
            }
        }
        int added = 0;
        for (Map.Entry<String, Integer> g : genres.entrySet()) {
            if (g.getValue() >= 2 && g.getValue() < items.size() && added < 6) {
                tabs.add(new String[]{"genre:" + g.getKey(), genreLabel(g.getKey())});
                added++;
            }
        }
        // Like the Apple TV app: a handful of tabs, never a long strip.
        return tabs.size() > 6 ? new ArrayList<>(tabs.subList(0, 6)) : tabs;
    }

    /** A row's name as a short tab ("YouTube Music" stays, long descriptions are cut). */
    private static String shortLabel(String row) {
        String r = row.trim();
        if (r.length() <= 16) {
            return r;
        }
        int cut = r.lastIndexOf(' ', 16);
        return (cut > 6 ? r.substring(0, cut) : r.substring(0, 15)) + "…";
    }

    /** The TV tile's filters: all, favourites, then the channel categories that exist. */
    private List<String[]> liveTabs(List<ChannelActivity.Channel> channels) {
        List<String[]> tabs = new ArrayList<>();
        tabs.add(new String[]{"all", "Acum"});
        if (!prefs.getStringSet(Profiles.scoped(prefs, "live_tv_favourites"), new HashSet<>()).isEmpty()) {
            tabs.add(new String[]{"fav", "Favorite"});
        }
        for (String cat : new String[]{"Știri", "Sport", "Filme", "Copii", "Documentare", "Muzică", "Generale"}) {
            for (ChannelActivity.Channel ch : channels) {
                if (cat.equals(ch.category)) {
                    tabs.add(new String[]{"cat:" + cat, cat});
                    break;
                }
            }
        }
        return tabs.size() > 7 ? new ArrayList<>(tabs.subList(0, 7)) : tabs;
    }

    /** The filter chosen for an app (each app, and each profile, remembers its own). */
    private String tabOf(String pkg, List<String[]> tabs) {
        String t = prefs.getString(Profiles.scoped(prefs, "peek_tab2_" + pkg), "all");
        for (String[] ok : tabs) {
            if (ok[0].equals(t)) {
                return t;
            }
        }
        return "all";
    }

    private List<String[]> shownTabs = new ArrayList<>();

    // -- layout --------------------------------------------------------------------------------

    private void ensurePeek() {
        if (peekLayer != null) {
            return;
        }
        int h = px(PEEK_H);
        int chipsH = px(34);
        int top = px(24);       // room for the focused card to grow
        int below = px(34);     // the focused card's title
        peekLayer = new FrameLayout(this);
        peekLayer.setClipChildren(false);
        peekLayer.setClipToPadding(false);
        // A subtle blur band behind the cards: the frosted wallpaper, fading in and out.
        View band = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final int[] where = new int[2];
            private final android.graphics.Matrix map = new android.graphics.Matrix();

            {
                mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                setLayerType(LAYER_TYPE_HARDWARE, null);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                Bitmap b = backdrop.wallFront;
                if (b == null || b.isRecycled()) {
                    return;
                }
                getLocationOnScreen(where);
                BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                map.setScale(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
                map.postTranslate(-where[0], -where[1]);
                shader.setLocalMatrix(map);
                paint.setShader(shader);
                int w = getWidth();
                int hh = getHeight();
                canvas.drawRect(0, 0, w, hh, paint);
                canvas.drawColor(0x14000000);
                mask.setShader(new LinearGradient(0, 0, 0, hh, new int[]{0x00000000, 0x99000000, 0x99000000, 0x00000000},
                        new float[]{0f, 0.3f, 0.75f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, hh, mask);
            }
        };
        band.setTag("band");
        band.setAlpha(0f);
        peekLayer.addView(band, new FrameLayout.LayoutParams(-1, -1));
        peekScroll = new HorizontalScrollView(this) {
            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                // The focused card stays a little in from the edges; the row glides on a spring.
                View cell = (View) focused.getParent();
                int max = Math.max(0, peek.getWidth() - getWidth());
                float at = peekSpring != null ? peekSpring.target() : getScrollX();
                float target = at;
                int left = cell.getLeft() - side - px(11);
                int right = cell.getRight() + side + px(11);
                if (left < at) {
                    target = Math.max(0, left);
                } else if (right > at + getWidth()) {
                    target = Math.min(max, right - getWidth());
                }
                if (peekSpring == null) {
                    final HorizontalScrollView self = this;
                    peekSpring = new Spring(getScrollX(), 0.5f, v -> self.scrollTo(Math.round(v), 0));
                    peekSpring.configure(0.32f, 1f);
                }
                peekSpring.to(target);
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }

            @Override
            public boolean executeKeyEvent(KeyEvent event) {
                return false;
            }
        };
        peekScroll.setHorizontalScrollBarEnabled(false);
        peekScroll.setFocusable(false);
        peekScroll.setClipChildren(false);
        peekScroll.setClipToPadding(false);
        peekScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        peek = new LinearLayout(this);
        peek.setOrientation(LinearLayout.HORIZONTAL);
        peek.setClipChildren(false);
        peek.setClipToPadding(false);
        peek.setPadding(side + px(11), top, side + px(11), 0);
        peekScroll.addView(peek, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipChildren(false);
        chipsScroll.setClipToPadding(false);
        chipsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setClipChildren(false);
        chips.setClipToPadding(false);
        // All the tabs in one Liquid Glass capsule, centred, like the Apple TV app's tab bar.
        chips.setPadding(px(4), px(4), px(4), px(4));
        chips.setGravity(Gravity.CENTER_VERTICAL);
        // The capsule holds one pill that glides between the tabs (behind their text).
        FrameLayout chipsBar = new FrameLayout(this) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                // In a scroll view "match parent" measures to 0: the pill gets the tabs' size here.
                if (tabPill != null && chips != null) {
                    tabPill.layout(0, 0, chips.getWidth(), chips.getHeight());
                }
            }
        };
        chipsBar.setBackground(new GlassDrawable(px(19), dp, 0x2E14161A));
        glass(chipsBar, px(19), 0.8f);
        tabPill = new TabPill(this);
        chipsBar.addView(tabPill, new FrameLayout.LayoutParams(-1, -1));
        chipsBar.addView(chips, new FrameLayout.LayoutParams(-2, -2));
        chipsScroll.addView(chipsBar, new FrameLayout.LayoutParams(-2, -2));
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(chipsScroll, new LinearLayout.LayoutParams(-2, chipsH + px(6)));
        // The filters sit higher, with air between them and the cards.
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(16);
        column.addView(peekScroll, slp);
        peekLayer.addView(column, new FrameLayout.LayoutParams(-1, -2));
        int layerH = chipsH + px(6) + px(16) + top + h + below;
        // Well above the bar: the cards never crowd it.
        home.addView(peekLayer, at(-1, layerH, Gravity.TOP | Gravity.START, 0, trayClosedTop - layerH - px(32), 0, 0));
    }

    /** The app's filters as chips; rebuilt only when they change, so focus is kept. */
    private void setChips(String pkg, List<String[]> tabs) {
        shownTabs = tabs;
        StringBuilder key = new StringBuilder(pkg);
        for (String[] t : tabs) {
            key.append('|').append(t[0]);
        }
        chipsPkg = pkg;
        if (key.toString().equals(chipsKey) && chips.getChildCount() > 0) {
            paintChips();
            return;
        }
        chipsKey = key.toString();
        chips.removeAllViews();
        if (tabPill != null) {
            tabPill.placed = false;
        }
        for (final String[] t : tabs) {
            final TextView chip = text(11.5f, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setId(View.generateViewId());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(px(13), 0, px(13), 0);
            chip.setFocusable(true);
            chip.setTag(t[0]);
            chip.setText(t[1]);
            chip.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    tabPill.moveTo(v, true);
                } else {
                    // Leaving the bar: the pill settles back on the tab in use, light.
                    v.post(() -> {
                        if (!chipsFocused()) {
                            tabPill.moveTo(selectedChip(), false);
                        }
                    });
                }
                paintChips();
                if (has) {
                    // tvOS tab bars switch as focus moves; a short pause so passing by is cheap.
                    pendingTab = t[0];
                    handler.removeCallbacks(tabApply);
                    handler.postDelayed(tabApply, 320);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(30));
            lp.rightMargin = px(2);
            chips.addView(chip, lp);
        }
        paintChips();
    }

    /** Selected: a light capsule; focused: white with dark text, like tvOS tabs. */
    private void paintChips() {
        if (chips == null || chipsPkg == null) {
            return;
        }
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            chip.setTextColor(chip.isFocused() ? 0xFF111111 : Color.WHITE);
        }
        if (!chipsFocused() && chips.getChildCount() > 0) {
            final View sel = selectedChip();
            sel.post(() -> {
                if (!chipsFocused()) {
                    tabPill.moveTo(sel, false);
                }
            });
        }
    }

    private TabPill tabPill;

    /**
     * The tab bar's highlight: one rounded pill behind the tabs that glides on springs to the
     * focused tab (white) or rests on the tab in use (light), always inside the capsule.
     */
    private final class TabPill extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Spring x;
        private final Spring w;
        private final Spring bright;
        private boolean placed;

        TabPill(Context context) {
            super(context);
            x = new Spring(0f, 0.3f, v -> invalidate());
            x.configure(0.28f, 0.86f);
            w = new Spring(0f, 0.3f, v -> invalidate());
            w.configure(0.28f, 0.9f);
            bright = new Spring(0f, 0.002f, v -> invalidate());
            bright.configure(0.2f, 1f);
        }

        void moveTo(View chip, boolean focused) {
            if (chip == null) {
                return;
            }
            if (chip.getWidth() == 0) {
                chip.post(() -> moveTo(chip, focused));
                return;
            }
            float left = chip.getLeft() + chips.getPaddingLeft() * 0f;
            if (!placed) {
                x.snap(left);
                w.snap(chip.getWidth());
                bright.snap(focused ? 1f : 0f);
                placed = true;
            } else {
                x.to(left);
                w.to(chip.getWidth());
                bright.to(focused ? 1f : 0f);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!placed || chips.getChildCount() == 0) {
                return;
            }
            float b = Math.max(0f, Math.min(1f, bright.value()));
            // light (0x47) at rest, white (0xF2) when focused
            int a = Math.round(0x47 + (0xF2 - 0x47) * b);
            paint.setColor((a << 24) | 0xFFFFFF);
            View any = chips.getChildAt(0);
            float top = any.getTop();
            float h = any.getHeight();
            box.set(x.value(), top, x.value() + w.value(), top + h);
            canvas.drawRoundRect(box, h / 2f, h / 2f, paint);
        }
    }

    private void applyTab() {
        String n = pendingTab;
        if (n == null || chipsPkg == null || n.equals(tabOf(chipsPkg, shownTabs))) {
            return;
        }
        prefs.edit().putString(Profiles.scoped(prefs, "peek_tab2_" + chipsPkg), n).apply();
        paintChips();
        if (shelfPkg != null) {
            renderPeek(shelfPkg);
        }
    }

    private View selectedChip() {
        String tab = chipsPkg != null ? tabOf(chipsPkg, shownTabs) : "all";
        for (int i = 0; i < chips.getChildCount(); i++) {
            View c = chips.getChildAt(i);
            if (tab.equals(c.getTag())) {
                return c;
            }
        }
        return chips.getChildAt(0);
    }

    private boolean peekFocused() {
        View f = getCurrentFocus();
        return f != null && peek != null && isInside(f, peek);
    }

    private boolean chipsFocused() {
        View f = getCurrentFocus();
        return f != null && chips != null && f.getParent() == chips;
    }

    // -- content -------------------------------------------------------------------------------

    private void renderPeek(final String pkg) {
        if (pkg == null || !pkg.equals(shelfPkg) || browsing || open || control.getVisibility() == View.VISIBLE
                || peekFocused()) {
            return;
        }
        ensurePeek();
        if (LIVE_TILE.equals(pkg)) {
            renderLive();
            return;
        }
        List<Previews.Item> items = new ArrayList<>();
        if (previewsFresh(pkg)) {
            items.addAll(previewCache.get(pkg));
        }
        if ("com.netflix.ninja".equals(pkg)) {
            // Netflix only shares "continue watching" here: its weekly Top 10s fill the rest.
            java.util.Set<String> seen = new HashSet<>();
            for (Previews.Item it : items) {
                seen.add(it.title.toLowerCase(Locale.ROOT));
            }
            for (Previews.Item it : NetflixTop.items(this, () -> handler.post(() -> {
                lastPeekSig = null;
                renderPeek(pkg);
            }))) {
                if (seen.add(it.title.toLowerCase(Locale.ROOT))) {
                    items.add(it);
                }
            }
        }
        if (prefs.getBoolean("hide_manele", true)) {
            java.util.Iterator<Previews.Item> drop = items.iterator();
            while (drop.hasNext()) {
                if (isManele(drop.next())) {
                    drop.remove();
                }
            }
        }
        if (items.isEmpty()) {
            hidePeek(); // nothing published by this app
            return;
        }
        List<Previews.Item> ranked = recommender.rank(items);
        List<String[]> tabs = tabsFor(ranked);
        if (!chipsFocused() || !pkg.equals(chipsPkg)) {
            setChips(pkg, tabs);
        }
        String tab = tabOf(pkg, tabs);
        List<Previews.Item> kept = new ArrayList<>();
        for (Previews.Item it : ranked) {
            if (keeps(tab, it)) {
                kept.add(it);
            }
        }
        // Something different comes first every time the row comes up.
        int turn = peekTurn.containsKey(pkg) ? peekTurn.get(pkg) : 0;
        if (!pkg.equals(lastPeekPkg)) {
            peekTurn.put(pkg, turn + 1); // a new order only when the row comes up again
        }
        lastPeekPkg = pkg;
        if (kept.size() > 4) {
            Collections.rotate(kept, -((turn * 3) % kept.size()));
        }
        int h = px(PEEK_H);
        List<Card> cards = new ArrayList<>();
        for (final Previews.Item item : kept) {
            if (cards.size() >= 12) {
                break;
            }
            if (hiddenPreviews().contains(pkg + "|" + item.title)) {
                continue;
            }
            Card c = new Card();
            c.width = Math.round(h * Math.max(1.3f, Math.min(16f / 9f, item.ratio())));
            c.title = item.title;
            c.hideKey = pkg + "|" + item.title;
            final int w = c.width;
            c.picture = () -> previews.poster(item.poster, w, h);
            c.open = () -> openPreview(item);
            cards.add(c);
        }
        if (!kept.isEmpty()) {
            recommender.shown(kept.get(0));
        }
        fillPeek(pkg, cards);
    }

    /** The TV tile: channels on now; the focused one plays live in its card. */
    private void renderLive() {
        final String pkg = LIVE_TILE;
        worker.execute(() -> {
            final List<ChannelActivity.Channel> channels = ChannelActivity.loadChannels(this);
            final List<String[]> tabs = liveTabs(channels);
            final String tab = tabOf(pkg, tabs);
            String key = Profiles.current(prefs) + tab;
            List<LiveNow.Entry> found = liveCache.get(key);
            Long at = liveCacheAt.get(key);
            if (found == null || at == null || System.currentTimeMillis() - at > 3 * 60_000) {
                if ("all".equals(tab)) {
                    found = LiveNow.forYou(this, prefs, 14);
                } else if ("fav".equals(tab)) {
                    found = new ArrayList<>();
                    Set<String> fav = prefs.getStringSet(Profiles.scoped(prefs, "live_tv_favourites"), new HashSet<>());
                    for (ChannelActivity.Channel ch : channels) {
                        if (fav.contains(String.valueOf(ch.id))) {
                            LiveNow.Entry e = LiveNow.nowOn(this, ch);
                            if (e != null) {
                                found.add(e);
                            }
                        }
                    }
                } else {
                    Set<String> cats = new HashSet<>();
                    cats.add(tab.substring(4));
                    found = LiveNow.find(this, cats, 14);
                }
                liveCache.put(key, found);
                liveCacheAt.put(key, System.currentTimeMillis());
            }
            final List<LiveNow.Entry> entries = found;
            handler.post(() -> {
                if (!pkg.equals(shelfPkg) || peekFocused()) {
                    return;
                }
                if (!chipsFocused() || !pkg.equals(chipsPkg)) {
                    setChips(pkg, tabs);
                }
                int h = px(PEEK_H);
                List<Card> cards = new ArrayList<>();
                for (final LiveNow.Entry e : entries) {
                    if (prefs.getBoolean("hide_manele", true) && isManeleText(e.channel.name + " " + e.programme)) {
                        continue;
                    }
                    if (hiddenPreviews().contains("live|" + e.channel.id)) {
                        continue;
                    }
                    Card c = new Card();
                    c.width = Math.round(h * 16f / 9f);
                    c.title = e.channel.number + "  ·  " + e.channel.name + "  ·  " + e.programme;
                    c.hideKey = "live|" + e.channel.id;
                    final int w = c.width;
                    c.picture = () -> LiveNow.picture(this, e, w, h, dp);
                    c.open = () -> {
                        stopPeekTv();
                        launch(ChannelActivity.intent(this, e.channel.id), e.channel.name);
                    };
                    c.channel = e.channel;
                    cards.add(c);
                }
                fillPeek(pkg, cards);
            });
        });
    }

    /** Manele, by name and by the best-known singers (Settings can turn this off). */
    private static final String[] MANELE = {
            "manele", " manea ", "maneaua", "manelist", "taraf tv", "florin salam", "nicolae guta", "adrian minune",
            "tzanca", "dani mocanu", "babi minune", "jador", "vali vijelie", "liviu pustiu", "sorinel pustiu",
            "costel biju", "leo de la kuweit", "bogdan de la ploiesti", "mr juve", "mr. juve", "culita sterp",
            "cristi dules", "florin cercel", "ionut cercel", "petrica cercel", "copilul de aur", "carmen de la salciua",
            " susanu", "dani printul", "bogdan artistu", "puisor de la medias", "fulgerica", "denisa raducu",
            "adi de la valcea", " salam ", "salam &", "& salam", "ft. salam", "ft salam", "x salam"};

    private static String plain(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return " " + n.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-|()\\[\\]]+", " ") + " ";
    }

    private static boolean isManeleText(String text) {
        String t = plain(text);
        for (String w : MANELE) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isManele(Previews.Item it) {
        return isManeleText(it.title + " " + (it.author != null ? it.author : "") + " " + (it.channel != null ? it.channel : ""));
    }

    /** Builds the row: old cards leave quickly, the new ones rise in a wave. */
    private void fillPeek(final String pkg, final List<Card> cards) {
        ensurePeek();
        StringBuilder sig = new StringBuilder(pkg).append('|').append(tabOf(pkg, shownTabs));
        for (Card c : cards) {
            sig.append('|').append(c.title);
        }
        if (sig.toString().equals(lastPeekSig) && peek.getChildCount() > 0
                && peekLayer.findViewWithTag("band").getAlpha() > 0.5f) {
            return; // the same row is already up: no new wave
        }
        lastPeekSig = sig.toString();
        if (peekTv != null && peekTv.getParent() != null && isInside((View) peekTv.getParent(), peek)) {
            stopPeekTv(); // only a card's preview; the TV tile keeps its picture
        }
        final LinearLayout row = peek;
        int old = row.getChildCount();
        for (int i = 0; i < old; i++) {
            row.getChildAt(i).animate().cancel();
            row.getChildAt(i).animate().alpha(0f).translationY(px(10)).setDuration(110).start();
        }
        handler.postDelayed(() -> {
            if (!pkg.equals(shelfPkg) || peekFocused() || control.getVisibility() == View.VISIBLE || open) {
                return;
            }
            row.removeAllViews();
            if (peekSpring != null) {
                peekSpring.snap(0);
            }
            peekScroll.scrollTo(0, 0);
            int count = 0;
            for (Card c : cards) {
                addPeekCard(row, c, count++);
            }
            if (count == 0) {
                TextView none = text(12.5f, Typeface.DEFAULT);
                none.setAlpha(0.75f);
                none.setGravity(Gravity.CENTER_VERTICAL);
                none.setText("Nimic aici acum. Alege alt filtru de mai sus.");
                row.addView(none, new LinearLayout.LayoutParams(-2, px(PEEK_H)));
            }
            paintChips();
            View now = getCurrentFocus();
            if (LIVE_TILE.equals(pkg) && now != null && LIVE_TILE.equals(now.getTag()) && now.getParent() == dock) {
                startFirstCardTv(now);
            }
            peek.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            chips.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View band = peekLayer.findViewWithTag("band");
            band.invalidate();
            band.animate().alpha(1f).setDuration(380).start();
            ((View) chips.getParent()).animate().alpha(1f).setDuration(300).start();
            peekLayer.setVisibility(View.VISIBLE);
            for (int i = 0; count > 0 && i < row.getChildCount(); i++) {
                View card = ((ViewGroup) row.getChildAt(i)).getChildAt(0);
                View before = i > 0 ? ((ViewGroup) row.getChildAt(i - 1)).getChildAt(0) : card;
                View after = i + 1 < row.getChildCount() ? ((ViewGroup) row.getChildAt(i + 1)).getChildAt(0) : card;
                card.setNextFocusLeftId(before.getId());
                card.setNextFocusRightId(after.getId());
            }
            if (enterPeekWhenReady) {
                enterPeekWhenReady = false;
                View now2 = getCurrentFocus();
                if (now2 != null && now2.getParent() == dock) {
                    enterPeek();
                }
            }
        }, old > 0 ? 120 : 0);
    }

    private void addPeekCard(LinearLayout row, final Card spec, int index) {
        int h = px(PEEK_H);
        int w = spec.width;
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        final FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        if (spec.channel != null) {
            card.setTag(R_CHANNEL, spec.channel);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2E35);
        bg.setCornerRadius(px(14));
        card.setBackground(bg);
        card.setOutlineProvider(roundOutline(px(14)));
        card.setClipToOutline(true);
        // No elevation: a dozen live shadows cost this TV's GPU most of every frame (the
        // shadow comes from the cell, see FocusShadow).
        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setAlpha(0f);
        card.addView(image, full());
        View rim = new View(this);
        rim.setTag("rim");
        rim.setBackground(new GlassDrawable(px(14), dp, 0x00000000));
        card.addView(rim, full());
        card.setFocusable(true);
        final TextView name = text(12, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setText(spec.title);
        name.setGravity(Gravity.CENTER);
        name.setAlpha(0f);
        // Readable over any wallpaper, like the names under the apps.
        name.setShadowLayer(px(6), 0, px(1), 0xE6000000);
        // The focused card lifts with the same soft shadow as the apps in the grid (one
        // bitmap, drawn only under the focused card: cheap for this TV's GPU).
        cell.setBackground(new FocusShadow(card));
        card.setOnFocusChangeListener((v, has) -> {
            focusEffect(v, has);
            // Below the card even when it has grown (12% of its height, half of it downwards).
            name.animate().alpha(has ? 1f : 0f).translationY(has ? px(16) : 0).setDuration(200).start();
            if (spec.channel != null) {
                if (has) {
                    schedulePeekTv(card, spec.channel, 250, true, card);
                } else {
                    stopPeekTv();
                }
            }
        });
        card.setOnClickListener(v -> {
            play("select");
            spec.open.run();
        });
        card.setOnLongClickListener(v -> {
            startPeekHide(card, spec.hideKey);
            return true;
        });
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(w + px(20), -2);
        nlp.topMargin = px(5);
        cell.addView(name, nlp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, -2);
        clp.rightMargin = px(38); // room for the focused card to grow without touching the next
        row.addView(cell, clp);
        cell.setAlpha(0f);
        cell.setTranslationY(px(22));
        cell.animate().alpha(1f).translationY(0)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f))
                .setStartDelay(45L * Math.min(index, 6)).setDuration(440).start();
        posterWorker.execute(() -> {
            final Bitmap bitmap = spec.picture.load();
            handler.post(() -> {
                if (bitmap != null && image.isAttachedToWindow()) {
                    image.setImageBitmap(bitmap);
                    image.animate().alpha(1f).setDuration(240).start();
                }
            });
        });
    }

    /** The focused channel card plays the channel itself, muted, after a short rest on it. */
    /** On the TV tile, the first channel card plays at once (muted) while the tile has focus. */
    private void startFirstCardTv(final View tile) {
        if (peek == null || peek.getChildCount() == 0 || !LIVE_TILE.equals(chipsPkg)) {
            return; // the row is still coming; fillPeek starts it
        }
        View cell = peek.getChildAt(0);
        if (!(cell instanceof ViewGroup)) {
            return;
        }
        View card = ((ViewGroup) cell).getChildAt(0);
        Object ch = card.getTag(R_CHANNEL);
        if (card instanceof FrameLayout && ch instanceof ChannelActivity.Channel) {
            schedulePeekTv((FrameLayout) card, (ChannelActivity.Channel) ch, 550, false, tile);
        }
    }

    private static final int R_CHANNEL = 0x7f0b1234;

    private List<ChannelActivity.Channel> liveChannels;
    private android.animation.ValueAnimator peekTvFade;

    private void schedulePeekTv(final FrameLayout card, final ChannelActivity.Channel channel,
                                long delay, final boolean sound, final View owner) {
        stopPeekTv();
        peekTvStart = () -> {
            if (!owner.isFocused() || !resumed) {
                return;
            }
            try {
                if (peekTv == null) {
                    peekTv = new android.media.tv.TvView(this);
                }
                final android.media.tv.TvView tv = peekTv;
                tv.setAlpha(0f);
                tv.setCallback(new android.media.tv.TvView.TvInputCallback() {
                    @Override
                    public void onVideoAvailable(String inputId) {
                        tv.animate().alpha(1f).setDuration(300).start();
                        if (sound) {
                            // The channel's sound comes up gently, like a preview should.
                            if (peekTvFade != null) {
                                peekTvFade.cancel();
                            }
                            peekTvFade = android.animation.ValueAnimator.ofFloat(0.08f, 1f).setDuration(1400);
                            peekTvFade.addUpdateListener(a -> {
                                try {
                                    tv.setStreamVolume((float) a.getAnimatedValue());
                                } catch (RuntimeException ignored) {
                                    // Session ended meanwhile.
                                }
                            });
                            peekTvFade.start();
                        }
                    }
                });
                // Under the rim, over the picture; as large as needed to fill the card (16:9
                // video in a 5:3 tile would otherwise leave bars), the overflow cut by the card.
                int cw = Math.max(1, card.getWidth());
                int chh = Math.max(1, card.getHeight());
                float ratio = 16f / 9f;
                FrameLayout.LayoutParams tlp = cw / (float) chh < ratio
                        ? new FrameLayout.LayoutParams(Math.round(chh * ratio), chh, Gravity.CENTER)
                        : new FrameLayout.LayoutParams(cw, Math.round(cw / ratio), Gravity.CENTER);
                card.setClipChildren(true);
                card.addView(tv, card.getChildCount() - 1, tlp);
                tv.tune(channel.inputId, android.media.tv.TvContract.buildChannelUri(channel.id));
                // Never a hard 0: this tuner keeps the last volume for the next session.
                tv.setStreamVolume(sound ? 0.08f : 0.02f);
            } catch (RuntimeException e) {
                stopPeekTv();
            }
        };
        handler.postDelayed(peekTvStart, delay);
    }

    private void stopPeekTv() {
        if (peekTvFade != null) {
            peekTvFade.cancel();
            peekTvFade = null;
        }
        if (peekTvStart != null) {
            handler.removeCallbacks(peekTvStart);
            peekTvStart = null;
        }
        if (peekTv != null) {
            try {
                peekTv.setStreamVolume(1f); // the tuner keeps this for the next session
                peekTv.reset();
            } catch (RuntimeException ignored) {
                // Session already gone.
            }
            ViewGroup parent = (ViewGroup) peekTv.getParent();
            if (parent != null) {
                parent.removeView(peekTv);
            }
        }
    }

    private static final String KEY_HIDDEN_PREVIEWS = "hidden_previews";
    private View peekHiding;
    private String peekHidingKey;

    private Set<String> hiddenPreviews() {
        return prefs.getStringSet(Profiles.scoped(prefs, KEY_HIDDEN_PREVIEWS), new HashSet<>());
    }

    /** Held OK on a card: an "x" appears; Up hides it for good, any other key keeps it. */
    private void startPeekHide(View card, String key) {
        if (key == null) {
            return;
        }
        swallowOkUp = true;
        endPeekHide();
        peekHiding = card;
        peekHidingKey = key;
        play("select");
        addRemoveBadge(card);
        card.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).start();
    }

    private void endPeekHide() {
        if (peekHiding != null) {
            removeBadge(peekHiding);
            peekHiding.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        }
        peekHiding = null;
        peekHidingKey = null;
    }

    private void hideThisPreview() {
        final View card = peekHiding;
        String key = peekHidingKey;
        endPeekHide();
        if (card == null || key == null) {
            return;
        }
        Set<String> set = new HashSet<>(hiddenPreviews());
        set.add(key);
        prefs.edit().putStringSet(Profiles.scoped(prefs, KEY_HIDDEN_PREVIEWS), set).apply();
        final View cell = (View) card.getParent();
        final ViewGroup row = (ViewGroup) cell.getParent();
        int index = row.indexOfChild(cell);
        View next = row.getChildAt(index + 1 < row.getChildCount() ? index + 1 : index - 1);
        if (next instanceof ViewGroup && next != cell) {
            ((ViewGroup) next).getChildAt(0).requestFocus();
        } else {
            focusDock(lastDockIndex);
        }
        stopPeekTv();
        cell.animate().translationY(-px(60)).alpha(0f).setDuration(240).withEndAction(() -> {
            row.removeView(cell);
            lastPeekSig = null;
        }).start();
    }

    private void hidePeek() {
        handler.removeCallbacks(peekShow);
        if (peekTv != null && peekTv.getParent() != null && peek != null && isInside((View) peekTv.getParent(), peek)) {
            stopPeekTv();
        }
        if (peek == null) {
            return;
        }
        if (peekFocused() || chipsFocused()) {
            focusDock(lastDockIndex);
        }
        lastPeekSig = null;
        lastPeekPkg = null;
        // Hidden cards and filters must not be reachable (Up from the grid landed on them).
        peek.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        chips.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (int i = 0; i < peek.getChildCount(); i++) {
            View c = peek.getChildAt(i);
            c.animate().cancel();
            c.animate().alpha(0f).translationY(px(16)).setStartDelay(0).setDuration(160).start();
        }
        peekLayer.findViewWithTag("band").animate().alpha(0f).setDuration(200).start();
        ((View) chips.getParent()).animate().alpha(0f).setDuration(160).start();
    }

    /** Up from the bar: into the cards (or the filters, if a filter is empty). */
    private boolean enterPeek() {
        if (peek == null || peekLayer.findViewWithTag("band").getAlpha() < 0.5f) {
            return false;
        }
        View firstCell = peek.getChildCount() > 0 ? peek.getChildAt(0) : null;
        if (firstCell instanceof ViewGroup) {
            ((ViewGroup) firstCell).getChildAt(0).requestFocus();
        } else {
            selectedChip().requestFocus();
        }
        return true;
    }

    private void focusDock(int index) {
        if (dock.getChildCount() > 0) {
            dock.getChildAt(Math.max(0, Math.min(index, dock.getChildCount() - 1))).requestFocus();
        } else if (grid.getChildCount() > 0) {
            gridTile(0, 0).requestFocus();
        }
    }

    /** Open: the tray slides to the top, the wallpaper frosts over and every app is shown. */
    private void setOpen(boolean value) {
        if (open == value) {
            return;
        }
        open = value;
        if (value) {
            hideShelf();
            hidePeek();
            showHint(false);
            if (wallpaperSoft != null) {
                trayPane.setBackdrop(wallpaperSoft, 420);
            }
            trayPane.setTint(0x40FFFFFF);
        } else {
            if (wallpaperLens != null) {
                trayPane.setBackdrop(wallpaperLens, 420);
            }
            trayPane.setTint(0x24FFFFFF);
        }
        if (openSpring == null) {
            openSpring = new Spring(0f, 0.001f, t -> {
                float trayY = (trayOpenTop - trayClosedTop) * t;
                tray.setTranslationY(trayY);
                dockHolder.setTranslationY(trayY);
                gridScroll.setTranslationY(gridClosedOffset * (1f - t));
                blurLayer.setAlpha(Math.max(0f, Math.min(1f, t)));
            });
        }
        // Opening is a confident move, closing a slightly softer one; both critically damped
        // so the tray never bounces past its rest position.
        openSpring.to(value ? 1f : 0f, value ? 0.48f : 0.42f, 1f);
        pills.animate().alpha(value ? 0f : 1f).setDuration(240).start();
        placeTitle.animate().alpha(value ? 0f : 1f).setDuration(240).start();
        if (!value) {
            scrollGridTo(0);
        }
    }

    private void scrollGridTo(int y) {
        if (gridScrollSpring == null) {
            gridScrollSpring = new Spring(gridScroll.getScrollY(), 0.5f, v -> gridScroll.scrollTo(0, Math.round(v)));
            gridScrollSpring.configure(0.38f, 1f);
        }
        gridScrollSpring.to(y);
    }

    private void scrollShelfTo(int x) {
        if (shelfScrollSpring == null) {
            shelfScrollSpring = new Spring(shelfScroll.getScrollX(), 0.5f, v -> shelfScroll.scrollTo(Math.round(v), 0));
            shelfScrollSpring.configure(0.4f, 1f);
        }
        shelfScrollSpring.to(x);
    }

    // ================================================================ Control Center

    private FrameLayout buildControl() {
        FrameLayout panel = new FrameLayout(this);
        panel.setClipChildren(false);
        // tvOS 26: no pane. The picture dims a little and the glass tiles float on the right.
        controlPane = new LiquidGlassView(this, 0, dp, screenW, screenH, 0, 0);
        controlPane.setVisibility(View.GONE);

        controlScroll = new ScrollView(this);
        controlScroll.setVerticalScrollBarEnabled(false);
        controlScroll.setFocusable(false);
        controlScroll.setClipToPadding(false);
        controlScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        controlScroll.setClipChildren(false);
        controlList = new LinearLayout(this);
        controlList.setOrientation(LinearLayout.VERTICAL);
        controlList.setPadding(0, 0, 0, px(20));
        controlList.setClipChildren(false);
        controlList.setClipToPadding(false);
        controlScroll.addView(controlList, new FrameLayout.LayoutParams(-1, -2));
        // Its height, once drawn larger, is what is left of the screen: scrolling keeps the
        // focused tile inside that.
        panel.addView(controlScroll, at(px(TILE_W) * 2 + px(6) + px(8),
                Math.round((screenH - px(26)) / CC_SCALE), Gravity.TOP | Gravity.END,
                0, px(26), side - px(4), 0));
        // Control Center is built in small units and drawn at CC_SCALE (tuned by eye on the TV);
        // the scale is anchored at its top-right corner.
        controlScroll.setScaleX(CC_SCALE);
        controlScroll.setScaleY(CC_SCALE);
        controlScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX(r - l);
            v.setPivotY(0);
        });
        panel.setVisibility(View.GONE);
        return panel;
    }

    /**
     * The tvOS Control Center: a glass panel on the right with the time, then glass tiles
     * in two columns (sources first, then the TV itself), ending with the full Settings.
     */
    // Control Center tabs, like the three buttons at the top of the tvOS panel.
    private static final int TAB_TV = 0;
    private static final int TAB_SOURCES = 1;
    private static final int TAB_PROFILE = 2;
    private int controlTab = TAB_TV;
    private final List<View> tabButtons = new ArrayList<>();
    private LinearLayout tabContent;

    /**
     * The tvOS Control Center: a compact glass panel at the top right with the time, three
     * round buttons (TV, sources, profile) that switch the panel, and glass tiles in two
     * columns underneath. Smaller than it looks: 272 dp wide, 42 dp tiles, 12 sp labels.
     */
    /**
     * The tvOS 26 Control Center: no panel at all. The screen dims a little, the time and
     * three round tab buttons sit where the clock normally is, and loose glass tiles hang
     * under them on the right: one big square, small two-line tiles beside and below it,
     * and a row of round icon buttons at the end.
     */
    private void showControl(boolean inputsFirst) {
        previews.note("showControl(" + inputsFirst + ") from "
                + android.util.Log.getStackTraceString(new Throwable()).replace('\n', ' '));
        controlList.removeAllViews();
        tabButtons.clear();
        controlTab = inputsFirst ? TAB_SOURCES : TAB_TV;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        header.setClipChildren(false);
        // The clock lines up with the tiles' left edge (they sit 8 in from the panel's), the
        // buttons with their right edge.
        TextView time = text(15, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setText(clockText());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = px(9);
        header.addView(time, tlp);
        header.addView(tabButton(TAB_TV, "power", null));
        header.addView(tabButton(TAB_SOURCES, "hdmi", null));
        header.addView(tabButton(TAB_PROFILE, null, avatar.getDrawable()));
        controlList.addView(header, new LinearLayout.LayoutParams(-1, px(30)));

        tabContent = new LinearLayout(this);
        tabContent.setOrientation(LinearLayout.VERTICAL);
        tabContent.setClipChildren(false);
        tabContent.setClipToPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = px(10);
        controlList.addView(tabContent, clp);
        fillTab(controlTab, false);

        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.animate().cancel();
        pills.setAlpha(0f);
        controlReturn = getCurrentFocus();
        if (peekFocused() || chipsFocused()) {
            controlReturn = dock.getChildCount() > 0
                    ? dock.getChildAt(Math.max(0, Math.min(lastDockIndex, dock.getChildCount() - 1))) : avatar;
        }
        // Never the cards and Control Center together.
        handler.removeCallbacks(peekShow);
        enterPeekWhenReady = false;
        hidePeek();
        handler.removeCallbacks(heroPlay);
        stopHeroVideo();
        play("open");
        controlScroll.scrollTo(0, 0);
        control.setVisibility(View.VISIBLE);
        control.setAlpha(0f);
        controlList.setTranslationX(px(30));
        freezeBehind(true);
        control.animate().alpha(1f).setInterpolator(EASE).setDuration(300).withLayer().start();
        controlList.animate().translationX(0).setInterpolator(EASE).setDuration(360).start();
        View first = tabContent.findViewWithTag("first");
        if (first != null) {
            first.requestFocus();
        } else {
            tabButtons.get(controlTab).requestFocus();
        }
    }

    /** A round glass button in the header; focusing it switches the panel. */
    private View tabButton(final int tab, String glyph, Drawable picture) {
        final FrameLayout button = new FrameLayout(this);
        int size = px(26);
        final GlassDrawable glass = new GlassDrawable(size / 2f, dp, 0x5214161A);
        button.setBackground(glass);
        glass(button, size / 2f, 0.7f);
        button.setOutlineProvider(roundOutline(size / 2f));
        button.setClipToOutline(true);
        final ImageView icon = new ImageView(this);
        if (picture != null) {
            icon.setImageDrawable(picture);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            button.addView(icon, full());
        } else {
            icon.setImageBitmap(Glyphs.symbol(this, glyph, ccPx(14)));
            button.addView(icon, at(px(14), px(14), Gravity.CENTER, 0, 0, 0, 0));
        }
        button.setId(View.generateViewId());
        button.setFocusable(true);
        button.setTag(tab);
        button.setOnFocusChangeListener((v, has) -> {
            glass.setTint(has ? 0xF2FFFFFF : 0x5214161A);
            if (picture == null) {
                icon.setColorFilter(has ? 0xFF111111 : Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setInterpolator(EASE)
                    .setDuration(200).start();
            if (has && controlTab != tab) {
                fillTab(tab, true);
            }
        });
        button.setOnClickListener(v -> {
            play("select");
            View first = tabContent.findViewWithTag("first");
            if (first != null) {
                first.requestFocus();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = px(8);
        button.setLayoutParams(lp);
        tabButtons.add(button);
        return button;
    }

    private void fillTab(int tab, boolean animate) {
        controlTab = tab;
        tabContent.removeAllViews();
        List<List<View>> rows = new ArrayList<>();
        if (tab == TAB_SOURCES) {
            TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            List<TvInputInfo> inputs = new ArrayList<>();
            if (tim != null) {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.isPassthroughInput() && !info.isHidden(this)) {
                        inputs.add(info);
                    }
                }
            }
            Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
            List<View> tiles = new ArrayList<>();
            for (final TvInputInfo info : inputs) {
                tiles.add(smallTile(inputLabel(info), "", "hdmi", () -> {
                    Intent intent = SourceActivity.intent(this, info.getId());
                    if (!launch(intent, inputLabel(info))) {
                        openPackage(INPUT_PICKER);
                    }
                }));
            }
            for (final String pkg : LIVE_TV) {
                if (isInstalled(pkg)) {
                    tiles.add(smallTile("Antenă", "Live TV", "antenna", () -> {
                        hideControl();
                        LiveTv.open(this);
                    }));
                    break;
                }
            }
            if (isInstalled(INPUT_PICKER)) {
                tiles.add(smallTile("Toate", "sursele", "tv", () -> openPackage(INPUT_PICKER)));
            }
            for (int i = 0; i < tiles.size(); i += 2) {
                rows.add(pairRow(tiles.get(i), i + 1 < tiles.size() ? tiles.get(i + 1) : null));
            }
        } else if (tab == TAB_PROFILE) {
            // Switch Users, like tvOS: the profiles as round avatars in a glass card, a check on
            // the one in use, "+" to add one; below, the Google accounts and the store.
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setClipChildren(false);
            card.setPadding(px(6), px(10), px(6), px(6));
            GlassDrawable cardGlass = new GlassDrawable(px(20), dp, 0x3314161A);
            card.setBackground(cardGlass);
            glass(card, px(20), 0.8f);
            TextView switchTitle = text(11, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            switchTitle.setText("Schimbă profilul");
            switchTitle.setAlpha(0.8f);
            switchTitle.setPadding(px(8), 0, 0, px(4));
            card.addView(switchTitle);
            List<View> cells = new ArrayList<>();
            for (Profiles.Profile p : Profiles.all(prefs, accountName())) {
                cells.add(profileCell(p));
            }
            cells.add(addProfileCell());
            int cellW = (px(TILE_W) * 2 + px(6) - px(12)) / 2;
            for (int i = 0; i < cells.size(); i += 2) {
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                List<View> pair = new ArrayList<>();
                for (int j = i; j < Math.min(i + 2, cells.size()); j++) {
                    line.addView(cells.get(j), new LinearLayout.LayoutParams(cellW, px(92)));
                    pair.add(cells.get(j));
                }
                card.addView(line);
                rows.add(pair);
            }
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(px(TILE_W) * 2 + px(6), -2);
            cardLp.gravity = Gravity.END;
            tabContent.addView(card, cardLp);
            View accounts = smallTile("Conturi", "Google", "list",
                    () -> launch(new Intent("android.settings.SYNC_SETTINGS"), "Conturi"));
            View store = smallTile("Google Play", "", "search", () -> openPackage("com.android.vending"));
            rows.add(pairRow(accounts, store));
        } else {
            // The big module: Cinema (the screensaver stays available among the shortcuts).
            View big = bigTile("Cinema", null,
                    new android.graphics.drawable.BitmapDrawable(getResources(), CinemaMark.bitmap(px(52) * 2)),
                    this::enterCinema);
            String wifi = wifiName();
            View wifiTile = smallTile("Wi-Fi", wifi, "wifi", () -> expand(0), false);
            // Do Not Disturb: no notices or weather drops while it is on (red warnings still come).
            final boolean quiet = prefs.getBoolean("dnd", false);
            final CcIcon moon = new CcIcon(this, dp, CcIcon.MOON, quiet);
            final View[] dndTile = new View[1];
            dndTile[0] = iconTile(t("Nu deranja", "Do Not Disturb"), quiet ? t("Pornit", "On") : t("Oprit", "Off"),
                    moon, () -> prefs.getBoolean("dnd", false), () -> {
                        boolean q = !prefs.getBoolean("dnd", false);
                        prefs.edit().putBoolean("dnd", q).apply();
                        moon.set(q);
                        setTileDetail(dndTile[0], q ? t("Pornit", "On") : t("Oprit", "Off"));
                        switchLook(dndTile[0], moon, q, dndTile[0].isFocused());
                    });
            rows.add(bigRow(big, wifiTile, dndTile[0]));
            View bluetooth = smallTile("Bluetooth", Connectivity.bluetoothOn() ? t("Pornit", "On") : t("Oprit", "Off"),
                    "bluetooth", () -> expand(1), false);
            // The sleep timer: its clock shows the time left; choosing a time plays the change.
            final long sleepAt = SourcesService.sleepAt(this);
            final CcIcon clockIcon = new CcIcon(this, dp, CcIcon.TIMER, sleepAt != 0);
            clockIcon.setTimer(sleepAt, prefs.getLong("sleep_len", 0));
            sleepIcon = clockIcon;
            View sleep = iconTile(t("Somn", "Sleep"), sleepDetail(sleepAt), clockIcon,
                    () -> SourcesService.sleepAt(this) != 0, () -> expand(2));
            rows.add(pairRow(bluetooth, sleep));
            ccBig = big;
            ccWifi = wifiTile;
            ccDnd = dndTile[0];
            ccBluetooth = bluetooth;
            ccSleep = sleep;
            rows.addAll(shortcutGrid());
        }

        // D-pad: rows stay rows; up from the first row reaches the active header button.
        View header = tabButtons.get(tab);
        for (int r = 0; r < rows.size(); r++) {
            List<View> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                View v = row.get(c);
                v.setNextFocusLeftId(c > 0 ? row.get(c - 1).getId() : v.getId());
                v.setNextFocusRightId(c + 1 < row.size() ? row.get(c + 1).getId() : v.getId());
                v.setNextFocusUpId(r > 0 ? nearest(rows.get(r - 1), c, row.size()).getId() : header.getId());
                v.setNextFocusDownId(r + 1 < rows.size() ? nearest(rows.get(r + 1), c, row.size()).getId() : v.getId());
                v.setTag(r == 0 && c == 0 ? "first" : null);
            }
        }
        // The module block is two logical rows sharing the big tile: wire its right column by hand.
        if (tab == TAB_TV && ccWifi != null && rows.size() > 1) {
            View below = nearest(rows.get(1), rows.get(1).size() - 1, rows.get(1).size());
            ccWifi.setNextFocusDownId(ccDnd.getId());
            ccDnd.setNextFocusUpId(ccWifi.getId());
            ccDnd.setNextFocusLeftId(ccBig.getId());
            ccDnd.setNextFocusRightId(ccDnd.getId());
            ccDnd.setNextFocusDownId(below.getId());
            ccBig.setNextFocusDownId(rows.get(1).get(0).getId());
            List<View> second = rows.get(1);
            for (View v : second) {
                int i = second.indexOf(v);
                boolean leftColumn = second.size() == 2 ? i == 0 : i < 2;
                v.setNextFocusUpId(leftColumn ? ccBig.getId() : ccDnd.getId());
            }
        }
        for (View button : tabButtons) {
            button.setNextFocusDownId(!rows.isEmpty() && !rows.get(0).isEmpty() ? rows.get(0).get(0).getId() : button.getId());
            button.setNextFocusUpId(button.getId());
        }
        tabButtons.get(0).setNextFocusLeftId(tabButtons.get(0).getId());
        tabButtons.get(2).setNextFocusRightId(tabButtons.get(2).getId());
        int order = 0;
        for (List<View> row : rows) {
            for (View v : row) {
                v.setAlpha(0f);
                v.setTranslationX(px(18));
                v.animate().alpha(1f).translationX(0).setInterpolator(EASE)
                        .setStartDelay(animate ? 30L * order : 40L + 35L * order).setDuration(300).withLayer().start();
                order++;
            }
        }
    }

    /** The view in `row` whose column best matches column `c` of a row with `of` entries. */
    private static View nearest(List<View> row, int c, int of) {
        int index = Math.round(c * (row.size() - 1) / (float) Math.max(1, of - 1));
        return row.get(Math.max(0, Math.min(row.size() - 1, index)));
    }

    /** One profile in the Switch Users card: its avatar, a check if in use, the name below. */
    private View profileCell(final Profiles.Profile p) {
        final boolean inUse = p.id.equals(Profiles.current(prefs));
        final LinearLayout cell = new LinearLayout(this);
        cell.setId(View.generateViewId());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        cell.setFocusable(true);
        final FrameLayout face = new FrameLayout(this);
        face.setClipChildren(false);
        final ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setImageBitmap(Profiles.picture(this, prefs, p, px(56) * 2));
        picture.setOutlineProvider(roundOutline(px(28)));
        picture.setClipToOutline(true);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(px(2), 0x00FFFFFF);
        face.addView(picture, new FrameLayout.LayoutParams(px(56), px(56), Gravity.CENTER));
        View ringView = new View(this);
        ringView.setBackground(ring);
        face.addView(ringView, new FrameLayout.LayoutParams(px(62), px(62), Gravity.CENTER));
        if (inUse) {
            ImageView check = new ImageView(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFF34C759);
            check.setBackground(dot);
            check.setImageBitmap(Glyphs.symbol(this, "check", ccPx(10)));
            check.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            check.setPadding(px(3), px(3), px(3), px(3));
            face.addView(check, new FrameLayout.LayoutParams(px(18), px(18), Gravity.TOP | Gravity.END));
        }
        cell.addView(face, new LinearLayout.LayoutParams(px(62), px(62)));
        final TextView name = text(10.5f, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setText(p.name);
        name.setGravity(Gravity.CENTER);
        name.setAlpha(inUse ? 1f : 0.7f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(4);
        cell.addView(name, nlp);
        cell.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(px(2), has ? 0xF2FFFFFF : 0x00FFFFFF);
            face.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setInterpolator(EASE).setDuration(220).start();
            name.animate().alpha(has || inUse ? 1f : 0.7f).setDuration(200).start();
        });
        cell.setOnClickListener(v -> {
            play("select");
            switchProfile(p);
        });
        // Hold OK: change this profile's name or picture.
        cell.setOnLongClickListener(v -> {
            swallowOkUp = true;
            showProfileEdit(p);
            return true;
        });
        return cell;
    }

    private Bitmap firstProfilePhoto(Profiles.Profile p) {
        File dir = getExternalFilesDir(null);
        if (dir != null) {
            for (String n : new String[]{"profile.jpg", "profile.png", "profile.webp"}) {
                File f = new File(dir, n);
                if (f.exists()) {
                    Bitmap b = decode(f.getAbsolutePath(), px(56) * 2, px(56) * 2);
                    if (b != null) {
                        return b;
                    }
                }
            }
        }
        return Profiles.initial(p, px(56) * 2);
    }

    /** "+": a new profile, named with the TV's keyboard. */
    private View addProfileCell() {
        final LinearLayout cell = new LinearLayout(this);
        cell.setId(View.generateViewId());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        cell.setFocusable(true);
        final FrameLayout face = new FrameLayout(this);
        final GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0x80000000);
        face.setBackground(disc);
        final ImageView icon = new ImageView(this);
        icon.setImageBitmap(plusGlyph(ccPx(22)));
        face.addView(icon, new FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(px(56), px(56));
        flp.topMargin = px(3);
        flp.bottomMargin = px(3);
        cell.addView(face, flp);
        final TextView name = text(10.5f, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setText("Adaugă profil");
        name.setGravity(Gravity.CENTER);
        name.setAlpha(0.7f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(4);
        cell.addView(name, nlp);
        cell.setOnFocusChangeListener((v, has) -> {
            disc.setColor(has ? 0xF2FFFFFF : 0x80000000);
            icon.setColorFilter(has ? 0xFF111111 : Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            face.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setInterpolator(EASE).setDuration(220).start();
            name.animate().alpha(has ? 1f : 0.7f).setDuration(200).start();
        });
        cell.setOnClickListener(v -> {
            play("select");
            askProfileName(null);
        });
        return cell;
    }

    private Bitmap plusGlyph(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(size * 0.11f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(size / 2f, size * 0.12f, size / 2f, size * 0.88f, paint);
        c.drawLine(size * 0.12f, size / 2f, size * 0.88f, size / 2f, paint);
        return b;
    }

    private FrameLayout profileModal;

    /**
     * A new profile, named with a tvOS-style keyboard in a Liquid Glass card (the dock's glass):
     * the name in a capsule, one row of letters that slides under the focus, and a row of
     * actions (upper case, digits, space, delete, done). Back cancels.
     */
    private void askProfileName(final Profiles.Profile editing) {
        hideControl();
        if (profileModal != null) {
            closeProfileModal();
        }
        final StringBuilder typed = new StringBuilder();
        if (editing != null && !"Profil".equals(editing.name)) {
            typed.append(editing.name);
        }
        final boolean[] upper = {typed.length() == 0};
        profileModal = new FrameLayout(this);
        profileModal.setBackgroundColor(0x80000000);
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setPadding(px(24), px(20), px(24), px(18));
        card.setBackground(new GlassDrawable(px(28), dp, 0x331C1C1E));
        glass(card, px(28), 1f);
        TextView title = text(17, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText(editing == null ? "Profil nou" : "Numele profilului");
        card.addView(title);
        TextView sub = text(11.5f, Typeface.DEFAULT);
        sub.setAlpha(0.7f);
        sub.setText(editing == null ? "Cum se numește?" : "Cum vrei să apară, lângă ceas și în Cinema?");
        card.addView(sub);
        final TextView field = text(17, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(px(16), 0, px(16), 0);
        field.setBackground(new GlassDrawable(px(20), dp, 0x29FFFFFF));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, px(44));
        flp.topMargin = px(14);
        flp.bottomMargin = px(10);
        card.addView(field, flp);
        final Runnable showText = () -> field.setText(typed.toString() + "▏");
        showText.run();

        // A full keyboard, every key visible: digits, QWERTY, Romanian letters, actions.
        final String[] rows = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "ăâîșț-'."};
        final List<TextView> letterKeys = new ArrayList<>();
        final Runnable relabel = () -> {
            for (TextView k : letterKeys) {
                String c = (String) k.getTag();
                k.setText(upper[0] ? c.toUpperCase(Locale.getDefault()) : c);
            }
        };
        View first = null;
        for (String keys : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            line.setClipChildren(false);
            for (int i = 0; i < keys.length(); i++) {
                final String c = String.valueOf(keys.charAt(i));
                final TextView[] holder = new TextView[1];
                TextView key = (TextView) keyView(c, px(46), () -> {
                    if (typed.length() < 18) {
                        typed.append(holder[0].getText());
                        if (upper[0]) {
                            upper[0] = false; // a capital first letter, then lower case
                            relabel.run();
                        }
                        showText.run();
                    }
                });
                holder[0] = key;
                key.setTag(c);
                if (Character.isLetter(c.charAt(0))) {
                    letterKeys.add(key);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(46), px(46));
                lp.setMargins(px(3), px(3), px(3), px(3));
                line.addView(key, lp);
                if (first == null && keys.startsWith("q")) {
                    first = key;
                }
            }
            card.addView(line, new LinearLayout.LayoutParams(-2, -2));
        }
        relabel.run();
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setClipChildren(false);
        Object[][] acts = {
                {"⇧ aA", 104, (Runnable) () -> {
                    upper[0] = !upper[0];
                    relabel.run();
                }},
                {"Spațiu", 170, (Runnable) () -> {
                    if (typed.length() > 0 && typed.length() < 18) {
                        typed.append(' ');
                        showText.run();
                    }
                }},
                {"Șterge", 104, (Runnable) () -> {
                    if (typed.length() > 0) {
                        typed.setLength(typed.length() - 1);
                        if (typed.length() == 0) {
                            upper[0] = true;
                            relabel.run();
                        }
                        showText.run();
                    }
                }},
                {"Gata", 104, (Runnable) () -> {
                    String n = typed.toString().trim();
                    closeProfileModal();
                    if (editing != null) {
                        Profiles.rename(prefs, editing.id, n);
                        loadAvatar();
                    } else if (!n.isEmpty()) {
                        Profiles.Profile added = Profiles.add(prefs, n);
                        switchProfile(added);
                        handler.postDelayed(() -> showAvatarPicker(added), 500);
                    }
                }},
        };
        for (Object[] act : acts) {
            int width = px((Integer) act[1]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, px(42));
            lp.setMargins(px(3), px(8), px(3), px(3));
            actions.addView(keyView((String) act[0], width, (Runnable) act[2]), lp);
        }
        card.addView(actions, new LinearLayout.LayoutParams(-2, -2));

        profileModal.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        ((ViewGroup) findViewById(android.R.id.content)).addView(profileModal, new FrameLayout.LayoutParams(-1, -1));
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        profileModal.setAlpha(0f);
        profileModal.animate().alpha(1f).setDuration(200).start();
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2.4f)).setDuration(320).start();
        final View f = first;
        f.post(f::requestFocus);
    }

    /** One key: glass at rest; focused it turns white and grows, like the tvOS keyboard. */
    private View keyView(String label, int width, final Runnable action) {
        final TextView key = text(label.length() > 1 ? 13 : 17, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        key.setGravity(Gravity.CENTER);
        key.setText(label);
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(px(12));
        shape.setColor(0x1FFFFFFF);
        key.setBackground(shape);
        key.setFocusable(true);
        key.setOnFocusChangeListener((v, has) -> {
            shape.setColor(has ? 0xF2FFFFFF : 0x1FFFFFFF);
            key.setTextColor(has ? 0xFF111111 : Color.WHITE);
            v.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).setDuration(200).start();
            if (has) {
                play("move");
            }
        });
        key.setOnClickListener(v -> {
            play("select");
            action.run();
        });
        return key;
    }

    /** Hold OK on a profile: a Liquid Glass card with its picture and name, to change either. */
    private void showProfileEdit(final Profiles.Profile p) {
        hideControl();
        if (profileModal != null) {
            closeProfileModal();
        }
        profileModal = new FrameLayout(this);
        profileModal.setBackgroundColor(0x80000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setPadding(px(30), px(22), px(30), px(24));
        card.setBackground(new GlassDrawable(px(28), dp, 0x331C1C1E));
        glass(card, px(28), 1f);
        ImageView face = new ImageView(this);
        face.setImageBitmap(Profiles.picture(this, prefs, p, px(72) * 2));
        card.addView(face, new LinearLayout.LayoutParams(px(72), px(72)));
        TextView name = text(17, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setText(p.name);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(10);
        card.addView(name, nlp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        View rename = capsuleButton("Schimbă numele", () -> askProfileName(p));
        View picture = capsuleButton("Schimbă imaginea", () -> showAvatarPicker(p));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(-2, px(42));
        a.rightMargin = px(14);
        row.addView(rename, a);
        row.addView(picture, new LinearLayout.LayoutParams(-2, px(42)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = px(18);
        card.addView(row, rlp);
        profileModal.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        ((ViewGroup) findViewById(android.R.id.content)).addView(profileModal, new FrameLayout.LayoutParams(-1, -1));
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        profileModal.setAlpha(0f);
        profileModal.animate().alpha(1f).setDuration(200).start();
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2.4f)).setDuration(320).start();
        rename.post(rename::requestFocus);
    }

    /** A tvOS button: a glass capsule, white and a little larger when focused. */
    private View capsuleButton(String label, final Runnable action) {
        final TextView b = text(14, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(22), 0, px(22), 0);
        b.setShadowLayer(0, 0, 0, 0);
        final GlassDrawable g = new GlassDrawable(px(21), dp, 0x33FFFFFF);
        b.setBackground(g);
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, has) -> {
            g.setTint(has ? 0xF2FFFFFF : 0x33FFFFFF);
            b.setTextColor(has ? 0xFF111111 : Color.WHITE);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).setDuration(220).start();
        });
        b.setOnClickListener(v -> {
            play("select");
            action.run();
        });
        return b;
    }

    /** A Liquid Glass card of pictures for a profile: the avatars, the initial, the photo. */
    private void showAvatarPicker(final Profiles.Profile p) {
        hideControl();
        if (profileModal != null) {
            closeProfileModal();
        }
        profileModal = new FrameLayout(this);
        profileModal.setBackgroundColor(0x80000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        card.setPadding(px(24), px(20), px(24), px(20));
        card.setBackground(new GlassDrawable(px(28), dp, 0x331C1C1E));
        glass(card, px(28), 1f);
        TextView title = text(17, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText("Imaginea pentru " + p.name);
        card.addView(title);
        List<String> choices = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            choices.add(String.format(Locale.ROOT, "a%02d", i));
        }
        choices.add("");
        File dir = getExternalFilesDir(null);
        for (String n : new String[]{"profile.jpg", "profile.png", "profile.webp"}) {
            if (dir != null && new File(dir, n).exists()) {
                choices.add("photo");
                break;
            }
        }
        LinearLayout line = null;
        View first = null;
        String current = Profiles.avatarOf(prefs, p.id);
        for (int i = 0; i < choices.size(); i++) {
            if (i % 7 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
                llp.topMargin = px(12);
                card.addView(line, llp);
            }
            final String choice = choices.get(i);
            final FrameLayout cell = new FrameLayout(this);
            cell.setFocusable(true);
            cell.setClipChildren(false);
            ImageView face = new ImageView(this);
            String saved = Profiles.avatarOf(prefs, p.id);
            Profiles.setAvatar(prefs, p.id, choice);
            face.setImageBitmap(Profiles.picture(this, prefs, p, px(56) * 2));
            Profiles.setAvatar(prefs, p.id, saved);
            cell.addView(face, new FrameLayout.LayoutParams(px(56), px(56), Gravity.CENTER));
            final GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setStroke(px(2), choice.equals(current) ? 0x99FFFFFF : 0x00FFFFFF);
            View ringView = new View(this);
            ringView.setBackground(ring);
            cell.addView(ringView, new FrameLayout.LayoutParams(px(64), px(64), Gravity.CENTER));
            cell.setOnFocusChangeListener((v, has) -> {
                ring.setStroke(px(2), has ? 0xF2FFFFFF : choice.equals(current) ? 0x99FFFFFF : 0x00FFFFFF);
                v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setInterpolator(EASE).setDuration(200).start();
            });
            cell.setOnClickListener(v -> {
                play("select");
                Profiles.setAvatar(prefs, p.id, choice);
                closeProfileModal();
                loadAvatar();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(64), px(64));
            clp.setMargins(px(5), 0, px(5), 0);
            line.addView(cell, clp);
            if (first == null || choice.equals(current)) {
                first = cell;
            }
        }
        profileModal.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        ((ViewGroup) findViewById(android.R.id.content)).addView(profileModal, new FrameLayout.LayoutParams(-1, -1));
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        profileModal.setAlpha(0f);
        profileModal.animate().alpha(1f).setDuration(200).start();
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2.4f)).setDuration(320).start();
        final View f = first;
        f.post(f::requestFocus);
    }

    private void closeProfileModal() {
        if (profileModal == null) {
            return;
        }
        final View old = profileModal;
        profileModal = null;
        old.animate().alpha(0f).setDuration(180).withEndAction(() ->
                ((ViewGroup) findViewById(android.R.id.content)).removeView(old)).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        focusNothing();
    }

    /** Another profile: its photo next to the clock, its row of apps, its favourite channels. */
    private void switchProfile(Profiles.Profile p) {
        if (p.id.equals(Profiles.current(prefs))) {
            hideControl();
            return;
        }
        Profiles.setCurrent(prefs, p.id);
        folders = Folders.load(prefs);
        hideControl();
        if (Profiles.FIRST.equals(p.id)) {
            avatar.setImageBitmap(placeholderAvatar());
        }
        loadAvatar();
        rebuildTiles();
        SourcesService service = SourcesService.instance;
        if (service != null) {
            service.showNotice("TV Home", new android.graphics.drawable.BitmapDrawable(getResources(),
                            Profiles.picture(this, prefs, p, px(32) * 2)),
                    p.name, "Profilul este acum în uz");
        }
    }

    private String accountName() {
        try {
            android.accounts.Account[] accounts = android.accounts.AccountManager.get(this)
                    .getAccountsByType("com.google");
            if (accounts.length > 0) {
                return accounts[0].name.split("@")[0];
            }
        } catch (RuntimeException ignored) {
            // Not visible to us on this Android version.
        }
        return "Profil";
    }

    /**
     * Control Center's size rule: its tiles take about a fifth of the screen width (0.196,
     * chosen on the 1920x1080 UI), whatever the TV's density. Sizes on a TV follow the screen,
     * not centimetres: a bigger TV is watched from further away, so the same fraction of the
     * picture looks the same size from the sofa (the same idea as tvOS's fixed 1920x1080 points).
     */
    private static final float CC_WIDTH_FRACTION = 0.1777f;
    private float CC_SCALE = 0.88f;
    private static final int TILE_W = 94;    // dp: the small tile; the big one is 2 small + the gap

    /** A big square tile on the left with two small tiles stacked on its right. */
    private List<View> bigRow(View big, View top, View bottom) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        int small = px(58);
        int gapPx = px(6);
        int bigSize = small * 2 + gapPx;
        // The big tile is one column wide and two rows tall, so every row lines up on the same
        // two columns as the rows below it.
        row.addView(big, new LinearLayout.LayoutParams(px(TILE_W), bigSize));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(px(TILE_W), small);
        column.addView(top, a);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(px(TILE_W), small);
        b.topMargin = gapPx;
        column.addView(bottom, b);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = gapPx;
        row.addView(column, clp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.gravity = Gravity.END;
        tabContent.addView(row, rlp);
        List<View> order = new ArrayList<>();
        order.add(big);
        order.add(top);
        List<View> second = new ArrayList<>();
        second.add(big);
        second.add(bottom);
        return order;
    }

    private List<View> pairRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        List<View> order = new ArrayList<>();
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(px(TILE_W), px(58));
        row.addView(left, a);
        order.add(left);
        if (right != null) {
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(px(TILE_W), px(58));
            b.leftMargin = px(6);
            row.addView(right, b);
            order.add(right);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.gravity = Gravity.END;
        rlp.topMargin = px(6);
        tabContent.addView(row, rlp);
        return order;
    }

    // ---------------------------------------------------------------- customisable shortcuts

    private static final String KEY_SHORTCUTS = "cc_shortcuts";
    private static final String DEFAULT_SHORTCUTS = "search,sources,antenna,apps,wallpaper,picture,sound,access,settings";
    private static final int SHORTCUT_COLUMNS = 4;
    private View ccEditing;
    private final java.util.WeakHashMap<View, String> shortcutIdOf = new java.util.WeakHashMap<>();
    private ValueAnimator ccJiggle;

    /** Every shortcut the Control Center can hold: id, symbol, name. */
    private static final String[][] SHORTCUTS = {
            {"search", "search", "Caută"},
            {"sources", "hdmi", "Surse"},
            {"antenna", "antenna", "Antenă"},
            {"apps", "list", "Aplicații"},
            {"wallpaper", "image", "Fundal următor"},
            {"picture", "tv", "Imagine"},
            {"sound", "speaker", "Sunet"},
            {"access", "access", "Accesibilitate"},
            {"settings", "gear", "Setări"},
            {"cameras", "camera", "Camere"},
            {"sleep", "timer", "Oprire programată"},
            {"store", "list", "Google Play"},
            {"screensaver", "moon", "Screensaver"},
    };

    private List<String> shortcutIds() {
        // Search arrived later: lists saved before it get it at the front, once.
        String key = Profiles.scoped(prefs, KEY_SHORTCUTS);
        String added = Profiles.scoped(prefs, "cc_search_added");
        if (!prefs.getBoolean(added, false)) {
            String saved = prefs.getString(key, null);
            if (saved != null && !("," + saved + ",").contains(",search,")) {
                prefs.edit().putString(key, "search," + saved).apply();
            }
            prefs.edit().putBoolean(added, true).apply();
        }
        List<String> out = new ArrayList<>();
        for (String id : prefs.getString(Profiles.scoped(prefs, KEY_SHORTCUTS), DEFAULT_SHORTCUTS).split(",")) {
            if (!id.trim().isEmpty() && shortcutDef(id.trim()) != null) {
                out.add(id.trim());
            }
        }
        return out;
    }

    private void saveShortcuts(List<String> ids) {
        prefs.edit().putString(Profiles.scoped(prefs, KEY_SHORTCUTS), TextUtils.join(",", ids)).apply();
    }

    private static String[] shortcutDef(String id) {
        for (String[] def : SHORTCUTS) {
            if (def[0].equals(id)) {
                return def;
            }
        }
        return null;
    }

    private Runnable shortcutAction(String id) {
        switch (id) {
            case "search":
                return () -> {
                    hideControl();
                    openSearch();
                };
            case "sources":
                return () -> tabButtons.get(TAB_SOURCES).requestFocus();
            case "antenna":
                return () -> {
                    for (String pkg : LIVE_TV) {
                        if (isInstalled(pkg)) {
                            hideControl();
                            openPackage(pkg);
                            return;
                        }
                    }
                };
            case "apps":
                return () -> {
                    hideControl();
                    if (grid.getChildCount() > 0) {
                        gridTile(0, 0).requestFocus();
                    }
                };
            case "wallpaper":
                return this::showNextWallpaper;
            case "picture":
                return () -> {
                    hideControl();
                    launch(new Intent("android.settings.DISPLAY_SETTINGS"), "Imagine");
                };
            case "sound":
                return () -> {
                    hideControl();
                    launch(new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS"), "Sunet");
                };
            case "access":
                return () -> {
                    hideControl();
                    launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Accesibilitate");
                };
            case "settings":
                return () -> {
                    hideControl();
                    launch(new Intent(this, SettingsActivity.class), "Setări");
                };
            case "cameras":
                return () -> {
                    hideControl();
                    showCameras();
                };
            case "sleep":
                return () -> {
                    hideControl();
                    launch(new Intent("com.tcl.action.sleep"), "Oprire programată");
                };
            case "store":
                return () -> {
                    hideControl();
                    openPackage("com.android.vending");
                };
            case "screensaver":
                return () -> {
                    hideControl();
                    launch(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui",
                            "com.android.systemui.Somnambulator"), "Screensaver");
                };
            default:
                return () -> { };
        }
    }

    /**
     * Small round shortcuts, four to a row, like the tvOS Control Center modules: hold OK on
     * one to move it (left/right/up/down) or remove it (Menu); the + at the end adds more.
     */
    private List<List<View>> shortcutGrid() {
        List<View> cells = new ArrayList<>();
        for (final String id : shortcutIds()) {
            final String[] def = shortcutDef(id);
            View b = shortcutButton(def[1], shortcutAction(id), false);
            shortcutIdOf.put(b, id);
            b.setContentDescription(def[2]);
            b.setOnLongClickListener(v -> {
                startCcEdit(v);
                return true;
            });
            cells.add(b);
        }
        if (shortcutIds().size() < SHORTCUTS.length) {
            View add = shortcutButton("plus", this::showAddShortcut, false);
            add.setContentDescription("Adaugă");
            cells.add(add);
        }
        List<List<View>> rows = new ArrayList<>();
        int size = px(40);
        int total = px(TILE_W) * 2 + px(6);
        int gapPx = (total - size * SHORTCUT_COLUMNS) / (SHORTCUT_COLUMNS - 1);
        LinearLayout row = null;
        List<View> current = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % SHORTCUT_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(total, -2);
                rlp.gravity = Gravity.END;
                rlp.topMargin = px(i == 0 ? 10 : 8);
                tabContent.addView(row, rlp);
                current = new ArrayList<>();
                rows.add(current);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = i % SHORTCUT_COLUMNS == 0 ? 0 : gapPx;
            row.addView(cells.get(i), lp);
            current.add(cells.get(i));
        }
        return rows;
    }

    private View shortcutButton(String glyph, Runnable action, boolean closes) {
        FrameLayout tile = (FrameLayout) glassTile(action, px(20), closes);
        tile.setOutlineProvider(roundOutline(px(20)));
        tile.setClipToOutline(true);
        ImageView icon = new ImageView(this);
        if ("plus".equals(glyph)) {
            TextView plus = text(20, Typeface.DEFAULT);
            plus.setText("+");
            plus.setGravity(Gravity.CENTER);
            tile.addView(plus, full());
        } else {
            icon.setImageBitmap(Glyphs.symbol(this, glyph, ccPx(18)));
            tile.addView(icon, at(px(18), px(18), Gravity.CENTER, 0, 0, 0, 0));
        }
        return tile;
    }

    private void showAddShortcut() {
        final List<String> have = shortcutIds();
        final List<String[]> missing = new ArrayList<>();
        for (String[] def : SHORTCUTS) {
            if (!have.contains(def[0])) {
                missing.add(def);
            }
        }
        String[] names = new String[missing.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = missing.get(i)[2];
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Adaugă în Control Center")
                .setItems(names, (dialog, which) -> {
                    have.add(missing.get(which)[0]);
                    saveShortcuts(have);
                    fillTab(controlTab, true);
                })
                .show();
    }

    private void startCcEdit(final View button) {
        if (!centerDown) {
            return;
        }
        endCcEdit();
        ccEditing = button;
        button.animate().scaleX(1.18f).scaleY(1.18f).setDuration(160).start();
        ccJiggle = ValueAnimator.ofFloat(-4f, 4f);
        ccJiggle.setDuration(120);
        ccJiggle.setRepeatMode(ValueAnimator.REVERSE);
        ccJiggle.setRepeatCount(ValueAnimator.INFINITE);
        ccJiggle.addUpdateListener(a -> button.setRotation((float) a.getAnimatedValue()));
        ccJiggle.start();
    }

    private void endCcEdit() {
        if (ccJiggle != null) {
            ccJiggle.cancel();
            ccJiggle = null;
        }
        if (ccEditing != null) {
            ccEditing.setRotation(0);
            ccEditing.animate().scaleX(ccEditing.hasFocus() ? 1.06f : 1f)
                    .scaleY(ccEditing.hasFocus() ? 1.06f : 1f).setDuration(160).start();
            ccEditing = null;
        }
    }

    private boolean ccEditKey(int code) {
        String id = shortcutIdOf.get(ccEditing);
        List<String> ids = shortcutIds();
        int index = ids.indexOf(id);
        int target = index;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                target = index - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                target = index + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                target = index - SHORTCUT_COLUMNS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                target = index + SHORTCUT_COLUMNS;
                break;
            case KeyEvent.KEYCODE_MENU:
                ids.remove(index);
                saveShortcuts(ids);
                endCcEdit();
                fillTab(controlTab, true);
                return true;
            default:
                endCcEdit();
                return true;
        }
        if (target < 0 || target >= ids.size()) {
            play("limit");
            return true;
        }
        java.util.Collections.swap(ids, index, target);
        saveShortcuts(ids);
        play("move");
        ccJiggle.cancel();
        ccJiggle = null;
        ccEditing = null;
        fillTab(controlTab, false);
        // Find the moved button again and keep editing it.
        View moved = findShortcut(id);
        if (moved != null) {
            moved.requestFocus();
            centerDown = true;
            startCcEdit(moved);
            centerDown = false;
        }
        return true;
    }

    private View findShortcut(String id) {
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(tabContent);
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (id.equals(shortcutIdOf.get(v))) {
                return v;
            }
            if (v instanceof ViewGroup) {
                for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                    queue.add(((ViewGroup) v).getChildAt(i));
                }
            }
        }
        return null;
    }

    private List<View> roundRow(List<View> buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        int size = px(48);
        int total = px(TILE_W) * 2 + px(6);
        int gapPx = Math.max(px(4), (total - size * buttons.size()) / Math.max(1, buttons.size() - 1));
        for (int i = 0; i < buttons.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = i == 0 ? 0 : gapPx;
            row.addView(buttons.get(i), lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.gravity = Gravity.END;
        rlp.topMargin = px(8);
        tabContent.addView(row, rlp);
        return buttons;
    }

    private View glassTile(final Runnable action, float radius) {
        return glassTile(action, radius, true);
    }

    private View glassTile(final Runnable action, float radius, final boolean closes) {
        final FrameLayout tile = new FrameLayout(this);
        tile.setId(View.generateViewId());
        final GlassDrawable glass = new GlassDrawable(radius, dp, 0x5214161A);
        tile.setBackground(glass);
        tileGlass.put(tile, glass);
        glass(tile, radius, 0.9f);
        tile.setFocusable(true);
        tile.setOnFocusChangeListener((v, has) -> {
            glass.setTint(has ? 0xF2FFFFFF : 0x5214161A);
            tintChildren((ViewGroup) v, has ? 0xFF111111 : Color.WHITE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setInterpolator(EASE).setDuration(200).start();
        });
        tile.setOnClickListener(v -> {
            play("select");
            if (closes) {
                hideControl();
            }
            action.run();
        });
        return tile;
    }

    private static void tintChildren(ViewGroup group, int color) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                tintChildren((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            } else if (child instanceof ImageView && !"picture".equals(child.getTag())) {
                ((ImageView) child).setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    /** Big square: a large icon (or picture) in the middle, the label at the bottom. */
    private View bigTile(String title, String glyph, Drawable picture, Runnable action) {
        FrameLayout tile = (FrameLayout) glassTile(action, px(18));
        ImageView icon = new ImageView(this);
        if (picture != null) {
            icon.setImageDrawable(picture);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setOutlineProvider(roundOutline(px(26)));
            icon.setClipToOutline(true);
            icon.setTag("picture");
            tile.addView(icon, at(px(52), px(52), Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, px(20), 0, 0));
        } else {
            icon.setImageBitmap(Glyphs.symbol(this, glyph, ccPx(46)));
            tile.addView(icon, at(px(46), px(46), Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, px(24), 0, 0));
        }
        TextView name = text(12, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setShadowLayer(0, 0, 0, 0);
        name.setText(title);
        name.setGravity(Gravity.CENTER);
        tile.addView(name, at(-1, -2, Gravity.BOTTOM, px(6), 0, px(6), px(12)));
        return tile;
    }

    // ---------------------------------------------------------------- cinema

    private boolean enteringCinema;
    private View cinemaCurtain;
    private TextView cinemaReturn;

    /** Back into Cinema, open behind the film's app, just as it was. */
    private void returnToCinema() {
        play("select");
        hideControl();
        try {
            startActivity(CinemaActivity.back(this, false));
            overridePendingTransition(0, 0);
        } catch (RuntimeException e) {
            cinemaReturn.setVisibility(View.GONE);
        }
    }
    private float cinemaPillsAlpha = 1f;

    /**
     * Cinema mode, like switching on a tvOS mode: a "Cinema mode on" notice slides in at the top
     * right and leaves, then the lights go down: the home screen fades away into black while a
     * short soundtrack swells, and Cinema opens in the dark.
     */
    private void enterCinema() {
        if (enteringCinema) {
            return;
        }
        if (CinemaActivity.alive) {
            returnToCinema();
            return;
        }
        enteringCinema = true;
        final ViewGroup content = findViewById(android.R.id.content);
        final View curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        curtain.setAlpha(0f);
        content.addView(curtain, full());
        cinemaCurtain = curtain;
        handler.postDelayed(() -> cinemaPill("Activat", "Se sting luminile…"), 250);
        handler.postDelayed(() -> {
            playCinemaSound();
            cinemaPillsAlpha = pills.getAlpha();
            home.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setInterpolator(EASE).setDuration(1000).start();
            pills.animate().alpha(0f).setDuration(500).start();
            curtain.animate().alpha(1f).setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .setDuration(1300).start();
        }, 2950);
        handler.postDelayed(() -> {
            enteringCinema = false;
            try {
                startActivity(new Intent(this, CinemaActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(CinemaActivity.EXTRA_NAME, profileFirstName()));
                overridePendingTransition(0, 0);
            } catch (RuntimeException e) {
                lightsOn(false);
            }
        }, 4300);
    }

    /** Back from Cinema: the black lifts and the home screen settles back in ("Cinema mode
     *  off" when Cinema was left, not when it only waits behind a film's app). */
    private void lightsOn(boolean announce) {
        if (announce) {
            handler.postDelayed(() -> cinemaPill("Dezactivat", "Luminile sunt din nou aprinse"), 500);
        }
        if (cinemaCurtain == null || enteringCinema) {
            return;
        }
        final View curtain = cinemaCurtain;
        cinemaCurtain = null;
        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2f))
                .setDuration(800).start();
        pills.animate().alpha(cinemaPillsAlpha).setDuration(700).start();
        curtain.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            if (curtain.getParent() instanceof ViewGroup) {
                ((ViewGroup) curtain.getParent()).removeView(curtain);
            }
        }).start();
    }

    private String profileFirstName() {
        for (Profiles.Profile p : Profiles.all(prefs, accountName())) {
            if (p.id.equals(Profiles.current(prefs)) && p.name != null) {
                String n = p.name.trim();
                int space = n.indexOf(' ');
                return space > 0 ? n.substring(0, space) : n;
            }
        }
        return "";
    }

    /**
     * "Cinema mode" switching on or off, like a tvOS mode banner: a Liquid Glass circle with the
     * Cinema sign springs in at the top right, grows into a capsule with the words (the sign
     * swells), holds a moment, folds back into the circle and floats away.
     */
    private View cinemaPillView;

    private void cinemaPill(String state, String detail) {
        final boolean on = !"Dezactivat".equals(state);
        SourcesService service = SourcesService.instance;
        if (service != null && service.cinemaDrop(on, detail)) {
            return;
        }
        final ViewGroup content = findViewById(android.R.id.content);
        final int h = px(48);
        final FrameLayout pill = new FrameLayout(this);
        pill.setBackground(new GlassDrawable(h / 2f, dp, 0x24FFFFFF));
        glass(pill, h / 2f, 1f);
        pill.setOutlineProvider(roundOutline(h / 2f));
        pill.setClipToOutline(true);
        pill.setElevation(px(14));
        final CinemaMark.Spin mark = new CinemaMark.Spin(this);
        mark.setRing(0f);
        mark.setPlay(0f);
        pill.addView(mark, at(px(32), px(32), Gravity.CENTER_VERTICAL | Gravity.START, px(8), 0, 0, 0));
        final LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView head = text(13, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setText("Mod Cinema");
        words.addView(head);
        TextView body = text(10.5f, Typeface.DEFAULT);
        body.setAlpha(0.75f);
        body.setText(detail);
        words.addView(body);
        pill.addView(words, at(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START, px(50), 0, 0, 0));
        words.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        final int wordsW = Math.max(words.getMeasuredWidth(), px(90));
        // The switch, like tvOS / iOS: it slides over a moment after the capsule opens.
        final CinemaMark.Toggle toggle = new CinemaMark.Toggle(this, dp, !on);
        pill.addView(toggle, at(px(40), px(24), Gravity.CENTER_VERTICAL | Gravity.START, px(50) + wordsW + px(14), 0, 0, 0));
        final int full = px(50) + wordsW + px(14) + px(40) + px(14);
        final FrameLayout.LayoutParams lp = at(h, h, Gravity.TOP | Gravity.END, 0, px(66), side, 0);
        if (cinemaPillView != null) {
            lensViews.remove(cinemaPillView);
            content.removeView(cinemaPillView);
        }
        cinemaPillView = pill;
        content.addView(pill, lp);
        toggle.setAlpha(0f);
        final Spring width = new Spring(h, 0.5f, v -> {
            lp.width = Math.round(v);
            pill.setLayoutParams(lp);
        });
        width.configure(0.5f, 0.8f);
        final Spring pop = new Spring(0.4f, 0.002f, v -> {
            pill.setScaleX(v);
            pill.setScaleY(v);
        });
        pop.configure(0.42f, 0.6f);
        pill.setAlpha(0f);
        pill.setScaleX(0.4f);
        pill.setScaleY(0.4f);
        pill.animate().alpha(1f).setDuration(200).start();
        pop.to(1f);
        words.setAlpha(0f);
        words.setTranslationX(-px(10));
        handler.postDelayed(() -> {
            width.to(full);
            words.animate().alpha(1f).translationX(0).setInterpolator(EASE).setStartDelay(120).setDuration(380).start();
            toggle.animate().alpha(1f).setStartDelay(200).setDuration(300).start();
        }, 340);
        handler.postDelayed(mark::load, 120);
        handler.postDelayed(() -> toggle.set(on), 1000);
        handler.postDelayed(() -> {
            words.animate().alpha(0f).setDuration(160).start();
            toggle.animate().alpha(0f).setDuration(160).start();
            width.to(h);
        }, 2300);
        handler.postDelayed(() -> {
            pop.to(0.6f);
            pill.animate().alpha(0f).translationY(-px(12)).setDuration(280).withEndAction(() -> {
                lensViews.remove(pill);
                content.removeView(pill);
                if (cinemaPillView == pill) {
                    cinemaPillView = null;
                }
            }).start();
        }, 2700);
    }

    /** The lights going down (assets/sounds/cinema_in.ogg, made by tools/cinema_sound.py). */
    private void playCinemaSound() {
        if (!prefs.getBoolean("cinema_sound", true)) {
            return;
        }
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("sounds/cinema_in.ogg")) {
            final android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            player.setVolume(0.75f, 0.75f);
            player.setOnCompletionListener(android.media.MediaPlayer::release);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            // Silent.
        }
    }

    /** Small two-line tile: icon on the left, title and detail on the right. */
    private View smallTile(String title, String detail, String glyph, Runnable action) {
        return smallTile(title, detail, glyph, action, true);
    }

    private View smallTile(String title, String detail, String glyph, Runnable action, boolean closes) {
        FrameLayout tile = (FrameLayout) glassTile(action, px(16), closes);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(Glyphs.symbol(this, glyph, ccPx(18)));
        inner.addView(icon, new LinearLayout.LayoutParams(px(18), px(18)));
        tileWords(inner, title, detail);
        tile.addView(inner, at(-1, -1, Gravity.CENTER_VERTICAL, px(9), 0, px(6), 0));
        return tile;
    }

    /** The name and state of a small tile, right of its symbol; the state can change later (setTileDetail). */
    private void tileWords(LinearLayout inner, String title, String detail) {
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(11, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setShadowLayer(0, 0, 0, 0);
        name.setText(title);
        shrinkToFit(name, 8.5f);
        words.addView(name, new LinearLayout.LayoutParams(-1, -2));
        if (!detail.isEmpty()) {
            TextView extra = text(10, Typeface.DEFAULT);
            extra.setShadowLayer(0, 0, 0, 0);
            extra.setAlpha(0.75f);
            extra.setText(detail);
            extra.setTag("detail");
            shrinkToFit(extra, 7.5f);
            words.addView(extra, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1f);
        wlp.leftMargin = px(6);
        inner.addView(words, wlp);
    }

    /**
     * A small tile whose symbol lives (CcIcon): Do Not Disturb, the sleep timer. While it is on
     * it stays white, as switches do in Control Center on iOS; focus adds the lift.
     */
    private View iconTile(String title, String detail, final CcIcon icon,
                          final java.util.function.BooleanSupplier on, Runnable action) {
        final FrameLayout tile = (FrameLayout) glassTile(action, px(16), false);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.addView(icon, new LinearLayout.LayoutParams(px(24), px(24)));
        tileWords(inner, title, detail);
        tile.addView(inner, at(-1, -1, Gravity.CENTER_VERTICAL, px(7), 0, px(6), 0));
        final View.OnFocusChangeListener base = tile.getOnFocusChangeListener();
        tile.setOnFocusChangeListener((v, has) -> {
            base.onFocusChange(v, has);
            switchLook(tile, icon, on.getAsBoolean(), has);
        });
        switchLook(tile, icon, on.getAsBoolean(), false);
        return tile;
    }

    private final java.util.WeakHashMap<View, GlassDrawable> tileGlass = new java.util.WeakHashMap<>();

    /** On: white glass and dark words; off: the dark glass; focused: white either way. */
    private void switchLook(View tile, CcIcon icon, boolean on, boolean focused) {
        GlassDrawable glass = tileGlass.get(tile);
        if (glass != null) {
            glass.setTint(focused ? 0xF2FFFFFF : on ? 0xD9FFFFFF : 0x5214161A);
        }
        tintChildren((ViewGroup) tile, on || focused ? 0xFF111111 : Color.WHITE);
        icon.setFocusedLook(on || focused);
    }

    /** The tile's state line changes with a short fade. */
    private static void setTileDetail(View tile, String detail) {
        View found = tile != null ? tile.findViewWithTag("detail") : null;
        if (found instanceof TextView && !detail.contentEquals(((TextView) found).getText())) {
            TextView extra = (TextView) found;
            extra.animate().cancel();
            extra.setAlpha(0f);
            extra.setText(detail);
            extra.animate().alpha(0.75f).setDuration(240).start();
        }
    }

    /** Romanian when the TV is in Romanian, English otherwise. */
    private static String t(String ro, String en) {
        return "ro".equals(Locale.getDefault().getLanguage()) ? ro : en;
    }

    private String sleepDetail(long at) {
        return at == 0 ? t("Oprit", "Off")
                : t("la ", "at ") + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(at));
    }

    /**
     * The sleep timer: a clock whose hours and minutes are rolled like wheels (SleepClock); OK
     * starts it. When one is set, "Don't turn off" comes first.
     */
    private void fillSleepRows(LinearLayout rows) {
        rows.removeAllViews();
        long at = SourcesService.sleepAt(this);
        if (at != 0) {
            rows.addView(listRow(t("Nu se mai oprește", "Don't turn off"), sleepDetail(at), () -> chooseSleep(0)),
                    listParams());
        }
        SleepClock clock = new SleepClock(this, dp, at, this::chooseSleep);
        clock.setId(View.generateViewId());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(6);
        rows.addView(clock, lp);
    }

    /** Switches the TV off at `at` (0: not at all). */
    private void chooseSleep(long at) {
        SourcesService service = SourcesService.instance;
        if (service == null) {
            hideControl();
            launch(new Intent("com.tcl.action.sleep"), t("Somn", "Sleep")); // the TV's own timer
            return;
        }
        play("select");
        service.setSleep(at, at == 0 ? 0 : at - System.currentTimeMillis());
        collapse();
    }

    /** Round icon-only button. */
    private View roundTile(String glyph, Runnable action) {
        FrameLayout tile = (FrameLayout) glassTile(action, px(24));
        tile.setOutlineProvider(roundOutline(px(24)));
        tile.setClipToOutline(true);
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(Glyphs.symbol(this, glyph, ccPx(22)));
        tile.addView(icon, at(px(22), px(22), Gravity.CENTER, 0, 0, 0, 0));
        return tile;
    }

    // ---------------------------------------------------------------- search

    /*
     * Search, on the home screen itself (Control Center > the magnifier): the wallpaper frosts as
     * for all apps, the bar and its cards step away, and a Liquid Glass search field with a glass
     * keyboard (and the microphone) comes down in the middle. The results are cards like the ones
     * over the bar, with the app each one is on, the ones on this TV's apps first; the focused
     * one's details are under the row. OK opens it in its app on its own page (as Cinema does);
     * down reaches "Watch on ..." for each app that has it, and the trailer. Back closes it.
     */
    private static final String[] SEARCH_KEYS = {"abcdefghijklm", "nopqrstuvwxyz", "1234567890"};
    private static final int SEARCH_SPEECH = 41;
    private static final long TMDB_HOUR = 3600_000L;
    private FrameLayout searchLayer;
    private View searchColumn;
    private Tmdb searchTmdb;
    private ExecutorService searchNet;
    private final StringBuilder searchQuery = new StringBuilder();
    private final List<CinemaActivity.Title> searchTitles = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> searchPictures =
            new android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private int searchId;
    private int searchShown = -1;
    private float heroBeforeSearch;
    private TextView searchTyped;
    private TextView searchHint;
    private View searchCaret;
    private View searchFirstKey;
    private TextView searchLabel;
    private TextView searchStatus;
    private HorizontalScrollView searchScroll;
    private LinearLayout searchRow;
    private View searchInfo;
    private ImageView searchInfoLogo;
    private TextView searchInfoName;
    private TextView searchInfoMeta;
    private TextView searchInfoText;
    private LinearLayout searchActions;

    private void openSearch() {
        if (searchLayer != null) {
            return;
        }
        if (searchTmdb == null) {
            searchTmdb = new Tmdb(this);
        }
        if (searchNet == null) {
            searchNet = Executors.newFixedThreadPool(3);
        }
        if (open) {
            setOpen(false);
        }
        handler.removeCallbacks(peekShow);
        enterPeekWhenReady = false;
        hidePeek();
        handler.removeCallbacks(heroPlay);
        stopHeroVideo();
        // The home screen steps back: the wallpaper frosts as for all apps, the rest fades.
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        home.animate().cancel();
        home.animate().alpha(0f).setDuration(260).start();
        pills.animate().alpha(0f).setDuration(200).start();
        heroBeforeSearch = hero.getAlpha();
        hero.animate().alpha(0f).setDuration(260).start();
        blurLayer.animate().alpha(1f).setDuration(320).start();
        searchQuery.setLength(0);
        searchTitles.clear();
        searchShown = -1;
        searchLayer = buildSearch();
        ViewGroup root = (ViewGroup) control.getParent();
        root.addView(searchLayer, root.indexOfChild(control), full());
        // In: the glass comes down a little and everything fades up.
        final View column = searchColumn;
        column.setAlpha(0f);
        column.setTranslationY(-px(16));
        new Spring(0f, 0.002f, v -> {
            column.setAlpha(Math.max(0f, Math.min(1f, v)));
            column.setTranslationY(-px(16) * (1f - v));
        }).to(1f, 0.42f, 0.82f);
        searchFirstKey.requestFocus();
        handler.post(searchBlink);
        if (searchTmdb.ready()) {
            search("");
        } else {
            searchSay(t("Căutarea are nevoie de cheia TMDB (python tvtool.py tmdb).",
                    "Search needs the TMDB key (python tvtool.py tmdb)."));
        }
    }

    private void closeSearch() {
        final View layer = searchLayer;
        if (layer == null) {
            return;
        }
        searchLayer = null;
        searchId++;
        handler.removeCallbacks(searchBlink);
        handler.removeCallbacks(searchNow);
        layer.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (layer.getParent() instanceof ViewGroup) {
                ((ViewGroup) layer.getParent()).removeView(layer);
            }
        }).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        home.animate().cancel();
        home.animate().alpha(1f).setDuration(300).start();
        pills.animate().alpha(1f).setDuration(300).start();
        hero.animate().alpha(heroBeforeSearch).setDuration(300).start();
        blurLayer.animate().alpha(0f).setDuration(320).start();
        focusDock(lastDockIndex);
    }

    private FrameLayout buildSearch() {
        FrameLayout layer = new FrameLayout(this);
        layer.setClipChildren(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(0, px(46), 0, 0);
        searchColumn = col;

        // The field: a capsule of glass, the magnifier, what was typed and a blinking caret.
        FrameLayout field = new FrameLayout(this);
        field.setBackground(new GlassDrawable(px(24), dp, 0x2EFFFFFF));
        glass(field, px(24), 1.2f);
        field.setOutlineProvider(roundOutline(px(24)));
        field.setClipToOutline(true);
        ImageView mag = new ImageView(this);
        mag.setImageBitmap(Glyphs.symbol(this, "search", px(18)));
        mag.setAlpha(0.8f);
        field.addView(mag, at(px(18), px(18), Gravity.CENTER_VERTICAL | Gravity.START, px(18), 0, 0, 0));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        searchTyped = text(19, Typeface.DEFAULT);
        searchTyped.setShadowLayer(0, 0, 0, 0);
        line.addView(searchTyped);
        searchCaret = new View(this);
        searchCaret.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Math.max(2, px(1.6f)), px(22));
        clp.leftMargin = px(1);
        line.addView(searchCaret, clp);
        field.addView(line, at(-2, -1, Gravity.CENTER_VERTICAL | Gravity.START, px(48), 0, px(18), 0));
        searchHint = text(17, Typeface.DEFAULT);
        searchHint.setShadowLayer(0, 0, 0, 0);
        searchHint.setText(t("Filme, seriale, actori", "Films, series, actors"));
        searchHint.setAlpha(0.55f);
        field.addView(searchHint, at(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START, px(52), 0, 0, 0));
        col.addView(field, new LinearLayout.LayoutParams(px(520), px(48)));

        LinearLayout keys = searchKeyboard();
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.topMargin = px(14);
        col.addView(keys, klp);

        searchLabel = text(13, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        searchLabel.setAlpha(0.8f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = px(16);
        llp.leftMargin = side; // the column centres its children: equal margins keep it at the bar's edge
        llp.rightMargin = side;
        col.addView(searchLabel, llp);

        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        searchScroll = new HorizontalScrollView(this);
        searchScroll.setHorizontalScrollBarEnabled(false);
        searchScroll.setClipChildren(false);
        searchScroll.setClipToPadding(false);
        searchScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        searchScroll.setFocusable(false);
        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setClipChildren(false);
        searchRow.setClipToPadding(false);
        searchRow.setPadding(side, px(12), side, px(8));
        searchScroll.addView(searchRow, new FrameLayout.LayoutParams(-2, -2));
        box.addView(searchScroll, new FrameLayout.LayoutParams(-1, -2));
        searchStatus = text(14, Typeface.DEFAULT);
        searchStatus.setAlpha(0.75f);
        searchStatus.setGravity(Gravity.CENTER);
        box.addView(searchStatus, new FrameLayout.LayoutParams(-1, px(110), Gravity.CENTER));
        col.addView(box, new LinearLayout.LayoutParams(-1, px(146)));

        // What the focused card is.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        searchInfoLogo = new ImageView(this);
        searchInfoLogo.setAdjustViewBounds(true);
        searchInfoLogo.setScaleType(ImageView.ScaleType.FIT_START);
        searchInfoLogo.setMaxHeight(px(40));
        searchInfoLogo.setMaxWidth(px(240));
        panel.addView(searchInfoLogo, new LinearLayout.LayoutParams(-2, -2));
        searchInfoName = text(21, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(searchInfoName);
        searchInfoMeta = text(12.5f, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        searchInfoMeta.setAlpha(0.85f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = px(4);
        panel.addView(searchInfoMeta, mlp);
        searchInfoText = text(12.5f, Typeface.DEFAULT);
        searchInfoText.setSingleLine(false);
        searchInfoText.setMaxLines(2);
        searchInfoText.setEllipsize(TextUtils.TruncateAt.END);
        searchInfoText.setAlpha(0.9f);
        searchInfoText.setLineSpacing(px(2), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(620), -2);
        tlp.topMargin = px(4);
        panel.addView(searchInfoText, tlp);
        searchActions = new LinearLayout(this);
        searchActions.setOrientation(LinearLayout.HORIZONTAL);
        searchActions.setClipChildren(false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = px(9);
        panel.addView(searchActions, alp);
        panel.setAlpha(0f);
        searchInfo = panel;
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.leftMargin = side;
        plp.rightMargin = side;
        col.addView(panel, plp);

        layer.addView(col, full());
        return layer;
    }

    /** a-m, n-z, the digits with space and delete (and the microphone if the TV understands speech). */
    private LinearLayout searchKeyboard() {
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        keys.setClipChildren(false);
        boolean speech = !getPackageManager().queryIntentActivities(
                new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
        searchFirstKey = null;
        for (int r = 0; r < SEARCH_KEYS.length; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            for (char c : SEARCH_KEYS[r].toCharArray()) {
                final String s = String.valueOf(c);
                View k = searchKey(s, 1, () -> searchType(s));
                line.addView(k);
                if (searchFirstKey == null) {
                    searchFirstKey = k;
                }
            }
            if (r == 2) {
                line.addView(searchKey(t("spațiu", "space"), speech ? 1 : 2, () -> searchType(" ")));
                View del = searchKey("⌫", 1, this::searchDelete);
                del.setOnLongClickListener(v -> {
                    searchQuery.setLength(0);
                    searchChanged();
                    return true;
                });
                line.addView(del);
                if (speech) {
                    line.addView(searchKey("mic", 1, this::searchListen));
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = r == 0 ? 0 : px(6);
            keys.addView(line, lp);
        }
        return keys;
    }

    /** One key of glass: white and lifted when focused (the press gives under OK, see Press). */
    private View searchKey(String label, int units, final Runnable action) {
        final FrameLayout k = new FrameLayout(this);
        k.setFocusable(true);
        final GlassDrawable surface = new GlassDrawable(px(9), dp, 0x29FFFFFF);
        k.setBackground(surface);
        glass(k, px(9), 0.8f);
        final View face;
        if ("mic".equals(label)) {
            ImageView mic = new ImageView(this);
            mic.setImageBitmap(micGlyph(px(16)));
            face = mic;
            k.addView(mic, at(px(16), px(16), Gravity.CENTER, 0, 0, 0, 0));
        } else {
            TextView v = text(label.length() > 1 && !"⌫".equals(label) ? 11 : 15,
                    Typeface.create("sans-serif-medium", Typeface.NORMAL));
            v.setShadowLayer(0, 0, 0, 0);
            v.setText(label);
            v.setGravity(Gravity.CENTER);
            face = v;
            k.addView(v, full());
        }
        final Spring lift = new Spring(0f, 0.002f, v -> {
            float s = 1f + 0.16f * v;
            k.setScaleX(s);
            k.setScaleY(s);
        });
        lift.configure(0.28f, 0.7f);
        k.setOnFocusChangeListener((v, has) -> {
            surface.setTint(has ? 0xF2FFFFFF : 0x29FFFFFF);
            int ink = has ? 0xFF111111 : Color.WHITE;
            if (face instanceof TextView) {
                ((TextView) face).setTextColor(ink);
            } else {
                ((ImageView) face).setColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            lift.to(has ? 1f : 0f);
        });
        k.setOnClickListener(v -> {
            play("select");
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(34) * units + px(6) * (units - 1), px(32));
        lp.leftMargin = px(6);
        k.setLayoutParams(lp);
        return k;
    }

    private void searchType(String s) {
        if (searchQuery.length() < 40) {
            searchQuery.append(s);
            searchChanged();
        }
    }

    private void searchDelete() {
        if (searchQuery.length() > 0) {
            searchQuery.setLength(searchQuery.length() - 1);
            searchChanged();
        }
    }

    private final Runnable searchNow = () -> search(searchQuery.toString().trim());

    private void searchChanged() {
        searchTyped.setText(searchQuery);
        searchHint.animate().alpha(searchQuery.length() == 0 ? 0.55f : 0f).setDuration(160).start();
        searchCaret.setAlpha(1f);
        handler.removeCallbacks(searchNow);
        handler.postDelayed(searchNow, 380);
    }

    private final Runnable searchBlink = new Runnable() {
        @Override
        public void run() {
            if (searchLayer == null) {
                return;
            }
            searchCaret.animate().alpha(searchCaret.getAlpha() > 0.5f ? 0f : 1f).setDuration(260).start();
            handler.postDelayed(this, 560);
        }
    };

    private void searchListen() {
        Intent speak = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, t("ro-RO", "en-US"))
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, t("Ce vrei să vezi?", "What would you like to watch?"));
        try {
            startActivityForResult(speak, SEARCH_SPEECH);
        } catch (RuntimeException e) {
            searchSay(t("Microfonul nu e disponibil", "The microphone is not available"));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SEARCH_SPEECH && result == RESULT_OK && data != null && searchLayer != null) {
            ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (said != null && !said.isEmpty()) {
                searchQuery.setLength(0);
                searchQuery.append(said.get(0).toLowerCase(Locale.getDefault()));
                searchChanged();
                handler.removeCallbacks(searchNow);
                searchNow.run();
            }
        }
    }

    /** Keys while Search is open: letters and digits type, delete deletes, Back from a button goes back to its card. */
    private boolean searchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DEL) {
            searchDelete();
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            searchListen();
            return true;
        }
        int ch = event.getUnicodeChar();
        if (ch > 0 && (Character.isLetterOrDigit(ch) || ch == ' ')) {
            searchType(String.valueOf((char) Character.toLowerCase(ch)));
            return true;
        }
        View f = getCurrentFocus();
        if (code == KeyEvent.KEYCODE_BACK && f != null && isInside(f, searchActions)
                && searchShown >= 0 && searchShown < searchRow.getChildCount()) {
            searchCard(searchShown).requestFocus();
            return true;
        }
        return false;
    }

    /** What TMDB finds for `q` (what is popular today when it is empty). */
    private void search(final String q) {
        if (searchLayer == null || !searchTmdb.ready()) {
            return;
        }
        final int id = ++searchId;
        if (!q.isEmpty()) {
            searchLabel.setText(t("Caut „", "Searching for “") + q + "”…");
        }
        final Tmdb tmdb = searchTmdb;
        searchNet.execute(() -> {
            final List<CinemaActivity.Title> found = new ArrayList<>();
            try {
                org.json.JSONObject o = q.isEmpty()
                        ? tmdb.get("/trending/all/day", "language=ro-RO", 6 * TMDB_HOUR)
                        : tmdb.get("/search/multi", "language=ro-RO&include_adult=false&query="
                        + android.net.Uri.encode(q), 6 * TMDB_HOUR);
                org.json.JSONArray results = o.optJSONArray("results");
                List<CinemaActivity.Title> known = new ArrayList<>();
                for (int i = 0; results != null && i < results.length(); i++) {
                    org.json.JSONObject r = results.optJSONObject(i);
                    if ("person".equals(r.optString("media_type"))) {
                        // An actor: the films and series they are known for, after the titles.
                        org.json.JSONArray k = r.optJSONArray("known_for");
                        for (int j = 0; k != null && j < k.length(); j++) {
                            searchAdd(known, k.optJSONObject(j));
                        }
                    } else {
                        searchAdd(found, r);
                    }
                }
                for (CinemaActivity.Title t : known) {
                    searchAdd(found, t);
                }
            } catch (java.io.IOException e) {
                handler.post(() -> {
                    if (id == searchId) {
                        searchSay(t("Nu pot căuta acum (internetul?)", "Cannot search right now (the internet?)"));
                    }
                });
                return;
            }
            while (found.size() > 16) {
                found.remove(found.size() - 1);
            }
            handler.post(() -> {
                if (id == searchId) {
                    searchShow(found, q);
                }
            });
            searchDetails(id, found);
        });
    }

    private static void searchAdd(List<CinemaActivity.Title> out, org.json.JSONObject r) {
        if (r == null) {
            return;
        }
        String kind = r.optString("media_type", "");
        if (!"movie".equals(kind) && !"tv".equals(kind)) {
            return;
        }
        CinemaActivity.Title t = new CinemaActivity.Title();
        t.id = r.optInt("id");
        t.tv = "tv".equals(kind);
        t.name = r.optString(t.tv ? "name" : "title", "");
        t.original = r.optString(t.tv ? "original_name" : "original_title", "");
        t.overview = r.optString("overview", "");
        String date = r.optString(t.tv ? "first_air_date" : "release_date", "");
        t.year = date.length() >= 4 ? date.substring(0, 4) : "";
        String poster = r.optString("poster_path", "");
        String still = r.optString("backdrop_path", "");
        t.poster = poster.isEmpty() || "null".equals(poster) ? null : poster;
        t.backdrop = still.isEmpty() || "null".equals(still) ? null : still;
        t.vote = r.optDouble("vote_average", 0);
        if (t.backdrop != null || t.poster != null) {
            searchAdd(out, t);
        }
    }

    private static void searchAdd(List<CinemaActivity.Title> out, CinemaActivity.Title t) {
        for (CinemaActivity.Title o : out) {
            if (o.id == t.id && o.tv == t.tv) {
                return;
            }
        }
        out.add(t);
    }

    /**
     * Each title's details (where it can be watched, genres, length, logo), a few at a time; once
     * they are all in, the ones on this TV's apps move to the front (unless a card has focus).
     */
    private void searchDetails(final int id, final List<CinemaActivity.Title> found) {
        final int[] left = {found.size()};
        final Tmdb tmdb = searchTmdb;
        for (final CinemaActivity.Title t : found) {
            searchNet.execute(() -> {
                if (id != searchId) {
                    return;
                }
                try {
                    String p = "/" + (t.tv ? "tv" : "movie") + "/" + t.id;
                    org.json.JSONObject d = tmdb.get(p, "language=ro-RO&append_to_response="
                            + android.net.Uri.encode("images,videos,watch/providers," + (t.tv ? "content_ratings" : "release_dates"))
                            + "&include_image_language=" + android.net.Uri.encode("ro,en,null")
                            + "&include_video_language=" + android.net.Uri.encode("ro,en,null"), 24 * TMDB_HOUR);
                    CinemaActivity.fill(this, t, d);
                    if (t.logo != null && searchPictures.get("logo" + t.logo) == null) {
                        Bitmap b = tmdb.image(t.logo, "w300", 300);
                        if (b != null) {
                            searchPictures.put("logo" + t.logo, b);
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // Shown with what the search had.
                }
                t.detailed = true;
                handler.post(() -> {
                    if (id != searchId) {
                        return;
                    }
                    int i = searchTitles.indexOf(t);
                    if (i >= 0 && i < searchRow.getChildCount()) {
                        searchBadge(searchCard(i), t);
                        if (i == searchShown) {
                            searchFillInfo(t);
                        }
                    }
                    if (--left[0] == 0) {
                        searchAvailableFirst();
                    }
                });
            });
        }
    }

    private void searchAvailableFirst() {
        View f = getCurrentFocus();
        if (f != null && isInside(f, searchRow)) {
            return;
        }
        List<CinemaActivity.Title> on = new ArrayList<>();
        List<CinemaActivity.Title> off = new ArrayList<>();
        for (CinemaActivity.Title t : searchTitles) {
            (t.included.isEmpty() ? off : on).add(t);
        }
        on.addAll(off);
        if (!on.equals(searchTitles)) {
            searchTitles.clear();
            searchTitles.addAll(on);
            searchBuildRow();
        }
    }

    private void searchShow(List<CinemaActivity.Title> found, String q) {
        searchTitles.clear();
        searchTitles.addAll(found);
        searchLabel.setText(q.isEmpty() ? t("Populare azi", "Popular today")
                : found.isEmpty() ? "" : t("Rezultate pentru „", "Results for “") + q + "”");
        searchStatus.setText(found.isEmpty() ? t("Nimic pentru „", "Nothing for “") + q + "”" : "");
        searchStatus.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
        searchStatus.setAlpha(0.75f);
        searchBuildRow();
    }

    private void searchBuildRow() {
        searchRow.removeAllViews();
        searchShown = -1;
        for (int i = 0; i < searchTitles.size(); i++) {
            searchRow.addView(searchResultCard(i));
        }
        searchScroll.scrollTo(0, 0);
        if (searchTitles.isEmpty()) {
            searchInfo.animate().alpha(0f).setDuration(200).start();
        } else {
            searchShowTitle(0);
        }
    }

    private View searchCard(int index) {
        return ((ViewGroup) searchRow.getChildAt(index)).getChildAt(0);
    }

    /**
     * A result as a card like the ones over the bar: the picture under a glass rim, lifting with
     * the apps' shadow and motion when focused, its name under it; in the corner, the app it is on.
     */
    private View searchResultCard(final int index) {
        final CinemaActivity.Title spec = searchTitles.get(index);
        int h = px(104);
        int w = Math.round(h * 16f / 9f);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        final FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2E35);
        bg.setCornerRadius(px(14));
        card.setBackground(bg);
        card.setOutlineProvider(roundOutline(px(14)));
        card.setClipToOutline(true);
        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setAlpha(0f);
        card.addView(image, full());
        TextView chip = text(9, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setShadowLayer(0, 0, 0, 0);
        chip.setTag("chip");
        chip.setPadding(px(7), px(2), px(7), px(3));
        GradientDrawable chipBack = new GradientDrawable();
        chipBack.setCornerRadius(px(8));
        chipBack.setColor(0xB3000000);
        chip.setBackground(chipBack);
        chip.setVisibility(View.GONE);
        card.addView(chip, at(-2, -2, Gravity.TOP | Gravity.END, 0, px(7), px(7), 0));
        View rim = new View(this);
        rim.setTag("rim");
        rim.setBackground(new GlassDrawable(px(14), dp, 0x00000000));
        card.addView(rim, full());
        card.setFocusable(true);
        final TextView name = text(12, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setText(spec.name);
        name.setGravity(Gravity.CENTER);
        name.setAlpha(0f);
        name.setShadowLayer(px(6), 0, px(1), 0xE6000000);
        cell.setBackground(new FocusShadow(card));
        card.setOnFocusChangeListener((v, has) -> {
            focusEffect(v, has);
            name.animate().alpha(has ? 1f : 0f).translationY(has ? px(10) : 0).setDuration(200).start();
            if (has) {
                searchShowTitle(index);
                View c = (View) v.getParent();
                int x = c.getLeft() - side;
                searchScroll.smoothScrollTo(Math.max(0, x - (searchScroll.getWidth() - 2 * side - c.getWidth()) / 2), 0);
            }
        });
        card.setOnClickListener(v -> {
            play("select");
            searchOpenFirst(spec);
        });
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(w + px(20), -2);
        nlp.topMargin = px(4);
        cell.addView(name, nlp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, -2);
        clp.rightMargin = px(28); // room for the focused card to grow without touching the next
        cell.setLayoutParams(clp);
        cell.setAlpha(0f);
        cell.setTranslationY(px(18));
        cell.animate().alpha(1f).translationY(0)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f))
                .setStartDelay(40L * Math.min(index, 6)).setDuration(420).start();
        searchPicture(spec.backdrop != null ? spec.backdrop : spec.poster, "w500", 400, b -> {
            image.setImageBitmap(b); // also from the cache, before the card is on screen
            image.animate().alpha(1f).setDuration(240).start();
        });
        searchBadge(card, spec);
        return cell;
    }

    private static void searchBadge(View card, CinemaActivity.Title t) {
        TextView chip = card.findViewWithTag("chip");
        if (chip != null && !t.included.isEmpty()) {
            chip.setText(t.included.get(0).name);
            if (chip.getVisibility() != View.VISIBLE) {
                chip.setVisibility(View.VISIBLE);
                chip.setAlpha(0f);
                chip.animate().alpha(1f).setDuration(240).start();
            }
        }
    }

    private void searchShowTitle(int index) {
        if (index == searchShown || index >= searchTitles.size()) {
            return;
        }
        searchShown = index;
        searchFillInfo(searchTitles.get(index));
    }

    /** The focused title under the row: its logo (or name), what it is, the story, where to watch. */
    private void searchFillInfo(final CinemaActivity.Title t) {
        Bitmap logo = t.logo != null ? searchPictures.get("logo" + t.logo) : null;
        if (logo != null) {
            searchInfoLogo.setImageBitmap(logo);
            searchInfoLogo.setVisibility(View.VISIBLE);
            searchInfoName.setVisibility(View.GONE);
        } else {
            searchInfoLogo.setVisibility(View.GONE);
            searchInfoName.setVisibility(View.VISIBLE);
            searchInfoName.setText(t.name);
        }
        List<String> meta = new ArrayList<>();
        meta.add(t.tv ? t("Serial", "Series") : t("Film", "Film"));
        if (!t.year.isEmpty()) {
            meta.add(t.year);
        }
        if (!t.genres.isEmpty()) {
            meta.add(t.genres);
        }
        if (!t.length.isEmpty()) {
            meta.add(t.length);
        }
        if (t.vote > 0) {
            meta.add("★ " + String.format(Locale.getDefault(), "%.1f", t.vote));
        }
        if (!t.cert.isEmpty()) {
            meta.add(t.cert);
        }
        searchInfoMeta.setText(TextUtils.join("  ·  ", meta));
        searchInfoText.setText(t.overview);
        searchActions.removeAllViews();
        for (final CinemaActivity.Provider p : t.included) {
            searchActions.addView(searchCapsule(t("Vezi pe ", "Watch on ") + p.name, () -> searchOpen(t, p)));
        }
        if (t.trailer != null) {
            searchActions.addView(searchCapsule(t("Trailer", "Trailer"), () -> searchTrailer(t)));
        }
        if (t.detailed && t.included.isEmpty()) {
            TextView note = text(11.5f, Typeface.DEFAULT);
            note.setAlpha(0.7f);
            note.setText(t("Nu e pe abonamentele tale", "Not on your apps")
                    + (t.rent.isEmpty() ? "" : t("  ·  de închiriat: ", "  ·  to rent: ") + TextUtils.join(", ", t.rent)));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.gravity = Gravity.CENTER_VERTICAL;
            nlp.leftMargin = searchActions.getChildCount() > 0 ? px(6) : 0;
            searchActions.addView(note, nlp);
        }
        int cardId = searchShown >= 0 && searchShown < searchRow.getChildCount() ? searchCard(searchShown).getId() : View.NO_ID;
        for (int i = 0; i < searchActions.getChildCount(); i++) {
            searchActions.getChildAt(i).setNextFocusUpId(cardId);
        }
        if (searchInfo.getAlpha() < 1f) {
            searchInfo.animate().alpha(1f).setDuration(260).start();
        }
    }

    /** "Watch on ...": a capsule of glass, white when focused. */
    private View searchCapsule(String label, final Runnable action) {
        final TextView b = text(12.5f, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setShadowLayer(0, 0, 0, 0);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(16), 0, px(16), 0);
        b.setFocusable(true);
        final GlassDrawable surface = new GlassDrawable(px(15), dp, 0x33FFFFFF);
        b.setBackground(surface);
        glass(b, px(15), 0.8f);
        b.setOnFocusChangeListener((v, has) -> {
            surface.setTint(has ? 0xF2FFFFFF : 0x33FFFFFF);
            b.setTextColor(has ? 0xFF111111 : Color.WHITE);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setInterpolator(EASE).setDuration(200).start();
        });
        b.setOnClickListener(v -> {
            play("select");
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(30));
        lp.rightMargin = px(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void searchOpenFirst(CinemaActivity.Title t) {
        if (!t.included.isEmpty()) {
            searchOpen(t, t.included.get(0));
        } else if (t.trailer != null) {
            searchTrailer(t);
        } else if (!t.detailed) {
            searchSay(t("Un moment, caut unde rulează…", "One moment, looking for where it is on…"));
        } else {
            searchSay(t("Nu e pe abonamentele tale", "Not on your apps"));
        }
    }

    private void searchOpen(final CinemaActivity.Title t, final CinemaActivity.Provider p) {
        final Tmdb tmdb = searchTmdb;
        searchNet.execute(() -> {
            // The app's own id for the title may take a moment (Wikidata): off the main thread.
            if (t.ids == null) {
                t.ids = tmdb.streamingIds(t.tv, t.id);
            }
            handler.post(() -> CinemaActivity.open(this, tmdb, t, p));
        });
    }

    private void searchTrailer(CinemaActivity.Title t) {
        String address = "Vimeo".equals(t.trailerSite) ? "https://vimeo.com/" + t.trailer
                : "https://www.youtube.com/watch?v=" + t.trailer;
        Intent watch = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!"Vimeo".equals(t.trailerSite)) {
            watch.setPackage("com.google.android.youtube.tv");
        }
        try {
            startActivity(watch);
        } catch (RuntimeException e) {
            try {
                startActivity(watch.setPackage(null));
            } catch (RuntimeException ignored) {
                searchSay(t("Nu pot porni trailerul", "Cannot play the trailer"));
            }
        }
    }

    private interface SearchGot {
        void got(Bitmap b);
    }

    private void searchPicture(final String path, final String size, final int maxW, final SearchGot got) {
        if (path == null) {
            return;
        }
        final String key = size + path;
        Bitmap have = searchPictures.get(key);
        if (have != null) {
            got.got(have);
            return;
        }
        final Tmdb tmdb = searchTmdb;
        searchNet.execute(() -> {
            Bitmap b = tmdb.image(path, size, maxW);
            if (b != null) {
                searchPictures.put(key, b);
                handler.post(() -> got.got(b));
            }
        });
    }

    private void searchSay(String message) {
        searchStatus.setText(message);
        searchStatus.setVisibility(View.VISIBLE);
        searchStatus.setAlpha(0f);
        searchStatus.animate().alpha(0.75f).setDuration(220).start();
        if (!searchTitles.isEmpty()) {
            handler.postDelayed(() -> searchStatus.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> searchStatus.setVisibility(View.GONE)).start(), 2600);
        }
    }

    /** The microphone, drawn like SF Symbols' "mic". */
    private static Bitmap micGlyph(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(s * 0.34f, s * 0.06f, s * 0.66f, s * 0.6f), s * 0.16f, s * 0.16f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.08f);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(new RectF(s * 0.22f, s * 0.2f, s * 0.78f, s * 0.72f), 0, 180, false, p);
        c.drawLine(s * 0.5f, s * 0.72f, s * 0.5f, s * 0.9f, p);
        c.drawLine(s * 0.36f, s * 0.92f, s * 0.64f, s * 0.92f, p);
        return b;
    }

    // ---------------------------------------------------------------- expanded Wi-Fi / Bluetooth

    private View expanded;
    private android.animation.Animator kenBurns;
    private Spring expandSpring;
    private int expandedKind;
    private int expandTileLeft;
    private int expandTileTop;
    /** A panel is still growing out of its tile: keys wait until it has settled. */
    private boolean growing;
    private CcIcon sleepIcon;
    private View ccWifi;
    private View ccBluetooth;
    private View ccDnd;
    private View ccSleep;
    private View ccBig;
    private Runnable stopBtDiscovery;
    private final List<Connectivity.Device> btFound = new ArrayList<>();

    /**
     * Like a Control Center module opening up on tvOS: the tiles step aside and one glass
     * panel the width of both columns grows out of the tile, listing networks or devices.
     */
    private void expand(final int kind) {
        final boolean wifi = kind == 0;
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(10), px(10), px(10), px(12));
        panel.setBackground(new GlassDrawable(px(18), dp, 0x6614161A));
        glass(panel, px(18), 1.2f);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        TextView heading = text(14, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setText(wifi ? "Wi-Fi" : kind == 1 ? "Bluetooth" : t("Oprire automată", "Sleep timer"));
        heading.setPadding(px(6), 0, 0, px(6));
        panel.addView(heading);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        panel.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        if (wifi) {
            Connectivity.scan(this);
            fillWifiRows(rows);
            handler.postDelayed(() -> {
                if (expanded == panel) {
                    fillWifiRows(rows);
                }
            }, 3500);
        } else if (kind == 2) {
            fillSleepRows(rows);
        } else {
            fillBtRows(rows);
            stopBtDiscovery = Connectivity.discover(this, d -> handler.post(() -> {
                synchronized (btFound) {
                    for (Connectivity.Device known : btFound) {
                        if (known.address.equals(d.address)) {
                            return;
                        }
                    }
                    btFound.add(d);
                }
                if (expanded == panel) {
                    fillBtRows(rows);
                }
            }));
        }
        // tvOS: the module itself grows into the list, out of the tile that was pressed, while
        // the other tiles fade away under it; the glass keeps its shape (nothing is squashed)
        // and the rows fade in once there is room for them. It grows over the tiles (in the
        // overlay) and takes its place in the panel once it has settled.
        View from = kind == 0 ? ccWifi : kind == 1 ? ccBluetooth : ccSleep;
        final int fullW = px(TILE_W) * 2 + px(6);
        final int right = tabContent.getWidth() - tabContent.getPaddingRight();
        final int top = tabContent.getPaddingTop();
        int fromLeft = right - px(TILE_W);
        int fromTop = top;
        if (from != null && from.isAttachedToWindow()) {
            fromLeft = 0;
            fromTop = 0;
            for (View v = from; v != null && v != tabContent; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                fromLeft += v.getLeft();
                fromTop += v.getTop();
            }
        }
        expandTileLeft = fromLeft;
        expandTileTop = fromTop;
        final int sl = fromLeft;
        final int st = fromTop;
        final int tileW = px(TILE_W);
        final int tileH = px(58);
        expanded = panel;
        expandedKind = kind;
        growing = true;
        panel.setOutlineProvider(roundOutline(px(18)));
        panel.setClipToOutline(true);
        panel.measure(View.MeasureSpec.makeMeasureSpec(fullW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int fullH = panel.getMeasuredHeight();
        heading.setAlpha(0f);
        rows.setAlpha(0f);
        tabContent.getOverlay().add(panel);
        panel.layout(sl, st, sl + tileW, st + tileH);
        for (int i = 0; i < tabContent.getChildCount(); i++) {
            View child = tabContent.getChildAt(i);
            child.forceHasOverlappingRendering(false); // faded tile by tile, no offscreen copy each frame
            child.animate().alpha(0f).setStartDelay(0).setDuration(180).start();
        }
        expandSpring = new Spring(0f, 0.002f, t -> {
            int l = Math.round(sl + (right - fullW - sl) * t);
            int tp = Math.round(st + (top - st) * t);
            int w = Math.round(tileW + (fullW - tileW) * t);
            int h = Math.max(tileH / 2, Math.round(tileH + (fullH - tileH) * t));
            panel.layout(l, tp, l + w, tp + h);
            float c = Math.max(0f, Math.min(1f, (t - 0.4f) / 0.6f));
            heading.setAlpha(c);
            rows.setAlpha(c);
        });
        expandSpring.onRest(() -> {
            growing = false;
            tabContent.getOverlay().remove(panel);
            if (expanded != panel) {
                return;
            }
            // Settled: into its own place, where the list can grow or shrink as networks and
            // devices come and go.
            for (int i = 0; i < tabContent.getChildCount(); i++) {
                tabContent.getChildAt(i).setVisibility(View.GONE);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(fullW, -2);
            lp.gravity = Gravity.END;
            tabContent.addView(panel, 0, lp);
            View first = rows.getChildCount() > 0 ? rows.getChildAt(0) : null;
            if (first != null) {
                first.requestFocus();
            }
        });
        expandSpring.to(1f, 0.36f, 0.86f);
    }

    private void collapse() {
        if (expanded == null) {
            return;
        }
        play("back");
        handler.removeCallbacksAndMessages(null);
        updateClock();
        if (stopBtDiscovery != null) {
            stopBtDiscovery.run();
            stopBtDiscovery = null;
        }
        synchronized (btFound) {
            btFound.clear();
        }
        final View panel = expanded;
        expanded = null;
        // The list folds back into the module: rows fade first, then the glass shrinks.
        final ViewGroup content = (ViewGroup) panel;
        for (int i = 0; i < content.getChildCount(); i++) {
            content.getChildAt(i).animate().alpha(0f).setDuration(110).start();
        }
        // The focused row keeps focus while the panel folds (blocking it would hand focus to
        // the header buttons and flash them); then focus goes back to the module it came from.
        final int wasKind = expandedKind;
        if (expandSpring != null) {
            expandSpring.stop();
        }
        growing = false;
        // The tiles come back at once, fading in under the glass as it folds into its tile;
        // nothing is rebuilt, so nothing blinks.
        for (int i = 0; i < tabContent.getChildCount(); i++) {
            View child = tabContent.getChildAt(i);
            if (child == panel) {
                continue;
            }
            child.animate().cancel();
            child.forceHasOverlappingRendering(false);
            child.setVisibility(View.VISIBLE);
            child.animate().alpha(1f).setStartDelay(60).setDuration(220).start();
        }
        View back = wasKind == 0 ? ccWifi : wasKind == 1 ? ccBluetooth : ccSleep;
        if (back == null || !back.isAttachedToWindow()) {
            back = tabContent.findViewWithTag("first");
        }
        if (back != null) {
            back.requestFocus();
        }
        if (panel.getParent() == tabContent) {
            tabContent.getOverlay().add(panel); // drawn where it is, over the tiles
        }
        final int fromL = panel.getLeft();
        final int fromT = panel.getTop();
        final int fromW = panel.getWidth();
        final int fromH = panel.getHeight();
        final int toL = expandTileLeft;
        final int toT = expandTileTop;
        final int toW = px(TILE_W);
        final int toH = px(58);
        refreshCcTiles();
        expandSpring = new Spring(1f, 0.004f, t -> {
            int l = Math.round(toL + (fromL - toL) * t);
            int tp = Math.round(toT + (fromT - toT) * t);
            panel.layout(l, tp, l + Math.round(toW + (fromW - toW) * t), tp + Math.round(toH + (fromH - toH) * t));
            panel.setAlpha(Math.max(0f, Math.min(1f, t * 4f)));
        });
        expandSpring.onRest(() -> tabContent.getOverlay().remove(panel));
        expandSpring.to(0f, 0.3f, 0.92f);
    }

    /**
     * While Control Center is open nothing behind it moves, so the home screen is drawn once into
     * a texture and only Control Center is redrawn each frame: on this TV redrawing everything
     * (wallpaper, the bar's glass, the cards) took 18 ms a frame, too slow for 60 fps.
     */
    private void freezeBehind(boolean frozen) {
        if ((PERF & 128) != 0) {
            return;
        }
        int type = frozen ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        for (View v : (PERF & 256) != 0 ? new View[]{home} : new View[]{home, hero, blurLayer, wallSharp}) {
            if (v != null && v.getLayerType() != type) {
                v.setLayerType(type, null);
            }
        }
    }

    /**
     * For recording the presentation from a computer: what normally takes OK held down.
     * "dock": the third app in the bar starts to wobble (left/right then move it);
     * "shortcuts": Control Center opens and its round shortcuts start to wobble;
     * "profile" / "addprofile": the profiles, then this profile's page or a new profile's name.
     */
    private void demo(String what) {
        if ("dock".equals(what) && dock.getChildCount() > 2) {
            View tile = dock.getChildAt(2);
            tile.requestFocus();
            handler.postDelayed(() -> {
                centerDown = true;
                startEdit(tile);
                centerDown = false;
            }, 500);
        } else if ("profile".equals(what) || "addprofile".equals(what)) {
            // The profiles in Control Center, then this profile's page (name, picture) or a new one.
            showControl(false);
            handler.postDelayed(() -> tabButtons.get(TAB_PROFILE).requestFocus(), 700);
            handler.postDelayed(() -> {
                if ("addprofile".equals(what)) {
                    askProfileName(null);
                    return;
                }
                for (Profiles.Profile p : Profiles.all(prefs, accountName())) {
                    if (p.id.equals(Profiles.current(prefs))) {
                        showProfileEdit(p);
                        return;
                    }
                }
            }, 2000);
        } else if ("shortcuts".equals(what)) {
            showControl(false);
            handler.postDelayed(() -> {
                for (Map.Entry<View, String> e : shortcutIdOf.entrySet()) {
                    if ("wallpaper".equals(e.getValue()) && e.getKey().isAttachedToWindow()) {
                        e.getKey().requestFocus();
                        startCcEdit(e.getKey());
                        return;
                    }
                }
            }, 900);
        }
    }

    /** hello is on screen (the TV has just come on): the home screen waits behind it, out of sight. */
    void helloStarted() {
        if (!resumed || searchLayer != null || control.getVisibility() == View.VISIBLE || open) {
            return;
        }
        arriving = true;
        home.animate().cancel();
        home.setAlpha(0f);
        pills.animate().cancel();
        pills.setAlpha(0f);
    }

    /**
     * hello is leaving: the home screen arrives, as tvOS does coming back from sleep. The
     * wallpaper, frosted under hello, comes into focus a little large and settles; the bar rises
     * with its glass, the apps lighting up one after another; the clock and the profile come down.
     */
    void arrive() {
        if (!arriving) {
            return;
        }
        arriving = false;
        wallSharp.setScaleX(1.06f);
        wallSharp.setScaleY(1.06f);
        wallSharp.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2f))
                .setDuration(1500).start();
        home.setAlpha(1f);
        tray.setAlpha(0f);
        tray.setTranslationY(px(40));
        tray.animate().alpha(1f).translationY(0).setInterpolator(new OvershootInterpolator(0.7f))
                .setStartDelay(150).setDuration(700).start();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View tile = dock.getChildAt(i);
            tile.setAlpha(0f);
            tile.animate().alpha(1f).setStartDelay(300 + 60L * i).setDuration(380).start();
        }
        pills.setTranslationY(-px(16));
        pills.animate().alpha(1f).translationY(0).setInterpolator(EASE).setStartDelay(420).setDuration(520).start();
    }

    /** After a panel folds back: the tiles say how things are now (network, Bluetooth, the timer). */
    private void refreshCcTiles() {
        setTileDetail(ccWifi, wifiName());
        setTileDetail(ccBluetooth, Connectivity.bluetoothOn() ? t("Pornit", "On") : t("Oprit", "Off"));
        if (sleepIcon != null && ccSleep != null) {
            long at = SourcesService.sleepAt(this);
            sleepIcon.setTimer(at, prefs.getLong("sleep_len", 0));
            sleepIcon.set(at != 0);
            setTileDetail(ccSleep, sleepDetail(at));
            switchLook(ccSleep, sleepIcon, at != 0, ccSleep.isFocused());
        }
    }

    private void fillWifiRows(LinearLayout rows) {
        rows.removeAllViews();
        List<Connectivity.Network> networks = Connectivity.networks(this);
        int shown = 0;
        for (final Connectivity.Network n : networks) {
            if (shown++ >= 6) {
                break;
            }
            int bars = n.level >= 3 ? 3 : n.level == 2 ? 2 : 1;
            String[] marks = n.current ? new String[]{"check", "wifi" + bars}
                    : n.secure ? new String[]{"lock", "wifi" + bars} : new String[]{"wifi" + bars};
            rows.addView(listRow(n.ssid, "", marks, () -> Connectivity.join(this, n, () -> {
                if (expanded != null) {
                    fillWifiRows(rows);
                }
            })), listParams());
        }
        if (networks.isEmpty()) {
            rows.addView(listRow("Caut rețele…", "", () -> { }), listParams());
        }
        rows.addView(listRow("Setări Wi-Fi", "›", () -> {
            hideControl();
            launch(new Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi");
        }), listParams());
    }

    private void fillBtRows(LinearLayout rows) {
        View focused = getCurrentFocus();
        rows.removeAllViews();
        for (final Connectivity.Device d : Connectivity.paired()) {
            rows.addView(listRow(d.name, d.connected ? "Conectat" : "", () -> {
                hideControl();
                launch(new Intent().setClassName("com.android.tv.settings",
                        "com.tcl.settings.accessories.AccessoriesActivity"), d.name);
            }), listParams());
        }
        synchronized (btFound) {
            List<Connectivity.Device> named = new ArrayList<>();
            for (Connectivity.Device d : btFound) {
                // Devices that do not say their name (only an address) are left out, as on tvOS.
                if (d.name != null && !d.name.trim().isEmpty()
                        && !d.name.matches("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}")) {
                    named.add(d);
                }
            }
            for (final Connectivity.Device d : named.subList(0, Math.min(4, named.size()))) {
                rows.addView(listRow(d.name, "Asociază", () -> SourcesService.say(this,
                        Connectivity.pair(d) ? "Se asociază cu " + d.name + "…" : "Nu am putut porni asocierea")),
                        listParams());
            }
        }
        rows.addView(listRow(btFound.isEmpty() ? "Caut dispozitive…" : "Alte dispozitive", "", () -> { }), listParams());
        rows.addView(listRow("Asociază telecomandă sau accesoriu", "›", () -> {
            hideControl();
            launch(new Intent().setClassName("com.android.tv.settings",
                    "com.android.tv.settings.accessories.AddAccessoryActivity"), "Bluetooth");
        }), listParams());
        if (focused == null || !focused.isAttachedToWindow()) {
            View first = rows.getChildAt(0);
            if (first != null) {
                first.requestFocus();
            }
        }
    }

    private LinearLayout.LayoutParams listParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(34));
        lp.topMargin = px(4);
        return lp;
    }

    /** A row inside an expanded panel: name on the left, state on the right, white when focused. */
    private View listRow(String title, String value, final Runnable action) {
        return listRow(title, value, null, action);
    }

    /** The same row with small symbols at its right end (checkmark, lock, signal). */
    private View listRow(String title, String value, String[] marks, final Runnable action) {
        final FrameLayout row = new FrameLayout(this);
        final List<ImageView> markViews = new ArrayList<>();
        final GlassDrawable glass = new GlassDrawable(px(11), dp, 0x1AFFFFFF);
        row.setBackground(glass);
        final TextView name = text(12, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setText(title);
        shrinkToFit(name, 9f);
        row.addView(name, at(-1, -2, Gravity.CENTER_VERTICAL | Gravity.START, px(10), 0, px(70), 0));
        final TextView state = text(11, Typeface.DEFAULT);
        state.setAlpha(0.7f);
        state.setText(value);
        if (marks != null && marks.length > 0) {
            LinearLayout icons = new LinearLayout(this);
            icons.setOrientation(LinearLayout.HORIZONTAL);
            icons.setGravity(Gravity.CENTER_VERTICAL);
            for (String mark : marks) {
                ImageView icon = new ImageView(this);
                icon.setImageBitmap(Glyphs.symbol(this, mark, ccPx(13)));
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(13), px(13));
                ilp.leftMargin = px(7);
                icons.addView(icon, ilp);
                markViews.add(icon);
            }
            row.addView(icons, at(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, px(10), 0));
        } else {
            row.addView(state, at(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, px(10), 0));
        }
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, has) -> {
            for (ImageView icon : markViews) {
                if (has) {
                    icon.setColorFilter(0xFF222222, android.graphics.PorterDuff.Mode.SRC_IN);
                } else {
                    icon.clearColorFilter();
                }
            }
            glass.setTint(has ? 0xF2FFFFFF : 0x1AFFFFFF);
            name.setTextColor(has ? 0xFF111111 : Color.WHITE);
            state.setTextColor(has ? 0xFF333333 : Color.WHITE);
            Motion m = motion(v);
            m.focus.to(has ? 0.3f : 0f, 0.16f, 1f);
        });
        row.setOnClickListener(v -> {
            play("select");
            action.run();
        });
        return row;
    }

    private static boolean isInside(View view, View ancestor) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) {
                return true;
            }
        }
        return false;
    }

    private boolean hideControl() {
        if (control.getVisibility() != View.VISIBLE) {
            return false;
        }
        endCcEdit();
        if (expanded != null) {
            if (stopBtDiscovery != null) {
                stopBtDiscovery.run();
                stopBtDiscovery = null;
            }
            expanded = null;
        }
        final boolean showPills = !open;
        control.animate().alpha(0f).setDuration(200).withLayer()
                .withEndAction(() -> {
                    control.setVisibility(View.GONE);
                    freezeBehind(false);
                    if (showPills && searchLayer == null) {
                        pills.animate().alpha(1f).setDuration(200).start();
                    }
                }).start();
        controlList.animate().translationX(px(24)).setDuration(200).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (controlReturn != null && controlReturn.isAttachedToWindow() && controlReturn != avatar) {
            controlReturn.requestFocus();
        } else {
            avatar.requestFocus();
        }
        controlReturn = null;
        return true;
    }

    private String inputLabel(TvInputInfo info) {
        CharSequence label = info.loadCustomLabel(this);
        if (label == null || label.length() == 0) {
            label = info.loadLabel(this);
        }
        return String.valueOf(label);
    }

    @SuppressWarnings("deprecation")
    private String wifiName() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi == null || !wifi.isWifiEnabled()) {
                return "Oprit";
            }
            WifiInfo info = wifi.getConnectionInfo();
            String ssid = info != null ? info.getSSID() : null;
            if (ssid == null || ssid.contains("unknown") || ssid.equals("0x")) {
                return info != null && info.getNetworkId() != -1 ? "Conectat" : "";
            }
            return ssid.replace("\"", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ================================================================ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (enteringCinema || growing) {
            return true;
        }
        Press.key(event, getCurrentFocus());
        if (idle != null && getCurrentFocus() == idle && event.getAction() == KeyEvent.ACTION_DOWN
                && control.getVisibility() != View.VISIBLE && profileModal == null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        focusDock(lastDockIndex);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getRepeatCount() == 0) {
                        showControl(false);
                    }
                    return true;
                default:
                    break;
            }
        }
        if (event.getDeviceId() != -1) {
            previews.note("remote key " + KeyEvent.keyCodeToString(keyCode)
                    + (event.getAction() == KeyEvent.ACTION_DOWN ? " down" : " up") + " rep=" + event.getRepeatCount()
                    + " t=" + event.getEventTime() + " dev=" + event.getDeviceId());
        }

        // One press = one step. This remote can deliver a press twice (Bluetooth and infrared, or
        // a bounce) and repeats a held key very fast: duplicates within 90 ms of a release are
        // dropped, and held-key repeats are limited to one step every 150 ms.
        boolean dpad = keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT;
        if (dpad) {
            long t = event.getEventTime();
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    boolean duplicate = keyCode == lastDpadCode && t - lastDpadUpAt < 90 && t - lastDpadDownAt < 300;
                    if (duplicate) {
                        previews.note("dpad duplicate dropped " + KeyEvent.keyCodeToString(keyCode)
                                + " +" + (t - lastDpadDownAt) + "ms dev=" + event.getDeviceId());
                        swallowDpadUp = true;
                        return true;
                    }
                    lastDpadDownAt = t;
                    lastDpadCode = keyCode;
                } else {
                    // This remote's "released" can arrive ~0.6 s late, and Android then repeats
                    // a short press by itself: a repeat only counts once the key is really held.
                    if (t - event.getDownTime() < 750 || t - lastDpadRepeatAt < 150) {
                        return true;
                    }
                    lastDpadRepeatAt = t;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                lastDpadUpAt = t;
                if (swallowDpadUp) {
                    swallowDpadUp = false;
                    return true;
                }
            }
        }
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (peekHiding != null && event.getAction() == KeyEvent.ACTION_DOWN && !ok) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideThisPreview();
            } else {
                endPeekHide();
            }
            return true;
        }
        if (ok && event.getAction() == KeyEvent.ACTION_UP && swallowOkUp) {
            swallowOkUp = false;
            centerDown = false;
            return true;
        }
        if (ok) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    centerDown = true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!centerDown) {
                    previews.note("ignored stray OK release");
                    return true;
                }
                centerDown = false;
            }
        }
        if (searchLayer != null) {
            return searchKeyEvent(event) || super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveDx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                moveDy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                moveAt = SystemClock.uptimeMillis();
            }
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                onVolumeKey(code);
                return true;
            }
            if (fullscreen) {
                // Any other key brings the home screen back.
                setFullscreen(false);
                return true;
            }
            if (ccEditing != null) {
                if (event.getRepeatCount() > 0) {
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    swallowOkUp = true;
                }
                return ccEditKey(code);
            }
            if (editing != null) {
                // Holding OK keeps sending repeats; only a fresh press counts.
                if (event.getRepeatCount() > 0) {
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    // The OK that ends editing must not also open the app when it is released.
                    swallowOkUp = true;
                }
                return editKey(code);
            }
            // CH +/- (and TCL's own channel key, 4001): straight to the antenna's live channels.
            if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                    || code == KeyEvent.KEYCODE_LAST_CHANNEL || code == 4001) {
                if (event.getRepeatCount() == 0) {
                    hideControl();
                    play("select");
                    LiveTv.open(this);
                }
                return true;
            }
            // The remote's Settings (gear) key arrives as NOTIFICATION on this TV: open Settings.
            if (code == KeyEvent.KEYCODE_NOTIFICATION || code == KeyEvent.KEYCODE_SETTINGS) {
                if (event.getRepeatCount() == 0) {
                    hideControl();
                    play("select");
                    startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (editing == null && !hideControl()) {
                    showControl(true);
                }
                return true;
            }
            // The remote's Source/Input key: our Control Center instead of the TCL overlay.
            if (code == KeyEvent.KEYCODE_TV_INPUT) {
                if (!hideControl()) {
                    showControl(true);
                }
                // The TV's window manager opens TCL's input panel for this key before we see it;
                // TCL's own service closes it again. A few tries, since it appears ~150 ms later.
                for (long delay : new long[]{120, 350, 700, 1200}) {
                    handler.postDelayed(this::dismissTclPanel, delay);
                }
                return true;
            }
            View focused = getCurrentFocus();
            boolean homeVisible = control.getVisibility() != View.VISIBLE && !open;
            if (homeVisible && code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                setFullscreen(true);
                return true;
            }
            // Control Center sits over the previews: its keys are its own, never the previews'.
            boolean ccOpen = control.getVisibility() == View.VISIBLE;
            if (browsing && !ccOpen && focused != null && focused.getParent() != pills
                    && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK)) {
                play("back");
                exitBrowse();
                return true;
            }
            if (browsing && !ccOpen && focused == avatar && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                exitBrowse();
                return true;
            }
            // Fewer presses to the top: from the previews, Up opens Control Center straight away
            // (the profile is its first tab); an app without previews opens it from the dock.
            if (browsing && !ccOpen && code == KeyEvent.KEYCODE_DPAD_UP && focused != null
                    && isInside(focused, shelf)) {
                showControl(false);
                return true;
            }
            boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
            boolean inRows = focused != null && (focused.getParent() == dock || chipsFocused()
                    || peek != null && isInside(focused, peek));
            if (homeVisible && vertical && inRows) {
                // One press of this remote can arrive as several quick presses: between the bar,
                // the cards, the filters and Control Center, only one step per press.
                long nowV = SystemClock.uptimeMillis();
                if (nowV - lastVerticalAt < 320) {
                    return true;
                }
                lastVerticalAt = nowV;
            }
            if (homeVisible && focused != null && peek != null && isInside(focused, peek)) {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusDock(lastDockIndex);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    selectedChip().requestFocus();
                    return true;
                }
            }
            if (homeVisible && chipsFocused()) {
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    showControl(false);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    applyTab();
                    View firstCell = peek.getChildCount() > 0 ? peek.getChildAt(0) : null;
                    if (firstCell instanceof ViewGroup) {
                        ((ViewGroup) firstCell).getChildAt(0).requestFocus();
                    } else {
                        focusDock(lastDockIndex);
                    }
                    return true;
                }
            }
            if (homeVisible && code == KeyEvent.KEYCODE_DPAD_UP && focused != null
                    && focused.getParent() == dock) {
                if (!enterPeek()) {
                    if (SystemClock.uptimeMillis() - peekAskedAt < 1500) {
                        enterPeekWhenReady = true; // the cards are on their way: into them when they arrive
                    } else {
                        showControl(false);
                    }
                }
                return true;
            }
            if (homeVisible && code == KeyEvent.KEYCODE_DPAD_UP && focused == avatar) {
                showControl(false);
                return true;
            }
            if (focused != null && control.getVisibility() != View.VISIBLE
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                ViewGroup row = rowOf(focused);
                if (row != null) {
                    View next = focused.focusSearch(code == KeyEvent.KEYCODE_DPAD_LEFT
                            ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                    if (next == null || rowOf(next) != row) {
                        bump(focused, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** The dock, or the grid row holding the tile; null for anything else. */
    private ViewGroup rowOf(View view) {
        if (view.getParent() == dock) {
            return dock;
        }
        if (view.getParent() instanceof ViewGroup && view.getParent().getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) view.getParent().getParent();
            if (row.getParent() == grid || row == shelfRow) {
                return row;
            }
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to: Back closes the Control Center or returns to the top row.
        play("back");
        if (profileModal != null) {
            closeProfileModal();
            return;
        }
        if (searchLayer != null) {
            closeSearch();
            return;
        }
        if (peekFocused() || chipsFocused()) {
            focusDock(lastDockIndex);
            return;
        }
        if (camerasPane != null) {
            camerasPane.back();
            return;
        }
        if (openFolder != null) {
            closeFolder();
            return;
        }
        if (expanded != null) {
            collapse();
            return;
        }
        if (hideControl()) {
            return;
        }
        if (editing != null) {
            endEdit();
            return;
        }
        if (open) {
            setOpen(false);
            focusDock(lastDockIndex);
            return;
        }
        View focused = getCurrentFocus();
        if (focused != null && focused.getParent() == dock) {
            focusNothing(); // Back on the row: nothing selected, like arriving home
        }
    }

    // ================================================================ top shelf

    private void buildShelf() {
        shelf = new FrameLayout(this);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        shelf.setAlpha(0f);
        shelfScroll = new HorizontalScrollView(this) {
            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                // Keep the focused poster a little in from the left edge, like tvOS.
                View cell = focused.getParent() == shelfRow ? focused : (View) focused.getParent();
                int max = Math.max(0, shelfRow.getWidth() - getWidth());
                scrollShelfTo(Math.min(max, Math.max(0, cell.getLeft() - side - px(14))));
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }

            @Override
            public boolean executeKeyEvent(KeyEvent event) {
                return false;
            }
        };
        shelfScroll.setHorizontalScrollBarEnabled(false);
        shelfScroll.setFocusable(false);
        shelfScroll.setClipChildren(false);
        shelfScroll.setClipToPadding(false);
        shelfScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        shelfRow = new LinearLayout(this);
        shelfRow.setOrientation(LinearLayout.HORIZONTAL);
        shelfRow.setClipChildren(false);
        shelfRow.setClipToPadding(false);
        shelfRow.setPadding(side + px(11), px(26), side + px(11), 0);
        shelfScroll.addView(shelfRow, new FrameLayout.LayoutParams(-2, -2));
        shelf.addView(shelfScroll, new FrameLayout.LayoutParams(-1, -2));
        shelf.setVisibility(View.INVISIBLE);
        home.addView(shelf, at(-1, shelfH + px(70), Gravity.TOP, 0, screenH - shelfH - px(96), 0, 0));
    }

    private boolean previewsFresh(String pkg) {
        Long when = previewTime.get(pkg);
        return previewCache.containsKey(pkg) && when != null
                && SystemClock.elapsedRealtime() - when < PREVIEW_TTL;
    }

    private void prefetchPreviews(final String pkg) {
        if (previewsFresh(pkg)) {
            return;
        }
        worker.execute(() -> {
            final List<Previews.Item> items = previews.load(pkg, 24);
            handler.post(() -> {
                previewCache.put(pkg, items);
                previewTime.put(pkg, SystemClock.elapsedRealtime());
                if (pkg.equals(shelfPkg) && !browsing && !open) {
                    schedulePeek(pkg);
                }
                if (pkg.equals(shelfPkg) && !open && !isDestroyed() && !browsing) {
                    if (items.isEmpty()) {
                        hideHero();
                    } else {
                        scheduleHero(nextOf(pkg, items), HERO_DELAY);
                    }
                }
            });
        });
    }

    /** Focused dock app: after a pause its newest program fills the screen (the tvOS top shelf). */
    private void showShelf(final String pkg) {
        if (pkg == null) {
            hideShelf();
            return;
        }
        if (pkg.equals(shelfPkg)) {
            return;
        }
        shelfPkg = pkg;
        schedulePeek(pkg);
        handler.removeCallbacks(heroShow);
        pendingHero = null;
        if (previewsFresh(pkg)) {
            List<Previews.Item> items = previewCache.get(pkg);
            if (items.isEmpty()) {
                hideHero();
            } else {
                scheduleHero(nextOf(pkg, items), heroItem == null ? HERO_DELAY : 600);
            }
        } else {
            hideHero();
            prefetchPreviews(pkg);
        }
    }

    /** The recommender's top pick for the app: new content first, then continue watching, never the last one shown. */
    private Previews.Item nextOf(String pkg, List<Previews.Item> items) {
        List<Previews.Item> ranked = recommender.rank(items);
        Previews.Item pick = ranked.get(0);
        recommender.shown(pick);
        return pick;
    }

    /** Re-reads what the apps publish and refreshes the banner if the focused app has something newer. */
    private static final String KEY_ASKED = "programs_asked_at";

    /**
     * The system launcher asks every app to (re)publish its rows with INITIALIZE_PROGRAMS; we
     * replaced it, so apps were never asked and some published nothing. Asked on start and then
     * every couple of hours, each app refreshes what it shows us.
     */
    private void askAppsForPrograms() {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_ASKED, 0) < 2 * 3600_000L) {
            return;
        }
        prefs.edit().putLong(KEY_ASKED, now).apply();
        worker.execute(() -> {
            Intent ask = new Intent("android.media.tv.action.INITIALIZE_PROGRAMS");
            for (ResolveInfo ri : getPackageManager().queryBroadcastReceivers(ask, 0)) {
                try {
                    sendBroadcast(new Intent(ask).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
                } catch (RuntimeException ignored) {
                    // That app refused.
                }
            }
            previews.note("asked apps for programs");
            // Give them a minute to publish, then read again.
            handler.postDelayed(this::refreshRecommendations, 60_000);
        });
    }

    private void refreshRecommendations() {
        if (!resumed) {
            return;
        }
        previews.refresh();
        previewTime.clear();
        final String focusedPkg = shelfPkg;
        worker.execute(() -> {
            List<String> order = recommender.byUsage(dockPackages());
            for (String pkg : order) {
                if (!apps.containsKey(pkg)) {
                    continue;
                }
                final List<Previews.Item> items = previews.load(pkg, 24);
                recommender.observe(items);
                handler.post(() -> {
                    previewCache.put(pkg, items);
                    previewTime.put(pkg, SystemClock.elapsedRealtime());
                    if (pkg.equals(focusedPkg) && !open && !browsing && !items.isEmpty()
                            && control.getVisibility() != View.VISIBLE) {
                        Previews.Item top = recommender.rank(items).get(0);
                        if (top != heroItem && (heroItem == null || !key(top).equals(key(heroItem)))) {
                            scheduleHero(top, 400);
                        }
                    }
                });
            }
            handler.postDelayed(refreshTick, REFRESH_EVERY);
        });
    }

    private static String key(Previews.Item item) {
        return item.intentUri != null ? item.intentUri : item.title + "|" + item.poster;
    }

    private void scheduleHero(Previews.Item item, long delay) {
        pendingHero = item;
        handler.removeCallbacks(heroShow);
        handler.postDelayed(heroShow, delay);
    }

    private void showPendingHero() {
        if (pendingHero != null && !open && control.getVisibility() != View.VISIBLE) {
            showHero(pendingHero);
        }
    }

    /** Up from a dock app with programs: the row of posters replaces the tray, full screen. */
    private boolean enterBrowse() {
        if (shelfPkg == null || !previewsFresh(shelfPkg) || previewCache.get(shelfPkg).isEmpty()) {
            return false;
        }
        browsing = true;
        hidePeek();
        handler.removeCallbacks(heroShow);
        List<Previews.Item> items = recommender.rank(previewCache.get(shelfPkg));
        int start = heroItem != null ? items.indexOf(heroItem) : -1;
        if (start > 0) {
            Collections.rotate(items, -start);
        }
        renderShelf(shelfPkg, items);
        previews.note("after render: cards=" + shelfRow.getChildCount() + " focus=" + getCurrentFocus());
        for (View v : new View[]{tray, dockHolder, gridScroll}) {
            float rest = v == gridScroll ? gridClosedOffset : 0;
            v.animate().alpha(0f).translationY(rest + px(40)).setInterpolator(EASE).setDuration(360).start();
        }
        showHint(false);
        return true;
    }

    private void exitBrowse() {
        if (!browsing) {
            return;
        }
        browsing = false;
        previews.note("exitBrowse from " + android.util.Log.getStackTraceString(new Throwable()).replace(System.lineSeparator(), " "));
        for (View v : new View[]{tray, dockHolder, gridScroll}) {
            float rest = v == gridScroll ? gridClosedOffset : 0;
            v.animate().alpha(1f).translationY(rest).setInterpolator(EASE).setDuration(360).start();
        }
        pills.animate().alpha(1f).setDuration(240).start();
        shelf.animate().cancel();
        shelf.animate().alpha(0f).translationY(px(16)).setDuration(220).withEndAction(() -> {
            shelf.setVisibility(View.INVISIBLE);
            shelfRow.removeAllViews();
        }).start();
        focusDock(lastDockIndex);
    }

    private View dockTileFor(String pkg) {
        for (int i = 0; i < dock.getChildCount(); i++) {
            if (pkg.equals(dock.getChildAt(i).getTag())) {
                return dock.getChildAt(i);
            }
        }
        return null;
    }

    private void renderShelf(String pkg, List<Previews.Item> items) {
        View dockTile = dockTileFor(pkg);
        if (items.isEmpty() || dockTile == null) {
            return;
        }
        shelfRow.removeAllViews();
        if (shelfScrollSpring != null) {
            shelfScrollSpring.snap(0);
        }
        shelfScroll.scrollTo(0, 0);
        View first = null;
        for (final Previews.Item item : items) {
            final int w = Math.min(px(340), Math.round(shelfH * item.ratio()));
            final FrameLayout card = new FrameLayout(this);
            final float radius = px(12);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2A2E35);
            bg.setCornerRadius(radius);
            card.setBackground(bg);
            card.setOutlineProvider(roundOutline(radius));
            card.setClipToOutline(true);
            applyShadow(card, 0xFF202226);
            final ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setAlpha(0f);
            card.addView(image, full());
            GradientDrawable sheen = new GradientDrawable();
            sheen.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            sheen.setColors(new int[]{0x26FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
            sheen.setGradientRadius(w * 0.85f);
            sheen.setGradientCenter(0.25f, 0.1f);
            View shine = new View(this);
            shine.setBackground(sheen);
            shine.setAlpha(0f);
            shine.setTag("shine");
            card.addView(shine, full());
            card.setId(View.generateViewId());
            card.setFocusable(true);
            card.setOnClickListener(v -> openPreview(item));
            card.setNextFocusDownId(card.getId());
            card.setNextFocusUpId(avatar.getId());

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            final TextView name = text(13, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setText(item.title);
            name.setGravity(Gravity.CENTER);
            name.setAlpha(0f);
            card.setOnFocusChangeListener((v, has) -> {
                focusEffect(v, has);
                name.animate().alpha(has ? 1f : 0f).translationY(has ? px(6) : 0).setDuration(200).start();
                if (has) {
                    previews.note("card focus " + item.title);
                    scheduleHero(item, 350);
                }
            });
            cell.addView(card, new LinearLayout.LayoutParams(w, shelfH));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(w + gap, -2);
            nlp.topMargin = px(14);
            cell.addView(name, nlp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, -2);
            clp.setMargins(gap / 2, 0, gap / 2, 0);
            shelfRow.addView(cell, clp);
            if (first == null) {
                first = card;
            }
            posterWorker.execute(() -> {
                final Bitmap bitmap = previews.poster(item.poster, w, shelfH);
                handler.post(() -> {
                    if (bitmap != null && card.isAttachedToWindow()) {
                        image.setImageBitmap(bitmap);
                        image.animate().alpha(1f).setDuration(260).start();
                    }
                });
            });
        }
        int count = shelfRow.getChildCount();
        for (int i = 0; i < count; i++) {
            View cell = shelfRow.getChildAt(i);
            View card = ((ViewGroup) cell).getChildAt(0);
            View before = i > 0 ? ((ViewGroup) shelfRow.getChildAt(i - 1)).getChildAt(0) : card;
            View after = i + 1 < count ? ((ViewGroup) shelfRow.getChildAt(i + 1)).getChildAt(0) : card;
            card.setNextFocusLeftId(before.getId());
            card.setNextFocusRightId(after.getId());
            cell.setAlpha(0f);
            cell.setTranslationY(px(28));
            cell.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setStartDelay(60L + 45L * Math.min(i, 8)).setDuration(420).start();
        }
        shelf.setVisibility(View.VISIBLE);
        shelf.animate().cancel();
        shelf.setAlpha(1f);
        shelf.setTranslationY(0);
        first.requestFocus();
    }

    private void hideShelf() {
        shelfPkg = null;
        hidePeek();
        handler.removeCallbacks(heroShow);
        pendingHero = null;
        hideHero();
        exitBrowse();
    }

    // ================================================================ hero (tvOS inset top shelf)

    private View buildHero() {
        hero = new FrameLayout(this);
        hero.setAlpha(0f);
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.FIT_XY);
        hero.addView(heroImage, full());
        View filmGrain = new View(this);
        android.graphics.drawable.BitmapDrawable grainTile =
                new android.graphics.drawable.BitmapDrawable(getResources(), grain());
        grainTile.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        filmGrain.setBackground(grainTile);
        hero.addView(filmGrain, full());
        heroVideo = new android.view.TextureView(this);
        heroVideo.setAlpha(0f);
        heroVideo.setOpaque(false);
        hero.addView(heroVideo, full());
        // Shade the lower third so the posters, tray and title read over any picture.
        // The still already carries these gradients (heroFrame); the views only cover a trailer.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x73000000, 0xD9000000, 0xF7000000}));
        shade.setVisibility(View.GONE);
        heroShade = shade;
        hero.addView(shade, at(-1, screenH - trayClosedTop + px(120), Gravity.BOTTOM, 0, 0, 0, 0));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x8C000000, 0x00000000}));
        left.setVisibility(View.GONE);
        heroLeft = left;
        hero.addView(left, at(screenW / 2, -1, Gravity.START, 0, 0, 0, 0));
        heroApp = text(11, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroApp.setAlpha(0.75f);
        heroApp.setLetterSpacing(0.12f);
        heroTitle = text(22, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroTitle.setSingleLine(false);
        heroTitle.setMaxLines(2);
        heroTitle.setLineSpacing(0, 1.05f);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(heroApp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(400), -2);
        tlp.topMargin = px(6);
        words.addView(heroTitle, tlp);
        hero.addView(words, at(-2, -2, Gravity.TOP | Gravity.START, side, px(170), 0, 0));
        // The picture speaks for itself, like the tvOS Top Shelf: no caption over it.
        words.setVisibility(View.GONE);
        return hero;
    }

    /**
     * The big full-screen banner behind the dock. Off: the home screen keeps its wallpaper, and
     * Up on an app shows only its row of program cards, for when there is something to watch.
     */
    private static final boolean HERO_BANNER = false;

    private void showHero(final Previews.Item item) {
        if (!HERO_BANNER || item == heroItem || (PERF & 16) != 0) {
            return;
        }
        heroItem = item;
        handler.removeCallbacks(heroPlay);
        stopHeroVideo();
        App app = apps.get(item.pkg);
        String source = app != null ? app.label.toString() : "";
        if (item.channel != null && !item.channel.isEmpty() && !item.channel.equalsIgnoreCase(source)) {
            source += "  ·  " + item.channel;
        } else if (item.watchNext) {
            source += "  ·  Continuă vizionarea";
        }
        heroApp.setText(source.toUpperCase(Locale.getDefault()));
        heroTitle.setText(item.title);
        hero.animate().alpha(1f).setDuration(400).start();
        posterWorker.execute(() -> {
            final Bitmap bitmap = previews.bigPoster(item.poster, screenW, screenH);
            final Bitmap frame = bitmap != null ? heroFrame(bitmap, 1f) : null;
            handler.post(() -> {
                if (bitmap != null && item == heroItem) {
                    heroImage.setImageBitmap(frame);
                    heroImage.setAlpha(0f);
                    kenBurns();
                    heroImage.animate().alpha(1f).setDuration(500).withEndAction(() -> {
                        // An opaque banner covers the wallpaper: stop drawing what nobody sees.
                        if (item == heroItem && hero.getAlpha() >= 0.99f && glassLayer == null) {
                            wallSharp.setVisibility(View.INVISIBLE);
                        }
                    }).start();
                    worker.execute(() -> {
                        // The glass sees exactly what is behind it: the composed banner.
                        Bitmap blur = frosted(frame, 480, 2, 2, 1.25f);
                        handler.post(() -> {
                            if (item == heroItem) {
                                if (glassLayer != null) {
                                    glassLayer.setHero(frame, blur);
                                }
                                backdrop.hero = blur;
                                invalidateGlass();
                            }
                        });
                    });
                    worker.execute(() -> {
                        final Bitmap lens = darkened(frosted(bitmap, 480, 2, 2, 1.2f), 0.5f);
                        handler.post(() -> {
                            if (item == heroItem) {
                                trayPane.setBackdrop(lens, 600);
                            }
                        });
                    });
                }
            });
        });
        if (item.video != null && !item.video.isEmpty()) {
            handler.postDelayed(heroPlay, TRAILER_DELAY);
        }
        // Like the Top Shelf carousel: the next program comes up on its own after a while.
        handler.removeCallbacks(heroCycle);
        handler.postDelayed(heroCycle, item.video != null && !item.video.isEmpty() ? 30000 : 9000);
    }

    private void cycleHero() {
        if (heroItem == null || open || browsing || control.getVisibility() == View.VISIBLE || shelfPkg == null) {
            return;
        }
        List<Previews.Item> items = previewCache.get(shelfPkg);
        if (items != null && items.size() > 1) {
            showHero(nextOf(shelfPkg, items));
        }
    }

    /** A muted, looping YouTube embed under the banner (YouTube publishes no clip of its own). */
    @SuppressWarnings("SetJavaScriptEnabled")
    private void startYoutube(String id) {
        if (heroWeb == null) {
            try {
                heroWeb = new android.webkit.WebView(this);
            } catch (RuntimeException e) {
                previews.note("webview: " + e);
                return;
            }
            heroWeb.setBackgroundColor(0);
            heroWeb.setFocusable(false);
            heroWeb.setAlpha(0f);
            android.webkit.WebSettings settings = heroWeb.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setDomStorageEnabled(true);
            heroWeb.setWebChromeClient(new android.webkit.WebChromeClient());
            // YouTube refuses embeds from the TV WebView's own user agent (error 152-4).
            settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            heroWeb.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void playing() {
                    handler.post(() -> {
                        if (heroWeb != null && heroItem != null) {
                            heroWeb.animate().alpha(1f).setDuration(700).start();
                        }
                    });
                }

                @android.webkit.JavascriptInterface
                public void failed(String code) {
                    previews.note("youtube embed failed: " + code);
                }
            }, "Android");
            hero.addView(heroWeb, 2, full());
        }
        // 16:9 player stretched a little past the edges hides YouTube's black bars and title.
        String html = "<html><body style='margin:0;background:#000;overflow:hidden'>"
                + "<div id='p' style='position:absolute;top:-9%;left:-9%;width:118%;height:118%'></div>"
                + "<script src='https://www.youtube.com/iframe_api'></script><script>"
                + "var player;function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + id + "',"
                + "playerVars:{autoplay:1,mute:1,controls:0,loop:1,playlist:'" + id + "',rel:0,modestbranding:1,"
                + "iv_load_policy:3,playsinline:1,disablekb:1,fs:0,origin:'https://www.youtube.com'},"
                + "events:{onReady:function(e){e.target.mute();e.target.playVideo();},"
                + "onStateChange:function(e){if(e.data==1)Android.playing();},"
                + "onError:function(e){Android.failed(''+e.data);}}});}"
                + "</script></body></html>";
        heroWeb.setAlpha(0f);
        heroWeb.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null);
    }

    private void stopYoutube() {
        if (heroWeb != null) {
            heroWeb.animate().cancel();
            heroWeb.setAlpha(0f);
            heroWeb.loadUrl("about:blank");
        }
    }

    private void hideHero() {
        if (heroItem == null) {
            return;
        }
        heroItem = null;
        handler.removeCallbacks(heroPlay);
        stopHeroVideo();
        if (glassLayer == null) {
            wallSharp.setVisibility(View.VISIBLE);
        }
        handler.removeCallbacks(heroCycle);
        hero.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (heroItem == null && kenBurns != null) {
                kenBurns.cancel();
            }
        }).start();
        if (wallpaperLens != null) {
            trayPane.setBackdrop(wallpaperLens, 600);
        }
    }

    /** Plays the program's preview clip over the still, muted, looping, like tvOS trailers. */
    private void startHeroVideo() {
        final Previews.Item item = heroItem;
        if (item == null) {
            return;
        }
        if (item.video == null || item.video.isEmpty()) {
            return;
        }
        if (!heroVideo.isAvailable()) {
            handler.postDelayed(heroPlay, 500);
            return;
        }
        try {
            heroPlayer = new android.media.MediaPlayer();
            heroPlayer.setSurface(new android.view.Surface(heroVideo.getSurfaceTexture()));
            heroPlayer.setDataSource(this, android.net.Uri.parse(item.video));
            heroPlayer.setLooping(true);
            heroPlayer.setVolume(0f, 0f);
            heroPlayer.setOnPreparedListener(mp -> {
                if (item == heroItem) {
                    mp.start();
                    heroShade.setVisibility(View.VISIBLE);
                    heroLeft.setVisibility(View.VISIBLE);
                    heroVideo.animate().alpha(1f).setDuration(600).start();
                }
            });
            heroPlayer.setOnErrorListener((mp, what, extra) -> {
                stopHeroVideo();
                return true;
            });
            heroPlayer.prepareAsync();
        } catch (Exception e) {
            previews.note("video " + item.video + ": " + e);
            stopHeroVideo();
        }
    }

    private void stopHeroVideo() {
        if (heroShade != null) {
            heroShade.setVisibility(View.GONE);
            heroLeft.setVisibility(View.GONE);
        }
        if (heroPlayer != null) {
            try {
                heroPlayer.stop();
            } catch (IllegalStateException ignored) {
                // Not started.
            }
            heroPlayer.release();
            heroPlayer = null;
        }
        heroVideo.animate().alpha(0f).setDuration(200).start();
    }

    private void openPreview(Previews.Item item) {
        try {
            Intent intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            if (launch(intent, item.title)) {
                return;
            }
        } catch (RuntimeException | java.net.URISyntaxException ignored) {
            // Fall back to the app itself.
        }
        if (shelfPkg != null) {
            openPackage(shelfPkg);
        }
    }

    // ================================================================ shadows, avatar, hint

    /** A focus shadow in a darkened version of the tile's colour: tinted, but still a shadow. */
    private void applyShadow(View view, int tint) {
        if (Build.VERSION.SDK_INT >= 28) {
            int shade = Color.argb(0xB8, Color.red(tint) / 3, Color.green(tint) / 3, Color.blue(tint) / 3);
            view.setOutlineSpotShadowColor(shade);
            view.setOutlineAmbientShadowColor(0x5A000000);
        }
    }

    private Bitmap placeholderAvatar() {
        int s = px(30);
        Bitmap bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xD9FFFFFF);
        canvas.drawCircle(s / 2f, s * 0.37f, s * 0.17f, paint);
        canvas.drawOval(new RectF(s * 0.2f, s * 0.6f, s * 0.8f, s * 1.2f), paint);
        return bitmap;
    }

    /** profile.jpg/.png pushed by tvtool into the app's files folder. */
    private void loadAvatar() {
        for (final Profiles.Profile p : Profiles.all(prefs, accountName())) {
            if (p.id.equals(Profiles.current(prefs)) && !"photo".equals(Profiles.avatarOf(prefs, p.id))) {
                avatar.setImageBitmap(Profiles.picture(this, prefs, p, px(30) * 2));
                return;
            }
        }
        worker.execute(() -> {
            Bitmap found = null;
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                for (String name : new String[]{"profile.jpg", "profile.png", "profile.webp"}) {
                    File file = new File(dir, name);
                    if (file.exists()) {
                        found = decode(file.getAbsolutePath(), px(30) * 2, px(30) * 2);
                        if (found != null) {
                            break;
                        }
                    }
                }
            }
            final Bitmap bitmap = found;
            handler.post(() -> {
                if (bitmap != null && !isDestroyed()) {
                    avatar.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Drawable chevron() {
        int w = px(18);
        int h = px(10);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(px(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xE6FFFFFF);
        Path path = new Path();
        path.moveTo(px(2), h - px(2));
        path.lineTo(w / 2f, px(2));
        path.lineTo(w - px(2), h - px(2));
        new Canvas(bitmap).drawPath(path, paint);
        return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
    }

    private void showHint(boolean on) {
        hint.animate().alpha(on && !open && !browsing ? 0.85f : 0f).setDuration(on ? 400 : 160).start();
    }

    // ================================================================ full screen

    /** Everything fades away and the wallpaper fills the screen (tvOS "swipe up for full screen"). */
    private void setFullscreen(boolean value) {
        if (fullscreen == value) {
            return;
        }
        fullscreen = value;
        showHint(false);
        for (View v : new View[]{pills, tray, dockHolder, shelf, gridScroll}) {
            if (v == shelf && shelfRow.getChildCount() == 0) {
                continue;
            }
            float rest = v == gridScroll ? gridClosedOffset : 0;
            v.animate().alpha(value ? 0f : 1f).translationY(value ? rest + px(30) : rest)
                    .setInterpolator(EASE).setDuration(value ? 520 : 420).start();
        }
        placeTitle.animate().alpha(value ? 0f : 1f).setDuration(400).start();
    }

    // ================================================================ folders

    /** A grid tile showing up to four of the folder's apps on a glass pane, like tvOS folders. */
    private FrameLayout folderTile(final String name) {
        FrameLayout tile = new FrameLayout(this);
        final float radius = px(14);
        tile.setBackground(new GlassDrawable(radius, dp, 0x5A2A2E35));
        tile.setOutlineProvider(roundOutline(radius));
        tile.setClipToOutline(true);
        tile.setElevation(px(2));
        tile.setTag("folder:" + name);
        applyShadow(tile, 0xFF2A2E35);
        tile.setCameraDistance(12000 * dp);
        List<String> members = folders.get(name);
        int miniW = (tileW - px(26)) / 2;
        int miniH = miniW * 3 / 5;
        int i = 0;
        for (String pkg : members) {
            App app = apps.get(pkg);
            if (app == null || i >= 4) {
                continue;
            }
            ImageView mini = new ImageView(this);
            mini.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mini.setImageDrawable(app.banner != null ? app.banner : app.icon);
            mini.setOutlineProvider(roundOutline(px(5)));
            mini.setClipToOutline(true);
            int left = px(9) + (i % 2) * (miniW + px(8));
            int top = (tileH - 2 * miniH - px(6)) / 2 + (i / 2) * (miniH + px(6));
            tile.addView(mini, at(miniW, miniH, Gravity.TOP | Gravity.START, left, top, 0, 0));
            i++;
        }
        View shine = new View(this);
        GradientDrawable sheen = new GradientDrawable();
        sheen.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        sheen.setColors(new int[]{0x26FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheen.setGradientRadius(tileW * 0.85f);
        sheen.setGradientCenter(0.25f, 0.1f);
        shine.setBackground(sheen);
        shine.setAlpha(0f);
        shine.setTag("shine");
        tile.addView(shine, full());
        tile.setFocusable(true);
        tile.setOnClickListener(v -> {
            play("select");
            showFolder(name, v);
        });
        return tile;
    }

    /** The folder's apps on a glass pane in the middle of the frosted screen. */
    private void showFolder(final String name, View from) {
        List<String> members = folders.get(name);
        if (members == null) {
            return;
        }
        closeFolder();
        openFolder = name;
        folderReturn = from;
        play("open");
        folderPane = new FrameLayout(this);
        folderPane.setClipChildren(false);
        View dim = new View(this);
        dim.setBackgroundColor(0x66000000);
        folderPane.addView(dim, full());
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(px(26), px(18), px(26), px(26));
        panel.setBackground(new GlassDrawable(px(22), dp, 0x5C14161A));
        glass(panel, px(22), 1.3f);
        TextView title = text(18, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText(name);
        title.setShadowLayer(0, 0, 0, 0);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = px(16);
        panel.addView(title, tlp);
        LinearLayout row = null;
        int column = 0;
        int perRow = 4;
        View first = null;
        for (final String pkg : members) {
            final App app = apps.get(pkg);
            if (app == null) {
                continue;
            }
            if (row == null || column == perRow) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.topMargin = px(14);
                panel.addView(row, rlp);
                column = 0;
            }
            FrameLayout tile = tile(app);
            tile.setId(View.generateViewId());
            tile.setOnFocusChangeListener((v, has) -> focusEffect(v, has));
            tile.setOnLongClickListener(v -> {
                organiseMenu(app, v);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.setMargins(gap / 2, 0, gap / 2, 0);
            row.addView(tile, lp);
            if (first == null) {
                first = tile;
            }
            column++;
        }
        folderPane.addView(panel, at(-2, -2, Gravity.CENTER, 0, 0, 0, 0));
        folderPane.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        ((ViewGroup) home.getParent()).addView(folderPane, full());
        folderPane.animate().alpha(1f).setDuration(260).start();
        panel.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(1.2f)).setDuration(360).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (first != null) {
            first.requestFocus();
        }
    }

    private void closeFolder() {
        if (openFolder == null) {
            return;
        }
        openFolder = null;
        final FrameLayout pane = folderPane;
        folderPane = null;
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pane.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) pane.getParent();
            if (parent != null) {
                parent.removeView(pane);
            }
        }).start();
        if (folderReturn != null && folderReturn.isAttachedToWindow()) {
            folderReturn.requestFocus();
        } else if (grid.getChildCount() > 0) {
            gridTile(0, 0).requestFocus();
        }
        folderReturn = null;
    }

    /** Hold OK on a grid app: top row, folders. */
    private void organiseMenu(final App app, final View from) {
        final String current = Folders.of(folders, app.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add("Pune în rândul de sus");
        actions.add(() -> {
            Folders.move(folders, app.pkg, null);
            Folders.save(prefs, folders);
            closeFolder();
            toggleDock(app);
        });
        labels.add("Folder nou…");
        actions.add(() -> askFolderName(app));
        for (final String name : folders.keySet()) {
            if (!name.equals(current)) {
                labels.add("Mută în „" + name + "”");
                actions.add(() -> {
                    Folders.move(folders, app.pkg, name);
                    Folders.save(prefs, folders);
                    closeFolder();
                    rebuildTiles();
                });
            }
        }
        labels.add("Ascunde în profilul acesta");
        actions.add(() -> {
            Folders.move(folders, app.pkg, null);
            Folders.save(prefs, folders);
            closeFolder();
            setHidden(app.pkg, true);
        });
        final java.util.Set<String> hiddenNow = hiddenApps();
        if (!hiddenNow.isEmpty()) {
            labels.add("Aplicații ascunse (" + hiddenNow.size() + ")…");
            actions.add(this::hiddenAppsMenu);
        }
        if (current != null) {
            labels.add("Scoate din „" + current + "”");
            actions.add(() -> {
                Folders.move(folders, app.pkg, null);
                Folders.save(prefs, folders);
                closeFolder();
                rebuildTiles();
            });
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    /** The apps this profile hid: choosing one shows it again. */
    private void hiddenAppsMenu() {
        final List<String> pkgs = new ArrayList<>(hiddenApps());
        final List<String> names = new ArrayList<>();
        for (String pkg : pkgs) {
            App a = apps.get(pkg);
            names.add(a != null ? a.label.toString() : pkg);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Arată din nou")
                .setItems(names.toArray(new String[0]), (dialog, which) -> setHidden(pkgs.get(which), false))
                .show();
    }

    private void askFolderName(final App app) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Numele folderului");
        input.setSingleLine(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Folder nou")
                .setView(input)
                .setPositiveButton("Creează", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        Folders.move(folders, app.pkg, name);
                        Folders.save(prefs, folders);
                        closeFolder();
                        rebuildTiles();
                    }
                })
                .setNegativeButton("Renunță", null)
                .show();
    }

    private void folderMenu(final String name) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name)
                .setItems(new String[]{"Desfă folderul"}, (dialog, which) -> {
                    folders.remove(name);
                    Folders.save(prefs, folders);
                    rebuildTiles();
                })
                .show();
    }

    // ================================================================ cameras

    private void showCameras() {
        List<Cameras.Camera> list = Cameras.load(getExternalFilesDir(null));
        if (list.isEmpty()) {
            SourcesService.say(this, "Nicio cameră", "python tvtool.py cameras add \"Nume\" rtsp://…");
            return;
        }
        if (camerasPane != null) {
            camerasPane.close();
        }
        camerasPane = new Cameras(this, list, dp, () -> {
            final View pane = camerasPane;
            camerasPane = null;
            if (pane != null) {
                pane.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    ViewGroup parent = (ViewGroup) pane.getParent();
                    if (parent != null) {
                        parent.removeView(pane);
                    }
                }).start();
            }
            home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            pills.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            focusDock(lastDockIndex);
        });
        camerasPane.setAlpha(0f);
        ((ViewGroup) home.getParent()).addView(camerasPane, full());
        camerasPane.animate().alpha(1f).setDuration(300).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pills.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    // ================================================================ dock editing

    /** Hold OK on a tile: it lifts and wobbles; left/right move it, OK or Back settle it. */
    private void startEdit(final View tile) {
        if (SystemClock.elapsedRealtime() - startedAt < 2500 || !centerDown) {
            previews.note("ignored stray long press");
            return;
        }
        if (editing != null) {
            endEdit();
        }
        if (tile.getParent() != dock) {
            // Grid tiles: add to the top row, as before.
            App app = apps.get((String) tile.getTag());
            if (app != null) {
                toggleDock(app);
            }
            return;
        }
        editing = tile;
        motion(tile).stopAll();
        addRemoveBadge(tile);
        previews.note("startEdit from " + android.util.Log.getStackTraceString(new Throwable()).replace('\n', ' '));
        stopSway();
        tile.animate().cancel();
        tile.animate().scaleX(1.18f).scaleY(1.18f).translationZ(px(28)).rotationX(0).rotationY(0)
                .setInterpolator(new OvershootInterpolator(2f)).setDuration(240).start();
        jiggle = ValueAnimator.ofFloat(-1.8f, 1.8f);
        jiggle.setDuration(130);
        jiggle.setRepeatMode(ValueAnimator.REVERSE);
        jiggle.setRepeatCount(ValueAnimator.INFINITE);
        jiggle.addUpdateListener(a -> tile.setRotation((float) a.getAnimatedValue()));
        jiggle.start();
    }

    /** The small x in the corner of a card being moved, like the delete badge on tvOS. */
    private void addRemoveBadge(View tile) {
        if (!(tile instanceof FrameLayout) || tile.findViewWithTag("badge") != null) {
            return;
        }
        TextView x = text(12, Typeface.create("sans-serif-medium", Typeface.BOLD));
        x.setText("✕");
        x.setGravity(Gravity.CENTER);
        x.setTextColor(0xFF111111);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xF2FFFFFF);
        x.setBackground(dot);
        x.setTag("badge");
        x.setElevation(px(4));
        x.setScaleX(0f);
        x.setScaleY(0f);
        ((FrameLayout) tile).addView(x, at(px(20), px(20), Gravity.TOP | Gravity.END, 0, px(5), px(5), 0));
        x.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
    }

    private void removeBadge(View tile) {
        View badge = tile.findViewWithTag("badge");
        if (badge != null) {
            ((ViewGroup) tile).removeView(badge);
        }
    }

    private void endEdit() {
        if (editing == null) {
            return;
        }
        final View tile = editing;
        removeBadge(tile);
        editing = null;
        if (jiggle != null) {
            jiggle.cancel();
            jiggle = null;
        }
        tile.animate().cancel();
        tile.animate().rotation(0).scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(px(22))
                .setInterpolator(EASE).setDuration(220).start();
        if (tile.hasFocus()) {
            startSway(tile);
        }
        wireFocus();
    }

    private boolean editKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveEditing(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveEditing(1);
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_DPAD_UP:
                // Up goes to the x in the corner: the app leaves the row and stays in Apps.
                removeEditing();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                endEdit();
                return true;
            default:
                return true;
        }
    }

    private void moveEditing(int direction) {
        final View tile = editing;
        int index = dock.indexOfChild(tile);
        int target = index + direction;
        if (target < 0 || target >= dock.getChildCount()) {
            bump(tile, direction);
            return;
        }
        final View other = dock.getChildAt(target);
        List<String> list = dockPackages();
        int a = list.indexOf((String) tile.getTag());
        int b = list.indexOf((String) other.getTag());
        if (a < 0 || b < 0) {
            return;
        }
        Collections.swap(list, a, b);
        prefs.edit().putString(Profiles.scoped(prefs, KEY_DOCK), TextUtils.join(",", list)).apply();
        // Swap the views, then slide both from their old places to the new ones.
        float dx = other.getLeft() - tile.getLeft();
        dock.removeView(other);
        dock.addView(other, index);
        tile.setTranslationX(-dx);
        tile.animate().translationX(0).setInterpolator(EASE).setDuration(240).start();
        other.setTranslationX(dx);
        other.animate().translationX(0).setInterpolator(EASE).setDuration(240).start();
        lastDockIndex = target;
    }

    private void removeEditing() {
        final View tile = editing;
        removeBadge(tile);
        App app = apps.get((String) tile.getTag());
        if (jiggle != null) {
            jiggle.cancel();
            jiggle = null;
        }
        editing = null;
        if (app != null) {
            toggleDock(app);
        }
    }

    // ================================================================ volume

    /** A vertical glass capsule on the right that fills with the level, like the tvOS volume HUD. */
    private View buildVolumeHud() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        int w = px(30);
        volumeH = px(230);
        FrameLayout capsule = new FrameLayout(this);
        capsule.setOutlineProvider(roundOutline(w / 2f));
        capsule.setClipToOutline(true);
        capsule.setElevation(px(8));
        glass(capsule, w / 2f, 0.8f);
        View surface = new View(this);
        surface.setBackground(new GlassDrawable(w / 2f, dp, 0x8C1A1C20));
        capsule.addView(surface, full());
        volumeFill = new View(this);
        volumeFill.setBackgroundColor(0xF2FFFFFF);
        capsule.addView(volumeFill, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        column.addView(capsule, new LinearLayout.LayoutParams(w, volumeH));
        ImageView speaker = new ImageView(this);
        speaker.setImageBitmap(speakerGlyph(px(22)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(22), px(22));
        slp.topMargin = px(10);
        column.addView(speaker, slp);
        column.setAlpha(0f);
        column.setTranslationX(px(24));
        volumeHud = column;
        return column;
    }

    private Bitmap speakerGlyph(int s) {
        Bitmap bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(px(3), 0, px(1), 0x66000000);
        Path body = new Path();
        body.moveTo(s * 0.12f, s * 0.38f);
        body.lineTo(s * 0.32f, s * 0.38f);
        body.lineTo(s * 0.55f, s * 0.18f);
        body.lineTo(s * 0.55f, s * 0.82f);
        body.lineTo(s * 0.32f, s * 0.62f);
        body.lineTo(s * 0.12f, s * 0.62f);
        body.close();
        canvas.drawPath(body, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.09f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(new RectF(s * 0.5f, s * 0.3f, s * 0.86f, s * 0.7f), -45, 90, false, paint);
        canvas.drawArc(new RectF(s * 0.46f, s * 0.14f, s * 1.08f, s * 0.86f), -45, 90, false, paint);
        return bitmap;
    }

    private final Runnable hideVolume = () ->
            volumeHud.animate().alpha(0f).translationX(px(24)).setDuration(260).start();

    private void onVolumeKey(int code) {
        if (audio == null) {
            return;
        }
        int direction = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                : code == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER
                : AudioManager.ADJUST_TOGGLE_MUTE;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (RuntimeException ignored) {
            // Some TVs route volume elsewhere; still show the level we can read.
        }
        showVolume();
    }

    private void showVolume() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        float level = max > 0 && !muted ? current / (float) max : 0f;
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) volumeFill.getLayoutParams();
        ValueAnimator grow = ValueAnimator.ofInt(lp.height, Math.round(level * volumeH));
        grow.setDuration(160);
        grow.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            volumeFill.setLayoutParams(lp);
        });
        grow.start();
        volumeHud.animate().alpha(1f).translationX(0).setInterpolator(EASE).setDuration(180).start();
        handler.removeCallbacks(hideVolume);
        handler.postDelayed(hideVolume, 1600);
    }

    // ================================================================ clock

    private final Runnable clockTick = this::updateClock;

    private String clockText() {
        String pattern = android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
    }

    private void updateClock() {
        clockPill.setText(clockText());
        handler.removeCallbacks(clockTick);
        long untilNextMinute = 60_000 - (System.currentTimeMillis() % 60_000) + 50;
        handler.postDelayed(clockTick, untilNextMinute);
    }

    // ================================================================ entrance

    /**
     * tvOS wake-up: the wallpaper settles from a slight zoom while the tray rises and the
     * app tiles pop in one after another with a small overshoot; the pills fade in last.
     */
    private void playEntrance() {
        for (ImageView[] pair : wallLayers) {
            for (ImageView view : pair) {
                if ("sharp".equals(view.getTag())) {
                    view.setScaleX(1.08f);
                    view.setScaleY(1.08f);
                    view.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(1.6f))
                            .setDuration(1800).start();
                }
            }
        }
        for (View v : new View[]{tray, gridScroll}) {
            float rest = v == gridScroll ? gridClosedOffset : 0;
            v.setAlpha(0f);
            v.setTranslationY(rest + px(48));
            v.animate().alpha(1f).translationY(rest).setInterpolator(new DecelerateInterpolator(2.2f))
                    .setStartDelay(140).setDuration(640).start();
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            View tile = dock.getChildAt(i);
            tile.setAlpha(0f);
            tile.setTranslationY(px(36));
            tile.animate().alpha(1f).translationY(0)
                    .setInterpolator(new OvershootInterpolator(1.4f))
                    .setStartDelay(260 + 55L * i).setDuration(520).start();
        }
        pills.setAlpha(0f);
        pills.setTranslationY(-px(12));
        pills.animate().alpha(1f).translationY(0).setStartDelay(520).setDuration(520).start();
    }

    /**
     * Coming back from an app: the tile it was opened from lands back in place a little too big
     * and settles, the tray rises a few points and the wallpaper eases out of a slight zoom.
     */
    private void playReturn() {
        final View tile = returnTile;
        returnTile = null;
        if (tile == null || !tile.isAttachedToWindow()) {
            return;
        }
        Motion m = motion(tile);
        m.focus.snap(1.9f);
        m.focus.to(tile.hasFocus() ? 1f : 0f, 0.5f, 0.82f);
        for (View v : new View[]{tray, dockHolder}) {
            v.setTranslationY(px(30) + (open ? trayOpenTop - trayClosedTop : 0));
            v.animate().translationY(open ? trayOpenTop - trayClosedTop : 0)
                    .setInterpolator(new DecelerateInterpolator(2f)).setDuration(500).start();
        }
        for (ImageView[] pair : wallLayers) {
            for (ImageView view : pair) {
                if ("sharp".equals(view.getTag())) {
                    view.setScaleX(1.05f);
                    view.setScaleY(1.05f);
                    view.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(1.8f))
                            .setDuration(700).start();
                }
            }
        }
    }

    /** The Settings tile: Android's settings gear (Material Symbols) in white on the tvOS grey. */
    private Bitmap gearBanner() {
        int w = 320;
        int h = 192;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFFA7ABB2, 0xFF6E727A,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        Drawable gear = getDrawable(R.drawable.sym_settings_app);
        if (gear != null) {
            int size = Math.round(h * 0.56f);
            int left = (w - size) / 2;
            int top = (h - size) / 2;
            gear.setBounds(left, top, left + size, top + size);
            gear.setTint(Color.WHITE);
            gear.draw(canvas);
        }
        return bitmap;
    }

    // ================================================================ wallpapers

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    /** Built-in wallpapers from assets plus any pushed to the app's external files dir. */
    private void loadWallpaperList() {
        List<String> found = new ArrayList<>();
        File dir = getExternalFilesDir("wallpapers");
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) {
            for (File f : files) {
                if (isImage(f.getName())) {
                    found.add(f.getAbsolutePath());
                }
            }
        }
        try {
            String[] assets = found.isEmpty() || prefs.getBoolean(KEY_BUILTIN, true)
                    ? getAssets().list("wallpapers") : null;
            if (assets != null) {
                for (String name : assets) {
                    if (isImage(name)) {
                        found.add("asset:wallpapers/" + name);
                    }
                }
            }
        } catch (java.io.IOException ignored) {
            // No built-in wallpapers.
        }
        loadCaptions(dir);
        Collections.sort(found);
        List<String> current = new ArrayList<>(wallpapers);
        Collections.sort(current);
        if (!found.equals(current)) {
            wallpapers.clear();
            wallpapers.addAll(found);
            Collections.shuffle(wallpapers);
            wallpaperIndex = -1;
        }
        if (wallpapers.isEmpty()) {
            for (ImageView[] pair : wallLayers) {
                pair[front].setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFF0B1E3A, 0xFF12485A, 0xFF1F6F5C}));
            }
        }
    }

    /** Reads "file.jpg=Place name" lines from captions.txt (built-in and pushed). */
    private void loadCaptions(File dir) {
        captions.clear();
        readCaptions("asset:wallpapers/", () -> getAssets().open("wallpapers/captions.txt"));
        if (dir != null) {
            final File file = new File(dir, "captions.txt");
            if (file.exists()) {
                readCaptions(dir.getAbsolutePath() + "/", () -> new java.io.FileInputStream(file));
            }
        }
    }

    private interface Opener {
        InputStream open() throws java.io.IOException;
    }

    private void readCaptions(String prefix, Opener opener) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(opener.open(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    captions.put(prefix + line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (java.io.IOException ignored) {
            // No captions.
        }
    }

    private final Runnable wallpaperTick = this::showNextWallpaper;

    private void showNextWallpaper() {
        handler.removeCallbacks(wallpaperTick);
        if (wallpapers.isEmpty()) {
            return;
        }
        wallpaperIndex = (wallpaperIndex + 1) % wallpapers.size();
        final String source = wallpapers.get(wallpaperIndex);
        worker.execute(() -> {
            final Bitmap bitmap = vivid(decode(source, screenW, screenH));
            final Bitmap soft = bitmap != null ? frosted(bitmap, 192, 3, 3, 1.3f) : null;
            // The glass panes see a lightly blurred, more saturated copy: enough blur to read
            // as glass, enough detail for the lens at the rim to have something to bend.
            final Bitmap lens = bitmap != null ? frosted(bitmap, 480, 2, 2, 1.3f) : null;
            handler.post(() -> {
                if (bitmap != null && !isDestroyed()) {
                    crossfadeTo(bitmap, soft, lens);
                    showPlace(captions.get(source));
                    wallpaperShownAt = SystemClock.elapsedRealtime();
                }
                if (resumed) {
                    handler.postDelayed(wallpaperTick, prefs.getInt(KEY_INTERVAL, 300) * 1000L);
                }
            });
        });
    }

    /**
     * A small, softly blurred copy of the wallpaper for the glass panes: downscaled, then a
     * three-pass box blur (close to Gaussian). Scaled back up bilinearly it reads as frosted glass.
     */
    private static Bitmap frosted(Bitmap sharp, int w, int passes, int radius, float saturation) {
        int h = Math.max(1, sharp.getHeight() * w / Math.max(1, sharp.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(sharp, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int pass = 0; pass < passes; pass++) {
            boxBlur(px, tmp, w, h, radius);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        if (saturation != 1f) {
            // Bake the saturation in once, so drawing the pane later is a plain texture.
            Bitmap tinted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(saturation);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            new Canvas(tinted).drawBitmap(out, 0, 0, paint);
            out.recycle();
            return tinted;
        }
        return out;
    }

    /** tvOS wallpapers are vivid: a touch more saturation and contrast than the photo, baked in. */
    private static Bitmap vivid(Bitmap source) {
        if (source == null) {
            return null;
        }
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(1.22f);
        float c = 1.06f;
        float o = -8f;
        matrix.postConcat(new ColorMatrix(new float[]{
                c, 0, 0, 0, o,
                0, c, 0, 0, o,
                0, 0, c, 0, o,
                0, 0, 0, 1, 0}));
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(source, 0, 0, paint);
        // The scrim that keeps the clock and the dock legible, drawn once instead of every frame.
        Paint scrim = new Paint();
        scrim.setShader(new android.graphics.LinearGradient(0, 0, 0, out.getHeight(),
                new int[]{0x59000000, 0x00000000, 0x00000000, 0x40000000},
                new float[]{0f, 0.33f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, out.getWidth(), out.getHeight(), scrim);
        source.recycle();
        return out;
    }

    /**
     * The banner composed for a cinematic look, like the Apple TV app's full-bleed heroes: the
     * picture covers the whole screen (anchored high, since the dock covers the bottom), lightly
     * graded (a touch more contrast, deeper blacks), with a vignette, a soft dark side for depth
     * and a fade under the dock. Grain and a slow camera move are added by the views on top.
     */
    private Bitmap heroFrame(Bitmap poster, float k) {
        final int W = screenW;
        final int H = screenH;
        Bitmap out = Bitmap.createBitmap(Math.round(W * k), Math.round(H * k), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(k, k);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        ColorMatrix grade = new ColorMatrix();
        grade.setSaturation(1.08f);
        float c = 1.07f;
        float t = (1f - c) * 128f - 5f;
        grade.postConcat(new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t + 3f,
                0, 0, 0, 1, 0}));
        paint.setColorFilter(new ColorMatrixColorFilter(grade));

        // 1. Full width. Services bake the title into the bottom of their stills, so the picture
        // ends just under the top of the dock (its top edge goes off screen instead); a picture
        // too short for that is scaled up until it reaches the top.
        float bottomAt = trayClosedTop + px(24);
        float scale = Math.max(W / (float) poster.getWidth(), H / (float) poster.getHeight());
        float w = poster.getWidth() * scale;
        float h = poster.getHeight() * scale;
        float left = (W - w) / 2f;
        // Halfway: the title clears the dock, faces near the top keep their heads.
        float top = Math.min(0f, (bottomAt - h) * 0.5f);
        canvas.drawBitmap(poster, null, new RectF(left, top, left + w, top + h), paint);

        // 2. Vignette around the upper-right centre of interest.
        Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
        vignette.setShader(new android.graphics.RadialGradient(W * 0.6f, H * 0.36f, W * 0.8f,
                new int[]{0x00000000, 0x00000000, 0x99000000}, new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, vignette);

        // 3. A soft dark left side, for depth.
        Paint side = new Paint();
        side.setShader(new android.graphics.LinearGradient(0, 0, W * 0.5f, 0,
                new int[]{0xA6000000, 0x40000000, 0x00000000}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W * 0.5f, H, side);

        // 4. Fade into black under the dock so its cards read clearly.
        float shadeTop = trayClosedTop - px(30);
        Paint shade = new Paint();
        shade.setShader(new android.graphics.LinearGradient(0, shadeTop, 0, H,
                new int[]{0x00000000, 0x8C000000, 0xE6000000}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, shadeTop, W, H, shade);
        return out;
    }

    /** Fine monochrome film grain, tiled over the banner. */
    private static Bitmap grain() {
        int n = 160;
        int[] px = new int[n * n];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < px.length; i++) {
            int a = random.nextInt(20);
            px[i] = (a << 24) | (random.nextBoolean() ? 0xFFFFFF : 0x000000);
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    /** A slow camera move over the still, restarted for every new picture. */
    private void kenBurns() {
        if (kenBurns != null) {
            kenBurns.cancel();
        }
        heroImage.setPivotX(screenW * 0.62f);
        heroImage.setPivotY(screenH * 0.38f);
        heroImage.setScaleX(1f);
        heroImage.setScaleY(1f);
        heroImage.setTranslationX(0f);
        kenBurns = android.animation.ObjectAnimator.ofPropertyValuesHolder(heroImage,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -px(14)));
        kenBurns.setDuration(16000);
        kenBurns.setInterpolator(new android.view.animation.DecelerateInterpolator(0.6f));
        kenBurns.start();
    }

    /** The same bitmap with its brightness scaled down (0..1). */
    private static Bitmap darkened(Bitmap source, float factor) {
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                factor, 0, 0, 0, 0,
                0, factor, 0, 0, 0,
                0, 0, factor, 0, 0,
                0, 0, 0, 1, 0})));
        new Canvas(out).drawBitmap(source, 0, 0, paint);
        source.recycle();
        return out;
    }

    private static void boxBlur(int[] src, int[] tmp, int w, int h, int r) {
        // Horizontal pass into tmp, vertical pass back into src.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = Math.min(w - 1, Math.max(0, x + k));
                    int c = src[y * w + xx];
                    rs += (c >> 16) & 0xFF;
                    gs += (c >> 8) & 0xFF;
                    bs += c & 0xFF;
                    n++;
                }
                tmp[y * w + x] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = Math.min(h - 1, Math.max(0, y + k));
                    int c = tmp[yy * w + x];
                    rs += (c >> 16) & 0xFF;
                    gs += (c >> 8) & 0xFF;
                    bs += c & 0xFF;
                    n++;
                }
                src[y * w + x] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
    }

    private Bitmap decode(String source, int targetW, int targetH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            readBitmap(source, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return readBitmap(source, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private Bitmap readBitmap(String source, BitmapFactory.Options opts) throws java.io.IOException {
        if (source.startsWith("asset:")) {
            try (InputStream in = getAssets().open(source.substring("asset:".length()))) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        }
        return BitmapFactory.decodeFile(source, opts);
    }

    private void showPlace(String caption) {
        final String text = caption == null ? "" : caption.toUpperCase(Locale.getDefault());
        if (text.contentEquals(placeTitle.getText())) {
            return;
        }
        placeTitle.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            placeTitle.setText(text);
            placeTitle.animate().alpha(open ? 0f : 1f).setDuration(1200).start();
        }).start();
    }

    /** Lets the service keep its copy of the screen (under the volume capsule) up to date. */
    private void screenChanged() {
        SourcesService service = SourcesService.instance;
        if (service != null) {
            service.screenChanging();
        }
    }

    private void crossfadeTo(Bitmap sharp, Bitmap soft, Bitmap lens) {
        screenChanged();
        handler.postDelayed(this::screenChanged, 2000);
        backdrop.screenW = screenW;
        backdrop.screenH = screenH;
        backdrop.wallBack = backdrop.wallFront;
        backdrop.wallFront = lens;
        backdrop.frost = soft;
        HelloIntro.wallpaper = soft != null ? soft : lens; // hello's background when the TV comes on
        backdrop.wallMix = backdrop.wallBack == null ? 1f : 0f;
        if (backdrop.wallBack != null) {
            ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f).setDuration(1800);
            fade.addUpdateListener(a -> {
                backdrop.wallMix = (float) a.getAnimatedValue();
                invalidateGlass();
            });
            fade.start();
        }
        invalidateGlass();
        if (glassLayer != null) {
            glassLayer.setWallpaper(sharp, lens, 1800);
            glassLayer.setFrostSource(soft);
        }
        // The window keeps its black background: if the wallpaper is ever missing, the screen
        // shows black instead of the trails of old frames.
        wallpaperLens = lens;
        wallpaperSoft = soft;
        if (open) {
            trayPane.setBackdrop(soft, 1800);
        } else if (heroItem == null) {
            trayPane.setBackdrop(lens, 1800);
        }
        controlPane.setBackdrop(lens, 1800);
        int incoming = 1 - front;
        final int gen = ++wallGen;
        for (ImageView[] pair : wallLayers) {
            final ImageView in = pair[incoming];
            final ImageView out = pair[front];
            // A change still under way must not finish now: its end would clear the picture
            // this change is about to show (two changes close together left both layers empty).
            in.animate().cancel();
            out.animate().cancel();
            out.setAlpha(1f);
            in.setImageBitmap("glass".equals(in.getTag()) ? soft : sharp);
            in.setAlpha(0f);
            in.bringToFront();
            in.animate().alpha(1f).setDuration(1800).withEndAction(() -> {
                if (gen == wallGen) {
                    out.setImageDrawable(null);
                }
            }).start();
        }
        front = incoming;
    }
}
