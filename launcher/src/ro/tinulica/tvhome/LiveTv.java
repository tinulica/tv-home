package ro.tinulica.tvhome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

/**
 * Opening the TV's own channels (antenna / cable). Launching the Live TV app is not enough:
 * TCL's player resumes whatever it showed last, often an HDMI input (the PlayStation). So a
 * tuner channel is asked for by its URI: the last one we opened, else the lowest number.
 */
final class LiveTv {

    private static final String PREFS = "home";
    private static final String KEY_LAST = "live_tv_channel";
    private static final String PLAYER = "com.tcl.tv";

    private LiveTv() {
    }

    /** The id of a channel of a tuner input (not HDMI), or -1. */
    static long channel(Context context) {
        TvInputManager inputs = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        long last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, -1);
        long best = -1;
        float bestNumber = Float.MAX_VALUE;
        // Third-party apps may not filter the channel table, so it is read whole.
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID,
                        TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_BROWSABLE},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                String inputId = c.getString(1);
                if (inputId == null || inputs == null) {
                    continue;
                }
                TvInputInfo info = inputs.getTvInputInfo(inputId);
                if (info == null || info.isPassthroughInput()) {
                    continue;
                }
                long id = c.getLong(0);
                if (id == last) {
                    return id;
                }
                if (!c.isNull(3) && c.getInt(3) == 0) {
                    continue;
                }
                float number = parse(c.getString(2));
                if (number < bestNumber) {
                    bestNumber = number;
                    best = id;
                }
            }
        } catch (RuntimeException ignored) {
            // No access to the channel table.
        }
        return best;
    }

    private static float parse(String number) {
        if (number == null) {
            return Float.MAX_VALUE - 1;
        }
        try {
            return Float.parseFloat(number.replace('-', '.'));
        } catch (NumberFormatException e) {
            return Float.MAX_VALUE - 1;
        }
    }

    /** Opens the TV's channels in TV Home's own channel screen (the last one, else the first). */
    static boolean open(Context context) {
        if (ChannelActivity.loadChannels(context).isEmpty()) {
            // No tuner channels we can read: TCL's player, which can also scan for them.
            Intent app = context.getPackageManager().getLeanbackLaunchIntentForPackage(PLAYER);
            if (app == null) {
                app = context.getPackageManager().getLaunchIntentForPackage(PLAYER);
            }
            if (app != null) {
                context.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return false;
        }
        context.startActivity(ChannelActivity.intent(context, -1));
        return true;
    }
}
