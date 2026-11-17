package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.changelog.ChangeLog;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.standing.ChangeLogRecentChanges;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Laufzeit der Leseempfehlung beim Lesen des Änderungsprotokolls (ADR 0032 FL6, Auflage AT): gefülltes Protokoll mit
 * einigen zehntausend Zeilen in der echten Ablage, Auswahl nur in einem Space. Gemessene Zeit in
 * {@code build/reports/status-quo/leseempfehlung-protokoll.txt}.
 */
class ReadingAdviceChangeLogLaufzeitTest {

    private static final int PAGES_PER_SPACE = 4000;
    private static final int VERSIONS = 3;
    private static final List<String> SPACES = List.of("FOE", "OPS", "DEV");

    @TempDir
    Path directory;

    @Test
    void aFilledChangeLogIsReadOnlyForTheSelection() throws IOException {
        int rows = 0;
        try (LucenePageCache cache = LucenePageCache.open(directory)) {
            for (int version = 1; version <= VERSIONS; version++) {
                for (String source : List.of("wiki", "other")) {
                    for (String space : SPACES) {
                        String v = Integer.toString(version);
                        try (LucenePageCache.Batch batch = cache.begin()) {
                            for (int p = 0; p < PAGES_PER_SPACE; p++) {
                                String id = space + "-" + p;
                                ChangeTrigger.MANUAL.run(() -> batch.save(page(source, id, space, null, v,
                                        json("{\"v\":" + v + "}"))));
                            }
                            batch.commit();
                        }
                        rows += PAGES_PER_SPACE;
                    }
                }
            }
            Supplier<List<String>> sources = () -> List.of("wiki", "other");
            ChangeLog log = new ChangeLog(cache, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                    key -> Optional.empty(), sources);
            AccessContext caller = AccessContext.technicalClient("desktop");
            ChangeLogRecentChanges changes = new ChangeLogRecentChanges(log);
            Instant from = Instant.EPOCH;
            Map<String, Set<String>> oneSpace = Map.of("wiki", Set.of("FOE"));
            Set<String> twoSpaces = new HashSet<>(List.of("FOE", "OPS"));
            changes.since(caller, oneSpace, from, () -> false);

            long bestSpace = Long.MAX_VALUE;
            long bestSource = Long.MAX_VALUE;
            Map<PageKey, Instant> space = null;
            Map<PageKey, Instant> source = null;
            for (int round = 0; round < 3; round++) {
                long start = System.nanoTime();
                space = changes.since(caller, oneSpace, from, () -> false);
                bestSpace = Math.min(bestSpace, System.nanoTime() - start);
                start = System.nanoTime();
                source = changes.since(caller, Map.of("wiki", twoSpaces), from, () -> false);
                bestSource = Math.min(bestSource, System.nanoTime() - start);
            }
            log.close();

            assertThat(space).hasSize(PAGES_PER_SPACE).allSatisfy((key, at) -> {
                assertThat(key.sourceId()).isEqualTo("wiki");
                assertThat(key.pageId()).startsWith("FOE-");
            });
            assertThat(source).as("zwei Spaces → ganze Quelle").hasSize(PAGES_PER_SPACE * SPACES.size());
            String line = rows + " Protokollzeilen (2 Quellen, je 3 Spaces, " + VERSIONS + " Fassungen): "
                    + bestSpace / 1_000_000 + " ms für einen Space (" + space.size() + " Seiten), "
                    + bestSource / 1_000_000 + " ms für eine ganze Quelle (" + source.size()
                    + " Seiten), bester von 3 Läufen";
            Path report = Path.of("build", "reports", "status-quo", "leseempfehlung-protokoll.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, line + "\n");
            System.out.println("Leseempfehlung, Protokoll: " + line);
            assertThat(bestSpace / 1_000_000).as("großzügige Obergrenze").isLessThan(20_000);
        }
    }
}
