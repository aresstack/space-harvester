package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.CatalogPage;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.Stock;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Bereichszusammenfassung (ADR 0034 §7, Paket 4b; Angelo 24.11.2026: Umschalter „Änderungen | Bereich“): Bestand einer
 * Auswahl statt Änderungen, alle Seiten ohne Seitenbudget, KI-Freigabe je Seite, Ablage je Aufrufer,
 * „veraltet“ über den Fingerabdruck und kein Modellaufruf für einen leeren Bereich.
 */
class AreaSummaryTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final AccessContext CALLER = AccessContext.technicalClient("client:a");
    private static final AccessContext OTHER = AccessContext.technicalClient("client:b");
    private static final CacheGeneration CONFIRMED = new CacheGeneration("g1", CacheState.STALE, NOW, NOW);
    private static final Selection SPACE = Selection.of(Selection.Root.space("wiki", "DEV"));

    private final Map<String, Stock> stocks = new HashMap<>();
    private final HoldingsCatalog catalog = new HoldingsCatalog() {
        @Override
        public Optional<Stock> stock(String sourceId, String spaceKey) {
            return Optional.ofNullable(stocks.get(sourceId + "/" + spaceKey));
        }

        @Override
        public List<String> spaceKeys(String sourceId) {
            return stocks.keySet().stream().filter(key -> key.startsWith(sourceId + "/"))
                    .map(key -> key.substring(sourceId.length() + 1)).sorted().toList();
        }

        @Override
        public Optional<String> spaceOf(String sourceId, String pageId) {
            return Optional.empty();
        }
    };
    private final SourceCatalog sources = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(mock(HarvestSource.class)) : Optional.empty();
        }
    };
    private final Map<String, StoredSummary> summaries = new HashMap<>();
    private final Map<PageKey, PageHeader> headers = new HashMap<>();
    private final Set<String> released = new HashSet<>();
    private final Map<String, String> texts = new HashMap<>();
    private final List<String> inputs = new CopyOnWriteArrayList<>();
    private final List<String> instructions = new CopyOnWriteArrayList<>();
    private final List<String> marked = new CopyOnWriteArrayList<>();
    private Consumer<String> recorded = ignored -> {
    };
    private RuntimeException failure;
    /** Läuft vor jeder Anfrage an das Modell, etwa um den vorläufigen Stand mitten im Lauf zu lesen. */
    private Runnable beforeAnswer;
    private String answer = """
            {"zusammenfassung": "Der Bereich DEV beschreibt die Entwicklung.",
             "themen": [{"titel": "Einstieg", "text": "Wozu es DEV gibt.", "details": "Ausführlich.",
                         "seiten": ["1", "3"]}]}""";

    private final ChangeLogStore store = new ChangeLogStore() {
        @Override
        public List<ChangeEntry> changeEntries(String sourceId) {
            return List.of();
        }

        @Override
        public void changeLogRecorded(Consumer<String> listener) {
            recorded = listener;
        }

        @Override
        public List<UpdateMark> marks(String sourceId) {
            return List.of();
        }

        @Override
        public void mark(UpdateMark mark) {
        }

        @Override
        public Optional<StoredSummary> summary(String sourceId, String updateId, String spaceKey) {
            return Optional.ofNullable(summaries.get(sourceId + "/" + updateId + "/" + spaceKey));
        }

        @Override
        public void deleteSummary(String sourceId, String updateId, String spaceKey) {
            summaries.remove(sourceId + "/" + updateId + "/" + spaceKey);
        }

        @Override
        public void saveSummary(StoredSummary summary) {
            summaries.put(summary.sourceId() + "/" + summary.updateId() + "/" + summary.spaceKey(), summary);
            recorded.accept(summary.sourceId());
        }
    };
    private final SummaryModel model = new SummaryModel() {
        @Override
        public String version() {
            return "test-area:1";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int parallel() {
            return 1;
        }

        @Override
        public String complete(String given, String input) {
            if (failure != null) {
                throw failure;
            }
            if (beforeAnswer != null) {
                beforeAnswer.run();
            }
            instructions.add(given);
            inputs.add(input);
            return answer;
        }
    };
    private final List<Runnable> held = new ArrayList<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(1, 5, Duration.ofHours(1)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private final Holdings holdings = new Holdings(sources, catalog, access, 100);
    private final ChangeLog log = new ChangeLog(store, access, key -> Optional.ofNullable(headers.get(key)),
            () -> List.of("wiki"), new ChangeLog.Summaries(jobs, model, key -> released.contains(key.pageId()),
            Clock.fixed(NOW, ZoneOffset.UTC), new SummaryMaterial() {
                @Override
                public Optional<String> text(PageKey page) {
                    return Optional.ofNullable(texts.get(page.pageId()));
                }

                @Override
                public Optional<String> spaceName(PageKey page, String spaceKey) {
                    return Optional.of("Entwicklung");
                }
            })).areas(holdings);

    @AfterEach
    void close() {
        log.close();
        holdings.close();
        jobs.close();
    }

    /** DEV: 1 oben, darunter 2 und 3, unter 3 die 4; 5 ist nur aus der Quelle bekannt. */
    private void devSpace() {
        stocks.put("wiki/DEV", new Stock(CONFIRMED, List.of(
                new CatalogPage("1", null, true, "1", "1"),
                new CatalogPage("2", "1", true, "1", "1"),
                new CatalogPage("3", "1", true, "1", "1"),
                new CatalogPage("4", "3", true, "1", "1"),
                new CatalogPage("5", "1", false, "1", null)), Set.of(), List.of()));
        page("1", null, "Startseite");
        page("2", "1", "Glossar");
        page("3", "1", "Abläufe");
        page("4", "3", "Freigabe");
        for (String id : List.of("1", "2", "3", "4")) {
            texts.put(id, "Inhalt von Seite " + id);
        }
        released.addAll(List.of("1", "2", "3", "4"));
        log.subscribe(CALLER, "wiki", sourceId -> marked.add(sourceId));
    }

    private void page(String pageId, String parentId, String title) {
        headers.put(new PageKey("wiki", pageId), PageHeader.of("wiki", new PageMetadata(pageId, "res:" + pageId,
                title, "DEV", parentId, "1", NOW, URI.create("https://wiki.example.org/" + pageId),
                PageMetadata.sorted(Map.of())), NOW));
    }

    private void runHeld() {
        while (!held.isEmpty()) {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            now.forEach(Runnable::run);
        }
    }

    @Test
    void summarizesTheStoredPagesOfASpaceDidacticallyAndReadsItBackAsArea() throws Exception {
        devSpace();
        JobStatus job = log.summarizeArea(CALLER, SPACE, true);
        assertThat(job.type()).isEqualTo(JobType.UPDATE_SUMMARY);
        assertThat(job.parameters()).containsEntry(ChangeLog.AREA_PARAMETER,
                SummaryScope.selection(CALLER, SPACE).key()).containsKey(Jobs.INTERACTIVE);
        runHeld();

        assertThat(instructions).first().isEqualTo(AreaSummarizer.INSTRUCTIONS);
        // Titel, Pfad, Space-Name und Auszug je Seite; die nur aus der Quelle bekannte 5 zählt als nicht lesbar.
        assertThat(inputs.getFirst()).contains("\"titel\":\"Freigabe\"", "\"pfad\":[\"Startseite\",\"Abläufe\"]",
                "\"space\":\"Entwicklung\"", "Inhalt von Seite 4", "\"nichtLesbar\":1").doesNotContain("\"art\"");
        Optional<UpdateSummary> read = log.areaSummary(CALLER, SPACE);
        assertThat(read).hasValueSatisfying(summary -> {
            assertThat(summary.isArea()).isTrue();
            assertThat(summary.updateId()).isEqualTo(UpdateSummary.AREA);
            assertThat(summary.spaceKey()).isEqualTo("DEV");
            assertThat(summary.text()).isEqualTo("Der Bereich DEV beschreibt die Entwicklung.");
            assertThat(summary.statements()).singleElement().satisfies(statement -> assertThat(statement.pages())
                    .extracting(SummaryPage::title, SummaryPage::sourceId).containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Startseite", "wiki"),
                            org.assertj.core.groups.Tuple.tuple("Abläufe", "wiki")));
            assertThat(summary.stale()).isFalse();
            assertThat(summary.spaceName("DEV")).isEqualTo("Entwicklung");
        });
        // Die Ansichten der Quelle erfahren davon (zugestellt auf dem Thread der Meldungen).
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (marked.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(marked).contains("wiki");
    }

    /**
     * Angelo 24.11.2026: „nicht pauschal 300 Seiten und dann ist Schluss“. Ein großer Bereich geht vollständig ein, in
     * mehreren Teilen, die danach zusammengeführt werden; keine Seite fällt einem Budget zum Opfer.
     */
    @Test
    void everyPageOfALargeAreaReachesTheModelInPartsWithoutAPageBudget() {
        int count = 400;
        List<CatalogPage> pages = new ArrayList<>();
        pages.add(new CatalogPage("1", null, true, "1", "1"));
        page("1", null, "Startseite");
        texts.put("1", "Inhalt von Seite 1");
        released.add("1");
        for (int i = 2; i <= count; i++) {
            String id = String.valueOf(i);
            pages.add(new CatalogPage(id, "1", true, "1", "1"));
            page(id, "1", "Thema " + id);
            texts.put(id, "Inhalt von Seite " + id + ". " + "Erklärung ".repeat(20));
            released.add(id);
        }
        stocks.put("wiki/DEV", new Stock(CONFIRMED, pages, Set.of(), List.of()));

        log.summarizeArea(CALLER, SPACE, false);
        runHeld();

        List<String> parts = instructions.stream().filter(AreaSummarizer.INSTRUCTIONS::equals).toList();
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(instructions).anyMatch(given -> given.startsWith(AreaSummarizer.COMBINE));
        String all = String.join("\n", inputs);
        for (int i = 2; i <= count; i++) {
            assertThat(all).contains("\"titel\":\"Thema " + i + "\"");
        }
        assertThat(inputs).noneMatch(input -> input.contains("\"nichtLesbar\":") && !input.contains(
                "\"nichtLesbar\":0"));
        assertThat(log.areaSummary(CALLER, SPACE)).isPresent();
    }

    /**
     * Angelo: „Teilergebnisse sofort“. Nach jedem fertigen Teil liegt ein vorläufiger Stand mit den Themen der fertigen
     * Teile vor, noch ohne Überblickstext; die Ablage am Ende nimmt ihn weg.
     */
    @Test
    void finishedPartsAreReadableAsPartialOverviewWhileTheRunGoesOn() {
        List<CatalogPage> pages = new ArrayList<>();
        pages.add(new CatalogPage("1", null, true, "1", "1"));
        page("1", null, "Startseite");
        texts.put("1", "Inhalt von Seite 1");
        released.add("1");
        for (int i = 2; i <= 400; i++) {
            String id = String.valueOf(i);
            pages.add(new CatalogPage(id, "1", true, "1", "1"));
            page(id, "1", "Thema " + id);
            texts.put(id, "Inhalt von Seite " + id + ". " + "Erklärung ".repeat(20));
            released.add(id);
        }
        stocks.put("wiki/DEV", new Stock(CONFIRMED, pages, Set.of(), List.of()));
        List<Optional<UpdateSummary>> seen = new CopyOnWriteArrayList<>();
        beforeAnswer = () -> seen.add(log.areaPartial(CALLER, SPACE));

        log.summarizeArea(CALLER, SPACE, false);
        runHeld();

        assertThat(seen.getFirst()).as("vor dem ersten Teil gibt es nichts").isEmpty();
        assertThat(seen).anySatisfy(partial -> assertThat(partial).hasValueSatisfying(summary -> {
            assertThat(summary.isArea()).isTrue();
            assertThat(summary.text()).isEmpty();
            assertThat(summary.statements()).isNotEmpty();
        }));
        assertThat(log.areaPartial(CALLER, SPACE)).as("nach der Ablage kein vorläufiger Stand").isEmpty();
        assertThat(log.areaSummary(CALLER, SPACE)).isPresent();
    }

    @Test
    void pagesWithoutAiReleaseGoOnlyAsNumberAndLosingTheReleaseHidesTheSummary() {
        devSpace();
        released.remove("2");
        log.summarizeArea(CALLER, SPACE, false);
        runHeld();
        assertThat(inputs.getFirst()).doesNotContain("Glossar");
        assertThat(log.areaSummary(CALLER, SPACE)).isPresent();
        released.remove("1");
        assertThat(log.areaSummary(CALLER, SPACE)).isEmpty();
    }

    @Test
    void theSummaryBelongsToItsCallerAndBecomesStaleWhenTheStockChanges() {
        devSpace();
        log.summarizeArea(CALLER, SPACE, false);
        runHeld();
        assertThat(log.areaSummary(OTHER, SPACE)).isEmpty();
        stocks.put("wiki/DEV", new Stock(CONFIRMED, List.of(
                new CatalogPage("1", null, true, "2", "2"),
                new CatalogPage("2", "1", true, "1", "1"),
                new CatalogPage("3", "1", true, "1", "1"),
                new CatalogPage("4", "3", true, "1", "1")), Set.of(), List.of()));
        assertThat(log.areaSummary(CALLER, SPACE)).hasValueSatisfying(summary ->
                assertThat(summary.stale()).isTrue());
    }

    @Test
    void deletingTheSummaryRemovesOnlyItTellsTheViewsAndIsNotOfferedWhileItIsMade() throws Exception {
        // ADR 0047: lokales Artefakt löschen; während „Zusammenfassen“ läuft, geht es nicht.
        devSpace();
        log.summarizeArea(CALLER, SPACE, false);
        assertThat(log.areaSummaryDeletable(CALLER, SPACE)).isFalse();
        assertThatThrownBy(() -> log.deleteAreaSummary(CALLER, SPACE)).isInstanceOf(IllegalStateException.class);
        runHeld();
        assertThat(log.areaSummary(CALLER, SPACE)).isPresent();
        marked.clear();

        assertThat(log.areaSummaryDeletable(CALLER, SPACE)).isTrue();
        assertThat(log.deleteAreaSummary(CALLER, SPACE)).isTrue();

        assertThat(log.areaSummary(CALLER, SPACE)).isEmpty();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (marked.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(marked).contains("wiki");
        assertThat(log.deleteAreaSummary(CALLER, SPACE)).as("schon weg").isFalse();
    }

    @Test
    void anOpenRunForTheSameAreaIsTheAnswerAndAnEmptyAreaNeverCallsTheModel() {
        devSpace();
        JobStatus first = log.summarizeArea(CALLER, SPACE, false);
        assertThat(log.summarizeArea(CALLER, SPACE, true).id()).isEqualTo(first.id());
        stocks.put("wiki/OPS", new Stock(CONFIRMED, List.of(), Set.of(), List.of()));
        assertThatThrownBy(() -> log.summarizeArea(CALLER, Selection.of(Selection.Root.space("wiki", "OPS")), true))
                .isInstanceOfSatisfying(SummaryUnavailableException.class, e ->
                        assertThat(e.reason()).isEqualTo(SummaryUnavailableException.Reason.EMPTY_AREA));
    }

    /**
     * Fehler 24.11.2026 (Angelo: „Zusammenfassen“ unter „Bereich“ endet sofort ohne Überblick): Ohne geladene oder ohne
     * freigegebene Seite bekam das Modell nur {@code "seiten":[]}. Jetzt nennt der Start den Grund, ohne Modellaufruf.
     */
    @Test
    void withoutAReleasedOrLoadedPageTheStartSaysWhyAndNeverAsksTheModel() {
        devSpace();
        released.clear();
        assertThatThrownBy(() -> log.summarizeArea(CALLER, SPACE, true))
                .isInstanceOfSatisfying(SummaryUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(SummaryUnavailableException.Reason.AREA_NOT_RELEASED));
        stocks.put("wiki/OPS", new Stock(CONFIRMED, List.of(new CatalogPage("9", null, false, "1", null)),
                Set.of(), List.of()));
        assertThatThrownBy(() -> log.summarizeArea(CALLER, Selection.of(Selection.Root.space("wiki", "OPS")), true))
                .isInstanceOfSatisfying(SummaryUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(SummaryUnavailableException.Reason.AREA_NOT_LOADED));
        assertThat(inputs).isEmpty();
    }

    /** Scheitert der KI-Dienst, nennt der Auftrag den Grund statt „Unerwarteter Fehler (AiServiceException)“. */
    @Test
    void aFailingModelEndsTheRunWithAReadableReason() {
        devSpace();
        failure = new com.aresstack.spaceharvester.ai.AiServiceException(
                com.aresstack.spaceharvester.ai.AiServiceException.Reason.UNAUTHORIZED, "HTTP 403 vom Dienst");
        JobStatus job = log.summarizeArea(CALLER, SPACE, true);
        runHeld();
        JobStatus ended = jobs.status(job.id());
        assertThat(ended.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.FAILED);
        assertThat(ended.error().code()).isEqualTo("AI_UNAUTHORIZED");
        assertThat(ended.error().message()).isEqualTo("Der KI-Dienst lehnt den Zugang ab (HTTP 403)");
    }
}
