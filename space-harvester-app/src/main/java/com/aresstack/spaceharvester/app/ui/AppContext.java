package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
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
        OVERVIEW("Übersicht"), SOURCES("Wissensquellen"), CONTENT("Inhalte"), ACTIVITIES("Aktivitäten"),
        EXPORTS("Exporte"), SEARCH("Suche"), SETTINGS("Einstellungen");

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

        void prepareExport(String sourceId, String spaceKey);

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

    /** Liest die Konfiguration neu und startet die Laufzeit neu; laufende Aufträge werden dabei beendet. */
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
        for (AppSettings.SourceEntry entry : loaded.sources()) {
            if (entry.config().auth().type() == SourceSettingsConfig.AuthType.NONE || secrets.has(entry.config())
                    || !vault.has(entry.id())) {
                continue;
            }
            try {
                String login = AppRuntime.SessionSecrets.login(entry.config().auth());
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
