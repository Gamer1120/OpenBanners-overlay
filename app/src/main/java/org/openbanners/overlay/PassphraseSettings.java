package org.openbanners.overlay;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings → Passphrases: the source, the mission-number fallback and auto-copy, plus the migration from the old
 * "Copy mission number as passphrase" switch (versions up to 51). Pure logic on maps so it can be unit-tested;
 * {@link #migrate(SharedPreferences)} applies it.
 */
final class PassphraseSettings {
    static final String SOURCE = "passphrase_source";
    static final String FALLBACK = "passphrase_fallback";
    static final String AUTO_COPY = "passphrase_autocopy";
    /** The old switch: copied the mission number when a passphrase step came in range. */
    static final String LEGACY_CLIPBOARD = "clipboard_enable";

    private PassphraseSettings() {
    }

    static String source(Map<String, ?> stored) {
        Object value = stored.get(SOURCE);
        if (Passphrases.SOURCE_MISSION_NUMBER.equals(value) || Passphrases.SOURCE_OPENBANNERS.equals(value)) return (String) value;
        if (value == null && Boolean.TRUE.equals(stored.get(LEGACY_CLIPBOARD))) return Passphrases.SOURCE_MISSION_NUMBER;
        return Passphrases.SOURCE_NONE;
    }

    static boolean fallback(Map<String, ?> stored) {
        return Passphrases.SOURCE_OPENBANNERS.equals(source(stored)) && Boolean.TRUE.equals(stored.get(FALLBACK));
    }

    static boolean autoCopy(Map<String, ?> stored) {
        if (Passphrases.SOURCE_NONE.equals(source(stored))) return false;
        Object value = stored.get(AUTO_COPY);
        if (value == null && stored.get(SOURCE) == null) return Boolean.TRUE.equals(stored.get(LEGACY_CLIPBOARD));
        return Boolean.TRUE.equals(value);
    }

    /**
     * The changes that bring stored settings from before version 52 up to date (empty if there's nothing to do). The
     * old switch on → mission number, copied automatically, as before, and the 🔑 button on the card. Everyone else
     * keeps the defaults (no passphrases). A null value means "remove".
     */
    static Map<String, Object> migration(Map<String, ?> stored) {
        Map<String, Object> changes = new HashMap<>();
        if (!stored.containsKey(LEGACY_CLIPBOARD)) return changes;
        if (stored.get(SOURCE) == null && Boolean.TRUE.equals(stored.get(LEGACY_CLIPBOARD))) {
            changes.put(SOURCE, Passphrases.SOURCE_MISSION_NUMBER);
            changes.put(AUTO_COPY, true);
            Object items = stored.get("card_items");
            changes.put("card_items", CardItems.withPassphrase(items instanceof String ? (String) items : null));
        }
        changes.put(LEGACY_CLIPBOARD, null);
        return changes;
    }

    static void apply(SharedPreferences.Editor editor, Map<String, Object> changes) {
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            Object value = change.getValue();
            if (value == null) {
                editor.remove(change.getKey());
            } else if (value instanceof Boolean) {
                editor.putBoolean(change.getKey(), (Boolean) value);
            } else {
                editor.putString(change.getKey(), String.valueOf(value));
            }
        }
    }

    /** Runs {@link #migration(Map)} once; safe to call on every start. */
    static void migrate(SharedPreferences preferences) {
        Map<String, Object> changes = migration(preferences.getAll());
        if (changes.isEmpty()) return;
        SharedPreferences.Editor editor = preferences.edit();
        apply(editor, changes);
        editor.apply(); // the new values are readable right away; the file is written in the background
    }

    /**
     * When a source is chosen for the first time, the 🔑 button joins the card (before Start / Next). If the user
     * removed it from the card before, it stays removed.
     */
    static void addCardItemIfNew(SharedPreferences preferences) {
        if (Passphrases.SOURCE_NONE.equals(source(preferences.getAll()))) return;
        String items = preferences.getString("card_items", null);
        if (CardItems.mentionsPassphrase(items)) return;
        preferences.edit().putString("card_items", CardItems.withPassphrase(items)).apply();
    }

    static List<String> keys() {
        List<String> keys = new ArrayList<>();
        keys.add(SOURCE);
        keys.add(FALLBACK);
        keys.add(AUTO_COPY);
        return keys;
    }

    static Map<String, Object> defaults() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(SOURCE, Passphrases.SOURCE_NONE);
        defaults.put(FALLBACK, false);
        defaults.put(AUTO_COPY, false);
        return defaults;
    }
}
