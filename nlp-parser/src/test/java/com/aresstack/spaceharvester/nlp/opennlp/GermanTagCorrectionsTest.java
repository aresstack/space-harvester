package com.aresstack.spaceharvester.nlp.opennlp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Korrekturen geschlossener Wortklassen und der zu-Infinitiv-Grundform, ohne Modell. */
class GermanTagCorrectionsTest {

    @Test
    void closedClassFormsTaggedAsVerbAreRetagged() {
        String[] forms = {"Datenprodukten", ",", "welchen", "das", "Muster", "zugewiesen", "ist", "hinweg", "sich",
                "und", "welchen", "Daten"};
        String[] upos = {"NOUN", "PUNCT", "VERB", "VERB", "NOUN", "VERB", "AUX", "VERB", "AUX", "VERB", "VERB", "NOUN"};
        GermanTagCorrections.correctTags(forms, upos);
        assertArrayEquals(new String[] {"NOUN", "PUNCT", "PRON", "DET", "NOUN", "VERB", "AUX", "ADP", "PRON", "CCONJ",
                "DET", "NOUN"}, upos);
    }

    @Test
    void onlyVerbalTagsAreCorrected() {
        String[] forms = {"Die", "Daten", "die", "liegen"};
        String[] upos = {"DET", "NOUN", "PRON", "VERB"};
        GermanTagCorrections.correctTags(forms, upos);
        assertArrayEquals(new String[] {"DET", "NOUN", "PRON", "VERB"}, upos, "korrekte Wortarten bleiben");
    }

    @Test
    void zuInfinitiveWithSeparableParticleGetsTheInfinitive() {
        assertEquals("bereitstellen", GermanTagCorrections.lemma("bereitzustellen", "VERB", "bereitzustellen"));
        assertEquals("bereitstellen", GermanTagCorrections.lemma("bereitzustellen", "VERB", null));
        assertEquals("zuordnen", GermanTagCorrections.lemma("zuzuordnen", "VERB", "zuzuordnen"));
        assertEquals("zusammenführen", GermanTagCorrections.lemma("zusammenzuführen", "VERB", "zusammenzuführen"));
        assertEquals("sicherstellen", GermanTagCorrections.lemma("sicherzustellen", "VERB", "sicherzustellen"));
    }

    @Test
    void otherLemmasStayAsTheLemmatizerGaveThem() {
        assertEquals("anstellen", GermanTagCorrections.lemma("anzustellen", "VERB", "anstellen"), "schon richtig");
        assertEquals("zuzeln", GermanTagCorrections.lemma("zuzeln", "VERB", "zuzeln"), "kein Verbzusatz vor zu");
        assertEquals("bereitzustellen", GermanTagCorrections.lemma("bereitzustellen", "ADJ", "bereitzustellen"));
        assertEquals("verstehen", GermanTagCorrections.lemma("verstehen", "VERB", "verstehen"));
        assertNull(GermanTagCorrections.lemma("Zuzug", "VERB", null));
    }
}
