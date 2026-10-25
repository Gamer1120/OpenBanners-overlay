package org.openbanners.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * "Only show over Ingress" with pop-up windows and split screen (screach 10-25: a game opened as a pop-up over
 * Ingress and then maximised kept the overlay on top).
 */
public class ForegroundStateTest {
    private static final String INGRESS = ForegroundApp.INGRESS;
    private static final String INGRESS_ACTIVITY = "com.nianticproject.ingress.NemesisActivity";
    private static final String SELF = "org.openbanners.overlay";
    private static final String GAME = "com.example.game";
    private static final String GAME_ACTIVITY = "com.example.game.MainActivity";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String LAUNCHER = "com.example.launcher";
    private static final String KEYBOARD = "com.example.keyboard";

    private ForegroundState state;

    @Before
    public void setUp() {
        state = new ForegroundState(INGRESS, SELF);
        state.ignore(SYSTEM_UI);
        state.ignore(LAUNCHER);
        state.ignore(KEYBOARD);
        state.ignore(null);
    }

    @Test
    public void nothingSeenShows() {
        assertTrue(state.overlayWanted());
    }

    @Test
    public void ingressAloneShows() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        assertTrue(state.overlayWanted());
    }

    @Test
    public void popUpAppOverIngressHides() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(GAME, GAME_ACTIVITY);
        assertFalse(state.overlayWanted());
    }

    @Test
    public void ingressResumedAfterPopUpStillHides() {
        // Tapping Ingress next to the pop-up (or Ingress resuming last): the pop-up is still open.
        state.activityResumed(GAME, GAME_ACTIVITY);
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        assertFalse(state.overlayWanted());
    }

    @Test
    public void popUpMaximisedHides() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(GAME, GAME_ACTIVITY);
        state.activityPaused(INGRESS, INGRESS_ACTIVITY);  // the maximised game covers Ingress
        assertFalse(state.overlayWanted());
    }

    @Test
    public void popUpMaximisedWithoutIngressEventHides() {
        // Ingress resumed last and never paused (no event on maximising), the game is still resumed.
        state.activityResumed(GAME, GAME_ACTIVITY);
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        assertFalse(state.overlayWanted());
    }

    @Test
    public void popUpClosedShows() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(GAME, GAME_ACTIVITY);
        state.activityPaused(GAME, GAME_ACTIVITY);
        assertTrue(state.overlayWanted());
    }

    @Test
    public void popUpMaximisedThenClosedBackToIngressShows() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(GAME, GAME_ACTIVITY);
        state.activityPaused(INGRESS, INGRESS_ACTIVITY);
        state.activityPaused(GAME, GAME_ACTIVITY);
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        assertTrue(state.overlayWanted());
    }

    @Test
    public void splitScreenHides() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(GAME, GAME_ACTIVITY);
        assertFalse(state.overlayWanted());
        state.activityPaused(GAME, GAME_ACTIVITY);
        assertTrue(state.overlayWanted());
    }

    @Test
    public void systemUiLauncherAndKeyboardIgnored() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(SYSTEM_UI, "com.android.systemui.SomeDialog");
        state.activityResumed(LAUNCHER, "com.example.launcher.Recents");
        state.activityResumed(KEYBOARD, "com.example.keyboard.Settings");
        assertTrue(state.overlayWanted());
    }

    @Test
    public void ownSettingsNextToIngressShows() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(SELF, SELF + ".SettingsActivity");
        assertTrue(state.overlayWanted());
    }

    @Test
    public void ownSettingsAloneShows() {
        state.activityResumed(SELF, SELF + ".SettingsActivity");
        assertTrue(state.overlayWanted());
    }

    @Test
    public void otherAppFullScreenHides() {
        // Switching apps, as before 44.
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityPaused(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(GAME, GAME_ACTIVITY);
        assertFalse(state.overlayWanted());
    }

    @Test
    public void homeScreenHides() {
        // Only the launcher resumed: the last resumed app decides, as before 44.
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityPaused(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(LAUNCHER, "com.example.launcher.Home");
        assertFalse(state.overlayWanted());
    }

    @Test
    public void ingressSecondActivityKeepsShowing() {
        // Ingress switching activities: the new one resumes before the old one pauses.
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(INGRESS, "com.nianticproject.ingress.Other");
        state.activityPaused(INGRESS, INGRESS_ACTIVITY);
        assertTrue(state.overlayWanted());
    }

    @Test
    public void clearForgetsResumedApps() {
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        state.activityResumed(GAME, GAME_ACTIVITY);
        state.clear();
        state.activityResumed(INGRESS, INGRESS_ACTIVITY);
        assertTrue(state.overlayWanted());
    }
}
