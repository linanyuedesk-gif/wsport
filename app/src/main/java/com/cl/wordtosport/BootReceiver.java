package com.cl.wordtosport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "WordToSportBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed received, starting application...");
            
            // Acquire a temporary wake lock to ensure the device stays awake while we launch
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordToSport:BootLock");
            wl.acquire(10000); // Hold for 10 seconds max

            Intent i = new Intent(context, MainActivity.class);
            // Flag is required when starting activity from a Receiver context
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); 
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
            
            wl.release();
        }
    }
}
