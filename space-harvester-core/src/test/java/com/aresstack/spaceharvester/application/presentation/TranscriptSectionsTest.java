package com.aresstack.spaceharvester.application.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Abschnitte eines Video-Transkripts im Format der App. */
class TranscriptSectionsTest {

    private static final String TRANSCRIPT = "[00:00:00 – 00:00:28] Willkommen zum Vortrag.\n\n"
            + "[00:00:28 – 00:00:57] Heute geht es um Kuberneds.\n\n"
            + "[00:00:57 – 00:01:25] Erste Folie: Architektur.\n\n";

    @Test
    void parsesRangesAndTextAndRendersTheSameFormat() {
        List<TranscriptSections.Section> sections = TranscriptSections.parse(TRANSCRIPT);
        assertThat(sections).hasSize(3);
        assertThat(sections.get(1).startMillis()).isEqualTo(28_000);
        assertThat(sections.get(1).endMillis()).isEqualTo(57_000);
        assertThat(sections.get(1).text()).isEqualTo("Heute geht es um Kuberneds.");
        assertThat(TranscriptSections.render(sections)).isEqualTo(TRANSCRIPT);
    }

    @Test
    void olderTranscriptsWithOnlyStartsEndWhereTheNextBegins() {
        List<TranscriptSections.Section> sections = TranscriptSections.parse("[00:00:05] eins\n\n[00:00:40] zwei");
        assertThat(sections.get(0).endMillis()).isEqualTo(40_000);
        assertThat(sections.get(1).endMillis()).isEqualTo(40_000);
    }

    @Test
    void textWithoutTimesIsOneSectionFromTheStart() {
        assertThat(TranscriptSections.parse("nur Text")).singleElement()
                .satisfies(section -> assertThat(section.startMillis()).isZero());
        assertThat(TranscriptSections.parse("  ")).isEmpty();
        assertThat(TranscriptSections.parse(null)).isEmpty();
    }

    @Test
    void betweenTakesEverySectionOverlappingTheSlideTime() {
        List<TranscriptSections.Section> sections = TranscriptSections.parse(TRANSCRIPT);
        assertThat(TranscriptSections.between(sections, 30_000, 60_000)).extracting(TranscriptSections.Section::text)
                .containsExactly("Heute geht es um Kuberneds.", "Erste Folie: Architektur.");
        assertThat(TranscriptSections.between(sections, 90_000, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void chunksKeepSectionsWholeAndRespectTheLimit() {
        List<TranscriptSections.Section> sections = TranscriptSections.parse(TRANSCRIPT);
        assertThat(TranscriptSections.chunks(sections, 80)).hasSize(3);
        assertThat(TranscriptSections.chunks(sections, 10_000)).singleElement()
                .satisfies(chunk -> assertThat(chunk).hasSize(3));
    }
}
