package org.openbanners.overlay;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Decides whether a tapped bannergress.com link came from a browser (Settings → Links). Links tapped in a browser
 * stay on Bannergress; only links from other apps (Telegram, Discord, mail, ...) go to openbanners.org.
 * <p>
 * What Chrome sends when a page link is handed to this app (checked on Android 16, Chrome tab and Custom Tab):
 * {@code Activity.getReferrer()} is {@code android-app://com.android.chrome} (page without a referrer, e.g.
 * openbanners.org) or the page's URL like {@code https://example.com/} (also in EXTRA_REFERRER), and the extra
 * {@code com.android.browser.application_id} is {@code com.android.chrome}. Another app gets
 * {@code android-app://<its package>}; {@code adb shell am start} gets {@code android-app://com.android.shell}.
 * Apps like Telegram also put their own package in the application_id extra, so that extra alone doesn't prove a
 * browser: the package has to be one.
 */
final class LinkSource {
    /** android.provider.Browser.EXTRA_APPLICATION_ID: the app that opened the link (browsers and some apps set it). */
    static final String EXTRA_APPLICATION_ID = "com.android.browser.application_id";

    /** The source is a browser, but which one isn't known: use the default browser. */
    static final String UNKNOWN_BROWSER = "";

    private static final String ANDROID_APP = "android-app://";

    private LinkSource() {
    }

    /**
     * The package of the browser the link was tapped in, {@link #UNKNOWN_BROWSER} when it came from a web page but
     * the browser can't be named, or null when it came from another app or an unknown source (then it's redirected
     * to openbanners.org, as intended for links from other apps).
     *
     * @param referrer    {@code Activity.getReferrer()} as a string, or null
     * @param appIdExtra  the {@link #EXTRA_APPLICATION_ID} extra, or null
     * @param ownPackage  this app's package (never returned, that would loop)
     * @param isBrowser   whether a package is a browser (opens ordinary https web pages)
     */
    static String browserPackage(String referrer, String appIdExtra, String ownPackage, Predicate<String> isBrowser) {
        String referrerPackage = null;
        boolean fromWebPage = false;
        if (referrer != null) {
            String r = referrer.trim();
            String lower = r.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                fromWebPage = true; // the page the link was on: only browsers send that
            } else if (lower.startsWith(ANDROID_APP)) {
                String rest = r.substring(ANDROID_APP.length());
                int slash = rest.indexOf('/');
                referrerPackage = slash < 0 ? rest : rest.substring(0, slash);
                // android-app://<pkg>/https/<host>/...: a web page shown in that app (e.g. a search result)
                String path = slash < 0 ? "" : rest.substring(slash + 1).toLowerCase(Locale.ROOT);
                if (path.startsWith("http/") || path.startsWith("https/")) fromWebPage = true;
            }
        }
        if (isUsableBrowser(referrerPackage, ownPackage, isBrowser)) return referrerPackage;
        if (isUsableBrowser(appIdExtra, ownPackage, isBrowser)) return appIdExtra;
        return fromWebPage ? UNKNOWN_BROWSER : null;
    }

    private static boolean isUsableBrowser(String pkg, String ownPackage, Predicate<String> isBrowser) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(ownPackage)) return false;
        try {
            return isBrowser.test(pkg);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
