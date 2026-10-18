package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Fälle aus {@code KnowledgeResearchTest} und {@code DirectModeAgentTest} des Enterprise AI Client. */
class SearchTermsTest {

    private static final String LONG_REQUEST = "Wie wird in HaVdB der PoC mit Hashicorp Vault für PIMA beschrieben?";

    @Test
    void literalQueryKeepsIdentifiersInTheirSpelling() {
        assertThat(SearchTerms.literalQuery(LONG_REQUEST)).isEqualTo("HaVdB PoC PIMA");
        assertThat(SearchTerms.literalQuery("Was macht BS-PIMA mit Hashicorp Vault?"))
                .isEqualTo("BS-PIMA Hashicorp Vault");
        assertThat(SearchTerms.literalQuery("Was bedeutet RSL in IAR?")).isEqualTo("RSL IAR");
        assertThat(SearchTerms.literalQuery("Finde heraus, welche Bedeutung IRR im internen Unternehmenswissen hat."))
                .isEqualTo("IRR");
    }

    @Test
    void aLiteralQueryIsConciseAndASentenceIsNot() {
        assertThat(SearchTerms.isConcise(SearchTerms.literalQuery(LONG_REQUEST))).isTrue();
        assertThat(SearchTerms.isConcise("PIMA Hashicorp Vault")).isTrue();
        assertThat(SearchTerms.isConcise(LONG_REQUEST)).isFalse();
        assertThat(SearchTerms.isConcise("  ")).isFalse();
        assertThat(SearchTerms.isConcise(null)).isFalse();
    }

    @Test
    void keywordQueriesAreTheFirstKeywordsAloneAndTheFirstTwoTogether() {
        assertThat(SearchTerms.keywordQueries("Kannst du mir was über IAR aus Confluence finden?", 4))
                .containsExactly("IAR");
        assertThat(SearchTerms.keywordQueries(LONG_REQUEST, 4)).containsExactly("wird", "in", "HaVdB", "wird in");
        assertThat(SearchTerms.keywordQueries("Urlaub Antrag Formular", 2)).containsExactly("Urlaub",
                "Urlaub Antrag");
    }

    @Test
    void withoutKeywordsTheShortenedMessageIsTheQuery() {
        assertThat(SearchTerms.keywordQueries("was ist das und wie ist der mit dir", 4))
                .containsExactly("was ist das und wie ist");
        assertThat(SearchTerms.keywordQueries("  ", 4)).isEmpty();
    }

    @Test
    void identifiersAreStrongerThanNamesAndNamesStrongerThanWords() {
        assertThat(SearchTerms.strength("HaVdB")).isEqualTo(2);
        assertThat(SearchTerms.strength("BS-PIMA")).isEqualTo(2);
        assertThat(SearchTerms.strength("X1")).isEqualTo(2);
        assertThat(SearchTerms.strength("Vault")).isEqualTo(1);
        assertThat(SearchTerms.strength("vault")).isZero();
    }

    @Test
    void requestWordsDoNotBecomeKeywords() {
        assertThat(SearchTerms.withoutRequestWords(LONG_REQUEST)).isEqualTo("Wie HaVdB der PoC mit Hashicorp Vault "
                + "für PIMA");
        assertThat(SearchTerms.keywordQueries(SearchTerms.withoutRequestWords(LONG_REQUEST), 4))
                .containsExactly("HaVdB", "PoC", "Hashicorp", "HaVdB PoC");
    }
}
