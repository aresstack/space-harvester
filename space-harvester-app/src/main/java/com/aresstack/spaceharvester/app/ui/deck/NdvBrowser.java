package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvObjectType;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.NdvState;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

/**
 * Was eine NDV-Sitzung zeigt, wie der NDV-Reiter in MainframeMate ({@code NdvConnectionTab}): links die Bibliotheken
 * mit Filter, rechts die Objekte der gewählten Bibliothek (Name, Art, Größe, Geändert, Benutzer), Seite für Seite
 * sofort sichtbar, darunter der Quelltext des geöffneten Objekts. Oben rechts in der Kopfzeile ({@link #tools()})
 * Namensfilter, Art und Neu laden; unten ({@link #foot()}) Zustand und Aktionen der Sitzung.
 *
 * <p>Nur lesend: Auflisten und Öffnen schreiben nichts auf den Server. Bearbeiten und Speichern übernimmt der
 * gemeinsame Editor des Command Deck. Alle Gespräche mit dem Server laufen im Hintergrund; die Sitzung reiht sie.
 */
final class NdvBrowser extends JPanel {

    static final String LIBRARY_KEY = "deck.ndv.library";
    static final String ALL_TYPES = "Alle Arten";

    private final NdvSession session;
    private final NdvSessions sessions;
    private final Executor background;
    private final DefaultListModel<String> libraries = new DefaultListModel<>();
    private final List<String> allLibraries = new ArrayList<>();
    private final JList<String> libraryList = new JList<>(libraries);
    private final PlaceholderField libraryFilter = new PlaceholderField(10, "Bibliothek filtern");
    private final ObjectModel objects = new ObjectModel();
    private final JTable objectTable = Comic.table(new JTable(objects));
    private final TableRowSorter<ObjectModel> sorter = new TableRowSorter<>(objects);
    private final PlaceholderField objectFilter = new PlaceholderField(12, "Objekt filtern, * erlaubt");
    private final JComboBox<Object> typeFilter = Comic.combo();
    private final JTextArea source = Comic.code();
    private final JLabel sourceTitle = Comic.caption("Quelltext – Objekt doppelt anklicken");
    private final BusyOverlay libraryBusy;
    private final BusyOverlay objectBusy;
    private final JLabel status = Comic.muted(" ");
    private final ComposerButton open = Comic.button("Öffnen", "Quelltext des gewählten Objekts laden (Eingabe)");
    private final ComposerButton reconnect = Comic.button("Neu verbinden", "Verbindung neu aufbauen");
    private final ComposerButton disconnect = Comic.button("Trennen", "Verbindung dieser Sitzung trennen");
    private final ComposerButton close = Comic.button("Schließen", "Getrennte Sitzung aus der Liste nehmen");
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final JPanel foot = new JPanel(new BorderLayout());
    /** Zählt Auflistungen; eine überholte hört bei der nächsten Seite auf. */
    private final AtomicInteger listing = new AtomicInteger();
    private String library;
    private NdvObject shown;
    private boolean everConnected;
    private String note = "";
    private boolean noteIsError;

