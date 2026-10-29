package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeepLinkTest {
    @Test
    public void startLinkAutostarts() {
        DeepLink link = DeepLink.parse("openbanners-overlay://banner/level-8-week-2026-4458?start=1");
        assertEquals("level-8-week-2026-4458", link.bannerId);
        assertTrue(link.autostart);
        assertEquals("https://openbanners.org/banner/level-8-week-2026-4458", link.bannerUrl());
    }

    @Test
    public void withoutStartJustLoads() {
        DeepLink link = DeepLink.parse("openbanners-overlay://banner/winter-enschede-db51/");
        assertEquals("winter-enschede-db51", link.bannerId);
        assertFalse(link.autostart);
        assertFalse(DeepLink.parse("openbanners-overlay://banner/x?start=0").autostart);
    }

    @Test
    public void otherLinksAreIgnored() {
        assertNull(DeepLink.parse(null));
        assertNull(DeepLink.parse("https://openbanners.org/banner/x?start=1"));
        assertNull(DeepLink.parse("openbanners-overlay://mission/x"));
        assertNull(DeepLink.parse("openbanners-overlay://banner/"));
        assertNull(DeepLink.parse("openbanners-overlay://banner/a/b"));
    }

    @Test
    public void originComesBackOnlyForOpenBannersSites() {
        DeepLink test = DeepLink.parse("openbanners-overlay://banner/x?start=1&origin=https%3A%2F%2Ftest.openbanners.org");
        assertTrue(test.autostart);
        assertEquals("https://test.openbanners.org", test.origin);
        assertEquals("https://openbanners.org", DeepLink.parse("openbanners-overlay://banner/x?origin=https%3A%2F%2Fopenbanners.org").origin);
        assertEquals("https://openbanners.org", DeepLink.parse("openbanners-overlay://banner/x?start=1").origin);
        assertEquals("https://openbanners.org", DeepLink.parse("openbanners-overlay://banner/x?origin=https%3A%2F%2Fevil.example").origin);
        assertEquals("https://openbanners.org", DeepLink.parse("openbanners-overlay://banner/x?origin=http%3A%2F%2Ftest.openbanners.org").origin);
        assertEquals("https://openbanners.org", DeepLink.parse("openbanners-overlay://banner/x?origin=https%3A%2F%2Ftest.openbanners.org.evil.example").origin);
        assertEquals("https://openbanners.org", DeepLink.parse("openbanners-overlay://banner/x?origin=https%3A%2F%2Ftest.openbanners.org%2Fbanner%2Fy").origin);
    }

    @Test
    public void safeOriginNormalises() {
        assertEquals("https://test.openbanners.org", DeepLink.safeOrigin("https://TEST.openbanners.org/"));
        assertEquals("https://openbanners.org", DeepLink.safeOrigin(null));
        assertEquals("https://openbanners.org", DeepLink.safeOrigin(""));
        assertEquals("https://openbanners.org", DeepLink.safeOrigin("javascript:alert(1)"));
    }

    @Test
    public void markDoneUrlOpensTheBannerPage() {
        assertEquals("https://test.openbanners.org/banner/level-8-week-2026-4458?markDone=1",
                DeepLink.markDoneUrl("https://test.openbanners.org", "level-8-week-2026-4458"));
        assertEquals("https://openbanners.org/banner/a%20b%2Fc?markDone=1", DeepLink.markDoneUrl("https://evil.example", "a b/c"));
        assertEquals("https://openbanners.org/banner/x?markDone=1", DeepLink.markDoneUrl(null, "x"));
    }

    @Test
    public void bannerIdsWithAccentsFromTheSite() {
        // The exact link openbanners.org sends (Chrome turns the intent:// URI into this data URI).
        String intentUri = "intent://banner/st-paulus-dom-m%C3%BCnster-4d07?start=1&origin=https%3A%2F%2Fopenbanners.org"
                + "#Intent;scheme=openbanners-overlay;package=org.openbanners.overlay;end";
        String data = "openbanners-overlay" + intentUri.substring("intent".length(), intentUri.indexOf('#'));
        DeepLink link = DeepLink.parse(data);
        assertNotNull(link);
        assertEquals("st-paulus-dom-münster-4d07", link.bannerId);
        assertTrue(link.autostart);
        assertEquals("https://openbanners.org", link.origin);
        assertEquals("https://openbanners.org/banner/st-paulus-dom-m%C3%BCnster-4d07", link.bannerUrl());

        assertEquals("café-de-flore-1a2b", DeepLink.parse("openbanners-overlay://banner/caf%C3%A9-de-flore-1a2b?start=1").bannerId);
        assertEquals("große-straße-9f00", DeepLink.parse("openbanners-overlay://banner/gro%C3%9Fe-stra%C3%9Fe-9f00").bannerId);
        // Unencoded letters work too.
        assertEquals("français-ç-0001", DeepLink.parse("openbanners-overlay://banner/français-ç-0001").bannerId);
    }

    @Test
    public void badBannerIdsAreRefused() {
        assertNull(DeepLink.parse("openbanners-overlay://banner/a%2Fb"));
        assertNull(DeepLink.parse("openbanners-overlay://banner/a%20b"));
        assertNull(DeepLink.parse("openbanners-overlay://banner/a%0Ab"));
        assertNull(DeepLink.parse("openbanners-overlay://banner/a%E2%80%8Bb"));
        assertNull(DeepLink.parse("openbanners-overlay://banner//"));
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i <= DeepLink.MAX_BANNER_ID_LENGTH; i++) longId.append('a');
        assertNull(DeepLink.parse("openbanners-overlay://banner/" + longId));
        assertNotNull(DeepLink.parse("openbanners-overlay://banner/" + longId.substring(1)));
    }

    @Test
    public void planLinks() {
        DeepLink link = DeepLink.parse("openbanners-overlay://plan/AbCdEfGhIjKlMnOpQrStUv?start=1&origin=https%3A%2F%2Ftest.openbanners.org");
        assertNotNull(link);
        assertTrue(link.plan);
        assertTrue(link.autostart);
        assertEquals("https://test.openbanners.org/planner/AbCdEfGhIjKlMnOpQrStUv", link.bannerUrl());
        // Someone else's site falls back to openbanners.org; bad ids are refused.
        assertEquals("https://openbanners.org/planner/AbCdEfGhIjKlMnOpQrStUv",
                DeepLink.parse("openbanners-overlay://plan/AbCdEfGhIjKlMnOpQrStUv?origin=https%3A%2F%2Fevil.example").bannerUrl());
        assertNull(DeepLink.parse("openbanners-overlay://plan/short"));
        assertFalse(DeepLink.parse("openbanners-overlay://banner/abc-1234").plan);
    }
}
