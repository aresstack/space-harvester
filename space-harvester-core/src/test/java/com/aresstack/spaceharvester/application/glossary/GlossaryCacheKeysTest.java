package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.persistence.files.FileResolutionCache;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D4: Die Modellantworten des Glossars liegen dauerhaft im Datei-Cache; „Daten bereinigen“ einer Quelle trifft ihre
 * Läufe (Quelle, Space, Phase je Seite) und die globalen, nie die einer anderen Quelle. Erfundene Kennungen.
 */
class GlossaryCacheKeysTest {

    @TempDir
    Path directory;

    @Test
    void answersSurviveARestartAndPurgingASourceHitsOnlyItsRuns() {
        FileResolutionCache cache = new FileResolutionCache(directory.resolve(GlossaryClassification.CACHE_DIRECTORY));
        cache.replace("wiki-a\u0000", Map.of("f1", List.of("Wolkenpegel")));
        cache.replace("classify\u0000wiki-a\u0000DEV", Map.of("f2", List.of("Kieselgrund")));
        cache.replace("null\u0000", Map.of("f3", List.of("Ankerschein")));
        cache.replace("wiki-b\u0000", Map.of("f4", List.of("Schattenmarke")));

        FileResolutionCache reopened = new FileResolutionCache(
                directory.resolve(GlossaryClassification.CACHE_DIRECTORY));
        assertThat(reopened.find("f2")).contains(List.of("Kieselgrund"));

        reopened.removeSources(GlossaryClassification.cacheKeysOf("wiki-a"));

        assertThat(reopened.find("f1")).isEmpty();
        assertThat(reopened.find("f2")).isEmpty();
        assertThat(reopened.find("f3")).as("globale Läufe, an denen die Quelle beteiligt war").isEmpty();
        assertThat(reopened.find("f4")).contains(List.of("Schattenmarke"));
        assertThat(GlossaryClassification.cacheKeysOf("wiki").test("wiki-a\u0000")).isFalse();
    }
}
