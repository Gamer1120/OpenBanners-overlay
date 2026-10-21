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
 */
final class DeepLink {
    static final String SCHEME = "openbanners-overlay";
    static final String DEFAULT_ORIGIN = "https://openbanners.org";
    private static final List<String> ALLOWED_ORIGINS = Arrays.asList(DEFAULT_ORIGIN, "https://test.openbanners.org");

    final String bannerId;
    final boolean autostart;
    /** Always one of the allowed sites. */
    final String origin;

    private DeepLink(String bannerId, boolean autostart, String origin) {
        this.bannerId = bannerId;
        this.autostart = autostart;
        this.origin = origin;
    }

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
        if (!SCHEME.equalsIgnoreCase(uri.getScheme()) || !"banner".equalsIgnoreCase(uri.getHost())) return null;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.replaceAll("^/+|/+$", "").split("/");
        if (segments.length != 1 || !segments[0].matches("[A-Za-z0-9._~-]+")) return null;
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
        return new DeepLink(segments[0], autostart, safeOrigin(origin));
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    /** The banner as a link the overlay already understands (see {@link SharedDataParser}). */
    String bannerUrl() {
        return "https://openbanners.org/banner/" + bannerId;
    }
}
