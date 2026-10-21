package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
