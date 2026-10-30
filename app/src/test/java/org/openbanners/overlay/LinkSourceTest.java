package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.Test;

public class LinkSourceTest {
    private static final String OWN = "org.openbanners.overlay";
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.vivaldi.browser", "org.mozilla.firefox", "com.sec.android.app.sbrowser"));
    private static final Predicate<String> IS_BROWSER = BROWSERS::contains;

    private static String source(String referrer, String appId) {
        return LinkSource.browserPackage(referrer, appId, OWN, IS_BROWSER);
    }

    @Test
    public void chromePageWithoutReferrer() {
        // "View on Bannergress" on openbanners.org, as Chrome sends it
        assertEquals("com.android.chrome", source("android-app://com.android.chrome", "com.android.chrome"));
        assertEquals("com.android.chrome", source("android-app://com.android.chrome", null));
    }

    @Test
    public void chromeCrossSiteLinkWithPageReferrer() {
        assertEquals("com.android.chrome", source("https://example.com/", "com.android.chrome"));
        assertEquals("com.android.chrome", source("http://localhost:8765/", "com.android.chrome"));
    }

    @Test
    public void otherBrowsers() {
        assertEquals("com.vivaldi.browser", source("android-app://com.vivaldi.browser", null));
        assertEquals("org.mozilla.firefox", source("android-app://org.mozilla.firefox", null));
        assertEquals("com.sec.android.app.sbrowser", source(null, "com.sec.android.app.sbrowser"));
    }

    @Test
    public void webPageReferrerFromUnknownBrowser() {
        assertEquals(LinkSource.UNKNOWN_BROWSER, source("https://www.google.com/", null));
        assertEquals(LinkSource.UNKNOWN_BROWSER, source("HTTPS://www.google.com/", "org.telegram.messenger"));
        // a web page inside an app that isn't a browser (e.g. a search result in the Google app)
        assertEquals(LinkSource.UNKNOWN_BROWSER,
                source("android-app://com.google.android.googlequicksearchbox/https/www.google.com", null));
    }

    @Test
    public void otherAppsAreRedirected() {
        // Telegram sets the application_id extra to its own package: not a browser
        assertNull(source("android-app://org.telegram.messenger", "org.telegram.messenger"));
        assertNull(source("android-app://com.discord", null));
        assertNull(source("android-app://com.android.shell", null)); // adb shell am start
        assertNull(source("android-app://com.google.android.gm/", null));
    }

    @Test
    public void unknownSourceIsRedirected() {
        assertNull(source(null, null));
        assertNull(source("", ""));
        assertNull(source("android-app://", null));
        assertNull(source("garbage", null));
    }

    @Test
    public void neverItself() {
        Predicate<String> everything = p -> true;
        assertNull(LinkSource.browserPackage("android-app://" + OWN, OWN, OWN, everything));
        assertEquals(LinkSource.UNKNOWN_BROWSER, LinkSource.browserPackage("https://x.org/", OWN, OWN, everything));
    }

    @Test
    public void brokenBrowserCheckCountsAsNoBrowser() {
        Predicate<String> broken = p -> {
            throw new SecurityException("no");
        };
        assertNull(LinkSource.browserPackage("android-app://com.android.chrome", null, OWN, broken));
    }
}
