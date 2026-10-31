package org.openbanners.overlay;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Links that open a banner directly, e.g. from the "Start with Overlay" button on openbanners.org:
 * {@code openbanners-overlay://banner/{bannerId}?start=1&origin=https%3A%2F%2Ftest.openbanners.org}. With
 * {@code start=1} the overlay begins at mission 1 and opens it in Ingress straight away. {@code origin} is the
 * site the link came from; "Close and mark as done" goes back there (only known OpenBanners sites).
 * Mission Day plans use {@code openbanners-overlay://plan/{planId}?start=1&origin=…}; the plan lives on that site.
 * "Start with Overlay from here" on a mission uses {@code openbanners-overlay://mission/{bannerId}/{n}?start=1&origin=…}
 * with n the 1-based mission number: the overlay begins at that mission instead of mission 1. It's its own host so
 * older app versions don't match it and the site shows its install/update dialog.
 */
final class DeepLink {
    static final String SCHEME = "openbanners-overlay";
    static final String DEFAULT_ORIGIN = "https://openbanners.org";
    private static final List<String> ALLOWED_ORIGINS = Arrays.asList(DEFAULT_ORIGIN, "https://test.openbanners.org");

    /** The banner id, or the plan id when {@link #plan}. */
    final String bannerId;
    final boolean plan;
    final boolean autostart;
    /** Mission to begin at (0-based); 0 except for mission links. */
    final int startMission;
    /** Always one of the allowed sites. */
    final String origin;

    private DeepLink(String bannerId, boolean plan, boolean autostart, int startMission, String origin) {
        this.bannerId = bannerId;
        this.plan = plan;
        this.autostart = autostart;
        this.startMission = startMission;
        this.origin = origin;
    }

    /** Highest mission number we accept in a mission link; banners are far shorter. */
    static final int MAX_MISSION_NUMBER = 9999;

    /** The site for "Close and mark as done": {@code origin} if it's a known OpenBanners site, else openbanners.org. */
    static String safeOrigin(String origin) {
        if (origin == null) return DEFAULT_ORIGIN;
        String trimmed = origin.trim().toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        return ALLOWED_ORIGINS.contains(trimmed) ? trimmed : DEFAULT_ORIGIN;
    }

    /** Opens the banner on the site and marks it as done there (signing in first if needed). */
    static String markDoneUrl(String origin, String bannerId) {
        String path;
        try {
            path = URLEncoder.encode(bannerId, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            path = bannerId;
        }
        return safeOrigin(origin) + "/banner/" + path + "?markDone=1";
    }

    /** Returns null when the link isn't a banner link. */
    static DeepLink parse(String link) {
        if (link == null) return null;
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) return null;
        boolean plan = "plan".equalsIgnoreCase(uri.getHost());
        boolean mission = "mission".equalsIgnoreCase(uri.getHost());
        if (!plan && !mission && !"banner".equalsIgnoreCase(uri.getHost())) return null;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.replaceAll("^/+|/+$", "").split("/");
        if (segments.length != (mission ? 2 : 1) || !(plan ? segments[0].matches("[A-Za-z0-9_-]{22}") : isBannerId(segments[0]))) return null;
        int startMission = 0;
        if (mission) {
            int number = parseMissionNumber(segments[1]);
            if (number < 1) return null;
            startMission = number - 1;
        }
        boolean autostart = false;
        String origin = null;
        String query = uri.getRawQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                int equals = parameter.indexOf('=');
                String name = equals < 0 ? parameter : parameter.substring(0, equals);
                String value = equals < 0 ? "" : decode(parameter.substring(equals + 1));
                if (name.equals("start")) autostart = value.equals("1") || value.equals("true");
                else if (name.equals("origin")) origin = value;
            }
        }
        return new DeepLink(segments[0], plan, autostart, startMission, safeOrigin(origin));
    }

    /** The 1-based mission number of a mission link, or -1 when it isn't a plain number from 1 to the maximum. */
    static int parseMissionNumber(String segment) {
        if (segment == null || !segment.matches("[0-9]{1,4}")) return -1;
        int number = Integer.parseInt(segment);
        return number >= 1 && number <= MAX_MISSION_NUMBER ? number : -1;
    }

    /** Longest banner id we accept; Bannergress slugs are far shorter. */
    static final int MAX_BANNER_ID_LENGTH = 200;

    /**
     * Bannergress banner ids are slugs of the title and can contain any letter (st-paulus-dom-münster-4d07, café-…,
     * straße-…), so only refuse what can't be a single path segment: empty, too long, slashes, whitespace,
     * control/format characters and URL delimiters.
     */
    static boolean isBannerId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_BANNER_ID_LENGTH) return false;
        for (int i = 0; i < id.length(); ) {
            int c = id.codePointAt(i);
            i += Character.charCount(c);
            if (c == '/' || c == '\\' || c == '?' || c == '#' || c == '%') return false;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) return false;
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.UNASSIGNED || type == Character.SURROGATE || type == Character.PRIVATE_USE) return false;
        }
        return true;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    /** The banner or plan as a link the overlay already understands (see {@link SharedDataParser}). */
    String bannerUrl() {
        // Percent-encoded, so ids with letters like ü survive the URL matching in SharedDataParser.
        return plan ? origin + "/planner/" + bannerId : "https://openbanners.org/banner/" + encodePathSegment(bannerId);
    }

    /** A banner id as one URL path segment (UTF-8 percent-encoded, spaces as %20). */
    static String encodePathSegment(String segment) {
        try {
            return URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return segment;
        }
    }
}
