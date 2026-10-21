package ro.tinulica.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two things TV Home does outside its own screen, both as overlays drawn over whatever is on
 * the TV (an accessibility service is the only way an app sees keys and draws there without
 * root; it has to be switched on once in Settings > Accessibility):
 *
 * - The remote's Source key, while watching an input or the antenna, opens our Sources panel
 *   instead of TCL's (TCL's is closed again through its own dismiss service). On the home
 *   screen the key is left to the launcher, which opens Control Center.
 * - When a device on HDMI turns on (a PlayStation, a laptop), a card comes up at the top
 *   right asking whether to switch to it, with a live preview when nothing else is playing.
 */
public final class SourcesService extends AccessibilityService {

    static final String TEST_PANEL = "ro.tinulica.tvhome.TEST_SOURCES";
    static final String TEST_CARD = "ro.tinulica.tvhome.TEST_CARD";
    private static final String LIVE_TV = "com.tcl.tv";
    private static final long PANEL_TIMEOUT = 20000;
    private static final long CARD_TIMEOUT = 25000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> states = new HashMap<>();
    private WindowManager windows;
    private TvInputManager inputs;
    private TvInputManager.TvInputCallback inputCallback;
    private SettingsActivity.Sounds sounds;
    private float dp;
    private boolean homeInFront = true;
    private FrameLayout panel;
    private FrameLayout card;
    private TvView preview;
    private final Runnable hidePanel = this::hidePanel;
    private final Runnable hideCard = this::hideCard;

