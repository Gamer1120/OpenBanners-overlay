package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Android 14+ crashed the app when starting the overlay without location permission (v47). */
public class ForegroundModeTest {
    @Test
    public void beforeAndroid10UsesTheManifest() {
        assertEquals(ForegroundMode.LEGACY, ForegroundMode.choose(26, false));
        assertEquals(ForegroundMode.LEGACY, ForegroundMode.choose(28, true));
    }

    @Test
    public void android10To13KeepTheLocationTypeWithoutPermission() {
        assertEquals(ForegroundMode.LOCATION, ForegroundMode.choose(29, false));
        assertEquals(ForegroundMode.LOCATION, ForegroundMode.choose(33, false));
        assertEquals(ForegroundMode.LOCATION, ForegroundMode.choose(33, true));
    }

    @Test
    public void android14AndNewerNeedThePermissionForTheLocationType() {
        assertEquals(ForegroundMode.NOT_FOREGROUND, ForegroundMode.choose(34, false));
        assertEquals(ForegroundMode.NOT_FOREGROUND, ForegroundMode.choose(36, false));
        assertEquals(ForegroundMode.LOCATION, ForegroundMode.choose(34, true));
        assertEquals(ForegroundMode.LOCATION, ForegroundMode.choose(36, true));
    }
}
