package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The to-do list Claude keeps for the TV (files/claude/tasks-ACCOUNT.json, written by `tvtool tasks`),
 * shown under the weather when the TV comes on: one row per task, a check of Liquid Glass
 * in front of each. A finished task's check fills green from the middle and its tick draws
 * itself in, one after another, as Control Center's switches do; an open one is a ring of glass.
 */
final class TaskList extends LinearLayout {

    static final class Task {
        String text;
        boolean done;
    }

    /** The linked account's tasks, or an empty list (none linked, no file, or older than two days). */
    static List<Task> load(Context context) {
        List<Task> out = new ArrayList<>();
        File f = ClaudeLink.file(context, "tasks");
        if (f == null || !f.exists() || System.currentTimeMillis() - f.lastModified() > 2 * 24 * 3600_000L) {
            return out;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            JSONArray a = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).optJSONArray("tasks");
            for (int i = 0; a != null && i < a.length() && out.size() < 6; i++) {
                JSONObject o = a.getJSONObject(i);
                Task t = new Task();
                t.text = o.optString("text", "");
                t.done = o.optBoolean("done", false);
                if (!t.text.isEmpty()) {
                    out.add(t);
                }
            }
        } catch (Exception ignored) {
            // Shown without tasks.
        }
        return out;
    }

    TaskList(Context context, float dp, List<Task> tasks) {
        super(context);
        setOrientation(VERTICAL);
        int open = 0;
        for (Task t : tasks) {
            open += t.done ? 0 : 1;
        }
        TextView head = text(context, dp, 11, true);
        head.setText(open == 0 ? "Gata pentru azi" : open == 1 ? "1 lucru de făcut" : open + " lucruri de făcut");
        head.setAlpha(0.8f);
        addView(head);
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(new Check(context, dp, t.done, 1.3f + 0.18f * i),   // once the drop has opened
                    new LayoutParams(Math.round(18 * dp), Math.round(18 * dp)));
            TextView label = text(context, dp, 12.5f, false);
            label.setText(t.text);
            label.setAlpha(t.done ? 0.62f : 1f);
            if (t.done) {
                label.setPaintFlags(label.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
            LayoutParams llp = new LayoutParams(0, -2, 1f);
            llp.leftMargin = Math.round(9 * dp);
            row.addView(label, llp);
            // As tall as its words (one line, or all of them when the notice is opened).
            row.setMinimumHeight(Math.round(24 * dp));
            LayoutParams rlp = new LayoutParams(-1, -2);
            rlp.topMargin = Math.round((i == 0 ? 6 : 2) * dp);
            addView(row, rlp);
        }
    }

    private static TextView text(Context context, float dp, float sp, boolean bold) {
        TextView v = new TextView(context);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setIncludeFontPadding(false);
        v.setShadowLayer(3 * dp, 0, dp, 0x80000000);
        return v;
    }

    /** One check: a ring of glass; finished, the green fills it and the tick draws itself in. */
    private static final class Check extends View {
        private final boolean done;
        private final float delay;
        private final float dp;
        private final long born = SystemClock.uptimeMillis();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private final Path drawn = new Path();

        Check(Context context, float dp, boolean done, float delay) {
            super(context);
            this.done = done;
            this.delay = delay;
            this.dp = dp;
        }

        private static float springy(float x) {
            return x <= 0 ? 0 : (float) (1.0 - Math.exp(-6.5 * x) * Math.cos(10.5 * x));
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float t = (SystemClock.uptimeMillis() - born) / 1000f - delay;
            // The glass: a faint disc, a bright rim lit from the top-left.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33FFFFFF);
            c.drawCircle(cx, cy, s / 2f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 1.2f * dp));
            paint.setColor(0xB3FFFFFF);
            c.drawArc(cx - s / 2f + 1, cy - s / 2f + 1, cx + s / 2f - 1, cy + s / 2f - 1, 150, 150, false, paint);
            paint.setColor(0x4DFFFFFF);
            c.drawArc(cx - s / 2f + 1, cy - s / 2f + 1, cx + s / 2f - 1, cy + s / 2f - 1, 300, 210, false, paint);
            if (done) {
                float fill = Math.min(1.08f, springy(t));
                if (fill > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xF034C759);
                    c.drawCircle(cx, cy, s / 2f * Math.min(1f, fill) - 1, paint);
                }
                float k = Math.max(0f, Math.min(1f, (t - 0.12f) / 0.3f));
                if (k > 0) {
                    tick.reset();
                    tick.moveTo(cx - s * 0.22f, cy + s * 0.02f);
                    tick.lineTo(cx - s * 0.06f, cy + s * 0.18f);
                    tick.lineTo(cx + s * 0.24f, cy - s * 0.16f);
                    PathMeasure m = new PathMeasure(tick, false);
                    drawn.reset();
                    m.getSegment(0, m.getLength() * k, drawn, true);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    paint.setStrokeWidth(s * 0.13f);
                    paint.setColor(Color.WHITE);
                    c.drawPath(drawn, paint);
                }
            } else {
                // Open: a soft light going round the ring.
                float a = (t * 120f) % 360f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, 1.6f * dp));
                paint.setColor(0x99FFFFFF);
                c.drawArc(cx - s / 2f + 1, cy - s / 2f + 1, cx + s / 2f - 1, cy + s / 2f - 1, a, 40, false, paint);
            }
            if (t < 1.5f || !done) {
                postInvalidateOnAnimation();
            }
        }
    }
}
