package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Am Auftrag „Aussagenanalyse“: Seiten ohne KI-Freigabe getrennt von „kein Modell“ (ADR 0032, FL3). */
class ActivitiesStageNotesTest {

    @Test
    void pagesWithoutReleaseAndWithoutModelAreShownSeparatelyAndOnlyTheLatestOfEach() {
        List<JobEvent> events = List.of(
                event(1, null, "12 Seiten ermittelt, 0 aktuell übersprungen, 0 ohne Lesetext"),
                event(2, StatementAnalysisJobs.STAGE_NOT_RELEASED, "1 Seiten ohne KI-Freigabe, ohne Vorstufe analysiert"),
                event(3, StatementAnalysisJobs.STAGE_NOT_RELEASED, "3 Seiten ohne KI-Freigabe, ohne Vorstufe analysiert"),
                event(4, StatementAnalysisJobs.STAGE_NO_MODEL,
                        "2 Seiten ohne Vorstufe analysiert, weil kein Modell eingestellt ist"),
                event(5, "PAGE_FAILED", "Eine Seite wurde nicht analysiert"));

        assertThat(ActivitiesView.stageNotes(events)).containsExactly(
                "3 Seiten ohne KI-Freigabe, ohne Vorstufe analysiert",
                "2 Seiten ohne Vorstufe analysiert, weil kein Modell eingestellt ist");
        assertThat(ActivitiesView.stageNotes(List.of(event(1, null, "ohne Vorstufe gestartet")))).isEmpty();
    }

    /** Am Auftrag „Aussagen prüfen“ (FL4): Bilanz und Seiten ohne KI-Freigabe, je die jüngste. */
    @Test
    void theCheckShowsItsBalanceAndThePagesWithoutRelease() {
        List<JobEvent> events = List.of(
                event(1, StatementAnalysisJobs.CHECK_COSTS, "Prüfschritt: 3 Seiten ermittelt"),
                event(2, StatementAnalysisJobs.CHECK_NOT_RELEASED, "1 Seiten ohne KI-Freigabe nicht geprüft"),
                event(3, StatementAnalysisJobs.CHECK_COSTS, "Prüfschritt: 5 Seiten ermittelt"),
                event(4, StatementAnalysisJobs.CHECK_UNAVAILABLE, "kein Modell"));

        assertThat(ActivitiesView.stageNotes(events)).containsExactly("Prüfschritt: 5 Seiten ermittelt",
                "1 Seiten ohne KI-Freigabe nicht geprüft");
    }

    /** „Fachbegriffe ermitteln“ (FL5b): ausgelassene Modellphase gelb, Regelphase als Ergebniszeile. */
    @Test
    void theGlossaryShowsASkippedModelPhaseInYellowAndTheRuleResult() {
        List<JobEvent> events = List.of(
                event(1, com.aresstack.spaceharvester.application.glossary.GlossaryClassification.RULES_DONE,
                        "40 Begriffe abgeleitet"),
                event(2, com.aresstack.spaceharvester.application.glossary.GlossaryClassification.SkipReason.NO_MODEL
                        .code(), "Kein Modell eingestellt"),
                event(3, com.aresstack.spaceharvester.application.glossary.GlossaryClassification.SkipReason
                        .NOT_RELEASED.code(), "Quelle nicht freigegeben"));

        assertThat(ActivitiesView.notes(events)).containsExactly(
                new ActivitiesView.Note(ActivitiesView.RESULT_LABEL, "40 Begriffe abgeleitet", false),
                new ActivitiesView.Note(ActivitiesView.SKIPPED_LABEL, "Kein Modell eingestellt", true),
                new ActivitiesView.Note(ActivitiesView.SKIPPED_LABEL, "Quelle nicht freigegeben", true));
    }

    /** KI-Analysator (Fahrplan A2/A3): je Grund eine gelbe Zahl, die Kosten als eigene Zeile. */
    @Test
    void theModelAnalyzerShowsEachReasonInYellowAndItsCosts() {
        List<JobEvent> events = List.of(
                event(1, StatementAnalysisJobs.MODEL_COSTS, "Sprachmodell: 2 Seiten, 9 Sätze, 2 Modellaufrufe"),
                event(2, StatementAnalysisJobs.MODEL_NOT_RELEASED, "12 Seiten ohne KI-Freigabe bewusst ausgelassen"),
                event(3, StatementAnalysisJobs.MODEL_NO_MODEL, "3 Seiten bewusst ausgelassen"));

        assertThat(ActivitiesView.notes(events)).containsExactly(
                new ActivitiesView.Note(ActivitiesView.MODEL_COSTS_LABEL,
                        "Sprachmodell: 2 Seiten, 9 Sätze, 2 Modellaufrufe", false),
                new ActivitiesView.Note(ActivitiesView.MODEL_NOT_RELEASED_LABEL,
                        "12 Seiten ohne KI-Freigabe bewusst ausgelassen", true),
                new ActivitiesView.Note(ActivitiesView.MODEL_NO_MODEL_LABEL, "3 Seiten bewusst ausgelassen", true));
    }

    private static JobEvent event(long sequence, String code, String message) {
        return new JobEvent("job-1", sequence, Instant.parse("2026-11-17T03:00:00Z"), JobType.STATEMENT_ANALYSIS,
                "wiki", "DEV", JobEvent.Severity.INFO, JobEvent.Category.JOB, StatementAnalysisJobs.COMPONENT,
                StatementAnalysisJobs.PHASE_ANALYSIS, code, message, null, null, null, null);
    }
}
