package com.aresstack.spaceharvester.application.jobs;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein Diagnoseereignis eines Auftrags (docs/DIAGNOSTICS.md): wann, wo und in welcher Phase etwas geschah, mit den technischen
 * Befunden, die eine Fehlersuche braucht. Alle Texte sind maskiert ({@link Redaction}); Zugangsdaten, Tokens und
 * Abfrageparameter erscheinen nie.
 *
 * @param jobId        Auftrag
 * @param sequence     laufende Nummer innerhalb des Auftrags, ab 1
 * @param at           Zeitpunkt
 * @param type         Art des Auftrags
 * @param sourceId     Quelle des Auftrags
 * @param partitionKey Space oder {@code null}
 * @param severity     Schwere
 * @param category     Bereich, nach dem die Status-Ansicht filtert
 * @param component    beteiligte Komponente, etwa {@code Exports} oder {@code SpaceSync}
 * @param phase        Phase des Auftrags beim Ereignis, etwa {@code Einlesen} oder {@code Konvertieren}
 * @param code         maschinenlesbarer Code, bei Fehlern der Code des {@link JobError}
 * @param message      Meldung für Menschen
 * @param httpStatus   HTTP-Status der Quelle, falls bekannt, sonst {@code null}
 * @param proxyRoute   verwendete Proxy-Route zur Quelle ({@code DIRECT}, {@code PROXY host:port} …) oder {@code null}
 * @param tlsFinding   TLS- oder Zertifikatsbefund oder {@code null}
 * @param detail       Ausnahmekette mit Stacktrace für die aufklappbare Ansicht, maskiert und gekürzt, oder {@code null}
 */
public record JobEvent(String jobId, long sequence, Instant at, JobType type, String sourceId, String partitionKey,
                       Severity severity, Category category, String component, String phase, String code,
                       String message, Integer httpStatus, String proxyRoute, String tlsFinding, String detail) {

    /** Größte Länge von {@link #detail()} in Zeichen. */
    public static final int MAX_DETAIL_CHARS = 16_000;

    public enum Severity { INFO, WARNING, ERROR }

    public enum Category {
        /** Lebenslauf und Phasen des Auftrags. */
        JOB,
        /** Verbindung, Proxy, HTTP-Status, TLS und Zertifikate. */
        NETWORK,
        /** Antworten der Quelle. */
        SOURCE,
        /** Rechte des Auftraggebers. */
        ACCESS,
        /** Cache, Exportablage, Konvertierung. */
        STORAGE
    }

    public JobEvent {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(message, "message");
        message = Redaction.mask(message);
        proxyRoute = proxyRoute == null ? null : Redaction.mask(proxyRoute);
        tlsFinding = tlsFinding == null ? null : Redaction.mask(tlsFinding);
        if (detail != null) {
            detail = Redaction.mask(detail);
            if (detail.length() > MAX_DETAIL_CHARS) {
                detail = detail.substring(0, MAX_DETAIL_CHARS) + "\n… gekürzt";
            }
        }
    }

    /**
     * Was ein Auftrag über {@link JobContext#record} meldet; Auftrag, Zeit, Nummer und Phase ergänzt {@link Jobs}.
     */
    public record Draft(Severity severity, Category category, String component, String code, String message,
                        Integer httpStatus, String tlsFinding, Throwable cause) {

        public Draft {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(message, "message");
        }

        public static Draft info(String component, String message) {
            return new Draft(Severity.INFO, Category.JOB, component, null, message, null, null, null);
        }

        public static Draft warning(Category category, String component, String code, String message) {
            return new Draft(Severity.WARNING, category, component, code, message, null, null, null);
        }
    }
}
