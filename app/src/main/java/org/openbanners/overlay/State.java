package org.openbanners.overlay;

import android.location.Location;

import org.openbanners.overlay.api.Banner;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.MissionType;
import org.openbanners.overlay.api.POIType;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Set;

public class State {
    public final Banner banner;

    public final int currentMission;

    public final boolean error;

    public final boolean cooldown;

    public final Location currentLocation;

    public final ImmutableSet<Integer> currentMissionVisitedStepIndexes;

    public final boolean locationEnabled;

    /**
     * Manual correction for the compass, in degrees (positive turns the map left / counterclockwise), set from the
     * notification when the phone's heading is off. Starts at 0 for every banner.
     */
    public final int headingOffset;

    /**
     * True after the overlay was (re)opened on a banner without "Start with Overlay": the player may be in the middle
     * of it, so the first portal of a sequential mission that comes into range may be any of them (the earliest one in
     * range wins, and everything before it counts as done). Stays true while they click through missions they did
     * before the restart, and ends at the first portal marked visited; from then on missions are strictly in order.
     */
    public final boolean justRestarted;

    private State(Banner banner, int currentMission, boolean error, boolean cooldown, Location currentLocation, ImmutableSet<Integer> currentMissionVisitedStepIndexes, boolean locationEnabled) {
        this(banner, currentMission, error, cooldown, currentLocation, currentMissionVisitedStepIndexes, locationEnabled, 0);
    }

    private State(Banner banner, int currentMission, boolean error, boolean cooldown, Location currentLocation, ImmutableSet<Integer> currentMissionVisitedStepIndexes, boolean locationEnabled, int headingOffset) {
        this(banner, currentMission, error, cooldown, currentLocation, currentMissionVisitedStepIndexes, locationEnabled, headingOffset, false);
    }

    private State(Banner banner, int currentMission, boolean error, boolean cooldown, Location currentLocation, ImmutableSet<Integer> currentMissionVisitedStepIndexes, boolean locationEnabled, int headingOffset, boolean justRestarted) {
        this.justRestarted = justRestarted;
        this.banner = banner;
        this.currentMission = currentMission;
        this.error = error;
        this.cooldown = cooldown;
        this.currentLocation = currentLocation;
        this.currentMissionVisitedStepIndexes = currentMissionVisitedStepIndexes;
        this.locationEnabled = locationEnabled;
        this.headingOffset = headingOffset;
    }

    static State initial() {
        return new State(null, -1, false, false, null, ImmutableSet.of(), false);
    }

    static State terminal() {
        return initial();
    }

    static State error() {
        return new State(null, -1, true, false, null, ImmutableSet.of(), false);
    }

    State previousMission() {
        return new State(this.banner, this.currentMission - 1, false, false, this.currentLocation, ImmutableSet.of(), this.locationEnabled, this.headingOffset, this.justRestarted)
                .applyLocation();
    }

    State nextMission(boolean cooldown) {
        return new State(this.banner, this.currentMission + 1, false, cooldown, this.currentLocation, ImmutableSet.of(), this.locationEnabled, this.headingOffset, this.justRestarted)
                .applyLocation();
    }

    State bannerLoaded(Banner banner) {
        return bannerLoaded(banner, -1);
    }

    State bannerLoaded(Banner banner, int currentMission) {
        return bannerLoaded(banner, currentMission, true);
    }

    /** {@code fromStart}: opened with "Start with Overlay" at mission 1, so missions stay strictly in order. */
    State bannerStarted(Banner banner) {
        return bannerLoaded(banner, 0, false);
    }

    private State bannerLoaded(Banner banner, int currentMission, boolean justRestarted) {
        return new State(banner, currentMission, false, false, this.currentLocation, ImmutableSet.of(), this.locationEnabled, 0, justRestarted)
                .applyLocation();
    }

    State cooldownFinished() {
        return new State(this.banner, this.currentMission, this.error, false, this.currentLocation, this.currentMissionVisitedStepIndexes, this.locationEnabled, this.headingOffset, this.justRestarted);
    }

    State location(Location location) {
        return new State(this.banner, this.currentMission, this.error, this.cooldown, location, this.currentMissionVisitedStepIndexes, this.locationEnabled, this.headingOffset, this.justRestarted).applyLocation();
    }

    State locationEnabled() {
        return new State(this.banner, this.currentMission, this.error, this.cooldown, this.currentLocation, this.currentMissionVisitedStepIndexes, true, this.headingOffset, this.justRestarted);
    }

