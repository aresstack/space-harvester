package com.aresstack.spaceharvester.application.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HitRelevanceTest {

    @Test
    void titleCountsMoreThanSnippetAndExactTitleMost() {
        double exact = HitRelevance.of("Betriebshandbuch", "Betriebshandbuch", "");
        double inTitle = HitRelevance.of("Betriebshandbuch", "Betriebshandbuch Teil 2", "");
        double inSnippet = HitRelevance.of("Betriebshandbuch", "Release-Plan", "siehe Betriebshandbuch");
        double none = HitRelevance.of("Betriebshandbuch", "Release-Plan", "nichts");
        assertThat(exact).isGreaterThan(inTitle);
        assertThat(inTitle).isGreaterThan(inSnippet);
        assertThat(inSnippet).isGreaterThan(none);
        assertThat(none).isZero();
    }

    @Test
    void ignoresCaseAccentsAndPunctuation() {
        assertThat(HitRelevance.of("ausgangsvalidierung iar", "Ausgangsvalidierung (IAR)", ""))
                .isEqualTo(HitRelevance.of("Ausgangsvalidierung IAR", "Ausgangsvalidierung IAR", "")
                        - HitRelevance.EXACT_TITLE + HitRelevance.EXACT_TITLE);
        assertThat(HitRelevance.of("Café", "cafe", "")).isEqualTo(HitRelevance.of("cafe", "cafe", ""));
        assertThat(HitRelevance.of("", "x", "y")).isZero();
    }
}
