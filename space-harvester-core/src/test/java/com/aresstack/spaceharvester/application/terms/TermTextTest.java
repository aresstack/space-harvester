package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermTextTest {

    @Test
    void dottedAcronymsSearchLikeTheirPlainForm() {
        assertThat(TermText.normalize("U.S.A.")).isEqualTo("usa");
        assertThat(TermText.normalize("U.S.A")).isEqualTo("usa");
        assertThat(TermText.normalize("USA")).isEqualTo("usa");
    }

    @Test
    void otherDotsStay() {
        assertThat(TermText.normalize("Version 1.2")).isEqualTo("version 1.2");
        assertThat(TermText.normalize("Ende.")).isEqualTo("ende.");
        assertThat(TermText.normalize("A.")).isEqualTo("a.");
        assertThat(TermText.normalize("1.2.3")).isEqualTo("1.2.3");
        assertThat(TermText.normalize("1.2.")).isEqualTo("1.2.");
    }
}
