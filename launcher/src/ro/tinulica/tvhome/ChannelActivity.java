package ro.tinulica.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The TV's own channels (antenna / cable) shown by TV Home instead of TCL's player: the picture
 * through TvView, Liquid Glass for video around it.
 *
 * - CH or Up/Down zaps; a compact banner shows the number, name, the programme on now with its
 *   progress, and the next one.
 * - OK opens the channel list: a floating glass panel; Left/Right changes category (Favourites,
 *   Sport, News, Movies, Kids, Documentaries, Music, General); moving through it previews the
 *   channel behind after a moment; OK keeps it, Back returns to the one you were watching;
 *   holding OK adds or removes a favourite.
 * - Digits jump straight to a number.
 */
public final class ChannelActivity extends Activity {

    static final String EXTRA_CHANNEL = "channel";
    private static final String PREFS = "home";
    private static final String KEY_LAST = "live_tv_channel";
    private static final String KEY_LAST_INPUT = "live_tv_channel_input";
    private static final String KEY_FAVOURITES = "live_tv_favourites";
    private static final long PREVIEW_DELAY = 650;

    /** One tuner channel from the TV provider. */
    static final class Channel {
        long id;
        String inputId;
        String number;
        String name;
        float sort;
        String category;
    }

    /** What is on, from the programme guide (when the TV has one). */
    static final class Programme {
        String title;
        long start;
        long end;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Channel> channels = new ArrayList<>();
    private final Set<Long> favourites = new HashSet<>();
    private final List<String> categories = new ArrayList<>();
    private SharedPreferences prefs;
    private float dp;
    private TvView tv;
    private int current = -1;      // the channel being watched (saved)
    private int showing = -1;      // the channel on screen (differs while previewing)
    private int previous = -1;     // the channel before, for Back
    private FrameLayout root;
    private View waiting;
    private TextView waitingTitle;
    private TextView waitingDetail;
    private LinearLayout banner;
    private TextView bannerNumber;
    private TextView bannerName;
    private TextView bannerNow;
    private TextView bannerTime;
    private TextView bannerNext;
    private View bannerProgress;
    private View bannerTrack;
    private FrameLayout list;
    private LinearLayout panel;
    private LinearLayout listRows;
    private ScrollView listScroll;
    private TextView categoryView;
    private int category;
    private TextView digitsView;
    private final StringBuilder digits = new StringBuilder();
    private SettingsActivity.Sounds sounds;
    private boolean okHeld;

    static Intent intent(Context context, long channelId) {
        Intent intent = new Intent(context, ChannelActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (channelId >= 0) {
            intent.putExtra(EXTRA_CHANNEL, channelId);
        }
        return intent;
    }

    private static String t(String ro, String en) {
        return "ro".equals(Locale.getDefault().getLanguage()) ? ro : en;
    }

    private int px(float value) {
        return Math.round(value * dp);
    }

    // ------------------------------------------------------------------ channels and guide

    /** Browsable channels of the TV's tuner inputs (not HDMI), in channel-number order. */
    static List<Channel> loadChannels(Context context) {
        List<Channel> out = new ArrayList<>();
        TvInputManager inputs = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        // One question per input (there are a few), not per channel (there are hundreds).
        java.util.Map<String, Boolean> tuner = new java.util.HashMap<>();
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID,
                        TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_BROWSABLE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String inputId = c.getString(1);
                if (inputId == null || inputs == null || (!c.isNull(4) && c.getInt(4) == 0)) {
                    continue;
                }
                Boolean ok = tuner.get(inputId);
                if (ok == null) {
                    TvInputInfo info = inputs.getTvInputInfo(inputId);
                    ok = info != null && !info.isPassthroughInput();
                    tuner.put(inputId, ok);
                }
                if (!ok) {
                    continue;
                }
                Channel ch = new Channel();
                ch.id = c.getLong(0);
                ch.inputId = inputId;
                ch.number = c.isNull(2) ? "" : c.getString(2);
                ch.name = c.isNull(3) ? ch.number : c.getString(3).trim();
                ch.category = categoryOf(ch.name);
                try {
                    ch.sort = Float.parseFloat(ch.number.replace('-', '.'));
                } catch (NumberFormatException e) {
                    ch.sort = Float.MAX_VALUE;
                }
                out.add(ch);
            }
        } catch (RuntimeException ignored) {
            // No access to the channel table.
        }
        Collections.sort(out, (a, b) -> Float.compare(a.sort, b.sort));
        return out;
    }

    private static final String[][] CATEGORY_WORDS = {
            {"Sport", "sport", "eurosport", "arena", "golf", "fight", "motorvision", "bein", "extreme", "auto motor"},
            {"Știri", "news", "stiri", "știri", "antena 3", "realitatea", "b1", "digi24", "digi 24", "euronews",
                    "cnn", "tvr info", "romania tv", "românia tv", "money", "bloomberg", "aljazeera", "sky news", "rfi"},
            {"Copii", "kids", "junior", "cartoon", "nick", "disney", "baby", "minimax", "boomerang", "jim jam",
                    "duck", "teen", "cartoonito", "pbs"},
            {"Filme", "film", "movie", "cinema", "cinemax", "hbo", "axn", "amc", "tcm", "tv1000", "comedy", "epic",
                    "diva", "warner", "universal", "paramount", "filmbox", "cinestar", "pro cinema", "happy", "stars",
                    "acasa gold", "acasă gold", "bollywood", "sundance", "kino", "series", "seriale"},
            {"Documentare", "discovery", "national geographic", "nat geo", "history", "viasat", "animal", "planet",
                    "travel", "food", "explorer", "earth", "science", "investigation", "crime", "docu", "da vinci",
                    "fishing", "hunting", "outdoor", "museum", "nature", "spektrum", "ci ", "tlc", "hgtv"},
            {"Muzică", "music", "muzic", "mtv", "vh1", "hit", "kiss", "taraf", "favorit", "etno", "dance", "radio",
                    "trace", "mezzo", "clubbing"},
    };

    /** A category from the channel's name (the TV's channel list carries no genre). */
    static String categoryOf(String name) {
        String n = " " + name.toLowerCase(Locale.ROOT) + " ";
        for (String[] group : CATEGORY_WORDS) {
            for (int i = 1; i < group.length; i++) {
                if (n.contains(group[i])) {
                    return group[0];
                }
            }
        }
        return "Generale";
    }

