package org.openbanners.overlay.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

public class BannerApiTest {
    @Test
    public void bannerUrlEncodesTheIdOnce() {
        assertEquals("https://api.bannergress.com/bnrs/st-paulus-dom-m%C3%BCnster-4d07",
                BannerApi.bannerUrl("st-paulus-dom-münster-4d07").toString());
        assertEquals("https://api.bannergress.com/bnrs/level-8-week-2026-4458",
                BannerApi.bannerUrl("level-8-week-2026-4458").toString());
    }

    /** Talks to the real API; run with OB_LIVE_TEST=1 ./gradlew testDebugUnitTest. */
    @Test
    public void liveBannerWithUmlautLoads() throws Exception {
        assumeTrue("1".equals(System.getenv("OB_LIVE_TEST")));
        Banner banner = BannerApi.getBanner("st-paulus-dom-münster-4d07");
        assertEquals("st-paulus-dom-münster-4d07", banner.id);
        assertFalse(banner.missions.isEmpty());
    }
}
