package com.openbanners.overlay;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Apps targeting Android 15+ are drawn edge-to-edge: the content starts behind the status bar and the
 * action bar. Pads the content below them (and above the navigation bar) and picks readable status bar icons.
 */
final class EdgeToEdgeInsets {
    private EdgeToEdgeInsets() {
    }

    static void apply(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        // AppCompat's action bar layout already adds the action bar height to the top inset it passes down.
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Edge-to-edge: the status bar shows the window background, so match its icons to the theme.
            boolean nightMode = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView()).setAppearanceLightStatusBars(!nightMode);
        }
    }
}