    private final java.util.concurrent.ExecutorService guideWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.Map<Long, List<Programme>> guideCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, Long> guideAt = new java.util.concurrent.ConcurrentHashMap<>();

    interface GuideDone {
        void onGuide(List<Programme> guide);
    }

    /** The guide for a channel without blocking the screen: cached a minute, read in the background. */
    private void guide(final Channel ch, final GuideDone done) {
        Long at = guideAt.get(ch.id);
        List<Programme> cached = guideCache.get(ch.id);
        if (cached != null && at != null && System.currentTimeMillis() - at < 60_000) {
            done.onGuide(cached);
            return;
        }
        guideWorker.execute(() -> {
            List<Programme> list = programmes(ch);
            guideCache.put(ch.id, list);
            guideAt.put(ch.id, System.currentTimeMillis());
            handler.post(() -> done.onGuide(list));
        });
    }

    /** The programme on now and the one after it; empty when the TV has no guide for the channel. */
    private List<Programme> programmes(Channel ch) {
        List<Programme> all = new ArrayList<>();
        long now = System.currentTimeMillis();
        Uri uri = TvContract.buildProgramsUriForChannel(ch.id, now - 6 * 3600_000L, now + 8 * 3600_000L);
        try (Cursor c = getContentResolver().query(uri, new String[]{TvContract.Programs.COLUMN_TITLE,
                TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                Programme p = new Programme();
                p.title = c.getString(0);
                p.start = c.getLong(1);
                p.end = c.getLong(2);
                if (p.title != null && p.end > now) {
                    all.add(p);
                }
            }
        } catch (RuntimeException ignored) {
            // No guide access.
        }
        Collections.sort(all, (a, b) -> Long.compare(a.start, b.start));
        List<Programme> out = new ArrayList<>();
        for (Programme p : all) {
            if (out.isEmpty()) {
                if (p.start <= now) {
                    out.add(p); // on now
                }
            } else if (p.start >= out.get(0).end - 60_000 && !p.title.equals(out.get(0).title) || p.start >= out.get(0).end) {
                out.add(p); // the next, different, programme
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (String id : prefs.getStringSet(KEY_LOCKED, new HashSet<>())) {
            try {
                locked.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // Skip.
            }
        }
        for (String id : prefs.getStringSet(Profiles.scoped(prefs, KEY_FAVOURITES), new HashSet<>())) {
            try {
                favourites.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // Skip.
            }
        }
        sounds = new SettingsActivity.Sounds(this, prefs);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        waiting = buildWaiting();
        root.addView(waiting, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildBanner(), new FrameLayout.LayoutParams(px(560), -2, Gravity.BOTTOM | Gravity.START) {
            {
                leftMargin = px(40);
                bottomMargin = px(32);
            }
        });
        list = buildList();
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));
        digitsView = text(30, true);
        digitsView.setGravity(Gravity.CENTER);
        digitsView.setPadding(px(18), px(6), px(18), px(6));
        VideoGlass digitsGlass = new VideoGlass(px(18), dp);
        digitsView.setBackground(digitsGlass);
        digitsView.setVisibility(View.GONE);
        root.addView(digitsView, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END) {
            {
                topMargin = px(30);
                rightMargin = px(40);
            }
        });
        setContentView(root);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String inputId) {
                timing("picture");
                showWaiting(false, null, null);
                tv.setStreamVolume(1f); // the tuner may still hold a preview's lower volume
            }

