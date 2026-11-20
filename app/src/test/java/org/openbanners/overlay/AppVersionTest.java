package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AppVersionTest {
    @Test
    public void playAndApkBuildsAreToldApartByTheirCertificate() {
        assertEquals("60 \u00B7 Google Play", AppVersion.label("60",
                "8F:44:96:03:83:88:A0:15:73:97:E7:0C:EB:23:03:0E:EF:1E:78:C2:DC:D0:F2:39:58:F0:45:6E:37:DD:9B:BB"));
        assertEquals("60 \u00B7 GitHub / F-Droid", AppVersion.label("60", AppVersion.OPENBANNERS_CERT));
        assertEquals("60", AppVersion.label("60", "00ff"));
        assertEquals("60", AppVersion.label("60", null));
        assertNull(AppVersion.source(null));
    }

    @Test
    public void sha256IsLowercaseHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AppVersion.sha256(new byte[0]));
    }
}
