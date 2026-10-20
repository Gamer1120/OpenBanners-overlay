package org.openbanners.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The route map is only shown after the user confirmed the safety points (see SettingsActivity). Acceptance is
 * stored with a version number: bumping {@link #CURRENT_VERSION} asks everyone to confirm again.
 */
final class RouteDisclaimer {
    static final int CURRENT_VERSION = 1;

    private RouteDisclaimer() {
    }

    static boolean isAccepted(int acceptedVersion) {
        return acceptedVersion >= CURRENT_VERSION;
    }

    /** Whether the route map may be shown: switched on and the current disclaimer accepted. */
    static boolean isEffectivelyEnabled(boolean switchedOn, int acceptedVersion) {
        return switchedOn && isAccepted(acceptedVersion);
    }

    /** Whether a stored "on" must be switched off, so the user has to turn it on again and confirm. */
    static boolean mustSwitchOff(boolean switchedOn, int acceptedVersion) {
        return switchedOn && !isAccepted(acceptedVersion);
    }

    static boolean isAccepted(Context context, SharedPreferences preferences) {
        return isAccepted(acceptedVersion(context, preferences));
    }

    static boolean isRouteStripEnabled(Context context, SharedPreferences preferences) {
        return isEffectivelyEnabled(switchedOn(context, preferences), acceptedVersion(context, preferences));
    }

    /**
     * Switches the route map off if it's on without an accepted disclaimer (upgrades from before the disclaimer,
     * a bumped version, an imported layout). Returns whether it did.
     */
    static boolean enforce(Context context, SharedPreferences preferences) {
        if (!mustSwitchOff(switchedOn(context, preferences), acceptedVersion(context, preferences))) return false;
        preferences.edit().putBoolean(context.getString(R.string.route_strip_enable), false).apply();
        return true;
    }

    static void accept(Context context, SharedPreferences preferences) {
        preferences.edit().putInt(context.getString(R.string.route_disclaimer_accepted_version), CURRENT_VERSION).apply();
    }

    private static boolean switchedOn(Context context, SharedPreferences preferences) {
        return preferences.getBoolean(context.getString(R.string.route_strip_enable), false);
    }

    private static int acceptedVersion(Context context, SharedPreferences preferences) {
        return preferences.getInt(context.getString(R.string.route_disclaimer_accepted_version), 0);
    }
}
