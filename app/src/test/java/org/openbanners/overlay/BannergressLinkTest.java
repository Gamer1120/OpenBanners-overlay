package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BannergressLinkTest {
    private static final String MUENSTER = "https://openbanners.org/banner/st-paulus-dom-m%C3%BCnster-4d07";

    @Test
    public void plainId() {
        assertEquals("https://openbanners.org/banner/level-8-week-2026-4458",
                BannergressLink.toOpenBanners("https://bannergress.com/banner/level-8-week-2026-4458"));
    }

    @Test
    public void accentedIdRawAndEncoded() {
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://bannergress.com/banner/st-paulus-dom-münster-4d07"));
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://bannergress.com/banner/st-paulus-dom-m%C3%BCnster-4d07"));
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://bannergress.com/banner/st-paulus-dom-m%c3%bcnster-4d07"));
        assertEquals("https://openbanners.org/banner/caf%C3%A9-stra%C3%9Fe-1a2b",
                BannergressLink.toOpenBanners("https://bannergress.com/banner/café-straße-1a2b"));
    }

    @Test
    public void wwwHostTrailingSlashQueryAndFragment() {
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://www.bannergress.com/banner/st-paulus-dom-münster-4d07"));
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://bannergress.com/banner/st-paulus-dom-münster-4d07/"));
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://bannergress.com/banner/st-paulus-dom-münster-4d07?utm_source=x&a=b"));
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://bannergress.com/banner/st-paulus-dom-münster-4d07#map"));
        assertEquals(MUENSTER, BannergressLink.toOpenBanners("https://BannerGress.com/banner/st-paulus-dom-m%C3%BCnster-4d07/?x=1#y"));
    }

    @Test
    public void httpBecomesHttps() {
        assertEquals("https://openbanners.org/banner/abc-1234", BannergressLink.toOpenBanners("http://bannergress.com/banner/abc-1234"));
    }

    @Test
    public void nonBannerPagesAreNull() {
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/browse"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/user/x"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/a/b"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner//"));
    }

    @Test
    public void otherHostsAndSchemesAreNull() {
        assertNull(BannergressLink.toOpenBanners(null));
        assertNull(BannergressLink.toOpenBanners(""));
        assertNull(BannergressLink.toOpenBanners("https://openbanners.org/banner/abc"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com.evil.example/banner/abc"));
        assertNull(BannergressLink.toOpenBanners("https://evilbannergress.com/banner/abc"));
        assertNull(BannergressLink.toOpenBanners("https://api.bannergress.com/bnrs/abc"));
        assertNull(BannergressLink.toOpenBanners("ftp://bannergress.com/banner/abc"));
        assertNull(BannergressLink.toOpenBanners("javascript:alert(1)"));
        assertNull(BannergressLink.toOpenBanners("https://user@bannergress.com/banner/abc"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com:8443/banner/abc"));
    }

    @Test
    public void brokenIdsAreNull() {
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/a%2Fb"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/a%20b"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/a%FFb"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/a%ZZb"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/a b"));
        assertNull(BannergressLink.toOpenBanners("https://bannergress.com/banner/a%3Fb"));
    }
}
