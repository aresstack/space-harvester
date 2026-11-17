package com.aresstack.spaceharvester.nlp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aresstack.spaceharvester.nlp.extraction.SimplificationSelection.Mode;
import com.aresstack.spaceharvester.nlp.extraction.SimplificationSelection.Reason;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Auswahl „nur wo nötig“ der Vorstufe (ADR 0032, FL3) an erfundenen Gold-Analysen. */
class SimplificationSelectionTest {

    @Test
    void bulletsLabelsVerblessLinesAndSentencesWithoutPlausibleStatementAreSelected() {
        ParsedDocument document = Gold.load("selection.conllu");
        ObjectCentricAnalysis analysis = Gold.analyze("selection.conllu");

        Map<Integer, Set<Reason>> selected = SimplificationSelection.select(document, analysis, Mode.WHERE_NEEDED);

        assertEquals(Set.of(Reason.NO_VERB, Reason.NO_PLAUSIBLE_STATEMENT), selected.get(0), "Überschrift");
        assertEquals(null, selected.get(1), "vollständiger Satz mit PLAUSIBLE-Aussage bleibt");
        assertEquals(Set.of(Reason.BULLET, Reason.NO_PLAUSIBLE_STATEMENT), selected.get(2));
        assertEquals(Set.of(Reason.LABEL), selected.get(3), "Kennungszeile, auch mit PLAUSIBLE-Aussage");
        assertEquals(3, selected.size());
        assertEquals(4, SimplificationSelection.select(document, analysis, Mode.ALWAYS).size());
        assertEquals(Set.of(), SimplificationSelection.select(document, analysis, Mode.ALWAYS).get(1));
    }

    @Test
    void headingsAreContextAndOnlySectionsWithASentenceToSimplifyGoToTheModel() {
        ParsedDocument document = Gold.load("selection.conllu");
        Map<Integer, Set<Reason>> selected = SimplificationSelection.select(document, Gold.analyze("selection.conllu"),
                Mode.WHERE_NEEDED);

        assertEquals(false, SimplificationSelection.simplify(selected.get(0)), "Überschrift ist Zusammenhang");
        assertEquals(java.util.List.of(new SimplificationSelection.Section(0, 3, java.util.List.of(2, 3))),
                SimplificationSelection.sections(document, selected));
    }

    @Test
    void mergeKeepsPlausibleOriginalStatementsAndAddsOnlyMissingVerbs() {
        record S(int sentence, String verb, boolean plausible, String from) {
        }
        java.util.List<S> original = java.util.List.of(new S(0, "prüfen", true, "o"), new S(0, "senden", false, "o"),
                new S(1, "zahlen", false, "o"));
        java.util.List<S> simplified = java.util.List.of(new S(0, "prüfen", true, "v"), new S(0, "senden", true, "v"),
                new S(0, "buchen", true, "v"), new S(1, "zahlen", true, "v"));

        java.util.List<S> merged = SimplificationSelection.merge(original, simplified, Set.of(0), S::sentence,
                S::plausible, (a, b) -> a.verb().equals(b.verb()));

        assertEquals(java.util.List.of(new S(0, "prüfen", true, "o"), new S(0, "senden", true, "v"),
                new S(0, "buchen", true, "v"), new S(1, "zahlen", false, "o")), merged);
    }
}
