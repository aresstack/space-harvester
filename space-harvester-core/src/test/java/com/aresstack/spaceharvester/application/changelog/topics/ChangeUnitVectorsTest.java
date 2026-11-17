package com.aresstack.spaceharvester.application.changelog.topics;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Kind;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Nature;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Occurrence;
import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Role;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Vektoren der Änderungseinheiten: nur freigegeben, in Stapeln, mit Obergrenze und Ablage. */
class ChangeUnitVectorsTest {

    private final List<String> sent = new ArrayList<>();
    private final AtomicInteger checkpoints = new AtomicInteger();
    private final EmbeddingProvider provider = new EmbeddingProvider() {
        @Override
        public String modelId() {
            return "test@1";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            sent.addAll(texts);
            return texts.stream().map(text -> new float[] {text.length(), 1f}).toList();
        }
    };

    @Test
    void onlyReleasedUnitsWithTextAreSentAndStoredVectorsAreReused() {
        ChangeUnitVectors.Cache cache = ChangeUnitVectors.Cache.inMemory();
        List<ChangeUnit> units = List.of(unit("a", "Neue Frist von 14 Tagen.", true, Nature.EDIT),
                unit("b", "Geheimer Text.", false, Nature.EDIT),
                unit("c", "Intranet statt Intranett.", true, Nature.CORRECTION));

        ChangeUnitVectors.Result first = new ChangeUnitVectors(provider, cache).embed(units, 100, job());
        ChangeUnitVectors.Result again = new ChangeUnitVectors(provider, cache).embed(units, 100, job());

        assertThat(first.vectors()).containsOnlyKeys("a");
        assertThat(sent).containsExactly("Neue Frist von 14 Tagen.");
        assertThat(again.vectors()).containsOnlyKeys("a");
        assertThat(first.modelId()).isEqualTo("test@1");
    }

    @Test
    void theBudgetLimitsNewEmbeddingsAndEachBatchHasACheckpoint() {
        List<ChangeUnit> units = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            units.add(unit("u" + i, "Satz Nummer " + i + ".", true, Nature.EDIT));
        }

        ChangeUnitVectors.Result result = new ChangeUnitVectors(provider, ChangeUnitVectors.Cache.inMemory())
                .embed(units, 40, job());

        assertThat(result.vectors()).hasSize(40);
        assertThat(result.skipped()).isEqualTo(30);
        assertThat(checkpoints).hasValue(2);
    }

    @Test
    void withoutAServiceThereAreNoVectors() {
        ChangeUnitVectors.Result result = new ChangeUnitVectors(null, ChangeUnitVectors.Cache.inMemory())
                .embed(List.of(unit("a", "Text.", true, Nature.EDIT)), 10, job());

        assertThat(result.modelId()).isNull();
        assertThat(result.vectors()).isEmpty();
        assertThat(result.skipped()).isEqualTo(1);
    }

    private JobContext job() {
        return new JobContext() {
            @Override
            public String jobId() {
                return "job";
            }

            @Override
            public void checkpoint() {
                checkpoints.incrementAndGet();
            }

            @Override
            public boolean cancelRequested() {
                return false;
            }

            @Override
            public void discovered(long pages) {
            }

            @Override
            public void processed(long pages) {
            }

            @Override
            public void written(long pages) {
            }

            @Override
            public void failed(long pages) {
            }
        };
    }

    private static ChangeUnit unit(String id, String text, boolean released, Nature nature) {
        Occurrence occurrence = new Occurrence(new PageKey("wiki", id), "DEV", "Seite", List.of(), null, null,
                List.of(), Instant.parse("2026-11-16T08:00:00Z"), Role.CHANGED, 0, 0);
        return new ChangeUnit(id, Kind.PARAGRAPH_ADDED, nature, null, text, List.of(), List.of(text),
                List.of(occurrence), Set.of(), text.length(), released);
    }
}
