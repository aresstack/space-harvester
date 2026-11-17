package com.aresstack.spaceharvester.application.standing;

import static com.aresstack.spaceharvester.application.standing.StandingFixtures.features;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.header;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Features;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Modality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Negation;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Status-quo-Sicht und Entscheidungen des Menschen (ADR 0032, FL6), mit erfundenen Seiten. */
class StatusQuoTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("desktop");
    private static final AccessContext READER = AccessContext.technicalClient("leser");

    private final List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
    private final Map<PageKey, PageHeader> headers = new HashMap<>();
    private final StandingDecisionStore store = StandingDecisionStore.inMemory();
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            kind == AccessKind.SYNC && "leser".equals(context.principal()) ? AccessDecision.DENY
                    : AccessDecision.ALLOW);
    private final StatusQuo statusQuo = new StatusQuo(caller -> List.copyOf(pages),
            key -> Optional.ofNullable(headers.get(key)), store, access, () -> StandingWords.DEFAULT,
            Clock.fixed(StandingFixtures.AT, ZoneOffset.UTC));

    @Test
    void currentComesFirstAndOutdatedLastButNothingIsHidden() {
        pages.add(page("1", "Förderung", 'a')
                .action("Amt", "prüfte", "Antrag", features(Temporality.FRUEHER))
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .action("Kasse", "wird prüfen", "Nachweis", features(Temporality.GEPLANT))
                .action("Kasse", "zahlt", "Rest", Features.UNKNOWN)
                .build());

        StatusQuoView view = statusQuo.view(EDITOR, key -> true);

        assertThat(view.entries()).extracting(entry -> entry.standing().standing())
                .containsExactly(Standing.GILT, Standing.GEPLANT, Standing.UNKLAR, Standing.UEBERHOLT);
        assertThat(view.entries().getFirst().subject()).isEqualTo("der Kasse");
        assertThat(view.counts()).containsEntry(Standing.GILT, 1).containsEntry(Standing.UEBERHOLT, 1)
                .containsEntry(Standing.UNKLAR, 1).containsEntry(Standing.GEPLANT, 1);
        assertThat(view.pages()).singleElement().satisfies(standing -> {
            assertThat(standing.standing()).as("Gleichstand 1:1:1:1").isEqualTo(Standing.UNKLAR);
            assertThat(standing.reason()).isEqualTo(StandingReason.NO_MAJORITY);
            assertThat(standing.basis()).isEqualTo(StandingBasis.STATEMENTS);
            assertThat(standing.counts()).containsEntry(Standing.GILT, 1);
        });
    }

    @Test
    void staleAnalysesAreShownButNeverCountedAsCurrent() {
        pages.add(page("1", "Aktuell", 'a')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT)).build());
        StandingFixtures.PageBuilder old = page("2", "Alt", 'b')
                .action("Kasse", "zahlt", "Zuschuss", features(Negation.NEGATED, Modality.NONE, Temporality.GILT))
                .state("Antrag", "vollständig", Features.UNKNOWN);
        old.state = PageStatements.State.STALE;
        pages.add(old.build());

        StatusQuoView view = statusQuo.view(EDITOR, key -> true);

        assertThat(view.entries()).hasSize(3);
        assertThat(view.staleEntries()).isEqualTo(2);
        assertThat(view.counts()).containsEntry(Standing.GILT, 1).containsEntry(Standing.UNKLAR, 0);
        assertThat(view.certainCounts()).containsEntry(Standing.GILT, 1);
        assertThat(view.states()).as("Zustände nur aus aktuellen Analysen").isEmpty();
        assertThat(view.pages()).filteredOn(standing -> standing.page().key().pageId().equals("2")).singleElement()
                .satisfies(standing -> assertThat(standing.counts().values()).allMatch(count -> count == 0));
        assertThat(StatementConflicts.of(view).contradictions()).as("veraltete Einträge nie verglichen").isEmpty();
    }

    @Test
    void negationAndModalityTravelWithTheEntry() {
        pages.add(page("1", "Förderung", 'a')
                .action("Kasse", "prüft", "Beleg", features(Negation.NEGATED, Modality.MUSS, Temporality.GILT))
                .build());

        StatusQuoEntry entry = statusQuo.view(EDITOR, key -> true).entries().getFirst();

        assertThat(entry.standing().standing()).isEqualTo(Standing.GILT);
        assertThat(entry.negation()).isEqualTo(Negation.NEGATED);
        assertThat(entry.modality()).isEqualTo(Modality.MUSS);
    }

    @Test
    void labelsAndTheTitlesOfAncestorsMarkPages() {
        headers.put(new PageKey("wiki", "0"), header("0", "Archiv 2019", null, Map.of()));
        headers.put(new PageKey("wiki", "1"), header("1", "Alte Förderung", "0", Map.of()));
        headers.put(new PageKey("wiki", "2"), header("2", "Förderung neu", null,
                Map.of("confluence.labels", "foerderung,entwurf")));
        headers.put(new PageKey("wiki", "3"), header("3", "Archivierung der Belege", null, Map.of()));
        pages.add(page("1", "Alte Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT)).build());
        pages.add(page("2", "Förderung neu", 'b').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT)).build());
        pages.add(page("3", "Archivierung der Belege", 'c').action("Kasse", "archiviert", "Beleg",
                features(Temporality.GILT)).build());

        StatusQuoView view = statusQuo.view(EDITOR, key -> true);

        assertThat(view.pages()).extracting(standing -> standing.page().key().pageId(), PageStanding::standing,
                PageStanding::reason).containsExactly(
                tuple("1", Standing.UEBERHOLT, StandingReason.PAGE_OUTDATED),
                tuple("2", Standing.UNKLAR, StandingReason.PAGE_DRAFT),
                tuple("3", Standing.GILT, StandingReason.MAJORITY));
        assertThat(view.pages().getFirst().marks()).containsExactly(
                new PageMark(PageMark.Kind.OUTDATED, PageMark.Where.ANCESTOR_TITLE, "archiv", "0"));
        assertThat(view.pages().get(1).marks()).containsExactly(
                new PageMark(PageMark.Kind.DRAFT, PageMark.Where.LABEL, "entwurf", "2"));
        assertThat(view.entries()).extracting(entry -> entry.page().key().pageId(),
                entry -> entry.standing().standing()).containsExactly(
                tuple("3", Standing.GILT),
                tuple("2", Standing.UNKLAR),
                tuple("1", Standing.UEBERHOLT));
    }

    @Test
    void ancestorCyclesEnd() {
        headers.put(new PageKey("wiki", "1"), header("1", "Förderung", "2", Map.of()));
        headers.put(new PageKey("wiki", "2"), header("2", "Übersicht", "1", Map.of()));
        pages.add(page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT)).build());

        assertThat(statusQuo.view(EDITOR, key -> true).pages().getFirst().standing()).isEqualTo(Standing.GILT);
    }

    @Test
    void statesAreGroupedByBearerWithTheMostCurrentFirst() {
        pages.add(page("1", "Förderung", 'a')
                .state("Antrag", "unvollständig", features(Temporality.GILT))
                .state("Antrag", "genehmigt", features(Temporality.FRUEHER))
                .state("Beleg", "gültig", features(Temporality.FRUEHER))
                .action("Kasse", "prüft", "Antrag", features(Temporality.GILT))
                .build());
        pages.add(page("2", "Zuschuss", 'b').state("antrag", "vollständig", features(Temporality.GEPLANT)).build());

        StatusQuoView view = statusQuo.view(EDITOR, key -> true);

        assertThat(view.states()).extracting(BearerStates::bearer).containsExactly("Antrag", "Beleg");
        assertThat(view.states().getFirst().states()).extracting(entry -> entry.statement().predicateText())
                .containsExactly("unvollständig", "vollständig", "genehmigt");
        assertThat(view.entries()).filteredOn(entry -> entry.kind() == StatementKind.ACTION).hasSize(1);
    }

    @Test
    void theSelectionLimitsPagesAndStalenessIsShown() {
        StandingFixtures.PageBuilder stale = page("2", "Zuschuss", 'b').action("Kasse", "zahlt", "Zuschuss",
                features(Temporality.GILT));
        stale.state = PageStatements.State.STALE;
        pages.add(page("1", "Förderung", 'a').action("Amt", "prüft", "Antrag", features(Temporality.GILT)).build());
        pages.add(stale.build());

        StatusQuoView view = statusQuo.view(EDITOR, key -> key.pageId().equals("2"));

        assertThat(view.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.page().key().pageId()).isEqualTo("2");
            assertThat(entry.analysisStale()).isTrue();
        });
    }

    @Test
    void decisionsWinNotifyAndGoStaleWhenTheTextChanges() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Amt", "prüfte", "Antrag",
                features(Temporality.FRUEHER));
        pages.add(first.build());
        AtomicInteger notified = new AtomicInteger();
        statusQuo.subscribe(notified::incrementAndGet);
        String id = first.statements.getFirst().id();

        statusQuo.decide(EDITOR, first.key, id, Standing.GILT, 0L);
        statusQuo.decide(EDITOR, first.key, null, Standing.UEBERHOLT, null);
        StatusQuoView decided = statusQuo.view(EDITOR, key -> true);

        assertThat(notified).hasValue(2);
        assertThat(decided.entries().getFirst().standing()).isEqualTo(new StatementStanding(Standing.GILT,
                StandingBasis.DECISION, StandingReason.DECISION));
        assertThat(decided.entries().getFirst().decision().revision()).isEqualTo(1);
        assertThat(decided.pages().getFirst()).satisfies(standing -> {
            assertThat(standing.standing()).isEqualTo(Standing.UEBERHOLT);
            assertThat(standing.basis()).isEqualTo(StandingBasis.DECISION);
            assertThat(standing.decision().decidedBy()).isEqualTo(EDITOR.jobOwner());
        });
        assertThat(decided.staleDecisions()).isEmpty();

        // Neue Fassung des Texts: neue Aussage-IDs, die Entscheidung zur Aussage greift nicht mehr.
        pages.clear();
        pages.add(page("1", "Förderung", 'b').action("Amt", "prüfte", "Antrag", features(Temporality.FRUEHER))
                .build());
        StatusQuoView changed = statusQuo.view(EDITOR, key -> true);

        assertThat(changed.entries().getFirst().standing().basis()).isEqualTo(StandingBasis.FEATURES);
        assertThat(changed.staleDecisions()).extracting(StandingDecision::statementId).containsExactly(id);
        assertThat(statusQuo.clear(EDITOR, first.key, id, 1L)).isTrue();
        assertThat(statusQuo.view(EDITOR, key -> true).staleDecisions()).isEmpty();
        assertThat(notified).hasValue(3);
        assertThat(statusQuo.history(EDITOR, first.key, id)).extracting(StandingDecisionStore.AuditEntry::action)
                .containsExactly("set", "remove");
    }

    @Test
    void aDecisionOnAStageStatementGoesStaleWhenTheStageIsWithdrawn() {
        StandingFixtures.PageBuilder staged = page("1", "Förderung", 'a')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .add("Kasse", "prüft", "Nachweis", features(Temporality.GILT), false,
                        ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Review.UNREVIEWED,
                        ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.PLANNING,
                                "Die Kasse prüft den Nachweis."));
        pages.add(staged.build());
        String stageId = staged.statements.get(1).id();
        assertThat(statusQuo.view(EDITOR, key -> true).entries()).filteredOn(entry -> entry.statement().id()
                .equals(stageId)).singleElement().satisfies(entry -> {
                    assertThat(entry.origin()).isEqualTo(ParsedStatements.Origin.VORSTUFE);
                    assertThat(entry.stageFlag()).isEqualTo(ParsedStatements.StageFlag.PLANNING);
                    assertThat(entry.standing().basis()).isEqualTo(StandingBasis.STAGE_FLAG);
                });
        statusQuo.decide(EDITOR, staged.key, stageId, Standing.GILT, null);

        // Gleicher Text, aber ohne die Aussage der Vorstufe (Freigabe entzogen).
        staged.statements.remove(1);
        pages.clear();
        pages.add(staged.build());

        assertThat(statusQuo.view(EDITOR, key -> true).staleDecisions()).extracting(StandingDecision::statementId)
                .containsExactly(stageId);
    }

    @Test
    void aStaleRevisionIsAConflict() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Amt", "prüft", "Antrag",
                features(Temporality.GILT));
        pages.add(first.build());
        statusQuo.decide(EDITOR, first.key, null, Standing.GEPLANT, 0L);

        assertThatThrownBy(() -> statusQuo.decide(EDITOR, first.key, null, Standing.GILT, 0L))
                .isInstanceOfSatisfying(StandingConflictException.class,
                        conflict -> assertThat(conflict.revision()).isEqualTo(1));
        assertThat(statusQuo.decide(EDITOR, first.key, null, Standing.GILT, 1L).revision()).isEqualTo(2);
    }

    @Test
    void uncertainStatementsAreShownButDoNotCountForThePage() {
        pages.add(page("1", "Förderung", 'a')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .add("Amt", "prüfte", "Antrag", features(Temporality.FRUEHER), false,
                        ParsedStatements.Quality.UNCERTAIN, ParsedStatements.Review.UNREVIEWED, null)
                .add("Amt", "prüfte", "Beleg", features(Temporality.FRUEHER), false,
                        ParsedStatements.Quality.UNCERTAIN, ParsedStatements.Review.UNREVIEWED, null)
                .build());

        StatusQuoView view = statusQuo.view(EDITOR, key -> true);

        assertThat(view.entries()).extracting(StatusQuoEntry::uncertain).containsExactly(false, true, true);
        assertThat(view.pages().getFirst()).satisfies(standing -> {
            assertThat(standing.standing()).isEqualTo(Standing.GILT);
            assertThat(standing.counts()).containsEntry(Standing.UEBERHOLT, 2);
            assertThat(standing.certainCounts()).containsEntry(Standing.UEBERHOLT, 0).containsEntry(Standing.GILT, 1);
        });
        assertThat(view.certainCounts()).containsEntry(Standing.GILT, 1).containsEntry(Standing.UEBERHOLT, 0);
    }

    @Test
    void theViewCanBeCancelled() {
        pages.add(page("1", "Förderung", 'a').action("Amt", "prüft", "Antrag", features(Temporality.GILT)).build());

        assertThatThrownBy(() -> statusQuo.view(EDITOR, key -> true, () -> true))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void decidingNeedsAVisibleTargetAndSync() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Amt", "prüft", "Antrag",
                features(Temporality.GILT));
        pages.add(first.build());

        assertThatThrownBy(() -> statusQuo.decide(EDITOR, new PageKey("wiki", "9"), null, Standing.GILT, null))
                .isInstanceOf(UnknownStandingTargetException.class);
        assertThatThrownBy(() -> statusQuo.decide(EDITOR, first.key, "wiki/1@ffffffffffff#s0.v2", Standing.GILT,
                null)).isInstanceOf(UnknownStandingTargetException.class);
        assertThatThrownBy(() -> statusQuo.decide(READER, first.key, null, Standing.GILT, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(store.all()).isEmpty();
    }

    @Test
    void removingASourceDropsItsDecisions() {
        StandingFixtures.PageBuilder first = page("1", "Förderung", 'a').action("Amt", "prüft", "Antrag",
                features(Temporality.GILT));
        pages.add(first.build());
        statusQuo.decide(EDITOR, first.key, null, Standing.UEBERHOLT, null);

        assertThat(statusQuo.removeSource("andere", "purge")).isZero();
        assertThat(statusQuo.removeSource("wiki", "purge")).isEqualTo(1);
        assertThat(store.all()).isEmpty();
    }
}
