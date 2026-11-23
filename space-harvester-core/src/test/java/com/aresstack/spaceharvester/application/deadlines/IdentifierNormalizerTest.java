package com.aresstack.spaceharvester.application.deadlines;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierNormalizerTest {

    @Test
    void groupedAndCompactDigitsNormalizeAlike() {
        // Erfundene Kennzeichen.
        assertThat(IdentifierNormalizer.digits("012 34 567")).contains("01234567");
        assertThat(IdentifierNormalizer.digits("01234567")).contains("01234567");
        assertThat(IdentifierNormalizer.digits("09 87-6543")).contains("09876543");
    }

    @Test
    void anythingButDigitsAndSeparatorsIsNotGuessed() {
        assertThat(IdentifierNormalizer.digits("12 AB 34")).isEmpty();
        assertThat(IdentifierNormalizer.digits(" - ")).isEmpty();
        assertThat(IdentifierNormalizer.digits(null)).isEmpty();
    }

    @Test
    void germanDatesBecomeIsoAndInvalidOnesStayEmpty() {
        assertThat(IdentifierNormalizer.germanDate("03.02.2031")).contains(LocalDate.of(2031, 2, 3));
        assertThat(IdentifierNormalizer.germanDate("3. 2. 2031")).contains(LocalDate.of(2031, 2, 3));
        assertThat(IdentifierNormalizer.germanDate("31.02.2031")).isEmpty();
        assertThat(IdentifierNormalizer.germanDate("Februar 2031")).isEmpty();
    }

    @Test
    void fileReferenceKeepsCaseAndSquashesSpace() {
        assertThat(IdentifierNormalizer.fileReference("  ZZ 9 -  X 1234/56 ")).isEqualTo("ZZ 9 - X 1234/56");
    }
}
