package com.poc.gctune;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Keep-alive watchdog: a self-rescheduling alarm that survives swipe-away,
 * process death and doze (setAndAllowWhileIdle needs no exact-alarm
 * permission). While the "Неубиваемый фон" toggle is on, every INTERVAL the
 * alarm fires, raises the KeepAliveService priority and schedules the next
 * tick - so the process always comes back even if the task was cleared.
 *
 * A plain force-stop by the user still wins (the system drops all alarms
 * until the next manual launch) - documented on the "Прочее" page.
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "GCTune";
    private static final int REQ = 7701;
    /** AlarmManager allows ~1/9 min per app for setAndAllowWhileIdle. */
    public static final long INTERVAL = 12 * 60 * 1000L;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gctune_m3", Context.MODE_PRIVATE);
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, KeepAliveReceiver.class);
        return PendingIntent.getBroadcast(c, REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context c, long atMs) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am == null) return;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi(c));
            prefs(c).edit().putLong("ka_next", atMs).apply();
        } catch (Throwable t) {
            Log.e(TAG, "keep-alive schedule failed: " + t);
        }
    }

    public static void cancel(Context c) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am != null) am.cancel(pi(c));
        } catch (Throwable ignored) {}
        prefs(c).edit().remove("ka_next").apply();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        boolean on;
        try {
            on = prefs(ctx).getBoolean("keep_alive", false);
        } catch (Throwable t) {
            on = false;
        }
        if (!on) {
            KeepAliveService.stop(ctx);
            return;
        }
        try {
            KeepAliveService.start(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "keep-alive service start: " + t);
        }
        schedule(ctx, System.currentTimeMillis() + INTERVAL);
    }
}
