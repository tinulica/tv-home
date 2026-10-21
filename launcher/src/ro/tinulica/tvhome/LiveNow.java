package ro.tinulica.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.tv.TvContract;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What is on the TV's own channels right now, for the preview cards ("Acum la TV"): channels of
 * the chosen categories, in number order, each with the programme on now from the guide, and a
 * card picture drawn for it (the guide has no artwork).
 */
final class LiveNow {

    static final class Entry {
        ChannelActivity.Channel channel;
        String programme;
        long start;
        long end;
    }

    private LiveNow() {
    }

    /** The programme on now on a channel, or null. */
    static Entry nowOn(Context context, ChannelActivity.Channel ch) {
        long now = System.currentTimeMillis();
        Uri uri = TvContract.buildProgramsUriForChannel(ch.id, now - 4 * 3600_000L, now + 60_000L);
        try (Cursor c = context.getContentResolver().query(uri, new String[]{TvContract.Programs.COLUMN_TITLE,
                TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                long start = c.getLong(1);
                long end = c.getLong(2);
                if (start <= now && end > now && c.getString(0) != null) {
                    Entry e = new Entry();
                    e.channel = ch;
                    e.programme = c.getString(0);
                    e.start = start;
                    e.end = end;
                    return e;
                }
            }
        } catch (RuntimeException ignored) {
            // No guide for this channel.
        }
        return null;
    }

    /**
     * "For you" on TV now: the profile's favourite channels, the one watched last, then the
     * first channel of each category, so there is always something live to show.
     */
    static List<Entry> forYou(Context context, android.content.SharedPreferences prefs, int limit) {
        List<ChannelActivity.Channel> all = ChannelActivity.loadChannels(context);
        java.util.LinkedHashSet<Long> order = new java.util.LinkedHashSet<>();
        for (String id : prefs.getStringSet(Profiles.scoped(prefs, "live_tv_favourites"), new java.util.HashSet<>())) {
            try {
                order.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // Skip.
            }
        }
        long last = prefs.getLong("live_tv_channel", -1);
        if (last >= 0) {
            order.add(last);
        }
        for (String cat : new String[]{"Știri", "Sport", "Filme", "Documentare", "Copii", "Muzică"}) {
            int taken = 0;
            for (ChannelActivity.Channel ch : all) {
                if (cat.equals(ch.category) && taken < 2) {
                    order.add(ch.id);
                    taken++;
                }
            }
        }
        java.util.Map<Long, ChannelActivity.Channel> byId = new java.util.HashMap<>();
        for (ChannelActivity.Channel ch : all) {
            byId.put(ch.id, ch);
        }
        List<Entry> out = new ArrayList<>();
        for (Long id : order) {
            ChannelActivity.Channel ch = byId.get(id);
            Entry e = ch != null ? nowOn(context, ch) : null;
            if (e != null) {
                out.add(e);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** Up to {@code limit} channels of the categories (all, if empty) with something on now. */
    static List<Entry> find(Context context, Set<String> categories, int limit) {
        List<Entry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        int asked = 0;
        for (ChannelActivity.Channel ch : ChannelActivity.loadChannels(context)) {
            if (!categories.isEmpty() && !categories.contains(ch.category)) {
                continue;
            }
            if (asked++ > 60 || out.size() >= limit) {
                break;
            }
            Uri uri = TvContract.buildProgramsUriForChannel(ch.id, now - 4 * 3600_000L, now + 60_000L);
            try (Cursor c = context.getContentResolver().query(uri, new String[]{TvContract.Programs.COLUMN_TITLE,
                    TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS},
                    null, null, null)) {
                while (c != null && c.moveToNext()) {
                    long start = c.getLong(1);
                    long end = c.getLong(2);
                    if (start <= now && end > now && c.getString(0) != null) {
                        Entry e = new Entry();
                        e.channel = ch;
                        e.programme = c.getString(0);
                        e.start = start;
                        e.end = end;
                        out.add(e);
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
                // No guide for this channel.
            }
        }
        return out;
    }

    private static int colourOf(String category) {
        switch (category) {
            case "Știri": return 0xFF2C6BED;
            case "Sport": return 0xFF1FA463;
            case "Filme": return 0xFFB8334A;
            case "Copii": return 0xFFF29A2E;
            case "Muzică": return 0xFF9B4DE0;
            case "Documentare": return 0xFF2E9C9C;
            default: return 0xFF4A5568;
        }
    }

    /** A card picture: the category's colour, LIVE, the channel and the programme on now. */
    static Bitmap picture(Context context, Entry e, int w, int h, float dp) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int base = colourOf(e.channel.category);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w, h, lighten(base, 0.18f), darken(base, 0.45f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new android.graphics.RadialGradient(w * 0.85f, h * 0.1f, w * 0.7f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glow);
        float pad = 14 * dp;
        // The channel's own logo, when the TV has one, in the middle.
        try (java.io.InputStream in = context.getContentResolver().openInputStream(
                TvContract.buildChannelLogoUri(e.channel.id))) {
            Bitmap logo = in != null ? android.graphics.BitmapFactory.decodeStream(in) : null;
            if (logo != null) {
                float box = h * 0.42f;
                float k = Math.min(box * 1.8f / logo.getWidth(), box / logo.getHeight());
                float lw = logo.getWidth() * k;
                float lh = logo.getHeight() * k;
                RectF dst = new RectF((w - lw) / 2f, h * 0.36f - lh / 2f, (w + lw) / 2f, h * 0.36f + lh / 2f);
                c.drawBitmap(logo, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            }
        } catch (Exception ignored) {
            // No logo: name and colour only.
        }
        // the channel number, small, top right
        TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        small.setColor(0xCCFFFFFF);
        small.setTextSize(11 * dp);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.drawText(e.channel.number, w - pad - small.measureText(e.channel.number), pad + 13 * dp, small);
        // channel and programme
        TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        name.setColor(0xFFFFFFFF);
        name.setTextSize(20 * dp);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String channel = TextUtils.ellipsize(e.channel.name, name, w - 2 * pad, TextUtils.TruncateAt.END).toString();
        TextPaint what = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        what.setColor(0xE6FFFFFF);
        what.setTextSize(13 * dp);
        String programme = TextUtils.ellipsize(e.programme, what, w - 2 * pad, TextUtils.TruncateAt.END).toString();
        c.drawText(channel, pad, h - pad - 22 * dp, name);
        c.drawText(programme, pad, h - pad - 4 * dp, what);
        // progress
        float done = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - e.start) / (float) Math.max(1, e.end - e.start)));
        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setColor(0x40FFFFFF);
        c.drawRect(0, h - 3 * dp, w, h, track);
        track.setColor(0xFFFFFFFF);
        c.drawRect(0, h - 3 * dp, w * done, h, track);
        return out;
    }

    private static int lighten(int c, float t) {
        return mix(c, 0xFFFFFFFF, t);
    }

    private static int darken(int c, float t) {
        return mix(c, 0xFF000000, t);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
