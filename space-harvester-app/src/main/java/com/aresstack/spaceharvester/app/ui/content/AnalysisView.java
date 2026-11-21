package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.RelationTab;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.CopyButton;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageSet;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Der Unterreiter „Analyse“ des Technik-Reiters (BG2b, Angelo 17.11.2026): EINE Ansicht ohne weitere Reiter. Oben die
 * Analyseschritte der Seite mit deutschem Namen, Ergebnis, Stand nach der Ampel und Zeitpunkt; ein Klick auf eine Zeile
 * zeigt das Technische (Kennung, Version, Warnungen, Rohergebnis) als Overlay am Mausklick. Darunter die Beziehungen
 * der Auswahl links ({@link RelationTab}) mit ihrer Gesamtzahl aus dem Core. Auf den Oberseiten (Ordner, Space, Quelle,
 * Wissensquellen) dieselbe Komponente ohne die Schritte. Gelesen wird erst, wenn die Ansicht zu sehen ist; alles
 * Lesen im Hintergrund. Nur auf dem EDT.
 */
final class AnalysisView extends JPanel {

    static final String RELATIONS = "Beziehungen";
    static final String COUNTING = "…";
    static final String NO_STEPS = "Noch keine Analysen für diese Seite. Sie entstehen nach jedem bestätigten Abgleich "
            + "oder über „Neu berechnen“.";
    static final String STEP_TIP = "Klick zeigt Kennung, Version und Rohergebnis";
    /** Höchstens so viele Schritte hoch; darüber rollt die Tabelle, damit die Beziehungen Platz behalten. */
    static final int MAX_STEP_ROWS = 10;

    /** Holt Werte im Hintergrund und meldet sie auf dem EDT; in Tests sofort. */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Die Seitenmenge der Auswahl ({@code null}: ohne Grenze) und die Zahl ihrer Beziehungen, beides aus dem Core. */
    interface Scope {
        PageSet pages() throws Exception;

        int relations(PageSet pages) throws Exception;
    }

