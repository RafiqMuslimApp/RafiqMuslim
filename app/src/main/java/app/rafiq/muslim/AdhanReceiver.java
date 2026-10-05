package app.rafiq.muslim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Fires at prayer time - works with the app closed, screen locked and in Doze. */
public class AdhanReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent in) {
        try {
            String key = in.getStringExtra("key");
            long at = in.getLongExtra("at", 0);
            boolean stale = at > 0 && System.currentTimeMillis() - at > 10 * 60 * 1000L;
            if (!stale && AdhanScheduler.claim(ctx, key)) {
                boolean play = in.getBooleanExtra("play", true), notif = in.getBooleanExtra("notif", true);
                String title = in.getStringExtra("title"), body = in.getStringExtra("body");
                if (play) {
                    try { ContextCompat.startForegroundService(ctx, new Intent(ctx, AdhanService.class).putExtras(in.getExtras())); }
                    catch (Exception e) { AdhanService.notifyOnly(ctx, title, body, false); }
                } else if (notif) {
                    AdhanService.notifyOnly(ctx, title, body, "reminders".equals(in.getStringExtra("ch")));
                }
            }
        } finally {
            AdhanScheduler.scheduleNext(ctx); // arm the following item
        }
    }
}
