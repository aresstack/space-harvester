package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.app.ui.brand.BrandImages;
import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Einrichtungsassistent nach Vorbild des Enterprise AI Client ({@code SetupWizardPanel}): öffnet sich beim ersten
 * Start und danach über Einstellungen → „Einrichtungsassistent …“. Statt Merlin spricht das Space-Harvester-Schiff in
 * einer Sprechblase; oben steht die Schrittleiste. Reihenfolge: Wissensquellen, Netzwerk, Modelle, KI-Dienst,
 * Abschluss. Jede Einstellung erscheint genau einmal: Quellen über das {@link SourceForm} (Anmeldung und
 * Client-Zertifikat gehören zur Quelle), Proxy und Serverzertifikate im Schritt Netzwerk, Modelle und KI-Dienst in
 * ihren Schritten. Ein Häkchen in der Schrittleiste heißt „eingerichtet und geprüft“, nicht „angesehen“. Ob „Fertig“
 * oder „Später“: Danach merkt sich die Datei {@code space-harvester-app.setup-completed}, und der Assistent kommt beim
 * Start nicht wieder.
 */
final class SetupWizard {

    static final String TITLE = "Einrichtungsassistent";
    static final int SOURCES = 0;
    static final int NETWORK = 1;
    static final int AI = 2;
    static final int MODELS = 3;
    static final int SUMMARY = 4;
    static final String[] STEPS = {"Wissensquellen", "Netzwerk", "KI-Dienst", "Modelle", "Abschluss"};
    /** Was das Schiff je Schritt sagt. */
    static final String[] LINES = {
            "Willkommen an Bord! Zuerst die Wissensquellen, zum Beispiel ein Confluence. Ohne eigene Adresse nehme "
                    + "ich die öffentliche Confluence-Hilfe von Atlassian.",
            "Proxy und Serverzertifikate gelten für alle Wissensquellen und den KI-Dienst. „Verbindung testen“ "
                    + "versucht es mit deinen Quellen.",
            "Optional: ein OpenAI-kompatibler KI-Dienst für die semantische Suche. Der API-Key liegt nur im Tresor "
                    + "dieses Windows-Kontos, nie in der Konfigurationsdatei.",
            "Die Modelle gelten zentral für alle Quellen. Ich lade sie beim Dienst und wähle die Vorzugsmodelle vor; "
                    + "andere wählst du über ▾.",
            "Geschafft! Gesucht wird per Schlagwort im Cache; die semantische Suche schaltest du in der Suche per "
                    + "Haken ein. Den Assistenten findest du unter Einstellungen wieder."
    };
    private static final int SHIP_WIDTH = 190;

    private final AppContext context;
    private final AiSettingsPanel ai;
    private final JPanel root = new JPanel(new BorderLayout(14, 10));
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    final SetupStepTrail trail = new SetupStepTrail(STEPS);
    final SpeechBubblePanel bubble = new SpeechBubblePanel(BubbleSide.LEFT, Comic.SURFACE, Comic.INK,
            "Space Harvester", LINES[0]);
    /** Je Quelle eine Zeile mit Stand und „Bearbeiten …“. */
    final JPanel sources = new JPanel();
    final Facts network = new Facts();
    /** Ergebnis von „Verbindung testen“ je Quelle. */
    final Facts tests = new Facts();
    final Facts summary = new Facts();
    final Feedback feedback = new Feedback();
    final ComposerButton addSource = Comic.primary("Quelle hinzufügen …",
            "Quelle in einem Formular anlegen, Verbindung testen und speichern");
    final ComposerButton proxy = Comic.button("Proxy ändern …", "Proxy für alle Quellen einstellen und auflösen");
    final ComposerButton tls = Comic.button("Serverzertifikate …",
            "Festlegen, welchen Zertifizierungsstellen die App bei allen Quellen glaubt");
    final ComposerButton testConnections = Comic.button("Verbindung testen",
            "Mit Proxy und Serverzertifikaten zu jeder eingerichteten Quelle verbinden");
    final ComposerButton later = Comic.button("Später", "Assistent schließen; erreichbar unter Einstellungen");
    final ComposerButton back = Comic.button("Zurück", null);
    final ComposerButton next = Comic.primary("Weiter", null);
    final ComposerButton finish = Comic.primary("Fertig", "Einrichtung abschließen");
    /** Ergebnis des letzten Tests je Quelle in diesem Assistenten. */
    private final Map<String, Boolean> tested = new LinkedHashMap<>();
    private int step;
    private boolean closed;
    private Runnable close = () -> { };

