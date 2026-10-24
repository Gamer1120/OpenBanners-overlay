package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.openbanners.overlay.api.Banner;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.MissionType;
import org.openbanners.overlay.api.POI;
import org.openbanners.overlay.api.POIType;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Restarting the overlay in the middle of a sequential mission (feedback 2026-10-24): the earliest portal in range
 * counts with everything before it, then strictly in order. In unit tests every Location is at 0,0, so "in range"
 * means a portal placed at 0,0.
 */
public class StateRestartTest {
    private static final Location HERE = new Location("test");

    /** One sequential mission per argument; each string marks its portals: 'x' at 0,0 (in range), '.' far away. */
    private static Banner banner(String... missions) {
        Banner banner = new Banner();
        banner.id = "b";
        banner.title = "b";
        banner.missions = new TreeMap<>();
        for (int m = 0; m < missions.length; m++) {
            Mission mission = new Mission();
            mission.id = "m" + m;
            mission.type = MissionType.sequential;
            List<MissionStep> steps = new ArrayList<>();
            for (char c : missions[m].toCharArray()) {
                MissionStep step = new MissionStep();
                step.poi = new POI();
                step.poi.type = POIType.portal;
                step.poi.latitude = c == 'x' ? 0.0 : 1.0;
                step.poi.longitude = c == 'x' ? 0.0 : 1.0;
                step.poi.title = String.valueOf(c);
                steps.add(step);
            }
            mission.steps = steps;
            banner.missions.put(m, mission);
        }
        return banner;
    }

    @Test
    public void resumingMidMissionMarksTheEarliestPortalInRangeAndEverythingBefore() {
        State state = State.initial().bannerLoaded(banner("..x.x."), 0).location(HERE);
        assertEquals(ImmutableSet.of(0, 1, 2), state.currentMissionVisitedStepIndexes);
        assertFalse(state.justRestarted);
    }

    @Test
    public void notificationsOnlyForThePortalYouAreAt() {
        State before = State.initial().bannerLoaded(banner("..x..."), 0);
        State after = before.location(HERE);
        assertEquals(ImmutableSet.of(2), State.getNewStepsInRange(after, before).keySet());
    }

    @Test
    public void clickingThroughEarlierMissionsKeepsTheRestart() {
        State state = State.initial().bannerLoaded(banner("....", "....", "..x."));
        state = state.nextMission(false).nextMission(false);
        assertTrue(state.justRestarted);
        state = state.nextMission(false).location(HERE);
        assertEquals(ImmutableSet.of(0, 1, 2), state.currentMissionVisitedStepIndexes);
        assertFalse(state.justRestarted);
    }

    @Test
    public void afterTheFirstPortalMissionsAreStrictlyInOrderAgain() {
        State state = State.initial().bannerLoaded(banner("x...", "..x."), 0).location(HERE);
        assertEquals(ImmutableSet.of(0), state.currentMissionVisitedStepIndexes);
        // next mission: walking past portal 3 on the way to portal 1 marks nothing
        assertEquals(ImmutableSet.of(), state.nextMission(false).currentMissionVisitedStepIndexes);
    }

    @Test
    public void startWithOverlayIsStrictlyInOrderFromTheStart() {
        State state = State.initial().bannerStarted(banner("..x.")).location(HERE);
        assertEquals(ImmutableSet.of(), state.currentMissionVisitedStepIndexes);
        assertFalse(state.justRestarted);
    }
}
