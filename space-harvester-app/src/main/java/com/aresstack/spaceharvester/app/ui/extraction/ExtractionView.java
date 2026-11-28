package com.aresstack.spaceharvester.app.ui.extraction;

import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.controls.TextContextMenu;
import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.SourceReference;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Die Ansicht „Extraktion“ eines Dokuments (experimentell): eine Tabelle Information · Wert · Stand · Fundstelle,
 * gruppiert nach fachlichen Abschnitten, darüber fest die gelbe Zeile „Experimentell – nicht fachlich geprüft“.
 *
 * <p>Kennt keine Feldnamen und keine Regeln; sie zeigt nur, was der {@link DocumentAnalysisProvider} liefert. Ohne
 * angeschlossene Analyse ({@link DocumentAnalysisProvider#NONE}) steht „Noch nicht analysiert“, und „Analyse starten“
 * bleibt deaktiviert. Eigene Leiste und Knöpfe hat sie nicht (UX): {@link #actions()} gehört in die Fußleiste des Deck
 * bzw. die Tab-Zeile der Seitenansicht, {@link #statusInfo()} in die Statuszeile der App.
 *
 * <p>Gelesen und angestoßen wird im Hintergrund ({@link Runner}); ein spätes Ergebnis eines inzwischen gewechselten
 * Dokuments wird verworfen. Beim Wechsel ist die Tabelle sofort leer. Neue Ergebnisse meldet der Anbieter über
 * {@link DocumentAnalysisProvider#observe} (ADR 0027, kein Polling); dann liest die Ansicht neu. {@link #close()} meldet
 * ab. Ändert nie ein Dokument. Nur auf dem EDT.
 */
public final class ExtractionView extends JPanel {

    static final String EXPERIMENTAL = "Experimentell – nicht fachlich geprüft";
    static final String SAMPLE = " · Beispieldaten, keine echten Analyseergebnisse";
    static final String START = "Analyse starten";
    static final String START_LATER = "Analyse starten – folgt, noch kein Analysedienst angeschlossen";
    static final String LOADING = "Wird geladen …";
    static final String[] COLUMNS = {"Information", "Wert", "Stand", "Fundstelle"};

    private static final String TABLE_CARD = "table";
    private static final String MESSAGE_CARD = "message";

    /** Holt Werte im Hintergrund und meldet sie auf dem EDT; in Tests sofort. */
    public interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private final Runner runner;
    private DocumentAnalysisProvider provider;
    private final Model model = new Model();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel experimental = new JLabel(EXPERIMENTAL);
    private final JLabel message = Comic.muted(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel stage = new JPanel(cards);
    private final IconButton start = new IconButton(Glyph.SPARKLE, START_LATER);
    private final JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 6, 0));
    private final StatusLabel status = new StatusLabel();
    private Consumer<SourceReference> onSource = source -> { };
    private Consumer<String> clipboard = ExtractionView::systemClipboard;
    private Consumer<StatusNote> statusObserver = note -> { };
    private StatusNote currentStatus = StatusNote.plain(" ");

    private Subscription subscription = Subscription.NONE;
    private DocumentRef shown;
    private DocumentAnalysis analysis;
    /** Zählt jeden Wechsel und jeden Auftrag; nur das Ergebnis des letzten zählt. */
    private long generation;

    public ExtractionView(DocumentAnalysisProvider provider) {
        this(provider, new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        });
    }

    ExtractionView(DocumentAnalysisProvider provider, Runner runner) {
        super(new BorderLayout());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.runner = Objects.requireNonNull(runner, "runner");
        setBackground(Comic.SURFACE);

        experimental.setOpaque(true);
        experimental.setBackground(Comic.NOTICE);
        experimental.setForeground(StatusLabel.WARNING_COLOR);
        experimental.setFont(Comic.semiBold(12.5f));
        experimental.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.YELLOW), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        add(experimental, BorderLayout.NORTH);

        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(240);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(200);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getAccessibleContext().setAccessibleName("Extrahierte Informationen");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, TextContextMenu.shortcutMask()), "copyValue");
        table.getActionMap().put("copyValue", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copySelectedValue();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (event.getButton() == MouseEvent.BUTTON1 && table.columnAtPoint(event.getPoint()) == 3) {
                    openSource(row);
                }
            }
        });

        message.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.setOpaque(false);
        messagePanel.add(message, BorderLayout.CENTER);
        stage.setOpaque(false);
        stage.add(Comic.scroll(table), TABLE_CARD);
        stage.add(messagePanel, MESSAGE_CARD);
        add(stage, BorderLayout.CENTER);

        start.addActionListener(e -> startAnalysis());
        actions.setOpaque(false);
        actions.add(start);
        subscribe();
        refresh();
    }

    /** Meldet sich beim Anbieter ab; danach liest die Ansicht nur noch auf {@link #show}. */
    public void close() {
        subscription.close();
        subscription = Subscription.NONE;
    }

    private void subscribe() {
        subscription.close();
        subscription = provider.observe(changed -> SwingUtilities.invokeLater(() -> {
            if (changed != null && changed.equals(shown)) {
                reload();
            }
        }));
    }

    /** Die Aktionen für Fußleiste bzw. Tab-Zeile; derselbe Baustein über die ganze Laufzeit. */
    public JComponent actions() {
        return actions;
    }

    /** Der Stand des Dokuments für die Statuszeile der App. */
    public JComponent statusInfo() {
        return status;
    }

    /** Meldet jeden neuen Stand für eine Statuszeile, die der Bereich selbst führt. */
    public void onStatus(Consumer<StatusNote> observer) {
        statusObserver = Objects.requireNonNull(observer, "observer");
    }

    /** Der zuletzt gemeldete Stand. */
    public StatusNote currentStatus() {
        return currentStatus;
    }

    /** Wird mit der Fundstelle gerufen, wenn der Nutzer auf sie klickt (Sprung in die Vorschau). */
    public void onSource(Consumer<SourceReference> action) {
        onSource = Objects.requireNonNull(action, "action");
    }

    /** Tauscht die Analyse aus, etwa wenn der Analysedienst angeschlossen wird; liest das gezeigte Dokument neu. */
    public void provider(DocumentAnalysisProvider other) {
        provider = Objects.requireNonNull(other, "other");
        subscribe();
        show(shown);
    }

    /** Das gezeigte Dokument; {@code null}: keins. Die Tabelle ist sofort leer, das Ergebnis kommt im Hintergrund. */
    public void show(DocumentRef document) {
        long mine = ++generation;
        shown = document;
        analysis = null;
        model.rows(List.of());
        if (document == null) {
            refresh();
            return;
        }
        message.setText(LOADING);
        refresh();
        load(mine, document);
    }

    /** Liest das Ergebnis des gezeigten Dokuments neu, ohne die Tabelle vorher zu leeren (Meldung des Anbieters). */
    void reload() {
        if (shown != null) {
            load(++generation, shown);
        }
    }

    private void load(long mine, DocumentRef document) {
        DocumentAnalysisProvider asked = provider;
        runner.run(() -> new Loaded(asked.result(document), asked.canAnalyze(document)),
                loaded -> {
                    if (mine == generation) {
                        apply(loaded.analysis(), loaded.canAnalyze());
                    }
                },
                failure -> {
                    if (mine == generation) {
                        apply(DocumentAnalysis.failed(document, reason(failure)), false);
                    }
                });
    }

    /** Das gezeigte Dokument; {@code null} ohne. */
    public DocumentRef shown() {
        return shown;
    }

    /** Das gezeigte Ergebnis; {@code null} solange keins geladen ist. */
    DocumentAnalysis analysis() {
        return analysis;
    }

    void clipboard(Consumer<String> target) {
        clipboard = target;
    }

    JTable table() {
        return table;
    }

    IconButton startButton() {
        return start;
    }

    String messageText() {
        return message.getText();
    }

    String experimentalText() {
        return experimental.getText();
    }

    /** Kopiert den Wert der gewählten Zeile; Überschriften und leere Werte nicht. */
    void copySelectedValue() {
        ExtractedField field = model.field(table.getSelectedRow());
        if (field != null && !field.value().isEmpty()) {
            clipboard.accept(field.value());
        }
    }

    private void startAnalysis() {
        DocumentRef document = shown;
        DocumentAnalysisProvider asked = provider;
        if (document == null || !asked.canAnalyze(document)) {
            return;
        }
        long mine = ++generation;
        apply(DocumentAnalysis.inProgress(document), false);
        runner.run(() -> {
                    asked.startAnalysis(document);
                    return new Loaded(asked.result(document), asked.canAnalyze(document));
                },
                loaded -> {
                    if (mine == generation) {
                        apply(loaded.analysis(), loaded.canAnalyze());
                    }
                },
                failure -> {
                    if (mine == generation) {
                        apply(DocumentAnalysis.failed(document, reason(failure)), asked.canAnalyze(document));
                    }
                });
    }

    private void apply(DocumentAnalysis result, boolean canAnalyze) {
        if (result == null || !Objects.equals(result.document(), shown)) {
            // Ein Ergebnis, das nicht zum gezeigten Dokumentstand gehört, erscheint nie
            result = shown == null ? null : DocumentAnalysis.notAnalyzed(shown);
        }
        analysis = result;
        model.rows(ExtractionRows.rows(result));
        start.setEnabled(canAnalyze && result != null && result.state() != AnalysisState.IN_PROGRESS);
        start.setToolTipText(canAnalyze ? START : START_LATER);
        message.setText(ExtractionRows.status(result));
        refresh();
    }

    private void refresh() {
        if (shown == null) {
            message.setText(ExtractionRows.status(null));
            start.setEnabled(false);
        }
        boolean sample = analysis != null && analysis.sample();
        experimental.setText(sample ? EXPERIMENTAL + SAMPLE : EXPERIMENTAL);
        cards.show(stage, model.getRowCount() > 0 ? TABLE_CARD : MESSAGE_CARD);
        StatusNote.Tone tone = analysis != null && analysis.state() == AnalysisState.FAILED
                ? StatusNote.Tone.ERROR : StatusNote.Tone.PLAIN;
        String text = shown != null && analysis == null ? LOADING : ExtractionRows.status(analysis);
        currentStatus = new StatusNote("Extraktion: " + text, tone, null, null);
        status.show(currentStatus);
        statusObserver.accept(currentStatus);
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(event.getPoint());
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
        }
        ExtractedField field = model.field(row);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem(TextContextMenu.COPY);
        copy.setEnabled(field != null && !field.value().isEmpty());
        copy.addActionListener(e -> copySelectedValue());
        menu.add(copy);
        menu.show(table, event.getX(), event.getY());
    }

    private void openSource(int row) {
        ExtractedField field = model.field(row);
        if (field != null && !field.source().isEmpty()) {
            onSource.accept(field.source());
        }
    }

    private static String reason(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text;
    }

    private static void systemClipboard(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    private record Loaded(DocumentAnalysis analysis, boolean canAnalyze) {
    }

    /** Zeilen der Tabelle: Überschrift oder Feld. */
    static final class Model extends AbstractTableModel {

        private List<ExtractionRows.Row> rows = List.of();

        void rows(List<ExtractionRows.Row> other) {
            rows = List.copyOf(other);
            fireTableDataChanged();
        }

        ExtractionRows.Row row(int index) {
            return index < 0 || index >= rows.size() ? null : rows.get(index);
        }

        ExtractedField field(int index) {
            ExtractionRows.Row row = row(index);
            return row == null ? null : row.field();
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
        public Object getValueAt(int rowIndex, int column) {
            ExtractionRows.Row row = rows.get(rowIndex);
            if (row.isHeading()) {
                return column == 0 ? row.heading() : "";
            }
            ExtractedField field = row.field();
            return switch (column) {
                case 0 -> ExtractionRows.label(field);
                case 1 -> ExtractionRows.value(field);
                case 2 -> ExtractionRows.word(field.status());
                default -> ExtractionRows.source(field.source());
            };
        }
    }

    /** Überschriften halbfett auf der Fläche, Stand in seiner Farbe, Fundstelle als Link mit vollem Auszug im Tooltip. */
    private final class Renderer extends DefaultTableCellRenderer {

        Renderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
            setFont(Comic.regular(13f));
            setToolTipText(null);
            setForeground(Comic.INK);
            if (!selected) {
                setBackground(AppColors.of("comic.fieldBg"));
            }
            ExtractionRows.Row shownRow = model.row(row);
            if (shownRow == null) {
                return this;
            }
            if (shownRow.isHeading()) {
                setFont(Comic.semiBold(13f));
                if (!selected) {
                    setBackground(Comic.SURFACE);
                }
                return this;
            }
            ExtractedField field = shownRow.field();
            switch (column) {
                case 1 -> {
                    if (field.value().isEmpty()) {
                        setForeground(Comic.MUTED);
                    } else if (!field.rawValue().equals(field.value())) {
                        setToolTipText("Im Dokument: " + field.rawValue());
                    }
                }
                case 2 -> {
                    setForeground(ExtractionRows.tone(field.status()));
                    setFont(Comic.semiBold(13f));
                    setToolTipText(ExtractionRows.origin(field));
                }
                case 3 -> {
                    if (!field.source().isEmpty()) {
                        setForeground(Comic.BLUE);
                        setToolTipText(field.source().excerpt().isEmpty() ? "Klick springt zur Fundstelle"
                                : "„" + field.source().excerpt() + "“ – Klick springt zur Fundstelle");
                    }
                }
                default -> {
                }
            }
            return this;
        }
    }
}
