package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

/** Settings → Navigation → "Navigate to next waypoint": which waypoint, when the button shows, and the Maps link. */
public class NextWaypointTest {
    /** You stand at 0,0; a waypoint at latitude d/1000 is about 111 m × d north of you. */
    private static final double HERE_LAT = 0, HERE_LNG = 0;

    private static MissionStep step(double latitude, POIType type) {
        MissionStep step = new MissionStep();
        step.poi = new POI();
        step.poi.type = type;
        step.poi.latitude = latitude;
        step.poi.longitude = 0.0;
        return step;
    }

    /** One mission per array: the latitudes of its waypoints. */
    private static Banner banner(MissionType type, double[]... missions) {
        Banner banner = new Banner();
        banner.id = "b";
        banner.missions = new TreeMap<>();
        for (int m = 0; m < missions.length; m++) {
            Mission mission = new Mission();
            mission.id = "m" + m;
            mission.type = type;
            List<MissionStep> steps = new ArrayList<>();
            for (double latitude : missions[m]) steps.add(step(latitude, POIType.portal));
            mission.steps = steps;
            banner.missions.put(m, mission);
        }
        return banner;
    }

    private static double[] at(double... latitudes) {
        return latitudes;
    }

    private static double find(Banner banner, int mission, ImmutableSet<Integer> visited, boolean catchUp) {
        NextWaypoint.Target target = NextWaypoint.find(banner, mission, visited, catchUp, HERE_LAT, HERE_LNG);
        return target == null ? Double.NaN : target.latitude;
    }

    @Test
    public void sequentialMissionGoesToTheFirstWaypointNotVisited() {
        Banner banner = banner(MissionType.sequential, at(0.03, 0.02, 0.01), at(0.05));
        assertEquals(0.03, find(banner, 0, ImmutableSet.of(), false), 0);
        assertEquals(0.02, find(banner, 0, ImmutableSet.of(0), false), 0);
        assertEquals(0.01, find(banner, 0, ImmutableSet.of(0, 1), false), 0);
    }

    @Test
    public void anyOrderAndCatchUpGoToTheNearestWaypointNotVisited() {
        Banner anyOrder = banner(MissionType.anyOrder, at(0.03, 0.01, 0.02));
        assertEquals(0.01, find(anyOrder, 0, ImmutableSet.of(), false), 0);
        assertEquals(0.02, find(anyOrder, 0, ImmutableSet.of(1), false), 0);
        // After +/- or a restart the waypoints behind you aren't marked: the nearest one, not the mission's first.
        Banner sequential = banner(MissionType.sequential, at(0.03, 0.01, 0.02));
        assertEquals(0.01, find(sequential, 0, ImmutableSet.of(), true), 0);
    }

    @Test
    public void beforeTheStartItIsTheFirstWaypointOfMissionOne() {
        Banner banner = banner(MissionType.sequential, at(0.03, 0.02), at(0.05));
        // Visited steps belong to the current mission; before the start there are none.
        assertEquals(0.03, find(banner, -1, ImmutableSet.of(0), false), 0);
    }

    @Test
    public void aFinishedMissionLeadsToTheNextMissionsStart() {
        Banner banner = banner(MissionType.sequential, at(0.03, 0.02), at(0.05, 0.06));
        assertEquals(0.05, find(banner, 0, ImmutableSet.of(0, 1), false), 0);
        // The last mission done: nothing left to navigate to.
        assertTrue(Double.isNaN(find(banner, 1, ImmutableSet.of(0, 1), false)));
    }

    @Test
    public void waypointsWithoutAPositionAreSkipped() {
        Banner banner = banner(MissionType.sequential, at(0.03, 0.02));
        banner.missions.get(0).steps.add(0, step(0.09, POIType.unavailable));
        MissionStep noPoi = new MissionStep();
        banner.missions.get(0).steps.add(1, noPoi);
        assertEquals(0.03, find(banner, 0, ImmutableSet.of(), false), 0);
    }

    @Test
    public void noBannerNoTarget() {
        assertNull(NextWaypoint.find(null, 0, ImmutableSet.of(), false, 0, 0));
        Banner empty = new Banner();
        empty.missions = new TreeMap<>();
        assertNull(NextWaypoint.find(empty, -1, ImmutableSet.of(), false, 0, 0));
    }

    @Test
    public void distanceIsFromYourPosition() {
        Banner banner = banner(MissionType.sequential, at(0.01));
        NextWaypoint.Target target = NextWaypoint.find(banner, 0, ImmutableSet.of(), false, HERE_LAT, HERE_LNG);
        assertEquals(1_112, target.meters, 1); // 0.01° of latitude
    }

    @Test
    public void buttonOnlyBeyondTheThreshold() {
        assertFalse(NextWaypoint.offer(900, 1_000, false));
        assertFalse(NextWaypoint.offer(1_000, 1_000, false));
        assertTrue(NextWaypoint.offer(1_001, 1_000, false));
        // Once shown it stays through GPS jitter right at the threshold, then goes.
        assertTrue(NextWaypoint.offer(990, 1_000, true));
        assertFalse(NextWaypoint.offer(1_000 - NextWaypoint.HYSTERESIS_METERS, 1_000, true));
        assertTrue(NextWaypoint.offer(5_001, 5_000, false));
        assertFalse(NextWaypoint.offer(4_000, 5_000, false));
    }

    @Test
    public void thresholdSettingDefaultsToOneKilometer() {
        assertFalse(NextWaypoint.DEFAULT_ENABLED);
        assertEquals(1_000, NextWaypoint.minMeters(NextWaypoint.DEFAULT_MIN_METERS), 0);
        assertEquals(2_000, NextWaypoint.minMeters("2000"), 0);
        assertEquals(1_000, NextWaypoint.minMeters(null), 0);
        assertEquals(1_000, NextWaypoint.minMeters("far"), 0);
        assertEquals(1_000, NextWaypoint.minMeters("-5"), 0);
    }

    /** The same link as openbanners.org (BannerInfo.jsx, MissionStepMarker.jsx), numbers printed as JavaScript does. */
    @Test
    public void directionsUrlMatchesTheWebsite() {
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=52.370216,4.895168",
                NextWaypoint.directionsUrl(52.370216, 4.895168));
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=52,-4.5",
                NextWaypoint.directionsUrl(52.0, -4.5));
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=0.00001,0",
                NextWaypoint.directionsUrl(0.00001, 0.0));
    }
}
