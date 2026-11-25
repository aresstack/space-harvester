package com.aresstack.spaceharvester.deck.ftp;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CobolAnalysisAreaTest {
    @Test
    void normalizesHomeAndUsesOrderedProfileLibraries() {
        var area = new CobolAnalysisArea(" 'hlq.cobol' ", List.of("'hlq.cpy'", "HLQ.COMMON", "HLQ.CPY"));
        assertThat(area.home()).isEqualTo("HLQ.COBOL");
        assertThat(area.absoluteHome()).isEqualTo("'HLQ.COBOL'");
        assertThat(area.syslib()).containsExactly("HLQ.CPY", "HLQ.COMMON");
        assertThat(area.containsProgramDataset("'hlq.cobol'")).isTrue();
        assertThat(area.containsProgramDataset("HLQ.COBOL.SRC")).isTrue();
        assertThat(area.containsProgramDataset("HLQ.COBOL2")).isFalse();
        assertThat(area.containsProgramDataset("OTHER.COBOL")).isFalse();
        assertThat(area.containsProgramDataset("HLQ.COBOL(PGM)")).isFalse();
    }

    @Test
    void rejectsInvalidRootsAndMembers() {
        for (String invalid : List.of("", "''", "/", "HLQ.COBOL(PGM)", "HLQ.*", "HLQ..SRC", "HLQ/COBOL",
                "'HLQ.COBOL", "1HLQ.SRC", "HLQ.TOOLONG99", "A1234567.B1234567.C1234567.D1234567.E1234567.F")) {
            assertThatThrownBy(() -> new CobolAnalysisArea(invalid, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(CobolAnalysisArea.configured(" ", List.of())).isEmpty();
        assertThat(CobolAnalysisArea.configured(null, List.of())).isEmpty();
    }
}
