package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Gemeinsamer Zustand der Oberfläche: die laufende Laufzeit (nach „Neu laden“ eine neue), die Geheimnisse dieser
 * Sitzung, der geprüfte Verbindungsstand je Quelle und die Navigation zwischen den Bereichen. Wird nur auf dem EDT
 * verändert; Laden und Starten laufen über {@link Background}.
 */
public final class AppContext {

    /** Die Bereiche der Hauptnavigation. */
    public enum Section {
        // Reihenfolge der Seitenleiste; die Suche ist die Startansicht.
        SEARCH("Suche"), SOURCES("Wissensquellen"), CONTENT("Inhalte"), EXPORTS("Herunterladen"),
        ACTIVITIES("Aufträge"), STATUS("Status"), SETTINGS("Einstellungen");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Navigation; die Hauptansicht setzt sie. */
    public interface Navigator {
        void show(Section section);

        void openPage(String sourceId, String pageId);

        void openSpace(String sourceId, String spaceKey);

        void showJob(String jobId);

        /** Status mit der Diagnose genau dieses Auftrags. */
        void showDiagnosis(String jobId);

        /** Herunterladen mit Quelle und diesen Spaces vorbelegt; gestartet wird erst dort. */
        void prepareExport(String sourceId, java.util.List<String> spaceKeys);

        default void prepareExport(String sourceId, String spaceKey) {
            prepareExport(sourceId, spaceKey == null ? java.util.List.of() : java.util.List.of(spaceKey));
        }

        void search(String text);
    }

    /** Geprüfter Verbindungsstand einer Quelle; „verbunden“ nur nach einer erfolgreichen Prüfung. */
    public record Connection(State state, Instant checkedAt, List<Partition> spaces, Throwable error) {

        public enum State { NOT_CHECKED, CHECKING, CONNECTED, FAILED }

        static final Connection NOT_CHECKED = new Connection(State.NOT_CHECKED, null, List.of(), null);
    }

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final AppSettingsLoader loader;
    private final Path configFile;
    private final String version;
    private final AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
    private char[] sessionAiKey;
    private final SecretVault vault;
    private final List<Consumer<AppContext>> listeners = new ArrayList<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private AppSettings settings;
    private AppRuntime runtime;
    private Throwable startProblem;
    private Navigator navigator;

    public AppContext(AppSettingsLoader loader, Path configFile, String version) {
        this(loader, configFile, version, SecretVault.forPlatform(
                configFile.toAbsolutePath().getParent().resolve("secrets")));
    }

    /** @param vault Tresor für Token und Passwörter der Quellen (unter Windows DPAPI) */
    public AppContext(AppSettingsLoader loader, Path configFile, String version, SecretVault vault) {
        this.loader = loader;
        this.configFile = configFile;
        this.version = version;
        this.vault = vault;
    }

    public SecretVault vault() {
        return vault;
    }

    public AppSettingsLoader loader() {
        return loader;
    }

    public Path configFile() {
        return configFile;
    }

    public String version() {
        return version;
    }

    public AppSettings settings() {
        return settings;
    }

    /** Die laufende Laufzeit oder {@code null}, wenn der Start scheiterte ({@link #startProblem()}). */
    public AppRuntime runtime() {
        return runtime;
    }

    public Throwable startProblem() {
        return startProblem;
    }

    public AppRuntime.SessionSecrets secrets() {
        return secrets;
    }

    /** Tresor nur für den API-Key des KI-Dienstes, getrennt von den Quellen. */
    public SecretVault aiVault() {
        return vault.scope(AiSettingsPanel.VAULT);
    }

    /** API-Key des KI-Dienstes für diese Sitzung, wenn es keinen Tresor gibt; sonst {@code null}. */
    char[] sessionAiKey() {
        return sessionAiKey == null ? null : sessionAiKey.clone();
    }

    void sessionAiKey(char[] key) {
        if (sessionAiKey != null) {
            java.util.Arrays.fill(sessionAiKey, '\0');
        }
        sessionAiKey = key == null || key.length == 0 ? null : key.clone();
    }

    /**
     * Der gespeicherte API-Key des KI-Dienstes aus Sitzung oder Tresor oder {@code null}; liest den Tresor, also nur im
     * Hintergrund. Der Aufrufer leert das Feld nach Gebrauch.
     */
    char[] storedAiKey() {
        char[] aiKey = sessionAiKey();
        try {
            if (aiKey == null && aiVault().has(AiSettingsPanel.KEY_ID, AiSettingsPanel.KEY_LOGIN)) {
                aiKey = aiVault().load(AiSettingsPanel.KEY_ID, AiSettingsPanel.KEY_LOGIN);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Der API-Key des KI-Dienstes ließ sich nicht lesen: {}", e.getMessage());
        }
        return aiKey;
    }

    /**
     * Speichert den Ausgabeordner in der Datei ({@code null} für Downloads) und übernimmt ihn ohne Neustart der
     * Laufzeit: Nur die Oberfläche legt dort Dateien ab.
     */
    public void outputDirectory(Path directory, Runnable done, Consumer<Throwable> failed) {
        Background.run(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(loader);
            String text = Files.isRegularFile(configFile)
                    ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withOutputDirectory(configFile,
                    new ConfigFileEditor.Draft(text, null), directory);
            editor.save(configFile, written, !editor.fileHasPlaintextSecret(configFile));
            return written.settings().outputDirectory();
        }, saved -> {
            if (settings != null) {
                settings = settings.withOutputDirectory(saved);
            }
            fire();
            done.run();
        }, failed);
    }

