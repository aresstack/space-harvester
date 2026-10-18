package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
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
 * Einstellungen und Diagnose in Reitern wie im Enterprise AI Client: „Allgemein“ (welche Datei gilt, was daran nicht
 * stimmt, wo die Daten liegen, Versionen), „KI-Dienst“ und „Modelle“ ({@link AiSettingsPanel}, zentral für alle
 * Quellen), „Netzwerk“ (Proxy und Serverzertifikate) und „Cache“ (Aktualisierung gespeicherter Seiten, Frist für gesperrte
 * Seiten). Geheimnisse erscheinen nur als „gesetzt“ oder „fehlt“. Jede Änderung schreibt die Datei und startet die
 * Laufzeit mit ihr neu; von Hand geänderte Dateien übernimmt „Neu laden“.
 */
public final class SettingsView implements View {

    static final String GENERAL = "Allgemein";
    static final String AI = "KI-Dienst";
    static final String MODELS = "Modelle";
    static final String NETWORK = "Netzwerk";
    static final String CACHE = "Cache";
    static final String EXAMPLE = "/com/aresstack/spaceharvester/app/space-harvester-app.example.yml";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final Facts file = new Facts();
    private final JTextArea problems = Comic.note("");
    private final Facts storage = new Facts();
    private final Facts network = new Facts();
    private final Facts cache = new Facts();
    private final ComposerButton editCache = Comic.button("Cache und Aufbewahrung …",
            "Aktualisierung gespeicherter Seiten und Frist für gesperrte Seiten einstellen");
    private final PillTabs tabs = new PillTabs();
    private final AiSettingsPanel ai;
    private final Facts runtime = new Facts();
    private final Feedback feedback = new Feedback();
    private final ComposerButton reload = Comic.primary("Neu laden", "Konfiguration neu lesen und Laufzeit neu starten");
    private final ComposerButton create = Comic.button("Beispiel anlegen", "Beispielkonfiguration an diesem Ort anlegen");
    private final ComposerButton openFile = Comic.button("Datei öffnen", "Konfiguration im Standardeditor öffnen");
    private final ComposerButton openFolder = Comic.button("Ordner öffnen", "Ordner der Konfiguration öffnen");
    private final ComposerButton openData = Comic.button("Datenordner öffnen", "Ordner des lokalen Bestands öffnen");
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
        tabs.tab(GENERAL, Comic.column(
                Comic.plate("Konfiguration", Comic.BLUE, config),
                Comic.plate("Ausgabeordner", Comic.GREEN, outputPanel),
                Comic.plate("Ablage", Comic.PETROL, data),
                Comic.plate("Diagnose", Comic.DARK, diagnosis)));
        tabs.tab(AI, Comic.column(Comic.plate("KI-Dienst", Comic.YELLOW, ai.serviceComponent())));
        tabs.tab(MODELS, Comic.column(Comic.plate("Modelle für alle Quellen", Comic.YELLOW, ai.modelsComponent())));
        tabs.tab(NETWORK, Comic.column(Comic.plate("Netz und Zertifikate", Comic.ORANGE,
                withButtons(network, proxy, tls))));
        tabs.tab(CACHE, Comic.column(Comic.plate("Cache und Aufbewahrung", Comic.PETROL,
                withButtons(cache, editCache))));
        tabs.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        root.add(tabs, BorderLayout.CENTER);
        context.onChange(ignored -> {
            render();
            // Eingaben in den offenen Reitern „KI-Dienst“ und „Modelle“ bleiben stehen; beim nächsten Öffnen gilt der
            // neue Stand.
            if (!AI.equals(tabs.selected()) && !MODELS.equals(tabs.selected())) {
                ai.render();
            }
        });
        tabs.onSelect(name -> {
            if ((AI.equals(name) || MODELS.equals(name)) && !ai.changed()) {
                ai.render();
            }
        });
        render();
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
        tabs.select(AI);
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
        cache.reset();
        if (settings != null) {
            AppSettings.Cache c = settings.cache();
            cache.add(SettingDialogs.REFRESH_TITLE, SettingDialogs.refreshStrategy(c));
            if (!c.maxAge().isZero()) {
                cache.add(SettingDialogs.CACHE_MAX_AGE, ConfigFileEditor.duration(c.maxAge()) + " ohne Rückfrage, danach "
                        + (c.versionCheck() ? "Version prüfen" : "neu laden"));
            }
            cache.add(SettingDialogs.WITHHELD_RETENTION, Texts.count(c.withheldRetention().toDays(), "Tag",
                            "Tagen"));
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
