package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PassphraseSettingsTest {
    @Test
    public void oldCopySwitchOnBecomesMissionNumberWithAutoCopy() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("clipboard_enable", true);
        stored.put("card_items", "minus,counter,plus,next,close,done");
        Map<String, Object> changes = PassphraseSettings.migration(stored);
        assertEquals("mission", changes.get("passphrase_source"));
        assertEquals(true, changes.get("passphrase_autocopy"));
        assertFalse(changes.containsKey("passphrase_fallback"));
        assertEquals("minus,counter,plus,passphrase,next,close,done", changes.get("card_items"));
        assertTrue(changes.containsKey("clipboard_enable"));
        assertNull(changes.get("clipboard_enable")); // removed
        // Read before the migration ran (e.g. by a backup): same result.
        assertEquals("mission", PassphraseSettings.source(stored));
        assertTrue(PassphraseSettings.autoCopy(stored));
        assertFalse(PassphraseSettings.fallback(stored));
    }

    @Test
    public void oldCopySwitchOffGetsTheDefaults() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("clipboard_enable", false);
        Map<String, Object> changes = PassphraseSettings.migration(stored);
        assertEquals(1, changes.size());
        assertNull(changes.get("clipboard_enable"));
        assertEquals("none", PassphraseSettings.source(stored));
        assertFalse(PassphraseSettings.autoCopy(stored));
    }

    @Test
    public void newInstallsAndMigratedSettingsAreLeftAlone() {
        assertTrue(PassphraseSettings.migration(new HashMap<>()).isEmpty());
        Map<String, Object> stored = new HashMap<>();
        stored.put("passphrase_source", "openbanners");
        assertTrue(PassphraseSettings.migration(stored).isEmpty());
        assertEquals("none", PassphraseSettings.source(new HashMap<>()));
        assertFalse(PassphraseSettings.autoCopy(new HashMap<>()));
    }

    /**
     * An update (2026-11-20, Michael: "when I updated my passphrase settings were lost") must keep what the user chose
     * in Settings → Passphrases. Settings as a 52–59 install has them, also with a leftover old copy switch: what runs
     * at every start (migrate) changes none of them, and the overlay reads them back unchanged.
     */
    @Test
    public void startAfterAnUpdateKeepsThePassphraseSettings() {
        for (Object leftover : new Object[]{null, false, true}) {
            Map<String, Object> stored = new HashMap<>();
            stored.put("passphrase_source", "openbanners");
            stored.put("passphrase_fallback", true);
            stored.put("passphrase_autocopy", true);
            stored.put("card_items", "minus,counter,plus,passphrase,next,close,done");
            stored.put("navigate_next_enable", true);
            if (leftover != null) stored.put("clipboard_enable", leftover);
            Map<String, Object> changes = PassphraseSettings.migration(stored);
            for (String key : new String[]{"passphrase_source", "passphrase_fallback", "passphrase_autocopy", "card_items"}) {
                assertFalse(key + " with clipboard_enable=" + leftover, changes.containsKey(key));
            }
            for (Map.Entry<String, Object> change : changes.entrySet()) {
                if (change.getValue() == null) stored.remove(change.getKey()); else stored.put(change.getKey(), change.getValue());
            }
            assertEquals("openbanners", PassphraseSettings.source(stored));
            assertTrue(PassphraseSettings.fallback(stored));
            assertTrue(PassphraseSettings.autoCopy(stored));
            assertTrue(PassphraseSettings.migration(stored).isEmpty()); // the next start does nothing
        }
        Map<String, Object> mission = new HashMap<>();
        mission.put("passphrase_source", "mission");
        mission.put("passphrase_autocopy", false);
        mission.put("clipboard_enable", true); // old switch still on: the newer choice wins
        Map<String, Object> changes = PassphraseSettings.migration(mission);
        assertEquals(1, changes.size());
        assertNull(changes.get("clipboard_enable"));
        assertFalse(PassphraseSettings.autoCopy(mission));
    }

    @Test
    public void fallbackOnlyWithOpenBannersAndAutoCopyOnlyWithASource() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("passphrase_fallback", true);
        stored.put("passphrase_autocopy", true);
        stored.put("passphrase_source", "none");
        assertFalse(PassphraseSettings.fallback(stored));
        assertFalse(PassphraseSettings.autoCopy(stored));
        stored.put("passphrase_source", "mission");
        assertFalse(PassphraseSettings.fallback(stored));
        assertTrue(PassphraseSettings.autoCopy(stored));
        stored.put("passphrase_source", "openbanners");
        assertTrue(PassphraseSettings.fallback(stored));
        stored.put("passphrase_source", "something new");
        assertEquals("none", PassphraseSettings.source(stored));
    }
}
