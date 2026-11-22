package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.files.FileBrowser;
import com.aresstack.spaceharvester.deck.files.FileEntry;
import com.aresstack.spaceharvester.deck.files.FileSource;
import com.aresstack.spaceharvester.deck.files.SortMode;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Arbeitsbereich „Dateien“ des Command Deck (#1141/#1142), Funktionen aus dem lokalen Dateibrowser von MainframeMate
 * ({@code LocalConnectionTabImpl}): Ordner öffnen, Zurück/Vorwärts/Nach oben/Neu laden, Pfad eingeben, Orte
 * (Benutzerordner, freigegebene Ordner, Laufwerke), Regex-Filter, Sortierung, Ordner und Dateien anlegen, löschen und
 * Textdateien im gemeinsamen Editor öffnen.
 *
 * <p>Oben die Reiter als Pillen („Durchsuchen“ und je ein offenes Dokument), rechts daneben Filter und Sortierung;
 * darunter die Pfadzeile, in der Mitte die Liste, unten Status und die Aktionen des gewählten Reiters. Zustand und
 * Logik liegen im Anwendungsfall {@link FileBrowser} bzw. {@link EditorSessions}; die Oberfläche hört nur zu (kein
 * Abfragen). Gelesen wird erst, wenn der Bereich zum ersten Mal sichtbar ist; geschrieben nur auf ausdrücklichen Wunsch.
 */
public final class FilesWorkspace extends JPanel {

    static final String LOCATION_KEY = "deck.files.location";
    static final String SORT_KEY = "deck.files.sort";
    private static final String BROWSER = "browser";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);

    private final FileBrowser browser;
    private final EditorSessions editors;
    private final DocumentStore store;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final ComposerToggleButton browsePill;
    private final Map<EditorSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<EditorSession, DocumentEditor> editorOf = new LinkedHashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel browserTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    private final IconButton back = new IconButton(ComposerIcons.chevronLeft(), "Zurück (Alt+Links)");
    private final IconButton forward = new IconButton(ComposerIcons.chevronRight(), "Vorwärts (Alt+Rechts)");
    private final IconButton up = new IconButton(ComposerIcons.chevronUp(), "Übergeordneter Ordner (Rücktaste)");
    private final IconButton refresh = new IconButton(Glyph.REFRESH, "Neu laden (F5)");
    private final JTextField path = Comic.field(30);
    private final JTextField filter = Comic.field(14);
    private final InputFrame filterFrame;
    private final ComposerButton sortButton;
    private final JPanel nameRow = new JPanel(new BorderLayout(8, 0));
    private final JLabel nameLabel = Comic.caption("");
    private final JTextField name = Comic.field(24);
    private final EntryModel model = new EntryModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel status = Comic.muted(" ");
    private final JPanel browserActions;
    private final JPanel documentActions;
    private final ComposerButton open;
    private final ComposerButton delete;
    private final ComposerButton save;
    private final ComposerButton reload;
    private boolean creatingFolder;
    private boolean started;
    private EditorSession selected;

    public FilesWorkspace(FileBrowser browser, EditorSessions editors, DocumentStore store) {
        super(new BorderLayout());
        this.browser = browser;
        this.editors = editors;
        this.store = store;
        setBackground(Comic.SURFACE);

        // Kopfzeile: links die Reiter, rechts Filter und Sortierung der Liste (wie die Seitenansicht).
        pills.setOpaque(false);
        browsePill = new ComposerToggleButton(FilesIcons.folder(), "Durchsuchen", browser.source().name());
        browsePill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        browsePill.addActionListener(e -> select(null));
        pillGroup.add(browsePill);
        pills.add(browsePill);
        filter.getAccessibleContext().setAccessibleName("Filter");
        filter.setToolTipText("<html>Regulärer Ausdruck auf den Namen, ohne Groß/klein<br>Beispiel: \\.jcl$</html>");
        filterFrame = InputFrame.search(filter);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                browser.filter(filter.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                browser.filter(filter.getText());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        sortButton = Comic.button(ComposerIcons.chevronDown(), browser.sort().label(), "Sortierung");
        sortButton.addActionListener(e -> showSortMenu());
        browserTools.setOpaque(false);
        browserTools.add(filterFrame);
        browserTools.add(sortButton);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        head.add(pills, BorderLayout.CENTER);
        head.add(browserTools, BorderLayout.EAST);

        // Durchsuchen: Pfadzeile, Namenszeile (nur beim Anlegen), Liste.
        center.setOpaque(false);
        center.add(browserCard(), BROWSER);

        // Fußzeile: Status und die Aktionen des gewählten Reiters.
        open = Comic.button("Öffnen", "Ordner öffnen bzw. Datei im Editor öffnen (Enter)");
        open.addActionListener(e -> openSelected());
        ComposerButton newFolder = Comic.button(FilesIcons.newFolder(), "Neuer Ordner", "Ordner anlegen");
        newFolder.addActionListener(e -> askName(true));
        ComposerButton newFile = Comic.button(FilesIcons.newFile(), "Neue Datei", "Leere Datei anlegen");
        newFile.addActionListener(e -> askName(false));
        delete = Comic.button(Glyph.TRASH, "Löschen", "Gewählte Einträge löschen (Entf)");
        delete.addActionListener(e -> deleteSelected());
        browserActions = Comic.row(open, newFolder, newFile, delete);
        save = Comic.primary("Speichern", "Änderungen speichern (Strg+S)");
        save.setIcon(FilesIcons.save());
        save.addActionListener(e -> {
            if (selected != null) {
                selected.save();
            }
        });
        reload = Comic.button(Glyph.REFRESH, "Neu laden", "Datei neu laden; ungespeicherte Änderungen gehen verloren");
        reload.addActionListener(e -> reloadSelected());
        ComposerButton close = Comic.button("Schließen", "Dokument schließen");
        close.addActionListener(e -> {
            if (selected != null) {
                closeDocument(selected);
            }
        });
        documentActions = Comic.row(save, reload, close);
        JPanel actions = new JPanel(new CardLayout());
        actions.setOpaque(false);
        actions.add(browserActions, BROWSER);
        actions.add(documentActions, "document");
        JPanel foot = new JPanel(new BorderLayout(12, 0));
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        foot.add(status, BorderLayout.CENTER);
        foot.add(actions, BorderLayout.EAST);

        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);

        String savedSort = AppState.shared().get(SORT_KEY);
        if (savedSort != null) {
            try {
                browser.sort(SortMode.valueOf(savedSort));
            } catch (IllegalArgumentException ignored) {
                // Alter Wert: Standard bleibt.
            }
        }
        browser.observe(changed -> SwingUtilities.invokeLater(this::render));
        editors.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        editors.onClosed(session -> SwingUtilities.invokeLater(() -> removed(session)));
        editors.all().forEach(this::added);
        // Erst lesen, wenn man den Bereich sieht: das Öffnen der Perspektive fasst keine Dateien an.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                start();
            }
        });
        select(null);
    }

    /** Öffnet beim ersten Mal den zuletzt benutzten Ordner bzw. den Benutzerordner. */
    void start() {
        if (started) {
            return;
        }
        started = true;
        String last = AppState.shared().get(LOCATION_KEY);
        browser.open(last == null || last.isBlank() ? browser.source().home() : last);
    }

    private JComponent browserCard() {
        // Pfadzeile: Navigation, dann das Pfadfeld mit „Orte“ im Feld.
        back.addActionListener(e -> browser.back());
        forward.addActionListener(e -> browser.forward());
        up.addActionListener(e -> browser.up());
        refresh.addActionListener(e -> browser.refresh());
        path.getAccessibleContext().setAccessibleName("Pfad");
        path.setToolTipText("Pfad eingeben und mit Enter öffnen");
        path.addActionListener(e -> {
            browser.open(path.getText());
            table.requestFocusInWindow();
        });
        InputFrame pathFrame = new InputFrame(path);
        JButton places = InputFrame.flatButton(null, "Orte: Benutzerordner, freigegebene Ordner, Laufwerke");
        places.setIcon(FilesIcons.places());
        places.addActionListener(e -> showPlaces(places));
        pathFrame.addButton(places);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navigation.setOpaque(false);
        navigation.add(back);
        navigation.add(forward);
        navigation.add(up);
        navigation.add(refresh);
        JPanel pathRow = new JPanel(new BorderLayout(8, 0));
        pathRow.setOpaque(false);
        pathRow.add(navigation, BorderLayout.WEST);
        pathRow.add(pathFrame, BorderLayout.CENTER);

        // Namenszeile zum Anlegen: Feld mit „Anlegen“ im Feld, Esc bricht ab.
        name.getAccessibleContext().setAccessibleName("Name");
        InputFrame nameFrame = new InputFrame(name);
        JButton create = nameFrame.addButton(InputFrame.flatButton("Anlegen", "Mit diesem Namen anlegen (Enter)"));
        create.addActionListener(e -> createNamed());
        name.addActionListener(e -> createNamed());
        name.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "deck-cancel");
        name.getActionMap().put("deck-cancel", action(this::hideNameRow));
        nameRow.setOpaque(false);
        nameRow.add(nameLabel, BorderLayout.WEST);
        nameRow.add(nameFrame, BorderLayout.CENTER);
        nameRow.setVisible(false);

        JPanel rows = new JPanel();
        rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        pathRow.setAlignmentX(LEFT_ALIGNMENT);
        nameRow.setAlignmentX(LEFT_ALIGNMENT);
        rows.add(pathRow);
        rows.add(javax.swing.Box.createVerticalStrut(6));
        rows.add(nameRow);

        // Liste: Name mit Symbol, Größe, Geändert; Mehrfachauswahl zum Löschen.
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean chosen, boolean focus,
                                                           int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(t, value, chosen, focus, row, column);
                label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 18));
                return label;
            }
        };
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        // Größe und Datum fest schmal, der Name bekommt den Rest.
        fix(table, 1, 96);
        fix(table, 2, 150);
        table.getAccessibleContext().setAccessibleName("Dateien");
        table.getSelectionModel().addListSelectionListener(e -> showActions());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
                    openSelected();
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }
        });
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "deck-open", this::openSelected);
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deck-delete", this::deleteSelected);
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deck-up", browser::up);
        JComponent scroll = Comic.scroll(table);
        BusyOverlay overlay = new BusyOverlay(scroll);
        overlay.setText("Lade …");

        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(false);
        card.add(rows, BorderLayout.NORTH);
        card.add(overlay, BorderLayout.CENTER);
        card.putClientProperty(BusyOverlay.class, overlay);
        bind(card, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "deck-refresh", browser::refresh);
        bind(card, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), "deck-back", browser::back);
        bind(card, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), "deck-forward",
                browser::forward);
        return card;
    }

    // ---- Anzeige ---------------------------------------------------------------------------------------------------

    private void render() {
        back.setEnabled(browser.canGoBack());
        forward.setEnabled(browser.canGoForward());
        up.setEnabled(browser.canGoUp());
        String location = browser.location().orElse("");
        if (!path.isFocusOwner() && !path.getText().equals(location)) {
            path.setText(location);
        }
        if (!location.isEmpty() && !browser.loading() && browser.failure().isEmpty()) {
            AppState.shared().put(LOCATION_KEY, location);
        }
        filter.setBackground(browser.filterValid() ? Color.WHITE : Comic.CHANGE_REMOVED);
        filterFrame.setBackground(filter.getBackground());
        Set<String> chosen = new HashSet<>();
        selectedEntries().forEach(entry -> chosen.add(entry.location()));
        model.show(browser.visible());
        for (int row = 0; row < model.getRowCount(); row++) {
            if (chosen.contains(model.entry(row).location())) {
                table.addRowSelectionInterval(row, row);
            }
        }
        sortButton.setText(browser.sort().label());
        busy().setBusy(browser.loading());
        showStatus();
        showActions();
    }

    private void showStatus() {
        if (selected != null) {
            showDocumentStatus();
            return;
        }
        Color color = Comic.MUTED;
        String text;
        if (browser.failure().isPresent()) {
            text = browser.location().orElse("") + ": " + browser.failure().get();
            color = Comic.RED;
        } else if (browser.message().isPresent()) {
            FileBrowser.Message message = browser.message().get();
            text = message.text();
            color = switch (message.tone()) {
                case ERROR -> Comic.RED;
                case SUCCESS -> Comic.STANDING_CURRENT;
                case INFO -> Comic.MUTED;
            };
        } else if (browser.loading()) {
            text = "Lade " + browser.location().orElse("") + " …";
        } else {
            int all = browser.entries().size();
            int shown = model.getRowCount();
            text = all == 1 ? "1 Eintrag" : all + " Einträge";
            if (shown != all) {
                text += " · " + shown + " passen zum Filter";
            }
            if (!browser.filterValid()) {
                text += " · Filter ist kein gültiger regulärer Ausdruck";
            }
        }
        status.setText(text);
        status.setForeground(color);
    }

    private void showDocumentStatus() {
        EditorSession session = selected;
        String text = session.location() + " · " + session.state().label();
        Color color = Comic.MUTED;
        if (session.state() == EditorSession.State.FAILED || session.state() == EditorSession.State.CONFLICT) {
            text += session.problem().map(problem -> ": " + problem).orElse("");
            color = Comic.RED;
        } else if (session.note().isPresent()) {
            text += " · " + session.note().get();
            color = session.note().get().startsWith("Nicht") ? Comic.RED : Comic.STANDING_CURRENT;
        } else if (session.dirty()) {
            text += " · ungespeicherte Änderungen";
        }
        String charset = session.document().map(document -> " · " + document.charset()
                + ("\r\n".equals(document.lineEnding()) ? " · CRLF" : "")).orElse("");
        status.setText(text + charset);
        status.setForeground(color);
    }

    private void showActions() {
        Component actions = browserActions.getParent();
        if (actions != null) {
            ((CardLayout) ((JPanel) actions).getLayout()).show((JPanel) actions,
                    selected == null ? BROWSER : "document");
        }
        int count = table.getSelectedRowCount();
        open.setEnabled(count == 1);
        delete.setEnabled(count > 0);
        if (selected != null) {
            EditorSession.State state = selected.state();
            save.setEnabled(selected.dirty() && (state == EditorSession.State.READY
                    || state == EditorSession.State.CONFLICT));
            reload.setEnabled(state != EditorSession.State.LOADING && state != EditorSession.State.SAVING);
        }
    }

    // ---- Reiter ----------------------------------------------------------------------------------------------------

    /** Zeigt die Liste ({@code null}) oder ein offenes Dokument. */
    void select(EditorSession session) {
        selected = session;
        ComposerToggleButton pill = session == null ? browsePill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        browserTools.setVisible(session == null);
        if (session == null) {
            cards.show(center, BROWSER);
            SwingUtilities.invokeLater(table::requestFocusInWindow);
        } else {
            cards.show(center, session.location());
            DocumentEditor editor = editorOf.get(session);
            if (editor != null) {
                SwingUtilities.invokeLater(editor::focusText);
            }
        }
        showStatus();
        showActions();
    }

    private void added(EditorSession session) {
        if (session.store() != store || pillOf.containsKey(session)) {
            return;
        }
        DocumentEditor editor = new DocumentEditor(session);
        editorOf.put(session, editor);
        center.add(editor, session.location());
        ComposerToggleButton pill = new ComposerToggleButton(FilesIcons.file(), session.name(), session.location());
        pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        pill.getAccessibleContext().setAccessibleName(session.name());
        pill.addActionListener(e -> select(session));
        pill.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isMiddleMouseButton(event)) {
                    closeDocument(session);
                }
            }
        });
        pillGroup.add(pill);
        pills.add(pill);
        pillOf.put(session, pill);
        session.observe(changed -> SwingUtilities.invokeLater(() -> documentChanged(changed)));
        pills.revalidate();
        pills.repaint();
        select(session);
    }

    private void documentChanged(EditorSession session) {
        ComposerToggleButton pill = pillOf.get(session);
        if (pill == null) {
            return;
        }
        pill.setIcon(session.state() == EditorSession.State.FAILED
                || session.state() == EditorSession.State.CONFLICT ? FilesIcons.dot(Comic.RED)
                : session.dirty() ? FilesIcons.unsaved() : FilesIcons.file());
        pill.setToolTipText(session.location() + (session.dirty() ? " – ungespeicherte Änderungen" : ""));
        if (session == selected) {
            showStatus();
            showActions();
        }
    }

    private void removed(EditorSession session) {
        ComposerToggleButton pill = pillOf.remove(session);
        DocumentEditor editor = editorOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
        if (editor != null) {
            center.remove(editor);
        }
        if (session == selected) {
            select(null);
        }
    }

    private void closeDocument(EditorSession session) {
        if (session.dirty() && !Dialogs.confirm(this, "Dokument schließen",
                "„" + session.name() + "“ hat ungespeicherte Änderungen. Wirklich schließen und verwerfen?")) {
            return;
        }
        editors.close(session);
    }

    private void reloadSelected() {
        EditorSession session = selected;
        if (session == null) {
            return;
        }
        if (session.dirty() && !Dialogs.confirm(this, "Neu laden",
                "„" + session.name() + "“ hat ungespeicherte Änderungen. Wirklich neu laden und verwerfen?")) {
            return;
        }
        session.load();
    }

    // ---- Aktionen der Liste ----------------------------------------------------------------------------------------

    private void openSelected() {
        List<FileEntry> chosen = selectedEntries();
        if (chosen.size() != 1) {
            return;
        }
        FileEntry entry = chosen.get(0);
        if (entry.directory()) {
            browser.open(entry.location());
        } else {
            EditorSession session = editors.open(store, entry.location(), entry.name());
            select(session);
        }
    }

    private void askName(boolean folder) {
        creatingFolder = folder;
        nameLabel.setText(folder ? "Neuer Ordner:" : "Neue Datei:");
        name.setText("");
        nameRow.setVisible(true);
        nameRow.getParent().revalidate();
        name.requestFocusInWindow();
    }

    private void createNamed() {
        String text = name.getText();
        if (creatingFolder) {
            browser.createFolder(text);
        } else {
            browser.createFile(text);
        }
        if (com.aresstack.spaceharvester.deck.files.FileNames.problem(text).isEmpty()) {
            hideNameRow();
        }
    }

    private void hideNameRow() {
        nameRow.setVisible(false);
        nameRow.getParent().revalidate();
        table.requestFocusInWindow();
    }

    private void deleteSelected() {
        List<FileEntry> doomed = selectedEntries();
        if (doomed.isEmpty()) {
            return;
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < Math.min(doomed.size(), 5); i++) {
            list.append("\n• ").append(doomed.get(i).name());
        }
        if (doomed.size() > 5) {
            list.append("\n… und ").append(doomed.size() - 5).append(" weitere");
        }
        String question = (doomed.size() == 1 ? "„" + doomed.get(0).name() + "“ wirklich löschen?"
                : doomed.size() + " Einträge wirklich löschen?" + list)
                + "\nOrdner werden nur gelöscht, wenn sie leer sind.";
        if (Dialogs.confirm(this, "Löschen", question)) {
            browser.delete(doomed);
        }
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(event.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        JPopupMenu menu = new JPopupMenu();
        List<FileEntry> chosen = selectedEntries();
        if (chosen.size() == 1) {
            menu.add(item("Öffnen", this::openSelected));
            FileEntry entry = chosen.get(0);
            menu.add(item("Pfad kopieren", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(entry.location()), null)));
            menu.addSeparator();
        }
        menu.add(item("Neuer Ordner …", () -> askName(true)));
        menu.add(item("Neue Datei …", () -> askName(false)));
        if (!chosen.isEmpty()) {
            menu.addSeparator();
            menu.add(item(chosen.size() == 1 ? "Löschen …" : chosen.size() + " Einträge löschen …",
                    this::deleteSelected));
        }
        menu.show(table, event.getX(), event.getY());
    }

    private void showPlaces(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (FileSource.Place place : browser.source().places()) {
            menu.add(item(place.label(), () -> browser.open(place.location())));
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void showSortMenu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        for (SortMode mode : SortMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(mode.label(), mode == browser.sort());
            item.setFont(Comic.regular(13f));
            item.addActionListener(e -> {
                browser.sort(mode);
                AppState.shared().put(SORT_KEY, mode.name());
            });
            group.add(item);
            menu.add(item);
        }
        menu.show(sortButton, 0, sortButton.getHeight());
    }

    private List<FileEntry> selectedEntries() {
        List<FileEntry> chosen = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            if (row >= 0 && row < model.getRowCount()) {
                chosen.add(model.entry(row));
            }
        }
        return chosen;
    }

    private BusyOverlay busy() {
        return (BusyOverlay) ((JComponent) center.getComponent(0)).getClientProperty(BusyOverlay.class);
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(Comic.regular(13f));
        item.addActionListener(e -> action.run());
        return item;
    }

    private static AbstractAction action(Runnable run) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        };
    }

    private static void bind(JComponent component, KeyStroke key, String name, Runnable run) {
        int condition = component instanceof JTable ? JComponent.WHEN_FOCUSED
                : JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
        component.getInputMap(condition).put(key, name);
        component.getActionMap().put(name, action(run));
    }

    private static void fix(JTable table, int column, int width) {
        var col = table.getColumnModel().getColumn(column);
        col.setMinWidth(width);
        col.setMaxWidth(width);
        col.setPreferredWidth(width);
    }

    static String size(FileEntry entry) {
        if (entry.directory()) {
            return "";
        }
        long bytes = entry.size();
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.GERMANY, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    FileBrowser browser() {
        return browser;
    }

    JTable table() {
        return table;
    }

    JTextField pathField() {
        return path;
    }

    JTextField filterField() {
        return filter;
    }

    JTextField nameField() {
        return name;
    }

    boolean nameRowShown() {
        return nameRow.isVisible();
    }

    String statusText() {
        return status.getText();
    }

    EditorSession selected() {
        return selected;
    }

    DocumentEditor editor(EditorSession session) {
        return editorOf.get(session);
    }

    List<String> pillTitles() {
        List<String> titles = new ArrayList<>();
        titles.add(browsePill.getText());
        pillOf.values().forEach(pill -> titles.add(pill.getText()));
        return titles;
    }

    void openRow(int row) {
        table.setRowSelectionInterval(row, row);
        openSelected();
    }

    void newFolder() {
        askName(true);
    }

    void newFile() {
        askName(false);
    }

    void confirmName() {
        createNamed();
    }

    // ---- Tabelle ---------------------------------------------------------------------------------------------------

    private static final class EntryModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Name", "Größe", "Geändert"};
        private List<FileEntry> rows = List.of();

        void show(List<FileEntry> entries) {
            if (!entries.equals(rows)) {
                rows = entries;
                fireTableDataChanged();
            }
        }

        FileEntry entry(int row) {
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
        public Object getValueAt(int row, int column) {
            FileEntry entry = rows.get(row);
            return switch (column) {
                case 0 -> entry;
                case 1 -> size(entry);
                default -> entry.modified() == null ? ""
                        : DATE.format(entry.modified().atZone(ZoneId.systemDefault()));
            };
        }
    }

    private static final class NameRenderer extends DefaultTableCellRenderer {

        NameRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            FileEntry entry = (FileEntry) value;
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, entry.name(), selected, focus, row,
                    column);
            label.setIcon(entry.directory() ? FilesIcons.folder() : FilesIcons.file());
            label.setIconTextGap(8);
            return label;
        }
    }
}
