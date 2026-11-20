package com.aresstack.spaceharvester.application.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Angelo 20.11.2026: Die Analysen einer Videoseite laufen über ihr Transkript wie über normalen Text, über die letzte
 * Korrektur, und fallen mit dem gelöschten Transkript weg.
 */
class TranscriptAnalysesTest {

    private static final PageKey KEY = new PageKey("bscw", "video-1");

    @Test
    void theTextIsTheTranscriptWithoutTimeMarksOneParagraphPerSection() {
        assertThat(VideoTranscriptTextProjector.text("[00:00:00 – 00:00:28] Heute geht es um Kubernetes.\n\n"
                + "[00:00:28 – 00:00:57] Und um Helm-Charts.\n\n"))
                .isEqualTo("Heute geht es um Kubernetes.\n\nUnd um Helm-Charts.");
        assertThat(VideoTranscriptTextProjector.text("[00:01:13] Älteres Format ohne Ende")).isEqualTo(
                "Älteres Format ohne Ende");
    }

    @Test
    void theProjectorReadsTheLatestTranscriptTextOfThePageVersion() {
        Assets assets = mock(Assets.class);
        PageSnapshot snapshot = snapshot();
        when(assets.latestVideoTranscriptText("bscw", "video-1", "r1"))
                .thenReturn(Optional.of("[00:00:00 – 00:00:10] Korrigiert."));

        assertThat(new VideoTranscriptTextProjector(() -> assets).project(snapshot)).hasValueSatisfying(text -> {
            assertThat(text.schema()).isEqualTo(VideoTranscriptTextProjector.SCHEMA);
            assertThat(text.text()).isEqualTo("Korrigiert.");
        });
        assertThat(new VideoTranscriptTextProjector(() -> null).project(snapshot)).as("Ablage steht noch nicht")
                .isEmpty();
        when(assets.latestVideoTranscriptText("bscw", "video-1", "r1")).thenReturn(Optional.empty());
        assertThat(new VideoTranscriptTextProjector(() -> assets).project(snapshot)).isEmpty();
    }

    @Test
    void aNewTextRecomputesAndAMissingTextRemovesTheAnalyses() {
        String[] transcript = {"Erste Fassung."};
        List<String> seen = new ArrayList<>();
        EnrichmentProcessor words = new EnrichmentProcessor() {
            @Override
            public String id() {
                return "words";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public EnrichmentOutput process(PageText text) {
                seen.add(text.text());
                return EnrichmentOutput.of("{}");
            }
        };
        PageTextProjector projector = snapshot -> Optional.ofNullable(transcript[0])
                .map(text -> new PageText("t", text, List.of()));
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        Enrichments enrichments = new Enrichments(projector, List.of(words), store, Clock.systemUTC());
        PageSnapshot snapshot = snapshot();
        List<PageKey> reported = new ArrayList<>();
        try (TranscriptAnalyses analyses = new TranscriptAnalyses(enrichments, key -> Optional.of(snapshot),
                Duration.ZERO)) {
            analyses.subscribe(reported::add);

            assertThat(analyses.update(KEY)).isTrue();
            assertThat(analyses.update(KEY)).as("aktuell: nichts neu").isTrue();
            transcript[0] = "Korrektur.";
            analyses.update(KEY);
            assertThat(seen).containsExactly("Erste Fassung.", "Korrektur.");
            assertThat(store.find(KEY)).extracting(Enrichment::processorId).containsExactly("words");

            transcript[0] = null;
            assertThat(analyses.update(KEY)).as("Transkript gelöscht").isFalse();
            assertThat(store.find(KEY)).isEmpty();
            assertThat(reported).hasSize(4).containsOnly(KEY);
        }
    }

    private static PageSnapshot snapshot() {
        PageSnapshot snapshot = mock(PageSnapshot.class);
        when(snapshot.key()).thenReturn(KEY);
        when(snapshot.revision()).thenReturn("r1");
        return snapshot;
    }
}
