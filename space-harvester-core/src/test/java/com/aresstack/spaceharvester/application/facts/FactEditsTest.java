package com.aresstack.spaceharvester.application.facts;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link FactEdits} mit {@link EditedFactStore}: manuelle Fakten, Prüfstatus, der eine Neuberechnung übersteht,
 * Protokoll, Revision und Rechte.
 */
class FactEditsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC);
    private static final PageKey P1 = new PageKey("wiki", "p1");
    private static final String OTHER = "Das Domain Name System (DNS) ist neu. Der Dienst bleibt verfügbar.";

    private final HarvestSource wiki = mock(HarvestSource.class);
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(wiki);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(wiki) : Optional.empty();
        }
    };
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final InMemoryFactEditStore editStore = new InMemoryFactEditStore();
    private final EditedFactStore facts = new EditedFactStore(store, editStore);

    /** p2 darf gelesen, aber nicht geändert werden. */
    private final AccessPolicy policy = (context, scope, kind) -> "p2".equals(scope.pageId())
            && kind == AccessKind.SYNC ? AccessDecision.DENY : AccessDecision.ALLOW;

    @BeforeEach
    void pages() {
        when(wiki.id()).thenReturn("wiki");
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(page("wiki", "DEV", "p1", null),
                page("wiki", "DEV", "p2", null)));
        store.save(FactTestResults.acronyms(P1, FactTestResults.TEXT));
        store.save(FactTestResults.acronyms(new PageKey("wiki", "p2"), FactTestResults.TEXT));
    }

    private FactEdits edits() {
        return new FactEdits(new AccessGuard(policy), queries(), null, facts, editStore, CLOCK);
    }

    private PageQueries queries() {
        return new PageQueries(catalog, cache, new AccessGuard(policy),
                new PageFreshness(new SourceQueries(catalog, CLOCK), null, CLOCK), 1000);
    }

    private PageFacts sheet() {
        AccessGuard guard = new AccessGuard(policy);
        return new PageFacts(catalog, guard, queries(), null, facts, key -> cache.page(key).map(CachedPage::header),
                editStore);
    }

    private MetadataFact definition(PageKey key) {
        return facts.factsOf(key).stream().filter(fact -> fact.type().equals(FactTypes.ACRONYM_DEFINITION))
                .findFirst().orElseThrow();
    }

    @Test
    void aManualFactAppearsOnItsPageAndInTheSearchAndSurvivesARecomputation() {
        FactEdits.Edited added = edits().add(CALLER, "wiki", "p1", new FactEdits.NewFact(FactTypes.PROPERTY, null,
                "{\"subject\":\"Dienst\",\"property\":\"zuständig\",\"value\":\"Team A\"}",
                List.of(new FactEdits.NewArgument("SUBJECT", "Dienst"), new FactEdits.NewArgument("VALUE", "Team A")),
                null));

        MetadataFact fact = added.fact();
        assertThat(fact.id()).matches("[0-9a-f]{32}");
        assertThat(fact.origin()).isEqualTo(FactOrigin.MANUAL);
        assertThat(fact.review()).isEqualTo(FactReview.UNREVIEWED);
        assertThat(fact.provenance().processorId()).isEqualTo(FactEdits.MANUAL_PROCESSOR);
        assertThat(fact.values("VALUE")).containsExactly("team a");
        assertThat(added.revision()).isEqualTo(1);
        store.save(FactTestResults.acronyms(P1, OTHER));
        assertThat(facts.factsOf(P1)).contains(fact);
        PageFacts.Sheet sheet = sheet().factsOf(CALLER, "wiki", "p1", FactTypes.PROPERTY);
        assertThat(sheet.facts()).extracting(PageFacts.Entry::revision).containsExactly(1L);
        PageFacts.Results found = sheet().search(CALLER, new PageFacts.Search(FactTypes.PROPERTY,
                List.of(ArgumentCondition.parse("VALUE:team a")), null, java.util.Set.of(), java.util.Set.of()),
                null, 10);
        assertThat(found.facts()).containsExactly(fact);
        assertThat(edits().history(CALLER, "wiki", "p1", fact.id())).extracting(FactEditStore.AuditEntry::action)
                .containsExactly("add");
    }

    @Test
    void aConfirmedAutomaticFactKeepsItsStatusWhileTheResultIsTheSame() {
        MetadataFact automatic = definition(P1);

        FactEdits.Edited confirmed = edits().review(CALLER, "wiki", "p1", automatic.id(), FactReview.CONFIRMED, 0L);

        assertThat(confirmed.revision()).isEqualTo(1);
        assertThat(definition(P1)).isEqualTo(automatic.withReview(FactReview.CONFIRMED));
        store.save(FactTestResults.acronyms(P1, FactTestResults.TEXT));
        assertThat(definition(P1).review()).isEqualTo(FactReview.CONFIRMED);
        assertThat(facts.factsOf(P1)).hasSameSizeAs(store.factsOf(P1));
    }

    @Test
    void aReviewedCopyOutlivesANewResultWhichGetsItsOwnId() {
        MetadataFact automatic = definition(P1);
        edits().review(CALLER, "wiki", "p1", automatic.id(), FactReview.REJECTED, null);

        store.save(FactTestResults.acronyms(P1, OTHER));

        List<MetadataFact> sheet = facts.factsOf(P1);
        MetadataFact kept = sheet.stream().filter(fact -> fact.id().equals(automatic.id())).findFirst().orElseThrow();
        assertThat(kept.review()).isEqualTo(FactReview.REJECTED);
        assertThat(kept.provenance().inputSha256()).isEqualTo(automatic.provenance().inputSha256());
        assertThat(sheet).hasSize(store.factsOf(P1).size() + 1);
        assertThat(sheet).extracting(MetadataFact::id).doesNotHaveDuplicates();
        MetadataFact successor = sheet.stream().filter(fact -> fact.id().equals(EditedFactStore.successorId(
                store.factsOf(P1).stream().filter(base -> base.id().equals(automatic.id())).findFirst()
                        .orElseThrow()))).findFirst().orElseThrow();
        assertThat(successor.review()).isEqualTo(FactReview.UNREVIEWED);

        // Suche: jede Kennung genau einmal, auch stapelweise.
        FactStore.Query query = new FactStore.Query(FactTypes.ACRONYM_DEFINITION, List.of(),
                java.util.Set.of("wiki"));
        List<String> all = facts.facts(query, null, 1000).stream().map(MetadataFact::id).toList();
        List<String> walked = new ArrayList<>();
        String after = null;
        List<MetadataFact> batch;
        do {
            batch = facts.facts(query, after, 1);
            batch.forEach(fact -> walked.add(fact.id()));
            after = batch.isEmpty() ? after : batch.get(batch.size() - 1).id();
        } while (batch.size() == 1);
        assertThat(walked).isEqualTo(all).doesNotHaveDuplicates();
        assertThat(all).contains(automatic.id());
    }

    @Test
    void revisionsAreCheckedAndUnreviewingRemovesTheEntry() {
        MetadataFact automatic = definition(P1);
        FactEdits edits = edits();
        edits.review(CALLER, "wiki", "p1", automatic.id(), FactReview.CONFIRMED, 0L);

        assertThatThrownBy(() -> edits.review(CALLER, "wiki", "p1", automatic.id(), FactReview.REJECTED, 0L))
                .isInstanceOfSatisfying(FactConflictException.class, e -> assertThat(e.revision()).isEqualTo(1));
        edits.review(CALLER, "wiki", "p1", automatic.id(), FactReview.UNREVIEWED, 1L);

        assertThat(editStore.all()).isEmpty();
        assertThat(definition(P1)).isEqualTo(automatic);
        assertThat(edits.history(CALLER, "wiki", "p1", automatic.id())).extracting(FactEditStore.AuditEntry::review)
                .containsExactly(FactReview.CONFIRMED, FactReview.UNREVIEWED);
    }

    @Test
    void onlyManualFactsAndReviewedCopiesCanBeRemoved() {
        FactEdits edits = edits();
        MetadataFact automatic = definition(P1);
        assertThatThrownBy(() -> edits.remove(CALLER, "wiki", "p1", automatic.id(), null))
                .isInstanceOf(InvalidQueryException.class);
        FactEdits.Edited added = edits.add(CALLER, "wiki", "p1", new FactEdits.NewFact(FactTypes.MENTION, "MENTIONS",
                null, List.of(new FactEdits.NewArgument("TERM", "Dienst")), null));

        edits.remove(CALLER, "wiki", "p1", added.fact().id(), 1L);

        assertThat(facts.factsOf(P1)).doesNotContain(added.fact());
        assertThat(edits.history(CALLER, "wiki", "p1", added.fact().id())).extracting(
                FactEditStore.AuditEntry::action).containsExactly("add", "remove");
        assertThatThrownBy(() -> edits.history(CALLER, "wiki", "p2", added.fact().id()))
                .isInstanceOf(UnknownFactException.class);
    }

    @Test
    void changesNeedSyncAndAKnownFactAndAValidPayload() {
        FactEdits edits = edits();
        MetadataFact other = definition(new PageKey("wiki", "p2"));

        assertThatThrownBy(() -> edits.review(CALLER, "wiki", "p2", other.id(), FactReview.CONFIRMED, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> edits.review(CALLER, "wiki", "p1", other.id(), FactReview.CONFIRMED, null))
                .isInstanceOf(UnknownFactException.class);
        assertThatThrownBy(() -> edits.add(CALLER, "wiki", "p1", new FactEdits.NewFact("UNKNOWN", null, null,
                List.of(), null))).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> edits.add(CALLER, "wiki", "p1", new FactEdits.NewFact(FactTypes.MENTION, null,
                "[1]", List.of(), null))).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> edits.add(CALLER, "wiki", "p1", new FactEdits.NewFact(FactTypes.MENTION, null,
                null, List.of(new FactEdits.NewArgument("klein", "x")), null)))
                .isInstanceOf(InvalidQueryException.class);
        assertThat(editStore.all()).isEmpty();
    }
}
