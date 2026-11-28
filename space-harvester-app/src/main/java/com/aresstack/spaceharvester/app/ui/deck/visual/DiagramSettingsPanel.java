package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunction;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunctions;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Einstellungen › Diagramme: die Diagramm-Ansicht (KI-Kurzbeschriftung, in MainframeMate „UML-Zusammenfassung“ des
 * Summarizers) und die Systemfunktionen (MainframeMate: Einstellungen › Systemfunktionen): z/OS-Programme, die im
 * Diagramm blau hervorgehoben und per Doppelklick im Wikipedia-Artikel geöffnet werden. Änderungen gelten sofort und
 * werden sofort gespeichert wie in MainframeMate.
 */
public final class DiagramSettingsPanel {

    public static final String DIAGRAM_HEADING = "Diagramm-Ansicht";
    public static final String FUNCTIONS_HEADING = "Systemfunktionen (z/OS-Programme)";

    private final SystemFunctionsStore store;
    private final List<SystemFunction> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel feedback = Comic.muted(" ");
    final JCheckBox aiLabels = Comic.checkBox("Diagramme von der KI beschreiben lassen");
    final javax.swing.JTextField length = Comic.field(5);
    final javax.swing.JTextField detailLength = Comic.field(5);
    final javax.swing.JTextField lineWidth = Comic.field(5);
    private final JPanel diagram = new JPanel(new BorderLayout(0, 6));
    private final JPanel functions = new JPanel(new BorderLayout(0, 8));

    public DiagramSettingsPanel() {
        this(SystemFunctionsStore.shared());
    }

