package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.TextSearch;
import com.aresstack.spaceharvester.deck.editor.VersionHistory;
import com.aresstack.spaceharvester.mainframe.outline.Outline;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Der gemeinsame Editor des Command Deck (#1141/#1142) für ein Dokument, mit den Funktionen des Dateireiters von
 * MainframeMate ({@code FileTabImpl}/{@code SplitPreviewTab}):
 * <ul>
 *   <li>RSyntaxTextArea mit Zeilennummern, Faltung und Hervorhebung; JCL, COBOL und Natural aus dem Modul
 *       {@code mainframe-languages}, Natural-Blöcke (DEFINE DATA, Unterprogramme, ON ERROR) farbig hinterlegt</li>
 *   <li>Sprachwahl unten rechts (in MainframeMate Teil der Satzarten-Auswahl): steuert Hervorhebung und den Parser
 *       der Gliederung; „Automatisch“ erkennt an Endung und Inhalt</li>
 *   <li>Gliederung rechts (Outline), Doppelklick springt zur Zeile</li>
 *   <li>Suchleiste unten: regulärer Ausdruck ohne Groß/klein, alle Treffer markiert; „Zeilen filtern“ klappt alle
 *       Zeilen ohne Treffer zu, sonst schrittweise mit Enter/Shift+Enter bzw. ▲ ▼</li>
 *   <li>Vergleich unten mit den lokal aufbewahrten Versionen (beim Öffnen, vor jedem Speichern) und farbigem Diff</li>
 *   <li>Rückgängig, Wiederholen, Zeilenumbruch, Inhalt kopieren, Speichern unter (wo die Ablage es kann); Strg+S
 *       speichert, Strg+F sucht</li>
 * </ul>
 * Hat jemand die Datei seit dem Öffnen geändert, steht oben eine Meldung mit „Trotzdem speichern“. Jeder Arbeitsbereich
 * (Dateien, FTP, NDV) öffnet ihn mit einer eigenen Ablage und kann über {@link #addAction} eigene Knöpfe einhängen
 * (z. B. JCL ausführen, Diagramm-Ansicht). Laden und Speichern laufen im Hintergrund; der Zustand kommt per
 * Beobachter aus der {@link EditorSession}.
 */
public final class DocumentEditor extends JPanel {

    static final String WRAP_KEY = "deck.editor.wrap";
    static final String OUTLINE_KEY = "deck.editor.outline";
    static final String FILTER_KEY = "deck.editor.find-filter";
    private static final String TEXT_CARD = "text";
    private static final String CUSTOM_CARD = "custom";
    private static final DateTimeFormatter VERSION_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss",
            Locale.GERMANY);
    private static final Color FIND_MARK = new Color(0xFFE066);
    private static final Color FIND_CURRENT = new Color(0xFFA94D);

    private final EditorSession session;
    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final RTextScrollPane scroll;
    private final MessagePlate plate = new MessagePlate();
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    private final IconButton outlineToggle = new IconButton(EditorIcons.outline(), "Gliederung ein/aus");
    private final IconButton compareToggle = new IconButton(EditorIcons.compare(),
            "Mit einer früheren Version vergleichen");
    private final IconButton wrapToggle = new IconButton(EditorIcons.wrap(), "Zeilenumbruch ein/aus");
    private final IconButton undo = new IconButton(EditorIcons.undo(), "Rückgängig (Strg+Z)");
    private final IconButton redo = new IconButton(EditorIcons.redo(), "Wiederholen (Strg+Y)");
    private final IconButton copy = new IconButton(EditorIcons.copy(), "Ganzen Inhalt kopieren");
    private final IconButton saveAs = new IconButton(EditorIcons.saveAs(), "Speichern unter …");
    private final OutlinePanel outlinePanel;
    private final JSplitPane textAndCompare;
    private final JSplitPane textAndOutline;
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JPanel comparePanel = new JPanel(new BorderLayout());
    private final RSyntaxTextArea compareArea = new RSyntaxTextArea();
    private final JComboBox<VersionChoice> versions = Comic.combo();
    private final JLabel compareInfo = Comic.muted(" ");
    private final JTextField findField = Comic.field(22);
    private InputFrame findFrame;
    private final IconButton filterToggle = new IconButton(EditorIcons.filterLines(),
            "Zeilen filtern: alle Zeilen ohne Treffer zuklappen");
    private final IconButton previous = new IconButton(ComposerIcons.chevronUp(), "Vorheriger Treffer (Shift+Enter)");
    private final IconButton next = new IconButton(ComposerIcons.chevronDown(), "Nächster Treffer (Enter)");
    private final JLabel findCount = Comic.muted("");
    private final JComboBox<EditorLanguage> languageChoice = Comic.combo();
    private final Timer reparse = new Timer(400, e -> reparse());
    /** Sucht nach einer Textänderung neu, damit Treffer und Zahl zum aktuellen Text passen. */
    private final Timer refind = new Timer(300, e -> find());
    private final java.util.function.Consumer<EditorSession> observer =
            changed -> SwingUtilities.invokeLater(this::render);
    private final List<TextSearch.Match> matches = new ArrayList<>();
    private int current = -1;
    /** Welcher Ladestand ({@link EditorSession#revision()}) im Editor steht. */
    private long shownRevision;
    /** Während der Editor selbst Text setzt, ist das keine Bearbeitung. */
    private boolean filling;
    private boolean choosingLanguage;
    private EditorLanguage chosen = EditorLanguage.AUTO;
    private EditorLanguage effective = EditorLanguage.AUTO;
    private Outline outline;
    private long parseTicket;
    /** Fasst schnelles Tippen zusammen, bevor die Natural-Blöcke neu gefärbt werden. */
    private final javax.swing.Timer blocks;
    private final Runnable recolor = () -> SwingUtilities.invokeLater(this::recolorBlocks);

    public DocumentEditor(EditorSession session) {
        super(new BorderLayout());
        this.session = session;
        setOpaque(true);
        setBackground(Color.WHITE);
        configure(area);
        blocks = new javax.swing.Timer(300, e -> NaturalBlockHighlighter.apply(area));
        blocks.setRepeats(false);
        area.setEditable(false);
        area.getAccessibleContext().setAccessibleName(session.name());
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Nur Attribute (Hervorhebung), kein Text.
            }
        });
        key(area, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "deck-save", session::save);
        key(area, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "deck-find", this::focusFind);
        scroll = new RTextScrollPane(area, true);
        style(scroll);
        reparse.setRepeats(false);
        refind.setRepeats(false);

        // Werkzeuge oben rechts als Symbolknöpfe.
        tools.setOpaque(false);
        outlineToggle.addActionListener(e -> showOutline(!outlineToggle.marked()));
        compareToggle.addActionListener(e -> showCompare(!compareToggle.marked()));
        wrapToggle.addActionListener(e -> wrap(!wrapToggle.marked()));
        undo.addActionListener(e -> {
            if (area.canUndo()) {
                area.undoLastAction();
            }
        });
        redo.addActionListener(e -> {
            if (area.canRedo()) {
                area.redoLastAction();
            }
        });
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(area.getText()), null));
        saveAs.addActionListener(e -> saveAsChosen());
        tools.add(outlineToggle);
        tools.add(compareToggle);
        tools.add(wrapToggle);
        tools.add(undo);
        tools.add(redo);
        tools.add(copy);
        if (session.store().canSaveAs()) {
            tools.add(saveAs);
        }
        undo.setVisible(session.writable());
        redo.setVisible(session.writable());

        // Mitte: Text, darunter der Vergleich, rechts die Gliederung.
        outlinePanel = new OutlinePanel(this::goToLine);
        buildCompare();
        textAndCompare = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, comparePanel);
        textAndCompare.setResizeWeight(0.6);
        textAndCompare.setBorder(BorderFactory.createEmptyBorder());
        textAndCompare.setDividerSize(6);
        textAndOutline = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, textAndCompare, outlinePanel);
        textAndOutline.setResizeWeight(1.0);
        textAndOutline.setBorder(BorderFactory.createEmptyBorder());
        textAndOutline.setDividerSize(6);
        center.setOpaque(false);
        center.add(textAndOutline, TEXT_CARD);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JPanel toolRow = new JPanel(new BorderLayout());
        toolRow.setOpaque(false);
        toolRow.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        toolRow.add(tools, BorderLayout.EAST);
        top.add(plate, BorderLayout.NORTH);
        top.add(toolRow, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(findRow(), BorderLayout.SOUTH);

        AppState state = AppState.shared();
        wrap(state.getBoolean(WRAP_KEY, false));
        filterToggle.marked(state.getBoolean(FILTER_KEY, true));
        showCompare(false);
        showOutline(state.getBoolean(OUTLINE_KEY, true));

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                if (isDisplayable()) {
                    watch();
                    render();
                } else {
                    session.unobserve(observer);
                    reparse.stop();
                    refind.stop();
                    NaturalBlockHighlighter.unobserve(recolor);
                }
            }
        });
        watch();
        render();
    }

    /**
     * Meldet sich bei Sitzung und Blockfarben an, höchstens einmal: Konstruktor und „sichtbar geworden“ rufen das
     * beide; ein doppelt angemeldeter Beobachter würde beim Schließen nur einmal abgemeldet und hielte den Editor fest.
     */
    void watch() {
        session.unobserve(observer);
        NaturalBlockHighlighter.unobserve(recolor);
        session.observe(observer);
        NaturalBlockHighlighter.observe(recolor);
    }

    // ---- Schnittstelle für die Arbeitsbereiche ----------------------------------------------------------------------

    public EditorSession session() {
        return session;
    }

    /** Hängt einen Symbolknopf an die Werkzeugleiste (z. B. „JCL ausführen“ im FTP-Bereich, „Visuell“). */
    public void addAction(String tooltip, Icon icon, Consumer<DocumentEditor> action) {
        IconButton button = new IconButton(icon, tooltip);
        button.addActionListener(e -> action.accept(this));
        tools.add(button);
        tools.revalidate();
        tools.repaint();
    }

    /** Die Parser-Sprache, mit der der Text gerade gelesen wird (gewählt oder erkannt). */
    public Optional<SourceLanguage> language() {
        return effective.parser();
    }

    /** Die zuletzt berechnete Gliederung. */
    public Optional<Outline> outline() {
        return Optional.ofNullable(outline);
    }

    /** Ersetzt die Mitte durch eine eigene Ansicht (z. B. ein Diagramm); {@link #showText()} holt den Text zurück. */
    public void showInCenter(JComponent view) {
        for (java.awt.Component component : center.getComponents()) {
            if (component != textAndOutline) {
                center.remove(component);
            }
        }
        center.add(view, CUSTOM_CARD);
        centerCards.show(center, CUSTOM_CARD);
        center.revalidate();
        center.repaint();
    }

    public void showText() {
        centerCards.show(center, TEXT_CARD);
        focusText();
    }

    /** Setzt den Fokus in den Text. */
    public void focusText() {
        area.requestFocusInWindow();
    }

    /** Springt zu einer Zeile (ab 1) und klappt sie auf. */
    public void goToLine(int line) {
        try {
            int offset = area.getLineStartOffset(Math.max(0, Math.min(line - 1, area.getLineCount() - 1)));
            area.getFoldManager().ensureOffsetNotInClosedFold(offset);
            area.setCaretPosition(offset);
            centerOn(offset);
            area.requestFocusInWindow();
        } catch (BadLocationException ignored) {
            // Zeile gibt es nicht.
        }
    }

    /** Der angezeigte Text (auch für Tests der Arbeitsbereiche). */
    public String text() {
        return area.getText();
    }

    /** Tippt Text an die Schreibmarke, wie eine Eingabe (für Tests). */
    public void type(String text) {
        area.replaceSelection(text);
    }

    /** Der Satz der Meldung oben, leer ohne Meldung (für Tests). */
    public String plateSentence() {
        return plate.sentence();
    }

    public void clickPlateAction() {
        plate.clickAction();
    }

    public String syntaxStyle() {
        return area.getSyntaxEditingStyle();
    }

    // ---- Suche -----------------------------------------------------------------------------------------------------

    private JComponent findRow() {
        findField.getAccessibleContext().setAccessibleName("Suchen");
        findField.setToolTipText("<html>Regulärer Ausdruck, ohne Groß/klein<br><br><b>Beispiele:</b><br>"
                + "abc – enthält „abc“<br>^abc – beginnt mit „abc“<br>abc$ – endet mit „abc“<br>\\d+ – Ziffern<br><br>"
                + "Mit „Zeilen filtern“ werden alle Zeilen ohne Treffer zugeklappt.</html>");
        findFrame = InputFrame.search(findField);
        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                find();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                find();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        key(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find-next", () -> step(1));
        key(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "find-previous",
                () -> step(-1));
        key(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find-clear", () -> {
            findField.setText("");
            focusText();
        });
        filterToggle.addActionListener(e -> {
            filterToggle.marked(!filterToggle.marked());
            AppState.shared().put(FILTER_KEY, filterToggle.marked());
            find();
        });
        previous.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));

        languageChoice.getAccessibleContext().setAccessibleName("Sprache");
        languageChoice.setToolTipText("Wie der Text gelesen wird: Hervorhebung und Gliederung");
        EditorLanguage.ALL.forEach(languageChoice::addItem);
        languageChoice.setSelectedItem(EditorLanguage.AUTO);
        languageChoice.addActionListener(e -> {
            if (!choosingLanguage && languageChoice.getSelectedItem() instanceof EditorLanguage language) {
                chooseLanguage(language);
            }
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(findFrame);
        left.add(filterToggle);
        left.add(previous);
        left.add(next);
        left.add(findCount);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(Comic.caption("Sprache"));
        right.add(languageChoice);
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        row.add(left, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private void focusFind() {
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    /** Markiert alle Treffer neu, faltet im Filtermodus und zeigt die Zahl. */
    private void find() {
        String regex = findField.getText();
        clearFindMarks();
        matches.clear();
        current = -1;
        Optional<String> problem = TextSearch.problem(regex);
        findField.setBackground(problem.isPresent() ? Comic.CHANGE_REMOVED : Color.WHITE);
        findFrame.setBackground(findField.getBackground());
        if (regex.isBlank() || problem.isPresent()) {
            RegexFolds.clear(area);
            findCount.setText(problem.isPresent() ? "ungültig" : "");
            findCount.setToolTipText(problem.orElse(null));
            showNaturalBlocks();
            return;
        }
        matches.addAll(TextSearch.matches(area.getText(), regex));
        for (TextSearch.Match match : matches) {
            mark(match, FIND_MARK);
        }
        if (filterToggle.marked()) {
            int lines = RegexFolds.apply(area, regex);
            findCount.setText(matches.size() + (matches.size() == 1 ? " Treffer" : " Treffer") + " in " + lines
                    + (lines == 1 ? " Zeile" : " Zeilen"));
        } else {
            RegexFolds.clear(area);
            findCount.setText(matches.isEmpty() ? "kein Treffer" : "– / " + matches.size());
        }
        findCount.setToolTipText(null);
        if (matches.isEmpty()) {
            findField.setBackground(Comic.CHANGE_REMOVED);
            findFrame.setBackground(findField.getBackground());
        }
        showNaturalBlocks();
    }

    private void step(int direction) {
        if (matches.isEmpty()) {
            find();
            if (matches.isEmpty()) {
                return;
            }
        }
        current = current < 0 ? (direction > 0 ? 0 : matches.size() - 1)
                : Math.floorMod(current + direction, matches.size());
        clearFindMarks();
        for (int i = 0; i < matches.size(); i++) {
            mark(matches.get(i), i == current ? FIND_CURRENT : FIND_MARK);
        }
        TextSearch.Match match = matches.get(current);
        area.getFoldManager().ensureOffsetNotInClosedFold(match.start());
        area.select(match.start(), match.end());
        centerOn(match.start());
        findCount.setText((current + 1) + " / " + matches.size());
    }

    private void mark(TextSearch.Match match, Color color) {
        try {
            area.getHighlighter().addHighlight(match.start(), match.end(), new FindPainter(color));
        } catch (BadLocationException ignored) {
            // Text hat sich inzwischen geändert.
        }
    }

    private void clearFindMarks() {
        Highlighter highlighter = area.getHighlighter();
        for (Highlighter.Highlight highlight : highlighter.getHighlights()) {
            if (highlight.getPainter() instanceof FindPainter) {
                highlighter.removeHighlight(highlight);
            }
        }
    }

    private static final class FindPainter extends DefaultHighlighter.DefaultHighlightPainter {
        FindPainter(Color color) {
            super(color);
        }
    }

    // ---- Sprache und Gliederung ------------------------------------------------------------------------------------

    private void chooseLanguage(EditorLanguage language) {
        chosen = language;
        applyLanguage();
    }

    /** Wendet die gewählte bzw. erkannte Sprache an: Hervorhebung, Natural-Blöcke, Gliederung. */
    private void applyLanguage() {
        effective = chosen == EditorLanguage.AUTO ? EditorLanguage.detect(session.name(), area.getText()) : chosen;
        String style = effective.style();
        if (!style.equals(area.getSyntaxEditingStyle())) {
            area.setSyntaxEditingStyle(style);
            compareArea.setSyntaxEditingStyle(style);
        }
        choosingLanguage = true;
        try {
            languageChoice.setSelectedItem(chosen);
            languageChoice.setToolTipText(chosen == EditorLanguage.AUTO
                    ? "Automatisch erkannt: " + effective.label() : "Wie der Text gelesen wird: Hervorhebung und Gliederung");
        } finally {
            choosingLanguage = false;
        }
        reparse();
    }

    private void reparse() {
        showNaturalBlocks();
        Optional<SourceLanguage> parser = effective.parser();
        long ticket = ++parseTicket;
        if (parser.isEmpty()) {
            outline = null;
            outlinePanel.show(Optional.empty());
            return;
        }
        String text = area.getText();
        String name = session.name();
        Thread.ofVirtual().name("deck-outline").start(() -> {
            Outline parsed;
            try {
                parsed = Outline.parse(parser.get(), text, name);
            } catch (RuntimeException e) {
                parsed = null;
            }
            Outline result = parsed;
            SwingUtilities.invokeLater(() -> {
                if (ticket == parseTicket) {
                    outline = result;
                    outlinePanel.show(Optional.ofNullable(result));
                }
            });
        });
    }

    private void showNaturalBlocks() {
        if (effective.parser().filter(l -> l == SourceLanguage.NATURAL).isPresent()) {
            NaturalBlockHighlighter.apply(area);
        } else {
            NaturalBlockHighlighter.clear(area);
        }
    }

    // ---- Vergleich -------------------------------------------------------------------------------------------------

    private void buildCompare() {
        configure(compareArea);
        compareArea.setEditable(false);
        compareArea.getAccessibleContext().setAccessibleName("Vergleichsstand");
        RTextScrollPane compareScroll = new RTextScrollPane(compareArea, true);
        style(compareScroll);
        versions.getAccessibleContext().setAccessibleName("Version");
        versions.setToolTipText("Ältere Version zum Vergleich auswählen");
        versions.addActionListener(e -> showVersion());
        IconButton close = new IconButton(Glyph.CANCEL, "Vergleich schließen");
        close.addActionListener(e -> showCompare(false));
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(true);
        bar.setBackground(Comic.SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(Comic.caption("Vergleich mit"));
        left.add(versions);
        left.add(compareInfo);
        bar.add(left, BorderLayout.CENTER);
        bar.add(close, BorderLayout.EAST);
        comparePanel.add(bar, BorderLayout.NORTH);
        comparePanel.add(compareScroll, BorderLayout.CENTER);
    }

    private void showCompare(boolean show) {
        compareToggle.marked(show);
        comparePanel.setVisible(show);
        if (show) {
            fillVersions();
            SwingUtilities.invokeLater(() -> textAndCompare.setDividerLocation(0.6));
        } else {
            DiffHighlighter.clear(area);
            DiffHighlighter.clear(compareArea);
        }
        textAndCompare.revalidate();
    }

    private void fillVersions() {
        VersionChoice before = (VersionChoice) versions.getSelectedItem();
        versions.removeAllItems();
        List<VersionHistory.Version> all = session.versions();
        for (VersionHistory.Version version : all) {
            versions.addItem(new VersionChoice(version));
        }
        if (all.isEmpty()) {
            compareArea.setText("");
            compareInfo.setText("Noch keine Version aufbewahrt");
            return;
        }
        int index = 0;
        if (before != null) {
            for (int i = 0; i < versions.getItemCount(); i++) {
                if (versions.getItemAt(i).version().id().equals(before.version().id())) {
                    index = i;
                }
            }
        }
        versions.setSelectedIndex(index);
        showVersion();
    }

    private void showVersion() {
        if (!(versions.getSelectedItem() instanceof VersionChoice choice)) {
            return;
        }
        compareArea.setText(session.version(choice.version().id()).orElse(""));
        compareArea.setCaretPosition(0);
        diff();
    }

    private void diff() {
        if (!comparePanel.isVisible() || versions.getItemCount() == 0) {
            return;
        }
        int changes = DiffHighlighter.apply(area, compareArea);
        compareInfo.setText(changes < 0 ? "Zu groß für den Vergleich"
                : changes == 0 ? "Keine Unterschiede" : changes + (changes == 1 ? " Unterschied" : " Unterschiede"));
    }

    private record VersionChoice(VersionHistory.Version version) {
        @Override
        public String toString() {
            return VERSION_TIME.format(version.at().atZone(ZoneId.systemDefault())) + " · " + version.label()
                    + " (" + version.length() + " Zeichen)";
        }
    }

    // ---- Ansicht ---------------------------------------------------------------------------------------------------

    private void showOutline(boolean show) {
        outlineToggle.marked(show);
        outlinePanel.setVisible(show);
        AppState.shared().put(OUTLINE_KEY, show);
        if (show) {
            SwingUtilities.invokeLater(() -> {
                int width = textAndOutline.getWidth();
                if (width > 0) {
                    textAndOutline.setDividerLocation(Math.max(width / 2, width - 300));
                }
            });
        }
        textAndOutline.revalidate();
    }

    private void wrap(boolean on) {
        wrapToggle.marked(on);
        area.setLineWrap(on);
        area.setWrapStyleWord(on);
        compareArea.setLineWrap(on);
        AppState.shared().put(WRAP_KEY, on);
    }

    /** „Speichern unter …“ mit Dateiauswahl (nur Ablagen, die das können). */
    public void saveAsChosen() {
        javax.swing.JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser
                .create(session.name());
        try {
            chooser.setSelectedFile(new java.io.File(session.location()));
        } catch (RuntimeException ignored) {
            // Vorschlag aus dem SaveChooser bleibt.
        }
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION
                || chooser.getSelectedFile() == null) {
            return;
        }
        java.io.File target = chooser.getSelectedFile();
        if (target.exists() && !target.getAbsolutePath().equals(session.location())
                && !com.aresstack.spaceharvester.app.ui.Dialogs.confirm(this, "Speichern unter",
                "„" + target.getName() + "“ gibt es schon. Wirklich ersetzen?")) {
            return;
        }
        session.saveAs(target.getAbsolutePath());
    }

    private void centerOn(int offset) {
        try {
            java.awt.geom.Rectangle2D box = area.modelToView2D(offset);
            if (box != null) {
                java.awt.Rectangle visible = area.getVisibleRect();
                int y = (int) box.getY() - visible.height / 3;
                area.scrollRectToVisible(new java.awt.Rectangle(0, Math.max(0, y), 1, visible.height));
            }
        } catch (BadLocationException ignored) {
            // nichts zu zeigen
        }
    }

    // ---- Zustand ---------------------------------------------------------------------------------------------------

    private void edited() {
        if (!findField.getText().isBlank()) {
            // Alte Fundstellen zeigen nach einer Änderung auf falsche Stellen: sofort verwerfen, gleich neu suchen.
            matches.clear();
            current = -1;
            refind.restart();
        }
        if (!filling) {
            session.edit(area.getText());
            undo.setEnabled(area.canUndo());
            redo.setEnabled(area.canRedo());
            reparse.restart();
            if (comparePanel.isVisible()) {
                diff();
            }
        }
        // Wie SplitPreviewTab.scheduleSubroutineHighlighting in MainframeMate: nach 300 ms Ruhe neu einfärben.
        if (natural()) {
            blocks.restart();
        }
    }

    private boolean natural() {
        return SyntaxStyles.NATURAL.equals(area.getSyntaxEditingStyle());
    }

    /** Färbt die Natural-Blöcke sofort neu (etwa nach geänderten Farben in den Einstellungen). */
    public void recolorBlocks() {
        if (natural()) {
            NaturalBlockHighlighter.apply(area);
        }
    }

    private void render() {
        EditorSession.State state = session.state();
        // Neuer Inhalt nur nach einem Laden; nach dem Speichern bleibt der Text, wie er im Editor steht. Gezählt wird
        // über die Ladenummer, nicht über Zustandswechsel, die zwischen zwei EDT-Durchläufen verloren gehen können.
        long revision = session.revision();
        if (revision != shownRevision && state != EditorSession.State.LOADING) {
            shownRevision = revision;
            fill(session.document().map(EditorDocument::text).orElse(""));
        }
        area.setEditable(session.writable()
                && (state == EditorSession.State.READY || state == EditorSession.State.CONFLICT));
        saveAs.setEnabled(state == EditorSession.State.READY || state == EditorSession.State.CONFLICT);
        if (state == EditorSession.State.CONFLICT) {
            plate.show(session.problem().orElse("Die Datei wurde geändert.") + " Speichern überschreibt das.",
                    "Trotzdem speichern", session::overwrite);
        } else if (state == EditorSession.State.FAILED) {
            plate.show(session.problem().orElse("Laden gescheitert."), null, null);
        } else {
            plate.dismiss();
        }
        if (comparePanel.isVisible() && state == EditorSession.State.READY) {
            int known = versions.getItemCount();
            if (session.versions().size() != known) {
                fillVersions();
            }
        }
    }

    private void fill(String text) {
        filling = true;
        try {
            area.setText(text);
            area.setCaretPosition(0);
            area.discardAllEdits();
        } finally {
            filling = false;
        }
        if (natural()) {
            SwingUtilities.invokeLater(() -> NaturalBlockHighlighter.apply(area));
        }
        session.edit(area.getText());
        undo.setEnabled(false);
        redo.setEnabled(false);
        applyLanguage();
        if (!findField.getText().isBlank()) {
            find();
        }
        if (comparePanel.isVisible()) {
            fillVersions();
        }
    }

    private static void configure(RSyntaxTextArea text) {
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setCodeFoldingEnabled(true);
        text.setAntiAliasingEnabled(true);
        text.setTabSize(4);
        text.setHighlightCurrentLine(true);
        text.setCurrentLineHighlightColor(Comic.NOTICE);
        text.setPaintTabLines(true);
    }

    private static void style(RTextScrollPane pane) {
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setFoldIndicatorEnabled(true);
        // Balken nur, wenn der Text nicht passt.
        pane.setVerticalScrollBarPolicy(javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        pane.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        pane.getGutter().setBackground(Comic.SURFACE);
        pane.getGutter().setLineNumberColor(Comic.MUTED);
        pane.getGutter().setBorderColor(Comic.LINE);
    }

    private static void key(JComponent component, KeyStroke stroke, String name, Runnable run) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        });
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    /** Sucht wie eine Eingabe in der Suchleiste. */
    public void search(String regex) {
        findField.setText(regex);
    }

    public void searchStep(int direction) {
        step(direction);
    }

    public String searchCount() {
        return findCount.getText();
    }

    /** Ob „Zeilen filtern“ an ist. */
    public boolean lineFilter() {
        return filterToggle.marked();
    }

    public void lineFilter(boolean on) {
        if (filterToggle.marked() != on) {
            filterToggle.doClick();
        }
    }

    /** Wählt eine Sprache wie unten rechts. */
    public void language(EditorLanguage language) {
        languageChoice.setSelectedItem(language);
    }

    public EditorLanguage effectiveLanguage() {
        return effective;
    }

    OutlinePanel outlinePanel() {
        return outlinePanel;
    }

    public boolean outlineShown() {
        return outlinePanel.isVisible();
    }

    public void compare(boolean show) {
        showCompare(show);
    }

    public String compareInfo() {
        return compareInfo.getText();
    }

    public int versionCount() {
        return versions.getItemCount();
    }

    /** Zahl der Zeilen, die gerade zugeklappt sind. */
    public int hiddenLines() {
        return area.getFoldManager().getHiddenLineCount();
    }

    public int caretLine() {
        return area.getCaretLineNumber() + 1;
    }
}
