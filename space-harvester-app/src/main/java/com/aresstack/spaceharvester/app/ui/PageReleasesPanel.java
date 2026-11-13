package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.release.PageReleaseService.Group;
import com.aresstack.spaceharvester.application.release.PageReleases;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Einstellungen › Sicherheit, Platte „Für KI freigegebene Seiten“ (ADR 0028): die Seiten, die der Chat auf Anfrage des
 * Benutzers an den KI-Dienst gegeben hat, gebündelt nach Quelle, Space und Knoten (Seite samt Unterseiten oder ganzer
 * Space). Je Zeile ein Häkchen für „Angehakte entfernen“ und rechts der Papierkorb; Entfernen fragt mit „wirklich“ nach
 * und löscht auch die KI-Ergebnisse der Seiten (im Core, im Hintergrund). Die Liste liest einmal und danach nur auf
 * Meldung des Core ({@code subscribePageReleases}, ADR 0027), solange die Platte sichtbar ist.
 */
public final class PageReleasesPanel {

    static final String HEADING = "Für KI freigegebene Seiten";
    static final String EMPTY = "Noch keine Seite freigegeben. Der Chat gibt beim Senden die links gewählten Seiten "
            + "frei, auch ohne Haken an der Quelle; sie stehen dann hier.";
    static final String REMOVE_TOOLTIP = "Freigabe entfernen; die KI-Ergebnisse dieser Seiten werden gelöscht";
    static final String REMOVE_CHECKED = "Angehakte entfernen";
    static final String WHOLE_SPACE = "ganzer Space";
    static final int CHECK = 0;
    static final int SOURCE = 1;
    static final int SPACE = 2;
    static final int NODE = 3;
    static final int PAGES = 4;
    static final int RELEASED = 5;
    static final int ACTIONS = 6;

    /** Die Aufrufe an den Core; in Tests ersetzbar. */
    interface Access {

        /** Die Gruppen der Liste; nie auf dem EDT. */
        List<Group> groups();

        /** Entfernt eine Gruppe; liefert die Zahl der Seiten. Nie auf dem EDT. */
        int removeGroup(String sourceId, String spaceKey, String node);

        Subscription subscribe(Consumer<PageReleases.Change> listener);

        /** Anzeigename einer Quelle, oder ihre ID. */
        String sourceTitle(String sourceId);

        static Access of(AppContext context, AppRuntime runtime) {
            Workspace workspace = runtime.workspace();
            return new Access() {
                @Override
                public List<Group> groups() {
                    return workspace.pageReleaseGroups();
                }

                @Override
                public int removeGroup(String sourceId, String spaceKey, String node) {
                    return workspace.removePageReleaseGroup(sourceId, spaceKey, node);
                }

                @Override
                public Subscription subscribe(Consumer<PageReleases.Change> listener) {
                    return workspace.subscribePageReleases(listener);
                }

                @Override
                public String sourceTitle(String sourceId) {
                    AppSettings settings = context.settings();
                    if (settings == null) {
                        return sourceId;
                    }
                    return settings.sources().stream().filter(source -> source.config().id().equals(sourceId))
                            .map(source -> source.config().title()).filter(title -> title != null && !title.isBlank())
                            .findFirst().orElse(sourceId);
                }
            };
        }
    }

    /** Fragt vor dem Entfernen; {@code true} nur nach „Ja“. */
    interface Confirmation {
        boolean confirm(Component parent, String question);
    }

