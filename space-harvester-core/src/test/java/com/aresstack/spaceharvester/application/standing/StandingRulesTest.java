package com.aresstack.spaceharvester.application.standing;

import static com.aresstack.spaceharvester.application.standing.StandingFixtures.features;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Derivation;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Features;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Modality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Negation;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.StageFlag;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Statement;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regeln des Stands (ADR 0032, FL6), mit erfundenen Aussagen. */
class StandingRulesTest {

    private static final PageMark ARCHIVE = new PageMark(PageMark.Kind.OUTDATED, PageMark.Where.ANCESTOR_TITLE,
            "archiv", "0");
    private static final PageMark DRAFT = new PageMark(PageMark.Kind.DRAFT, PageMark.Where.LABEL, "entwurf", "1");

    @Test
    void theTemporalityDecidesAndKeepsPastAndDiscardedApart() {
        assertThat(own(features(Temporality.GILT))).isEqualTo(new StatementStanding(Standing.GILT,
                StandingBasis.FEATURES, StandingReason.TEMPORALITY_GILT));
        assertThat(own(features(Temporality.GEPLANT)).standing()).isEqualTo(Standing.GEPLANT);
        assertThat(own(features(Temporality.FRUEHER))).isEqualTo(new StatementStanding(Standing.UEBERHOLT,
                StandingBasis.FEATURES, StandingReason.TEMPORALITY_FRUEHER));
        assertThat(own(features(Temporality.VERWORFEN))).isEqualTo(new StatementStanding(Standing.UEBERHOLT,
                StandingBasis.FEATURES, StandingReason.TEMPORALITY_VERWORFEN));
        assertThat(own(Features.UNKNOWN)).isEqualTo(new StatementStanding(Standing.UNKLAR, StandingBasis.NONE,
                StandingReason.NO_FEATURES));
    }

    @Test
    void negationAndModalityNeverChangeTheStanding() {
        // „Die Kasse muss den Beleg nicht prüfen.“ gilt trotzdem.
        assertThat(own(features(Negation.NEGATED, Modality.MUSS, Temporality.GILT)).standing())
                .isEqualTo(Standing.GILT);
        assertThat(own(features(Negation.NEGATED, Modality.KANN, Temporality.GEPLANT)).standing())
                .isEqualTo(Standing.GEPLANT);
    }

    @Test
    void theStageFlagDecidesOnlyWhenTheFeaturesComeFromTheStageAndSayNothing() {
        Derivation planning = Derivation.vorstufe(StageFlag.PLANNING, "Die Kasse prüft den Beleg.");
        assertThat(own(features(Temporality.GILT), planning)).isEqualTo(new StatementStanding(Standing.GEPLANT,
                StandingBasis.STAGE_FLAG, StandingReason.STAGE_PLANNING));
        assertThat(own(Features.UNKNOWN, Derivation.vorstufe(StageFlag.DISCARDED, "Die Kasse prüft den Beleg."))
                .reason()).isEqualTo(StandingReason.STAGE_DISCARDED);
        assertThat(own(features(Temporality.GILT), Derivation.vorstufe(StageFlag.CONDITION,
                "Die Kasse prüft den Beleg.")).reason()).isEqualTo(StandingReason.STAGE_CONDITION);
        // Sagt der Satz selbst etwas über die Zeit, gewinnt der Satz.
        assertThat(own(features(Temporality.FRUEHER), planning).reason())
                .isEqualTo(StandingReason.TEMPORALITY_FRUEHER);
        // Merkmale aus dem Original: dessen Zeitbezug zählt, das Kennzeichen nicht.
        assertThat(own(features(Temporality.GILT), planning.withFeaturesFromOriginal()).reason())
                .isEqualTo(StandingReason.TEMPORALITY_GILT);
    }

    @Test
    void thePageOnlyPullsWhatIsCurrentByDefault() {
        StandingRules.PageCap outdated = StandingRules.cap(null, List.of(ARCHIVE));
        StandingRules.PageCap draft = StandingRules.cap(null, List.of(DRAFT));

        assertThat(StandingRules.statement(statement(features(Temporality.GILT), null), null, outdated))
                .isEqualTo(new StatementStanding(Standing.UEBERHOLT, StandingBasis.PAGE,
                        StandingReason.PAGE_OUTDATED, ARCHIVE));
        assertThat(StandingRules.statement(statement(features(Temporality.GILT), null), null, draft))
                .isEqualTo(new StatementStanding(Standing.UNKLAR, StandingBasis.PAGE, StandingReason.PAGE_DRAFT,
                        DRAFT));
        assertThat(StandingRules.statement(statement(features(Temporality.FRUEHER), null), null, outdated).basis())
                .as("der Satz gewinnt").isEqualTo(StandingBasis.FEATURES);
        assertThat(StandingRules.statement(statement(features(Temporality.GEPLANT), null), null, draft).standing())
                .isEqualTo(Standing.GEPLANT);
        assertThat(StandingRules.statement(statement(Features.UNKNOWN, null), null, outdated).basis())
                .isEqualTo(StandingBasis.NONE);
    }

    @Test
    void anArchivePagePullsFactsAndConditionsOfTheStage() {
        StandingRules.PageCap outdated = StandingRules.cap(null, List.of(ARCHIVE));
        for (StageFlag flag : List.of(StageFlag.FACT, StageFlag.CONDITION)) {
            assertThat(StandingRules.statement(statement(features(Temporality.GILT),
                    Derivation.vorstufe(flag, "Die Kasse prüft den Beleg.")), null, outdated))
                    .isEqualTo(new StatementStanding(Standing.UEBERHOLT, StandingBasis.PAGE,
                            StandingReason.PAGE_OUTDATED, ARCHIVE));
        }
        assertThat(StandingRules.statement(statement(features(Temporality.GILT),
                Derivation.vorstufe(StageFlag.PLANNING, "Die Kasse prüft den Beleg.")), null, outdated))
                .as("Planung auf überholter Seite").isEqualTo(new StatementStanding(Standing.UNKLAR,
                        StandingBasis.PAGE, StandingReason.PAGE_OUTDATED_PLAN, ARCHIVE));
    }

