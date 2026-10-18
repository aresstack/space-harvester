package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
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
 * Einstellungen und Diagnose in zwei Reitern: „Allgemein“ (welche Datei gilt, was daran nicht stimmt, wo die Daten
 * liegen, Netz, Zertifikate und Versionen) und „KI“ (KI-Dienst für die semantische Suche, {@link AiSettingsPanel}). Geheimnisse erscheinen nur als „gesetzt“ oder „fehlt“; die Datei wird außerhalb
 * der App bearbeitet und mit „Neu laden“ übernommen.
 */
public final class SettingsView implements View {

    static final String GENERAL = "Allgemein";
    static final String AI = "KI";
    static final String EXAMPLE = "/com/aresstack/spaceharvester/app/space-harvester-app.example.yml";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final Facts file = new Facts();
    private final JTextArea problems = Comic.note("");
    private final Facts storage = new Facts();
    private final Facts network = new Facts();
    private final PillTabs tabs = new PillTabs();
    private final AiSettingsPanel ai;
    private final Facts runtime = new Facts();
    private final Feedback feedback = new Feedback();
    private final ComposerButton reload = Comic.primary("Neu laden", "Konfiguration neu lesen und Laufzeit neu starten");
    private final ComposerButton create = Comic.button("Beispiel anlegen", "Beispielkonfiguration an diesem Ort anlegen");
    private final ComposerButton openFile = Comic.button("Datei öffnen", "Konfiguration im Standardeditor öffnen");
    private final ComposerButton openFolder = Comic.button("Ordner öffnen", "Ordner der Konfiguration öffnen");
    private final ComposerButton openData = Comic.button("Datenordner öffnen", "Ordner des lokalen Bestands öffnen");
    private final ComposerButton about = Comic.button("Über Space Harvester", "Version und Herkunft");
    private final ComposerButton proxy = Comic.button("Proxy ändern …", "Proxy für alle Quellen einstellen und auflösen");

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
        about.addActionListener(e -> AboutDialog.open(root, context));
        proxy.addActionListener(e -> SettingDialogs.proxy(root, context, null,
                () -> feedback.success("Proxy übernommen")).open());
        JPanel config = new JPanel(new BorderLayout(0, 8));
        config.setOpaque(false);
        config.add(file, BorderLayout.NORTH);
        config.add(problems, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(reload, create, openFile, openFolder), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        config.add(south, BorderLayout.SOUTH);
        JPanel data = new JPanel(new BorderLayout(0, 8));
        data.setOpaque(false);
        data.add(storage, BorderLayout.CENTER);
        data.add(Comic.row(openData), BorderLayout.SOUTH);
        JPanel diagnosis = new JPanel(new BorderLayout(0, 8));
        diagnosis.setOpaque(false);
        diagnosis.add(runtime, BorderLayout.CENTER);
        diagnosis.add(Comic.row(about), BorderLayout.SOUTH);
        tabs.tab(GENERAL, Comic.column(
                Comic.plate("Konfiguration", Comic.BLUE, config),
                Comic.plate("Ablage", Comic.PETROL, data),
                Comic.plate("Netz und Zertifikate", Comic.ORANGE, withButtons(network, proxy)),
                Comic.plate("Diagnose", Comic.DARK, diagnosis)));
        tabs.tab(AI, Comic.column(Comic.plate("KI-Dienst", Comic.YELLOW, ai.component())));
        tabs.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        root.add(tabs, BorderLayout.CENTER);
        context.onChange(ignored -> {
            render();
            // Eingaben im offenen Reiter „KI“ bleiben stehen; beim nächsten Öffnen gilt der neue Stand.
            if (!AI.equals(tabs.selected())) {
                ai.render();
            }
        });
        tabs.onSelect(name -> {
            if (AI.equals(name)) {
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

    /** Öffnet den Reiter „KI“, z. B. vom Hinweis an der semantischen Suche. */
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
        if (settings != null) {
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
            if (proxy != null && !proxy.nonProxyHosts().isEmpty()) {
                network.add("Ohne Proxy", String.join(", ", proxy.nonProxyHosts()));
            }
            network.add("Transport", settings.asyncTransport().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            if (settings.trustStores().isEmpty()) {
                network.add("Vertrauensquellen", "nur die der Java-Laufzeit");
            }
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

    private void open(Path path) {
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
