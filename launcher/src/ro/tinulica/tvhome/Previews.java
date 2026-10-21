package ro.tinulica.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "Watch Next" and preview-channel programs that apps publish for the home
 * screen through the system TV provider (android.media.tv). Google TV shows them
 * as rows; TV Home shows the ones of the focused app as a top shelf.
 *
 * Reading other apps' rows needs READ_EPG_DATA plus READ_TV_LISTINGS, and the
 * provider refuses any WHERE clause from a third-party app ("Selection not
 * allowed"), so the three tables are read whole, once in a while, and filtered here.
 */
final class Previews {

    static final class Item {
        String pkg;
        String title;
        String poster;
        int aspect;        // TvContract aspect ratio: 0 = 16:9, 1 = 3:2, 2 = 4:3, 3 = 1:1, 4 = 2:3, 5 = movie poster
        String intentUri;
        String video;
        String channel;
        long weight;
        long channelId;
        boolean watchNext;
        int type = -1;      // TvContract program type: 0 movie, 1-3 series/season/episode, 4 clip, 5 event…
        String genre;       // canonical genres, e.g. "MOVIES,DRAMA"
        boolean live;
        String author;

        float ratio() {
            switch (aspect) {
                case 1: return 3f / 2f;
                case 2: return 4f / 3f;
                case 3: return 1f;
                case 4: return 2f / 3f;
                case 5: return 1f / 1.441f;
                default: return 16f / 9f;
            }
        }
    }

    private static final Uri CHANNELS = Uri.parse("content://android.media.tv/channel");
    private static final Uri PROGRAMS = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String[] PROGRAM_COLUMNS = {
            "package_name", "title", "poster_art_uri", "poster_art_aspect_ratio", "intent_uri", "browsable",
            "weight", "channel_id", "preview_video_uri"};
    private static final String[] WATCH_NEXT_COLUMNS = {
            "package_name", "title", "poster_art_uri", "poster_art_aspect_ratio", "intent_uri", "browsable",
            "last_engagement_time_utc_millis", "preview_video_uri"};
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final long TTL = 5 * 60_000L;

    private final ContentResolver resolver;
    private final LruCache<String, Bitmap> posters;
    private final File logFile;
    private final Map<String, List<Item>> byPackage = new HashMap<>();
    private long loadedAt;
    private boolean loaded;

    Previews(Context context) {
        resolver = context.getContentResolver();
        File dir = context.getExternalFilesDir(null);
        logFile = dir != null ? new File(dir, "previews.log") : null;
        int budget = (int) Math.min(40L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6);
        posters = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Diagnostic line in files/previews.log (adb can read it; logcat on some TVs cannot). */
    void note(String line) {
        android.util.Log.d("TVHome", line);
        if (logFile == null) {
            return;
        }
        try (FileWriter out = new FileWriter(logFile, true)) {
            out.write(new java.util.Date() + "  " + line + System.lineSeparator());
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /**
     * Rows we never show. YouTube's "Recommended for you" follows whichever account is
     * signed in inside the YouTube app, which on a shared TV is rarely the viewer's.
     */
    static boolean hiddenChannel(String pkg, String channel) {
        if (channel == null) {
            return false;
        }
        String name = channel.toLowerCase(java.util.Locale.ROOT);
        return pkg.startsWith("com.google.android.youtube")
                && (name.startsWith("recomand") || name.startsWith("recommend"));
    }

    /** Forgets the cached tables so the next request reads them again. */
    void refresh() {
        loaded = false;
    }

    /** Watch Next first, then the app's browsable channels by weight. Never throws. */
    synchronized List<Item> load(String pkg, int limit) {
        if (Build.VERSION.SDK_INT < 26) {
            return new ArrayList<>();
        }
        if (!loaded || SystemClock.elapsedRealtime() - loadedAt > TTL) {
            readAll();
        }
        List<Item> all = byPackage.get(pkg);
        List<Item> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        int watchNext = 0;
        for (Item item : all) {
            if (out.size() >= limit) {
                break;
            }
            String key = item.intentUri != null ? item.intentUri : item.title + "|" + item.poster;
            if (!seen.add(key)) {
                continue;
            }
            if (item.watchNext && watchNext >= 4) {
                continue;
            }
            if (item.watchNext) {
                watchNext++;
            }
            out.add(item);
        }
        return out;
    }

    private void readAll() {
        byPackage.clear();
        Map<Long, Integer> channelOrder = new HashMap<>();
        Map<Long, String> channelNames = new HashMap<>();
        try (Cursor c = resolver.query(CHANNELS, new String[]{"_id", "display_name"}, null, null, "_id ASC")) {
            int order = 0;
            while (c != null && c.moveToNext()) {
                channelOrder.put(c.getLong(0), order++);
                channelNames.put(c.getLong(0), c.isNull(1) ? "" : c.getString(1));
            }
        } catch (RuntimeException e) {
            note("channels: " + e);
        }
        List<Item> programs = new ArrayList<>();
        try (Cursor c = resolver.query(PROGRAMS, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Item item = read(c);
                if (item == null || num(c, "browsable", 1) == 0) {
                    continue;
                }
                item.weight = num(c, "weight", 0);
                item.channelId = num(c, "channel_id", -1);
                item.video = str(c, "preview_video_uri");
                item.channel = channelNames.get(item.channelId);
                if (hiddenChannel(item.pkg, item.channel)) {
                    continue;
                }
                // Channels are "browsable" only once Google TV's launcher approved them; we are the
                // launcher now, so every channel the app published counts.
                programs.add(item);
            }
        } catch (RuntimeException e) {
            note("preview_program: " + e);
        }
        // Channel order first (the app's main channel comes first), then the app's own weight.
        Collections.sort(programs, (a, b) -> {
            int ca = channelOrder.containsKey(a.channelId) ? channelOrder.get(a.channelId) : Integer.MAX_VALUE;
            int cb = channelOrder.containsKey(b.channelId) ? channelOrder.get(b.channelId) : Integer.MAX_VALUE;
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            return Long.compare(b.weight, a.weight);
        });
        List<Item> next = new ArrayList<>();
        try (Cursor c = resolver.query(WATCH_NEXT, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Item item = read(c);
                if (item == null || num(c, "browsable", 1) == 0) {
                    continue;
                }
                item.watchNext = true;
                item.weight = num(c, "last_engagement_time_utc_millis", 0);
                item.video = str(c, "preview_video_uri");
                next.add(item);
            }
        } catch (RuntimeException e) {
            note("watch_next: " + e);
        }
        Collections.sort(next, (a, b) -> Long.compare(b.weight, a.weight));
        for (Item item : next) {
            group(item);
        }
        for (Item item : programs) {
            group(item);
        }
        loadedAt = SystemClock.elapsedRealtime();
        loaded = true;
        StringBuilder names = new StringBuilder("channels: ");
        try (Cursor c = resolver.query(CHANNELS, new String[]{"package_name", "display_name", "browsable"}, null, null, "_id ASC")) {
            while (c != null && c.moveToNext()) {
                names.append(c.getString(0)).append(" '").append(c.isNull(1) ? "" : c.getString(1)).append("'")
                        .append(c.isNull(2) || c.getInt(2) != 0 ? "" : " (hidden)").append("; ");
            }
        } catch (RuntimeException ignored) {
            // Diagnostic only.
        }
        note(names.toString());
        StringBuilder summary = new StringBuilder("read: ");
        for (Map.Entry<String, List<Item>> entry : byPackage.entrySet()) {
            int videos = 0;
            for (Item item : entry.getValue()) {
                if (item.video != null && !item.video.isEmpty()) {
                    videos++;
                }
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue().size())
                    .append(" (video ").append(videos).append(") ");
        }
        note(summary.toString());
        for (Map.Entry<String, List<Item>> entry : byPackage.entrySet()) {
            Item sample = entry.getValue().get(0);
            note("sample " + entry.getKey() + ": intent=" + sample.intentUri + " poster=" + sample.poster + " video=" + sample.video);
        }
    }

    private void group(Item item) {
        List<Item> list = byPackage.get(item.pkg);
        if (list == null) {
            list = new ArrayList<>();
            byPackage.put(item.pkg, list);
        }
        list.add(item);
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long num(Cursor c, String column, long fallback) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
    }

    /** One row of the TV provider, read by column name (the real type, genre and live flag too). */
    private Item read(Cursor c) {
        Item item = new Item();
        item.pkg = str(c, "package_name") == null ? "" : str(c, "package_name");
        item.title = str(c, "title") == null ? "" : str(c, "title");
        item.poster = str(c, "poster_art_uri");
        if (item.poster == null || item.poster.isEmpty()) {
            item.poster = str(c, "thumbnail_uri");
        }
        item.aspect = (int) num(c, "poster_art_aspect_ratio", 0);
        item.intentUri = str(c, "intent_uri");
        item.type = (int) num(c, "type", -1);
        item.genre = str(c, "canonical_genre");
        item.live = num(c, "live", 0) == 1;
        item.author = str(c, "author");
        if (item.pkg.isEmpty() || item.poster == null || item.poster.isEmpty()) {
            return null;
        }
        return item;
    }

    /** Decodes a poster at roughly the given size, from the cache when possible. Never throws. */
    Bitmap poster(String uri, int targetW, int targetH) {
        Bitmap cached = posters.get(uri);
        if (cached != null) {
            return cached;
        }
        Bitmap bitmap = fetch(uri, targetW, targetH);
        if (bitmap != null) {
            posters.put(uri, bitmap);
        }
        return bitmap;
    }

    /**
     * The same picture at the size the service actually offers for a full screen: apps hand
     * the home screen a thumbnail, but YouTube keeps a 1280x720 frame and Twitch a 1080p one
     * under a predictable name. Falls back to the thumbnail when the big one does not exist.
     */
    Bitmap bigPoster(String uri, int targetW, int targetH) {
        String big = highRes(uri);
        if (!big.equals(uri)) {
            Bitmap bitmap = poster(big, targetW, targetH);
            if (bitmap != null) {
                return bitmap;
            }
        }
        return poster(uri, targetW, targetH);
    }

    static String highRes(String uri) {
        if (uri == null) {
            return "";
        }
        if (uri.contains("i.ytimg.com/vi/")) {
            return uri.replaceAll("/(sddefault|hqdefault|mqdefault|default|hq720)\\.jpg", "/maxresdefault.jpg")
                    .replaceAll("\\?.*$", "");
        }
        if (uri.contains("static-cdn.jtvnw.net/previews-ttv/")) {
            return uri.replaceAll("-\\d+x\\d+\\.jpg", "-1920x1080.jpg");
        }
        if (uri.contains("pixel.disco.skyshowtime.com") && !uri.contains("w=")) {
            return uri + (uri.contains("?") ? "&" : "?") + "w=1920";
        }
        return uri;
    }

    /** The YouTube video id inside an intent URI, or null. */
    static String youtubeId(String intentUri) {
        if (intentUri == null || !intentUri.contains("youtube.com")) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})").matcher(intentUri);
        return m.find() ? m.group(1) : null;
    }

    private Bitmap fetch(String uri, int targetW, int targetH) {
        try {
            byte[] bytes = read(uri);
            if (bytes == null) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            // Services like Netflix only hand out ~850 px wide stills. Blown up on a 4K panel they
            // go soft, so a full-screen request gets a filtered upscale plus a gentle unsharp mask.
            if (bitmap != null && targetW >= 1600 && bitmap.getWidth() < 1500) {
                bitmap = enhance(bitmap, 1920);
            }
            return bitmap;
        } catch (Exception | OutOfMemoryError e) {
            note("poster " + uri + ": " + e);
            return null;
        }
    }

    /** Upscale to the given width with filtering, then sharpen (3x3 unsharp mask). */
    static Bitmap enhance(Bitmap source, int width) {
        int height = Math.max(1, source.getHeight() * width / Math.max(1, source.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (scaled != source) {
            source.recycle();
        }
        int w = scaled.getWidth();
        int h = scaled.getHeight();
        int[] in = new int[w * h];
        scaled.getPixels(in, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        final float amount = 0.55f;
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - 1) * w;
            int y1 = y * w;
            int y2 = Math.min(h - 1, y + 1) * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 1);
                int x2 = Math.min(w - 1, x + 1);
                int c = in[y1 + x];
                int rs = 0, gs = 0, bs = 0;
                int[] n = {in[y0 + x0], in[y0 + x], in[y0 + x2], in[y1 + x0], in[y1 + x2], in[y2 + x0], in[y2 + x], in[y2 + x2]};
                for (int p : n) {
                    rs += (p >> 16) & 0xFF;
                    gs += (p >> 8) & 0xFF;
                    bs += p & 0xFF;
                }
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                // blur = (8 neighbours + centre) / 9; result = centre + amount * (centre - blur)
                int br = (rs + r) / 9;
                int bg = (gs + g) / 9;
                int bb = (bs + b) / 9;
                r = clamp(r + Math.round(amount * (r - br)));
                g = clamp(g + Math.round(amount * (g - bg)));
                b = clamp(b + Math.round(amount * (b - bb)));
                out[y1 + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        scaled.recycle();
        return result;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private byte[] read(String uri) throws IOException {
        InputStream in;
        HttpURLConnection conn = null;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }
            in = conn.getInputStream();
        } else {
            in = resolver.openInputStream(Uri.parse(uri));
        }
        if (in == null) {
            return null;
        }
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) {
                    return null;
                }
            }
            return out.toByteArray();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
