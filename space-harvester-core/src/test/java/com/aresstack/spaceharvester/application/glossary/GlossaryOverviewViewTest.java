package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.Category;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Argument;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Basis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.MentionKind;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Priority;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Quality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Role;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Voice;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Die fertige Sicht des Glossar-Reiters (ADR 0032, FL5b-2c): Einträge mit Wichtigkeit verknüpft, sortiert, gefiltert
 * und gezählt; Einzelheiten aus dem gehaltenen Ergebnis ohne neues Rechnen; nichts gespeichert.
 */
class GlossaryOverviewViewTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final RelationScope WIKI = new RelationScope("wiki", null);
    private static final String SHA = "sha256:" + "d".repeat(64);
    private static final ParsedStatements.Features FACT = new ParsedStatements.Features(
            ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT);

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source());
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source()) : Optional.empty();
        }
    };
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();
    private GlossaryStore store;
    private Glossary glossary;
    private GlossaryOverview overview;

    private static HarvestSource source() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        return source;
    }

    private void page(String id, String space, String actor, String verb, String affected) {
        PageKey key = new PageKey("wiki", id);
        PageMetadata metadata = new PageMetadata(id, "confluence:wiki/page/" + id, "Seite " + id, space, null, "1",
                T0, null, PageMetadata.sorted(Map.of()));
        cache.put(key, PageSnapshot.of("wiki", metadata, new NativePayload(
                "Erfunden.".getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0));
        int length = actor.length() + verb.length() + affected.length() + 3;
        String first = "wiki/" + id + "@1#s0.t0";
        String second = "wiki/" + id + "@1#s0.t1";
        List<ParsedStatements.Mention> mentions = List.of(
                new ParsedStatements.Mention(first, 0, 0, actor.length(), actor, actor, actor, MentionKind.NOUN,
                        false, Set.of()),
                new ParsedStatements.Mention(second, 0, length - affected.length() - 1, length - 1, affected,
                        affected, affected, MentionKind.NOUN, false, Set.of()));
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/" + id + "@1#s0.v2", 0,
                actor.length() + 1, actor.length() + 1 + verb.length(), verb, verb, Voice.ACTIVE,
                ParsedStatements.Clause.MAIN, true, List.of(sure(Role.ACTOR, first), sure(Role.AFFECTED_OBJECT,
                second)), Set.of(), 0, Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED, FACT, null);
        ParsedStatements parsed = new ParsedStatements(List.of(new ParsedStatements.Sentence(0, 0, length)),
                mentions, List.of(statement), List.of(), List.of(), null);
        pages.add(new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, space, "Seite " + id,
                PageStatements.State.CURRENT, null), new PageStatementAnalysis(key, space, "Seite " + id, "1", SHA,
                "fake", "1", Instant.EPOCH, parsed)));
    }

    private static Argument sure(Role role, String mention) {
        return new Argument(role, mention, "rel", null, Priority.CORE, Basis.PARSER, true);
    }

    private static RelationEvidence on(String pageId) {
        return new RelationEvidence("wiki", pageId, 0, 10, SHA);
    }

    private static Glossary.Proposal rule(String term, Category category, String pageId) {
        return new Glossary.Proposal(term, List.of(on(pageId)), new GlossaryEntry.Classification(category,
                GlossaryEntry.ClassifiedBy.RULE, null), null);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryGlossaryStore();
        glossary = new Glossary(catalog, store, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        overview = new GlossaryOverview(caller -> {
            reads.incrementAndGet();
            return List.copyOf(pages);
        }, () -> GlossaryRuleSettings.DEFAULT, glossary);
        page("1", "FOE", "Bewilligungsstelle", "prüft", "Antrag");
        page("2", "FOE", "Antragsdatenbank", "speichert", "Antrag");
        page("3", "IT", "Server", "liefert", "Bescheid");
        glossary.propose(RelationOrigin.RULE, "regel", WIKI, "r@1", List.of(rule("Antrag", Category.UNKLAR, "1"),
                rule("Antragsdatenbank", Category.TECHNISCH, "2"), rule("Bewilligungsstelle", Category.ROLLE, "1"),
                rule("Bescheid", Category.FACHLICH, "3")), Set.of());
        glossary.add(EDITOR, new Glossary.NewEntry("Zuwendung", WIKI, null, List.of()));
        GlossaryEntry bescheid = store.find(GlossaryEntry.idOf("Bescheid", WIKI)).orElseThrow();
        glossary.reject(EDITOR, bescheid.id(), bescheid.revision());
    }

    @Test
    void theViewJoinsSortsFiltersAndCountsAndHoldsTheDerivationForDetails() {
        GlossaryOverview.View view = overview.view(EDITOR, WIKI, key -> true, GlossaryOverview.Query.DEFAULT,
                () -> false);

        assertThat(view.rows()).extracting(row -> row.entry().term())
                .as("nach Wichtigkeit, ohne TECHNISCH, ohne abgelehnte, ohne Wichtigkeit hinten")
                .containsExactly("Antrag", "Bewilligungsstelle", "Zuwendung");
        assertThat(view.rows().get(2).importance()).isNull();
        assertThat(view.rows().get(2).category()).isEqualTo(Category.UNKLAR);
        assertThat(view.countsByCategory()).containsEntry(Category.TECHNISCH, 1).containsEntry(Category.UNKLAR, 2)
                .containsEntry(Category.ROLLE, 1).containsEntry(Category.FACHLICH, 0);
        assertThat(view.countsByStatus()).containsEntry(ReviewStatus.PROPOSED, 2)
                .containsEntry(ReviewStatus.CONFIRMED, 1).containsEntry(ReviewStatus.REJECTED, 1);
        assertThat(view.pages()).isEqualTo(3);

        int before = reads.get();
        assertThat(view.term("antrag")).get().satisfies(term -> assertThat(term.undergoes())
                .extracting(GlossaryDerivation.Use::lemma).contains("prüft", "speichert"));
        assertThat(view.term("unbekannt")).isEmpty();
        assertThat(reads.get()).as("kein zweiter Durchlauf je Klick").isEqualTo(before);

        GlossaryOverview.View rejected = overview.view(EDITOR, WIKI, key -> true,
                new GlossaryOverview.Query(null, null, ReviewStatus.REJECTED), () -> false);
        assertThat(rejected.rows()).extracting(row -> row.entry().term()).containsExactly("Bescheid");
        assertThat(rejected.countsByStatus()).containsEntry(ReviewStatus.REJECTED, 1);
        assertThat(overview.view(EDITOR, WIKI, key -> true, new GlossaryOverview.Query("bew", null, null),
                () -> false).rows()).extracting(row -> row.entry().term()).containsExactly("Bewilligungsstelle");
    }

    @Test
    void refiningReusesTheHeldDerivationAndReadsTheEntriesAgain() {
        GlossaryOverview.View full = overview.view(EDITOR, WIKI, key -> true, GlossaryOverview.Query.DEFAULT,
                () -> false);
        int before = reads.get();
        glossary.add(EDITOR, new Glossary.NewEntry("Bewilligung", WIKI, null, List.of()));

        GlossaryOverview.View refined = overview.view(EDITOR, full, new GlossaryOverview.Query("bew", null, null),
                () -> false);

        assertThat(reads.get()).as("keine neue Ableitung beim Verfeinern").isEqualTo(before);
        assertThat(refined.derivation()).isSameAs(full.derivation());
        assertThat(refined.scope()).isEqualTo(full.scope());
        assertThat(refined.pages()).isEqualTo(full.pages());
        assertThat(refined.rows()).extracting(row -> row.entry().term())
                .as("Einträge neu gelesen, Wichtigkeit aus dem gehaltenen Ergebnis")
                .containsExactly("Bewilligungsstelle", "Bewilligung");
        assertThat(refined.rows().get(0).importance()).isNotNull();
        assertThat(refined.countsByStatus()).containsEntry(ReviewStatus.CONFIRMED, 1)
                .containsEntry(ReviewStatus.PROPOSED, 1);
        assertThatThrownBy(() -> overview.view(EDITOR, full, GlossaryOverview.Query.DEFAULT, () -> true))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void importanceFollowsTheSelectionAndNothingIsStored() {
        long revisions = store.all().stream().mapToLong(GlossaryEntry::revision).sum();

        GlossaryOverview.View it = overview.view(EDITOR, WIKI, key -> key.pageId().equals("3"),
                new GlossaryOverview.Query(null, null, null), () -> false);

        assertThat(it.pages()).isEqualTo(1);
        assertThat(it.rows()).filteredOn(row -> row.importance() != null).extracting(row -> row.entry().term())
                .isEmpty();
        assertThat(store.all().stream().mapToLong(GlossaryEntry::revision).sum()).isEqualTo(revisions);
        assertThatThrownBy(() -> overview.view(EDITOR, WIKI, key -> true, GlossaryOverview.Query.DEFAULT,
                () -> true)).isInstanceOf(CancellationException.class);
    }

    @Test
    void theViewOverFiveThousandPagesAndFiveHundredEntriesStaysFast() {
        pages.clear();
        cache.clear();
        store = new InMemoryGlossaryStore();
        glossary = new Glossary(catalog, store, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        overview = new GlossaryOverview(caller -> List.copyOf(pages), () -> GlossaryRuleSettings.DEFAULT, glossary);
        for (int i = 0; i < 5000; i++) {
            page(String.valueOf(i), "FOE", "Stelle" + (i % 700), "prüft", "Vorgang" + (i % 900));
        }
        List<Glossary.Proposal> proposals = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            proposals.add(rule("Vorgang" + i, Category.UNKLAR, String.valueOf(i)));
        }
        glossary.propose(RelationOrigin.RULE, "regel", WIKI, "r@1", proposals, Set.of());

        long start = System.nanoTime();
        GlossaryOverview.View view = overview.view(EDITOR, WIKI, key -> true, GlossaryOverview.Query.DEFAULT,
                () -> false);
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println("Glossar-Sicht über 5000 Seiten und 500 Einträge: " + millis + " ms");
        assertThat(view.rows()).hasSize(500);
        assertThat(view.pages()).isEqualTo(5000);
        assertThat(millis).as("großzügige Obergrenze").isLessThan(15_000);
    }
}
