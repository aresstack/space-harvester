package com.aresstack.spaceharvester.application.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.List;
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
            return new ChildPages("DEV", List.of(new PageNode("102", "Zweite", "DEV", true),
                    new PageNode("103", "Gesperrt", "DEV", false), new PageNode("101", "Erste", "DEV", false)),
                    "weiter");
        }

        @Override
        public PagePosition position(String pageId) {
            return new PagePosition("DEV", "Entwicklung", List.of(new PageNode("100", "Start", "DEV", true),
                    new PageNode("103", "Gesperrt", "DEV", true)), new PageNode(pageId, "Tief", "DEV", false));
        }

        @Override
        public PageReferences references(String pageId) {
            return new PageReferences(List.of(new PageNode("102", "Zweite", "DEV", null),
                    new PageNode("200", "Personal", "HR", null), new PageNode("300", "Geheim", null, null)), null);
        }
    }
}
