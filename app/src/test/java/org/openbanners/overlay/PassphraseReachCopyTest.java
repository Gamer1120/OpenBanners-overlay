package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.Objective;
import org.openbanners.overlay.api.POI;
import org.openbanners.overlay.api.POIType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class PassphraseReachCopyTest {
    private static MissionStep step(Objective objective, String poiId) {
        MissionStep step = new MissionStep();
        step.objective = objective;
        step.poi = new POI();
        step.poi.id = poiId;
        step.poi.title = poiId;
        return step;
    }

    private static Mission mission(MissionStep... steps) {
        Mission mission = new Mission();
        mission.id = "m5";
        mission.sourceBannerId = "banner";
        mission.sourceIndex = 4;
        mission.steps = new ArrayList<>(Arrays.asList(steps));
        return mission;
    }

    private static Passphrases.ForMission passphrases(Mission mission) {
        return Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, mission, 4, null);
    }

    private static Integer copied(Mission mission, Integer... reached) {
        Passphrases.Step step = PassphraseReachCopy.toCopy(mission, passphrases(mission), Arrays.asList(reached));
        return step == null ? null : step.stepIndex;
    }

    @Test
    public void laterPassphrasePortalIsCopiedWhenReached() {
        Mission mission = mission(step(Objective.hack, "p0"), step(Objective.hack, "p1"), step(Objective.enterPassphrase, "p2"));
        assertEquals(Integer.valueOf(2), copied(mission, 2));
        assertEquals(Integer.valueOf(2), copied(mission, 1, 2));
        assertNull(copied(mission, 0));
        assertNull(copied(mission, 1));
        assertNull(copied(mission));
    }

    @Test
    public void passphraseOnTheFirstPortalIsLeftToTheSwitchCopy() {
        Mission mission = mission(step(Objective.enterPassphrase, "p0"), step(Objective.hack, "p1"));
        assertNull(copied(mission, 0));
        assertNull(copied(mission, 0, 1));
        // The first portal again later in the mission (same portal id): also already copied.
        Mission loop = mission(step(Objective.hack, "p0"), step(Objective.hack, "p1"), step(Objective.enterPassphrase, "p0"));
        assertNull(copied(loop, 2));
    }

    @Test
    public void firstPortalSkipsDeletedPortals() {
        Mission mission = mission(step(Objective.hack, "gone"), step(Objective.enterPassphrase, "p1"), step(Objective.enterPassphrase, "p2"));
        mission.steps.get(0).poi.type = POIType.unavailable;
        assertEquals(1, PassphraseReachCopy.firstPortalStep(mission));
        assertNull(copied(mission, 0, 1));
        assertEquals(Integer.valueOf(2), copied(mission, 2));
    }

    @Test
    public void severalReachedAtOnceCopiesTheLast() {
        Mission mission = mission(step(Objective.hack, "p0"), step(Objective.enterPassphrase, "p1"),
                step(Objective.hack, "p2"), step(Objective.enterPassphrase, "p3"));
        assertEquals(Integer.valueOf(1), copied(mission, 1));
        assertEquals(Integer.valueOf(3), copied(mission, 1, 2, 3));
    }

    @Test
    public void nothingWithoutPassphrases() {
        Mission mission = mission(step(Objective.hack, "p0"), step(Objective.enterPassphrase, "p1"));
        assertNull(PassphraseReachCopy.toCopy(mission, Passphrases.NONE, Collections.singletonList(1)));
        assertNull(PassphraseReachCopy.toCopy(null, passphrases(mission), Collections.singletonList(1)));
    }

    @Test
    public void samePortalAtMostOnceAMinute() {
        PassphraseReachCopy copy = new PassphraseReachCopy();
        String key = PassphraseReachCopy.key(mission(), 4, 2);
        assertTrue(copy.allow(key, 1_000));
        assertFalse(copy.allow(key, 1_000 + 30_000));
        assertFalse(copy.allow(key, 1_000 + PassphraseReachCopy.COOLDOWN_MS - 1));
        assertTrue(copy.allow(key, 1_000 + PassphraseReachCopy.COOLDOWN_MS));
        assertFalse(copy.allow(key, 1_000 + PassphraseReachCopy.COOLDOWN_MS + 1));
    }

    @Test
    public void cooldownIsPerStep() {
        PassphraseReachCopy copy = new PassphraseReachCopy();
        Mission mission = mission();
        assertTrue(copy.allow(PassphraseReachCopy.key(mission, 4, 2), 0));
        assertTrue(copy.allow(PassphraseReachCopy.key(mission, 4, 5), 10));
        assertTrue(copy.allow(PassphraseReachCopy.key(mission, 5, 2), 20));
        assertNotEquals(PassphraseReachCopy.key(mission, 4, 2), PassphraseReachCopy.key(mission, 4, 5));
    }
}
