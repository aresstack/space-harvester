package com.aresstack.spaceharvester.application.jobs;

import java.util.List;
import java.util.Objects;

/**
 * Wirksamer Zustand eines Auftrags als Zustandsobjekt: Jeder Zustand legt selbst fest, welche {@link JobAction}s er
 * anbietet, in der Reihenfolge, in der die Oberfläche sie zeigt. Er verbindet den technischen
 * {@link JobState} mit dem Ergebnis ({@link JobStatus#incomplete()}, {@link JobProgress#failed()}): „Fertig“ heißt nur
 * fertig, wenn nichts fehlt.
 *
 * <p>Regeln: Anhalten und Fortsetzen erscheinen nie gleichzeitig; vor dem Start steht „Jetzt starten“; nur Aufträge,
 * deren Art es kennt ({@link JobType#resumable()}), lassen sich anhalten und fortsetzen; Löschen gibt es in jedem
 * Zustand, als letzte Aktion (wartende und laufende bricht {@link Jobs#delete} vorher ab); „Nur Fehler neu versuchen“
 * gibt es in jedem Zustand, sobald etwas gescheitert ist, als erste Aktion (Angelo 11.11.2026): während des Laufs
 * hält der Durchgang an und startet sofort neu ({@link Restarting}, Angelo 12.11.2026), angehalten oder abgebrochen
 * holt Fortsetzen das Gescheiterte mit.
 */
public sealed interface JobLifecycleState {

    /** Fehlercode eines Abgleichs mit bestätigter Generation, aber nicht geladenen Seiten. */
    String SYNC_PARTIAL = "SYNC_PARTIAL";
    /** Angabe der Betriebsart eines Abgleichs in {@link JobStatus#parameters()}. */
    String SYNC_MODE = "mode";
    /** Betriebsart „nur katalogisieren“ eines Abgleichs. */
    String CATALOG_ONLY = "CATALOG_ONLY";
    /**
     * Angabe in {@link JobStatus#parameters()} ({@code "true"}): Der Auftrag liest nur den Bestand, nie die Quelle
     * (ADR 0029). Er hat keinen neuen Stand zu erzwingen; „Nur Fehler neu versuchen“ bleibt.
     */
    String HOLDINGS = "holdings";

    /** Stabiler Schlüssel des Zustands, etwa für Texte und REST, z. B. {@code WAITING}. */
    String key();

    /** Erlaubte Aktionen in Anzeigereihenfolge. */
    List<JobAction> actions();

    /** Ob {@code action} in diesem Zustand erlaubt ist. */
    default boolean allows(JobAction action) {
        return actions().contains(action);
    }

    /** Der wirksame Zustand eines Auftrags. */
    static JobLifecycleState of(JobStatus status) {
        Objects.requireNonNull(status, "status");
        boolean resumable = status.type().resumable();
        // Downloads und Abgleiche laden gezielt nach oder erzwingen einen neuen Stand (ADR 0025); ein reiner
        // Katalogabgleich lädt keine Inhalte und hat nichts zu erzwingen.
        boolean download = status.type() == JobType.EXPORT;
        boolean sync = status.type() == JobType.SYNC;
        // Bilder und Anhänge holen beim erneuten Lauf nur, was fehlt; abgelegte Dateien kommen aus dem Speicher.
        boolean files = status.type() == JobType.ASSETS || status.type() == JobType.ATTACHMENTS;
        boolean forceable = download || files || sync && !CATALOG_ONLY.equals(status.parameter(SYNC_MODE));
        // Gescheitertes gezielt nachladen geht, sobald etwas gescheitert ist, in jedem Zustand (Angelo 11.11.2026).
        boolean failures = forceable && status.progress().failed() > 0;
        // Eine Ausgabe aus dem Bestand und ihre Bilder und Anhänge fragen die Quelle nie (ADR 0029).
        boolean refreshable = forceable && !Boolean.parseBoolean(status.parameter(HOLDINGS));
        return switch (status.state()) {
            case QUEUED -> status.cancelRequested() ? stopping(status)
                    : status.stage() == JobStage.AWAITING ? new AwaitingExport() : new Waiting(resumable);
            case RUNNING -> status.cancelRequested() ? stopping(status)
                    : status.stage() == JobStage.PREPARING ? new Preparing(failures)
                    : status.stage() == JobStage.PREPARED ? new Prepared()
                    : new Running(resumable, failures);
            case PAUSED -> new Paused(status.pauseReason(), failures);
            case COMPLETED -> status.incomplete() || status.progress().failed() > 0
                    ? new CompletedWithErrors(download || files, (download || files) && refreshable)
                    : new Completed(refreshable);
            // Ein Abgleich, der seine Generation bestätigt hat, aber Seiten nicht laden konnte, ist fertig mit Fehlern.
            case FAILED -> sync && status.error() != null && SYNC_PARTIAL.equals(status.error().code())
                    ? new CompletedWithErrors(true, true)
                    : new Stopped(status.state(), resumable, refreshable, failures && resumable);
            case CANCELLED -> new Stopped(status.state(), resumable, refreshable, failures && resumable);
        };
    }

