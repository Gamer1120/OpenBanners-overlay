package org.openbanners.overlay;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class MainActivity extends AppCompatActivity {
    /** Start at mission 1 and open it in Ingress once the banner has loaded. */
    static final String EXTRA_AUTOSTART = "org.openbanners.overlay.AUTOSTART";
    /** The OpenBanners site the banner was opened from (for "Close and mark as done"). */
    static final String EXTRA_ORIGIN = "org.openbanners.overlay.ORIGIN";

    private final ActivityResultLauncher<Intent> requestPermissionResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> startService(false)
    );

    private void startService(boolean requestPermission) {
        if (Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(this, OverlayService.class);
            DeepLink link = Intent.ACTION_VIEW.equals(getIntent().getAction()) ? DeepLink.parse(getIntent().getDataString()) : null;
            if (link != null) {
                intent.putExtra(Intent.EXTRA_TEXT, link.bannerUrl());
                intent.putExtra(EXTRA_AUTOSTART, link.autostart);
                intent.putExtra(EXTRA_ORIGIN, link.origin);
            } else {
                intent.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra(Intent.EXTRA_TEXT));
            }
            startService(intent);
            // "Only show over Ingress": a link shared from elsewhere (a browser) opens Ingress so the overlay shows.
            // "Start with Overlay" opens the mission in Ingress itself.
            if ((link == null || !link.autostart)
                    && PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.ingress_only), false)) {
                Ingress.createLaunchIngressIntent(this).ifPresent(pending -> {
                    try {
                        pending.send();
                    } catch (Exception ignored) {
                        // Ingress isn't installed or can't start: the overlay just stays visible
                    }
                });
            }
            finish();
        } else if (requestPermission) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            requestPermissionResultLauncher.launch(intent);
        } else {
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Upgrades from before the safety confirmation: the route map has to be switched on (and confirmed) again.
        RouteDisclaimer.enforce(this, PreferenceManager.getDefaultSharedPreferences(this));

        String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (LayoutCodec.looksLikeLayout(text)) {
            // A shared layout, not a banner: offer to import it.
            Intent settings = new Intent(this, SettingsActivity.class);
            settings.putExtra(SettingsActivity.EXTRA_IMPORT_LAYOUT, text);
            startActivity(settings);
            finish();
            return;
        }
        startService(true);
    }
}
