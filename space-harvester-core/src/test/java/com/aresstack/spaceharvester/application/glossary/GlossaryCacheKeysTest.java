package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.AcronymExpansionModel;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.persistence.files.FileResolutionCache;
import java.time.Clock;
import java.util.Optional;
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

    @Test
    void withdrawingAiReleaseEmptiesTheBucketsOfThatSourceOnly() {
        // Auflage zu #1040 (ADR 0028): Entzug löscht die Modellantworten der Quelle, andere bleiben.
        FileResolutionCache cache = new FileResolutionCache(directory.resolve(GlossaryClassification.CACHE_DIRECTORY));
        cache.replace("classify\u0000wiki-a\u0000DEV", Map.of("f1", List.of("Kieselgrund")));
        cache.replace("classify\u0000wiki-a\u0000DEV\u0000subtree\u00004", Map.of("f2", List.of("Wolkenpegel")));
        cache.replace("classify\u0000wiki-b\u0000", Map.of("f3", List.of("Schattenmarke")));
        GlossaryClassification classification = new GlossaryClassification(mock(SourceCatalog.class),
                mock(TermIndex.class), new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                key -> Optional.empty(), key -> Optional.empty(), mock(Glossary.class), AcronymExpansionModel.NONE,
                cache, mock(Jobs.class), "1", GlossaryClassification.Budget.DEFAULT, Clock.systemUTC());

        classification.withdrawn(List.of(new PageKey("wiki-a", "4"), new PageKey("wiki-a", "7")));

        FileResolutionCache reopened = new FileResolutionCache(
                directory.resolve(GlossaryClassification.CACHE_DIRECTORY));
        assertThat(reopened.find("f1")).isEmpty();
        assertThat(reopened.find("f2")).isEmpty();
        assertThat(reopened.find("f3")).contains(List.of("Schattenmarke"));
    }
}
