package ro.tinulica.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A small client for The Movie Database (TMDB): what is popular, what is where in Romania
 * (TMDB's watch providers, which come from JustWatch), posters and stills. The key is the one
 * `tvtool tmdb` put in files/tmdb.key; answers and pictures are cached so the TV asks rarely.
 *
 * "This product uses the TMDB API but is not endorsed or certified by TMDB."
 */
final class Tmdb {

    static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final String API = "https://api.themoviedb.org/3";
    private static final long CACHE_LIMIT = 80L * 1024 * 1024;

    private final Context context;
    private final String key;

    Tmdb(Context context) {
        this.context = context.getApplicationContext();
        this.key = readKey(this.context);
    }

    boolean ready() {
        return key != null;
    }

    private static String readKey(Context context) {
        File dir = context.getExternalFilesDir(null);
        File f = dir != null ? new File(dir, "tmdb.key") : null;
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            String k = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8).trim();
            return k.isEmpty() ? null : k;
        } catch (IOException e) {
            return null;
        }
    }

    /** GET api.themoviedb.org/3`path`?`query`, from the cache when younger than `maxAge` ms. */
    JSONObject get(String path, String query, long maxAge) throws IOException {
        // A v4 read token (a JWT, with dots) goes in a header; a v3 key in the address.
        boolean bearer = key.contains(".");
        String address = API + path + "?" + query + (bearer ? "" : "&api_key=" + key);
        File cache = new File(context.getCacheDir(), "tmdb/" + hash(API + path + "?" + query) + ".json");
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < maxAge) {
            try {
                return new JSONObject(new String(readAll(new FileInputStream(cache)), StandardCharsets.UTF_8));
            } catch (JSONException | IOException ignored) {
                // Fetch again.
            }
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer) {
                c.setRequestProperty("Authorization", "Bearer " + key);
            }
            if (c.getResponseCode() != 200) {
                throw new IOException("TMDB " + c.getResponseCode());
            }
            byte[] body = readAll(c.getInputStream());
            JSONObject out = new JSONObject(new String(body, StandardCharsets.UTF_8));
            cache.getParentFile().mkdirs();
            try (FileOutputStream f = new FileOutputStream(cache)) {
                f.write(body);
            }
            return out;
        } catch (IOException | JSONException e) {
            if (cache.exists()) {
                try {
                    // Offline or refused: an old answer is better than none.
                    return new JSONObject(new String(readAll(new FileInputStream(cache)), StandardCharsets.UTF_8));
                } catch (JSONException ignored) {
                    // Fall through.
                }
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** A TMDB picture (`size` like "w342", "w1280"), cached on disk, decoded at most `maxW` wide. */
    Bitmap image(String path, String size, int maxW) {
        if (path == null || path.isEmpty() || "null".equals(path)) {
            return null;
        }
        File f = new File(context.getCacheDir(), "tmdb-img/" + size + path.replace('/', '_'));
        if (!f.exists()) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(IMAGES + size + path).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) {
                    return null;
                }
                byte[] body = readAll(c.getInputStream());
                f.getParentFile().mkdirs();
                File part = new File(f.getPath() + ".part");
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(body);
                }
                if (!part.renameTo(f)) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= maxW) {
            o.inSampleSize *= 2;
        }
        f.setLastModified(System.currentTimeMillis());
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Keeps the picture cache under its limit, dropping the least recently used first. */
    void prune() {
        File[] files = new File(context.getCacheDir(), "tmdb-img").listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (total <= CACHE_LIMIT) {
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= CACHE_LIMIT * 3 / 4) {
                break;
            }
            total -= f.length();
            f.delete();
        }
    }

    /**
     * The title's own ids in the streaming apps, from Wikidata (free, CC0): "netflix", "disney",
     * "hbo", "prime". With these the app opens on the very title instead of its home screen.
     */
    Map<String, String> streamingIds(boolean tv, int tmdbId) {
        Map<String, String> out = new HashMap<>();
        File cache = new File(context.getCacheDir(), "tmdb/wd-" + (tv ? "tv" : "movie") + tmdbId + ".json");
        JSONObject answer = null;
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 7L * 24 * 3600_000L) {
            try {
                answer = new JSONObject(new String(readAll(new FileInputStream(cache)), StandardCharsets.UTF_8));
            } catch (IOException | JSONException ignored) {
                answer = null;
            }
        }
        if (answer == null) {
            String sparql = "SELECT ?netflix ?disney ?hbo ?prime WHERE { ?item wdt:" + (tv ? "P4983" : "P4947")
                    + " \"" + tmdbId + "\" . OPTIONAL { ?item wdt:P1874 ?netflix } OPTIONAL { ?item wdt:"
                    + (tv ? "P7596" : "P7595") + " ?disney } OPTIONAL { ?item wdt:P8298 ?hbo }"
                    + " OPTIONAL { ?item wdt:P8055 ?prime } } LIMIT 1";
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://query.wikidata.org/sparql?format=json&query="
                        + URLEncoder.encode(sparql, "UTF-8")).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "TVHome/3.0 (personal TV launcher)");
                c.setRequestProperty("Accept", "application/sparql-results+json");
                if (c.getResponseCode() == 200) {
                    byte[] body = readAll(c.getInputStream());
                    answer = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    cache.getParentFile().mkdirs();
                    try (FileOutputStream f = new FileOutputStream(cache)) {
                        f.write(body);
                    }
                }
            } catch (IOException | JSONException ignored) {
                answer = null;
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        if (answer != null) {
            JSONArray rows = answer.optJSONObject("results") != null
                    ? answer.optJSONObject("results").optJSONArray("bindings") : null;
            JSONObject row = rows != null && rows.length() > 0 ? rows.optJSONObject(0) : null;
            if (row != null) {
                for (String name : new String[]{"netflix", "disney", "hbo", "prime"}) {
                    JSONObject v = row.optJSONObject(name);
                    if (v != null && !v.optString("value").isEmpty()) {
                        out.put(name, v.optString("value"));
                    }
                }
            }
        }
        return out;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                b.append(String.format("%02x", d[i]));
            }
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = input.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
