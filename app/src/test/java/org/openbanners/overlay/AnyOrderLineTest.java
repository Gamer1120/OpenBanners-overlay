package org.openbanners.overlay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.openbanners.overlay.api.MissionType;

public class AnyOrderLineTest {
    @Test
    public void dashedByDefault() {
        assertEquals(AnyOrderLine.DASHED, AnyOrderLine.DEFAULT);
        assertEquals(AnyOrderLine.DASHED, AnyOrderLine.of(null));
        assertEquals(AnyOrderLine.DASHED, AnyOrderLine.of("zigzag"));
        assertEquals(AnyOrderLine.DOTTED, AnyOrderLine.of("dotted"));
        assertEquals(AnyOrderLine.NONE, AnyOrderLine.of("none"));
    }

    @Test
    public void orderedMissionsStaySolid() {
        assertEquals(AnyOrderLine.LEG_SOLID, AnyOrderLine.legWithin(MissionType.sequential));
        assertEquals(AnyOrderLine.LEG_SOLID, AnyOrderLine.legWithin(MissionType.hidden));
        assertEquals(AnyOrderLine.LEG_SOLID, AnyOrderLine.legWithin(null));
        for (String style : new String[]{AnyOrderLine.DASHED, AnyOrderLine.DOTTED, AnyOrderLine.NONE}) {
            assertTrue(AnyOrderLine.drawn(AnyOrderLine.LEG_SOLID, style));
            assertNull(AnyOrderLine.intervals(AnyOrderLine.LEG_SOLID, style, 2f));
            assertFalse(AnyOrderLine.drawn(AnyOrderLine.LEG_NONE, style));
        }
    }

    @Test
    public void anyOrderMissionsFollowTheSetting() {
        assertEquals(AnyOrderLine.LEG_ANY_ORDER, AnyOrderLine.legWithin(MissionType.anyOrder));
        int leg = AnyOrderLine.LEG_ANY_ORDER;
        assertTrue(AnyOrderLine.drawn(leg, AnyOrderLine.DASHED));
        assertTrue(AnyOrderLine.drawn(leg, AnyOrderLine.DOTTED));
        assertFalse(AnyOrderLine.drawn(leg, AnyOrderLine.NONE)); // as before version 63
        assertArrayEquals(new float[]{20f, 18f}, AnyOrderLine.intervals(leg, AnyOrderLine.DASHED, 2f), 0.001f);
        float[] dots = AnyOrderLine.intervals(leg, AnyOrderLine.DOTTED, 2f);
        assertTrue(dots[0] < 0.1f); // round caps turn near-zero dashes into dots
        assertEquals(18f, dots[1], 0.001f);
        assertNull(AnyOrderLine.intervals(leg, AnyOrderLine.NONE, 2f));
    }
}
