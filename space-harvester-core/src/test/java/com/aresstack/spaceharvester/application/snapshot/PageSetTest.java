package com.aresstack.spaceharvester.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Eine feste Seitenmenge für Filter (ADR 0029): Prüfung je Seite, Fingerabdruck nur nach Inhalt. */
class PageSetTest {

    @Test
    void aPageBelongsOnlyInItsOwnSourceAndEmptySourcesFallAway() {
        PageSet set = PageSet.of(Map.of("wiki", List.of("1", "2"), "intranet", List.of("2"), "leer", List.of()));

        assertThat(set.contains("wiki", "1")).isTrue();
        assertThat(set.contains(new PageKey("intranet", "2"))).isTrue();
        assertThat(set.contains("intranet", "1")).isFalse();
        assertThat(set.contains("fremd", "1")).isFalse();
        assertThat(set.sourceIds()).containsExactly("intranet", "wiki");
        assertThat(set.pagesOf("leer")).isEmpty();
        assertThat(set.size()).isEqualTo(3);
        assertThat(PageSet.of(Map.of("leer", List.of()))).isSameAs(PageSet.EMPTY);
        assertThat(PageSet.EMPTY.isEmpty()).isTrue();
    }

    @Test
    void theFingerprintDependsOnlyOnTheContentAndNamesNoPage() {
        PageSet one = PageSet.of(Map.of("wiki", List.of("1", "2"), "intranet", List.of("7")));
        PageSet same = PageSet.ofKeys(List.of(new PageKey("intranet", "7"), new PageKey("wiki", "2"),
                new PageKey("wiki", "1")));

        assertThat(same).isEqualTo(one).hasSameHashCodeAs(one);
        assertThat(same.fingerprint()).isEqualTo(one.fingerprint()).hasSize(24);
        assertThat(PageSet.of(Map.of("wiki", Set.of("1", "2", "3"))).fingerprint()).isNotEqualTo(one.fingerprint());
        // Dieselbe ID in einer anderen Quelle ist eine andere Seite.
        assertThat(PageSet.of(Map.of("wiki", Set.of("12"))).fingerprint())
                .isNotEqualTo(PageSet.of(Map.of("wiki1", Set.of("2"))).fingerprint())
                .isNotEqualTo(PageSet.of(Map.of("wiki", Set.of("1", "2"))).fingerprint());
        assertThat(one.toString()).doesNotContain("wiki", "intranet").contains("3 Seiten");
    }
}
