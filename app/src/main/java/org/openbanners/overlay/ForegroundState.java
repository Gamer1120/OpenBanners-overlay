package org.openbanners.overlay;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The decision behind "Only show over Ingress", without Android types so it can be unit tested. Fed with the Usage
 * access activity events in order.
 *
 * <p>Since Android 10 every visible activity stays resumed, also in a pop-up (freeform) window or split screen, so
 * just looking at the last resumed app isn't enough: screach (10-25) opened a game as a pop-up over Ingress and
 * maximised it, and the overlay stayed over the game (maximising gives no new event and Ingress was resumed last).
 * So this keeps which activities are resumed right now, and hides the overlay as soon as any other app is resumed
 * next to Ingress; it stays hidden when that app is maximised (Ingress then pauses). It shows again once only
 * Ingress (or this app's settings) is left.
 *
 * <p>System UI, the launcher and the keyboard are ignored, so they don't hide it. Chat bubbles (an expanded bubble
 * is the chat app's activity) may count as another app and hide the overlay while open.
 */
class ForegroundState {
    private final String ingress;
    private final String self;
    private final Set<String> ignored = new HashSet<>();
    /** Resumed activities ("package/class") with their package. */
    private final Map<String, String> resumed = new LinkedHashMap<>();
    private String lastResumed;

    ForegroundState(String ingress, String self) {
        this.ingress = ingress;
        this.self = self;
    }

    /** Packages that never hide the overlay (system UI, launcher, keyboard); null is skipped. */
    void ignore(String packageName) {
        if (packageName != null) ignored.add(packageName);
    }

    void activityResumed(String packageName, String className) {
        if (packageName == null) return;
        resumed.put(packageName + "/" + className, packageName);
        lastResumed = packageName;
    }

    /** Paused or stopped. */
    void activityPaused(String packageName, String className) {
        if (packageName == null) return;
        resumed.remove(packageName + "/" + className);
    }

    /** The phone restarted or the screen went off: nothing is resumed any more. */
    void clear() {
        resumed.clear();
    }

    /** The last resumed package, or null when nothing seen yet. */
    String lastResumed() {
        return lastResumed;
    }

    boolean overlayWanted() {
        boolean ours = false;
        for (String packageName : resumed.values()) {
            if (ignored.contains(packageName)) continue;
            if (packageName.equals(ingress) || packageName.equals(self)) {
                ours = true;
            } else {
                return false;  // another app is open next to (or instead of) Ingress
            }
        }
        if (ours) return true;
        // Nothing that counts is resumed (home screen, recents, or not known yet): the last resumed app decides,
        // as before 44. Unknown shows the overlay.
        return lastResumed == null || lastResumed.equals(ingress) || lastResumed.equals(self);
    }
}
