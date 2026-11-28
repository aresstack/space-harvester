package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * Die Werkzeugleiste direkt über der Statuszeile, nur so breit wie die Arbeitsfläche (nicht über dem Explorer), wie
 * die Zeile unter dem Lesebereich im Enterprise AI Client ({@code ReaderToolbar}): links die Lupe, mittig die
 * Umschalter des Bereichs ({@link #tools}, etwa die Seitenreiter als Symbolknöpfe). Klick auf die Lupe oder Strg+F
 * klappt die Suche nach rechts auf und überdeckt die Umschalter: Feld, „aktueller Treffer / Gesamtzahl“, vorheriger,
 * nächster Treffer. Enter springt zum nächsten Treffer (bei neuem Text: neue Suche), Shift+Enter zum vorigen, Esc
 * entfernt die Markierungen und klappt die Suche zu, ein zweiter Klick auf die Lupe ebenso. Gesucht und markiert wird
 * mit {@link ReaderFind} unterhalb der Wurzel, die {@code area} gerade liefert.
 */
public final class FindBar extends com.aresstack.spaceharvester.app.ui.controls.FootBar {

    static final String PLACEHOLDER = "In dieser Ansicht suchen … (Strg+F)";
    private static final int BUTTON_SIZE = 22;
    /** Tooltip der Lupe. */
    static final String FIND_TOOLTIP = "In dieser Ansicht suchen (Strg+F)";

    private final Supplier<JComponent> area;
    private final PlaceholderField field = new PlaceholderField(24, PLACEHOLDER);
    private final JLabel counter = Comic.muted("");
    private final ComposerButton previousButton;
    private final ComposerButton nextButton;
    private ReaderFind find;
    /** Der Text, nach dem zuletzt gesucht wurde; {@code null}, solange nichts markiert ist. */
    private String searched;
    /** Die Wurzel, in der {@link #find} sucht. */
    private JComponent findRoot;
    /** Die Lupe links; aufgeklappt hervorgehoben. */
    private final ComposerButton findButton;
    /** Feld mit Zähler und Treffer-Knöpfen; nur aufgeklappt sichtbar. */
    private final JComponent search;
    /** Platz für die Umschalter des Bereichs, mittig. */
    /**
     * Die Werkzeuge des Bereichs neben der Lupe. Was hier steht, bekommt die ganze Breite, aber nur seine eigene Höhe
     * und steht senkrecht mittig, auf der Höhe der Lupe. Eine Zeile, die sich nicht selbst mittig stellt (etwa ein
     * FlowLayout), säße sonst an der Oberkante und damit höher als die Lupe (Angelo 22.11.2026, zum zweiten Mal).
     */
    private final JPanel tools = new JPanel(new MiddleLayout());

    /** Volle Breite, eigene Höhe, senkrecht mittig. */
    static final class MiddleLayout extends BorderLayout {

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            int width = Math.max(0, parent.getWidth() - in.left - in.right);
            int height = Math.max(0, parent.getHeight() - in.top - in.bottom);
            for (java.awt.Component child : parent.getComponents()) {
                int own = Math.min(height, child.getPreferredSize().height);
                child.setBounds(in.left, in.top + (height - own) / 2, width, own);
            }
        }
    }
    private boolean open;
    /** Erst nach dem Aufbau fragt die Leiste die Ansicht (der Träger ist dann fertig). */
    private boolean built;
    /**
     * Ecke rechts bei den Werkzeugen (Sprachauswahl des Code-Viewers, Angelo 24.11.2026); wie die Werkzeuge von der
     * aufgeklappten Suche überdeckt.
     */
    private final JPanel corner = new JPanel(new MiddleLayout());
    /**
     * Die „?“-Hilfe der gezeigten Ansicht, ganz rechts in der Ecke. Wie die Lupe links gehört sie fest zur Leiste und
     * bleibt auch stehen, solange die Suche aufgeklappt ist (Angelo 27.11.2026).
     */
    private final JPanel help = new JPanel(new MiddleLayout());
    /**
     * Umschalter „Hervorheben | Zeilen falten“ im Feld, links vor ▲▼ (Angelo 24.11.2026); nur sichtbar, wenn die
     * Ansicht selbst sucht ({@link Own}).
     */
    private final IconButton foldToggle = new IconButton(null, Own.FOLD_TOOLTIP);
    /** Die Ansicht, die gerade selbst sucht; {@code null}, solange {@link ReaderFind} sucht. */
    private Own own;
    /** Tooltip des Umschalters im Feld, solange die gezeigte Ansicht ein eigenes Filterfeld hat. */
    static final String FILTER_TOOLTIP = "Filtern statt suchen: die Ansicht zeigt nur noch, was zum Text passt";
    static final String FILTER_PLACEHOLDER = "In dieser Ansicht filtern …";
    /**
     * Ob das Feld filtert statt sucht (Angelo 28.11.2026: abwechselnd, nie beides). Der Text geht dann in das
     * Filterfeld der gezeigten Ansicht, das selbst nicht zu sehen ist ({@link
     * com.aresstack.spaceharvester.app.ui.controls.InputFrame#closed}); markiert wird nichts.
     */
    private boolean filtering;
    /** Das Filterfeld, in dem der Text dieser Leiste gerade steht; {@code null}, solange nicht gefiltert wird. */
    private javax.swing.text.JTextComponent filtered;

    /**
     * Eine Ansicht, die mit dieser Leiste selbst sucht, statt dass {@link ReaderFind} nur hervorhebt (Code-Viewer:
     * markieren und auf Wunsch alle Zeilen ohne Treffer zufalten). Gefunden wird die sichtbare Komponente unter der
     * Wurzel, die {@link #ownsFind()} meldet.
     */
    public interface Own {
        String FOLD_TOOLTIP = "Zeilen filtern: alle Zeilen ohne Treffer zuklappen";

        /** Ob die Ansicht gerade selbst sucht. */
        boolean ownsFind();

        /** Sucht {@code query} neu (leer: Markierungen weg). */
        void find(String query);

        /** Springt zum nächsten ({@code 1}) oder vorigen ({@code -1}) Treffer. */
        void findStep(int direction);

        /** „aktueller / Gesamtzahl“ oder Hinweis der Ansicht. */
        String findCounter();

        /** Ob Zeilen ohne Treffer zugefaltet werden. */
        boolean folding();

        void folding(boolean on);

        /** Das Symbol des Umschalters. */
        javax.swing.Icon foldIcon();

        /** Ob die Ansicht Zeilen ohne Treffer zufalten kann; ohne das zeigt das Feld keinen Falt-Umschalter. */
        default boolean canFold() {
            return true;
        }

        /** Was rechts in der Ecke der Leiste steht (Sprachauswahl des Code-Viewers); {@code null} ohne. */
        JComponent findCorner();
    }

    /** Schlüssel an der Komponente, die diese Leiste trägt: {@link Runnable}, das {@link #viewChanged()} ruft. */
    public static final String VIEW_HOST = "findBar.viewHost";

    /**
     * Meldet der Leiste über {@code source}, dass eine selbst suchende Ansicht ({@link Own}) gezeigt oder verdeckt
     * wurde, damit Umschalter, Ecke und Treffer zur sichtbaren Ansicht passen.
     */
    public static void viewChanged(java.awt.Component source) {
        for (java.awt.Container up = source.getParent(); up != null; up = up.getParent()) {
            if (up instanceof JComponent host && host.getClientProperty(VIEW_HOST) instanceof Runnable run) {
                javax.swing.SwingUtilities.invokeLater(run);
                return;
            }
        }
    }

    /** Eine andere Ansicht ist zu sehen: alte Markierungen weg, mit stehendem Text in der neuen weitersuchen. */
    public void viewChanged() {
        JComponent root = area.get();
        Own shown = root == null ? null : owner(root);
        if (shown != own || (filtering && target() != filtered)) {
            areaChanged();
        } else {
            showOwn();
        }
    }

    public FindBar(Supplier<JComponent> area) {
        super(null);
        setLayout(new RowLayout());
        this.area = area;
        setOpaque(false);
        line(true);
        field.getAccessibleContext().setAccessibleName("In dieser Ansicht suchen");
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                live();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                live();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                live();
            }
        });
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.next", this::next);
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "find.previous",
                this::previous);
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.close", this::close);
        previousButton = small(ComposerIcons.chevronUp(), "Vorheriger Treffer (Shift+Enter)");
        previousButton.addActionListener(event -> previous());
        nextButton = small(ComposerIcons.chevronDown(), "Nächster Treffer (Enter)");
        nextButton.addActionListener(event -> next());
        foldToggle.getAccessibleContext().setAccessibleName("Zeilen filtern");
        foldToggle.setVisible(false);
        foldToggle.addActionListener(event -> {
            if (own != null) {
                own.folding(!own.folding());
                foldToggle.marked(own.folding());
                updateCounter();
            } else if (target() != null) {
                filtering(!filtering);
            }
        });
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        controls.setOpaque(false);
        controls.add(counter);
        controls.add(foldToggle);
        controls.add(previousButton);
        controls.add(nextButton);
        // Ein Feld mit allem darin (AskAI-ComicFindBar): Text, ✕ bei Text, Zähler, vorheriger/nächster Treffer; die
        // Lupe steht als Knopf links davor.
        com.aresstack.spaceharvester.app.ui.controls.InputFrame frame =
                new com.aresstack.spaceharvester.app.ui.controls.InputFrame(field);
        frame.addClearButton();
        frame.addButton(controls);
        search = frame;
        findButton = ComposerButton.iconButton(ComposerIcons.magnifier(), FIND_TOOLTIP);
        findButton.getAccessibleContext().setAccessibleName("In dieser Ansicht suchen");
        findButton.addActionListener(event -> {
            if (open) {
                close();
            } else {
                focus();
            }
        });
        tools.setOpaque(false);
        corner.setOpaque(false);
        add(findButton);
        add(tools);
        add(search);
        add(corner);
        help.setOpaque(false);
        add(help);
        expand(false);
        updateCounter();
        built = true;
    }

    /** Strg+F oder Klick auf die Lupe: Suche aufklappen, Fokus ins Feld, Text markiert. */
    public void focus() {
        if (!open) {
            expand(true);
            if (!field.getText().trim().isEmpty()) {
                step(true);
            }
        }
        field.requestFocusInWindow();
        field.selectAll();
    }

    /** Esc oder zweiter Klick auf die Lupe: Markierungen weg, Suche zuklappen; der Suchtext bleibt stehen. */
    public void close() {
        clear();
        expand(false);
    }

    /**
     * Der Trenner über der Leiste; ohne ihn, solange darüber eine Zeitleiste steht ({@code TimelineEvent}, Podcast).
     */
    void line(boolean shown) {
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(shown ? 1 : 0, 0, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(shown ? 4 : 5, 16, 4, 16)));
    }

    /** Setzt die Umschalter des gezeigten Bereichs mittig in die Leiste; {@code null} lässt die Mitte leer. */
    public void tools(JComponent component) {
        tools.removeAll();
        if (component != null) {
            tools.add(component, BorderLayout.CENTER);
        }
        tools.revalidate();
        tools.repaint();
        revalidate();
        repaint();
    }

    /** Setzt die „?“-Hilfe ganz rechts; {@code null} lässt den Platz leer. */
    public void help(JComponent component) {
        if (component != null && component.getParent() == help) {
            return;
        }
        help.removeAll();
        if (component != null) {
            help.add(component, BorderLayout.CENTER);
        }
        help.revalidate();
        revalidate();
        repaint();
    }

    /** Für Tests: der Platz der Hilfe ganz rechts. */
    public JComponent helpArea() {
        return help;
    }

    /** Setzt die Ecke rechts; {@code null} lässt sie leer. */
    void corner(JComponent component) {
        if (component != null && component.getParent() == corner) {
            return;
        }
        corner.removeAll();
        if (component != null) {
            corner.add(component, BorderLayout.CENTER);
        }
        corner.revalidate();
        revalidate();
        repaint();
    }

    /** Für Tests: die Ecke rechts. */
    public JComponent cornerArea() {
        return corner;
    }

    /** Für Tests: der Falt-Umschalter im Feld. */
    public IconButton foldToggle() {
        return foldToggle;
    }

    /** Ob die Suche aufgeklappt ist. */
    public boolean isOpen() {
        return open;
    }

    ComposerButton findButton() {
        return findButton;
    }

    JComponent toolsArea() {
        return tools;
    }

    JComponent searchArea() {
        return search;
    }

    /** Enter: neuer Text sucht neu, derselbe Text springt zum nächsten Treffer. */
    public void next() {
        step(true, true);
    }

    /** Shift+Enter: neuer Text sucht neu, derselbe Text springt zum vorigen Treffer. */
    void previous() {
        step(false, true);
    }

    /**
     * Schaltet zwischen Suchen und Filtern um; wirkt nur, wo die gezeigte Ansicht ein Filterfeld hat. Der Text im
     * Feld gilt sofort in der neuen Betriebsart, die alte räumt hinter sich auf.
     */
    public void filtering(boolean on) {
        filtering = on;
        clear();
        if (open && !field.getText().trim().isEmpty()) {
            step(true);
        }
        showOwn();
    }

    /** Ob gerade gefiltert statt gesucht wird. */
    public boolean filtering() {
        return filtering && target() != null;
    }

    /** Das unsichtbare Filterfeld der gezeigten Ansicht; {@code null}, wenn sie keines hat. */
    private javax.swing.text.JTextComponent target() {
        JComponent root = area.get();
        com.aresstack.spaceharvester.app.ui.controls.InputFrame frame = root == null ? null
                : com.aresstack.spaceharvester.app.ui.controls.InputFrame.closed(root);
        return frame != null && frame.field() instanceof javax.swing.text.JTextComponent text ? text : null;
    }

    private static com.aresstack.spaceharvester.app.ui.controls.InputFrame frameOf(
            javax.swing.text.JTextComponent text) {
        return (com.aresstack.spaceharvester.app.ui.controls.InputFrame) javax.swing.SwingUtilities
                .getAncestorOfClass(com.aresstack.spaceharvester.app.ui.controls.InputFrame.class, text);
    }

    private boolean mirroring;

    /** Übernimmt Text, den die Ansicht selbst in ihr Filterfeld setzt, in das Feld dieser Leiste. */
    private final DocumentListener mirror = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent event) {
            take();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            take();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
        }

        private void take() {
            javax.swing.text.JTextComponent source = filtered;
            // Erst nach dem laufenden Ereignis: Ein Dokument lässt sich nicht aus seiner eigenen Meldung ändern.
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (source != null && source == filtered && !source.getText().equals(field.getText())) {
                    // Nur übernehmen, nicht zurückschreiben: Sonst schriebe das Feld den Text noch einmal in das
                    // Filterfeld, und die Ansicht hielte das für Tippen (Vorschläge gingen wieder auf).
                    mirroring = true;
                    try {
                        field.setText(source.getText());
                    } finally {
                        mirroring = false;
                    }
                }
            });
        }
    };

    /** Nimmt den Text aus dem Filterfeld der Ansicht, damit kein unsichtbarer Filter weiterwirkt. */
    private void release() {
        if (filtered != null) {
            filtered.getDocument().removeDocumentListener(mirror);
            frameOf(filtered).standIn(null);
            if (!filtered.getText().isEmpty()) {
                filtered.setText("");
            }
            filtered = null;
        }
    }

    /** Entfernt Markierungen und Filter (Esc, Wechsel des Bereichs); der Text im Feld bleibt stehen. */
    void clear() {
        release();
        unmark();
    }

    private void unmark() {
        if (find != null) {
            find.clear();
        }
        if (own != null) {
            own.find("");
        }
        own = null;
        find = null;
        searched = null;
        updateCounter();
    }

    /** Der Bereich wurde gewechselt: alte Markierungen weg, mit stehendem Text im neuen Bereich neu suchen. */
    public void areaChanged() {
        clear();
        if (open && !field.getText().trim().isEmpty()) {
            step(true);
        }
        showOwn();
    }

    /** Sucht {@code query}, als hätte man es eingetippt und Enter gedrückt; liefert die Zahl der Treffer. */
    public int search(String query) {
        expand(true);
        field.setText(query == null ? "" : query);
        return own != null ? 1 : find == null ? 0 : find.hitCount();
    }

    /** Der Suchtext im Feld. */
    public String query() {
        return field.getText();
    }

    public String counterText() {
        return counter.getText();
    }

    PlaceholderField field() {
        return field;
    }

    ReaderFind find() {
        return find;
    }

    // ------------------------------------------------------------------ intern

    /** Aufgeklappt: Feld statt Umschalter, Lupe hervorgehoben; zugeklappt umgekehrt. */
    private void expand(boolean searchOpen) {
        open = searchOpen;
        search.setVisible(searchOpen);
        tools.setVisible(!searchOpen);
        corner.setVisible(!searchOpen);
        findButton.setEmphasized(searchOpen);
        showOwn();
        revalidate();
        repaint();
    }

    /** Wie im Browser: Tippen sucht sofort. */
    private void live() {
        if (mirroring) {
            return;
        }
        searched = null;
        step(true);
    }

    private void step(boolean forward) {
        step(forward, false);
    }

    /** @param entered ob Enter gedrückt wurde (ein Filterfeld, das erst auf Enter handelt, bekommt es weiter) */
    private void step(boolean forward, boolean entered) {
        JComponent root = area.get();
        if (root == null) {
            clear();
            showOwn();
            return;
        }
        javax.swing.text.JTextComponent target = filtering ? target() : null;
        if (target != filtered) {
            release();
        }
        if (target != null) {
            // Filtern statt suchen: Der Text steht im Filterfeld der Ansicht, sie schränkt sich selbst ein.
            unmark();
            if (filtered != target) {
                // Was die Ansicht selbst ins Filterfeld setzt (ein gewählter Vorschlag), steht auch hier im Feld;
                // Vorschläge der Ansicht erscheinen an diesem Feld.
                target.getDocument().addDocumentListener(mirror);
                frameOf(target).standIn(field);
            }
            filtered = target;
            if (!field.getText().equals(target.getText())) {
                target.setText(field.getText());
            }
            if (entered && target instanceof javax.swing.JTextField line) {
                line.postActionEvent();
            }
            updateCounter();
            return;
        }
        Own owner = owner(root);
        if (owner != own) {
            clear();
            own = owner;
            showOwn();
        }
        if (own != null) {
            // Die Ansicht sucht selbst (Hervorheben oder Zeilen falten); ReaderFind bleibt aus dem Spiel.
            String query = field.getText();
            if (query.trim().isEmpty()) {
                own.find("");
                searched = null;
            } else if (query.equals(searched)) {
                own.findStep(forward ? 1 : -1);
            } else {
                own.find(query);
                searched = query;
            }
            updateCounter();
            return;
        }
        if (find != null && root != findRoot) {
            find.clear();
            find = null;
            searched = null;
        }
        if (find == null) {
            find = new ReaderFind(root);
            findRoot = root;
        }
        if (find.stale()) {
            // Inhalt hat sich geändert (anderer Reiter, neu geladenes Dokument): gemerkte Stellen verwerfen.
            find.clear();
            searched = null;
        }
        String query = field.getText();
        if (query.trim().isEmpty()) {
            find.clear();
            searched = null;
        } else if (query.equals(searched) && find.hitCount() > 0) {
            if (forward) {
                find.next();
            } else {
                find.previous();
            }
        } else {
            find.search(query);
            searched = query;
        }
        updateCounter();
    }

    /** Die sichtbare Ansicht unter {@code root}, die selbst sucht, sonst {@code null}. */
    static Own owner(java.awt.Component root) {
        if (!root.isVisible()) {
            return null; // auch verdeckte Karten eines CardLayout
        }
        if (root instanceof Own candidate && candidate.ownsFind()) {
            return candidate;
        }
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                Own found = owner(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Falt-Umschalter im Feld und Ecke rechts nur, wenn die gezeigte Ansicht selbst sucht. */
    private void showOwn() {
        if (!built) {
            return;
        }
        JComponent root = area.get();
        Own shown = own != null ? own : root == null ? null : owner(root);
        boolean folds = shown != null && shown.canFold();
        boolean filters = !folds && target() != null;
        foldToggle.setVisible(folds || filters);
        if (folds) {
            foldToggle.setIcon(shown.foldIcon());
            foldToggle.marked(shown.folding());
            foldToggle.setToolTipText(Own.FOLD_TOOLTIP);
        } else if (filters) {
            foldToggle.setIcon(Glyph.FILTER);
            foldToggle.marked(filtering);
            foldToggle.setToolTipText(FILTER_TOOLTIP);
        }
        field.setPlaceholder(filters && filtering ? FILTER_PLACEHOLDER : PLACEHOLDER);
        boolean steps = !(filters && filtering);
        previousButton.setVisible(steps);
        nextButton.setVisible(steps);
        corner(shown == null ? null : shown.findCorner());
    }

    private void updateCounter() {
        if (own != null) {
            boolean active = searched != null;
            counter.setText(active ? own.findCounter() : "");
            previousButton.setEnabled(active);
            nextButton.setEnabled(active);
            return;
        }
        boolean active = searched != null && find != null;
        counter.setText(!active ? "" : find.hitCount() == 0 ? "Keine Treffer"
                : (find.currentIndex() + 1) + " / " + find.hitCount());
        previousButton.setEnabled(active && find.hitCount() > 0);
        nextButton.setEnabled(active && find.hitCount() > 0);
    }

    /** Ob die gesetzten Werkzeuge die Zeile rechts der Lupe füllen wollen ({@link View#TOOLS_FILL}). */
    private boolean fills() {
        return tools.getComponentCount() > 0 && tools.getComponent(0) instanceof JComponent component
                && Boolean.TRUE.equals(component.getClientProperty(View.TOOLS_FILL));
    }

    private static ComposerButton small(javax.swing.Icon icon, String tooltip) {
        ComposerButton button = ComposerButton.iconButton(icon, tooltip);
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        return button;
    }

    /**
     * Lupe links in voller Zeilenhöhe; die Umschalter mittig über die ganze Zeile (nie unter die Lupe); die offene
     * Suche füllt alles rechts der Lupe.
     */
    private final class RowLayout implements java.awt.LayoutManager {
        @Override
        public void addLayoutComponent(String name, java.awt.Component comp) {
        }

        @Override
        public void removeLayoutComponent(java.awt.Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            Dimension button = findButton.getPreferredSize();
            Dimension middle = tools.getPreferredSize();
            Dimension field = search.getPreferredSize();
            int width = button.width + GAP + Math.max(middle.width + cornerWidth(), field.width) + helpWidth();
            // Die Höhe legt allein die Basisklasse fest (FootBar), nicht dieses Layout.
            return new Dimension(width + insets.left + insets.right, 0);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            Dimension preferred = preferredLayoutSize(parent);
            return new Dimension(findButton.getPreferredSize().width + insets.left + insets.right, preferred.height);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int left = insets.left;
            int top = insets.top;
            int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
            int cornerWidth = cornerWidth();
            int helpWidth = helpWidth();
            help.setBounds(parent.getWidth() - insets.right - Math.max(0, helpWidth - GAP), top,
                    Math.max(0, helpWidth - GAP), height);
            int outer = parent.getWidth() - insets.right - helpWidth;
            corner.setBounds(Math.max(left, outer - Math.max(0, cornerWidth - GAP)), top,
                    Math.max(0, cornerWidth - GAP), height);
            int right = outer - cornerWidth;
            Dimension button = findButton.getPreferredSize();
            findButton.setBounds(left, top + (height - button.height) / 2, button.width, button.height);
            int start = left + button.width + GAP;
            Dimension field = search.getPreferredSize();
            int fieldHeight = Math.min(height, field.height);
            search.setBounds(start, top + (height - fieldHeight) / 2, Math.max(0, right - start), fieldHeight);
            if (fills()) {
                tools.setBounds(start, top, Math.max(0, right - start), height);
                return;
            }
            int middle = Math.min(tools.getPreferredSize().width, Math.max(0, right - start));
            int x = Math.max(start, Math.min((parent.getWidth() - middle) / 2, right - middle));
            tools.setBounds(x, top, middle, height);
        }
    }

    /** Platz der Hilfe samt Abstand; ohne sichtbare Hilfe keiner. */
    private int helpWidth() {
        int width = help.getComponentCount() == 0 || !help.getComponent(0).isVisible() ? 0
                : help.getComponent(0).getPreferredSize().width;
        return width <= 0 ? 0 : width + GAP;
    }

    private static final int GAP = 6;

    /** Platz der Ecke samt Abstand; ohne Inhalt keiner. */
    private int cornerWidth() {
        return open || corner.getComponentCount() == 0 ? 0 : corner.getPreferredSize().width + 12;
    }

    private static void bind(JComponent component, KeyStroke key, String name, Runnable action) {
        component.getInputMap(WHEN_FOCUSED).put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
