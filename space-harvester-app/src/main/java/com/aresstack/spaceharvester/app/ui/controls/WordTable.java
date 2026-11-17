package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.RowActions;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Eine Wortliste als Tabelle (Einstellungen mit mehreren Wörtern, etwa die Wörter für den Stand oder die Regeln des
 * Fachglossars): Kopfzeile mit Titel und den Icon-Knöpfen „Alle aus“ und „Vorgabe“, darunter die Tabelle mit Häkchen
 * „Aktiv“, dem Wort und ✕ nur an eigenen Wörtern, darunter das Feld mit „Hinzufügen“ (Enter). Die Vorgabewörter stehen
 * immer in der Liste und lassen sich nur abhaken; eigene Wörter kommen dazu und gehen mit ✕. „Alle aus“ und
 * „Vorgabe“ fragen vorher mit „Wirklich …“ nach, weil dabei eigene Wörter bzw. Häkchen verloren gehen.
 *
 * <p>Gespeichert wird nichts: Die Platte liest {@link #active()}, {@link #added()} und {@link #excluded()} und schreibt
 * selbst.
 */
public final class WordTable {

    public static final String ACTIVE_COLUMN = "Aktiv";
    public static final String WORD_COLUMN = "Wort";
    public static final String ALL_OFF_TIP = "Alle aus: kein Wort dieser Liste zählt mehr; die Wörter bleiben "
            + "stehen und lassen sich wieder anhaken";
    public static final String DEFAULT_TIP = "Vorgabe: die Liste auf die mitgelieferten Wörter zurücksetzen; eigene "
            + "Wörter fallen dabei weg";
    public static final String REMOVE_TIP = "Eigenes Wort entfernen";
    public static final String ADD_TIP = "Das Wort in die Liste aufnehmen (Enter)";
    public static final String INVALID = "Nur ganze Wörter aus Buchstaben und Ziffern";
    public static final String ALL_OFF_QUESTION = "Wirklich alle Wörter dieser Liste abschalten?";
    public static final String DEFAULT_QUESTION = "Wirklich auf die Vorgabe zurücksetzen? Eigene Wörter fallen weg.";
    public static final int ACTIVE = 0;
    public static final int WORD = 1;
    public static final int ACTIONS = 2;

    private final String title;
    private final List<String> defaults;
    private final List<String> words = new ArrayList<>();
    private final Set<String> off = new LinkedHashSet<>();
    private boolean loaded;
    private boolean updating;
    private final DefaultTableModel model = new DefaultTableModel(new Object[] {ACTIVE_COLUMN, WORD_COLUMN, ""}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == ACTIVE;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == ACTIVE ? Boolean.class : Object.class;
        }
    };
    private final JTable table = Comic.table(new JTable(model));
    private final JTextField input = new PlaceholderField(12, "Neues Wort");
    private final JButton add = InputFrame.flatButton("Hinzufügen", ADD_TIP);
    private final IconButton allOff = new IconButton(Glyph.CIRCLE_EMPTY, ALL_OFF_TIP).compact();
    private final IconButton reset = new IconButton(Glyph.BACK, DEFAULT_TIP).compact();
    private final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final WrappedText invalid = Comic.hint(" ");
    private final JScrollPane scroll;
    /** Macht aus der Eingabe das Wort der Liste. */
    private UnaryOperator<String> normalizer = typed -> typed.strip().toLowerCase(Locale.ROOT);
    /** Der Grund, warum ein Wort nicht taugt, in Worten; {@code null} wenn es taugt. */
    private Function<String, String> check = word -> word.chars().allMatch(Character::isLetterOrDigit) ? null : INVALID;
    /** Fragt vor „Alle aus“ und „Vorgabe“; Tests ersetzen die Rückfrage. */
    private BiPredicate<String, String> confirm = (heading, question) -> Dialogs.confirm(panel, heading, question);

    /**
     * @param title    Überschrift der Liste
     * @param defaults die mitgelieferten Wörter, klein geschrieben; sie stehen immer in der Liste
     */
    public WordTable(String title, List<String> defaults) {
        this.title = Objects.requireNonNull(title, "title");
        this.defaults = List.copyOf(defaults);
        panel.setOpaque(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var columns = table.getColumnModel();
        columns.getColumn(ACTIVE).setMinWidth(52);
        columns.getColumn(ACTIVE).setMaxWidth(52);
        JCheckBox editor = Comic.checkBox("");
        editor.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        columns.getColumn(ACTIVE).setCellEditor(new javax.swing.DefaultCellEditor(editor));
        RowActions.install(table, ACTIONS, 1, row -> row < words.size() && !this.defaults.contains(words.get(row))
                ? List.of(new RowActions.Action(Glyph.CANCEL, REMOVE_TIP, true, () -> remove(words.get(row))))
                : List.of(RowActions.EMPTY));
        model.addTableModelListener(e -> {
            if (updating || e.getColumn() != ACTIVE || e.getFirstRow() < 0 || e.getFirstRow() >= words.size()) {
                return;
            }
            String word = words.get(e.getFirstRow());
            if (Boolean.TRUE.equals(model.getValueAt(e.getFirstRow(), ACTIVE))) {
                off.remove(word);
            } else {
                off.add(word);
            }
        });
        scroll = Comic.scroll(table);
        scroll.setColumnHeaderView(table.getTableHeader());

        JLabel heading = new JLabel(title);
        heading.setFont(Comic.semiBold(13f));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        tools.setOpaque(false);
        tools.add(allOff);
        tools.add(reset);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(heading, BorderLayout.WEST);
        head.add(tools, BorderLayout.EAST);

        input.getAccessibleContext().setAccessibleName("Neues Wort: " + title);
        InputFrame field = new InputFrame(input);
        field.addClearButton();
        field.addButton(add);
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        invalid.setAlignmentX(Component.LEFT_ALIGNMENT);
        invalid.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 0));
        bottom.add(field);
        bottom.add(invalid);

        panel.add(head, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        add.addActionListener(e -> addTyped());
        input.addActionListener(e -> addTyped());
        allOff.addActionListener(e -> {
            if (confirm.test(title, ALL_OFF_QUESTION)) {
                allOff();
            }
        });
        reset.addActionListener(e -> {
            if (confirm.test(title, DEFAULT_QUESTION)) {
                reset();
            }
        });
        refresh();
    }

    public JComponent component() {
        return panel;
    }

    /**
     * Eigene Regeln für neue Wörter (etwa die des Core beim Fachglossar: Leerzeichen und Bindestrich erlaubt).
     *
     * @param normalize macht aus der Eingabe das Wort der Liste
     * @param problem   der Grund in Worten, warum ein Wort nicht taugt (steht unter dem Feld); {@code null} wenn es taugt
     */
    public WordTable checkWith(UnaryOperator<String> normalize, Function<String, String> problem) {
        this.normalizer = Objects.requireNonNull(normalize, "normalize");
        this.check = Objects.requireNonNull(problem, "problem");
        return this;
    }

    /** Ob die Tabelle schon aus der Datei gefüllt wurde; vorher gilt nichts als geändert. */
    public boolean loaded() {
        return loaded;
    }

    /** Nach dem Speichern: die Tabelle darf wieder aus der Datei gefüllt werden. */
    public void forget() {
        loaded = false;
    }

    /**
     * Füllt die Tabelle: die Vorgabewörter immer, dazu die eigenen.
     *
     * @param active die gelten (Vorgabewörter und eigene); Vorgabewörter, die fehlen, stehen abgehakt da
     */
    public void show(List<String> active) {
        loaded = true;
        words.clear();
        off.clear();
        words.addAll(defaults);
        active.stream().filter(word -> !words.contains(word)).forEach(words::add);
        words.stream().filter(word -> !active.contains(word)).forEach(off::add);
        refresh();
    }

    /** Die geltenden Wörter in Reihenfolge der Tabelle. */
    public List<String> active() {
        return words.stream().filter(word -> !off.contains(word)).toList();
    }

    /** Eigene Wörter mit Häkchen (für Listen, die nur Abweichungen speichern: „dazu“). */
    public List<String> added() {
        return active().stream().filter(word -> !defaults.contains(word)).toList();
    }

    /** Abgehakte Vorgabewörter („heraus“). */
    public List<String> excluded() {
        return defaults.stream().filter(off::contains).toList();
    }

    public List<String> words() {
        return List.copyOf(words);
    }

    public void allOff() {
        off.addAll(words);
        refresh();
    }

    public void reset() {
        words.clear();
        off.clear();
        words.addAll(defaults);
        refresh();
    }

    public void remove(String word) {
        if (!defaults.contains(word) && words.remove(word)) {
            off.remove(word);
            refresh();
        }
    }

    // ---- für Tests

    public void confirmWith(BiPredicate<String, String> confirm) {
        this.confirm = Objects.requireNonNull(confirm, "confirm");
    }

    public JTable table() {
        return table;
    }

    public JTextField input() {
        return input;
    }

    public JButton addButton() {
        return add;
    }

    public IconButton allOffButton() {
        return allOff;
    }

    public IconButton resetButton() {
        return reset;
    }

    public String invalidText() {
        return invalid.getText();
    }

    private void addTyped() {
        String word = normalizer.apply(input.getText());
        if (word == null || word.isEmpty()) {
            return;
        }
        String problem = check.apply(word);
        if (problem != null) {
            invalid.setText(problem);
            return;
        }
        invalid.setText(" ");
        if (!words.contains(word)) {
            words.add(word);
        }
        off.remove(word);
        input.setText("");
        refresh();
        table.changeSelection(words.indexOf(word), WORD, false, false);
    }

    private void refresh() {
        updating = true;
        try {
            model.setRowCount(0);
            for (String word : words) {
                model.addRow(new Object[] {!off.contains(word), word, null});
            }
        } finally {
            updating = false;
        }
        // Alle Wörter ohne Rollbalken, erst ab zwölf Zeilen rollt die Tabelle.
        int rows = Math.max(6, Math.min(12, words.size()));
        int header = table.getTableHeader().getPreferredSize().height;
        scroll.setPreferredSize(new Dimension(240, rows * table.getRowHeight() + header + 4));
        scroll.revalidate();
    }
}
