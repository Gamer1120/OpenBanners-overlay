package org.openbanners.overlay;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

/**
 * Which app is in front, for "Only show over Ingress" (screach 10-24). Android only tells with Usage access, a special
 * permission the player switches on by hand in the system settings; without it the overlay simply stays visible.
 */
class ForegroundApp {
    static final String INGRESS = "com.nianticproject.ingress";
    /** The first look goes back this far to find the app that is in front right now. */
    private static final long FIRST_LOOK_BACK_MILLIS = 60 * 60 * 1000;

    /** UsageEvents.Event.ACTIVITY_STOPPED, API 29. */
    private static final int ACTIVITY_STOPPED = 23;

    private final Context context;
    private final ForegroundState state;
    private long lastQuery;

    ForegroundApp(Context context) {
        this.context = context.getApplicationContext();
        state = new ForegroundState(INGRESS, this.context.getPackageName());
        state.ignore("com.android.systemui");
        state.ignore(launcher(this.context));
        String keyboard = Settings.Secure.getString(this.context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (keyboard != null) state.ignore(keyboard.split("/")[0]);
    }

    private static String launcher(Context context) {
        try {
            ResolveInfo home = context.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return home == null || home.activityInfo == null ? null : home.activityInfo.packageName;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean hasAccess(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                : appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Reads the new activity events into the state (nothing without access). */
    private void update() {
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        long from = lastQuery == 0 ? now - FIRST_LOOK_BACK_MILLIS : lastQuery - 2_000;
        lastQuery = now;
        UsageEvents events = usage.queryEvents(from, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            switch (event.getEventType()) {
                case UsageEvents.Event.MOVE_TO_FOREGROUND:  // = ACTIVITY_RESUMED
                    state.activityResumed(event.getPackageName(), event.getClassName());
                    break;
                case UsageEvents.Event.MOVE_TO_BACKGROUND:  // = ACTIVITY_PAUSED
                case ACTIVITY_STOPPED:
                    state.activityPaused(event.getPackageName(), event.getClassName());
                    break;
                case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                case UsageEvents.Event.DEVICE_SHUTDOWN:
                case UsageEvents.Event.DEVICE_STARTUP:
                    state.clear();
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Only Ingress or this app (its settings) open, or unknown: show the overlay. Another app open next to Ingress
     * (pop-up window, split screen) or instead of it: hide. See ForegroundState.
     */
    boolean overlayWanted() {
        update();
        return state.overlayWanted();
    }
}
