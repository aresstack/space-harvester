package com.aresstack.spaceharvester.deck.terminal.openterm;

import com.aresstack.spaceharvester.deck.terminal.TerminalModel;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
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
final class OpenTermScreen extends JTerminalScreen implements TerminalView {

    /** Zeilen der Statuszeile unter dem 3270-Bild. */
    static final int STATUS_ROWS = 2;

    private final transient Terminal terminal;
    private final TerminalModel model;
    private final transient IntConsumer statusListener;

    OpenTermScreen(Terminal terminal, TerminalModel model, IntConsumer statusListener) {
        super(terminal, hiddenToolbar());
        this.terminal = terminal;
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
                if (event.isControlDown() && !event.isAltDown() && event.getKeyCode() == KeyEvent.VK_V) {
                    paste();
                    event.consume();
                } else if (event.isControlDown() && !event.isAltDown() && event.getKeyCode() == KeyEvent.VK_C) {
                    copy();
                    event.consume();
                } else if (moveCursor(event.getKeyCode())) {
                    refresh();
                    event.consume();
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
        JMenuItem copy = new JMenuItem("Kopieren (Strg+C)");
        copy.addActionListener(e -> copy());
        JMenuItem paste = new JMenuItem("Einfügen (Strg+V)");
        paste.setEnabled(!terminal.isKeyboardLocked());
        paste.addActionListener(e -> paste());
        JMenuItem all = new JMenuItem("Alles auswählen");
        all.addActionListener(e -> selectAll());
        menu.add(copy);
        menu.add(paste);
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
            var input = terminal.getCharHandler();
            for (int i = 0; i < text.length(); i++) {
                input.type(text.charAt(i));
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
    public boolean keyboardLocked() {
        return terminal.isKeyboardLocked();
    }
}
