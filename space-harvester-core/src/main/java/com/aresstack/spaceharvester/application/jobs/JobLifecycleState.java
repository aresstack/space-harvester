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
 * deren Art es kennt ({@link JobType#resumable()}), lassen sich anhalten und fortsetzen; gelöscht werden nur beendete
 * Aufträge.
 */
public sealed interface JobLifecycleState {

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
        // Nur Downloads laden gezielt nach oder erzwingen einen neuen Stand (ADR 0025).
        boolean download = status.type() == JobType.EXPORT;
        return switch (status.state()) {
            case QUEUED -> status.cancelRequested() ? new Stopping(status.pauseRequested()) : new Waiting(resumable);
            case RUNNING -> status.cancelRequested() ? new Stopping(status.pauseRequested()) : new Running(resumable);
            case PAUSED -> new Paused(status.pauseReason());
            case COMPLETED -> status.incomplete() || status.progress().failed() > 0
                    ? new CompletedWithErrors(download) : new Completed(download);
            case FAILED, CANCELLED -> new Stopped(status.state(), resumable, download);
        };
    }

    /** Wartet auf einen freien Platz. */
    record Waiting(boolean resumable) implements JobLifecycleState {
        @Override
        public String key() {
            return "WAITING";
        }

        @Override
        public List<JobAction> actions() {
            return List.of(JobAction.START_NOW, JobAction.CANCEL);
        }
    }

    /** Läuft. */
    record Running(boolean resumable) implements JobLifecycleState {
        @Override
        public String key() {
            return "RUNNING";
        }

        @Override
        public List<JobAction> actions() {
            return resumable ? List.of(JobAction.PAUSE, JobAction.CANCEL) : List.of(JobAction.CANCEL);
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
            return pausing ? List.of(JobAction.CANCEL) : List.of();
        }
    }

    /**
     * Angehalten; Geladenes bleibt. Hat sich der Auftrag selbst angehalten ({@link PauseReason#SIGN_IN_REQUIRED},
     * {@link PauseReason#CONNECTION_LOST}), nennt der Schlüssel den Grund, damit die Oberfläche ihn zeigt.
     */
    record Paused(PauseReason reason) implements JobLifecycleState {
        @Override
        public String key() {
            return reason == null || reason == PauseReason.USER ? "PAUSED" : "PAUSED_" + reason.name();
        }

        @Override
        public List<JobAction> actions() {
            return List.of(JobAction.RESUME, JobAction.DELETE);
        }
    }

    /**
     * Fertig, und nichts fehlt. Ein Download bietet „Neu erzwingen“ an ({@link JobAction#FORCE_REFRESH}).
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
     * Technisch fertig, aber Seiten oder Bilder fehlen. Ein Download bietet „Nur Fehler neu versuchen“ und „Neu
     * erzwingen“ an ({@link JobAction#RETRY_FAILED}, {@link JobAction#FORCE_REFRESH}).
     *
     * @param retryable ob die Art gescheiterte Seiten gezielt nachladen und einen neuen Stand erzwingen kann
     */
    record CompletedWithErrors(boolean retryable) implements JobLifecycleState {

        public CompletedWithErrors() {
            this(false);
        }

        @Override
        public String key() {
            return "COMPLETED_WITH_ERRORS";
        }

        @Override
        public List<JobAction> actions() {
            return retryable ? List.of(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH, JobAction.DELETE)
                    : List.of(JobAction.DELETE);
        }
    }

    /**
     * Gescheitert oder abgebrochen. Ein Download bietet neben Fortsetzen auch „Neu erzwingen“ an.
     *
     * @param forceable ob die Art einen neuen Stand erzwingen kann
     */
    record Stopped(JobState state, boolean resumable, boolean forceable) implements JobLifecycleState {

        public Stopped(JobState state, boolean resumable) {
            this(state, resumable, false);
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
            return forceable ? List.of(JobAction.RESUME, JobAction.FORCE_REFRESH, JobAction.DELETE)
                    : List.of(JobAction.RESUME, JobAction.DELETE);
        }
    }
}
