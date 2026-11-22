package org.openbanners.overlay;

import org.openbanners.overlay.api.MissionType;

/**
 * Settings → Route map → "Line for any-order missions": how the route map joins the portals of a mission whose steps
 * can be done in any order (in listed order). Dashed (default) or dotted, so it reads as a suggestion and not as the
 * mission's path, or no line at all (versions up to 62). Ordered missions always get a solid line. Pure Java, unit-tested.
 */
final class AnyOrderLine {
    static final String DASHED = "dashed";
    static final String DOTTED = "dotted";
    static final String NONE = "none";
    static final String DEFAULT = DASHED;

    /** Kinds of leg between two consecutive points on the route map. */
    static final int LEG_NONE = 0;
    static final int LEG_SOLID = 1;
    /** Between two portals of an any-order mission: drawn in the style chosen here. */
    static final int LEG_ANY_ORDER = 2;

    private AnyOrderLine() {
    }

    /** The stored setting, or the default if it's missing or unknown. */
    static String of(String stored) {
        return DASHED.equals(stored) || DOTTED.equals(stored) || NONE.equals(stored) ? stored : DEFAULT;
    }

    /** The leg between two consecutive portals of one mission. */
    static int legWithin(MissionType type) {
        return type == MissionType.anyOrder ? LEG_ANY_ORDER : LEG_SOLID;
    }

    /** Whether a leg of this kind is drawn with this setting. */
    static boolean drawn(int leg, String style) {
        return leg == LEG_SOLID || leg == LEG_ANY_ORDER && !NONE.equals(of(style));
    }

    /**
     * On/off lengths in pixels for a DashPathEffect, or null for a solid line. Dotted uses a near-zero "on" length:
     * with round caps every dash becomes a dot as wide as the line.
     */
    static float[] intervals(int leg, String style, float dp) {
        if (leg != LEG_ANY_ORDER) return null;
        switch (of(style)) {
            case DASHED:
                return new float[]{10 * dp, 9 * dp};
            case DOTTED:
                return new float[]{0.01f, 9 * dp};
            default:
                return null;
        }
    }
}
