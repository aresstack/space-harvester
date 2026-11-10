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
        assertEquals("hinzufügen", GermanTagCorrections.lemma("hinzuzufügen", "VERB", "hinzuzufügen"));
        assertEquals("abändern", GermanTagCorrections.lemma("abzuändern", "VERB", "abzuändern"));
        assertEquals("hinweisen", GermanTagCorrections.lemma("hinzuweisen", "VERB", "hinzuweisen"),
                "„darauf hinzuweisen“ ist der zu-Infinitiv von hinweisen");
        assertEquals("hinwirken", GermanTagCorrections.lemma("hinzuwirken", "VERB", null));
        assertEquals("hinsetzen", GermanTagCorrections.lemma("hinzusetzen", "VERB", "hinzusetzen"));
        assertEquals("hinzucken", GermanTagCorrections.lemma("hinzuzucken", "VERB", "hinzuzucken"));
    }

    @Test
    void ordinaryInfinitivesContainingParticleAndZuStayUnchanged() {
        assertEquals("hinzufügen", GermanTagCorrections.lemma("hinzufügen", "VERB", "hinzufügen"),
                "Verbzusatz hinzu-, kein zu-Infinitiv");
        assertEquals("hinzuziehen", GermanTagCorrections.lemma("hinzuziehen", "VERB", "hinzuziehen"));
        assertEquals("hinzuaddieren", GermanTagCorrections.lemma("hinzuaddieren", "VERB", "hinzuaddieren"));
        assertEquals("anzupfen", GermanTagCorrections.lemma("anzupfen", "VERB", "anzupfen"), "„pf“ ist kein Stamm");
        assertEquals("aufzucken", GermanTagCorrections.lemma("aufzucken", "VERB", "aufzucken"), "„ck“ ist kein Stamm");
        assertNull(GermanTagCorrections.lemma("abzupfen", "VERB", null), "ohne Grundform bleibt es ohne");
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
