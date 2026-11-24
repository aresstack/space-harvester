package com.aresstack.spaceharvester.app.ui.content.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Fundstellen im gezeigten Text (Angelo 24.11.2026: „das Interessante ist ja, wo es steht“). Texte erfunden. */
class PassagesTest {

    private static final String TEXT = "Bescheid vom 3. März\nDie Frist endet am\n31.12.2026 mit Ablauf des Tages.";

    @Test
    void theExcerptIsFoundAcrossLineBreaksAndCase() {
        int[] at = Passages.locate(TEXT, "die frist endet am 31.12.2026", -1, -1);
        assertThat(at).isNotNull();
        assertThat(TEXT.substring(at[0], at[1])).isEqualTo("Die Frist endet am\n31.12.2026");
    }

    @Test
    void anExcerptMissingFromTheTextIsNotGuessedFromTheRange() {
        assertThat(Passages.locate(TEXT, "Widerspruch", 0, 8)).isNull();
    }

    @Test
    void withoutExcerptTheRangeIsUsedWhenItFitsTheText() {
        assertThat(Passages.locate(TEXT, "", 0, 8)).containsExactly(0, 8);
        assertThat(Passages.locate(TEXT, " ", -1, -1)).isNull();
        assertThat(Passages.locate(TEXT, null, 5, TEXT.length() + 1)).isNull();
        assertThat(Passages.locate("", "Frist", -1, -1)).isNull();
    }

    @Test
    void specialCharactersInTheExcerptAreTakenLiterally() {
        assertThat(Passages.locate("Az. (II.3) – § 6 VO", "(ii.3) – §", -1, -1)).containsExactly(4, 14);
    }
}
