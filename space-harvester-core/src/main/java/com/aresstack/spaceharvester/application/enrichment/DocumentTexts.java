package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import java.util.Optional;

/**
 * Lesetext eines Abschnitts eines Dokuments (ADR 0026, AP9a) für Ausschnitte und Absätze, nur wenn er genau der Text
 * ist, aus dem ein Ergebnis entstand ({@code inputSha256}); umgesetzt von {@link DocumentEnrichments#sectionText}.
 * Mit {@code extraction} ({@link Enrichment#extraction()} des Ergebnisses) zudem nur, wenn das Ergebnis zur jetzigen
 * Extraktion des Dokuments gehört: Ändert eine neue Extraktion einen anderen Abschnitt, gilt die Capture des ganzen
 * Dokuments nicht mehr, auch wenn dieser Abschnitt gleich blieb. {@code null}: nur der Abschnitt zählt (Ausschnitte).
 */
@FunctionalInterface
public interface DocumentTexts {

    /** Ohne Lesetexte: Treffer in Dokumenten haben keinen Ausschnitt. */
    DocumentTexts NONE = (document, section, inputSha256, extraction) -> Optional.empty();

    Optional<PageText> text(DocumentKey document, int section, String inputSha256, String extraction);

    /** Nur der Abschnitt zählt, etwa für Ausschnitte von Treffern. */
    default Optional<PageText> text(DocumentKey document, int section, String inputSha256) {
        return text(document, section, inputSha256, null);
    }
}
