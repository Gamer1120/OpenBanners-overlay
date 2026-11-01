package org.openbanners.overlay;

import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.Objective;
import org.openbanners.overlay.api.PassphraseApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which passphrases the mission card offers for one mission (pure Java, unit-tested). Everything is about the
 * currently selected mission: nothing depends on your position or on ticked-off steps.
 * <p>
 * Sources (Settings → Passphrases): none (default), the mission number (for banners where every passphrase is the
 * mission number), or crowd-sourced answers from openbanners.org, optionally falling back to the mission number when
 * no answer is known.
 */
final class Passphrases {
    static final String SOURCE_NONE = "none";
    static final String SOURCE_MISSION_NUMBER = "mission";
    static final String SOURCE_OPENBANNERS = "openbanners";

    private Passphrases() {
    }

    /** One possible answer for a step. */
    static final class Candidate {
        final String text;
        final int confirms;
        final int wrongs;

        Candidate(String text, int confirms, int wrongs) {
            this.text = text;
            this.confirms = confirms;
            this.wrongs = wrongs;
        }

        @Override
        public String toString() {
            return text + "(" + confirms + ")";
        }
    }

    /** A passphrase step of the mission and the answers known for it, best (most confirms) first. */
    static final class Step {
        final int stepIndex;
        /** Same label as on the route map, e.g. "5c". */
        final String label;
        final String portal;
        final List<Candidate> candidates;

        Step(int stepIndex, String label, String portal, List<Candidate> candidates) {
            this.stepIndex = stepIndex;
            this.label = label;
            this.portal = portal;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }

        boolean known() {
            return !candidates.isEmpty();
        }

        /** The answer to copy: the one with the most confirms. Null when none is known. */
        String best() {
            return candidates.isEmpty() ? null : candidates.get(0).text;
        }

        @Override
        public String toString() {
            return label + candidates;
        }
    }

    /** What the card shows for the current mission. */
    static final class ForMission {
        /** Empty when there is no button (no source, no passphrase step, or the author opted out). */
        final List<Step> steps;
        /** The mission number to copy, used by the mission-number source and the fallback. */
        final String missionNumber;
        /** Copy the mission number when no answer is known (OpenBanners source only). */
        final boolean fallback;

        ForMission(List<Step> steps, String missionNumber, boolean fallback) {
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.missionNumber = missionNumber;
            this.fallback = fallback;
        }

        boolean show() {
            return !steps.isEmpty();
        }

        boolean single() {
            return steps.size() == 1;
        }

        /** Whether tapping the button / a row can copy something. */
        boolean anyCopyable() {
            for (Step step : steps) if (step.known()) return true;
            return fallback && !steps.isEmpty();
        }

        /** Nothing known and no fallback: the button is grey. */
        boolean unknown() {
            return show() && !anyCopyable();
        }

        /** What tapping copies for {@code step}: its best answer, else the mission number with the fallback, else null. */
        Copy copy(Step step) {
            if (step.known()) return new Copy(step.best(), false);
            if (fallback) return new Copy(missionNumber, true);
            return null;
        }

        /**
         * Copied automatically when you switch to this mission (if that's on): only for a mission with exactly one
         * passphrase, never for several (which one would you want?).
         */
        Copy autoCopy() {
            return single() ? copy(steps.get(0)) : null;
        }
    }

    static final class Copy {
        final String text;
        /** The mission number, copied because no answer is known. */
        final boolean missionNumber;

        Copy(String text, boolean missionNumber) {
            this.text = text;
            this.missionNumber = missionNumber;
        }
    }

    static final ForMission NONE = new ForMission(Collections.emptyList(), null, false);

    static boolean isPassphraseStep(MissionStep step) {
        return step != null && step.objective == Objective.enterPassphrase;
    }

    /** Indexes of the mission's passphrase steps (same rule as the website's passphraseStepsOf). */
    static List<Integer> passphraseSteps(Mission mission) {
        List<Integer> indexes = new ArrayList<>();
        if (mission == null || mission.steps == null) return indexes;
        for (int i = 0; i < mission.steps.size(); i++) {
            if (isPassphraseStep(mission.steps.get(i))) indexes.add(i);
        }
        return indexes;
    }

    private static String poiId(MissionStep step) {
        return step == null || step.poi == null ? null : step.poi.id;
    }

