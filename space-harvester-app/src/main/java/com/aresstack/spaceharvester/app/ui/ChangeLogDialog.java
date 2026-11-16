package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.application.changelog.ChangeEntries;
import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeFilter;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Einstellungen › Cache, Dialog „Änderungsprotokoll“ (ADR 0030, Abschnitt 4): die rohen Zeilen des Protokolls, neueste
 * zuerst, mit Suche nach Titel oder Seiten-ID und Filtern nach Quelle, Art und Zeitraum. Nur zur Einsicht; die einzige
 * Aktion öffnet die Seite. Gelesen wird im Hintergrund, einmal beim Öffnen und bei jeder Änderung der Filter, danach
 * nur auf Meldung des Core ({@code subscribeChangeLog}, ADR 0027), solange der Dialog sichtbar ist.
 */
final class ChangeLogDialog {

    static final String TITLE = "Änderungsprotokoll";
    static final String ALL_SOURCES = "Alle Quellen";
    static final String ALL_KINDS = "Alle Arten";
    static final String EMPTY = "Noch keine Änderung protokolliert.";
    static final String NO_MATCH = "Keine Zeile passt zu Suche und Filtern.";
    static final String OPEN_TOOLTIP = "Seite öffnen";
    static final String GONE_TOOLTIP = "Die Seite ist nicht mehr im Cache";
    /** Zeilen je Abruf; „Weitere laden“ holt die nächsten. */
    static final int PAGE = 500;
    static final int TIME = 0;
    static final int SOURCE = 1;
    static final int SPACE = 2;
    static final int PAGE_TITLE = 3;
    static final int KIND = 4;
    static final int TRIGGER = 5;
    static final int ACTIONS = 6;

    /** Der Zeitraum-Filter: Name und Dauer zurück ab jetzt; {@code null} ohne Grenze. */
    enum Period {
        ALL("Gesamter Zeitraum", null), DAY("Letzte 24 Stunden", Duration.ofDays(1)),
        WEEK("Letzte 7 Tage", Duration.ofDays(7)), MONTH("Letzte 30 Tage", Duration.ofDays(30)),
        YEAR("Letztes Jahr", Duration.ofDays(365));

        private final String label;
        private final Duration back;