    /** Ob ein API-Key für den KI-Dienst vorliegt: im Tresor oder für diese Sitzung. */
    public boolean aiKeyAvailable() {
        return sessionAiKey != null || aiVault().has(AiSettingsPanel.KEY_ID, AiSettingsPanel.KEY_LOGIN);
    }

    public Navigator navigator() {
        return navigator;
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    /** Ansichten melden sich hier, um nach jedem (Neu-)Start ihre Daten neu zu laden. */
    public void onChange(Consumer<AppContext> listener) {
        listeners.add(listener);
    }

    public Connection connection(String sourceId) {
        return connections.getOrDefault(sourceId, Connection.NOT_CHECKED);
    }

    public void setConnection(String sourceId, Connection connection) {
        connections.put(sourceId, connection);
        fire();
    }

    /** Prüft die Verbindung einer Quelle (Spaces live laden) und merkt sich das Ergebnis. */
    public void checkConnection(String sourceId, Runnable done) {
        if (runtime == null) {
            return;
        }
        AppRuntime current = runtime;
        connections.put(sourceId, new Connection(Connection.State.CHECKING, Instant.now(), List.of(), null));
        fire();
        Background.run(() -> current.workspace().spaces(sourceId),
                spaces -> {
                    // Ergebnis einer inzwischen ersetzten Laufzeit (Neu laden) verwerfen.
                    if (runtime != current) {
                        return;
                    }
                    connections.put(sourceId, new Connection(Connection.State.CONNECTED, Instant.now(), spaces, null));
                    fire();
                    done.run();
                },
                error -> {
                    if (runtime != current) {
                        return;
                    }
                    connections.put(sourceId, new Connection(Connection.State.FAILED, Instant.now(), List.of(), error));
                    fire();
                    done.run();
                });
    }

    /**
     * Die Aufträge, die {@link #reload} abbrechen würde (wartend oder laufend); leer ohne Laufzeit oder wenn die
     * Auftragsablage nicht lesbar ist.
     */
    public List<JobStatus> runningJobs() {
        if (runtime == null) {
            return List.of();
        }
        try {
            return runtime.workspace().jobs().stream().filter(job -> !job.state().terminal()).toList();
        } catch (RuntimeException e) {
            LOG.warn("Laufende Aufträge ließen sich nicht lesen: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Liest die Konfiguration neu und startet die Laufzeit neu; laufende Aufträge werden dabei beendet. Wer neu lädt,
     * fragt vorher mit {@link Dialogs#restartAllowed} nach, damit kein Export still abbricht.
     */
    public void reload(Runnable done) {
        AppRuntime previous = runtime;
        Background.run(() -> {
            if (previous != null) {
                previous.close();
            }
            AppSettings loaded = loader.load(configFile);
            unlock(loaded);
            try {
                return new Started(loaded, AppRuntime.start(loaded, secrets, version), null);
            } catch (RuntimeException e) {
                return new Started(loaded, null, e);
            }
        }, started -> {
            settings = started.settings();
            runtime = started.runtime();
            startProblem = started.problem();
            connections.clear();
            fire();
            if (runtime != null) {
                // Verbindungen gleich prüfen, damit Spaces und Zustand ohne weiteren Klick da sind.
                runtime.workspace().sources().forEach(source -> checkConnection(source.id(), () -> { }));
            }
            done.run();
        }, error -> {
            runtime = null;
            startProblem = error;
            fire();
            done.run();
        });
    }

    /** Lädt gespeicherte Token und Passwörter aus dem Tresor in die Sitzung (im Hintergrund, vor dem Start). */
    private void unlock(AppSettings loaded) {
        // Der Core bekommt den Key nur zur Laufzeit (ADR 0011).
        secrets.aiKey(storedAiKey());
        for (AppSettings.SourceEntry entry : loaded.sources()) {
            if (entry.config().auth().type() == SourceSettingsConfig.AuthType.NONE || secrets.has(entry)
                    || !vault.has(entry.id())) {
                continue;
            }
            try {
                String login = AppRuntime.SessionSecrets.login(entry);
                // Nur ein Wert für genau diese Anmeldung; nach einem Wechsel (Token → Passwort) fragt die App.
                char[] value = vault.load(entry.id(), login);
                if (value != null) {
                    secrets.put(entry.id(), login, value);
                }
            } catch (IOException | RuntimeException e) {
                // Ohne gespeicherten Wert fragt die App wie bisher nach; der Grund steht im Log, nie der Wert.
                LOG.warn("Gespeicherte Zugangsdaten der Quelle {} ließen sich nicht lesen: {}", entry.id(),
                        e.getMessage());
            }
        }
    }

    /** Beendet die Laufzeit (beim Schließen des Fensters); schließt Lucene-Indizes und gibt ihre Sperren frei. */
    public void shutdown() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    private void fire() {
        Runnable notify = () -> new ArrayList<>(listeners).forEach(listener -> listener.accept(this));
        if (SwingUtilities.isEventDispatchThread()) {
            notify.run();
        } else {
            SwingUtilities.invokeLater(notify);
        }
    }

    private record Started(AppSettings settings, AppRuntime runtime, Throwable problem) {
    }
}
