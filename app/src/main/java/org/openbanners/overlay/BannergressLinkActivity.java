package org.openbanners.overlay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import java.util.Map;

/**
 * Opens bannergress.com banner links from other apps on openbanners.org (Settings → Links, off by default); links
 * tapped in a browser stay on bannergress.com. No UI: it hands the link to a browser and finishes. The component is disabled in the manifest and only enabled while the setting is on, so
 * nothing changes for people who don't turn it on. Doesn't start the overlay and doesn't use the network.
 */
public class BannergressLinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String link = getIntent() == null ? null : getIntent().getDataString();
            String browser = sourceBrowser();
            if (browser != null) {
                // Tapped in a browser (e.g. "View on Bannergress" on openbanners.org, or browsing Bannergress):
                // leave it alone and open the original link in that browser.
                if (link != null) openInBrowser(this, Uri.parse(link), browser);
            } else {
                String target = BannergressLink.toOpenBanners(link);
                if (target == null) target = link; // not a banner page: open the original link, just not in this app
                if (target != null) openInBrowser(this, Uri.parse(target));
            }
        } catch (RuntimeException e) {
            // Never crash on a link someone tapped.
            Toast.makeText(getApplicationContext(), R.string.links_no_browser, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /**
     * The browser the link was tapped in (see {@link LinkSource}): its package, {@link LinkSource#UNKNOWN_BROWSER},
     * or null when the link came from another app.
     */
    private String sourceBrowser() {
        Intent intent = getIntent();
        String appId = null;
        try {
            appId = intent == null ? null : intent.getStringExtra(LinkSource.EXTRA_APPLICATION_ID);
        } catch (RuntimeException ignored) {
            // bad extras: treat as absent
        }
        Uri referrer = null;
        try {
            referrer = getReferrer();
        } catch (RuntimeException ignored) {
            // bad extras: treat as absent
        }
        return LinkSource.browserPackage(referrer == null ? null : referrer.toString(), appId, getPackageName(),
                pkg -> isBrowser(this, pkg));
    }

    /** Whether {@code pkg} opens ordinary https web pages (a browser). */
    static boolean isBrowser(Context context, String pkg) {
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(pkg);
        return !context.getPackageManager().queryIntentActivities(probe, 0).isEmpty();
    }

    /** Opens {@code uri} in {@code browser} if it can, else like {@link #openInBrowser(Context, Uri)}. */
    static void openInBrowser(Context context, Uri uri, String browser) {
        if (browser != null && !browser.isEmpty() && !browser.equals(context.getPackageName())) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setPackage(browser));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // fall through to the default browser
            }
        }
        openInBrowser(context, uri);
    }

    /**
     * Opens {@code uri} in a browser and never in this app (which would loop): the default browser if there is one,
     * else Android's pick among the browsers (apps with the APP_BROWSER category, which this app doesn't have).
     */
    static void openInBrowser(Context context, Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String browser = defaultBrowser(context);
        if (browser != null) {
            try {
                context.startActivity(new Intent(view).setPackage(browser));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // fall through to any browser
            }
        }
        try {
            Intent any = new Intent(view);
            any.setSelector(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER));
            context.startActivity(any);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context.getApplicationContext(), R.string.links_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    /** Package of the default browser (the app that opens an ordinary web page), or null if none is set. */
    private static String defaultBrowser(Context context) {
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            ResolveInfo info = context.getPackageManager().resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) return null;
            String pkg = info.activityInfo.packageName;
            // "android" is the chooser (no default yet); never ourselves.
            if (pkg == null || pkg.equals("android") || pkg.equals(context.getPackageName())) return null;
            return pkg;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, BannergressLinkActivity.class);
    }

    /** Whether the link handler is switched on (it's disabled in the manifest). */
    static boolean isEnabled(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(component(context))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static void setEnabled(Context context, boolean enabled) {
        if (isEnabled(context) == enabled) return;
        context.getPackageManager().setComponentEnabledSetting(component(context),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
    }

    /**
     * Android 12+: whether the user allowed this app to open bannergress.com links (Settings → Apps → Open by
     * default). Always true before Android 12, where Android asks on the first tap instead.
     */
    static boolean isLinkHandlingApproved(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        try {
            DomainVerificationManager manager = context.getSystemService(DomainVerificationManager.class);
            if (manager == null) return false;
            DomainVerificationUserState state = manager.getDomainVerificationUserState(context.getPackageName());
            if (state == null || !state.isLinkHandlingAllowed()) return false;
            Map<String, Integer> hosts = state.getHostToStateMap();
            Integer host = hosts.get("bannergress.com");
            return host != null && (host == DomainVerificationUserState.DOMAIN_STATE_SELECTED
                    || host == DomainVerificationUserState.DOMAIN_STATE_VERIFIED);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** Android 12+ "Open by default" screen for this app, or its app info screen if that one doesn't open. */
    static void openLinkSettings(Context context) {
        Uri pkg = Uri.parse("package:" + context.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                context.startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // fall back to app info
            }
        }
        try {
            context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.links_settings_missing, Toast.LENGTH_LONG).show();
        }
    }
}
