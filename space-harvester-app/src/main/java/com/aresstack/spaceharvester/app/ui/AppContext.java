package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingCallback;
import com.aresstack.spaceharvester.app.credentials.AiCredentials;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.app.credentials.ManualAiCredentials;
import com.aresstack.spaceharvester.app.settings.AiAccess;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
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
        // Reihenfolge der Icon-Leiste: erst die Arbeitsbereiche, dann Verwaltung und Einstellungen; die Suche ist die
        // Startansicht.
        SEARCH("Suche"), CONTENT("Inhalte"), ACTIVITIES("Aufträge"), STATUS("Status"),
        SOURCES("Wissensquellen verwalten"), SETTINGS("Einstellungen");

        /** Rechts in der Icon-Leiste, getrennt von den Arbeitsbereichen. */
        public boolean administration() {
            return this == SOURCES || this == SETTINGS;
        }

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

        /**
         * Startet sofort je Space einen Download (JSON-Datenbestand, {@link Downloads}), ohne Dialog; leer heißt alle
         * Spaces der Quelle. Die Aufträge erscheinen unter Aufträge, exportiert wird dort.
         */
        void download(String sourceId, java.util.List<String> spaceKeys);

        default void download(String sourceId, String spaceKey) {
            download(sourceId, spaceKey == null ? java.util.List.of() : java.util.List.of(spaceKey));
        }

        void search(String text);
    }

    /** Geprüfter Verbindungsstand einer Quelle; „verbunden“ nur nach einer erfolgreichen Prüfung. */
    public record Connection(State state, Instant checkedAt, List<Partition> spaces, Throwable error) {

        public enum State { NOT_CHECKED, CHECKING, CONNECTED, FAILED }

        static final Connection NOT_CHECKED = new Connection(State.NOT_CHECKED, null, List.of(), null);
    }

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);
    /** Tresor und Eintrag des Proxy-Passworts; die Anmeldung, für die es gilt, ist der Proxy-Benutzer. */
    static final String PROXY_VAULT = "network";
    static final String PROXY_KEY = "proxy";

    private final AppSettingsLoader loader;
    private final Path configFile;
    private final String version;
    private final AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
    private char[] sessionAiKey;
    private KeePassLink keePass;
    /** Für welchen Proxy-Benutzer das Passwort der Sitzung gilt. */
    private volatile String proxyPasswordUser;
    private final SecretVault vault;
    private final List<Consumer<AppContext>> listeners = new ArrayList<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private AppSettings settings;
    private AppRuntime runtime;
    private Throwable startProblem;
    /** Ob beim letzten Laden aus dem KeePass-Eintrag ein API-Key kam. */
    private volatile boolean keePassKeyRead;
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

    /** Tresor nur für das Proxy-Passwort ({@code ProxySettings.SECRET_REF}), getrennt von Quellen und KI-Dienst. */
    public SecretVault proxyVault() {
        return vault.scope(PROXY_VAULT);
    }

    /** Ob für diesen Proxy-Benutzer ein Passwort vorliegt: im Tresor oder für diese Sitzung. */
    public boolean proxyPasswordAvailable(String username) {
        if (username == null) {
            return false;
        }
        char[] session = secrets.proxyPassword();
        if (session != null) {
            java.util.Arrays.fill(session, '\0');
            return proxyPasswordUser != null && proxyPasswordUser.equals(username);
        }
        return proxyVault().has(PROXY_KEY, username);
    }

    /** Merkt das Proxy-Passwort für diese Sitzung, wenn es keinen Tresor gibt; das Feld wird geleert. */
    void sessionProxyPassword(String username, char[] password) {
        proxyPasswordUser = username;
        secrets.proxyPassword(password);
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
        AppSettings current = settings;
        if (current != null && current.aiAccess().fromKeePass()) {
            return aiCredentials(current.aiAccess()).apiKey();
        }
        return manualAiKey();
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

    /**
     * Merkt in der Datei, dass der Einrichtungsassistent lief ({@code space-harvester-app.setup-completed}); ohne
     * Neustart der Laufzeit, denn nur die Oberfläche liest den Merker.
     */
    public void setupCompleted(Runnable done, Consumer<Throwable> failed) {
        Background.run(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(loader);
            String text = Files.isRegularFile(configFile)
                    ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
            editor.save(configFile, editor.withSetupCompleted(configFile, new ConfigFileEditor.Draft(text, null)),
                    !editor.fileHasPlaintextSecret(configFile));
            return Boolean.TRUE;
        }, ignored -> {
            if (settings != null) {
                settings = settings.withSetupCompleted();
            }
            fire();
            done.run();
        }, failed);
    }

    /**
     * Schaltet eine Quelle ab oder wieder ein: legt den Schalter in der Datei ab
     * ({@code space-harvester-app.disabled-sources}) und startet die Quellen neu; beim Start setzt die Laufzeit ihn im
     * Core ({@code SourceManagement.setEnabled}). Der Bestand der Quelle bleibt.
     */
    public void sourceEnabled(String sourceId, boolean enabled, Runnable done, Consumer<Throwable> failed) {
        Background.run(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(loader);
            String text = Files.isRegularFile(configFile)
                    ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft base = new ConfigFileEditor.Draft(text, loader.read(configFile, text));
            java.util.Set<String> disabled = new java.util.TreeSet<>(base.settings().disabledSources());
            if (enabled) {
                disabled.remove(sourceId);
            } else {
                disabled.add(sourceId);
            }
            editor.save(configFile, editor.withDisabledSources(configFile, base, disabled),
                    !editor.fileHasPlaintextSecret(configFile));
            return disabled;
        }, saved -> reload(done), failed);
    }

    /** Ob ein API-Key für den KI-Dienst vorliegt: im Tresor oder für diese Sitzung. */
    public boolean aiKeyAvailable() {
        AppSettings current = settings;
        if (current != null && current.aiAccess().fromKeePass()) {
            // Gekoppelt heißt nicht, dass der Eintrag (noch) einen Key liefert; maßgeblich ist das letzte Lesen.
            return keePassKeyRead;
        }
        return sessionAiKey != null || aiVault().has(AiSettingsPanel.KEY_ID, AiSettingsPanel.KEY_LOGIN);
    }

    /** Woher der API-Key kommt: KeePass-Eintrag oder Tresor bzw. Sitzung („Manuell“). */
    AiCredentials aiCredentials(AiAccess access) {
        if (access.fromKeePass()) {
            return keePass().credentials(access.entry());
        }
        return new ManualAiCredentials(this::manualAiKey);
    }

    /** Der API-Key bei „Manuell“: aus der Sitzung, sonst aus dem Tresor, oder {@code null}. Nur im Hintergrund. */
    char[] manualAiKey() {
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

    /** Die KeePass-Anbindung (KeePassRPC); fragt beim Koppeln nach dem Einmal-Passwort aus KeePass. */
    public synchronized KeePassLink keePass() {
        if (keePass == null) {
            keePass = new KeePassLink(vault.scope(KeePassLink.PAIRING_VAULT), new SwingPairing());
        }
        return keePass;
    }

    /** Für Tests: eine andere KeePass-Anbindung. */
    synchronized void keePass(KeePassLink link) {
        this.keePass = link;
    }

    /** Fragt auf dem EDT nach dem Einmal-Passwort, das KeePass beim Koppeln anzeigt; Abbrechen heißt {@code null}. */
    private static final class SwingPairing implements KeePassPairingCallback {
        @Override
        public char[] requestPairingPassword(String clientDisplayName) {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return null;
            }
            java.util.concurrent.atomic.AtomicReference<char[]> typed = new java.util.concurrent.atomic.AtomicReference<>();
            Runnable ask = () -> typed.set(Dialogs.askSecret(null, "Mit KeePass koppeln",
                    "KeePass zeigt jetzt ein Einmal-Passwort für „" + clientDisplayName + "“. Bitte hier eintragen.",
                    "Koppeln"));
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                ask.run();
            } else {
                try {
                    javax.swing.SwingUtilities.invokeAndWait(ask);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    return null;
                }
            }
            return typed.get();
        }
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
            AppSettings loaded = loader.load(configFile);
            unlock(loaded);
            // Nur Laufzeitwerte geändert (KI-Dienst, Modelle, Freigaben, Synchronisierung): der Core übernimmt sie
            // ohne Neustart, laufende Aufträge laufen weiter (ADR 0015).
            AppRuntime.Reconfiguration live = previous == null ? AppRuntime.Reconfiguration.RESTART
                    : previous.reconfigure(loaded, secrets, version);
            if (live == AppRuntime.Reconfiguration.APPLIED) {
                return new Started(loaded, previous, null);
            }
            if (live == AppRuntime.Reconfiguration.REJECTED) {
                // Der Core behält die bisherige Version (ADR 0015); ein Neustart mit derselben Datei hülfe nicht und
                // beendete laufende Aufträge. Was er bemängelt, steht unter Einstellungen › Konfiguration.
                return new Started(previous.settings(), previous, null);
            }
            if (previous != null) {
                previous.close();
            }
            try {
                return new Started(loaded, AppRuntime.start(loaded, secrets, version), null);
            } catch (RuntimeException e) {
                return new Started(loaded, null, e);
            }
        }, started -> {
            boolean restarted = started.runtime() != previous;
            settings = started.settings();
            runtime = started.runtime();
            startProblem = started.problem();
            if (restarted) {
                connections.clear();
            }
            fire();
            if (runtime != null && restarted) {
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

    /**
     * Das Proxy-Passwort für den Benutzer aus der Datei: aus dem Tresor, sonst ein in dieser Sitzung eingegebenes für
     * denselben Benutzer. Ohne Anmeldung am Proxy fällt ein altes Passwort weg.
     */
    private void unlockProxy(ProxySettings.Authentication auth) {
        String username = auth == null ? null : auth.username();
        if (username == null) {
            sessionProxyPassword(null, null);
            return;
        }
        try {
            if (proxyVault().has(PROXY_KEY, username)) {
                sessionProxyPassword(username, proxyVault().load(PROXY_KEY, username));
                return;
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Das gespeicherte Proxy-Passwort ließ sich nicht lesen: {}", e.getMessage());
        }
        if (!username.equals(proxyPasswordUser)) {
            sessionProxyPassword(null, null);
        }
    }

    /** Lädt gespeicherte Token und Passwörter aus dem Tresor in die Sitzung (im Hintergrund, vor dem Start). */
    private void unlock(AppSettings loaded) {
        // Der Core bekommt den Key nur zur Laufzeit (ADR 0011), aus KeePass oder dem Tresor.
        char[] aiKey = aiCredentials(loaded.aiAccess()).apiKey();
        keePassKeyRead = loaded.aiAccess().fromKeePass() && aiKey != null && aiKey.length > 0;
        secrets.aiKey(aiKey);
        if (aiKey != null) {
            java.util.Arrays.fill(aiKey, '\0');
        }
        unlockProxy(loaded.proxy().authentication());
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
