package com.aresstack.spaceharvester.application.standing;

import static com.aresstack.spaceharvester.application.standing.StandingFixtures.copy;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.features;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Features;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Modality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Negation;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Role;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Mögliche Widersprüche und Ablösungen (ADR 0032, FL6), mit erfundenen Seiten und Schein-Analyse. */
class StatementConflictsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final Features JA = features(Negation.AFFIRMED, Modality.NONE, Temporality.GILT);
    private static final Features NEIN = features(Negation.NEGATED, Modality.NONE, Temporality.GILT);

    private final List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
    private final StatusQuo statusQuo = new StatusQuo(caller -> List.copyOf(pages), key -> Optional.empty(),
            StandingDecisionStore.inMemory(), new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
            () -> StandingWords.DEFAULT, Clock.fixed(StandingFixtures.AT, ZoneOffset.UTC));

    private StatementConflicts conflicts() {
        return StatementConflicts.of(statusQuo.view(CALLER, key -> true));
    }

    @Test
    void currentAgainstCurrentWithOtherNegationIsAPossibleContradictionAcrossPages() {
        pages.add(page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss", JA).build());
        pages.add(page("2", "Ausnahmen", 'b').action("Kasse", "zahlt", "Zuschuss", NEIN)
                .action("Kasse", "zahlt", "Zuschuss", NEIN).build());
        pages.add(page("3", "Ausnahmen", 'c').action("Kasse", "zahlt", "Zuschuss", NEIN).build());

        StatementConflicts conflicts = conflicts();

        assertThat(conflicts.contradictions()).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.situation().predicate()).isEqualTo("zahlt");
            assertThat(contradiction.situation().others()).extracting(Situation.Participant::role)
                    .containsExactly(Role.AFFECTED_OBJECT);
            assertThat(contradiction.modality()).isEqualTo(Modality.NONE);
            assertThat(contradiction.affirmed()).extracting(entry -> entry.page().key().pageId())
                    .containsExactly("1");
            assertThat(contradiction.negated()).extracting(entry -> entry.page().key().pageId())
                    .containsExactly("2", "2", "3");
            assertThat(contradiction.pages()).hasSize(3);
        });
        assertThat(conflicts.replacements()).isEmpty();
    }

    @Test
    void onlyTheSameDeterminedModalityIsCompared() {
        pages.add(page("1", "Pflichten", 'a')
                .action("Kasse", "prüft", "Beleg", features(Negation.AFFIRMED, Modality.MUSS, Temporality.GILT))
                .action("Kasse", "prüft", "Beleg", NEIN)
                .action("Amt", "bucht", "Reise", features(Negation.AFFIRMED, Modality.UNKNOWN, Temporality.GILT))
                .action("Amt", "bucht", "Reise", features(Negation.NEGATED, Modality.UNKNOWN, Temporality.GILT))
                .action("Kasse", "prüft", "Beleg", features(Negation.NEGATED, Modality.MUSS, Temporality.GILT))
                .build());

        assertThat(conflicts().contradictions()).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.modality()).isEqualTo(Modality.MUSS);
            assertThat(contradiction.affirmed()).hasSize(1);
            assertThat(contradiction.negated()).hasSize(1);
        });
    }

    @Test
    void otherParticipantsMustMatchOrBeMissingOnBothSides() {
        pages.add(page("1", "Zuständigkeit", 'a')
                .state("Referat", "zuständig", JA).with(Role.PREPOSITIONAL_OBJECT, "für", "Vergabe")
                .state("Referat", "zuständig", NEIN).with(Role.PREPOSITIONAL_OBJECT, "für", "Inventur")
                .state("Referat", "zuständig", NEIN).build());

        assertThat(conflicts().contradictions()).as("Gegenbeispiel: anderer Bezug, ein Bezug fehlt").isEmpty();

        pages.add(page("2", "Zuständigkeit neu", 'b')
                .state("Referat", "zuständig", NEIN).with(Role.PREPOSITIONAL_OBJECT, "für", "Vergabe").build());
        assertThat(conflicts().contradictions()).singleElement().satisfies(contradiction ->
                assertThat(contradiction.situation().others()).extracting(Situation.Participant::marker)
                        .containsExactly("für"));
    }

    @Test
    void neverAPairFromTheSameSentenceOfTheSamePage() {
        pages.add(page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss", JA)
                .sameSentence("Kasse", "zahlt", "Zuschuss", NEIN,
                        ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.FACT,
                                "Die Kasse zahlt den Zuschuss nicht.")).build());

        assertThat(conflicts().contradictions()).as("Gegenbeispiel: Original und Vorstufe desselben Satzes").isEmpty();
    }

    @Test
    void subordinateClausesAndConditionsStayOut() {
        pages.add(page("1", "Förderung", 'a').action("Kasse", "zahlt", "Zuschuss", JA).build());
        pages.add(page("2", "Bedingungen", 'b')
                .action("Kasse", "zahlt", "Zuschuss", NEIN)
                .edit(statement -> copy(statement, statement.arguments(), statement.findings(),
                        ParsedStatements.Clause.SUBORDINATE, statement.derivation()))
                .action("Kasse", "zahlt", "Zuschuss", NEIN)
                .edit(statement -> copy(statement, statement.arguments(), statement.findings(), statement.clause(),
                        ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.CONDITION,
                                "Wenn der Antrag fehlt, zahlt die Kasse den Zuschuss nicht.")))
                .build());

        assertThat(conflicts().contradictions()).as("Gegenbeispiel: Nebensatz und Bedingung").isEmpty();
    }

    @Test
    void stateChangesUncertainAndPlannedAreNotCompared() {
        pages.add(page("1", "Förderung", 'a')
                .action("Kasse", "zahlt", "Zuschuss", JA)
                .action("Kasse", "zahlt", "Zuschuss", features(Negation.NEGATED, Modality.NONE, Temporality.GEPLANT))
                .add("Kasse", "zahlt", "Zuschuss", NEIN, false, ParsedStatements.Quality.UNCERTAIN,
                        ParsedStatements.Review.UNREVIEWED, null)
                .state("Antrag", "vollständig", JA)
                .state("Antrag", "vollständig", NEIN)
                .edit(statement -> copy(statement, statement.arguments(),
                        Set.of(ParsedStatements.Statement.STATE_PROPERTY, StatusQuoEntry.STATE_CHANGE),
                        statement.clause(), statement.derivation()))
                .build());

        StatementConflicts conflicts = conflicts();

        assertThat(conflicts.contradictions()).isEmpty();
        assertThat(conflicts.replacements()).isEmpty();
    }

    @Test
    void replacementsOnlyWithOtherNegationOrAnotherActorOnTheSamePage() {
        Features frueherJa = features(Negation.AFFIRMED, Modality.NONE, Temporality.FRUEHER);
        Features frueherNein = features(Negation.NEGATED, Modality.NONE, Temporality.FRUEHER);
        pages.add(page("1", "Vergabe", 'a')
                .action("Leiter", "vergibt", "Nummer", JA)
                .action("System", "vergibt", "Nummer", frueherJa)
                .action("Kasse", "zahlt", "Zuschuss", JA)
                .action("Kasse", "zahlt", "Zuschuss", frueherNein)
                .action("Amt", "prüft", "Antrag", JA)
                .action("Amt", "prüft", "Antrag", frueherJa)
                .build());
        pages.add(page("2", "Andere Seite", 'b').action("Bote", "vergibt", "Nummer", frueherJa).build());

        StatementConflicts conflicts = conflicts();

        assertThat(conflicts.contradictions()).isEmpty();
        assertThat(conflicts.replacements()).extracting(StatementConflicts.Replacement::kind,
                        StatementConflicts.Replacement::predicate)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(StatementConflicts.ReplacementKind.NEGATION, "zahlt"),
                        org.assertj.core.groups.Tuple.tuple(StatementConflicts.ReplacementKind.ACTOR, "vergibt"));
        assertThat(conflicts.replacements()).filteredOn(r -> r.kind() == StatementConflicts.ReplacementKind.ACTOR)
                .singleElement().satisfies(replacement -> {
                    assertThat(replacement.current()).extracting(StatusQuoEntry::subjectKey)
                            .containsExactly("Leiter");
                    assertThat(replacement.outdated()).extracting(StatusQuoEntry::subjectKey)
                            .as("nur dieselbe Seite").containsExactly("System");
                });
    }
}
