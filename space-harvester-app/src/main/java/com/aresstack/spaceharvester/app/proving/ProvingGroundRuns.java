package com.aresstack.spaceharvester.app.proving;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.run.Credentials;
import com.aresstack.spaceharvester.provingground.run.ProjectRunResult;
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
 * Testläufe des Proving Ground: einer nach dem anderen in einem eigenen Thread (Playwright Java will einen Thread je
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
        if (busy) {
            failed.accept("Es läuft schon ein Test");
            return;
        }
        busy = true;
        thread.execute(() -> {
            try {
                Setup current = setup.get();
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

    /** Bricht den laufenden Test ab (trennt seinen Browser); kehrt sofort zurück. */
    public void stop() {
        RunningBrowser browser = running.getAndSet(null);
        if (browser != null) {
            browser.abandon();
        }
    }

    @Override
    public void close() {
        stop();
        thread.shutdownNow();
    }
}
