package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStage;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Die Zeilenaktionen kommen vom Zustandsobjekt des Core (ADR 0025): Pause und Play nie zugleich, kein eigener
 * Pfeil im Kreis mehr; die Oberfläche liefert nur Symbol und Tooltip je Aktion und die Texte der Zustände.
 */
class ActivitiesActionsTest {

    @Test
    void theCoreStateDecidesWhichActionsARowOffers() {
        assertThat(job(JobType.EXPORT, JobState.RUNNING, false).lifecycle().actions())
                .containsExactly(JobAction.PAUSE, JobAction.CANCEL, JobAction.DELETE);
        assertThat(job(JobType.EXPORT, JobState.PAUSED, false).lifecycle().actions())
                .containsExactly(JobAction.RESUME, JobAction.DELETE);
        assertThat(job(JobType.EXPORT, JobState.FAILED, false).lifecycle().actions())
                .containsExactly(JobAction.RESUME, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(job(JobType.ENRICHMENT, JobState.RUNNING, false).lifecycle().actions())
                .containsExactly(JobAction.CANCEL, JobAction.DELETE);
        // Bilder und Anhänge lassen sich anhalten und fortsetzen wie Downloads (ADR 0025).
        assertThat(job(JobType.ASSETS, JobState.PAUSED, false).lifecycle().actions())
                .containsExactly(JobAction.RESUME, JobAction.DELETE);
        assertThat(job(JobType.ATTACHMENTS, JobState.RUNNING, false).lifecycle().actions())
                .containsExactly(JobAction.PAUSE, JobAction.CANCEL, JobAction.DELETE);
        assertThat(job(JobType.EXPORT, JobState.QUEUED, false).lifecycle().actions())
                .containsExactly(JobAction.START_NOW, JobAction.CANCEL, JobAction.DELETE);
        // Downloads (ADR 0025): fertig → Neu erzwingen; mit Fehlern fertig → zuerst Nur Fehler.
        assertThat(job(JobType.EXPORT, JobState.COMPLETED, false).lifecycle().actions())
                .containsExactly(JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(withFailures().lifecycle().actions())
                .containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH, JobAction.DELETE);
        assertThat(job(JobType.SYNC, JobState.COMPLETED, false).lifecycle().actions())
                .containsExactly(JobAction.FORCE_REFRESH, JobAction.DELETE);
    }

    /**
     * Feste Plätze (Angelo 10.11.2026): ↻ Nur Fehler, Play ⇄ Pause, Stop ⇄ Neu erzwingen, Mülltonne. Jede Zeile hat
     * gleich viele Knöpfe, kein Platz ist je leer; was der Zustand nicht erlaubt, ist blass.
     */
    @Test
    void everyStateFillsTheSameFourSlotsAndTheTrashIsAlwaysThere() {
        for (JobType type : JobType.values()) {
            for (JobState state : JobState.values()) {
                for (boolean pausing : new boolean[] {false, true}) {
                    JobStatus job = job(type, state, pausing);
                    List<RowActions.Action> slots = ActivitiesView.stateActions(job, action -> { });
                    assertThat(slots).as(type + " " + state).hasSize(ActivitiesView.Slot.values().length);
                    assertThat(slots).as("kein Platz leer").noneMatch(RowActions.Action::empty);
                    assertThat(slots.get(ActivitiesView.Slot.DELETE.ordinal()).enabled()).as("Mülltonne").isTrue();
                }
            }
        }
        assertThat(ActivitiesView.SLOTS).isEqualTo(5 + 1 + 2 + 4);
    }

    @Test
    void running() {
        assertSlots(job(JobType.EXPORT, JobState.RUNNING, false), null, JobAction.PAUSE, JobAction.CANCEL);
    }

    @Test
    void waiting() {
        assertSlots(job(JobType.EXPORT, JobState.QUEUED, false), null, JobAction.START_NOW, JobAction.CANCEL);
    }

    @Test
    void pauseRequested() {
        assertSlots(job(JobType.EXPORT, JobState.RUNNING, true), null, null, JobAction.CANCEL);
    }

    @Test
    void cancelRequested() {
        JobStatus job = new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(0, 0, 0, 0), null, true, Instant.EPOCH, null, null, null, null, Map.of(), false, false,
                null);
        assertSlots(job, null, null, null);
        // Der Platz bleibt Stop, blass, solange der Abbruch läuft.
        assertThat(ActivitiesView.stateActions(job, a -> { }).get(ActivitiesView.Slot.STOP_FORCE.ordinal()).icon())
                .isSameAs(ActivitiesView.actionIcon(JobAction.CANCEL));
    }

