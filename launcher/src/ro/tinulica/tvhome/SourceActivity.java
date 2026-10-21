package ro.tinulica.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * An HDMI input (a PlayStation, a laptop) shown by TV Home itself instead of TCL's TV app: the
 * picture comes through the TV input framework (TvView), and everything around it is ours, a
 * glass capsule with the device's name when it comes up, and a calm "waiting for a signal"
 * screen with a loader instead of TCL's "no signal" page and source cards.
 */
public final class SourceActivity extends Activity {

    static final String EXTRA_INPUT = "input";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;
    private TvView tv;
    private String inputId;
    private String name = "";
    private View waiting;
    private TextView waitingTitle;
    private TextView waitingDetail;
    private View banner;
    private boolean fellBack;

    static Intent intent(Context context, String inputId) {
        return new Intent(context, SourceActivity.class).putExtra(EXTRA_INPUT, inputId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private static String t(String ro, String en) {
        return "ro".equals(Locale.getDefault().getLanguage()) ? ro : en;
    }

    private int px(float value) {
        return Math.round(value * dp);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        waiting = buildWaiting();
        root.addView(waiting, new FrameLayout.LayoutParams(-1, -1));
        banner = buildBanner();
        root.addView(banner, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START) {
            {
                leftMargin = px(40);
                topMargin = px(30);
            }
        });
        setContentView(root);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String id) {
                showWaiting(false, null, null);
                showBanner();
            }

            @Override
            public void onVideoUnavailable(String id, int reason) {
                if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING) {
                    showWaiting(true, t("Se conectează…", "Connecting…"), null);
                } else if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL) {
                    showWaiting(true, t("Semnal slab", "Weak signal"),
                            t("Verifică cablul HDMI.", "Check the HDMI cable."));
                } else {
                    showWaiting(true, t("Se așteaptă semnal", "Waiting for a signal"),
                            t("Pornește dispozitivul sau verifică cablul HDMI.",
                                    "Turn the device on or check the HDMI cable."));
                }
            }

            @Override
            public void onConnectionFailed(String id) {
                fallBack();
            }

            @Override
            public void onDisconnected(String id) {
                fallBack();
            }
        });
        tune(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        tune(intent);
    }

    private void tune(Intent intent) {
        String id = intent.getStringExtra(EXTRA_INPUT);
        if (id == null) {
            finish();
            return;
        }
        inputId = id;
        fellBack = false;
        TvInputManager inputs = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        TvInputInfo info = inputs != null ? inputs.getTvInputInfo(id) : null;
        if (info != null) {
            CharSequence label = info.loadCustomLabel(this);
            if (label == null || label.length() == 0) {
                label = info.loadLabel(this);
            }
            name = String.valueOf(label);
        }
        ((TextView) banner.findViewWithTag("name")).setText(name);
        ((TextView) waiting.findViewWithTag("name")).setText(name);
        showWaiting(true, t("Se conectează…", "Connecting…"), null);
        try {
            tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
        } catch (RuntimeException e) {
            fallBack();
        }
    }

    /** If the TV will not give us the input, hand it to TCL's own player rather than show nothing. */
    private void fallBack() {
        if (fellBack || inputId == null) {
            return;
        }
        fellBack = true;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                    .setPackage("com.tcl.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // Nothing else can show it.
        }
        finish();
    }

    // ------------------------------------------------------------------ the waiting screen

    private View buildWaiting() {
        FrameLayout screen = new FrameLayout(this);
        // A deep graphite fade instead of TCL's flat page.
        screen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1C1C21, 0xFF0B0B0D}));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(new GlassDrawable(px(26), dp, 0x241C1C1E));
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(Glyphs.symbol(this, "hdmi", px(34)));
        tile.addView(icon, new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER));
        Spinner spinner = new Spinner(this, dp);
        tile.addView(spinner, new FrameLayout.LayoutParams(-1, -1));
        column.addView(tile, new LinearLayout.LayoutParams(px(96), px(96)));
        TextView title = text(20, true);
        title.setTag("name");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = px(20);
        column.addView(title, tlp);
        waitingTitle = text(14, false);
        waitingTitle.setAlpha(0.85f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = px(6);
        column.addView(waitingTitle, wlp);
        waitingDetail = text(11.5f, false);
        waitingDetail.setAlpha(0.55f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(4);
        column.addView(waitingDetail, dlp);
        screen.addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        TextView hint = text(11, false);
        hint.setAlpha(0.45f);
        hint.setText(t("Înapoi: ecranul principal   ·   Surse: alt dispozitiv",
                "Back: home screen   ·   Input: another device"));
        screen.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) {
            {
                bottomMargin = px(34);
            }
        });
        return screen;
    }

    private void showWaiting(boolean show, String title, String detail) {
        if (show) {
            waitingTitle.setText(title);
            waitingDetail.setText(detail != null ? detail : "");
            waitingDetail.setVisibility(detail != null ? View.VISIBLE : View.GONE);
            if (waiting.getVisibility() != View.VISIBLE) {
                waiting.setVisibility(View.VISIBLE);
                waiting.setAlpha(0f);
                waiting.animate().alpha(1f).setDuration(300).start();
            }
        } else if (waiting.getVisibility() == View.VISIBLE) {
            waiting.animate().alpha(0f).setDuration(400).withEndAction(() -> waiting.setVisibility(View.GONE)).start();
        }
    }

    // ------------------------------------------------------------------ the name capsule

    private View buildBanner() {
        LinearLayout capsule = new LinearLayout(this);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setPadding(px(14), px(9), px(18), px(9));
        capsule.setBackground(new GlassDrawable(px(20), dp, 0xB31C1C1E));
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(Glyphs.symbol(this, "hdmi", px(16)));
        capsule.addView(icon, new LinearLayout.LayoutParams(px(16), px(16)));
        TextView label = text(13, true);
        label.setTag("name");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.leftMargin = px(9);
        capsule.addView(label, llp);
        capsule.setAlpha(0f);
        return capsule;
    }

    private final Runnable hideBanner = () -> banner.animate().alpha(0f).translationY(-px(10)).setDuration(400).start();

    private void showBanner() {
        handler.removeCallbacks(hideBanner);
        banner.setTranslationY(-px(10));
        banner.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f)).setDuration(420).start();
        handler.postDelayed(hideBanner, 3500);
    }

    private TextView text(float sp, boolean bold) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setSingleLine(true);
        return view;
    }

    // ------------------------------------------------------------------ keys and lifecycle

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == 4001) {
                LiveTv.open(this);
                finish();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_INFO) {
                showBanner();
            }
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                finish();
            }
            return true;
        }
        // Everything else (the PlayStation's own remote keys via HDMI-CEC, volume) goes on.
        if (tv != null && tv.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the input: release it so the home screen and other apps get the TV back.
        if (tv != null) {
            tv.reset();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** A thin rotating arc, the same style as the hold ring, around the device symbol. */
    private static final class Spinner extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float dp;

        Spinner(Context context, float dp) {
            super(context);
            this.dp = dp;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * dp);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = 6f * dp;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            long now = android.os.SystemClock.uptimeMillis();
            float start = (now % 1400) / 1400f * 360f;
            float sweep = 50f + 70f * (float) (0.5 + 0.5 * Math.sin(now / 420.0));
            paint.setAlpha(0x2E);
            canvas.drawOval(box, paint);
            paint.setAlpha(0xFF);
            canvas.drawArc(box, start - 90f, sweep, false, paint);
            if (isShown()) {
                postInvalidateOnAnimation();
            }
        }
    }
}
