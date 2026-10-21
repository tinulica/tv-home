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
 */
public final class MessageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        String from = intent.getStringExtra("from");
        SourcesService service = SourcesService.instance;
        if (service != null && service.message(from, title, text)) {
            return;
        }
        String line = (title != null ? title : "") + (text != null && !text.isEmpty() ? "\n" + text : "");
        android.widget.Toast.makeText(context.getApplicationContext(), line, android.widget.Toast.LENGTH_LONG).show();
    }
}
