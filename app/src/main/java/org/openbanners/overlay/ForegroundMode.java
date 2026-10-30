package org.openbanners.overlay;

/**
 * How the overlay service shows its notification. Android 14+ (API 34) refuses a "location" foreground service
 * without a location permission (SecurityException, "Starting FGS with type location ... requires permissions"),
 * and a foreground service without any type isn't allowed there either. Without the permission the service
 * therefore runs as an ordinary started service with an ordinary notification; the visible overlay window keeps
 * the app in the foreground. No other foreground service type fits this app without a new permission and Play
 * declaration.
 */
enum ForegroundMode {
    /** Before Android 10: types don't exist, startForeground uses the manifest. */
    LEGACY,
    /** startForeground with the location type. */
    LOCATION,
    /** Android 14+ without location permission: no foreground service, just post the notification. */
    NOT_FOREGROUND;

    static final int API_Q = 29;
    static final int API_UPSIDE_DOWN_CAKE = 34;

    static ForegroundMode choose(int sdkInt, boolean hasLocationPermission) {
        if (sdkInt < API_Q) return LEGACY;
        // Android 10-13 allow the location type without the permission (location just isn't delivered).
        if (hasLocationPermission || sdkInt < API_UPSIDE_DOWN_CAKE) return LOCATION;
        return NOT_FOREGROUND;
    }
}
