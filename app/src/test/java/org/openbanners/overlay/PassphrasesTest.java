package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.Objective;
import org.openbanners.overlay.api.POI;
import org.openbanners.overlay.api.PassphraseApi;

import java.util.ArrayList;
import java.util.Arrays;

public class PassphrasesTest {
    private static MissionStep step(Objective objective, String poiId, String title) {
        MissionStep step = new MissionStep();
        step.objective = objective;
        step.poi = new POI();
        step.poi.id = poiId;
        step.poi.title = title;
        return step;
    }

    /** Mission 5 (index 4) of its banner: hack, passphrase at portal p1, hack, passphrase at portal p3. */
    private static Mission mission(MissionStep... steps) {
        Mission mission = new Mission();
        mission.id = "m5";
        mission.sourceBannerId = "banner";
        mission.sourceIndex = 4;
        mission.steps = new ArrayList<>(Arrays.asList(steps));
        return mission;
    }

    private static Mission singlePassphraseMission() {
        return mission(step(Objective.hack, "p0", "Fountain"), step(Objective.enterPassphrase, "p1", "Statue"));
    }

    private static Mission twoPassphraseMission() {
        return mission(step(Objective.hack, "p0", "Fountain"), step(Objective.enterPassphrase, "p1", "Statue"),
                step(Objective.hack, "p2", "Church"), step(Objective.enterPassphrase, "p3", "Bridge"));
    }

    private static PassphraseApi.Answer answer(String missionId, int missionIndex, int stepIndex, String poiId, String text, int confirms) {
        PassphraseApi.Answer answer = new PassphraseApi.Answer();
        answer.missionId = missionId;
        answer.missionIndex = missionIndex;
        answer.stepIndex = stepIndex;
        answer.poiId = poiId;
        answer.kind = "text";
        answer.status = "live";
        answer.answer = text;
        answer.confirms = confirms;
        return answer;
    }

    private static PassphraseApi.BannerAnswers answers(PassphraseApi.Answer... answers) {
        PassphraseApi.BannerAnswers result = new PassphraseApi.BannerAnswers();
        result.bannerId = "banner";
        result.answers = new ArrayList<>(Arrays.asList(answers));
        return result;
    }

    private static Passphrases.ForMission openBanners(Mission mission, boolean fallback, PassphraseApi.BannerAnswers answers) {
        return Passphrases.forMission(Passphrases.SOURCE_OPENBANNERS, fallback, mission, 4, answers);
    }

    @Test
    public void noSourceMeansNoButton() {
        assertFalse(Passphrases.forMission(Passphrases.SOURCE_NONE, true, singlePassphraseMission(), 4, answers()).show());
        assertFalse(Passphrases.forMission(null, false, singlePassphraseMission(), 4, null).show());
    }

