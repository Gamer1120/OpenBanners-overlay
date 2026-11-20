package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SettingsBackupTest {
    private static String res(String path) throws Exception {
        File file = new File("src/main/res/" + path);
        if (!file.exists()) file = new File("app/src/main/res/" + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> array(String xml, String name) {
        Matcher block = Pattern.compile("<string-array name=\"" + name + "\"[^>]*>(.*?)</string-array>", Pattern.DOTALL).matcher(xml);
        assertTrue(name, block.find());
        List<String> items = new ArrayList<>();
        Matcher item = Pattern.compile("<item>(.*?)</item>").matcher(block.group(1));
        while (item.find()) items.add(item.group(1));
        return items;
    }

    /** Keys, defaults and choices must match the Settings screen, or a backup would miss or mangle a setting. */
    @Test
    public void settingsMatchTheSettingsScreen() throws Exception {
        String constants = res("values/constants.xml");
        String arrays = res("values/arrays.xml");
        String screen = res("xml/root_preferences.xml");
        for (SettingsBackup.Setting setting : SettingsBackup.SETTINGS) {
            if (setting.key.equals(AppLanguage.KEY)) {
                // A literal key on the screen (not a constants.xml string): checked below with its values.
                assertTrue(screen.contains("app:key=\"" + AppLanguage.KEY + "\""));
            } else assertTrue(setting.key, constants.contains("<string name=\"" + setting.key + "\" translatable=\"false\">" + setting.key + "</string>"));
            if (setting.kind == SettingsBackup.Kind.CARD_ITEMS) {
                assertEquals(CardItems.DEFAULT, setting.defaultValue);
                continue;
            }
            if (setting.key.equals(PassphraseSettings.SOURCE)) {
                // Three radio buttons on the screen, one per choice (SettingsActivity.setUpPassphrases).
                assertEquals(Passphrases.SOURCE_NONE, setting.defaultValue);
                for (String choice : setting.choices) assertTrue(choice, screen.contains("app:key=\"passphrase_source_" + choice + "\""));
                continue;
            }
            Matcher element = Pattern.compile("<([\\w.]+)\\s(?:(?!/>|<).)*?app:key=\"(?:@string/)?" + setting.key + "\"(?:(?!<).)*?/>", Pattern.DOTALL).matcher(screen);
            assertTrue("on the Settings screen: " + setting.key, element.find());
            String xml = element.group();
            Matcher def = Pattern.compile("app:defaultValue=\"([^\"]*)\"").matcher(xml);
            assertTrue("default for " + setting.key, def.find());
            assertEquals(setting.key, def.group(1), String.valueOf(setting.defaultValue));
            Matcher values = Pattern.compile("app:entryValues=\"@array/(\\w+)\"").matcher(xml);
            if (setting.kind == SettingsBackup.Kind.CHOICE) {
                assertTrue(setting.key, values.find());
                assertEquals(setting.key, array(arrays, values.group(1)), setting.choices);
            }
            if (setting.kind == SettingsBackup.Kind.COLOR) {
                assertTrue(setting.key, values.find());
                for (String color : array(arrays, values.group(1))) assertTrue(color, setting.accepts(color));
            }
        }
    }

    /** Settings → Language: every value the list offers ("system" included) and nothing else. */
    @Test
    public void languageIsBackedUpWithTheListsValues() throws Exception {
        SettingsBackup.Setting language = SettingsBackup.find(AppLanguage.KEY);
        assertNotNull(language);
        assertEquals(SettingsBackup.Kind.CHOICE, language.kind);
        assertEquals(AppLanguage.SYSTEM, language.defaultValue);
        List<String> values = array(res("values/arrays.xml"), "language_values");
        assertEquals(values, language.choices);
        assertEquals("system", values.get(0));
        assertEquals(Arrays.asList(AppLanguage.TAGS), values.subList(1, values.size()));
    }

    /**
     * Preferences on the Settings screen that aren't in the backup, on purpose. A new preference must go either in
     * SettingsBackup.SETTINGS or here, so none is left out of backups by accident (as the language was until 60).
     */
    private static final List<String> NOT_BACKED_UP = Arrays.asList(
            "location_enable", "notifications_enable", // permissions: Android decides
            "introduction", // text
            "layout_export", "layout_import", "openbanners_backup", "openbanners_restore", // actions
            "notification_progress", "notification_step_in_range", // open Android's notification settings
            "bannergress_links_status", // shows the state of bannergress_links
            "licenses", "privacy", "source", // links
            "version"); // shows the installed version

    /** The three radio buttons that together are passphrase_source. */
    private static final List<String> PASSPHRASE_SOURCE_BUTTONS = Arrays.asList(
            "passphrase_source_none", "passphrase_source_mission", "passphrase_source_openbanners");

    /** Every key on the Settings screen is either backed up or on the list of what isn't. */
    @Test
    public void everyPreferenceIsBackedUpOrExplicitlyNot() throws Exception {
        String screen = res("xml/root_preferences.xml");
        Matcher keys = Pattern.compile("app:key=\"(?:@string/)?([\\w.]+)\"").matcher(screen);
        List<String> onScreen = new ArrayList<>();
        while (keys.find()) {
            String key = keys.group(1);
            onScreen.add(key);
            if (NOT_BACKED_UP.contains(key)) continue;
            if (PASSPHRASE_SOURCE_BUTTONS.contains(key)) {
                assertNotNull(SettingsBackup.find(PassphraseSettings.SOURCE));
                continue;
            }
            assertNotNull("add " + key + " to SettingsBackup.SETTINGS, or to NOT_BACKED_UP if it mustn't be backed up",
                    SettingsBackup.find(key));
        }
        assertTrue(onScreen.size() > 20); // the pattern still finds the keys
        for (String key : NOT_BACKED_UP) assertTrue("no longer on the Settings screen: " + key, onScreen.contains(key));
        for (SettingsBackup.Setting setting : SettingsBackup.SETTINGS) {
            assertTrue("not on the Settings screen: " + setting.key, onScreen.contains(setting.key)
                    || (setting.key.equals(PassphraseSettings.SOURCE) && onScreen.containsAll(PASSPHRASE_SOURCE_BUTTONS)));
            assertFalse(setting.key, NOT_BACKED_UP.contains(setting.key));
        }
    }

    @Test
    public void languageIsBackedUpAndRestored() {
        Map<String, Object> phone = new HashMap<>();
        phone.put(AppLanguage.KEY, "zh-Hant"); // SettingsActivity puts AppLanguage.current here
        Map<String, Object> backup = SettingsBackup.collect(phone);
        assertEquals("zh-Hant", backup.get(AppLanguage.KEY));
        assertEquals("system", SettingsBackup.collect(new HashMap<>()).get(AppLanguage.KEY));

        Map<String, Object> other = new HashMap<>();
        other.put(AppLanguage.KEY, "nl");
        SettingsBackup.Plan plan = SettingsBackup.plan(backup, other);
        assertEquals("zh-Hant", plan.values.get(AppLanguage.KEY));
        assertEquals(1, plan.changes.size());
        assertEquals(AppLanguage.KEY, plan.changes.get(0).setting.key);
        assertEquals("nl", plan.changes.get(0).from);
        assertEquals("zh-Hant", plan.changes.get(0).to);

        // Back to following the phone.
        Map<String, Object> system = new HashMap<>();
        system.put(AppLanguage.KEY, "system");
        assertEquals("system", SettingsBackup.plan(system, other).values.get(AppLanguage.KEY));
    }

    /** Backups from before 60 have no language: restoring one keeps the language the phone has now. */
    @Test
    public void backupWithoutLanguageKeepsTheCurrentOne() {
        Map<String, Object> current = new HashMap<>();
        current.put(AppLanguage.KEY, "de");
        Map<String, Object> old = SettingsBackup.collect(current);
        old.remove(AppLanguage.KEY);
        SettingsBackup.Plan plan = SettingsBackup.plan(old, current);
        assertEquals("de", plan.values.get(AppLanguage.KEY));
        assertTrue(plan.changes.isEmpty());
        assertTrue(plan.invalid.isEmpty());
        assertTrue(plan.unknown.isEmpty());
    }

    /** A language this version doesn't offer (a newer app's) follows the phone, like other invalid values. */
    @Test
    public void unknownLanguageFollowsThePhone() {
        Map<String, Object> current = new HashMap<>();
        current.put(AppLanguage.KEY, "de");
        Map<String, Object> backup = new HashMap<>();
        backup.put(AppLanguage.KEY, "tr");
        SettingsBackup.Plan plan = SettingsBackup.plan(backup, current);
        assertEquals("system", plan.values.get(AppLanguage.KEY));
        assertEquals(Arrays.asList(AppLanguage.KEY), plan.invalid);
    }

    @Test
    public void collectsEveryValueWithDefaults() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("route_orientation", "north");
        stored.put("route_strip_enable", true);
        stored.put("card_size", "9"); // not a choice: the default is used
        stored.put("location_hint_shown", true); // not a setting
        stored.put("route_disclaimer_accepted_version", 1); // asked again on the restoring phone
        Map<String, Object> backup = SettingsBackup.collect(stored);
        assertEquals(SettingsBackup.SETTINGS.size() + 1, backup.size()); // + the old clipboard switch for older apps
        assertEquals(false, backup.get("clipboard_enable"));
        assertEquals("north", backup.get("route_orientation"));
        assertEquals(true, backup.get("route_strip_enable"));
        assertEquals("1.0", backup.get("card_size"));
        assertEquals(true, backup.get("route_show_whole_banner"));
        assertEquals("system", backup.get(AppLanguage.KEY));
        assertFalse(backup.containsKey("location_hint_shown"));
        assertFalse(backup.containsKey("route_disclaimer_accepted_version"));
    }

    @Test
    public void restoreListsChangesSkipsUnknownKeysAndDefaultsMissingOnes() {
        Map<String, Object> current = new HashMap<>();
        current.put("route_orientation", "compass");
        current.put("card_layout", "wide");
        current.put("route_color_me", "#FF1744");
        // A backup from a newer app: one unknown key, one value this version doesn't know, card_layout missing.
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("route_orientation", "north");
        backup.put("route_compass_step", "7");
        backup.put("hologram_mode", true);
        backup.put("ingress_only", true);
        backup.put("route_color_me", "#FF1744");
        SettingsBackup.Plan plan = SettingsBackup.plan(backup, current);
        assertEquals(Arrays.asList("hologram_mode"), plan.unknown);
        assertEquals(Arrays.asList("route_compass_step"), plan.invalid);
        assertEquals("north", plan.values.get("route_orientation"));
        assertEquals("two_rows", plan.values.get("card_layout"));
        assertEquals("15", plan.values.get("route_compass_step"));
        assertEquals(true, plan.values.get("ingress_only"));
        assertEquals("#FF1744", plan.values.get("route_color_me"));
        List<String> changed = new ArrayList<>();
        for (SettingsBackup.Change change : plan.changes) changed.add(change.setting.key + ":" + change.from + ">" + change.to);
        assertEquals(Arrays.asList("ingress_only:false>true", "card_layout:wide>two_rows", "route_orientation:compass>north"), changed);
        assertFalse(plan.values.containsKey("hologram_mode"));
    }

    @Test
    public void restoringYourOwnBackupChangesNothing() {
        Map<String, Object> current = new HashMap<>();
        current.put("route_orientation", "north");
        current.put("card_items", CardItems.DEFAULT);
        SettingsBackup.Plan plan = SettingsBackup.plan(SettingsBackup.collect(current), current);
        assertTrue(plan.changes.isEmpty());
        assertTrue(plan.unknown.isEmpty());
        assertTrue(plan.invalid.isEmpty());
    }

    @Test
    public void oldClipboardSwitchIsBackedUpAsPassphraseSettings() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("clipboard_enable", true); // never opened version 52's settings yet
        Map<String, Object> backup = SettingsBackup.collect(stored);
        assertEquals("mission", backup.get("passphrase_source"));
        assertEquals(true, backup.get("passphrase_autocopy"));
        assertEquals(false, backup.get("passphrase_fallback"));
        // Older apps: their switch, and card items they can read.
        assertEquals(true, backup.get("clipboard_enable"));
        assertEquals(CardItems.DEFAULT, backup.get("card_items"));
        assertEquals("minus,counter,plus,passphrase,next,close,done", backup.get(SettingsBackup.CARD_ITEMS_WITH_PASSPHRASE));
    }

    @Test
    public void backupFromBefore52WithClipboardOnRestoresMissionNumberAndAutoCopy() {
        Map<String, Object> backup = new HashMap<>();
        backup.put("clipboard_enable", true);
        backup.put("card_items", "minus,counter,plus,next");
        SettingsBackup.Plan plan = SettingsBackup.plan(backup, new HashMap<>());
        assertTrue(plan.unknown.isEmpty()); // clipboard_enable is understood, not "from a newer app"
        assertEquals("mission", plan.values.get("passphrase_source"));
        assertEquals(true, plan.values.get("passphrase_autocopy"));
        assertEquals(false, plan.values.get("passphrase_fallback"));
        assertEquals("minus,counter,plus,passphrase,next,close,done", plan.values.get("card_items"));
        assertFalse(plan.values.containsKey("clipboard_enable"));
    }

    @Test
    public void backupFromBefore52WithClipboardOffKeepsPassphrasesOff() {
        Map<String, Object> backup = new HashMap<>();
        backup.put("clipboard_enable", false);
        SettingsBackup.Plan plan = SettingsBackup.plan(backup, new HashMap<>());
        assertTrue(plan.unknown.isEmpty());
        assertEquals("none", plan.values.get("passphrase_source"));
        assertEquals(false, plan.values.get("passphrase_autocopy"));
        assertEquals(CardItems.DEFAULT, plan.values.get("card_items"));
        assertTrue(plan.changes.isEmpty());
    }

    @Test
    public void newBackupRestoresTheCardWithThePassphraseButton() {
        Map<String, Object> current = new HashMap<>();
        current.put("passphrase_source", "openbanners");
        current.put("passphrase_fallback", true);
        current.put("card_items", "passphrase,minus,counter,plus,next,close,done");
        Map<String, Object> backup = SettingsBackup.collect(current);
        assertEquals(false, backup.get("clipboard_enable"));
        assertEquals(CardItems.DEFAULT, backup.get("card_items"));
        SettingsBackup.Plan plan = SettingsBackup.plan(backup, new HashMap<>());
        assertEquals("passphrase,minus,counter,plus,next,close,done", plan.values.get("card_items"));
        assertEquals("openbanners", plan.values.get("passphrase_source"));
        assertEquals(true, plan.values.get("passphrase_fallback"));
        assertTrue(plan.unknown.isEmpty());
        assertTrue(SettingsBackup.plan(backup, current).changes.isEmpty());
    }

    @Test
    public void wrongTypesAreNotRestored() {
        Map<String, Object> backup = new HashMap<>();
        backup.put("route_strip_enable", "true");
        backup.put("route_color_open", "yellow");
        backup.put("card_items", "nonsense,items");
        SettingsBackup.Plan plan = SettingsBackup.plan(backup, new HashMap<>());
        assertEquals(false, plan.values.get("route_strip_enable"));
        assertEquals("#FFEA00", plan.values.get("route_color_open"));
        assertEquals(CardItems.DEFAULT, plan.values.get("card_items"));
        assertEquals(3, plan.invalid.size());
    }
}
