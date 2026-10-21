package ro.tinulica.tvhome;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Netflix publishes only "continue watching" on this TV. What is new and watched right now comes
 * from Netflix's public Top 10 pages (Romania first, then worldwide, series and films), which
 * change every week and carry each title's artwork and id: read at most every 12 hours, kept in
 * a small file, turned into cards that open the title in the Netflix app.
 */
final class NetflixTop {

    private static final String PKG = "com.netflix.ninja";
    private static final long FRESH = 12 * 3600_000L;
    /** page, row name, program type (0 film, 1 series) */
    private static final String[][] PAGES = {
            {"https://www.netflix.com/tudum/top10/romania/tv", "Top 10 seriale în România", "1"},
            {"https://www.netflix.com/tudum/top10/romania", "Top 10 filme în România", "0"},
            {"https://www.netflix.com/tudum/top10/tv", "Top 10 seriale în lume", "1"},
            {"https://www.netflix.com/tudum/top10", "Top 10 filme în lume", "0"},
            {"https://www.netflix.com/tudum/top10/non-english/tv", "Seriale din toată lumea", "1"},
            {"https://www.netflix.com/tudum/top10/non-english", "Filme din toată lumea", "0"},
    };
    private static final Pattern CARD = Pattern.compile(
            "data-uia=\"top10-card\"[^>]*style=\"background-image:url\\(([^)]+)\\)");
    private static final Pattern TITLE = Pattern.compile("data-uia=\"top10-card-logo\"[^>]*><img src=\"[^\"]+\" alt=\"([^\"]+)\"");
    private static final Pattern WATCH = Pattern.compile("netflix\\.com/watch/(\\d+)");

    private static List<Previews.Item> cached;
    private static long cachedAt;
    private static boolean loading;

    private NetflixTop() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "netflix-top.tsv");
    }

    /** What we have now (maybe empty); a fresh copy is fetched in the background when due. */
    static synchronized List<Previews.Item> items(final Context context, final Runnable onFresh) {
        if (cached == null) {
            cached = readFile(context);
            cachedAt = file(context).lastModified();
        }
        if (System.currentTimeMillis() - cachedAt > FRESH && !loading) {
            loading = true;
            new Thread(() -> {
                List<Previews.Item> fresh = fetchAll();
                synchronized (NetflixTop.class) {
                    loading = false;
                    if (!fresh.isEmpty()) {
                        cached = fresh;
                        cachedAt = System.currentTimeMillis();
                        writeFile(context, fresh);
                    } else {
                        cachedAt = System.currentTimeMillis() - FRESH + 3600_000L; // retry in an hour
                    }
                }
                if (!fresh.isEmpty() && onFresh != null) {
                    onFresh.run();
                }
            }, "netflix-top").start();
        }
        return new ArrayList<>(cached);
    }

    private static List<Previews.Item> fetchAll() {
        Map<String, Previews.Item> byId = new LinkedHashMap<>();
        for (String[] page : PAGES) {
            String html = get(page[0]);
            if (html == null) {
                continue;
            }
            Matcher card = CARD.matcher(html);
            while (card.find()) {
                String rest = html.substring(card.end(), Math.min(html.length(), card.end() + 6000));
                Matcher t = TITLE.matcher(rest);
                Matcher w = WATCH.matcher(rest);
                if (!t.find() || !w.find()) {
                    continue;
                }
                String id = w.group(1);
                if (byId.containsKey(id)) {
                    continue;
                }
                Previews.Item it = new Previews.Item();
                it.pkg = PKG;
                it.title = unescape(t.group(1));
                it.poster = unescape(card.group(1));
                it.aspect = 0;
                it.type = Integer.parseInt(page[2]);
                it.channel = page[1];
                it.intentUri = "intent://www.netflix.com/title/" + id + "#Intent;scheme=https;package=" + PKG + ";end";
                byId.put(id, it);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String get(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124 Safari/537.36");
            c.setRequestProperty("Accept-Language", "ro-RO,ro;q=0.9,en;q=0.8");
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 6 * 1024 * 1024) {
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static void writeFile(Context context, List<Previews.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Previews.Item it : items) {
            sb.append(it.type).append('\t').append(clean(it.channel)).append('\t').append(clean(it.title))
                    .append('\t').append(clean(it.poster)).append('\t').append(clean(it.intentUri)).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(file(context))) {
            out.write(sb.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
            // Kept in memory only.
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static List<Previews.Item> readFile(Context context) {
        List<Previews.Item> out = new ArrayList<>();
        File f = file(context);
        if (!f.exists()) {
            return out;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            for (String line : new String(data, 0, read, "UTF-8").split("\n")) {
                String[] p = line.split("\t");
                if (p.length < 5) {
                    continue;
                }
                Previews.Item it = new Previews.Item();
                it.pkg = PKG;
                it.type = Integer.parseInt(p[0]);
                it.channel = p[1];
                it.title = p[2];
                it.poster = p[3];
                it.intentUri = p[4];
                out.add(it);
            }
        } catch (Exception ignored) {
            // Start empty.
        }
        return out;
    }
}
