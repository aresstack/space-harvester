package com.aresstack.spaceharvester.deck.terminal.openterm;

import com.aresstack.spaceharvester.deck.terminal.AidKey;
import com.aresstack.spaceharvester.deck.terminal.TerminalKeyboard;
import com.aresstack.spaceharvester.deck.terminal.TerminalModel;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import com.ascert.open.ohio.Ohio;
import com.ascert.open.term.core.Terminal;
import com.ascert.open.term.gui.JTerminalScreen;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;

/**
 * Die TN3270-Anzeige von OpenTerm mit den Ergänzungen aus MainframeMate: Pfeiltasten bewegen die Schreibmarke,
 * Strg+C/Strg+V kopieren und fügen ein, Rechtsklick zeigt ein Menü, die Schrift wächst mit der Fläche. F-Tasten,
 * Eingabe und Tabulator verarbeitet OpenTerm selbst. Die Rückmeldungen des Terminals ({@code status}) reicht sie an
 * die Verbindung weiter.
 */
final class OpenTermScreen extends JTerminalScreen implements TerminalView, TerminalKeyboard {

    /** Zeilen der Statuszeile unter dem 3270-Bild. */
    static final int STATUS_ROWS = 2;

    private final transient Terminal terminal;
    private final TerminalModel model;
    private final transient java.util.List<Runnable> screenListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Bereit = Tastatur frei UND das Bild danach fertig gezeichnet (Felder aufgebaut). */
    private final transient java.util.concurrent.locks.ReentrantLock readyLock = new java.util.concurrent.locks.ReentrantLock();
    private final transient java.util.concurrent.locks.Condition readyChanged = readyLock.newCondition();
    private boolean ready;
    private volatile boolean hostWrote;
    /** Letzte Taste des Menschen (nanoTime); die automatische Anmeldung tippt danach nicht weiter. */
    private volatile long lastUserInput = Long.MIN_VALUE;
    private volatile long lastRefresh = System.nanoTime();
    /** So lange ohne neues Zeichnen gilt ein Bild als fertig gelesen. */
    private static final long SETTLE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);
    private final transient IntConsumer statusListener;
    /** Von der KI ausgefüllte Felder, gelb umrandet bis zum Absenden oder Zurücknehmen (#1151). */
    private transient volatile java.util.List<Span> highlighted = java.util.List.of();
    /** Wer die Eingaben des Menschen aufzeichnet (Makro); nie für Anmeldung oder Abspielen. */
    private final transient java.util.List<InputListener> inputListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Ob die Taste, deren Zeichen gleich kommt, in einem verdeckten Feld (Passwort) gedrückt wurde. */
    private boolean hiddenAtPress;

    OpenTermScreen(Terminal terminal, TerminalModel model, IntConsumer statusListener) {
        super(terminal, hiddenToolbar());
        this.terminal = terminal;
        // Erst ein fertiges Bild vom Host macht die Tastatur wirklich bereit: OpenTerm gibt die Tastatur schon beim
        // Lesen des Kopfs frei, die Eingabefelder stehen aber erst am Ende (refresh). Vorher verwirft es Tasten still.
        terminal.addKbdLockListener(changed -> {
            if (changed.isKeyboardLocked()) {
                setReady(false);
            } else {
                hostWrote = true; // nur ein Bild des Hosts gibt die Tastatur frei
            }
        });
        this.model = model;
        this.statusListener = statusListener;
        // OpenTerm füllt nur sein Bild, nicht die ganze Fläche: durchsichtig malt der Hintergrund (Sternenhimmel)
        // den Rest, ohne Geisterbilder.
        setOpaque(false);
        setFocusable(true);
        setRequestFocusEnabled(true);
        // Tabulator gehört dem Host (nächstes Eingabefeld), nicht dem Fokuswechsel von Swing.
        setFocusTraversalKeysEnabled(false);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                int code = event.getKeyCode();
                if (code != KeyEvent.VK_SHIFT && code != KeyEvent.VK_CONTROL && code != KeyEvent.VK_ALT
                        && code != KeyEvent.VK_ALT_GRAPH && code != KeyEvent.VK_META
                        && !(event.isControlDown() && code == KeyEvent.VK_C)) {
                    lastUserInput = System.nanoTime();
                }
                if (event.isControlDown() && !event.isAltDown() && event.getKeyCode() == KeyEvent.VK_V) {
                    paste();
                    event.consume();
                } else if (event.isControlDown() && !event.isAltDown() && event.getKeyCode() == KeyEvent.VK_C) {
                    copy();
                    event.consume();
                } else if (moveCursor(event.getKeyCode())) {
                    refresh();
                    event.consume();
                } else {
                    // Aufzeichnung wie in MainframeMate: F-Tasten und Enter als AID (OpenTerm schickt sie selbst).
                    hiddenAtPress = hiddenAt(terminal.getCursorPosition());
                    int number = functionKey(event);
                    if (!terminal.isKeyboardLocked()) {
                        if (number > 0) {
                            fireAid(AidKey.pf(number));
                        } else if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                            fireAid(AidKey.ENTER);
                        } else if (event.getKeyCode() == KeyEvent.VK_TAB && !event.isShiftDown()) {
                            for (InputListener listener : inputListeners) {
                                listener.tab();
                            }
                        }
                    }
                }
            }

            @Override
            public void keyTyped(KeyEvent event) {
                char c = event.getKeyChar();
                if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)
                        && !(event.isControlDown() && !event.isAltDown())
                        && !hiddenAtPress) {
                    fireTyped(String.valueOf(c));
                }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                // Klick auf einen Menüpunkt wählt ihn aus (autoEnterIfMenuItem in MainframeMate); kurz warten, bis
                // OpenTerm die Schreibmarke an die Klickstelle gesetzt hat.
                if (event.getButton() != MouseEvent.BUTTON1 || event.getClickCount() != 1) {
                    return;
                }
                javax.swing.Timer autoEnter = new javax.swing.Timer(50, e -> autoEnterIfMenuItem());
                autoEnter.setRepeats(false);
                autoEnter.start();
            }
        });
        getAccessibleContext().setAccessibleName("TN3270-Terminal");
    }

    /** OpenTerm verlangt eine Werkzeugleiste; ihre Knöpfe ersetzt das Command Deck, deshalb bleibt sie unsichtbar. */
    private static JToolBar hiddenToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setVisible(false);
        toolbar.setPreferredSize(new Dimension(0, 0));
        toolbar.setMaximumSize(new Dimension(0, 0));
        return toolbar;
    }

    @Override
    public void status(int status) {
        super.status(status);
        statusListener.accept(status);
    }

    /** Pfeiltasten bewegen die Schreibmarke und laufen am Rand um, wie an einem echten 3270. */
    boolean moveCursor(int keyCode) {
        int columns = terminal.getCols();
        int rows = terminal.getRows();
        if (columns <= 0 || rows <= 0) {
            return false;
        }
        int cells = rows * columns;
        int position = terminal.getCursorPosition();
        int next = switch (keyCode) {
            case KeyEvent.VK_LEFT -> (position - 1 + cells) % cells;
            case KeyEvent.VK_RIGHT -> (position + 1) % cells;
            case KeyEvent.VK_UP -> (position - columns + cells) % cells;
            case KeyEvent.VK_DOWN -> (position + columns) % cells;
            default -> -1;
        };
        if (next < 0) {
            return false;
        }
        terminal.setCursorPosition((short) next);
        return true;
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        // Reihenfolge und Tastenkürzel wie im Kontextmenü von MainframeMate.
        JMenuItem paste = new JMenuItem("Einfügen");
        paste.setAccelerator(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        paste.setEnabled(!terminal.isKeyboardLocked());
        paste.addActionListener(e -> {
            paste();
            requestFocusInWindow();
        });
        JMenuItem copy = new JMenuItem("Kopieren");
        copy.setAccelerator(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        copy.addActionListener(e -> {
            copy();
            requestFocusInWindow();
        });
        JMenuItem all = new JMenuItem("Alles auswählen");
        all.setAccelerator(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_A, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        all.addActionListener(e -> {
            selectAll();
            requestFocusInWindow();
        });
        menu.add(paste);
        menu.add(copy);
        menu.addSeparator();
        menu.add(all);
        menu.show(this, event.getX(), event.getY());
    }

    @Override
    public void copy() {
        String text = selectedText();
        if (!text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }

    /** Die Auswahl, ohne Auswahl das ganze Bild zeilenweise. */
    String selectedText() {
        int start = getSelectionStartPos();
        int end = getSelectionEndPos();
        if (start >= 0 && end >= 0 && start != end) {
            int from = Math.min(start, end);
            return terminal.getCharString(from, Math.max(start, end) - from + 1);
        }
        return lines(terminal.getDisplay(), terminal.getCols());
    }

    static String lines(char[] display, int columns) {
        if (display == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(display.length + display.length / Math.max(1, columns));
        for (int i = 0; i < display.length; i++) {
            text.append(display[i] == 0 ? ' ' : display[i]);
            if (columns > 0 && (i + 1) % columns == 0 && i + 1 < display.length) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void paste() {
        if (terminal.isKeyboardLocked()) {
            return;
        }
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return;
            }
            String text = flatten((String) clipboard.getData(DataFlavor.stringFlavor));
            boolean hidden = hiddenAt(terminal.getCursorPosition());
            var input = terminal.getCharHandler();
            for (int i = 0; i < text.length(); i++) {
                input.type(text.charAt(i));
            }
            if (!hidden) {
                fireTyped(text);
            }
            refresh();
        } catch (Exception e) {
            // Zwischenablage gerade belegt oder kein Text: nichts einfügen.
        }
    }

    /** 3270-Felder sind einzeilig: Umbrüche und Tabulatoren werden Leerzeichen. */
    static String flatten(String text) {
        return text == null ? "" : text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    /** F1–F24 einer echten Taste, sonst 0 (fkeyNumberFromEvent in MainframeMate). */
    static int functionKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) {
            return code - KeyEvent.VK_F1 + 1;
        }
        if (code >= KeyEvent.VK_F13 && code <= KeyEvent.VK_F24) {
            return code - KeyEvent.VK_F13 + 13;
        }
        return 0;
    }

    /** Ob an dieser Stelle ein verdecktes Feld (Passwort) steht; im Zweifel ja, dann wird nichts aufgezeichnet. */
    boolean hiddenAt(int position) {
        try {
            com.ascert.open.term.core.TermField field = terminal.getField(position);
            if (field == null) {
                return false;
            }
            com.ascert.open.term.core.TermChar attribute = field.getFAChar();
            return attribute != null && attribute.isHidden();
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    private void fireTyped(String text) {
        for (InputListener listener : inputListeners) {
            listener.typed(text);
        }
    }

    private void fireAid(AidKey key) {
        for (InputListener listener : inputListeners) {
            listener.aid(key);
        }
    }

    @Override
    public void addInputListener(InputListener listener) {
        inputListeners.add(listener);
    }

    @Override
    public void removeInputListener(InputListener listener) {
        inputListeners.remove(listener);
    }

    // ── Klick auf Menüpunkt (autoEnterIfMenuItem aus MainframeMate) ─────────────

    /**
     * Steht die Schreibmarke nach einem Klick auf einem auswählbaren Menüpunkt – einem <b>geschützten</b> Feld mit
     * sichtbarem Text –, schickt sie ENTER. Zeilen 0–1 (Aktionsleiste) immer; darunter, wenn das Feld wie ein
     * CUA-Menüpunkt hervorgehoben ist (invers, hell, unterstrichen) oder die Zeile wie ein umrahmter Pulldown-Eintrag
     * mit Nummer aussieht ({@code | 3  Save |}); die untersten 3 Zeilen (Befehl, Meldung, PF-Legende) nie.
     *
     * @return ob ENTER geschickt wurde
     */
    boolean autoEnterIfMenuItem() {
        if (terminal.isKeyboardLocked()) {
            return false;
        }
        int columns = terminal.getCols();
        int rows = terminal.getRows();
        if (columns <= 0 || rows <= 0) {
            return false;
        }
        int cursor = terminal.getCursorPosition();
        int row = cursor / columns;
        if (row >= rows - 3) {
            return false;
        }
        try {
            com.ascert.open.term.core.TermField field = terminal.getField(cursor);
            if (field == null || !field.isProtected()) {
                return false;
            }
            int begin = field.getBeginBA() + 1; // Attributbyte überspringen
            int length = field.getEndBA() - begin + 1;
            if (length <= 0 || length > columns) {
                return false;
            }
            String text = terminal.getCharString(begin, length);
            if (text == null || text.isBlank()) {
                return false;
            }
            if (row <= 1 || hasMenuStyle(field) || looksLikePulldownLine(terminal.getCharString(row * columns,
                    columns))) {
                terminal.Fkey(aid(AidKey.ENTER));
                fireAid(AidKey.ENTER);
                return true;
            }
        } catch (RuntimeException unreadable) {
            // Feld nicht lesbar: nichts schicken
        }
        return false;
    }

    /** Eine Zeile wie {@code | 3  Save |}: mindestens zwei Rahmenzeichen, dazwischen zuerst eine Ziffer. */
    static boolean looksLikePulldownLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < line.length(); i++) {
            if (isBorder(line.charAt(i))) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0 || last - first < 2) {
            return false;
        }
        String between = line.substring(first + 1, last).trim();
        return !between.isEmpty() && Character.isDigit(between.charAt(0));
    }

    static boolean isBorder(char c) {
        return c == '|' || c == '│' || c == '┃' || c == '║' || c == '¦';
    }

    /** Invers, hell oder unterstrichen wie ein CUA-Menüpunkt (fieldHasMenuStyle in MainframeMate). */
    private static boolean hasMenuStyle(com.ascert.open.term.core.TermField field) {
        com.ascert.open.term.core.TermChar[] chars = field.getChars();
        if (chars != null) {
            for (com.ascert.open.term.core.TermChar c : chars) {
                if (c == null || c.isStartField()) {
                    continue;
                }
                return c.isReverse() || c.isAltIntensity() || c.isUnderscore();
            }
        }
        com.ascert.open.term.core.TermChar attribute = field.getFAChar();
        return attribute != null && (attribute.isReverse() || attribute.isAltIntensity());
    }

    @Override
    public void fit(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int columns = model.columns();
        // Unter dem Bild zeichnet OpenTerm noch die Statuszeile (Trennlinie und Anzeigen): zwei Zeilen mehr.
        int rows = model.rows() + STATUS_ROWS;
        String family = getFont() == null ? Font.MONOSPACED : getFont().getFamily();
        int best = 8;
        for (int size = 8; size <= 40; size++) {
            FontMetrics metrics = getFontMetrics(new Font(family, Font.PLAIN, size));
            int needWidth = metrics.charWidth('M') * columns + 2 * MARGIN_X;
            int needHeight = metrics.getHeight() * rows + 2 * MARGIN_Y;
            if (needWidth > width || needHeight > height) {
                break;
            }
            best = size;
        }
        if (getFont() != null && getFont().getSize() == best && isPreferredSizeSet()) {
            return;
        }
        Font font = new Font(family, Font.PLAIN, best);
        setFont(font);
        FontMetrics metrics = getFontMetrics(font);
        setPreferredSize(new Dimension(metrics.charWidth('M') * columns + 2 * MARGIN_X,
                metrics.getHeight() * rows + 2 * MARGIN_Y));
        revalidate();
        repaint();
    }

    @Override
    public String text() {
        return lines(terminal.getDisplay(), terminal.getCols());
    }

    @Override
    public void focus() {
        requestFocusInWindow();
    }

    @Override
    public long lastUserInput() {
        return lastUserInput;
    }

    @Override
    public boolean keyboardLocked() {
        return terminal.isKeyboardLocked();
    }

    @Override
    public boolean awaitUnlocked(java.time.Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        readyLock.lock();
        try {
            while (true) {
                if (ready && !terminal.isKeyboardLocked()) {
                    // Das Bild muss auch zur Ruhe gekommen sein: Während OpenTerm einen Datenstrom liest, zeichnet es
                    // zwischendurch (Schreibmarke), bevor die Felder stehen.
                    long quiet = System.nanoTime() - lastRefresh;
                    if (quiet >= SETTLE_NANOS) {
                        return true;
                    }
                    if (remaining <= 0) {
                        return false;
                    }
                    long wait = Math.min(remaining, SETTLE_NANOS - quiet);
                    remaining -= wait;
                    readyChanged.awaitNanos(wait);
                    continue;
                }
                if (remaining <= 0) {
                    return false;
                }
                remaining = readyChanged.awaitNanos(remaining);
            }
        } finally {
            readyLock.unlock();
        }
    }

    private void setReady(boolean value) {
        readyLock.lock();
        try {
            ready = value;
            readyChanged.signalAll();
        } finally {
            readyLock.unlock();
        }
    }

    @Override
    public void press(AidKey key) {
        if (key == AidKey.CLEAR) {
            // OpenTerm schickt CLEAR erst und löscht danach das Bild. Antwortet der Host schneller, löscht es sein neues
            // Bild mit (dann stehen Felder ohne Zeichen da). Der Parser liest Antworten nur mit seinem eigenen Monitor;
            // ihn halten wir, bis das Löschen durch ist. Kurz und ohne Warten auf den Host, daher hier synchronized.
            synchronized (terminal.getStreamParser()) {
                terminal.Fkey(aid(key));
            }
            return;
        }
        terminal.Fkey(aid(key));
    }

    /** Übersetzung in die OHIO-Tasten von OpenTerm (wie pfKeyToAid in MainframeMate). */
    static Ohio.OHIO_AID aid(AidKey key) {
        return switch (key) {
            case ENTER -> Ohio.OHIO_AID.OHIO_AID_3270_ENTER;
            case CLEAR -> Ohio.OHIO_AID.OHIO_AID_3270_CLEAR;
            case PA1 -> Ohio.OHIO_AID.OHIO_AID_3270_PA1;
            case PA2 -> Ohio.OHIO_AID.OHIO_AID_3270_PA2;
            case PA3 -> Ohio.OHIO_AID.OHIO_AID_3270_PA3;
            case SYSREQ -> Ohio.OHIO_AID.OHIO_AID_3270_SYSREQ;
            default -> Ohio.OHIO_AID.valueOf("OHIO_AID_3270_PF" + key.pfNumber());
        };
    }

    @Override
    public void refresh() {
        super.refresh();
        if (screenListeners == null) {
            return; // Aufruf aus dem Konstruktor der Oberklasse
        }
        lastRefresh = System.nanoTime();
        if (hostWrote && !terminal.isKeyboardLocked()) {
            setReady(true);
        }
        for (Runnable listener : screenListeners) {
            listener.run();
        }
    }

    @Override
    public void addScreenListener(Runnable listener) {
        screenListeners.add(listener);
    }

    @Override
    public void removeScreenListener(Runnable listener) {
        screenListeners.remove(listener);
    }

    @Override
    public boolean firstInputField() {
        short field = terminal.getNextUnprotectedField(0);
        if (field < 0) {
            return false;
        }
        terminal.setCursorPosition(field);
        return true;
    }

    @Override
    public boolean nextInputField() {
        int cursor = terminal.getCursorPosition();
        short first = terminal.getNextUnprotectedField(0);
        short field = terminal.getNextUnprotectedField(cursor);
        // Läuft die Suche zum ersten Feld zurück, gibt es kein weiteres (wie in MainframeMate geprüft).
        if (field < 0 || field == first || field <= cursor - 1 && field == first) {
            return false;
        }
        terminal.setCursorPosition(field);
        return true;
    }

    @Override
    public void type(char[] text) {
        var input = terminal.getCharHandler();
        try {
            for (char c : text) {
                input.type(c);
            }
        } catch (Exception protectedField) {
            throw new IllegalStateException("Das Feld unter dem Cursor ist geschützt", protectedField);
        } finally {
            refresh();
        }
    }

    @Override
    public void highlight(java.util.List<Span> spans) {
        highlighted = spans == null ? java.util.List.of() : java.util.List.copyOf(spans);
        repaint();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        super.paintComponent(g);
        java.util.List<Span> spans = highlighted;
        int columns = terminal.getCols();
        int rows = terminal.getRows();
        if (spans.isEmpty() || columns <= 0 || rows <= 0 || getFont() == null) {
            return;
        }
        FontMetrics metrics = getFontMetrics(getFont());
        int width = metrics.charWidth('M');
        int height = metrics.getHeight();
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new java.awt.BasicStroke(2f));
        g2.setColor(ASSIST);
        int cells = rows * columns;
        for (Span span : spans) {
            // Je Zeile ein Rahmen; ein Feld kann über das Zeilenende laufen.
            int position = span.start();
            int left = span.length();
            while (left > 0) {
                int row = (position % cells) / columns;
                int column = position % columns;
                int run = Math.min(left, columns - column);
                g2.drawRoundRect(MARGIN_X + column * width - 1, MARGIN_Y + row * height, run * width + 2,
                        height, 6, 6);
                left -= run;
                position += run;
            }
        }
        g2.dispose();
    }

    /** Gelb wie die Griffe der App: Hier hat die KI etwas vorbereitet. */
    private static final java.awt.Color ASSIST = new java.awt.Color(0xF2, 0xC2, 0x1B);
}
