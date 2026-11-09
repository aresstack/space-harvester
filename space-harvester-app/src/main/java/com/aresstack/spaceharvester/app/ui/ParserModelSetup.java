package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.nlp.ParserModelInstaller;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
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
 * Gemeinsamer Stand der Parsermodell-Installation für alle Stellen der App (Assistent Schritt „Modelle“,
 * Einstellungen › Modelle, Reiter Analysen › Aussagen): eine laufende Installation zeigt sich überall, ein Erfolg
 * blendet überall die Hinweise aus. Installiert nur auf Klick, nie beim Start. Alle Methoden auf dem EDT; die
 * Arbeit läuft über {@link Background}, Meldungen kommen auf dem EDT.
 */
public final class ParserModelSetup {

    /** Text für Zeilen und Knöpfe. */
    public static final String LABEL = "Parsermodell";
    public static final String INSTALL = "Installieren";
    public static final String INSTALL_NOW = "Jetzt installieren";
    public static final String CANCEL = "Abbrechen";
    public static final String INSTALLED = "Installiert (MaltParser, deutsch)";
    public static final String NOT_INSTALLED = "Nicht installiert";

    private static final Logger LOG = LoggerFactory.getLogger(ParserModelSetup.class);

    /** Zustand der Installation. */
    public enum State { NOT_INSTALLED, INSTALLING, INSTALLED, UNAVAILABLE }

    /** Momentaufnahme für die Oberfläche; {@code message} ist der letzte Schritt oder Fehler, {@code failed} ob Fehler. */
    public record Status(State state, String message, boolean failed, int percent) {
    }

    /** Läuft Arbeit im Hintergrund; in Tests synchron. */
    interface Runner {
        <T> void run(java.util.concurrent.Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private final Supplier<Optional<ParserModelInstaller>> installers;
    private final Runner runner;
    private final List<Consumer<Status>> listeners = new ArrayList<>();
    private Status status;
    private AtomicBoolean running;
    private ParserModelInstaller active;

    /** @param installers liefert den Installer mit den aktuellen Einstellungen, leer ohne hinterlegtes Modell */
    public ParserModelSetup(Supplier<Optional<ParserModelInstaller>> installers) {
        this(installers, new Runner() {
            @Override
            public <T> void run(java.util.concurrent.Callable<T> work, Consumer<T> onSuccess,
                                Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        });
    }

    ParserModelSetup(Supplier<Optional<ParserModelInstaller>> installers, Runner runner) {
        this.installers = Objects.requireNonNull(installers, "installers");
        this.runner = Objects.requireNonNull(runner, "runner");
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

    /** Prüft erneut, ob das Modell auf der Platte liegt (etwa nach manueller Installation). */
    public void refresh() {
        if (running == null) {
            update(probe(null, false));
        }
    }

    /** Die Release-Seite zum manuellen Herunterladen, sofern ein Modell hinterlegt ist. */
    public Optional<String> releasePage() {
        return installer().map(installer -> installer.release().page());
    }

    /** Wohin die ZIP manuell entpackt wird. */
    public Optional<Path> directory() {
        return installer().map(ParserModelInstaller::directory);
    }

    public String zipName() {
        return installer().map(installer -> installer.release().asset()).orElse("");
    }

    /** Startet die Installation; nichts, wenn schon eine läuft oder kein Modell hinterlegt ist. */
    public void install() {
        if (running != null) {
            return;
        }
        Optional<ParserModelInstaller> found = installer();
        if (found.isEmpty()) {
            update(probe(null, false));
            return;
        }
        ParserModelInstaller installer = found.get();
        AtomicBoolean cancelled = new AtomicBoolean();
        running = cancelled;
        active = installer;
        update(new Status(State.INSTALLING, "Wird vorbereitet …", false, -1));
        runner.run(() -> {
            installer.install(new ParserModelInstaller.Progress() {
                private volatile String step = "";

                @Override
                public void step(String text) {
                    step = text;
                    publish(new Status(State.INSTALLING, text, false, -1), cancelled);
                }

                @Override
                public void bytes(long done, long total) {
                    int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                    String text = step + " " + megabytes(done) + (total > 0 ? " von " + megabytes(total) : "");
                    publish(new Status(State.INSTALLING, text.strip(), false, percent), cancelled);
                }
            }, cancelled::get);
            return Boolean.TRUE;
        }, ok -> {
            running = null;
            active = null;
            update(probe("Parsermodell installiert; „Mit MaltParser analysieren“ ist bereit.", false));
        }, error -> {
            running = null;
            active = null;
            if (error instanceof CancellationException || cancelled.get()) {
                update(probe("Installation abgebrochen; nichts geändert.", false));
                return;
            }
            LOG.warn("Parsermodell-Installation fehlgeschlagen ({})", error.getClass().getSimpleName());
            update(probe("Installation fehlgeschlagen: " + Problems.describe(error).title()
                    + (error.getMessage() == null ? "" : " – " + error.getMessage()), true));
        });
    }

    /** Bricht die laufende Installation ab. */
    public void cancel() {
        AtomicBoolean run = running;
        if (run != null) {
            run.set(true);
            ParserModelInstaller installer = active;
            if (installer != null) {
                // Schließt den Datenstrom aus dem Hintergrund, damit auch ein hängender Download endet.
                Background.run(() -> {
                    installer.abort();
                    return Boolean.TRUE;
                }, ok -> { }, error -> { });
            }
            update(new Status(State.INSTALLING, "Wird abgebrochen …", false, -1));
        }
    }

    private void publish(Status next, AtomicBoolean run) {
        SwingUtilities.invokeLater(() -> {
            if (running == run && !run.get()) {
                update(next);
            }
        });
    }

    private Optional<ParserModelInstaller> installer() {
        try {
            return installers.get();
        } catch (RuntimeException e) {
            LOG.warn("Parsermodell-Installer nicht verfügbar ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Status probe(String message, boolean failed) {
        Optional<ParserModelInstaller> found = installer();
        if (found.isEmpty()) {
            return new Status(State.UNAVAILABLE, "In dieser Installation ist kein Parsermodell hinterlegt.", false,
                    -1);
        }
        boolean installed = found.get().installed();
        return new Status(installed ? State.INSTALLED : State.NOT_INSTALLED, message, failed, installed ? 100 : -1);
    }

    private void update(Status next) {
        status = next;
        new ArrayList<>(listeners).forEach(listener -> listener.accept(next));
    }

    static String megabytes(long bytes) {
        return String.format(java.util.Locale.GERMANY, "%.1f MB", bytes / 1_048_576d);
    }
}
