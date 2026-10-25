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
 * The +/- buttons wrap around (screach 10-25: at 1/23 he needed 20x + to reach 20/23 instead of 4x -). Wrapping is
 * a +/- press like any other, so catch-up mode stays on. The Next button doesn't wrap. In unit tests every Location
 * is at 0,0, so "in range" means a portal placed at 0,0.
 */
public class StateWrapTest {
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
    public void minusOnTheFirstMissionGoesToTheLast() {
        State state = State.initial().bannerStarted(banner("....", "....", "....")).previousMission();
        assertEquals(2, state.currentMission);
        assertEquals(1, state.previousMission().currentMission);
    }

    @Test
    public void minusBeforeTheStartGoesToTheLast() {
        State state = State.initial().bannerLoaded(banner("....", "....", "....")).previousMission();
        assertEquals(2, state.currentMission);
    }

    @Test
    public void plusOnTheLastMissionGoesToTheFirst() {
        State state = State.initial().bannerLoaded(banner("....", "....", "...."), 2).nextMission(false);
        assertEquals(0, state.currentMission);
        assertEquals(1, state.nextMission(false).currentMission);
    }

    @Test
    public void screachsCase() {
        // 1/23 -> 20/23 in 4 presses of -
        String[] missions = new String[23];
        java.util.Arrays.fill(missions, "....");
        State state = State.initial().bannerStarted(banner(missions));
        for (int i = 0; i < 4; i++) {
            state = state.previousMission();
        }
        assertEquals(19, state.currentMission);
    }

    @Test
    public void catchUpStaysOnAfterWrappingBack() {
        // started strictly in order, then - wraps to the last mission: catch-up marks where he is
        State state = State.initial().bannerStarted(banner("....", "..x.")).location(HERE);
        assertFalse(state.justNavigated);
        state = state.previousMission();
        assertEquals(1, state.currentMission);
        assertTrue(state.justNavigated);
        assertEquals(ImmutableSet.of(0, 1, 2), state.currentMissionVisitedStepIndexes);
    }

    @Test
    public void catchUpStaysOnAfterWrappingForward() {
        State state = State.initial().bannerStarted(banner("..x.", "....")).nextMission(true).cooldownFinished();
        assertFalse(state.justNavigated);
        state = state.nextMission(false).location(HERE);
        assertEquals(0, state.currentMission);
        assertTrue(state.justNavigated);
        assertEquals(ImmutableSet.of(0, 1, 2), state.currentMissionVisitedStepIndexes);
    }

    @Test
    public void singleMissionBannerStaysPut() {
        State state = State.initial().bannerStarted(banner("..x."));
        State minus = state.previousMission();
        assertEquals(0, minus.currentMission);
        assertTrue(minus.justNavigated);
        State plus = state.nextMission(false);
        assertEquals(0, plus.currentMission);
        assertTrue(plus.justNavigated);
        assertEquals(ImmutableSet.of(0, 1, 2), plus.location(HERE).currentMissionVisitedStepIndexes);
    }

    @Test
    public void theNextButtonDoesNotWrap() {
        // on the last mission Next is Close: the state moves past the end, never back to mission 1
        State state = State.initial().bannerLoaded(banner("....", "...."), 1).nextMission(true);
        assertEquals(2, state.currentMission);
        assertFalse(state.justNavigated);
    }
}