    /** Turns the compass correction by {@code degrees} (positive = map turns left), kept within -180..180. */
    State rotateHeading(int degrees) {
        int offset = ((this.headingOffset + degrees) % 360 + 540) % 360 - 180;
        return new State(this.banner, this.currentMission, this.error, this.cooldown, this.currentLocation, this.currentMissionVisitedStepIndexes, this.locationEnabled, offset, this.justRestarted);
    }

    private State applyLocation() {
        ImmutableSet<Integer> visited = calculateStepIndexesInRange(this.banner, this.currentMission, this.currentMissionVisitedStepIndexes, this.currentLocation);
        if (visited.isEmpty() && this.justRestarted) {
            visited = resumeAtEarliestInRange(this.banner, this.currentMission, this.currentLocation);
        }
        return new State(this.banner, this.currentMission, this.error, this.cooldown, this.currentLocation, visited, this.locationEnabled, this.headingOffset,
                this.justRestarted && visited.isEmpty());
    }

    /**
     * Right after a restart nothing of the mission is marked yet: the earliest portal of the mission that is in range
     * counts, with every portal before it, and the rest follows in order from there. Empty when none is in range.
     */
    ImmutableSet<Integer> resumeAtEarliestInRange(Banner banner, int currentMission, Location location) {
        if (banner == null || location == null || currentMission < 0 || currentMission >= banner.missions.size()) {
            return ImmutableSet.of();
        }
        Mission mission = banner.missions.get(currentMission);
        assert mission != null;
        List<MissionStep> steps = mission.steps;
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            MissionStep step = steps.get(stepIndex);
            if (step.poi != null && step.poi.type != POIType.unavailable && DistanceCalculation.isInRange(step, location)) {
                ImmutableSet.Builder<Integer> before = ImmutableSet.builder();
                for (int i = 0; i <= stepIndex; i++) {
                    before.add(i);
                }
                return calculateStepIndexesInRange(banner, currentMission, before.build(), location);
            }
        }
        return ImmutableSet.of();
    }

    ImmutableSet<Integer> calculateStepIndexesInRange(Banner banner, int currentMission, ImmutableSet<Integer> finishedSteps, Location location) {
        if (banner == null || currentMission < 0 || currentMission >= banner.missions.size()) {
            return ImmutableSet.of();
        } else if (location == null) {
            return finishedSteps;
        } else {
            Mission mission = banner.missions.get(currentMission);
            assert mission != null;
            List<MissionStep> steps = mission.steps;
            ImmutableSet.Builder<Integer> builder = ImmutableSet.builder();
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                MissionStep step = steps.get(stepIndex);
                if (finishedSteps.contains(stepIndex)
                        || step.poi == null
                        || step.poi.type == POIType.unavailable
                        || DistanceCalculation.isInRange(step, location)) {
                    builder.add(stepIndex);
                } else if (mission.type != MissionType.anyOrder) {
                    break;
                }
            }
            return builder.build();
        }
    }
    static ImmutableMap<Integer, MissionStep> getNewStepsInRange(State newState, State oldState) {
        Set<Integer> newStepsInRange;
        if (newState.currentMission != oldState.currentMission) {
            newStepsInRange = newState.currentMissionVisitedStepIndexes;
        } else {
            newStepsInRange = Sets.difference(newState.currentMissionVisitedStepIndexes, oldState.currentMissionVisitedStepIndexes);
        }
        if (oldState.justRestarted && !newState.justRestarted && newState.currentLocation != null) {
            // resumed after a restart: the portals before it count as done, but only the ones in range are news
            Mission mission = newState.banner.missions.get(newState.currentMission);
            assert mission != null;
            newStepsInRange = Sets.filter(newStepsInRange, i -> {
                MissionStep step = mission.steps.get(i);
                return step.poi != null && step.poi.type != POIType.unavailable
                        && DistanceCalculation.isInRange(step, newState.currentLocation);
            });
        }
        ImmutableMap.Builder<Integer, MissionStep> result = ImmutableMap.builder();
        for (int stepIndex : newStepsInRange) {
            Mission mission = newState.banner.missions.get(newState.currentMission);
            assert mission != null;
            MissionStep step = mission.steps.get(stepIndex);
            result.put(stepIndex, step);
        }
        return result.build();
    }

}
