package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.deck.TerminalSettings;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding.Modifier;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding.MouseAction;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/**
 * Bereich „Terminal“ der Einstellungen, übernommen aus MainframeMate (Tn3270SettingsPanel): Aktionsverzögerung,
 * Deckkraft der F-Tasten-Leiste, Sternenhimmel und die Maus-Aktionen (Seitentasten und Scrollrad lösen F-Tasten aus).
 * Verbindungswerte (Port, TLS, Modell, Codepage, Keep-Alive, Anmeldung, Startkommando) stehen wie bisher im Formular
 * „Neue Verbindung“ und werden dort gemerkt. Jede Änderung gilt sofort für alle Sitzungen. Nur auf dem EDT.
 */
final class TerminalSettingsPanel {

    static final String HEADING = "3270-Terminal";
    static final String MOUSE_HEADING = "Maus-Aktionen";

    private final TerminalSettings settings;
    private final SettingDialogs.Rows rows = new SettingDialogs.Rows();
    final JTextField delay = Comic.field(6);
    final JTextField opacity = Comic.field(6);
    final JCheckBox sky = Comic.checkBox("Sternenhimmel hinter dem Terminal (aus = klassisch schwarz)");
    final JTextField skyFactor = Comic.field(6);
    final JCheckBox skyGerman = Comic.checkBox("Sternbild-Namen auf Deutsch");
    final JCheckBox reconnect = Comic.checkBox("Abgerissene Verbindung automatisch neu aufbauen und anmelden");
    final BindingModel bindings = new BindingModel();
    final JTable table = Comic.table(new JTable(bindings));
    private final Feedback feedback = new Feedback();
    private final JPanel component = new JPanel(new BorderLayout(0, 8));
    private final JPanel mouse = new JPanel(new BorderLayout(0, 8));
    /** Während {@link #render} die Felder füllt, ist das keine Eingabe. */
    private boolean showing;

