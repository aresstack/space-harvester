package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobStore;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSyncBaselineStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Der Seiten-Cache entsteht nur mit konfiguriertem Verzeichnis und gibt seine Sperre beim Herunterfahren frei. */
class CacheConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfiguration.class);

    @TempDir
    Path directory;

    @Test
    void withoutDirectoryThereIsNoCache() {
        runner.run(context -> assertThat(context).doesNotHaveBean(SnapshotRepository.class));
    }

    @Test
    void withDirectoryTheCacheIsTheSnapshotRepository() {
        runner.withPropertyValues("space-harvester.cache.directory=" + directory,
                        "space-harvester.cache.max-payload-size=1MB")
                .run(context -> assertThat(context).hasSingleBean(SnapshotRepository.class)
                        .getBean(SnapshotRepository.class).isInstanceOf(LucenePageCache.class));

        // Nach dem Schließen des Kontexts ist der Index wieder frei.
        LucenePageCache.open(directory.resolve(CacheConfiguration.PAGES)).close();
    }

    @Test
    void withDirectoryTheQueryApiAndTheSyncReadAndWriteTheSameIndex() {
        runner.withPropertyValues("space-harvester.cache.directory=" + directory).run(context -> {
            assertThat(context).hasSingleBean(PageCacheReader.class).hasSingleBean(SyncBaselineStore.class);
            assertThat(context.getBean(PageCacheReader.class)).isInstanceOf(LucenePageCacheReader.class);
            assertThat(context.getBean(SyncBaselineStore.class)).isInstanceOf(LuceneSyncBaselineStore.class);
        });
        runner.run(context -> assertThat(context).doesNotHaveBean(PageCacheReader.class)
                .doesNotHaveBean(SyncBaselineStore.class));
    }

    @Test
    void withDirectoryJobsAreStoredInLucene() {
        // Reihenfolge wie beim Komponentenscan: CacheConfiguration vor JobsConfiguration.
        new ApplicationContextRunner().withUserConfiguration(CacheConfiguration.class, JobsConfiguration.class)
                .withPropertyValues("space-harvester.cache.directory=" + directory)
                .run(context -> assertThat(context).hasSingleBean(JobStore.class)
                        .getBean(JobStore.class).isInstanceOf(LuceneJobStore.class));
        new ApplicationContextRunner().withUserConfiguration(CacheConfiguration.class, JobsConfiguration.class)
                .run(context -> assertThat(context).getBean(JobStore.class).isInstanceOf(InMemoryJobStore.class));
    }

    @Test
    void aLockedIndexPreventsTheStart() {
        try (LucenePageCache held = LucenePageCache.open(directory.resolve(CacheConfiguration.PAGES))) {
            runner.withPropertyValues("space-harvester.cache.directory=" + directory)
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasStackTraceContaining("von einem anderen Prozess geöffnet"));
        }
    }
}