    private final boolean withSteps;
    private final StepModel model = new StepModel();
    private final JTable steps = Comic.table(new JTable(model));
    private final JScrollPane stepScroll = Comic.scroll(steps);
    private final JLabel noSteps = Comic.muted(NO_STEPS);
    private final JPanel stepArea = new JPanel(new BorderLayout());
    private final InfoPopup popup = new InfoPopup();
    private final RelationTab relations;
    private final SubpageSection below = new SubpageSection(RELATIONS, 8, "Begriff", "Beziehung", "Zu Begriff",
            "Status");
    private Runner runner = new Runner() {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            Background.run(work, onSuccess, onFailure);
        }
    };
    private Scope scope;
    private PageSet pages;
    private boolean resolved;
    private boolean dirty;
    private boolean shown;
    private int request;
    private String total = COUNTING;
    private String detailJson = "";

    /**
     * @param withSteps ob oben die Analyseschritte stehen (Seite) oder nur die Beziehungen (Oberseiten)
     */
    AnalysisView(AppContext context, boolean withSteps) {
        this(RelationTab.all(context), withSteps);
    }

    AnalysisView(RelationTab relations, boolean withSteps) {
        super(new BorderLayout(0, 10));
        this.relations = Objects.requireNonNull(relations, "relations");
        this.withSteps = withSteps;
        setOpaque(false);
        steps.setRowSelectionAllowed(true);
        steps.setToolTipText(STEP_TIP);
        stepArea.setOpaque(false);
        noSteps.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        steps.getColumnModel().getColumn(2).setCellRenderer(new StandRenderer());
        steps.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = steps.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    details(row, e.getX(), e.getY());
                }
            }
        });
        if (withSteps) {
            add(stepArea, BorderLayout.NORTH);
        }
        relations.heading(heading(), null);
        relations.onChanged(() -> {
            count();
            relationsChanged.run();
        });
        add(relations.component(), BorderLayout.CENTER);
        if (withSteps) {
            // Fahrplan P3: die Beziehungen der Unterseiten abgetrennt unter denen der Seite.
            add(below, BorderLayout.SOUTH);
        }
        fitSteps();
    }

    /** Der Abschnitt „Aus Unterseiten“ (nur auf der Seite). */
    SubpageSection below() {
        return below;
    }

    /** Die Analyseschritte der gezeigten Seite; {@code result} liefert je Schritt das Ergebnis in Worten. */
    void steps(List<Enrichments.View> views, Function<Enrichments.View, String> result) {
        popup.setVisible(false);
        model.set(views == null ? List.of() : views, result);
        fitSteps();
    }

    /** Die Auswahl links: Seite, Ordner, Space, Quelle oder Wissensquellen; gelesen wird, sobald sie zu sehen ist. */
    void scope(Scope scope) {
        this.scope = scope;
        resolved = false;
        pages = null;
        dirty = true;
        request++;
        total = COUNTING;
        relations.heading(heading(), null);
        loadIfShown();
    }

    /** Ob die Ansicht gerade zu sehen ist; erst dann wird gelesen. */
    void shown(boolean shown) {
        this.shown = shown;
        if (!shown) {
            popup.setVisible(false);
            relations.forgetRemoved();
        }
        loadIfShown();
    }

    private void loadIfShown() {
        if (!shown || !dirty || scope == null) {
            return;
        }
        dirty = false;
        Scope current = scope;
        int mine = request;
        runner.run(current::pages, found -> {
            if (mine != request) {
                return;
            }
            pages = found;
            resolved = true;
            relations.pages(found);
        }, failure -> {
            if (mine == request) {
                total = COUNTING;
                relations.heading(RELATIONS + " nicht lesbar: " + failure.getClass().getSimpleName(), null);
            }
        });
    }

    /** Zählt die Beziehungen der Auswahl neu, sobald die Liste geladen oder ein Urteil gefallen ist. */
    private void count() {
        if (!resolved || scope == null) {
            return;
        }
        Scope current = scope;
        PageSet within = pages;
        int mine = request;
        runner.run(() -> current.relations(within), counted -> {
            if (mine == request) {
                total = String.valueOf(counted);
                relations.heading(heading(), null);
            }
        }, failure -> { });
    }

    private String heading() {
        return RELATIONS + " · " + total;
    }

    private void fitSteps() {
        stepArea.removeAll();
        // Ohne Analysen ein gedämpfter Satz an der Stelle der Schritte.
        stepArea.add(model.getRowCount() > 0 ? stepScroll : noSteps, BorderLayout.CENTER);
        int rows = Math.max(1, Math.min(MAX_STEP_ROWS, model.getRowCount()));
        int height = steps.getTableHeader().getPreferredSize().height + rows * steps.getRowHeight() + 4;
        stepScroll.setPreferredSize(new Dimension(300, height));
        stepArea.revalidate();
        stepArea.repaint();
        revalidate();
    }

    private void details(int row, int x, int y) {
        Enrichments.View view = model.view(row);
        Enrichment result = view.enrichment();
        StringBuilder text = new StringBuilder();
        text.append("Kennung: ").append(result.processorId()).append('\n');
        text.append("Version: ").append(result.processorVersion()).append('\n');
        if (result.errorCode() != null) {
            text.append("Fehler: ").append(result.errorCode()).append('\n');
        }
        for (String warning : result.warnings()) {
            text.append("Warnung: ").append(warning).append('\n');
        }
        detailJson = Payloads.prettyJson(result.payloadJson());
        text.append('\n').append(detailJson);
        JPanel row0 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row0.setOpaque(false);
        row0.add(new CopyButton(() -> detailJson));
        popup.clearLinks().text(name(result.processorId()), text.toString()).controls(row0);
        popup.showAt(steps, x, y);
    }

    private Runnable relationsChanged = () -> { };

    /** {@code listener} läuft, wenn eine Beziehung angelegt, bestätigt, abgelehnt oder entfernt wurde. */
    void onRelationsChanged(Runnable listener) {
        relationsChanged = Objects.requireNonNull(listener, "listener");
    }

    private com.aresstack.spaceharvester.app.ui.IconButton proposeIcon;
    private com.aresstack.spaceharvester.app.ui.IconButton createIcon;

    /** „Synonyme vorschlagen“ für die Reiterzeile; nur sichtbar, solange „Analyse“ gewählt ist. */
    ComposerButton proposeButton() {
        return relations.proposeButton();
    }

    /** „Neue Beziehung …“ für die Reiterzeile; nur sichtbar, solange „Analyse“ gewählt ist. */
    ComposerButton createButton() {
        return relations.createButton();
    }

    /**
     * „Synonyme vorschlagen“ als Icon-Knopf für die enge Reiterzeile einer Seite (UX-Manager, 960 px): gleicher Wortlaut
     * als Tooltip und zugänglicher Name, gleiche Aktion, gesperrt wie der Textknopf.
     */
    com.aresstack.spaceharvester.app.ui.IconButton proposeIcon() {
        if (proposeIcon == null) {
            proposeIcon = mirror(proposeButton(), com.aresstack.spaceharvester.app.ui.Glyph.SPARKLE);
        }
        return proposeIcon;
    }

    /** „Neue Beziehung …“ als Icon-Knopf (Plus) für die Reiterzeile einer Seite; wie {@link #proposeIcon()}. */
    com.aresstack.spaceharvester.app.ui.IconButton createIcon() {
        if (createIcon == null) {
            createIcon = mirror(createButton(), com.aresstack.spaceharvester.app.ui.Glyph.PLUS);
        }
        return createIcon;
    }

    private static com.aresstack.spaceharvester.app.ui.IconButton mirror(javax.swing.AbstractButton button,
                                                                         javax.swing.Icon glyph) {
        com.aresstack.spaceharvester.app.ui.IconButton icon =
                new com.aresstack.spaceharvester.app.ui.IconButton(glyph, button.getText());
        icon.setEnabled(button.isEnabled());
        button.addPropertyChangeListener("enabled", e -> icon.setEnabled(button.isEnabled()));
        icon.addActionListener(e -> button.doClick(0));
        return icon;
    }

    // --------------------------------------------------------------------------------------------- Namen und Worte

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("acronyms", "Akronyme"),
            Map.entry("terms", "Begriffe"),
            Map.entry("passages", "Absätze"),
            Map.entry("navigation", "Themenabschnitte"),
            Map.entry("outline", "Gliederung"),
            Map.entry("statements", "Aussagen"),
            Map.entry("images", "Bilder"),
            Map.entry("entities", "Nominalgruppen"),
            Map.entry("properties", "Eigenschaften"),
            Map.entry("activities", "Handlungen"),
            Map.entry("relations", "Beziehungen im Text"),
            Map.entry("embedding", "Suchvektoren"),
            Map.entry(Enrichments.READING_VIEW, "Leseansicht"),
            Map.entry(Enrichments.IMPORTED_ASSETS, "Anhänge"),
            Map.entry(Enrichments.SIMPLIFICATIONS, "Vereinfachte Sätze"),
            Map.entry(Enrichments.STATEMENT_CHECKS, "Aussagen prüfen"));

    /** Der deutsche Name eines Analyseschritts, wie ihn die Reiter nennen; unbekannte behalten ihre Kennung. */
    static String name(String processorId) {
        return NAMES.getOrDefault(processorId, processorId);
    }

    /** Das Ergebnis in Worten, wenn {@code word} nichts Genaueres weiß. */
    static String plainResult(Enrichments.View view) {
        Enrichment result = view.enrichment();
        return result.status() == Enrichment.Status.FAILED
                ? "fehlgeschlagen" + (result.errorCode() == null ? "" : " (" + result.errorCode() + ")")
                : "berechnet";
    }

    /** Der Stand nach der Ampel: grün aktuell, gelb veraltet, rot nur fehlgeschlagen. */
    static String standWord(Enrichments.View view) {
        if (view.enrichment().status() == Enrichment.Status.FAILED) {
            return "fehlgeschlagen";
        }
        return view.freshness() == Enrichment.Freshness.CURRENT ? "aktuell" : "veraltet";
    }

    static Color standTone(Enrichments.View view) {
        if (view.enrichment().status() == Enrichment.Status.FAILED) {
            return Comic.RED;
        }
        return view.freshness() == Enrichment.Freshness.CURRENT ? Comic.STANDING_CURRENT : Comic.YELLOW;
    }

    // ---------------------------------------------------------------------------------------------------- Tests

    void runner(Runner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    JTable stepTable() {
        return steps;
    }

    boolean showsSteps() {
        return withSteps && stepScroll.getParent() == stepArea;
    }

    /** Der gedämpfte Satz einer Seite ohne Analysen, sonst {@code null}. */
    String stepPlaceholder() {
        return withSteps && noSteps.getParent() == stepArea ? noSteps.getText() : null;
    }

    /** Die Wunschhöhe der Schritte in Zeilen (höchstens {@link #MAX_STEP_ROWS}). */
    int visibleStepRows() {
        int header = steps.getTableHeader().getPreferredSize().height;
        return (stepScroll.getPreferredSize().height - header - 4) / steps.getRowHeight();
    }

    String relationHeading() {
        return heading();
    }

    InfoPopup detailPopup() {
        return popup;
    }

    RelationTab relationTab() {
        return relations;
    }

    /** Die Schritte: Analyse, Ergebnis, Stand, Berechnet. */
    private static final class StepModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Analyse", "Ergebnis", "Stand", "Berechnet"};
        private List<Enrichments.View> rows = new ArrayList<>();
        private List<String> results = new ArrayList<>();

        void set(List<Enrichments.View> views, Function<Enrichments.View, String> result) {
            rows = new ArrayList<>(views);
            results = new ArrayList<>(rows.size());
            for (Enrichments.View view : rows) {
                String word = view.enrichment().status() == Enrichment.Status.FAILED || result == null ? null
                        : result.apply(view);
                results.add(word == null ? plainResult(view) : word);
            }
            fireTableDataChanged();
        }

        Enrichments.View view(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Enrichments.View view = rows.get(row);
            return switch (column) {
                case 0 -> name(view.enrichment().processorId());
                case 1 -> results.get(row);
                case 2 -> view;
                default -> Texts.time(view.enrichment().computedAt());
            };
        }
    }

    /** Punkt in der Farbe der Ampel, daneben das Wort. */
    private static final class StandRenderer extends DefaultTableCellRenderer {

        private Color tone = Comic.MUTED;

        StandRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Enrichments.View view = (Enrichments.View) value;
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, standWord(view), selected, false,
                    row, column);
            tone = standTone(view);
            label.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 4));
            return label;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(tone);
                int size = 9;
                g2.fillOval(6, (getHeight() - size) / 2, size, size);
            } finally {
                g2.dispose();
            }
        }
    }
}
