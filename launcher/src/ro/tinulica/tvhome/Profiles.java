package ro.tinulica.tvhome;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TV Home's own profiles (Android's user switching is reserved to the system): each has a name,
 * a colour or the photo pushed with `tvtool profile` (the first one), its own row of apps and
 * its own favourite channels. Stored in the "home" preferences as "id|name|colour" lines.
 */
final class Profiles {

    static final String FIRST = "p0";
    private static final String KEY_LIST = "profiles";
    private static final String KEY_CURRENT = "profile";
    private static final int[] COLOURS = {0xFF5E8BFF, 0xFFFF7A59, 0xFF34C38F, 0xFFB46CFF, 0xFFFFB238, 0xFFFF5C8A};

    static final class Profile {
        String id;
        String name;
        int colour;
    }

    private Profiles() {
    }

    static String current(SharedPreferences prefs) {
        return prefs.getString(KEY_CURRENT, FIRST);
    }

    static void setCurrent(SharedPreferences prefs, String id) {
        prefs.edit().putString(KEY_CURRENT, id).apply();
    }

    /** A preference key of the current profile (the first profile keeps the plain key). */
    static String scoped(SharedPreferences prefs, String key) {
        String id = current(prefs);
        return FIRST.equals(id) ? key : key + "_" + id;
    }

    static List<Profile> all(SharedPreferences prefs, String firstName) {
        List<Profile> out = new ArrayList<>();
        Profile first = new Profile();
        first.id = FIRST;
        first.name = prefs.getString("profile_name_" + FIRST, firstName);
        first.colour = COLOURS[0];
        out.add(first);
        String saved = prefs.getString(KEY_LIST, "");
        for (String line : saved.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 3) {
                Profile p = new Profile();
                p.id = parts[0];
                p.name = parts[1];
                try {
                    p.colour = (int) Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    p.colour = COLOURS[1];
                }
                out.add(p);
            }
        }
        return out;
    }

    /** The picture chosen for a profile: "a01".."a12" (bundled avatars), "photo", "" (initial). */
    static String avatarOf(SharedPreferences prefs, String id) {
        return prefs.getString("profile_avatar_" + id, FIRST.equals(id) ? "photo" : "");
    }

    static void setAvatar(SharedPreferences prefs, String id, String avatar) {
        prefs.edit().putString("profile_avatar_" + id, avatar).apply();
    }

    /** A profile's round picture at the given size. */
    static android.graphics.Bitmap picture(android.content.Context context, SharedPreferences prefs, Profile p, int size) {
        String a = avatarOf(prefs, p.id);
        android.graphics.Bitmap src = null;
        if (a.startsWith("a")) {
            try (java.io.InputStream in = context.getAssets().open("avatars/" + a + ".png")) {
                src = android.graphics.BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
                src = null;
            }
        } else if ("photo".equals(a)) {
            java.io.File dir = context.getExternalFilesDir(null);
            for (String n : new String[]{"profile.jpg", "profile.png", "profile.webp"}) {
                java.io.File f = dir != null ? new java.io.File(dir, n) : null;
                if (f != null && f.exists()) {
                    src = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (src != null) {
                        break;
                    }
                }
            }
        }
        if (src == null) {
            return initial(p, size);
        }
        // Centre-cropped into a circle.
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        int side = Math.min(src.getWidth(), src.getHeight());
        android.graphics.Rect from = new android.graphics.Rect((src.getWidth() - side) / 2, (src.getHeight() - side) / 2,
                (src.getWidth() + side) / 2, (src.getHeight() + side) / 2);
        c.drawBitmap(src, from, new android.graphics.Rect(0, 0, size, size), paint);
        return out;
    }

    static Profile add(SharedPreferences prefs, String name) {
        List<Profile> existing = all(prefs, "");
        Profile p = new Profile();
        p.id = "p" + System.currentTimeMillis();
        p.name = name.replace("|", " ").replace("\n", " ").trim();
        p.colour = COLOURS[existing.size() % COLOURS.length];
        String saved = prefs.getString(KEY_LIST, "");
        String line = p.id + "|" + p.name + "|" + (p.colour & 0xFFFFFFFFL);
        prefs.edit().putString(KEY_LIST, saved.isEmpty() ? line : saved + "\n" + line).apply();
        return p;
    }

    /** A new name for a profile (the first one keeps it apart from the Google account's). */
    static void rename(SharedPreferences prefs, String id, String name) {
        String clean = name.replace("|", " ").replace("\n", " ").trim();
        if (clean.isEmpty()) {
            return;
        }
        if (FIRST.equals(id)) {
            prefs.edit().putString("profile_name_" + FIRST, clean).apply();
            return;
        }
        StringBuilder out = new StringBuilder();
        for (String line : prefs.getString(KEY_LIST, "").split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length >= 3 && parts[0].equals(id)) {
                line = parts[0] + "|" + clean + "|" + parts[2];
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        prefs.edit().putString(KEY_LIST, out.toString()).apply();
    }

    static void remove(SharedPreferences prefs, String id) {
        if (FIRST.equals(id)) {
            return;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : prefs.getString(KEY_LIST, "").split("\n")) {
            if (!line.isEmpty() && !line.startsWith(id + "|")) {
                if (kept.length() > 0) {
                    kept.append('\n');
                }
                kept.append(line);
            }
        }
        SharedPreferences.Editor e = prefs.edit().putString(KEY_LIST, kept.toString());
        if (id.equals(current(prefs))) {
            e.putString(KEY_CURRENT, FIRST);
        }
        e.apply();
    }

    /** A round avatar with the initial, in the profile's colour (for profiles without a photo). */
    static Bitmap initial(Profile p, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        int light = blend(p.colour, 0xFFFFFFFF, 0.25f);
        fill.setShader(new LinearGradient(0, 0, 0, size, light, p.colour, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, fill);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size * 0.44f);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String letter = p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(Locale.getDefault());
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, text);
        return out;
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
