package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.standing.DecisionReviews;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StandingDecisionStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Die Prüfung durch Menschen wirkt auf den Lesewegen (Knowledge Graph, Aussagen-Reiter), nie in der Ablage der Analysen
 * (ADR 0032, W3). Erfundene Seiten.
 */
class StatementReviewsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final Instant AT = Instant.parse("2026-11-20T09:00:00Z");

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private final StandingDecisionStore decisions = StandingDecisionStore.inMemory();
    private final StatementAnalysis statements = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access, () -> parser,
            StatementAnalysisTest.clock(), null, null, () -> null, page -> false, new DecisionReviews(decisions));
    private final KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
            KnowledgeGraph.TermExpansion.NONE);

    @Test
    void bothReadPathsShowTheDecisionWhileTheStoreKeepsItsOwnValue() {
        PageSnapshot snapshot = analyse("1", "Die Buchhaltung prüft den Beleg.");
        String id = stored(snapshot.key()).id();

        review(snapshot.key(), id, AnalyzerChoice.PARSER, ParsedStatements.Review.CONFIRMED);

        assertThat(graph.analysed(CALLER)).singleElement().satisfies(page -> assertThat(page.analysis().result()
                .statements().getFirst().review()).isEqualTo(ParsedStatements.Review.CONFIRMED));
        assertThat(graph.page(CALLER, snapshot.key()).orElseThrow().analysis().result().statements().getFirst()
                .review()).isEqualTo(ParsedStatements.Review.CONFIRMED);
        assertThat(statements.read(CALLER, snapshot).analysis().result().statements().getFirst().review())
                .isEqualTo(ParsedStatements.Review.CONFIRMED);
        assertThat(stored(snapshot.key()).review()).as("nie in die Analyse geschrieben")
                .isEqualTo(ParsedStatements.Review.UNREVIEWED);
    }

    @Test
    void aReviewForTheOtherAnalyzerDoesNotTouchAStatementWithTheSameId() {
        PageSnapshot snapshot = analyse("1", "Die Buchhaltung prüft den Beleg.");
        String id = stored(snapshot.key()).id();

        review(snapshot.key(), id, AnalyzerChoice.MODEL, ParsedStatements.Review.REJECTED);

        assertThat(graph.analysed(CALLER).getFirst().analysis().result().statements().getFirst().review())
                .isEqualTo(ParsedStatements.Review.UNREVIEWED);
        assertThat(statements.read(CALLER, snapshot).analysis().result().statements().getFirst().review())
                .isEqualTo(ParsedStatements.Review.UNREVIEWED);
    }

    @Test
    void theDecisionWinsOverAStoredReviewAndWithoutItTheStoredOneStays() {
        PageSnapshot snapshot = analyse("1", "Die Buchhaltung prüft den Beleg.");
        PageStatementAnalysis analysis = store.find(snapshot.key()).orElseThrow();
        ParsedStatements.Statement statement = analysis.result().statements().getFirst();
        store.replace(analysis.withResult(withStatements(analysis.result(),
                List.of(statement.withReview(ParsedStatements.Review.REJECTED)))));

        assertThat(graph.analysed(CALLER).getFirst().analysis().result().statements().getFirst().review())
                .isEqualTo(ParsedStatements.Review.REJECTED);

        review(snapshot.key(), statement.id(), AnalyzerChoice.PARSER, ParsedStatements.Review.CONFIRMED);

        assertThat(graph.analysed(CALLER).getFirst().analysis().result().statements().getFirst().review())
                .isEqualTo(ParsedStatements.Review.CONFIRMED);
    }

    @Test
    void aReadModifyWritePathKeepsReadingTheStoredAnalysis() {
        PageSnapshot snapshot = analyse("1", "Die Buchhaltung prüft den Beleg.");
        PageStatementAnalysis analysis = store.find(snapshot.key()).orElseThrow();
        ParsedStatements.Statement statement = analysis.result().statements().getFirst();
        store.replace(analysis.withResult(withStatements(analysis.result(), List.of(statement.withCheck(
                new ParsedStatements.Check(ParsedStatements.Verdict.STIMMT, "pruef-1", false))))));
        review(snapshot.key(), statement.id(), AnalyzerChoice.PARSER, ParsedStatements.Review.CONFIRMED);

        assertThat(statements.withdrawChecks(List.of(snapshot.key()), page -> false)).isEqualTo(1);

        assertThat(stored(snapshot.key())).satisfies(raw -> {
            assertThat(raw.check()).isNull();
            assertThat(raw.review()).as("die Prüfung von Hand landet nie in der Analyse")
                    .isEqualTo(ParsedStatements.Review.UNREVIEWED);
        });
        decisions.write(List.of(), List.of(new StandingDecision.Target(snapshot.key(), statement.id(),
                AnalyzerChoice.PARSER)), List.of());
        assertThat(graph.analysed(CALLER).getFirst().analysis().result().statements().getFirst().review())
                .as("zurückgenommen heißt wieder gespeichert").isEqualTo(ParsedStatements.Review.UNREVIEWED);
    }

    private void review(PageKey page, String statementId, AnalyzerChoice analyzer, ParsedStatements.Review review) {
        decisions.write(List.of(new StandingDecision(page, statementId, analyzer, null, review, 1, "anna", AT, "anna",
                AT)), List.of(), List.of());
    }

    private ParsedStatements.Statement stored(PageKey key) {
        return store.find(key).orElseThrow().result().statements().getFirst();
    }

    private static ParsedStatements withStatements(ParsedStatements result,
                                                   List<ParsedStatements.Statement> statements) {
        return new ParsedStatements(result.sentences(), result.mentions(), statements, result.attachments(),
                result.components(), result.conllu());
    }

    private PageSnapshot analyse(String pageId, String text) {
        PageSnapshot snapshot = EnrichmentTestPages.text("wiki", pageId, "1", text);
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
        return snapshot;
    }
}