    @Test
    void paused() {
        assertSlots(paused(PauseReason.USER), null, JobAction.RESUME, null);
    }

    @Test
    void failedOrCancelled() {
        assertSlots(job(JobType.EXPORT, JobState.FAILED, false), null, JobAction.RESUME, JobAction.FORCE_REFRESH);
        assertSlots(job(JobType.EXPORT, JobState.CANCELLED, false), null, JobAction.RESUME, JobAction.FORCE_REFRESH);
    }

    @Test
    void completed() {
        assertSlots(job(JobType.EXPORT, JobState.COMPLETED, false), null, null, JobAction.FORCE_REFRESH);
    }

    @Test
    void completedWithErrors() {
        assertSlots(withFailures(), JobAction.RETRY_FAILED, null, JobAction.FORCE_REFRESH);
    }

    /**
     * Bilder und Anhänge zeigen statt der blassen Formatknöpfe einen Fortschrittsbalken über dieselben fünf Plätze
     * (Angelo 11.11.2026); Exporte behalten ihre Formate. Die Platzzahl bleibt gleich, damit alles untereinander steht.
     */
    @Test
    void imageAndAttachmentRowsShowAProgressBarInsteadOfFormats() {
        for (JobType type : new JobType[] {JobType.ASSETS, JobType.ATTACHMENTS}) {
            JobStatus job = new JobStatus("j1", type, "wiki", "OPS", "local", JobState.RUNNING,
                    new JobProgress(630, 629, 600, 3), null, false, Instant.EPOCH, Instant.EPOCH, null, null, null,
                    Map.of(), false, false, null);
            assertThat(ActivitiesView.showsProgress(job)).isTrue();
            List<RowActions.Action> slots = ActivitiesView.progressSlots(job);
            assertThat(slots).hasSize(ActivitiesView.FORMATS.size());
            assertThat(RowActions.wide(slots.get(0))).isTrue();
            assertThat(slots.subList(1, slots.size())).allMatch(RowActions.Action::empty);
            assertThat(RowActions.covering(slots, 4)).as("Tooltip auch am rechten Ende").isSameAs(slots.get(0));
            // Geprüft, aber weder abgelegt noch fehlgeschlagen = bewusst ausgelassen: im Balken gelb, nicht rot, und
            // als eigene Zahl (Angelo 13.11.2026).
            assertThat(slots.get(0).tooltip()).isEqualTo("629 von 630 geprüft · 26 bewusst ausgelassen · 3 fehlend");
            ActivitiesView.ProgressIcon icon = (ActivitiesView.ProgressIcon) slots.get(0).icon();
            assertThat(icon.skipped()).isEqualTo(26);
            assertThat(icon.failed()).isEqualTo(3);
            assertThat(ActivitiesView.progressText(job)).isEqualTo("629/630 · 26 ausgelassen · 3 Fehler");
        }
        assertThat(ActivitiesView.showsProgress(job(JobType.EXPORT, JobState.RUNNING, false))).isFalse();
        JobStatus export = new JobStatus("j2", JobType.EXPORT, "wiki", "OPS", "local", JobState.COMPLETED,
                new JobProgress(100, 100, 90, 2), null, false, Instant.EPOCH, Instant.EPOCH, null, null, null,
                Map.of(), false, false, null);
        assertThat(ActivitiesView.skipped(export)).as("nur Bilder und Anhänge zählen Ausgelassenes").isZero();
        assertThat(ActivitiesView.progressText(export)).isEqualTo("90/100 · 2 Fehler");
    }

