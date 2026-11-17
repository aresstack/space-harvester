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

    @Test
    void finiteFormsAndParticiplesKeptAsLemmaByOpenNlp() {
        assertEquals("abrufen", GermanTagCorrections.lemma("abruft", "VERB", "abruft"));
        assertEquals("sammeln", GermanTagCorrections.lemma("sammelt", "VERB", "sammelt"));
        assertEquals("ändert", GermanTagCorrections.lemma("ändert", "VERB", "ändert"), "mit Umlaut: bleibt");
        assertEquals("arbeiten", GermanTagCorrections.lemma("arbeitet", "VERB", "arbeitet"));
        assertEquals("einhalten", GermanTagCorrections.lemma("einhält", "VERB", "einhält"), "starkes Verb aus der Tabelle");
        assertEquals("prüft", GermanTagCorrections.lemma("prüft", "NOUN", "prüft"), "nur Verben");
        assertEquals("erfassen", GermanTagCorrections.lemma("erfasst", "VERB", "erfassen"), "Grundform bleibt");
        assertNull(GermanTagCorrections.lemma("holt", "VERB", null), "ohne Grundform nichts erfinden");
        assertEquals("zurücksenden", GermanTagCorrections.lemma("zurückgesendet", "VERB", "zurückgesenden"));
        assertEquals("angegeben", GermanTagCorrections.lemma("angegeben", "VERB", "angegeben"));
        assertEquals("Maßnahme", GermanTagCorrections.lemma("Maßnahme", "NOUN", "Maßnahme"), "kein ß→ss");
        // Gegenproben: im Zweifel die Form lassen statt ein Wort zu erfinden
        for (String form : java.util.List.of("gebucht", "gehört")) {
            assertEquals(form, GermanTagCorrections.lemma(form, "VERB", form), form);
        }
        assertEquals("bilden", GermanTagCorrections.lemma("bildet", "VERB", "bildet"));
        assertEquals("liefern", GermanTagCorrections.lemma("liefert", "VERB", "liefert"));
    }

    @Test
    void strongVerbsWithVowelChangeInThePresentFromTheTable() {
        java.util.Map<String, String> strong = java.util.Map.of("hält", "halten", "fährt", "fahren", "trägt", "tragen",
                "lässt", "lassen", "gibt", "geben", "gilt", "gelten", "nimmt", "nehmen", "liest", "lesen", "sieht",
                "sehen", "hältst", "halten");
        strong.forEach((form, lemma) -> assertEquals(lemma, GermanTagCorrections.lemma(form, "VERB", form), form));
        assertEquals("einhalten", GermanTagCorrections.lemma("einhält", "VERB", "einhält"), "mit Verbzusatz");
        assertEquals("erhalten", GermanTagCorrections.lemma("erhält", "VERB", "erhält"), "mit untrennbarer Vorsilbe");
        assertEquals("entsprechen", GermanTagCorrections.lemma("entspricht", "VERB", "entspricht"));
        // Gegenproben: regelmäßige Verben mit Umlaut bleiben, Nicht-Verben bleiben, vorhandene Grundformen bleiben
        for (String form : java.util.List.of("zählt", "wählt", "klärt", "ändert")) {
            assertEquals(form, GermanTagCorrections.lemma(form, "VERB", form), form);
        }
        assertEquals("Tritt", GermanTagCorrections.lemma("Tritt", "NOUN", "Tritt"), "Nomen, kein Verb");
        assertEquals("halten", GermanTagCorrections.lemma("hält", "VERB", "halten"));
        assertEquals("hielt", GermanTagCorrections.lemma("hielt", "VERB", "hielt"), "kein Präteritum");
    }

    @Test
    void oldSpellingOfTheSameFormsAndWissen() {
        java.util.Map<String, String> forms = java.util.Map.of("läßt", "lassen", "mißt", "messen", "ißt", "essen",
                "frißt", "fressen", "vergißt", "vergessen", "weiß", "wissen", "weißt", "wissen");
        forms.forEach((form, lemma) -> assertEquals(lemma, GermanTagCorrections.lemma(form, "VERB", form), form));
        // keine allgemeine Ersetzung von ß: unbekannte Formen mit ß bleiben
        assertEquals("grüßt", GermanTagCorrections.lemma("grüßt", "VERB", "grüßt"));
    }

    @Test
    void capitalisedFormInsideTheSentenceKeepsItsForm() {
        assertEquals("Gerät", GermanTagCorrections.lemma("Gerät", "VERB", "Gerät", false), "mitten im Satz kein Verb");
        assertEquals("Hält", GermanTagCorrections.lemma("Hält", "VERB", "Hält", false));
        assertNull(GermanTagCorrections.lemma("Bereitzustellen", "VERB", null, false));
        assertEquals("halten", GermanTagCorrections.lemma("Hält", "VERB", "Hält", true), "am Satzanfang groß");
        assertEquals("geraten", GermanTagCorrections.lemma("gerät", "VERB", "gerät", false), "klein mitten im Satz");
        assertEquals("halten", GermanTagCorrections.lemma("Hält", "VERB", "halten", false), "Grundform von OpenNLP bleibt");
    }

    @Test
    void everyInflectedFormOfAListedNominalizedAdjectiveHasOneBaseForm() {
        for (String form : new String[] {"Bevollmächtigte", "Bevollmächtigter", "Bevollmächtigten", "Bevollmächtigtem",
                "Bevollmächtigtes"}) {
            assertEquals("bevollmächtigte", GermanTagCorrections.lemma(form, "NOUN", form.toLowerCase(), false), form);
        }
        assertEquals("vorgesetzte", GermanTagCorrections.lemma("Vorgesetzte", "NOUN", "vorgesetzt", false));
        assertEquals("vorgesetzte", GermanTagCorrections.lemma("Vorgesetzter", "NOUN", "vorgesetzte", false));
        assertEquals("sachverständige", GermanTagCorrections.lemma("Sachverständiger", "NOUN", "sachverständiger",
                false));
        assertEquals("datenschutzbeauftragte", GermanTagCorrections.lemma("Datenschutzbeauftragten", "NOUN",
                null, false), "auch als Ende einer Zusammensetzung");
    }

    @Test
    void ordinaryNounsOnTerDerAndErStayAsTheyAre() {
        for (String form : new String[] {"Vertreter", "Bearbeiter", "Berater", "Verwalter", "Gesellschafter", "Anwender",
                "Absender", "Spender", "Leiter", "Sachbearbeiter", "Mitarbeiter", "Empfänger", "Antrag", "Beamtenrecht"}) {
            assertNull(GermanTagCorrections.nominalizedAdjective(form), form);
            String lemma = form.toLowerCase();
            assertEquals(lemma, GermanTagCorrections.lemma(form, "NOUN", lemma, false), form);
        }
    }

    @Test
    void aNominalizedAdjectiveOutsideTheListStaysAsTheLemmatizerGivesIt() {
        assertNull(GermanTagCorrections.nominalizedAdjective("Bevorrechtigter"), "nicht in der Liste: Lücke");
        assertEquals("bevorrechtigter", GermanTagCorrections.lemma("Bevorrechtigter", "NOUN", "bevorrechtigter",
                false));
    }

    @Test
    void aListedNominalizedAdjectiveIsNeverAProperNoun() {
        String[] forms = {"Der", "Bevollmächtigte", "prüft", "Herr", "Wolf"};
        String[] upos = {"DET", "PROPN", "VERB", "NOUN", "PROPN"};
        GermanTagCorrections.correctTags(forms, upos);
        assertArrayEquals(new String[] {"DET", "NOUN", "VERB", "NOUN", "PROPN"}, upos);
    }
}
