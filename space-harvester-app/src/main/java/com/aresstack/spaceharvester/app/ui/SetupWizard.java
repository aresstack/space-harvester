package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.ui.brand.BrandImages;
import com.aresstack.spaceharvester.source.network.ProxySettings;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Einrichtungsassistent nach Vorbild des Enterprise AI Client ({@code SetupWizardPanel}): öffnet sich beim ersten
 * Start und danach über Einstellungen → „Einrichtungsassistent …“. Statt Merlin spricht das Space-Harvester-Schiff in
 * einer Sprechblase; oben steht die Schrittleiste. Die Schritte nutzen dieselben Bausteine wie die Einstellungen
 * (Proxy, Serverzertifikate, KI-Dienst, Modelle, Quellen-Assistent): Jeder speichert sofort in die
 * Konfigurationsdatei, und die App übernimmt es zur Laufzeit. Ob „Fertig“ oder „Später“: Danach merkt sich die Datei
 * {@code space-harvester-app.setup-completed}, und der Assistent kommt beim Start nicht wieder.
 */
final class SetupWizard {

    static final String TITLE = "Einrichtungsassistent";
    static final String[] STEPS = {"Willkommen", "Netzwerk", "KI-Dienst", "Modelle", "Wissensquelle", "Abschluss"};
    /** Was das Schiff je Schritt sagt. */
    static final String[] LINES = {
            "Willkommen an Bord! Ich helfe dir, Space Harvester einzurichten. Jeder Schritt lässt sich überspringen "
                    + "und später unter Einstellungen nachholen.",
            "Erst das Netz: Proxy und Serverzertifikate gelten für alle Wissensquellen und den KI-Dienst. Unter "
                    + "Windows ist die PAC-Adresse der Interneteinstellungen vorgegeben.",
            "Optional: ein OpenAI-kompatibler KI-Dienst für die semantische Suche. Der API-Key liegt nur im Tresor "
                    + "dieses Windows-Kontos, nie in der Konfigurationsdatei.",
            "Die Modelle gelten zentral für alle Quellen. „Modelle laden“ fragt den Dienst ab; Embedding- und "
                    + "Rerank-Modell wählst du über ▾.",
            "Jetzt die erste Wissensquelle, zum Beispiel ein Confluence. Ohne eigene Adresse nehme ich die öffentliche "
                    + "Confluence-Hilfe von Atlassian.",
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
    final Facts network = new Facts();
    final Facts sources = new Facts();
    final Facts summary = new Facts();
    final Feedback feedback = new Feedback();
    final ComposerButton proxy = Comic.button("Proxy ändern …", "Proxy für alle Quellen einstellen und auflösen");
    final ComposerButton tls = Comic.button("Serverzertifikate …",
            "Festlegen, welchen Zertifizierungsstellen die App bei allen Quellen glaubt");
    final ComposerButton addSource = Comic.primary("Wissensquelle hinzufügen …",
            "Quelle anlegen, Verbindung testen und speichern");
    final ComposerButton later = Comic.button("Später", "Assistent schließen; erreichbar unter Einstellungen");
    final ComposerButton back = Comic.button("Zurück", null);
    final ComposerButton next = Comic.primary("Weiter", null);
    final ComposerButton finish = Comic.primary("Fertig", "Einrichtung abschließen");
    private final boolean[] visited = new boolean[STEPS.length];
    private int step;
    private Runnable close = () -> { };

    SetupWizard(AppContext context) {
        this.context = context;
        this.ai = new AiSettingsPanel(context);
        root.setOpaque(false);
        root.setPreferredSize(new Dimension(900, 600));

        pages.setOpaque(false);
        pages.add(page(Comic.note("Space Harvester sammelt Seiten aus Wissensquellen wie Confluence in einen lokalen "
                + "Cache, durchsucht sie dort und exportiert sie als PDF, Word, Markdown oder JSON.\n\n"
                + "Die nächsten Schritte: Netzwerk, KI-Dienst (optional), Modelle, erste Wissensquelle. Alles, was du "
                + "hier einstellst, steht in der Konfigurationsdatei und gilt sofort; Geheimnisse liegen nur im "
                + "Tresor.")), STEPS[0]);
        pages.add(page(withButtons(network, proxy, tls)), STEPS[1]);
        pages.add(page(ai.serviceComponent()), STEPS[2]);
        pages.add(page(ai.modelsComponent()), STEPS[3]);
        pages.add(page(withButtons(sources, addSource)), STEPS[4]);
        pages.add(page(summary), STEPS[5]);

        proxy.addActionListener(e -> SettingDialogs.proxy(root, context, null, () -> {
            feedback.success("Proxy übernommen");
            render();
        }).open());
        tls.addActionListener(e -> SettingDialogs.tls(root, context, () -> {
            feedback.success("Serverzertifikate übernommen");
            render();
        }).open());
        addSource.addActionListener(e -> SourceWizard.open(root, context, id -> {
            feedback.success("Wissensquelle gespeichert");
            render();
        }));

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
        visited[step] = true;
        render();
        cards.show(pages, STEPS[step]);
        bubble.setText(LINES[step]);
        trail.show(step, visited.clone());
        back.setEnabled(step > 0);
        next.setVisible(step < STEPS.length - 1);
        finish.setVisible(step == STEPS.length - 1);
        if ((step == 2 || step == 3) && !ai.changed()) {
            ai.render();
        }
        root.revalidate();
        root.repaint();
    }

    /** Liest den aktuellen Stand der Datei in die Übersichten. */
    void render() {
        AppSettings settings = context.settings();
        network.reset();
        sources.reset();
        summary.reset();
        if (settings == null) {
            network.add("Konfiguration", "noch nicht geladen");
            return;
        }
        String proxyMode = proxyLabel(settings.proxy());
        network.add("Proxy", proxyMode);
        if (settings.proxy().pacUrl() != null) {
            network.add("PAC-Adresse", settings.proxy().pacUrl().toString());
        }
        network.add("Serverzertifikate", settings.tls().describe());
        if (settings.sources().isEmpty()) {
            sources.add("Wissensquellen", "noch keine");
        } else {
            settings.sources().forEach(source -> sources.add(source.config().title(), source.config().id()));
        }
        summary.add("Proxy", proxyMode);
        summary.add("KI-Dienst", settings.ai().configured() ? settings.ai().baseUrl() : "nicht eingerichtet (optional)");
        summary.add("Embedding-Modell", blank(settings.ai().embeddingModel()));
        summary.add("Rerank-Modell", blank(settings.ai().rerankModel()));
        summary.add("Wissensquellen", Texts.count(settings.sources().size(), "Quelle", "Quellen"));
        summary.add("Konfigurationsdatei", context.configFile().toAbsolutePath().toString());
    }

    private void completed() {
        if (context.settings() != null && context.settings().setupCompleted()) {
            return;
        }
        context.setupCompleted(() -> { }, feedback::problem);
    }

    private static String proxyLabel(ProxySettings proxy) {
        return proxy == null ? "direkt" : proxy.mode().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "–" : value;
    }

    private static JComponent page(JComponent content) {
        return Comic.column(content);
    }

    private static JPanel withButtons(JComponent facts, ComposerButton... buttons) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(facts, BorderLayout.CENTER);
        panel.add(Comic.row(buttons), BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return panel;
    }

    private static ImageIcon shipIcon() {
        BufferedImage ship = BrandImages.ship();
        int height = Math.max(1, ship.getHeight() * SHIP_WIDTH / Math.max(1, ship.getWidth()));
        return new ImageIcon(BrandImages.scale(ship, SHIP_WIDTH, height));
    }
}
