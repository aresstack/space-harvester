package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.application.configuration.StandingSettings;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Platte „Wörter für den Stand“ unter Einstellungen › Modelle, bei der Aussagenanalyse (ADR 0032, FL6, S5): die Wörter,
 * an denen Labels und Titel eine Seite als überholt oder als Entwurf kennzeichnen
 * ({@code space-harvester.statements.standing.outdated-words} und {@code .draft-words}). Je Liste eine Tabelle mit
 * Häkchen „Aktiv“, dem Wort und ✕ für eigene Wörter; die Vorgabewörter stehen immer da und lassen sich nur abhaken.
 * In der Kopfzeile jeder Liste „Alle aus“ (schreibt „-“, keine Wörter) und „Vorgabe“ (zurücksetzen). Neue Wörter über
 * das Feld darunter (Enter). „Speichern“ schreibt nur den Block {@code statements.standing}.
 */
final class StandingWordsEditor {

    static final String HEADING = "Wörter für den Stand";
    static final String OUTDATED_TITLE = "Seite überholt";
    static final String DRAFT_TITLE = "Seite ist Entwurf";
    static final String HELP = "Steht eines dieser Wörter als Label oder als ganzes Wort im Titel einer Seite oder "
            + "eines Vorfahren, gilt die Seite als überholt bzw. als Entwurf. Das zieht nur Aussagen mit, die selbst "
            + "nichts über die Zeit sagen; eine Entscheidung von Hand gewinnt immer. Groß- und Kleinschreibung zählt "
            + "nicht.\n\nDie Vorgabewörter lassen sich abhaken, aber nicht löschen; „Alle aus“ heißt: keine Wörter "
            + "(in der Datei als „-“). „Vorgabe“ stellt die Liste zurück. Gilt nach dem Speichern beim nächsten Lesen "
            + "des Stands.";
    static final String ACTIVE_COLUMN = "Aktiv";
    static final String WORD_COLUMN = "Wort";
    static final String ALL_OFF_TIP = "Alle aus: keine Wörter für diese Liste";
    static final String DEFAULT_TIP = "Auf die Vorgabe zurücksetzen";
    static final String REMOVE_TIP = "Eigenes Wort entfernen";
    static final String ADD_TIP = "Das Wort in die Liste aufnehmen (Enter)";
    static final String INVALID = "Nur ganze Wörter aus Buchstaben und Ziffern";
    static final int ACTIVE = 0;
    static final int WORD = 1;
    static final int ACTIONS = 2;

