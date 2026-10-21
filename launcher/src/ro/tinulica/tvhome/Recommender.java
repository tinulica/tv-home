package ro.tinulica.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks what the top shelf shows. The apps publish their rows (Watch Next, channels);
 * this class decides the order, so the shelf is never the same thing twice in a row:
 *
 *  - every program gets a "first seen" time when it appears in the provider, so what an
 *    app published since last time ranks first (new content, new episodes);
 *  - Watch Next rows ("continue watching") get a boost, most recent engagement first;
 *  - a program shown on the banner is remembered and sinks for a while, so the shelf
 *    rotates through everything an app offers before repeating;
 *  - apps you actually use (UsageStats, when the permission is granted over ADB) are
 *    refreshed first and their new programs weigh more.
 *
 * State lives in the preferences; the worker in MainActivity re-reads the provider every
 * 15 minutes while the home screen is on, and immediately when an app publishes changes.
 */
final class Recommender {

    private static final String PREFS = "recommender";
    private static final String SEEN_PREFIX = "seen:";
    private static final String SHOWN_PREFIX = "shown:";
    private static final long DAY = 86_400_000L;

    private final SharedPreferences prefs;
    private final Context context;
    private final Map<String, Long> usage = new HashMap<>();
    private long usageReadAt;

    Recommender(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(Previews.Item item) {
        return item.intentUri != null ? item.intentUri : item.title + "|" + item.poster;
    }

    /** Records that these programs exist now; new ones get the current time as first-seen. */
    void observe(List<Previews.Item> items) {
        SharedPreferences.Editor edit = prefs.edit();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Previews.Item item : items) {
            String k = SEEN_PREFIX + key(item);
            if (!prefs.contains(k)) {
                edit.putLong(k, now);
                changed = true;
            }
        }
        if (changed) {
            edit.apply();
        }
    }

    /** The banner showed this program: it will not come first again for a while. */
    void shown(Previews.Item item) {
        prefs.edit().putLong(SHOWN_PREFIX + key(item), System.currentTimeMillis()).apply();
    }

    private long firstSeen(Previews.Item item) {
        return prefs.getLong(SEEN_PREFIX + key(item), 0);
    }

    private long lastShown(Previews.Item item) {
        return prefs.getLong(SHOWN_PREFIX + key(item), 0);
    }

    /** Foreground time of the app over the last two weeks, in ms (0 without the permission). */
    long usageOf(String pkg) {
        long now = System.currentTimeMillis();
        if (now - usageReadAt > 10 * 60_000L) {
            usage.clear();
            try {
                UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
                List<UsageStats> stats = manager != null
                        ? manager.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, now - 14 * DAY, now) : null;
                if (stats != null) {
                    for (UsageStats s : stats) {
                        Long old = usage.get(s.getPackageName());
                        usage.put(s.getPackageName(), (old == null ? 0 : old) + s.getTotalTimeInForeground());
                    }
                }
            } catch (RuntimeException ignored) {
                // No permission: everything weighs the same.
            }
            usageReadAt = now;
        }
        Long value = usage.get(pkg);
        return value == null ? 0 : value;
    }

    /** Dock apps ordered by how much they are used, most used first. */
    List<String> byUsage(List<String> packages) {
        List<String> out = new ArrayList<>(packages);
        Collections.sort(out, (a, b) -> Long.compare(usageOf(b), usageOf(a)));
        return out;
    }

    /** The app's programs in the order the shelf should offer them. */
    List<Previews.Item> rank(List<Previews.Item> items) {
        observe(items);
        final long now = System.currentTimeMillis();
        List<Previews.Item> out = new ArrayList<>(items);
        Collections.sort(out, (a, b) -> Double.compare(score(b, now), score(a, now)));
        return out;
    }

    private double score(Previews.Item item, long now) {
        double score = 0;
        // New since we first saw it: 3 points today, fading over a week.
        double ageDays = (now - firstSeen(item)) / (double) DAY;
        score += Math.max(0, 3.0 - ageDays * 3.0 / 7.0);
        // Continue watching: a strong, steady pull; the most recent one first.
        if (item.watchNext) {
            score += 2.0 + Math.min(1.0, item.weight / (double) now);
        }
        // Shown on the banner lately: sink for two days, then slowly come back.
        double sinceShown = (now - lastShown(item)) / (double) DAY;
        if (lastShown(item) > 0) {
            score -= Math.max(0, 4.0 - sinceShown * 2.0);
        }
        // The app's own ordering as a tie-breaker (channel order, weight) via position is lost
        // here, so a small deterministic jitter keeps equal scores from always tying the same way.
        score += ((key(item).hashCode() & 0xFF) / 255.0) * 0.3;
        return score;
    }
}
