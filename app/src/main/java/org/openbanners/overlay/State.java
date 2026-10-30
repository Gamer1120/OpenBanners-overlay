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
     * Catch-up mode: true after the overlay was (re)opened on a banner without "Start with Overlay", and after every
     * +/- press (screach 10-24: "just navigated" — that's what you do after a restart, or to reset the zoom). The
     * player may be in the middle of the mission, so the earliest portal of it in range counts with everything before
     * it. It survives clicking on through missions (even if one gets anchored on the way) and ends once he walks on
     * from the anchored portal to the next one. "Start with Overlay" and the Next button stay strictly in order.
     */
    public final boolean justNavigated;

    private State(Banner banner, int currentMission, boolean error, boolean cooldown, Location currentLocation, ImmutableSet<Integer> currentMissionVisitedStepIndexes, boolean locationEnabled) {
        this(banner, currentMission, error, cooldown, currentLocation, currentMissionVisitedStepIndexes, locationEnabled, 0);
    }

    private State(Banner banner, int currentMission, boolean error, boolean cooldown, Location currentLocation, ImmutableSet<Integer> currentMissionVisitedStepIndexes, boolean locationEnabled, int headingOffset) {
        this(banner, currentMission, error, cooldown, currentLocation, currentMissionVisitedStepIndexes, locationEnabled, headingOffset, false);
    }

    private State(Banner banner, int currentMission, boolean error, boolean cooldown, Location currentLocation, ImmutableSet<Integer> currentMissionVisitedStepIndexes, boolean locationEnabled, int headingOffset, boolean justNavigated) {
        this.justNavigated = justNavigated;
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

    /**
     * The - button: catch-up mode on. Wraps around: - on the first mission (or before the start) goes to the last
     * (screach 10-25: at 1/23 he needed 20x + to reach 20/23 instead of 4x -).
     */
    State previousMission() {
        int target = this.currentMission - 1;
        if (target < 0 && this.banner != null && !this.banner.missions.isEmpty()) {
            target = this.banner.missions.size() - 1;
        }
        return new State(this.banner, target, false, false, this.currentLocation, ImmutableSet.of(), this.locationEnabled, this.headingOffset, true)
                .applyLocation();
    }

    /**
     * {@code cooldown}: the Next button (strictly in order from portal 1, no wrap-around: on the last mission it's
     * Close); without it the + button (catch-up mode), which wraps from the last mission to the first.
     */
    State nextMission(boolean cooldown) {
        int target = this.currentMission + 1;
        if (!cooldown && this.banner != null && target >= this.banner.missions.size()) {
            target = 0;
        }
        return new State(this.banner, target, false, cooldown, this.currentLocation, ImmutableSet.of(), this.locationEnabled, this.headingOffset, !cooldown)
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

    private State bannerLoaded(Banner banner, int currentMission, boolean justNavigated) {
        return new State(banner, currentMission, false, false, this.currentLocation, ImmutableSet.of(), this.locationEnabled, 0, justNavigated)
                .applyLocation();
    }

    State cooldownFinished() {
        return new State(this.banner, this.currentMission, this.error, false, this.currentLocation, this.currentMissionVisitedStepIndexes, this.locationEnabled, this.headingOffset, this.justNavigated);
    }

    State location(Location location) {
        return new State(this.banner, this.currentMission, this.error, this.cooldown, location, this.currentMissionVisitedStepIndexes, this.locationEnabled, this.headingOffset, this.justNavigated).applyLocation();
    }

    State locationEnabled() {
        return new State(this.banner, this.currentMission, this.error, this.cooldown, this.currentLocation, this.currentMissionVisitedStepIndexes, true, this.headingOffset, this.justNavigated);
    }

    /** Turns the compass correction by {@code degrees} (positive = map turns left), kept within -180..180. */
    State rotateHeading(int degrees) {
        int offset = ((this.headingOffset + degrees) % 360 + 540) % 360 - 180;
        return new State(this.banner, this.currentMission, this.error, this.cooldown, this.currentLocation, this.currentMissionVisitedStepIndexes, this.locationEnabled, offset, this.justNavigated);
    }

    private State applyLocation() {
        ImmutableSet<Integer> visited = calculateStepIndexesInRange(this.banner, this.currentMission, this.currentMissionVisitedStepIndexes, this.currentLocation);
        boolean restarted = this.justNavigated;
        if (restarted) {
            if (visited.isEmpty()) {
                // anchoring keeps the restart: clicking on through the missions he did before must still work
                // (v35, screach 10-24: standing at 9f and clicking + through missions 1-8 anchored on one of them)
                visited = resumeAtEarliestInRange(this.banner, this.currentMission, this.currentLocation);
            } else if (visited.size() > this.currentMissionVisitedStepIndexes.size()
                    && !this.currentMissionVisitedStepIndexes.isEmpty()) {
                restarted = false;  // walked on to the next portal of an anchored mission: strictly in order from here
            }
        }
        return new State(this.banner, this.currentMission, this.error, this.cooldown, this.currentLocation, visited, this.locationEnabled, this.headingOffset,
                restarted);
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
        if (newState.banner == null || newState.banner.missions.get(newState.currentMission) == null) {
            // Banner loaded but not started yet (mission -1), e.g. the first location fix after granting location.
            return ImmutableMap.of();
        }
        Set<Integer> newStepsInRange;
        if (newState.currentMission != oldState.currentMission) {
            newStepsInRange = newState.currentMissionVisitedStepIndexes;
        } else {
            newStepsInRange = Sets.difference(newState.currentMissionVisitedStepIndexes, oldState.currentMissionVisitedStepIndexes);
        }
        if (oldState.justNavigated && newState.currentLocation != null) {
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
