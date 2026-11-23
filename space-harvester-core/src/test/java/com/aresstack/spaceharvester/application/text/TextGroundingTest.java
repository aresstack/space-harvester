package com.aresstack.spaceharvester.application.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextGroundingTest {

    private static final String TEXT = "Der Vorschuss ist bis zum 31.03.2031 auszugleichen. Danach gilt die Frist nicht mehr.";

    @Test
    void aQuoteThatOccursOnceIsFoundEvenWithoutHint() {
        TextGrounding.Result result = TextGrounding.ground(TEXT, null, "bis zum 31.03.2031",
                new TextSpan(0, TEXT.length()));
        assertThat(result).isInstanceOfSatisfying(TextGrounding.Grounded.class, found -> {
            assertThat(found.text().text()).isEqualTo("bis zum 31.03.2031");
            assertThat(found.text().span()).isEqualTo(new TextSpan(TEXT.indexOf("bis"), TEXT.indexOf(" auszu")));
        });
    }

    @Test
    void aWrongHintIsRepairedByTheSingleOccurrence() {
        TextGrounding.Result result = TextGrounding.ground(TEXT, new TextSpan(0, 3), "auszugleichen.",
                new TextSpan(0, TEXT.length()));
        assertThat(result).isInstanceOfSatisfying(TextGrounding.Grounded.class,
                found -> assertThat(found.text().text()).isEqualTo("auszugleichen."));
    }

    @Test
    void missingAmbiguousAndInventedQuotesAreRejected() {
        TextSpan all = new TextSpan(0, TEXT.length());
        assertThat(TextGrounding.ground(TEXT, null, "", all)).isEqualTo(
                new TextGrounding.Rejected(TextGrounding.Reason.MISSING));
        assertThat(TextGrounding.ground(TEXT, null, "bis zum 30.06.2031", all)).isEqualTo(
                new TextGrounding.Rejected(TextGrounding.Reason.NOT_FOUND));
    }

    @Test
    void twoOccurrencesAreAmbiguous() {
        String text = "Frist A gilt. Frist A gilt.";
        assertThat(TextGrounding.ground(text, null, "Frist A", new TextSpan(0, text.length()))).isEqualTo(
                new TextGrounding.Rejected(TextGrounding.Reason.AMBIGUOUS));
    }

    @Test
    void theWindowLimitsTheSearchAndTheHint() {
        String first = "Frist A gilt.";
        String text = first + " Frist A gilt.";
        TextSpan second = new TextSpan(first.length(), text.length());
        assertThat(TextGrounding.ground(text, new TextSpan(0, 7), "Frist A", second))
                .isInstanceOfSatisfying(TextGrounding.Grounded.class,
                        found -> assertThat(found.text().span().start()).isEqualTo(first.length() + 1));
    }

    @Test
    void comparisonIgnoresCaseSpaceAndEdgePunctuation() {
        assertThat(TextGrounding.comparable("  „Bis   zum Ende.“ ")).isEqualTo("bis zum ende");
    }
}
