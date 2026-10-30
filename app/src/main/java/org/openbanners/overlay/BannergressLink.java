package org.openbanners.overlay;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns a Bannergress banner link into the same banner on OpenBanners (Settings → Links, opt-in):
 * {@code https://bannergress.com/banner/st-paulus-dom-m%C3%BCnster-4d07?x=1#y} →
 * {@code https://openbanners.org/banner/st-paulus-dom-m%C3%BCnster-4d07}. Both sites use the same banner ids.
 */
final class BannergressLink {
    private BannergressLink() {
    }

    /** The openbanners.org page for a bannergress.com banner link, or null when it isn't one. */
    static String toOpenBanners(String link) {
        if (link == null) return null;
        URI uri;
        try {
            uri = new URI(link.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("bannergress.com") && !host.equals("www.bannergress.com")) return null;
        if (uri.getRawUserInfo() != null) return null;
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) return null;
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/")) return null;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String[] segments = path.substring(1).split("/", -1);
        if (segments.length != 2 || !segments[0].equals("banner")) return null;
        String id = decodePathSegment(segments[1]);
        if (!DeepLink.isBannerId(id)) return null;
        return "https://openbanners.org/banner/" + DeepLink.encodePathSegment(id);
    }

    /** Strict percent-decoding (a '+' stays a '+'); null for broken escapes or bytes that aren't UTF-8. */
    static String decodePathSegment(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] utf8 = raw.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < utf8.length; i++) {
            byte b = utf8[i];
            if (b == '%') {
                if (i + 2 >= utf8.length) return null;
                int high = Character.digit(utf8[i + 1], 16);
                int low = Character.digit(utf8[i + 2], 16);
                if (high < 0 || low < 0) return null;
                bytes.write(high * 16 + low);
                i += 2;
            } else {
                bytes.write(b);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
