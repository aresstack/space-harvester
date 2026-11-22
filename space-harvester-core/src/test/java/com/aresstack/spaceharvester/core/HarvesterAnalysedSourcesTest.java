package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Welche Quellen der Knowledge Graph liest (#1090, Abnahme W6); Quellen erfunden. */
class HarvesterAnalysedSourcesTest {

    @Test
    void onlyConfiguredSourcesWithoutAnOpenWithdrawalAreRead() {
        Set<String> configured = Set.of("wiki", "akte");
        Map<String, String> open = new HashMap<>();
        Predicate<String> sources = Harvester.analysedSources(configured::contains, open::get);

        assertThat(sources.test("wiki")).isTrue();
        assertThat(sources.test("fremd")).as("nicht eingerichtet").isFalse();

        open.put("wiki", "job-1");
        assertThat(sources.test("wiki")).as("Löschen offen (auch wartend oder in Selbstheilung)").isFalse();
        assertThat(sources.test("akte")).isTrue();

        open.remove("wiki");
        assertThat(sources.test("wiki")).as("nach dem Löschen bzw. Abbruch wieder").isTrue();
    }
}
