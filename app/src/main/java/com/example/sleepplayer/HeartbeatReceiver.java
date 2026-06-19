package com.example.sleepplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Empfängt den AlarmManager-Alarm im Sleep-Mode und startet den Service neu,
 * damit die MediaSession auch im Doze-Mode aktiv bleibt.
 * Handler.postDelayed() schläft selbst im Doze-Mode ein (kein WakeLock),
 * setAndAllowWhileIdle() weckt dagegen die CPU zuverlässig auf (ohne dass
 * dafür die SCHEDULE_EXACT_ALARM-Permission nötig ist).
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final String TAG = "HeartbeatReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        LogManager.getInstance(context).log(TAG, "Heartbeat-Alarm empfangen – starte Service");
        Intent serviceIntent = new Intent(context, PlaybackService.class);
        serviceIntent.setAction(PlaybackService.ACTION_SLEEP_HEARTBEAT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
