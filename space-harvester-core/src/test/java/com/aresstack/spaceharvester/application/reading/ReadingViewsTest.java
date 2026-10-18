package com.aresstack.spaceharvester.application.reading;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Lese-Ansicht als Core-Anwendungsfall (#176): Rechte, Version, Ablage, eingebundene Inhalte. */
class ReadingViewsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("leser");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneOffset.UTC);
    private static final String INCLUDE = "{\"body\":{\"storage\":{\"value\":\"<ac:structured-macro "
            + "ac:name=\\\"include\\\"/><ac:structured-macro ac:name=\\\"jira\\\"/>\"}}}";

    private final Source source = new Source();
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
        }
    };
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final InMemoryEnrichmentStore enrichments = new InMemoryEnrichmentStore();

    ReadingViewsTest() {
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(page("wiki", "DEV", "100", null),
                page("wiki", "DEV", "101", null, INCLUDE.getBytes(StandardCharsets.UTF_8))));
    }

    private ReadingViews views(AccessPolicy policy) {
        PageFreshness cacheOnly = new PageFreshness(new SourceQueries(catalog, CLOCK), null, CLOCK);
        return new ReadingViews(catalog, new PageQueries(catalog, cache, new AccessGuard(policy), cacheOnly, 1000),
                new AccessGuard(policy), new ReadingViewStore(enrichments), CLOCK);
    }

    @Test
    void theViewIsFetchedOnceThenServedFromBesideTheSnapshot() {
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);

        ReadingView first = views.view(CALLER, "wiki", "100", false);
        ReadingView second = views.view(CALLER, "wiki", "100", false);

        assertThat(first.status()).isEqualTo(ReadingView.Status.AVAILABLE);
        assertThat(first.stored()).isFalse();
        assertThat(first.html()).contains("Kinderseite");
        assertThat(first.text()).contains("Kinderseite");
        assertThat(first.version()).isEqualTo("3");
        assertThat(first.images()).singleElement().satisfies(image -> assertThat(image.attachmentId())
                .isEqualTo("att1"));
        assertThat(second.stored()).isTrue();
        assertThat(second.html()).isEqualTo(first.html());
        assertThat(source.reads).isEqualTo(1);
        // Kein Eintrag in der Liste der Anreicherungen; der Raw-Snapshot bleibt unverändert.
        Enrichments listed = new Enrichments(snapshot -> Optional.empty(), List.of(), enrichments, CLOCK);
        assertThat(listed.view(cache.page(new PageKey("wiki", "100")).orElseThrow().snapshot())).isEmpty();
    }

    @Test
    void includedContentNeedsTheWholeSourceAndIsFetchedEveryTime() {
        ReadingView restricted = views((context, scope, kind) -> AccessDecision.ALLOW).view(CALLER, "wiki", "101",
                false);

        assertThat(restricted.status()).isEqualTo(ReadingView.Status.RESTRICTED);
        assertThat(restricted.html()).isEmpty();
        assertThat(restricted.foreign().macros()).containsExactly("include", "jira");
        assertThat(source.reads).isZero();

        ReadingViews sourceWide = views(new AccessPolicy() {
            @Override
            public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
                return AccessDecision.ALLOW;
            }

            @Override
            public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
                return AccessDecision.ALLOW;
            }
        });
        assertThat(sourceWide.view(CALLER, "wiki", "101", false).status()).isEqualTo(ReadingView.Status.AVAILABLE);
        assertThat(sourceWide.view(CALLER, "wiki", "101", false).stored()).isFalse();
        assertThat(source.reads).isEqualTo(2);
    }

    @Test
    void anUnreachableSourceServesTheStoredViewAndAnotherVersionIsNeverShown() {
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);
        views.view(CALLER, "wiki", "100", false);
        source.unreachable = true;
        // Die abgelegte Ansicht gehört zur geprüften Payload; ohne erneuten Abruf.
        assertThat(views.view(CALLER, "wiki", "100", false).stored()).isTrue();

        enrichments.removeAll(new PageKey("wiki", "100"));
        assertThatThrownBy(() -> views.view(CALLER, "wiki", "100", false)).isInstanceOf(SourceAccessException.class);

        source.unreachable = false;
        source.version = "4";
        // Nur zur geprüften Version 3; die Neuprüfung braucht die Quelle, ohne Anbindung scheitert sie.
        assertThatThrownBy(() -> views.view(CALLER, "wiki", "100", false)).isInstanceOf(RuntimeException.class);
        assertThat(enrichments.find(new PageKey("wiki", "100"))).isEmpty();
    }

    @Test
    void aSourceWithoutViewSaysSo() {
        source.supported = false;

        assertThat(views((context, scope, kind) -> AccessDecision.ALLOW).view(CALLER, "wiki", "100", false).status())
                .isEqualTo(ReadingView.Status.UNSUPPORTED);
    }

    private static final class Source implements HarvestSource {

        int reads;
        boolean unreachable;
        boolean supported = true;
        String version = "3";

        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "confluence";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of(new Partition("DEV", "Entwicklung", null));
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, version,
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public ReadingPage readingPage(String pageId) {
            if (!supported) {
                return HarvestSource.super.readingPage(pageId);
            }
            if (unreachable) {
                throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "weg", null);
            }
            reads++;
            return new ReadingPage(version, "<p>Seite " + pageId + "</p><ul><li>Kinderseite</li></ul>",
                    "https://wiki.example.org/", "# Seite " + pageId + "\n\n- Kinderseite",
                    List.of(new ViewImage("https://wiki.example.org/download/attachments/" + pageId + "/a.png",
                            "a.png", "att1", "image/png", "1")));
        }
    }
}
