package com.aresstack.spaceharvester.application.enrichment.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

class GermanTextTest {

    @Test
    void splitsSentencesAtLineBreaksCellsAndFullStopsButNotAfterAbbreviationsOrNumbers() {
        GermanText text = analyze("Überschrift\nDer Dienst läuft z. B. am 17. Oktober. Danach ruht er! Ok?\nA | B");

        assertThat(text.sentences()).extracting(GermanTextTest::words).containsExactly(
                "Überschrift", "Der Dienst läuft z . B . am 17 . Oktober .", "Danach ruht er !", "Ok ?", "A", "B");
    }

    @Test
    void classifiesWordsByLexiconSpellingAndPosition() {
        Sentence sentence = analyze("Die neue API liefert ABC-1234 und pageId an den Kunden.").sentences().get(0);

        assertThat(sentence.tokens()).extracting(Token::wordClass).containsExactly(WordClass.DETERMINER,
                WordClass.OTHER, WordClass.ACRONYM, WordClass.OTHER, WordClass.IDENTIFIER, WordClass.CONJUNCTION,
                WordClass.IDENTIFIER, WordClass.PREPOSITION, WordClass.DETERMINER, WordClass.NOUN,
                WordClass.PUNCTUATION);
    }

    @Test
    void treatsACapitalizedFirstWordAsVerbBeforeSieOrWhenItAlsoAppearsInLowerCase() {
        assertThat(analyze("Starten Sie den Dienst.").sentences().get(0).tokens().get(0).wordClass())
                .isEqualTo(WordClass.OTHER);
        assertThat(analyze("Prüfen den Eintrag.\nBitte prüfen.").sentences().get(0).tokens().get(0).wordClass())
                .isEqualTo(WordClass.OTHER);
        assertThat(analyze("Server neu starten.").sentences().get(0).tokens().get(0).wordClass())
                .isEqualTo(WordClass.NOUN);
    }

    @Test
    void findsNounGroupsWithDeterminersAdjectivesAndMultiWordTerms() {
        Sentence sentence = analyze("Die zuständige Sachbearbeitung prüft im Confluence Data Center alle eingereichten"
                + " Belege mit neuen Regeln.").sentences().get(0);

        assertThat(sentence.nounGroups()).extracting(NounGroup::term)
                .containsExactly("Sachbearbeitung", "Confluence Data Center", "Belege", "Regeln");
        assertThat(sentence.nounGroups().get(0).adjectives(sentence)).extracting(Token::text)
                .containsExactly("zuständige");
        assertThat(sentence.nounGroups().get(2).adjectives(sentence)).extracting(Token::text)
                .containsExactly("eingereichten");
        assertThat(sentence.nounGroups().get(3).adjectives(sentence)).extracting(Token::text)
                .containsExactly("neuen");
    }

    @Test
    void doesNotTakeAVerbBeforeANounWithoutDeterminerForAnAdjective() {
        Sentence sentence = analyze("Dann starten Server neu.").sentences().get(0);

        assertThat(sentence.nounGroups()).singleElement().satisfies(group -> {
            assertThat(group.term()).isEqualTo("Server");
            assertThat(group.adjectives(sentence)).isEmpty();
        });
    }

    @Test
    void skipsEnglishSentencesAndSaysSo() {
        GermanText text = analyze("The service is down and the users are waiting.\nDer Dienst ist erreichbar.");

        assertThat(text.sentences()).hasSize(1);
        assertThat(text.warnings()).containsExactly("1 Satz nicht deutsch, übersprungen");
    }

    @Test
    void handlesEmptyTextAndPunctuationOnly() {
        assertThat(analyze("").sentences()).isEmpty();
        assertThat(analyze("…\n--- | !").sentences()).isEmpty();
    }

    private static GermanText analyze(String text) {
        return GermanText.analyze(new PageText("t/v1", text, List.of()));
    }

    private static String words(Sentence sentence) {
        return String.join(" ", sentence.tokens().stream().map(Token::text).toList());
    }
}