        Period(String label, Duration back) {
            this.label = label;
            this.back = back;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Die Aufrufe an den Core; in Tests ersetzbar. */
    interface Access {

        /** Eine Seite Zeilen; nie auf dem EDT. */
        ChangeEntries entries(ChangeFilter filter);

        /** Meldet neue Zeilen (ADR 0027); der Empfänger läuft nicht auf dem EDT. */
        Subscription subscribe(Runnable changed);

        /** Die Quellen: ID und Anzeigename, in der Reihenfolge der Konfiguration. */
        Map<String, String> sources();

        static Access of(AppContext context, AppRuntime runtime) {
            Workspace workspace = runtime.workspace();
            return new Access() {
                @Override
                public ChangeEntries entries(ChangeFilter filter) {
                    return workspace.changeEntries(filter);
                }

                @Override
                public Subscription subscribe(Runnable changed) {
                    return workspace.subscribeChangeLog(sourceId -> changed.run());
                }

                @Override
                public Map<String, String> sources() {
                    Map<String, String> sources = new LinkedHashMap<>();
                    AppSettings settings = context.settings();
                    if (settings != null) {
                        for (var source : settings.sources()) {
                            String title = source.config().title();
                            sources.put(source.config().id(), title == null || title.isBlank() ? source.config().id()
                                    : title);
                        }
                    }
                    return sources;
                }
            };
        }
    }

    /** Eine Quelle in der Auswahlliste; {@code id} {@code null} für alle. */
    private record SourceChoice(String id, String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    /** Eine Art in der Auswahlliste; {@code kind} {@code null} für alle. */
    private record KindChoice(ChangeKind kind) {
        @Override
        public String toString() {
            return kind == null ? ALL_KINDS : ChangeLogDialog.kind(kind);
        }
    }

    /** Öffnet eine Seite (Quelle, Seiten-ID) im Hauptfenster. */
    private final java.util.function.BiConsumer<String, String> opener;
    /** Der Zugang zur laufenden Laufzeit; {@code null}, solange keine läuft. */
    private final Supplier<Access> access;
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Zeit", "Quelle", "Space", "Seite", "Art", "Auslöser", ""}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    final JTable table = Comic.table(new JTable(model));
    final PlaceholderField search = new PlaceholderField(24, "Titel oder Seiten-ID");
    final JComboBox<SourceChoice> source = Comic.combo();
    final JComboBox<KindChoice> kinds = Comic.combo();
    final JComboBox<Period> period = Comic.combo();
    final JLabel count = Comic.muted(" ");
    final ComposerButton more = Comic.button("Weitere laden", "Die nächsten " + PAGE + " Zeilen dazuladen");
    final Feedback feedback = new Feedback();
    private final javax.swing.JTextArea empty = Comic.note(EMPTY);
    private final JComponent scroll;
    private final List<ChangeEntry> shown = new ArrayList<>();
    private Map<String, String> titles = Map.of();
    private Subscription subscription = Subscription.NONE;
    private Access subscribed;
    private boolean refreshPending;
    private boolean filling;
    private int request;
    private int total;

    /** Öffnet den Dialog; die Größe merkt er sich. */
    static void open(Component parent, AppContext context) {
        // Ein Zugang je Laufzeit: Nach einem Neustart des Core meldet sich der Dialog an der neuen an.
        AppRuntime[] known = new AppRuntime[1];
        Access[] current = new Access[1];
        ChangeLogDialog dialog = new ChangeLogDialog((sourceId, pageId) -> {
            AppContext.Navigator navigator = context.navigator();
            if (navigator != null) {
                navigator.openPage(sourceId, pageId);
            }
        }, () -> {
            AppRuntime runtime = context.runtime();
            if (runtime != known[0]) {
                known[0] = runtime;
                current[0] = runtime == null ? null : Access.of(context, runtime);
            }
            return current[0];
        });
        Dialogs.viewSized(parent, TITLE, dialog.component(), "change-log", 0.7);
    }

    ChangeLogDialog(java.util.function.BiConsumer<String, String> opener, Supplier<Access> access) {
        this.opener = opener;
        this.access = access;
        root.setOpaque(false);
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (Period value : Period.values()) {
            period.addItem(value);
        }
        kinds.addItem(new KindChoice(null));
        for (ChangeKind kind : ChangeKind.values()) {
            kinds.addItem(new KindChoice(kind));
        }
        source.addItem(new SourceChoice(null, ALL_SOURCES));
        source.setToolTipText("Nur Zeilen dieser Wissensquelle");
        kinds.setToolTipText("Nur Zeilen dieser Art");
        period.setToolTipText("Nur Zeilen aus diesem Zeitraum");

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var columns = table.getColumnModel();
        columns.getColumn(TIME).setPreferredWidth(140);
        columns.getColumn(TIME).setMaxWidth(170);
        columns.getColumn(SOURCE).setPreferredWidth(140);
        columns.getColumn(SPACE).setPreferredWidth(80);
        columns.getColumn(SPACE).setMaxWidth(140);
        columns.getColumn(PAGE_TITLE).setPreferredWidth(360);
        columns.getColumn(KIND).setPreferredWidth(150);
        columns.getColumn(TRIGGER).setPreferredWidth(150);
        RowActions.install(table, ACTIONS, 1, row -> {
            boolean there = row < shown.size() && openable(shown.get(row));
            return List.of(new RowActions.Action(Glyph.EXPORT, there ? OPEN_TOOLTIP : GONE_TOOLTIP, there,
                    () -> openPage(row)));
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && row >= 0 && table.columnAtPoint(e.getPoint()) != ACTIONS) {
                    openPage(row);
                }
            }
        });
        var pane = Comic.scroll(table);
        pane.setColumnHeaderView(table.getTableHeader());
        scroll = pane;

        // Suche und Filter in einer Zeile über der Tabelle; das Suchfeld trägt sein ✕ im Feld.
        JPanel filters = new JPanel(new BorderLayout(8, 0));
        filters.setOpaque(false);
        filters.add(InputFrame.search(search), BorderLayout.CENTER);
        filters.add(Comic.row(source, kinds, period), BorderLayout.EAST);
        root.add(filters, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(empty, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setOpaque(false);
        south.add(count, BorderLayout.WEST);
        south.add(Comic.row(more), BorderLayout.EAST);
        south.add(feedback, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        more.setVisible(false);
        empty.setVisible(false);

        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refresh();
            }
        });
        source.addActionListener(e -> filterChanged());
        kinds.addActionListener(e -> filterChanged());
        period.addActionListener(e -> filterChanged());
        more.addActionListener(e -> load(shown.size()));
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
    }

    JComponent component() {
        return root;
    }

    private void filterChanged() {
        if (!filling) {
            refresh();
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
            if (root.isShowing()) {
                loadNew();
            }
        });
    }

