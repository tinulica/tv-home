package ro.tinulica.tvhome;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Sees the notifications apps and the system post, and has TV Home show each new one as a
 * Liquid Glass card (drawn by SourcesService, which can draw over any app). Ongoing ones
 * (music, downloads, services), group summaries and silent updates of the same notification
 * are left alone. Needs notification access: `tvtool notificari` grants it.
 */
public final class NoticeListener extends NotificationListenerService {

    private final Map<String, String> lastText = new HashMap<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) {
            return;
        }
        Notification n = sbn.getNotification();
        if (n == null || sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0
                || (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 && lastText.containsKey(sbn.getKey())) {
            return;
        }
        String category = n.category;
        if (Notification.CATEGORY_TRANSPORT.equals(category) || Notification.CATEGORY_SERVICE.equals(category)
                || Notification.CATEGORY_PROGRESS.equals(category)) {
            return;
        }
        Bundle extras = n.extras;
        CharSequence title = extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence text = extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null;
        if (text == null && extras != null) {
            text = extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) {
            return;
        }
        String signature = title + "|" + text;
        if (signature.equals(lastText.put(sbn.getKey(), signature))) {
            return; // the same notification posted again unchanged
        }
        PackageManager pm = getPackageManager();
        String app = sbn.getPackageName();
        Drawable icon = null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(sbn.getPackageName(), 0);
            app = String.valueOf(pm.getApplicationLabel(info));
            icon = pm.getApplicationIcon(info);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep the package name.
        }
        Icon large = n.getLargeIcon();
        Drawable picture = null;
        if (large != null) {
            try {
                picture = large.loadDrawable(this);
            } catch (RuntimeException ignored) {
                picture = null;
            }
        }
        SourcesService service = SourcesService.instance;
        if (service != null) {
            service.showNotice(app, picture != null ? picture : icon,
                    title != null ? title.toString() : app, text != null ? text.toString() : "");
            // Ours replaces the TV's own pop-up: the original is dismissed (only clearable ones).
            if (sbn.isClearable()) {
                try {
                    cancelNotification(sbn.getKey());
                } catch (RuntimeException ignored) {
                    // Listener not connected any more.
                }
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) {
            lastText.remove(sbn.getKey());
        }
    }
}
