package com.aresstack.spaceharvester.application.metadata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Die Abfragen des Exports „Analytics“ an das Confluence-Analytics-Plugin (Data Center ab 7.11). Das Plugin hat
 * keine dokumentierte Schnittstelle; die Pfade unter {@code /rest/confanalytics/1.0/} sind die, die die
 * Analytics-Oberfläche selbst aufruft, und können sich je Confluence-Version unterscheiden. Deshalb sind sie
 * austauschbar: Platzhalter {@code {spaceKey}}, {@code {pageId}}, {@code {from}} und {@code {to}}
 * (ISO-8601, UTC). Ohne Treffer bricht der Auftrag nach der ersten Seite ab, statt jede Seite zu versuchen.
 *
 * <p>Gefragt wird nur nach Summen (Aufrufe), nie nach den Namen der Leser.
 *
 * @param space Abfragen einmal je Space
 * @param page  Abfragen je Seite
 * @param days  Zeitraum bis heute, in Tagen
 */
public record AnalyticsQueries(List<Query> space, List<Query> page, int days) {

    public static final String ROOT = "/rest/confanalytics/1.0/";
    /** Aufbewahrung der Analytics-Ereignisse: 12 Monate Standard, einstellbar 6 bis 24. */
    public static final int DEFAULT_DAYS = 365;

    /** Die Vorgabe: Aufrufe je Tag für den Space und je Seite. */
    public static final AnalyticsQueries DEFAULT = new AnalyticsQueries(
            List.of(new Query("spaceViewsByDate", ROOT + "space/activityByDate?spaceKey={spaceKey}&fromDate={from}"
                    + "&toDate={to}&period=day&type=total&timezone=UTC")),
            List.of(new Query("viewsByDate", ROOT + "content/viewsByDate?contentId={pageId}&contentType=page"
                    + "&fromDate={from}&toDate={to}&period=day&type=total&timezone=UTC")),
            DEFAULT_DAYS);

    public AnalyticsQueries {
        space = List.copyOf(Objects.requireNonNull(space, "space"));
        page = List.copyOf(Objects.requireNonNull(page, "page"));
        if (page.isEmpty() && space.isEmpty()) {
            throw new IllegalArgumentException("mindestens eine Abfrage");
        }
        if (days < 1) {
            throw new IllegalArgumentException("days muss mindestens 1 sein");
        }
    }

    /** Eine benannte Abfrage; der Name ist der Schlüssel in der Datei. */
    public record Query(String name, String pathTemplate) {

        public Query {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(pathTemplate, "pathTemplate");
            if (name.isBlank() || !pathTemplate.startsWith("/rest/")) {
                throw new IllegalArgumentException("Abfrage braucht einen Namen und einen Pfad unter /rest/");
            }
        }

        /** Der Pfad mit eingesetzten Werten; Werte werden URL-kodiert. */
        public String path(String spaceKey, String pageId, Instant from, Instant to) {
            return pathTemplate.replace("{spaceKey}", encode(spaceKey)).replace("{pageId}", encode(pageId))
                    .replace("{from}", encode(stamp(from))).replace("{to}", encode(stamp(to)));
        }

        private static String stamp(Instant instant) {
            return instant.truncatedTo(ChronoUnit.SECONDS).toString();
        }

        private static String encode(String value) {
            return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    /** Beginn des Zeitraums vor {@code now}. */
    public Instant from(Instant now) {
        return now.minus(days, ChronoUnit.DAYS);
    }
}
