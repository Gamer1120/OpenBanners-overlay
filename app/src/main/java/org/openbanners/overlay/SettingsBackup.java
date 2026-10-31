package org.openbanners.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Which settings "Back up to OpenBanners" saves and how a backup is restored (Settings → OpenBanners backup). Pure
 * Java so it can be unit-tested; {@link SettingsActivity} does the SharedPreferences and the dialogs.
 * <p>
 * Every user preference is backed up with its current value (or its default), so a backup is complete. Not backed
 * up: permission switches (location, notifications: Android decides), the route map safety confirmation (asked again
 * on the restoring phone), one-time hints, overlay progress, and the OpenBanners sign-in state.
 * <p>
 * Restoring copes with other app versions: keys this version doesn't know are skipped, settings missing from the
 * backup (an older app) get their default, and a value this version doesn't accept keeps the default too.
 * Adding a preference to the app? Add it to {@link #SETTINGS} (SettingsBackupTest checks the keys against
 * constants.xml and arrays.xml).
 */
final class SettingsBackup {
    /** Version of the backup body sent to openbanners.org (server/overlay-backup.mjs). */
    static final int FORMAT = 1;

    enum Kind { BOOLEAN, CHOICE, COLOR, CARD_ITEMS }

    static final class Setting {
        final String key;
        final Kind kind;
        final Object defaultValue;
        /** Allowed values for {@link Kind#CHOICE}. */
        final List<String> choices;

        Setting(String key, Kind kind, Object defaultValue, String... choices) {
            this.key = key;
            this.kind = kind;
            this.defaultValue = defaultValue;
            this.choices = Collections.unmodifiableList(Arrays.asList(choices));
        }

        boolean accepts(Object value) {
            switch (kind) {
                case BOOLEAN:
                    return value instanceof Boolean;
                case CHOICE:
                    return value instanceof String && choices.contains(value);
                case COLOR:
                    return value instanceof String && COLOR_PATTERN.matcher((String) value).matches();
                default:
                    return value instanceof String && CardItems.isValid((String) value);
            }
        }

        Object normalize(Object value) {
            return kind == Kind.CARD_ITEMS ? CardItems.normalize((String) value) : value;
        }
    }

    private static final Pattern COLOR_PATTERN = Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");

    /** In the order the Settings screen shows them. Defaults match root_preferences.xml. */
    static final List<Setting> SETTINGS = Collections.unmodifiableList(Arrays.asList(
            new Setting("ingress_only", Kind.BOOLEAN, false),
            new Setting("card_items", Kind.CARD_ITEMS, CardItems.DEFAULT),
            new Setting("card_layout", Kind.CHOICE, "two_rows", "two_rows", "wide", "tall"),
            new Setting("card_size", Kind.CHOICE, "1.0", "0.75", "1.0", "1.25", "1.5"),
            new Setting("route_strip_enable", Kind.BOOLEAN, false),
            new Setting("route_show_whole_banner", Kind.BOOLEAN, true),
            new Setting("route_hack_range", Kind.BOOLEAN, false),
            new Setting("route_orientation", Kind.CHOICE, "compass", "north", "compass"),
            new Setting("route_compass_always", Kind.BOOLEAN, false),
            new Setting("route_compass_needle", Kind.CHOICE, "north", "north", "heading"),
            new Setting("route_compass_interval", Kind.CHOICE, "1000", "0", "500", "1000", "2000", "5000"),
            new Setting("route_compass_step", Kind.CHOICE, "15", "5", "10", "15", "20", "30", "45", "90"),
            new Setting("route_color_open", Kind.COLOR, "#FFEA00"),
            new Setting("route_color_other", Kind.COLOR, "#FFFFFF"),
            new Setting("route_color_done", Kind.COLOR, "#969696"),
            new Setting("route_color_me", Kind.COLOR, "#00E5FF"),
            new Setting("clipboard_enable", Kind.BOOLEAN, false),
            new Setting("bannergress_links", Kind.BOOLEAN, false)));

    private SettingsBackup() {
    }

    static Setting find(String key) {
        for (Setting setting : SETTINGS) {
            if (setting.key.equals(key)) return setting;
        }
        return null;
    }

    /** The value the app uses now: the stored one if it's valid, else the default. */
    static Object effective(Setting setting, Map<String, ?> stored) {
        Object value = stored.get(setting.key);
        return value != null && setting.accepts(value) ? setting.normalize(value) : setting.defaultValue;
    }

    /** The backup's settings: every setting with its current value. */
    static Map<String, Object> collect(Map<String, ?> stored) {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Setting setting : SETTINGS) settings.put(setting.key, effective(setting, stored));
        return settings;
    }

    /** One setting a restore changes. */
    static final class Change {
        final Setting setting;
        final Object from;
        final Object to;

        Change(Setting setting, Object from, Object to) {
            this.setting = setting;
            this.from = from;
            this.to = to;
        }
    }

    /** What restoring a backup would do. {@link #values} has every setting: the value to store. */
    static final class Plan {
        final Map<String, Object> values = new LinkedHashMap<>();
        final List<Change> changes = new ArrayList<>();
        /** Keys in the backup this version doesn't know (from a newer app). */
        final List<String> unknown = new ArrayList<>();
        /** Known keys with a value this version doesn't accept: they keep the default. */
        final List<String> invalid = new ArrayList<>();
    }

    static Plan plan(Map<String, ?> backup, Map<String, ?> stored) {
        Plan plan = new Plan();
        for (String key : backup.keySet()) {
            if (find(key) == null) plan.unknown.add(key);
        }
        Collections.sort(plan.unknown);
        for (Setting setting : SETTINGS) {
            Object value = backup.get(setting.key);
            Object target;
            if (value == null) {
                target = setting.defaultValue; // missing: an older app, or never set
            } else if (setting.accepts(value)) {
                target = setting.normalize(value);
            } else {
                plan.invalid.add(setting.key);
                target = setting.defaultValue;
            }
            plan.values.put(setting.key, target);
            Object current = effective(setting, stored);
            if (!Objects.equals(current, target)) plan.changes.add(new Change(setting, current, target));
        }
        return plan;
    }
}