    @Test
    public void missionWithoutPassphraseStepHasNoButton() {
        Mission mission = mission(step(Objective.hack, "p0", "Fountain"));
        assertFalse(Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, mission, 4, null).show());
        assertFalse(openBanners(mission, true, answers()).show());
    }

    @Test
    public void matchesByPortalId() {
        Mission mission = twoPassphraseMission();
        // The step number is off (the mission was edited), but the portal still identifies the step.
        assertEquals(3, Passphrases.matchStep(mission, 4, answer("m5", 4, 1, "p3", "x", 0)));
        assertEquals(1, Passphrases.matchStep(mission, 4, answer("m5", 4, 1, "p1", "x", 0)));
    }

    @Test
    public void fallsBackToStepNumberWithoutPortalIds() {
        Mission mission = twoPassphraseMission();
        assertEquals(3, Passphrases.matchStep(mission, 4, answer("m5", 4, 3, null, "x", 0)));
        mission.steps.get(3).poi.id = null;
        assertEquals(3, Passphrases.matchStep(mission, 4, answer("m5", 4, 3, "gone", "x", 0)));
        // No mission id: the mission number decides.
        assertEquals(1, Passphrases.matchStep(mission, 4, answer(null, 4, 1, "p1", "x", 0)));
        assertEquals(-1, Passphrases.matchStep(mission, 4, answer(null, 3, 1, "p1", "x", 0)));
    }

    @Test
    public void mismatchIsHiddenRatherThanShownAtTheWrongStep() {
        Mission mission = twoPassphraseMission();
        // Portal unknown in this mission while the step at that number has another portal.
        assertEquals(-1, Passphrases.matchStep(mission, 4, answer("m5", 4, 1, "elsewhere", "x", 0)));
        // Step number points at a hack step.
        assertEquals(-1, Passphrases.matchStep(mission, 4, answer("m5", 4, 0, null, "x", 0)));
        // Another mission.
        assertEquals(-1, Passphrases.matchStep(mission, 4, answer("other", 4, 1, "p1", "x", 0)));
        // Out of range.
        assertEquals(-1, Passphrases.matchStep(mission, 4, answer("m5", 4, 9, null, "x", 0)));
        Passphrases.ForMission result = openBanners(mission, false, answers(answer("m5", 4, 1, "elsewhere", "wrong place", 5)));
        assertTrue(result.show());
        assertFalse(result.steps.get(0).known());
        assertFalse(result.steps.get(1).known());
    }

    @Test
    public void singlePassphraseCopiesTheMostConfirmedAnswer() {
        Passphrases.ForMission result = openBanners(singlePassphraseMission(), false, answers(
                answer("m5", 4, 1, "p1", "Onion", 1),
                answer("m5", 4, 1, "p1", "Hagyma", 3),
                answer("m5", 4, 1, "p1", "  ", 9)));
        assertTrue(result.single());
        assertEquals("5b", result.steps.get(0).label);
        assertEquals("Statue", result.steps.get(0).portal);
        assertEquals("Hagyma", result.steps.get(0).best());
        assertEquals("[Hagyma(3), Onion(1)]", result.steps.get(0).candidates.toString());
        Passphrases.Copy copy = result.copy(result.steps.get(0));
        assertEquals("Hagyma", copy.text);
        assertFalse(copy.missionNumber);
        assertEquals("Hagyma", result.autoCopy().text);
    }

    @Test
    public void onlyLiveAnswersCount() {
        PassphraseApi.Answer pending = answer("m5", 4, 1, "p1", "Secret", 0);
        pending.status = "pending";
        PassphraseApi.Answer hidden = answer("m5", 4, 1, "p1", null, 0);
        Passphrases.ForMission result = openBanners(singlePassphraseMission(), false, answers(pending, hidden));
        assertTrue(result.show());
        assertFalse(result.steps.get(0).known());
        assertTrue(result.unknown());
        assertNull(result.copy(result.steps.get(0)));
        assertNull(result.autoCopy());
    }

    @Test
    public void unknownWithFallbackCopiesTheMissionNumber() {
        Passphrases.ForMission result = openBanners(singlePassphraseMission(), true, answers());
        assertFalse(result.unknown()); // not grey: tapping copies the mission number
        Passphrases.Copy copy = result.copy(result.steps.get(0));
        assertEquals("5", copy.text);
        assertTrue(copy.missionNumber);
        assertEquals("5", result.autoCopy().text);
    }

    @Test
    public void notDownloadedYetCountsAsUnknown() {
        Passphrases.ForMission result = openBanners(singlePassphraseMission(), false, null);
        assertTrue(result.show());
        assertTrue(result.unknown());
    }

    @Test
    public void severalPassphrasesAreListedAndNeverAutoCopied() {
        Passphrases.ForMission result = openBanners(twoPassphraseMission(), true, answers(answer("m5", 4, 3, "p3", "Dió", 0)));
        assertFalse(result.single());
        assertEquals(2, result.steps.size());
        assertEquals("5b", result.steps.get(0).label);
        assertEquals("5d", result.steps.get(1).label);
        assertFalse(result.steps.get(0).known());
        assertEquals("Dió", result.steps.get(1).best());
        assertTrue(result.copy(result.steps.get(0)).missionNumber);
        assertNull(result.autoCopy());
    }

    @Test
    public void authorOptedOutMeansNoButton() {
        PassphraseApi.BannerAnswers blocked = answers(answer("m5", 4, 1, "p1", "Hagyma", 3));
        blocked.blocked = "author";
        assertSame(Passphrases.NONE, openBanners(singlePassphraseMission(), true, blocked));
        assertFalse(openBanners(singlePassphraseMission(), true, blocked).show());
    }

    @Test
    public void missionNumberSourceCopiesTheNumberInItsOwnBanner() {
        Mission mission = singlePassphraseMission();
        // Mission 5 of its banner, followed as the 2nd mission of a Mission Day plan: label 2b, passphrase 5.
        Passphrases.ForMission result = Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, mission, 1, null);
        assertTrue(result.single());
        assertEquals("2b", result.steps.get(0).label);
        assertEquals("5", result.copy(result.steps.get(0)).text);
        assertFalse(result.copy(result.steps.get(0)).missionNumber);
        assertEquals("5", result.autoCopy().text);
        // Without a known source banner the position counts.
        mission.sourceIndex = -1;
        assertEquals("2", Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, mission, 1, null).autoCopy().text);
    }

    @Test
    public void missionNumberSourceListsSeveralSteps() {
        Passphrases.ForMission result = Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, twoPassphraseMission(), 4, null);
        assertEquals(2, result.steps.size());
        assertEquals("5", result.steps.get(1).best());
        assertNull(result.autoCopy());
    }

    @Test
    public void parsesTheSiteResponse() throws Exception {
        String json = "{\"bannerId\":\"b\",\"blocked\":null,\"pendingDays\":30,\"viewer\":null,\"answers\":[{\"id\":340,"
                + "\"missionId\":\"m5\",\"missionIndex\":4,\"stepIndex\":1,\"poiId\":\"p1\",\"kind\":\"text\",\"source\":\"crowd\","
                + "\"status\":\"live\",\"answer\":\"Hagyma\",\"confirms\":2,\"wrongs\":0,\"myVote\":null,\"mine\":false}]}";
        PassphraseApi.BannerAnswers parsed = PassphraseApi.parse(json);
        assertNull(parsed.blocked);
        assertEquals(1, parsed.answers.size());
        assertEquals("Hagyma", openBanners(singlePassphraseMission(), false, parsed).steps.get(0).best());
        assertEquals("author", PassphraseApi.parse("{\"blocked\":\"author\",\"answers\":[]}").blocked);
    }
}