    DiagramSettingsPanel(SystemFunctionsStore store) {
        this.store = store;
        diagram.setOpaque(false);
        aiLabels.setSelected(DiagramLabels.switchedOn());
        aiLabels.setToolTipText("Eingeschaltet beschreibt das Modell der Zeile „Quellcode“ (sonst das Chat-Modell) "
                + "jeden zusammengefassten Block ausführlich, jede Verzweigung als Frage und jedes aufgerufene "
                + "Programm mit seinem Zweck. Aus: keine KI-Aufrufe, die einfache Beschriftung bleibt.");
        aiLabels.addActionListener(e -> AppState.shared().put(DiagramLabels.ENABLED_KEY, aiLabels.isSelected()));
        diagram.add(aiLabels, BorderLayout.NORTH);
        diagram.add(Comic.hint("Der Code der Blöcke und der aufgerufenen Programme geht dabei an den KI-Dienst. Das "
                + "Diagramm erscheint sofort mit der einfachen Beschriftung („📦 5 Anweisungen“, Bedingungen, Zweige); "
                + "die KI-Fassung ersetzt es, sobald sie fertig ist."), BorderLayout.CENTER);
        number(length, DiagramLabels.LENGTH_KEY, "Länge kurzer Beschriftungen (Frage, Zweig, Zweck) in Zeichen");
        number(detailLength, DiagramLabels.DETAIL_LENGTH_KEY, "Länge der Beschreibung eines Blocks in Zeichen");
        number(lineWidth, DiagramLabels.LINE_WIDTH_KEY, "Zeichen je Zeile in einem Diagrammknoten");
        diagram.add(Comic.row(new JLabel("Kurz"), length, new JLabel("Block"), detailLength,
                new JLabel("Zeilenbreite"), lineWidth), BorderLayout.SOUTH);
        showNumbers();

        functions.setOpaque(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getAccessibleContext().setAccessibleName("Systemfunktionen");
        table.getColumnModel().getColumn(0).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(320);
        JScrollPane scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(720, 260));
        functions.add(Comic.hint("Programme dieser Liste werden im Diagramm (EXEC PGM=…) blau hervorgehoben; ein "
                + "Doppelklick auf den Knoten öffnet den Wikipedia-Artikel. Wikipedia-Titel = Artikelname in der URL, "
                + "z. B. „Access_Method_Services“; leer = Programmname als Suchbegriff. Zellen direkt bearbeiten."),
                BorderLayout.NORTH);
        functions.add(scroll, BorderLayout.CENTER);
        var add = Comic.button("Hinzufügen", "Neue Systemfunktion anlegen");
        var remove = Comic.button("Entfernen", "Gewählte Systemfunktion entfernen");
        var defaults = Comic.button("Standard ergänzen", "Fehlende Standard-Systemfunktionen ergänzen");
        add.addActionListener(e -> add());
        remove.addActionListener(e -> remove());
        defaults.addActionListener(e -> addDefaults());
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.add(Comic.row(add, remove, defaults), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        functions.add(south, BorderLayout.SOUTH);
        render();
        store.onChange(() -> javax.swing.SwingUtilities.invokeLater(this::render));
    }

    /** Ein Zahlenfeld, das beim Verlassen oder mit Enter speichert; Ungültiges stellt den gespeicherten Wert wieder her. */
    private void number(javax.swing.JTextField field, String key, String tip) {
        field.setToolTipText(tip);
        field.getAccessibleContext().setAccessibleName(tip);
        Runnable save = () -> {
            try {
                int value = Integer.parseInt(field.getText().trim());
                if (value > 0) {
                    AppState.shared().put(key, value);
                }
            } catch (NumberFormatException ignored) {
                // gespeicherter Wert bleibt
            }
            showNumbers();
        };
        field.addActionListener(e -> save.run());
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                save.run();
            }
        });
    }

    private void showNumbers() {
        var layout = DiagramLabels.layout();
        length.setText(Integer.toString(layout.length()));
        detailLength.setText(Integer.toString(layout.detailLength()));
        lineWidth.setText(Integer.toString(layout.lineWidth()));
    }

    public JComponent diagramComponent() {
        return diagram;
    }

    public JComponent functionsComponent() {
        return functions;
    }

    /** Zeigt den gespeicherten Stand. */
    public void render() {
        aiLabels.setSelected(DiagramLabels.switchedOn());
        showNumbers();
        if (table.isEditing()) {
            return;
        }
        rows.clear();
        rows.addAll(store.all());
        model.fireTableDataChanged();
    }

    private void add() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        rows.add(new SystemFunction("", "", "", ""));
        model.fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        table.changeSelection(rows.size() - 1, 0, false, false);
        table.editCellAt(rows.size() - 1, 0);
        feedback.setText("Programmnamen eintragen; gespeichert wird, sobald er nicht leer ist.");
    }

    private void remove() {
        int row = table.getSelectedRow();
        if (row < 0) {
            feedback.setText("Erst eine Zeile wählen.");
            return;
        }
        SystemFunction function = rows.get(table.convertRowIndexToModel(row));
        if (!function.name().isEmpty() && !Dialogs.confirm(table, "Entfernen",
                "Systemfunktion „" + function.name() + "“ wirklich entfernen?")) {
            return;
        }
        rows.remove(function);
        save("„" + function.name() + "“ entfernt.");
    }

    private void addDefaults() {
        Set<String> present = new HashSet<>();
        rows.forEach(f -> present.add(f.name()));
        int added = 0;
        for (SystemFunction function : SystemFunctions.DEFAULTS) {
            if (present.add(function.name())) {
                rows.add(function);
                added++;
            }
        }
        save(added == 0 ? "Alle Standard-Systemfunktionen sind schon da."
                : added + " Standard-Systemfunktion" + (added == 1 ? "" : "en") + " ergänzt.");
    }

    private void save(String done) {
        List<SystemFunction> valid = rows.stream().filter(f -> !f.name().isEmpty()).toList();
        try {
            store.save(valid);
            feedback.setText(done);
        } catch (IOException e) {
            feedback.setText("Nicht gespeichert: " + e.getMessage());
        }
        render();
    }

    private final class Model extends AbstractTableModel {

        private final String[] columns = {"Programmname", "Wikipedia (DE)", "Wikipedia (EN)", "Beschreibung"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            SystemFunction f = rows.get(row);
            return switch (column) {
                case 0 -> f.name();
                case 1 -> f.wikiTitleDe();
                case 2 -> f.wikiTitleEn();
                default -> f.description();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            String text = value == null ? "" : value.toString().trim();
            SystemFunction f = rows.get(row);
            if (column == 0) {
                String name = text.toUpperCase(Locale.ROOT);
                for (int i = 0; i < rows.size(); i++) {
                    if (i != row && rows.get(i).name().equals(name) && !name.isEmpty()) {
                        feedback.setText("Ein Eintrag „" + name + "“ gibt es schon.");
                        return;
                    }
                }
                rows.set(row, new SystemFunction(name, f.wikiTitleDe(), f.wikiTitleEn(), f.description()));
            } else if (column == 1) {
                rows.set(row, new SystemFunction(f.name(), text, f.wikiTitleEn(), f.description()));
            } else if (column == 2) {
                rows.set(row, new SystemFunction(f.name(), f.wikiTitleDe(), text, f.description()));
            } else {
                rows.set(row, new SystemFunction(f.name(), f.wikiTitleDe(), f.wikiTitleEn(), text));
            }
            fireTableRowsUpdated(row, row);
            if (!rows.get(row).name().isEmpty()) {
                save("Gespeichert.");
            }
        }
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    JTable table() {
        return table;
    }

    String feedback() {
        return feedback.getText();
    }
}
