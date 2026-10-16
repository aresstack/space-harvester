package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
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
    void aLockedIndexPreventsTheStart() {
        try (LucenePageCache held = LucenePageCache.open(directory.resolve(CacheConfiguration.PAGES))) {
            runner.withPropertyValues("space-harvester.cache.directory=" + directory)
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasStackTraceContaining("von einem anderen Prozess geöffnet"));
        }
    }
}