    private final AppContext context;
    final WordList outdated = new WordList(OUTDATED_TITLE, StandingSettings.DEFAULT_OUTDATED_WORDS);
    final WordList draft = new WordList(DRAFT_TITLE, StandingSettings.DEFAULT_DRAFT_WORDS);
    final ComposerButton save = Comic.primary("Speichern", "Wörter für den Stand in die Konfiguration schreiben");
    final Feedback feedback = new Feedback();
    final HelpButton help = new HelpButton(Dialogs.CLOSE_SIZE, HEADING, HELP);
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));

    StandingWordsEditor(AppContext context) {
        this.context = context;
        panel.setOpaque(false);
        JPanel lists = new JPanel(new java.awt.GridLayout(1, 2, 16, 0));
        lists.setOpaque(false);
        lists.add(outdated.component());
        lists.add(draft.component());
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(save), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        // Überschrift selbst gesetzt, damit der ?-Knopf in ihrer Zeile steht (die Platte hat keine Kopfaktionen).
        JLabel title = new JLabel(HEADING);
        title.setForeground(Comic.INK);
        title.setFont(Comic.semiBold(14f));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(title, BorderLayout.WEST);
        top.add(help, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(lists, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        save.addActionListener(e -> save());
        render();
    }

    JComponent component() {
        return panel;
    }


    /** Zeigt den gespeicherten Stand; noch nicht gespeicherte Änderungen bleiben stehen. */
    void render() {
        if (changed()) {
            return;
        }
        StandingSettings saved = saved();
        outdated.show(saved.outdatedWords());
        draft.show(saved.draftWords());
    }

    boolean changed() {
        StandingSettings saved = saved();
        return outdated.loaded() && (!ConfigFileEditor.sameWords(outdated.active(), saved.outdatedWords())
                || !ConfigFileEditor.sameWords(draft.active(), saved.draftWords()));
    }

    /** Die Einstellung, wie sie gespeichert würde. */
    StandingSettings settings() {
        return new StandingSettings(outdated.active(), draft.active());
    }

    void save() {
        if (!changed()) {
            feedback.info("Nichts geändert", null);
            return;
        }
        StandingSettings chosen = settings();
        Path file = context.configFile();
        save.setEnabled(false);
        feedback.working("Wörter werden gespeichert …");
        Background.execute(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withStanding(file, new ConfigFileEditor.Draft(text, null), chosen);
            editor.save(file, written, !editor.fileHasPlaintextSecret(file));
        }, () -> context.reload(() -> {
            save.setEnabled(true);
            outdated.forget();
            render();
            feedback.success("Wörter für den Stand übernommen");
        }), error -> {
            save.setEnabled(true);
            feedback.problem(error);
        });
    }

    private StandingSettings saved() {
        AppSettings settings = context.settings();
        return settings == null ? StandingSettings.DEFAULT : settings.standing();
    }

    /** Kurzform für die Abschlussseite des Assistenten. */
    static String summary(StandingSettings standing) {
        return "überholt: " + count(standing.outdatedWords(), StandingSettings.DEFAULT_OUTDATED_WORDS)
                + " · Entwurf: " + count(standing.draftWords(), StandingSettings.DEFAULT_DRAFT_WORDS);
    }

    private static String count(List<String> words, List<String> fallback) {
        if (words.isEmpty()) {
            return "keine Wörter";
        }
        return ConfigFileEditor.sameWords(words, fallback) ? "Vorgabe (" + words.size() + " Wörter)"
                : Texts.count(words.size(), "Wort", "Wörter") + ": " + String.join(", ", words);
    }

    /** Eine Liste: Kopfzeile mit Titel und Icon-Knöpfen, Tabelle, Eingabefeld. */
    static final class WordList {
        private final List<String> defaults;
        private final List<String> words = new ArrayList<>();
        private final Set<String> off = new LinkedHashSet<>();
        private boolean loaded;
        private final DefaultTableModel model = new DefaultTableModel(
                new Object[] {ACTIVE_COLUMN, WORD_COLUMN, ""}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == ACTIVE;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == ACTIVE ? Boolean.class : Object.class;
            }
        };
        final JTable table = Comic.table(new JTable(model));
        final JTextField input = new PlaceholderField(12, "Neues Wort");
        final JButton add = InputFrame.flatButton("Hinzufügen", ADD_TIP);
        final IconButton allOff = new IconButton(Glyph.CIRCLE_EMPTY, ALL_OFF_TIP).compact();
        final IconButton reset = new IconButton(Glyph.BACK, DEFAULT_TIP).compact();
        private final JPanel panel = new JPanel(new BorderLayout(0, 4));
        private final JLabel invalid = Comic.muted(" ");
        private final javax.swing.JScrollPane scroll;
        private boolean updating;

        WordList(String title, List<String> defaults) {
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
            allOff.addActionListener(e -> allOff());
            reset.addActionListener(e -> reset());
        }

        JComponent component() {
            return panel;
        }

        boolean loaded() {
            return loaded;
        }

        /** Nach dem Speichern: die Tabelle darf wieder aus der Datei gefüllt werden. */
        void forget() {
            loaded = false;
        }

        /** Füllt die Tabelle: Vorgabewörter immer, dazu die eigenen; abgehakt, was nicht gespeichert ist. */
        void show(List<String> saved) {
            loaded = true;
            words.clear();
            off.clear();
            words.addAll(defaults);
            saved.stream().filter(word -> !words.contains(word)).forEach(words::add);
            words.stream().filter(word -> !saved.contains(word)).forEach(off::add);
            refresh();
        }

        /** Die aktiven Wörter in Reihenfolge der Tabelle. */
        List<String> active() {
            return words.stream().filter(word -> !off.contains(word)).toList();
        }

        List<String> words() {
            return List.copyOf(words);
        }

        void allOff() {
            off.addAll(words);
            refresh();
        }

        void reset() {
            words.clear();
            off.clear();
            words.addAll(defaults);
            refresh();
        }

        void addTyped() {
            String word = input.getText().strip().toLowerCase(Locale.ROOT);
            if (word.isEmpty()) {
                return;
            }
            if (!word.chars().allMatch(Character::isLetterOrDigit)) {
                invalid.setText(INVALID);
                return;
            }
            invalid.setText(" ");
            if (!words.contains(word)) {
                words.add(word);
            }
            off.remove(word);
            input.setText("");
            refresh();
            int row = words.indexOf(word);
            table.changeSelection(row, WORD, false, false);
        }

        void remove(String word) {
            if (!defaults.contains(word) && words.remove(word)) {
                off.remove(word);
                refresh();
            }
        }

        String invalidText() {
            return invalid.getText();
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
}