    @Test
    void aDraftPagePullsFactsAndConditionsOfTheStage() {
        StandingRules.PageCap draft = StandingRules.cap(null, List.of(DRAFT));
        for (StageFlag flag : List.of(StageFlag.FACT, StageFlag.CONDITION)) {
            assertThat(StandingRules.statement(statement(Features.UNKNOWN,
                    Derivation.vorstufe(flag, "Die Kasse prüft den Beleg.")), null, draft))
                    .isEqualTo(new StatementStanding(Standing.UNKLAR, StandingBasis.PAGE, StandingReason.PAGE_DRAFT,
                            DRAFT));
        }
        assertThat(StandingRules.statement(statement(Features.UNKNOWN,
                Derivation.vorstufe(StageFlag.DISCARDED, "Die Kasse prüft den Beleg.")), null, draft).basis())
                .isEqualTo(StandingBasis.STAGE_FLAG);
    }

    @Test
    void aPlanOnAnOutdatedPageBecomesUnclear() {
        assertThat(StandingRules.statement(statement(features(Temporality.GEPLANT), null), null,
                StandingRules.cap(null, List.of(ARCHIVE)))).isEqualTo(new StatementStanding(Standing.UNKLAR,
                StandingBasis.PAGE, StandingReason.PAGE_OUTDATED_PLAN, ARCHIVE));
    }

    @Test
    void outdatedWinsOverDraftAndAPageDecisionWinsOverMarks() {
        assertThat(StandingRules.cap(null, List.of(DRAFT, ARCHIVE))).isEqualTo(
                new StandingRules.PageCap(Standing.UEBERHOLT, StandingReason.PAGE_OUTDATED, ARCHIVE));
        assertThat(StandingRules.cap(decision(null, Standing.GILT), List.of(DRAFT, ARCHIVE)))
                .as("Mensch sagt: Seite gilt").isNull();
        assertThat(StandingRules.statement(statement(features(Temporality.GILT), null), null,
                StandingRules.cap(decision(null, Standing.GEPLANT), List.of()))).isEqualTo(new StatementStanding(
                Standing.GEPLANT, StandingBasis.PAGE, StandingReason.PAGE_DECISION));
    }

    @Test
    void aStatementDecisionWinsOverEverything() {
        StandingRules.PageCap outdated = StandingRules.cap(null, List.of(ARCHIVE));

        assertThat(StandingRules.statement(statement(features(Temporality.FRUEHER), null),
                decision("x#s0.v2", Standing.GILT), outdated)).isEqualTo(new StatementStanding(Standing.GILT,
                StandingBasis.DECISION, StandingReason.DECISION));
    }

    @Test
    void onePlaceDecidesWhetherAndHowAStatementCounts() {
        StandingFixtures.PageBuilder page = page("1", "Förderung", 'a')
                .add("Kasse", "prüft", "Beleg", features(Temporality.GILT), false, ParsedStatements.Quality.INVALID,
                        ParsedStatements.Review.UNREVIEWED, null)
                .add("Kasse", "prüft", "Beleg", features(Temporality.GILT), false,
                        ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Review.REJECTED, null)
                .add("Kasse", "prüft", "Beleg", features(Temporality.GILT), false,
                        ParsedStatements.Quality.UNCERTAIN, ParsedStatements.Review.UNREVIEWED, null)
                .state("Antrag", "unvollständig", features(Temporality.GILT));

        assertThat(page.statements).extracting(statement -> StandingRules.effectiveQuality(statement).orElse(null))
                .containsExactly(null, null, ParsedStatements.Quality.UNCERTAIN, ParsedStatements.Quality.PLAUSIBLE);
        assertThat(StandingRules.kind(page.statements.get(3))).isEqualTo(StatementKind.STATE);
        assertThat(StandingRules.kind(page.statements.get(0))).isEqualTo(StatementKind.ACTION);
    }

    @Test
    void thePageLabelIsTheMajorityOfCertainStatements() {
        assertThat(StandingRules.majority(Map.of(Standing.UEBERHOLT, 3, Standing.GILT, 1)))
                .isEqualTo(Standing.UEBERHOLT);
        assertThat(StandingRules.majority(Map.of(Standing.GILT, 2, Standing.GEPLANT, 2, Standing.UNKLAR, 1)))
                .isEqualTo(Standing.UNKLAR);
        assertThat(StandingRules.majority(Map.of(Standing.GEPLANT, 1))).isEqualTo(Standing.GEPLANT);
        assertThat(StandingRules.majority(Map.of())).isEqualTo(Standing.UNKLAR);
    }

    private static StandingDecision decision(String statementId, Standing standing) {
        return new StandingDecision(new PageKey("wiki", "1"), statementId, standing, 1, "angelo",
                StandingFixtures.AT, "angelo", StandingFixtures.AT);
    }

    private static StatementStanding own(Features features) {
        return StandingRules.own(statement(features, null));
    }

    private static StatementStanding own(Features features, Derivation derivation) {
        return StandingRules.own(statement(features, derivation));
    }

    private static Statement statement(Features features, Derivation derivation) {
        return page("1", "Förderung", 'a').add("Kasse", "prüft", "Beleg", features, false,
                ParsedStatements.Quality.PLAUSIBLE, ParsedStatements.Review.UNREVIEWED, derivation).statements
                .getFirst();
    }
}
