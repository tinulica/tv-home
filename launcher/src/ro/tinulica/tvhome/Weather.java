package ro.tinulica.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The weather where the TV is, and the official weather warnings for its county, all from the
 * internet: the place from the connection (ipwho.is, once a day), the weather from Open-Meteo
 * (free, no key), the warnings of Romania's ANM through MeteoAlarm (the European feed of the
 * national weather services). Read on a background thread.
 */
final class Weather {

    /** The place, from the TV's internet connection (a city, its county, coordinates). */
    static final class Place {
        String city = "";
        String region = "";
        double lat;
        double lon;
    }

    /** The weather now. */
    static final class Now {
        double temp;
        double high;
        double low;
        int code;
        boolean day = true;
        String city = "";
        /** When the forecast says the sky turns into another kind (clear, cloudy, rain...), or 0. */
        long changeAt;
        int changeCode = -1;
    }

    /** One warning: level 2 yellow, 3 orange, 4 red; type as MeteoAlarm numbers them. */
    static final class Alert {
        String id;
        int level;
        int type;
        String headline = "";
        String area = "";
        long expires;
    }

    private Weather() {
    }

    // ------------------------------------------------------------------ the place

    static Place place(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        String saved = prefs.getString("weather_place", "");
        String[] parts = saved.split("\\|");
        if (parts.length == 5) {
            try {
                if (System.currentTimeMillis() - Long.parseLong(parts[4]) < 24 * 3600_000L) {
                    Place p = new Place();
                    p.city = parts[0];
                    p.region = parts[1];
                    p.lat = Double.parseDouble(parts[2]);
                    p.lon = Double.parseDouble(parts[3]);
                    return p;
                }
            } catch (NumberFormatException ignored) {
                // Ask again.
            }
        }
        try {
            JSONObject o = new JSONObject(get("https://ipwho.is/?fields=success,city,region,latitude,longitude"));
            if (!o.optBoolean("success", true)) {
                return null;
            }
            Place p = new Place();
            p.city = romanian(o.optString("city", ""));
            p.region = o.optString("region", "");
            p.lat = o.optDouble("latitude", 0);
            p.lon = o.optDouble("longitude", 0);
            if (p.lat == 0 && p.lon == 0) {
                return null;
            }
            prefs.edit().putString("weather_place", p.city.replace("|", " ") + "|" + p.region.replace("|", " ") + "|"
                    + p.lat + "|" + p.lon + "|" + System.currentTimeMillis()).apply();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the weather

    static Now now(Place place) {
        try {
            JSONObject o = new JSONObject(get(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                            + "&minutely_15=weather_code&forecast_minutely_15=32&forecast_days=2"
                            + "&timeformat=unixtime&timezone=auto", place.lat, place.lon)));
            JSONObject c = o.getJSONObject("current");
            Now n = new Now();
            n.temp = c.optDouble("temperature_2m");
            n.code = c.optInt("weather_code");
            n.day = c.optInt("is_day", 1) == 1;
            JSONObject d = o.optJSONObject("daily");
            if (d != null) {
                n.high = d.getJSONArray("temperature_2m_max").optDouble(0, n.temp);
                n.low = d.getJSONArray("temperature_2m_min").optDouble(0, n.temp);
            }
            n.city = place.city;
            // The next quarter of an hour (in the coming eight) whose sky is of another kind.
            JSONObject m = o.optJSONObject("minutely_15");
            JSONArray times = m != null ? m.optJSONArray("time") : null;
            JSONArray codes = m != null ? m.optJSONArray("weather_code") : null;
            if (times != null && codes != null) {
                long now = System.currentTimeMillis() / 1000;
                for (int i = 0; i < Math.min(times.length(), codes.length()); i++) {
                    long at = times.optLong(i);
                    int code = codes.optInt(i, -1);
                    if (at > now + 300 && code >= 0 && group(code) != group(n.code)) {
                        n.changeAt = at * 1000;
                        n.changeCode = code;
                        break;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    /** The scene for a WMO weather code (Open-Meteo's). */
    static int sky(int code, boolean day) {
        if (code <= 1) {
            return day ? WeatherCard.SUN : WeatherCard.MOON;
        }
        if (code == 2) {
            return WeatherCard.PARTLY;
        }
        if (code == 3) {
            return WeatherCard.CLOUDS;
        }
        if (code == 45 || code == 48) {
            return WeatherCard.FOG;
        }
        if (code >= 51 && code <= 57) {
            return WeatherCard.DRIZZLE;
        }
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) {
            return WeatherCard.RAIN;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return WeatherCard.SNOW;
        }
        if (code >= 95) {
            return WeatherCard.STORM;
        }
        return WeatherCard.CLOUDS;
    }

    static String words(int code) {
        switch (code) {
            case 0: return "Senin";
            case 1: return "Predominant senin";
            case 2: return "Parțial noros";
            case 3: return "Înnorat";
            case 45: case 48: return "Ceață";
            case 51: case 53: case 55: return "Burniță";
            case 56: case 57: return "Burniță înghețată";
            case 61: return "Ploaie slabă";
            case 63: return "Ploaie";
            case 65: return "Ploaie puternică";
            case 66: case 67: return "Ploaie înghețată";
            case 71: return "Ninsoare slabă";
            case 73: return "Ninsoare";
            case 75: return "Ninsoare abundentă";
            case 77: return "Fulgi mărunți";
            case 80: return "Averse slabe";
            case 81: return "Averse";
            case 82: return "Averse puternice";
            case 85: case 86: return "Averse de ninsoare";
            case 95: return "Furtună";
            case 96: case 99: return "Furtună cu grindină";
            default: return "";
        }
    }

    /** Rain, snow or a storm. */
    static boolean wet(int code) {
        return code >= 51;
    }

    /** The kind of sky: 0 clear, 1 cloudy, 2 fog, 3 rain, 4 snow, 5 storm. */
    static int group(int code) {
        if (code <= 2) {
            return 0;
        }
        if (code == 3) {
            return 1;
        }
        if (code == 45 || code == 48) {
            return 2;
        }
        if (code >= 95) {
            return 5;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return 4;
        }
        return code >= 51 ? 3 : 1;
    }

    /** What changed, in a few words: "Începe să plouă", "S-a oprit ninsoarea", "S-a înnorat"… */
    static String change(int was, int code) {
        int from = group(was);
        int to = group(code);
        switch (to) {
            case 5: return "Vine furtuna";
            case 4: return "Începe să ningă";
            case 3: return from == 4 ? "Ninsoarea trece în ploaie" : "Începe să plouă";
            case 2: return "S-a lăsat ceața";
            case 1: return from == 3 ? "S-a oprit ploaia" : from == 4 ? "S-a oprit ninsoarea"
                    : from == 5 ? "Furtuna a trecut" : "S-a înnorat";
            default: return from == 3 ? "S-a oprit ploaia" : from == 4 ? "S-a oprit ninsoarea"
                    : from == 5 ? "Furtuna a trecut" : "Se înseninează";
        }
    }

    // ------------------------------------------------------------------ the warnings

    /** The warnings in force now for the place's county (in Romanian). */
    static List<Alert> alerts(Place place) {
        List<Alert> out = new ArrayList<>();
        try {
            JSONArray warnings = new JSONObject(get("https://feeds.meteoalarm.org/api/v1/warnings/feeds-romania"))
                    .optJSONArray("warnings");
            String county = plain(place.region).replace("judetul", "").replace("county", "").trim();
            long now = System.currentTimeMillis();
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
            for (int i = 0; warnings != null && i < warnings.length(); i++) {
                JSONObject alert = warnings.getJSONObject(i).optJSONObject("alert");
                JSONArray infos = alert != null ? alert.optJSONArray("info") : null;
                for (int j = 0; infos != null && j < infos.length(); j++) {
                    JSONObject info = infos.getJSONObject(j);
                    if (!info.optString("language").startsWith("ro")) {
                        continue;
                    }
                    String where = null;
                    JSONArray areas = info.optJSONArray("area");
                    for (int k = 0; areas != null && k < areas.length(); k++) {
                        String a = areas.getJSONObject(k).optString("areaDesc");
                        if (!county.isEmpty() && plain(a).contains(county)) {
                            where = a;
                        }
                    }
                    if (where == null) {
                        continue;
                    }
                    Alert w = new Alert();
                    w.id = alert.optString("identifier") + "/" + where;
                    w.headline = info.optString("headline", info.optString("description", ""));
                    w.area = where;
                    try {
                        w.expires = iso.parse(info.optString("expires")).getTime();
                    } catch (Exception e) {
                        w.expires = now + 6 * 3600_000L;
                    }
                    JSONArray params = info.optJSONArray("parameter");
                    for (int k = 0; params != null && k < params.length(); k++) {
                        JSONObject p = params.getJSONObject(k);
                        String v = p.optString("value");
                        int n = v.indexOf(';');
                        int num = 0;
                        try {
                            num = Integer.parseInt((n > 0 ? v.substring(0, n) : v).trim());
                        } catch (NumberFormatException ignored) {
                            // Not a number.
                        }
                        if ("awareness_level".equals(p.optString("valueName"))) {
                            w.level = num;
                        } else if ("awareness_type".equals(p.optString("valueName"))) {
                            w.type = num;
                        }
                    }
                    if (w.level >= 2 && w.expires > now) {
                        out.add(w);
                    }
                }
            }
        } catch (Exception ignored) {
            // No warnings we can read now.
        }
        return out;
    }

    static String alertName(int type) {
        switch (type) {
            case 1: return "Vânt puternic";
            case 2: return "Ninsori și polei";
            case 3: return "Furtuni";
            case 4: return "Ceață";
            case 5: return "Caniculă";
            case 6: return "Ger";
            case 7: return "Fenomene costiere";
            case 8: return "Incendii de vegetație";
            case 9: return "Avalanșe";
            case 10: return "Ploi abundente";
            case 12: return "Inundații";
            case 13: return "Ploi și inundații";
            default: return "Fenomene periculoase";
        }
    }

    static int alertSky(int type) {
        switch (type) {
            case 1: return WeatherCard.WIND;
            case 2: case 6: return WeatherCard.SNOW;
            case 3: return WeatherCard.STORM;
            case 4: return WeatherCard.FOG;
            case 5: case 8: return WeatherCard.HEAT;
            case 10: case 12: case 13: return WeatherCard.RAIN;
            default: return WeatherCard.CLOUDS;
        }
    }

    static int alertColour(int level) {
        return level >= 4 ? 0xFFFF453A : level == 3 ? 0xFFFF9F0A : 0xFFFFD60A;
    }

    static String alertLevel(int level) {
        return level >= 4 ? "COD ROȘU" : level == 3 ? "COD PORTOCALIU" : "COD GALBEN";
    }

    /** "până la 21:00" today, "până mâine, 08:00", or the day's name further away. */
    static String until(long time) {
        Calendar c = Calendar.getInstance();
        Calendar e = Calendar.getInstance();
        e.setTimeInMillis(time);
        String hour = new SimpleDateFormat("HH:mm", Locale.ROOT).format(e.getTime());
        int days = e.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
                + 365 * (e.get(Calendar.YEAR) - c.get(Calendar.YEAR));
        if (days <= 0) {
            return "până la " + hour;
        }
        if (days == 1) {
            return "până mâine, " + hour;
        }
        return "până " + new SimpleDateFormat("EEEE", new Locale("ro")).format(e.getTime()) + ", " + hour;
    }

    // ------------------------------------------------------------------ helpers

    /** The location service writes Romanian places without diacritics: put them back. */
    private static String romanian(String city) {
        String[][] names = {
                {"Iasi", "Iași"}, {"Bucharest", "București"}, {"Bucuresti", "București"}, {"Brasov", "Brașov"},
                {"Constanta", "Constanța"}, {"Timisoara", "Timișoara"}, {"Ploiesti", "Ploiești"}, {"Bacau", "Bacău"},
                {"Galati", "Galați"}, {"Botosani", "Botoșani"}, {"Pitesti", "Pitești"}, {"Buzau", "Buzău"},
                {"Focsani", "Focșani"}, {"Bistrita", "Bistrița"}, {"Targu Mures", "Târgu Mureș"},
                {"Ramnicu Valcea", "Râmnicu Vâlcea"}, {"Piatra Neamt", "Piatra Neamț"}, {"Targoviste", "Târgoviște"},
                {"Calarasi", "Călărași"}, {"Slobozia", "Slobozia"}, {"Vaslui", "Vaslui"}, {"Barlad", "Bârlad"},
                {"Pascani", "Pașcani"}, {"Resita", "Reșița"}, {"Medias", "Mediaș"}, {"Sfantu Gheorghe", "Sfântu Gheorghe"},
        };
        for (String[] n : names) {
            if (n[0].equalsIgnoreCase(city.trim())) {
                return n[1];
            }
        }
        return city;
    }

    private static String plain(String s) {
        String n = Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replace('ş', 's').replace('ţ', 't');
    }

    private static String get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "TVHome/3.0 (personal TV launcher)");
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
