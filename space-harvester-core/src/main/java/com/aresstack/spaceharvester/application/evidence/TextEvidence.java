package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.Objects;
import java.util.Optional;

/**
 * Textbeleg (ADR 0032): der Originalsatz einer Aussage als Position in genau einer Fassung des Dokuments, nie als Text.
 * Das ist die bisherige Regel „Beleg bleibt der Originalsatz“ unter neuem Namen. Geprüft wird er wie bisher durch den
 * Wortabgleich im Core und die Prüfverfahren aus ADR 0032 (Vorstufe, Prüfschritt, Prüfung durch Menschen).
 *
 * @param document Seite oder Dokument des Satzes
 * @param revision Fassung (Eingabe-Hash der Analyse)
 * @param sentence Satzindex ab 0
 * @param start    Zeichenposition im Text (inklusive)
 * @param end      Zeichenposition im Text (exklusive)
 */
public record TextEvidence(DocumentKey document, String revision, int sentence, int start, int end)
        implements Evidence {

    /** Trennt in einer Aussagen-ID die Fassung vom Satz ({@code quelle/seite@fassung#s<Satz>…}). */
    private static final String SENTENCE_MARK = "#s";
    private static final char REVISION_MARK = '@';

    public TextEvidence {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(revision, "revision");
        if (revision.isBlank()) {
            throw new IllegalArgumentException("Fassung darf nicht leer sein");
        }
        if (sentence < 0) {
            throw new IllegalArgumentException("Satzindex " + sentence + " ungültig");
        }
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Textspanne " + start + ".." + end + " ungültig");
        }
    }

    /**
     * Der Textbeleg einer gespeicherten Aussage: Dokument aus der Seite der Analyse, Fassung aus der Aussagen-ID
     * ({@code quelle/seite@fassung#s<Satz>…}), Satz und Spanne aus der Aussage. Eine Aussage aus der Vorstufe oder einer
     * Korrektur des Prüfschritts hat denselben Beleg wie ihr Originalsatz: Ein vereinfachter Satz ist nie Beleg.
     *
     * @throws IllegalArgumentException wenn die ID keine Fassung trägt
     */
    public static TextEvidence of(KnowledgeGraph.PageRef page, ParsedStatements.Statement statement) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(statement, "statement");
        return new TextEvidence(page.document(), revisionOf(statement.id()), statement.sentence(), statement.start(),
                statement.end());
    }

    /** Fassung aus einer Aussagen-ID {@code quelle/seite@fassung#s<Satz>…}. */
    static String revisionOf(String statementId) {
        int sentence = statementId.lastIndexOf(SENTENCE_MARK);
        int at = sentence < 0 ? -1 : statementId.lastIndexOf(REVISION_MARK, sentence);
        if (at < 0 || at + 1 >= sentence) {
            throw new IllegalArgumentException("Aussagen-ID ohne Fassung: " + statementId);
        }
        return statementId.substring(at + 1, sentence);
    }

    @Override
    public Kind kind() {
        return Kind.TEXT;
    }

    @Override
    public Optional<Bearing> bearing() {
        return Optional.empty();
    }
}
