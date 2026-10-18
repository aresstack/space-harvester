package com.aresstack.spaceharvester.application.query;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Wann eine Seite aus dem Cache ohne Rückfrage bei der Quelle gilt (ADR 0013, Punkt 4). Jede Seite trägt den Zeitpunkt
 * ihres Inhaltsabrufs ({@code fetchedAt}, {@link com.aresstack.spaceharvester.application.snapshot.PageSnapshot#retrievedAt()},
 * mit dem Cache gespeichert, übersteht also einen Neustart) und getrennt davon die letzte erfolgreiche Prüfung bei der
 * Quelle ({@code lastCheckedAt}). Beispiel: geladen um 10:00, {@code maxAge} 6 h: bis 16:00 kommt die Seite aus dem
 * Cache, danach wird sie geprüft bzw. neu abgerufen.
 *
 * <p>Die Frische entscheidet nie über Rechte: Auch eine frische Seite liefert der Cache nur, wenn der Aufrufer sie lesen
 * darf; bei delegierter Anmeldung fragt die Rechteprüfung die Quelle mit seiner Identität.
 *
 * @param maxAge       so lange gilt eine Seite ab ihrem Inhaltsabruf (mit Versionsprüfung: ab der letzten Prüfung) ohne
 *                     Rückfrage; {@link Duration#ZERO} fragt bei jedem Abruf
 * @param versionCheck nach Ablauf erst die Version bei der Quelle prüfen und nur eine neuere laden; ohne sie wird die
 *                     Seite nach Ablauf neu abgerufen
 */
public record CacheFreshness(Duration maxAge, boolean versionCheck) {

    /** Längstes Zeitfenster. */
    public static final Duration MAX_AGE = Duration.ofDays(365);

    /** Ohne Einstellung: jeder Abruf prüft die Version bei der Quelle (wie bisher). */
    public static final CacheFreshness DEFAULT = new CacheFreshness(Duration.ZERO, true);

    public CacheFreshness {
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isNegative() || maxAge.compareTo(MAX_AGE) > 0) {
            throw new IllegalArgumentException("maxAge muss zwischen 0 und 365 Tagen liegen");
        }
    }

    /**
     * Ob die Seite noch ohne Rückfrage gilt.
     *
     * @param fetchedAt     Inhaltsabruf der Seite im Cache
     * @param lastCheckedAt letzte erfolgreiche Versionsprüfung oder {@code null}
     */
    public boolean fresh(Instant fetchedAt, Instant lastCheckedAt, Instant now) {
        if (maxAge.isZero() || fetchedAt == null) {
            return false;
        }
        Instant since = versionCheck && lastCheckedAt != null && lastCheckedAt.isAfter(fetchedAt) ? lastCheckedAt
                : fetchedAt;
        return now.isBefore(since.plus(maxAge));
    }
}
