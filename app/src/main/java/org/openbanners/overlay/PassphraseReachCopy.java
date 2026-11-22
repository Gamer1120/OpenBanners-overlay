package org.openbanners.overlay;

import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.POIType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Auto-copy (Settings → Passphrases) also when you reach a passphrase portal that isn't the mission's first portal
 * (Michael 11-22): the passphrase copied when you switched to the mission may have long been replaced on the clipboard,
 * and with several passphrases a later one is the one you need there. "Reaching" is the same as ticking the step off on
 * the route map and for the "Next step in range" notification ({@link State#getNewStepsInRange}): within hack range, and
 * in ordered missions only after the steps before it. Each step is copied at most once a minute
 * ({@link #COOLDOWN_MS}). If several passphrase portals are reached at the same moment, only the earliest one in mission
 * order is copied; the later ones are never copied automatically (Michael 11-22: the user copies them with 🔑).
 * The copy on switching to a mission is unchanged. Pure Java, unit-tested.
 */
final class PassphraseReachCopy {
    static final long COOLDOWN_MS = 60_000;

    /** When each step (see {@link #key}) was last copied on reaching it, in elapsed-realtime milliseconds. */
    private final Map<String, Long> lastCopied = new HashMap<>();
    /** Later passphrase steps reached together with an earlier one (see {@link #key}): left to the 🔑 button. */
    private final Set<String> handled = new HashSet<>();

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
     * The passphrase step to copy now that the steps {@code reached} were newly reached, or null. Only the earliest
     * passphrase step (in mission order) among them counts (Michael 11-22): if several passphrase portals are reached
     * at the same moment, the later ones are left to the 🔑 button. If that earliest one is on the mission's first
     * portal (by step or by portal id; copied when you switch to the mission), nothing is copied.
     */
    static Passphrases.Step toCopy(Mission mission, Passphrases.ForMission passphrases, Collection<Integer> reached) {
        List<Passphrases.Step> steps = reachedSteps(mission, passphrases, reached);
        if (steps.isEmpty()) return null;
        Passphrases.Step earliest = steps.get(0);
        return onFirstPortal(mission, earliest) ? null : earliest;
    }

    /**
     * Like {@link #toCopy}, and remembers the later passphrase steps reached at the same moment as handled: they are
     * never copied automatically afterwards (not on a later update or when the card is rebuilt and sees them as new
     * again), and don't count as reached in later calls. {@code missionPosition} as in {@link #key}.
     */
    Passphrases.Step pick(Mission mission, int missionPosition, Passphrases.ForMission passphrases, Collection<Integer> reached) {
        List<Passphrases.Step> steps = reachedSteps(mission, passphrases, reached);
        steps.removeIf(step -> handled.contains(key(mission, missionPosition, step.stepIndex)));
        if (steps.isEmpty()) return null;
        for (int i = 1; i < steps.size(); i++) {
            if (handled.size() >= 500) handled.clear();
            handled.add(key(mission, missionPosition, steps.get(i).stepIndex));
        }
        Passphrases.Step earliest = steps.get(0);
        return onFirstPortal(mission, earliest) ? null : earliest;
    }

    /** The passphrase steps among {@code reached}, in mission order. */
    private static List<Passphrases.Step> reachedSteps(Mission mission, Passphrases.ForMission passphrases, Collection<Integer> reached) {
        List<Passphrases.Step> result = new ArrayList<>();
        if (mission == null || mission.steps == null || passphrases == null || !passphrases.show() || reached == null || reached.isEmpty()) return result;
        for (Passphrases.Step step : passphrases.steps) {
            if (reached.contains(step.stepIndex) && step.stepIndex >= 0 && step.stepIndex < mission.steps.size()) result.add(step);
        }
        result.sort((a, b) -> Integer.compare(a.stepIndex, b.stepIndex));
        return result;
    }

    /** True if the step is the mission's first portal, by step or by portal id. */
    private static boolean onFirstPortal(Mission mission, Passphrases.Step step) {
        int first = firstPortalStep(mission);
        if (step.stepIndex == first) return true;
        String firstPoi = first < 0 || mission.steps.get(first).poi == null ? null : mission.steps.get(first).poi.id;
        MissionStep missionStep = mission.steps.get(step.stepIndex);
        String poi = missionStep == null || missionStep.poi == null ? null : missionStep.poi.id;
        return firstPoi != null && firstPoi.equals(poi);
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
