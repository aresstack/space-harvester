package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Eine abgelegte Zusammenfassung (ADR 0030, Abschnitt 5): Text, Aussagen mit Seiten-IDs, Modell, Zeitpunkt und der Stand
 * des Berichts, aus dem sie entstand. Titel liegen nicht darin; sie kommen beim Lesen mit den Rechten des Lesers.
 *
 * @param at          bis wann sie aufbewahrt wird: Ende des Updates, beim offenen der Zeitpunkt der Erstellung
 * @param reportSha256 Stand des Berichts bei der Erstellung
 * @param inputPages  die Seiten, deren Titel und Unterschiede an das Modell gingen; verliert eine die Freigabe, verfällt
 *                    die Zusammenfassung
 */
public record StoredSummary(String sourceId, String updateId, String text, List<Statement> statements, String model,
                            Instant createdAt, Instant at, int unreadableChanges, String reportSha256,
                            List<InputPage> inputPages) {

    /** Eine Aussage mit den IDs ihrer Seiten. */
    public record Statement(String text, List<String> pageIds) {

        public Statement {
            Objects.requireNonNull(text, "text");
            pageIds = List.copyOf(pageIds);
        }
    }

    /** Eine Seite, die mit Titel an das Modell ging, samt Space für die Rechteprüfung. */
    public record InputPage(String spaceKey, String pageId) {

        public InputPage {
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(pageId, "pageId");
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
}