    TerminalSettingsPanel(TerminalSettings settings) {
        this.settings = settings;
        rows.add("Verzögerung", unit(delay, "ms"), "Aktionsverzögerung: Pause nach jedem Schritt der automatischen Anmeldung und "
                + "beim Abspielen eines Lesezeichens, damit langsame Hosts mitkommen. Vorgabe "
                + TerminalSettings.DEFAULT_DELAY_MS + " ms, von 0 bis 10000.");
        rows.add("Deckkraft", unit(opacity, "%"), "Wie deckend die F-Tasten-Leiste über dem Terminal liegt: "
                + "0 unsichtbar, 100 deckend. Vorgabe 50.");
        rows.add("Neuaufbau", reconnect, "Trennt der Host die Sitzung oder reißt die Leitung ab, verbindet das Terminal "
                + "sich neu, meldet sich wieder an und spielt Startkommando bzw. Lesezeichen ab: nach 2, 10 und 30 Sekunden, "
                + "dann nicht mehr. Ein Ereignis löst das aus, es wird nichts abgefragt.");
        rows.add("Hintergrund", sky, "Animierter Sternenhimmel (kosmische Uhr) hinter dem 3270-Bild.");
        rows.add("Zeitfaktor", unit(skyFactor, "×"), "1 = Echtzeit, 120 = 2 Minuten ≈ 4 Stunden. Vorgabe "
                + TerminalSettings.DEFAULT_SKY_FACTOR + ", von 1 bis 10000.");
        rows.add("Sternbilder", skyGerman, "Aus = englische bzw. lateinische Namen.");
        for (JTextField field : new JTextField[] {delay, opacity, skyFactor}) {
            field.addActionListener(e -> save());
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    save();
                }
            });
        }
        sky.addActionListener(e -> {
            skyFactor.setEnabled(sky.isSelected());
            skyGerman.setEnabled(sky.isSelected());
            save();
        });
        skyGerman.addActionListener(e -> save());
        reconnect.addActionListener(e -> {
            if (!showing) {
                settings.autoReconnect(reconnect.isSelected());
                feedback.success(HEADING + " gespeichert");
            }
        });
        component.setOpaque(false);
        component.add(rows, BorderLayout.CENTER);
        component.add(feedback, BorderLayout.SOUTH);

        // Maus-Aktionen: Tabelle mit Häkchenspalte, Auswahl je Spalte wie in MainframeMate.
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(combo(MouseAction.values())));
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(combo(Modifier.values())));
        JComboBox<String> keys = Comic.combo();
        for (int i = 1; i <= 24; i++) {
            keys.addItem("F" + i);
        }
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(keys));
        bindings.addTableModelListener(e -> {
            if (!showing) {
                save();
            }
        });
        var scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(0, 170));
        ComposerButton add = Comic.button("Hinzufügen", "Neue Zeile: Maus Zurück → F3");
        add.addActionListener(e -> {
            bindings.add(new MouseFkeyBinding(MouseAction.BACK, Modifier.NONE, 3));
            int row = bindings.getRowCount() - 1;
            table.setRowSelectionInterval(row, row);
        });
        ComposerButton remove = Comic.button("Entfernen", "Gewählte Zeile entfernen");
        remove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                if (table.isEditing()) {
                    table.getCellEditor().cancelCellEditing();
                }
                bindings.remove(row);
            }
        });
        ComposerButton reset = Comic.button("Vorgaben", "Scrollrad F7/F8, Shift+Scrollrad F10/F11, Maus Zurück F3");
        reset.addActionListener(e -> bindings.reset(MouseFkeyBinding.defaults()));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(add);
        buttons.add(remove);
        buttons.add(reset);
        mouse.setOpaque(false);
        mouse.add(Comic.wrapped("Maustasten und Scrollrad lösen über dem Terminal F-Tasten aus, etwa Blättern mit "
                + "F7/F8. Die Zusatztaste muss genau so gedrückt sein. Änderungen gelten sofort.", Comic.TEXT,
                12.5f), BorderLayout.NORTH);
        mouse.add(scroll, BorderLayout.CENTER);
        mouse.add(buttons, BorderLayout.SOUTH);
        render();
    }

    /** Auswahl in der Tabelle im Comic-Stil wie überall in der App. */
    @SafeVarargs
    private static <T> JComboBox<T> combo(T... values) {
        JComboBox<T> box = Comic.combo();
        for (T value : values) {
            box.addItem(value);
        }
        return box;
    }

    private static JComponent unit(JTextField field, String unit) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);
        panel.add(field);
        JLabel label = Comic.text(unit);
        panel.add(label);
        return panel;
    }

    JComponent component() {
        return component;
    }

    JComponent mouseComponent() {
        return mouse;
    }

    /** Zeigt die gespeicherten Werte. */
    void render() {
        showing = true;
        try {
            delay.setText(String.valueOf(settings.actionDelayMs()));
            opacity.setText(String.valueOf(settings.functionKeyOpacity()));
            sky.setSelected(settings.sky());
            skyFactor.setText(String.valueOf(settings.skyFactor()));
            skyGerman.setSelected(settings.skyGerman());
            reconnect.setSelected(settings.autoReconnect());
            skyFactor.setEnabled(sky.isSelected());
            skyGerman.setEnabled(sky.isSelected());
            bindings.reset(settings.mouse());
        } finally {
            showing = false;
        }
    }

    /** Prüft und speichert; ein ungültiger Wert wird gemeldet und nicht gespeichert. */
    void save() {
        if (showing) {
            return;
        }
        try {
            int delayMs = number(delay, "Aktionsverzögerung", 0, 10_000);
            int percent = number(opacity, "F-Tasten-Deckkraft", 0, 100);
            int factor = number(skyFactor, "Zeitfaktor", 1, 10_000);
            List<MouseFkeyBinding> chosen = bindings.rows();
            if (delayMs == settings.actionDelayMs() && percent == settings.functionKeyOpacity()
                    && sky.isSelected() == settings.sky() && factor == settings.skyFactor()
                    && skyGerman.isSelected() == settings.skyGerman() && chosen.equals(settings.mouse())) {
                return;
            }
            settings.save(delayMs, percent, sky.isSelected(), factor, skyGerman.isSelected(), chosen);
            feedback.success(HEADING + " gespeichert");
        } catch (IllegalArgumentException invalid) {
            feedback.problem(invalid);
        }
    }

    private static int number(JTextField field, String name, int min, int max) {
        String text = field.getText().strip();
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + ": „" + text + "“ ist keine ganze Zahl; nicht gespeichert");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + ": " + value + " liegt nicht zwischen " + min + " und " + max
                    + "; nicht gespeichert");
        }
        return value;
    }

    /** Die Zeilen der Mausbelegung: aktiv, Aktion, Zusatztaste, F-Taste. */
    static final class BindingModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Aktiv", "Maus-Aktion", "Zusatztaste", "F-Taste"};
        private final List<MouseFkeyBinding> rows = new ArrayList<>();

        List<MouseFkeyBinding> rows() {
            return List.copyOf(rows);
        }

        void add(MouseFkeyBinding binding) {
            rows.add(binding);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void remove(int row) {
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        void reset(List<MouseFkeyBinding> bindings) {
            rows.clear();
            rows.addAll(bindings);
            fireTableDataChanged();
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
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0 -> Boolean.class;
                case 1 -> MouseAction.class;
                case 2 -> Modifier.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            MouseFkeyBinding binding = rows.get(row);
            return switch (column) {
                case 0 -> binding.enabled();
                case 1 -> binding.action();
                case 2 -> binding.modifier();
                default -> "F" + binding.fkey();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            MouseFkeyBinding old = rows.get(row);
            MouseFkeyBinding changed = switch (column) {
                case 0 -> new MouseFkeyBinding(old.action(), old.modifier(), old.fkey(), Boolean.TRUE.equals(value));
                case 1 -> new MouseFkeyBinding(value instanceof MouseAction action ? action
                        : MouseAction.valueOf(String.valueOf(value)), old.modifier(), old.fkey(), old.enabled());
                case 2 -> new MouseFkeyBinding(old.action(), value instanceof Modifier modifier ? modifier
                        : Modifier.valueOf(String.valueOf(value)), old.fkey(), old.enabled());
                default -> {
                    String digits = String.valueOf(value).replaceAll("[^0-9]", "");
                    yield digits.isEmpty() ? old : new MouseFkeyBinding(old.action(), old.modifier(),
                            Integer.parseInt(digits), old.enabled());
                }
            };
            rows.set(row, changed);
            fireTableCellUpdated(row, column);
        }
    }
}
