package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import java.util.Optional;

/**
 * Lesetext eines Abschnitts eines Dokuments (ADR 0026, AP9a) für Ausschnitte und Absätze, nur wenn er genau der Text
 * ist, aus dem ein Ergebnis entstand ({@code inputSha256}); umgesetzt von {@link DocumentEnrichments#sectionText}.
 */
@FunctionalInterface
public interface DocumentTexts {

    /** Ohne Lesetexte: Treffer in Dokumenten haben keinen Ausschnitt. */
    DocumentTexts NONE = (document, section, inputSha256) -> Optional.empty();

    Optional<PageText> text(DocumentKey document, int section, String inputSha256);
}
