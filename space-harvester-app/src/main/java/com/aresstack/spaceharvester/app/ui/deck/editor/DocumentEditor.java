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
import java.util.Objects;
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
public final class DocumentEditor extends JPanel implements com.aresstack.spaceharvester.app.ui.FindBar.Own {

    static final String WRAP_KEY = "deck.editor.wrap";
    static final String OUTLINE_KEY = "deck.editor.outline";
    static final String RELATIONS_KEY = "deck.editor.relations";
    static final String FILTER_KEY = "deck.editor.find-filter";
    private static final String TEXT_CARD = "text";
    private static final String CUSTOM_CARD = "custom";
    private static final DateTimeFormatter VERSION_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss",
            Locale.GERMANY);
    private static final Color FIND_MARK = new Color(0xFFE066);
    private static final Color FIND_CURRENT = new Color(0xFFA94D);
    /** Eine gezeigte Fundstelle ({@link #showPassage}): kräftiges Orange wie der aktuelle Treffer. */
    private static final Color PASSAGE_MARK = FIND_CURRENT;

    private final EditorSession session;
    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final RTextScrollPane scroll;
    private final MessagePlate plate = new MessagePlate();
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    /** Werkzeuge der eigenen Ansicht in der Mitte (etwa Zoom und Export des Diagramms), vorn in der Werkzeugzeile. */
    private final JPanel centerTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    private final List<Consumer<JComponent>> centerSideListeners = new ArrayList<>();
    private final IconButton relationsToggle = new IconButton(EditorIcons.relations(),
            "Beziehungen ein/aus (Ressourcen, Callees, Caller)");
    private final IconButton outlineToggle = new IconButton(EditorIcons.outline(), "Gliederung ein/aus");
    private final IconButton compareToggle = new IconButton(EditorIcons.compare(),
            "Mit einer früheren Version vergleichen");
    private final IconButton wrapToggle = new IconButton(EditorIcons.wrap(), "Zeilenumbruch ein/aus");
    private final IconButton undo = new IconButton(EditorIcons.undo(), "Rückgängig (Strg+Z)");
    private final IconButton redo = new IconButton(EditorIcons.redo(), "Wiederholen (Strg+Y)");
    private final IconButton copy = new IconButton(EditorIcons.copy(), "Ganzen Inhalt kopieren");
    private final IconButton saveAs = new IconButton(EditorIcons.saveAs(), "Speichern unter …");
    private final OutlinePanel outlinePanel;
    private final RelationsPanel relationsPanel;
    private final JSplitPane relationsAndText;
    private long relationsTicket;
    private final JSplitPane textAndCompare;
    private final JSplitPane textAndOutline;
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JPanel comparePanel = new JPanel(new BorderLayout());
    private final RSyntaxTextArea compareArea = new RSyntaxTextArea();
    private final JComboBox<VersionChoice> versions = Comic.combo();
    private final JLabel compareInfo = Comic.muted(" ");
    private final JTextField findField = Comic.field(22);
    /**
     * Keine eigene Suchzeile mehr (Angelo 24.11.2026: eine Suchleiste): gesucht wird mit der Lupe des Deck, die über
     * {@link com.aresstack.spaceharvester.app.ui.FindBar.Own} in diesen Editor sucht; Falt-Umschalter und ▲▼ stehen in
     * ihrem Feld. Das Feld hier trägt nur noch den Suchtext für die vorhandene Such- und Faltlogik.
     */
    private InputFrame findFrame;
    /** „Sprache“ samt Auswahl; steht rechts in der Ecke der Leiste mit der Lupe. */
    private JComponent languagePart;
    /** Leseansichten ({@link #readOnlyView()}) suchen nicht selbst; dort hebt die Lupe nur hervor. */
    private boolean ownsFind = true;
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
    private static final List<Consumer<DocumentEditor>> CREATED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Runnable> languageObservers = new ArrayList<>();
    private final List<OutlinePanel> extraOutlines = new ArrayList<>();
    private CenterSearch centerSearch;
    private boolean choosingLanguage;
    private EditorLanguage chosen = EditorLanguage.AUTO;
    private EditorLanguage effective = EditorLanguage.AUTO;
    private Outline outline;
    private long parseTicket;
    private CobolEditorAnalysis cobolEditorAnalysis;
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
        scroll = new RTextScrollPane(area, true);
        style(scroll);
        reparse.setRepeats(false);
        refind.setRepeats(false);

        // Werkzeuge oben rechts als Symbolknöpfe.
        tools.setOpaque(false);
        relationsToggle.addActionListener(e -> showRelations(!relationsToggle.marked()));
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
        centerTools.setOpaque(false);
        tools.add(centerTools);
        tools.add(relationsToggle);
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

        // Mitte: links die Beziehungen, Text, darunter der Vergleich, rechts die Gliederung.
        outlinePanel = new OutlinePanel(this::goToLine);
        relationsPanel = new RelationsPanel(new RelationsPanel.Actions() {
            @Override
            public void line(String sourceFile, int line) {
                relationLine(sourceFile, line);
            }

            @Override
            public void open(String target) {
                openTarget(target);
            }
        });
        buildCompare();
        textAndCompare = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, comparePanel);
        textAndCompare.setResizeWeight(0.6);
        textAndCompare.setBorder(BorderFactory.createEmptyBorder());
        textAndCompare.setDividerSize(6);
        relationsAndText = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, relationsPanel, textAndCompare);
        relationsAndText.setResizeWeight(0.0);
        relationsAndText.setBorder(BorderFactory.createEmptyBorder());
        relationsAndText.setDividerSize(6);
        textAndOutline = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, relationsAndText, outlinePanel);
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
        languagePart = findSetup();
        // Gezeigt oder verdeckt: die Leiste mit der Lupe richtet Umschalter, Ecke und Treffer auf den sichtbaren Editor.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                com.aresstack.spaceharvester.app.ui.FindBar.viewChanged(this);
            }
        });

        AppState state = AppState.shared();
        wrap(state.getBoolean(WRAP_KEY, false));
        filterToggle.marked(state.getBoolean(FILTER_KEY, true));
        showCompare(false);
        showOutline(state.getBoolean(OUTLINE_KEY, true));
        showRelations(state.getBoolean(RELATIONS_KEY, true));

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
        for (Consumer<DocumentEditor> created : CREATED) {
            created.accept(this);
        }
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
    public IconButton addAction(String tooltip, Icon icon, Consumer<DocumentEditor> action) {
        IconButton button = new IconButton(icon, tooltip);
        button.addActionListener(e -> action.accept(this));
        tools.add(button);
        tools.revalidate();
        tools.repaint();
        return button;
    }

    /**
     * Meldet jeden neu gebauten Editor, gleich in welchem Bereich (Dateien, FTP, NDV); so hängen Erweiterungen wie die
     * Diagramm-Ansicht „Visuell“ ihre Knöpfe an, ohne dass jeder Bereich davon weiß. Aufruf auf dem EDT.
     */
    public static void onCreated(Consumer<DocumentEditor> observer) {
        CREATED.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Meldet, wenn die Sprache wechselt (gewählt oder nach dem Laden erkannt). */
    public void onLanguageChanged(Runnable observer) {
        languageObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Ersetzt den ganzen Text wie eine Eingabe (rückgängig machbar), z. B. nach einer Änderung im Diagramm. */
    public void replaceText(String text) {
        if (!area.isEditable() || text == null || text.equals(area.getText())) {
            return;
        }
        area.beginAtomicEdit();
        try {
            area.setText(text);
        } finally {
            area.endAtomicEdit();
        }
    }

    /**
     * Eine weitere Gliederung desselben Texts, die mit jeder neuen Gliederung mitgeht; die Auswahl meldet sie an
     * {@code chosen} statt zur Zeile zu springen (Diagramm-Ansicht: Element im Diagramm zeigen).
     */
    public JComponent outlineView(Consumer<com.aresstack.spaceharvester.mainframe.model.JclElement> chosen) {
        OutlinePanel panel = new OutlinePanel(line -> { }, Objects.requireNonNull(chosen, "chosen"));
        panel.show(Optional.ofNullable(outline));
        extraOutlines.add(panel);
        return panel;
    }

    /** Meldet eine Gliederung aus {@link #outlineView} wieder ab (die Diagramm-Ansicht ist weg). */
    public void releaseOutlineView(JComponent view) {
        extraOutlines.remove(view);
    }

    /** Ob der Text gerade bearbeitet werden darf. */
    public boolean editable() {
        return area.isEditable();
    }

    /** Die Parser-Sprache, mit der der Text gerade gelesen wird (gewählt oder erkannt). */
    public Optional<SourceLanguage> language() {
        return effective.parser();
    }

    /** Die zuletzt berechnete Gliederung. */
    public Optional<Outline> outline() {
        return Optional.ofNullable(outline);
    }

    /**
     * Suche einer eigenen Ansicht in der Mitte: Solange sie gezeigt wird, sucht die Suchzeile unten darin (in
     * MainframeMate suchte die Suchleiste im Diagramm nach Knoten), Enter/Shift+Enter springen von Treffer zu Treffer.
     */
    public interface CenterSearch {

        /** Sucht und markiert; gibt die Zahl der Treffer zurück. */
        int search(String query);

        /** Nächster ({@code +1}) oder vorheriger ({@code -1}) Treffer; Anzeige wie „2 / 5“ oder {@code null}. */
        String step(int direction);

        /** Suche aufheben (beim Zurückschalten zum Text). */
        void clearSearch();
    }

    /**
     * Eine eigene Ansicht in der Mitte mit Werkzeugen: {@link #barTools()} stehen vorn in der Werkzeugzeile des
     * Editors, {@link #sideTools()} zeigt ein Arbeitsbereich mit eigener Seitenleiste dort als Reiter (sonst bettet die
     * Ansicht sie selbst ein, siehe {@link #sidePanelsDetached()}).
     */
    public interface CenterTools {

        JComponent[] barTools();

        JComponent sideTools();
    }

    /** Meldet die Seitenwerkzeuge der Mitte: beim Zeigen einer {@link CenterTools}-Ansicht, {@code null} beim Text. */
    public void onCenterSide(Consumer<JComponent> listener) {
        centerSideListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Ob ein Arbeitsbereich die Seitenpanels in seine Seitenleiste genommen hat ({@link #detachSidePanels()}). */
    public boolean sidePanelsDetached() {
        return sidePanels != null;
    }

    /**
     * Auswahl in der Gliederung geht an {@code chooser} statt zur Zeile (Diagramm-Ansicht: Element im Diagramm zeigen);
     * {@code null} stellt das Springen zur Zeile wieder her.
     */
    public void outlineChooser(Consumer<com.aresstack.spaceharvester.mainframe.model.JclElement> chooser) {
        outlinePanel.chooser(chooser);
    }

    private void centerTools(CenterTools view) {
        centerTools.removeAll();
        if (view != null) {
            for (JComponent part : view.barTools()) {
                centerTools.add(part);
            }
        }
        centerTools.revalidate();
        revalidate();
        repaint();
        JComponent side = view == null ? null : view.sideTools();
        for (Consumer<JComponent> listener : List.copyOf(centerSideListeners)) {
            listener.accept(side);
        }
    }

    /** Ersetzt die Mitte durch eine eigene Ansicht (z. B. ein Diagramm); {@link #showText()} holt den Text zurück. */
    public void showInCenter(JComponent view) {
        centerTools(view instanceof CenterTools withTools ? withTools : null);
        centerSearch = view instanceof CenterSearch search ? search : null;
        for (java.awt.Component component : center.getComponents()) {
            if (component != textAndOutline && component != textAndCompare) {
                center.remove(component);
            }
        }
        center.add(view, CUSTOM_CARD);
        centerCards.show(center, CUSTOM_CARD);
        center.revalidate();
        center.repaint();
    }

    public void showText() {
        centerTools(null);
        if (centerSearch != null) {
            centerSearch.clearSearch();
            centerSearch = null;
        }
        centerCards.show(center, TEXT_CARD);
        find();
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

    private JComponent findSetup() {
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

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(Comic.caption("Sprache"));
        right.add(languageChoice);
        return right;
    }

    // ---- Suche über die Lupe des Deck (FindBar.Own) --------------------------------------------------------------

    @Override
    public boolean ownsFind() {
        return ownsFind;
    }

    @Override
    public void find(String query) {
        findField.setText(query == null ? "" : query);
    }

    @Override
    public void findStep(int direction) {
        step(direction);
    }

    @Override
    public String findCounter() {
        return findCount.getText();
    }

    @Override
    public boolean folding() {
        return filterToggle.marked();
    }

    @Override
    public void folding(boolean on) {
        if (filterToggle.marked() != on) {
            filterToggle.doClick();
        }
    }

    @Override
    public javax.swing.Icon foldIcon() {
        return filterToggle.getIcon();
    }

    @Override
    public JComponent findCorner() {
        return ownsFind ? languagePart : null;
    }

    /**
     * Zeigt eine Fundstelle im Text: holt den Text nach vorn, markiert die Stelle orange, wählt sie aus und rollt sie
     * ins obere Drittel. Gesucht wird über
     * {@link com.aresstack.spaceharvester.app.ui.content.viewer.Passages#locate}; die vorige Fundstelle verliert ihre
     * Markierung.
     *
     * @return ob die Stelle gefunden wurde
     */
    public boolean showPassage(String excerpt, int start, int end) {
        clearPassage();
        int[] found = com.aresstack.spaceharvester.app.ui.content.viewer.Passages.locate(area.getText(), excerpt,
                start, end);
        if (found == null) {
            return false;
        }
        if (centerSearch != null) {
            showText();
        }
        try {
            area.getHighlighter().addHighlight(found[0], found[1], new PassagePainter());
        } catch (BadLocationException e) {
            return false;
        }
        area.getFoldManager().ensureOffsetNotInClosedFold(found[0]);
        area.select(found[0], found[1]);
        // Erst nach dem Layout: die Vorschau ist eben erst wieder sichtbar geworden
        javax.swing.SwingUtilities.invokeLater(() -> centerOn(found[0]));
        return true;
    }

    private void clearPassage() {
        Highlighter highlighter = area.getHighlighter();
        for (Highlighter.Highlight highlight : highlighter.getHighlights()) {
            if (highlight.getPainter() instanceof PassagePainter) {
                highlighter.removeHighlight(highlight);
            }
        }
    }

    /** Die markierte Fundstelle als {@code {start, end}}; {@code null} ohne. Für Tests. */
    int[] passage() {
        for (Highlighter.Highlight highlight : area.getHighlighter().getHighlights()) {
            if (highlight.getPainter() instanceof PassagePainter) {
                return new int[] {highlight.getStartOffset(), highlight.getEndOffset()};
            }
        }
        return null;
    }

    private static final class PassagePainter extends DefaultHighlighter.DefaultHighlightPainter {
        PassagePainter() {
            super(PASSAGE_MARK);
        }
    }

    /** Markiert alle Treffer neu, faltet im Filtermodus und zeigt die Zahl. */
    private void find() {
        if (centerSearch != null) {
            String query = findField.getText().trim();
            int count = query.isEmpty() ? 0 : centerSearch.search(query);
            findField.setBackground(!query.isEmpty() && count == 0 ? Comic.CHANGE_REMOVED : Color.WHITE);
            findFrame.setBackground(findField.getBackground());
            findCount.setText(query.isEmpty() ? "" : count == 0 ? "kein Treffer" : "1 / " + count);
            findCount.setToolTipText(null);
            return;
        }
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
        if (centerSearch != null) {
            String position = centerSearch.step(direction);
            if (position != null) {
                findCount.setText(position);
            }
            return;
        }
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
        for (Runnable observer : List.copyOf(languageObservers)) {
            observer.run();
        }
    }

    private void reparse() {
        showNaturalBlocks();
        Optional<SourceLanguage> parser = effective.parser();
        long ticket = ++parseTicket;
        if (parser.isEmpty()) {
            outline = null;
            outlinePanel.show(Optional.empty());
            relationsTicket++;
            relationsPanel.show(com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.Analysis.NONE);
            showRelations(relationsToggle.marked());
            extraOutlines.forEach(panel -> panel.show(Optional.empty()));
            return;
        }
        showRelations(relationsToggle.marked());
        if (parser.get() == SourceLanguage.COBOL) {
            reparseCobol(ticket);
            return;
        }
        analyzeRelations(parser);
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
                    extraOutlines.forEach(panel -> panel.show(Optional.ofNullable(result)));
                }
            });
        });
    }

    private void reparseCobol(long outlineTicket) {
        long relationTicket = ++relationsTicket;
        CobolEditorAnalysis worker = cobolWorker();
        String text = area.getText();
        if (!relationsPanel.hasData()) {
            relationsPanel.loading();
        }
        Thread.ofVirtual().name("deck-cobol-analysis").start(() -> {
            CobolEditorAnalysis.Result result = worker.analyze(text, DocumentEditors.relations());
            SwingUtilities.invokeLater(() -> {
                if (outlineTicket == parseTicket) {
                    outline = result.outline();
                    outlinePanel.show(Optional.of(outline));
                    extraOutlines.forEach(panel -> panel.show(Optional.of(outline)));
                }
                if (relationTicket == relationsTicket) {
                    relationsPanel.show(result.relations());
                }
            });
        });
    }

    private CobolEditorAnalysis cobolWorker() {
        String location = session.location().isBlank() ? session.name() : session.location();
        long revision = session.revision();
        if (cobolEditorAnalysis == null || !cobolEditorAnalysis.matches(location, revision)) {
            cobolEditorAnalysis = new CobolEditorAnalysis(session.store(), location, revision);
        }
        return cobolEditorAnalysis;
    }

    /** Capture a COBOL snapshot on the EDT and analyze it only on the caller's worker thread. */
    public java.util.function.Supplier<Outline> cobolDiagramOutline(String text) {
        if (!language().filter(value -> value == SourceLanguage.COBOL).isPresent()) {
            return () -> null;
        }
        CobolEditorAnalysis worker = cobolWorker();
        return () -> worker.analyze(text, DocumentEditors.relations()).outline();
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

    /**
     * Wertet die Beziehungen aus wie {@code TabbedPaneManager.updateLeftDrawerRelations} in MainframeMate; der Graph
     * kommt bei NDV-Quellen vom Server, deshalb im Hintergrund.
     */
    private void analyzeRelations(Optional<SourceLanguage> parser) {
        long ticket = ++relationsTicket;
        String text = area.getText();
        String name = session.location() == null || session.location().isBlank() ? session.name()
                : session.location();
        String library = library();
        if (!relationsPanel.hasData()) {
            relationsPanel.loading();
        }
        Thread.ofVirtual().name("deck-relations").start(() -> {
            com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.Analysis analysis;
            try {
                analysis = DocumentEditors.relations().analyze(parser, text, name, library);
            } catch (RuntimeException e) {
                analysis = new com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.Analysis(
                        new com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.Resources("",
                                java.util.Map.of(), 0, "Fehler bei der Abhängigkeitsanalyse ("
                                + e.getClass().getSimpleName() + ")"),
                        com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.Analysis.NONE.callees(),
                        com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.Analysis.NONE.callers());
            }
            com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.Analysis result = analysis;
            SwingUtilities.invokeLater(() -> {
                if (ticket == relationsTicket) {
                    relationsPanel.show(result);
                }
            });
        });
    }

    /** Natural-Bibliothek einer NDV-Quelle ({@code LIB/NAME.NSP}), sonst {@code null}. */
    private String library() {
        String location = session.location();
        if (location == null || !session.store().id().startsWith("ndv:")) {
            return null;
        }
        int slash = location.indexOf('/');
        return slash > 0 && slash < location.length() - 1 ? location.substring(0, slash) : null;
    }

    /** Einfachklick mit Zeile: im offenen Dokument springen, in einer anderen NDV-Quelle über NDV öffnen. */
    private void relationLine(String sourceFile, int line) {
        if (sourceFile == null || sourceFile.isBlank() || isThisDocument(sourceFile)) {
            goToLine(line);
            return;
        }
        openNdv(sourceFile, line);
    }

    private boolean isThisDocument(String target) {
        String library = library();
        if (library == null || !target.startsWith("ndv://")) {
            return false;
        }
        String object = com.aresstack.spaceharvester.mainframe.dependency.SourceRelations.objectName(
                session.location());
        return target.equalsIgnoreCase("ndv://" + library + "/" + object);
    }

    /** Doppelklick: Ziel öffnen wie {@code MainFrame} in MainframeMate. */
    private void openTarget(String target) {
        if (target == null || target.isBlank()) {
            return;
        }
        if (target.startsWith("ndv://")) {
            openNdv(target, 0);
        } else if (target.startsWith("nat-jcl://")) {
            String rest = target.substring("nat-jcl://".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                return;
            }
            String stepLib = rest.substring(0, slash);
            String program = rest.substring(slash + 1);
            DocumentEditors.naturalFromJcl().ifPresentOrElse(open -> open.accept(stepLib, program),
                    () -> relationsPanel.notice("Natural-Quelle öffnen geht nur mit NDV-Verbindung."));
        } else if (target.startsWith("sysfunc://")) {
            browse(DocumentEditors.wikipedia(target.substring("sysfunc://".length())));
        } else if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                browse(java.net.URI.create(target));
            } catch (IllegalArgumentException e) {
                relationsPanel.notice("Keine gültige Adresse.");
            }
        }
    }

    private void openNdv(String target, int line) {
        String rest = target.substring("ndv://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return;
        }
        String library = rest.substring(0, slash);
        String object = rest.substring(slash + 1);
        DocumentEditors.naturalOpener().ifPresentOrElse(open -> open.open(library, object, line),
                () -> relationsPanel.notice("Natural-Quelle öffnen geht nur mit NDV-Verbindung."));
    }

    private void browse(java.net.URI uri) {
        Thread.ofVirtual().name("deck-browse").start(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(uri);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> relationsPanel.notice("Browser ließ sich nicht öffnen ("
                        + e.getClass().getSimpleName() + ")."));
            }
        });
    }

    private void showRelations(boolean show) {
        if (sidePanels != null) {
            relationsToggle.setVisible(false);
            return;
        }
        relationsToggle.marked(show);
        boolean visible = show && effective.parser().isPresent();
        relationsToggle.setVisible(effective.parser().isPresent());
        boolean wasVisible = relationsPanel.isVisible();
        relationsPanel.setVisible(visible);
        AppState.shared().put(RELATIONS_KEY, show);
        if (visible && !wasVisible) {
            SwingUtilities.invokeLater(() -> {
                int width = relationsAndText.getWidth();
                if (width > 0) {
                    relationsAndText.setDividerLocation(Math.min(width * 3 / 10, 380));
                }
            });
        }
        relationsAndText.revalidate();
    }

    /**
     * Reine Leseansicht (E-Akte, Angelo 24.11.): ganze Fläche für das Dokument, keine Gliederung daneben, keine
     * zweite Suchzeile, kein Vergleich, keine Sprachwahl; gesucht wird nur mit der Lupe des Deck (Strg+F geht dann an sie). Die gemerkten Einstellungen
     * der übrigen Editoren bleiben unberührt.
     */
    public void readOnlyView() {
        outlineToggle.marked(false);
        outlinePanel.setVisible(false);
        ownsFind = false;
        // Für Dokumente ohne Wirkung: Gliederung/Beziehungen/Sprache sind für JCL, COBOL, Natural; der Vergleich
        // nutzt lokale Kopien, die an der wechselnden temporären Datei hängen; Bearbeiten gibt es nicht.
        showRelations(false);
        for (JComponent tool : List.of(outlineToggle, relationsToggle, compareToggle, undo, redo, saveAs)) {
            tool.setVisible(false);
        }
        textAndOutline.revalidate();
        revalidate();
    }

    private void showOutline(boolean show) {
        if (sidePanels != null) {
            outlineToggle.setVisible(false);
            return;
        }
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

    RelationsPanel relationsPanel() {
        return relationsPanel;
    }

    /** Ressourcen, Aufrufhierarchie (Callees/Caller) und Gliederung für die Seitenleiste eines Arbeitsbereichs. */
    public record SidePanels(JComponent resources, JComponent calls, JComponent outline) {
    }

    private SidePanels sidePanels;

    /**
     * Nimmt Ressourcen, Callees/Caller und Gliederung aus der Mitte; der Text bekommt die ganze Fläche (Angelo 24.11.:
     * „es bleibt nur noch ein Mini-Quadrat“). Der Arbeitsbereich zeigt sie in seiner Seitenleiste, je einen als Reiter.
     * Die Schalter dafür verschwinden aus der Werkzeugleiste. Mehrfach aufgerufen: dieselben Bausteine.
     */
    public SidePanels detachSidePanels() {
        if (sidePanels == null) {
            center.remove(textAndOutline);
            textAndOutline.removeAll();
            relationsAndText.removeAll();
            center.add(textAndCompare, TEXT_CARD, 0);
            JComponent[] relations = relationsPanel.detach();
            relationsPanel.setVisible(true);
            outlinePanel.setVisible(true);
            outlinePanel.setBorder(BorderFactory.createEmptyBorder());
            sidePanels = new SidePanels(relations[0], relations[1], outlinePanel);
            relationsToggle.setVisible(false);
            outlineToggle.setVisible(false);
            if (centerSearch == null) {
                centerCards.show(center, TEXT_CARD);
            }
            center.revalidate();
            center.repaint();
        }
        return sidePanels;
    }

    /** Filtert Callees und Caller nach Namen (Suche der Seitenleiste); leer zeigt alle. */
    public void filterCalls(String regex) {
        relationsPanel.names(regex);
    }

    /** Filtert die Ressourcen (Suche der Seitenleiste). */
    public void filterResources(String query) {
        relationsPanel.resources(query);
    }

    /** Filtert die Gliederung (Suche der Seitenleiste). */
    public void filterOutline(String query) {
        outlinePanel.query(query);
    }

    public boolean relationsShown() {
        return relationsPanel.isVisible();
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
