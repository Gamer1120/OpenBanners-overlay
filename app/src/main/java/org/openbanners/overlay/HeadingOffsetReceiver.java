package org.openbanners.overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;

/** The notification's ↺ / ↻ buttons: turn the compass correction by the step chosen in the settings. */
public class HeadingOffsetReceiver extends BroadcastReceiver {
    static final String EXTRA_DIRECTION = "direction";
    static final int DEFAULT_STEP_DEGREES = 15;

    @Override
    public void onReceive(Context context, Intent intent) {
        int direction = intent.getIntExtra(EXTRA_DIRECTION, 0);
        if (direction == 0) return;
        int step = stepDegrees(context);
        StateManager.updateState(state -> state.rotateHeading(direction * step));
    }

    static int stepDegrees(Context context) {
        String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.route_compass_step), String.valueOf(DEFAULT_STEP_DEGREES));
        try {
            int step = Integer.parseInt(value);
            return step > 0 && step <= 180 ? step : DEFAULT_STEP_DEGREES;
        } catch (NumberFormatException e) {
            return DEFAULT_STEP_DEGREES;
        }
    }
}
