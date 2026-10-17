package com.aresstack.spaceharvester.application.query;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Seiten-ID aus der URL einer Confluence-Seite. Unterstützt werden die Formen, die die ID enthalten:
 * {@code …?pageId=123} (etwa {@code viewpage.action}), {@code …/spaces/KEY/pages/123[/Titel]} und Kurzlinks
 * {@code …/x/AgAB}. Formen ohne ID ({@code /display/KEY/Titel}) werden nicht aufgelöst, weil ein Titel weder eindeutig
 * noch stabil ist.
 */
public final class PageUrls {

    /** Größte angenommene URL-Länge. */
    public static final int MAX_URL_LENGTH = 2048;

    private static final Pattern NUMERIC = Pattern.compile("[0-9]{1,19}");
    private static final Pattern PAGE_ID_PARAMETER = Pattern.compile("(?:^|&)pageId=([0-9]{1,19})(?:&|$)");
    private static final Pattern PAGES_PATH = Pattern.compile("/spaces/[^/]+/pages/([0-9]{1,19})(?:/.*)?$");
    private static final Pattern TINY_PATH = Pattern.compile("/x/([A-Za-z0-9_-]{1,11})/?$");

    private PageUrls() {
    }

    /**
     * @return die Seiten-ID oder leer, wenn die URL keine absolute http(s)-URL ist oder keine Seiten-ID enthält
     */
    public static Optional<String> pageId(String url) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        if (uri.getRawQuery() != null) {
            Matcher parameter = PAGE_ID_PARAMETER.matcher(uri.getRawQuery());
            if (parameter.find()) {
                return positive(parameter.group(1));
            }
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        Matcher pages = PAGES_PATH.matcher(path);
        if (pages.find()) {
            return positive(pages.group(1));
        }
        Matcher tiny = TINY_PATH.matcher(path);
        if (tiny.find()) {
            return tiny(tiny.group(1));
        }
        return Optional.empty();
    }

    /**
     * Ob zwei URLs auf denselben Server zeigen (Schema, Host ohne Groß-/Kleinschreibung, Port mit Standardwerten).
     * Damit wird eine URL abgewiesen, deren ID zufällig zu einer Seite einer anderen Confluence-Instanz passt.
     */
    public static boolean sameServer(String url, URI known) {
        try {
            URI uri = new URI(url.trim());
            return uri.getScheme().equalsIgnoreCase(known.getScheme())
                    && uri.getHost().equalsIgnoreCase(known.getHost())
                    && port(uri) == port(known);
        } catch (URISyntaxException | NullPointerException e) {
            return false;
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Confluence-Kurzlinks: die Seiten-ID als 8 Byte little-endian, Base64 mit {@code -} statt {@code /} und {@code _}
     * statt {@code +}, ohne Füllzeichen und ohne abschließende {@code A} (Null-Bits).
     */
    private static Optional<String> tiny(String code) {
        StringBuilder base64 = new StringBuilder(code.replace('-', '/').replace('_', '+'));
        while (base64.length() < 11) {
            base64.append('A');
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64 + "=");
            long id = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
            return id > 0 ? Optional.of(Long.toString(id)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> positive(String digits) {
        if (!NUMERIC.matcher(digits).matches()) {
            return Optional.empty();
        }
        try {
            long id = Long.parseLong(digits);
            return id > 0 ? Optional.of(Long.toString(id)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
