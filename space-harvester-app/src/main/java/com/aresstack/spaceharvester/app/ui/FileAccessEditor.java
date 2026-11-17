package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Platte „Dateizugriff“ unter Einstellungen › Allgemein, wie im Enterprise AI Client: die Ordner, die Space Harvester
 * lesen darf ({@code space-harvester.local-files.roots}), als Tabelle: Häkchen „Aktiv“ (abgehakt = vorübergehend aus,
 * bleibt als {@code space-harvester-app.inactive-roots} stehen), Ordner, Status erreichbar/nicht erreichbar und ein
 * ✕ je Zeile zum Entfernen; „Hinzufügen“ über das Eingabefeld (Ordnerdialog im Feld, Enter). Dazu der Haken
 * „Alle lokalen Dateien und Netzlaufwerke freigeben“ ({@code space-harvester.local-files.allow-all}), der erst nach
 * einer zweiten, ausdrücklichen Bestätigung angeht. Abschalten fragt nicht nach. Eine Freigabe liest nichts ein;
 * „Speichern“ schreibt die Datei und übernimmt sie ohne Neustart der Quellen.
 */
final class FileAccessEditor {

    static final String HEADING = "Dateizugriff";
    static final String NOTE = "Nur freigegebene Ordner kann Space Harvester lesen; gemappte Laufwerke (P:\\Projekt) "
            + "und Netzpfade (\\\\server\\freigabe) zählen wie lokale Ordner, Verknüpfungen hinaus nicht. Der Ordner "
            + "einer Ordner-Quelle ist immer freigegeben; was eingelesen wird, legst du unter Wissensquellen fest. "
            + "Gilt nach dem Speichern ohne Neustart.";
    static final String ACTIVE_COLUMN = "Aktiv";
    static final String PATH_COLUMN = "Ordner oder Netzpfad";
    static final String STATUS_COLUMN = "Status";
    static final String STATUS_CHECKING = "Wird geprüft …";
    static final String STATUS_REACHABLE = "Erreichbar";
    static final String STATUS_UNREACHABLE = "Nicht erreichbar";
    static final String STATUS_INVALID = "Ungültiger Pfad";
    static final String ADD_LABEL = "Hinzufügen";
    static final String CHOOSE_LABEL = "Ordner wählen …";
    static final String REMOVE_TOOLTIP = "Ordner entfernen";
    static final String ACTIVE_TOOLTIP = "Häkchen weg = Zugriff vorübergehend aus, der Eintrag bleibt";
    static final int ACTIVE = 0;
    static final int PATH = 1;
    static final int STATUS = 2;
    static final int ACTIONS = 3;
    static final String ALLOW_ALL_LABEL = "Alle lokalen Dateien und Netzlaufwerke freigeben";
    static final String ALLOW_ALL_HELP = "Standardmäßig aus. Startet kein Einlesen (local-files.allow-all).";
    static final String CONFIRM_TITLE = "Alle Dateizugriffe freigeben";
    static final String CONFIRM_QUESTION = "Wirklich alle Dateien freigeben?";
    static final String CONFIRM_TEXT = "Dadurch darf Space Harvester auf alle Dateien zugreifen, die dein "
            + "Windows-Benutzerkonto lesen kann. Das kann persönliche, vertrauliche und dienstliche Dateien sowie "
            + "erreichbare Netzlaufwerke umfassen. Die Freigabe betrifft den Dateizugriff; ein Einlesen aller "
            + "Laufwerke wird dadurch nicht automatisch gestartet.";

    /** Die zweite Bestätigung, bevor alles freigegeben wird; Tests ersetzen sie. */
    interface Confirmation {
        boolean confirm(Component parent);
    }

    /** Prüft abseits des EDT, ob ein Ordner erreichbar ist; Tests ersetzen die Prüfung, um Verzögerungen zu stellen. */
    interface Probe {
        boolean reachable(Path folder) throws Exception;
    }

