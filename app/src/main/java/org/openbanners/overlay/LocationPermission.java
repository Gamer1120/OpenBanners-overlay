package org.openbanners.overlay;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

final class LocationPermission {
    private LocationPermission() {
    }

    /** Precise or approximate location: either is enough for the location foreground service and the fused provider. */
    static boolean granted(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
