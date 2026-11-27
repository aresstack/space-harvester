package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.application.query.LocalCoverage;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Lokal zuerst (Angelo 27.11.2026): Welche Spaces gelten als vollständig und aktuell lokal? */
class CatalogCoverageTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private final SpaceSyncJobs syncs = mock(SpaceSyncJobs.class);
    private CacheFreshness freshness = CacheFreshness.DEFAULT;
    private final CatalogCoverage coverage = new CatalogCoverage(syncs, () -> freshness,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static SpaceCatalog catalog(String space, int known, int cached, int stale, Instant confirmedAt) {
        return new SpaceCatalog("wiki", space, SpaceCatalog.State.CATALOGED, known, cached, stale, 0, 1L,
                confirmedAt, confirmedAt);
    }

    @Test
    void aConfirmedSpaceWithAllContentAndNothingStaleIsCurrent() {
        assertThat(coverage.current(catalog("DEV", 10, 10, 0, NOW.minusSeconds(60)))).isTrue();
        assertThat(coverage.current(catalog("DEV", 10, 9, 0, NOW))).as("Inhalt fehlt").isFalse();
        assertThat(coverage.current(catalog("DEV", 10, 10, 1, NOW))).as("veraltet gemeldet").isFalse();
        // Gesperrte Seiten fehlen schon in der Zahl der bekannten Seiten (LucenePageCache.withhold): eine lesbare Seite
        // ohne Inhalt neben einer gesperrten ist nicht abgedeckt.
        assertThat(coverage.current(new SpaceCatalog("wiki", "DEV", SpaceCatalog.State.CATALOGED, 1, 0, 0, 1, 1L,
                NOW, NOW))).as("lesbare Seite ohne Inhalt neben gesperrter").isFalse();
        assertThat(coverage.current(new SpaceCatalog("wiki", "DEV", SpaceCatalog.State.PARTIAL, 10, 10, 0, 0,
                null, null, null))).as("nie bestätigt").isFalse();

        freshness = new CacheFreshness(Duration.ofHours(1), true);
        assertThat(coverage.current(catalog("DEV", 10, 10, 0, NOW.minus(Duration.ofHours(2)))))
                .as("älter als die eingestellte Frist").isFalse();
        assertThat(coverage.current(catalog("DEV", 10, 10, 0, NOW.minus(Duration.ofMinutes(30))))).isTrue();
    }

    @Test
    void theWholeSourceCountsOnlyWhenEverySpaceIsCovered() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        when(source.partitions()).thenReturn(List.of(new Partition("DEV", "Dev", null),
                new Partition("OPS", "Ops", null)));
        when(syncs.confirmedSpaces()).thenReturn(List.of(new SyncBaselineStore.SpaceRef("wiki", "DEV")));
        when(syncs.catalog("wiki", "DEV")).thenReturn(catalog("DEV", 3, 3, 0, NOW));

        LocalCoverage.Coverage all = coverage.coverage(source, SearchSpaces.all());
        assertThat(all.all()).isFalse();
        assertThat(all.spaces()).containsExactly("DEV");

        LocalCoverage.Coverage dev = coverage.coverage(source, new SearchSpaces(Set.of("DEV"), Set.of()));
        assertThat(dev.all()).isTrue();
    }
}
