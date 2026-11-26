package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sync.Exclusions.Exclusion;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Übersicht „Ausgeschlossen“ einer Wissensquelle (Angelo 26.11.2026): oben alle Ausschlüsse der Quelle mit ✕ je Zeile
 * zum Wiedereinblenden, darunter die oberste Ebene der Quelle, live abgefragt, mit Häkchen zum Ausschließen (wie die
 * Mehrfachauswahl der NDV-Bibliotheken). Tiefer liegende Ordner schließt man per Rechtsklick im Baum aus; wieder
 * eingeblendet wird nur hier. Ausschließen und Einblenden ändern nur den Umfang, gelöscht wird nichts.
 */
final class ExclusionsDialog {

    static final String TITLE = "Ausgeschlossen";
    static final String NOTE = "Ausgeschlossene Ordner erscheinen nicht im Wissensbaum und in der Suche und werden nicht "
            + "indexiert. Gelöscht wird nichts: Bereits Geladenes bleibt erhalten, bis du es ausdrücklich bereinigst. "
            + "Ordner schließt du am schnellsten per Rechtsklick im Baum aus; wieder einblenden geht hier.";
    static final String LIST_HEADING = "Ausgeschlossene Ordner";
    static final String TOP_HEADING = "Oberste Ebene";
    static final String NOTHING = "Nichts ausgeschlossen";
    static final String LOADING = "Oberste Ebene wird abgefragt …";
    static final String FOLDER_COLUMN = "Ordner";
    static final String SPACE_COLUMN = "Bereich";
    static final String EXCLUDED_COLUMN = "Ausgeschlossen";
    static final String INCLUDE_TOOLTIP = "Wieder einblenden";
    static final String WHOLE_SPACE = "ganzer Bereich";
    static final int LIST_FOLDER = 0;
    static final int LIST_SPACE = 1;
    static final int LIST_ACTIONS = 2;
    static final int TOP_EXCLUDED = 0;
    static final int TOP_FOLDER = 1;

    /** Ein Eintrag der obersten Ebene: ein Bereich ({@code pageId == null}) oder ein Ordner darin. */
    record Top(String spaceKey, String pageId, String title) {
    }

    /** Was der Dialog von der Laufzeit braucht; Tests ersetzen es. */
    interface Access {

        List<Exclusion> exclusions(String sourceId);

        List<Top> topLevel(String sourceId);

        void exclude(String sourceId, String spaceKey, String pageId);

        void include(String sourceId, String spaceKey, String pageId);
    }

