package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Ausgabe aus dem Bestand (ADR 0029): Der Export liest nur den Seiten-Cache, nie die Quelle; ein Teilbaum-Export mit
 * Beschaffung liest nur den Cache, wenn alle Seiten seines Teilbaums gespeichert und aktuell sind.
 */
class ExportFromHoldingsTest {

    private static final Instant SYNCED = Instant.parse("2026-11-12T20:00:00Z");

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T20:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final RecordingExportArtifactWriter writer = new RecordingExportArtifactWriter();
    private final InMemoryPageCache cache = new InMemoryPageCache();
    /** Jeder Zugriff auf die Quelle; eine Ausgabe aus dem Bestand macht keinen. */
    private final List<String> provisioned = new ArrayList<>();
    private HoldingsCatalog.Stock stock;

    private final HoldingsCatalog holdings = new HoldingsCatalog() {
        @Override
        public Optional<Stock> stock(String sourceId, String spaceKey) {
            return Optional.ofNullable(stock);
        }

        @Override
        public List<String> spaceKeys(String sourceId) {
            return List.of("DEV");
        }

        @Override
        public Optional<String> spaceOf(String sourceId, String pageId) {
            return Optional.empty();
        }
    };

    /** Einlesen, das nur mitschreibt, dass die Quelle gefragt wurde, und den Bestand unverändert lässt. */
    private final SpaceProvisioning provisioning = new SpaceProvisioning() {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Provisioned provision(JobContext context, String sourceId, String spaceKey) {
            return provision(context, sourceId, spaceKey, header -> true);
        }

        @Override
        public Provisioned provision(JobContext context, String sourceId, String spaceKey,
                                     Predicate<PageHeader> counted) {
            provisioned.add(sourceId + "/" + spaceKey);
            return new Provisioned(true, false, List.of());
        }

        @Override
        public Provisioned provisionScoped(JobContext context, String sourceId, String spaceKey,
                                           Function<List<PageHeader>, Predicate<PageHeader>> counting) {
            provisioned.add(sourceId + "/" + spaceKey);
            return new Provisioned(true, false, List.of());
        }
    };

    private Exports exports() {
        return new Exports(jobs, cache, writer, ExportConverters.none(), provisioning, holdings, clock);
    }

    private JobStatus run(ExportRequest request) {
        return jobs.status(exports().start(request).id());
    }

    private static ExportRequest fromHoldings() {
        return ExportRequest.fromHoldings("confluence", "DEV", "client-a", List.of());
    }

    /** Die Kopfdaten einer Seite mit neuerer Version, als hätte eine Suche oder ein Abgleich sie katalogisiert. */
    private static PageHeader newer(PageSnapshot page, String version) {
        PageMetadata m = page.metadata();
        PageMetadata metadata = new PageMetadata(m.pageId(), m.resourceId(), m.title(), m.partitionKey(),
                m.parentPageId(), version, m.modifiedAt(), m.url(), m.attributes());
        return new PageHeader(page.key(), metadata, "sha256:neu", page.attributesHash(), SYNCED);
    }

    /** Seiten 1 (Wurzel), 2 und 3 unter 1, 4 unter 2: im Cache gespeichert, nicht bestätigt. */
    private static List<PageSnapshot> stored() {
        return List.of(page("confluence", "DEV", "1", null), page("confluence", "DEV", "2", "1"),
                page("confluence", "DEV", "3", "1"), page("confluence", "DEV", "4", "2"));
    }

    private static HoldingsCatalog.CatalogPage local(String pageId, String parentPageId) {
        return new HoldingsCatalog.CatalogPage(pageId, parentPageId, true, "3", "3");
    }

