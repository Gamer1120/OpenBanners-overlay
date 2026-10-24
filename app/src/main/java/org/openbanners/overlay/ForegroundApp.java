package org.openbanners.overlay;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

/**
 * Which app is in front, for "Only show over Ingress" (screach 10-24). Android only tells with Usage access, a special
 * permission the player switches on by hand in the system settings; without it the overlay simply stays visible.
 */
class ForegroundApp {
    static final String INGRESS = "com.nianticproject.ingress";
    /** The first look goes back this far to find the app that is in front right now. */
    private static final long FIRST_LOOK_BACK_MILLIS = 60 * 60 * 1000;

    private final Context context;
    private long lastQuery;
    private String current;

    ForegroundApp(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean hasAccess(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                : appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** The package of the app in front, or null when unknown (no access, nothing seen yet). */
    String current() {
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        long from = lastQuery == 0 ? now - FIRST_LOOK_BACK_MILLIS : lastQuery - 2_000;
        lastQuery = now;
        UsageEvents events = usage.queryEvents(from, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {  // = ACTIVITY_RESUMED
                current = event.getPackageName();
            }
        }
        return current;
    }

    /** Ingress or this app (its settings) in front, or unknown: show the overlay. */
    boolean overlayWanted() {
        String app = current();
        return app == null || app.equals(INGRESS) || app.equals(context.getPackageName());
    }
}