    SetupWizard(AppContext context) {
        this.context = context;
        this.ai = new AiSettingsPanel(context);
        root.setOpaque(false);
        root.setPreferredSize(new Dimension(900, 600));

        sources.setOpaque(false);
        sources.setLayout(new BoxLayout(sources, BoxLayout.Y_AXIS));
        JPanel sourcesPage = new JPanel(new BorderLayout(0, 10));
        sourcesPage.setOpaque(false);
        sourcesPage.add(Comic.note("Richte deine erste Wissensquelle ein oder prüfe die vorhandenen. Anmeldung und "
                + "Client-Zertifikat gehören zur Quelle und stehen in ihrem Formular; alles lässt sich später unter "
                + "Wissensquellen ändern."), BorderLayout.NORTH);
        sourcesPage.add(sources, BorderLayout.CENTER);
        sourcesPage.add(Comic.row(addSource), BorderLayout.SOUTH);
        JPanel networkPage = new JPanel(new BorderLayout(0, 10));
        networkPage.setOpaque(false);
        networkPage.add(network, BorderLayout.NORTH);
        networkPage.add(tests, BorderLayout.CENTER);
        networkPage.add(Comic.row(proxy, tls, testConnections), BorderLayout.SOUTH);

        pages.setOpaque(false);
        pages.add(page(sourcesPage), STEPS[SOURCES]);
        pages.add(page(networkPage), STEPS[NETWORK]);
        pages.add(page(ai.serviceComponent()), STEPS[AI]);
        pages.add(page(ai.modelsComponent()), STEPS[MODELS]);
        pages.add(page(summary), STEPS[SUMMARY]);

        addSource.addActionListener(e -> SourceForm.open(root, context, this::sourceSaved));
        proxy.addActionListener(e -> SettingDialogs.proxy(root, context, null, () -> {
            feedback.success("Proxy übernommen");
            tested.clear();
            refresh();
        }).open());
        tls.addActionListener(e -> SettingDialogs.tls(root, context, () -> {
            feedback.success("Serverzertifikate übernommen");
            tested.clear();
            refresh();
        }).open());
        testConnections.addActionListener(e -> testConnections(() -> { }));
        // Verbindungsstand und neu geladene Einstellungen ändern Liste und Häkchen.
        context.onChange(changed -> {
            if (!closed) {
                refresh();
            }
        });

        JLabel ship = new JLabel(shipIcon());
        ship.setAlignmentX(Component.CENTER_ALIGNMENT);
        bubble.setMaximumBubbleWidth(SHIP_WIDTH + 40);
        JPanel mascot = new JPanel(new BorderLayout(0, 6));
        mascot.setOpaque(false);
        mascot.add(bubble, BorderLayout.NORTH);
        mascot.add(ship, BorderLayout.SOUTH);
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.add(mascot, BorderLayout.SOUTH);

        trail.setNavigable(true);
        trail.setOnSelect(this::show);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(later);
        buttons.add(back);
        buttons.add(next);
        buttons.add(finish);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(feedback, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(trail, BorderLayout.NORTH);
        center.add(pages, BorderLayout.CENTER);
        center.add(south, BorderLayout.SOUTH);
        root.add(west, BorderLayout.WEST);
        root.add(center, BorderLayout.CENTER);

        back.addActionListener(e -> show(step - 1));
        next.addActionListener(e -> show(step + 1));
        finish.addActionListener(e -> close.run());
        later.addActionListener(e -> close.run());
        show(0);
    }

    /** Öffnet den Assistenten modal; beim Schließen merkt sich die Datei, dass er lief. */
    static void open(Component parent, AppContext context) {
        SetupWizard wizard = new SetupWizard(context);
        JDialog dialog = Dialogs.dialog(parent, TITLE);
        wizard.close = dialog::dispose;
        Dialogs.frame(dialog, TITLE, wizard.component(), dialog::dispose);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                wizard.closed = true;
                wizard.completed();
            }
        });
        Dialogs.present(dialog, new Dimension(860, 600));
    }

    /** Beim Start: nur, solange die Datei keinen Abschluss kennt. */
    static boolean due(AppContext context) {
        AppSettings settings = context.settings();
        return settings != null && !settings.setupCompleted();
    }

    JComponent component() {
        return root;
    }

    int step() {
        return step;
    }

    void show(int index) {
        step = Math.max(0, Math.min(STEPS.length - 1, index));
        cards.show(pages, STEPS[step]);
        bubble.setText(LINES[step]);
        back.setEnabled(step > 0);
        next.setVisible(step < STEPS.length - 1);
        finish.setVisible(step == STEPS.length - 1);
        if ((step == MODELS || step == AI) && !ai.changed()) {
            ai.render();
        }
        if (step == MODELS) {
            ai.prepareModels();
        }
        refresh();
    }

    /** Liest den aktuellen Stand neu: Übersichten und Häkchen der Schrittleiste. */
    void refresh() {
        render();
        trail.show(step, done());
        root.revalidate();
        root.repaint();
    }

    /**
     * Welche Schritte eingerichtet und geprüft sind: Wissensquellen, wenn jede Quelle verbunden ist; Netzwerk, wenn
     * eine Verbindung gelang; Modelle mit Embedding-Modell und gemessener Dimension; KI-Dienst mit Adresse und
     * API-Key. Der Abschluss ist nie „erledigt“, er ist die Übersicht.
     */
    boolean[] done() {
        boolean[] done = new boolean[STEPS.length];
        AppSettings settings = context.settings();
        if (settings == null) {
            return done;
        }
        List<AppSettings.SourceEntry> entries = settings.sources();
        boolean allConnected = !entries.isEmpty() && entries.stream().allMatch(entry -> connected(entry.id()));
        done[SOURCES] = allConnected;
        done[NETWORK] = entries.stream().anyMatch(entry -> connected(entry.id()));
        done[MODELS] = settings.ai().embeddingModel() != null && settings.ai().embeddingDimensions() != null;
        done[AI] = settings.ai().configured() && context.aiKeyAvailable();
        return done;
    }

    private boolean connected(String sourceId) {
        Boolean test = tested.get(sourceId);
        return test != null ? test : context.connection(sourceId).state() == AppContext.Connection.State.CONNECTED;
    }

    /** Liest den aktuellen Stand der Datei in die Übersichten. */
    void render() {
        AppSettings settings = context.settings();
        sources.removeAll();
        network.reset();
        summary.reset();
        if (settings == null) {
            network.add("Konfiguration", "noch nicht geladen");
            sources.add(Comic.muted("Konfiguration noch nicht geladen"));
            return;
        }
        if (settings.sources().isEmpty()) {
            sources.add(Comic.muted("Noch keine Wissensquelle eingerichtet."));
        }
        for (AppSettings.SourceEntry entry : settings.sources()) {
            sources.add(sourceRow(entry));
        }
        sources.revalidate();
        sources.repaint();
        String proxyMode = SettingDialogs.proxyLabel(settings.proxy().mode());
        network.add("Proxy", proxyMode);
        if (settings.proxy().pacUrl() != null) {
            network.add("PAC-Adresse", settings.proxy().pacUrl().toString());
        }
        network.add("Serverzertifikate", settings.tls().describe());
        testConnections.setEnabled(context.runtime() != null && !settings.sources().isEmpty());

        for (AppSettings.SourceEntry entry : settings.sources()) {
            Status status = status(entry);
            summary.add(name(entry), status.text(), status.color());
        }
        if (settings.sources().isEmpty()) {
            summary.add("Wissensquellen", "noch keine", Comic.MUTED);
        }
        summary.add("Proxy", proxyMode);
        summary.add("Serverzertifikate", settings.tls().describe());
        summary.add("Embedding-Modell", blank(settings.ai().embeddingModel()));
        summary.add("Rerank-Modell", blank(settings.ai().rerankModel()));
        summary.add("KI-Dienst", settings.ai().configured() ? settings.ai().baseUrl() : "nicht eingerichtet (optional)");
        summary.add("API-Key", context.aiKeyAvailable() ? "im Tresor" : "fehlt");
        summary.add("Freigegeben für den KI-Dienst",
                AiSettingsPanel.releasedText(settings, settings.ai().externalProcessingSources()));
        summary.add("Konfigurationsdatei", context.configFile().toAbsolutePath().toString());
    }

    /** Eine Quelle mit ihrem Stand und „Bearbeiten …“. */
    private JComponent sourceRow(AppSettings.SourceEntry entry) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(name(entry));
        name.setFont(Comic.semiBold(13f));
        name.setForeground(Comic.INK);
        Status status = status(entry);
        JLabel state = Comic.muted(status.text());
        state.setForeground(status.color());
        JPanel text = new JPanel(new BorderLayout());
        text.setOpaque(false);
        text.add(name, BorderLayout.NORTH);
        text.add(state, BorderLayout.SOUTH);
        ComposerButton edit = Comic.button("Bearbeiten …", "Formular dieser Quelle öffnen");
        edit.addActionListener(e -> edit(entry));
        row.add(text, BorderLayout.CENTER);
        row.add(edit, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private record Status(String text, Color color) {
    }

    private Status status(AppSettings.SourceEntry entry) {
        AppRuntime runtime = context.runtime();
        if (context.settings() != null && context.settings().disabled(entry.id())) {
            return new Status("abgeschaltet", Comic.MUTED);
        }
        if (runtime != null && runtime.waitingForSecret().contains(entry.id())) {
            return new Status("Eingerichtet · wartet auf Zugangsdaten", Comic.ORANGE);
        }
        Boolean test = tested.get(entry.id());
        if (test != null) {
            return test ? new Status("Eingerichtet · Verbindung erfolgreich", Comic.GREEN)
                    : new Status("Eingerichtet · Verbindung fehlgeschlagen", Comic.RED);
        }
        return switch (context.connection(entry.id()).state()) {
            case CONNECTED -> new Status("Eingerichtet · Verbindung erfolgreich", Comic.GREEN);
            case CHECKING -> new Status("Eingerichtet · Verbindung wird geprüft …", Comic.MUTED);
            case FAILED -> new Status("Eingerichtet · Verbindung fehlgeschlagen", Comic.RED);
            case NOT_CHECKED -> new Status("Eingerichtet · Prüfung ausstehend", Comic.MUTED);
        };
    }

    private static String name(AppSettings.SourceEntry entry) {
        String title = entry.config().title();
        return title == null || title.isBlank() ? entry.id() : title + " (" + entry.id() + ")";
    }

    private void sourceSaved(String id) {
        feedback.success("Wissensquelle gespeichert");
        tested.remove(id);
        context.checkConnection(id, () -> { });
        refresh();
    }

    private void edit(AppSettings.SourceEntry entry) {
        SourceDraft.Editable editable;
        try {
            editable = new ConfigFileEditor(context.loader()).editable(context.configFile(), entry.id());
        } catch (IOException | RuntimeException e) {
            feedback.problem(e);
            return;
        }
        SourceForm.open(root, context, editable, this::sourceSaved);
    }

    /**
     * „Verbindung testen“: ein echter Verbindungsversuch je Quelle über Proxy-Route, Namensauflösung, TCP, TLS,
     * Anmeldung und API ({@code SourceManagement.diagnose}, #189); das Ergebnis steht eindeutig je Quelle da.
     */
    void testConnections(Runnable done) {
        AppRuntime runtime = context.runtime();
        AppSettings settings = context.settings();
        if (runtime == null || settings == null || settings.sources().isEmpty()) {
            done.run();
            return;
        }
        List<AppSettings.SourceEntry> entries = settings.sources();
        testConnections.setEnabled(false);
        feedback.working("Verbindungen werden getestet …");
        Background.run(() -> {
            Map<AppSettings.SourceEntry, Optional<SourceDiagnosis>> results = new LinkedHashMap<>();
            for (AppSettings.SourceEntry entry : entries) {
                try {
                    results.put(entry, runtime.workspace().diagnose(entry.id()));
                } catch (RuntimeException e) {
                    results.put(entry, Optional.empty());
                }
            }
            return results;
        }, results -> {
            testConnections.setEnabled(true);
            tests.reset();
            int failed = 0;
            for (Map.Entry<AppSettings.SourceEntry, Optional<SourceDiagnosis>> result : results.entrySet()) {
                String id = result.getKey().id();
                if (result.getValue().isEmpty()) {
                    tests.add(name(result.getKey()), "nicht prüfbar: abgeschaltet oder ohne Zugangsdaten",
                            Comic.ORANGE);
                    failed++;
                    continue;
                }
                SourceDiagnosis diagnosis = result.getValue().get();
                tested.put(id, diagnosis.successful());
                if (diagnosis.successful()) {
                    tests.add(name(result.getKey()), "Verbindung erfolgreich", Comic.GREEN);
                } else {
                    failed++;
                    ConnectionStep step = diagnosis.failure().orElseThrow();
                    tests.add(name(result.getKey()), "Verbindung fehlgeschlagen · " + step.stage().label()
                            + (step.message() == null ? "" : ": " + step.message()), Comic.RED);
                }
            }
            if (failed == 0) {
                feedback.success("Verbindung erfolgreich");
            } else {
                feedback.warning("Verbindung fehlgeschlagen", Texts.count(failed, "Quelle", "Quellen")
                        + " nicht erreichbar; Hinweise unter Wissensquellen › Verbindung prüfen …");
            }
            refresh();
            done.run();
        }, error -> {
            testConnections.setEnabled(true);
            feedback.problem(error);
            done.run();
        });
    }

    private void completed() {
        if (context.settings() != null && context.settings().setupCompleted()) {
            return;
        }
        context.setupCompleted(() -> { }, feedback::problem);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "–" : value;
    }

    private static JComponent page(JComponent content) {
        return Comic.column(content);
    }

    private static ImageIcon shipIcon() {
        BufferedImage ship = BrandImages.ship();
        int height = Math.max(1, ship.getHeight() * SHIP_WIDTH / Math.max(1, ship.getWidth()));
        return new ImageIcon(BrandImages.scale(ship, SHIP_WIDTH, height));
    }
}
