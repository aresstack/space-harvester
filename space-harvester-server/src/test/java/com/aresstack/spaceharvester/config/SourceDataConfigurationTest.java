package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.facts.FactEditStore;
import com.aresstack.spaceharvester.application.glossary.Glossary;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobEventStore;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.RouteLookup;
import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceDataPurge;
import com.aresstack.spaceharvester.application.sync.InventoryLog;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.persistence.files.FileResolutionCache;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFillQueue;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** „Daten bereinigen“ im Dienst leert die Liste der KI-freigegebenen Seiten der Freigabeprüfung, auch ohne Cache. */
class SourceDataConfigurationTest {

    private final DefaultListableBeanFactory none = new DefaultListableBeanFactory();

    @Test
    void withoutCacheThePurgeEmptiesTheListOfTheReleaseCheck() {
        AiRelease release = new AiRelease(sourceId -> false, PageReleases.inMemory());
        PageKey wiki = new PageKey("wiki", "1");
        PageKey other = new PageKey("andere", "1");
        Instant now = Instant.parse("2026-11-13T22:00:00Z");
        release.pages().add(List.of(new PageReleases.Entry(wiki, "DEV", null, PageReleases.CHAT, now),
                new PageReleases.Entry(other, "OPS", null, PageReleases.CHAT, now)));
        Jobs jobs = new Jobs(new InMemoryJobStore(), new InMemoryJobEventStore(), RouteLookup.NONE,
                Executors.newVirtualThreadPerTaskExecutor(),
                new JobLimits(2, 5, Duration.ofHours(1), Duration.ofDays(1)), Clock.systemUTC());
        try {
            SourceDataPurge purge = new SourceDataConfiguration().sourceDataPurge(jobs,
                    ConfiguredSourceCatalog.of(List.of(), "test"), none(LuceneFillQueue.class), release,
                    none(LucenePageCache.class), none(LucenePageTextIndex.class), none(LuceneEnrichmentStore.class),
                    none(AssetStore.class), none(ExportArtifactWriter.class), none(TermRelations.class),
                    none(FileResolutionCache.class), none(FileResolutionCache.class), none(Glossary.class),
                    none(FactEditStore.class), none(InventoryLog.class), none(AcquisitionScheduler.class));

            assertThat(purge.purge("wiki").complete()).isTrue();

            assertThat(release.mayProcess(wiki)).isFalse();
            assertThat(release.mayProcess(other)).isTrue();
        } finally {
            jobs.close();
        }
    }

    private <T> ObjectProvider<T> none(Class<T> type) {
        return none.getBeanProvider(type);
    }
}
