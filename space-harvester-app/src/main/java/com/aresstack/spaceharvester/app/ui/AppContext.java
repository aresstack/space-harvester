package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingCallback;
import com.aresstack.spaceharvester.app.credentials.AiCredentials;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.app.credentials.ManualAiCredentials;
import com.aresstack.spaceharvester.app.logging.ConsoleLogging;
import com.aresstack.spaceharvester.app.settings.AiAccess;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.AnalyticsSettings;
import com.aresstack.spaceharvester.app.settings.ViewerBackground;
import com.aresstack.spaceharvester.app.settings.ConsoleOutput;
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
        // Reihenfolge der Tastenkürzel Strg+1 …: erst die Arbeitsbereiche, dann Verwaltung und Einstellungen; die Suche
        // ist die Startansicht.
        SEARCH("Suche"), CONTENT("Inhalte"), ACTIVITIES("Aufträge"), STATUS("Status"),
        SOURCES("Wissensquellen verwalten"), SETTINGS("Einstellungen");

        /** Rechts in der Icon-Leiste, abgetrennt von allem anderen. */
        public boolean administration() {
            return this == SOURCES || this == SETTINGS;
        }

        /**
         * Rechts in der Icon-Leiste, links vom Trenner der Verwaltung (Angelo, 13.11.2026): Aufträge stehen nicht bei
         * den Reitern der Mitte. Den Bereich „Begriffe“ gibt es nicht mehr; seine Reiter stehen unter Analysen (BG2c).
         */
        public boolean trailing() {
            return this == ACTIVITIES;
        }

        /**
         * Ob der Bereich einen eigenen Knopf in der Icon-Leiste hat. Inhalte hat keinen: Die Reiter der Seitenansicht
         * (Leseansicht, Text, …) stehen selbst in der Leiste und schalten die Mitte um; Status führt zur Diagnose.
         */
        public boolean button() {
            return toolbar() && this != CONTENT;
        }

        /**
         * Ob der Bereich ein Tastenkürzel Strg+Ziffer hat. Status (Diagnose) hat keins mehr: Er steht als Bereich
         * „Diagnose“ in den Einstellungen (Angelo, 13.11.2026); {@code show(STATUS)} führt dorthin.
         */
        public boolean toolbar() {
            return this != STATUS;
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

        default void openProgram(String sourceId, String pageId, String rawSha256, int line) {
            openPage(sourceId, pageId);
        }

        /**
         * Öffnet einen Absatz über seine stabile Passage-ID (#452) unter Inhalte im Reiter „Absätze“; ohne eigene
         * Umsetzung nur seine Seite.
         */
        default void openPassage(String sourceId, String passageId) {
            openPage(sourceId, com.aresstack.spaceharvester.application.passages.PassageId.parse(passageId).pageId());
        }

        void openSpace(String sourceId, String spaceKey);

        /**
         * Klick auf eine Seite im Explorer: öffnet sie, außer in der Suche. Dort ist die Auswahl links nur der Filter
         * der Suche, und die Suchansicht bleibt stehen (Angelo, 21.11.2026).
         */
        default void openFromTree(String sourceId, String pageId) {
            openPage(sourceId, pageId);
        }

        /**
         * Klick auf einen Knoten ohne eigenen Inhalt im Explorer (ein Ordner, {@link
         * com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind#hasOwnContent()}): Die Mitte
         * zeigt ihn statt der vorher geöffneten Seite, außer in der Suche; ohne eigene Umsetzung nichts.
         */
        default void openContainerFromTree(String sourceId, String spaceKey, String pageId, String title) {
        }

        /** Zeigt die Quellenseite einer Wissensquelle mit ihren Updates (ADR 0030); ohne eigene Umsetzung nichts. */
        default void openSource(String sourceId, String title) {
        }

        /**
         * „Neue Auflage erzeugen“ des Buchs am Favoritenordner (Architekt 27.11.2026), etwa aus der Leseansicht; ohne
         * eigene Umsetzung nichts.
         */
        default void newBookEdition(String folderId) {
        }

        /** Zeigt eine Quelle unter Quellen zum Bearbeiten; ohne eigene Umsetzung nur die Quellen. */
        default void editSource(String sourceId) {
            show(Section.SOURCES);
        }

        /**
         * Zeigt Inhalte › Export für einen Space bzw. eine Startseite samt Unterseiten (Angelo, 13.11.2026): wählt
         * sie zuerst im Explorer, dann den Reiter; ohne eigene Umsetzung nur den Space.
         */
        default void openExport(String sourceId, String spaceKey, String rootPageId) {
            openSpace(sourceId, spaceKey);
        }

        /** Zeigt eine Seite im Explorer (aufgeklappt und gewählt), ohne sie zu öffnen; ohne Explorer ohne Wirkung. */
        default void revealPage(String sourceId, String pageId) {
        }

        /**
         * Wie {@link #revealPage(String, String)}, mit dem Space des Treffers: Quellen ohne Seitenbaum (Jira) zeigen die
         * Seite dann unter diesem Space, statt gar nicht.
         */
        default void revealPage(String sourceId, String spaceKey, String pageId) {
            revealPage(sourceId, pageId);
        }

        /** Zeigt einen Suchtreffer im Baum hervorgehoben, ohne die Auswahl zu ändern; ohne eigene Umsetzung wie reveal. */
        default void hintPage(String sourceId, String spaceKey, String pageId) {
            revealPage(sourceId, spaceKey, pageId);
        }

        void showJob(String jobId);

        /**
         * Einstellungen mit der Seite „Allgemein“ (Konfigurationsdatei, Hinweise, abgewiesene Quellen), auch wenn
         * zuletzt eine andere Seite der Einstellungen, etwa die Diagnose, offen war.
         */
        default void showGeneralSettings() {
            show(Section.SETTINGS);
        }

        /**
         * Ein Auftrag wurde außerhalb von „Aufträge“ gestartet und läuft im Hintergrund weiter: die Auftragsliste und
         * der Balken der Statusleiste folgen ihm, ohne dass die Ansicht wechselt.
         */
        default void followJobs() {
        }

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

        /** Startet sofort den Download einer Seite samt Unterseiten, wie {@link #download(String, java.util.List)}. */
        default void downloadPage(String sourceId, String spaceKey, String pageId) {
            downloadPage(sourceId, spaceKey, pageId, true);
        }

        /** Wie {@link #downloadPage(String, String, String)}, ohne {@code withChildren} nur die Seite selbst. */
        default void downloadPage(String sourceId, String spaceKey, String pageId, boolean withChildren) {
        }

        /**
         * Indexieren (Angelo, 20.11.2026): markiert den Knoten und startet sofort den Abgleich, der ihn vollständig lädt;
         * danach hält ihn jede Synchronisierung aktuell, auch neue Seiten. Ohne {@code spaceKey} jeder Space der
         * Quelle, ohne {@code pageId} der ganze Space, sonst die Seite samt Unterseiten.
         */
        default void index(String sourceId, String spaceKey, String pageId) {
        }

        /** Hebt die Markierung genau dieses Knotens auf (Space bzw. Seite); Geladenes bleibt. */
        default void unindex(String sourceId, String spaceKey, String pageId) {
        }

        /** In den Einstellungen wurde etwas ausgeschlossen oder wieder eingeblendet: der Baum lädt die Quelle neu. */
        default void exclusionsChanged(String sourceId) {
        }

        /**
         * Holt die Änderungen eines Knotens samt Teilbaum aus der Quelle nach (Kreispfeile im Baum, Angelo
         * 16.11.2026): ohne {@code spaceKey} jeden Space der Quelle, mit {@code spaceKey} diesen Space (Nachholen wie
         * auf der Quellenkarte), mit {@code pageId} die Seite samt Unterseiten neu aus der Quelle.
         */
        default void refresh(String sourceId, String spaceKey, String pageId) {
        }

        /**
         * Wiederholt gescheiterte Abgleiche (Angelo, 21.11.2026): holt die Spaces wie die Kreispfeile im Baum nach,
         * ohne {@code spaceKeys} jeden Space der Quelle.
         */
        default void retrySyncs(String sourceId, java.util.List<String> spaceKeys) {
            retrySyncs(sourceId, spaceKeys, jobs -> { });
        }

        /**
         * Wie {@link #retrySyncs(String, java.util.List)}; {@code started} erfährt die Kennungen der gestarteten
         * Aufträge, damit eine offene Ansicht ihr Ende abonnieren kann (Angelo, 21.11.2026).
         */
        default void retrySyncs(String sourceId, java.util.List<String> spaceKeys,
                                java.util.function.Consumer<java.util.List<String>> started) {
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
    private com.aresstack.spaceharvester.app.chat.browser.ChatWebBrowser webBrowser;
    private boolean webBrowserLocated;
    /** Für welchen Proxy-Benutzer das Passwort der Sitzung gilt. */
    private volatile String proxyPasswordUser;
    private final SecretVault vault;
    private final List<Consumer<AppContext>> listeners = new ArrayList<>();
    /** Wer nach einer anderen Browserwahl neu prüfen muss, ob es die Websuche gibt (die Weltkugel des Chats). */
    private final List<Runnable> browserListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private volatile AppSettings settings;
    private volatile AppRuntime runtime;
    /** Wie viele {@link #reload} gerade laufen; solange holt die App keine Zugangsdaten aus KeePass nach. */
    private final java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
    /** Ob gerade Zugangsdaten aus KeePass nachgeholt werden ({@link #recoverFromKeePass}). */
    private final java.util.concurrent.atomic.AtomicBoolean recovering =
            new java.util.concurrent.atomic.AtomicBoolean();
    private Throwable startProblem;
    /** Ob beim letzten Laden aus dem KeePass-Eintrag ein API-Key kam. */
    private volatile boolean keePassKeyRead;
    private Navigator navigator;
    /** Statusmeldungen der Bereiche an die Statuszeile (Muster aus MainframeMate). */
    private final com.aresstack.spaceharvester.app.event.ApplicationEventBus events =
            new com.aresstack.spaceharvester.app.event.ApplicationEventBus();

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
        // Speichern-Dialoge öffnen im eingestellten Ausgabeordner, Vorgabe Downloads (Angelo 20.11.2026).
        com.aresstack.spaceharvester.app.ui.controls.SaveChooser.directory(() -> {
            AppSettings current = settings;
            return current == null ? null : current.outputDirectory();
        });
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

    /**
     * Die Quellentypen für das Quellenformular, immer vollständig: aus denselben Adaptern wie die Laufzeit
     * ({@link AppRuntime#sourceTypes}), auch solange sie noch startet, neu startet oder nicht starten konnte.
     */
    public java.util.List<com.aresstack.spaceharvester.application.sources.SourceType> sourceTypes() {
        AppSettings current = settings;
        return AppRuntime.sourceTypes(current != null ? current : loader.load(configFile));
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
     * Übernimmt die Grenzen für Bilder- und Anhängeaufträge ({@code space-harvester.jobs.parallel-preparations} und
     * {@code parallel-downloads}) und das Verbindungsbudget ({@code download-connections}) sofort im Core ({@link AppRuntime#parallel}) und speichert sie in der Datei, ohne
     * Neustart der Laufzeit. Läuft im Hintergrund; schnell aufeinanderfolgende Änderungen schreiben immer die jüngste
     * zuletzt.
     */
    public void parallel(int preparations, int downloads, int connections, Runnable done,
            Consumer<Throwable> failed) {
        parallelWanted = new int[] {preparations, downloads, connections};
        Background.run(() -> {
            synchronized (parallelLock) {
                int[] wanted = parallelWanted;
                AppRuntime current = runtime;
                if (current != null) {
                    current.parallel(wanted[0], wanted[1], wanted[2]);
                }
                writeParallel(wanted[0], wanted[1], wanted[2]);
                return wanted;
            }
        }, saved -> done.run(), failed);
    }

    private final Object parallelLock = new Object();
    private volatile int[] parallelWanted;

    private void writeParallel(int preparations, int downloads, int connections) throws java.io.IOException {
        ConfigFileEditor editor = new ConfigFileEditor(loader);
        String text = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
        ConfigFileEditor.Draft written = editor.withParallel(configFile, new ConfigFileEditor.Draft(text, null),
                preparations, downloads);
        written = editor.withConnections(configFile, written, connections);
        editor.save(configFile, written, !editor.fileHasPlaintextSecret(configFile));
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
     * Stellt die Terminal-Ausgabe sofort um ({@link ConsoleLogging#apply}) und speichert sie in der Datei
     * ({@code logging.threshold.console}), ohne Neustart der Laufzeit; die Protokolldatei bleibt unberührt. Schnell
     * aufeinanderfolgende Änderungen schreiben immer die jüngste zuletzt. Scheitert das Speichern, gilt wieder der
     * gespeicherte Wert.
     */
    public void consoleOutput(ConsoleOutput output, Runnable done, Consumer<Throwable> failed) {
        ConsoleLogging.apply(output);
        consoleWanted = output;
        Background.run(() -> {
            synchronized (consoleLock) {
                ConsoleOutput wanted = consoleWanted;
                ConfigFileEditor editor = new ConfigFileEditor(loader);
                String text = Files.isRegularFile(configFile)
                        ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
                editor.save(configFile, editor.withConsoleOutput(configFile, new ConfigFileEditor.Draft(text, null),
                        wanted), !editor.fileHasPlaintextSecret(configFile));
                return wanted;
            }
        }, saved -> {
            if (settings != null) {
                settings = settings.withConsoleOutput(saved);
            }
            // Die jüngste Wahl gilt, auch wenn ein früheres Speichern scheiterte und zurückgesetzt hat.
            ConsoleLogging.apply(consoleWanted);
            fire();
            done.run();
        }, error -> {
            ConsoleLogging.apply(settings == null ? ConsoleOutput.DEFAULT : settings.consoleOutput());
            fire();
            failed.accept(error);
        });
    }

    private final Object consoleLock = new Object();
    private volatile ConsoleOutput consoleWanted;

    /**
     * Speichert den Export „Analytics“ ({@code space-harvester-app.analytics}) in der Datei, ohne Neustart der
     * Laufzeit: der nächste Export liest die Werte aus den Einstellungen. Schnell aufeinanderfolgende Änderungen
     * schreiben immer die jüngste zuletzt.
     */
    public void analytics(AnalyticsSettings analytics, Runnable done, Consumer<Throwable> failed) {
        analyticsWanted = analytics;
        Background.run(() -> {
            synchronized (consoleLock) {
                AnalyticsSettings wanted = analyticsWanted;
                ConfigFileEditor editor = new ConfigFileEditor(loader);
                String text = Files.isRegularFile(configFile)
                        ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
                editor.save(configFile, editor.withAnalytics(configFile, new ConfigFileEditor.Draft(text, null),
                        wanted), !editor.fileHasPlaintextSecret(configFile));
                return wanted;
            }
        }, saved -> {
            if (settings != null) {
                settings = settings.withAnalytics(saved);
            }
            fire();
            done.run();
        }, error -> {
            fire();
            failed.accept(error);
        });
    }

    private volatile AnalyticsSettings analyticsWanted;

    /**
     * Speichert den Hintergrund des Bildbetrachters ({@code space-harvester-app.viewer.background}) in der Datei, ohne
     * Neustart der Laufzeit: der Betrachter liest die Farbe beim nächsten Öffnen aus den Einstellungen. Schnell
     * aufeinanderfolgende Änderungen schreiben immer die jüngste zuletzt.
     */
    public void viewerBackground(ViewerBackground background, Runnable done, Consumer<Throwable> failed) {
        viewerWanted = background;
        Background.run(() -> {
            synchronized (consoleLock) {
                ViewerBackground wanted = viewerWanted;
                ConfigFileEditor editor = new ConfigFileEditor(loader);
                String text = Files.isRegularFile(configFile)
                        ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
                editor.save(configFile, editor.withViewerBackground(configFile,
                        new ConfigFileEditor.Draft(text, null), wanted), !editor.fileHasPlaintextSecret(configFile));
                return wanted;
            }
        }, saved -> {
            if (settings != null) {
                settings = settings.withViewerBackground(saved);
            }
            fire();
            done.run();
        }, error -> {
            fire();
            failed.accept(error);
        });
    }

    private volatile ViewerBackground viewerWanted;

    /**
     * Speichert die Werte für Videos ({@code space-harvester-app.video.*}) in der Datei, ohne Neustart der Laufzeit:
     * Player, Standbilder und Marken lesen sie beim nächsten Gebrauch aus den Einstellungen. Schnell aufeinanderfolgende
     * Änderungen schreiben immer die jüngste zuletzt.
     */
    public void video(com.aresstack.spaceharvester.app.settings.VideoSettings video, Runnable done,
                      Consumer<Throwable> failed) {
        videoWanted = video;
        Background.run(() -> {
            synchronized (consoleLock) {
                com.aresstack.spaceharvester.app.settings.VideoSettings wanted = videoWanted;
                ConfigFileEditor editor = new ConfigFileEditor(loader);
                String text = Files.isRegularFile(configFile)
                        ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
                editor.save(configFile, editor.withVideo(configFile, new ConfigFileEditor.Draft(text, null), wanted),
                        !editor.fileHasPlaintextSecret(configFile));
                return wanted;
            }
        }, saved -> {
            if (settings != null) {
                settings = settings.withVideo(saved);
            }
            fire();
            done.run();
        }, error -> {
            fire();
            failed.accept(error);
        });
    }

    /**
     * Whisper und Standbilder eines Videos für einen Auftrag von „In PPTX umwandeln“, der nach einem Neustart
     * fortgesetzt wird; dieselben Angaben wie beim Start aus dem Player.
     */
    private com.aresstack.spaceharvester.application.presentation.VideoMedia presentationMedia(String sourceId,
                                                                                          String pageId) {
        AppRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("Die Quellen sind nicht verbunden.");
        }
        com.aresstack.spaceharvester.application.sources.PageMetadata metadata =
                current.workspace().page(sourceId, pageId, false).header().metadata();
        return new com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia(
                current.workspace().openMediaRange(sourceId, pageId),
                com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia.size(metadata),
                com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia.mediaType(metadata), video(),
                new com.aresstack.spaceharvester.app.transcription.TranscribeAudioUseCase(
                        new com.aresstack.spaceharvester.app.transcription.VlcPcmAudioDecoder(),
                        new com.aresstack.spaceharvester.app.transcription.WhisperSpeechTranscriber()));
    }

    private volatile com.aresstack.spaceharvester.app.settings.VideoSettings videoWanted;

    /** Die Werte für Videos; ohne geladene Einstellungen die Defaults. */
    public com.aresstack.spaceharvester.app.settings.VideoSettings video() {
        AppSettings current = settings;
        return current == null ? com.aresstack.spaceharvester.app.settings.VideoSettings.DEFAULT : current.video();
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

    /**
     * Ändert ein Feld einer Quelle in der Konfigurationsdatei (kommentarschonend, mit .bak, außer die Datei enthält ein
     * Geheimnis im Klartext) und lädt neu. Enthält der Eintrag selbst ein Geheimnis im Klartext, bleibt die Datei
     * unverändert: Das Speichern ersetzte es durch einen Platzhalter; das übernimmt das Formular „Bearbeiten …“.
     * Ändert sich nur der Anzeigename, übernimmt die Laufzeit ihn ohne Neustart ({@code AppRuntime.liveChange}).
     *
     * @param saved läuft nach dem Schreiben der Datei, vor dem Neuladen
     * @param done  läuft nach dem Neuladen
     */
    public void changeSource(String sourceId, java.util.function.UnaryOperator<com.aresstack.spaceharvester.app
            .settings.SourceDraft> change, Runnable saved, Runnable done, Consumer<Throwable> failed) {
        ConfigFileEditor editor = new ConfigFileEditor(loader);
        Background.run(() -> {
            com.aresstack.spaceharvester.app.settings.SourceDraft.Editable editable =
                    editor.editable(configFile, sourceId);
            if (!editable.plaintextSecrets().isEmpty()) {
                throw new IllegalStateException("Der Eintrag enthält ein Geheimnis im Klartext. Bitte unter "
                        + "Wissensquellen über ⋯ › „Bearbeiten …“ an der Karte ändern; das Formular legt es sicher ab.");
            }
            boolean backup = !editor.fileHasPlaintextSecret(configFile);
            editor.save(configFile, editor.replace(configFile, change.apply(editable.draft())), backup);
            return sourceId;
        }, id -> {
            saved.run();
            reload(done);
        }, failed);
    }

    /**
     * Rechtsklick „Umbenennen“ an einer Quelle (Angelo 26.11.2026): dasselbe Feld wie „Anzeigename“ in der
     * Quellenverwaltung, dieselbe Speicherung ({@link #changeSource}); ID und Ziel bleiben, kein Neustart.
     */
    public void renameSource(String sourceId, String title, Runnable done, Consumer<Throwable> failed) {
        changeSource(sourceId, draft -> draft.withTitle(title), () -> { }, done, failed);
    }

    /**
     * Gibt eine Quelle für den KI-Dienst frei: dasselbe Häkchen wie im Quellenformular („Inhalte dieser Quelle dürfen
     * an den KI-Dienst gehen“), derselbe Schlüssel {@code space-harvester.ai.external-processing-sources} in derselben
     * Datei; danach übernimmt der Core die Freigabe ohne Neustart ({@link #reload}).
     */
    public void releaseSource(String sourceId, Runnable done, Consumer<Throwable> failed) {
        changeAi(settings -> SourceForm.withRelease(settings, sourceId, true), done, failed);
    }

    /**
     * Ändert Werte des KI-Dienstes in derselben Datei wie die Einstellungen ({@code space-harvester.ai.*}), etwa die
     * Modellwahl im Menü am Dreieck (Angelo 24.11.2026); danach übernimmt der Core sie ohne Neustart ({@link #reload}).
     *
     * @param change aus den gelesenen Einstellungen die neuen Eingaben des KI-Dienstes
     */
    public void changeAi(java.util.function.Function<AppSettings, com.aresstack.spaceharvester.app.settings.AiDraft> change,
                         Runnable done, Consumer<Throwable> failed) {
        Background.run(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(loader);
            String text = Files.isRegularFile(configFile)
                    ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft base = new ConfigFileEditor.Draft(text, loader.read(configFile, text));
            editor.save(configFile, editor.withAi(configFile, base, change.apply(base.settings())),
                    !editor.fileHasPlaintextSecret(configFile));
            return base;
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

    /**
     * Der Browser der Websuche im Chat (Research4j über Playwright, ADR 0046; Edge, Chrome oder Camoufox nach
     * Einstellungen › Browser), beim ersten Gebrauch angelegt; {@code null}, wenn der gewählte Browser fehlt. Die
     * Profile je Chat liegen im Datenordner unter {@code browser-profiles}.
     */
    public synchronized com.aresstack.spaceharvester.app.chat.browser.ChatWebBrowser webBrowser() {
        if (!webBrowserLocated) {
            webBrowserLocated = true;
            AppSettings current = settings();
            Path data = current == null || current.storage() == null ? configFile.toAbsolutePath().getParent()
                    : current.storage().dataDirectory();
            webBrowser = com.aresstack.spaceharvester.app.chat.browser.ChatWebBrowser.start(data,
                    com.aresstack.spaceharvester.app.chat.browser.BrowserSettings.shared());
        }
        return webBrowser;
    }

    /** Nach einer anderen Browserwahl: offene Chat-Browser schließen, der nächste Gebrauch startet den neuen. */
    public void browserChannelChanged() {
        com.aresstack.spaceharvester.app.chat.browser.ChatWebBrowser old;
        synchronized (this) {
            old = webBrowser;
            webBrowser = null;
            webBrowserLocated = false;
        }
        if (old != null) {
            old.shutdown();
        }
        Runnable notify = () -> browserListeners.forEach(Runnable::run);
        if (SwingUtilities.isEventDispatchThread()) {
            notify.run();
        } else {
            SwingUtilities.invokeLater(notify);
        }
    }

    /** Meldet sich für {@link #browserChannelChanged}; läuft auf dem EDT. */
    public void onBrowserChanged(Runnable listener) {
        browserListeners.add(listener);
    }

    /** Die KeePass-Anbindung (KeePassRPC); fragt beim Koppeln nach dem Einmal-Passwort aus KeePass. */
    public synchronized KeePassLink keePass() {
        if (keePass == null) {
            keePass = new KeePassLink(configFile.toAbsolutePath().getParent().resolve(KeePassLink.PAIRING_DIRECTORY),
                    new SwingPairing());
            keePass.onReachable(this::keePassReachable);
        }
        return keePass;
    }

    /** Für Tests: eine andere KeePass-Anbindung. */
    synchronized void keePass(KeePassLink link) {
        this.keePass = link;
        link.onReachable(this::keePassReachable);
    }

    /**
     * Pausen der Nachprüfung nach dem Start, wenn Quellen ohne ihre Zugangsdaten aus KeePass starteten: wie die
     * Nachprüfung im Einrichtungsassistenten des EAC ({@code security.keepass.setupRecheck*}: vier Versuche ab 2 s,
     * Faktor 2,5, höchstens 30 s). Danach endet sie; ein späterer gelungener KeePass-Zugriff holt sie trotzdem nach.
     */
    static final long[] RECHECK_PAUSES_MILLIS = {2_000, 5_000, 12_500, 30_000};

    private boolean reloading() {
        return reloads.get() > 0;
    }

    /**
     * Quellen, die laut {@code started} auf ihr Geheimnis warten, obwohl ihr Passwort bzw. Token aus einem
     * KeePass-Eintrag kommt: Beim Start antwortete KeePass nicht (rechtzeitig).
     */
    private static List<AppSettings.SourceEntry> keePassWaiting(AppSettings current, AppRuntime started) {
        if (current == null || started == null || started.waitingForSecret().isEmpty()) {
            return List.of();
        }
        return current.sources().stream().filter(entry -> started.waitingForSecret().contains(entry.id())
                && current.keePassEntry(entry.id()) != null).toList();
    }

    /**
     * Beobachter der KeePass-Anbindung: KeePass hat gerade geantwortet. Warten Quellen noch auf ihre Zugangsdaten aus
     * KeePass, holt die App sie im Hintergrund nach (kein Polling: nur auf dieses Ereignis und die begrenzte
     * Nachprüfung nach dem Start).
     */
    private void keePassReachable() {
        if (reloading() || recovering.get() || keePassWaiting(settings, runtime).isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("keepass-recover").start(this::recoverFromKeePass);
    }

    /**
     * Liest die Zugangsdaten der Quellen, die beim Start ohne ihren KeePass-Eintrag blieben, erneut aus KeePass. Gelingt
     * das für mindestens eine, starten die Quellen neu, damit die Karte ohne Neustart der App „Verbunden“ zeigt.
     * Blockiert; nie auf dem EDT.
     *
     * @return ob dabei Zugangsdaten nachgeholt wurden
     */
    boolean recoverFromKeePass() {
        if (reloading() || !recovering.compareAndSet(false, true)) {
            return false;
        }
        try {
            AppSettings current = settings;
            AppRuntime started = runtime;
            List<AppSettings.SourceEntry> waiting = keePassWaiting(current, started);
            if (waiting.isEmpty() || !keePass().paired()) {
                return false;
            }
            boolean unlocked = false;
            for (AppSettings.SourceEntry entry : waiting) {
                if (!secrets.has(entry)) {
                    unlockFromKeePass(entry, current.keePassEntry(entry.id()));
                }
                unlocked |= secrets.has(entry);
            }
            if (unlocked) {
                SwingUtilities.invokeLater(() -> restartWithKeePass(started));
            }
            return unlocked;
        } finally {
            recovering.set(false);
        }
    }

    /** Startet die Quellen mit den nachgeholten Zugangsdaten neu, solange das keinen laufenden Auftrag beendet. */
    private void restartWithKeePass(AppRuntime started) {
        if (runtime != started || reloading()) {
            return;
        }
        if (!runningJobs().isEmpty()) {
            LOG.info("Zugangsdaten aus KeePass nachgeholt; die Quellen starten neu, sobald sie neu geladen werden "
                    + "(laufende Aufträge bleiben)");
            return;
        }
        LOG.info("Zugangsdaten aus KeePass nachgeholt; die Quellen starten neu");
        reload(() -> { });
    }

    /** Nach einem Start mit fehlenden KeePass-Zugangsdaten: begrenzt nachprüfen ({@link #RECHECK_PAUSES_MILLIS}). */
    private void recheckKeePass(AppRuntime started) {
        Thread.ofVirtual().name("keepass-recheck").start(() -> {
            for (long pause : RECHECK_PAUSES_MILLIS) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    return;
                }
                if (runtime != started || keePassWaiting(settings, started).isEmpty() || recoverFromKeePass()) {
                    return;
                }
            }
        });
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

    /** Der Ereignisbus dieses Fensters; die Statuszeile zeigt, was darauf als {@code StatusMessageEvent} erscheint. */
    public com.aresstack.spaceharvester.app.event.ApplicationEventBus events() {
        return events;
    }

    /** Favoriten des Benutzers (Stern im Explorer und in der Leseansicht), Datei im Datenordner der App. */
    private final Favorites favorites = new Favorites(() -> settings() == null ? null
            : settings().storage().dataDirectory().resolve(Favorites.FILE));

    /**
     * Die Projekte und das aktive Projekt der ganzen App (ADR 0043, Nachtrag F6), Ordner {@code projekte} im Datenordner
     * der App. Ein Speicher für alle Perspektiven; Wechsel werden über seine Beobachter gemeldet.
     */
    private final com.aresstack.spaceharvester.app.project.ProjectStore projects =
            new com.aresstack.spaceharvester.app.project.ProjectStore(() -> settings() == null ? null
                    : settings().storage().dataDirectory()
                    .resolve(com.aresstack.spaceharvester.app.project.ProjectStore.FOLDER));

    public com.aresstack.spaceharvester.app.project.ProjectStore projects() {
        return projects;
    }

    /** Der Ordner aller Chats im Datenordner der App; {@code null} ohne Einstellungen. */
    public java.nio.file.Path chatsDirectory() {
        return settings() == null ? null
                : settings().storage().dataDirectory().resolve(com.aresstack.spaceharvester.app.chat.ChatStore.FOLDER);
    }

    /** Anzeigename einer Wissensquelle (etwa für den Pfad eines Favoriten); ohne Laufzeit oder Titel ihre Kennung. */
    public String sourceTitle(String id) {
        AppRuntime runtime = runtime();
        if (runtime != null) {
            for (com.aresstack.spaceharvester.application.sources.HarvestSource source
                    : runtime.workspace().sources()) {
                if (source.id().equals(id)) {
                    return source.title() == null || source.title().isBlank() ? id : source.title();
                }
            }
        }
        return id;
    }

    public Favorites favorites() {
        return favorites;
    }

    public Navigator navigator() {
        return navigator;
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    /**
     * Ansichten melden sich hier, um nach jedem (Neu-)Start ihre Daten neu zu laden; wer vor der App endet (etwa ein
     * geschlossener Export-Reiter), meldet sich mit der Rückgabe wieder ab.
     */
    public com.aresstack.spaceharvester.application.observe.Subscription onChange(Consumer<AppContext> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
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
        VisibleCheck visible = new VisibleCheck(sourceId, () -> current.workspace().beginConnectionCheck(sourceId));
        Background.run(() -> current.workspace().spaces(sourceId),
                spaces -> {
                    visible.end(null);
                    // Ergebnis einer inzwischen ersetzten Laufzeit (Neu laden) verwerfen.
                    if (runtime != current) {
                        return;
                    }
                    connections.put(sourceId, new Connection(Connection.State.CONNECTED, Instant.now(), spaces, null));
                    fire();
                    done.run();
                },
                error -> {
                    visible.end(com.aresstack.spaceharvester.application.background.ConnectionChecks.reasonCode(error));
                    if (runtime != current) {
                        return;
                    }
                    connections.put(sourceId, new Connection(Connection.State.FAILED, Instant.now(), List.of(), error));
                    fire();
                    done.run();
                });
    }

    /**
     * Die Sichtbarkeit einer Verbindungsprüfung (Fundstelle 17, Abnahme des Architekten): ein einmaliger Zeitgeber von
     * einer Sekunde je Prüfung, kein Polling. Läuft die Prüfung dann noch, wird sie als Hintergrundteil „Verbindung
     * prüfen“ sichtbar; endet sie vorher, entsteht kein Teil. Kommt der Teil erst nach dem Ergebnis an, endet er sofort.
     */
    static final class VisibleCheck {

        /** Ab dieser Dauer wird eine Prüfung sichtbar. */
        static final int THRESHOLD_MILLIS = 1000;

        private final javax.swing.Timer threshold;
        private boolean ended;
        private String failure;
        private com.aresstack.spaceharvester.application.background.ConnectionChecks.Check check;

        VisibleCheck(String sourceId, java.util.function.Supplier<java.util.Optional<
                com.aresstack.spaceharvester.application.background.ConnectionChecks.Check>> begin) {
            threshold = new javax.swing.Timer(THRESHOLD_MILLIS, event -> {
                synchronized (this) {
                    if (ended) {
                        return;
                    }
                }
                Background.run(begin::get, started -> started.ifPresent(this::shown),
                        // Nur Quelle und Klasse, nie die Nachricht (Abnahme #1091).
                        error -> LOG.debug("Verbindungsprüfung von {} ließ sich nicht anzeigen ({})", sourceId,
                                error.getClass().getName()));
            });
            threshold.setRepeats(false);
            threshold.start();
        }

        private void shown(com.aresstack.spaceharvester.application.background.ConnectionChecks.Check started) {
            boolean finishNow;
            String reason;
            synchronized (this) {
                finishNow = ended;
                reason = failure;
                check = started;
            }
            if (finishNow) {
                started.finish(reason);
            }
        }

        /** @param reason der Grundcode ({@code ConnectionChecks#reasonCode}) einer gescheiterten Prüfung, sonst null */
        void end(String reason) {
            threshold.stop();
            com.aresstack.spaceharvester.application.background.ConnectionChecks.Check shown;
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
                failure = reason;
                shown = check;
            }
            if (shown != null) {
                shown.finish(reason);
            }
        }
    }

    /**
     * Ob {@link #reload} diesen wartenden oder laufenden Auftrag abbräche. Ein Hintergrundauftrag nur, wenn ein Benutzer
     * ihn startete ({@code BackgroundJobs#startedByUser}, Abnahme #1073): Er verlöre mit der Laufzeit seinen Aufrufer
     * und hielte danach gelb „von Hand fortsetzen“ an. Automatik und Abgleiche beim Start unterbricht das Neuladen nur,
     * sie setzen danach von selbst fort (ADR 0025, Nachtrag Punkt 9); sonst fragte jedes Übernehmen kurz nach dem Start
     * nach.
     */
    static boolean abortedByReload(JobStatus job) {
        // Was das Ende des Dienstes übersteht, etwa das Löschen einer entfernten Quelle (Fundstelle 22), setzt danach
        // von selbst fort und wird nicht abgebrochen.
        if ("true".equals(job.parameter(com.aresstack.spaceharvester.application.jobs.Jobs.SURVIVES_SHUTDOWN))) {
            return false;
        }
        return !job.state().terminal() && (!job.type().background()
                || com.aresstack.spaceharvester.application.background.BackgroundJobs.startedByUser(job));
    }

    /**
     * Die Aufträge, die {@link #reload} abbrechen würde (wartend oder laufend); leer ohne Laufzeit oder wenn die
     * Auftragsablage nicht lesbar ist ({@link #abortedByReload}).
     */
    public List<JobStatus> runningJobs() {
        if (runtime == null) {
            return List.of();
        }
        try {
            return runtime.workspace().jobs().stream().filter(AppContext::abortedByReload).toList();
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
        reloads.incrementAndGet();
        Background.run(() -> {
            AppSettings loaded = loader.load(configFile);
            // Gleich nach dem Lesen, damit schon der Start der Laufzeit der Terminal-Ausgabe folgt.
            ConsoleLogging.apply(loaded.consoleOutput());
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
                // beendete laufende Aufträge. Was er bemängelt, steht unter Einstellungen › Konfiguration. Die
                // Terminal-Ausgabe betrifft den Core nicht und gilt wie gelesen.
                return new Started(previous.settings().withConsoleOutput(loaded.consoleOutput()), previous, null);
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
            // Vorschauen außerhalb der Quellen (Deck, Chat) lesen Dokumente mit denselben Grenzen.
            if (settings != null) {
                com.aresstack.spaceharvester.app.deck.DocumentTexts.limits(settings.storage().extraction());
                // Diagnose per KI: Schalter und Modell für Kontextmenü und Chat-Werkzeuge der Diagnose.
                com.aresstack.spaceharvester.app.chat.AiDiagnosis.settings(settings.ai().diagnosis());
            }
            runtime = started.runtime();
            if (runtime != null) {
                // Der Vergleich der Standbilder im Core folgt den Werten für Videos, auch nach dem Speichern.
                runtime.workspace().slideThresholds(() -> video().slideThresholds());
                // „In PPTX umwandeln“ nach einem Neustart fortsetzen: das Video kommt wie beim Start aus der Quelle.
                runtime.workspace().presentationMedia(this::presentationMedia);
            }
            startProblem = started.problem();
            reloads.decrementAndGet();
            if (restarted) {
                connections.clear();
            }
            fire();
            if (runtime != null && restarted) {
                // Keine Verbindungsprüfung beim Start (Angelo 24.11.2026: nichts ohne Klick): Jede Ansicht, die Spaces
                // braucht, prüft eine noch nicht geprüfte Quelle selbst (SourcePicker, Quellenansicht).
                // Antwortete KeePass beim Start nicht, holt die App die Zugangsdaten nach, ohne dass neu zu starten ist.
                if (!keePassWaiting(settings, runtime).isEmpty()) {
                    recheckKeePass(runtime);
                }
            }
            done.run();
        }, error -> {
            reloads.decrementAndGet();
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
            if (entry.config().auth().type() == SourceSettingsConfig.AuthType.NONE || secrets.has(entry)) {
                continue;
            }
            String keePassEntry = loaded.keePassEntry(entry.id());
            // Ein Wert aus JVM-Property oder Umgebung (${VAR}) geht KeePass vor; die App fragt KeePass nur, wenn er fehlt.
            if (keePassEntry != null && !entry.secretMissing()) {
                continue;
            }
            if (keePassEntry != null) {
                unlockFromKeePass(entry, keePassEntry);
                continue;
            }
            if (!vault.has(entry.id())) {
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

    /**
     * Passwort bzw. Token einer Quelle aus ihrem KeePass-Eintrag, ohne Kopplungsdialog: Ist die App gekoppelt, gilt
     * dieselbe Kopplung wie für den KI-Dienst. Ohne Kopplung oder Eintrag fragt die App wie bisher.
     */
    private void unlockFromKeePass(AppSettings.SourceEntry entry, String title) {
        if (!keePass().paired()) {
            return;
        }
        try (com.aresstack.enterpriseai.adyton.VaultEntryDraft read = keePass().readSilently(title)) {
            char[] value = read.copyPassword();
            if (value != null && value.length > 0) {
                secrets.put(entry.id(), AppRuntime.SessionSecrets.login(entry), value);
            } else if (AppRuntime.SessionSecrets.withoutLogin(read.fields())) {
                // Ohne Anmeldung braucht die Quelle kein Passwort; sie gilt trotzdem als bereit.
                secrets.put(entry.id(), AppRuntime.SessionSecrets.login(entry), new char[0]);
            }
            // Zusatzfelder wie im Enterprise AI Client (MM_RequiresLogin), nie Benutzer oder Passwort (#571).
            secrets.keePassFields(entry.id(), read.fields());
        } catch (com.aresstack.enterpriseai.adyton.SecretUnavailableException | RuntimeException e) {
            // Nur Quelle und Ausnahmeklasse, nie Eintrag oder Wert.
            LOG.warn("Zugangsdaten der Quelle {} ließen sich nicht aus KeePass lesen: {}", entry.id(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Ob „KeePass“ überall vorgewählt wird: Die App ist gekoppelt oder KeePass ist schon für den KI-Dienst oder eine
     * Quelle gewählt; sonst „Manuell“.
     */
    public boolean keePassPreferred() {
        AppSettings current = settings;
        return keePass().paired() || current != null
                && (current.aiAccess().fromKeePass() || !current.sourceKeePass().isEmpty());
    }

    private ParserModelSetup parserModel;

    /**
     * Die Installation des MaltParser-Modells (ADR 0024), gemeinsam für Assistent, Einstellungen und Reiter
     * „Aussagen“. Ziel ist {@code <Datenverzeichnis>/nlp-model/<Tag>}; geladen wird über Proxy und TLS der App.
     */
    public synchronized ParserModelSetup parserModel() {
        if (parserModel == null) {
            parserModel = new ParserModelSetup(() -> {
                AppSettings current = settings();
                if (current == null) {
                    return java.util.Optional.empty();
                }
                return com.aresstack.spaceharvester.nlp.malt.GermanParserModelPin.release().map(release ->
                        new com.aresstack.spaceharvester.app.nlp.ParserModelInstaller(
                                com.aresstack.spaceharvester.app.nlp.MaltStatementAnalyzer.defaultDirectory(
                                        current.storage().dataDirectory()),
                                release,
                                new com.aresstack.spaceharvester.app.nlp.HttpModelDownload(
                                        AppRuntime.proxy(current.proxy(), secrets()), current.tls(),
                                        "space-harvester-app/" + version()),
                                // Abgerissene Downloads so oft ab dem letzten Byte fortsetzen wie jobs.retry.max-attempts.
                                current.jobLimits().retry().maxAttempts()));
            });
            // Die Einstellungen laden erst nach dem Fenster: danach (und nach jeder Änderung) neu prüfen.
            ParserModelSetup setup = parserModel;
            onChange(context -> setup.refresh());
        }
        return parserModel;
    }

    private WhisperModelSetup whisperModel;

    /**
     * Die Whisper-Modelle für Spracheingabe und Transkripte, gemeinsam für Einstellungen, Chat und Videos; ein Download
     * läuft für alle sichtbar, egal für welche Stelle. Das eingestellte Modell ist das der Transkripte, die
     * Spracheingabe fragt mit {@link #dictationWhisper()} nach ihrem eigenen. Es liegt
     * nicht im Programm, sondern wird nach Rückfrage von Hugging Face nach {@code ~/.space-harvester/models} geladen,
     * über Proxy und TLS der App.
     */
    public synchronized WhisperModelSetup whisperModel() {
        if (whisperModel == null) {
            whisperModel = new WhisperModelSetup(() -> {
                AppSettings current = settings();
                if (current == null) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(new com.aresstack.spaceharvester.app.transcription.WhisperModelInstaller(
                        com.aresstack.spaceharvester.app.transcription.WhisperModelLocation.modelsDirectory(),
                        new com.aresstack.spaceharvester.app.hf.HuggingFaceClient(
                                new com.aresstack.spaceharvester.app.nlp.HttpModelDownload(
                                        AppRuntime.proxy(current.proxy(), secrets()), current.tls(),
                                        "space-harvester-app/" + version()))));
            }, this::whisper);
            // Die Einstellungen laden erst nach dem Fenster: danach (und nach jeder Änderung) neu prüfen.
            WhisperModelSetup setup = whisperModel;
            onChange(context -> setup.refresh());
        }
        return whisperModel;
    }

    /** Das eingestellte Whisper-Modell der Transkripte; ohne geladene Einstellungen Tiny. */
    public com.aresstack.spaceharvester.app.transcription.WhisperModel whisper() {
        AppSettings current = settings;
        return current == null ? com.aresstack.spaceharvester.app.transcription.WhisperModel.DEFAULT
                : current.whisperModel();
    }

    /** Das eingestellte Whisper-Modell der Spracheingabe im Chat; ohne geladene Einstellungen Tiny. */
    public com.aresstack.spaceharvester.app.transcription.WhisperModel dictationWhisper() {
        AppSettings current = settings;
        return current == null ? com.aresstack.spaceharvester.app.transcription.WhisperModel.DEFAULT : current.dictationModel();
    }

    /**
     * Speichert das Whisper-Modell der Transkripte ({@code space-harvester-app.whisper.model}) in der Datei, ohne
     * Neustart der Laufzeit: Transkripte nehmen es beim nächsten Gebrauch.
     */
    public void whisper(com.aresstack.spaceharvester.app.transcription.WhisperModel model, Runnable done, Consumer<Throwable> failed) {
        saveWhisper(model, false, done, failed);
    }

    /**
     * Speichert das Whisper-Modell der Spracheingabe ({@code space-harvester-app.whisper.dictation-model}) in der
     * Datei, ohne Neustart der Laufzeit: es gilt ab der nächsten Aufnahme (Angelo 21.11.2026).
     */
    public void dictationWhisper(com.aresstack.spaceharvester.app.transcription.WhisperModel model, Runnable done, Consumer<Throwable> failed) {
        saveWhisper(model, true, done, failed);
    }

    private void saveWhisper(com.aresstack.spaceharvester.app.transcription.WhisperModel model, boolean dictation, Runnable done, Consumer<Throwable> failed) {
        if (dictation) {
            dictationWanted = model;
        } else {
            whisperWanted = model;
        }
        Background.run(() -> {
            synchronized (consoleLock) {
                com.aresstack.spaceharvester.app.transcription.WhisperModel wanted = dictation ? dictationWanted : whisperWanted;
                ConfigFileEditor editor = new ConfigFileEditor(loader);
                String text = Files.isRegularFile(configFile)
                        ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
                ConfigFileEditor.Draft base = new ConfigFileEditor.Draft(text, null);
                editor.save(configFile, dictation ? editor.withDictationModel(configFile, base, wanted)
                        : editor.withWhisperModel(configFile, base, wanted), !editor.fileHasPlaintextSecret(configFile));
                return wanted;
            }
        }, saved -> {
            if (settings != null) {
                settings = dictation ? settings.withDictationModel(saved) : settings.withWhisperModel(saved);
            }
            fire();
            done.run();
        }, error -> {
            fire();
            failed.accept(error);
        });
    }

    private volatile com.aresstack.spaceharvester.app.transcription.WhisperModel whisperWanted;
    private volatile com.aresstack.spaceharvester.app.transcription.WhisperModel dictationWanted;

    /** Beendet die Laufzeit (beim Schließen des Fensters); schließt Lucene-Indizes und gibt ihre Sperren frei. */
    public void shutdown() {
        com.aresstack.spaceharvester.app.chat.browser.ChatWebBrowser browser;
        synchronized (this) {
            browser = webBrowser;
        }
        if (browser != null) {
            browser.shutdown();
        }
        // Nur einmal schließen, auch wenn Fenster und Shutdown-Hook zugleich beenden.
        AppRuntime closing;
        synchronized (this) {
            closing = runtime;
            runtime = null;
        }
        if (closing != null) {
            closing.close();
        }
    }

    private void fire() {
        // Spracheingabe und Transkripte lesen das eingestellte Modell beim nächsten Gebrauch.
        com.aresstack.spaceharvester.app.transcription.WhisperModelLocation.select(whisper());
        com.aresstack.spaceharvester.app.transcription.WhisperModelLocation.selectDictation(dictationWhisper());
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