            @Override
            public void onTracksChanged(String inputId, List<android.media.tv.TvTrackInfo> tracks) {
                tv.setStreamVolume(1f);
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING) {
                    showWaiting(true, t("Se schimbă canalul…", "Tuning…"), null);
                } else if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL) {
                    showWaiting(true, t("Semnal slab", "Weak signal"), t("Verifică antena sau cablul.", "Check the antenna or cable."));
                } else if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY) {
                    showWaiting(true, t("Canal radio", "Radio channel"), null);
                } else {
                    showWaiting(true, t("Fără semnal", "No signal"), t("Verifică antena sau cablul.", "Check the antenna or cable."));
                }
            }

            @Override
            public void onContentBlocked(String inputId, android.media.tv.TvContentRating rating) {
                showWaiting(true, t("Conținut blocat", "Content blocked"), t("Restricțiile parentale ale televizorului.", "The TV's parental controls."));
            }

            @Override
            public void onConnectionFailed(String inputId) {
                fallBack();
            }

            @Override
            public void onDisconnected(String inputId) {
                fallBack();
            }
        });
        // The picture first: the channel to watch is tuned straight away (its id and input are
        // remembered), and the list of channels is read while the tuner locks on.
        long wanted = getIntent().getLongExtra(EXTRA_CHANNEL, prefs.getLong(KEY_LAST, -1));
        String input = prefs.getString(KEY_LAST_INPUT, null);
        if (wanted >= 0 && input != null && wanted == prefs.getLong(KEY_LAST, -1)) {
            try {
                tv.tune(input, TvContract.buildChannelUri(wanted));
                earlyTuned = wanted;
                timing("tuned early");
            } catch (RuntimeException ignored) {
                earlyTuned = -1;
            }
        }
        channels.addAll(loadChannels(this));
        timing(channels.size() + " channels read");
        if (channels.isEmpty()) {
            fallBack();
            return;
        }
        tuneTo(indexFor(getIntent()));
    }

    private long earlyTuned = -1;
    private final long born = android.os.SystemClock.uptimeMillis();

    /** How long the picture takes, in files/channels.log. */
    private void timing(String what) {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) {
            return;
        }
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "channels.log"), true)) {
            w.write(new Date() + "  +" + (android.os.SystemClock.uptimeMillis() - born) + " ms  " + what + "\n");
        } catch (java.io.IOException ignored) {
            // Debug only.
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_CHANNEL)) {
            tuneTo(indexFor(intent));
        }
    }

    private int indexFor(Intent intent) {
        long wanted = intent.getLongExtra(EXTRA_CHANNEL, prefs.getLong(KEY_LAST, -1));
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id == wanted) {
                return i;
            }
        }
        return 0;
    }

    /** Watches a channel: tunes it if needed, remembers it, shows the banner. */
    private void tuneTo(int index) {
        if (channels.isEmpty()) {
            return;
        }
        int n = channels.size();
        int next = ((index % n) + n) % n;
        if (current >= 0 && next != current) {
            previous = current;
        }
        current = next;
        prefs.edit().putLong(KEY_LAST, channels.get(current).id).putString(KEY_LAST_INPUT, channels.get(current).inputId).apply();
        show(current);
        showBanner();
    }

    /** Puts a channel on screen (also used to preview from the list). */
    private void show(int index) {
        if (index == showing && !(lockShown && !isLocked(channels.get(index)))) {
            return;
        }
        showing = index;
        Channel ch = channels.get(index);
        if (isLocked(ch)) {
            // A locked channel is not tuned at all until its PIN is entered.
            tv.reset();
            lockShown = true;
            showWaiting(true, t("Canal blocat", "Channel locked"),
                    t("Apasă OK și introdu codul PIN.", "Press OK and enter the PIN."));
            return;
        }
        if (lockShown) {
            lockShown = false;
            showWaiting(false, null, null);
        }
        try {
            if (ch.id == earlyTuned) {
                earlyTuned = -1; // already tuned while the list was read
            } else {
                tv.tune(ch.inputId, TvContract.buildChannelUri(ch.id));
                timing("tune " + ch.number);
            }
            tv.setStreamVolume(1f); // full sound (a muted preview on the home screen may have left it at 0)
        } catch (RuntimeException e) {
            fallBack();
        }
    }

    private void fallBack() {
        // The TV would not give us the tuner: let TCL's own player show the channels.
        Intent app = getPackageManager().getLeanbackLaunchIntentForPackage("com.tcl.tv");
        if (current >= 0 && current < channels.size()) {
            app = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(channels.get(current).id)).setPackage("com.tcl.tv");
        }
        if (app != null) {
            try {
                startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // Nothing else can show it.
            }
        }
        finish();
    }

    private TextView text(float sp, boolean bold) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setShadowLayer(px(3), 0, px(1), 0x66000000);
        return view;
    }

    // ------------------------------------------------------------------ waiting screen

    private View buildWaiting() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1C1C21, 0xFF0B0B0D}));
        waitingTitle = text(18, true);
        column.addView(waitingTitle);
        waitingDetail = text(12.5f, false);
        waitingDetail.setAlpha(0.6f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(6);
        column.addView(waitingDetail, lp);
        column.setVisibility(View.GONE);
        return column;
    }

    private void showWaiting(boolean show, String title, String detail) {
        if (show) {
            waitingTitle.setText(title);
            waitingDetail.setText(detail != null ? detail : "");
            if (waiting.getVisibility() != View.VISIBLE) {
                waiting.setVisibility(View.VISIBLE);
                waiting.setAlpha(0f);
                waiting.animate().alpha(1f).setDuration(250).start();
            }
        } else if (waiting.getVisibility() == View.VISIBLE) {
            waiting.animate().alpha(0f).setDuration(300).withEndAction(() -> waiting.setVisibility(View.GONE)).start();
        }
    }

    // ------------------------------------------------------------------ info banner

    private View buildBanner() {
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(px(14), px(12), px(18), px(12));
        VideoGlass glass = new VideoGlass(px(24), dp);
        holdFill = new HoldFill(px(24));
        banner.setBackground(new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{glass, holdFill}));
        banner.setOutlineProvider(glass.outline());
        banner.setElevation(px(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        bannerNumber = text(19, true);
        bannerNumber.setGravity(Gravity.CENTER);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x26FFFFFF);
        chip.setCornerRadius(px(14));
        chip.setStroke(Math.max(1, px(0.75f)), 0x40FFFFFF);
        bannerNumber.setBackground(chip);
        FrameLayout numberBox = new FrameLayout(this);
        numberBox.addView(bannerNumber, new FrameLayout.LayoutParams(-1, -1));
        top.addView(numberBox, new LinearLayout.LayoutParams(px(54), px(54)));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        bannerName = text(16, true);
        middle.addView(bannerName);
        bannerNow = text(12.5f, false);
        bannerNow.setAlpha(0.9f);
        middle.addView(bannerNow);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
        mlp.leftMargin = px(14);
        top.addView(middle, mlp);
        bannerTime = text(11, false);
        bannerTime.setAlpha(0.7f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(10);
        top.addView(bannerTime, tlp);
        banner.addView(top);

        FrameLayout bar = new FrameLayout(this);
        bannerTrack = new View(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x33FFFFFF);
        track.setCornerRadius(px(2));
        bannerTrack.setBackground(track);
        bar.addView(bannerTrack, new FrameLayout.LayoutParams(-1, px(3), Gravity.CENTER_VERTICAL));
        bannerProgress = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xFFFFFFFF);
        fill.setCornerRadius(px(2));
        bannerProgress.setBackground(fill);
        bar.addView(bannerProgress, new FrameLayout.LayoutParams(0, px(3), Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, px(8));
        blp.topMargin = px(10);
        banner.addView(bar, blp);

        bannerNext = text(11.5f, false);
        bannerNext.setAlpha(0.65f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(6);
        banner.addView(bannerNext, nlp);
        holdHint = text(12.5f, true);
        holdHint.setGravity(Gravity.CENTER);
        holdHint.setText(t("Dă drumul pentru opțiuni", "Let go for options"));
        holdHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = px(8);
        banner.addView(holdHint, hlp);

        banner.setAlpha(0f);
        return banner;
    }

    private final Runnable hideBanner = () ->
            banner.animate().alpha(0f).translationY(px(16)).setDuration(320).start();

    private void showBanner() {
        if (current < 0) {
            return;
        }
        Channel ch = channels.get(current);
        bannerNumber.setText(ch.number);
        bannerName.setText((favourites.contains(ch.id) ? "★  " : "") + ch.name);
        final int shownFor = current;
        guide(ch, list -> {
            if (shownFor == current) {
                fillGuide(ch, list);
            }
        });
        handler.removeCallbacks(hideBanner);
        if (banner.getAlpha() < 0.99f) {
            banner.setTranslationY(px(16));
        }
        banner.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2.2f)).setDuration(360).start();
        handler.postDelayed(hideBanner, 4500);
    }

    private void fillGuide(Channel ch, List<Programme> guide) {
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        View bar = (View) bannerTrack.getParent();
        if (!guide.isEmpty()) {
            final Programme now = guide.get(0);
            bannerNow.setText(now.title);
            bannerTime.setText(hm.format(new Date(now.start)) + " – " + hm.format(new Date(now.end)));
            final float done = Math.max(0f, Math.min(1f,
                    (System.currentTimeMillis() - now.start) / (float) Math.max(1, now.end - now.start)));
            bar.setVisibility(View.VISIBLE);
            bannerTrack.post(() -> {
                ViewGroup.LayoutParams lp = bannerProgress.getLayoutParams();
                lp.width = Math.round(bannerTrack.getWidth() * done);
                bannerProgress.setLayoutParams(lp);
            });
            if (guide.size() > 1) {
                bannerNext.setText(t("Urmează", "Next") + " " + hm.format(new Date(guide.get(1).start))
                        + "  ·  " + guide.get(1).title);
                bannerNext.setVisibility(View.VISIBLE);
            } else {
                bannerNext.setVisibility(View.GONE);
            }
        } else {
            bannerNow.setText(ch.category);
            bannerTime.setText(hm.format(new Date()));
            bar.setVisibility(View.GONE);
            bannerNext.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ channel list

    private FrameLayout buildList() {
        FrameLayout layer = new FrameLayout(this);
        layer.setClipChildren(false);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(14), px(18), px(14), px(18));
        VideoGlass glass = new VideoGlass(px(28), dp);
        glass.setVeil(0x4D0C0C10); // a little more under a list of names
        panel.setBackground(glass);
        panel.setOutlineProvider(glass.outline());
        panel.setClipToOutline(true);
        panel.setElevation(px(18));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(8), 0, px(4), px(10));
        TextView title = text(17, true);
        title.setText(t("Canale", "Channels"));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        categoryView = text(12, true);
        categoryView.setGravity(Gravity.CENTER);
        categoryView.setPadding(px(10), px(4), px(10), px(4));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x2EFFFFFF);
        pill.setCornerRadius(px(12));
        categoryView.setBackground(pill);
        head.addView(categoryView);
        panel.addView(head);

        listScroll = new ScrollView(this) {
            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                // Keep the focused channel near the middle, scrolling smoothly.
                int target = focused.getTop() + focused.getHeight() / 2 - getHeight() / 2;
                smoothScrollTo(0, Math.max(0, target));
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }
        };
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setVerticalFadingEdgeEnabled(true);
        // Rows fade out softly under the title instead of being cut by it.
        listScroll.setFadingEdgeLength(px(44));
        listScroll.setClipToPadding(true);
        listScroll.setPadding(0, px(8), 0, px(8));
        listRows = new LinearLayout(this);
        listRows.setOrientation(LinearLayout.VERTICAL);
        listRows.setPadding(px(4), 0, px(4), 0);
        FrameLayout listFrame = new FrameLayout(this);
        selector = new Selector(this);
        listFrame.addView(selector, new FrameLayout.LayoutParams(-1, -1));
        listFrame.addView(listRows, new FrameLayout.LayoutParams(-1, -2));
        listScroll.addView(listFrame);
        panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        layer.addView(panel, new FrameLayout.LayoutParams(px(290), -1, Gravity.START) {
            {
                leftMargin = px(28);
                topMargin = px(28);
                bottomMargin = px(28);
            }
        });
        layer.setVisibility(View.GONE);
        return layer;
    }

    private int listStart = -1; // the channel watched when the list opened
    private Selector selector;
    private Spring panelSpring;

    /**
     * The white focus capsule of the channel list: one shape that glides on springs from row to
     * row (position and height), like focus moving on the home screen, instead of each row
     * lighting up on its own.
     */
    private final class Selector extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        private final Spring y;
        private final Spring h;
        private boolean placed;

        Selector(Context context) {
            super(context);
            paint.setColor(0xF2FFFFFF);
            y = new Spring(0f, 0.3f, v -> invalidate());
            y.configure(0.26f, 0.86f);
            h = new Spring(0f, 0.3f, v -> invalidate());
            h.configure(0.26f, 0.9f);
        }

        void moveTo(View row, boolean animate) {
            float top = row.getTop();
            float height = row.getHeight();
            if (height <= 0) {
                row.post(() -> moveTo(row, animate));
                return;
            }
            if (!placed || !animate) {
                y.snap(top);
                h.snap(height);
                placed = true;
            } else {
                y.to(top);
                h.to(height);
            }
            invalidate();
        }

        void reset() {
            placed = false;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (!placed) {
                return;
            }
            float inset = 4 * dp;
            box.set(inset, y.value(), getWidth() - inset, y.value() + h.value());
            canvas.drawRoundRect(box, 12 * dp, 12 * dp, paint);
        }
    }

    private void showList() {
        categories.clear();
        if (!favourites.isEmpty()) {
            categories.add(t("Favorite", "Favourites"));
        }
        categories.add(t("Toate", "All"));
        for (String[] group : CATEGORY_WORDS) {
            for (Channel ch : channels) {
                if (group[0].equals(ch.category)) {
                    categories.add(group[0]);
                    break;
                }
            }
        }
        categories.add("Generale");
        category = categories.indexOf(t("Toate", "All"));
        listStart = current;
        list.setVisibility(View.VISIBLE);
        fillRows(true);
        panel.setAlpha(0f);
        panel.setTranslationX(-px(48));
        panel.animate().alpha(1f).setDuration(220).start();
        if (panelSpring == null) {
            panelSpring = new Spring(-px(48), 0.5f, v -> panel.setTranslationX(v));
        }
        panelSpring.snap(-px(48));
        panelSpring.to(0f, 0.36f, 0.82f);
        handler.removeCallbacks(hideBanner);
        banner.animate().alpha(0f).setDuration(200).start();
    }

    private boolean inCategory(Channel ch) {
        String name = categories.get(category);
        if (name.equals(t("Toate", "All"))) {
            return true;
        }
        if (name.equals(t("Favorite", "Favourites"))) {
            return favourites.contains(ch.id);
        }
        return name.equals(ch.category);
    }

    /** Rows for the current category; focus goes to the channel watched, if it is in it. */
    private void fillRows(boolean first) {
        categoryView.setText("‹  " + categories.get(category) + "  ›");
        selector.reset();
        listRows.removeAllViews();
        View focus = null;
        for (int i = 0; i < channels.size(); i++) {
            Channel ch = channels.get(i);
            if (!inCategory(ch)) {
                continue;
            }
            View row = row(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(2);
            listRows.addView(row, lp);
            if (i == showing || focus == null) {
                focus = i == showing || focus == null ? row : focus;
            }
        }
        if (listRows.getChildCount() == 0) {
            TextView none = text(12.5f, false);
            none.setAlpha(0.6f);
            none.setPadding(px(10), px(12), px(10), px(12));
            none.setSingleLine(false);
            none.setText(t("Niciun canal aici. Ține apăsat OK pe un canal ca să-l adaugi la Favorite.",
                    "No channels here. Hold OK on a channel to add it to Favourites."));
            listRows.addView(none);
            listRows.setFocusable(true);
            listRows.requestFocus();
            return;
        }
        if (!first) {
            listRows.setAlpha(0f);
            listRows.animate().alpha(1f).setDuration(220).start();
        }
        // Open right on the channel being watched: once laid out, centre it and focus it.
        final View f = focus;
        listRows.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // Only once the list is really on screen and measured; until then, wait.
                if (!f.isShown() || f.getHeight() == 0 || listScroll.getHeight() == 0) {
                    return;
                }
                listRows.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int target = f.getTop() + f.getHeight() / 2 - listScroll.getHeight() / 2;
                listScroll.scrollTo(0, Math.max(0, target));
                f.requestFocus();
                handler.removeCallbacks(preview); // opening the list is not browsing it
            }
        });
        listRows.requestLayout();
    }

    private View row(final int index) {
        final Channel ch = channels.get(index);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(10), px(7), px(10), px(7));
        final TextView number = text(12, true);
        number.setText(ch.number);
        number.setAlpha(0.65f);
        row.addView(number, new LinearLayout.LayoutParams(px(38), -2));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        final TextView name = text(13, true);
        name.setText((favourites.contains(ch.id) ? "★ " : "") + ch.name);
        words.addView(name);
        if (locked.contains(ch.id)) {
            final android.widget.ImageView lock = new android.widget.ImageView(this);
            lock.setImageBitmap(Glyphs.symbol(this, "lock", px(12)));
            lock.setTag("lock");
            row.addView(lock, new LinearLayout.LayoutParams(px(12), px(12)));
        }
        // The programme line is always there (filled in the background), so focusing a row only
        // changes its colours: no row grows, nothing below it jumps.
        final TextView now = text(10.5f, false);
        now.setAlpha(0.7f);
        now.setText(" ");
        words.addView(now);
        guide(ch, list -> {
            if (!list.isEmpty()) {
                now.setText(list.get(0).title);
            }
        });
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setFocusable(true);
        row.setTag(index);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v.hasFocus() || v.isFocused()) {
                selector.moveTo(v, false); // the row grew (programme line): follow it
            }
        });
        row.setOnFocusChangeListener((v, has) -> {
            if (has) {
                selector.moveTo(v, true);
            }
            int ink = has ? 0xFF111111 : Color.WHITE;
            Object lockIcon = v.findViewWithTag("lock");
            if (lockIcon instanceof android.widget.ImageView) {
                if (has) {
                    ((android.widget.ImageView) lockIcon).setColorFilter(0xFF111111, android.graphics.PorterDuff.Mode.SRC_IN);
                } else {
                    ((android.widget.ImageView) lockIcon).clearColorFilter();
                }
            }
            number.setTextColor(ink);
            name.setTextColor(ink);
            now.setTextColor(ink);
            float shadow = has ? 0 : px(3);
            name.setShadowLayer(shadow, 0, has ? 0 : px(1), 0x66000000);
            number.setShadowLayer(shadow, 0, has ? 0 : px(1), 0x66000000);
            if (has) {
                sounds.play("move");

                // Preview: after a moment on a channel, it plays behind the list.
                handler.removeCallbacks(preview);
                previewIndex = index;
                handler.postDelayed(preview, PREVIEW_DELAY);
            }
        });
        return row;
    }

    private boolean okDown;
    private final Runnable okLong = () -> {
        if (okDown && listOpen()) {
            okHeld = true;
            toggleFavourite(getCurrentFocus());
        }
    };
    private final Runnable okReleased = () -> {
        okDown = false;
        handler.removeCallbacks(okLong);
        if (!okHeld && listOpen()) {
            sounds.play("select");
            closeList(true);
        }
    };

    private int previewIndex = -1;
    private final Runnable preview = () -> {
        if (listOpen() && previewIndex >= 0) {
            show(previewIndex);
        }
    };

    private void toggleFavourite(View row) {
        if (row == null || !(row.getTag() instanceof Integer)) {
            return;
        }
        Channel ch = channels.get((Integer) row.getTag());
        if (!favourites.remove(ch.id)) {
            favourites.add(ch.id);
        }
        Set<String> ids = new HashSet<>();
        for (Long id : favourites) {
            ids.add(String.valueOf(id));
        }
        prefs.edit().putStringSet(Profiles.scoped(prefs, KEY_FAVOURITES), ids).apply();
        sounds.play("select");
        TextView name = (TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(1)).getChildAt(0);
        name.setText((favourites.contains(ch.id) ? "★ " : "") + ch.name);
        row.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120)
                .withEndAction(() -> row.animate().scaleX(1.03f).scaleY(1.03f).setDuration(160).start()).start();
    }

    private void closeList(boolean keep) {
        handler.removeCallbacks(preview);
        View focused = getCurrentFocus();
        if (keep && focused != null && focused.getTag() instanceof Integer) {
            tuneTo((Integer) focused.getTag());
        } else if (listStart >= 0 && showing != listStart) {
            show(listStart); // back to what was on
        }
        panel.animate().alpha(0f).translationX(-px(36)).setDuration(220)
                .withEndAction(() -> list.setVisibility(View.GONE)).start();
        root.requestFocus();
    }

    private boolean listOpen() {
        return list.getVisibility() == View.VISIBLE && panel.getAlpha() > 0.01f;
    }

    // ------------------------------------------------------------------ digits

    private final Runnable commitDigits = () -> {
        String wanted = digits.toString();
        digits.setLength(0);
        digitsView.animate().alpha(0f).setDuration(200).withEndAction(() -> digitsView.setVisibility(View.GONE)).start();
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number.equals(wanted)) {
                tuneTo(i);
                return;
            }
        }
    };

    private void typeDigit(int digit) {
        if (digits.length() >= 4) {
            digits.setLength(0);
        }
        digits.append(digit);
        digitsView.setText(digits);
        digitsView.setVisibility(View.VISIBLE);
        digitsView.setAlpha(1f);
        handler.removeCallbacks(commitDigits);
        handler.postDelayed(commitDigits, 1500);
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (modal != null) {
            if (modalKeys != null) {
                modalKeys.onKey(event);
            }
            return true;
        }
        if (!listOpen() && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Held for two seconds: the ring round the number fills, then the lock options.
            // This remote sends a held key as quick press/release pairs: a release only ends the
            // hold if no new press follows within a moment.
            if (down) {
                handler.removeCallbacks(rightReleased);
                if (!rightHolding) {
                    rightHolding = true;
                    rightDownAt = android.os.SystemClock.uptimeMillis();
                    showBanner();
                    handler.removeCallbacks(hideBanner);
                    handler.post(rightTick);
                }
            } else {
                handler.removeCallbacks(rightReleased);
                handler.postDelayed(rightReleased, 260);
            }
            return true;
        }
        if (listOpen()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) {
                    sounds.play("back");
                    closeList(false);
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) {
                    int n = categories.size();
                    category = (category + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : n - 1)) % n;
                    sounds.play("move");
                    fillRows(false);
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                // A press keeps the channel; holding OK (0.8 s) adds or removes it from Favourites.
                // Held keys arrive as press/release pairs, so both are decided by time.
                if (down) {
                    handler.removeCallbacks(okReleased);
                    if (!okDown) {
                        okDown = true;
                        okHeld = false;
                        handler.postDelayed(okLong, 800);
                    }
                } else {
                    handler.removeCallbacks(okReleased);
                    handler.postDelayed(okReleased, 260);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            // Back never leaves the TV (Home does): it only puts away what is on screen.
            if (down && event.getRepeatCount() == 0 && banner.getAlpha() > 0.05f) {
                sounds.play("back");
                handler.removeCallbacks(hideBanner);
                hideBanner.run();
            }
            return true;
        }
        if (!down) {
            return super.dispatchKeyEvent(event);
        }
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_DPAD_UP) {
            tuneTo(current + 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            tuneTo(current - 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_TV_CONTENTS_MENU) {
            if (event.getRepeatCount() == 0) {
                sounds.play("select");
                if (digits.length() > 0) {
                    // A number being typed: OK confirms it (and nothing else opens).
                    handler.removeCallbacks(commitDigits);
                    commitDigits.run();
                } else if (lockShown && current >= 0) {
                    final Channel ch = channels.get(current);
                    askPin(t("Introdu codul PIN", "Enter the PIN"), pin -> {
                        if (pinMatches(pin)) {
                            unlocked.add(ch.id);
                            closeModal();
                            show(current);
                            showBanner();
                        } else {
                            wrongPin();
                        }
                    });
                } else {
                    showList();
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_INFO) {
            showBanner();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            // The channel list lives on the left: Left (like OK) slides it in.
            if (event.getRepeatCount() == 0 && digits.length() == 0) {
                sounds.play("select");
                showList();
            }
            return true;
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            typeDigit(code - KeyEvent.KEYCODE_0);
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            // TCL's own player for what only it has (guide, subtitles, audio tracks).
            fallBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------ locks and PIN

    private static final String KEY_LOCKED = "live_tv_locked";
    private static final String KEY_PIN = "live_tv_pin";
    private final Set<Long> locked = new HashSet<>();
    private final Set<Long> unlocked = new HashSet<>(); // opened with the PIN while watching
    private boolean lockShown;
    private HoldFill holdFill;
    private TextView holdHint;
    private boolean holdReady;
    private boolean rightHolding;
    private long rightDownAt;
    private FrameLayout modal;
    private ModalKeys modalKeys;
    private LinearLayout modalCard;

    interface ModalKeys {
        void onKey(KeyEvent event);
    }

    interface PinDone {
        void onPin(String pin);
    }

    private boolean isLocked(Channel ch) {
        return locked.contains(ch.id) && !unlocked.contains(ch.id);
    }

    private final Runnable rightReleased = () -> {
        rightHolding = false;
        boolean open = holdReady;
        holdReady = false;
        holdFill.animateTo(0f);
        holdHint.animate().cancel();
        holdHint.setVisibility(View.GONE);
        if (open) {
            sounds.play("select");
            openLockMenu();
        } else {
            handler.removeCallbacks(hideBanner);
            handler.postDelayed(hideBanner, 3000);
        }
    };

    private final Runnable rightTick = new Runnable() {
        @Override
        public void run() {
            if (!rightHolding) {
                return;
            }
            float t = (android.os.SystemClock.uptimeMillis() - rightDownAt - 250) / 1750f;
            float p = Math.max(0f, Math.min(1f, t));
            holdFill.setProgress(p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2);
            if (p >= 1f) {
                // Full: the card glows and asks to let go; the options open on release.
                if (!holdReady) {
                    holdReady = true;
                    sounds.play("move");
                    holdHint.setVisibility(View.VISIBLE);
                    holdHint.setAlpha(0f);
                    pulseHint();
                    holdFill.breathe(true);
                }
            } else {
                android.view.Choreographer.getInstance().postFrameCallback(f -> run());
            }
        }
    };

    private void pulseHint() {
        if (!holdReady) {
            return;
        }
        holdHint.animate().alpha(holdHint.getAlpha() > 0.6f ? 0.45f : 1f).setDuration(520)
                .withEndAction(this::pulseHint).start();
    }

    /**
     * Light filling the channel card from the left while Right is held, with a soft bright
     * leading edge; when full it breathes until the key is let go.
     */
    private final class HoldFill extends android.graphics.drawable.Drawable {
        private final float radius;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();
        private final android.graphics.RectF box = new android.graphics.RectF();
        private float progress;
        private boolean breathing;
        private long breatheFrom;
        private android.animation.ValueAnimator back;

        HoldFill(float radius) {
            this.radius = radius;
        }

        void setProgress(float p) {
            if (back != null) {
                back.cancel();
            }
            progress = p;
            invalidateSelf();
        }

        void animateTo(float p) {
            breathing = false;
            back = android.animation.ValueAnimator.ofFloat(progress, p).setDuration(260);
            back.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            back.start();
        }

        void breathe(boolean on) {
            breathing = on;
            breatheFrom = android.os.SystemClock.uptimeMillis();
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            if (progress <= 0.002f) {
                return;
            }
            android.graphics.Rect b = getBounds();
            box.set(b);
            clip.reset();
            clip.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW);
            int saved = canvas.save();
            canvas.clipPath(clip);
            float right = b.left + b.width() * progress;
            float glow = 0f;
            if (breathing) {
                double t = (android.os.SystemClock.uptimeMillis() - breatheFrom) / 900.0 * Math.PI * 2;
                glow = (float) (0.5 - 0.5 * Math.cos(t));
                invalidateSelf();
            }
            paint.setShader(new android.graphics.LinearGradient(b.left, 0, right, 0,
                    0x14FFFFFF, Math.round(0x38 + 0x20 * glow) << 24 | 0xFFFFFF,
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(b.left, b.top, right, b.bottom, paint);
            if (progress < 1f) {
                float w = 26 * dp;
                edge.setShader(new android.graphics.LinearGradient(right - w, 0, right, 0,
                        0x00FFFFFF, 0x66FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRect(right - w, b.top, right, b.bottom, edge);
            }
            canvas.restoreToCount(saved);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static String hash(String pin) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(("tvhome:" + pin).getBytes("UTF-8"))) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return pin;
        }
    }

    private boolean pinMatches(String pin) {
        return hash(pin).equals(prefs.getString(KEY_PIN, null));
    }

    private void saveLocks() {
        Set<String> ids = new HashSet<>();
        for (Long id : locked) {
            ids.add(String.valueOf(id));
        }
        prefs.edit().putStringSet(KEY_LOCKED, ids).apply();
    }

    /** A centred glass card over a dim scrim; keys go to {@code keys} until it closes. */
    private LinearLayout openModal(ModalKeys keys) {
        closeModal();
        handler.removeCallbacks(hideBanner);
        banner.animate().alpha(0f).setDuration(180).start();
        modal = new FrameLayout(this);
        modal.setBackgroundColor(0x80000000);
        modalCard = new LinearLayout(this);
        modalCard.setOrientation(LinearLayout.VERTICAL);
        modalCard.setGravity(Gravity.CENTER_HORIZONTAL);
        modalCard.setPadding(px(24), px(22), px(24), px(22));
        VideoGlass glass = new VideoGlass(px(28), dp);
        glass.setVeil(0x660C0C10);
        modalCard.setBackground(glass);
        modalCard.setOutlineProvider(glass.outline());
        modalCard.setElevation(px(20));
        modal.addView(modalCard, new FrameLayout.LayoutParams(px(380), -2, Gravity.CENTER));
        root.addView(modal, new FrameLayout.LayoutParams(-1, -1));
        modalKeys = keys;
        modal.setAlpha(0f);
        modal.animate().alpha(1f).setDuration(200).start();
        modalCard.setScaleX(0.92f);
        modalCard.setScaleY(0.92f);
        modalCard.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2.4f)).setDuration(320).start();
        return modalCard;
    }

    private void closeModal() {
        if (modal == null) {
            return;
        }
        final View old = modal;
        modal = null;
        modalKeys = null;
        old.animate().alpha(0f).setDuration(180).withEndAction(() -> root.removeView(old)).start();
    }

    /** Options for the channel: lock or unlock it, change the PIN. */
    private void openLockMenu() {
        if (current < 0) {
            return;
        }
        final Channel ch = channels.get(current);
        final List<TextView> buttons = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final int[] at = {0};
        LinearLayout card = openModal(event -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return;
            }
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) {
                sounds.play("back");
                closeModal();
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
                at[0] = Math.max(0, Math.min(buttons.size() - 1, at[0] + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                highlight(buttons, at[0]);
                sounds.play("move");
            } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                sounds.play("select");
                actions.get(at[0]).run();
            }
        });
        TextView title = text(17, true);
        title.setText(ch.number + "  ·  " + ch.name);
        card.addView(title);
        TextView sub = text(12, false);
        sub.setAlpha(0.65f);
        sub.setText(locked.contains(ch.id) ? t("Canal blocat cu PIN", "Locked with a PIN")
                : t("Blochează-l ca să ceară codul PIN.", "Lock it to ask for the PIN."));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(4);
        slp.bottomMargin = px(14);
        card.addView(sub, slp);
        final boolean hasPin = prefs.getString(KEY_PIN, null) != null;
        if (!locked.contains(ch.id)) {
            addButton(card, buttons, actions, t("Blochează canalul", "Lock channel"), () -> {
                Runnable lock = () -> {
                    locked.add(ch.id);
                    unlocked.remove(ch.id);
                    saveLocks();
                    closeModal();
                    showing = -1;
                    show(current);
                };
                if (hasPin) {
                    lock.run();
                } else {
                    createPin(lock);
                }
            });
        } else {
            addButton(card, buttons, actions, t("Deblochează canalul", "Unlock channel"), () ->
                    askPin(t("Introdu codul PIN", "Enter the PIN"), pin -> {
                        if (pinMatches(pin)) {
                            locked.remove(ch.id);
                            saveLocks();
                            closeModal();
                            showing = -1;
                            show(current);
                        } else {
                            wrongPin();
                        }
                    }));
        }
        if (hasPin) {
            addButton(card, buttons, actions, t("Schimbă codul PIN", "Change PIN"), () ->
                    askPin(t("Codul PIN actual", "Current PIN"), pin -> {
                        if (pinMatches(pin)) {
                            createPin(this::closeModal);
                        } else {
                            wrongPin();
                        }
                    }));
        }
        addButton(card, buttons, actions, t("Anulează", "Cancel"), this::closeModal);
        highlight(buttons, 0);
    }

    private void addButton(LinearLayout card, List<TextView> buttons, List<Runnable> actions, String label, Runnable action) {
        TextView b = text(14, true);
        b.setGravity(Gravity.CENTER);
        b.setText(label);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(px(14));
        shape.setColor(0x26FFFFFF);
        b.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(42));
        lp.topMargin = px(6);
        card.addView(b, lp);
        buttons.add(b);
        actions.add(action);
    }

    private void highlight(List<TextView> buttons, int index) {
        for (int i = 0; i < buttons.size(); i++) {
            TextView b = buttons.get(i);
            boolean on = i == index;
            ((GradientDrawable) b.getBackground()).setColor(on ? 0xF2FFFFFF : 0x26FFFFFF);
            b.setTextColor(on ? 0xFF111111 : Color.WHITE);
            b.setShadowLayer(on ? 0 : px(3), 0, on ? 0 : px(1), 0x66000000);
        }
    }

    /** A new PIN, typed twice. */
    private void createPin(final Runnable then) {
        askPin(t("Alege un cod PIN din 4 cifre", "Choose a 4-digit PIN"), first ->
                askPin(t("Confirmă codul PIN", "Confirm the PIN"), second -> {
                    if (first.equals(second)) {
                        prefs.edit().putString(KEY_PIN, hash(first)).apply();
                        then.run();
                    } else {
                        wrongPin();
                        modalCard.postDelayed(() -> createPin(then), 700);
                    }
                }));
    }

    private TextView[] pinBoxes;
    private Runnable pinReset;

    /**
     * Four boxes for a PIN: digit keys type it; with only the arrows, Up/Down change the digit
     * in the box, OK or Right moves on, Left goes back; Back cancels.
     */
    private void askPin(String title, final PinDone done) {
        final int[] values = {0, 0, 0, 0};
        final int[] at = {0};
        LinearLayout card = openModal(event -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return;
            }
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) {
                sounds.play("back");
                closeModal();
                return;
            }
            boolean advance = false;
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                values[at[0]] = code - KeyEvent.KEYCODE_0;
                advance = true;
            } else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                values[at[0]] = (values[at[0]] + (code == KeyEvent.KEYCODE_DPAD_UP ? 1 : 9)) % 10;
                sounds.play("move");
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                advance = true;
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT && at[0] > 0) {
                at[0]--;
                sounds.play("move");
            }
            if (advance) {
                sounds.play("select");
                if (at[0] == 3) {
                    StringBuilder pin = new StringBuilder();
                    for (int v : values) {
                        pin.append(v);
                    }
                    done.onPin(pin.toString());
                    return;
                }
                at[0]++;
            }
            paintPin(values, at[0]);
        });
        TextView head = text(16, true);
        head.setText(title);
        card.addView(head);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        pinBoxes = new TextView[4];
        for (int i = 0; i < 4; i++) {
            TextView box = text(22, true);
            box.setGravity(Gravity.CENTER);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(px(14));
            box.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(52), px(60));
            lp.leftMargin = px(6);
            lp.rightMargin = px(6);
            row.addView(box, lp);
            pinBoxes[i] = box;
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = px(16);
        card.addView(row, rlp);
        TextView hint = text(11, false);
        hint.setAlpha(0.55f);
        hint.setText(t("Cifrele telecomenzii, sau Sus/Jos și OK", "Digit keys, or Up/Down and OK"));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = px(12);
        card.addView(hint, hlp);
        paintPin(values, 0);
        pinReset = () -> {
            java.util.Arrays.fill(values, 0);
            at[0] = 0;
            paintPin(values, 0);
        };
    }

    private void paintPin(int[] values, int at) {
        for (int i = 0; i < 4; i++) {
            TextView box = pinBoxes[i];
            GradientDrawable shape = (GradientDrawable) box.getBackground();
            boolean here = i == at;
            shape.setColor(here ? 0xF2FFFFFF : 0x26FFFFFF);
            box.setTextColor(here ? 0xFF111111 : Color.WHITE);
            box.setText(i < at ? "•" : here ? String.valueOf(values[i]) : "");
        }
    }

    private void wrongPin() {
        sounds.play("back");
        if (modalCard == null) {
            return;
        }
        // A short shake, like a wrong passcode on Apple devices.
        android.animation.ObjectAnimator shake = android.animation.ObjectAnimator.ofFloat(modalCard,
                View.TRANSLATION_X, 0, -px(14), px(14), -px(10), px(10), -px(5), 0);
        shake.setDuration(420);
        shake.start();
        if (pinReset != null) {
            modalCard.postDelayed(pinReset, 450); // try again
        }
    }

    /** A thin ring that fills round the channel number while Right is held. */
    private static final class Ring extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        private float progress;

        Ring(Context context) {
            super(context);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        void setProgress(float p) {
            progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (progress <= 0.001f) {
                return;
            }
            float d = getResources().getDisplayMetrics().density;
            paint.setStrokeWidth(2.5f * d);
            float inset = 2f * d;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(box, 13 * d, 13 * d, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setShadowLayer(4 * d, 0, 0, 0x99FFFFFF);
            canvas.drawArc(box, -90, 360 * progress, false, paint);
            paint.setShadowLayer(0, 0, 0, 0);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (tv != null) {
            tv.reset();
        }
        finish();
    }

    /** Debugging: the screen's own layers into files/overlay-channels.png (the video cannot be captured). */
    private final android.content.BroadcastReceiver dump = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String what = intent.getStringExtra("show");
            if ("list".equals(what)) {
                showList();
            } else if ("banner".equals(what)) {
                showBanner();
            } else if ("lock".equals(what)) {
                openLockMenu();
            } else if ("pin".equals(what)) {
                askPin(t("Alege un cod PIN din 4 cifre", "Choose a 4-digit PIN"), pin -> closeModal());
            }
            handler.postDelayed(() -> {
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(),
                        android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(b);
                // A colourful stand-in for the picture, so the glass can be judged.
                android.graphics.Paint p = new android.graphics.Paint();
                p.setShader(new android.graphics.LinearGradient(0, 0, b.getWidth(), b.getHeight(),
                        new int[]{0xFF2E6FB0, 0xFFE0A040, 0xFF305A30, 0xFFB04040}, null,
                        android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, b.getWidth(), b.getHeight(), p);
                tv.setVisibility(View.INVISIBLE);
                root.setBackground(null);
                root.draw(c);
                tv.setVisibility(View.VISIBLE);
                root.setBackgroundColor(Color.BLACK);
                java.io.File out = new java.io.File(getExternalFilesDir(null), "overlay-channels.png");
                try (java.io.FileOutputStream f = new java.io.FileOutputStream(out)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f);
                } catch (java.io.IOException ignored) {
                    // Debug only.
                }
            }, 900);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(dump, new android.content.IntentFilter("ro.tinulica.tvhome.DUMP_CHANNELS"));
    }

    @Override
    protected void onDestroy() {
        guideWorker.shutdownNow();
        try {
            unregisterReceiver(dump);
        } catch (RuntimeException ignored) {
            // Not registered.
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
