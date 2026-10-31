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
            assertTrue(setting.key, constants.contains("<string name=\"" + setting.key + "\" translatable=\"false\">" + setting.key + "</string>"));
            if (setting.kind == SettingsBackup.Kind.CARD_ITEMS) {
                assertEquals(CardItems.DEFAULT, setting.defaultValue);
                continue;
            }
            Matcher element = Pattern.compile("<(\\w+)\\s(?:(?!/>|<).)*?app:key=\"@string/" + setting.key + "\"(?:(?!<).)*?/>", Pattern.DOTALL).matcher(screen);
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

    /** Every user preference on the Settings screen is in the backup, except the ones that mustn't be. */
    @Test
    public void everyPersistedPreferenceIsBackedUp() throws Exception {
        String screen = res("xml/root_preferences.xml");
        List<String> notBackedUp = Arrays.asList("location_enable", "notifications_enable"); // permissions: Android decides
        Matcher keys = Pattern.compile("<(SwitchPreferenceCompat|SwitchPreference|ListPreference)\\b[^>]*?app:key=\"@string/(\\w+)\"", Pattern.DOTALL).matcher(screen);
        int count = 0;
        while (keys.find()) {
            String key = keys.group(2);
            if (notBackedUp.contains(key)) continue;
            assertNotNull("add " + key + " to SettingsBackup.SETTINGS", SettingsBackup.find(key));
            count++;
        }
        assertEquals(SettingsBackup.SETTINGS.size() - 1, count); // + card_items, a plain Preference with its own dialog
        assertNotNull(SettingsBackup.find("card_items"));
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
        assertEquals(SettingsBackup.SETTINGS.size(), backup.size());
        assertEquals("north", backup.get("route_orientation"));
        assertEquals(true, backup.get("route_strip_enable"));
        assertEquals("1.0", backup.get("card_size"));
        assertEquals(true, backup.get("route_show_whole_banner"));
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
