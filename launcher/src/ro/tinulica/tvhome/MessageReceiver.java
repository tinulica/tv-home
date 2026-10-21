package ro.tinulica.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * A message from the computer (tvtool or any script): shown as a drop like any notice
 * when TV Home's service is running, else as a toast, which the system shows over any app (TCL
 * switches accessibility off while some players run). Only the shell can send it (it holds DUMP):
 *
 *     am broadcast -n ro.tinulica.tvhome/.MessageReceiver --es title "..." --es text "..."
 *
 * With "details", "options" or "style" it is a larger card (ClaudeCard; see SourcesService.claude):
 * "options" (separated by |) makes it a question, answered with the remote or from the phone.
 * "b64" true: the texts are base64 (UTF-8), so any text survives the shell.
 */
public final class MessageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        String from = intent.getStringExtra("from");
        if (intent.getBooleanExtra("b64", false)) {
            title = decode(title);
            text = decode(text);
            from = decode(from);
        }
        SourcesService service = SourcesService.instance;
        boolean card = intent.hasExtra("style") || intent.hasExtra("options") || intent.hasExtra("details")
                || intent.hasExtra("dismiss");
        if (service != null && (card ? service.claude(intent) : service.message(from, title, text))) {
            return;
        }
        if (intent.hasExtra("dismiss")) {
            return;
        }
        String line = (title != null ? title : "") + (text != null && !text.isEmpty() ? "\n" + text : "");
        android.widget.Toast.makeText(context.getApplicationContext(), line, android.widget.Toast.LENGTH_LONG).show();
    }

    private static String decode(String v) {
        if (v == null) {
            return null;
        }
        try {
            return new String(android.util.Base64.decode(v, android.util.Base64.DEFAULT),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return v;
        }
    }
}
