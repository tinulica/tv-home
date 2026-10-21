package ro.tinulica.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cinema: tonight's film, chosen together, in a full-screen place of its own (tvOS style).
 *
 * - The logo: the Cinema ring draws itself, "cinema" is written letter by letter with a note
 *   for each, then fills with a flowing gradient as the soundtrack blooms; the lights go down.
 * - A few questions as big Liquid Glass cards with 3D pictures (who is watching, film or series,
 *   the mood, how long); the background turns to a still from something popular of the focused
 *   kind, and the answers collect at the top.
 * - The results as in the Apple TV app: the focused title fills the screen (logo, genre, length,
 *   rating, what it is about, where it is included in Romania) with "Watch on Netflix", the
 *   other subscriptions that have it, trailer, details and next; a row of cards shaped like the
 *   app tiles; after a few seconds the still turns into the trailer. A glass tab bar on top: for
 *   you, popular now, choose again, subscriptions; top right "Cinema mode" with the profile,
 *   which also leaves Cinema. Home asks before leaving.
 *
 * Data: TMDB (watch providers for Romania come from JustWatch); app ids from Wikidata.
 */
public final class CinemaActivity extends Activity {

    static final String EXTRA_NAME = "name";
    private static final String PREFS = "home";
    private static final String KEY_SUBS = "cinema_subs";
    private static final String KEY_SEEN = "cinema_seen";
    private static final long HOUR = 3600_000L;
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.25f, 1f);
    /** Bluetooth speakers play late; the picture waits this long for the sound. */
    private static final long AUDIO_LEAD = 200;

    /** While Cinema is in front, Home asks before leaving (the accessibility service calls this). */
    static volatile Runnable onHome;
    /** Cinema is open (maybe behind the film's app): the home screen offers a way back. */
    static volatile boolean alive;
    /** Cinema was just left on purpose: the home screen says so. */
    static volatile boolean left;
    /** Back to Cinema as it was, not from the start. */
    static final String EXTRA_RESUME = "resume";
    /** Leave Cinema mode from outside (the home screen's capsule, the app switcher). */
    static final String EXTRA_EXIT = "exit";

    /** Opens Cinema where it was, or (`exit`) plays its goodbye and leaves Cinema mode. */
    static Intent back(android.content.Context context, boolean exit) {
        return new Intent(context, CinemaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(exit ? EXTRA_EXIT : EXTRA_RESUME, true);
    }

    /** A streaming app we can open: its TMDB provider id, package, name. */
    static final class Provider {
        final int id;
        final String pkg;
        final String name;

        Provider(int id, String pkg, String name) {
            this.id = id;
            this.pkg = pkg;
            this.name = name;
        }
    }

    static final Provider[] PROVIDERS = {
            new Provider(8, "com.netflix.ninja", "Netflix"),
            new Provider(337, "com.disney.disneyplus", "Disney+"),
            new Provider(1899, "com.wbd.stream", "HBO Max"),
            new Provider(119, "com.amazon.amazonvideo.livingroom", "Prime Video"),
            new Provider(1773, "com.skyshowtime.skyshowtime.google", "SkyShowtime"),
            new Provider(283, "com.crunchyroll.crunchyroid", "Crunchyroll"),
    };

    /** One film or series from TMDB; the second half arrives with its details. */
    static final class Title {
        int id;
        boolean tv;
        String name = "";
        String original = "";
        String overview = "";
        String year = "";
        String poster;
        String backdrop;
        double vote;
        boolean loading;
        boolean detailed;
        String genres = "";
        String length = "";
        String cert = "";
        String logo;
        final List<String> stills = new ArrayList<>();
        String trailer;
        String trailerSite;
        boolean trailerFailed;
        final List<Provider> included = new ArrayList<>();
        final List<String> rent = new ArrayList<>();
        Map<String, String> ids;
        final List<Runnable> onDetails = new ArrayList<>();
    }

    /** Who is watching: value, words, picture, the genres of its background still. */
    private static final String[][] WHO = {
            {"solo", "Doar eu", "popcorn", "53|18"},
            {"couple", "În doi", "hearts", "10749"},
            {"kids", "Cu copiii", "teddy", "16|10751"},
            {"family", "În familie", "house", "10751"},
            {"friends", "Cu prietenii", "party", "35"},
    };
    /** Moods: label, film genres, series genres (TMDB ids; "|" is or), picture. */
    private static final String[][] MOODS = {
            {"Să râdem", "35", "35", "laugh"},
            {"Acțiune", "28|12", "10759", "collision"},
            {"Suspans", "53|9648|80", "9648|80", "detective"},
            {"Ceva emoționant", "18|10749", "18", "tears"},
            {"Groază", "27", null, "ghost"},
            {"SF și fantasy", "878|14", "10765", "rocket"},
            {"Povești adevărate", "99|36", "99", "globe"},
            {"Animație", "16", "16", "palette"},
            {"Surprinde-mă", "", "", "die"},
    };
    private static final String[][] KID_MOODS = {
            {"Animație", "16", "16", "palette"},
            {"Aventură", "12", "10759", "map"},
            {"Să râdem", "35", "35", "laugh"},
            {"Magie", "14", "10765", "wand"},
            {"Surprinde-mă", "", "", "die"},
    };

    private static final int INTRO = 0;
    private static final int ASK = 1;
    private static final int LOADING = 2;
    private static final int RESULTS = 3;
    private static final int DETAIL = 4;
    private static final int MESSAGE = 5;
    private static final int EXITING = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> pictures = new LruCache<String, Bitmap>(30 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private SharedPreferences prefs;
    private SettingsActivity.Sounds sounds;
    private Tmdb tmdb;
    private float dp;
    private int screenW;
    private int screenH;
    private String viewer = "";

    // What is behind the glass: a blurred copy of the picture on screen.
    private final LensDrawable.Backdrop glassBack = new LensDrawable.Backdrop();
    private final List<View> lensViews = new ArrayList<>();
    private ValueAnimator lensFade;

    private FrameLayout root;
    private final ImageView[] back = new ImageView[2];
    private final ValueAnimator[] pushes = new ValueAnimator[2];
    private boolean topShowing;
    private String backShown;
    private View scrim;
    private View veil;
    private FrameLayout stage;
    private View stageView;
    private LinearLayout crumbs;
    private LinearLayout dots;
    private LinearLayout hero;
    private ImageView heroLogo;
    private TextView heroTitle;
    private ImageView heroBadge;
    private TextView heroMeta;
    private TextView heroOverview;
    private TextView heroRent;
    private LinearLayout heroButtons;
    private TextView rowHeading;
    private HorizontalScrollView rowScroll;
    private LinearLayout row;
    private FrameLayout tabBar;
    private LinearLayout tabs;
    private TabPill tabPill;
    private FrameLayout badge;
    private LinearLayout badgeCapsule;
    private GlassDrawable badgeGlass;
    private TextView badgeText;
    private TextView credit;
    private View curtain;
    private FrameLayout exitAsk;
    private View beforeAsk;
    private android.webkit.WebView video;

    private int screen = INTRO;
    private int step;
    private boolean askSubs;
    private final Set<Integer> subs = new LinkedHashSet<>();
    private final Set<String> signedIn = new HashSet<>();
    private String who;
    private String kind;
    private String[] mood;
    private String span;
    private final List<String[]> answers = new ArrayList<>();
    private List<Title> titles = new ArrayList<>();
    private final List<Title> forYou = new ArrayList<>();
    private final List<Title> popular = new ArrayList<>();
    private int tab;
    private int focused = -1;
    private Title open;
    private int searchGen;
    private final List<String> lobby = new ArrayList<>();
    private int lobbyIndex;
    private final Map<Integer, String> providerLogos = new HashMap<>();
    private final Map<String, String> optionStills = new HashMap<>();
    private boolean leftForApp;
    private android.media.MediaPlayer player;

    private int px(float value) {
        return Math.round(value * dp);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        glassBack.screenW = screenW;
        glassBack.screenH = screenH;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sounds = new SettingsActivity.Sounds(this, prefs);
        tmdb = new Tmdb(this);
        alive = true;
        left = false;
        buildUi();
        begin(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_EXIT, false)) {
            closeAsk();
            exitCinema();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
            // From the home screen's "Cinema" capsule: everything as it was.
            curtain.animate().cancel();
            curtain.animate().alpha(0f).setDuration(350).start();
            restartPreviewTimer();
            return;
        }
        setIntent(intent);
        begin(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        onHome = () -> handler.post(this::askExit);
    }

    @Override
    protected void onPause() {
        onHome = null;
        super.onPause();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        leftForApp = false;
        curtain.animate().cancel();
        curtain.setAlpha(0f);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPreview(true);
        // Anything but the app we opened ends Cinema; the film's app comes back here.
        if (!leftForApp) {
            alive = false;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        net.shutdownNow();
        if (video != null) {
            video.destroy();
            video = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the screen

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        for (int i = 0; i < 2; i++) {
            back[i] = new ImageView(this);
            back[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            back[i].setAlpha(0f);
            back[i].setPivotX(screenW * 0.62f);
            back[i].setPivotY(screenH * 0.38f);
            root.addView(back[i], full());
        }
        scrim = new View(this);
        scrim.setBackground(new Scrim());
        scrim.setAlpha(0f);
        root.addView(scrim, full());
        veil = new View(this);
        veil.setBackgroundColor(0x8C000000);
        veil.setAlpha(0f);
        root.addView(veil, full());

        hero = buildHero();
        hero.setVisibility(View.GONE);
        root.addView(hero, at(px(480), -2, Gravity.TOP | Gravity.START, px(64), px(118), 0, 0));

        rowHeading = text(15, true);
        rowHeading.setVisibility(View.GONE);
        root.addView(rowHeading, at(-2, -2, Gravity.TOP | Gravity.START, px(64), px(366), 0, 0));
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(px(64), px(12), px(64), px(30));
        rowScroll = sideScroll(row, 64);
        rowScroll.setVisibility(View.GONE);
        root.addView(rowScroll, at(-1, -2, Gravity.TOP, 0, px(380), 0, 0));

        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        root.addView(stage, full());

        crumbs = new LinearLayout(this);
        crumbs.setOrientation(LinearLayout.HORIZONTAL);
        crumbs.setClipChildren(false);
        root.addView(crumbs, at(-2, px(30), Gravity.TOP | Gravity.START, px(72), px(26), 0, 0));
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setAlpha(0f);
        root.addView(dots, at(-2, px(8), Gravity.BOTTOM | Gravity.START, px(72), 0, 0, px(44)));

        buildTabs();
        buildBadge();

        credit = text(9.5f, false);
        credit.setText("Date: TMDB · Disponibilitate în România: JustWatch");
        credit.setAlpha(0f);
        root.addView(credit, at(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, px(24), px(12)));

        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        root.addView(curtain, full());
        setContentView(root);
    }

    private FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static FrameLayout.LayoutParams at(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private TextView text(float sp, boolean bold) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private static ViewOutlineProvider round(final float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    /**
     * Makes the view a pane of Liquid Glass, the same lens as the home screen's dock: the
     * blurred picture behind, bent at the rim, under a tinted surface with a specular edge.
     */
    private GlassDrawable glass(View view, float radius, int tint) {
        GlassDrawable surface = new GlassDrawable(radius, dp, tint);
        LensDrawable lens = new LensDrawable(view, glassBack, radius, 1f, dp);
        view.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{lens, surface}));
        lensViews.add(view);
        return surface;
    }

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

    /** A sideways row that glides on a spring just far enough to show the focused item. */
    private HorizontalScrollView sideScroll(final LinearLayout content, final int marginDp) {
        HorizontalScrollView scroll = new HorizontalScrollView(this) {
            private Spring spring;

            @Override
            public void requestChildFocus(View child, View focus) {
                super.requestChildFocus(child, focus);
                View cell = focus;
                while (cell.getParent() != content && cell.getParent() instanceof View) {
                    cell = (View) cell.getParent();
                }
                int max = Math.max(0, content.getWidth() - getWidth());
                int left = cell.getLeft() - px(marginDp);
                int right = cell.getRight() + px(marginDp);
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
                    spring.configure(0.42f, 1f);
                }
                spring.to(target);
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }

            @Override
            public boolean executeKeyEvent(KeyEvent event) {
                return false;
            }
        };
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFocusable(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-2, -2));
        return scroll;
    }

    /** Dark at the left and the bottom, where the words and the cards sit; clear elsewhere. */
    private static final class Scrim extends Drawable {
        private final Paint left = new Paint(Paint.DITHER_FLAG);
        private final Paint bottom = new Paint(Paint.DITHER_FLAG);
        private final Paint top = new Paint(Paint.DITHER_FLAG);

        @Override
        protected void onBoundsChange(Rect b) {
            left.setShader(new LinearGradient(b.left, 0, b.left + b.width() * 0.7f, 0,
                    new int[]{0xE6000000, 0x8C000000, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            bottom.setShader(new LinearGradient(0, b.bottom, 0, b.bottom - b.height() * 0.5f,
                    new int[]{0xF2000000, 0x8C000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            top.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.2f,
                    0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b, left);
            canvas.drawRect(b, bottom);
            canvas.drawRect(b, top);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ top right: Cinema mode

    /** "Cinema mode" with the profile picture, like the clock on the home screen; focused it
     *  turns white and says "Leave Cinema". */
    private void buildBadge() {
        badge = new FrameLayout(this);
        badge.setClipChildren(false);
        badgeCapsule = new LinearLayout(this);
        badgeCapsule.setOrientation(LinearLayout.HORIZONTAL);
        badgeCapsule.setGravity(Gravity.CENTER_VERTICAL);
        badgeCapsule.setPadding(px(7), 0, px(16) + px(30), 0);
        badgeGlass = glass(badgeCapsule, px(15), 0x29FFFFFF);
        CinemaMark.Spin spin = new CinemaMark.Spin(this);
        badgeCapsule.addView(spin, new LinearLayout.LayoutParams(px(20), px(20)));
        badgeText = text(13.5f, true);
        badgeText.setText("Mod Cinema");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(7);
        badgeCapsule.addView(badgeText, tlp);
        badge.addView(badgeCapsule, new FrameLayout.LayoutParams(-2, px(30), Gravity.END));
        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setOutlineProvider(round(px(15)));
        avatar.setClipToOutline(true);
        avatar.setTag("avatar");
        badge.addView(avatar, new FrameLayout.LayoutParams(px(30), px(30), Gravity.END));
        badge.setFocusable(true);
        badge.setId(View.generateViewId());
        badge.setNextFocusRightId(badge.getId());
        badge.setNextFocusUpId(badge.getId());
        final Spring lift = new Spring(0f, 0.002f, v -> {
            float k = 1f + 0.08f * v;
            badge.setScaleX(k);
            badge.setScaleY(k);
        });
        lift.configure(0.34f, 0.72f);
        badge.setPivotX(0);
        badge.setOnFocusChangeListener((v, has) -> {
            badgeGlass.setTint(has ? 0xF2FFFFFF : 0x29FFFFFF);
            badgeText.setTextColor(has ? 0xFF111111 : Color.WHITE);
            badgeText.setText(has ? "Ieși din Cinema" : "Mod Cinema");
            lift.to(has ? 1f : 0f);
        });
        badge.setOnClickListener(v -> {
            sounds.play("select");
            exitCinema();
        });
        badge.setAlpha(0f);
        root.addView(badge, at(-2, px(30), Gravity.TOP | Gravity.END, 0, px(26), px(40), 0));
    }

    private void showAvatar() {
        ImageView avatar = badge.findViewWithTag("avatar");
        for (Profiles.Profile p : Profiles.all(prefs, viewer)) {
            if (p.id.equals(Profiles.current(prefs))) {
                avatar.setImageBitmap(Profiles.picture(this, prefs, p, px(30) * 2));
            }
        }
    }

    // ------------------------------------------------------------------ the tab bar

    private static final String[] TABS = {"Pentru voi", "Populare acum", "Altă alegere", "Abonamente"};

    /** One centred Liquid Glass capsule, the highlight gliding between the tabs (Apple TV app). */
    private void buildTabs() {
        tabBar = new FrameLayout(this) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                tabPill.layout(0, 0, tabs.getWidth(), tabs.getHeight());
            }
        };
        tabBar.setClipChildren(false);
        glass(tabBar, px(19), 0x2E14161A);
        tabPill = new TabPill();
        // Laid out by hand to the tabs' size: MATCH_PARENT here would make the bar (and its
        // glass) as wide and tall as the screen.
        tabBar.addView(tabPill, new FrameLayout.LayoutParams(0, 0));
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(px(4), px(4), px(4), px(4));
        tabs.setClipChildren(false);
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            final TextView t = text(13.5f, true);
            t.setText(TABS[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(14), 0, px(14), 0);
            t.setFocusable(true);
            t.setId(View.generateViewId());
            t.setOnFocusChangeListener((v, has) -> {
                paintTabs();
                if (has) {
                    tabPill.moveTo(v, true);
                    if (index <= 1 && index != tab) {
                        handler.removeCallbacks(tabSwitch);
                        pendingTab = index;
                        handler.postDelayed(tabSwitch, 280);
                    }
                } else {
                    if (pendingTab == index) {
                        handler.removeCallbacks(tabSwitch);
                    }
                    v.post(() -> {
                        if (!tabsFocused()) {
                            tabPill.moveTo(tabs.getChildAt(tab), false);
                        }
                    });
                }
            });
            t.setOnClickListener(v -> {
                sounds.play("select");
                if (index == 2) {
                    answers.clear();
                    showStep(1, true);
                } else if (index == 3) {
                    askSubs = true;
                    showStep(0, true);
                } else {
                    switchTab(index);
                    View first = heroButtons.getChildAt(0);
                    if (first != null) {
                        first.requestFocus();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(30));
            lp.rightMargin = i < TABS.length - 1 ? px(2) : 0;
            tabs.addView(t, lp);
        }
        tabBar.addView(tabs, new FrameLayout.LayoutParams(-2, -2));
        tabBar.setVisibility(View.GONE);
        root.addView(tabBar, at(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, px(22), 0, 0));
    }

    private int pendingTab;
    private final Runnable tabSwitch = () -> switchTab(pendingTab);

    private boolean tabsFocused() {
        View f = getCurrentFocus();
        return f != null && f.getParent() == tabs;
    }

    private void paintTabs() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            t.setTextColor(t.isFocused() ? 0xFF111111 : Color.WHITE);
        }
    }

    /** The tab bar's highlight: a pill behind the tabs, white on focus, light on the one in use. */
    private final class TabPill extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Spring x;
        private final Spring w;
        private final Spring bright;
        private boolean placed;

        TabPill() {
            super(CinemaActivity.this);
            x = new Spring(0f, 0.3f, v -> invalidate());
            x.configure(0.28f, 0.86f);
            w = new Spring(0f, 0.3f, v -> invalidate());
            w.configure(0.28f, 0.9f);
            bright = new Spring(0f, 0.002f, v -> invalidate());
            bright.configure(0.2f, 1f);
        }

        void moveTo(View chip, boolean focus) {
            if (chip == null) {
                return;
            }
            if (chip.getWidth() == 0) {
                chip.post(() -> moveTo(chip, focus));
                return;
            }
            if (!placed) {
                x.snap(chip.getLeft());
                w.snap(chip.getWidth());
                bright.snap(focus ? 1f : 0f);
                placed = true;
            } else {
                x.to(chip.getLeft());
                w.to(chip.getWidth());
                bright.to(focus ? 1f : 0f);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!placed || tabs.getChildCount() == 0) {
                return;
            }
            float b = Math.max(0f, Math.min(1f, bright.value()));
            int a = Math.round(0x40 + (0xF2 - 0x40) * b);
            paint.setColor((a << 24) | 0xFFFFFF);
            View any = tabs.getChildAt(0);
            box.set(x.value(), any.getTop(), x.value() + w.value(), any.getTop() + any.getHeight());
            canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, paint);
        }
    }

    // ------------------------------------------------------------------ the start

    private void begin(Intent intent) {
        searchGen++;
        handler.removeCallbacksAndMessages(null);
        stopPreview(true);
        closeAsk();
        screen = INTRO;
        viewer = intent != null && intent.getStringExtra(EXTRA_NAME) != null ? intent.getStringExtra(EXTRA_NAME) : "";
        forYou.clear();
        popular.clear();
        titles = forYou;
        answers.clear();
        open = null;
        focused = -1;
        tab = 0;
        hideResults();
        crumbs.removeAllViews();
        credit.setAlpha(0f);
        scrim.setAlpha(0f);
        veil.setAlpha(0f);
        dots.setAlpha(0f);
        badge.setAlpha(0f);
        for (ImageView b : back) {
            b.animate().cancel();
            b.setAlpha(0f);
            b.setImageDrawable(null);
        }
        backShown = null;
        curtain.setAlpha(0f);
        showAvatar();
        loadSubs();
        if (!tmdb.ready()) {
            message("Cinema are nevoie de o cheie TMDB",
                    "Pe calculator rulează:  python tvtool.py tmdb\nCheia e gratuită, de pe themoviedb.org.", null);
            return;
        }
        net.execute(this::loadLobby);
        intro();
    }

    /**
     * The logo, synchronised with its soundtrack: the ring draws itself, the play sign pops,
     * "cinema" is written letter by letter, then fills with a flowing gradient as the chord
     * blooms; a warm light glows behind and slowly goes down.
     */
    private void intro() {
        FrameLayout group = new FrameLayout(this);
        group.setClipChildren(false);
        final LogoView logo = new LogoView();
        group.addView(logo, new FrameLayout.LayoutParams(px(780), px(150), Gravity.CENTER));
        swapStage(group, true, false);
        sound("cinema_logo.ogg", 0.85f);
        handler.postDelayed(logo::start, AUDIO_LEAD);
        // The logo rises away into the dark, then the first question.
        handler.postDelayed(() -> logo.animate().alpha(0f).translationY(-px(20)).setStartDelay(0).setDuration(700).start(),
                AUDIO_LEAD + 4300);
        handler.postDelayed(this::afterIntro, AUDIO_LEAD + 5000);
    }

    private void afterIntro() {
        if (screen != INTRO) {
            return;
        }
        badge.animate().alpha(1f).setDuration(600).start();
        askSubs = subs.isEmpty() || tempSubs;
        showStep(askSubs ? 0 : 1, true);
        lobbyTick.run();
    }

    /** A warm, soft pool of light. */
    private static final class Glow extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final int inner;
        private final int outer;

        Glow(int inner, int outer) {
            this.inner = inner;
            this.outer = outer;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float r = Math.max(b.width(), b.height()) / 2f;
            paint.setShader(new RadialGradient(b.exactCenterX(), b.exactCenterY(), r,
                    new int[]{inner, outer, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save();
            canvas.scale(1f, b.height() / (float) Math.max(1, b.width()), b.exactCenterX(), b.exactCenterY());
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), Math.max(b.width(), b.height()) / 2f, paint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The animated logo, timed with cinema_logo.ogg: the glass ring fills with light and the
     * play sign pops, "Mod Cinema" comes out of a blur, then "activ" out of a blur too, in the
     * warm gradient, with a glow behind and a band of light passing over it.
     */
    private final class LogoView extends View {
        private static final String FIRST = "Mod Cinema";
        private static final String SECOND = "activ";
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint colour = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint soft = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix flow = new Matrix();
        private final Rect whole = new Rect();
        private final ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        private LinearGradient fill;
        private Bitmap blurFirst;
        private Bitmap blurSecond;
        private final float firstW;
        private final float secondW;
        private float markX;
        private float firstX;
        private float secondX;
        private long started;

        LogoView() {
            super(CinemaActivity.this);
            Typeface face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            for (Paint p : new Paint[]{white, colour, sheen}) {
                p.setTypeface(face);
                p.setTextSize(px(54));
                p.setLetterSpacing(-0.01f);
            }
            white.setColor(Color.WHITE);
            firstW = white.measureText(FIRST);
            secondW = white.measureText(SECOND);
            clock.setDuration(1000);
            clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(null);
            clock.addUpdateListener(a -> invalidate());
        }

        void start() {
            started = SystemClock.uptimeMillis();
            clock.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            clock.cancel();
            super.onDetachedFromWindow();
        }

        private float markR() {
            return px(30);
        }

        private float baseline() {
            Paint.FontMetrics fm = white.getFontMetrics();
            return getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float total = markR() * 2 + px(22) + firstW + px(16) + secondW;
            float x0 = (w - total) / 2f;
            markX = x0 + markR();
            firstX = x0 + markR() * 2 + px(22);
            secondX = firstX + firstW + px(16);
            fill = new LinearGradient(secondX, 0, secondX + secondW, 0, CinemaMark.COLOURS, null, Shader.TileMode.MIRROR);
            colour.setShader(fill);
            whole.set(0, 0, w, h);
            // The words as soft blurs (drawn once in software, where blur works), to sharpen from.
            blurFirst = blurredWord(w, h, FIRST, firstX, white, px(14));
            blurSecond = blurredWord(w, h, SECOND, secondX, colour, px(14));
        }

        private Bitmap blurredWord(int w, int h, String word, float x, Paint paint, float radius) {
            Bitmap b = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(0.5f, 0.5f);
            Paint p = new Paint(paint);
            p.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
            c.drawText(word, x, baseline(), p);
            return b;
        }

        private float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        private float easeOut(float v) {
            float k = 1f - clamp(v);
            return 1f - k * k * k;
        }

        /** A word coming out of a blur: the blur fades in large, then gives way to the sharp word. */
        private void reveal(Canvas canvas, float t, float at, String word, float x, float w, Bitmap blur, Paint sharp) {
            float appear = clamp((t - at) / 0.25f);
            if (appear <= 0) {
                return;
            }
            float focus = easeOut((t - at - 0.08f) / 0.7f);
            float scale = 1.08f - 0.08f * focus;
            float cx = x + w / 2f;
            float cy = getHeight() / 2f;
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
            soft.setAlpha(Math.round(255 * appear * (1f - focus)));
            canvas.drawBitmap(blur, null, whole, soft);
            sharp.setAlpha(Math.round(255 * appear * focus));
            canvas.drawText(word, x, baseline(), sharp);
            canvas.restore();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (started == 0 || fill == null) {
                return;
            }
            float t = (SystemClock.uptimeMillis() - started) / 1000f;
            float cy = getHeight() / 2f;
            // The glass ring fills with light, then the play sign pops with a little overshoot.
            float k = clamp(t / 0.9f);
            float ring = k < 0.5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2;
            float p = clamp((t - 0.7f) / 0.35f);
            float play = p <= 0 ? 0 : (float) (1 + Math.sin(p * Math.PI) * 0.2f * (1 - p)) * easeOut(p);
            CinemaMark.draw(canvas, markX, cy, markR(), ring, t * 40f, play, 255);
            reveal(canvas, t, 0.85f, FIRST, firstX, firstW, blurFirst, white);
            flow.setTranslate(-(t * px(50)) % (secondW * 2f), 0);
            fill.setLocalMatrix(flow);
            reveal(canvas, t, 1.5f, SECOND, secondX, secondW, blurSecond, colour);
            // A band of light passing over "activ".
            float sweep = (t - 1.75f) / 0.7f;
            if (sweep > 0 && sweep < 1) {
                float bx = secondX - px(50) + (secondW + px(100)) * sweep;
                sheen.setShader(new LinearGradient(bx - px(40), 0, bx + px(40), 0,
                        new int[]{0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                canvas.drawText(SECOND, secondX, baseline(), sheen);
            }
        }
    }

    private void loadLobby() {
        try {
            JSONObject week = tmdb.get("/trending/all/week", "language=ro-RO", 12 * HOUR);
            JSONArray results = week.optJSONArray("results");
            List<String> stills = new ArrayList<>();
            for (int i = 0; results != null && i < results.length() && stills.size() < 8; i++) {
                String b = str(results.optJSONObject(i), "backdrop_path");
                if (b != null) {
                    stills.add(b);
                }
            }
            handler.post(() -> {
                lobby.clear();
                lobby.addAll(stills);
            });
            JSONObject list = tmdb.get("/watch/providers/movie", "watch_region=RO&language=ro-RO", 7 * 24 * HOUR);
            JSONArray all = list.optJSONArray("results");
            Map<Integer, String> logos = new HashMap<>();
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject p = all.optJSONObject(i);
                logos.put(p.optInt("provider_id"), str(p, "logo_path"));
            }
            handler.post(() -> providerLogos.putAll(logos));
        } catch (IOException ignored) {
            // The lobby stays dark; the questions still work.
        }
    }

    /** Behind the questions when nothing is focused yet: this week's stills, one after another. */
    private final Runnable lobbyTick = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (screen != ASK && screen != LOADING) {
                return;
            }
            if (!lobby.isEmpty() && backShown == null) {
                final String path = lobby.get(lobbyIndex++ % lobby.size());
                picture(path, "w1280", 1280, false, b -> {
                    if ((screen == ASK || screen == LOADING) && backShown == null) {
                        showBack(b, path, 1400);
                    }
                });
            }
            handler.postDelayed(this, 9000);
        }
    };

    // ------------------------------------------------------------------ questions

    interface Answer {
        void picked(String value);
    }

    private void showStep(int which, boolean forward) {
        screen = ASK;
        step = which;
        stopPreview(true);
        hideResults();
        scrim.animate().alpha(0.7f).setDuration(500).start();
        credit.animate().alpha(0f).setDuration(300).start();
        veil.animate().alpha(1f).setDuration(600).start();
        badge.animate().alpha(1f).setDuration(400).start();
        showCrumbs();
        List<String[]> options = new ArrayList<>();
        switch (which) {
            case 0: {
                for (Provider p : PROVIDERS) {
                    if (installed(p.pkg)) {
                        options.add(new String[]{String.valueOf(p.id), p.name, p.pkg});
                    }
                }
                swapStage(question("Pe ce platforme aveți abonament?",
                        "Am bifat aplicațiile în care pari conectat. Schimbă cu OK, apoi Continuă.", options, true, v -> {
                            if (!forYou.isEmpty() && who != null) {
                                search();
                            } else {
                                showStep(1, true);
                            }
                        }), forward, true);
                break;
            }
            case 1: {
                for (String[] w : WHO) {
                    options.add(new String[]{w[0], w[1], w[2]});
                }
                swapStage(question("Cine se uită în seara asta?", "Alegem altceva pentru copii decât pentru prieteni.",
                        options, false, v -> {
                            who = v;
                            answers.clear();
                            answers.add(find(WHO, v));
                            showStep(2, true);
                        }), forward, true);
                break;
            }
            case 2: {
                options.add(new String[]{"movie", "Un film", "clapper"});
                options.add(new String[]{"tv", "Un serial", "television"});
                swapStage(question("Film sau serial?", "", options, false, v -> {
                    kind = v;
                    trimAnswers(1);
                    answers.add(new String[]{v, "movie".equals(v) ? "Film" : "Serial", "movie".equals(v) ? "clapper" : "television"});
                    showStep(3, true);
                }), forward, true);
                break;
            }
            case 3: {
                final String[][] moods = "kids".equals(who) ? KID_MOODS : MOODS;
                for (String[] m : moods) {
                    if ("tv".equals(kind) && m[2] == null) {
                        continue;
                    }
                    if ("Groază".equals(m[0]) && "family".equals(who)) {
                        continue;
                    }
                    options.add(new String[]{m[0], m[0], m[3]});
                }
                swapStage(question("Ce chef aveți?", "", options, false, v -> {
                    for (String[] m : moods) {
                        if (m[0].equals(v)) {
                            mood = m;
                        }
                    }
                    trimAnswers(2);
                    answers.add(new String[]{v, v, mood[3]});
                    showStep(4, true);
                }), forward, true);
                break;
            }
            default: {
                boolean tv = "tv".equals(kind);
                if (tv) {
                    options.add(new String[]{"new", "Ceva nou", "sparkles"});
                    options.add(new String[]{"any", "Oricând", "calendar"});
                    options.add(new String[]{"classic", "Un clasic", "frames"});
                } else {
                    options.add(new String[]{"short", "Sub o oră și jumătate", "stopwatch"});
                    options.add(new String[]{"two", "Cam două ore", "hourglass"});
                    options.add(new String[]{"any", "Oricât", "infinity"});
                }
                swapStage(question(tv ? "Ceva nou sau un clasic?" : "Cât timp aveți?", "", options, false, v -> {
                    span = v;
                    trimAnswers(3);
                    search();
                }), forward, true);
                break;
            }
        }
        showDots();
    }

    private static String[] find(String[][] list, String value) {
        for (String[] o : list) {
            if (o[0].equals(value)) {
                return new String[]{o[0], o[1], o[2]};
            }
        }
        return new String[]{value, value, null};
    }

    private void trimAnswers(int keep) {
        while (answers.size() > keep) {
            answers.remove(answers.size() - 1);
        }
    }

    /** The answers so far, as small glass capsules at the top left. */
    private void showCrumbs() {
        crumbs.removeAllViews();
        int shown = screen == ASK ? Math.min(answers.size(), Math.max(0, step - 1)) : 0;
        for (int i = 0; i < shown; i++) {
            String[] a = answers.get(i);
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(px(6), 0, px(12), 0);
            glass(pill, px(15), 0x24FFFFFF);
            if (a[2] != null) {
                ImageView e = new ImageView(this);
                e.setImageBitmap(emoji(a[2]));
                pill.addView(e, new LinearLayout.LayoutParams(px(20), px(20)));
            }
            TextView t = text(12, true);
            t.setText(a[1]);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = px(5);
            pill.addView(t, tlp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(30));
            lp.rightMargin = px(8);
            crumbs.addView(pill, lp);
            if (i == shown - 1) {
                pill.setAlpha(0f);
                pill.setTranslationY(px(8));
                pill.animate().alpha(1f).translationY(0).setInterpolator(EASE).setDuration(420).start();
            }
        }
    }

    private Bitmap emoji(String name) {
        Bitmap have = pictures.get("emoji" + name);
        if (have != null) {
            return have;
        }
        try (java.io.InputStream in = getAssets().open("cinema/" + name + ".png")) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) {
                pictures.put("emoji" + name, b);
            }
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private View question(String title, String hint, List<String[]> options, final boolean multi, final Answer answer) {
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView head = text(34, true);
        head.setText(title);
        words.addView(head, new LinearLayout.LayoutParams(-2, -2));
        if (!hint.isEmpty()) {
            TextView sub = text(14, false);
            sub.setText(hint);
            sub.setAlpha(0.7f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = px(10);
            words.addView(sub, slp);
        }
        holder.addView(words, at(-2, -2, Gravity.TOP | Gravity.START, px(72), px(118), 0, 0));
        final LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        cards.setPadding(px(72), px(20), px(72), px(40));
        HorizontalScrollView scroll = sideScroll(cards, 72);
        holder.addView(scroll, at(-1, -2, Gravity.TOP, 0, px(206), 0, 0));
        View first = null;
        for (int i = 0; i <= options.size(); i++) {
            final boolean done = i == options.size();
            if (done && !multi) {
                break;
            }
            final OptionCard card;
            if (done) {
                card = new OptionCard("Continuă", null, null);
                card.setOnClickListener(v -> {
                    if (subs.isEmpty()) {
                        card.shake();
                        return;
                    }
                    sounds.play("select");
                    saveSubs();
                    card.chosen(() -> answer.picked(""));
                });
            } else {
                final String[] o = options.get(i);
                if (multi) {
                    final int id = Integer.parseInt(o[0]);
                    card = new OptionCard(o[1], null, providerLogos.get(id));
                    if (signedIn.contains(o[2])) {
                        card.setNote("conectat");
                    }
                    card.setChecked(subs.contains(id));
                    card.setOnClickListener(v -> {
                        sounds.play("select");
                        if (!subs.remove(id)) {
                            subs.add(id);
                        }
                        card.setChecked(subs.contains(id));
                    });
                } else {
                    card = new OptionCard(o[1], o[2], null);
                    final String key = step + ":" + o[0];
                    prefetchStill(key, o[0]);
                    card.setOnFocusListener(() -> focusOption(key));
                    card.setOnClickListener(v -> {
                        sounds.play("select");
                        for (int k = 0; k < cards.getChildCount(); k++) {
                            View other = cards.getChildAt(k);
                            if (other != v.getParent()) {
                                other.animate().alpha(0.25f).setDuration(220).start();
                            }
                        }
                        card.chosen(() -> answer.picked(o[0]));
                    });
                }
            }
            FrameLayout cell = card.cell;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(132), px(156));
            clp.rightMargin = px(16);
            cards.addView(cell, clp);
            card.setId(View.generateViewId());
            if (first == null) {
                first = card;
            }
            cell.setAlpha(0f);
            cell.setTranslationY(px(26));
            cell.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2.2f))
                    .setStartDelay(160 + 45L * i).setDuration(520).start();
        }
        // The row ends where it ends: Right on the last card stays there (it must never jump
        // to "Leave Cinema"); Up goes to the Cinema capsule on purpose.
        for (int i = 0; i < cards.getChildCount(); i++) {
            View c = ((ViewGroup) cards.getChildAt(i)).getChildAt(0);
            c.setNextFocusUpId(badge.getId());
            c.setNextFocusDownId(c.getId());
            if (i == 0) {
                c.setNextFocusLeftId(c.getId());
            }
            if (i == cards.getChildCount() - 1) {
                c.setNextFocusRightId(c.getId());
            }
        }
        badge.setNextFocusDownId(first != null ? first.getId() : View.NO_ID);
        final View focus = first;
        holder.post(() -> {
            if (focus != null) {
                focus.requestFocus();
            }
        });
        return holder;
    }

    /**
     * An answer as a card of Liquid Glass: a 3D picture (or the app's logo) and the words under
     * it. Focused, it lifts on a spring with a shadow below and its picture bobs gently.
     */
    private final class OptionCard extends FrameLayout {
        final FrameLayout cell;
        private final GlassDrawable surface;
        private final ImageView picture;
        private final ImageView check;
        private final TextView note;
        private final Spring lift;
        private final ValueAnimator bob = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        private boolean checked;
        private Runnable onFocus;

        OptionCard(String label, String emojiName, String logoPath) {
            super(CinemaActivity.this);
            setFocusable(true);
            setClipChildren(false);
            surface = glass(this, px(24), 0x14FFFFFF);
            setOutlineProvider(round(px(24)));
            picture = new ImageView(CinemaActivity.this);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (emojiName != null) {
                picture.setImageBitmap(emoji(emojiName));
                addView(picture, at(px(72), px(72), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, px(20), 0, 0));
            } else if (logoPath != null) {
                picture.setOutlineProvider(round(px(14)));
                picture.setClipToOutline(true);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                addView(picture, at(px(64), px(64), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, px(22), 0, 0));
                Bitmap have = pictures.get("w154" + logoPath);
                if (have != null) {
                    picture.setImageBitmap(have);
                } else {
                    picture(logoPath, "w154", 154, false, picture::setImageBitmap);
                }
            } else {
                picture.setImageBitmap(arrowGlyph(px(40)));
                addView(picture, at(px(40), px(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, px(40), 0, 0));
            }
            LinearLayout words = new LinearLayout(CinemaActivity.this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView name = text(14.5f, true);
            name.setSingleLine(false);
            name.setMaxLines(2);
            name.setGravity(Gravity.CENTER);
            name.setText(label);
            words.addView(name);
            note = text(11, false);
            note.setAlpha(0.7f);
            note.setVisibility(GONE);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.topMargin = px(3);
            words.addView(note, nlp);
            addView(words, at(-1, -2, Gravity.BOTTOM, px(8), 0, px(8), px(16)));
            check = new ImageView(CinemaActivity.this);
            check.setImageBitmap(checkGlyph(px(22)));
            check.setVisibility(GONE);
            addView(check, at(px(22), px(22), Gravity.TOP | Gravity.END, 0, px(10), px(10), 0));
            cell = new FrameLayout(CinemaActivity.this);
            cell.setClipChildren(false);
            final FocusShadow shadow = new FocusShadow(this);
            cell.setBackground(shadow);
            cell.addView(this, new FrameLayout.LayoutParams(-1, -1));
            lift = new Spring(0f, 0.002f, v -> {
                float k = 1f + 0.1f * v;
                setScaleX(k);
                setScaleY(k);
                setTranslationY(-px(6) * v);
                shadow.setAmount(v);
                cell.invalidate();
            });
            lift.configure(0.36f, 0.68f);
            bob.setDuration(2600);
            bob.setRepeatCount(ValueAnimator.INFINITE);
            bob.setInterpolator(null);
            bob.addUpdateListener(a -> picture.setTranslationY((float) Math.sin((float) a.getAnimatedValue()) * px(3)));
            setOnFocusChangeListener((v, has) -> {
                paint(has);
                lift.to(has ? 1f : 0f);
                picture.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).rotation(0)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).setDuration(420).start();
                if (has) {
                    picture.setRotation(-8f);
                    bob.start();
                    if (onFocus != null) {
                        onFocus.run();
                    }
                } else {
                    bob.cancel();
                    picture.setTranslationY(0);
                }
            });
        }

        void setOnFocusListener(Runnable r) {
            onFocus = r;
        }

        void setNote(String text) {
            note.setText(text);
            note.setVisibility(VISIBLE);
        }

        void setChecked(boolean value) {
            checked = value;
            check.setVisibility(value ? VISIBLE : GONE);
            if (value) {
                check.setScaleX(0.4f);
                check.setScaleY(0.4f);
                check.animate().scaleX(1f).scaleY(1f).setInterpolator(new android.view.animation.OvershootInterpolator(3f))
                        .setDuration(300).start();
            }
            paint(hasFocus());
        }

        private void paint(boolean has) {
            surface.setTint(has ? 0x47FFFFFF : checked ? 0x33FFFFFF : 0x14FFFFFF);
        }

        /** Picked: a quick press-and-swell, then the next question. */
        void chosen(final Runnable then) {
            lift.to(1.6f);
            handler.postDelayed(() -> lift.to(1f), 160);
            handler.postDelayed(then, 300);
        }

        void shake() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(360);
            a.addUpdateListener(v -> setTranslationX((float) Math.sin((float) v.getAnimatedValue() * Math.PI * 4)
                    * px(6) * (1f - (float) v.getAnimatedValue())));
            a.start();
        }
    }

    /** The still behind an answer: something popular of that kind on your subscriptions. */
    private void prefetchStill(final String key, final String value) {
        if (optionStills.containsKey(key) || subs.isEmpty()) {
            return;
        }
        optionStills.put(key, "");
        final int at = step;
        final String forKind = kind != null ? kind : "movie";
        final String forWho = who;
        net.execute(() -> {
            try {
                String path;
                String genres = "";
                if (at == 1) {
                    genres = whoGenres(value);
                } else if (at == 3) {
                    for (String[] m : "kids".equals(forWho) ? KID_MOODS : MOODS) {
                        if (m[0].equals(value)) {
                            genres = "tv".equals(forKind) ? m[2] : m[1];
                        }
                    }
                }
                if (at == 2 || genres == null || genres.isEmpty()) {
                    String k = at == 2 ? value : forKind;
                    JSONArray r = tmdb.get("/trending/" + k + "/week", "language=ro-RO", 12 * HOUR).optJSONArray("results");
                    path = firstBackdrop(r, at == 2 ? 0 : at + 2);
                } else {
                    JSONArray r = tmdb.get("/discover/" + forKind, "language=ro-RO&watch_region=RO"
                            + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&vote_count.gte=200"
                            + "&with_watch_providers=" + Uri.encode(TextUtils.join("|", subs))
                            + "&with_genres=" + Uri.encode(genres), 12 * HOUR).optJSONArray("results");
                    path = firstBackdrop(r, 0);
                }
                if (path != null) {
                    final String found = path;
                    handler.post(() -> {
                        optionStills.put(key, found);
                        picture(found, "w780", 780, false, b -> {
                            if (key.equals(focusedOption)) {
                                optionStill.run();
                            }
                        });
                    });
                }
            } catch (IOException ignored) {
                // The lobby stays.
            }
        });
    }

    private static String whoGenres(String value) {
        for (String[] w : WHO) {
            if (w[0].equals(value)) {
                return w[3];
            }
        }
        return "";
    }

    private static String firstBackdrop(JSONArray results, int skip) {
        for (int i = 0, seen = 0; results != null && i < results.length(); i++) {
            String b = str(results.optJSONObject(i), "backdrop_path");
            if (b != null && seen++ >= skip) {
                return b;
            }
        }
        return null;
    }

    private String focusedOption;
    private final Runnable optionStill = () -> {
        String path = focusedOption != null ? optionStills.get(focusedOption) : null;
        if (path != null && !path.isEmpty() && screen == ASK) {
            final String p = path;
            picture(p, "w780", 780, false, b -> {
                if (screen == ASK && focusedOption != null && p.equals(optionStills.get(focusedOption))) {
                    showBack(b, p, 700);
                }
            });
        }
    };

    private void focusOption(String key) {
        focusedOption = key;
        handler.removeCallbacks(optionStill);
        handler.postDelayed(optionStill, 180);
    }

    /** Page dots under the questions: the current one a longer white capsule. */
    private void showDots() {
        int count = askSubs ? 5 : 4;
        int index = askSubs ? step : step - 1;
        if (dots.getChildCount() != count) {
            dots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View d = new View(this);
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(px(3));
                shape.setColor(Color.WHITE);
                d.setBackground(shape);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(7), px(5));
                lp.rightMargin = px(6);
                dots.addView(d, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            final View d = dots.getChildAt(i);
            final boolean on = i == index;
            d.animate().alpha(on ? 1f : i < index ? 0.6f : 0.3f).setDuration(300).start();
            final int from = d.getLayoutParams().width;
            final int to = px(on ? 26 : 7);
            if (from != to) {
                ValueAnimator a = ValueAnimator.ofInt(from, to);
                a.setDuration(380);
                a.setInterpolator(EASE);
                a.addUpdateListener(v -> {
                    d.getLayoutParams().width = (int) v.getAnimatedValue();
                    d.requestLayout();
                });
                a.start();
            }
        }
        dots.animate().alpha(1f).setDuration(400).start();
    }

    /** The next screen slides in from the side it comes from; the old one leaves the other way. */
    private void swapStage(final View next, boolean forward, boolean slide) {
        final View old = stageView;
        stageView = next;
        int shift = slide ? px(64) : 0;
        if (old != null) {
            old.animate().cancel();
            old.animate().alpha(0f).translationX(forward ? -shift : shift).setInterpolator(new AccelerateInterpolator(1.4f))
                    .setDuration(240).withEndAction(() -> stage.removeView(old)).start();
            if (old instanceof ViewGroup) {
                ((ViewGroup) old).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
        }
        if (next == null) {
            return;
        }
        stage.addView(next, full());
        next.setAlpha(0f);
        next.setTranslationX(forward ? shift : -shift);
        next.animate().alpha(1f).translationX(0).setInterpolator(new DecelerateInterpolator(2.4f))
                .setStartDelay(old != null ? 140 : 0).setDuration(480).start();
    }

    // ------------------------------------------------------------------ subscriptions

    private boolean tempSubs;

    /**
     * Which subscriptions to search. Android lets no app see whether you are signed in to
     * another, so the best sign is what the apps put on the TV's home screen: one that
     * publishes "continue watching" or recommendations of its own is almost always signed in.
     * Those are ticked the first time; you confirm once (and can change it later).
     */
    private void loadSubs() {
        subs.clear();
        signedIn.clear();
        // Signed-out apps publish recommendations too (adverts, really), so those do not count;
        // what does: "continue watching" in the TV's list, or a row of the app's own named so.
        Map<String, Integer> next = countByPackage("content://android.media.tv/watch_next_program");
        signedIn.addAll(next.keySet());
        Set<String> rows = continueRows();
        signedIn.addAll(rows);
        log("signed in (continue watching " + next + ", rows " + rows + ")");
        String saved = prefs.getString(Profiles.scoped(prefs, KEY_SUBS), null);
        if (saved != null) {
            tempSubs = false;
            for (String s : saved.split(",")) {
                try {
                    subs.add(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {
                    // Skip.
                }
            }
            return;
        }
        for (Provider p : PROVIDERS) {
            if (installed(p.pkg) && signedIn.contains(p.pkg)) {
                subs.add(p.id);
            }
        }
        tempSubs = true;
    }

    /** Apps with a preview row of their own called something like "Continue watching". */
    private Set<String> continueRows() {
        Set<String> out = new HashSet<>();
        Pattern own = Pattern.compile("(?i)contin|watching|vizion|my list|lista mea|mylist|resume|reluare");
        try (Cursor c = getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"package_name", "display_name"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = c.getString(0);
                String name = c.getString(1);
                if (pkg != null && name != null && own.matcher(name).find()) {
                    out.add(pkg);
                }
            }
        } catch (RuntimeException ignored) {
            // No access to the TV provider.
        }
        return out;
    }

    private Map<String, Integer> countByPackage(String uri) {
        Map<String, Integer> out = new HashMap<>();
        try (Cursor c = getContentResolver().query(Uri.parse(uri), new String[]{"package_name"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = c.getString(0);
                if (pkg != null) {
                    Integer n = out.get(pkg);
                    out.put(pkg, n == null ? 1 : n + 1);
                }
            }
        } catch (RuntimeException ignored) {
            // No access to the TV provider.
        }
        return out;
    }

    private void log(String line) {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) {
            return;
        }
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "cinema.log"), true)) {
            w.write(new java.util.Date() + "  " + line + "\n");
        } catch (IOException ignored) {
            // Debug only.
        }
    }

    private void saveSubs() {
        tempSubs = false;
        prefs.edit().putString(Profiles.scoped(prefs, KEY_SUBS), TextUtils.join(",", subs)).apply();
    }

    private boolean installed(String pkg) {
        return installed(this, pkg);
    }

    static boolean installed(android.content.Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the search

    private void search() {
        final int gen = ++searchGen;
        screen = LOADING;
        crumbs.removeAllViews();
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER_HORIZONTAL);
        group.addView(new Ring(), new LinearLayout.LayoutParams(px(48), px(48)));
        TextView words = text(17, true);
        words.setText("Caut ceva bun pentru voi…");
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = px(20);
        group.addView(words, wlp);
        FrameLayout holder = new FrameLayout(this);
        holder.addView(group, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // The loading screen holds the focus itself, so it does not fall on "Leave Cinema".
        holder.setFocusable(true);
        swapStage(holder, true, true);
        holder.requestFocus();
        dots.animate().alpha(0f).setDuration(300).start();
        final long started = System.currentTimeMillis();
        net.execute(() -> {
            final List<Title> found = new ArrayList<>();
            String failed = null;
            try {
                boolean tv = "tv".equals(kind);
                Set<Integer> trending = new HashSet<>();
                JSONArray week = tmdb.get("/trending/" + kind + "/week", "language=ro-RO", 12 * HOUR).optJSONArray("results");
                for (int i = 0; week != null && i < week.length(); i++) {
                    trending.add(week.optJSONObject(i).optInt("id"));
                }
                for (int relax = 0; relax < 3 && found.size() < 6; relax++) {
                    found.clear();
                    Set<Integer> ids = new HashSet<>();
                    for (int page = 1; page <= 2; page++) {
                        JSONObject r = tmdb.get("/discover/" + kind, discover(relax, page), 6 * HOUR);
                        JSONArray results = r.optJSONArray("results");
                        for (int i = 0; results != null && i < results.length(); i++) {
                            Title t = parse(results.optJSONObject(i), tv);
                            if (t.backdrop != null && ids.add(t.id)) {
                                found.add(t);
                            }
                        }
                        if (r.optInt("total_pages") <= page) {
                            break;
                        }
                    }
                }
                rank(found, trending, 12);
            } catch (IOException e) {
                failed = e.getMessage();
            }
            final String error = failed;
            long wait = Math.max(0, 1600 - (System.currentTimeMillis() - started));
            handler.postDelayed(() -> {
                if (gen != searchGen || screen != LOADING) {
                    return;
                }
                if (error != null) {
                    message("Nu am putut ajunge la TMDB", "Verifică internetul și încearcă din nou.", this::search);
                } else if (found.isEmpty()) {
                    message("N-am găsit nimic pe platformele voastre",
                            "Încearcă alt chef sau alege și alte abonamente.", () -> showStep(3, false));
                } else {
                    forYou.clear();
                    forYou.addAll(found);
                    popular.clear();
                    tab = 0;
                    showResults();
                }
            }, wait);
        });
    }

    /**
     * The TMDB query for the answers. Animation only for children or when asked for; no
     * documentaries, reality or talk shows unless asked for; only titles enough people rated.
     */
    private String discover(int relax, int page) {
        boolean tv = "tv".equals(kind);
        StringBuilder q = new StringBuilder("language=ro-RO&watch_region=RO&include_adult=false"
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&vote_average.gte=6.2");
        q.append("&page=").append(page);
        q.append("&vote_count.gte=").append(tv ? 150 : 300);
        q.append("&with_watch_providers=").append(Uri.encode(TextUtils.join("|", subs)));
        boolean kids = "kids".equals(who);
        String moodName = mood != null ? mood[0] : "";
        List<String> with = new ArrayList<>();
        if (kids) {
            with.add(tv ? "10762|16" : "16|10751");
        }
        String genres = mood == null ? "" : tv ? mood[2] : mood[1];
        if (relax < 2 && genres != null && !genres.isEmpty()) {
            with.add(genres);
        }
        if (!with.isEmpty()) {
            q.append("&with_genres=").append(Uri.encode(TextUtils.join(",", with)));
        }
        List<String> without = new ArrayList<>();
        if (!kids && !"Animație".equals(moodName)) {
            without.add("16");
        }
        if (!kids && !"family".equals(who)) {
            without.add(tv ? "10762" : "10751");
        }
        if (!"Povești adevărate".equals(moodName)) {
            without.add("99");
        }
        if (kids || "family".equals(who)) {
            without.add("27");
        }
        if (tv) {
            Collections.addAll(without, "10763", "10764", "10766", "10767");
        } else {
            without.add("10770");
        }
        q.append("&without_genres=").append(Uri.encode(TextUtils.join(",", without)));
        if (!tv && (kids || "family".equals(who))) {
            q.append("&certification_country=US&certification.lte=").append(kids ? "PG" : "PG-13");
        }
        if (relax < 1) {
            int year = Calendar.getInstance().get(Calendar.YEAR);
            if ("short".equals(span)) {
                q.append("&with_runtime.lte=95");
            } else if ("two".equals(span)) {
                q.append("&with_runtime.lte=135");
            } else if ("new".equals(span)) {
                q.append("&first_air_date.gte=").append(year - 2).append("-01-01");
            } else if ("classic".equals(span)) {
                q.append("&first_air_date.lte=2012-12-31");
            }
        }
        return q.toString();
    }

    /** What is trending this week first, then the rest; titles suggested lately go last. */
    private void rank(List<Title> found, Set<Integer> trending, int keep) {
        List<String> seen = new ArrayList<>();
        Collections.addAll(seen, prefs.getString(Profiles.scoped(prefs, KEY_SEEN), "").split(","));
        List<Title> hot = new ArrayList<>();
        List<Title> rest = new ArrayList<>();
        List<Title> old = new ArrayList<>();
        for (Title t : found) {
            String key = (t.tv ? "t" : "m") + t.id;
            (seen.contains(key) ? old : trending.contains(t.id) ? hot : rest).add(t);
        }
        found.clear();
        found.addAll(hot);
        found.addAll(rest);
        found.addAll(old);
        while (found.size() > keep) {
            found.remove(found.size() - 1);
        }
        for (Title t : found) {
            String key = (t.tv ? "t" : "m") + t.id;
            seen.remove(key);
            seen.add(0, key);
        }
        while (seen.size() > 80) {
            seen.remove(seen.size() - 1);
        }
        prefs.edit().putString(Profiles.scoped(prefs, KEY_SEEN), TextUtils.join(",", seen)).apply();
    }

    /** What is popular now on your subscriptions, films and series mixed. */
    private void loadPopular() {
        final int gen = searchGen;
        net.execute(() -> {
            List<Title> films = new ArrayList<>();
            List<Title> series = new ArrayList<>();
            try {
                String common = "language=ro-RO&watch_region=RO&include_adult=false&with_watch_monetization_types=flatrate"
                        + "&sort_by=popularity.desc&with_watch_providers=" + Uri.encode(TextUtils.join("|", subs));
                JSONArray m = tmdb.get("/discover/movie", common + "&vote_count.gte=150&without_genres="
                        + Uri.encode("99,10770"), 6 * HOUR).optJSONArray("results");
                JSONArray s = tmdb.get("/discover/tv", common + "&vote_count.gte=80&without_genres="
                        + Uri.encode("10763,10764,10766,10767,99"), 6 * HOUR).optJSONArray("results");
                for (int i = 0; m != null && i < m.length() && films.size() < 7; i++) {
                    Title t = parse(m.optJSONObject(i), false);
                    if (t.backdrop != null) {
                        films.add(t);
                    }
                }
                for (int i = 0; s != null && i < s.length() && series.size() < 7; i++) {
                    Title t = parse(s.optJSONObject(i), true);
                    if (t.backdrop != null) {
                        series.add(t);
                    }
                }
            } catch (IOException ignored) {
                // Empty.
            }
            final List<Title> mixed = new ArrayList<>();
            for (int i = 0; i < Math.max(films.size(), series.size()); i++) {
                if (i < films.size()) {
                    mixed.add(films.get(i));
                }
                if (i < series.size()) {
                    mixed.add(series.get(i));
                }
            }
            handler.post(() -> {
                if (gen != searchGen) {
                    return;
                }
                popular.clear();
                popular.addAll(mixed);
                if (tab == 1 && (screen == RESULTS || screen == DETAIL)) {
                    fillRow();
                }
            });
        });
    }

    private static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) {
            return null;
        }
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }

    private static Title parse(JSONObject o, boolean tv) {
        Title t = new Title();
        t.id = o.optInt("id");
        t.tv = tv;
        t.name = o.optString(tv ? "name" : "title", "");
        t.original = o.optString(tv ? "original_name" : "original_title", "");
        t.overview = o.optString("overview", "");
        String date = o.optString(tv ? "first_air_date" : "release_date", "");
        t.year = date.length() >= 4 ? date.substring(0, 4) : "";
        t.poster = str(o, "poster_path");
        t.backdrop = str(o, "backdrop_path");
        t.vote = o.optDouble("vote_average", 0);
        return t;
    }

    /** The tvOS loading ring: a bright head with a fading tail going round, breathing softly. */
    private final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Matrix turn = new Matrix();
        private final ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        private final long born = SystemClock.uptimeMillis();
        private SweepGradient tail;

        Ring() {
            super(CinemaActivity.this);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0x26FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            head.setColor(Color.WHITE);
            clock.setDuration(1000);
            clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(null);
            clock.addUpdateListener(a -> invalidate());
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            clock.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            clock.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            tail = new SweepGradient(w / 2f, h / 2f, new int[]{0x00FFFFFF, 0x00FFFFFF, 0xFFFFFFFF},
                    new float[]{0f, 0.15f, 0.82f});
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (tail == null) {
                return;
            }
            float t = (SystemClock.uptimeMillis() - born) / 1000f;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float breathe = 1f + 0.05f * (float) Math.sin(t * 3.4f);
            float stroke = getWidth() * 0.09f;
            float r = (Math.min(cx, cy) - stroke) * breathe;
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            box.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawOval(box, track);
            float angle = (t * 400f) % 360f;
            turn.setRotate(angle, cx, cy);
            tail.setLocalMatrix(turn);
            arc.setShader(tail);
            canvas.drawArc(box, angle + 54f, 241f, false, arc);
            double a = Math.toRadians(angle + 295f);
            canvas.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, stroke / 2f, head);
        }
    }

    // ------------------------------------------------------------------ results

    private void showResults() {
        screen = RESULTS;
        titles = tab == 1 ? popular : forYou;
        swapStage(null, true, false);
        crumbs.removeAllViews();
        dots.animate().alpha(0f).setDuration(300).start();
        veil.animate().alpha(0f).setDuration(700).start();
        scrim.animate().alpha(1f).setDuration(700).start();
        credit.animate().alpha(0.45f).setDuration(700).start();
        badge.animate().alpha(1f).setDuration(400).start();
        tabBar.setVisibility(View.VISIBLE);
        tabBar.setAlpha(0f);
        tabBar.setTranslationY(-px(12));
        tabBar.animate().alpha(1f).translationY(0).setInterpolator(EASE).setStartDelay(200).setDuration(500).start();
        tabPill.moveTo(tabs.getChildAt(tab), false);
        paintTabs();
        hero.setVisibility(View.VISIBLE);
        hero.setAlpha(1f);
        hero.setTranslationY(0);
        heroOverview.setMaxLines(2);
        heroRent.setVisibility(View.GONE);
        rowHeading.setVisibility(View.VISIBLE);
        rowHeading.setAlpha(1f);
        rowScroll.setVisibility(View.VISIBLE);
        rowScroll.setAlpha(1f);
        rowScroll.setTranslationY(0);
        rowScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (popular.isEmpty()) {
            loadPopular();
        }
        fillRow();
        // tvOS starts on the play button of the first title.
        handler.post(() -> {
            View first = heroButtons.getChildAt(0);
            if (first != null) {
                first.requestFocus();
            }
        });
        tabs.getChildAt(0).setNextFocusLeftId(tabs.getChildAt(0).getId());
        tabs.getChildAt(TABS.length - 1).setNextFocusRightId(badge.getId());
        badge.setNextFocusLeftId(tabs.getChildAt(TABS.length - 1).getId());
        badge.setNextFocusDownId(heroButtons.getChildCount() > 0 ? heroButtons.getChildAt(0).getId() : View.NO_ID);
    }

    private void switchTab(int index) {
        if (index == tab || (screen != RESULTS && screen != DETAIL)) {
            return;
        }
        tab = index;
        if (screen == DETAIL) {
            closeDetail();
        }
        tabPill.moveTo(tabs.getChildAt(tab), tabsFocused());
        if (tab == 1 && popular.isEmpty()) {
            loadPopular();
        }
        fillRow();
    }

    private void fillRow() {
        titles = tab == 1 ? popular : forYou;
        rowHeading.setText(tab == 1 ? "Populare acum pe abonamentele voastre" : "Pentru voi, în seara asta");
        row.removeAllViews();
        rowScroll.scrollTo(0, 0);
        focused = -1;
        for (int i = 0; i < titles.size(); i++) {
            View cell = titleCard(i);
            View card = ((ViewGroup) cell).getChildAt(0);
            if (i == 0) {
                card.setNextFocusLeftId(card.getId());
            }
            if (i == titles.size() - 1) {
                card.setNextFocusRightId(card.getId());
            }
            row.addView(cell);
            cell.setAlpha(0f);
            cell.setTranslationY(px(40));
            cell.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2.2f))
                    .setStartDelay(100 + 50L * i).setDuration(520).start();
        }
        for (Title t : titles) {
            details(t);
        }
        if (!titles.isEmpty()) {
            focusTitle(0);
        }
    }

    private void hideResults() {
        tabBar.setVisibility(View.GONE);
        hero.setVisibility(View.GONE);
        rowHeading.setVisibility(View.GONE);
        rowScroll.setVisibility(View.GONE);
    }

    /** A title as a card shaped like the app tiles: its still, its logo on a soft shade. */
    private View titleCard(final int index) {
        final Title t = titles.get(index);
        final FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        final FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setOutlineProvider(round(px(12)));
        card.setClipToOutline(true);
        card.setBackgroundColor(0xFF1C1C1E);
        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, full());
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, px(56), Gravity.BOTTOM));
        final ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(px(110));
        logo.setMaxHeight(px(38));
        card.addView(logo, at(-2, -2, Gravity.BOTTOM | Gravity.START, px(10), 0, 0, px(9)));
        final TextView name = text(13, true);
        name.setText(t.name);
        name.setShadowLayer(px(4), 0, 0, 0x99000000);
        card.addView(name, at(-1, -2, Gravity.BOTTOM | Gravity.START, px(10), 0, px(10), px(10)));
        final View rim = new View(this);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(px(12));
        edge.setStroke(Math.max(1, px(1)), 0x4DFFFFFF);
        rim.setBackground(edge);
        rim.setAlpha(0f);
        card.addView(rim, full());
        final FocusShadow shadow = new FocusShadow(card);
        cell.setBackground(shadow);
        cell.addView(card, new FrameLayout.LayoutParams(px(168), px(100)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(168), px(100));
        lp.rightMargin = px(18);
        cell.setLayoutParams(lp);
        picture(t.backdrop, "w780", 360, false, image::setImageBitmap);
        final Runnable showLogo = () -> {
            Bitmap b = t.logo != null ? pictures.get("w300" + t.logo) : null;
            if (b != null) {
                logo.setImageBitmap(b);
                name.setVisibility(View.GONE);
            }
        };
        showLogo.run();
        t.onDetails.add(showLogo);
        final Spring lift = new Spring(0f, 0.002f, v -> {
            float k = 1f + 0.1f * v;
            card.setScaleX(k);
            card.setScaleY(k);
            rim.setAlpha(Math.max(0f, Math.min(1f, v)));
            shadow.setAmount(v);
            cell.invalidate();
        });
        lift.configure(0.36f, 0.72f);
        card.setOnFocusChangeListener((v, has) -> {
            lift.to(has ? 1f : 0f);
            if (has) {
                focusTitle(index);
            }
        });
        card.setOnClickListener(v -> {
            sounds.play("select");
            View play = heroButtons.getChildAt(0);
            if (play != null) {
                play.requestFocus();
            }
        });
        card.setNextFocusDownId(card.getId());
        return cell;
    }

    private final Runnable heroChange = () -> fillHero(true);
    private final Runnable backChange = () -> {
        final Title t = shownTitle();
        if (t != null && (screen == RESULTS || screen == DETAIL)) {
            picture(t.backdrop, "w1280", 1280, false, b -> {
                if (shownTitle() == t) {
                    showBack(b, t.backdrop, 650);
                }
            });
        }
    };

    private void focusTitle(int index) {
        if (index == focused) {
            return;
        }
        boolean first = focused < 0;
        focused = index;
        stopPreview(false);
        handler.removeCallbacks(heroChange);
        handler.removeCallbacks(backChange);
        if (first) {
            fillHero(false);
            backChange.run();
        } else {
            // Moving quickly along the row the words change a moment later, the picture later still.
            handler.postDelayed(heroChange, 120);
            handler.postDelayed(backChange, 360);
        }
        restartPreviewTimer();
        if (index + 1 < titles.size()) {
            picture(titles.get(index + 1).backdrop, "w1280", 1280, false, b -> { });
        }
    }

    private LinearLayout buildHero() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setClipChildren(false);
        heroLogo = new ImageView(this);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setMaxHeight(px(92));
        heroLogo.setMaxWidth(px(340));
        heroLogo.setVisibility(View.GONE);
        h.addView(heroLogo, new LinearLayout.LayoutParams(-2, -2));
        heroTitle = text(34, true);
        heroTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heroTitle.setSingleLine(false);
        heroTitle.setMaxLines(2);
        heroTitle.setLineSpacing(0, 0.95f);
        h.addView(heroTitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        heroBadge = new ImageView(this);
        heroBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBadge.setOutlineProvider(round(px(5)));
        heroBadge.setClipToOutline(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(20), px(20));
        blp.rightMargin = px(8);
        meta.addView(heroBadge, blp);
        heroMeta = text(13.5f, false);
        heroMeta.setAlpha(0.85f);
        meta.addView(heroMeta, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, px(22));
        mlp.topMargin = px(10);
        h.addView(meta, mlp);
        heroOverview = text(14.5f, false);
        heroOverview.setSingleLine(false);
        heroOverview.setMaxLines(2);
        heroOverview.setLineSpacing(px(3), 1f);
        heroOverview.setAlpha(0.92f);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = px(10);
        h.addView(heroOverview, olp);
        heroRent = text(12, false);
        heroRent.setAlpha(0.6f);
        heroRent.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = px(8);
        h.addView(heroRent, rlp);
        heroButtons = new LinearLayout(this);
        heroButtons.setOrientation(LinearLayout.HORIZONTAL);
        heroButtons.setClipChildren(false);
        heroButtons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(-2, px(48));
        bblp.topMargin = px(14);
        h.addView(heroButtons, bblp);
        return h;
    }

    private Title shownTitle() {
        if (screen == DETAIL && open != null) {
            return open;
        }
        return focused >= 0 && focused < titles.size() ? titles.get(focused) : null;
    }

    /** The focused title's words; with `fade` they cross-fade quickly instead of jumping. */
    private void fillHero(boolean fade) {
        final Title t = shownTitle();
        if (t == null) {
            return;
        }
        final View[] parts = {heroLogo, heroTitle, (View) heroMeta.getParent(), heroOverview};
        if (!fade) {
            for (View v : parts) {
                v.animate().cancel();
                v.setAlpha(v == heroOverview ? 0.92f : 1f);
                v.setTranslationY(0);
            }
            setHero(t);
            return;
        }
        for (View v : parts) {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(90).start();
        }
        handler.postDelayed(() -> {
            Title now = shownTitle();
            if (now == null) {
                return;
            }
            setHero(now);
            for (View v : parts) {
                float to = v == heroOverview ? 0.92f : 1f;
                v.setTranslationY(px(5));
                v.animate().alpha(to).translationY(0).setInterpolator(EASE).setDuration(280).start();
            }
        }, 95);
    }

    private void setHero(Title t) {
        Bitmap logo = t.logo != null ? pictures.get("w500" + t.logo) : null;
        heroLogo.setImageBitmap(logo);
        heroLogo.setVisibility(logo != null ? View.VISIBLE : View.GONE);
        heroTitle.setVisibility(logo != null ? View.GONE : View.VISIBLE);
        heroTitle.setText(t.name);
        List<String> meta = new ArrayList<>();
        meta.add(t.tv ? "Serial" : "Film");
        if (!t.genres.isEmpty()) {
            meta.add(t.genres);
        }
        if (!t.year.isEmpty()) {
            meta.add(t.year);
        }
        if (!t.length.isEmpty()) {
            meta.add(t.length);
        }
        if (t.vote > 0) {
            meta.add("★ " + String.format(Locale.ROOT, "%.1f", t.vote).replace('.', ','));
        }
        if (!t.cert.isEmpty()) {
            meta.add(t.cert);
        }
        List<Provider> on = subscribed(t);
        List<String> names = new ArrayList<>();
        for (Provider p : on) {
            names.add(p.name);
        }
        String where = names.isEmpty() ? "" : "  ·  Inclus pe " + joinAnd(names);
        heroMeta.setText(TextUtils.join("  ·  ", meta) + where);
        Provider main = on.isEmpty() ? null : on.get(0);
        String path = main != null ? providerLogos.get(main.id) : null;
        Bitmap b = path != null ? pictures.get("w92" + path) : null;
        heroBadge.setImageBitmap(b);
        heroBadge.setVisibility(b != null ? View.VISIBLE : View.GONE);
        if (b == null && path != null) {
            picture(path, "w92", 92, false, x -> {
                if (shownTitle() == t) {
                    setHero(t);
                }
            });
        }
        heroOverview.setText(t.overview.isEmpty() ? " " : t.overview);
        if (screen == DETAIL && !t.rent.isEmpty()) {
            heroRent.setText("Și de închiriat: " + TextUtils.join(", ", t.rent));
            heroRent.setVisibility(View.VISIBLE);
        } else {
            heroRent.setVisibility(View.GONE);
        }
        heroButtons(t);
    }

    /** The title's platforms you subscribe to, first; the others that have it after. */
    private List<Provider> subscribed(Title t) {
        List<Provider> mine = new ArrayList<>();
        for (Provider p : t.included) {
            if (subs.contains(p.id)) {
                mine.add(p);
            }
        }
        if (mine.isEmpty()) {
            mine.addAll(t.included);
        }
        return mine;
    }

    private static String joinAnd(List<String> names) {
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        return TextUtils.join(", ", names.subList(0, names.size() - 1)) + " și " + names.get(names.size() - 1);
    }

    // ------------------------------------------------------------------ the buttons

    /**
     * Like the Apple TV app: a glass "Watch on …" capsule; beside it the other subscriptions
     * that have the title (their logos), then round glass buttons: trailer, details, next.
     */
    private String buttonsShown;

    private void heroButtons(final Title t) {
        final List<Provider> on = subscribed(t);
        String signature = t.id + ":" + t.detailed + ":" + on + ":" + t.trailer + ":" + screen + ":" + focused;
        if (signature.equals(buttonsShown) && heroButtons.getChildCount() > 0) {
            return;
        }
        buttonsShown = signature;
        View had = getCurrentFocus();
        int focusIndex = had != null && had.getParent() == heroButtons ? heroButtons.indexOfChild(had) : -1;
        if (focusIndex >= 0) {
            // Removing the focused button would send the focus somewhere (a tab, which then
            // switches): park it on the hero itself for the moment.
            hero.setFocusable(true);
            hero.requestFocus();
        }
        heroButtons.removeAllViews();
        List<View> buttons = new ArrayList<>();
        final Provider main = on.isEmpty() ? null : on.get(0);
        buttons.add(capsule(main != null ? "Vezi pe " + main.name : t.detailed ? "Indisponibil acum" : "Se încarcă…", true,
                () -> {
                    if (main != null) {
                        watch(t, main);
                    }
                }));
        for (int i = 1; i < on.size(); i++) {
            final Provider other = on.get(i);
            buttons.add(logoButton(other, () -> watch(t, other)));
        }
        if (t.trailer != null) {
            buttons.add(roundButton("trailer", () -> trailer(t)));
        }
        buttons.add(roundButton(screen == DETAIL ? "less" : "info", () -> {
            if (screen == DETAIL) {
                closeDetail();
            } else {
                showDetail(t);
            }
        }));
        buttons.add(roundButton("next", this::nextTitle));
        View card = cardOf(focused);
        for (View b : buttons) {
            b.setId(View.generateViewId());
        }
        buttons.get(0).setNextFocusLeftId(buttons.get(0).getId());
        buttons.get(buttons.size() - 1).setNextFocusRightId(buttons.get(buttons.size() - 1).getId());
        for (View b : buttons) {
            b.setNextFocusUpId(tabs.getChildAt(tab).getId());
            b.setNextFocusDownId(card != null && screen != DETAIL ? card.getId() : b.getId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(b instanceof TextView ? -2 : px(44), px(44));
            lp.rightMargin = px(12);
            heroButtons.addView(b, lp);
        }
        if (focusIndex >= 0) {
            View target = heroButtons.getChildAt(Math.min(focusIndex, heroButtons.getChildCount() - 1));
            target.requestFocus();
            hero.setFocusable(false);
        }
        // Up from the cards comes back to the watch button.
        for (int i = 0; i < row.getChildCount(); i++) {
            ((ViewGroup) row.getChildAt(i)).getChildAt(0).setNextFocusUpId(heroButtons.getChildAt(0).getId());
        }
    }

    private View cardOf(int index) {
        if (index < 0 || index >= row.getChildCount()) {
            return null;
        }
        return ((ViewGroup) row.getChildAt(index)).getChildAt(0);
    }

    private TextView capsule(String label, boolean playSign, final Runnable action) {
        final TextView b = text(15, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(playSign ? 18 : 24), 0, px(24), 0);
        b.setCompoundDrawablePadding(px(8));
        final Drawable playWhite = playSign
                ? new android.graphics.drawable.BitmapDrawable(getResources(), glyph("play", px(16), Color.WHITE)) : null;
        final Drawable playDark = playSign
                ? new android.graphics.drawable.BitmapDrawable(getResources(), glyph("play", px(16), 0xFF111111)) : null;
        b.setCompoundDrawablesWithIntrinsicBounds(playWhite, null, null, null);
        final GlassDrawable g = glass(b, px(22), 0x33000000);
        b.setFocusable(true);
        final Spring lift = springLift(b);
        b.setOnFocusChangeListener((v, has) -> {
            g.setTint(has ? 0xF2FFFFFF : 0x33000000);
            b.setTextColor(has ? 0xFF111111 : Color.WHITE);
            b.setCompoundDrawablesWithIntrinsicBounds(has ? playDark : playWhite, null, null, null);
            lift.to(has ? 1f : 0f);
            restartPreviewTimer();
        });
        b.setOnClickListener(v -> {
            sounds.play("select");
            action.run();
        });
        return b;
    }

    private View roundButton(String kind, final Runnable action) {
        final FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        final GlassDrawable g = glass(b, px(22), 0x33000000);
        final ImageView icon = new ImageView(this);
        final Bitmap light = glyph(kind, px(20), Color.WHITE);
        final Bitmap dark = glyph(kind, px(20), 0xFF111111);
        icon.setImageBitmap(light);
        b.addView(icon, new FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER));
        final Spring lift = springLift(b);
        b.setOnFocusChangeListener((v, has) -> {
            g.setTint(has ? 0xF2FFFFFF : 0x33000000);
            icon.setImageBitmap(has ? dark : light);
            lift.to(has ? 1f : 0f);
            restartPreviewTimer();
        });
        b.setOnClickListener(v -> {
            sounds.play("select");
            action.run();
        });
        return b;
    }

    /** Another subscription that has the title: its logo in a glass circle. */
    private View logoButton(Provider p, final Runnable action) {
        final FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        final GlassDrawable g = glass(b, px(22), 0x33000000);
        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setOutlineProvider(round(px(14)));
        icon.setClipToOutline(true);
        b.addView(icon, new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER));
        String path = providerLogos.get(p.id);
        if (path != null) {
            picture(path, "w92", 92, false, icon::setImageBitmap);
        }
        b.setContentDescription("Vezi pe " + p.name);
        final Spring lift = springLift(b);
        b.setOnFocusChangeListener((v, has) -> {
            g.setTint(has ? 0xF2FFFFFF : 0x33000000);
            lift.to(has ? 1f : 0f);
            restartPreviewTimer();
        });
        b.setOnClickListener(v -> {
            sounds.play("select");
            action.run();
        });
        return b;
    }

    private Spring springLift(final View v) {
        Spring lift = new Spring(0f, 0.002f, x -> {
            float k = 1f + 0.1f * x;
            v.setScaleX(k);
            v.setScaleY(k);
        });
        lift.configure(0.34f, 0.7f);
        return lift;
    }

    private void nextTitle() {
        if (titles.isEmpty()) {
            return;
        }
        int next = (Math.max(0, focused) + 1) % titles.size();
        if (screen == DETAIL) {
            open = titles.get(next);
            details(open);
            slideIndex = 0;
        }
        View card = cardOf(next);
        if (card != null) {
            // The row follows along without taking the focus off the button.
            rowScroll.smoothScrollTo(Math.max(0, ((View) card.getParent()).getLeft() - px(64)), 0);
        }
        focusTitle(next);
    }

    // ------------------------------------------------------------------ details

    private void details(final Title t) {
        if (t.detailed || t.loading) {
            return;
        }
        t.loading = true;
        net.execute(() -> {
            try {
                String path = "/" + (t.tv ? "tv" : "movie") + "/" + t.id;
                JSONObject d = tmdb.get(path, "language=ro-RO&append_to_response="
                        + Uri.encode("videos,images,watch/providers," + (t.tv ? "content_ratings" : "release_dates"))
                        + "&include_image_language=" + Uri.encode("ro,en,null")
                        + "&include_video_language=" + Uri.encode("ro,en,null"), 24 * HOUR);
                fill(this, t, d);
                if (t.overview.isEmpty()) {
                    t.overview = tmdb.get(path, "language=en-US", 7 * 24 * HOUR).optString("overview", "");
                }
                if (t.logo != null) {
                    Bitmap big = tmdb.image(t.logo, "w500", 500);
                    if (big != null) {
                        pictures.put("w500" + t.logo, big);
                        pictures.put("w300" + t.logo, Bitmap.createScaledBitmap(big, Math.max(1, big.getWidth() * 3 / 5),
                                Math.max(1, big.getHeight() * 3 / 5), true));
                    }
                }
            } catch (IOException ignored) {
                // Shown with what the list had.
            }
            t.detailed = true;
            handler.post(() -> {
                for (Runnable r : t.onDetails) {
                    r.run();
                }
                if (shownTitle() == t) {
                    setHero(t);
                }
            });
        });
    }

    /** A title's details from TMDB's answer: genres, length, rating, trailer, logo, stills, where to watch. */
    static void fill(android.content.Context c, Title t, JSONObject d) {
        JSONArray genres = d.optJSONArray("genres");
        List<String> g = new ArrayList<>();
        for (int i = 0; genres != null && i < genres.length() && g.size() < 2; i++) {
            g.add(genres.optJSONObject(i).optString("name"));
        }
        t.genres = TextUtils.join(", ", g);
        if (t.tv) {
            int seasons = d.optInt("number_of_seasons");
            t.length = seasons <= 0 ? "" : seasons == 1 ? "1 sezon" : seasons + (seasons >= 20 ? " de sezoane" : " sezoane");
        } else {
            int minutes = d.optInt("runtime");
            t.length = minutes <= 0 ? "" : minutes < 60 ? minutes + " min"
                    : (minutes / 60) + " h" + (minutes % 60 > 0 ? " " + (minutes % 60) + " min" : "");
        }
        String overview = d.optString("overview", "");
        if (!overview.isEmpty()) {
            t.overview = overview;
        }
        t.cert = certification(t.tv, d);
        JSONObject videos = d.optJSONObject("videos");
        JSONArray v = videos != null ? videos.optJSONArray("results") : null;
        int best = -1;
        for (int i = 0; v != null && i < v.length(); i++) {
            JSONObject o = v.optJSONObject(i);
            String site = o.optString("site");
            if (!"YouTube".equals(site) && !"Vimeo".equals(site)) {
                continue;
            }
            int score = ("Trailer".equals(o.optString("type")) ? 4 : "Teaser".equals(o.optString("type")) ? 2 : 0)
                    + ("ro".equals(o.optString("iso_639_1")) ? 2 : 0) + (o.optBoolean("official") ? 1 : 0);
            if (score >= 2 && score > best) {
                best = score;
                t.trailer = o.optString("key");
                t.trailerSite = site;
            }
        }
        JSONObject images = d.optJSONObject("images");
        if (images != null) {
            JSONArray logos = images.optJSONArray("logos");
            int rank = -1;
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject o = logos.optJSONObject(i);
                String lang = o.optString("iso_639_1", "");
                int r = "ro".equals(lang) ? 3 : "en".equals(lang) ? 2 : 1;
                double ratio = o.optDouble("aspect_ratio", 2);
                if (ratio > 1.2 && ratio < 7 && r > rank && o.optString("file_path", "").endsWith(".png")) {
                    rank = r;
                    t.logo = o.optString("file_path");
                }
            }
            JSONArray backdrops = images.optJSONArray("backdrops");
            t.stills.clear();
            if (t.backdrop != null) {
                t.stills.add(t.backdrop);
            }
            for (int i = 0; backdrops != null && i < backdrops.length() && t.stills.size() < 6; i++) {
                JSONObject o = backdrops.optJSONObject(i);
                String s = o.optString("file_path");
                if (o.isNull("iso_639_1") && !t.stills.contains(s)) {
                    t.stills.add(s);
                }
            }
        }
        JSONObject providers = d.optJSONObject("watch/providers");
        JSONObject ro = providers != null && providers.optJSONObject("results") != null
                ? providers.optJSONObject("results").optJSONObject("RO") : null;
        t.included.clear();
        t.rent.clear();
        if (ro != null) {
            JSONArray flat = ro.optJSONArray("flatrate");
            for (int i = 0; flat != null && i < flat.length(); i++) {
                int id = flat.optJSONObject(i).optInt("provider_id");
                for (Provider p : PROVIDERS) {
                    if (p.id == id && installed(c, p.pkg) && !t.included.contains(p)) {
                        t.included.add(p);
                    }
                }
            }
            Set<String> rent = new LinkedHashSet<>();
            for (String kindOf : new String[]{"rent", "buy"}) {
                JSONArray a = ro.optJSONArray(kindOf);
                for (int i = 0; a != null && i < a.length(); i++) {
                    rent.add(a.optJSONObject(i).optString("provider_name"));
                }
            }
            t.rent.addAll(rent);
            while (t.rent.size() > 3) {
                t.rent.remove(t.rent.size() - 1);
            }
        }
    }

    /** The age rating: Romania's own when TMDB has it, else the American one. */
    private static String certification(boolean tv, JSONObject d) {
        String us = "";
        if (tv) {
            JSONObject c = d.optJSONObject("content_ratings");
            JSONArray r = c != null ? c.optJSONArray("results") : null;
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.optJSONObject(i);
                String rating = o.optString("rating", "");
                if ("RO".equals(o.optString("iso_3166_1")) && !rating.isEmpty()) {
                    return rating;
                }
                if ("US".equals(o.optString("iso_3166_1"))) {
                    us = rating;
                }
            }
            return us;
        }
        JSONObject c = d.optJSONObject("release_dates");
        JSONArray r = c != null ? c.optJSONArray("results") : null;
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            JSONArray dates = o.optJSONArray("release_dates");
            String found = "";
            for (int j = 0; dates != null && j < dates.length(); j++) {
                String cert = dates.optJSONObject(j).optString("certification", "");
                if (!cert.isEmpty()) {
                    found = cert;
                    break;
                }
            }
            if ("RO".equals(o.optString("iso_3166_1")) && !found.isEmpty()) {
                return found;
            }
            if ("US".equals(o.optString("iso_3166_1"))) {
                us = found;
            }
        }
        return us;
    }

    // ------------------------------------------------------------------ the title's page

    private void showDetail(final Title t) {
        screen = DETAIL;
        open = t;
        details(t);
        rowScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rowScroll.animate().cancel();
        rowScroll.animate().alpha(0f).translationY(px(80)).setInterpolator(EASE).setDuration(420).start();
        rowHeading.animate().alpha(0f).setDuration(250).start();
        heroOverview.setMaxLines(7);
        hero.animate().translationY(px(36)).setInterpolator(EASE).setDuration(520).start();
        setHero(t);
        View info = heroButtons.getChildAt(heroButtons.getChildCount() - 2);
        if (info != null) {
            info.requestFocus();
        }
        slideIndex = 0;
        handler.removeCallbacks(slideshow);
        handler.postDelayed(slideshow, 6000);
        net.execute(() -> {
            Map<String, String> ids = tmdb.streamingIds(t.tv, t.id);
            handler.post(() -> t.ids = ids);
        });
    }

    private int slideIndex;

    /** On the title's page its stills drift by, one every few seconds (until the trailer). */
    private final Runnable slideshow = new Runnable() {
        @Override
        public void run() {
            if (screen != DETAIL || open == null || open.stills.size() < 2 || previewing) {
                return;
            }
            final Title t = open;
            slideIndex = (slideIndex + 1) % t.stills.size();
            final String path = t.stills.get(slideIndex);
            picture(path, "w1280", 1280, false, b -> {
                if (screen == DETAIL && open == t && !previewing) {
                    showBack(b, path, 1600);
                }
            });
            handler.postDelayed(this, 8000);
        }
    };

    private void closeDetail() {
        if (screen != DETAIL) {
            return;
        }
        screen = RESULTS;
        handler.removeCallbacks(slideshow);
        final Title t = open;
        open = null;
        heroRent.setVisibility(View.GONE);
        heroOverview.setMaxLines(2);
        hero.animate().translationY(0).setInterpolator(EASE).setDuration(480).start();
        rowScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        rowScroll.animate().cancel();
        rowScroll.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2.2f))
                .setDuration(480).start();
        rowHeading.animate().alpha(1f).setDuration(300).start();
        int index = Math.max(0, titles.indexOf(t));
        focused = -1;
        focusTitle(index);
        View info = heroButtons.getChildAt(heroButtons.getChildCount() - 2);
        if (info != null) {
            info.requestFocus();
        }
    }

    // ------------------------------------------------------------------ the trailer behind

    private boolean previewing;
    private Title previewTitle;
    private final Runnable previewStart = this::startPreview;

    private void restartPreviewTimer() {
        if (screen == RESULTS || screen == DETAIL) {
            handler.removeCallbacks(previewStart);
            if (!previewing) {
                handler.postDelayed(previewStart, 5000);
            }
        }
    }

    /**
     * After a few seconds on a title the still turns into its trailer, as in the Apple TV app:
     * YouTube's (or Vimeo's) own player in a frameless web view, identified as this app the way
     * YouTube asks embedding apps to; it fades in only once the video really plays.
     */
    private void startPreview() {
        final Title t = shownTitle();
        if (t == null || t.trailer == null || t.trailerFailed || exitAsk != null
                || (screen != RESULTS && screen != DETAIL)) {
            return;
        }
        if (video == null) {
            try {
                video = new android.webkit.WebView(this);
            } catch (RuntimeException e) {
                return;     // no WebView on this TV
            }
            video.setBackgroundColor(Color.BLACK);
            video.setFocusable(false);
            video.setFocusableInTouchMode(false);
            android.webkit.WebSettings s = video.getSettings();
            s.setJavaScriptEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setDomStorageEnabled(true);
            video.setWebChromeClient(new android.webkit.WebChromeClient());
            video.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void state(final String value) {
                    handler.post(() -> onVideoState(value));
                }
            }, "tv");
            // Under the pictures, never faded itself: a web view drawn with any transparency
            // crashes this TV's graphics driver, so the picture above fades to show it instead.
            // Slightly larger than the screen: the player's corners and bars stay out of sight.
            video.setScaleX(1.18f);
            video.setScaleY(1.18f);
            root.addView(video, 0, full());
        }
        previewTitle = t;
        previewing = false;
        String origin = "https://" + getPackageName();
        String html;
        if ("Vimeo".equals(t.trailerSite)) {
            html = "<html><body style='margin:0;background:#000;overflow:hidden'>"
                    + "<iframe src='https://player.vimeo.com/video/" + t.trailer + "?background=1&autoplay=1&muted=1'"
                    + " style='position:fixed;left:0;top:0;width:100vw;height:100vh;border:0' allow='autoplay'"
                    + " onload='setTimeout(function(){tv.state(\"1\")},2500)'></iframe></body></html>";
        } else {
            html = "<html><head><meta name='referrer' content='strict-origin-when-cross-origin'>"
                    + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                    + "#p{position:fixed;left:0;top:0;width:100vw;height:100vh}</style></head><body><div id='p'></div>"
                    + "<script src='https://www.youtube.com/iframe_api'></script><script>"
                    + "var p;function onYouTubeIframeAPIReady(){p=new YT.Player('p',{width:'100%',height:'100%',"
                    + "videoId:'" + t.trailer + "',playerVars:{autoplay:1,mute:1,controls:0,rel:0,playsinline:1,"
                    + "iv_load_policy:3,fs:0,disablekb:1,origin:'" + origin + "'},"
                    + "events:{onReady:function(e){e.target.mute();e.target.playVideo();},"
                    + "onStateChange:function(e){tv.state(''+e.data);},onError:function(e){tv.state('error'+e.data);}}});}"
                    + "function vol(v){if(p&&p.unMute){p.unMute();p.setVolume(v);}}"
                    + "</script></body></html>";
        }
        video.setVisibility(View.VISIBLE);
        video.onResume();
        video.loadDataWithBaseURL(origin + "/", html, "text/html", "utf-8", null);
        log("preview " + t.trailerSite + " " + t.trailer + " for " + t.name);
        handler.removeCallbacks(previewGiveUp);
        handler.postDelayed(previewGiveUp, 12000);
    }

    private final Runnable previewGiveUp = () -> {
        if (!previewing && previewTitle != null) {
            log("preview gave up for " + previewTitle.name);
            previewTitle.trailerFailed = true;
            stopPreview(false);
        }
    };

    private void onVideoState(String state) {
        if (video == null || previewTitle == null || previewTitle != shownTitle()) {
            return;
        }
        log("preview state " + state);
        if (state.startsWith("error")) {
            previewTitle.trailerFailed = true;
            stopPreview(false);
            return;
        }
        if ("1".equals(state) && !previewing) {
            previewing = true;
            handler.removeCallbacks(previewGiveUp);
            // A moment for the player's own title to go, then the picture becomes the trailer.
            for (ImageView b : back) {
                b.animate().cancel();
                b.animate().alpha(0f).setStartDelay(1200).setDuration(900).start();
            }
            final int[] level = {0};
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!previewing || video == null) {
                        return;
                    }
                    level[0] = Math.min(45, level[0] + 3);
                    video.evaluateJavascript("vol(" + level[0] + ")", null);
                    if (level[0] < 45) {
                        handler.postDelayed(this, 120);
                    }
                }
            }, 1400);
        } else if ("0".equals(state) && previewing) {
            stopPreview(false);
        }
    }

    private void stopPreview(boolean now) {
        handler.removeCallbacks(previewStart);
        handler.removeCallbacks(previewGiveUp);
        boolean wasShowing = previewing;
        previewing = false;
        previewTitle = null;
        if (video != null) {
            final android.webkit.WebView v = video;
            // The picture comes back over the trailer, then the trailer stops.
            ImageView front = topShowing ? back[1] : back[0];
            for (ImageView b : back) {
                b.animate().cancel();
            }
            if (now || !wasShowing) {
                front.setAlpha(1f);
                v.loadUrl("about:blank");
                v.setVisibility(View.INVISIBLE);
                if (now) {
                    v.onPause();
                }
            } else {
                front.animate().alpha(1f).setStartDelay(0).setDuration(400).withEndAction(() -> {
                    if (!previewing) {
                        v.loadUrl("about:blank");
                        v.setVisibility(View.INVISIBLE);
                    }
                }).start();
            }
        }
    }

    // ------------------------------------------------------------------ opening

    private static final Pattern NETFLIX_ID = Pattern.compile("title/(\\d+)");

    private void watch(Title t, Provider p) {
        stopPreview(true);
        leftForApp = true;
        log("watch " + t.name + " on " + p.name);
        if (!open(this, tmdb, t, p)) {
            leftForApp = false;
        }
    }

    /**
     * Opens the title in the app: straight on its page where the app's id for it is known
     * (Wikidata, Netflix's Top 10), else the app's search for it, else just the app. If the app
     * first asks for the profile, TV Home sends the title again afterwards.
     */
    static boolean open(android.app.Activity a, Tmdb tmdb, Title t, Provider p) {
        Map<String, String> ids = t.ids != null ? t.ids : idsNow(tmdb, t);
        String query = Uri.encode(!t.original.isEmpty() ? t.original : t.name);
        String address = null;
        switch (p.id) {
            case 8: {
                String id = ids.get("netflix");
                if (id == null) {
                    id = netflixTopId(a, t);
                }
                address = id != null ? "https://www.netflix.com/title/" + id : "nflx://www.netflix.com/search?q=" + query;
                break;
            }
            case 337: {
                String id = ids.get("disney");
                address = id != null ? "https://www.disneyplus.com/" + (t.tv ? "series" : "movies") + "/x/" + id
                        : "https://www.disneyplus.com/search?q=" + query;
                break;
            }
            case 1899: {
                String id = ids.get("hbo");
                address = id != null ? "https://play.hbomax.com/" + id : "https://play.max.com/search?q=" + query;
                break;
            }
            case 119: {
                String id = ids.get("prime");
                address = id != null ? "https://watch.amazon.com/detail?asin=" + id : null;
                break;
            }
            default:
                break;
        }
        Intent intent = address != null ? new Intent(Intent.ACTION_VIEW, Uri.parse(address)).setPackage(p.pkg) : null;
        if (intent != null && startApp(a, intent)) {
            SourcesService.titleOpened(a, p.pkg, intent);
            return true;
        }
        Intent app = a.getPackageManager().getLeanbackLaunchIntentForPackage(p.pkg);
        if (app == null) {
            app = a.getPackageManager().getLaunchIntentForPackage(p.pkg);
        }
        return app != null && startApp(a, app);
    }

    private static boolean startApp(android.app.Activity a, Intent intent) {
        try {
            a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The app ids when the title's page was never opened: asked now (a moment at most). */
    private static Map<String, String> idsNow(final Tmdb tmdb, final Title t) {
        final List<Map<String, String>> out = new ArrayList<>();
        Thread th = new Thread(() -> {
            Map<String, String> ids = tmdb.streamingIds(t.tv, t.id);
            synchronized (out) {
                out.add(ids);
            }
        });
        th.start();
        try {
            th.join(2500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (out) {
            Map<String, String> ids = out.isEmpty() ? new HashMap<String, String>() : out.get(0);
            t.ids = ids;
            return ids;
        }
    }

    /** The Netflix id from the Top 10 lists TV Home already reads, matched by title. */
    private static String netflixTopId(android.content.Context c, Title t) {
        String a = norm(t.name);
        String b = norm(t.original);
        for (Previews.Item it : NetflixTop.items(c, null)) {
            String n = norm(it.title);
            if (!n.isEmpty() && (n.equals(a) || n.equals(b)) && it.intentUri != null) {
                Matcher m = NETFLIX_ID.matcher(it.intentUri);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        String n = java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]", "");
    }

    private void trailer(Title t) {
        stopPreview(true);
        String address = "Vimeo".equals(t.trailerSite) ? "https://vimeo.com/" + t.trailer
                : "https://www.youtube.com/watch?v=" + t.trailer;
        Intent yt = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
        if (!"Vimeo".equals(t.trailerSite)) {
            yt.setPackage("com.google.android.youtube.tv");
        }
        if (!start(yt)) {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        }
    }

    private boolean start(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            leftForApp = true;
            startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            leftForApp = false;
            return false;
        }
    }

    // ------------------------------------------------------------------ messages

    private void message(String title, String body, final Runnable retry) {
        screen = MESSAGE;
        hideResults();
        crumbs.removeAllViews();
        scrim.animate().alpha(0f).setDuration(300).start();
        veil.animate().alpha(1f).setDuration(300).start();
        dots.animate().alpha(0f).setDuration(300).start();
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER_HORIZONTAL);
        group.setClipChildren(false);
        CinemaMark.Spin mark = new CinemaMark.Spin(this);
        group.addView(mark, new LinearLayout.LayoutParams(px(44), px(44)));
        TextView head = text(24, true);
        head.setText(title);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = px(16);
        group.addView(head, hlp);
        TextView sub = text(14, false);
        sub.setSingleLine(false);
        sub.setGravity(Gravity.CENTER);
        sub.setAlpha(0.7f);
        sub.setLineSpacing(px(3), 1f);
        sub.setText(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(10);
        group.addView(sub, slp);
        if (retry != null) {
            TextView again = capsule("Încearcă din nou", false, retry);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, px(44));
            alp.topMargin = px(26);
            group.addView(again, alp);
            again.post(again::requestFocus);
        }
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        holder.addView(group, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        swapStage(holder, true, false);
        curtain.animate().alpha(0f).setDuration(400).start();
        badge.animate().alpha(1f).setDuration(400).start();
    }

    // ------------------------------------------------------------------ asking before leaving

    /** Home in Cinema: a Liquid Glass question, "Leave Cinema mode?", Leave or Stay. */
    private void askExit() {
        if (exitAsk != null || screen == EXITING || isFinishing()) {
            return;
        }
        stopPreview(false);
        beforeAsk = getCurrentFocus();
        exitAsk = new FrameLayout(this);
        exitAsk.setBackgroundColor(0x99000000);
        exitAsk.setClipChildren(false);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        card.setPadding(px(34), px(26), px(34), px(26));
        glass(card, px(30), 0x4D1C1C1E);
        CinemaMark.Spin mark = new CinemaMark.Spin(this);
        card.addView(mark, new LinearLayout.LayoutParams(px(40), px(40)));
        TextView head = text(20, true);
        head.setText("Ieși din Modul Cinema?");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = px(14);
        card.addView(head, hlp);
        TextView sub = text(13.5f, false);
        sub.setAlpha(0.7f);
        sub.setText("Revii la ecranul principal.");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(6);
        card.addView(sub, slp);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setClipChildren(false);
        TextView leave = capsule("Ieși", false, () -> {
            closeAsk();
            exitCinema();
        });
        TextView stay = capsule("Rămân", false, this::closeAsk);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(-2, px(44));
        a.rightMargin = px(12);
        choices.addView(leave, a);
        choices.addView(stay, new LinearLayout.LayoutParams(-2, px(44)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(22);
        card.addView(choices, clp);
        exitAsk.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        for (View v : new View[]{stage, hero, rowScroll, tabBar}) {
            ((ViewGroup) v).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        badge.setFocusable(false);
        root.addView(exitAsk, full());
        exitAsk.setAlpha(0f);
        exitAsk.animate().alpha(1f).setDuration(220).start();
        card.setScaleX(0.9f);
        card.setScaleY(0.9f);
        card.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2.4f)).setDuration(340).start();
        leave.post(leave::requestFocus);
    }

    private void closeAsk() {
        if (exitAsk == null) {
            return;
        }
        final View gone = exitAsk;
        exitAsk = null;
        gone.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(gone)).start();
        for (View v : new View[]{stage, hero, rowScroll, tabBar}) {
            ((ViewGroup) v).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (screen == DETAIL) {
            rowScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        badge.setFocusable(true);
        if (beforeAsk != null && beforeAsk.isAttachedToWindow()) {
            beforeAsk.requestFocus();
        }
        restartPreviewTimer();
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Leaving Cinema: everything steps back into the dark, the Cinema sign turns once more,
     * then a warm light comes up (the lights coming back on) and the home screen returns.
     */
    private void exitCinema() {
        if (screen == EXITING) {
            return;
        }
        screen = EXITING;
        handler.removeCallbacksAndMessages(null);
        stopPreview(true);
        sound("cinema_out.ogg", 0.85f);
        for (View v : new View[]{hero, rowHeading, rowScroll, tabBar, badge, stage, crumbs, dots, credit}) {
            v.animate().cancel();
            v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setInterpolator(EASE).setDuration(420).start();
        }
        curtain.bringToFront();
        curtain.animate().cancel();
        curtain.animate().alpha(1f).setDuration(650).start();
        final FrameLayout group = new FrameLayout(this);
        group.setClipChildren(false);
        final View glow = new View(this);
        glow.setBackground(new Glow(0x4DFFFFFF, 0x1AFFFFFF));
        glow.setAlpha(0f);
        group.addView(glow, new FrameLayout.LayoutParams(px(900), px(520), Gravity.CENTER));
        final CinemaMark.Spin mark = new CinemaMark.Spin(this);
        mark.setSpeed(3f);
        mark.setRing(0f);
        mark.setPlay(0f);
        mark.setAlpha(0f);
        group.addView(mark, new FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER));
        root.addView(group, full());
        handler.postDelayed(() -> {
            mark.animate().alpha(1f).setDuration(300).start();
            mark.setScaleX(0.7f);
            mark.setScaleY(0.7f);
            mark.animate().scaleX(1f).scaleY(1f).setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                    .setDuration(500).start();
            mark.load();
        }, AUDIO_LEAD + 150);
        handler.postDelayed(() -> {
            glow.setScaleX(0.5f);
            glow.setScaleY(0.5f);
            glow.animate().alpha(1f).scaleX(1.9f).scaleY(1.9f).setInterpolator(new DecelerateInterpolator(1.5f))
                    .setDuration(1300).start();
        }, AUDIO_LEAD + 650);
        handler.postDelayed(() -> group.animate().alpha(0f).setDuration(380).start(), AUDIO_LEAD + 1500);
        handler.postDelayed(() -> {
            alive = false;
            left = true;
            // Always to the home screen (not to an app Cinema happened to be opened over).
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // Finishing is enough.
            }
            finish();
            overridePendingTransition(0, 0);
        }, AUDIO_LEAD + 1900);
    }

    // ------------------------------------------------------------------ sound

    private void sound(String name, float volume) {
        if (!prefs.getBoolean("cinema_sound", true)) {
            return;
        }
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("sounds/" + name)) {
            if (player != null) {
                player.release();
            }
            player = new android.media.MediaPlayer();
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            player.setVolume(volume, volume);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            // Silent.
        }
    }

    // ------------------------------------------------------------------ pictures

    interface Got {
        void got(Bitmap b);
    }

    /** A TMDB picture from memory, else the disk cache or the network (off the main thread). */
    private void picture(final String path, final String size, final int maxW, final boolean blur, final Got got) {
        if (path == null) {
            return;
        }
        final String key = (blur ? "blur" : size) + path;
        Bitmap have = pictures.get(key);
        if (have != null) {
            got.got(have);
            return;
        }
        net.execute(() -> {
            Bitmap b = tmdb.image(path, size, maxW);
            if (b != null && blur) {
                b = blurred(Bitmap.createScaledBitmap(b, 160, 90, true), 4);
            }
            if (b != null) {
                pictures.put(key, b);
                final Bitmap done = b;
                handler.post(() -> got.got(done));
            }
        });
    }

    private static Bitmap blurred(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(a, b, w, h, radius, true);
            boxPass(b, a, w, h, radius, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    /** One box blur along rows (or columns), edges clamped. */
    private static void boxPass(int[] in, int[] out, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w;
        int len = rows ? w : h;
        int span = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[index(line, Math.max(0, Math.min(len - 1, k)), w, rows)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, rows)] = 0xFF000000 | ((sr / span) << 16) | ((sg / span) << 8) | (sb / span);
                int add = in[index(line, Math.min(len - 1, i + r + 1), w, rows)];
                int sub = in[index(line, Math.max(0, i - r), w, rows)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int index(int line, int i, int w, boolean rows) {
        return rows ? line * w + i : i * w + line;
    }

    /** The picture behind the glass: the screen's picture small, blurred, a little darker and richer. */
    static Bitmap lensOf(Bitmap b) {
        Bitmap small = blurred(Bitmap.createScaledBitmap(b, 192, 108, true), 3);
        Bitmap out = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix m = new ColorMatrix();
        m.setScale(0.72f, 0.72f, 0.72f, 1f);
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.25f);
        m.postConcat(sat);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        new Canvas(out).drawBitmap(small, 0, 0, p);
        return out;
    }

    /** Cross-fades the full-screen picture, with a slow push-in like the tvOS top shelf. */
    private void showBack(final Bitmap b, String id, long duration) {
        if (id.equals(backShown)) {
            return;
        }
        backShown = id;
        final ImageView in;
        if (topShowing) {
            // The top one fades out over the new picture underneath.
            in = back[0];
            in.setImageBitmap(b);
            in.setAlpha(1f);
            back[1].animate().cancel();
            back[1].animate().alpha(0f).setDuration(duration).setInterpolator(EASE).start();
            topShowing = false;
        } else {
            in = back[1];
            in.animate().cancel();
            in.setImageBitmap(b);
            in.setAlpha(0f);
            in.animate().alpha(1f).setDuration(duration).setInterpolator(EASE).start();
            topShowing = true;
        }
        int which = in == back[0] ? 0 : 1;
        if (pushes[which] != null) {
            pushes[which].cancel();
        }
        in.setScaleX(1f);
        in.setScaleY(1f);
        ValueAnimator push = ValueAnimator.ofFloat(1f, 1.07f);
        pushes[which] = push;
        push.setDuration(16000);
        push.setInterpolator(new DecelerateInterpolator(1.2f));
        push.addUpdateListener(a -> {
            float k = (float) a.getAnimatedValue();
            in.setScaleX(k);
            in.setScaleY(k);
        });
        push.start();
        // The glass follows: the same picture, blurred, cross-faded under every pane.
        final long fade = duration;
        net.execute(() -> {
            final Bitmap lens = lensOf(b);
            handler.post(() -> {
                glassBack.wallBack = glassBack.wallFront;
                glassBack.wallFront = lens;
                glassBack.wallMix = glassBack.wallBack == null ? 1f : 0f;
                if (lensFade != null) {
                    lensFade.cancel();
                }
                if (glassBack.wallBack != null) {
                    lensFade = ValueAnimator.ofFloat(0f, 1f);
                    lensFade.setDuration(fade);
                    lensFade.addUpdateListener(a -> {
                        glassBack.wallMix = (float) a.getAnimatedValue();
                        invalidateGlass();
                    });
                    lensFade.start();
                }
                invalidateGlass();
            });
        });
    }

    // ------------------------------------------------------------------ glyphs

    /** Small symbols for the buttons, drawn in the SF Symbols manner. */
    private Bitmap glyph(String kind, int size, int color) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.1f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        float s = size;
        switch (kind) {
            case "play": {
                Path p = new Path();
                p.moveTo(s * 0.22f, s * 0.12f);
                p.lineTo(s * 0.88f, s * 0.5f);
                p.lineTo(s * 0.22f, s * 0.88f);
                p.close();
                fill.setPathEffect(new android.graphics.CornerPathEffect(s * 0.12f));
                c.drawPath(p, fill);
                break;
            }
            case "trailer": {
                c.drawRoundRect(new RectF(s * 0.08f, s * 0.2f, s * 0.92f, s * 0.8f), s * 0.14f, s * 0.14f, stroke);
                Path p = new Path();
                p.moveTo(s * 0.42f, s * 0.36f);
                p.lineTo(s * 0.64f, s * 0.5f);
                p.lineTo(s * 0.42f, s * 0.64f);
                p.close();
                c.drawPath(p, fill);
                break;
            }
            case "info": {
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.42f, stroke);
                c.drawCircle(s * 0.5f, s * 0.31f, s * 0.06f, fill);
                c.drawLine(s * 0.5f, s * 0.46f, s * 0.5f, s * 0.71f, stroke);
                break;
            }
            case "less": {
                c.drawLine(s * 0.25f, s * 0.6f, s * 0.5f, s * 0.36f, stroke);
                c.drawLine(s * 0.5f, s * 0.36f, s * 0.75f, s * 0.6f, stroke);
                break;
            }
            case "next":
            default: {
                stroke.setStrokeWidth(s * 0.12f);
                c.drawLine(s * 0.38f, s * 0.2f, s * 0.66f, s * 0.5f, stroke);
                c.drawLine(s * 0.66f, s * 0.5f, s * 0.38f, s * 0.8f, stroke);
                break;
            }
        }
        return b;
    }

    private Bitmap checkGlyph(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(Color.WHITE);
        c.drawCircle(size / 2f, size / 2f, size / 2f, disc);
        Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        tick.setColor(0xFF111111);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(size * 0.13f);
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeJoin(Paint.Join.ROUND);
        Path p = new Path();
        p.moveTo(size * 0.28f, size * 0.52f);
        p.lineTo(size * 0.44f, size * 0.67f);
        p.lineTo(size * 0.72f, size * 0.36f);
        c.drawPath(p, tick);
        return b;
    }

    private Bitmap arrowGlyph(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.1f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        c.drawLine(size * 0.16f, size * 0.5f, size * 0.82f, size * 0.5f, stroke);
        c.drawLine(size * 0.56f, size * 0.24f, size * 0.84f, size * 0.5f, stroke);
        c.drawLine(size * 0.84f, size * 0.5f, size * 0.56f, size * 0.76f, stroke);
        return b;
    }

    // ------------------------------------------------------------------ keys

    private long lastDpadDownAt;
    private long lastDpadUpAt;
    private long lastDpadRepeatAt;
    private int lastDpadCode;
    private boolean swallowDpadUp;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (screen == EXITING) {
            return true;
        }
        Press.key(event, getCurrentFocus());
        // This remote sometimes sends one press twice, and its "released" can arrive late, so
        // Android repeats a short press by itself: one press, one step, and a repeat only
        // counts once the key has really been held.
        if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            long t = event.getEventTime();
            if (down) {
                if (event.getRepeatCount() == 0) {
                    if (code == lastDpadCode && t - lastDpadUpAt < 90 && t - lastDpadDownAt < 300) {
                        swallowDpadUp = true;
                        return true;
                    }
                    lastDpadDownAt = t;
                    lastDpadCode = code;
                } else {
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
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && down && event.getRepeatCount() > 0) {
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down && event.getRepeatCount() == 0) {
                back();
            }
            return true;
        }
        if (screen == INTRO) {
            if (down && event.getRepeatCount() == 0 && tmdb.ready()) {
                handler.removeCallbacksAndMessages(null);
                if (player != null) {
                    player.setVolume(0.3f, 0.3f);
                }
                afterIntro();
            }
            return true;
        }
        if (screen == LOADING && exitAsk == null) {
            return true;
        }
        if (down && (screen == RESULTS || screen == DETAIL)) {
            restartPreviewTimer();
        }
        return super.dispatchKeyEvent(event);
    }

    private void back() {
        if (exitAsk != null) {
            closeAsk();
            return;
        }
        switch (screen) {
            case DETAIL:
                closeDetail();
                break;
            case RESULTS:
                // Like tvOS: Back first goes up to the tab bar, from there it leaves.
                if (tabsFocused()) {
                    exitCinema();
                } else {
                    tabs.getChildAt(tab).requestFocus();
                }
                break;
            case LOADING:
                searchGen++;
                showStep(4, false);
                break;
            case ASK:
                if (step == 0) {
                    if (!forYou.isEmpty()) {
                        showResults();
                    } else if (!subs.isEmpty()) {
                        saveSubs();
                        showStep(1, false);
                    } else {
                        exitCinema();
                    }
                } else if (step == 1) {
                    if (!forYou.isEmpty()) {
                        showResults();
                    } else {
                        exitCinema();
                    }
                } else {
                    showStep(step - 1, false);
                }
                break;
            default:
                exitCinema();
                break;
        }
    }
}