    private final BroadcastReceiver tests = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ro.tinulica.tvhome.TEST_CLOSE".equals(intent.getAction())) {
                try {
                    sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                    android.util.Log.i("TVHome", "close system dialogs sent");
                } catch (RuntimeException e) {
                    android.util.Log.i("TVHome", "close system dialogs refused: " + e);
                }
            } else if ("ro.tinulica.tvhome.TEST_SESSION".equals(intent.getAction())) {
                volumeSession(intent.getBooleanExtra("on", true));
            } else if ("ro.tinulica.tvhome.TEST_WEATHER".equals(intent.getAction())) {
                int sky = intent.getIntExtra("sky", -1);
                int level = intent.getIntExtra("level", 0);
                if (sky < 0) {
                    checkWeather(true);
                } else if (level > 0) {
                    Weather.Alert a = new Weather.Alert();
                    a.level = level;
                    a.type = intent.getIntExtra("type", 1);
                    a.headline = "Intensificări ale vântului cu viteze de 50...70 km/h și izolat de peste 80 km/h.";
                    a.area = "București";
                    a.expires = System.currentTimeMillis() + 20 * 3600_000L;
                    showAlert(a);
                } else {
                    Weather.Now n = new Weather.Now();
                    int[] codes = {0, 0, 2, 3, 45, 53, 63, 73, 95, 3, 0};
                    n.code = codes[Math.max(0, Math.min(10, sky))];
                    n.day = sky != WeatherCard.MOON;
                    n.temp = sky == WeatherCard.SNOW ? -2 : sky == WeatherCard.HEAT ? 36 : 14;
                    n.high = n.temp + 4;
                    n.low = n.temp - 5;
                    n.city = "București";
                    showWeather(n, intent.getIntExtra("was", -1));
                }
            } else if ("ro.tinulica.tvhome.TEST_HOLD".equals(intent.getAction())) {
                // For recording: Back held (the ring fills), then let go (the app switcher).
                backKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                handler.postDelayed(() -> backKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)),
                        intent.getIntExtra("ms", 1700));
            } else if ("ro.tinulica.tvhome.DUMP_GLYPHS".equals(intent.getAction())) {
                dumpGlyphs();
            } else if ("ro.tinulica.tvhome.TEST_WAKE".equals(intent.getAction())) {
                wakeIntro();
            } else if ("ro.tinulica.tvhome.TEST_NOTICE".equals(intent.getAction())) {
                showNotice("Magazin Google Play", getApplicationInfo().loadIcon(getPackageManager()),
                        "Netflix a fost actualizat", "Versiunea nouă aduce subtitrări mai clare și redare mai rapidă.");
            } else if ("ro.tinulica.tvhome.TEST_VOLUME".equals(intent.getAction())) {
                volumeKey = intent.getIntExtra("key", KeyEvent.KEYCODE_VOLUME_UP);
                volumeStep();
                volumeKey = 0;
            } else if ("ro.tinulica.tvhome.DUMP".equals(intent.getAction())) {
                dumpOverlays();
            } else if ("ro.tinulica.tvhome.TEST_SWITCHER".equals(intent.getAction())) {
                showSwitcher();
            } else if (TEST_PANEL.equals(intent.getAction())) {
                showPanel();
            } else if (TEST_CARD.equals(intent.getAction())) {
                String id = intent.getStringExtra("input");
                TvInputInfo info = id != null ? inputs.getTvInputInfo(id) : null;
                if (info == null) {
                    List<TvInputInfo> list = passthrough();
                    info = list.isEmpty() ? null : list.get(0);
                }
                if (info != null) {
                    showCard(info, intent.getBooleanExtra("preview", true));
                }
            }
        }
    };

    /** The screen behind our overlays, blurred, or null over protected video (it captures black). */
    private android.graphics.Bitmap behind;
    private final LensDrawable.Backdrop backdrop = new LensDrawable.Backdrop();

    /**
     * Liquid Glass needs what is behind it, and this TV has no window blur: the service takes
     * one screenshot just before an overlay appears, shrinks and blurs it, and the overlay's
     * glass panes refract that (the same lens as the dock). Then {@code then} runs.
     */
    private void captureBehind(final Runnable then) {
        behind = null;
        if (android.os.Build.VERSION.SDK_INT < 30) {
            then.run();
            return;
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    try {
                        android.hardware.HardwareBuffer buffer = result.getHardwareBuffer();
                        android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hw != null) {
                            android.graphics.Bitmap soft = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            android.graphics.Rect r = tclVolumeRect();
                            if (soft.getWidth() >= r.right && soft.getHeight() >= r.bottom
                                    && (cover == null || cover.getVisibility() != View.VISIBLE)) {
                                coverPatch = android.graphics.Bitmap.createBitmap(soft, r.left, r.top, r.width(), r.height());
                                patchAt = android.os.SystemClock.uptimeMillis();
                            }
                            behind = frost(soft);
                            if (behind == null) {
                                coverPatch = null;
                            }
                        }
                        buffer.close();
                    } catch (RuntimeException ignored) {
                        behind = null;
                    }
                    backdrop.wallFront = behind;
                    backdrop.wallBack = null;
                    backdrop.wallMix = 1f;
                    backdrop.screenW = getResources().getDisplayMetrics().widthPixels;
                    backdrop.screenH = getResources().getDisplayMetrics().heightPixels;
                    then.run();
                }

                @Override
                public void onFailure(int errorCode) {
                    then.run();
                }
            });
        } catch (RuntimeException e) {
            then.run();
        }
    }

    /** Small, blurred and a little more saturated; null when the capture is (nearly) black. */
    private static android.graphics.Bitmap frost(android.graphics.Bitmap full) {
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(full, 240, 135, true);
        full.recycle();
        int w = small.getWidth();
        int h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        long sum = 0;
        for (int c : px) {
            sum += ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
        }
        if (sum / (3L * px.length) < 10) {
            return null; // protected video: there is nothing to see through
        }
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, 4, true);
            box(tmp, px, w, h, 4, false);
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.3f);
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        canvas.drawBitmap(android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888), 0, 0, paint);
        small.recycle();
        return out;
    }

    private static void box(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h;
        int lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            int rs = 0;
            int gs = 0;
            int bs = 0;
            int count = 0;
            for (int k = -r; k <= r; k++) {
                int i = Math.max(0, Math.min(n - 1, k));
                int c = src[horizontal ? line * w + i : i * w + line];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
                count++;
            }
            for (int i = 0; i < n; i++) {
                dst[horizontal ? line * w + i : i * w + line] = 0xFF000000 | ((rs / count) << 16) | ((gs / count) << 8) | (bs / count);
                int out = Math.max(0, i - r);
                int in = Math.min(n - 1, i + r + 1);
                int co = src[horizontal ? line * w + out : out * w + line];
                int ci = src[horizontal ? line * w + in : in * w + line];
                rs += ((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF);
                gs += ((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF);
                bs += (ci & 0xFF) - (co & 0xFF);
            }
        }
    }

    /**
     * A pane of Liquid Glass for one of our overlay views: the blurred screen refracted through
     * the lens, under a light tint and a specular rim; over protected video (nothing captured),
     * a darker tinted glass so text still reads. Returns the tint layer for focus changes.
     */
    // ------------------------------------------------------------------ notification cards

    /** The running service, for NoticeListener (same process). */
    static volatile SourcesService instance;

    /** Still connected to the system: TCL's player can stop accessibility services and leave
     *  this one running but cut off (its overlays then fail with a null window token). */
    boolean connected() {
        try {
            return getServiceInfo() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long reconnectedAt;

    /** Switches this service off and on in the settings (with the permission `tvtool surse`
     *  gave), which makes Android connect it again. At most once a minute. */
    static void reconnect(final android.content.Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - reconnectedAt < 60_000 || context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        reconnectedAt = now;
        try {
            final android.content.ContentResolver cr = context.getContentResolver();
            final String me = context.getPackageName() + "/" + SourcesService.class.getName();
            String list = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
            if (list == null || "null".equals(list)) {
                list = "";
            }
            final String with = (":" + list + ":").contains(":" + me + ":") ? list : list.isEmpty() ? me : list + ":" + me;
            String without = (":" + with + ":").replace(":" + me + ":", ":").replaceAll("^:+|:+$", "");
            android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", without);
            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", with);
                android.provider.Settings.Secure.putInt(cr, "accessibility_enabled", 1);
            }, 500);
        } catch (RuntimeException ignored) {
            // No permission any more.
        }
    }

    /** An overlay could not be shown: if we were cut off, reconnect. */
    private void overlayFailed() {
        if (!connected()) {
            reconnect(this);
        }
    }
    /**
     * A notification from any app or the system as a drop of Liquid Glass at the left edge, like
     * the weather: the app's picture, its name, the title and the text, the app's colour glowing
     * through the glass. It never takes the remote's focus and leaves after a few seconds.
     */
    void showNotice(final String app, final android.graphics.drawable.Drawable icon, final String title, final String text) {
        if (CinemaActivity.alive || quiet(this)) {
            return; // Cinema mode or Do Not Disturb: nothing interrupts
        }
        handler.post(() -> showDrop(noticeContent(app, icon, title, text), new DropScenes.AppGlow(icon),
                px(280), px(58), "notice.ogg", 7000, false));
    }

    private View noticeContent(String app, android.graphics.drawable.Drawable icon, String title, String text) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(px(16), 0, px(16), 0);
        if (icon != null) {
            android.widget.ImageView image = new android.widget.ImageView(this);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(8));
                }
            });
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(30), px(30));
            ilp.rightMargin = px(10);
            card.addView(image, ilp);
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView source = text(9, false);
        source.setIncludeFontPadding(false);
        source.setAlpha(0.7f);
        source.setText(app + "  ·  " + t("acum", "now"));
        words.addView(source);
        TextView head = text(12.5f, true);
        head.setIncludeFontPadding(false);
        head.setText(title);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = px(2);
        words.addView(head, hlp);
        if (text != null && !text.isEmpty() && !text.equals(title)) {
            TextView body = text(10.5f, false);
            body.setIncludeFontPadding(false);
            body.setAlpha(0.85f);
            body.setText(text);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = px(2);
            words.addView(body, blp);
        }
        card.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    /**
     * A message from the computer (MessageReceiver): a drop like any notice, shown even with Do Not
     * Disturb on and in Cinema mode, since it was asked for. False when the overlay cannot show.
     */
    boolean message(final String from, final String title, final String text) {
        if (windows == null || !connected()) {
            return false;
        }
        handler.post(() -> showDrop(noticeContent(from != null ? from : "TV Home",
                getApplicationInfo().loadIcon(getPackageManager()), title != null ? title : "", text),
                new DropScenes.AppGlow(null), px(320), px(58), "notice.ogg", 9000, true));
        return true;
    }

    // ------------------------------------------------------------------ messages from Claude

    private FrameLayout claudeLayer;
    private ClaudeCard claudeCard;
    private LiquidDrop claudeDrop;
    private Clawd clawd;
    private String claudeId;
    /** The height the Claude card takes at the top of the stack (open), 0 when there is none. */
    private int claudeReserved;

    /** Told when notices start or stop showing (the home screen moves its usage pill out of the way). */
    interface NoticeWatcher {
        void changed(boolean up);
    }

    private static NoticeWatcher noticeWatcher;
    private boolean noticesShowing;
    /** Each notice's timer to leave (paused while the remote is in the notices). */
    private final java.util.Map<LiquidDrop, Runnable> dropTimers = new java.util.HashMap<>();
    /** Which notice the remote has chosen (Left into them from the home screen), -1 when none. */
    private int chosenNotice = -1;

    /** The notices from the top: the Claude card first, then the others, not those leaving. */
    private List<LiquidDrop> noticeList() {
        List<LiquidDrop> all = new ArrayList<>();
        if (claudeLayer != null && claudeDrop != null) {
            all.add(claudeDrop);
        }
        for (LiquidDrop d : drops) {
            if (!d.isLeaving()) {
                all.add(d);
            }
        }
        return all;
    }

    /**
     * Into the notices with the remote: the top one is chosen (it comes forward, lit) and none
     * leaves on its own meanwhile. Up and Down go from one to the next, Back closes the chosen
     * one, OK opens a Claude card's details, Right (or any other key) goes back out.
     */
    boolean enterNotices() {
        List<LiquidDrop> all = noticeList();
        if (all.isEmpty()) {
            return false;
        }
        chosenNotice = 0;
        for (Runnable r : dropTimers.values()) {
            handler.removeCallbacks(r);
        }
        if (claudeCard != null && !claudeCard.waiting()) {
            handler.removeCallbacks(claudeTimeout);
        }
        sounds.play("move");
        showChosen();
        return true;
    }

    private void showChosen() {
        List<LiquidDrop> all = noticeList();
        if (all.isEmpty()) {
            leaveNotices();
            return;
        }
        chosenNotice = Math.max(0, Math.min(chosenNotice, all.size() - 1));
        for (int i = 0; i < all.size(); i++) {
            all.get(i).setChosen(i == chosenNotice);
        }
        // The chosen one opens up (all its words, a Claude card's details); the others make room.
        if (all.get(chosenNotice) == claudeDrop && claudeCard != null) {
            claudeCard.openNow();
        }
        placeDrops();
    }

    private void leaveNotices() {
        if (chosenNotice < 0) {
            return;
        }
        chosenNotice = -1;
        for (LiquidDrop d : noticeList()) {
            d.setChosen(false);
        }
        // They leave a little after, one by one.
        long at = 3500;
        for (LiquidDrop d : drops) {
            Runnable r = dropTimers.get(d);
            if (r != null && !d.isLeaving()) {
                handler.postDelayed(r, at);
                at += 900;
            }
        }
        if (claudeCard != null && !claudeCard.waiting()) {
            handler.postDelayed(claudeTimeout, 6000);
        }
    }

    private boolean noticeKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean press = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        List<LiquidDrop> all = noticeList();
        if (all.isEmpty()) {
            leaveNotices();
            return false;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (press) {
                    int to = chosenNotice + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if (to < 0 || to >= all.size()) {
                        sounds.play("limit");
                    } else {
                        chosenNotice = to;
                        sounds.play("move");
                        showChosen();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (press) {
                    sounds.play("limit");
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (press) {
                    LiquidDrop d = all.get(chosenNotice);
                    sounds.play("back");
                    if (d == claudeDrop) {
                        claudeAnswered(claudeId, -1, "");
                    } else {
                        dismissDrop(d);
                    }
                    handler.postDelayed(this::showChosen, 60);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (press) {
                    LiquidDrop d = all.get(chosenNotice);
                    if (d == claudeDrop && claudeCard != null && claudeCard.waiting()) {
                        leaveNotices();    // its window takes the keys: the choices
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (press) {
                    sounds.play("move");
                    leaveNotices();
                }
                return true;
            default:
                leaveNotices();
                return false;
        }
    }

    static void watchNotices(NoticeWatcher w) {
        noticeWatcher = w;
    }

    /** Whether a notice, an alert or a Claude card is on the screen (not on its way out). */
    static boolean noticesUp() {
        SourcesService s = instance;
        return s != null && s.noticesShowing;
    }

    private void noticesChanged() {
        boolean up = claudeLayer != null;
        for (LiquidDrop d : drops) {
            up |= !d.isLeaving();
        }
        if (up != noticesShowing) {
            noticesShowing = up;
            NoticeWatcher w = noticeWatcher;
            if (w != null) {
                w.changed(up);
            }
        }
    }
    /** Messages for an account the profile in use is not linked to, kept until it is. */
    private static final List<Intent> held = new ArrayList<>();
    private final QuestionServer questions = new QuestionServer(this::claudeAnswered);
    private final Runnable claudeTimeout = () -> claudeAnswered(claudeId, -1, "");

    private static String extra(Intent intent, String key, boolean b64) {
        String v = intent.getStringExtra(key);
        if (v == null || !b64) {
            return v;
        }
        try {
            return new String(android.util.Base64.decode(v, android.util.Base64.DEFAULT),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return v;
        }
    }

    /**
     * A message from Claude (MessageReceiver): a ClaudeCard at the top left. With "options" it is
     * a question: the remote or the phone (QuestionServer) answers it, and the answer is written to
     * files/claude/answer-ID.txt for the computer to read. "dismiss" with an ID takes a card away;
     * "mood" says what Clawd does (else the words decide). False when our overlay cannot show.
     */
    boolean claude(final Intent intent) {
        if (windows == null || !connected()) {
            return false;
        }
        handler.post(() -> {
            boolean b64 = intent.getBooleanExtra("b64", false);
            String dismiss = intent.getStringExtra("dismiss");
            if (dismiss != null) {
                if (dismiss.equals(claudeId)) {
                    closeClaude();
                }
                return;
            }
            final String id = intent.getStringExtra("id") != null ? intent.getStringExtra("id")
                    : Long.toHexString(System.nanoTime());
            final String title = extra(intent, "title", b64);
            final String text = extra(intent, "text", b64);
            final String details = extra(intent, "details", b64);
            final String from = extra(intent, "from", b64);
            final String mood = extra(intent, "mood", b64);
            String opts = extra(intent, "options", b64);
            final List<String> options = new ArrayList<>();
            if (opts != null) {
                for (String o : opts.split("\\|")) {
                    if (!o.trim().isEmpty()) {
                        options.add(o.trim());
                    }
                }
            }
            android.graphics.Bitmap qr = null;
            String qrName = intent.getStringExtra("qr");
            java.io.File dir = getExternalFilesDir("claude");
            if (qrName != null && dir != null) {
                qr = android.graphics.BitmapFactory.decodeFile(new java.io.File(dir, qrName).getPath());
            }
            final android.graphics.Bitmap code = qr;
            final int timeout = intent.getIntExtra("timeout", options.isEmpty() ? 0 : 600);
            captureBehind(() -> showClaude(id, from, title, text, details, options, code, timeout, mood));
        });
        return true;
    }

    /**
     * The message as a drop of Liquid Glass flowing out of the left edge, like every notice, a
     * little wider and taller; with details it opens further once settled, growing downwards.
     * Clawd lands on its top edge. A question's window takes the remote's keys.
     */
    private void showClaude(final String id, String from, String title, String text, String details,
                            List<String> options, android.graphics.Bitmap qr, int timeout, String mood) {
        closeClaude();
        final boolean asking = !options.isEmpty();
        claudeId = id;
        final FrameLayout layer = new FrameLayout(this) {
            private long downAt;
            private int lastCode;

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                boolean dpad = code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT;
                if (dpad && event.getAction() == KeyEvent.ACTION_DOWN) {
                    long t = event.getEventTime();
                    if (event.getRepeatCount() > 0) {
                        // This remote's "released" comes late and Android repeats a short press:
                        // a repeat only counts once the key is really held.
                        if (t - event.getDownTime() < 750) {
                            return true;
                        }
                    } else if (code == lastCode && t - downAt < 140) {
                        return true;   // the same press arriving twice
                    }
                    downAt = t;
                    lastCode = code;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        claudeAnswered(claudeId, -1, "");
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        layer.setClipChildren(false);
        final ClaudeCard card = new ClaudeCard(this, dp, from, title, text, details, options, qr, null,
                (index, label) -> claudeAnswered(id, index, label));
        int w = px(asking ? 420 : 380);
        card.measureFor(w);
        int h = card.compactHeight();
        // Out small (the name and the title), then it opens wider and down.
        final LiquidDrop drop = new LiquidDrop(this, dp, card, card.compactWidth(), h);
        drop.setCorner(px(24));
        drop.allowGrowth(card.extraWidth(), card.extraHeight());
        card.setGrowth((wider, taller) -> {
            drop.setExtraWidth(wider);
            drop.setExtra(taller);
        });
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        drop.setBehind(behind, m.widthPixels, m.heightPixels);
        // First at the top (below the usage pill, with room for Clawd); the notices make room under it.
        int y = claudeTop();
        claudeReserved = h + card.extraHeight();
        placeDrops();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(drop.viewWidth(), drop.viewHeight(),
                Gravity.TOP | Gravity.START);
        lp.topMargin = y - (drop.viewHeight() - h) / 2;
        layer.addView(drop, lp);
        final Clawd mascot = new Clawd(this, dp, Clawd.moodFor(mood, title, text, asking));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(w - px(44), Clawd.viewHeight(dp),
                Gravity.TOP | Gravity.START);
        mlp.leftMargin = px(16);
        mlp.topMargin = y - Clawd.viewHeight(dp) + px(1);   // standing on the glass
        layer.addView(mascot, mlp);
        try {
            windows.addView(layer, overlayParams(asking));
        } catch (RuntimeException e) {
            overlayFailed();
            return;
        }
        claudeLayer = layer;
        claudeCard = card;
        claudeDrop = drop;
        clawd = mascot;
        noticesChanged();
        drop.appear();
        card.appear();
        mascot.arrive(1350);
        chime("notice.ogg");
        if (asking) {
            QuestionServer.Question q = new QuestionServer.Question();
            q.id = id;
            q.from = from;
            q.title = title;
            q.text = text;
            q.details = details;
            q.options = options;
            questions.ask(q);
        }
        handler.removeCallbacks(claudeTimeout);
        long stay = asking ? timeout * 1000L
                : 9000L + Math.min(24000L, 45L * ((details == null ? 0 : details.length()) + (text == null ? 0 : text.length())));
        if (stay > 0) {
            handler.postDelayed(claudeTimeout, stay);
        }
    }

    /** An answer (index -1: closed without one): written down for the computer, the card leaves. */
    private void claudeAnswered(final String id, final int index, final String label) {
        handler.post(() -> {
            if (id == null || !id.equals(claudeId)) {
                return;
            }
            handler.removeCallbacks(claudeTimeout);
            questions.close(id);
            java.io.File dir = getExternalFilesDir("claude");
            if (dir != null && claudeCard != null && claudeCard.waiting()) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "answer-" + id + ".txt"))) {
                    out.write(((index < 0 ? "" : label) + "\n" + index + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException ignored) {
                    // The computer will time out.
                }
            }
            boolean chosen = index >= 0 && claudeCard != null && claudeCard.waiting();
            retireClaude(chosen, index, label);
        });
    }

    /** The card in place goes (flowing back into the edge); nothing is written down. */
    private void closeClaude() {
        handler.removeCallbacks(claudeTimeout);
        if (claudeId != null) {
            questions.close(claudeId);
        }
        retireClaude(false, -1, "");
    }

    /**
     * Sends the card in place away. Answered, its choice stays lit while Clawd jumps; then Clawd
     * hops off and the drop flows back into the edge. Its window stops taking keys at once.
     */
    private void retireClaude(boolean chosen, int index, String label) {
        final FrameLayout layer = claudeLayer;
        final ClaudeCard card = claudeCard;
        final LiquidDrop drop = claudeDrop;
        final Clawd mascot = clawd;
        claudeId = null;
        claudeLayer = null;
        claudeCard = null;
        claudeDrop = null;
        clawd = null;
        noticesChanged();
        if (layer == null) {
            return;
        }
        // The notices under it flow up as it goes.
        handler.postDelayed(() -> {
            if (claudeLayer == null) {
                claudeReserved = 0;
                placeDrops();
            }
        }, chosen ? 1800 : 300);
        try {
            windows.updateViewLayout(layer, overlayParams(false));
        } catch (RuntimeException ignored) {
            // Gone already.
        }
        final Runnable gone = () -> removeQuietly(layer);
        Runnable leave = () -> {
            card.closing();
            mascot.leave();
            layer.postDelayed(() -> drop.disappear(gone), 200);
            handler.postDelayed(gone, 3000);   // in case the animation never ends
        };
        if (chosen) {
            sounds.play("select");
            card.answered(index, label);
            mascot.jump();
            handler.postDelayed(leave, 1500);
        } else {
            leave.run();
        }
    }

    /** A message for an account this profile is not linked to: kept until one that is comes. */
    static void hold(Intent intent) {
        synchronized (held) {
            held.add(intent);
            while (held.size() > 4) {
                held.remove(0);
            }
        }
    }

    /** Another profile is in use: what was kept for its account shows now, one after another. */
    static void profileChanged(final Context context) {
        List<Intent> now = new ArrayList<>();
        synchronized (held) {
            for (java.util.Iterator<Intent> it = held.iterator(); it.hasNext(); ) {
                Intent i = it.next();
                if (ClaudeLink.shows(context, i.getStringExtra("account"))) {
                    now.add(i);
                    it.remove();
                }
            }
        }
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int k = 0; k < now.size(); k++) {
            final Intent i = now.get(k);
            main.postDelayed(() -> new MessageReceiver().onReceive(context.getApplicationContext(), i), 900 + 5000L * k);
        }
    }

    /** A short message (what just happened, as a toast would say it) as a drop at the left edge. */
    static void say(Context context, String title) {
        say(context, title, null);
    }

    static void say(Context context, final String title, final String detail) {
        final SourcesService s = instance;
        if (s != null && s.windows != null && s.connected()) {
            s.handler.post(() -> {
                WeatherCard card = new WeatherCard(s, s.dp, null, title, detail);
                card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int w = Math.max(s.px(170), Math.min(s.px(340), card.getMeasuredWidth() + s.px(10)));
                s.showDrop(card, null, w, s.px(detail == null ? 44 : 54), null, detail == null ? 3500 : 6000, true);
            });
        } else {
            android.widget.Toast.makeText(context, detail == null ? title : title + "\n" + detail,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Cinema mode switching on or off, as a drop: the glass ring loading, the words, the switch
     * flipping, a projector's beam in a room whose lights go down (or up) behind the glass.
     * False when our overlay cannot be shown (the caller then shows its own capsule).
     */
    boolean cinemaDrop(final boolean on, final String detail) {
        if (windows == null || !connected()) {
            return false;
        }
        handler.post(() -> {
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(px(14), 0, px(14), 0);
            final CinemaMark.Spin mark = new CinemaMark.Spin(this);
            mark.setRing(0f);
            mark.setPlay(0f);
            content.addView(mark, new LinearLayout.LayoutParams(px(30), px(30)));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView head = text(12.5f, true);
            head.setIncludeFontPadding(false);
            head.setText("Mod Cinema");
            words.addView(head);
            TextView body = text(10.5f, false);
            body.setIncludeFontPadding(false);
            body.setAlpha(0.8f);
            body.setText(detail);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.topMargin = px(2);
            words.addView(body, blp);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
            wlp.leftMargin = px(10);
            content.addView(words, wlp);
            final CinemaMark.Toggle toggle = new CinemaMark.Toggle(this, dp, !on);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(40), px(24));
            tlp.leftMargin = px(12);
            content.addView(toggle, tlp);
            words.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = px(14 + 30 + 10) + words.getMeasuredWidth() + px(12 + 40 + 16);
            showDrop(content, new DropScenes.Projector(on, dp), w, px(52), null, 2300, true);
            handler.postDelayed(mark::load, 450);
            handler.postDelayed(() -> toggle.set(on), 1000);
        });
        return true;
    }

    /** Do Not Disturb (Control Center): no notices, no weather; only red warnings come through. */
    static boolean quiet(Context context) {
        return context.getSharedPreferences("home", MODE_PRIVATE).getBoolean("dnd", false);
    }

    // ------------------------------------------------------------------ the sleep timer

    /** When the TV switches itself off (Control Center › Somn), or 0. */
    static long sleepAt(Context context) {
        long at = context.getSharedPreferences("home", MODE_PRIVATE).getLong("sleep_at", 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    /** Sets the time the TV switches itself off, `length` after now (0 cancels it). */
    void setSleep(long at, long length) {
        getSharedPreferences("home", MODE_PRIVATE).edit().putLong("sleep_at", at).putLong("sleep_len", length).apply();
        planSleep();
    }

    private final Runnable sleepWarn = this::warnSleep;
    private final Runnable sleepNow = this::sleepNow;

    private void planSleep() {
        handler.removeCallbacks(sleepWarn);
        handler.removeCallbacks(sleepNow);
        long at = sleepAt(this);
        if (at == 0) {
            return;
        }
        long left = at - System.currentTimeMillis();
        if (left > 70_000L) {
            handler.postDelayed(sleepWarn, left - 60_000L);
        }
        handler.postDelayed(sleepNow, left);
    }

    /** A minute before: a night sky behind the glass, and how to keep the TV on. */
    private void warnSleep() {
        showDrop(new WeatherCard(this, dp, null, t("Televizorul se oprește într-un minut", "The TV turns off in a minute"),
                        t("Control Center › Somn, ca să rămână pornit", "Control Center › Sleep to keep it on")),
                new WeatherCard.Scene(WeatherCard.MOON, false, 0, dp), px(300), px(54), "notice.ogg", 12000, true);
    }

    private void sleepNow() {
        getSharedPreferences("home", MODE_PRIVATE).edit().remove("sleep_at").apply();
        // To standby the way the power button does it (Android 9+: accessibility's "lock screen").
        boolean asleep = performGlobalAction(8 /* GLOBAL_ACTION_LOCK_SCREEN */);
        cinemaLog(this, "sleep timer: " + (asleep ? "switched off" : "could not switch off"));
    }

    // ------------------------------------------------------------------ weather and warnings

    private long lastUserAt;
    private boolean wakeCheck;

    /** The TV was switched on: the weather (and switched off: the sleep timer is done with). */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                setSleep(0, 0);
                return;
            }
            wakeIntro();
            handler.postDelayed(() -> {
                wakeCheck = true;
                checkWeather(false);
            }, 8000);
        }
    };

    // ------------------------------------------------------------------ waking up

    private FrameLayout wakeLayer;

    /**
     * The TV coming on, our way. This TCL resumes from standby out of memory, so there is no boot
     * animation to replace (and the real one sits in a read-only part of the system): over
     * whatever the TV shows while it wakes, "hello" as an iPhone says it (HelloIntro).
     */
    private void wakeIntro() {
        if (windows == null || wakeLayer != null
                || !getSharedPreferences("home", MODE_PRIVATE).getBoolean("wake_intro", true)) {
            return;
        }
        final HelloIntro hello = new HelloIntro(this, dp);
        try {
            windows.addView(hello, overlayParams(false));
        } catch (RuntimeException e) {
            overlayFailed();
            return;
        }
        wakeLayer = hello;
        helloSound();
        final MainActivity home = MainActivity.current;
        if (home != null) {
            home.helloStarted();
        }
        hello.play(() -> {
            if (home != null) {
                home.arrive();
            }
        }, () -> {
            try {
                windows.removeView(hello);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            if (wakeLayer == hello) {
                wakeLayer = null;
            }
        });
    }

    /** hello's sound (assets/sounds/hello.ogg, made by tools/hello_sound.py): bass, glass, a warm chord. */
    private void helloSound() {
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("sounds/hello.ogg")) {
            final android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            player.setVolume(0.7f, 0.7f);
            player.setOnCompletionListener(android.media.MediaPlayer::release);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            // Silent.
        }
    }

    /**
     * For the presentation video: TV Home's own symbols, large, as PNGs in the app's external
     * files ("glyphs"), which adb can read: the round shortcuts' symbols, the moon and the clock
     * of Control Center's switches (on), and Cinema mode's ring.
     */
    private void dumpGlyphs() {
        java.io.File dir = getExternalFilesDir("glyphs");
        if (dir == null) {
            return;
        }
        String[] kinds = {"power", "hdmi", "antenna", "tv", "wifi", "bluetooth", "moon", "timer", "image", "access",
                "search", "camera", "folder", "gear", "list"};
        for (String kind : kinds) {
            savePng(Glyphs.symbol(this, kind, 256), new java.io.File(dir, kind + ".png"));
        }
        savePng(CinemaMark.bitmap(512), new java.io.File(dir, "cinema.png"));
        for (int kind : new int[]{CcIcon.MOON, CcIcon.TIMER}) {
            CcIcon icon = new CcIcon(this, dp, kind, true);
            icon.setTimer(System.currentTimeMillis() + 40 * 60_000L, 60 * 60_000L);
            icon.measure(View.MeasureSpec.makeMeasureSpec(256, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(256, View.MeasureSpec.EXACTLY));
            icon.layout(0, 0, 256, 256);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
            icon.draw(new android.graphics.Canvas(b));
            savePng(b, new java.io.File(dir, kind == CcIcon.MOON ? "dnd_on.png" : "sleep_on.png"));
        }
    }

    private static void savePng(android.graphics.Bitmap b, java.io.File f) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } catch (java.io.IOException ignored) {
            // Best effort.
        }
    }

    /** The next look at the sky (see planWeather). */
    private final Runnable weatherTick = () -> checkWeather(false);

    /**
     * When to look again. The internet does not tell the TV that the weather changed, so it asks,
     * but only when it matters: a minute after the moment the forecast says the sky turns
     * (clear, cloudy, fog, rain, snow, storm), and otherwise every half hour, for ANM's
     * warnings (issued at any time) and a fresher forecast. Each look is two small requests.
     */
    private void planWeather(Weather.Now n, boolean reached) {
        long now = System.currentTimeMillis();
        long next = now + (reached ? 30 : 15) * 60_000L;
        if (n != null && n.changeAt > now) {
            next = Math.min(next, n.changeAt + 60_000L);
        }
        handler.removeCallbacks(weatherTick);
        handler.postDelayed(weatherTick, Math.max(60_000L, next - now));
    }

    private void checkWeather(final boolean force) {
        final android.content.SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        final boolean weatherOn = prefs.getBoolean("notify_weather", true);
        final boolean alertsOn = prefs.getBoolean("notify_alerts", true);
        if (!weatherOn && !alertsOn && !force) {
            handler.removeCallbacks(weatherTick);
            handler.postDelayed(weatherTick, 60 * 60_000L);
            return;
        }
        new Thread(() -> {
            Weather.Place place = Weather.place(this);
            if (place == null) {
                handler.post(() -> planWeather(null, false));
                return;
            }
            final List<Weather.Alert> alerts = alertsOn || force ? Weather.alerts(place) : new ArrayList<Weather.Alert>();
            final Weather.Now now = weatherOn || force ? Weather.now(place) : null;
            handler.post(() -> {
                planWeather(now, now != null || !weatherOn);
                weatherResults(prefs, now, alerts, force);
            });
        }, "weather").start();
    }

    private void weatherResults(android.content.SharedPreferences prefs, Weather.Now now, List<Weather.Alert> alerts,
                                boolean force) {
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (CinemaActivity.alive || (power != null && !power.isInteractive())) {
            return; // Cinema mode or the screen off: they wait for the next check
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>(prefs.getStringSet("alerts_seen",
                new java.util.HashSet<String>()));
        long delay = 0;
        boolean quiet = quiet(this) && !force;
        for (final Weather.Alert a : alerts) {
            if (quiet && a.level < 4) {
                continue; // Do Not Disturb: shown once it is switched off, if still in force
            }
            if (seen.add(a.id) || force) {
                final long at = delay;
                handler.postDelayed(() -> showAlert(a), at);
                delay += 2500;
            }
        }
        while (seen.size() > 40) {
            seen.remove(seen.iterator().next());
        }
        prefs.edit().putStringSet("alerts_seen", seen).apply();
        if (now != null) {
            // Shown when the TV is switched on (not again within 20 minutes), and whenever the
            // sky changes: clear, cloudy, fog, rain, snow, storm.
            int before = prefs.getInt("weather_code", -1);
            boolean changed = before >= 0 && Weather.group(before) != Weather.group(now.code);
            boolean woke = wakeCheck && System.currentTimeMillis() - prefs.getLong("weather_at", 0) > 20 * 60_000L;
            wakeCheck = false;
            // Cloud coming and going (clear <-> cloudy) is told at most once an hour.
            boolean minor = changed && Weather.group(before) <= 1 && Weather.group(now.code) <= 1
                    && System.currentTimeMillis() - prefs.getLong("weather_at", 0) < 60 * 60_000L;
            prefs.edit().putInt("weather_code", now.code).apply();
            if (force || ((woke || changed) && !minor && !quiet)) {
                prefs.edit().putLong("weather_at", System.currentTimeMillis()).apply();
                final Weather.Now n = now;
                final int was = changed ? before : -1;
                handler.postDelayed(() -> showWeather(n, was), delay);
            }
        }
    }

    /** The weather now; `was` is the code before a change (or -1), for "Starts raining" and the like. */
    private void showWeather(Weather.Now n, int was) {
        String title = was >= 0 ? Weather.change(was, n.code) : Weather.words(n.code);
        String detail = (n.city.isEmpty() ? "" : n.city + "  ·  ") + "max " + Math.round(n.high) + "°  min "
                + Math.round(n.low) + "°";
        String big = Math.round(n.temp) + "°";
        WeatherCard.Scene scene = new WeatherCard.Scene(Weather.sky(n.code, n.day), n.day, 0, dp);
        List<TaskList.Task> tasks = TaskList.load(this);
        if (tasks.isEmpty()) {
            showDrop(new WeatherCard(this, dp, big, title, detail), scene, false, 8000);
            return;
        }
        // The weather, and under it the day's tasks (Claude keeps them): a taller drop.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(new WeatherCard(this, dp, big, title, detail), new LinearLayout.LayoutParams(-1, px(54)));
        View line = new View(this);
        line.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Math.max(1, px(0.6f)));
        llp.leftMargin = px(18);
        llp.rightMargin = px(16);
        column.addView(line, llp);
        TaskList list = new TaskList(this, dp, tasks);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.leftMargin = px(18);
        tlp.rightMargin = px(16);
        tlp.topMargin = px(9);
        column.addView(list, tlp);
        // Out as the weather alone, then it opens down to the tasks.
        column.measure(View.MeasureSpec.makeMeasureSpec(px(300), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int rest = column.getMeasuredHeight() + px(12) - px(54);
        showDrop(column, scene, px(300), px(54), "notice.ogg", 12000 + 2500L * tasks.size(), false, px(24), rest);
    }

    private void showAlert(Weather.Alert a) {
        String title = Weather.alertLevel(a.level).charAt(0) + Weather.alertLevel(a.level).substring(1).toLowerCase(Locale.ROOT)
                + "  ·  " + Weather.alertName(a.type);
        showDrop(new WeatherCard(this, dp, null, title, a.headline),
                new WeatherCard.Scene(Weather.alertSky(a.type), true, Weather.alertColour(a.level), dp), true, 12000);
    }

    private FrameLayout dropLayer;
    private final List<LiquidDrop> drops = new ArrayList<>();

    private void showDrop(final WeatherCard card, final WeatherCard.Scene scene, final boolean alert, final long showFor) {
        showDrop(card, scene, px(250), px(54), alert ? "alert.ogg" : "notice.ogg", showFor, false);
    }

    /**
     * A notice as a drop of Liquid Glass at the left edge (see LiquidDrop): it wells out of the
     * edge, stays clinging to it, and is drawn back in; several stack downwards (at most three).
     * `anyway`: also in Cinema mode (an answer to what the viewer just did, or the sleep timer).
     */
    private void showDrop(final View content, final LiquidDrop.Backdrop scene, final int w, final int h,
                          final String sound, final long showFor, final boolean anyway) {
        showDrop(content, scene, w, h, sound, showFor, anyway, 0, 0);
    }

    /** `opens`: how much taller it gets once it has flowed out (it comes out `h` tall first). */
    private void showDrop(final View content, final LiquidDrop.Backdrop scene, final int w, final int h,
                          final String sound, final long showFor, final boolean anyway, final int corner,
                          final int opens) {
        if ((CinemaActivity.alive && !anyway) || windows == null) {
            return;
        }
        captureBehind(() -> {
            if (dropLayer == null) {
                dropLayer = new FrameLayout(this);
                dropLayer.setClipChildren(false);
                try {
                    windows.addView(dropLayer, overlayParams(false));
                } catch (RuntimeException e) {
                    overlayFailed();
                    dropLayer = null;
                    return;
                }
            }
            int staying = 0;
            for (LiquidDrop d : drops) {
                staying += d.isLeaving() ? 0 : 1;
            }
            for (int i = 0; i < drops.size() && staying >= 3; i++) {
                if (!drops.get(i).isLeaving()) {
                    dismissDrop(drops.get(i)); // the oldest makes room
                    staying--;
                }
            }
            // They fit the screen's height: the oldest make room when the new one would not.
            int room = getResources().getDisplayMetrics().heightPixels - px(60) - dropsTop();
            int used = h;
            for (LiquidDrop d : drops) {
                used += d.isLeaving() ? 0 : d.capsuleHeight() + px(30);
            }
            for (int i = 0; i < drops.size() && used > room; i++) {
                LiquidDrop d = drops.get(i);
                if (!d.isLeaving()) {
                    used -= d.capsuleHeight() + px(30);
                    dismissDrop(d);
                }
            }
            final LiquidDrop drop = new LiquidDrop(this, dp, content, w, h);
            // A long notice stays a panel with soft corners, not a fat pill.
            drop.setCorner(corner > 0 ? corner : px(26));
            drop.allowGrowth(opens + px(150));
            drop.setOpensText(true);
            drop.setOnResize(this::placeDrops);
            if (opens > 0) {
                drop.openBy(opens, 800);
            }
            if (scene != null) {
                drop.setBackdrop(scene);
            }
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            drop.setBehind(behind, m.widthPixels, m.heightPixels);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(drop.viewWidth(), drop.viewHeight(),
                    Gravity.TOP | Gravity.START);
            int y = dropsTop();
            for (LiquidDrop d : drops) {
                y += d.capsuleHeight() + px(30);
            }
            lp.topMargin = y + h / 2 - drop.viewHeight() / 2;
            dropLayer.addView(drop, lp);
            drops.add(drop);
            noticesChanged();
            drop.appear();
            if (sound != null) {
                chime(sound);
            }
            Runnable timer = () -> dismissDrop(drop);
            dropTimers.put(drop, timer);
            if (chosenNotice < 0) {
                handler.postDelayed(timer, showFor);
            }
        });
    }

    private void dismissDrop(final LiquidDrop drop) {
        Runnable timer = dropTimers.remove(drop);
        if (timer != null) {
            handler.removeCallbacks(timer);
        }
        drop.disappear(() -> {
            drops.remove(drop);
            if (dropLayer != null) {
                dropLayer.removeView(drop);
            }
            placeDrops();
            noticesChanged();
        });
        noticesChanged();
        if (chosenNotice >= 0) {
            handler.post(this::showChosen);
        }
    }

    /** Where the Claude card's glass starts, with Clawd's room above (the usage pill moves away). */
    private int claudeTop() {
        return px(86);
    }

    /** Where the first notice goes: the top, or under the Claude card when one is showing. */
    private int dropsTop() {
        return claudeReserved > 0 ? claudeTop() + claudeReserved + px(30) : px(69);
    }

    /** One under the other from the top, with room for their fillets; the others flow up into place. */
    private void placeDrops() {
        int y = dropsTop();
        for (final LiquidDrop d : drops) {
            FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) d.getLayoutParams();
            int to = y + d.capsuleHeight() / 2 - d.viewHeight() / 2;
            y += d.capsuleHeight() + px(30);
            if (dl.topMargin != to) {
                float from = d.getTranslationY() + dl.topMargin - to;
                dl.topMargin = to;
                d.setLayoutParams(dl);
                d.setTranslationY(from);
                // Like water: a spring that settles with a small sway.
                new Spring(from, 0.5f, d::setTranslationY).to(0f, 0.5f, 0.72f);
            }
        }
    }

    private android.media.MediaPlayer chimePlayer;

    /** The notice's sound: a soft glass clink (or the alert's two notes), quiet. */
    private void chime(String name) {
        if (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("notify_sound", true)) {
            return;
        }
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("sounds/" + name)) {
            if (chimePlayer != null) {
                chimePlayer.release();
            }
            chimePlayer = new android.media.MediaPlayer();
            chimePlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            chimePlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            chimePlayer.setVolume(0.35f, 0.35f);
            chimePlayer.prepare();
            chimePlayer.start();
        } catch (Exception ignored) {
            // Silent.
        }
    }

    private GlassDrawable glassOn(View view, float radius, int clearTint, int videoTint) {
        GlassDrawable surface = new GlassDrawable(radius, dp, behind != null ? clearTint : videoTint);
        if (behind != null) {
            view.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    new LensDrawable(view, backdrop, radius, 1f, dp), surface}));
        } else {
            view.setBackground(surface);
        }
        return surface;
    }

    private String frontPkg;
    private long tclReplacedAt;
    private final java.util.Map<String, Boolean> activities = new java.util.HashMap<>();

    private boolean isActivity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean known = activities.get(key);
        if (known == null) {
            try {
                getPackageManager().getActivityInfo(new android.content.ComponentName(pkg, cls), 0);
                known = true;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                known = false;
            }
            activities.put(key, known);
        }
        return known;
    }

    /** The home screen changed (focus moved, wallpaper faded, grid opened): copy it again soon. */
    void refreshSoon() {
        handler.post(() -> {
            handler.removeCallbacks(refreshAmbient);
            handler.postDelayed(refreshAmbient, 1200);
        });
    }

    /** The home screen is changing: the resting copy is out of date, hide it and re-take it. */
    void screenChanging() {
        screenChangedAt = android.os.SystemClock.uptimeMillis();
        handler.post(() -> {
            if (cover != null && !volumeShowing()) {
                cover.setVisibility(View.INVISIBLE);
            }
        });
        refreshSoon();
    }

    private boolean volumeShowing() {
        return android.os.SystemClock.uptimeMillis() - coverShownAt < 3300;
    }

    /**
     * On the home screen the cover rests over TCL's volume spot with a fresh copy of what is
     * there (so it is invisible): TCL's bar is then hidden from its very first frame, and our
     * capsule simply appears on top. Anywhere else it stays hidden until the volume changes.
     */
    private long patchAt;
    private long screenChangedAt;

    private void coverRest() {
        ensureCover();
        if (cover == null || volumeShowing()) {
            return;
        }
        // Nothing rests on screen: a copy left there goes stale as soon as the screen moves.
        cover.setVisibility(View.INVISIBLE);
    }

    private void ensureCover() {
        if (coverLayer != null || windows == null) {
            return;
        }
        coverLayer = new FrameLayout(this);
        cover = new VolumeCover(this, dp);
        android.graphics.Rect r = tclVolumeRect();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r.width(), r.height(), Gravity.TOP | Gravity.START);
        lp.leftMargin = r.left;
        lp.topMargin = r.top;
        coverLayer.addView(cover, lp);
        cover.setVisibility(View.INVISIBLE);
        try {
            windows.addView(coverLayer, overlayParams(false));
        } catch (RuntimeException e) {
            overlayFailed();
            coverLayer = null;
            cover = null;
        }
    }
    // TCL's system takes the volume keys and draws its own bar in a fixed window at the right
    // edge. Our overlays sit above it, so on every volume change we cover exactly that spot:
    // over the home screen with a fresh copy of what is there (TCL's bar vanishes) and our thin
    // glass capsule on top; over video, which cannot be copied, with a glass panel of ours.
    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            android.util.Log.i("TVHome", "volume changed, stream " + stream);
            if (stream != android.media.AudioManager.STREAM_MUSIC) {
                return;
            }
            android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio == null) {
                return;
            }
            int max = Math.max(1, audio.getStreamMaxVolume(stream));
            int now = audio.getStreamVolume(stream);
            boolean muted = audio.isStreamMute(stream);
            if (android.os.SystemClock.uptimeMillis() - lastSessionVolume < 400) {
                return; // our own session changed it: no TCL bar to cover
            }
            int before = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", now);
            int limit = now == before && now >= max ? 1 : now == before && now == 0 && !muted ? -1 : 0;
            showVolume(muted ? 0f : now / (float) max, max, muted, limit, false);
        }
    };
    private FrameLayout coverLayer;
    private VolumeCover cover;
    private android.graphics.Bitmap coverPatch;   // the screen where TCL's bar appears, sharp
    private long coverShownAt;

    /** Where TCL draws its volume bar (1920x1080 layout), with a small margin. */
    private android.graphics.Rect tclVolumeRect() {
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        return new android.graphics.Rect(Math.round(1740 * k), Math.round(310 * k),
                Math.round(1880 * k), Math.round(770 * k));
    }

    /** Keeps a recent, clean copy of the home screen for the cover (never while it shows). */
    private final Runnable refreshAmbient = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            boolean busy = volumeShowing();
            if (!busy && homeInFront && switcher == null && panel == null && card == null && ring == null
                    && drops.isEmpty()) {
                // Hide the resting copy for a frame so the new capture shows the real screen.
                if (cover != null) {
                    cover.setVisibility(View.INVISIBLE);
                }
                handler.postDelayed(() -> captureBehind(SourcesService.this::coverRest), 60);
            } else if (!busy) {
                coverRest();
            }
            handler.postDelayed(this, homeInFront ? 20000 : 60000);
        }
    };

    /** {@code bare}: only the capsule (no TCL bar to hide); else covering TCL's spot. */
    private void showVolume(float level, int max, boolean muted, int limit, boolean bare) {
        showVolumeCover(level, max, muted, limit);
        if (cover != null) {
            cover.setBare(bare);
        }
    }

    private void showVolumeCover(float level, int max, boolean muted, int limit) {
        if (windows == null) {
            return;
        }
        ensureCover();
        if (cover == null) {
            return;
        }
        boolean wasHidden = cover.getVisibility() != View.VISIBLE || cover.getAlpha() < 0.99f;
        if (wasHidden) {
            // Instantly: TCL's bar is already on screen.
            cover.animate().cancel();
            cover.setAlpha(1f);
            cover.setVisibility(View.VISIBLE);
            // The copy only if it was taken after the screen last changed; otherwise our glass
            // panel covers TCL's bar (a stale copy would show as a block).
            cover.setPatch(null, homeInFront ? behind : null);
        }
        cover.setIdle(false);
        cover.bar().setAlpha(1f);
        coverShownAt = android.os.SystemClock.uptimeMillis();
        cover.bar().set(level, max, muted, limit);
        handler.removeCallbacks(hideCover);
        handler.postDelayed(hideCover, 3000);
    }

    private final Runnable hideCover = () -> {
        if (cover == null) {
            return;
        }
        coverShownAt = 0;
        // Everything leaves together (TCL's bar has gone by now too).
        cover.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            cover.setVisibility(View.INVISIBLE);
            cover.bar().hidden();
            cover.setAlpha(1f);
        }).start();
    };

    private android.media.session.MediaSession session;
    private long lastSessionVolume;

    /**
     * A media session that takes the volume keys (remote volume): Android then sends volume to
     * us instead of the system bar; we set the TV's volume silently and draw our own capsule.
     */
    private void volumeSession(boolean on) {
        if (!on) {
            if (session != null) {
                session.release();
                session = null;
            }
            return;
        }
        if (session != null) {
            return;
        }
        final android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        final int stream = android.media.AudioManager.STREAM_MUSIC;
        final int max = audio.getStreamMaxVolume(stream);
        android.media.VolumeProvider provider = new android.media.VolumeProvider(
                android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE, max, audio.getStreamVolume(stream)) {
            @Override
            public void onAdjustVolume(int direction) {
                android.util.Log.i("TVHome", "session volume adjust " + direction);
                int before = audio.getStreamVolume(stream);
                lastSessionVolume = android.os.SystemClock.uptimeMillis();
                if (direction != 0) {
                    audio.adjustStreamVolume(stream, direction, 0);
                }
                int after = audio.getStreamVolume(stream);
                setCurrentVolume(after);
                int limit = after == before && direction > 0 ? 1 : after == before && direction < 0 ? -1 : 0;
                showVolume(after / (float) max, max, audio.isStreamMute(stream), limit, true);
            }

            @Override
            public void onSetVolumeTo(int volume) {
                android.util.Log.i("TVHome", "session volume set " + volume);
                lastSessionVolume = android.os.SystemClock.uptimeMillis();
                audio.setStreamVolume(stream, volume, 0);
                setCurrentVolume(volume);
                showVolume(volume / (float) max, max, audio.isStreamMute(stream), 0, true);
            }
        };
        session = new android.media.session.MediaSession(this, "TV Home volume");
        session.setPlaybackToRemote(provider);
        session.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f).build());
        session.setActive(true);
        android.util.Log.i("TVHome", "volume session on");
    }

    private int volumeKey;
    private long volumeDownAt;
    private final Runnable volumeRepeat = new Runnable() {
        @Override
        public void run() {
            if (volumeKey == 0) {
                return;
            }
            if (android.os.SystemClock.uptimeMillis() - volumeDownAt > 6000) {
                volumeKey = 0; // the release never came: stop rather than run away
                return;
            }
            android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null) {
                int stream = android.media.AudioManager.STREAM_MUSIC;
                int max = Math.max(1, audio.getStreamMaxVolume(stream));
                int before = audio.getStreamVolume(stream);
                lastSessionVolume = android.os.SystemClock.uptimeMillis(); // flags 0: no TCL bar
                audio.adjustStreamVolume(stream, volumeKey == KeyEvent.KEYCODE_VOLUME_UP
                        ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
                int after = audio.getStreamVolume(stream);
                int limit = after == before ? (volumeKey == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1) : 0;
                boolean tclBarUp = android.os.SystemClock.uptimeMillis() - coverShownAt < 3000 && !homeInFront && session == null;
                showVolume(after / (float) max, max, audio.isStreamMute(stream), limit, session != null || !tclBarUp && !homeInFront);
            }
            handler.postDelayed(this, 80);
        }
    };
    private VolumeBar volumeBar;
    private final Runnable hideVolume = () -> {
        if (volumeBar != null) {
            final VolumeBar bar = volumeBar;
            bar.animate().alpha(0f).translationX(px(18)).setDuration(260)
                    .withEndAction(bar::hidden).start();
        }
    };

    /** One volume step (or mute), straight to the audio system, then the bar. */
    private void volumeStep() {
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        int stream = android.media.AudioManager.STREAM_MUSIC;
        int before = audio.getStreamVolume(stream);
        int max = Math.max(1, audio.getStreamMaxVolume(stream));
        int direction = volumeKey == KeyEvent.KEYCODE_VOLUME_UP ? android.media.AudioManager.ADJUST_RAISE
                : volumeKey == KeyEvent.KEYCODE_VOLUME_DOWN ? android.media.AudioManager.ADJUST_LOWER
                : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
        if (direction != android.media.AudioManager.ADJUST_TOGGLE_MUTE && audio.isStreamMute(stream)) {
            audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, 0);
        }
        try {
            audio.adjustStreamVolume(stream, direction, 0);
        } catch (RuntimeException ignored) {
            // Fixed-volume output.
        }
        int after = audio.getStreamVolume(stream);
        boolean muted = audio.isStreamMute(stream);
        int limit = 0;
        if (after == before && direction == android.media.AudioManager.ADJUST_RAISE && after >= max) {
            limit = 1;
        } else if (after == before && direction == android.media.AudioManager.ADJUST_LOWER && after <= 0) {
            limit = -1;
        }
        volumeMax = max;
        showVolume(muted ? 0f : after / (float) max, muted, limit);
    }

    private void showVolume(float level, boolean muted, int limit) {
        if (windows == null) {
            return;
        }
        if (volumeBar != null && volumeBar.getAlpha() < 0.05f && !volumeCapturing) {
            // Coming back after it hid: look behind again (the screen may have changed).
            volumeCapturing = true;
            final float l = level;
            final boolean m = muted;
            captureBehind(() -> {
                volumeCapturing = false;
                volumeBar.setBehind(behind);
                showVolumeNow(l, m, 0);
            });
            return;
        }
        if (volumeBar == null) {
            if (!volumeCapturing) {
                volumeCapturing = true;
                final float l = level;
                final boolean m = muted;
                captureBehind(() -> {
                    volumeCapturing = false;
                    createVolumeBar();
                    if (volumeBar != null) {
                        volumeBar.setBehind(behind);
                    }
                    showVolumeNow(l, m, 0);
                });
            }
            return;
        }
        showVolumeNow(level, muted, limit);
    }

    private boolean volumeCapturing;
    private int volumeMax = 100;

    private void createVolumeBar() {
        {
            VolumeBar bar = new VolumeBar(this, dp);
            FrameLayout holder = new FrameLayout(this);
            holder.setClipChildren(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(40), px(172), Gravity.END | Gravity.CENTER_VERTICAL);
            lp.rightMargin = px(36);
            holder.addView(bar, lp);
            try {
                windows.addView(holder, overlayParams(false));
                volumeBar = bar;
                bar.setAlpha(0f);
                bar.setTranslationX(px(18));
            } catch (RuntimeException e) {
                volumeBar = null;
            }
        }
    }

    private void showVolumeNow(float level, boolean muted, int limit) {
        VolumeBar bar = volumeBar;
        if (bar == null) {
            return;
        }
        bar.set(level, volumeMax, muted, limit);
        bar.animate().alpha(1f).translationX(0).setInterpolator(new DecelerateInterpolator(2.2f)).setDuration(220).start();
        handler.removeCallbacks(hideVolume);
        handler.postDelayed(hideVolume, 1600);
    }

    private TvInputInfo justOn;
    private long justOnAt;
    private final Runnable askAboutJustOn = () -> {
        TvInputInfo info = justOn;
        if (info != null) {
            showCard(info, true);
        }
    };

    private int px(float value) {
        return Math.round(value * dp);
    }

    /** Romanian when the TV is in Romanian, English otherwise. */
    private static String t(String ro, String en) {
        return "ro".equals(Locale.getDefault().getLanguage()) ? ro : en;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        inputs = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        dp = getResources().getDisplayMetrics().density;
        sounds = new SettingsActivity.Sounds(this, getSharedPreferences("home", MODE_PRIVATE));
        wakeCheck = true;
        handler.postDelayed(weatherTick, 25_000);
        planSleep();
        if (android.os.SystemClock.elapsedRealtime() < 4 * 60_000L) {
            handler.postDelayed(this::wakeIntro, 1200); // the TV has just started from cold: hello too
        }
        try {
            IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
            screen.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenOn, screen);
        } catch (RuntimeException ignored) {
            // Already registered.
        }
        if (inputs != null) {
            for (TvInputInfo info : passthrough()) {
                states.put(info.getId(), inputs.getInputState(info.getId()));
            }
            inputCallback = new TvInputManager.TvInputCallback() {
                @Override
                public void onInputStateChanged(String inputId, int state) {
                    Integer before = states.put(inputId, state);
                    TvInputInfo info = inputs.getTvInputInfo(inputId);
                    if (info == null || !info.isPassthroughInput() || info.isHidden(SourcesService.this)) {
                        return;
                    }
                    // Only a device that was off or unplugged and is now on.
                    if (state == TvInputManager.INPUT_STATE_CONNECTED && before != null
                            && before != TvInputManager.INPUT_STATE_CONNECTED) {
                        // Devices like the PlayStation ask the TV to switch to them (HDMI-CEC
                        // One Touch Play) and TCL's system opens its own player for that. Give it
                        // a moment: if it does, our input screen takes over; if not, ask.
                        justOn = info;
                        justOnAt = android.os.SystemClock.uptimeMillis();
                        handler.removeCallbacks(askAboutJustOn);
                        handler.postDelayed(askAboutJustOn, 1800);
                    }
                }

                @Override
                public void onInputAdded(String inputId) {
                    states.put(inputId, inputs.getInputState(inputId));
                }
            };
            inputs.registerCallback(inputCallback, handler);
        }
        IntentFilter vf = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        vf.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        registerReceiver(volumeChanged, vf);
        handler.postDelayed(refreshAmbient, 1500);
        IntentFilter filter = new IntentFilter(TEST_PANEL);
        filter.addAction(TEST_CARD);
        filter.addAction("ro.tinulica.tvhome.DUMP");
        filter.addAction("ro.tinulica.tvhome.TEST_VOLUME");
        filter.addAction("ro.tinulica.tvhome.TEST_NOTICE");
        filter.addAction("ro.tinulica.tvhome.TEST_WEATHER");
        filter.addAction("ro.tinulica.tvhome.TEST_WAKE");
        filter.addAction("ro.tinulica.tvhome.DUMP_GLYPHS");
        filter.addAction("ro.tinulica.tvhome.TEST_HOLD");
        filter.addAction("ro.tinulica.tvhome.TEST_SESSION");
        filter.addAction("ro.tinulica.tvhome.TEST_CLOSE");
        filter.addAction("ro.tinulica.tvhome.TEST_SWITCHER");
        registerReceiver(tests, filter);
    }

    /**
     * Debugging: draws each open overlay into files/overlay-*.png. Screenshots fail whenever
     * protected video is on screen, and our overlays are mostly shown over video.
     */
    private void dumpOverlays() {
        View[] views = {switcher, panel, card, ring, coverLayer, dropLayer};
        String[] names = {"switcher", "panel", "card", "ring", "volume", "notices"};
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null || v.getWidth() == 0) {
                continue;
            }
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(v.getWidth(), v.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.drawColor(0xFF3A3A40); // stands in for the video behind
            v.draw(c);
            java.io.File out = new java.io.File(getExternalFilesDir(null), "overlay-" + names[i] + ".png");
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(out)) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f);
            } catch (java.io.IOException ignored) {
                // Debug only.
            }
            b.recycle();
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        if (inputs != null && inputCallback != null) {
            inputs.unregisterCallback(inputCallback);
        }
        try {
            unregisterReceiver(volumeChanged);
        } catch (RuntimeException ignored) {
            // Never registered.
        }
        try {
            unregisterReceiver(tests);
        } catch (RuntimeException ignored) {
            // Never registered.
        }
        hidePanel();
        hideCard();
        hideSwitcher();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null && pendingPkg != null && pendingPkg.contentEquals(event.getPackageName())) {
            watchTitleApp();
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        if ("com.tcl.suspension".equals(pkg) && !homeInFront) {
            // TCL's input panel came up (the Source key, however it arrived): close it and show
            // ours instead. On the home screen the launcher handles Source (Control Center).
            long now = android.os.SystemClock.uptimeMillis();
            if (now - tclReplacedAt > 1500) {
                tclReplacedAt = now;
                handler.postDelayed(() -> {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    dismissTclPanel();
                }, 60);
                handler.postDelayed(() -> {
                    if (panel == null) {
                        showPanel();
                    }
                }, 260);
            }
            return;
        }
        // Only a real activity coming to the front counts: TCL's tuner and source services open
        // small windows of their own while a channel changes, which turned the volume session
        // off in the middle of watching.
        if (!isActivity(pkg, cls)) {
            return;
        }
        if (pkg.equals(getPackageName())) {
            if (cls.endsWith("Activity")) {
                homeInFront = cls.endsWith(".MainActivity");
                if (!homeInFront && cover != null && !volumeShowing()) {
                    cover.setVisibility(View.INVISIBLE);
                }
                // Where TV Home itself plays the picture, it also takes the volume keys.
                // (A media session cannot take the volume keys on TCL: its system handles them first.)
            }
            return;
        }
        if (cls.startsWith("android.widget.") || cls.startsWith("android.app.Dialog")
                || pkg.equals("com.tcl.suspension") || pkg.equals("android")) {
            return; // panels and dialogs over the same app
        }
        homeInFront = false;
        frontPkg = pkg;
        volumeSession(false);
        if (cover != null && !volumeShowing()) {
            cover.setVisibility(View.INVISIBLE);
        }
        if ("com.tcl.tv".equals(pkg) && justOn != null
                && android.os.SystemClock.uptimeMillis() - justOnAt < 6000) {
            String id = justOn.getId();
            justOn = null;
            handler.removeCallbacks(askAboutJustOn);
            hideCard();
            // TCL's player was opened for the device that just came on: show it in ours instead.
            start(SourceActivity.intent(this, id));
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ a title opened from Cinema

    // Streaming apps started cold ask "who's watching?" first and then forget the title they
    // were asked to open. So the title is remembered here: once the profile is picked (the
    // profile screen goes away, or OK is pressed on it), the app is asked again for that title.
    private static volatile Intent pendingTitle;
    private static volatile String pendingPkg;
    private static volatile long pendingAt;
    private static volatile boolean pendingCold;
    private boolean gateSeen;
    private boolean anyText;
    private boolean resent;
    private long lastLook;
    private static final java.util.regex.Pattern GATE = java.util.regex.Pattern.compile(
            "(?i)cine (se )?(uit|prive)|who.?s watching|alege(ți)? (un |profilul)|choose (a |your )?profile|profiluri|profiles");

    /** Called by Cinema after it opened `intent` (a title's page) in `pkg`. */
    static void titleOpened(Context context, String pkg, Intent intent) {
        boolean cold = true;
        try {
            android.app.usage.UsageStatsManager usage =
                    (android.app.usage.UsageStatsManager) context.getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = usage != null
                    ? usage.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 3600_000L, now) : null;
            for (int i = 0; stats != null && i < stats.size(); i++) {
                if (pkg.equals(stats.get(i).getPackageName()) && now - stats.get(i).getLastTimeUsed() < 15 * 60_000L) {
                    cold = false;
                }
            }
        } catch (RuntimeException ignored) {
            // Assume it starts cold.
        }
        pendingTitle = new Intent(intent);
        pendingPkg = pkg;
        pendingCold = cold;
        pendingAt = android.os.SystemClock.uptimeMillis();
        SourcesService s = instance;
        if (s != null) {
            s.gateSeen = false;
            s.anyText = false;
            s.resent = false;
        }
        cinemaLog(context, "title opened in " + pkg + (cold ? " (cold)" : " (warm)") + ": " + intent.getData());
    }

    private boolean pendingFresh() {
        if (pendingPkg != null && android.os.SystemClock.uptimeMillis() - pendingAt > 120_000L) {
            pendingPkg = null;
            pendingTitle = null;
        }
        return pendingPkg != null && !resent;
    }

    /** Reads what the title's app shows: the profile screen coming, then going. */
    private void watchTitleApp() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!pendingFresh() || now - lastLook < 400) {
            return;
        }
        lastLook = now;
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !pendingPkg.contentEquals(root.getPackageName())) {
            return;
        }
        StringBuilder words = new StringBuilder();
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 250 && words.length() < 600) {
            android.view.accessibility.AccessibilityNodeInfo n = queue.poll();
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t != null && t.length() > 0) {
                words.append(t).append(" | ");
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                android.view.accessibility.AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    queue.add(c);
                }
            }
        }
        if (words.length() == 0) {
            return;
        }
        boolean gate = GATE.matcher(words).find();
        if (!anyText) {
            anyText = true;
            cinemaLog(this, "title app shows: " + words);
        }
        if (gate && !gateSeen) {
            gateSeen = true;
            cinemaLog(this, "profile screen in " + pendingPkg);
        } else if (!gate && gateSeen) {
            // The profile was picked: now the title.
            resendTitle(900);
        }
    }

    /** OK on the profile screen (or, for apps that show us nothing, the first OK after a cold start). */
    private void titleKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_UP || !pendingFresh()
                || (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER)) {
            return;
        }
        if (gateSeen || (!anyText && pendingCold)) {
            resendTitle(2200);
        }
    }

    private void resendTitle(long delay) {
        if (resent || pendingTitle == null) {
            return;
        }
        resent = true;
        final Intent again = new Intent(pendingTitle);
        final String pkg = pendingPkg;
        handler.postDelayed(() -> {
            cinemaLog(this, "title sent again to " + pkg);
            start(again);
            pendingPkg = null;
            pendingTitle = null;
        }, delay);
    }

    static void cinemaLog(Context context, String line) {
        java.io.File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            return;
        }
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "cinema.log"), true)) {
            w.write(new java.util.Date() + "  " + line + "\n");
        } catch (java.io.IOException ignored) {
            // Debug only.
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int vk = event.getKeyCode();
        lastUserAt = android.os.SystemClock.uptimeMillis();
        if (pendingPkg != null) {
            titleKey(event);
        }
        if (vk == KeyEvent.KEYCODE_VOLUME_UP || vk == KeyEvent.KEYCODE_VOLUME_DOWN
                || vk == KeyEvent.KEYCODE_VOLUME_MUTE) {
            // The volume is ours everywhere: set without the TV's own UI, shown as a Liquid
            // Glass bar. (Keys through a filtering accessibility service also lose their
            // auto-repeat, so holding is repeated here.)
            // The press itself is left to the TV (one step, TCL's handling or our media session).
            // Keys that pass through a key-filtering accessibility service lose their
            // auto-repeat, so a held key would move one step only: while it is held, the steps
            // are repeated here, silently, and shown by our capsule.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // Ours goes up at the press itself, before TCL's system draws its bar.
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    int mx = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                    int nowV = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    boolean mu = am.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
                    showVolume(mu ? 0f : nowV / (float) mx, mx, mu, 0, false);
                }
            }
            if (vk != KeyEvent.KEYCODE_VOLUME_MUTE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    volumeKey = vk;
                    volumeDownAt = android.os.SystemClock.uptimeMillis();
                    handler.removeCallbacks(volumeRepeat);
                    handler.postDelayed(volumeRepeat, 420);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    volumeKey = 0;
                    handler.removeCallbacks(volumeRepeat);
                }
            }
            return false;
        }
        if (chosenNotice >= 0) {
            return noticeKey(event);
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return backKey(event);
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            // In Cinema, Home asks first ("Leave Cinema mode?") instead of leaving at once.
            Runnable ask = CinemaActivity.onHome;
            if (ask == null) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                ask.run();
            }
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_TV_INPUT) {
            return false;
        }
        if (homeInFront && panel == null) {
            return false; // the launcher opens Control Center
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (panel != null) {
                hidePanel();
            } else {
                showPanel();
            }
            for (long delay : new long[]{60, 200, 450, 900, 1500}) {
                handler.postDelayed(this::dismissTclPanel, delay);
            }
            // Over a channel TCL's dismiss command is ignored: when its panel shows up, it is
            // sent Back (it has focus), and ours stays.
            for (long delay : new long[]{180, 420, 800}) {
                handler.postDelayed(this::backOutOfTclPanel, delay);
            }
        }
        return true;
    }

    /**
     * Back held down opens the app switcher; a short press is passed on as a normal Back (an
     * accessibility service cannot let a key through after holding it, so it sends one).
     */
    private boolean backKey(KeyEvent event) {
        if (claudeLayer != null && claudeCard != null && claudeCard.waiting()) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                claudeAnswered(claudeId, -1, "");
            }
            return true;
        }
        if (backHeld) {
            // The rest of the press that opened the switcher (repeats, then the release) must
            // not reach it, or letting go of Back would close it again at once.
            if (event.getAction() == KeyEvent.ACTION_UP) {
                backHeld = false;
            }
            return true;
        }
        if (switcher != null || panel != null || card != null) {
            return false; // our own overlays handle Back themselves
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                backHeld = false;
                ready = false;
                holding = true;
                backDownAt = android.os.SystemClock.uptimeMillis();
                // A quick press never shows anything (and never pays for a screenshot).
                handler.postDelayed(startHold, 150);
            }
            return true;
        }
        holding = false;
        handler.removeCallbacks(startHold);
        handler.removeCallbacks(ringTick);
        if (ready) {
            // Released after the ring filled: now the switcher opens.
            ready = false;
            final HoldRing done = ring;
            ring = null;
            if (done != null) {
                done.pop(() -> removeQuietly(done));
            }
            buildSwitcher();
            return true;
        }
        removeRing();
        performGlobalAction(GLOBAL_ACTION_BACK);
        return true;
    }

    private static final long HOLD = 1100;
    private boolean backHeld;
    private boolean holding;
    private boolean ready;
    private long backDownAt;
    private HoldRing ring;
    private FrameLayout switcher;

    private final Runnable startHold = () -> captureBehind(() -> {
        if (holding && switcher == null && ring == null && windows != null) {
            HoldRing view = new HoldRing(this, dp, behind);
            try {
                windows.addView(view, overlayParams(false));
                ring = view;
            } catch (RuntimeException ignored) {
                overlayFailed();
                ring = null;
            }
            handler.post(this.ringTick);
        }
    });

    private final Runnable ringTick = new Runnable() {
        @Override
        public void run() {
            if (!holding) {
                return;
            }
            float p = (android.os.SystemClock.uptimeMillis() - backDownAt) / (float) HOLD;
            if (ring != null) {
                ring.setProgress(p);
            }
            if (p >= 1f) {
                // Full: the ring pulses ("you can let go") until Back is released.
                if (!ready) {
                    ready = true;
                    if (ring != null) {
                        ring.setReady();
                    }
                    sounds.play("move");
                }
            } else {
                // Every display frame, so the ring fills smoothly.
                android.view.Choreographer.getInstance().postFrameCallback(t -> run());
            }
        }
    };

    private void removeRing() {
        if (ring != null) {
            removeQuietly(ring);
            ring = null;
        }
    }

    /** Apps used in the last hours, most recent first (the TV keeps no other list for us). */
    private List<String> recentApps() {
        List<String> out = new ArrayList<>();
        android.app.usage.UsageStatsManager usage =
                (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usage == null) {
            return out;
        }
        long now = System.currentTimeMillis();
        List<android.app.usage.UsageStats> stats;
        try {
            stats = usage.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST,
                    now - 6 * 3600 * 1000L, now);
        } catch (RuntimeException e) {
            return out;
        }
        if (stats == null) {
            return out;
        }
        Collections.sort(stats, (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.SharedPreferences closed = getSharedPreferences("closed_apps", MODE_PRIVATE);
        for (android.app.usage.UsageStats st : stats) {
            String pkg = st.getPackageName();
            if (st.getLastTimeUsed() <= closed.getLong(pkg, 0)) {
                continue; // closed from the switcher and not opened since
            }
            if (out.contains(pkg) || pkg.equals(getPackageName()) || st.getTotalTimeInForeground() < 1000
                    || pkg.startsWith("com.tcl.suspension") || pkg.equals("com.android.systemui")
                    || pkg.equals("com.google.android.apps.tv.launcherx") || pkg.equals("android")
                    // Settings is opened under the switcher to force-stop apps; it is not an app
                    // one switches back to.
                    || pkg.equals("com.android.tv.settings")) {
                continue;
            }
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) {
                continue;
            }
            out.add(pkg);
            if (out.size() >= 6) {
                break;
            }
        }
        return out;
    }

    private void showSwitcher() {
        if (switcher != null || windows == null) {
            return;
        }
        hidePanel();
        hideCard();
        captureBehind(this::buildSwitcher);
    }

    private void buildSwitcher() {
        if (switcher != null || windows == null) {
            return;
        }
        List<String> apps = recentApps();
        if (CinemaActivity.alive) {
            apps.add(0, CINEMA_CARD); // Cinema mode is on: back to it (OK) or leave it (Up) from here
        }
        final FrameLayout root = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                        sounds.play("back");
                        hideSwitcher();
                    }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    sounds.play("move");
                }
                return super.dispatchKeyEvent(event);
            }
        };
        if (behind != null) {
            // Like the app grid: the screen behind, frosted and dimmed.
            android.graphics.drawable.BitmapDrawable frosted =
                    new android.graphics.drawable.BitmapDrawable(getResources(), behind);
            frosted.setFilterBitmap(true);
            frosted.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                    0.78f, 0, 0, 0, 6, 0, 0.78f, 0, 0, 6, 0, 0, 0.78f, 0, 6, 0, 0, 0, 1, 0}));
            root.setBackground(frosted);
        } else {
            // Over video (nothing captured): graphite rather than black, so the cards' black
            // shadows still read, and the picture still shows faintly through.
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xD9404048, 0xE6242428}));
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        TextView title = text(18, true);
        title.setText(t("Aplicații deschise recent", "Recent apps"));
        title.setPadding(0, 0, 0, px(22));
        column.addView(title, new LinearLayout.LayoutParams(-2, -2));

        // Up to six apps, sized so the row always fits, centred, with room at both ends.
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int count = Math.max(1, apps.size());
        int cardW = Math.min(px(170), (screenW - px(120)) / count - px(24));
        LinearLayout row = new LinearLayout(this);
        // The other cards slide together when one is closed.
        android.animation.LayoutTransition slide = new android.animation.LayoutTransition();
        slide.setDuration(280);
        slide.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        row.setLayoutTransition(slide);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        View first = null;
        for (String pkg : apps) {
            View cardView = appCard(pkg, cardW);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, -2);
            lp.leftMargin = px(12);
            lp.rightMargin = px(12);
            row.addView(cardView, lp);
            if (first == null) {
                first = cardView;
            }
        }
        if (apps.isEmpty()) {
            TextView none = text(13, false);
            none.setAlpha(0.7f);
            none.setText(t("Nicio aplicație folosită recent", "No recent apps"));
            row.addView(none);
        }
        row.setPadding(0, px(18), 0, px(40));
        column.addView(row, new LinearLayout.LayoutParams(-2, -2));
        root.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        try {
            windows.addView(root, overlayParams(true));
        } catch (RuntimeException e) {
            return;
        }
        switcher = root;
        sounds.play("select");
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220).start();
        row.setScaleX(0.92f);
        row.setScaleY(0.92f);
        row.animate().scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2f)).setDuration(320).start();
        // Focus the most recent app once the window really has focus (earlier requests get
        // overridden by the window's own default focus).
        final View focus = first != null ? first : root;
        if (first == null) {
            root.setFocusable(true);
        }
        root.getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                if (hasFocus) {
                    focus.requestFocus();
                    root.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                }
            }
        });
        focus.post(focus::requestFocus);
    }

    /** One app: its TV banner (or icon), name below; lifts and brightens when focused. */
    private static final String CINEMA_CARD = "ro.tinulica.tvhome.cinema";

    /** Cinema in the switcher: its sign on a dark, warm card; OK goes back, Up leaves Cinema mode. */
    private View cinemaCard(int cardW) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        final FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        final FrameLayout art = new FrameLayout(this);
        final FocusShadow shadow = new FocusShadow(art);
        cell.setBackground(shadow);
        cell.addView(art, new FrameLayout.LayoutParams(-1, -1));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A1A1C, 0xFF120C10});
        bg.setCornerRadius(px(14));
        art.setBackground(bg);
        art.setClipToOutline(true);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(14));
            }
        });
        CinemaMark.Spin mark = new CinemaMark.Spin(this);
        art.addView(mark, new FrameLayout.LayoutParams(cardW / 4, cardW / 4, Gravity.CENTER));
        box.addView(cell, new LinearLayout.LayoutParams(cardW, cardW * 9 / 16));
        final TextView label = text(12, true);
        label.setText("Cinema");
        label.setAlpha(0.6f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = px(14);
        box.addView(label, llp);
        box.setFocusable(true);
        final Spring lift = new Spring(0f, 0.002f, value -> {
            float scale = 1f + 0.12f * value;
            art.setScaleX(scale);
            art.setScaleY(scale);
            shadow.setAmount(value);
            cell.invalidate();
            label.setAlpha(0.6f + 0.4f * Math.max(0f, Math.min(1f, value)));
            label.setTranslationY(px(8) * value);
        });
        box.setOnFocusChangeListener((v, has) -> lift.to(has ? 1f : 0f, has ? 0.30f : 0.42f, has ? 0.72f : 1f));
        box.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_UP) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                // Up, like closing an app: Cinema mode ends (with its goodbye).
                sounds.play("back");
                v.animate().translationY(-px(120)).alpha(0f).setDuration(260).withEndAction(() -> {
                    hideSwitcher();
                    start(CinemaActivity.back(this, true));
                }).start();
            }
            return true;
        });
        box.setOnClickListener(v -> {
            sounds.play("select");
            hideSwitcher();
            start(CinemaActivity.back(this, false));
        });
        return box;
    }

    private View appCard(final String pkg, int cardW) {
        if (CINEMA_CARD.equals(pkg)) {
            return cinemaCard(cardW);
        }
        final android.content.pm.PackageManager pm = getPackageManager();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        final FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        final FrameLayout art = new FrameLayout(this);
        final FocusShadow shadow = new FocusShadow(art);
        cell.setBackground(shadow);
        cell.addView(art, new FrameLayout.LayoutParams(-1, -1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2C2C2E);
        bg.setCornerRadius(px(14));
        art.setBackground(bg);
        art.setClipToOutline(true);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(14));
            }
        });
        android.graphics.drawable.Drawable banner = null;
        CharSequence name = pkg;
        try {
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            name = pm.getApplicationLabel(info);
            banner = pm.getApplicationBanner(info);
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (banner == null && launch != null && launch.getComponent() != null) {
                banner = pm.getActivityBanner(launch.getComponent());
            }
            if (banner == null) {
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(pm.getApplicationIcon(info));
                art.addView(icon, new FrameLayout.LayoutParams(px(56), px(56), Gravity.CENTER));
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // Shown with its package name.
        }
        if (banner != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
            art.addView(image, new FrameLayout.LayoutParams(-1, -1));
        }
        box.addView(cell, new LinearLayout.LayoutParams(cardW, cardW * 9 / 16));
        final TextView label = text(12, true);
        label.setText(name);
        label.setAlpha(0.6f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = px(14);
        box.addView(label, llp);
        box.setFocusable(true);
        // The same motion as the home grid: a spring lift to 1.12 with a black shadow pooling
        // under the card, and the name sliding down out of its way.
        final Spring lift = new Spring(0f, 0.002f, value -> {
            float scale = 1f + 0.12f * value;
            art.setScaleX(scale);
            art.setScaleY(scale);
            shadow.setAmount(value);
            cell.invalidate();
            label.setAlpha(0.6f + 0.4f * Math.max(0f, Math.min(1f, value)));
            label.setTranslationY(px(8) * value);
        });
        box.setOnFocusChangeListener((v, has) -> {
            if (has) {
                lift.to(1f, 0.30f, 0.72f);
            } else {
                lift.to(0f, 0.42f, 1f);
            }
        });
        box.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_UP) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                closeApp(pkg, v);
            }
            return true;
        });
        box.setOnClickListener(v -> {
            sounds.play("select");
            hideSwitcher();
            Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(pkg);
            }
            if (intent != null) {
                // An app already running comes back where it was, like switching on tvOS.
                start(intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            }
        });
        return box;
    }

    /**
     * Up on a card, like swiping an app away on tvOS: the card flies up and fades, the others
     * close the gap, and the app's processes are ended to free memory. Android lets us end only
     * apps in the background, so if it is the one we came from, the home screen comes first.
     */
    private void closeApp(final String pkg, final View card) {
        sounds.play("back");
        getSharedPreferences("closed_apps", MODE_PRIVATE).edit()
                .putLong(pkg, System.currentTimeMillis()).apply();
        final android.view.ViewGroup row = (android.view.ViewGroup) card.getParent();
        int index = row.indexOfChild(card);
        View next = row.getChildAt(index + 1 < row.getChildCount() ? index + 1 : index - 1);
        card.setFocusable(false);
        if (next != null && next != card) {
            next.requestFocus();
        }
        card.animate().translationY(-px(120)).alpha(0f).setDuration(260)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    row.removeView(card);
                    if (row.getChildCount() == 0) {
                        hideSwitcher();
                    }
                }).start();
        stopQueue.add(pkg);
        if (stopping == null) {
            nextStop();
        }
    }

    // TCL ignores ActivityManager.killBackgroundProcesses, so closing an app does what a person
    // would do: its App info page, "Force stop", "OK". It all happens under the switcher, which
    // stays on top; only those two controls are ever pressed, and only on the page that belongs
    // to the app being closed. Anything unexpected, or 6 s without progress, and it gives up.
    private final List<String> stopQueue = new ArrayList<>();
    private String stopping;
    private String stoppingLabel;
    private int stopStep;
    private String returnTo;
    private final Runnable stopTimeout = () -> finishStop(false);

    private void nextStop() {
        if (stopQueue.isEmpty()) {
            stopping = null;
            return;
        }
        stopping = stopQueue.remove(0);
        stopStep = 0;
        try {
            stoppingLabel = String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(stopping, 0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            stoppingLabel = stopping;
        }
        if (returnTo == null) {
            // Where to come back to afterwards: the app that was in front, unless it is the one
            // being closed (then the home screen).
            returnTo = homeInFront || frontPkg == null || frontPkg.equals(stopping) ? "" : frontPkg;
        }
        handler.removeCallbacks(stopTimeout);
        handler.postDelayed(stopTimeout, 6000);
        start(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + stopping))
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        handler.postDelayed(this::stepStop, 500);
    }

    /** Looks at the Settings window and presses the next control, if it is the expected one. */
    private void stepStop() {
        if (stopping == null) {
            return;
        }
        android.view.accessibility.AccessibilityNodeInfo root = settingsRoot();
        if (root == null) {
            handler.postDelayed(this::stepStop, 250);
            return;
        }
        if (stopStep == 0) {
            // The App info page of exactly this package.
            if (!hasText(root, "android:id/summary", stopping)) {
                handler.postDelayed(this::stepStop, 250);
                return;
            }
            android.view.accessibility.AccessibilityNodeInfo row = byText(root, "android:id/title",
                    "Opriți forțat", "Oprire forțată", "Force stop");
            if (row == null) {
                handler.postDelayed(this::stepStop, 250);
                return;
            }
            android.view.accessibility.AccessibilityNodeInfo target = clickable(row);
            if (target == null || !target.isEnabled()) {
                finishStop(true); // already stopped: Android greys the row out
                return;
            }
            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            stopStep = 1;
            handler.postDelayed(this::stepStop, 400);
        } else if (stopStep == 1) {
            // The confirmation for this app: title "Force stop", breadcrumb the app's name.
            boolean confirm = byText(root, "com.android.tv.settings:id/guidance_title",
                    "Opriți forțat", "Oprire forțată", "Force stop") != null
                    && hasText(root, "com.android.tv.settings:id/guidance_breadcrumb", stoppingLabel);
            android.view.accessibility.AccessibilityNodeInfo ok = confirm ? byText(root,
                    "com.android.tv.settings:id/guidedactions_item_title", "OK") : null;
            android.view.accessibility.AccessibilityNodeInfo target = ok != null ? clickable(ok) : null;
            if (target == null) {
                handler.postDelayed(this::stepStop, 250);
                return;
            }
            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            stopStep = 2;
            handler.postDelayed(() -> finishStop(true), 350);
        }
    }

    private void finishStop(boolean done) {
        handler.removeCallbacks(stopTimeout);
        if (stopping == null) {
            return;
        }
        android.util.Log.i("TVHome", (done ? "force-stopped " : "could not stop ") + stopping);
        stopping = null;
        if (!stopQueue.isEmpty()) {
            nextStop();
            return;
        }
        // Back to where we were; the Settings page is left behind in the background.
        String back = returnTo;
        returnTo = null;
        Intent intent = null;
        if (back != null && !back.isEmpty()) {
            intent = getPackageManager().getLeanbackLaunchIntentForPackage(back);
            if (intent == null) {
                intent = getPackageManager().getLaunchIntentForPackage(back);
            }
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        }
        start(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        handler.postDelayed(this::hideStopCover, 700);
    }

    private android.view.accessibility.AccessibilityNodeInfo settingsRoot() {
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null
                    && "com.android.tv.settings".contentEquals(r.getPackageName())) {
                return r;
            }
        }
        return null;
    }

    private static boolean hasText(android.view.accessibility.AccessibilityNodeInfo root, String id, String text) {
        for (android.view.accessibility.AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
            if (n.getText() != null && text.contentEquals(n.getText())) {
                return true;
            }
        }
        return false;
    }

    private static android.view.accessibility.AccessibilityNodeInfo byText(
            android.view.accessibility.AccessibilityNodeInfo root, String id, String... texts) {
        for (android.view.accessibility.AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
            if (n.getText() == null) {
                continue;
            }
            for (String t : texts) {
                if (t.contentEquals(n.getText())) {
                    return n;
                }
            }
        }
        return null;
    }

    private static android.view.accessibility.AccessibilityNodeInfo clickable(
            android.view.accessibility.AccessibilityNodeInfo node) {
        android.view.accessibility.AccessibilityNodeInfo n = node;
        for (int i = 0; n != null && i < 6; i++) {
            if (n.isClickable()) {
                return n;
            }
            n = n.getParent();
        }
        return null;
    }

    private void hideSwitcher() {
        final View root = switcher;
        switcher = null;
        if (root == null) {
            return;
        }
        if (stopping != null) {
            // An app is still being closed in Settings: keep that page out of sight.
            showStopCover();
        }
        root.animate().alpha(0f).setDuration(180).withEndAction(() -> removeQuietly(root)).start();
    }

    private View stopCover;

    /**
     * While an app is closed in its Settings page, a still of the screen (frosted) covers it
     * with "Closing …", so you never land in Settings; it lifts once you are back.
     */
    private void showStopCover() {
        if (stopCover != null || windows == null) {
            return;
        }
        FrameLayout cover = new FrameLayout(this);
        if (behind != null) {
            ImageView still = new ImageView(this);
            still.setScaleType(ImageView.ScaleType.FIT_XY);
            still.setImageBitmap(behind);
            cover.addView(still, new FrameLayout.LayoutParams(-1, -1));
            View dim = new View(this);
            dim.setBackgroundColor(0x66000000);
            cover.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        } else {
            cover.setBackgroundColor(0xFF0B0B0D);
        }
        TextView label = text(13, true);
        label.setText("Se închide " + (stoppingLabel != null ? stoppingLabel : "aplicația") + "…");
        label.setGravity(Gravity.CENTER);
        label.setPadding(px(18), 0, px(18), 0);
        label.setBackground(new GlassDrawable(px(17), dp, 0x59202226));
        cover.addView(label, new FrameLayout.LayoutParams(-2, px(34), Gravity.CENTER));
        try {
            windows.addView(cover, overlayParams(false));
            stopCover = cover;
            cover.setAlpha(0f);
            cover.animate().alpha(1f).setDuration(120).start();
        } catch (RuntimeException e) {
            overlayFailed();
        }
    }

    private void hideStopCover() {
        final View cover = stopCover;
        stopCover = null;
        if (cover != null) {
            cover.animate().alpha(0f).setDuration(260).withEndAction(() -> removeQuietly(cover)).start();
        }
    }

    private void backOutOfTclPanel() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && "com.tcl.suspension".contentEquals(r.getPackageName())) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    android.util.Log.i("TVHome", "closed TCL's input panel with Back");
                    handler.postDelayed(() -> {
                        if (panel != null) {
                            View f = panel.findFocus();
                            if (f == null && panel.getChildCount() > 0) {
                                panel.requestFocus();
                            }
                        }
                    }, 120);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // No window info.
        }
    }

    private void dismissTclPanel() {
        try {
            startService(new Intent("com.tcl.quickmenu.dismiss")
                    .setClassName("com.tcl.suspension", "com.tcl.suspension.DismissService"));
        } catch (RuntimeException ignored) {
            // Not a TCL TV, or nothing to close.
        }
    }

    private List<TvInputInfo> passthrough() {
        List<TvInputInfo> list = new ArrayList<>();
        if (inputs == null) {
            return list;
        }
        for (TvInputInfo info : inputs.getTvInputList()) {
            if (info.isPassthroughInput() && !info.isHidden(this)) {
                list.add(info);
            }
        }
        Collections.sort(list, (x, y) -> label(x).compareToIgnoreCase(label(y)));
        return list;
    }

    private String label(TvInputInfo info) {
        CharSequence label = info.loadCustomLabel(this);
        if (label == null || label.length() == 0) {
            label = info.loadLabel(this);
        }
        return String.valueOf(label);
    }

    private WindowManager.LayoutParams overlayParams(boolean focusable) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // Windows a service adds are drawn in software unless asked: springs,
                        // glass and shadows need the GPU to stay smooth.
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private TextView text(float sp, boolean bold) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setShadowLayer(px(4), 0, px(1), 0x59000000);
        return view;
    }

    // ------------------------------------------------------------------ the Sources panel

    private void showPanel() {
        if (panel != null || windows == null) {
            return;
        }
        hideCard();
        hideSwitcher();
        captureBehind(this::buildPanel);
    }

    private void buildPanel() {
        if (panel != null || windows == null) {
            return;
        }
        final FrameLayout root = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                handler.removeCallbacks(hidePanel);
                handler.postDelayed(hidePanel, PANEL_TIMEOUT);
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        sounds.play("back");
                        hidePanel();
                    }
                    return code == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event);
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    sounds.play("move");
                }
                return super.dispatchKeyEvent(event);
            }
        };
        // A soft shade on the right so the panel reads over any picture (there is no blur over video).
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00000000, 0x8C000000, 0xD9000000}));
        root.addView(shade, new FrameLayout.LayoutParams(px(420), -1, Gravity.END));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        TextView title = text(17, true);
        title.setText(t("Surse", "Inputs"));
        title.setPadding(px(6), 0, 0, px(10));
        list.addView(title);

        View first = null;
        for (final TvInputInfo info : passthrough()) {
            int state = inputs.getInputState(info.getId());
            String detail = state == TvInputManager.INPUT_STATE_CONNECTED ? t("Pornit", "On")
                    : state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? t("În așteptare", "Standby")
                    : t("Neconectat", "Not connected");
            View row = row("hdmi", label(info), detail, () -> openInput(info.getId()));
            list.addView(row, rowParams());
            if (first == null || state == TvInputManager.INPUT_STATE_CONNECTED && first.getTag() == null) {
                if (state == TvInputManager.INPUT_STATE_CONNECTED) {
                    row.setTag("on");
                }
                if (first == null || row.getTag() != null) {
                    first = row;
                }
            }
        }
        if (getPackageManager().getLaunchIntentForPackage(LIVE_TV) != null
                || getPackageManager().getLeanbackLaunchIntentForPackage(LIVE_TV) != null) {
            list.addView(row("antenna", t("Antenă", "Antenna"), "Live TV", this::openAntenna), rowParams());
        }
        list.addView(row("house", t("Ecran principal", "Home screen"), "", this::openHome), rowParams());

        // Room on both sides so a focused row (slightly larger) is never cut.
        list.setPadding(px(10), px(6), px(10), px(6));
        root.setClipChildren(false);
        root.addView(list, new FrameLayout.LayoutParams(px(228), -2, Gravity.END | Gravity.CENTER_VERTICAL) {
            {
                rightMargin = px(30);
            }
        });
        try {
            windows.addView(root, overlayParams(true));
        } catch (RuntimeException e) {
            return;
        }
        panel = root;
        sounds.play("select");
        // TCL's own input panel opens on the same key a moment later and would stay behind ours.
        handler.post(keepTclClosed);
        root.setAlpha(0f);
        list.setTranslationX(px(40));
        root.animate().alpha(1f).setDuration(220).start();
        list.animate().translationX(0).setInterpolator(new DecelerateInterpolator(2f)).setDuration(320).start();
        if (first != null) {
            final View focus = first;
            focus.post(focus::requestFocus);
        }
        handler.postDelayed(hidePanel, PANEL_TIMEOUT);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(40));
        lp.topMargin = px(5);
        return lp;
    }

    /** A capsule row like the Control Center tiles: glass at rest, white when focused. */
    private View row(String glyph, String name, String detail, final Runnable action) {
        final FrameLayout row = new FrameLayout(this);
        final int rest = behind != null ? 0x2EFFFFFF : 0x9E1C1C1E;
        final GlassDrawable glass = glassOn(row, px(16), 0x2EFFFFFF, 0x9E1C1C1E);
        row.setFocusable(true);
        final ImageView icon = new ImageView(this);
        icon.setImageBitmap(Glyphs.symbol(this, glyph, px(18)));
        row.addView(icon, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER_VERTICAL | Gravity.START) {
            {
                leftMargin = px(14);
            }
        });
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(13, true);
        title.setText(name);
        words.addView(title);
        final TextView sub = text(10.5f, false);
        sub.setAlpha(0.7f);
        if (!detail.isEmpty()) {
            sub.setText(detail);
            words.addView(sub);
        }
        row.addView(words, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL) {
            {
                leftMargin = px(44);
                rightMargin = px(12);
            }
        });
        row.setOnFocusChangeListener((v, has) -> {
            glass.setTint(has ? 0xF2FFFFFF : rest);
            int ink = has ? 0xFF111111 : Color.WHITE;
            title.setTextColor(ink);
            sub.setTextColor(ink);
            if (has) {
                icon.setColorFilter(0xFF111111, android.graphics.PorterDuff.Mode.SRC_IN);
            } else {
                icon.clearColorFilter();
            }
            v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f)
                    .setInterpolator(new DecelerateInterpolator(2f)).setDuration(200).start();
        });
        row.setOnClickListener(v -> {
            sounds.play("select");
            action.run();
        });
        return row;
    }

    private final Runnable keepTclClosed = new Runnable() {
        @Override
        public void run() {
            if (panel != null) {
                dismissTclPanel();
                handler.postDelayed(this, 400);
            }
        }
    };

    private void hidePanel() {
        handler.removeCallbacks(hidePanel);
        handler.removeCallbacks(keepTclClosed);
        dismissTclPanel();
        final View root = panel;
        panel = null;
        if (root == null) {
            return;
        }
        root.animate().alpha(0f).setDuration(180).withEndAction(() -> removeQuietly(root)).start();
    }

    private void removeQuietly(View view) {
        try {
            windows.removeView(view);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    private void openInput(String inputId) {
        hidePanel();
        hideCard();
        // Our own input screen (no TCL source cards or "no signal" page).
        start(SourceActivity.intent(this, inputId));
    }

    private void openAntenna() {
        hidePanel();
        LiveTv.open(this);
    }

    private void openHome() {
        hidePanel();
        start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
    }

    private void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // Nothing handles it on this TV.
        }
    }

    // ------------------------------------------------------------------ "PlayStation 5 is on" card

    private void showCard(final TvInputInfo info, final boolean withPreview) {
        if (windows == null || panel != null) {
            return;
        }
        hideCard();
        // The preview's own video must not end up in the capture: capture first, then build.
        captureBehind(() -> buildCard(info, withPreview));
    }

    private void buildCard(final TvInputInfo info, boolean withPreview) {
        if (windows == null || panel != null || card != null) {
            return;
        }
        final FrameLayout root = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        hideCard();
                    }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    sounds.play("move");
                }
                handler.removeCallbacks(hideCard);
                handler.postDelayed(hideCard, CARD_TIMEOUT);
                return super.dispatchKeyEvent(event);
            }
        };
        FrameLayout box = new FrameLayout(this);
        glassOn(box, px(22), 0x33FFFFFF, 0xC71C1C1E);
        box.setElevation(px(10));
        box.setClipToOutline(true);
        box.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(22));
            }
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(px(12), px(12), px(14), px(12));

        // The picture from the source itself when nothing else is playing; otherwise its symbol.
        FrameLayout picture = new FrameLayout(this);
        GradientDrawable well = new GradientDrawable();
        well.setColor(0xFF000000);
        well.setCornerRadius(px(12));
        picture.setBackground(well);
        picture.setClipToOutline(true);
        picture.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(12));
            }
        });
        ImageView symbol = new ImageView(this);
        symbol.setImageBitmap(Glyphs.symbol(this, "hdmi", px(28)));
        picture.addView(symbol, new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER));
        if (homeInFront && withPreview) {
            try {
                final TvView tv = new TvView(this);
                tv.setAlpha(0f);
                tv.setCallback(new TvView.TvInputCallback() {
                    @Override
                    public void onVideoAvailable(String inputId) {
                        tv.animate().alpha(1f).setDuration(400).start();
                    }
                });
                picture.addView(tv, new FrameLayout.LayoutParams(-1, -1));
                tv.tune(info.getId(), TvContract.buildChannelUriForPassthroughInput(info.getId()));
                preview = tv;
            } catch (RuntimeException ignored) {
                preview = null;
            }
        }
        content.addView(picture, new LinearLayout.LayoutParams(px(128), px(72)));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(14, true);
        name.setText(label(info));
        side.addView(name);
        TextView question = text(11, false);
        question.setSingleLine(false);
        question.setMaxLines(2);
        question.setAlpha(0.75f);
        question.setText(t("S-a pornit. Treci pe această sursă?", "Turned on. Switch to this input?"));
        side.addView(question);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        View open = button(t("Deschide", "Open"), () -> openInput(info.getId()));
        View later = button(t("Nu acum", "Not now"), this::hideCard);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, px(28));
        blp.rightMargin = px(8);
        buttons.addView(open, blp);
        buttons.addView(later, new LinearLayout.LayoutParams(-2, px(28)));
        LinearLayout.LayoutParams bwrap = new LinearLayout.LayoutParams(-2, -2);
        bwrap.topMargin = px(8);
        side.addView(buttons, bwrap);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
        slp.leftMargin = px(12);
        content.addView(side, slp);
        box.addView(content, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(380), -2, Gravity.TOP | Gravity.END);
        lp.topMargin = px(30);
        lp.rightMargin = px(40);
        root.addView(box, lp);
        try {
            windows.addView(root, overlayParams(true));
        } catch (RuntimeException e) {
            return;
        }
        card = root;
        sounds.play("select");
        box.setAlpha(0f);
        box.setTranslationY(-px(24));
        box.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f)).setDuration(380).start();
        open.post(open::requestFocus);
        handler.postDelayed(hideCard, CARD_TIMEOUT);
    }

    private View button(String label, final Runnable action) {
        final TextView button = text(11.5f, true);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setPadding(px(14), 0, px(14), 0);
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(px(14));
        shape.setColor(0x33FFFFFF);
        button.setBackground(shape);
        button.setFocusable(true);
        button.setOnFocusChangeListener((v, has) -> {
            shape.setColor(has ? 0xF2FFFFFF : 0x33FFFFFF);
            button.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        button.setOnClickListener(v -> {
            sounds.play("select");
            action.run();
        });
        return button;
    }

    private void hideCard() {
        handler.removeCallbacks(hideCard);
        if (preview != null) {
            try {
                preview.reset();
            } catch (RuntimeException ignored) {
                // Session already gone.
            }
            preview = null;
        }
        final View root = card;
        card = null;
        if (root == null) {
            return;
        }
        root.animate().alpha(0f).setDuration(200).withEndAction(() -> removeQuietly(root)).start();
    }
}