    private final AppContext context;
    private final Function<AppRuntime, Access> access;
    private final Confirmation confirmation;
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"", "Quelle", "Space", "Knoten", "Seiten", "Freigegeben", ""}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == CHECK;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == CHECK ? Boolean.class : Object.class;
        }
    };
    final JTable table = Comic.table(new JTable(model));
    final ComposerButton removeChecked = Comic.danger(REMOVE_CHECKED,
            "Die Freigabe aller angehakten Gruppen entfernen; ihre KI-Ergebnisse werden gelöscht");
    final Feedback feedback = new Feedback();
    private final javax.swing.JTextArea empty = Comic.note(EMPTY);
    private final List<Group> shown = new ArrayList<>();
    private Subscription subscription = Subscription.NONE;
    private AppRuntime subscribed;
    private boolean refreshPending;
    private int request;

    PageReleasesPanel(AppContext context) {
        this(context, runtime -> Access.of(context, runtime),
                (parent, question) -> Dialogs.confirm(parent, "Freigabe entfernen?", question));
    }

    PageReleasesPanel(AppContext context, Function<AppRuntime, Access> access, Confirmation confirmation) {
        this.context = context;
        this.access = access;
        this.confirmation = confirmation;
        root.setOpaque(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        var columns = table.getColumnModel();
        columns.getColumn(CHECK).setMinWidth(40);
        columns.getColumn(CHECK).setMaxWidth(40);
        javax.swing.JCheckBox check = Comic.checkBox("");
        check.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        check.setOpaque(true);
        check.setBackground(Comic.YELLOW);
        columns.getColumn(CHECK).setCellEditor(new javax.swing.DefaultCellEditor(check));
        columns.getColumn(PAGES).setMaxWidth(70);
        columns.getColumn(RELEASED).setPreferredWidth(130);
        columns.getColumn(RELEASED).setMaxWidth(160);
        RowActions.install(table, ACTIONS, 1, row -> List.of(new RowActions.Action(Glyph.TRASH, REMOVE_TOOLTIP, true,
                () -> remove(List.of(shown.get(row))))));
        model.addTableModelListener(e -> removeChecked.setEnabled(!checked().isEmpty()));
        removeChecked.setEnabled(false);
        removeChecked.addActionListener(e -> remove(checked()));
        var scroll = Comic.scroll(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setPreferredSize(new Dimension(420, 140));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(Comic.wrapped("Diese Seiten darf der KI-Dienst auswerten (Embeddings, Reranking, Aussagen, Themen), "
                + "weil du sie im Chat gesendet hast; die Freigabe je Quelle steht unter KI-Dienst. Entfernen löscht "
                + "auch die KI-Ergebnisse der Seiten.", Comic.TEXT, 12.5f), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(empty, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        body.add(Comic.row(removeChecked), BorderLayout.SOUTH);
        root.add(body, BorderLayout.CENTER);
        root.add(feedback, BorderLayout.SOUTH);
        empty.setVisible(true);
        scroll.setVisible(false);
        // Sichtbar: anmelden und lesen; verborgen: abmelden. Kein Zeitgeber (ADR 0027).
        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (root.isShowing()) {
                    refresh();
                } else {
                    unsubscribe();
                }
            }
        });
        context.onChange(ignored -> {
            if (root.isShowing()) {
                refresh();
            }
        });
    }

    JComponent component() {
        return root;
    }

    private void subscribe(AppRuntime runtime) {
        if (runtime == subscribed) {
            return;
        }
        unsubscribe();
        subscribed = runtime;
        if (runtime != null) {
            subscription = access.apply(runtime).subscribe(change -> scheduleRefresh());
        }
    }

    private void unsubscribe() {
        subscription.close();
        subscription = Subscription.NONE;
        subscribed = null;
    }

    /** Vom Thread des Core: einmal auf dem EDT lesen, auch bei mehreren Meldungen hintereinander. */
    private void scheduleRefresh() {
        synchronized (this) {
            if (refreshPending) {
                return;
            }
            refreshPending = true;
        }
        SwingUtilities.invokeLater(() -> {
            synchronized (this) {
                refreshPending = false;
            }
            refresh();
        });
    }

    /** Liest die Gruppen im Hintergrund; nur das Ergebnis der jüngsten Abfrage zählt. Erst anmelden, dann lesen. */
    void refresh() {
        if (!root.isShowing()) {
            // Eine späte Rückmeldung (Entfernen, gebündelte Meldung) darf die Abmeldung beim Verbergen nicht aufheben.
            unsubscribe();
            return;
        }
        AppRuntime runtime = context.runtime();
        subscribe(runtime);
        int ticket = ++request;
        if (runtime == null) {
            render(List.of(), null);
            return;
        }
        Access current = access.apply(runtime);
        Background.run(current::groups, groups -> {
            if (ticket == request) {
                render(groups, current);
            }
        }, error -> {
            if (ticket == request) {
                feedback.problem(error);
            }
        });
    }

    private void render(List<Group> groups, Access current) {
        List<Group> keep = checked();
        shown.clear();
        shown.addAll(groups);
        model.setRowCount(0);
        for (Group group : groups) {
            model.addRow(new Object[] {keep.contains(group), current == null ? group.sourceId()
                    : current.sourceTitle(group.sourceId()), group.spaceKey(), nodeText(group),
                    String.valueOf(group.count()), Texts.time(group.releasedAt()), ""});
        }
        boolean any = !groups.isEmpty();
        empty.setVisible(!any);
        table.getParent().getParent().setVisible(any);
        removeChecked.setEnabled(!checked().isEmpty());
        root.revalidate();
        root.repaint();
    }

    /** „ganzer Space“ oder der Knoten: Titel samt Unterseiten, ohne Titel die Seiten-ID. */
    static String nodeText(Group group) {
        if (group.node() == null) {
            return WHOLE_SPACE;
        }
        String title = group.nodeTitle() == null || group.nodeTitle().isBlank() ? "Seite " + group.node()
                : group.nodeTitle();
        return title + " samt Unterseiten";
    }

    private List<Group> checked() {
        List<Group> result = new ArrayList<>();
        for (int row = 0; row < model.getRowCount() && row < shown.size(); row++) {
            if (Boolean.TRUE.equals(model.getValueAt(row, CHECK))) {
                result.add(shown.get(row));
            }
        }
        return result;
    }

    /** Fragt einmal nach und entfernt dann die Gruppen im Hintergrund; die Liste frischt per Meldung auf. */
    void remove(List<Group> groups) {
        AppRuntime runtime = context.runtime();
        if (groups.isEmpty() || runtime == null) {
            return;
        }
        if (!confirmation.confirm(root, question(groups))) {
            return;
        }
        Access current = access.apply(runtime);
        feedback.working("Freigabe wird entfernt …");
        Background.run(() -> {
            int pages = 0;
            for (Group group : groups) {
                pages += current.removeGroup(group.sourceId(), group.spaceKey(), group.node());
            }
            return pages;
        }, pages -> {
            feedback.success("Freigabe von " + Texts.count(pages, "Seite", "Seiten")
                    + " entfernt; die KI-Ergebnisse werden gelöscht");
            refresh();
        }, feedback::problem);
    }

    /** „Die Freigabe von 12 Seiten (Space DEV, Handbuch samt Unterseiten) wirklich entfernen? …“ */
    static String question(List<Group> groups) {
        int pages = groups.stream().mapToInt(Group::count).sum();
        String where = groups.size() == 1 ? " (Space " + groups.get(0).spaceKey() + ", " + nodeText(groups.get(0)) + ")"
                : " in " + groups.size() + " Gruppen";
        return "Die Freigabe von " + Texts.count(pages, "Seite", "Seiten") + where
                + " wirklich entfernen? Die KI-Ergebnisse dieser Seiten werden gelöscht.";
    }

    // ------------------------------------------------------------------ für Tests

    int rowCount() {
        return model.getRowCount();
    }

    String cell(int row, int column) {
        return String.valueOf(model.getValueAt(row, column));
    }

    void check(int row) {
        model.setValueAt(Boolean.TRUE, row, CHECK);
    }
}
