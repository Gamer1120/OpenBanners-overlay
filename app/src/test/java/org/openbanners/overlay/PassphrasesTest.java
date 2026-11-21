package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.openbanners.overlay.api.Banner;
import org.openbanners.overlay.api.BannerApi;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.Objective;
import org.openbanners.overlay.api.POI;
import org.openbanners.overlay.api.POIType;
import org.openbanners.overlay.api.PassphraseApi;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        assertEquals("Hagyma", result.autoCopy(true).text);
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
        assertNull(result.autoCopy(true));
    }

    @Test
    public void unknownWithFallbackCopiesTheMissionNumber() {
        Passphrases.ForMission result = openBanners(singlePassphraseMission(), true, answers());
        assertFalse(result.unknown()); // not grey: tapping copies the mission number
        Passphrases.Copy copy = result.copy(result.steps.get(0));
        assertEquals("5", copy.text);
        assertTrue(copy.missionNumber);
        assertEquals("5", result.autoCopy(true).text);
    }

    @Test
    public void notDownloadedYetCountsAsUnknown() {
        Passphrases.ForMission result = openBanners(singlePassphraseMission(), false, null);
        assertTrue(result.show());
        assertTrue(result.unknown());
    }

    @Test
    public void severalPassphrasesAreListedAndTheFirstIsAutoCopied() {
        Passphrases.ForMission result = openBanners(twoPassphraseMission(), true, answers(answer("m5", 4, 3, "p3", "Dió", 0)));
        assertFalse(result.single());
        assertEquals(2, result.steps.size());
        assertEquals("5b", result.steps.get(0).label);
        assertEquals("5d", result.steps.get(1).label);
        assertFalse(result.steps.get(0).known());
        assertEquals("Dió", result.steps.get(1).best());
        assertTrue(result.copy(result.steps.get(0)).missionNumber);
        // The first passphrase (5b) is not known: the mission number, not the known 5d answer.
        assertEquals("5", result.autoCopy(true).text);
        assertTrue(result.autoCopy(true).missionNumber);
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
        assertEquals("5", result.autoCopy(true).text);
        // Without a known source banner the position counts.
        mission.sourceIndex = -1;
        assertEquals("2", Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, mission, 1, null).autoCopy(true).text);
    }

    @Test
    public void missionNumberSourceListsSeveralSteps() {
        Passphrases.ForMission result = Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, twoPassphraseMission(), 4, null);
        assertEquals(2, result.steps.size());
        assertEquals("5", result.steps.get(1).best());
        assertEquals("5", result.autoCopy(true).text);
        assertFalse(result.autoCopy(true).missionNumber);
        assertNull(result.autoCopy(false));
    }

    // --- Auto-copy on switching to a mission: every case (OpenBanners source) ----------------------------------------

    /** What auto-copy copies on switching to {@code mission}, or null for nothing. */
    private static String autoCopied(Mission mission, boolean autoCopy, boolean fallback, PassphraseApi.BannerAnswers answers) {
        Passphrases.Copy copy = openBanners(mission, fallback, answers).autoCopy(autoCopy);
        return copy == null ? null : copy.text;
    }

    @Test
    public void autoCopyOneKnownPassphrase() {
        PassphraseApi.BannerAnswers known = answers(answer("m5", 4, 1, "p1", "Hagyma", 2));
        assertEquals("Hagyma", autoCopied(singlePassphraseMission(), true, false, known));
        assertEquals("Hagyma", autoCopied(singlePassphraseMission(), true, true, known));
        assertNull(autoCopied(singlePassphraseMission(), false, false, known));
        assertNull(autoCopied(singlePassphraseMission(), false, true, known));
    }

    @Test
    public void autoCopySeveralPassphrasesCopiesTheFirst() {
        PassphraseApi.BannerAnswers both = answers(answer("m5", 4, 3, "p3", "Dió", 5), answer("m5", 4, 1, "p1", "Alma", 1));
        assertEquals("Alma", autoCopied(twoPassphraseMission(), true, false, both));
        assertEquals("Alma", autoCopied(twoPassphraseMission(), true, true, both));
        assertFalse(openBanners(twoPassphraseMission(), true, both).autoCopy(true).missionNumber);
        assertNull(autoCopied(twoPassphraseMission(), false, false, both));
        assertNull(autoCopied(twoPassphraseMission(), false, true, both));
    }

    @Test
    public void autoCopySeveralPassphrasesFirstUnknownCountsAsUnknown() {
        PassphraseApi.BannerAnswers secondOnly = answers(answer("m5", 4, 3, "p3", "Dió", 5));
        assertEquals("5", autoCopied(twoPassphraseMission(), true, true, secondOnly));
        assertNull(autoCopied(twoPassphraseMission(), true, false, secondOnly));
        assertNull(autoCopied(twoPassphraseMission(), false, true, secondOnly));
        assertNull(autoCopied(twoPassphraseMission(), false, false, secondOnly));
    }

    @Test
    public void autoCopyUnknownPassphraseCopiesTheMissionNumberWithTheFallback() {
        for (PassphraseApi.BannerAnswers none : new PassphraseApi.BannerAnswers[] {answers(), null}) {
            for (Mission mission : new Mission[] {singlePassphraseMission(), twoPassphraseMission()}) {
                Passphrases.Copy copy = openBanners(mission, true, none).autoCopy(true);
                assertEquals("5", copy.text); // same text as the 🔑 button / "Copy mission number"
                assertTrue(copy.missionNumber);
                assertEquals(openBanners(mission, true, none).missionNumber, copy.text);
                assertNull(autoCopied(mission, true, false, none));
                assertNull(autoCopied(mission, false, true, none));
                assertNull(autoCopied(mission, false, false, none));
            }
        }
    }

    @Test
    public void autoCopyNothingWithoutPassphraseOrSource() {
        Mission noPassphrase = mission(step(Objective.hack, "p0", "Fountain"));
        assertNull(openBanners(noPassphrase, true, answers()).autoCopy(true));
        assertNull(Passphrases.forMission(Passphrases.SOURCE_NONE, true, twoPassphraseMission(), 4, answers()).autoCopy(true));
        PassphraseApi.BannerAnswers blocked = answers(answer("m5", 4, 1, "p1", "Alma", 3));
        blocked.blocked = "author";
        assertNull(openBanners(twoPassphraseMission(), true, blocked).autoCopy(true));
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

    // --- Passphrase steps on deleted portals (POI type unavailable): Ingress skips them, so they don't count ---------

    private static MissionStep onType(MissionStep step, POIType type) {
        step.poi.type = type;
        return step;
    }

    private static MissionStep deleted(MissionStep step) {
        return onType(step, POIType.unavailable);
    }

    @Test
    public void onlyPassphraseOnADeletedPortalMeansNoPassphrase() {
        Mission mission = mission(step(Objective.hack, "p0", "Fountain"), deleted(step(Objective.enterPassphrase, "p1", "Statue")));
        PassphraseApi.BannerAnswers known = answers(answer("m5", 4, 1, "p1", "Alma", 3));
        for (boolean fallback : new boolean[] {true, false}) {
            Passphrases.ForMission result = openBanners(mission, fallback, known);
            assertSame(Passphrases.NONE, result); // no 🔑
            assertNull(result.autoCopy(true)); // nothing copied, also no mission number
        }
        Passphrases.ForMission number = Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, mission, 4, null);
        assertFalse(number.show()); // no mission-number guess
        assertNull(number.autoCopy(true));
        assertTrue(Passphrases.passphraseSteps(mission).isEmpty());
    }

    @Test
    public void firstPassphraseDeletedLaterLive() {
        Mission mission = twoPassphraseMission();
        deleted(mission.steps.get(1));
        assertEquals(Arrays.asList(3), Passphrases.passphraseSteps(mission));
        PassphraseApi.BannerAnswers both = answers(answer("m5", 4, 1, "p1", "Alma", 9), answer("m5", 4, 3, "p3", "Dió", 1));
        Passphrases.ForMission result = openBanners(mission, false, both);
        assertTrue(result.single());
        assertEquals("5d", result.steps.get(0).label);
        assertEquals("Dió", result.autoCopy(true).text); // first passphrase on a live portal
        // Only the deleted one known: the live one is unknown.
        PassphraseApi.BannerAnswers deletedOnly = answers(answer("m5", 4, 1, "p1", "Alma", 9));
        assertTrue(openBanners(mission, false, deletedOnly).unknown());
        assertNull(autoCopied(mission, true, false, deletedOnly));
        Passphrases.Copy copy = openBanners(mission, true, deletedOnly).autoCopy(true);
        assertEquals("5", copy.text);
        assertTrue(copy.missionNumber);
        Passphrases.ForMission number = Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, mission, 4, null);
        assertTrue(number.single());
        assertEquals("5", number.autoCopy(true).text);
    }

    @Test
    public void firstPassphraseLiveLaterDeleted() {
        Mission mission = twoPassphraseMission();
        deleted(mission.steps.get(3));
        assertEquals(Arrays.asList(1), Passphrases.passphraseSteps(mission));
        PassphraseApi.BannerAnswers both = answers(answer("m5", 4, 3, "p3", "Dió", 9), answer("m5", 4, 1, "p1", "Alma", 1));
        Passphrases.ForMission result = openBanners(mission, false, both);
        assertTrue(result.single());
        assertEquals("5b", result.steps.get(0).label);
        assertEquals("Alma", result.autoCopy(true).text);
        PassphraseApi.BannerAnswers deletedOnly = answers(answer("m5", 4, 3, "p3", "Dió", 9));
        assertTrue(openBanners(mission, false, deletedOnly).unknown());
        assertNull(autoCopied(mission, true, false, deletedOnly));
        assertEquals("5", autoCopied(mission, true, true, deletedOnly));
    }

    @Test
    public void noPassphraseDeletedKeepsEveryStep() {
        Mission mission = twoPassphraseMission();
        onType(mission.steps.get(1), POIType.portal);
        onType(mission.steps.get(3), POIType.fieldTripWaypoint);
        assertEquals(Arrays.asList(1, 3), Passphrases.passphraseSteps(mission));
        PassphraseApi.BannerAnswers both = answers(answer("m5", 4, 3, "p3", "Dió", 5), answer("m5", 4, 1, "p1", "Alma", 1));
        Passphrases.ForMission result = openBanners(mission, false, both);
        assertEquals(2, result.steps.size());
        assertEquals("Alma", result.autoCopy(true).text);
        // No type at all (older data) counts as live too.
        assertEquals(Arrays.asList(1, 3), Passphrases.passphraseSteps(twoPassphraseMission()));
    }

    /** Real Bannergress data (GET /bnrs/malaysia-mission-hunt-klcc-c868, 2026-11-21). */
    @Test
    public void malaysiaMissionHuntKlcc() throws Exception {
        Banner banner;
        try (InputStream in = getClass().getResourceAsStream("/banners/malaysia-mission-hunt-klcc-c868.json")) {
            banner = BannerApi.parseBanner(new String(readAll(in), StandardCharsets.UTF_8));
        }
        // Mission 23: its only passphrase (step 1) is on a deleted portal.
        Mission m23 = banner.missions.get(22);
        assertSame(POIType.unavailable, m23.steps.get(0).poi.type);
        assertTrue(Passphrases.isPassphraseStep(m23.steps.get(0)));
        for (String source : new String[] {Passphrases.SOURCE_MISSION_NUMBER, Passphrases.SOURCE_OPENBANNERS}) {
            Passphrases.ForMission result = Passphrases.forMission(source, true, m23, 22, answers());
            assertFalse(result.show());
            assertNull(result.autoCopy(true));
        }
        // Mission 18: step 1 live, step 2 on a deleted portal: one passphrase, step 1.
        Mission m18 = banner.missions.get(17);
        assertSame(POIType.unavailable, m18.steps.get(1).poi.type);
        Passphrases.ForMission m18Number = Passphrases.forMission(Passphrases.SOURCE_MISSION_NUMBER, false, m18, 17, null);
        assertTrue(m18Number.single());
        assertEquals("18a", m18Number.steps.get(0).label);
        assertEquals("18", m18Number.autoCopy(true).text);
        Passphrases.ForMission m18Ob = Passphrases.forMission(Passphrases.SOURCE_OPENBANNERS, true, m18, 17, null);
        assertTrue(m18Ob.single());
        assertEquals(0, m18Ob.steps.get(0).stepIndex);
        // Mission 11: two live passphrases, both count.
        assertEquals(Arrays.asList(0, 2), Passphrases.passphraseSteps(banner.missions.get(10)));
        // Mission 1 has no passphrase step; every other mission except 23 has one that counts.
        assertTrue(Passphrases.passphraseSteps(banner.missions.get(0)).isEmpty());
        int withPassphrase = 0;
        for (Mission mission : banner.missions.values()) if (!Passphrases.passphraseSteps(mission).isEmpty()) withPassphrase++;
        assertEquals(24, banner.missions.size());
        assertEquals(22, withPassphrase);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