    /** Hält an, bricht ab oder hält für einen sofortigen Neustart an ({@link PauseReason#RETRY}). */
    private static JobLifecycleState stopping(JobStatus status) {
        return status.pauseRequested() && status.pauseReason() == PauseReason.RETRY ? new Restarting()
                : new Stopping(status.pauseRequested());
    }

    /** Wartet auf einen freien Platz. */
    record Waiting(boolean resumable) implements JobLifecycleState {
        @Override
        public String key() {
            return "WAITING";
        }

        @Override
        public List<JobAction> actions() {
            return List.of(JobAction.START_NOW, JobAction.CANCEL, JobAction.DELETE);
        }
    }

    /**
     * Bilder oder Anhänge sind schon mit dem Export angelegt und warten, bis sein Text veröffentlicht ist (Angelo
     * 11.11.2026). Löschen heißt: nur den Text, still und ohne Fehler.
     */
    record AwaitingExport() implements JobLifecycleState {
        @Override
        public String key() {
            return "AWAITING_EXPORT";
        }

        @Override
        public List<JobAction> actions() {
            return List.of(JobAction.DELETE);
        }
    }

    /**
     * Läuft.
     *
     * @param retryable ob schon etwas gescheitert ist und die Art es gezielt nachladen kann; „Nur Fehler neu versuchen“
     *                  hält den Durchgang dann an und startet ihn sofort neu ({@link Jobs#requestRetryFailed})
     */
    record Running(boolean resumable, boolean retryable) implements JobLifecycleState {

        public Running(boolean resumable) {
            this(resumable, false);
        }

        @Override
        public String key() {
            return "RUNNING";
        }

        @Override
        public List<JobAction> actions() {
            List<JobAction> actions = new java.util.ArrayList<>(4);
            if (retryable) {
                actions.add(JobAction.RETRY_FAILED);
            }
            if (resumable) {
                actions.add(JobAction.PAUSE);
            }
            actions.add(JobAction.CANCEL);
            actions.add(JobAction.DELETE);
            return List.copyOf(actions);
        }
    }

    /**
     * Ein Download ({@link JobType#download()}) bereitet vor: zählt die Verweise, lädt noch nichts (Angelo 11.11.2026).
     * Lässt sich wie ein laufender anhalten.
     *
     * @param retryable wie bei {@link Running}
     */
    record Preparing(boolean retryable) implements JobLifecycleState {
        @Override
        public String key() {
            return "PREPARING";
        }

        @Override
        public List<JobAction> actions() {
            return new Running(true, retryable).actions();
        }
    }

    /**
     * Ein Download ist vorbereitet und wartet auf einen Download-Platz ({@link Jobs#parallelDownloads()}); „Jetzt
     * starten“ gibt ihm den nächsten freien.
     */
    record Prepared() implements JobLifecycleState {
        @Override
        public String key() {
            return "PREPARED";
        }

        @Override
        public List<JobAction> actions() {
            return List.of(JobAction.START_NOW, JobAction.CANCEL, JobAction.DELETE);
        }
    }

    /**
     * „Nur Fehler neu versuchen“ während des Laufs: der Durchgang beendet noch seinen Schritt und startet dann sofort
     * im selben Eintrag neu (Angelo 12.11.2026). Bis dahin lässt er sich noch anhalten (dann ohne Neustart) oder
     * abbrechen; weiteres ↻ löst keinen zweiten Neustart aus und wird daher nicht angeboten.
     */
    record Restarting() implements JobLifecycleState {
        @Override
        public String key() {
            return "RESTARTING";
        }

        @Override
        public List<JobAction> actions() {
            return List.of(JobAction.PAUSE, JobAction.CANCEL, JobAction.DELETE);
        }
    }

    /**
     * Anhalten oder Abbrechen ist angefordert, der Auftrag beendet noch seinen Schritt. Ein angefordertes Anhalten
     * lässt sich noch in einen Abbruch verwandeln, nie umgekehrt.
     */
    record Stopping(boolean pausing) implements JobLifecycleState {
        @Override
        public String key() {
            return pausing ? "PAUSE_REQUESTED" : "CANCEL_REQUESTED";
        }

