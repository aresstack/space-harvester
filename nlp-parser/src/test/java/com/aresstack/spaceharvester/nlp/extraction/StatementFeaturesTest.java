package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures.Features;
import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures.Modality;
import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures.Temporality;
import org.junit.jupiter.api.Test;

/** Merkmale Verneinung, Verbindlichkeit und Zeitbezug an erfundenen Gold-Analysen (ADR 0032, FL2). */
class StatementFeaturesTest {

    private static final ObjectCentricAnalysis ANALYSIS = Gold.analyze("features.conllu");

    private static Features features(int sentence, String verb) {
        Statement statement = ANALYSIS.statements().stream()
                .filter(s -> s.sentence() == sentence - 1 && s.predicate().lemma().equals(verb))
                .findFirst().orElseThrow(() -> new AssertionError("keine Aussage " + verb + " in Satz " + sentence));
        return StatementFeatures.assess(ANALYSIS, statement);
    }

    @Test
    void negationByParticleAndByKein() {
        assertEquals(new Features(true, Modality.NONE, Temporality.GILT), features(1, "erfüllen"));
        assertEquals(new Features(true, Modality.NONE, Temporality.GILT), features(8, "erfassen"));
    }

    @Test
    void nichtNurSondernAuchIsNoNegation() {
        assertEquals(new Features(false, Modality.NONE, Temporality.GILT), features(5, "prüfen"));
    }

    @Test
    void modalVerbsAndSeinWithZuInfinitive() {
        assertEquals(Modality.MUSS, features(2, "prüfen").modality());
        assertEquals(Temporality.GILT, features(2, "prüfen").temporality(), "Pflicht ohne Zeitangabe gilt");
        assertEquals(Modality.MUSS, features(6, "vorlegen").modality(), "ist … vorzulegen");
    }

    @Test
    void temporalityFromWordsAndPastAuxiliary() {
        assertEquals(new Features(false, Modality.SOLL, Temporality.GEPLANT), features(3, "erfassen"));
        assertEquals(Temporality.FRUEHER, features(4, "erfassen").temporality());
        assertEquals(Temporality.FRUEHER, features(7, "löschen").temporality(),
                "zu-Infinitiv teilt den Zeitbezug von „war vorgesehen“");
        assertEquals(Temporality.FRUEHER, features(10, "verwerfen").temporality(), "wurde … verworfen: Präteritum");
    }

    @Test
    void purposeClauseDoesNotInheritFromGovernor() {
        assertEquals(Temporality.FRUEHER, features(9, "löschen").temporality(), "xcomp teilt „früher“ von „bat“");
        assertEquals(Temporality.GILT, features(9, "sparen").temporality(), "um … zu bleibt eigen");
    }
}
