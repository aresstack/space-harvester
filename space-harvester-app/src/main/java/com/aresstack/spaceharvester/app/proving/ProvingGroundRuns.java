package com.aresstack.spaceharvester.app.proving;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.recorder.Recorder;
import com.aresstack.spaceharvester.provingground.recorder.RecorderOptions;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;
import com.aresstack.spaceharvester.provingground.run.Credentials;
import com.aresstack.spaceharvester.provingground.run.ProjectRunResult;
import com.aresstack.spaceharvester.provingground.player.JUnitExport;
import com.aresstack.spaceharvester.provingground.run.ProjectRunner;
import com.aresstack.spaceharvester.provingground.run.RunOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Testläufe und Aufnahmen des Proving Ground: eins nach dem anderen in einem eigenen Thread (Playwright Java will einen Thread je
 * Verbindung), jeder in einem frischen Browser mit Wegwerfprofil über die Browser-Laufzeit, mit dem Browser aus
 * Einstellungen › Browser (Vorgabe: der installierte Firefox). Abbrechen trennt den Browser
 * ({@link RunningBrowser#abandon()}, gedacht für einen anderen Thread); der Lauf endet dann mit Fehlern an den offenen
 * Schritten.
 */
public final class ProvingGroundRuns implements AutoCloseable {

    /** Wie ein Lauf gestartet wird: Browser und Einstellungen, ausgewertet erst beim Start. */
    public record Setup(String channel, String executable, Path dataDirectory, Duration browserStart,
                        Duration actionTimeout, boolean headless) {
    }

    private final ExecutorService thread = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "proving-ground-run");
        worker.setDaemon(true);
        return worker;
    });
    private final AtomicReference<RunningBrowser> running = new AtomicReference<>();
    /** Playwright of the exported JUnit test that runs now: Abbrechen schließt es. */
    private final AtomicReference<AutoCloseable> junitRunning = new AtomicReference<>();
    private volatile boolean busy;

    public boolean busy() {
        return busy;
    }

    /**
     * Startet einen Lauf von {@code project} (Dateien in {@code directory}); {@code only} leer = alles. Ergebnis oder
     * Fehler kommen im Lauf-Thread.
     */
    public void run(TestProject project, Path directory, List<String> only, Supplier<Setup> setup,
                    Supplier<Credentials> credentials, Path outputDirectory, Consumer<ProjectRunResult> done,
                    Consumer<String> failed) {
        run(project, directory, only, setup, credentials, outputDirectory, null, done, failed);
    }

    /**
     * Wie {@link #run(TestProject, Path, List, Supplier, Supplier, Path, Consumer, Consumer)}; mit {@code junit} läuft
     * jeder Testfall als sein exportierter JUnit-Test (startet seinen Browser selbst), Benutzer, Passwörter und
     * Einmalcodes übergibt Space Harvester aus {@code credentials} (KeePass).
     */
    public void run(TestProject project, Path directory, List<String> only, Supplier<Setup> setup,
                    Supplier<Credentials> credentials, Path outputDirectory, JUnitExport.Launch junit,
                    Consumer<ProjectRunResult> done, Consumer<String> failed) {
        if (busy) {
            failed.accept("Es läuft schon ein Test");
            return;
        }
        busy = true;
        thread.execute(() -> {
            try {
                Setup current = setup.get();
                if (junit != null) {
                    try {
                        done.accept(new ProjectRunner().run(project, directory, user -> {
                            throw new IllegalStateException("Der JUnit-Test startet seinen Browser selbst");
                        }, credentials.get(), RunOptions.defaults().withOnly(only)
                                .withActionTimeout(current.actionTimeout()).withOutputDirectory(outputDirectory)
                                .withJunit(junit, junitRunning::set)));
                    } finally {
                        junitRunning.set(null);
                    }
                    return;
                }
                BrowserRuntime runtime = new BrowserRuntime(BrowserRuntimeConfig.of(BrowserChannel.of(current.channel()),
                        current.executable(), current.dataDirectory(), current.browserStart()));
                try (RunningBrowser browser = runtime.ephemeral(current.headless(), null, false)) {
                    running.set(browser);
                    ProjectRunResult result = new ProjectRunner().run(project, directory,
                            user -> browser.browser().newContext(), credentials.get(),
                            RunOptions.defaults().withOnly(only).withActionTimeout(current.actionTimeout())
                                    .withOutputDirectory(outputDirectory));
                    done.accept(result);
                } finally {
                    running.set(null);
                }
            } catch (Exception | LinkageError broken) {
                failed.accept(broken.getMessage() == null ? broken.toString() : broken.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    /** Eine laufende Aufnahme: Pause und Ende kommen aus der Oberfläche, die Arbeit geschieht im Lauf-Thread. */
    public static final class Recording {
        private volatile boolean stopRequested;
        private volatile Recorder recorder;
        private volatile boolean paused;

        /** Nimmt bis {@link #resume()} nichts auf. */
        public void pause() {
            paused = true;
            Recorder current = recorder;
            if (current != null) {
                current.pause();
            }
        }

        public void resume() {
            paused = false;
            Recorder current = recorder;
            if (current != null) {
                current.resume();
            }
        }

        public boolean paused() {
            return paused;
        }

        /** Beendet die Aufnahme; der Browser schließt, die Schritte bleiben. */
        public void stop() {
            stopRequested = true;
        }
    }

    /**
     * Startet eine Aufnahme mit dem originalen Playwright-Recorder in einem sichtbaren Browser; die Schritte kommen an
     * {@code target} hinzu. {@code steps} erhält nach jeder Änderung den ganzen Stand, {@code done} das Ergebnis, wenn
     * die Aufnahme endet ({@link Recording#stop()} oder der Benutzer schließt das Browserfenster); beides im
     * Lauf-Thread.
     */
    public Recording record(TestCase target, Supplier<Setup> setup, Consumer<List<TestStep>> steps,
                            Consumer<TestCase> done, Consumer<String> failed) {
        return record(target, null, setup, steps, done, failed);
    }

    /**
     * Wie oben; mit {@code startUrl} öffnet der Browser gleich diese Adresse (Playwright nimmt sie als Schritt
     * „navigate“ auf), sonst eine leere Seite, in der man die Adresse selbst eingibt.
     */
    public Recording record(TestCase target, String startUrl, Supplier<Setup> setup, Consumer<List<TestStep>> steps,
                            Consumer<TestCase> done, Consumer<String> failed) {
        Recording recording = new Recording();
        if (busy) {
            failed.accept("Es läuft schon ein Test oder eine Aufnahme");
            return recording;
        }
        busy = true;
        thread.execute(() -> {
            try {
                Setup current = setup.get();
                BrowserRuntime runtime = new BrowserRuntime(BrowserRuntimeConfig.of(BrowserChannel.of(current.channel()),
                        current.executable(), current.dataDirectory(), current.browserStart()));
                try (RunningBrowser browser = runtime.ephemeral(false, null, false)) {
                    running.set(browser);
                    BrowserContext context = browser.browser().newContext();
                    try (Recorder recorder = Recorder.start(context, target, RecorderOptions.defaults())) {
                        recording.recorder = recorder;
                        if (recording.paused) {
                            recorder.pause();
                        }
                        recorder.onStep(step -> steps.accept(List.copyOf(target.steps())));
                        com.microsoft.playwright.Page page = context.newPage();
                        if (startUrl != null && !startUrl.isBlank()) {
                            try {
                                page.navigate(startUrl.strip());
                            } catch (PlaywrightException unreachable) {
                                // Adresse falsch oder langsam: die Aufnahme läuft weiter, die Adresse lässt sich im
                                // Browser korrigieren, Stopp speichert, was bis dahin aufgenommen ist
                            }
                        }
                        try {
                            recorder.pump(() -> recording.stopRequested || context.pages().isEmpty());
                        } catch (PlaywrightException browserClosed) {
                            // der Benutzer hat den Browser geschlossen: die Schritte bis dahin bleiben
                        }
                    }
                    done.accept(target);
                } finally {
                    running.set(null);
                }
            } catch (Exception | LinkageError broken) {
                failed.accept(broken.getMessage() == null ? broken.toString() : broken.getMessage());
            } finally {
                busy = false;
            }
        });
        return recording;
    }

    /** Bricht den laufenden Test ab (trennt seinen Browser); kehrt sofort zurück. */
    public void stop() {
        RunningBrowser browser = running.getAndSet(null);
        if (browser != null) {
            browser.abandon();
        }
        AutoCloseable junit = junitRunning.getAndSet(null);
        if (junit != null) {
            // like RunningBrowser.abandon: from another thread, the test then ends with an error at its open step
            Thread cutter = new Thread(() -> {
                try {
                    junit.close();
                } catch (Exception ignored) {
                    // already gone
                }
            }, "proving-ground-junit-abandon");
            cutter.setDaemon(true);
            cutter.start();
        }
    }

    @Override
    public void close() {
        stop();
        thread.shutdownNow();
    }
}
