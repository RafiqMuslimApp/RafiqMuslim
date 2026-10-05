package app.rafiq.muslim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the next alarm after reboot / app update / time, timezone or exact-alarm-permission change. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent in) { AdhanScheduler.scheduleNext(ctx); }
}
