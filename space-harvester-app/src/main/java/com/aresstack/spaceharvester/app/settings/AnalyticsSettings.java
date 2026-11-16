package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.application.metadata.AnalyticsQueries;
import java.time.Duration;

/**
 * Der Export „Analytics“ (Angelo 16.11.2026): Zeitraum und die Pfade des Confluence-Analytics-Plugins, in der Datei
 * unter {@code space-harvester-app.analytics}. Die Pfade sind nicht dokumentiert und können je Confluence-Version
 * abweichen, deshalb stehen sie nicht fest im Code: ohne Angabe gelten die Vorgaben aus {@link AnalyticsQueries}
 * (die Pfade der Analytics-Oberfläche von Confluence 10.2), eine Angabe ersetzt die Vorgabe. Platzhalter
 * {@code {spaceKey}}, {@code {from}}, {@code {to}}.
 *
 * @param period         Zeitraum bis heute; {@code null} für die Vorgabe (365 Tage)
 * @param details        Pfad der Space-Details; {@code null} für die Vorgabe
 * @param activityByDate Pfad der Aktivität je Datum; {@code null} für die Vorgabe
 * @param contentViews   Pfad der Seitenliste mit Aufrufen je Seite; {@code null} für die Vorgabe
 */
public record AnalyticsSettings(Duration period, String details, String activityByDate, String contentViews) {

    /** Alles Vorgabe. */
    public static final AnalyticsSettings DEFAULT = new AnalyticsSettings(null, null, null, null);
    /** Der Schlüssel in der Datei. */
    public static final String KEY = "space-harvester-app.analytics";
    public static final Duration DEFAULT_PERIOD = Duration.ofDays(AnalyticsQueries.DEFAULT_DAYS);

    public AnalyticsSettings {
        if (period != null && !wholeDays(period)) {
            throw new IllegalArgumentException("Der Zeitraum muss ganze Tage umfassen, mindestens einen");
        }
        details = blankToNull(details);
        activityByDate = blankToNull(activityByDate);
        contentViews = blankToNull(contentViews);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Die Abfragen des Core: Angaben ersetzen die Vorgaben. */
    public AnalyticsQueries toQueries() {
        return AnalyticsQueries.of(details, activityByDate, contentViews, period == null ? null : days());
    }

    /** Ob die Dauer als Zeitraum taugt: ganze Tage, mindestens einer; die Abfragen rechnen in Tagen. */
    public static boolean wholeDays(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero()
                && duration.toSeconds() % 86_400 == 0 && duration.toDays() <= Integer.MAX_VALUE;
    }

    /** Der Zeitraum in Tagen; ohne Angabe die Vorgabe. */
    public int days() {
        return (int) effectivePeriod().toDays();
    }

    /** Der wirksame Zeitraum. */
    public Duration effectivePeriod() {
        return period == null ? DEFAULT_PERIOD : period;
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }
}
