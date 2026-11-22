package org.openbanners.overlay;

import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.POIType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Auto-copy (Settings → Passphrases) also when you reach a passphrase portal that isn't the mission's first portal
 * (Michael 11-22): the passphrase copied when you switched to the mission may have long been replaced on the clipboard,
 * and with several passphrases a later one is the one you need there. "Reaching" is the same as ticking the step off on
 * the route map and for the "Next step in range" notification ({@link State#getNewStepsInRange}): within hack range, and
 * in ordered missions only after the steps before it. Each step is copied at most once a minute
 * ({@link #COOLDOWN_MS}). The copy on switching to a mission is unchanged. Pure Java, unit-tested.
 */
final class PassphraseReachCopy {
    static final long COOLDOWN_MS = 60_000;

    /** When each step (see {@link #key}) was last copied on reaching it, in elapsed-realtime milliseconds. */
    private final Map<String, Long> lastCopied = new HashMap<>();

    /** Index of the mission's first step on a live portal (its first portal), or -1. */
    static int firstPortalStep(Mission mission) {
        if (mission == null || mission.steps == null) return -1;
        for (int i = 0; i < mission.steps.size(); i++) {
            MissionStep step = mission.steps.get(i);
            if (step != null && step.poi != null && step.poi.type != POIType.unavailable) return i;
        }
        return -1;
    }

    /**
     * The passphrase step to copy now that the steps {@code reached} were newly reached, or null: a step of
     * {@code passphrases} that isn't on the mission's first portal (by step and by portal id; that one is copied when
     * you switch to the mission). If several were reached at once, the last one (the farthest along).
     */
    static Passphrases.Step toCopy(Mission mission, Passphrases.ForMission passphrases, Collection<Integer> reached) {
        if (mission == null || mission.steps == null || passphrases == null || !passphrases.show() || reached == null || reached.isEmpty()) return null;
        int first = firstPortalStep(mission);
        String firstPoi = first < 0 || mission.steps.get(first).poi == null ? null : mission.steps.get(first).poi.id;
        Passphrases.Step result = null;
        for (Passphrases.Step step : passphrases.steps) {
            if (step.stepIndex == first || !reached.contains(step.stepIndex)) continue;
            MissionStep missionStep = mission.steps.get(step.stepIndex);
            String poi = missionStep == null || missionStep.poi == null ? null : missionStep.poi.id;
            if (firstPoi != null && firstPoi.equals(poi)) continue;
            if (result == null || step.stepIndex > result.stepIndex) result = step;
        }
        return result;
    }

    /** Identifies a step of a mission of the banner or plan that is open. */
    static String key(Mission mission, int missionPosition, int stepIndex) {
        return Objects.toString(mission == null ? null : mission.id) + "/" + missionPosition + "/" + stepIndex;
    }

    /** True (and remembered) unless this step was copied on reaching it less than a minute ago. */
    boolean allow(String key, long nowMs) {
        Long last = lastCopied.get(key);
        if (last != null && nowMs - last < COOLDOWN_MS) return false;
        lastCopied.put(key, nowMs);
        if (lastCopied.size() > 200) lastCopied.values().removeIf(time -> nowMs - time >= COOLDOWN_MS);
        return true;
    }
}
