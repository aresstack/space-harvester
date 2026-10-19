package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Ein konkretes Vorkommen einer Nominalgruppe in genau dieser Textprojektion (#108): die Wörter mit Bereich, die
 * vorgeschlagene Grundform (ungeprüft) und der Suchwert mit seinem Verfahren. Kein Begriff: zwei Erwähnungen mit
 * gleichem Suchwert bleiben zwei. Ein Mehrwortbegriff ist eine Erwähnung.
 *
 * @param key            Schlüssel innerhalb der Seite
 * @param sentence       Bereich des Satzes
 * @param surface        die Wörter
 * @param lemmaCandidate vorgeschlagene Grundform ({@link TermNormalizer#LEMMA_STATUS}) oder {@code null}
 * @param normalized     Suchwert, nie eine Identität
 * @param normalization  wie {@code normalized} entstand ({@link TermNormalizer#FROM_LEMMA_CANDIDATE} oder
 *                       {@link TermNormalizer#FROM_SURFACE})
 * @param pronoun        ein Pronomen oder anderer Bezug, dessen Ziel nicht aufgelöst ist
 * @param status         {@link ExtractionStatus}
 */
public record Mention(String key, TextSpan sentence, GroundedText surface, String lemmaCandidate, String normalized,
                      String normalization, boolean pronoun, ExtractionStatus status) {

    @Override
    public String toString() {
        return "Mention[" + key + " " + surface.span() + ", " + status + "]";
    }
}
