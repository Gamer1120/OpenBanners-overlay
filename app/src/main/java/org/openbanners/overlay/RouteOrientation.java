package org.openbanners.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;

/**
 * The route map orientation setting: "north" (north up) or "compass" (the map turns with the phone).
 * Follow compass is the default since versionCode 50. Phones without a rotation-vector sensor always get north up.
 */
final class RouteOrientation {
    static final String NORTH = "north";
    static final String COMPASS = "compass";
    /** Must match app:defaultValue of the orientation setting in root_preferences.xml. */
    static final String DEFAULT = COMPASS;

    private RouteOrientation() {
    }

    /** Whether the map follows the compass for a stored value (null when never stored) and sensor availability. */
    static boolean followsCompass(String storedValue, boolean hasCompassSensor) {
        String value = storedValue == null ? DEFAULT : storedValue;
        return hasCompassSensor && COMPASS.equals(value);
    }

    static boolean followsCompass(Context context, SharedPreferences preferences) {
        return followsCompass(preferences.getString(context.getString(R.string.route_orientation), null), hasCompassSensor(context));
    }

    /** The sensor the route map reads its heading from. */
    static Sensor compassSensor(Context context) {
        SensorManager sensorManager = context.getSystemService(SensorManager.class);
        return sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    static boolean hasCompassSensor(Context context) {
        return compassSensor(context) != null;
    }
}
