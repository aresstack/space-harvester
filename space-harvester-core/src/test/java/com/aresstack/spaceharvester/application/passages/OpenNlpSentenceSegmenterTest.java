package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** OpenNLP nur für Satzgrenzen, mit dem deutschen Satzmodell aus dem Klassenpfad. */
class OpenNlpSentenceSegmenterTest {

    @Test
    void loadsTheGermanSentenceModelAndKeepsSentencesVerbatim() {
        SentenceSegmenter segmenter = OpenNlpSentenceSegmenter.german();
        String paragraph = "Der Dienst startet um 8 Uhr. Danach prüft er z. B. die Verbindung. Läuft alles?";

        assertThat(segmenter.id()).isEqualTo("opennlp-de");
        assertThat(segmenter.sentences(paragraph)).allSatisfy(sentence -> assertThat(paragraph).contains(sentence))
                .contains("Läuft alles?").hasSizeGreaterThanOrEqualTo(2);
    }
}
