package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.transcription.WhisperModel;
import com.aresstack.spaceharvester.app.transcription.WhisperModelInstaller;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemeinsamer Stand des Whisper-Modells für alle Stellen der App (Einstellungen › Modelle, Spracheingabe im Chat,
 * Transkript eines Videos, „In PPTX umwandeln“), nach dem Muster von {@link ParserModelSetup}: ein laufender
 * Download zeigt sich überall. Geladen wird nie von selbst, nur auf Klick oder nach der Rückfrage von
 * {@link #ensure}. Alle Methoden auf dem EDT; die Arbeit läuft über {@link Background}.
 */
public final class WhisperModelSetup {

    public static final String LABEL = "Whisper-Modell";
    public static final String INSTALL = "Laden";
    public static final String CANCEL = "Abbrechen";
    static final String ASK_TITLE = "Whisper-Modell laden";

    private static final Logger LOG = LoggerFactory.getLogger(WhisperModelSetup.class);

    /** Zustand des eingestellten Modells. */
    public enum State { NOT_INSTALLED, INSTALLING, INSTALLED }

    /**
     * Momentaufnahme für die Oberfläche.
     *
     * @param model   das eingestellte Modell, während eines Downloads das ladende
     * @param message letzter Schritt, Ergebnis oder Fehler; {@code null} ohne Meldung
     * @param failed  ob {@code message} ein Fehler ist
     * @param percent Fortschritt, {@code -1} wenn unbekannt
     */
    public record Status(WhisperModel model, State state, String message, boolean failed, int percent) {
    }

    /** Läuft Arbeit im Hintergrund; in Tests synchron. */
    interface Runner {
        <T> void run(java.util.concurrent.Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Die Rückfrage vor dem Laden; in Tests ohne Fenster. */
    interface Question {
        boolean ask(Component parent, String title, String question);
    }

    private final Supplier<Optional<WhisperModelInstaller>> installers;
    private final Supplier<WhisperModel> selected;
    private final Runner runner;
    private final Question question;
    private final List<Consumer<Status>> listeners = new ArrayList<>();
    private Status status;
    private AtomicBoolean running;
    private WhisperModelInstaller active;

    /**
     * @param installers liefert den Installer mit den aktuellen Netzwerk-Einstellungen, leer ohne Einstellungen
     * @param selected   das unter Einstellungen › Modelle gewählte Modell
     */
    public WhisperModelSetup(Supplier<Optional<WhisperModelInstaller>> installers, Supplier<WhisperModel> selected) {
        this(installers, selected, new Runner() {
            @Override
            public <T> void run(java.util.concurrent.Callable<T> work, Consumer<T> onSuccess,
                                Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        }, Dialogs::confirm);
    }

    WhisperModelSetup(Supplier<Optional<WhisperModelInstaller>> installers, Supplier<WhisperModel> selected,
                      Runner runner, Question question) {
        this.installers = Objects.requireNonNull(installers, "installers");
        this.selected = Objects.requireNonNull(selected, "selected");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.question = Objects.requireNonNull(question, "question");
        this.status = probe(null, false);
    }

    /** Meldet sofort den aktuellen Stand und danach jede Änderung. */
    public void listen(Consumer<Status> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(status);
    }

    public void unlisten(Consumer<Status> listener) {
        listeners.remove(listener);
    }

    public Status status() {
        return status;
    }

    /** Prüft erneut, ob das eingestellte Modell auf der Platte liegt (nach einer anderen Auswahl oder von Hand). */
    public void refresh() {
        if (running == null) {
            update(probe(null, false));
        }
    }

    /**
     * Führt {@code then} aus, sobald das eingestellte Modell auf diesem Rechner liegt. Fehlt es, fragt die App, ob sie
     * es von Hugging Face laden soll; nach „Ja“ lädt sie es und macht danach weiter, nach „Nein“ geschieht nichts.
     *
     * @param parent   Fenster für die Rückfrage
     * @param progress bekommt den Fortschritt des Ladens als Text
     * @param stopped  bekommt den Grund, wenn nicht geladen wurde (abgelehnt, abgebrochen, gescheitert)
     * @param then     die Aktion, die das Modell braucht
     */
    public void ensure(Component parent, Consumer<String> progress, Consumer<String> stopped, Runnable then) {
        ensure(selected.get(), parent, progress, stopped, then);
    }

    /**
     * Wie {@link #ensure(Component, Consumer, Consumer, Runnable)}, aber für ein bestimmtes Modell, etwa das in der
     * Transkriptansicht gewählte (Angelo 20.11.2026); die Einstellung bleibt, wie sie ist.
     */
    public void ensure(WhisperModel model, Component parent, Consumer<String> progress, Consumer<String> stopped,
                       Runnable then) {
        Objects.requireNonNull(model, "model");
        refresh();
        if (installed(model)) {
            then.run();
            return;
        }
        if (running != null && status.model() != model) {
            stopped.accept("Gerade lädt das Whisper-Modell „" + status.model().label() + "“; danach bitte erneut.");
            return;
        }
        if (running == null) {
            if (!question.ask(parent, ASK_TITLE, "Das Whisper-Modell „" + model.described() + "“ liegt noch nicht auf "
                    + "diesem Rechner. Wirklich jetzt von Hugging Face laden? Das ist in der Regel nur einmal nötig.")) {
                stopped.accept("Ohne Whisper-Modell nicht gestartet.");
                return;
            }
            install(model);
        }
        Consumer<Status>[] waiting = new Consumer[1];
        waiting[0] = next -> {
            if (next.state() == State.INSTALLING && running != null) {
                progress.accept(LABEL + ": " + next.message());
                return;
            }
            unlisten(waiting[0]);
            if (installed(model)) {
                then.run();
            } else {
                stopped.accept(next.message() == null ? "Whisper-Modell nicht geladen." : next.message());
            }
        };
        listen(waiting[0]);
    }

    /** Ob {@code model} auf diesem Rechner liegt. */
    public boolean installed(WhisperModel model) {
        return installer().map(installer -> installer.installed(model)).orElse(false);
    }

    /** Lädt das eingestellte Modell; nichts, wenn schon ein Download läuft oder es schon da ist. */
    public void install() {
        install(selected.get());
    }

    /**
     * Lädt {@code model}, etwa das der Spracheingabe; nichts, wenn schon ein Download läuft oder es schon da ist.
     */
    public void install(WhisperModel model) {
        if (running != null) {
            return;
        }
        Optional<WhisperModelInstaller> found = installer();
        if (found.isEmpty() || found.get().installed(model)) {
            update(probe(null, false));
            return;
        }
        WhisperModelInstaller installer = found.get();
        AtomicBoolean cancelled = new AtomicBoolean();
        running = cancelled;
        active = installer;
        update(new Status(model, State.INSTALLING, "Wird vorbereitet …", false, -1));
        runner.run(() -> {
            installer.install(model, new WhisperModelInstaller.Progress() {
                private volatile String step = "";

                @Override
                public void step(String text) {
                    step = text;
                    publish(new Status(model, State.INSTALLING, text, false, -1), cancelled);
                }

                @Override
                public void bytes(long done, long total) {
                    int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                    String text = step + " " + ParserModelSetup.megabytes(done)
                            + (total > 0 ? " von " + ParserModelSetup.megabytes(total) : "");
                    publish(new Status(model, State.INSTALLING, text.strip(), false, percent), cancelled);
                }
            }, cancelled::get);
            return Boolean.TRUE;
        }, ok -> {
            running = null;
            active = null;
            update(probe("Whisper-Modell „" + model.label() + "“ geladen.", false));
        }, error -> {
            running = null;
            active = null;
            if (error instanceof CancellationException || cancelled.get()) {
                update(probe("Laden abgebrochen; das schon Geladene bleibt und wird beim nächsten Mal fortgesetzt.",
                        false));
                return;
            }
            LOG.warn("Whisper-Modell nicht geladen ({})", error.getClass().getSimpleName());
            update(probe("Whisper-Modell nicht geladen: " + Problems.describe(error).title()
                    + (error.getMessage() == null ? "" : " – " + error.getMessage()), true));
        });
    }

    /** Bricht den laufenden Download ab. */
    public void cancel() {
        AtomicBoolean run = running;
        if (run != null) {
            run.set(true);
            WhisperModelInstaller installer = active;
            if (installer != null) {
                // Schließt den Datenstrom aus dem Hintergrund, damit auch ein hängender Download endet.
                Background.run(() -> {
                    installer.abort();
                    return Boolean.TRUE;
                }, ok -> { }, error -> { });
            }
            update(new Status(status.model(), State.INSTALLING, "Wird abgebrochen …", false, -1));
        }
    }

    private void publish(Status next, AtomicBoolean run) {
        SwingUtilities.invokeLater(() -> {
            if (running == run && !run.get()) {
                update(next);
            }
        });
    }

    private Optional<WhisperModelInstaller> installer() {
        try {
            return installers.get();
        } catch (RuntimeException e) {
            LOG.warn("Whisper-Installer nicht verfügbar ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Status probe(String message, boolean failed) {
        WhisperModel model = selected.get();
        boolean installed = installer().map(installer -> installer.installed(model)).orElse(false);
        return new Status(model, installed ? State.INSTALLED : State.NOT_INSTALLED, message, failed,
                installed ? 100 : -1);
    }

    private void update(Status next) {
        status = next;
        new ArrayList<>(listeners).forEach(listener -> listener.accept(next));
    }
}
