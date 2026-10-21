package ro.tinulica.tvhome;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App folders on the grid, stored as "Name=pkg,pkg;Other=pkg" in the preferences. */
final class Folders {

    private static final String KEY = "folders";

    private Folders() {
    }

    static LinkedHashMap<String, List<String>> load(SharedPreferences prefs) {
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        String saved = prefs.getString(Profiles.scoped(prefs, KEY), "");
        for (String entry : saved.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            List<String> apps = new ArrayList<>();
            for (String pkg : entry.substring(eq + 1).split(",")) {
                if (!pkg.trim().isEmpty()) {
                    apps.add(pkg.trim());
                }
            }
            out.put(entry.substring(0, eq).trim(), apps);
        }
        return out;
    }

    static void save(SharedPreferences prefs, Map<String, List<String>> folders) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : folders.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey().replace(';', ' ').replace('=', ' ')).append('=');
            sb.append(String.join(",", entry.getValue()));
        }
        prefs.edit().putString(Profiles.scoped(prefs, KEY), sb.toString()).apply();
    }

    /** The folder holding this app, or null. */
    static String of(Map<String, List<String>> folders, String pkg) {
        for (Map.Entry<String, List<String>> entry : folders.entrySet()) {
            if (entry.getValue().contains(pkg)) {
                return entry.getKey();
            }
        }
        return null;
    }

    static void move(Map<String, List<String>> folders, String pkg, String target) {
        for (List<String> apps : folders.values()) {
            apps.remove(pkg);
        }
        if (target != null) {
            List<String> apps = folders.get(target);
            if (apps == null) {
                apps = new ArrayList<>();
                folders.put(target, apps);
            }
            apps.add(pkg);
        }
    }
}
