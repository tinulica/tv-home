package ro.tinulica.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Which Claude account each profile is linked to. Every computer that talks to the TV with
 * tvtool is an account (`tvtool claude --name ...` names it; it writes files/claude/accounts.json).
 * A profile sees the messages, questions, tasks and usage of the account it is linked to and
 * nothing of the others; the first profile is linked to the first account until it chooses.
 * The link is chosen from Control Center (the Claude circle).
 */
final class ClaudeLink {

    private static final String KEY = "claude_account";
    /** Chosen to see no account (the key's absence means "not chosen yet"). */
    private static final String NONE = "-";

    static final class Account {
        String id;
        String name;
        String detail;
    }

    private ClaudeLink() {
    }

    static List<Account> accounts(Context context) {
        List<Account> out = new ArrayList<>();
        File dir = context.getExternalFilesDir("claude");
        File f = dir == null ? null : new File(dir, "accounts.json");
        if (f == null || !f.exists()) {
            return out;
        }
        try {
            JSONArray a = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8)).optJSONArray("accounts");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Account acc = new Account();
                acc.id = o.optString("id", "");
                acc.name = o.optString("name", "Claude");
                acc.detail = o.optString("detail", "");
                if (!acc.id.isEmpty()) {
                    out.add(acc);
                }
            }
        } catch (Exception ignored) {
            // No accounts.
        }
        return out;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** The account the current profile sees, or "" for none. */
    static String linked(Context context) {
        SharedPreferences prefs = prefs(context);
        String chosen = prefs.getString(Profiles.scoped(prefs, KEY), null);
        if (chosen == null) {
            if (!Profiles.FIRST.equals(Profiles.current(prefs))) {
                return "";
            }
            List<Account> all = accounts(context);
            return all.isEmpty() ? "" : all.get(0).id;
        }
        return NONE.equals(chosen) ? "" : chosen;
    }

    /** Links the current profile to `id` ("" for none). */
    static void link(Context context, String id) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putString(Profiles.scoped(prefs, KEY), id == null || id.isEmpty() ? NONE : id).apply();
    }

    /** Whether something for `account` shows on the current profile (no account: any linked one). */
    static boolean shows(Context context, String account) {
        String linked = linked(context);
        return !linked.isEmpty() && (account == null || account.isEmpty() || account.equals(linked));
    }

    /** One of the linked account's files ("tasks", "usage"), or null when none is linked. */
    static File file(Context context, String kind) {
        String linked = linked(context);
        File dir = context.getExternalFilesDir("claude");
        return linked.isEmpty() || dir == null ? null : new File(dir, kind + "-" + linked + ".json");
    }
}
