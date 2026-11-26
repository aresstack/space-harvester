package com.aresstack.spaceharvester.application.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Live-Navigation (#177): Reihenfolge der Quelle, Rechte je Knoten, nichts Gesperrtes scheint durch. */
class PageNavigationTest {

    private static final AccessContext CLIENT = AccessContext.technicalClient("leser");
    private static final AccessContext USER = AccessContext.endUser("anna", "leser", new UserCredential("wiki",
            "geheim-token"));
    /** Seiten, die niemand außer der Quelle sieht. */
    private static final Set<String> LOCKED = Set.of("103", "300");

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
    private final AccessPolicy policy = (context, scope, kind) -> scope.pageId() != null
            && LOCKED.contains(scope.pageId()) ? AccessDecision.DENY
            : "HR".equals(scope.spaceKey()) && kind == AccessKind.METADATA ? AccessDecision.DENY
            : AccessDecision.ALLOW;
    private final PageNavigation navigation = new PageNavigation(catalog, null, new AccessGuard(policy));

    @Test
    void cachedChildrenComeFromTheCacheSortedByTitleWithoutLockedPages() {
        com.aresstack.spaceharvester.application.query.InMemoryPageCache cache =
                new com.aresstack.spaceharvester.application.query.InMemoryPageCache();
        cache.publish("wiki", "DEV", com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed("g1"),
                List.of(com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "100", null),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "102", "100"),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "101", "100"),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "103", "100"),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "104", "101")));
        PageNavigation cached = new PageNavigation(catalog, cache, new AccessGuard(policy));

        PageNavigation.Children roots = cached.cachedChildren(CLIENT, "wiki", "DEV", null);
        PageNavigation.Children children = cached.cachedChildren(CLIENT, "wiki", "DEV", "100");

        assertThat(roots.pages()).extracting(PageNode::pageId).containsExactly("100");
        assertThat(roots.pages()).extracting(PageNode::hasChildren).containsExactly(true);
        assertThat(children.pages()).extracting(PageNode::pageId).containsExactly("101", "102");
        assertThat(children.pages()).extracting(PageNode::hasChildren).containsExactly(true, null);
        assertThat(children.nextCursor()).isNull();
        assertThat(children.spaceKey()).isEqualTo("DEV");
        assertThat(new PageNavigation(catalog, null, new AccessGuard(policy))
                .cachedChildren(CLIENT, "wiki", "DEV", null).pages()).isEmpty();
    }

    @Test
    void cachedChildrenFollowTheOrderTheSourceDeliveredLastAndPutUnknownChildrenBehindByTitle() {
        com.aresstack.spaceharvester.application.query.InMemoryPageCache cache =
                new com.aresstack.spaceharvester.application.query.InMemoryPageCache();
        cache.publish("wiki", "DEV", com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed("g1"),
                List.of(com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "100", null),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "101", "100"),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "102", "100"),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "105", "100"),
                        com.aresstack.spaceharvester.application.query.InMemoryPageCache.page("wiki", "DEV", "106", "100")));
        PageNavigation live = new PageNavigation(catalog, cache, new AccessGuard(policy));

        // Ohne bekannte Reihenfolge nach Titel („Seite 101“ …).
        assertThat(live.cachedChildren(CLIENT, "wiki", null, "100").pages()).extracting(PageNode::pageId)
                .containsExactly("101", "102", "105", "106");

        // Erster Stapel der Quelle: 102, (103 gesperrt), 101; die übrigen nach Titel dahinter.
        PageNavigation.Children first = live.children(CLIENT, "wiki", null, "100", null);
        assertThat(live.cachedChildren(CLIENT, "wiki", null, "100").pages()).extracting(PageNode::pageId)
                .containsExactly("102", "101", "105", "106");
        assertThat(cache.childOrder("wiki", null, "100")).containsExactly("102", "103", "101");

        // Zweiter und letzter Stapel hängt an; 106 kennt die Quelle nicht mehr und kommt nach Titel dahinter.
        live.children(CLIENT, "wiki", null, "100", first.nextCursor());
        assertThat(cache.childOrder("wiki", null, "100")).containsExactly("102", "103", "101", "105");
        assertThat(live.cachedChildren(CLIENT, "wiki", null, "100").pages()).extracting(PageNode::pageId)
                .containsExactly("102", "101", "105", "106");

        // Advance the revision even for identical orders to invalidate older continuations.
        int writes = cache.childOrderWrites;
        first = live.children(CLIENT, "wiki", null, "100", null);
        live.children(CLIENT, "wiki", null, "100", first.nextCursor());
        assertThat(cache.childOrderWrites).isEqualTo(writes + 2);

        // Wurzelseiten je Space.
        live.children(CLIENT, "wiki", "DEV", null, null);
        assertThat(cache.childOrder("wiki", "DEV", null)).containsExactly("100");
    }

    @Test
    void childrenKeepTheOrderOfTheSourceAndLeaveOutLockedPagesWithoutTrace() {
        PageNavigation.Children children = navigation.children(CLIENT, "wiki", null, "100", null);

        assertThat(children.spaceKey()).isEqualTo("DEV");
        assertThat(children.pages()).extracting(PageNode::pageId).containsExactly("102", "101");
        assertThat(children.pages()).extracting(PageNode::hasChildren).containsExactly(true, false);
        assertThat(children.nextCursor()).isEqualTo("weiter");
        assertThat(navigation.children(CLIENT, "wiki", "DEV", null, null).pages()).extracting(PageNode::pageId)
                .containsExactly("100");
    }

    @Test
    void excludedChildrenAreLeftOutUnlessTheSettingsAskForThem(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        com.aresstack.spaceharvester.application.sync.Exclusions exclusions =
                new com.aresstack.spaceharvester.application.sync.Exclusions(
                        new com.aresstack.spaceharvester.persistence.files.FileExclusionMarks(dir),
                        (sourceId, pageId) -> null, (sourceId, pageId) -> null);
        navigation.exclusions(exclusions);
        exclusions.exclude("wiki", "DEV", "102");

        assertThat(navigation.children(CLIENT, "wiki", null, "100", null).pages()).extracting(PageNode::pageId)
                .containsExactly("101");
        assertThat(navigation.children(CLIENT, "wiki", null, "100", null, true).pages())
                .extracting(PageNode::pageId).containsExactly("102", "101");

        // Wieder eingeblendet ist der Knoten sofort zurück; ein ganzer Space blendet seine Wurzeln aus.
        exclusions.include("wiki", "DEV", "102");
        assertThat(navigation.children(CLIENT, "wiki", null, "100", null).pages()).extracting(PageNode::pageId)
                .containsExactly("102", "101");
        exclusions.exclude("wiki", "DEV", null);
        assertThat(navigation.children(CLIENT, "wiki", "DEV", null, null).pages()).isEmpty();
    }

    @Test
    void endUsersDoNotLearnThatOnlyLockedChildrenExist() {
        assertThat(navigation.children(USER, "wiki", null, "100", null).pages())
                .extracting(PageNode::hasChildren).containsExactly(null, false);
    }

    @Test
    void lockedNodesAreUnknownAndAncestorsAreFiltered() {
        assertThatThrownBy(() -> navigation.children(CLIENT, "wiki", null, "103", null))
                .isInstanceOfSatisfying(AccessDeniedException.class, e -> assertThat(e.concealExistence()).isTrue());
        assertThatThrownBy(() -> navigation.children(CLIENT, "wiki", "HR", null, null))
                .isInstanceOf(AccessDeniedException.class);

        PageNavigation.Position position = navigation.position(CLIENT, "wiki", "104");
        assertThat(position.spaceName()).isEqualTo("Entwicklung");
        assertThat(position.ancestors()).extracting(PageNode::pageId).containsExactly("100");
        assertThat(position.page().pageId()).isEqualTo("104");
    }

    @Test
    void referencesShowOnlyTargetsTheCallerMaySee() {
        PageNavigation.References references = navigation.references(CLIENT, "wiki", "101");

        // 300 ist gesperrt, 200 liegt in HR ohne Freigabe; die Quelle kennt eingehende Verweise nicht.
        assertThat(references.outgoing()).extracting(PageNode::pageId).containsExactly("102");
        assertThat(references.incoming()).isNull();
    }

    @Test
    void withoutIncomingFromTheSourceTheDirectoryOfStoredPagesAnswersWithItsCoverage() {
        com.aresstack.spaceharvester.application.links.InMemoryLinkIndex index =
                new com.aresstack.spaceharvester.application.links.InMemoryLinkIndex(List.of(
                        entry("104", com.aresstack.spaceharvester.application.links.LinkTarget.page("101")),
                        entry("300", com.aresstack.spaceharvester.application.links.LinkTarget.page("101"))));
        com.aresstack.spaceharvester.application.links.PageLinks.StoredPages stored =
                new com.aresstack.spaceharvester.application.links.PageLinks.StoredPages() {
                    @Override
                    public Optional<com.aresstack.spaceharvester.application.snapshot.PageHeader> header(
                            com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                        return Optional.of(com.aresstack.spaceharvester.application.snapshot.PageSnapshot.of("wiki",
                                new com.aresstack.spaceharvester.application.sources.PageMetadata(key.pageId(),
                                        "test:" + key.pageId(), "Seite " + key.pageId(), "DEV", null, "1",
                                        java.time.Instant.EPOCH, null, null),
                                new com.aresstack.spaceharvester.application.snapshot.NativePayload(new byte[0],
                                        "text/plain", "test"), java.time.Instant.EPOCH).header());
                    }

                    @Override
                    public Optional<com.aresstack.spaceharvester.application.snapshot.PageSnapshot> snapshot(
                            com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                        return Optional.empty();
                    }

                    @Override
                    public List<com.aresstack.spaceharvester.application.links.PageLinks.StoredPage> pages(
                            String sourceId, String afterPageId, int limit) {
                        return List.of();
                    }

                    @Override
                    public long known(String sourceId) {
                        return 3;
                    }
                };
        try (com.aresstack.spaceharvester.application.links.PageLinks links =
                     new com.aresstack.spaceharvester.application.links.PageLinks(stored, index, List.of(),
                             new AccessGuard(policy))) {
            PageNavigation withLinks = new PageNavigation(catalog, null, new AccessGuard(policy),
                    com.aresstack.spaceharvester.application.acquisition.FindingCatalog.NONE, links);

            PageNavigation.References references = withLinks.references(CLIENT, "wiki", "101");

            // 300 ist gesperrt; die Zähler bleiben verborgen, weil die Richtlinie quellenweit nichts entscheidet.
            assertThat(references.outgoing()).extracting(PageNode::pageId).containsExactly("102");
            assertThat(references.incoming()).extracting(PageNode::pageId).containsExactly("104");
            assertThat(references.coverage())
                    .isEqualTo(com.aresstack.spaceharvester.application.links.PageLinks.Coverage.HIDDEN);

            // Fahrplan P3: die Verweise von Unterseiten aus dem Verzeichnis, mit der Unterseite als Herkunft; die
            // gesperrte 300 fehlt als Herkunft wie als Ziel.
            List<PageNavigation.SubpageReference> below = withLinks.subpageReferences(CLIENT,
                    com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(List.of(
                            new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "104"),
                            new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "300"),
                            new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "101"))));
            assertThat(below).extracting(reference -> reference.origin().pageId() + (reference.outgoing() ? " → "
                    : " ← ") + reference.target().pageId()).containsExactly("101 ← 104", "104 → 101");
            assertThat(withLinks.subpageReferences(CLIENT,
                    com.aresstack.spaceharvester.application.snapshot.PageSet.EMPTY)).isEmpty();
            assertThat(withLinks.nodes(CLIENT, List.of(
                    new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "104"),
                    new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "300"))).keySet())
                    .extracting(com.aresstack.spaceharvester.application.snapshot.PageKey::pageId)
                    .as("gesperrte Seite fehlt").containsExactly("104");
        }
    }

    private static com.aresstack.spaceharvester.application.links.LinkIndex.Entry entry(String pageId,
            com.aresstack.spaceharvester.application.links.LinkTarget target) {
        return new com.aresstack.spaceharvester.application.links.LinkIndex.Entry(
                new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", pageId), "DEV",
                "Seite " + pageId, "sha256:" + pageId, List.of(target));
    }

    @Test
    void titleMatchesComeInTheOrderOfTheSourceWithTheirSpaceAndWithoutLockedPages() {
        PageNavigation.TitleMatches matches = navigation.findByTitle(CLIENT, "wiki", Set.of(), "plan", 10);

        // 103 und 300 sind gesperrt, 200 liegt in HR ohne Freigabe; 101 nennt seinen Space erst in den Kopfdaten.
        assertThat(matches.pages()).extracting(PageNode::pageId, PageNode::partitionKey)
                .containsExactly(tuple("101", "DEV"), tuple("102", "DEV"), tuple("104", "DEV"));
        assertThat(matches.truncated()).isFalse();
        assertThat(navigation.findByTitle(CLIENT, "wiki", Set.of("OPS"), "plan", 10).pages()).isEmpty();
        assertThat(source.titleSpaces).containsExactly(Set.of(), Set.of("OPS"));
    }

    @Test
    void lockedPagesNeitherCrowdOutVisibleOnesNorCountAsMore() {
        PageNavigation.TitleMatches two = navigation.findByTitle(CLIENT, "wiki", Set.of(), "Plan", 2);
        assertThat(two.pages()).extracting(PageNode::pageId).containsExactly("101", "102");
        assertThat(two.truncated()).isTrue();
        // Die ersten drei Treffer waren fast nur gesperrt: die Navigation fragte tiefer.
        assertThat(source.titleLimits).containsExactly(3, 6);

        PageNavigation.TitleMatches three = navigation.findByTitle(CLIENT, "wiki", Set.of(), "Plan", 3);
        assertThat(three.pages()).extracting(PageNode::pageId).containsExactly("101", "102", "104");
        // Sechs Treffer der Quelle, aber nur drei sichtbar: nicht gekürzt.
        assertThat(three.truncated()).isFalse();
    }

    @Test
    void endUsersSeeTitleMatchesWithoutKnownChildren() {
        assertThat(navigation.findByTitle(USER, "wiki", Set.of(), "plan b", 10).pages())
                .extracting(PageNode::pageId, PageNode::hasChildren).containsExactly(tuple("102", null));
    }

    @Test
    void titleSearchNeedsATextAndALimitInRange() {
        assertThatThrownBy(() -> navigation.findByTitle(CLIENT, "wiki", Set.of(), " ", 10))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> navigation.findByTitle(CLIENT, "wiki", Set.of(), "plan", 0))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> navigation.findByTitle(CLIENT, "wiki", Set.of(), "plan",
                PageNavigation.MAX_TITLE_LIMIT + 1)).isInstanceOf(InvalidQueryException.class);
    }

    private static final class Source implements HarvestSource {

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
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, "1",
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public ChildPages children(String partitionKey, String parentPageId, String cursor) {
            if (parentPageId == null) {
                return new ChildPages(partitionKey, List.of(new PageNode("100", "Start", partitionKey, true)), null);
            }
            if ("weiter".equals(cursor)) {
                return new ChildPages("DEV", List.of(new PageNode("105", "Anhang", "DEV", false)), null);
            }
            return new ChildPages("DEV", List.of(new PageNode("102", "Zweite", "DEV", true),
                    new PageNode("103", "Gesperrt", "DEV", false), new PageNode("101", "Erste", "DEV", false)),
                    "weiter");
        }

        @Override
        public PagePosition position(String pageId) {
            return new PagePosition("DEV", "Entwicklung", List.of(new PageNode("100", "Start", "DEV", true),
                    new PageNode("103", "Gesperrt", "DEV", true)), new PageNode(pageId, "Tief", "DEV", false));
        }

        /** Titeltreffer in Rangfolge der Quelle; 101 ohne bekannten Space. */
        private final List<PageNode> titled = List.of(new PageNode("103", "Plan geheim", "DEV", null),
                new PageNode("300", "Plan Geheim", "DEV", null), new PageNode("101", "Plan A", null, null),
                new PageNode("102", "Plan B", "DEV", true), new PageNode("200", "Plan HR", "HR", null),
                new PageNode("104", "Plan C", "DEV", false), new PageNode("105", "Anderes", "DEV", null));
        private final List<Integer> titleLimits = new ArrayList<>();
        private final List<Set<String>> titleSpaces = new ArrayList<>();

        @Override
        public List<PageNode> findByTitle(String title, SearchSpaces spaces, int limit) {
            titleLimits.add(limit);
            titleSpaces.add(spaces.only());
            String needle = title.toLowerCase(Locale.ROOT);
            return titled.stream().filter(node -> node.title().toLowerCase(Locale.ROOT).contains(needle))
                    .filter(node -> node.partitionKey() == null || spaces.permits(node.partitionKey()))
                    .limit(limit).toList();
        }

        @Override
        public PageReferences references(String pageId) {
            return new PageReferences(List.of(new PageNode("102", "Zweite", "DEV", null),
                    new PageNode("200", "Personal", "HR", null), new PageNode("300", "Geheim", null, null)), null);
        }
    }
}