    private final String sourceId;
    private final Access access;
    private final Runnable changed;
    private final List<Exclusion> exclusions = new ArrayList<>();
    private final List<Top> top = new ArrayList<>();
    private final DefaultTableModel listModel = new DefaultTableModel(
            new Object[] {FOLDER_COLUMN, SPACE_COLUMN, ""}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final DefaultTableModel topModel = new DefaultTableModel(new Object[] {EXCLUDED_COLUMN, FOLDER_COLUMN}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == TOP_EXCLUDED;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == TOP_EXCLUDED ? Boolean.class : Object.class;
        }
    };
    final JTable list = Comic.table(new JTable(listModel));
    final JTable topTable = Comic.table(new JTable(topModel));
    final Feedback feedback = new Feedback();
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));
    /** Während die Tabellen neu gefüllt werden, ist ein geändertes Häkchen keine Benutzerwahl. */
    private boolean filling;

    /** @param changed läuft nach jedem Ausschließen und Einblenden, etwa um den Baum neu zu laden */
    ExclusionsDialog(String sourceId, Access access, Runnable changed) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.access = Objects.requireNonNull(access, "access");
        this.changed = Objects.requireNonNull(changed, "changed");
        panel.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getColumnModel().getColumn(LIST_SPACE).setPreferredWidth(140);
        list.getColumnModel().getColumn(LIST_SPACE).setMaxWidth(220);
        // Wieder einblenden als ✕ in der Zeile, wie Angelo es für alle Tabellen vorgibt.
        RowActions.install(list, LIST_ACTIONS, 1, row -> List.of(new RowActions.Action(Glyph.CANCEL,
                INCLUDE_TOOLTIP, true, () -> include(exclusions.get(row)))));
        topTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        topTable.getColumnModel().getColumn(TOP_EXCLUDED).setMinWidth(110);
        topTable.getColumnModel().getColumn(TOP_EXCLUDED).setMaxWidth(110);
        topModel.addTableModelListener(event -> {
            int row = event.getFirstRow();
            if (!filling && event.getColumn() == TOP_EXCLUDED && row >= 0 && row < top.size()) {
                toggled(top.get(row), Boolean.TRUE.equals(topModel.getValueAt(row, TOP_EXCLUDED)));
            }
        });
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        for (JComponent part : List.of(Comic.wrapped(NOTE, Comic.TEXT, 12.5f), heading(LIST_HEADING),
                scroll(list, 120), heading(TOP_HEADING), scroll(topTable, 160))) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(part);
            form.add(javax.swing.Box.createVerticalStrut(6));
        }
        panel.add(form, BorderLayout.CENTER);
        panel.add(feedback, BorderLayout.SOUTH);
    }

    /** Öffnet die Übersicht für die Quelle; {@code changed} läuft nach jeder Änderung. */
    static void open(Component parent, AppContext context, String sourceId, String sourceTitle, Runnable changed) {
        ExclusionsDialog view = new ExclusionsDialog(sourceId, runtimeAccess(context), changed);
        JDialog dialog = Dialogs.dialog(parent, TITLE);
        ComposerButton close = Comic.primary("Schließen", "Übersicht schließen");
        close.addActionListener(event -> dialog.dispose());
        view.reload();
        Dialogs.show(dialog, TITLE + " · " + sourceTitle, view.component(), close);
    }

    JComponent component() {
        return panel;
    }

    /** Liest Ausschlüsse und oberste Ebene neu, abseits des EDT. */
    void reload() {
        feedback.working(LOADING);
        Background.run(() -> new Loaded(access.exclusions(sourceId), access.topLevel(sourceId)), loaded -> {
            show(loaded.exclusions(), loaded.top());
            feedback.clear();
        }, feedback::problem);
    }

    private record Loaded(List<Exclusion> exclusions, List<Top> top) {
    }

    /** Füllt beide Tabellen; das Häkchen der obersten Ebene folgt den Ausschlüssen. */
    void show(List<Exclusion> excluded, List<Top> topLevel) {
        filling = true;
        try {
            exclusions.clear();
            exclusions.addAll(excluded);
            listModel.setRowCount(0);
            for (Exclusion exclusion : exclusions) {
                listModel.addRow(new Object[] {label(exclusion), exclusion.spaceKey(), ""});
            }
            if (exclusions.isEmpty()) {
                feedback.info(NOTHING, null);
            }
            top.clear();
            top.addAll(topLevel);
            topModel.setRowCount(0);
            for (Top entry : top) {
                topModel.addRow(new Object[] {excluded(entry), entry.title()});
            }
        } finally {
            filling = false;
        }
    }

    /** Für Tests: die Zeilen der Ausschlüsse, wie sie zu sehen sind. */
    List<String> listed() {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < listModel.getRowCount(); row++) {
            rows.add(listModel.getValueAt(row, LIST_FOLDER) + " · " + listModel.getValueAt(row, LIST_SPACE));
        }
        return rows;
    }

    /** Für Tests: Wiedereinblenden wie das ✕ der Zeile. */
    void includeRow(int row) {
        include(exclusions.get(row));
    }

    private boolean excluded(Top entry) {
        return exclusions.stream().anyMatch(exclusion -> exclusion.spaceKey().equals(entry.spaceKey())
                && Objects.equals(exclusion.pageId(), entry.pageId()));
    }

    private static String label(Exclusion exclusion) {
        if (exclusion.wholeSpace()) {
            return WHOLE_SPACE;
        }
        return exclusion.title().isEmpty() ? exclusion.pageId() : exclusion.title();
    }

    private void toggled(Top entry, boolean exclude) {
        run(() -> {
            if (exclude) {
                access.exclude(sourceId, entry.spaceKey(), entry.pageId());
            } else {
                access.include(sourceId, entry.spaceKey(), entry.pageId());
            }
        });
    }

    private void include(Exclusion exclusion) {
        run(() -> access.include(sourceId, exclusion.spaceKey(), exclusion.pageId()));
    }

    private void run(Runnable change) {
        Background.run(() -> {
            change.run();
            return access.exclusions(sourceId);
        }, excluded -> {
            show(excluded, List.copyOf(top));
            changed.run();
        }, feedback::problem);
    }

    private static JComponent heading(String text) {
        return Comic.wrapped(text, Comic.INK, 13.5f);
    }

    private static JComponent scroll(JTable table, int height) {
        var scroll = Comic.scroll(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setPreferredSize(new Dimension(460, height));
        return scroll;
    }

    /** Die oberste Ebene: mehrere Bereiche (Spaces) oder, bei einem einzigen, dessen Ordner (etwa Bibliotheken). */
    static List<Top> topLevel(Workspace workspace, String sourceId) {
        List<Partition> spaces = workspace.allSpaces(sourceId);
        List<Top> rows = new ArrayList<>();
        if (spaces.size() != 1) {
            spaces.forEach(space -> rows.add(new Top(space.key(), null,
                    space.name() == null || space.name().isBlank() ? space.key() : space.name())));
            return rows;
        }
        String spaceKey = spaces.getFirst().key();
        String cursor = null;
        do {
            PageNavigation.Children batch = workspace.allChildren(sourceId, spaceKey, null, cursor);
            batch.pages().forEach(page -> rows.add(new Top(spaceKey, page.pageId(), page.title())));
            cursor = batch.nextCursor();
        } while (cursor != null);
        return rows;
    }

    private static Access runtimeAccess(AppContext context) {
        return new Access() {
            @Override
            public List<Exclusion> exclusions(String sourceId) {
                return workspace().exclusions(sourceId);
            }

            @Override
            public List<Top> topLevel(String sourceId) {
                return ExclusionsDialog.topLevel(workspace(), sourceId);
            }

            @Override
            public void exclude(String sourceId, String spaceKey, String pageId) {
                workspace().exclude(sourceId, spaceKey, pageId);
            }

            @Override
            public void include(String sourceId, String spaceKey, String pageId) {
                workspace().include(sourceId, spaceKey, pageId);
            }

            private Workspace workspace() {
                AppRuntime runtime = context.runtime();
                if (runtime == null) {
                    throw new IllegalStateException("Die Laufzeit ist nicht gestartet");
                }
                return runtime.workspace();
            }
        };
    }
}