    /**
     * Restzeit mittig im Balken (Angelo 11.11.2026): globaler Durchschnitt je Datei mal offene Elemente; nichts bei 0
     * parallelen Downloads, ohne Offenes oder nach dem Ende.
     */
    @Test
    void theProgressBarShowsTheEstimatedRemainingTime() {
        JobStatus job = new JobStatus("j1", JobType.ATTACHMENTS, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(630, 30, 30, 0), null, false, Instant.EPOCH, Instant.EPOCH, null, null, null,
                Map.of(), false, false, null);
        java.time.Duration average = java.time.Duration.ofSeconds(2);
        java.time.Duration remaining = ActivitiesView.remaining(job, average, 4);
        assertThat(remaining).isEqualTo(java.time.Duration.ofSeconds(1200));
        assertThat(ActivitiesView.remainingText(remaining)).isEqualTo("≈ 20 min");
        assertThat(ActivitiesView.remainingText(java.time.Duration.ofSeconds(30))).isEqualTo("< 1 min");
        assertThat(ActivitiesView.remainingText(java.time.Duration.ofMinutes(303))).isEqualTo("≈ 5 h 3 min");
        assertThat(ActivitiesView.remaining(job, average, 0)).as("Downloads abgeschaltet").isNull();
        assertThat(ActivitiesView.remaining(job(JobType.ASSETS, JobState.COMPLETED, false), average, 4)).isNull();
        assertThat(ActivitiesView.remaining(job(JobType.EXPORT, JobState.RUNNING, false), average, 4)).isNull();

        List<RowActions.Action> slots = ActivitiesView.progressSlots(job, remaining);
        assertThat(((ActivitiesView.ProgressIcon) slots.get(0).icon()).label()).isEqualTo("≈ 20 min");
        assertThat(slots.get(0).tooltip()).isEqualTo("30 von 630 geprüft · noch etwa 20 min");
    }

    /** Bilder und Anhänge (#631): fertig → Neu erzwingen; unvollständig → Nur Fehler und Neu erzwingen. */
    @Test
    void imagesAndAttachments() {
        for (JobType type : new JobType[] {JobType.ASSETS, JobType.ATTACHMENTS}) {
            assertSlots(job(type, JobState.COMPLETED, false), null, null, JobAction.FORCE_REFRESH);
            JobStatus incomplete = new JobStatus("j1", type, "wiki", "OPS", "local", JobState.COMPLETED,
                    new JobProgress(5, 5, 3, 2), null, false, Instant.EPOCH, null, Instant.EPOCH, null, null,
                    Map.of(), true, false, null);
            assertSlots(incomplete, JobAction.RETRY_FAILED, null, JobAction.FORCE_REFRESH);
            assertSlots(job(type, JobState.CANCELLED, false), null, JobAction.RESUME, JobAction.FORCE_REFRESH);
        }
    }

    /** Die drei Zustandsplätze vor der Mülltonne; {@code null} heißt: Symbol steht da, aber blass. */
    private static void assertSlots(JobStatus job, JobAction retry, JobAction playPause, JobAction stopForce) {
        var state = job.lifecycle();
        assertThat(ActivitiesView.actionFor(ActivitiesView.Slot.RETRY, state)).isEqualTo(retry);
        assertThat(ActivitiesView.actionFor(ActivitiesView.Slot.PLAY_PAUSE, state)).isEqualTo(playPause);
        assertThat(ActivitiesView.actionFor(ActivitiesView.Slot.STOP_FORCE, state)).isEqualTo(stopForce);
        assertThat(ActivitiesView.actionFor(ActivitiesView.Slot.DELETE, state)).isEqualTo(JobAction.DELETE);
        List<RowActions.Action> slots = ActivitiesView.stateActions(job, a -> { });
        assertThat(slots).extracting(RowActions.Action::enabled)
                .containsExactly(retry != null, playPause != null, stopForce != null, true);
        if (playPause != null) {
            assertThat(slots.get(1).icon()).isSameAs(ActivitiesView.actionIcon(playPause));
        }
    }

