package com.aresstack.spaceharvester.application.standing;

import static com.aresstack.spaceharvester.application.standing.StandingFixtures.features;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Prüfung einer Aussage durch Menschen im Eintrag des Stands (ADR 0032, Umsetzung W3), mit erfundenen Seiten. Die
 * Sicht liest wie in der Anwendung über die eine Stelle, an der die Prüfung wirkt ({@link DecisionReviews}).
 */
class StatusQuoReviewTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("desktop");
    private static final AccessContext READER = AccessContext.technicalClient("leser");

    private final List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
    private final StandingDecisionStore store = StandingDecisionStore.inMemory();
    private final DecisionReviews reviews = new DecisionReviews(store);
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            kind == AccessKind.SYNC && "leser".equals(context.principal()) ? AccessDecision.DENY
                    : AccessDecision.ALLOW);
    private final StatusQuo statusQuo = new StatusQuo(caller -> {
        java.util.function.UnaryOperator<PageStatementAnalysis> reviewed = reviews.snapshot();
        return pages.stream().map(page -> new KnowledgeGraph.AnalysedPage(page.page(),
                reviewed.apply(page.analysis()))).toList();
    }, key -> Optional.empty(), store, access, () -> StandingWords.DEFAULT,
            Clock.fixed(StandingFixtures.AT, ZoneOffset.UTC));

    @Test
    void standingAndReviewAreSetAndWithdrawnIndependentlyInOneEntry() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        pages.add(first.build());
        String id = first.statements.getFirst().id();
        AtomicInteger notified = new AtomicInteger();
        statusQuo.subscribe(notified::incrementAndGet);
        AtomicInteger reviews = new AtomicInteger();
        statusQuo.subscribeReviews(reviews::incrementAndGet);

        statusQuo.decide(EDITOR, first.key, id, Standing.UEBERHOLT, 0L);
        assertThat(reviews).as("nur der Stand: keine Prüfungsmeldung").hasValue(0);
        StandingDecision reviewed = statusQuo.review(EDITOR, first.key, id, Review.CONFIRMED, 1L);

        assertThat(reviewed.standing()).as("Stand bleibt beim Prüfen").isEqualTo(Standing.UEBERHOLT);
        assertThat(reviewed.review()).isEqualTo(Review.CONFIRMED);
        assertThat(reviewed.revision()).as("eine Revision für beides").isEqualTo(2);
        assertThat(reviewed.analyzer()).isEqualTo(AnalyzerChoice.PARSER);

        assertThat(statusQuo.clear(EDITOR, first.key, id, 2L)).isTrue();
        StatusQuoEntry entry = statusQuo.view(EDITOR, key -> true).entries().getFirst();
        assertThat(entry.decision()).as("Eintrag mit Prüfung bleibt").satisfies(decision -> {
            assertThat(decision.standing()).isNull();
            assertThat(decision.review()).isEqualTo(Review.CONFIRMED);
            assertThat(decision.revision()).isEqualTo(3);
        });
        assertThat(entry.standing().basis()).as("ohne Stand von Hand gelten die Regeln")
                .isEqualTo(StandingBasis.FEATURES);
        assertThat(entry.statement().review()).as("wirksame Prüfung").isEqualTo(Review.CONFIRMED);
        assertThat(statusQuo.clear(EDITOR, first.key, id, 3L)).as("kein Stand mehr").isFalse();

        assertThat(statusQuo.withdrawReview(EDITOR, first.key, id, 3L)).isTrue();

        assertThat(store.all()).as("ohne Stand und Prüfung gelöscht").isEmpty();
        assertThat(notified).hasValue(4);
        assertThat(reviews).as("Prüfen und Zurücknehmen melden sich für den Graphen").hasValue(2);
        assertThat(statusQuo.history(EDITOR, first.key, id))
                .extracting(StandingDecisionStore.AuditEntry::action, StandingDecisionStore.AuditEntry::standing,
                        StandingDecisionStore.AuditEntry::review, StandingDecisionStore.AuditEntry::revision)
                .containsExactly(tuple("set", Standing.UEBERHOLT, null, 1L),
                        tuple("review", Standing.UEBERHOLT, Review.CONFIRMED, 2L),
                        tuple("remove", Standing.UEBERHOLT, Review.CONFIRMED, 3L),
                        tuple("unreview", null, Review.CONFIRMED, 3L));
    }

    @Test
    void withdrawingTheReviewKeepsTheStandingAndSettingTheStandingKeepsTheReview() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        pages.add(first.build());
        String id = first.statements.getFirst().id();

        statusQuo.review(EDITOR, first.key, id, Review.CONFIRMED, 0L);
        assertThat(statusQuo.decide(EDITOR, first.key, id, Standing.GEPLANT, 1L).review())
                .isEqualTo(Review.CONFIRMED);
        assertThat(statusQuo.withdrawReview(EDITOR, first.key, id, 2L)).isTrue();

        assertThat(store.all()).singleElement().satisfies(decision -> {
            assertThat(decision.standing()).isEqualTo(Standing.GEPLANT);
            assertThat(decision.review()).isNull();
            assertThat(decision.revision()).isEqualTo(3);
        });
        assertThat(statusQuo.withdrawReview(EDITOR, first.key, id, 3L)).as("keine Prüfung mehr").isFalse();
        assertThat(statusQuo.clear(EDITOR, first.key, id, 3L)).isTrue();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void aStatementRejectedByAHumanStopsCountingButStaysWithdrawable() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .action("Amt", "prüft", "Antrag", features(Temporality.GILT));
        pages.add(first.build());
        String id = first.statements.get(1).id();

        statusQuo.review(EDITOR, first.key, id, Review.REJECTED, 0L);
        StatusQuoView view = statusQuo.view(EDITOR, key -> true);

        assertThat(view.entries()).extracting(entry -> entry.statement().id()).doesNotContain(id);
        assertThat(view.counts()).containsEntry(Standing.GILT, 1);
        assertThat(view.rejectedDecisions()).extracting(StandingDecision::statementId).containsExactly(id);
        assertThat(view.staleDecisions()).as("die Aussage gibt es noch: nicht veraltet").isEmpty();

        long revision = view.rejectedDecisions().getFirst().revision();
        assertThat(statusQuo.withdrawReview(EDITOR, first.key, id, revision)).isTrue();

        StatusQuoView again = statusQuo.view(EDITOR, key -> true);
        assertThat(again.entries()).extracting(entry -> entry.statement().id()).contains(id);
        assertThat(again.rejectedDecisions()).isEmpty();
        assertThat(again.counts()).containsEntry(Standing.GILT, 2);
    }

    @Test
    void aReviewOnlyEntryOfAVanishedStatementGoesStaleAndCanBeWithdrawn() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        pages.add(first.build());
        String id = first.statements.getFirst().id();
        statusQuo.review(EDITOR, first.key, id, Review.CONFIRMED, 0L);

        pages.clear();
        pages.add(page("1", "Förderung", 'b').action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .build());
        StatusQuoView view = statusQuo.view(EDITOR, key -> true);

        assertThat(view.staleDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision.statementId()).isEqualTo(id);
            assertThat(decision.standing()).isNull();
        });
        assertThat(view.entries().getFirst().decision()).isNull();
        assertThat(statusQuo.withdrawReview(EDITOR, first.key, id, 1L)).isTrue();
        assertThat(statusQuo.view(EDITOR, key -> true).staleDecisions()).isEmpty();
    }

    @Test
    void aReviewBelongsToTheStoreOfTheAnalyzerWhereTheStatementWasFound() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        KnowledgeGraph.AnalysedPage parsed = first.build();
        KnowledgeGraph.AnalysedPage modelled = model(parsed);
        String id = first.statements.getFirst().id();
        pages.add(parsed);
        statusQuo.review(EDITOR, first.key, id, Review.REJECTED, 0L);

        // Dieselbe ID aus dem KI-Analysator: die Prüfung des MaltParsers greift dort nicht und ist nicht veraltet.
        pages.clear();
        pages.add(modelled);
        StatusQuoView model = statusQuo.view(EDITOR, key -> true);

        assertThat(model.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.statement().id()).isEqualTo(id);
            assertThat(entry.statement().review()).isEqualTo(Review.UNREVIEWED);
            assertThat(entry.decision()).isNull();
        });
        assertThat(model.staleDecisions()).isEmpty();
        assertThat(model.rejectedDecisions()).isEmpty();

        StandingDecision confirmed = statusQuo.review(EDITOR, first.key, id, Review.CONFIRMED, 0L);
        assertThat(confirmed.analyzer()).isEqualTo(AnalyzerChoice.MODEL);
        assertThat(store.all()).extracting(StandingDecision::analyzer, StandingDecision::review)
                .containsExactlyInAnyOrder(tuple(AnalyzerChoice.PARSER, Review.REJECTED),
                        tuple(AnalyzerChoice.MODEL, Review.CONFIRMED));

        // Zurück zum MaltParser: dort gilt weiter seine eigene Prüfung.
        pages.clear();
        pages.add(parsed);
        StatusQuoView parser = statusQuo.view(EDITOR, key -> true);
        assertThat(parser.entries()).isEmpty();
        assertThat(parser.rejectedDecisions()).singleElement()
                .satisfies(decision -> assertThat(decision.analyzer()).isEqualTo(AnalyzerChoice.PARSER));
        assertThat(parser.staleDecisions()).isEmpty();
    }

    @Test
    void reviewingNeedsAStatementAVisibleTargetAndSync() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        pages.add(first.build());
        String id = first.statements.getFirst().id();

        assertThatThrownBy(() -> statusQuo.review(EDITOR, first.key, null, Review.CONFIRMED, null))
                .as("nie zur Seite").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statusQuo.withdrawReview(EDITOR, first.key, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statusQuo.review(EDITOR, first.key, id, Review.UNREVIEWED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statusQuo.review(EDITOR, new PageKey("wiki", "9"), id, Review.CONFIRMED, null))
                .isInstanceOf(UnknownStandingTargetException.class);
        assertThatThrownBy(() -> statusQuo.review(EDITOR, first.key, "wiki/1@ffffffffffff#s0.v2", Review.CONFIRMED,
                null)).isInstanceOf(UnknownStandingTargetException.class);
        assertThatThrownBy(() -> statusQuo.review(READER, first.key, id, Review.CONFIRMED, null))
                .as("gesperrte Seite").isInstanceOf(AccessDeniedException.class);
        assertThat(store.all()).isEmpty();

        statusQuo.review(EDITOR, first.key, id, Review.CONFIRMED, null);
        assertThatThrownBy(() -> statusQuo.withdrawReview(READER, first.key, id, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(store.all()).singleElement().extracting(StandingDecision::review).isEqualTo(Review.CONFIRMED);
    }

    @Test
    void standingAndReviewShareTheRevisionOfTheEntry() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        pages.add(first.build());
        String id = first.statements.getFirst().id();
        statusQuo.decide(EDITOR, first.key, id, Standing.GILT, 0L);

        assertThatThrownBy(() -> statusQuo.review(EDITOR, first.key, id, Review.CONFIRMED, 0L))
                .isInstanceOfSatisfying(StandingConflictException.class,
                        conflict -> assertThat(conflict.revision()).isEqualTo(1));
        statusQuo.review(EDITOR, first.key, id, Review.CONFIRMED, 1L);
        assertThatThrownBy(() -> statusQuo.decide(EDITOR, first.key, id, Standing.GEPLANT, 1L))
                .as("der Stand mit alter Revision nach einer Prüfung").isInstanceOf(StandingConflictException.class);
        assertThatThrownBy(() -> statusQuo.withdrawReview(EDITOR, first.key, id, 1L))
                .isInstanceOf(StandingConflictException.class);
    }

    @Test
    void removingASourceRecordsTheReviewInTheProtocol() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        pages.add(first.build());
        String id = first.statements.getFirst().id();
        statusQuo.review(EDITOR, first.key, id, Review.REJECTED, null);

        assertThat(statusQuo.removeSource("wiki", "purge")).isEqualTo(1);

        assertThat(store.all()).isEmpty();
        assertThat(store.history(new StandingDecision.Target(first.key, id, AnalyzerChoice.PARSER))).last()
                .satisfies(entry -> {
                    assertThat(entry.action()).isEqualTo("remove");
                    assertThat(entry.review()).isEqualTo(Review.REJECTED);
                });
    }

    @Test
    void reviewsNameTheRightAndTheStoredRevisionPerPageForTheEvidenceOverlay() {
        StandingFixtures.PageBuilder plan = page("1", "Plan", 'a')
                .action("Nachtlauf", "startet", "Sicherung", features(Temporality.GILT))
                .action("Team", "prüft", "Ziel", features(Temporality.GILT));
        StandingFixtures.PageBuilder ziel = page("2", "Ziel", 'b').action("Start", "folgt", "Plan",
                features(Temporality.GILT));
        pages.add(plan.build());
        pages.add(ziel.build());
        String confirmed = plan.statements.getFirst().id();
        String open = plan.statements.get(1).id();
        String other = ziel.statements.getFirst().id();
        statusQuo.review(EDITOR, plan.key, confirmed, Review.CONFIRMED, null);
        statusQuo.decide(EDITOR, plan.key, confirmed, Standing.GILT, 1L);
        statusQuo.review(EDITOR, ziel.key, other, Review.REJECTED, null);
        PageKey unknown = new PageKey("wiki", "9");

        Map<PageKey, StatusQuo.Reviews> seen = statusQuo.reviews(EDITOR, Map.of(plan.key, List.of(confirmed, open),
                ziel.key, List.of(other), unknown, List.of("wiki/9@x#s0.v1")));

        assertThat(seen.get(plan.key).writable()).isTrue();
        assertThat(seen.get(plan.key).decisions()).as("ohne Entscheidung fehlt die Aussage").containsOnlyKeys(confirmed);
        assertThat(seen.get(plan.key).decisions().get(confirmed)).satisfies(decision -> {
            assertThat(decision.review()).isEqualTo(Review.CONFIRMED);
            assertThat(decision.revision()).as("die Revision, gegen die das Zurücknehmen prüft").isEqualTo(2);
        });
        assertThat(seen.get(ziel.key).decisions().get(other).review()).isEqualTo(Review.REJECTED);
        assertThat(seen.get(unknown)).as("nicht sichtbar analysiert").isEqualTo(StatusQuo.Reviews.NONE);
        assertThat(statusQuo.withdrawReview(EDITOR, plan.key, confirmed,
                seen.get(plan.key).decisions().get(confirmed).revision())).isTrue();

        Map<PageKey, StatusQuo.Reviews> reader = statusQuo.reviews(READER, Map.of(ziel.key, List.of(other)));
        assertThat(reader.get(ziel.key).writable()).as("ohne sync bzw. gesperrt: nicht prüfbar").isFalse();
        assertThat(reader.get(ziel.key).decisions()).as("lesen wie die Sicht").containsOnlyKeys(other);
        assertThat(statusQuo.reviews(EDITOR, Map.of())).isEmpty();
    }

    /** Dieselbe Analyse, als hätte der KI-Analysator sie gerechnet (gleiche IDs, andere Ablage). */
    private static KnowledgeGraph.AnalysedPage model(KnowledgeGraph.AnalysedPage page) {
        PageStatementAnalysis analysis = page.analysis();
        ParsedStatements result = analysis.result();
        return new KnowledgeGraph.AnalysedPage(page.page(), new PageStatementAnalysis(analysis.key(),
                analysis.spaceKey(), analysis.title(), analysis.revision(), analysis.inputSha256(),
                ModelStatementAnalyzer.ID, "modell-1", analysis.analyzedAt(), result));
    }
}
