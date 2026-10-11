package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.openbanners.overlay.api.Banner;

import java.util.TreeMap;

public class StateHeadingOffsetTest {
    private static Banner banner(String id) {
        Banner banner = new Banner();
        banner.id = id;
        banner.title = id;
        banner.missions = new TreeMap<>();
        return banner;
    }

    @Test
    public void rotatesLeftAndRight() {
        State state = State.initial().bannerLoaded(banner("a"));
        assertEquals(15, state.rotateHeading(15).headingOffset);
        assertEquals(-15, state.rotateHeading(-15).headingOffset);
        assertEquals(0, state.rotateHeading(15).rotateHeading(-15).headingOffset);
    }

    @Test
    public void wrapsWithinHalfTurn() {
        State state = State.initial().bannerLoaded(banner("a")).rotateHeading(170).rotateHeading(20);
        assertEquals(-170, state.headingOffset);
        assertEquals(170, state.rotateHeading(-20).headingOffset);
    }

    @Test
    public void survivesMissionChangesAndLocation() {
        State state = State.initial().bannerLoaded(banner("a")).rotateHeading(30);
        assertEquals(30, state.nextMission(false).headingOffset);
        assertEquals(30, state.nextMission(true).cooldownFinished().headingOffset);
        assertEquals(30, state.location(null).headingOffset);
        assertEquals(30, state.locationEnabled().headingOffset);
    }

    @Test
    public void resetsForNewBanner() {
        State state = State.initial().bannerLoaded(banner("a")).rotateHeading(45);
        assertEquals(0, state.bannerLoaded(banner("b")).headingOffset);
        assertEquals(0, state.bannerLoaded(banner("b"), 0).headingOffset);
    }
}
