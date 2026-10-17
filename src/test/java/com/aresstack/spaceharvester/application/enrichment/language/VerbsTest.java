package com.aresstack.spaceharvester.application.enrichment.language;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerbsTest {

    @Test
    void proposesBaseFormsFromSpellingAlone() {
        assertThat(Verbs.lemma("prüft", null)).isEqualTo("prüfen");
        assertThat(Verbs.lemma("liefert", null)).isEqualTo("liefern");
        assertThat(Verbs.lemma("arbeitet", null)).isEqualTo("arbeiten");
        assertThat(Verbs.lemma("geprüft", null)).isEqualTo("prüfen");
        assertThat(Verbs.lemma("eingereicht", null)).isEqualTo("einreichen");
        assertThat(Verbs.lemma("gespeichert", null)).isEqualTo("speichern");
        assertThat(Verbs.lemma("bearbeitet", null)).isEqualTo("bearbeiten");
        assertThat(Verbs.lemma("aktualisiert", null)).isEqualTo("aktualisieren");
        assertThat(Verbs.lemma("starten", null)).isEqualTo("starten");
        assertThat(Verbs.lemma("ruft", "auf")).isEqualTo("aufrufen");
        assertThat(Verbs.lemma("hat", null)).isEqualTo("haben");
    }

    @Test
    void leavesIrregularFormsWrongBecauseThereIsNoDictionary() {
        assertThat(Verbs.lemma("läuft", null)).isEqualTo("läufen");
    }
}
