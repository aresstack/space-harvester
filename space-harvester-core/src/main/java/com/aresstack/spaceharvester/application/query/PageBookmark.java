package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stabile, quellenneutrale Ressourcen-ID einer Seite als Lesezeichen (#178, wie {@code KnowledgeBookmarkIds} im
 * Enterprise AI Client): {@code space-harvester://<Quelle>/page/<Seiten-ID>[?version=<Version>]}. Sie hängt nur an der
 * Quellen-ID der Konfiguration und der Seiten-ID der Quelle, nicht an Basis-URL, Titel oder Space; eine verschobene
 * oder umbenannte Seite bleibt erreichbar. Die Version ist der Stand, den das Lesezeichen gesehen hat: aufgelöst wird
 * immer die aktuelle Seite, der Aufrufer vergleicht mit {@link #version()}.
 *
 * @param sourceId Quellen-ID aus der Konfiguration
 * @param pageId   Seiten-ID in der Quelle
 * @param version  gesehene Version oder {@code null}
 */
public record PageBookmark(String sourceId, String pageId, String version) {

    /** Schema der Lesezeichen. */
    public static final String SCHEME = "space-harvester";
    /**
     * Längstes Lesezeichen. Länger als {@link PageUrls#MAX_URL_LENGTH}, damit jede Seite, deren Ressourcen-ID
     * angenommen wird (etwa {@code files:<Quelle>/<lange ID>}), auch als Lesezeichen wieder auflösbar ist.
     */
    public static final int MAX_LENGTH = 4096;

    private static final String PREFIX = SCHEME + "://";
    private static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final int MAX_VERSION_LENGTH = 256;
    private static final Pattern FORM = Pattern.compile(
            "([^/?#]+)/page/([^/?#]+)(?:\\?version=([^&#]+))?");

    public PageBookmark {
        if (sourceId == null || !SOURCE_ID.matcher(sourceId).matches()) {
            throw new IllegalArgumentException("sourceId ist keine Quellen-ID");
        }
        if (pageId == null || pageId.isBlank() || pageId.length() > PageUrls.MAX_URL_LENGTH) {
            throw new IllegalArgumentException("pageId fehlt oder ist zu lang");
        }
        if (version != null && version.isBlank()) {
            version = null;
        }
        if (version != null && version.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("version ist zu lang");
        }
        if (text(sourceId, pageId, version).length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Lesezeichen wäre länger als " + MAX_LENGTH + " Zeichen");
        }
    }

    /** Das Lesezeichen einer Seite mit ihrer aktuellen Version. */
    public static PageBookmark of(PageHeader header) {
        return new PageBookmark(header.key().sourceId(), header.key().pageId(), header.metadata().version());
    }

    /** Ob die Eingabe ein Lesezeichen dieses Schemas sein will (auch ein fehlerhaftes). */
    public static boolean isBookmark(String value) {
        return value != null && value.trim().regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * @return das Lesezeichen oder leer, wenn {@code value} keines ist
     * @throws IllegalArgumentException wenn {@code value} das Schema hat, aber kein gültiges Lesezeichen ist
     */
    public static Optional<PageBookmark> parse(String value) {
        if (!isBookmark(value)) {
            return Optional.empty();
        }
        String rest = value.trim().substring(PREFIX.length());
        Matcher form = FORM.matcher(rest);
        if (!form.matches()) {
            throw new IllegalArgumentException("Lesezeichen hat nicht die Form " + PREFIX
                    + "<Quelle>/page/<Seiten-ID>[?version=<Version>]");
        }
        return Optional.of(new PageBookmark(form.group(1).toLowerCase(Locale.ROOT), decode(form.group(2)),
                form.group(3) == null ? null : decode(form.group(3))));
    }

    /** Die Ressourcen-ID als Text, wie {@link #parse} sie liest. */
    public String value() {
        return text(sourceId, pageId, version);
    }

    private static String text(String sourceId, String pageId, String version) {
        return PREFIX + sourceId + "/page/" + URLEncoder.encode(pageId, StandardCharsets.UTF_8)
                + (version == null ? "" : "?version=" + URLEncoder.encode(version, StandardCharsets.UTF_8));
    }

    /** Die längste angenommene Eingabe: für Lesezeichen {@link #MAX_LENGTH}, sonst {@link PageUrls#MAX_URL_LENGTH}. */
    static int maxAddressLength(String address) {
        return isBookmark(address) ? MAX_LENGTH : PageUrls.MAX_URL_LENGTH;
    }

    /** Dasselbe Lesezeichen ohne Version: dieselbe Seite unabhängig von ihrem Stand. */
    public PageBookmark withoutVersion() {
        return version == null ? this : new PageBookmark(sourceId, pageId, null);
    }

    @Override
    public String toString() {
        return value();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