    private final AppContext context;
    private final Confirmation confirmation;
    private final Probe probe;
    private final List<String> roots = new ArrayList<>();
    /** Die vorübergehend abgeschalteten Ordner aus {@link #roots}. */
    private final java.util.Set<String> inactive = new java.util.LinkedHashSet<>();
    /**
     * Zuletzt ermittelter Status je Ordner, damit die Tabelle beim Neuaufbau nicht flackert. Entfernte Ordner fallen
     * heraus, und {@link #render()} (nach Speichern oder Neu laden) verwirft alles, damit ein wieder verbundenes
     * Netzlaufwerk nicht dauerhaft als „Nicht erreichbar“ steht.
     */
    private final Map<String, String> status = new HashMap<>();
    /**
     * Die laufende Prüfung je Ordner. Eine Prüfung wird erst angestoßen, wenn keine läuft, und nur das Ergebnis der
     * zuletzt gestarteten zählt: Wird ein Ordner entfernt, wieder aufgenommen oder die Tabelle neu geladen, während
     * eine ältere Prüfung noch läuft, darf deren verspätetes Ergebnis die frische Zeile nicht überschreiben.
     */
    private final Map<String, Integer> running = new HashMap<>();
    private int ticket;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {ACTIVE_COLUMN, PATH_COLUMN, STATUS_COLUMN, ""}, 0) {
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
    final PlaceholderField path = new PlaceholderField(24, "D:\\Wissen oder \\\\server\\team\\wissen");
    final JButton add = InputFrame.flatButton(ADD_LABEL, "Den eingetragenen Ordner in die Liste aufnehmen (Enter)");
    final JButton choose = InputFrame.flatButton(CHOOSE_LABEL, "Einen Ordner im Dialog wählen");
    final JCheckBox allowAll = Comic.checkBox(ALLOW_ALL_LABEL);
    final ComposerButton save = Comic.primary("Speichern", "Dateizugriff in die Konfiguration schreiben");
    final Feedback feedback = new Feedback();
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));
    /** Ob die Tabelle schon einmal aus der Datei gefüllt wurde; vorher gilt nichts als „geändert“. */
    private boolean loaded;

    FileAccessEditor(AppContext context) {
        this(context, parent -> Dialogs.confirm(parent, CONFIRM_TITLE, CONFIRM_QUESTION + " " + CONFIRM_TEXT));
    }

    FileAccessEditor(AppContext context, Confirmation confirmation) {
        this(context, confirmation, Files::isDirectory);
    }

    FileAccessEditor(AppContext context, Confirmation confirmation, Probe probe) {
        this.context = context;
        this.confirmation = confirmation;
        this.probe = probe;
        panel.setOpaque(false);
        allowAll.setToolTipText(ALLOW_ALL_HELP);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        var columns = table.getColumnModel();
        columns.getColumn(ACTIVE).setMinWidth(52);
        columns.getColumn(ACTIVE).setMaxWidth(52);
        columns.getColumn(ACTIVE).setCellRenderer(new ActiveRenderer());
        JCheckBox activeEditor = Comic.checkBox("");
        activeEditor.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        activeEditor.setOpaque(true);
        activeEditor.setBackground(Comic.YELLOW);
        columns.getColumn(ACTIVE).setCellEditor(new javax.swing.DefaultCellEditor(activeEditor));
        columns.getColumn(PATH).setCellRenderer(new PathRenderer());
        columns.getColumn(STATUS).setPreferredWidth(140);
        columns.getColumn(STATUS).setMaxWidth(180);
        columns.getColumn(STATUS).setCellRenderer(new StatusRenderer());
        // Entfernen als ✕ in der Zeile, wie Angelo es für alle Tabellen vorgibt; kein Knopf unter der Tabelle.
        RowActions.install(table, ACTIONS, 1, row -> List.of(new RowActions.Action(Glyph.CANCEL, REMOVE_TOOLTIP, true,
                () -> revoke(roots.get(row)))));
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeSelected");
        table.getActionMap().put("removeSelected", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                removeSelected();
            }
        });
        model.addTableModelListener(e -> {
            if (e.getColumn() == ACTIVE && e.getFirstRow() >= 0 && e.getFirstRow() < roots.size()) {
                activeChanged(roots.get(e.getFirstRow()), Boolean.TRUE.equals(model.getValueAt(e.getFirstRow(), ACTIVE)));
            }
        });
        var scroll = Comic.scroll(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setPreferredSize(new Dimension(420, 120));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Knöpfe, die zum Feld gehören, sitzen im Feld: ✕ bei Text, Ordnerdialog, Hinzufügen.
        InputFrame input = new InputFrame(path);
        input.addClearButton();
        input.addButton(choose);
        input.addButton(add);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        for (JComponent part : List.of(Comic.wrapped(NOTE, Comic.TEXT, 12.5f), scroll, input, allowAll,
                Comic.hint(ALLOW_ALL_HELP))) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(part);
            form.add(javax.swing.Box.createVerticalStrut(6));
        }
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(save), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        panel.add(form, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        add.addActionListener(event -> addTyped());
        path.addActionListener(event -> addTyped());
        choose.addActionListener(event -> chooseFolder());
        allowAll.addActionListener(event -> allowAllToggled());
        save.addActionListener(event -> save());
        render();
    }

    JComponent component() {
        return panel;
    }

    /** Zeigt den gespeicherten Stand; noch nicht gespeicherte Eingaben bleiben stehen. */
    void render() {
        // Vor dem ersten Füllen ist die leere Tabelle keine Änderung: sonst bliebe eine Datei mit Ordnern unsichtbar.
        if (loaded && changed()) {
            return;
        }
        loaded = true;
        roots.clear();
        inactive.clear();
        roots.addAll(savedRoots());
        for (String root : savedInactive()) {
            if (!roots.contains(root)) {
                roots.add(root);
                inactive.add(root);
            }
        }
        allowAll.setSelected(savedAllowAll());
        // Erreichbarkeit ist kein Dauerzustand (Netzlaufwerk verbunden/getrennt): bei jedem Neuaufbau neu prüfen;
        // laufende Prüfungen verfallen, ihr Ergebnis zählt nicht mehr.
        status.clear();
        running.clear();
        refresh();
    }

    /** Ob seit dem letzten Speichern etwas freigegeben, entfernt oder umgeschaltet wurde. */
    boolean changed() {
        // Die Datei hält die Pfade normalisiert; so vergleicht auch die Platte, sonst bliebe „/x/“ für immer geändert.
        return !normalized(activeRoots()).equals(savedRoots()) || !normalized(inactiveRoots()).equals(savedInactive())
                || allowAll.isSelected() != savedAllowAll();
    }

    private static List<String> normalized(List<String> folders) {
        List<String> result = new ArrayList<>();
        for (String folder : folders) {
            String path;
            try {
                path = Path.of(folder).normalize().toString();
            } catch (InvalidPathException e) {
                path = folder;
            }
            if (!result.contains(path)) {
                result.add(path);
            }
        }
        return result;
    }

    /** Nimmt einen Ordner in die Liste auf, außer er ist leer oder schon enthalten. */
    void grant(String folder) {
        String trimmed = folder == null ? "" : folder.trim();
        if (!trimmed.isEmpty() && !roots.contains(trimmed)) {
            roots.add(trimmed);
            refresh();
            int row = roots.size() - 1;
            table.changeSelection(row, 0, false, false);
        }
    }

    void revoke(String folder) {
        if (roots.remove(folder)) {
            inactive.remove(folder);
            forget(folder);
            refresh();
        }
    }

    /** Schaltet einen Ordner vorübergehend ab oder wieder an; der Eintrag bleibt in der Tabelle. */
    void setActive(String folder, boolean active) {
        if (roots.contains(folder) && inactive.contains(folder) == active) {
            activeChanged(folder, active);
            int row = roots.indexOf(folder);
            if (!Boolean.valueOf(active).equals(model.getValueAt(row, ACTIVE))) {
                model.setValueAt(active, row, ACTIVE);
            }
        }
    }

    private void activeChanged(String folder, boolean active) {
        if (active) {
            inactive.remove(folder);
        } else {
            inactive.add(folder);
        }
    }

    /** Entfernt die markierten Zeilen, auch mehrere auf einmal. */
    void removeSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        List<String> doomed = new ArrayList<>();
        for (int row : rows) {
            doomed.add(roots.get(table.convertRowIndexToModel(row)));
        }
        roots.removeAll(doomed);
        doomed.forEach(inactive::remove);
        doomed.forEach(this::forget);
        refresh();
    }

    /** Alle Ordner der Tabelle in Reihenfolge, aktive und abgeschaltete (für Tests). */
    List<String> roots() {
        return Collections.unmodifiableList(roots);
    }

    /** Die aktiven Ordner, wie sie als {@code local-files.roots} gespeichert würden. */
    List<String> activeRoots() {
        return roots.stream().filter(root -> !inactive.contains(root)).toList();
    }

    /** Die abgeschalteten Ordner, wie sie als {@code inactive-roots} gespeichert würden. */
    List<String> inactiveRoots() {
        return roots.stream().filter(inactive::contains).toList();
    }

    /** Der angezeigte Status eines Ordners (für Tests). */
    String status(String folder) {
        return status.getOrDefault(folder, STATUS_CHECKING);
    }

    private List<String> savedRoots() {
        AppSettings settings = context.settings();
        return settings == null ? List.of() : settings.localFiles().roots().stream().map(Path::toString).toList();
    }

    private List<String> savedInactive() {
        AppSettings settings = context.settings();
        return settings == null ? List.of() : settings.inactiveRoots().stream().map(Path::toString).toList();
    }

    private boolean savedAllowAll() {
        AppSettings settings = context.settings();
        return settings != null && settings.localFiles().allowAll();
    }

    private void addTyped() {
        grant(path.getText());
        path.setText("");
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Ordner freigeben");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showDialog(panel, ADD_LABEL) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            grant(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void allowAllToggled() {
        if (allowAll.isSelected() && !confirmation.confirm(panel)) {
            allowAll.setSelected(false);
        }
    }

    /** Schreibt beide Werte wie die übrigen Einstellungen in die Datei und übernimmt sie mit „Neu laden“. */
    void save() {
        if (!changed()) {
            feedback.info("Nichts geändert", null);
            return;
        }
        // Der Core übernimmt den Dateizugriff zur Laufzeit (#411, #421): laufende Aufträge laufen weiter.
        boolean all = allowAll.isSelected();
        List<String> granted = activeRoots();
        List<String> paused = inactiveRoots();
        Path file = context.configFile();
        save.setEnabled(false);
        feedback.working("Dateizugriff wird gespeichert …");
        Background.execute(() -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withFileAccess(file, new ConfigFileEditor.Draft(text, null), all,
                    granted, paused);
            editor.save(file, written, !editor.fileHasPlaintextSecret(file));
        }, () -> context.reload(() -> {
            save.setEnabled(true);
            render();
            if (context.runtime() != null && !context.runtime().lastRejection().isEmpty()) {
                feedback.warning("Nicht übernommen", "Der Core hat die Änderung abgelehnt; die bisherige Freigabe "
                        + "gilt weiter. Details unter Konfiguration.");
            } else {
                feedback.success("Dateizugriff übernommen");
            }
        }), error -> {
            save.setEnabled(true);
            feedback.problem(error);
        });
    }

    /** Baut die Tabelle aus der Liste neu auf und prüft neue Ordner im Hintergrund (Netzpfade können hängen). */
    private void refresh() {
        model.setRowCount(0);
        for (String root : roots) {
            model.addRow(new Object[] {!inactive.contains(root), root, status(root), null});
            if (!status.containsKey(root) && !running.containsKey(root)) {
                check(root);
            }
        }
    }

    /** Vergisst Status und laufende Prüfung eines entfernten Ordners; ein verspätetes Ergebnis verfällt. */
    private void forget(String root) {
        status.remove(root);
        running.remove(root);
    }

    private void check(String root) {
        int mine = ++ticket;
        running.put(root, mine);
        Background.run(() -> {
            try {
                return probe.reachable(Path.of(root)) ? STATUS_REACHABLE : STATUS_UNREACHABLE;
            } catch (InvalidPathException e) {
                return STATUS_INVALID;
            }
        }, result -> show(root, mine, result), error -> show(root, mine, STATUS_UNREACHABLE));
    }

    /** Übernimmt das Ergebnis nur, wenn es von der zuletzt gestarteten Prüfung dieses Ordners stammt. */
    private void show(String root, int mine, String result) {
        Integer current = running.get(root);
        if (current == null || current != mine) {
            return;
        }
        running.remove(root);
        status.put(root, result);
        int row = roots.indexOf(root);
        if (row >= 0 && row < model.getRowCount()) {
            model.setValueAt(result, row, STATUS);
        }
    }

    /** Häkchen mittig im Stil der übrigen Kontrollkästchen ({@link Comic#checkBox}), mit Tooltip. */
    private static final class ActiveRenderer extends JCheckBox implements javax.swing.table.TableCellRenderer {
        ActiveRenderer() {
            setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            setIcon(new com.aresstack.enterpriseai.ui.comic.paint.ResearchCheckIcon());
            setOpaque(true);
            setToolTipText(ACTIVE_TOOLTIP);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                int row, int column) {
            setSelected(Boolean.TRUE.equals(value));
            setBackground(selected ? t.getSelectionBackground() : t.getBackground());
            return this;
        }
    }

    /** Pfad links, Tooltip mit dem vollen Pfad, falls die Spalte ihn abschneidet. */
    private static final class PathRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            label.setToolTipText(value == null ? null : value.toString());
            return label;
        }
    }

    /** Status in der Farbe des Zustands: grün erreichbar, rot nicht erreichbar, grau während der Prüfung. */
    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            String text = value == null ? "" : value.toString();
            Comic.Tone tone = switch (text) {
                case STATUS_REACHABLE -> Comic.Tone.GOOD;
                case STATUS_UNREACHABLE, STATUS_INVALID -> Comic.Tone.PROBLEM;
                default -> Comic.Tone.NEUTRAL;
            };
            label.setForeground(selected ? Comic.INK : Comic.tone(tone));
            return label;
        }
    }
}
