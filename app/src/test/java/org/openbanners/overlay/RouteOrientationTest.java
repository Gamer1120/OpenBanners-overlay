package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class RouteOrientationTest {
    @Test
    public void neverChosenFollowsCompass() {
        assertEquals(RouteOrientation.COMPASS, RouteOrientation.DEFAULT);
        assertTrue(RouteOrientation.followsCompass(null, true));
    }

    @Test
    public void storedChoiceIsKept() {
        assertFalse(RouteOrientation.followsCompass(RouteOrientation.NORTH, true));
        assertTrue(RouteOrientation.followsCompass(RouteOrientation.COMPASS, true));
    }

    @Test
    public void withoutSensorTheMapStaysNorthUp() {
        assertFalse(RouteOrientation.followsCompass(null, false));
        assertFalse(RouteOrientation.followsCompass(RouteOrientation.COMPASS, false));
        assertFalse(RouteOrientation.followsCompass(RouteOrientation.NORTH, false));
    }

    @Test
    public void settingsScreenDefaultMatchesCode() throws Exception {
        File xml = new File("src/main/res/xml/root_preferences.xml");
        if (!xml.exists()) xml = new File("app/src/main/res/xml/root_preferences.xml");
        String text = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<ListPreference((?:(?!/>).)*?@string/route_orientation\"(?:(?!/>).)*?)/>", Pattern.DOTALL).matcher(text);
        assertTrue("orientation preference not found", m.find());
        Matcher d = Pattern.compile("app:defaultValue=\"([^\"]*)\"").matcher(m.group(1));
        assertTrue(d.find());
        assertEquals(RouteOrientation.DEFAULT, d.group(1));
    }
}
