package org.openbanners.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.openbanners.overlay.api.Banner;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.PassphraseApi;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Crowd-sourced passphrases from openbanners.org, per Bannergress banner: downloaded when a banner (or each banner of a
 * Mission Day plan) loads, only if that source is chosen, and kept with the banner. The last download of each banner
 * is also saved in the app's cache folder, so the passphrases are still there without network. A failed download
 * just means "not known".
 */
final class PassphraseStore {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, PassphraseApi.BannerAnswers> answers = new ConcurrentHashMap<>();
    /** Downloads running now. */
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private static final Set<Runnable> listeners = new HashSet<>();

    private PassphraseStore() {
    }

    /** The answers for a banner, or null if not downloaded (yet) and not in the cache. */
    static PassphraseApi.BannerAnswers get(String bannerId) {
        return bannerId == null ? null : answers.get(bannerId);
    }

    /** Whether the answers for {@code bannerId} are being downloaded right now. */
    static boolean loading(String bannerId) {
        return bannerId != null && inFlight.contains(bannerId);
    }

    /** Called on the main thread when a download finished (with or without new answers). */
    static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** The Bannergress banners a banner or plan is made of. */
    static Set<String> bannerIds(Banner banner) {
        Set<String> ids = new LinkedHashSet<>();
        if (banner == null || banner.missions == null) return ids;
        for (Mission mission : banner.missions.values()) {
            if (mission != null && mission.sourceBannerId != null) ids.add(mission.sourceBannerId);
        }
        if (ids.isEmpty() && banner.id != null) ids.add(banner.id);
        return ids;
    }

    /**
     * Downloads the answers of every banner in {@code banner}. {@code onlyMissing}: skip banners that already have
     * answers (e.g. when the source is switched on while a banner is open).
     */
    static void load(Context context, Banner banner, boolean onlyMissing) {
        File cacheDir = new File(context.getApplicationContext().getCacheDir(), "passphrases");
        for (String bannerId : bannerIds(banner)) {
            if (onlyMissing && answers.containsKey(bannerId)) continue;
            if (!inFlight.add(bannerId)) continue;
            executor.execute(() -> {
                File cache = cacheFile(cacheDir, bannerId);
                PassphraseApi.BannerAnswers result = null;
                try {
                    String json = PassphraseApi.fetchJson(bannerId);
                    result = PassphraseApi.parse(json);
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        cacheDir.mkdirs();
                        Files.write(cache.toPath(), json.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // Only the offline copy is missing.
                    }
                } catch (Exception e) {
                    // Offline or the site is down: use the last download, if any.
                    try {
                        if (cache.exists()) result = PassphraseApi.parse(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                        // No usable copy: the passphrases count as not known.
                    }
                }
                if (result != null) answers.put(bannerId, result);
                inFlight.remove(bannerId);
                // Also after a failure: whoever waits (auto-copy) can go on with "not known".
                main.post(() -> {
                    for (Runnable listener : new HashSet<>(listeners)) listener.run();
                });
            });
        }
    }

    private static File cacheFile(File dir, String bannerId) {
        String name;
        try {
            name = URLEncoder.encode(bannerId, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            name = Integer.toHexString(bannerId.hashCode());
        }
        return new File(dir, name + ".json");
    }
}
