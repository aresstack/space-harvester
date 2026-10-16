package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Welche Seite ein Link der Lese-Ansicht meint (paketintern). Ob er überhaupt zu dieser Confluence gehört, entscheidet
 * {@link ConfluencePageView#underBase} (Schema, Host ohne Groß-/Kleinschreibung, Port mit Standardport, Kontextpfad);
 * erkannt werden dahinter die üblichen Formen der Seitenadresse:
 *
 * <ul>
 *   <li>{@code pages/viewpage.action?pageId=<id>} und {@code spaces/<KEY>/pages/<id>[/<Titel>]}: die Content-ID
 *   selbst,</li>
 *   <li>{@code x/<Kurzcode>} (Kurzlink, „Teilen“): die Content-ID, kodiert,</li>
 *   <li>{@code display/<KEY>/<Titel>} und {@code pages/viewpage.action?spaceKey=<KEY>&title=<Titel>}: Space und Titel
 *   (die ID kennt erst Confluence),</li>
 *   <li>{@code display/<KEY>}: die Startseite des Space.</li>
 * </ul>
 * Alles andere (Blogbeiträge, Anhänge, Bearbeiten, Suche, Jira) ist keine Seite für den Lesebereich.
 */
final class ConfluenceLinks {

    /** Die Seitenadresse über die Content-ID, relativ zur Basis-URL; die ID folgt. */
    static final String VIEW_PAGE = "pages/viewpage.action?pageId=";

    private static final Pattern SPACES_PAGE = Pattern.compile("/spaces/[^/]+/pages/([0-9]{1,19})(?:/.*)?");
    private static final Pattern DISPLAY = Pattern.compile("/display/([^/]+)(?:/([^/]*))?/?");
    private static final Pattern TINY = Pattern.compile("/x/([A-Za-z0-9_-]{1,11})/?");
    /** Base64 of eight bytes without padding: eleven characters. */
    private static final int TINY_LENGTH = 11;

    private ConfluenceLinks() {
    }

    /**
     * Adressmuster des Confluence-Quelltyps: eine vollständige HTTP(S)-Webadresse oder ein Bookmark-artiger Wert mit
     * dem Schema der Quelle ({@code confluence://<locator>/spaces/KEY/pages/123/...}, #109). Welcher konkreten
     * Confluence die Adresse gehört, entscheidet erst {@link #parse(String, String)} mit deren Basis-URL. Dadurch
     * dürfen mehrere Confluence-Quellen dasselbe Pattern matchen und werden anschließend jeweils mit Source-Hint
     * aufgelöst.
     */
    static Pattern addressPattern() {
        return Pattern.compile("^(?:https?|" + ConfluenceIds.SCHEME + ")://[^\\s]+$", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Turn a bookmark-like value of this source ({@code confluence://<locator>/<path>}) into the web address under
     * {@code base} ({@code <base>/<path>}), so that {@link #parse} reads the usual page forms behind it. Return any
     * other value unchanged; a bookmark of another Confluence stays foreign.
     *
     * @param locator the canonical locator of the source (base URL without scheme)
     */
    static String webAddressOf(String address, String locator, String base) {
        if (address == null || locator == null || base == null) {
            return address;
        }
        String trimmed = address.trim();
        String prefix = ConfluenceIds.SCHEME + "://" + locator;
        if (!trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return address;
        }
        String rest = trimmed.substring(prefix.length());
        if (!rest.isEmpty() && rest.charAt(0) != '/' && rest.charAt(0) != '?' && rest.charAt(0) != '#') {
            return address; // only a prefix of a longer locator
        }
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return root + rest;
    }

    /**
     * Die Seite hinter {@code address} oder {@code null}, wenn die Adresse nicht unter {@code base} liegt oder keine
     * Seitenadresse ist.
     *
     * @param base die eingestellte Basis-URL (mit Kontextpfad)
     */
    static Target parse(String address, String base) {
        String rest = ConfluencePageView.underBase(address, base, false);
        if (rest == null) {
            return null;
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        int question = rest.indexOf('?');
        String path = question < 0 ? rest : rest.substring(0, question);
        String query = question < 0 ? "" : rest.substring(question + 1);
        if ("/pages/viewpage.action".equals(path)) {
            String pageId = parameter(query, "pageId");
            if (pageId != null) {
                return ConfluenceIds.isContentId(pageId) ? Target.page(pageId) : null;
            }
            return titled(parameter(query, "spaceKey"), parameter(query, "title"));
        }
        Matcher spacesPage = SPACES_PAGE.matcher(path);
        if (spacesPage.matches()) {
            return Target.page(spacesPage.group(1));
        }
        Matcher tiny = TINY.matcher(path);
        if (tiny.matches()) {
            String pageId = tinyId(tiny.group(1));
            return pageId == null ? null : Target.page(pageId);
        }
        Matcher display = DISPLAY.matcher(path);
        if (display.matches() && query.isEmpty()) {
            String spaceKey = decode(display.group(1));
            String title = display.group(2) == null ? "" : decode(display.group(2));
            if (title == null || !ConfluenceIds.isSpaceKey(spaceKey)) {
                return null;
            }
            return title.trim().isEmpty() ? Target.home(spaceKey) : titled(spaceKey, title);
        }
        return null;
    }

    private static Target titled(String spaceKey, String title) {
        if (!ConfluenceIds.isSpaceKey(spaceKey) || title == null || title.trim().isEmpty()) {
            return null;
        }
        return new Target(null, spaceKey, title);
    }

    /** The decoded value of the first parameter {@code name}; {@code null} without it. */
    private static String parameter(String query, String name) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    /** Percent and {@code +} decoding as Confluence writes titles into addresses; {@code null} when malformed. */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * Die Content-ID eines Kurzlinks: Confluence schreibt die ID als acht Bytes Little Endian in Base64, mit
     * {@code -} statt {@code /} und {@code _} statt {@code +}, ohne Auffüllung und ohne abschließende {@code A}
     * (Null-Bytes).
     */
    static String tinyId(String code) {
        StringBuilder padded = new StringBuilder(code.replace('-', '/').replace('_', '+'));
        while (padded.length() < TINY_LENGTH) {
            padded.append('A');
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(padded.append('=').toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        long id = 0;
        for (int i = bytes.length - 1; i >= 0; i--) {
            id = (id << 8) | (bytes[i] & 0xFF);
        }
        return id > 0 ? Long.toString(id) : null;
    }

    /** Die Seite eines Links: Content-ID, oder Space und Titel, oder nur der Space (dessen Startseite). */
    static final class Target {
        /** Content-ID oder {@code null}, wenn erst Space und Titel sie bestimmen. */
        final String pageId;
        final String spaceKey;
        /** Titel der Seite; {@code null} bei ID oder Startseite. */
        final String title;

        private Target(String pageId, String spaceKey, String title) {
            this.pageId = pageId;
            this.spaceKey = spaceKey;
            this.title = title;
        }

        static Target page(String pageId) {
            return new Target(pageId, null, null);
        }

        static Target home(String spaceKey) {
            return new Target(null, spaceKey, null);
        }
    }
}
