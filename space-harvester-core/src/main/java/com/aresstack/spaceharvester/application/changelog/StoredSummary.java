package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Eine abgelegte Zusammenfassung (ADR 0030, Abschnitt 5): Text, Aussagen mit Seiten-IDs, Modell, Zeitpunkt und der Stand
 * des Berichts, aus dem sie entstand. Titel liegen nicht darin; sie kommen beim Lesen mit den Rechten des Lesers.
 *
 * @param spaceKey    der Space der Ebene (ADR 0031), {@code null} für die ganze Quelle
 * @param at          bis wann sie aufbewahrt wird: Ende des Updates, beim offenen der Zeitpunkt der Erstellung
 * @param reportSha256 Stand des Berichts bei der Erstellung
 * @param inputPages  die Seiten, deren Titel und Unterschiede an das Modell gingen; verliert eine die Freigabe, verfällt
 *                    die Zusammenfassung
 * @param from        Beginn der Spanne des Updates bei der Erstellung (ausschließlich), {@code null} vor der ersten Marke
 *                    oder bei Einträgen von vor dieser Angabe; mit einem Update je Tag gilt sie nur für dieselbe Spanne
 */
public record StoredSummary(String sourceId, String updateId, String spaceKey, String text, List<Statement> statements, String model,
                            Instant createdAt, Instant at, int unreadableChanges, String reportSha256,
                            List<InputPage> inputPages, Instant from) {

    /**
     * Eine Aussage (seit {@code update-summary-v2} ein Thema) mit den IDs ihrer Seiten.
     *
     * @param title   kurzer Titel des Themas, {@code null} bei älteren Zusammenfassungen
     * @param details ausführlichere Zusammenfassung für das Detailfenster, {@code null} wenn keine vorliegt
     */
    public record Statement(String text, List<String> pageIds, String title, String details) {

        public Statement {
            Objects.requireNonNull(text, "text");
            pageIds = List.copyOf(pageIds);
        }

        /** Ohne Titel und Details. */
        public Statement(String text, List<String> pageIds) {
            this(text, pageIds, null, null);
        }
    }

    /**
     * Eine Seite, die mit Titel an das Modell ging, samt Quelle und Space für die Rechteprüfung.
     *
     * @param sourceId Quelle der Seite; {@code null} bei Einträgen von vor dieser Angabe, dann die der Zusammenfassung
     * @param content  ob auch Absätze ihres Inhalts eingingen; dann muss der Leser den Inhalt lesen dürfen
     */
    public record InputPage(String sourceId, String spaceKey, String pageId, boolean content) {

        public InputPage {
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(pageId, "pageId");
        }

        /** Ohne Quelle (Einträge von vor dieser Angabe). */
        public InputPage(String spaceKey, String pageId, boolean content) {
            this(null, spaceKey, pageId, content);
        }

        public InputPage(String spaceKey, String pageId) {
            this(spaceKey, pageId, false);
        }

        /** Die Quelle der Seite, ohne eigene Angabe die der Zusammenfassung. */
        public String sourceIdOr(String summarySourceId) {
            return sourceId != null ? sourceId : summarySourceId;
        }
    }

    public StoredSummary {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        Objects.requireNonNull(text, "text");
        statements = List.copyOf(statements);
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(reportSha256, "reportSha256");
        inputPages = List.copyOf(inputPages);
    }

    /** Der Platz dieser Zusammenfassung in der Ablage (ADR 0034 §7): das Update ihrer Ebene. */
    public SummaryScope.Update scope() {
        return SummaryScope.update(sourceId, updateId, spaceKey);
    }

    /** Ohne bekannten Beginn der Spanne. */
    public StoredSummary(String sourceId, String updateId, String spaceKey, String text, List<Statement> statements,
                         String model, Instant createdAt, Instant at, int unreadableChanges, String reportSha256,
                         List<InputPage> inputPages) {
        this(sourceId, updateId, spaceKey, text, statements, model, createdAt, at, unreadableChanges, reportSha256,
                inputPages, null);
    }

    /** Zusammenfassung der ganzen Quelle (ohne Space). */
    public StoredSummary(String sourceId, String updateId, String text, List<Statement> statements, String model,
                         Instant createdAt, Instant at, int unreadableChanges, String reportSha256,
                         List<InputPage> inputPages) {
        this(sourceId, updateId, null, text, statements, model, createdAt, at, unreadableChanges, reportSha256,
                inputPages);
    }
}