    /**
     * Holt auf Meldung des Core nur die Zeilen, die seit der obersten gezeigten dazukamen, und setzt sie oben ein.
     * Tabelle, Auswahl und Rollposition bleiben stehen: Wer nicht am Anfang steht, sieht denselben Ausschnitt weiter
     * (Ansichten springen nie von selbst). Ohne gezeigte Zeile liest es die erste Seite.
     */
    void loadNew() {
        Access current = access.get();
        if (shown.isEmpty() || current == null || current != subscribed) {
            load(0);
            return;
        }
        int ticket = ++request;
        Instant newest = shown.get(0).at();
        ChangeFilter base = filter(0, ChangeFilter.MAX_LIMIT);
        Instant from = base.from() == null || base.from().isBefore(newest) ? newest : base.from();
        ChangeFilter filter = new ChangeFilter(base.sourceId(), base.text(), base.kinds(), base.triggers(),
                base.spaceKey(), from, null, 0, ChangeFilter.MAX_LIMIT);
        Background.run(() -> current.entries(filter), entries -> {
            if (ticket == request) {
                if (entries.total() > entries.entries().size()) {
                    // Mehr neue Zeilen als ein Abruf fasst (große Erstbefüllung): Einsetzen ließe darunter eine
                    // Lücke, deshalb die erste Seite neu lesen.
                    load(0);
                } else {
                    prepend(entries.entries());
                }
            }
        }, error -> {
            if (ticket == request) {
                feedback.problem(error);
            }
        });
    }

    private void prepend(List<ChangeEntry> entries) {
        java.util.Set<ChangeEntry> known = new java.util.HashSet<>(shown);
        List<ChangeEntry> fresh = entries.stream().filter(entry -> !known.contains(entry)).toList();
        if (fresh.isEmpty()) {
            return;
        }
        javax.swing.JViewport viewport = table.getParent() instanceof javax.swing.JViewport port ? port : null;
        java.awt.Point position = viewport == null ? null : viewport.getViewPosition();
        shown.addAll(0, fresh);
        // insertRow verschiebt die Auswahl mit; die gewählte Zeile bleibt dieselbe.
        for (int i = 0; i < fresh.size(); i++) {
            model.insertRow(i, row(fresh.get(i)));
        }
        total += fresh.size();
        if (position != null && position.y > 0) {
            // Nicht am Anfang: denselben Ausschnitt halten, die neuen Zeilen liegen darüber.
            table.setSize(table.getWidth(), table.getPreferredSize().height);
            viewport.setViewPosition(new java.awt.Point(position.x, position.y + fresh.size() * table.getRowHeight()));
        }
        counts();
    }

    private Object[] row(ChangeEntry entry) {
        return new Object[] {Texts.time(entry.at()), titles.getOrDefault(entry.sourceId(), entry.sourceId()),
                entry.spaceKey(), page(entry), kind(entry.kind()), trigger(entry.trigger()), ""};
    }

    private void counts() {
        boolean any = !shown.isEmpty();
        count.setText(!any ? " " : shown.size() >= total ? Texts.count(shown.size(), "Zeile", "Zeilen")
                : shown.size() + " von " + Texts.count(total, "Zeile", "Zeilen"));
        more.setVisible(shown.size() < total);
    }

    /** Liest die erste Seite neu, etwa nach einer Änderung von Suche oder Filter. Erst anmelden, dann lesen. */
    void refresh() {
        load(0);
    }

    private void load(int offset) {
        load(offset, PAGE);
    }

    private void load(int offset, int limit) {
        Access current = access.get();
        int ticket = ++request;
        if (current == null) {
            unsubscribe();
            render(new ChangeEntries(List.of(), 0, 0, limit), false);
            return;
        }
        if (current != subscribed) {
            unsubscribe();
            subscribed = current;
            subscription = current.subscribe(this::scheduleRefresh);
            sources(current.sources());
        }
        ChangeFilter filter = filter(offset, limit);
        Background.run(() -> current.entries(filter), entries -> {
            if (ticket == request) {
                feedback.clear();
                render(entries, offset > 0);
            }
        }, error -> {
            if (ticket == request) {
                feedback.problem(error);
            }
        });
    }

    /** Der Filter aus Suche und Auswahllisten. */
    ChangeFilter filter(int offset, int limit) {
        SourceChoice chosen = (SourceChoice) source.getSelectedItem();
        KindChoice kind = (KindChoice) kinds.getSelectedItem();
        Period span = (Period) period.getSelectedItem();
        return new ChangeFilter(chosen == null ? null : chosen.id(), search.getText(),
                kind == null || kind.kind() == null ? Set.of() : Set.of(kind.kind()), Set.of(), null,
                span == null || span.back == null ? null : Instant.now().minus(span.back), null, offset, limit);
    }

