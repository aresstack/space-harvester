package com.aresstack.spaceharvester.app.ui.deck.visual;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Das Ziel aus den Knoten-Beschriftungen, die der Konverter tatsächlich erzeugt. */
class CallTargetsTest {

    @Test
    void nameFromConverterLabels() {
        assertThat(CallTargets.clean("CALLNAT SUBPROG")).isEqualTo("SUBPROG");
        assertThat(CallTargets.clean("SUBPROG\n(CALLNAT)")).isEqualTo("SUBPROG");
        assertThat(CallTargets.clean("SUBPROG\\n(CALLNAT)")).isEqualTo("SUBPROG");
        assertThat(CallTargets.clean("STEP1\nPGM=IEFBR14")).isEqualTo("IEFBR14");
        assertThat(CallTargets.clean("'SUB-PROG'")).isEqualTo("SUB-PROG");
        assertThat(CallTargets.clean("📦 IDCAMS")).isEqualTo("IDCAMS");
    }

    @Test
    void ndvTargetsComeFromTheSourceCacheInSearchOrder() {
        var store = new com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache.Store() {
            @Override
            public java.util.Map<String, com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache.Entry> load(
                    String server, String library) {
                return library.equals("KASSE") ? java.util.Map.of("KASSE001",
                        new com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache.Entry("KASSE", "KASSE001",
                                "NSN", "DEFINE DATA LOCAL\nEND-DEFINE\nEND", 3, "")) : java.util.Map.of();
            }

            @Override
            public void put(String server, com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache.Entry entry) {
            }

            @Override
            public void removeLibrary(String server, String library) {
            }
        };
        var cache = new com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache("h:1", store,
                new com.aresstack.spaceharvester.deck.ndv.analysis.NdvAccessRules());
        var lookup = new CallTargets.NdvLookup(new com.aresstack.spaceharvester.deck.ndv.analysis.NdvLibraryResolver(
                java.util.List.of("SYSTEM", "KASSE"), java.util.Map.of()), cache, "LAGER", "LAGER");

        assertThat(lookup.library("kasse001")).contains("KASSE");
        assertThat(lookup.source("kasse001")).hasValueSatisfying(
                text -> assertThat(text).startsWith("DEFINE DATA"));
        assertThat(lookup.library("UNBEKANNT")).as("sonst die eigene Bibliothek")
                .contains("LAGER");
        assertThat(lookup.source("UNBEKANNT")).isEmpty();
        assertThat(lookup.source(" ")).isEmpty();
    }
}