    NdvBrowser(NdvSession session, NdvSessions sessions, Executor background, Runnable closed) {
        super(new BorderLayout());
        this.session = Objects.requireNonNull(session, "session");
        this.sessions = sessions;
        this.background = background;
        setBackground(Comic.SURFACE);

        // Links: Bibliotheken mit Filter im Feld (✕ leert).
        libraryList.setFont(Comic.regular(13f));
        libraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        libraryList.setSelectionBackground(Comic.YELLOW);
        libraryList.setSelectionForeground(Comic.INK);
        libraryList.putClientProperty("html.disable", Boolean.TRUE);
        libraryList.getAccessibleContext().setAccessibleName("Bibliotheken");
        libraryList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && libraryList.getSelectedValue() != null
                    && !libraryList.getSelectedValue().equals(library)) {
                openLibrary(libraryList.getSelectedValue());
            }
        });
        Comic.style(libraryFilter);
        libraryFilter.getDocument().addDocumentListener(changes(this::filterLibraries));
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 6));
        left.add(InputFrame.search(libraryFilter), BorderLayout.NORTH);
        libraryBusy = new BusyOverlay(Comic.scroll(libraryList));
        left.add(libraryBusy, BorderLayout.CENTER);
        left.setMinimumSize(new Dimension(150, 0));

        // Rechts oben: Objekte; Spalten sortierbar, Doppelklick oder Eingabe öffnet.
        objectTable.setRowSorter(sorter);
        objectTable.getAccessibleContext().setAccessibleName("Objekte");
        objectTable.getColumnModel().getColumn(0).setPreferredWidth(170);
        objectTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        objectTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        objectTable.getColumnModel().getColumn(3).setPreferredWidth(130);
        objectTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        // Größe rechtsbündig mit Abstand zur nächsten Spalte; sortiert wird nach der Zahl.
        javax.swing.table.DefaultTableCellRenderer size = new javax.swing.table.DefaultTableCellRenderer();
        size.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        size.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 24));
        objectTable.getColumnModel().getColumn(2).setCellRenderer((table, value, selected, focus, row, column) -> {
            JLabel label = (JLabel) size.getTableCellRendererComponent(table, value, selected, false, row, column);
            label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 24));
            return label;
        });
        objectTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    openSelected();
                }
            }
        });
        objectTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ndv-open");
        objectTable.getActionMap().put("ndv-open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        objectTable.getSelectionModel().addListSelectionListener(event -> showState());
        objectBusy = new BusyOverlay(Comic.scroll(objectTable));
        objectBusy.onCancel(() -> {
            listing.incrementAndGet();
            objectBusy.setBusy(false);
            note(objects.getRowCount() + " Objekte in " + library + " (abgebrochen, unvollständig)", false);
        });

        // Rechts unten: Quelltext, nur lesen.
        source.getAccessibleContext().setAccessibleName("Quelltext");
        Comic.noAutoScroll(source);
        JPanel sourcePanel = new JPanel(new BorderLayout(0, 4));
        sourcePanel.setOpaque(false);
        sourcePanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        sourcePanel.add(sourceTitle, BorderLayout.NORTH);
        sourcePanel.add(Comic.scroll(source), BorderLayout.CENTER);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, objectBusy, sourcePanel);
        right.setResizeWeight(0.55);
        right.setOpaque(false);
        right.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 12));
        ComicDivider.install(right);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.2);
        split.setDividerLocation(220);
        split.setOpaque(false);
        split.setBorder(BorderFactory.createEmptyBorder());
        ComicDivider.install(split);
        add(split, BorderLayout.CENTER);

        // Kopfzeile rechts: Namensfilter (✕ im Feld), Art, Neu laden.
        Comic.style(objectFilter);
        objectFilter.getDocument().addDocumentListener(changes(this::filterObjects));
        typeFilter.setModel(new DefaultComboBoxModel<>(new Object[] {ALL_TYPES}));
        typeFilter.setToolTipText("Nur Objekte dieser Art zeigen");
        typeFilter.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value instanceof NdvObjectType type ? type.label() : String.valueOf(value));
                return this;
            }
        });
        typeFilter.addActionListener(e -> filterObjects());
        IconButton refresh = new IconButton(Glyph.REFRESH, "Bibliotheken und Objekte neu vom Server laden");
        refresh.addActionListener(e -> refresh());
        tools.setOpaque(false);
        InputFrame filterFrame = InputFrame.search(objectFilter);
        filterFrame.setPreferredSize(new Dimension(220, filterFrame.getPreferredSize().height));
        tools.add(filterFrame);
        tools.add(typeFilter);
        tools.add(refresh);

        // Fuß: Zustand links, Aktionen rechts.
        open.addActionListener(e -> openSelected());
        reconnect.addActionListener(e -> sessions.reconnect(session));
        disconnect.addActionListener(e -> session.disconnect());
        close.addActionListener(e -> closed.run());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        foot.add(status, BorderLayout.CENTER);
        foot.add(Comic.row(open, reconnect, disconnect, close), BorderLayout.EAST);

        session.observe(changed -> SwingUtilities.invokeLater(this::stateChanged));
        stateChanged();
    }

    JComponent tools() {
        return tools;
    }

    JComponent foot() {
        return foot;
    }

    NdvSession session() {
        return session;
    }

    private void stateChanged() {
        if (session.state() == NdvState.CONNECTED && !everConnected) {
            everConnected = true;
            loadLibraries();
        }
        showState();
    }

    /** Bibliotheken neu und, wenn eine gewählt ist, ihre Objekte. */
    void refresh() {
        String current = library;
        library = null;
        loadLibraries();
        if (current != null) {
            openLibrary(current);
        }
    }

    void loadLibraries() {
        libraryBusy.setBusy(true);
        libraryBusy.setText("Lade Bibliotheken …");
        note("Lade Bibliotheken …", false);
        work(() -> session.libraries("*"), list -> {
            libraryBusy.setBusy(false);
            allLibraries.clear();
            allLibraries.addAll(list);
            filterLibraries();
            note(list.size() + " Bibliotheken", false);
            String wanted = library != null ? library : AppState.shared().get(LIBRARY_KEY);
            if (wanted != null && allLibraries.contains(wanted)) {
                libraryList.setSelectedValue(wanted, true);
            }
        }, error -> {
            libraryBusy.setBusy(false);
            note("Bibliotheken nicht lesbar: " + ConnectForm.describe(error), true);
        });
    }

    /** Listet die Objekte einer Bibliothek; jede Seite erscheint sofort, Abbrechen lässt sie stehen. */
    void openLibrary(String name) {
        library = name;
        AppState.shared().put(LIBRARY_KEY, name);
        if (!name.equals(libraryList.getSelectedValue())) {
            libraryList.setSelectedValue(name, true);
        }
        int generation = listing.incrementAndGet();
        objects.clear();
        typeFilter.setModel(new DefaultComboBoxModel<>(new Object[] {ALL_TYPES}));
        objectBusy.setText("Lade Objekte aus " + name + " …");
        objectBusy.setBusy(true);
        note("Lade Objekte aus " + name + " …", false);
        work(() -> session.objects(name, "*", (page, total) -> {
            if (listing.get() != generation) {
                return false;
            }
            List<NdvObject> copy = List.copyOf(page);
            SwingUtilities.invokeLater(() -> {
                if (listing.get() == generation) {
                    if (total == copy.size()) {
                        // Erste Seite, auch nach einem Wiederholen ab vorn.
                        objects.clear();
                    }
                    objects.add(copy);
                    updateTypes();
                    objectBusy.setText(objects.getRowCount() + " Objekte aus " + name + " …");
                    note("Lade Objekte aus " + name + " … " + objects.getRowCount(), false);
                }
            });
            return true;
        }), count -> {
            if (listing.get() != generation) {
                return;
            }
            objectBusy.setBusy(false);
            note(count == 0 ? "Keine Objekte in " + name : objects.getRowCount() + " Objekte in " + name, false);
        }, error -> {
            if (listing.get() != generation) {
                return;
            }
            objectBusy.setBusy(false);
            note((objects.getRowCount() > 0 ? objects.getRowCount() + " Objekte (unvollständig) – " : "")
                    + "Objekte nicht lesbar: " + ConnectForm.describe(error), true);
        });
    }

    /** Lädt den Quelltext des gewählten Objekts in die Ansicht darunter; schreibt nichts. */
    void openSelected() {
        int row = objectTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        NdvObject object = objects.at(objectTable.convertRowIndexToModel(row));
        if (!object.source()) {
            note("„" + object.displayName() + "“ hat keinen Quelltext (Art: " + object.type().name() + ")", true);
            return;
        }
        note("Lade Quelltext: " + object.path() + " …", false);
        work(() -> session.read(object), text -> {
            shown = object;
            source.setText(text);
            source.setCaretPosition(0);
            sourceTitle.setText(object.path() + " · " + text.lines().count() + " Zeilen");
            note(text.isEmpty() ? "Leerer Quelltext: " + object.path() : "Geöffnet: " + object.path(), false);
        }, error -> note("Quelltext nicht lesbar: " + ConnectForm.describe(error), true));
    }

    private void filterLibraries() {
        Pattern pattern = glob(libraryFilter.getText());
        String selected = libraryList.getSelectedValue();
        libraries.clear();
        allLibraries.stream().filter(name -> pattern.matcher(name).find()).forEach(libraries::addElement);
        if (selected != null && libraries.contains(selected)) {
            libraryList.setSelectedValue(selected, false);
        }
    }

    private void filterObjects() {
        Pattern pattern = glob(objectFilter.getText());
        Object type = typeFilter.getSelectedItem();
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends ObjectModel, ? extends Integer> entry) {
                NdvObject object = objects.at(entry.getIdentifier());
                return pattern.matcher(object.displayName()).find()
                        && (!(type instanceof NdvObjectType wanted) || wanted.equals(object.type()));
            }
        });
        showState();
    }

    private void updateTypes() {
        TreeSet<NdvObjectType> types = new TreeSet<>(Comparator.comparing(NdvObjectType::name));
        for (int i = 0; i < objects.getRowCount(); i++) {
            types.add(objects.at(i).type());
        }
        if (types.size() + 1 == typeFilter.getItemCount()) {
            return;
        }
        Object selected = typeFilter.getSelectedItem();
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        model.addElement(ALL_TYPES);
        types.forEach(model::addElement);
        model.setSelectedItem(selected != null && (selected == ALL_TYPES || types.contains(selected)) ? selected
                : ALL_TYPES);
        typeFilter.setModel(model);
    }

    /** Teil eines Namens; {@code *} steht für beliebig viel, ohne Rücksicht auf Groß- und Kleinschreibung. */
    static Pattern glob(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return Pattern.compile("");
        }
        return Pattern.compile(Pattern.quote(trimmed).replace("*", "\\E.*\\Q"), Pattern.CASE_INSENSITIVE);
    }

    private void note(String text, boolean error) {
        note = text;
        noteIsError = error;
        showState();
    }

    void showState() {
        NdvState state = session.state();
        boolean connected = state == NdvState.CONNECTED;
        open.setEnabled(connected && objectTable.getSelectedRow() >= 0);
        reconnect.setEnabled(state == NdvState.FAILED || state == NdvState.DISCONNECTED);
        disconnect.setEnabled(state == NdvState.CONNECTED || state == NdvState.CONNECTING);
        close.setEnabled(state == NdvState.FAILED || state == NdvState.DISCONNECTED);
        String head = session.profile().label() + (session.user().isEmpty() ? "" : " · " + session.user()) + " · "
                + state.label();
        String text = switch (state) {
            case FAILED -> head + session.failure().map(reason -> " – " + reason).orElse("");
            case CONNECTED -> note.isEmpty() ? head : head + " · " + note;
            default -> head;
        };
        status.setText(text);
        status.setToolTipText(text);
        status.setForeground(state == NdvState.FAILED || connected && noteIsError ? Comic.RED : Comic.MUTED);
    }

    private <T> void work(java.util.concurrent.Callable<T> task, java.util.function.Consumer<T> done,
                          java.util.function.Consumer<Throwable> failed) {
        background.execute(() -> {
            try {
                T result = task.call();
                SwingUtilities.invokeLater(() -> done.accept(result));
            } catch (Throwable e) {
                SwingUtilities.invokeLater(() -> failed.accept(e));
            }
        });
    }

    private static javax.swing.event.DocumentListener changes(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }
        };
    }

    // Für Tests
    String statusText() {
        return status.getText();
    }

    List<String> visibleLibraries() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < libraries.size(); i++) {
            list.add(libraries.get(i));
        }
        return list;
    }

    List<String> visibleObjects() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < objectTable.getRowCount(); i++) {
            list.add(objects.at(objectTable.convertRowIndexToModel(i)).displayName());
        }
        return list;
    }

    void selectObject(String name) {
        for (int i = 0; i < objectTable.getRowCount(); i++) {
            if (objects.at(objectTable.convertRowIndexToModel(i)).displayName().equals(name)) {
                objectTable.setRowSelectionInterval(i, i);
                return;
            }
        }
        throw new IllegalArgumentException(name);
    }

    String sourceText() {
        return source.getText();
    }

    NdvObject shown() {
        return shown;
    }

    String library() {
        return library;
    }

    PlaceholderField objectFilter() {
        return objectFilter;
    }

    PlaceholderField libraryFilter() {
        return libraryFilter;
    }

    JComboBox<Object> typeFilter() {
        return typeFilter;
    }

    boolean objectsLoading() {
        return objectBusy.isBusy();
    }

    /** Die Objekte als Tabelle: Name, Art, Größe, Geändert, Benutzer. */
    static final class ObjectModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Name", "Art", "Größe", "Geändert", "Benutzer"};
        private final List<NdvObject> rows = new ArrayList<>();

        void clear() {
            int size = rows.size();
            rows.clear();
            if (size > 0) {
                fireTableRowsDeleted(0, size - 1);
            }
        }

        void add(List<NdvObject> page) {
            if (page.isEmpty()) {
                return;
            }
            int first = rows.size();
            rows.addAll(page);
            fireTableRowsInserted(first, rows.size() - 1);
        }

        NdvObject at(int row) {
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
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            NdvObject object = rows.get(row);
            return switch (column) {
                case 0 -> object.displayName();
                case 1 -> object.type().label();
                case 2 -> object.sourceSize();
                case 3 -> object.changed();
                default -> object.user().toUpperCase(Locale.ROOT);
            };
        }
    }
}
