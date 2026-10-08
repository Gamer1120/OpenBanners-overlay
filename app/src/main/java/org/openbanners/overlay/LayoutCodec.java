package org.openbanners.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.annotation.StringRes;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Exports and imports the overlay's look (mission card and route map settings) as one line of text, so people can
 * share layouts. Only these appearance settings are included; permissions and notifications are never touched.
 */
final class LayoutCodec {
    /** Marks the text as an OpenBanners Overlay layout. */
    static final String FORMAT = "openbanners-overlay-layout";
    private static final int VERSION = 1;

    /** One exported setting and how to check an imported value. */
    private static final class Setting {
        final int key;
        final boolean isBoolean;
        /** Allowed values (an array resource), or 0 for colours, or -1 for the card item list. */
        final int allowed;

        Setting(@StringRes int key, boolean isBoolean, int allowed) {
            this.key = key;
            this.isBoolean = isBoolean;
            this.allowed = allowed;
        }
    }

    private static final int COLOR = 0;
    private static final int CARD_ITEMS = -1;

    private static final List<Setting> SETTINGS = Arrays.asList(
            new Setting(R.string.card_items, false, CARD_ITEMS),
            new Setting(R.string.card_layout, false, R.array.card_layout_values),
            new Setting(R.string.card_size, false, R.array.card_size_values),
            new Setting(R.string.route_strip_enable, true, 0),
            new Setting(R.string.route_show_whole_banner, true, 0),
            new Setting(R.string.route_orientation, false, R.array.route_orientation_values),
            new Setting(R.string.route_compass_interval, false, R.array.route_compass_interval_values),
            new Setting(R.string.route_compass_always, true, 0),
            new Setting(R.string.route_compass_needle, false, R.array.route_compass_needle_values),
            new Setting(R.string.route_color_open, false, COLOR),
            new Setting(R.string.route_color_other, false, COLOR),
            new Setting(R.string.route_color_done, false, COLOR),
            new Setting(R.string.route_color_me, false, COLOR));

    private LayoutCodec() {
    }

    /** The current layout as a single line of JSON. Settings never changed from their default are left out. */
    static String export(Context context, SharedPreferences preferences) {
        try {
            JSONObject settings = new JSONObject();
            for (Setting setting : SETTINGS) {
                String key = context.getString(setting.key);
                if (!preferences.contains(key)) continue;
                if (setting.isBoolean) {
                    settings.put(key, preferences.getBoolean(key, false));
                } else {
                    settings.put(key, preferences.getString(key, ""));
                }
            }
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("settings", settings);
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether {@code text} contains a layout (it may be surrounded by other text, e.g. from a chat message). */
    static boolean looksLikeLayout(String text) {
        return text != null && text.contains(FORMAT) && text.contains("{");
    }

    /**
     * Applies a layout. Settings missing from it are reset to their defaults, so the result looks like the
     * exporter's overlay. Throws {@link IllegalArgumentException} with a readable message if the text isn't valid.
     */
    static void importLayout(Context context, SharedPreferences preferences, String text) {
        if (text == null || !text.contains("{")) throw new IllegalArgumentException("No layout found in the text.");
        JSONObject root;
        try {
            root = new JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
        } catch (JSONException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("The layout text is incomplete or damaged.");
        }
        if (!FORMAT.equals(root.optString("format"))) throw new IllegalArgumentException("This isn't an OpenBanners Overlay layout.");
        if (root.optInt("version", 0) > VERSION) throw new IllegalArgumentException("This layout needs a newer version of the app.");
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) throw new IllegalArgumentException("The layout contains no settings.");

        SharedPreferences.Editor editor = preferences.edit();
        for (Setting setting : SETTINGS) {
            editor.remove(context.getString(setting.key));
        }
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Setting setting = find(context, key);
            if (setting == null) continue; // unknown (e.g. from a newer version): skip
            Object value = settings.opt(key);
            if (setting.isBoolean) {
                if (!(value instanceof Boolean)) throw invalid(key);
                editor.putBoolean(key, (Boolean) value);
            } else {
                if (!(value instanceof String)) throw invalid(key);
                String string = (String) value;
                if (!isValid(context, setting, string)) throw invalid(key);
                editor.putString(key, setting.allowed == CARD_ITEMS ? String.join(",", CardItems.parse(string)) : string);
            }
        }
        editor.apply();
    }

    private static Setting find(Context context, String key) {
        for (Setting setting : SETTINGS) {
            if (context.getString(setting.key).equals(key)) return setting;
        }
        return null;
    }

    private static boolean isValid(Context context, Setting setting, String value) {
        if (setting.allowed == CARD_ITEMS) {
            for (String item : value.split(",")) {
                if (!CardItems.ALL.contains(item.trim())) return false;
            }
            return true;
        }
        if (setting.allowed == COLOR) {
            try {
                Color.parseColor(value);
                return value.startsWith("#");
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return Arrays.asList(context.getResources().getStringArray(setting.allowed)).contains(value);
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("The layout has an invalid value for \"" + key + "\".");
    }
}
