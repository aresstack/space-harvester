package com.aresstack.spaceharvester.application.metadata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Die Abfragen des Exports „Analytics“ an das Confluence-Analytics-Plugin (Data Center ab 7.11). Das Plugin hat
 * keine dokumentierte Schnittstelle; die Pfade unter {@code /rest/confanalytics/1.0/} sind die, die die
 * Analytics-Oberfläche selbst aufruft (mitgeschnitten an Confluence 10.2.19, Angelo 16.11.2026), und können sich je
 * Confluence-Version unterscheiden. Deshalb sind sie austauschbar (in der App unter Einstellungen): Platzhalter
 * {@code {spaceKey}}, {@code {from}} und {@code {to}} (ISO-8601 mit Millisekunden, UTC, wie die Oberfläche sie
 * sendet).
 *
 * <p>Die Aufrufe je Seite kommen nicht aus einer Abfrage je Seite, sondern aus der Seitenliste des Space
 * ({@link #contentViews}): eine geblätterte Liste aller Seiten mit Aufrufen im Zeitraum, je Eintrag {@code views},
 * {@code usersViewed}, {@code lastViewedAt}, {@code lastModifiedAt}. So kostet der Export je Space wenige Anfragen
 * statt einer je Seite. Gefragt wird nur nach Summen, nie nach den Namen der Leser.
 *
 * @param space        Abfragen einmal je Space, je eine Antwort (Space-Details, Aktivität je Datum)
 * @param contentViews die Seitenliste des Space, geblättert über {@code nextPageToken}
 * @param days         Zeitraum bis heute, in Tagen
 */
public record AnalyticsQueries(List<Query> space, Query contentViews, int days) {

    public static final String ROOT = "/rest/confanalytics/1.0/";
    /** Aufbewahrung der Analytics-Ereignisse: 12 Monate Standard, einstellbar 6 bis 24. */
    public static final int DEFAULT_DAYS = 365;
    /** Wie die Oberfläche: Seiten und Blogbeiträge, Summen, Wochenraster. */
    private static final String PERIOD = "fromDate={from}&toDate={to}&type=total&period=week&timezone=UTC"
            + "&content=page%2Cblog";
    /** Name und Vorgabe der Space-Details (Name, Anlage, Installation des Plugins). */
    public static final String DETAILS = "details";
    public static final String DEFAULT_DETAILS = ROOT + "space/details?spaceKey={spaceKey}";
    /** Name und Vorgabe der Aktivität des Space je Datum (Anlagen, Änderungen, Aufrufe). */
    public static final String ACTIVITY_BY_DATE = "activityByDate";
    public static final String DEFAULT_ACTIVITY_BY_DATE = ROOT + "space/activityByDate?spaceKey={spaceKey}&" + PERIOD;
    /** Name und Vorgabe der Seitenliste mit Aufrufen je Seite; die Blätterung hängt {@code nextPageToken} an. */
    public static final String CONTENT_VIEWS = "contentViews";
    public static final String DEFAULT_CONTENT_VIEWS = ROOT + "space/paginated/contentViews?spaceKey={spaceKey}&"
            + PERIOD + "&sortField=VIEWED_COUNT&sortOrder=DESC&limit=100";
    /** Der Parameter, mit dem die nächste Listenseite angefordert wird (Wert aus {@code nextPageToken}). */
    public static final String PAGE_TOKEN = "nextPageToken";

    /** Die Vorgabe: die Pfade der Analytics-Oberfläche, 365 Tage. */
    public static final AnalyticsQueries DEFAULT = new AnalyticsQueries(
            List.of(new Query(DETAILS, DEFAULT_DETAILS), new Query(ACTIVITY_BY_DATE, DEFAULT_ACTIVITY_BY_DATE)),
            new Query(CONTENT_VIEWS, DEFAULT_CONTENT_VIEWS), DEFAULT_DAYS);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public AnalyticsQueries {
        space = List.copyOf(Objects.requireNonNull(space, "space"));
        Objects.requireNonNull(contentViews, "contentViews");
        if (days < 1) {
            throw new IllegalArgumentException("days muss mindestens 1 sein");
        }
    }

    /**
     * Die Vorgabe mit anderen Pfaden und Zeitraum, etwa aus der Konfiguration der App; {@code null} oder leer lässt
     * die Vorgabe stehen.
     */
    public static AnalyticsQueries of(String details, String activityByDate, String contentViews, Integer days) {
        return new AnalyticsQueries(List.of(new Query(DETAILS, orDefault(details, DEFAULT_DETAILS)),
                new Query(ACTIVITY_BY_DATE, orDefault(activityByDate, DEFAULT_ACTIVITY_BY_DATE))),
                new Query(CONTENT_VIEWS, orDefault(contentViews, DEFAULT_CONTENT_VIEWS)),
                days == null ? DEFAULT_DAYS : days);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** Ob ein Pfad als Abfrage taugt: unter {@code /rest/}, ohne Leerzeichen und Steuerzeichen. */
    public static boolean validPath(String pathTemplate) {
        return pathTemplate != null && pathTemplate.startsWith("/rest/") && !pathTemplate.contains("#")
                && pathTemplate.chars().allMatch(c -> c > 0x20 && c < 0x7f || c == '{' || c == '}');
    }

    /** Eine benannte Abfrage; der Name ist der Schlüssel in der Datei. */
    public record Query(String name, String pathTemplate) {

        public Query {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(pathTemplate, "pathTemplate");
            if (name.isBlank() || !validPath(pathTemplate)) {
                throw new IllegalArgumentException("Abfrage braucht einen Namen und einen Pfad unter /rest/");
            }
        }

        /** Der Pfad mit eingesetzten Werten; Werte werden URL-kodiert. */
        public String path(String spaceKey, Instant from, Instant to) {
            return pathTemplate.replace("{spaceKey}", encode(spaceKey))
                    .replace("{from}", encode(stamp(from))).replace("{to}", encode(stamp(to)));
        }

        /** Wie {@link #path}, mit dem Token der nächsten Listenseite als weiterem Parameter. */
        public String path(String spaceKey, Instant from, Instant to, String pageToken) {
            String path = path(spaceKey, from, to);
            return path + (path.contains("?") ? "&" : "?") + PAGE_TOKEN + "=" + encode(pageToken);
        }

        private static String stamp(Instant instant) {
            return STAMP.format(instant.truncatedTo(ChronoUnit.MILLIS));
        }

        private static String encode(String value) {
            return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    /** Beginn des Zeitraums vor {@code now}. */
    public Instant from(Instant now) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    /** Ob alles der Vorgabe entspricht. */
    public boolean isDefault() {
        return equals(DEFAULT);
    }
}
