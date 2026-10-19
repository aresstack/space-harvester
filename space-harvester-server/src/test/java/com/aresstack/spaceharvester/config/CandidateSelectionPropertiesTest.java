package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.query.CandidateSelectionStrategy;
import org.junit.jupiter.api.Test;

/** {@code space-harvester.search.candidate-selection} und {@code search.loads} (ADR 0016, Punkt 4). */
class CandidateSelectionPropertiesTest {

    @Test
    void withoutSettingsTheSelectionIsAdaptiveWithoutCap() {
        assertThat(QueryConfiguration.selection("", "")).isEqualTo(new CandidateSelectionStrategy.Adaptive(100));
    }

    @Test
    void searchLoadsAloneKeepsItsMeaningAsTopN() {
        assertThat(QueryConfiguration.selection(null, "5")).isEqualTo(new CandidateSelectionStrategy.TopN(5));
    }

    @Test
    void searchLoadsCapsTheChosenStrategy() {
        assertThat(QueryConfiguration.selection("adaptive", "20"))
                .isEqualTo(new CandidateSelectionStrategy.Adaptive(20));
        assertThat(QueryConfiguration.selection("order", "")).isEqualTo(new CandidateSelectionStrategy.SourceOrder(100));
        assertThat(QueryConfiguration.selection("top-n", "")).isEqualTo(new CandidateSelectionStrategy.TopN(100));
    }

    @Test
    void unknownValuesStopTheStart() {
        assertThatThrownBy(() -> QueryConfiguration.selection("beste", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryConfiguration.selection("", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryConfiguration.selection("", "viele"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
