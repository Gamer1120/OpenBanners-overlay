package org.openbanners.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RouteDisclaimerTest {
    @Test
    public void upgradeWithRouteMapOnSwitchesItOff() {
        // Installs from before the disclaimer have no accepted version (0).
        assertTrue(RouteDisclaimer.mustSwitchOff(true, 0));
        assertFalse(RouteDisclaimer.isEffectivelyEnabled(true, 0));
    }

    @Test
    public void routeMapOffStaysOff() {
        assertFalse(RouteDisclaimer.mustSwitchOff(false, 0));
        assertFalse(RouteDisclaimer.isEffectivelyEnabled(false, RouteDisclaimer.CURRENT_VERSION));
    }

    @Test
    public void acceptedCurrentVersionKeepsRouteMapOn() {
        assertFalse(RouteDisclaimer.mustSwitchOff(true, RouteDisclaimer.CURRENT_VERSION));
        assertTrue(RouteDisclaimer.isEffectivelyEnabled(true, RouteDisclaimer.CURRENT_VERSION));
    }

    @Test
    public void olderAcceptedVersionAsksAgain() {
        int older = RouteDisclaimer.CURRENT_VERSION - 1;
        assertTrue(RouteDisclaimer.mustSwitchOff(true, older));
        assertFalse(RouteDisclaimer.isAccepted(older));
    }
}
