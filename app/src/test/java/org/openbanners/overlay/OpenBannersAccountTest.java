package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;

public class OpenBannersAccountTest {
    private static String repeat(char c, int n) {
        return new String(new char[n]).replace('\0', c);
    }

    /** RFC 7636, appendix B. */
    @Test
    public void challengeIsPkceS256() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OpenBannersAccount.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    public void randomTokensFitTheServersPatterns() {
        String verifier = OpenBannersAccount.randomToken(48);
        String state = OpenBannersAccount.randomToken(24);
        assertTrue(verifier, verifier.matches("[A-Za-z0-9_-]{64}"));
        assertTrue(state, state.matches("[A-Za-z0-9_-]{32}"));
        assertTrue(OpenBannersAccount.challenge(verifier).matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(verifier, OpenBannersAccount.randomToken(48));
    }

    @Test
    public void connectUrlCarriesOnlyTheChallengeAndState() {
        String url = OpenBannersAccount.connectUrl("restore", repeat('c', 43), repeat('s', 32));
        assertEquals("https://openbanners.org/overlay/connect?action=restore&challenge=" + repeat('c', 43) + "&state=" + repeat('s', 32), url);
    }

    @Test
    public void linkMustMatchTheStartedSignInAndBeRecent() {
        OpenBannersAccount.Pending pending = new OpenBannersAccount.Pending("backup", "v", "state-1", 1_000_000L);
        assertTrue(pending.matches("state-1", 1_000_000L + 60_000));
        assertFalse(pending.matches("state-2", 1_000_000L + 60_000));
        assertFalse(pending.matches(null, 1_000_000L));
        assertFalse(pending.matches("state-1", 1_000_000L + OpenBannersAccount.PENDING_TTL_MS));
        assertFalse(pending.matches("state-1", 999_999L));
    }

    @Test
    public void parsesBackupsFromTheServer() throws Exception {
        OpenBannersAccount.Backup backup = OpenBannersAccount.parseBackupJson(
                "{\"backup\":{\"format\":1,\"appVersion\":51,\"createdAt\":\"2026-10-31T12:00:00.000Z\",\"settings\":{\"route_orientation\":\"north\",\"route_strip_enable\":true,\"future\":[\"a\"]}}}");
        assertEquals(51, backup.appVersion);
        assertEquals("north", backup.settings.get("route_orientation"));
        assertEquals(Boolean.TRUE, backup.settings.get("route_strip_enable"));
        SettingsBackup.Plan plan = SettingsBackup.plan(backup.settings, new HashMap<>());
        assertEquals(Collections.singletonList("future"), plan.unknown);
        assertNull(OpenBannersAccount.parseBackupJson("{\"backup\":null}"));
    }
}
