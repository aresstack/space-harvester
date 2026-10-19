package com.aresstack.spaceharvester.application.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Katalogisieren der Funde (ADR 0016): nur technische Aufrufer, nur freigegebene Seiten, Kopfdaten live mit den
 * Zugangsdaten der Quelle, gesperrte und unveränderte Einträge ohne Anfrage.
 */
class FindingCatalogingTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final Duration WAIT = Duration.ofSeconds(10);

    /** Space je Seite in der Quelle; fehlt eine, antwortet sie mit „nicht gefunden“. */
    private final Map<String, String> spaces = new ConcurrentHashMap<>();
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    /** Spaces, die die Richtlinie ausschließt. */
    private final Set<String> excludedSpaces = ConcurrentHashMap.newKeySet();
    private final MemoryStore store = new MemoryStore();
    private final List<BackgroundFill.Gap> gaps = Collections.synchronizedList(new ArrayList<>());
    private final FindingCataloging cataloging = new FindingCataloging(catalog(), new AccessGuard(
            (context, scope, kind) -> kind == AccessKind.METADATA && context.kind() != AccessContext.PrincipalKind.END_USER
                    && !excludedSpaces.contains(scope.spaceKey()) ? AccessDecision.ALLOW : AccessDecision.DENY),
            store, gaps::addAll, Clock.systemUTC());

    @AfterEach
    void close() {
        cataloging.close();
    }

    @Test
    void aNewFindingIsCataloguedOnceWithItsLiveHeaderAndReportedToTheFill() throws InterruptedException {
        page("1", "DEV", "3");

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();
        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).containsExactly("1");
        assertThat(store.headers.get("1").metadata().partitionKey()).isEqualTo("DEV");
        assertThat(gaps).extracting(gap -> gap.key().pageId() + ":" + gap.kind()).containsExactly("1:NOT_LOADED");
        assertThat(cataloging.counts().catalogued()).isEqualTo(1);
    }

    @Test
    void anEntryWithTheSameVersionCostsNoRequestAndANewerOneBecomesOutdated() throws InterruptedException {
        page("1", "DEV", "2");
        page("2", "DEV", "5");
        store.entries.put("1", new FindingCataloging.Entry(false, "2"));
        store.entries.put("2", new FindingCataloging.Entry(false, "4"));
        store.loaded.add("2");

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "2"),
                new FindingCatalog.Finding("2", "5")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).containsExactly("2");
        assertThat(gaps).extracting(gap -> gap.key().pageId() + ":" + gap.kind()).containsExactly("2:OUTDATED");
    }

    @Test
    void endUsersAndDelegatedCallersCatalogueNothing() throws InterruptedException {
        page("1", "DEV", "1");

        cataloging.offer(AccessContext.endUser("alice", "app", new UserCredential("wiki", "token")),
                "wiki", List.of(new FindingCatalog.Finding("1", "1")));
        cataloging.offer(null, "wiki", List.of(new FindingCatalog.Finding("1", "1")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).isEmpty();
        assertThat(store.headers).isEmpty();
    }

    @Test
    void pagesThePolicyExcludesWithheldPagesAndVanishedPagesStayOut() throws InterruptedException {
        page("1", "SECRET", "1");
        page("2", "DEV", "1");
        excludedSpaces.add("SECRET");
        store.entries.put("2", new FindingCataloging.Entry(true, null));

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "1"), new FindingCatalog.Finding("2", "2"),
                new FindingCatalog.Finding("3", null)));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        // Gesperrt: keine Anfrage; ausgeschlossen und verschwunden: gefragt, aber nicht katalogisiert.
        assertThat(asked).containsExactlyInAnyOrder("1", "3");
        assertThat(store.headers).isEmpty();
        assertThat(gaps).isEmpty();
        assertThat(cataloging.counts().excluded()).isEqualTo(1);
        assertThat(cataloging.counts().failed()).isEqualTo(1);
    }

    @Test
    void afterClosingNothingIsAccepted() throws InterruptedException {
        page("1", "DEV", "1");
        cataloging.close();

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "1")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).isEmpty();
    }

    private void page(String pageId, String spaceKey, String version) {
        spaces.put(pageId, spaceKey);
        versions.put(pageId, version);
    }

    private SourceCatalog catalog() {
        HarvestSource wiki = new HarvestSource() {
            @Override
            public String id() {
                return "wiki";
            }

            @Override
            public String type() {
                return "fake";
            }

            @Override
            public String title() {
                return "Wiki";
            }

            @Override
            public List<Partition> partitions() {
                return List.of();
            }

            @Override
            public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
                return new PageMetadataBatch(List.of(), null);
            }

            @Override
            public PageMetadata pageMetadata(String pageId) {
                asked.add(pageId);
                String spaceKey = spaces.get(pageId);
                if (spaceKey == null) {
                    throw new SourceAccessException("wiki", SourceAccessException.Reason.NOT_FOUND, "weg", null);
                }
                return new PageMetadata(pageId, "fake:" + pageId, "Seite " + pageId, spaceKey, null,
                        versions.get(pageId), null, null, new TreeMap<>());
            }
        };
        return new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(wiki);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return wiki.id().equals(sourceId) ? Optional.of(wiki) : Optional.empty();
            }
        };
    }

    /** Ein Katalog im Speicher mit derselben Regel wie der Lucene-Cache. */
    private static final class MemoryStore implements FindingCataloging.Store {

        final Map<String, FindingCataloging.Entry> entries = new ConcurrentHashMap<>();
        final Map<String, PageHeader> headers = new ConcurrentHashMap<>();
        final Set<String> loaded = ConcurrentHashMap.newKeySet();

        @Override
        public Optional<FindingCataloging.Entry> entry(PageKey key) {
            return Optional.ofNullable(entries.get(key.pageId()));
        }

        @Override
        public FindingCataloging.Outcome catalog(PageHeader header) {
            String pageId = header.key().pageId();
            FindingCataloging.Entry entry = entries.get(pageId);
            if (entry != null && entry.withheld()) {
                return FindingCataloging.Outcome.WITHHELD;
            }
            if (entry != null && header.revision() != null && header.revision().equals(entry.revision())) {
                return FindingCataloging.Outcome.UNCHANGED;
            }
            entries.put(pageId, new FindingCataloging.Entry(false, header.revision()));
            headers.put(pageId, header);
            return loaded.contains(pageId) ? FindingCataloging.Outcome.OUTDATED : FindingCataloging.Outcome.NOT_LOADED;
        }
    }
}