    private void sources(Map<String, String> sources) {
        titles = sources;
        filling = true;
        try {
            SourceChoice chosen = (SourceChoice) source.getSelectedItem();
            source.removeAllItems();
            source.addItem(new SourceChoice(null, ALL_SOURCES));
            sources.forEach((id, title) -> source.addItem(new SourceChoice(id, title)));
            if (chosen != null && chosen.id() != null && sources.containsKey(chosen.id())) {
                source.setSelectedItem(new SourceChoice(chosen.id(), sources.get(chosen.id())));
            }
        } finally {
            filling = false;
        }
    }

    private void render(ChangeEntries entries, boolean append) {
        if (!append) {
            shown.clear();
            model.setRowCount(0);
        }
        // Kamen zwischen zwei Abrufen Zeilen dazu, liefert das Nachladen schon gezeigte noch einmal: nicht doppeln.
        java.util.Set<ChangeEntry> known = new java.util.HashSet<>(shown);
        for (ChangeEntry entry : entries.entries()) {
            if (known.add(entry)) {
                shown.add(entry);
                model.addRow(row(entry));
            }
        }
        total = entries.total();
        boolean any = !shown.isEmpty();
        boolean filtered = !search.getText().isBlank() || source.getSelectedIndex() > 0 || kinds.getSelectedIndex() > 0
                || period.getSelectedIndex() > 0;
        empty.setText(filtered ? NO_MATCH : EMPTY);
        empty.setVisible(!any);
        scroll.setVisible(any);
        counts();
        root.revalidate();
        root.repaint();
    }

    /** Öffnet die Seite der Zeile im Hauptfenster und schließt den Dialog; entfernte Seiten bleiben ohne Wirkung. */
    void openPage(int row) {
        if (row < 0 || row >= shown.size() || !openable(shown.get(row))) {
            return;
        }
        ChangeEntry entry = shown.get(row);
        Window window = SwingUtilities.getWindowAncestor(root);
        if (window != null) {
            window.dispose();
        }
        opener.accept(entry.sourceId(), entry.pageId());
    }

    /** Entfernte und gesperrte Seiten gibt es im Cache nicht mehr. */
    static boolean openable(ChangeEntry entry) {
        return entry.kind() != ChangeKind.REMOVED && entry.kind() != ChangeKind.WITHHELD;
    }

    /** Der Titel der Seite; beim Umbenennen „Alt → Neu“, ohne Titel die Seiten-ID. */
    static String page(ChangeEntry entry) {
        String before = entry.oldTitle();
        String after = entry.newTitle();
        if (entry.kind() == ChangeKind.RENAMED && before != null && after != null && !before.equals(after)) {
            return before + " → " + after;
        }
        String title = after != null && !after.isBlank() ? after : before;
        return title == null || title.isBlank() ? "Seite " + entry.pageId() : title;
    }

    static String kind(ChangeKind kind) {
        return switch (kind) {
            case NEW -> "Neu";
            case CONTENT_CHANGED -> "Inhalt geändert";
            case RENAMED -> "Umbenannt";
            case MOVED -> "Verschoben";
            case LABELS_CHANGED -> "Labels geändert";
            case REMOVED -> "Entfernt";
            case WITHHELD -> "Gesperrt";
            case MOVED_OUT -> "In anderen Space verschoben";
            case LOADED -> "Inhalt geladen";
            case RELOADED -> "Inhalt nachgeladen";
        };
    }

    static String trigger(ChangeTrigger trigger) {
        return switch (trigger) {
            case MANUAL -> "Synchronisierung von Hand";
            case BACKGROUND -> "Hintergrund";
            case STARTUP -> "Start der App";
            case SCHEDULE -> "Zeitplan";
            case SEARCH -> "Suche";
            case READ -> "Lesen";
            case EVENT -> "Webhook";
            case RETRY -> "Wiederholung";
            case EXPORT -> "Export";
            case UNKNOWN -> "Unbekannt";
        };
    }

    // ------------------------------------------------------------------ für Tests

    int rowCount() {
        return model.getRowCount();
    }

    String cell(int row, int column) {
        return String.valueOf(model.getValueAt(row, column));
    }

    boolean emptyShown() {
        return empty.isVisible();
    }

    String emptyText() {
        return empty.getText();
    }
}
