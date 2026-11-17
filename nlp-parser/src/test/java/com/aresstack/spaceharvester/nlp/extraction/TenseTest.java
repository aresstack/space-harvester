package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Zeitbezug nach Zeitform (ADR 0032, Festlegung AT) an erfundenen Gold-Analysen. */
class TenseTest {

    @Test
    void preteriteIsEarlierButPerfectPresentAndConditionalAreNot() {
        ObjectCentricAnalysis analysis = Gold.analyze("zeitformen.conllu");
        List<StatementFeatures.Temporality> temporality = analysis.statements().stream()
                .map(statement -> StatementFeatures.assess(analysis, statement).temporality()).toList();
        assertEquals(List.of(StatementFeatures.Temporality.FRUEHER, // reparierte (schwach)
                StatementFeatures.Temporality.FRUEHER, // nahm … mit (stark, Partikel)
                StatementFeatures.Temporality.GILT, // würde … tauschen (Konjunktiv II, kein Futur)
                StatementFeatures.Temporality.FRUEHER, // verstand (Vorsilbe + stark)
                StatementFeatures.Temporality.GILT, // hat … repariert (Perfekt)
                StatementFeatures.Temporality.GILT), // repariert (Präsens)
                temporality);
    }
}
