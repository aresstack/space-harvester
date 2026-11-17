package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import java.util.Optional;

/**
 * Port: woher {@link StatusQuo#sentences} Satzgrenzen und Lesetext nimmt, nur aus dem Bestand.
 */
public interface SentenceSource {

    /** Ohne Lesetexte: jeder Satz ist {@link SentenceText#stale()}. */
    SentenceSource NONE = new SentenceSource() {
        @Override
        public Optional<KnowledgeGraph.AnalysedPage> analysis(AccessContext caller, KnowledgeGraph.PageRef page) {
            return Optional.empty();
        }

        @Override
        public Optional<String> text(KnowledgeGraph.PageRef page, String inputSha256) {
            return Optional.empty();
        }
    };

    /** Die gespeicherte Analyse der Seite bzw. des Dokuments, wie der Aufrufer sie sehen darf. */
    Optional<KnowledgeGraph.AnalysedPage> analysis(AccessContext caller, KnowledgeGraph.PageRef page);

    /** Der Lesetext der Seite bzw. des Dokuments, nur wenn er genau {@code inputSha256} hat. */
    Optional<String> text(KnowledgeGraph.PageRef page, String inputSha256);
}
