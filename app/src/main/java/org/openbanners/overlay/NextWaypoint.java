package org.openbanners.overlay;

import org.openbanners.overlay.api.Banner;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.MissionType;
import org.openbanners.overlay.api.POIType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Settings → Navigation → "Navigate to next waypoint" (off by default): a Navigate button on the mission card while
 * the next waypoint is farther away than the chosen distance (default 1 km). It opens the same Google Maps directions
 * link as openbanners.org's Navigate buttons (BannerInfo.jsx "Navigate to Start Point", MissionStepMarker.jsx
 * "Navigate to portal"). Pure Java so it can be unit-tested; {@link OverlayView} shows the button.
 */
final class NextWaypoint {
    /** Settings defaults (root_preferences.xml). */
    static final boolean DEFAULT_ENABLED = false;
    static final String DEFAULT_MIN_METERS = "1000";

    /**
     * Once shown, the button stays until you're this much closer than the threshold, so GPS jitter right at the
     * threshold doesn't make it blink on and off.
     */
    static final double HYSTERESIS_METERS = 25;

    /** The waypoint to head for and how far away it is. */
    static final class Target {
        final double latitude;
        final double longitude;
        final double meters;

        Target(double latitude, double longitude, double meters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.meters = meters;
        }
    }

    private NextWaypoint() {
    }

    /**
     * The next waypoint from where you are ({@code latitude}, {@code longitude}), or null (no banner, or every
     * waypoint is done). Before the start (mission -1) it's the first waypoint of mission 1. In the current mission
     * it's the first one not visited yet; in an any-order mission, or in catch-up mode after +/- or a restart (where
     * the waypoints before you aren't marked yet), the nearest one not visited. Once the mission's waypoints are all
     * visited, it's the first waypoint of the next mission.
     */
    static Target find(Banner banner, int currentMission, Set<Integer> visitedStepIndexes, boolean catchUp,
                       double latitude, double longitude) {
        if (banner == null || banner.missions == null || banner.missions.isEmpty()) return null;
        List<Mission> missions = new ArrayList<>(banner.missions.values());
        int active = Math.max(currentMission, 0);
        if (active >= missions.size()) return null;
        Mission mission = missions.get(active);
        Set<Integer> visited = currentMission == active && visitedStepIndexes != null ? visitedStepIndexes : Collections.emptySet();
        boolean nearest = catchUp || mission.type == MissionType.anyOrder;
        Target best = null;
        List<MissionStep> steps = mission.steps == null ? Collections.emptyList() : mission.steps;
        for (int i = 0; i < steps.size(); i++) {
            MissionStep step = steps.get(i);
            if (!hasPosition(step) || visited.contains(i)) continue;
            Target target = target(step, latitude, longitude);
            if (!nearest) return target;
            if (best == null || target.meters < best.meters) best = target;
        }
        if (best != null) return best;
        if (active + 1 < missions.size()) {
            Mission next = missions.get(active + 1);
            if (next.steps != null) {
                for (MissionStep step : next.steps) {
                    if (hasPosition(step)) return target(step, latitude, longitude);
                }
            }
        }
        return null;
    }

    private static boolean hasPosition(MissionStep step) {
        return step != null && step.poi != null && step.poi.type != POIType.unavailable
                && step.poi.latitude != null && step.poi.longitude != null;
    }

    private static Target target(MissionStep step, double latitude, double longitude) {
        return new Target(step.poi.latitude, step.poi.longitude,
                DistanceCalculation.distanceMeters(latitude, longitude, step.poi.latitude, step.poi.longitude));
    }

    /**
     * Whether to offer the Navigate button for a waypoint {@code meters} away: farther than {@code minMeters}, and
     * while it's shown ({@code shown}) until you're {@link #HYSTERESIS_METERS} closer than that.
     */
    static boolean offer(double meters, double minMeters, boolean shown) {
        return meters > (shown ? minMeters - HYSTERESIS_METERS : minMeters);
    }

    /** The distance setting in meters; the default (1 km) if it's missing or not a positive number. */
    static double minMeters(String setting) {
        try {
            double meters = Double.parseDouble(setting);
            if (meters > 0 && !Double.isInfinite(meters)) return meters;
        } catch (NumberFormatException | NullPointerException ignored) {
            // fall through to the default
        }
        return Double.parseDouble(DEFAULT_MIN_METERS);
    }

    /**
     * Google Maps directions to the waypoint, exactly as openbanners.org builds it:
     * {@code https://www.google.com/maps/dir/?api=1&destination=<lat>,<lng>} (no origin: Maps starts from your
     * location; no travel mode: Maps picks it). Coordinates are written the way JavaScript prints numbers there
     * (52.37, not 52.370 or 5.237E1).
     */
    static String directionsUrl(double latitude, double longitude) {
        return "https://www.google.com/maps/dir/?api=1&destination=" + jsNumber(latitude) + "," + jsNumber(longitude);
    }

    private static String jsNumber(double value) {
        if (value == 0) return "0";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
