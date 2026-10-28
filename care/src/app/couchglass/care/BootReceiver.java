package app.couchglass.care;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts auto-clean after the TV boots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        CleanerService.start(context);
    }
}