        @Override
        public List<JobAction> actions() {
            return pausing ? List.of(JobAction.CANCEL, JobAction.DELETE) : List.of(JobAction.DELETE);
        }
    }

    /**
     * Angehalten; Geladenes bleibt. Hat sich der Auftrag selbst angehalten ({@link PauseReason#SIGN_IN_REQUIRED},
     * {@link PauseReason#CONNECTION_LOST}), nennt der Schlüssel den Grund, damit die Oberfläche ihn zeigt.
     *
     * @param retryable ob etwas gescheitert ist; „Nur Fehler neu versuchen“ setzt dann fort, wie Fortsetzen, das das
     *                  Gescheiterte mitholt
     */
    record Paused(PauseReason reason, boolean retryable) implements JobLifecycleState {

        public Paused(PauseReason reason) {
            this(reason, false);
        }

        @Override
        public String key() {
            return reason == null || reason == PauseReason.USER || reason == PauseReason.RETRY ? "PAUSED"
                    : "PAUSED_" + reason.name();
        }

        @Override
        public List<JobAction> actions() {
            return retryable ? List.of(JobAction.RETRY_FAILED, JobAction.RESUME, JobAction.DELETE)
                    : List.of(JobAction.RESUME, JobAction.DELETE);
        }
    }

    /**
     * Fertig, und nichts fehlt. Downloads und Abgleiche bieten „Neu erzwingen“ an ({@link JobAction#FORCE_REFRESH}).
     *
     * @param forceable ob die Art einen neuen Stand erzwingen kann
     */
    record Completed(boolean forceable) implements JobLifecycleState {

        public Completed() {
            this(false);
        }

        @Override
        public String key() {
            return "COMPLETED";
        }

        @Override
        public List<JobAction> actions() {
            return forceable ? List.of(JobAction.FORCE_REFRESH, JobAction.DELETE) : List.of(JobAction.DELETE);
        }
    }

    /**
     * Technisch fertig, aber Seiten oder Bilder fehlen. Downloads und Abgleiche bieten „Nur Fehler neu versuchen“ und
     * „Neu erzwingen“ an ({@link JobAction#RETRY_FAILED}, {@link JobAction#FORCE_REFRESH}). Bei einem Abgleich ist das
     * der Fehlschlag {@link #SYNC_PARTIAL}: Die Generation ist bestätigt, nur einzelne Seiten fehlen.
     *
     * @param retryable ob die Art Gescheitertes gezielt nachladen kann (auch Bilder und Anhänge)
     * @param forceable ob die Art einen neuen Stand erzwingen kann
     */
    record CompletedWithErrors(boolean retryable, boolean forceable) implements JobLifecycleState {

        public CompletedWithErrors() {
            this(false, false);
        }

        public CompletedWithErrors(boolean retryable) {
            this(retryable, retryable);
        }

        @Override
        public String key() {
            return "COMPLETED_WITH_ERRORS";
        }

        @Override
        public List<JobAction> actions() {
            List<JobAction> actions = new java.util.ArrayList<>(3);
            if (retryable) {
                actions.add(JobAction.RETRY_FAILED);
            }
            if (forceable) {
                actions.add(JobAction.FORCE_REFRESH);
            }
            actions.add(JobAction.DELETE);
            return List.copyOf(actions);
        }
    }

    /**
     * Gescheitert oder abgebrochen. Downloads und Abgleiche bieten neben Fortsetzen auch „Neu erzwingen“ an.
     *
     * @param forceable ob die Art einen neuen Stand erzwingen kann
     * @param retryable ob etwas gescheitert ist; „Nur Fehler neu versuchen“ setzt dann fort und holt es mit
     */
    record Stopped(JobState state, boolean resumable, boolean forceable, boolean retryable)
            implements JobLifecycleState {

        public Stopped(JobState state, boolean resumable) {
            this(state, resumable, false, false);
        }

        public Stopped(JobState state, boolean resumable, boolean forceable) {
            this(state, resumable, forceable, false);
        }

        public Stopped {
            if (state != JobState.FAILED && state != JobState.CANCELLED) {
                throw new IllegalArgumentException("nur FAILED oder CANCELLED");
            }
        }

        @Override
        public String key() {
            return state.name();
        }

        @Override
        public List<JobAction> actions() {
            if (!resumable) {
                return List.of(JobAction.DELETE);
            }
            List<JobAction> actions = new java.util.ArrayList<>(4);
            if (retryable) {
                actions.add(JobAction.RETRY_FAILED);
            }
            actions.add(JobAction.RESUME);
            if (forceable) {
                actions.add(JobAction.FORCE_REFRESH);
            }
            actions.add(JobAction.DELETE);
            return List.copyOf(actions);
        }
    }
}
