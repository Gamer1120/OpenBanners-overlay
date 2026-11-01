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
