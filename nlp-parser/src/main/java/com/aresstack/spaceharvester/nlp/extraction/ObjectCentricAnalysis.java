package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Ergebnis der objektzentrierten Auswertung: die unveränderte Rohanalyse, alle Erwähnungen, Aussagen und nominalen
 * Bezüge. Die priorisierte Sicht ({@link SalienceProjection}) und Statistiken ({@link MentionStatistics}) werden
 * daraus abgeleitet; Details und Belege lassen sich über die IDs nachladen.
 *
 * @param document    vollständige UD-Analyse
 * @param mentions    Erwähnungen in Textreihenfolge
 * @param statements  Aussagen in Textreihenfolge
 * @param attachments nominale Bezüge
 */
public record ObjectCentricAnalysis(ParsedDocument document, List<Mention> mentions, List<Statement> statements,
                                    List<NominalAttachment> attachments) {

    public ObjectCentricAnalysis {
        Objects.requireNonNull(document, "document");
        mentions = List.copyOf(mentions);
        statements = List.copyOf(statements);
        attachments = List.copyOf(attachments);
    }

    public Mention mention(String id) {
        return index(mentions, Mention::id).computeIfAbsent(id, missing -> {
            throw new NoSuchElementException("Keine Erwähnung " + missing);
        });
    }

    public Statement statement(String id) {
        return index(statements, Statement::id).computeIfAbsent(id, missing -> {
            throw new NoSuchElementException("Keine Aussage " + missing);
        });
    }

    /** Der Satz, aus dem eine Aussage stammt: Originaltext und Spanne als Beleg. */
    public Sentence evidence(Statement statement) {
        return document.sentences().get(statement.sentence());
    }

    /** Nominale Bezüge, deren Kopf die Erwähnung {@code mentionId} ist ({@code Beleg} → {@code des Kunden}). */
    public List<NominalAttachment> attachmentsOf(String mentionId) {
        return attachments.stream().filter(attachment -> attachment.head().equals(mentionId)).toList();
    }

    private static <T> Map<String, T> index(List<T> values, java.util.function.Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        values.forEach(value -> map.put(key.apply(value), value));
        return map;
    }
}