    /**
     * The step an answer belongs to, or -1. Matched by portal id; if the answer or the step has no portal id, by its
     * step number. When the ids disagree the answer is dropped: better nothing than a passphrase at the wrong step.
     */
    static int matchStep(Mission mission, int sourceIndex, PassphraseApi.Answer answer) {
        if (mission == null || mission.steps == null || answer == null) return -1;
        if (answer.missionId != null) {
            if (!answer.missionId.equals(mission.id)) return -1;
        } else if (answer.missionIndex == null || answer.missionIndex != sourceIndex) {
            return -1;
        }
        Integer stepIndex = answer.stepIndex;
        MissionStep atIndex = stepIndex != null && stepIndex >= 0 && stepIndex < mission.steps.size() ? mission.steps.get(stepIndex) : null;
        if (answer.poiId != null) {
            if (isPassphraseStep(atIndex) && answer.poiId.equals(poiId(atIndex))) return stepIndex;
            for (int i = 0; i < mission.steps.size(); i++) {
                MissionStep step = mission.steps.get(i);
                if (isPassphraseStep(step) && answer.poiId.equals(poiId(step))) return i;
            }
            // No step with that portal: only use the step number if the step has no portal id to compare.
            if (isPassphraseStep(atIndex) && poiId(atIndex) == null) return stepIndex;
            return -1;
        }
        return isPassphraseStep(atIndex) ? stepIndex : -1;
    }

    static boolean usable(PassphraseApi.Answer answer) {
        return answer != null && "live".equals(answer.status) && answer.answer != null && !answer.answer.trim().isEmpty();
    }

    /**
     * The passphrases for {@code mission}, the mission at 0-based {@code position} in what the overlay follows (for the
     * labels). {@code answers}: the OpenBanners answers of the mission's banner, or null if not (yet) available, which
     * counts as "not known".
     */
    static ForMission forMission(String source, boolean fallback, Mission mission, int position, PassphraseApi.BannerAnswers answers) {
        if (mission == null || source == null || SOURCE_NONE.equals(source)) return NONE;
        boolean openBanners = SOURCE_OPENBANNERS.equals(source);
        if (!openBanners && !SOURCE_MISSION_NUMBER.equals(source)) return NONE;
        if (openBanners && answers != null && answers.blocked != null && !answers.blocked.isEmpty()) return NONE;
        List<Integer> indexes = passphraseSteps(mission);
        if (indexes.isEmpty()) return NONE;
        int sourceIndex = mission.sourceIndex >= 0 ? mission.sourceIndex : position;
        String missionNumber = String.valueOf(sourceIndex + 1);
        List<Step> steps = new ArrayList<>();
        for (int index : indexes) {
            MissionStep missionStep = mission.steps.get(index);
            String portal = missionStep.poi == null || missionStep.poi.title == null ? "" : missionStep.poi.title;
            List<Candidate> candidates = new ArrayList<>();
            if (!openBanners) {
                candidates.add(new Candidate(missionNumber, 0, 0));
            } else if (answers != null && answers.answers != null) {
                for (PassphraseApi.Answer answer : answers.answers) {
                    if (!usable(answer) || matchStep(mission, sourceIndex, answer) != index) continue;
                    String text = answer.answer.trim();
                    boolean duplicate = false;
                    for (Candidate existing : candidates) duplicate |= existing.text.equals(text);
                    if (!duplicate) candidates.add(new Candidate(text, answer.confirms, answer.wrongs));
                }
                // Most confirms first, then fewest "wrong" votes; otherwise the site's order.
                List<Candidate> sorted = new ArrayList<>(candidates);
                Collections.sort(sorted, (a, b) -> a.confirms != b.confirms ? Integer.compare(b.confirms, a.confirms) : Integer.compare(a.wrongs, b.wrongs));
                candidates = sorted;
            }
            steps.add(new Step(index, label(position, index), portal, candidates));
        }
        return new ForMission(steps, missionNumber, openBanners && fallback);
    }

    /** "5c" = mission 5, step 3: the route map's labels. */
    static String label(int missionIndex, int stepIndex) {
        String step = stepIndex < 26 ? String.valueOf((char) ('a' + stepIndex)) : "." + (stepIndex + 1);
        return (missionIndex + 1) + step;
    }
}
