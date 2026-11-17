package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.Category;
import com.aresstack.spaceharvester.application.glossary.GlossaryEntry.Classification;
import com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy;
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
import com.aresstack.spaceharvester.persistence.files.FileGlossaryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vorschläge der Regel im Fachglossar (ADR 0032, FL5b): Herkunft {@code RULE}, Stützung je Lauf, Einordnung mit
 * Vorrang Mensch vor KI vor Regel, Filter nach Einordnung, Sichtbarkeit wie KI-Vorschläge, Ablage über Neustarts.
 */
class GlossaryRuleOriginTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final RelationScope WIKI = new RelationScope("wiki", null);
    private static final String SHA = "sha256:" + "b".repeat(64);
    private static final Classification RULE_TECH = new Classification(Category.TECHNISCH, ClassifiedBy.RULE,
            "TECHNICAL_HEAD");
    private static final Classification RULE_OPEN = new Classification(Category.UNKLAR, ClassifiedBy.RULE, null);
    private static final Classification AI_DOMAIN = new Classification(Category.FACHLICH, ClassifiedBy.AI,
            "MODEL_DOMAIN");

    @TempDir
    Path directory;

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
    private GlossaryStore store;
    private Glossary glossary;

    private static HarvestSource source() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        return source;
    }

    private void cached(String pageId) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV",
                null, "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                "Erfundener Text.".getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
    }

    private void open() {
        store = new FileGlossaryStore(directory);
        glossary = new Glossary(catalog, store, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static RelationEvidence on(String pageId, int start) {
        return new RelationEvidence("wiki", pageId, start, start + 10, SHA);
    }

    private static Glossary.Proposal rule(String term, Classification classification, RelationEvidence... evidence) {
        return new Glossary.Proposal(term, List.of(evidence), classification, null);
    }

    private Glossary.ProposalRun ruleRun(Glossary.Proposal... proposals) {
        return glossary.propose(RelationOrigin.RULE, "regel", WIKI, "regeln@1", List.of(proposals), Set.of());
    }

    private Glossary.ProposalRun aiRun(Glossary.Proposal... proposals) {
        return glossary.propose(RelationOrigin.AI, "ki", WIKI, "fake:model@1", List.of(proposals), Set.of());
    }

    private GlossaryEntry entry(String term) {
        return store.find(GlossaryEntry.idOf(term, WIKI)).orElseThrow();
    }

    private Optional<GlossaryEntry> stored(String term) {
        return store.find(GlossaryEntry.idOf(term, WIKI));
    }

    @BeforeEach
    void setUp() {
        cached("1");
        cached("2");
        open();
    }

    @Test
    void ruleProposalsBecomeRuleEntriesWithClassificationThatSurviveARestart() {
        Glossary.ProposalRun run = ruleRun(rule("Datenbank", RULE_TECH, on("1", 0)),
                new Glossary.Proposal("Förderantrag", List.of(on("2", 5)), RULE_OPEN, on("2", 5)));

        assertThat(run.added()).isEqualTo(2);
        GlossaryEntry datenbank = entry("Datenbank");
        assertThat(datenbank.origin()).isEqualTo(RelationOrigin.RULE);
        assertThat(datenbank.status()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(datenbank.classification()).isEqualTo(RULE_TECH);
        assertThat(datenbank.supportedBy()).containsExactly(RelationOrigin.RULE);
        assertThat(entry("Förderantrag").definitionProposal()).isEqualTo(on("2", 5));

        open();

        assertThat(entry("Datenbank")).isEqualTo(datenbank);
        assertThat(entry("Förderantrag").definitionProposal()).isEqualTo(on("2", 5));
    }

    @Test
    void anEntryTheAiStillSupportsSurvivesARuleRunThatNoLongerNamesIt() {
        ruleRun(rule("Zuschuss", RULE_OPEN, on("1", 0)));
        aiRun(new Glossary.Proposal("Zuschuss", List.of(on("1", 0)), AI_DOMAIN, null));
        assertThat(entry("Zuschuss").supportedBy()).containsExactlyInAnyOrder(RelationOrigin.RULE, RelationOrigin.AI);
        assertThat(entry("Zuschuss").origin()).as("Herkunft bleibt").isEqualTo(RelationOrigin.RULE);

        Glossary.ProposalRun withoutRule = ruleRun();

        assertThat(withoutRule.withdrawn()).isZero();
        assertThat(entry("Zuschuss").supportedBy()).containsExactly(RelationOrigin.AI);

        Glossary.ProposalRun withoutAi = aiRun();

        assertThat(withoutAi.withdrawn()).as("niemand stützt ihn mehr").isEqualTo(1);
        assertThat(stored("Zuschuss")).isEmpty();
    }

    @Test
    void anEntryTheRuleStillSupportsSurvivesAnAiRunThatNoLongerNamesIt() {
        aiRun(new Glossary.Proposal("Bewilligung", List.of(on("1", 0))));
        ruleRun(rule("Bewilligung", RULE_OPEN, on("1", 0)));

        aiRun();

        assertThat(entry("Bewilligung").origin()).isEqualTo(RelationOrigin.AI);
        assertThat(entry("Bewilligung").supportedBy()).containsExactly(RelationOrigin.RULE);
        assertThat(ruleRun().withdrawn()).isEqualTo(1);
    }

    @Test
    void aRunOnlyReconcilesWhatItSupportsAndSkippedRunsDeleteNothing() {
        ruleRun(rule("Datenbank", RULE_TECH, on("1", 0)));
        aiRun(new Glossary.Proposal("Haushaltsverfahren", List.of(on("2", 0))));

        aiRun(new Glossary.Proposal("Haushaltsverfahren", List.of(on("2", 0))));
        glossary.proposeSome(RelationOrigin.RULE, "regel", WIKI, "regeln@2", List.of());

        assertThat(stored("Datenbank")).as("KI-Lauf berührt reine Regel-Einträge nicht").isPresent();
        assertThat(stored("Haushaltsverfahren")).isPresent();
    }

    @Test
    void confirmedEntriesGetStaleOnlyWhenNobodySupportsThemAnymore() {
        ruleRun(rule("Zuschuss", RULE_OPEN, on("1", 0)));
        aiRun(new Glossary.Proposal("Zuschuss", List.of(on("1", 0))));
        glossary.confirm(EDITOR, entry("Zuschuss").id(), null);

        assertThat(ruleRun().stale()).isZero();
        assertThat(entry("Zuschuss").evidenceStale()).isFalse();
        assertThat(aiRun().stale()).isEqualTo(1);
        assertThat(entry("Zuschuss").evidenceStale()).isTrue();
        assertThat(entry("Zuschuss").status()).isEqualTo(ReviewStatus.CONFIRMED);

        ruleRun(rule("Zuschuss", RULE_OPEN, on("2", 0)));

        assertThat(entry("Zuschuss").evidenceStale()).as("wieder belegt").isFalse();
        assertThat(entry("Zuschuss").evidence()).as("bestätigte Belege bleiben").containsExactly(on("1", 0));
    }

    @Test
    void classificationPrefersHumanOverAiOverRuleAndAiReplacesTheRuleOnlyWhenItWasUnclear() {
        ruleRun(rule("Zuschuss", RULE_OPEN, on("1", 0)), rule("Datenbank", RULE_TECH, on("1", 20)));
        aiRun(new Glossary.Proposal("Zuschuss", List.of(on("1", 0)), AI_DOMAIN, null),
                new Glossary.Proposal("Datenbank", List.of(on("1", 20)), AI_DOMAIN, null));

        assertThat(entry("Zuschuss").classification()).as("Regel ließ es offen").isEqualTo(AI_DOMAIN);
        assertThat(entry("Datenbank").classification()).as("Regel war sicher").isEqualTo(RULE_TECH);

        ruleRun(rule("Zuschuss", RULE_OPEN, on("1", 0)));
        assertThat(entry("Zuschuss").classification()).as("Regel überschreibt nie die KI").isEqualTo(AI_DOMAIN);

        GlossaryEntry manual = glossary.categorize(EDITOR, entry("Zuschuss").id(), Category.TECHNISCH,
                entry("Zuschuss").revision());
        assertThat(manual.classification()).isEqualTo(new Classification(Category.TECHNISCH, ClassifiedBy.MANUAL,
                null));
        aiRun(new Glossary.Proposal("Zuschuss", List.of(on("1", 0)), AI_DOMAIN, null));
        ruleRun(rule("Zuschuss", RULE_OPEN, on("1", 0)));
        assertThat(entry("Zuschuss").classification().by()).isEqualTo(ClassifiedBy.MANUAL);
        assertThat(store.history(entry("Zuschuss").id())).extracting(GlossaryStore.AuditEntry::action)
                .contains("categorize");
    }

    @Test
    void searchFiltersByCategoryAndEntriesWithoutClassificationCountAsUnclear() {
        ruleRun(rule("Datenbank", RULE_TECH, on("1", 0)), rule("Zuschuss", RULE_OPEN, on("1", 20)));
        glossary.add(EDITOR, new Glossary.NewEntry("Bewilligungsstelle", WIKI, null, List.of()));

        assertThat(terms(Set.of(Category.TECHNISCH))).containsExactly("Datenbank");
        assertThat(terms(Set.of(Category.FACHLICH, Category.ROLLE, Category.UNKLAR)))
                .containsExactlyInAnyOrder("Zuschuss", "Bewilligungsstelle");
        assertThat(terms(null)).hasSize(3);
    }

    @Test
    void ruleEntriesWithoutReadableEvidenceStayHiddenLikeAiProposals() {
        ruleRun(rule("Geheimwort", RULE_OPEN, on("9", 0)),
                new Glossary.Proposal("Zuschuss", List.of(on("1", 0), on("9", 4)), RULE_OPEN, on("9", 4)));

        assertThat(terms(null)).containsExactly("Zuschuss");
        GlossaryEntry shown = glossary.entries(EDITOR, search(null)).entries().get(0);
        assertThat(shown.evidence()).containsExactly(on("1", 0));
        assertThat(shown.definitionProposal()).as("nur, wenn lesbar").isNull();
    }

    @Test
    void removingASourceDropsItsRuleEntriesAndGlobalManualEntriesOnlyLoseTheirEvidence() {
        ruleRun(rule("Datenbank", RULE_TECH, on("1", 0)));
        aiRun(new Glossary.Proposal("Haushaltsverfahren", List.of(on("2", 0))));
        GlossaryEntry global = glossary.add(EDITOR, new Glossary.NewEntry("Zuschuss", RelationScope.GLOBAL,
                "Eigene Erklärung", List.of(on("1", 0))));
        GlossaryEntry local = glossary.add(EDITOR, new Glossary.NewEntry("Bewilligung", WIKI, null, List.of()));

        assertThat(glossary.removeSource("wiki", "bereinigen")).isEqualTo(2);

        assertThat(stored("Datenbank")).as("Regel-Eintrag fällt mit der Quelle").isEmpty();
        assertThat(stored("Haushaltsverfahren")).isEmpty();
        GlossaryEntry kept = store.find(global.id()).orElseThrow();
        assertThat(kept.evidence()).isEmpty();
        assertThat(kept.explanation()).isEqualTo("Eigene Erklärung");
        assertThat(store.find(local.id())).as("manuell bleibt").isPresent();
    }

    @Test
    void runEntriesWithoutAnyEvidenceAreNeverVisible() {
        ruleRun(rule("Datenbank", RULE_TECH, on("1", 0)));
        GlossaryEntry entry = entry("Datenbank");
        store.write(List.of(entry.withoutEvidenceOf("wiki", T0)), List.of(), List.of());

        assertThat(terms(null)).isEmpty();
    }

    @Test
    void reasonsAreIdentifiersNeverFreeText() {
        assertThatThrownBy(() -> new Classification(Category.FACHLICH, ClassifiedBy.AI,
                "Weil auf Seite 3 steht: Der Zuschuss"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Glossary.Proposal("Zuschuss", List.of(on("1", 0)),
                new Classification(Category.FACHLICH, ClassifiedBy.MANUAL, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oldFilesWithoutTheNewFieldsStayReadable() throws Exception {
        String id = GlossaryEntry.idOf("Zuschuss", WIKI);
        Files.writeString(directory.resolve("glossary.json"), """
                {"schema":"glossary/v1","entries":[{"id":"%s","term":"Zuschuss","normalized":"zuschuss",
                "sourceId":"wiki","spaceKey":null,"origin":"AI","status":"PROPOSED","evidence":[{"sourceId":"wiki",
                "pageId":"1","start":0,"end":10,"inputSha256":"%s"}],"evidenceStale":false,"explanation":null,
                "generation":"fake:model@1","createdBy":"ki","createdAt":"2026-11-01T00:00:00Z",
                "updatedAt":"2026-11-01T00:00:00Z","reviewedBy":null,"reviewedAt":null,"revision":1}]}
                """.formatted(id, SHA));

        open();

        GlossaryEntry old = entry("Zuschuss");
        assertThat(old.classification()).isNull();
        assertThat(old.definitionProposal()).isNull();
        assertThat(old.supportedBy()).as("der Lauf, der ihn anlegte").containsExactly(RelationOrigin.AI);
        assertThat(ruleRun().withdrawn()).as("Regel stützte ihn nie").isZero();
        assertThat(aiRun().withdrawn()).isEqualTo(1);
    }

    private Glossary.Search search(Set<Category> categories) {
        return new Glossary.Search(null, null, null, null, null, null, 50, null, categories);
    }

    private List<String> terms(Set<Category> categories) {
        return glossary.entries(EDITOR, search(categories)).entries().stream().map(GlossaryEntry::term).toList();
    }
}