    @Test
    void everyActionHasAnIconAndATooltipInPlainWords() {
        JobStatus running = job(JobType.EXPORT, JobState.RUNNING, false);
        for (JobAction action : JobAction.values()) {
            assertThat(ActivitiesView.actionIcon(action)).isNotNull();
            assertThat(ActivitiesView.actionTip(action, running)).isNotBlank();
        }
        assertThat(ActivitiesView.actionTip(JobAction.PAUSE, running))
                .isEqualTo("Anhalten · Geladenes bleibt, später fortsetzen");
        assertThat(ActivitiesView.actionTip(JobAction.RESUME, job(JobType.EXPORT, JobState.PAUSED, false)))
                .isEqualTo("Fortsetzen · holt nur, was noch fehlt");
        assertThat(ActivitiesView.actionTip(JobAction.RESUME, job(JobType.EXPORT, JobState.FAILED, false),
                dataset(new ExportDatasetInfo.Media(3, 0, 2, 0))))
                .isEqualTo("Fortsetzen · 2 Dateien fehlen wegen fehlender Anmeldung; "
                        + "die Einmalcode-Abfrage kommt dann wieder");
        assertThat(ActivitiesView.actionTip(JobAction.CANCEL, running)).isEqualTo("Abbrechen");
        assertThat(ActivitiesView.actionTip(JobAction.RETRY_FAILED, withFailures()))
                .startsWith("Nur Fehler neu versuchen · ");
        assertThat(ActivitiesView.actionTip(JobAction.FORCE_REFRESH, withFailures())).startsWith("Neu erzwingen · ");
        assertThat(ActivitiesView.actionTip(JobAction.CANCEL, job(JobType.EXPORT, JobState.RUNNING, true)))
                .isEqualTo("Abbrechen · statt nur anzuhalten");
    }

