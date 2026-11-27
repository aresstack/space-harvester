package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JobStepTest {

    @Test
    void theProgressColumnCountsLikeTheStatusLine() {
        // Abrufen: 504 geladen oder geprüft, noch nichts exportiert → nicht 0/2495.
        assertThat(ActivitiesView.progressText(running(JobType.EXPORT, new JobProgress(2495, 504, 0, 0))))
                .isEqualTo("504/2495");
        // Exportieren: die exportierten Seiten.
        assertThat(ActivitiesView.progressText(running(JobType.EXPORT, new JobProgress(2495, 2495, 40, 0))))
                .isEqualTo("40/2495");
        assertThat(ActivitiesView.progressText(running(JobType.EXPORT, new JobProgress(23, 23, 21, 2))))
                .isEqualTo("21/23 · 2 Fehler");
        assertThat(ActivitiesView.progressText(running(JobType.SYNC, new JobProgress(100, 37, 30, 0))))
                .isEqualTo("37/100");
        // Andere Arten: verarbeitet ohne die gescheiterten (Angelo 22.11.2026: nicht „398/398 · 1 Fehler“).
        assertThat(ActivitiesView.progressText(finished(JobType.BACKGROUND, new JobProgress(398, 398, 397, 1))))
                .isEqualTo("397/398 · 1 Fehler");
        // Eine Anreicherung zählt Abschnitte als erledigt, Fehlschläge je Prozessor: nichts abziehen (Codex #1103).
        assertThat(ActivitiesView.progressText(running(JobType.ENRICHMENT, new JobProgress(10, 10, 9, 2))))
                .isEqualTo("10/10 · 2 Fehler");
        // Beendet ohne eine exportierte Seite (alle fehlten): die exportierten, nicht die geprüften.
        assertThat(ActivitiesView.progressText(finished(JobType.EXPORT, new JobProgress(5, 5, 0, 5))))
                .isEqualTo("0/5 · 5 Fehler");
    }

    /** Angelo 22.11.2026: „Quelle“ und „Space“ sind zwei Spalten; die Startseite steht beim Space. */
    @Test
    void sourceAndSpaceAreSeparateColumns() {
        assertThat(ActivitiesView.source(job(null, null))).isEqualTo("wiki");
        assertThat(ActivitiesView.space(job(null, null))).isEqualTo("OPS");
        assertThat(ActivitiesView.space(job("42", "Betriebshandbuch"))).isEqualTo("OPS ab „Betriebshandbuch“");
        JobStatus glossary = new JobStatus("j2", JobType.GLOSSARY, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(0, 0, 0, 0), null, false, Instant.EPOCH, null, null, "42", "Betriebshandbuch");
        assertThat(ActivitiesView.space(glossary)).isEqualTo("OPS · „Betriebshandbuch“ samt Unterseiten");
        JobStatus whole = new JobStatus("j4", JobType.PURGE, "wiki", null, "local", JobState.RUNNING,
                new JobProgress(0, 0, 0, 0), null, false, Instant.EPOCH, null, null, null, null);
        assertThat(ActivitiesView.space(whole)).isEmpty();
    }

    /** Angelo 27.11.2026: Bei „Buch schreiben“ standen Quelle „*“ und Space „–“; jetzt Favoriten und der Ordner. */
    @Test
    void theBookFunctionShowsFavoritesAndItsFolder() {
        JobStatus book = new JobStatus("j5", JobType.BOOK, com.aresstack.spaceharvester.application.jobs.Jobs.ALL_SOURCES,
                null, "local", JobState.RUNNING, new JobProgress(88, 3, 0, 0), null, false, Instant.EPOCH, null, null,
                null, null, com.aresstack.spaceharvester.application.jobs.JobArea.with(java.util.Map.of(),
                "Ordner „Streuobst“"), false, false, null);
        assertThat(ActivitiesView.source(book)).isEqualTo("Favoriten");
        assertThat(ActivitiesView.space(book)).isEqualTo("Ordner „Streuobst“");
    }

    /** Der Balken trägt die Zahlen in der Mitte, Zustand und Zeit im Tooltip (statt eigener Spalten). */
    @Test
    void theBarCarriesCountsStateAndCreationTime() {
        JobStatus running = running(JobType.SYNC, new JobProgress(100, 37, 30, 0));
        RowActions.Action bar = ActivitiesView.progressSlots(running, null, ActivitiesView.stateText(running)).get(0);
        assertThat(((ActivitiesView.ProgressIcon) bar.icon()).label()).isEqualTo("37/100");
        assertThat(bar.tooltip()).startsWith(Texts.state(running) + " · 37 von 100 ")
                .endsWith(" · angelegt " + Texts.time(running.createdAt()));

        JobStatus done = finished(JobType.BACKGROUND, new JobProgress(398, 398, 398, 0));
        assertThat(ActivitiesView.stateText(done)).isEqualTo(Texts.state(JobState.COMPLETED) + ", fehlerfrei");
        JobStatus withErrors = finished(JobType.BACKGROUND, new JobProgress(398, 398, 397, 1));
        assertThat(ActivitiesView.stateText(withErrors)).doesNotContain("fehlerfrei");
        RowActions.Action failedBar = ActivitiesView.progressSlots(withErrors, null,
                ActivitiesView.stateText(withErrors)).get(0);
        // Beendet: der Zustand vor den Zahlen, für alle Arten gleich (Angelo 23.11.2026).
        assertThat(((ActivitiesView.ProgressIcon) failedBar.icon()).label()).isEqualTo("Fertig · 397/398");

        // Solange nichts gefunden ist, sagt der Balken den Zustand statt „0/0“.
        JobStatus fresh = running(JobType.SYNC, new JobProgress(0, 0, 0, 0));
        RowActions.Action empty = ActivitiesView.progressSlots(fresh, null, ActivitiesView.stateText(fresh)).get(0);
        assertThat(((ActivitiesView.ProgressIcon) empty.icon()).label()).isEqualTo(Texts.state(fresh));
        assertThat(empty.tooltip()).isEqualTo(Texts.state(fresh) + " · angelegt " + Texts.time(fresh.createdAt()));
    }

    /**
     * Angelo 23.11.2026: Manche Aufträge blieben bei 1034/1034 stehen, andere sagten „Fertig, fehlerfrei“. Jetzt sagt
     * jeder beendete Auftrag seinen Zustand, mit Zahlen, wo es welche gibt; offen nur die Zahlen.
     */
    @Test
    void everyFinishedJobSaysSoAndKeepsItsCountsWhereThereAreAny() {
        JobStatus full = finished(JobType.ENRICHMENT, new JobProgress(1034, 1034, 1034, 0));
        assertThat(ActivitiesView.barLabel(full, ActivitiesView.stateText(full))).isEqualTo("Fertig · 1034/1034");
        JobStatus nothing = finished(JobType.ENRICHMENT, new JobProgress(0, 0, 0, 0));
        assertThat(ActivitiesView.barLabel(nothing, ActivitiesView.stateText(nothing))).isEqualTo("Fertig, fehlerfrei");
        JobStatus open = running(JobType.ENRICHMENT, new JobProgress(1034, 1034, 1034, 0));
        assertThat(ActivitiesView.barLabel(open, ActivitiesView.stateText(open))).isEqualTo("1034/1034");
        JobStatus cancelled = new JobStatus("j5", JobType.SYNC, "wiki", "OPS", "local", JobState.CANCELLED,
                new JobProgress(15603, 12191, 12191, 0), null, false, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
                null, null);
        assertThat(ActivitiesView.barLabel(cancelled, ActivitiesView.stateText(cancelled)))
                .isEqualTo("Abgebrochen · 12191/15603");
        // Passt es nicht in den Balken, bleibt der Zustand; die Zahlen stehen im Tooltip.
        assertThat(ActivitiesView.ProgressIcon.fit("Abgebrochen · 12191/15603", String::length, 20))
                .isEqualTo("Abgebrochen");
        assertThat(ActivitiesView.ProgressIcon.fit("12191/15603", String::length, 5)).isEqualTo("12191/15603");
        assertThat(ActivitiesView.ProgressIcon.fit("Fertig · 3/3", String::length, 40)).isEqualTo("Fertig · 3/3");
    }

    @Test
    void everyJobTypeHasAnIcon() {
        for (JobType type : JobType.values()) {
            assertThat(ActivitiesView.typeIcon(type)).as(type.name()).isNotNull();
        }
    }

    private static JobStatus finished(JobType type, JobProgress progress) {
        return new JobStatus("j3", type, "wiki", "OPS", "local", JobState.COMPLETED, progress, null, false,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null);
    }

    private static JobStatus running(JobType type, JobProgress progress) {
        return new JobStatus("j2", type, "wiki", "OPS", "local", JobState.RUNNING, progress, null, false,
                Instant.EPOCH, null, null, null, null);
    }

    @Test
    void theExportCountsFetchedPagesWhileFetchingAndExportedPagesWhileExporting() {
        JobProgress progress = new JobProgress(127, 127, 40, 0);

        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Einlesen", new JobProgress(127, 90, 0, 0)))
                .isEqualTo(new ActivitiesView.Step("Einlesen · 90 von 127 Seiten geladen oder geprüft", 90, 127));
        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Seiten exportieren", progress))
                .isEqualTo(new ActivitiesView.Step("Seiten exportieren · 40 von 127 exportiert", 40, 127));
        assertThat(ActivitiesView.Counters.of(JobType.EXPORT).written()).isEqualTo("Exportiert");
        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Konvertieren (pdf)", progress))
                .isEqualTo(new ActivitiesView.Step("Konvertieren (pdf)", 0, 0));
        assertThat(ActivitiesView.Step.of(JobType.EXPORT, "Ablegen", progress).total()).isZero();
    }

    @Test
    void theEnrichmentCountsStepsAndNamesItsProcessor() {
        ActivitiesView.Step step = ActivitiesView.Step.of(JobType.ENRICHMENT, "Anreichern: nlp (1/2)",
                new JobProgress(200, 30, 28, 2));

        assertThat(step.text()).isEqualTo("Anreichern: nlp (1/2) · 30 von 200 Schritten");
        assertThat(step.done()).isEqualTo(30);
        assertThat(ActivitiesView.Counters.of(JobType.ENRICHMENT).discovered())
                .isEqualTo("Schritte (Seiten × Prozessoren)");
    }

    @Test
    void collectingImagesCountsCheckedAndStoredImages() {
        assertThat(ActivitiesView.Step.of(JobType.ASSETS, "Bilder beschaffen", new JobProgress(12, 5, 4, 1)))
                .isEqualTo(new ActivitiesView.Step("Bilder beschaffen · 5 von 12 Bildern geprüft, 4 abgelegt", 5, 12));
        assertThat(ActivitiesView.Counters.of(JobType.ASSETS))
                .isEqualTo(new ActivitiesView.Counters("Bildverweise", "Geprüft", "Abgelegt", "Fehlend"));
    }

    @Test
    void aPageDownloadNamesItsStartPageOnceTheJobHasReadIt() {
        assertThat(ActivitiesView.where(job(null, null))).isEqualTo("wiki / OPS");
        assertThat(ActivitiesView.startPage(job(null, null))).isEqualTo("–");
        assertThat(ActivitiesView.where(job("42", null))).isEqualTo("wiki / OPS ab „Seite 42“");
        assertThat(ActivitiesView.where(job("42", "Betriebshandbuch"))).isEqualTo("wiki / OPS ab „Betriebshandbuch“");
        assertThat(Explorer.PageItem.of("wiki", "OPS", "42", "Betriebshandbuch", true).indexable())
                .isTrue();
    }

    @Test
    void theSynonymRunIsNamedInsteadOfPlainEnrichment() {
        JobStatus synonyms = new JobStatus("j3", JobType.ENRICHMENT, "wiki", null, "space-harvester-core",
                JobState.QUEUED, new JobProgress(0, 0, 0, 0), null, false, Instant.EPOCH, null, null, null, null,
                java.util.Map.of(com.aresstack.spaceharvester.application.terms.SynonymDiscovery.TASK_PARAMETER,
                        com.aresstack.spaceharvester.application.terms.SynonymDiscovery.TASK), false, false, null);

        assertThat(Texts.type(synonyms)).isEqualTo("Synonyme der Fachbegriffe erkennen");
    }

    @Test
    void aGlossaryRunOverASubtreeNamesThePageWithItsSubpages() {
        JobStatus glossary = new JobStatus("j2", JobType.GLOSSARY, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(0, 0, 0, 0), null, false, Instant.EPOCH, null, null, "42", "Betriebshandbuch");

        assertThat(ActivitiesView.where(glossary)).isEqualTo("wiki / OPS · „Betriebshandbuch“ samt Unterseiten");
    }

    private static JobStatus job(String rootPageId, String rootTitle) {
        return new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.RUNNING, new JobProgress(0, 0, 0, 0),
                null, false, Instant.EPOCH, null, null, rootPageId, rootTitle);
    }

    @Test
    void aSyncWithoutPhaseStillShowsItsPages() {
        assertThat(ActivitiesView.Step.of(JobType.SYNC, null, new JobProgress(10, 3, 3, 0)).text())
                .isEqualTo("3 von 10 Seiten geladen oder geprüft");
    }

    /** „Aussagen prüfen“ (ADR 0032) zählt Seiten wie die Analyse, mit eigenen Wörtern. */
    @Test
    void checkingStatementsCountsPagesCheckedAndSkipped() {
        assertThat(ActivitiesView.Step.of(JobType.STATEMENT_CHECK, null, new JobProgress(10, 6, 4, 1)))
                .isEqualTo(new ActivitiesView.Step("Aussagen prüfen · 6 von 10 Seiten, 4 geprüft, 1 übersprungen", 6,
                        10));
        assertThat(ActivitiesView.Counters.of(JobType.STATEMENT_CHECK).written()).isEqualTo("Geprüft");
        assertThat(OverallProgress.priorSeconds(JobType.STATEMENT_CHECK, type -> null)).isEqualTo(2.0);
    }
}
