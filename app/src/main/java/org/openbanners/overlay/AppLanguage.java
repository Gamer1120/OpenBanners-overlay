package org.openbanners.overlay;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.util.Arrays;
import java.util.Locale;

/**
 * Settings → Language: the app's own language (Michael 2026-11-02), with the languages of openbanners.org.
 * <p>
 * Android 13+ keeps the choice per app (also settable in Android's app settings, see xml/locales_config) and applies
 * it to the whole app, the overlay service included. On older versions AppCompat applies it to its activities
 * (autoStoreLocales in the manifest) and {@link #wrap} to everything else (overlay, notification, other activities);
 * the tag is also kept in the preferences under {@link #KEY} for that. Not part of layouts or backups.
 */
final class AppLanguage {
    /** Follow the phone's language. */
    static final String SYSTEM = "system";
    static final String KEY = "app_language";

    /** Language tags offered, in the order of openbanners.org (English first). Same as arrays.xml language_values. */
    static final String[] TAGS = {"en", "nl", "de", "fr", "es", "it", "pt", "pl", "cs", "ru", "uk", "ja", "zh-Hans", "zh-Hant", "ko", "id"};

    private AppLanguage() {
    }

    /** The chosen tag ({@link #TAGS}) or {@link #SYSTEM}. */
    static String current(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Straight from Android: AppCompatDelegate only knows it while an AppCompat activity exists, and the
            // user may have changed it in Android's app settings.
            LocaleManager localeManager = context.getSystemService(LocaleManager.class);
            LocaleList locales = localeManager == null ? LocaleList.getEmptyLocaleList() : localeManager.getApplicationLocales();
            return locales.isEmpty() ? SYSTEM : tagFor(locales.get(0));
        }
        return stored(context);
    }

    /** Applies a choice from Settings → Language. AppCompat recreates the open activities. */
    static void set(Context context, String tag) {
        if (!SYSTEM.equals(tag) && !Arrays.asList(TAGS).contains(tag)) tag = SYSTEM;
        preferences(context).edit().putString(KEY, tag).apply();
        AppCompatDelegate.setApplicationLocales(SYSTEM.equals(tag)
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
    }

    /**
     * {@code base}, or a context with the chosen language on Android 12 and older (13+ does this itself).
     * For services, receivers and plain activities; AppCompat activities don't need it.
     */
    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base;
        String tag = stored(base);
        if (SYSTEM.equals(tag)) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(tag)));
        return base.createConfigurationContext(configuration);
    }

    /** The offered tag for a locale (zh-TW → zh-Hant, in → id, pt-BR → pt), or {@link #SYSTEM} if none fits. */
    static String tagFor(Locale locale) {
        if (locale == null) return SYSTEM;
        String language = locale.getLanguage();
        if ("in".equals(language)) language = "id";
        if ("zh".equals(language)) {
            String script = locale.getScript();
            String country = locale.getCountry();
            boolean traditional = "Hant".equals(script)
                    || (script.isEmpty() && ("TW".equals(country) || "HK".equals(country) || "MO".equals(country)));
            return traditional ? "zh-Hant" : "zh-Hans";
        }
        return Arrays.asList(TAGS).contains(language) ? language : SYSTEM;
    }

    private static String stored(Context context) {
        String tag = preferences(context).getString(KEY, SYSTEM);
        return tag != null && Arrays.asList(TAGS).contains(tag) ? tag : SYSTEM;
    }

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