    @Test
    void anOutputReadsOnlyTheCacheAndNamesTheCountOfPagesThatAreOnlyCatalogued() {
        List<PageSnapshot> pages = stored();
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, null, null),
                pages.subList(0, 3));
        // Seite 4 kennt nur der Katalog: Kopfdaten ohne Inhalt.
        cache.updateHeader("DEV", pages.get(3).header());

        JobStatus status = run(fromHoldings());

        assertThat(status.state()).isEqualTo(JobState.COMPLETED);
        assertThat(provisioned).isEmpty();
        RecordingExportArtifactWriter.Published published = writer.published.get(status.id());
        assertThat(published.pageIds()).containsExactly("1", "2", "3");
        assertThat(published.summary().complete()).isFalse();
        assertThat(published.summary().failures()).isEmpty();
        assertThat(published.summary().unfetched()).isEmpty();
        assertThat(published.summary().warnings())
                .anyMatch(warning -> warning.startsWith("1 Seiten des Umfangs liegen nicht im Bestand"));
        // Gefunden und bearbeitet sind alle vier, geschrieben drei; keine gilt als gescheitert.
        assertThat(status.progress()).isEqualTo(new JobProgress(4, 4, 3, 0));
    }

    @Test
    void anOutdatedPageComesInItsStoredStateAndIsCounted() {
        List<PageSnapshot> pages = stored();
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null), pages);
        cache.updateHeader("DEV", newer(pages.get(1), "4"));
        stock = new HoldingsCatalog.Stock(new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null),
                List.of(local("1", null), new HoldingsCatalog.CatalogPage("2", "1", true, "4", "3"), local("3", "1"),
                        local("4", "2")), Set.of(), List.of());

        JobStatus status = run(fromHoldings().withRoot("2"));

        assertThat(provisioned).isEmpty();
        RecordingExportArtifactWriter.Published published = writer.published.get(status.id());
        assertThat(published.pageIds()).containsExactly("2", "4");
        assertThat(published.summary().warnings())
                .anyMatch(warning -> warning.startsWith("1 Seiten stehen im gespeicherten, älteren Stand"));
        // Eine veraltete Seite heißt: der Teilbaum ist nicht aktuell, die Ausgabe nicht vollständig.
        assertThat(published.summary().complete()).isFalse();
    }

    @Test
    void aSubtreeWhosePagesAreAllStoredAndCurrentIsCompleteFromTheHoldings() {
        List<PageSnapshot> pages = stored();
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null),
                pages.subList(0, 2));
        cache.updateHeader("DEV", pages.get(2).header());
        stock = new HoldingsCatalog.Stock(new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null),
                List.of(local("1", null), local("2", "1"), new HoldingsCatalog.CatalogPage("3", "1", false, "3", null)),
                Set.of(), List.of());

        JobStatus status = run(fromHoldings().withRoot("2"));

        assertThat(writer.published.get(status.id()).summary().complete()).isTrue();
    }

    @Test
    void withoutHoldingsTheOutputFailsWithoutAskingTheSource() {
        JobStatus status = run(fromHoldings());

        assertThat(status.error().code()).isEqualTo("NO_HOLDINGS");
        assertThat(provisioned).isEmpty();
        assertThat(writer.published).isEmpty();
    }

    @Test
    void anOutputFromTheHoldingsCannotBeForcedToReadTheSource() {
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, null, null), stored());
        Exports exports = exports();
        String id = exports.start(fromHoldings()).id();

        assertThatThrownBy(() -> exports.forceRefresh(id, "client-a", request -> request))
                .isInstanceOf(JobNotResumableException.class)
                .hasMessageContaining("liest die Quelle nicht");
        assertThat(provisioned).isEmpty();
    }

    @Test
    void retryingTheFailuresOfAnOutputReadsTheCacheAgainAndNeverTheSource() {
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, null, null), stored());
        Exports exports = exports();
        String id = exports.start(fromHoldings()).id();

        exports.retryFailed(id, "client-a", request -> request);

        assertThat(jobs.status(id).state()).isEqualTo(JobState.COMPLETED);
        assertThat(provisioned).isEmpty();
    }

    @Test
    void aSubtreeExportWhosePagesAreAllStoredAndCurrentDoesNotReadTheSource() {
        List<PageSnapshot> pages = stored();
        // Nach einem Teilbaum-Export: Inventur bestätigt, der übrige Space nur katalogisiert.
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null),
                List.of(pages.get(1), pages.get(3)));
        cache.updateHeader("DEV", pages.get(0).header());
        cache.updateHeader("DEV", pages.get(2).header());
        stock = new HoldingsCatalog.Stock(new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null),
                List.of(new HoldingsCatalog.CatalogPage("1", null, false, "3", null), local("2", "1"),
                        new HoldingsCatalog.CatalogPage("3", "1", false, "3", null), local("4", "2")),
                Set.of(), List.of());

        JobStatus status = run(ExportRequest.of("confluence", "DEV", "client-a", List.of()).withRoot("2"));

        assertThat(provisioned).isEmpty();
        RecordingExportArtifactWriter.Published published = writer.published.get(status.id());
        assertThat(published.pageIds()).containsExactly("2", "4");
        assertThat(published.summary().complete()).isTrue();
    }

    @Test
    void aSubtreeExportWithAnOutdatedPageReadsTheSource() {
        List<PageSnapshot> pages = stored();
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null), pages);
        stock = new HoldingsCatalog.Stock(new CacheGeneration("g1", CacheState.INCOMPLETE, SYNCED, null),
                List.of(local("1", null), local("2", "1"), local("3", "1"), local("4", "2")), Set.of("4"), List.of());

        run(ExportRequest.of("confluence", "DEV", "client-a", List.of()).withRoot("2"));

        assertThat(provisioned).containsExactly("confluence/DEV");
    }

    @Test
    void aSubtreeExportWithoutAnInventoryReadsTheSource() {
        List<PageSnapshot> pages = stored();
        cache.publish("confluence", "DEV", new CacheGeneration("g1", CacheState.INCOMPLETE, null, null), pages);
        // Ohne Inventur kennt der Katalog den Teilbaum vielleicht nicht ganz.
        stock = new HoldingsCatalog.Stock(new CacheGeneration("g1", CacheState.INCOMPLETE, null, null),
                List.of(local("1", null), local("2", "1"), local("3", "1"), local("4", "2")), Set.of(), List.of());

        run(ExportRequest.of("confluence", "DEV", "client-a", List.of()).withRoot("2"));

        assertThat(provisioned).containsExactly("confluence/DEV");
    }
}
