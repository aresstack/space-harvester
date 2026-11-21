package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Zweiter Analysator (Fahrplan Paket A2): eigene Ablage, Wahl je Lauf, nur KI-freigegebene Seiten. Erfundene Sätze. */
class StatementAnalysisModelTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final String SENTENCE = "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg des Kunden.";

    private final InMemoryKnowledgeGraphStore parserStore = new InMemoryKnowledgeGraphStore();
    private final InMemoryKnowledgeGraphStore modelStore = new InMemoryKnowledgeGraphStore();
    private final AtomicReference<AnalyzerChoice> selected = new AtomicReference<>(AnalyzerChoice.PARSER);
    private final AnalyzerStores stores = new AnalyzerStores(parserStore, modelStore, selected::get);
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Set<PageKey> released = new HashSet<>();
    private final FakeStepModel chat = new FakeStepModel(input -> """
            {"satz": 1, "aussagen": [{"verb": "übermitteln", "handelnder": "Der Sachbearbeiter", "betroffenes": "den Beleg", "empfaenger": "dem Vorgesetzten"}]}
            """);
    private final ModelStatementAnalyzer model = new ModelStatementAnalyzer(chat, ModelStatementAnalyzerTest.SENTENCES);
    private final StatementAnalysis analysis = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), stores,
            new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), () -> parser,
            StatementAnalysisTest.clock(), null, null, () -> model, released::contains);
    private final PageSnapshot page = StatementAnalysisTest.page("1", "3", SENTENCE);

    @Test
    void bothAnalyzersKeepTheirOwnResultAndReadingFollowsTheSelectedStore() {
        released.add(page.key());

        analysis.analyze(CALLER, page, () -> false);
        List<StatementAnalyzer.Costs> costs = new ArrayList<>();
        PageStatements byModel = analysis.analyzeWith(CALLER, page, () -> false, AnalyzerChoice.MODEL, costs::add);

        assertThat(byModel.state()).isEqualTo(PageStatements.State.CURRENT);
        assertThat(byModel.analysis().analyzerId()).isEqualTo(ModelStatementAnalyzer.ID);
        assertThat(byModel.analysis().analyzerVersion()).isEqualTo("statement-model:fake-chat@0000|prompt-1|abgleich-2");
        assertThat(parserStore.find(page.key())).get().extracting(PageStatementAnalysis::analyzerId)
                .isEqualTo(parser.id());
        assertThat(modelStore.find(page.key())).get().extracting(PageStatementAnalysis::analyzerId)
                .isEqualTo(ModelStatementAnalyzer.ID);
        assertThat(costs).containsExactly(new StatementAnalyzer.Costs(1, 1, 0, 0, 0));

        assertThat(analysis.selected()).isEqualTo(AnalyzerChoice.PARSER);
        assertThat(analysis.read(CALLER, page).analysis().analyzerId()).isEqualTo(parser.id());
        selected.set(AnalyzerChoice.MODEL);
        assertThat(analysis.read(CALLER, page).analysis().analyzerId()).isEqualTo(ModelStatementAnalyzer.ID);
        assertThat(analysis.read(CALLER, page).state()).isEqualTo(PageStatements.State.CURRENT);
        assertThat(analysis.currentWith(CALLER, page, AnalyzerChoice.PARSER)).isTrue();
        assertThat(analysis.currentWith(CALLER, page, AnalyzerChoice.MODEL)).isTrue();
    }

    @Test
    void anotherModelMakesOnlyTheModelAnalysisStale() {
        released.add(page.key());
        analysis.analyze(CALLER, page, () -> false);
        analysis.analyzeWith(CALLER, page, () -> false, AnalyzerChoice.MODEL, costs -> { });

        chat.model = "other-chat";

        assertThat(analysis.read(CALLER, page, AnalyzerChoice.MODEL).reason())
                .isEqualTo(PageStatements.ANALYZER_CHANGED);
        assertThat(analysis.currentWith(CALLER, page, AnalyzerChoice.PARSER)).isTrue();
    }

    @Test
    void aPageWithoutReleaseNeverReachesTheModel() {
        assertThat(analysis.modelUnavailable(page.key()))
                .contains(AnalyzerUnavailableException.Reason.NOT_RELEASED);
        assertThatThrownBy(() -> analysis.analyzeWith(CALLER, page, () -> false, AnalyzerChoice.MODEL, c -> { }))
                .isInstanceOfSatisfying(AnalyzerUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(AnalyzerUnavailableException.Reason.NOT_RELEASED));
        assertThat(chat.inputs).isEmpty();
        assertThat(modelStore.all()).isEmpty();
    }

    @Test
    void withoutChatModelThePageIsSkippedNotFailed() {
        released.add(page.key());
        chat.available = false;

        assertThat(analysis.modelUnavailable(page.key()))
                .contains(AnalyzerUnavailableException.Reason.NO_AI_MODEL);
        assertThat(analysis.available(AnalyzerChoice.MODEL)).isTrue();
    }

    @Test
    void aReleaseWithdrawnDuringTheCallWritesNothing() {
        released.add(page.key());
        chat.answer = input -> {
            released.clear();
            return "";
        };

        assertThatThrownBy(() -> analysis.analyzeWith(CALLER, page, () -> false, AnalyzerChoice.MODEL, c -> { }))
                .isInstanceOf(AnalyzerUnavailableException.class);
        assertThat(modelStore.all()).isEmpty();
    }

    @Test
    void withdrawingTheReleaseRemovesOnlyTheModelAnalysis() {
        released.add(page.key());
        analysis.analyze(CALLER, page, () -> false);
        analysis.analyzeWith(CALLER, page, () -> false, AnalyzerChoice.MODEL, costs -> { });
        released.clear();

        assertThat(analysis.withdrawModel(List.of(page.key()), released::contains)).isEqualTo(1);

        assertThat(modelStore.find(page.key())).isEmpty();
        assertThat(parserStore.find(page.key())).isPresent();
    }

    @Test
    void removingAPageClearsBothStores() {
        released.add(page.key());
        analysis.analyze(CALLER, page, () -> false);
        analysis.analyzeWith(CALLER, page, () -> false, AnalyzerChoice.MODEL, costs -> { });

        stores.remove(page.key());

        assertThat(parserStore.all()).isEmpty();
        assertThat(modelStore.all()).isEmpty();
    }

    @Test
    void theChoiceIsParsedFromTheConfiguredValue() {
        assertThat(AnalyzerChoice.parse(null)).isEqualTo(AnalyzerChoice.PARSER);
        assertThat(AnalyzerChoice.parse(" model ")).isEqualTo(AnalyzerChoice.MODEL);
        assertThatThrownBy(() -> AnalyzerChoice.parse("llm")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AnalyzerChoice.of(ModelStatementAnalyzer.ID)).isEqualTo(AnalyzerChoice.MODEL);
        assertThat(AnalyzerChoice.of("maltparser")).isEqualTo(AnalyzerChoice.PARSER);
    }
}
