package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.source.network.ProxySettings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Einstellungen und Diagnose in Bereichen wie im AskAI-Client: Die Bereiche stehen untereinander im linken Drawer
 * ({@link #drawer()}, an Stelle des Explorers, solange die Einstellungen offen sind), rechts nur der gewählte Bereich.
 * „Allgemein“ (welche Datei gilt, was daran nicht stimmt, wo die Daten liegen, welche Ordner gelesen werden dürfen,
 * Versionen), „KI-Dienst“ und „Modelle“ ({@link AiSettingsPanel}, zentral für alle Quellen), „Netzwerk“ (Proxy und
 * Serverzertifikate), „Cache“ (Aktualisierung gespeicherter Seiten, Frist für gesperrte Seiten) und „Sicherheit“
 * (KeePass-Kopplung für KI-Dienst und Quellen, Tresor). Geheimnisse erscheinen nur als „gespeichert“ oder „fehlt“;
 * lokal gespeicherte Zugangsdaten lassen sich dort je Eintrag löschen. Jede
 * Änderung schreibt die Datei und startet die Laufzeit mit ihr neu; von Hand geänderte Dateien übernimmt „Neu laden“.
 */
public final class SettingsView implements View {

    static final String GENERAL = "Allgemein";
    static final String AI = "KI-Dienst";
    static final String MODELS = "Modelle";
    static final String NETWORK = "Netzwerk";
    static final String CACHE = "Cache";
    static final String SECURITY = "Sicherheit";
    /** Tooltip der Tresor-Zeilen, deren Wert nur in KeePass liegt: lokal gibt es nichts zu löschen. */
    static final String KEEPASS_TIP = "Die Zugangsdaten liegen nur in KeePass und werden bei Bedarf über die Kopplung "
            + "gelesen; lokal ist nichts gespeichert. Die Verbindung trennt „Entkoppeln“ im Abschnitt KeePass.";
    static final String EXAMPLE = "/com/aresstack/spaceharvester/app/space-harvester-app.example.yml";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final Facts file = new Facts();
    private final JTextArea problems = Comic.note("");
    private final Facts storage = new Facts();
    private final Facts network = new Facts();
    private final Facts cache = new Facts();
    /** Reiter „Sicherheit“: wo Geheimnisse liegen, je Eintrag nur „gespeichert“ oder „fehlt“, nie der Wert. */
    final Facts security = new Facts();
    /** Reiter „Sicherheit“, Abschnitt „KeePass“: die eine Kopplung der App. */
    final KeePassPairingPanel keePass;
    /** Meldungen im Abschnitt „Tresor“ (gelöschte Zugangsdaten). */
    final Feedback securityFeedback = new Feedback();
    /** Die Löschknöpfe des Reiters „Sicherheit“ in Zeilenreihenfolge, für Tests. */
    final java.util.List<IconButton> deleteButtons = new java.util.ArrayList<>();
    private final java.util.Map<IconButton, Runnable> deletions = new java.util.HashMap<>();
    private final ComposerButton editCache = Comic.button("Cache und Aufbewahrung …",
            "Aktualisierung gespeicherter Seiten und Frist für gesperrte Seiten einstellen");
    /** Rechts: der gewählte Bereich. */
    private final java.awt.CardLayout pages = new java.awt.CardLayout();
    private final JPanel pageDeck = new JPanel(pages);
    /** Links im Drawer: die Bereiche untereinander. */
    private final JPanel drawer = new JPanel(new BorderLayout(0, 6));
    private final JPanel navRows = new JPanel();
    private final java.util.Map<String, SettingsNavRow> rows = new java.util.LinkedHashMap<>();
    private String selected;
    /** „Zurück“ im Drawer: verlässt die Einstellungen (setzt das Hauptfenster). */
    private Runnable leave = () -> { };
    final ComposerButton back = Comic.button(ComposerIcons.chevronLeft(), "Zurück",
            "Einstellungen verlassen und zum vorigen Bereich zurückkehren");
    private final AiSettingsPanel ai;
    /** Platte „Dateizugriff“ unter Allgemein: freigegebene Ordner für Ordner-Quellen. */
    final FileAccessEditor fileAccess;
    private final Facts runtime = new Facts();
    private final Feedback feedback = new Feedback();
    private final ComposerButton reload = Comic.primary("Neu laden", "Konfiguration neu lesen und Laufzeit neu starten");
    private final ComposerButton create = Comic.button("Beispiel anlegen", "Beispielkonfiguration an diesem Ort anlegen");
    private final ComposerButton openFile = Comic.button("Datei öffnen", "Konfiguration im Standardeditor öffnen");
    private final ComposerButton openFolder = Comic.button("Ordner öffnen", "Ordner der Konfiguration öffnen");
    private final ComposerButton openData = Comic.button("Ordner öffnen", "Datenordner des lokalen Bestands im Dateimanager öffnen");
    final ComposerButton chooseOutput = Comic.button("Ausgabeordner ändern …",
            "Ordner, in den „Exportieren“ unter Aufträge die Dateien legt");
    final ComposerButton openOutput = Comic.button("Ausgabeordner öffnen", "Den Ausgabeordner anzeigen");
    final ComposerButton resetOutput = Comic.button("Zurück auf Downloads", "Wieder den Ordner Downloads verwenden");
    private final Facts output = new Facts();
    private final ComposerButton about = Comic.button("Über Space Harvester", "Version und Herkunft");
    final ComposerButton setup = Comic.button(SetupWizard.TITLE + " …",
            "Netzwerk, KI-Dienst, Modelle und erste Wissensquelle Schritt für Schritt einrichten");
    private final ComposerButton proxy = Comic.button("Proxy ändern …", "Proxy für alle Quellen einstellen und auflösen");
    private final ComposerButton tls = Comic.button("Serverzertifikate …",
            "Festlegen, welchen Zertifizierungsstellen die App bei allen Quellen glaubt");

    public SettingsView(AppContext context) {
        this.context = context;
        this.ai = new AiSettingsPanel(context);
        this.fileAccess = new FileAccessEditor(context);
        this.keePass = new KeePassPairingPanel(context);
        keePass.onChange(() -> {
            ai.refreshPairing();
            render();
        });
        ai.onPairingChange(keePass::render);
        root.setBackground(Comic.SURFACE);
        problems.setForeground(Comic.RED);
        reload.addActionListener(e -> reload());
        create.addActionListener(e -> createExample());
        openFile.addActionListener(e -> open(context.configFile()));
        openFolder.addActionListener(e -> open(context.configFile().toAbsolutePath().getParent()));
        openData.addActionListener(e -> {
            if (context.settings() != null) {
                open(context.settings().storage().dataDirectory());
            }
        });
        chooseOutput.addActionListener(e -> chooseOutput());
        openOutput.addActionListener(e -> {
            if (context.settings() != null) {
                open(context.settings().outputDirectory());
            }
        });
        resetOutput.addActionListener(e -> saveOutput(null));
        about.addActionListener(e -> AboutDialog.open(root, context));
        setup.addActionListener(e -> {
            SetupWizard.open(root, context);
            render();
            ai.render();
        });
        proxy.addActionListener(e -> SettingDialogs.proxy(root, context, null,
                () -> feedback.success("Proxy übernommen")).open());
        tls.addActionListener(e -> SettingDialogs.tls(root, context,
                () -> feedback.success("Serverzertifikate übernommen")).open());
        editCache.addActionListener(e -> SettingDialogs.cache(root, context,
                () -> feedback.success("Cache-Einstellungen übernommen")).open());
        JPanel config = new JPanel(new BorderLayout(0, 8));
        config.setOpaque(false);
        config.add(file, BorderLayout.NORTH);
        config.add(problems, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(reload, create, openFile, openFolder, setup), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        config.add(south, BorderLayout.SOUTH);
        JPanel data = new JPanel(new BorderLayout(0, 8));
        data.setOpaque(false);
        data.add(storage, BorderLayout.CENTER);
        data.add(Comic.row(openData), BorderLayout.SOUTH);
        JPanel outputPanel = new JPanel(new BorderLayout(0, 8));
        outputPanel.setOpaque(false);
        outputPanel.add(output, BorderLayout.CENTER);
        outputPanel.add(Comic.row(chooseOutput, openOutput, resetOutput), BorderLayout.SOUTH);
        JPanel diagnosis = new JPanel(new BorderLayout(0, 8));
        diagnosis.setOpaque(false);
        diagnosis.add(runtime, BorderLayout.CENTER);
        diagnosis.add(Comic.row(about), BorderLayout.SOUTH);
        page(GENERAL, Comic.column(
                Comic.plate("Konfiguration", Comic.BLUE, config),
                Comic.plate("Ausgabeordner", Comic.GREEN, outputPanel),
                Comic.plate("Ablage", Comic.PETROL, data),
                Comic.plate(FileAccessEditor.HEADING, Comic.ORANGE, fileAccess.component()),
                Comic.plate("Diagnose", Comic.DARK, diagnosis)));
        page(AI, Comic.column(Comic.plate("KI-Dienst", Comic.YELLOW, ai.serviceComponent())));
        page(MODELS, Comic.column(Comic.plate("Modelle für alle Quellen", Comic.YELLOW, ai.modelsComponent())));
        page(NETWORK, Comic.column(Comic.plate("Netz und Zertifikate", Comic.ORANGE,
                withButtons(network, proxy, tls))));
        page(CACHE, Comic.column(Comic.plate("Cache und Aufbewahrung", Comic.PETROL,
                withButtons(cache, editCache))));
        JPanel vault = new JPanel(new BorderLayout(0, 8));
        vault.setOpaque(false);
        vault.add(Comic.wrapped("Geheimnisse stehen nie in der Konfigurationsdatei: Tokens, Passwörter, der API-Key des "
                + "KI-Dienstes und das Proxy-Passwort liegen im Tresor dieses Windows-Kontos (DPAPI), als Dateien nur "
                + "für dieses Konto lesbar; in der Datei steht nur der Platzhalter ${NAME}. Der Knopf rechts löscht die "
                + "lokal gespeicherten Zugangsdaten eines Eintrags sofort; ändern an der Quelle, unter KI-Dienst bzw. "
                + "Proxy.", Comic.TEXT, 12.5f), BorderLayout.NORTH);
        vault.add(security, BorderLayout.CENTER);
        vault.add(securityFeedback, BorderLayout.SOUTH);
        page(SECURITY, Comic.column(Comic.plate(KeePassPairingPanel.HEADING, Comic.BLUE, keePass.component()),
                Comic.plate("Tresor", Comic.RED, vault)));
        pageDeck.setOpaque(false);
        root.add(pageDeck, BorderLayout.CENTER);
        buildDrawer();
        select(GENERAL);
        context.onChange(ignored -> {
            render();
            fileAccess.render();
            keePass.render();
            // Eingaben in den offenen Bereichen „KI-Dienst“ und „Modelle“ bleiben stehen; beim nächsten Öffnen gilt der
            // neue Stand.
            if (!AI.equals(selected) && !MODELS.equals(selected)) {
                ai.render();
            }
        });
        render();
    }

    private void page(String name, JComponent content) {
        pageDeck.add(content, name);
        SettingsNavRow row = new SettingsNavRow(name, () -> select(name));
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        rows.put(name, row);
        navRows.add(row);
        navRows.add(javax.swing.Box.createVerticalStrut(2));
    }

    /**
     * Der Drawer wie im AskAI-Client ({@code ChatWorkspacePanel.renderSettingsRows} und {@code buildSettingsFooter}):
     * Überschrift, die Bereiche als Zeilen untereinander, unten der Weg zurück.
     */
    private void buildDrawer() {
        drawer.setOpaque(false);
        drawer.add(Comic.caption(AppContext.Section.SETTINGS.title()), BorderLayout.NORTH);
        navRows.setLayout(new javax.swing.BoxLayout(navRows, javax.swing.BoxLayout.Y_AXIS));
        navRows.setOpaque(false);
        navRows.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 0, 4, 0));
        javax.swing.JScrollPane scroll = Comic.scroll(navRows);
        scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        drawer.add(scroll, BorderLayout.CENTER);
        back.addActionListener(e -> leave.run());
        JPanel foot = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        foot.setOpaque(false);
        foot.add(back);
        drawer.add(foot, BorderLayout.SOUTH);
    }

    /** Zeigt rechts den Bereich {@code name} und markiert ihn links im Drawer. */
    void select(String name) {
        if (!rows.containsKey(name)) {
            return;
        }
        selected = name;
        rows.forEach((title, row) -> row.setSelected(title.equals(name)));
        pages.show(pageDeck, name);
        if ((AI.equals(name) || MODELS.equals(name)) && !ai.changed()) {
            ai.render();
        }
    }

    /** Der gewählte Bereich. */
    String selected() {
        return selected;
    }

    /** Die Navigation der Bereiche für den linken Drawer des Hauptfensters. */
    public JComponent drawer() {
        return drawer;
    }

    /** Was „Zurück“ im Drawer tut (das Hauptfenster zeigt den vorigen Bereich). */
    public void onLeave(Runnable leave) {
        this.leave = leave == null ? () -> { } : leave;
    }

    /** Für Tests: die Zeilen der Navigation in ihrer Reihenfolge. */
    /**
     * Ein Papierkorb-Knopf in der Zeile eines Eintrags: löscht nach Rückfrage die lokal gespeicherten Zugangsdaten
     * (Datei im Tresor und den Wert dieser Sitzung), ohne den Wert je anzuzeigen, und startet die Laufzeit neu, damit
     * auch die Kopien in den laufenden Quellen und im KI-Dienst verschwinden; sofort, nicht erst beim nächsten Start.
     */
    private IconButton deleteButton(String what, Background.ThrowingRunnable delete) {
        IconButton button = new IconButton(Glyph.TRASH, "Lokal gespeicherte Zugangsdaten löschen: " + what);
        Runnable run = () -> Background.execute(delete, () -> context.reload(() -> {
            render();
            ai.render();
            securityFeedback.success("Gelöscht: " + what + "; die Laufzeit wurde ohne diese Zugangsdaten neu gestartet");
        }), securityFeedback::problem);
        button.addActionListener(e -> {
            if (Dialogs.restartAllowed(root, context) && Dialogs.confirm(root, "Zugangsdaten löschen?", what
                    + ": die lokal gespeicherte Datei wird gelöscht und die Laufzeit neu gestartet, damit der Wert "
                    + "sofort nirgends mehr gilt. Die App fragt beim nächsten Zugriff neu danach.", "Löschen")) {
                run.run();
            }
        });
        deleteButtons.add(button);
        deletions.put(button, run);
        return button;
    }

    /** Löscht wie der Knopf, ohne Rückfrage; für Tests. */
    void deleteWithoutAsking(IconButton button) {
        deletions.get(button).run();
    }

    java.util.List<SettingsNavRow> navRows() {
        return java.util.List.copyOf(rows.values());
    }

    private void renderSecurity(AppSettings settings) {
        security.reset();
        deleteButtons.clear();
        deletions.clear();
        security.add("Tresor", context.vault().describe());
        if (settings == null) {
            return;
        }
        // Je Zeile: woher der Wert kommt. Nur was lokal in einer Datei liegt, bekommt den Papierkorb; aus KeePass
        // gelesene Werte liegen nie lokal, dort gibt es nichts zu löschen.
        com.aresstack.spaceharvester.app.settings.AiAccess access = settings.aiAccess();
        boolean aiKeyStored = context.aiVault().has(AiSettingsPanel.KEY_ID);
        String aiState;
        String aiTip = null;
        if (aiKeyStored) {
            // Eine Datei liegt noch da, auch wenn der Dienst inzwischen aus KeePass liest: Der Papierkorb zeigt sie an.
            aiState = "im Tresor" + (access.fromKeePass() ? "; nicht mehr genutzt, der Dienst liest aus KeePass („"
                    + access.entry() + "“)" : "");
        } else if (access.fromKeePass()) {
            aiState = "in KeePass („" + access.entry() + "“)" + (context.aiKeyAvailable() ? "" : "; noch nicht gelesen");
            aiTip = KEEPASS_TIP;
        } else if (context.aiKeyAvailable()) {
            aiState = "nur für diese Sitzung";
        } else {
            aiState = settings.ai().configured() ? "fehlt" : "kein KI-Dienst eingerichtet";
        }
        security.add("API-Key des KI-Dienstes", aiState, Comic.INK,
                aiKeyStored ? deleteButton("API-Key des KI-Dienstes", () -> {
                    context.aiVault().remove(AiSettingsPanel.KEY_ID);
                    context.sessionAiKey(null);
                }) : null, aiTip);
        ProxySettings proxy = settings.proxy();
        if (proxy != null && proxy.authentication() != null) {
            String username = proxy.authentication().username();
            boolean proxyStored = context.proxyVault().has(AppContext.PROXY_KEY, username);
            security.add("Proxy-Passwort", proxyStored ? "im Tresor"
                    : context.proxyPasswordAvailable(username) ? "nur für diese Sitzung" : "fehlt", Comic.INK,
                    proxyStored ? deleteButton("Proxy-Passwort", () -> {
                        context.proxyVault().remove(AppContext.PROXY_KEY);
                        context.sessionProxyPassword(null, null);
                    }) : null);
        }
        for (AppSettings.SourceEntry source : settings.sources()) {
            String state;
            String tip = null;
            boolean stored = context.vault().has(source.id());
            String keePassEntry = settings.keePassEntry(source.id());
            if (stored) {
                state = "im Tresor" + (keePassEntry != null ? "; nicht mehr genutzt, die Quelle liest aus KeePass („"
                        + keePassEntry + "“)" : "");
            } else if (keePassEntry != null && source.secretMissing()) {
                state = "in KeePass („" + keePassEntry + "“)"
                        + (context.secrets().has(source) ? "" : "; noch nicht gelesen");
                tip = KEEPASS_TIP;
            } else if (source.secretMissing()) {
                state = context.secrets().has(source) ? "nur für diese Sitzung" : "fehlt";
            } else if (source.secretVariable() != null) {
                state = "aus ${" + source.secretVariable() + "}";
            } else {
                state = "keins nötig";
            }
            security.add("Zugang " + source.config().title(), state, Comic.INK,
                    stored ? deleteButton("Zugangsdaten von „" + source.config().title() + "“", () -> {
                        context.vault().remove(source.id());
                        context.secrets().remove(source.id());
                    }) : null, tip);
        }
        try {
            if (new ConfigFileEditor(context.loader()).fileHasPlaintextSecret(context.configFile())) {
                security.add("Konfigurationsdatei", "enthält ein Geheimnis im Klartext; bitte durch ${NAME} ersetzen",
                        Comic.RED);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // Nicht lesbar: steht schon unter Allgemein als Problem.
        }
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        render();
        ai.render();
    }

    /** Öffnet den Reiter „KI-Dienst“, z. B. vom Hinweis an der semantischen Suche. */
    public void showAi() {
        select(AI);
    }

    /** Für Tests. */
    AiSettingsPanel aiPanel() {
        return ai;
    }

    private void render() {
        Path config = context.configFile().toAbsolutePath();
        boolean exists = Files.isRegularFile(config);
        file.reset();
        file.add("Datei", config.toString())
                .add("Vorhanden", exists ? "ja" : "nein – es gelten die Standards ohne Quellen",
                        exists ? Comic.INK : Comic.ORANGE);
        create.setVisible(!exists);
        openFile.setEnabled(exists);
        AppSettings settings = context.settings();
        StringBuilder text = new StringBuilder();
        if (context.startProblem() != null) {
            Problems.Problem p = Problems.describe(context.startProblem());
            text.append("Start fehlgeschlagen: ").append(p.title()).append(". ").append(p.message()).append('\n');
        }
        if (settings != null) {
            settings.problems().forEach(problem -> text.append("• ").append(problem).append('\n'));
        }
        if (context.runtime() != null) {
            context.runtime().rejectedSources().forEach(problem -> text.append("• ").append(problem).append('\n'));
            context.runtime().lastRejection().forEach(problem -> text.append("• ").append(problem).append('\n'));
        }
        problems.setText(text.toString().strip());
        problems.setVisible(!text.isEmpty());

        storage.reset();
        network.reset();
        output.reset();
        resetOutput.setVisible(settings != null
                && !settings.outputDirectory().equals(AppSettings.defaultOutputDirectory()));
        if (settings != null) {
            output.add("Ordner", settings.outputDirectory().toString())
                    .add("Inhalt", "Dateien, die du unter Aufträge aus einem Download exportierst; ohne technische "
                            + "Zwischendateien");
            AppSettings.Storage s = settings.storage();
            storage.add("Daten (Cache, Aufträge, Analysen)", s.dataDirectory().toString())
                    .add("Exporte", s.exportDirectory().toString())
                    .add("Anhänge", s.assetDirectory().toString())
                    .add("Größter Anhang", Texts.bytes(s.maxAssetBytes()))
                    .add("Zeitgrenze der Suche", s.searchTimeout().toString().substring(2).toLowerCase(Locale.ROOT))
                    .add("Größter Seitenbaum", s.maxTreeNodes() + " Seiten")
                    .add("PDF-Schrift", s.pdfFont() == null ? "eingebaut" : s.pdfFont().toString());
            ProxySettings proxy = settings.proxy();
            network.add("Proxy", proxy == null ? "direkt" : proxy.mode().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            if (proxy != null && proxy.pacUrl() != null) {
                network.add("PAC-Adresse", proxy.pacUrl().toString());
            }
            if (proxy != null && proxy.proxyHost() != null) {
                network.add("Proxy-Host", proxy.proxyHost() + (proxy.proxyPort() == null ? "" : ":" + proxy.proxyPort()));
            }
            if (proxy != null && proxy.authentication() != null) {
                String user = proxy.authentication().username();
                network.add("Proxy-Anmeldung", user + (context.proxyPasswordAvailable(user) ? " · Passwort gespeichert"
                        : " · Passwort fehlt"));
            }
            if (proxy != null && proxy.pacDiscoveryScript() != null) {
                network.add("PAC-Skript", "eigenes Skript");
            }
            if (proxy != null && !proxy.nonProxyHosts().isEmpty()) {
                network.add("Ohne Proxy", String.join(", ", proxy.nonProxyHosts()));
            }
            network.add("Transport", settings.asyncTransport().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            network.add("Serverzertifikate", settings.tls().describe() + " (alle Quellen ohne eigenes Bundle)");
            settings.trustStores().values().forEach(store -> network.add("Vertrauensquelle " + store.name(),
                    (store.pem() != null ? "PEM " + store.pem() : store.location() + " (" + (store.type() == null
                            ? "Typ nach Endung" : store.type()) + ")")
                            + (store.password() == null ? "" : " · Passwort gesetzt")));
            for (AppSettings.SourceEntry source : settings.sources()) {
                var certificate = source.config().clientCertificate();
                if (certificate != null && certificate.enabled()) {
                    network.add("Client-Zertifikat " + source.id(),
                            certificate.type().name().toLowerCase(Locale.ROOT).replace('_', '-')
                                    + (certificate.path() == null ? "" : " · " + certificate.path())
                                    + (certificate.alias() == null ? "" : " · Alias " + certificate.alias())
                                    + (certificate.thumbprint() == null ? "" : " · Fingerabdruck "
                                    + Texts.shortHash(certificate.thumbprint())));
                }
            }
        }
        renderSecurity(settings);
        cache.reset();
        if (settings != null) {
            AppSettings.Cache c = settings.cache();
            cache.add(SettingDialogs.REFRESH_TITLE, SettingDialogs.refreshStrategy(c));
            if (!c.maxAge().isZero()) {
                cache.add(SettingDialogs.CACHE_MAX_AGE, ConfigFileEditor.duration(c.maxAge()) + " ohne Rückfrage, danach "
                        + (c.versionCheck() ? "Version prüfen" : "neu laden"));
            }
            java.time.Duration kept = c.withheldRetention();
            cache.add(SettingDialogs.WITHHELD_RETENTION, kept.equals(java.time.Duration.ofDays(kept.toDays()))
                    ? Texts.count(kept.toDays(), "Tag", "Tagen") : ConfigFileEditor.duration(kept));
            cache.add(SettingDialogs.BACKGROUND_TITLE, settings.sync().backgroundFill()
                    ? "an · " + settings.sync().backgroundParallelism() + " gleichzeitig" : "aus");
        }
        runtime.reset();
        AppRuntime current = context.runtime();
        runtime.add("App", context.version())
                .add("Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")")
                .add("System", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"))
                .add("Protokoll", logDirectory().resolve("space-harvester-app.log").toString())
                .add("Laufzeit", current == null ? "nicht gestartet" : "läuft", current == null ? Comic.RED : Comic.GREEN)
                .add("Quellen", current == null ? "–" : String.valueOf(current.workspace().sources().size()))
                .add("Warten auf Zugangsdaten", current == null || current.waitingForSecret().isEmpty() ? "keine"
                        : String.join(", ", current.waitingForSecret()));
        if (current != null) {
            runtime.add("Analysen", String.join(", ", current.workspace().enrichmentProcessors()))
                    .add("Exportformate", String.join(", ", current.workspace().exportFormats()));
        }
        root.revalidate();
        root.repaint();
    }

    private static JComponent withButtons(JComponent content, ComposerButton... buttons) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(content, BorderLayout.CENTER);
        panel.add(Comic.row(buttons), BorderLayout.SOUTH);
        return panel;
    }

    /** Wie logback.xml: {@code SPACE_HARVESTER_LOG_DIR} oder {@code <Benutzerordner>/.space-harvester/logs}. */
    static Path logDirectory() {
        String configured = System.getenv("SPACE_HARVESTER_LOG_DIR");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".space-harvester", "logs") : Path.of(configured);
    }

    private void reload() {
        if (!Dialogs.restartAllowed(root, context)) {
            return;
        }
        reload.setEnabled(false);
        feedback.working("Konfiguration wird neu geladen …");
        context.reload(() -> {
            reload.setEnabled(true);
            if (context.runtime() == null) {
                feedback.problem(context.startProblem());
            } else if (!context.runtime().lastRejection().isEmpty()) {
                feedback.warning("Nicht übernommen", "Der Core hat die Änderung abgelehnt; die bisherige Einstellung "
                        + "gilt weiter. Details unter Konfiguration.");
            } else if (!context.settings().problems().isEmpty()) {
                feedback.warning("Neu geladen, mit Hinweisen", "Quellen mit Fehlern fehlen, bis die Datei stimmt.");
            } else {
                feedback.success("Neu geladen");
            }
        });
    }

    private void createExample() {
        Path target = context.configFile().toAbsolutePath();
        Background.execute(() -> {
            Files.createDirectories(target.getParent());
            try (InputStream in = SettingsView.class.getResourceAsStream(EXAMPLE)) {
                if (in == null) {
                    throw new IOException("Beispielkonfiguration fehlt im JAR");
                }
                Files.copy(in, target);
            }
        }, () -> {
            feedback.success("Beispiel angelegt: " + target + ". Datei anpassen, dann „Neu laden“.");
            render();
            open(target);
        }, feedback::problem);
    }

    /** Ein Ordnerdialog genau hier, einmalig; „Exportieren“ unter Aufträge fragt nie nach einem Ordner. */
    private void chooseOutput() {
        AppSettings settings = context.settings();
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(
                settings == null ? null : settings.outputDirectory().toFile());
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Ausgabeordner wählen");
        if (chooser.showDialog(root, "Übernehmen") == javax.swing.JFileChooser.APPROVE_OPTION) {
            saveOutput(chooser.getSelectedFile().toPath());
        }
    }

    /** @param directory neuer Ordner oder {@code null} für Downloads */
    void saveOutput(Path directory) {
        feedback.working("Ausgabeordner wird gespeichert …");
        context.outputDirectory(directory, () -> feedback.success("Ausgabeordner: "
                + context.settings().outputDirectory()), feedback::problem);
    }

    private void open(Path path) {
        open(path, feedback);
    }

    /** Öffnet einen Ordner oder den Ordner einer Datei im Dateimanager; legt einen fehlenden Ordner an. */
    static void open(Path path, Feedback feedback) {
        if (path == null) {
            return;
        }
        Background.execute(() -> {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Öffnen wird von diesem System nicht unterstützt");
            }
            Files.createDirectories(Files.isRegularFile(path) ? path.getParent() : path);
            Desktop.getDesktop().open(path.toFile());
        }, () -> { }, feedback::problem);
    }
}
