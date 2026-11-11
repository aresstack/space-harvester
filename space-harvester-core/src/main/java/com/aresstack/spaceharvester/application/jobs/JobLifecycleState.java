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
 * folgt es direkt nach dem Durchgang, angehalten oder abgebrochen holt Fortsetzen das Gescheiterte mit.
 */
public sealed interface JobLifecycleState {

    /** Fehlercode eines Abgleichs mit bestätigter Generation, aber nicht geladenen Seiten. */
    String SYNC_PARTIAL = "SYNC_PARTIAL";
    /** Angabe der Betriebsart eines Abgleichs in {@link JobStatus#parameters()}. */
    String SYNC_MODE = "mode";
    /** Betriebsart „nur katalogisieren“ eines Abgleichs. */
    String CATALOG_ONLY = "CATALOG_ONLY";

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
        return switch (status.state()) {
            case QUEUED -> status.cancelRequested() ? new Stopping(status.pauseRequested()) : new Waiting(resumable);
            case RUNNING -> status.cancelRequested() ? new Stopping(status.pauseRequested())
                    : new Running(resumable, failures);
            case PAUSED -> new Paused(status.pauseReason(), failures);
            case COMPLETED -> status.incomplete() || status.progress().failed() > 0
                    ? new CompletedWithErrors(download || files, download || files) : new Completed(forceable);
            // Ein Abgleich, der seine Generation bestätigt hat, aber Seiten nicht laden konnte, ist fertig mit Fehlern.
            case FAILED -> sync && status.error() != null && SYNC_PARTIAL.equals(status.error().code())
                    ? new CompletedWithErrors(true, true)
                    : new Stopped(status.state(), resumable, forceable, failures && resumable);
            case CANCELLED -> new Stopped(status.state(), resumable, forceable, failures && resumable);
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
            return List.of(JobAction.START_NOW, JobAction.CANCEL, JobAction.DELETE);
        }
    }

    /**
     * Läuft.
     *
     * @param retryable ob schon etwas gescheitert ist und die Art es gezielt nachladen kann; „Nur Fehler neu versuchen“
     *                  folgt dann direkt nach dem Durchgang ({@link Jobs#requestRetryFailed})
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
            return reason == null || reason == PauseReason.USER ? "PAUSED" : "PAUSED_" + reason.name();
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
