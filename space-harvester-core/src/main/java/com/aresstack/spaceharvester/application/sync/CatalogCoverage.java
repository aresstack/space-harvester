package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.application.query.LocalCoverage;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Abdeckung aus dem Katalog der Abgleiche (Angelo 27.11.2026, „lokal zuerst“). Ein Space gilt als lokal vollständig
 * und aktuell, wenn
 * <ul>
 *   <li>ein Abgleich seine Generation bestätigt hat ({@link SpaceCatalog.State#CATALOGED}),</li>
 *   <li>jede bekannte Seite mit Inhalt im Cache liegt (gesperrte Seiten zählt der Katalog schon nicht mit) und</li>
 *   <li>keine Seite als veraltet gemeldet ist; mit einer Frist {@code search.cache-max-age} über null außerdem nur, wenn
 *       die Bestätigung jünger ist als diese Frist.</li>
 * </ul>
 * Nur Lesen der Ablage; synchronisiert wird nie. Die Spaces der Quelle werden nur aufgelistet, wenn sie überhaupt
 * einen abgedeckten Space hat.
 */
public final class CatalogCoverage implements LocalCoverage {

    private static final System.Logger LOG = System.getLogger(CatalogCoverage.class.getName());

    private final SpaceSyncJobs syncs;
    private final Supplier<CacheFreshness> freshness;
    private final Clock clock;

    public CatalogCoverage(SpaceSyncJobs syncs, Supplier<CacheFreshness> freshness, Clock clock) {
        this.syncs = Objects.requireNonNull(syncs, "syncs");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Coverage coverage(HarvestSource source, SearchSpaces scope) {
        try {
            Set<String> covered = new HashSet<>();
            for (SyncBaselineStore.SpaceRef ref : syncs.confirmedSpaces()) {
                if (ref.sourceId().equals(source.id()) && scope.permits(ref.spaceKey())
                        && current(syncs.catalog(ref.sourceId(), ref.spaceKey()))) {
                    covered.add(ref.spaceKey());
                }
            }
            if (covered.isEmpty()) {
                return Coverage.NONE;
            }
            Set<String> wanted = new HashSet<>(scope.only());
            if (wanted.isEmpty()) {
                source.partitions().forEach(partition -> {
                    if (scope.permits(partition.key())) {
                        wanted.add(partition.key());
                    }
                });
            }
            return new Coverage(!wanted.isEmpty() && covered.containsAll(wanted), covered);
        } catch (RuntimeException e) {
            // Im Zweifel nicht abgedeckt: Die Online-Suche bleibt der Rückfall.
            LOG.log(System.Logger.Level.INFO, "Abdeckung der Quelle nicht bestimmt ({0})", e.getClass().getName());
            return Coverage.NONE;
        }
    }

    /** Ob der Space vollständig und aktuell lokal liegt (siehe Klassenkommentar). */
    boolean current(SpaceCatalog catalog) {
        if (catalog.state() != SpaceCatalog.State.CATALOGED || catalog.stalePages() > 0
                || catalog.cachedPages() < catalog.knownPages()) {
            return false;
        }
        Duration maxAge = freshness.get().maxAge();
        return maxAge.isZero() || catalog.confirmedAt() != null
                && clock.instant().isBefore(catalog.confirmedAt().plus(maxAge));
    }
}