    @Test
    void theStateTextFollowsTheCoreStateKey() {
        assertThat(Texts.state(job(JobType.EXPORT, JobState.RUNNING, false))).isEqualTo("Läuft");
        assertThat(Texts.state(job(JobType.EXPORT, JobState.RUNNING, true))).isEqualTo("Anhalten angefordert");
        assertThat(Texts.state(job(JobType.EXPORT, JobState.PAUSED, false))).isEqualTo("Angehalten");
        JobStatus complete = job(JobType.EXPORT, JobState.COMPLETED, false);
        assertThat(Texts.state(complete)).isEqualTo("Fertig");
        assertThat(Texts.tone(complete)).isEqualTo(Comic.Tone.GOOD);
        JobStatus incomplete = new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.COMPLETED,
                new JobProgress(5, 5, 3, 2), null, false, Instant.EPOCH, null, Instant.EPOCH, null, null, Map.of(),
                true, false, null);
        assertThat(Texts.state(incomplete)).isEqualTo("Fertig, unvollständig");
        assertThat(Texts.tone(incomplete)).isEqualTo(Comic.Tone.WARNING);
        // Selbst angehalten (#611): der Grund steht im Zustand.
        assertThat(Texts.state(paused(PauseReason.SIGN_IN_REQUIRED))).isEqualTo("Angehalten – wartet auf Anmeldung");
        assertThat(Texts.state(paused(PauseReason.CONNECTION_LOST)))
                .isEqualTo("Angehalten – Verbindung unterbrochen");
        assertThat(Texts.tone(paused(PauseReason.CONNECTION_LOST))).isEqualTo(Comic.Tone.WARNING);
        assertThat(Texts.state(paused(PauseReason.USER))).isEqualTo("Angehalten");
    }

    /** ↻ Nur Fehler steht in jedem Zustand bereit, sobald etwas gescheitert ist (Angelo 11.11.2026, Core #634). */
    @Test
    void retryFailedIsOfferedWhileRunningOrPausedOnceSomethingFailed() {
        JobStatus running = new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(9, 5, 3, 2), null, false, Instant.EPOCH, Instant.EPOCH, null, null, null, Map.of(),
                false, false, null);
        assertSlots(running, JobAction.RETRY_FAILED, JobAction.PAUSE, JobAction.CANCEL);
        JobStatus paused = new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.PAUSED,
                new JobProgress(9, 5, 3, 2), null, false, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null,
                Map.of(), false, false, PauseReason.USER);
        assertSlots(paused, JobAction.RETRY_FAILED, JobAction.RESUME, null);
    }

    /** Vorbereitung und Vorbereitet (#635) haben eigene Texte und Farben; Vorbereitet startet mit „Jetzt starten“. */
    @Test
    void preparingAndPreparedDownloads() {
        JobStatus preparing = staged(JobStage.PREPARING);
        assertThat(Texts.state(preparing)).isEqualTo("Vorbereitung");
        assertThat(Texts.tone(preparing)).isEqualTo(Comic.Tone.RUNNING);
        assertSlots(preparing, null, JobAction.PAUSE, JobAction.CANCEL);
        JobStatus prepared = staged(JobStage.PREPARED);
        assertThat(Texts.state(prepared)).isEqualTo("Vorbereitet");
        assertThat(Texts.tone(prepared)).isEqualTo(Comic.Tone.NEUTRAL);
        assertSlots(prepared, null, JobAction.START_NOW, JobAction.CANCEL);
    }

    private static JobStatus staged(JobStage stage) {
        return new JobStatus("j1", JobType.ASSETS, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(9, 0, 0, 0), null, false, Instant.EPOCH, Instant.EPOCH, null, null, null, Map.of(),
                false, false, null, stage);
    }

    /**
     * Bilder und Anhänge stehen als Unterzeilen unter ihrem Export (Angelo 11.11.2026), Bilder zuerst; zugeklappt
     * bleibt nur der Export. Ohne Export in der Liste bleiben sie eigenständig.
     */
    @Test
    void imagesAndAttachmentsAreGroupedUnderTheirExport() {
        JobStatus older = linked("e0", JobType.EXPORT, null);
        JobStatus export = linked("e1", JobType.EXPORT, null);
        JobStatus attachments = linked("a1", JobType.ATTACHMENTS, "e1");
        JobStatus images = linked("b1", JobType.ASSETS, "e1");
        JobStatus orphan = linked("b9", JobType.ASSETS, "gone");
        List<JobStatus> jobs = List.of(attachments, export, orphan, images, older);

        assertThat(ActivitiesView.grouped(jobs, java.util.Set.of())).extracting(JobStatus::id)
                .containsExactly("e1", "b1", "a1", "b9", "e0");
        assertThat(ActivitiesView.grouped(jobs, java.util.Set.of("e1"))).extracting(JobStatus::id)
                .containsExactly("e1", "b9", "e0");
        // Gezeichnete Symbole statt Unicode-Pfeilen, die Swing als Kästchen zeigt.
        assertThat(ActivitiesView.groupIcon(true, false, false)).isSameAs(Glyph.COLLAPSE);
        assertThat(ActivitiesView.groupIcon(true, true, false)).isSameAs(Glyph.EXPAND);
        assertThat(ActivitiesView.groupIcon(false, false, true)).isSameAs(ActivitiesView.BRANCH);
        assertThat(ActivitiesView.groupIcon(false, false, false)).isNull();
    }

    /** Rückmeldung nach dem Ändern der Grenzen, mit Verbindungsbudget (Angelo 11.11.2026). */
    @Test
    void theFeedbackNamesTheConnectionBudget() {
        assertThat(ActivitiesView.parallelMessage(2, 4, 8))
                .isEqualTo("Gleichzeitig 4 Downloads mit zusammen 8 Verbindungen, 2 Vorbereitungen");
        assertThat(ActivitiesView.parallelMessage(2, 4, 0))
                .isEqualTo("Keine Verbindungen: Bilder und Anhänge warten, Texte laden weiter");
        assertThat(ActivitiesView.parallelMessage(2, 0, 8))
                .isEqualTo("Downloads angehalten: Bilder und Anhänge werden nur vorbereitet");
    }

    /** Alle auf- und alle zuklappen (Angelo 11.11.2026): Knöpfe links neben den Auswahlen. */
    @Test
    void allGroupsCanBeExpandedAndCollapsedAtOnce() {
        ActivitiesView.JobsModel model = new ActivitiesView.JobsModel();
        model.set(List.of(linked("e1", JobType.EXPORT, null), linked("b1", JobType.ASSETS, "e1"),
                linked("e2", JobType.EXPORT, null), linked("a2", JobType.ATTACHMENTS, "e2")));

        model.setAll(true);
        assertThat(model.visible()).extracting(JobStatus::id).containsExactly("e1", "e2");
        model.setAll(false);
        assertThat(model.visible()).extracting(JobStatus::id).containsExactly("e1", "b1", "e2", "a2");
    }

    /**
     * Hinter einer laufenden Export-Gruppe liegt derselbe hellblaue Balken wie in der Statuszeile (Angelo 11.11.2026):
     * zugeklappt nur hinter der Zeile des Exports, aufgeklappt auch hinter seinen Bildern und Anhängen. Gruppen ohne
     * Schätzung (nichts läuft) und andere Aufträge bleiben ohne Balken.
     */
    @Test
    void aRunningExportCarriesOneBandBehindAllRowsOfItsGroup() {
        ActivitiesView.JobsModel model = new ActivitiesView.JobsModel();
        model.set(List.of(linked("e1", JobType.EXPORT, null), linked("b1", JobType.ASSETS, "e1"),
                linked("a1", JobType.ATTACHMENTS, "e1"), linked("s1", JobType.SYNC, null),
                linked("e2", JobType.EXPORT, null), linked("b2", JobType.ASSETS, "e2"),
                linked("e3", JobType.EXPORT, null), linked("b3", JobType.ASSETS, "e3")));
        Map<String, OverallProgress.Estimate> estimates = Map.of(
                "e1", new OverallProgress.Estimate(0.4, false, null),
                "e2", new OverallProgress.Estimate(0, true, null));

        assertThat(ActivitiesView.bands(model.visible(), model::child, estimates)).containsExactly(
                new ActivitiesView.Band(0, 2, 0.4, false), new ActivitiesView.Band(4, 5, 0, true));
        model.setAll(true);
        assertThat(ActivitiesView.bands(model.visible(), model::child, estimates)).containsExactly(
                new ActivitiesView.Band(0, 0, 0.4, false), new ActivitiesView.Band(2, 2, 0, true));
        assertThat(new ActivitiesView.Band(4, 5, 0, true).covers(5)).isTrue();
        assertThat(new ActivitiesView.Band(4, 5, 0, true).covers(6)).isFalse();
    }

    /**
     * „Alle anhalten“ in der Kopfzeile wird zu „Alle fortsetzen“ mit Play-Symbol, sobald nichts mehr wartet oder läuft,
     * aber etwas angehalten ist; fertige Aufträge startet es nie neu.
     */
    @Test
    void pauseAllTurnsIntoResumeAllOnceEverythingIsPaused() {
        JobStatus running = job(JobType.EXPORT, JobState.RUNNING, false);
        JobStatus paused = paused(PauseReason.USER);
        JobStatus done = job(JobType.EXPORT, JobState.COMPLETED, false);

        assertThat(ActivitiesView.GlobalPause.of(List.of(running, paused, done)))
                .isEqualTo(ActivitiesView.GlobalPause.PAUSE);
        assertThat(ActivitiesView.GlobalPause.of(List.of(paused, done))).isEqualTo(ActivitiesView.GlobalPause.RESUME);
        assertThat(ActivitiesView.GlobalPause.of(List.of(done, withFailures())))
                .isEqualTo(ActivitiesView.GlobalPause.NONE);
        assertThat(ActivitiesView.GlobalPause.of(List.of())).isEqualTo(ActivitiesView.GlobalPause.NONE);
    }

    /**
     * „Alle anhalten“ und „Alle abbrechen“ stehen mittig über der ganzen Kopfzeile (Angelo 11.11.2026); wird es eng,
     * rücken sie zwischen Titel und rechte Gruppe und zeigen zuletzt nur ihr Symbol, statt abgeschnitten zu werden.
     */
    @Test
    void theGlobalButtonsSitInTheMiddleOfTheRowAndShrinkToTheirSymbols() {
        JPanel west = fixed(150);
        JPanel center = fixed(260);
        JPanel east = fixed(380);
        boolean[] compact = {false};
        JPanel head = new JPanel();
        head.setLayout(new ActivitiesView.HeadLayout(west, center, east, 260, narrow -> {
            compact[0] = narrow;
            center.setPreferredSize(new Dimension(narrow ? 74 : 260, 28));
        }));
        head.add(west);
        head.add(center);
        head.add(east);

        layout(head, 1200);
        assertThat(center.getBounds()).isEqualTo(new Rectangle(470, 0, 260, 28));
        assertThat(east.getX()).isEqualTo(820);
        assertThat(compact[0]).isFalse();
        layout(head, 900);
        assertThat(center.getX()).as("so mittig wie möglich, ohne die rechte Gruppe zu berühren").isEqualTo(258);
        assertThat(compact[0]).isFalse();
        layout(head, 700);
        assertThat(compact[0]).as("Texte passen nicht mehr").isTrue();
        assertThat(center.getBounds()).isEqualTo(new Rectangle(244, 0, 74, 28));
        assertThat(west.getWidth()).isEqualTo(150);
        layout(head, 560);
        assertThat(center.getBounds()).as("der Titel gibt Platz ab, beide Knöpfe bleiben ganz sichtbar")
                .isEqualTo(new Rectangle(104, 0, 74, 28));
        assertThat(west.getWidth()).isEqualTo(102);
        layout(head, 1200);
        assertThat(compact[0]).isFalse();
        assertThat(head.getPreferredSize().width).isEqualTo(150 + 260 + 380 + 2 * ActivitiesView.HeadLayout.GAP);
    }

    private static void layout(JPanel head, int width) {
        head.setSize(width, 28);
        head.doLayout();
    }

    private static JPanel fixed(int width) {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(width, 28));
        return panel;
    }

    private static JobStatus linked(String id, JobType type, String exportId) {
        return new JobStatus(id, type, "wiki", "OPS", "local", JobState.RUNNING, new JobProgress(0, 0, 0, 0), null,
                false, Instant.EPOCH, null, null, null, null,
                exportId == null ? Map.of() : Map.of("exportId", exportId), false, false, null);
    }

    private static JobStatus withFailures() {
        return new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.COMPLETED,
                new JobProgress(6, 6, 0, 6), null, false, Instant.EPOCH, null, Instant.EPOCH, null, null, Map.of(),
                true, false, null);
    }

    private static JobStatus paused(PauseReason reason) {
        return new JobStatus("j1", JobType.EXPORT, "wiki", "OPS", "local", JobState.PAUSED, new JobProgress(9, 4, 4, 0),
                null, false, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null, Map.of(), false, false, reason);
    }

    private static ExportDatasetInfo dataset(ExportDatasetInfo.Media media) {
        return new ExportDatasetInfo("j1", "wiki", "OPS", 3, true, ExportDatasetInfo.Images.NOT_COLLECTED,
                List.of("json"), media);
    }

    /** @param pausing laufend mit angefordertem Anhalten (cancelRequested + pauseRequested) */
    private static JobStatus job(JobType type, JobState state, boolean pausing) {
        return new JobStatus("j1", type, "wiki", "OPS", "local", state, new JobProgress(0, 0, 0, 0),
                state == JobState.FAILED ? new JobError("X", "kaputt") : null, pausing, Instant.EPOCH, null, null,
                null, null, Map.of(), false, pausing, null);
    }
}
