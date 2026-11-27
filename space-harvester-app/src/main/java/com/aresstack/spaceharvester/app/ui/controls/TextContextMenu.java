package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Das Rechtsklick-Menü JEDES Textfelds der App (Angelo, 23.11.2026): Ausschneiden, Kopieren, Einfügen, Alles
 * markieren, dasselbe wie Strg+X, Strg+C, Strg+V und Strg+A. Einmal für die ganze App eingerichtet
 * ({@link #install()}), damit kein Feld vergessen wird: Suchleisten, Filter, Formulare, Editoren, Chat-Eingabe, in
 * jedem Fenster und jedem Dialog, auch in später gebauten.
 *
 * <p>Ein Feld mit eigenem Kontextmenü behält es: Dieses Menü erscheint erst, nachdem das Feld den Rechtsklick
 * verarbeitet hat, und nur, wenn dabei kein anderes Menü aufgegangen ist und niemand den Klick verbraucht hat.
 * Schreibgeschützte Felder bieten nur Kopieren und Alles markieren, Passwortfelder weder Ausschneiden noch Kopieren.
 * Die Editoren (RSyntaxTextArea) zeigen statt ihres eigenen englischen Menüs dieses, mit Rückgängig und Wiederholen.
 *
 * <p>Ist ein Chat da ({@link #setQuoteTarget}), bietet das Menü zu markiertem Text zusätzlich „An Chat anhängen“
 * (Angelo 27.11.2026, wie bei ChatGPT): Der Ausschnitt erscheint als Zitat-Chip über der Chat-Eingabe.
 */
public final class TextContextMenu {

    /** Beschriftungen, auch für selbst gezeichnete Flächen mit eigenem Menü (Terminal), damit es überall gleich heißt. */
    public static final String CUT = "Ausschneiden";
    public static final String COPY = "Kopieren";
    public static final String PASTE = "Einfügen";
    public static final String SELECT_ALL = "Alles markieren";
    public static final String UNDO = "Rückgängig";
    public static final String REDO = "Wiederholen";
    public static final String QUOTE = "An Chat anhängen";

    /** Wohin „An Chat anhängen“ markierten Text gibt (der Chat der App). */
    public interface QuoteTarget {

        /** Ob dieses Feld zitiert werden kann (nicht die Chat-Eingabe selbst, nur solange der Chat zu sehen ist). */
        boolean accepts(Component field);

        /**
         * Hängt {@code text} als Zitat an die nächste Nachricht an; {@code reference} nennt, woher es stammt (etwa
         * Artefakt und Abschnitt, ADR 0045 §7), sonst {@code null}.
         */
        void quote(String text, String reference);
    }

    /**
     * Schlüssel einer Client-Eigenschaft (Text) an einem Feld oder einem seiner Vorfahren: der Bezug, den ein Zitat
     * aus diesem Bereich mitträgt (ADR 0045 §7), etwa „Dokument D1 „Gartenfibel“, Abschnitt a2“.
     */
    public static final String QUOTE_REFERENCE = "spaceharvester.quoteReference";

    /** Der Bezug für Zitate aus {@code field}: die nächste {@link #QUOTE_REFERENCE} nach außen, sonst {@code null}. */
    public static String quoteReference(Component field) {
        for (Component at = field; at != null; at = at.getParent()) {
            if (at instanceof javax.swing.JComponent component
                    && component.getClientProperty(QUOTE_REFERENCE) instanceof String reference) {
                return reference;
            }
        }
        return null;
    }

    /**
     * Gibt markierten Text aus {@code field} als Zitat an den Chat, mit dem Bezug des Bereichs; ein Weg für Menü und
     * schwebenden Knopf. {@code false}, wenn kein Chat ihn annimmt.
     */
    public static boolean quote(Component field, String text) {
        return quote(field, text, quoteReference(field));
    }

    /**
     * Wie {@link #quote(Component, String)}, mit einem eigenen Bezug: etwa ein Element einer Grafik, das kein
     * markierbarer Text ist (ADR 0045 §6).
     */
    public static boolean quote(Component source, String text, String reference) {
        QuoteTarget target = quoteTarget.get();
        if (target == null || text == null || text.isBlank() || source instanceof JPasswordField
                || !target.accepts(source)) {
            return false;
        }
        target.quote(text, reference);
        return true;
    }

    private static AWTEventListener installed;
    /** Schwach gehalten: Ein geschlossener Chat (in Tests viele) bleibt nicht wegen des Menüs im Speicher. */
    private static java.lang.ref.WeakReference<QuoteTarget> quoteTarget = new java.lang.ref.WeakReference<>(null);

    private TextContextMenu() {
    }

    /** Meldet den Chat an, der markierten Text als Zitat annimmt; der zuletzt angemeldete gilt. */
    public static synchronized void setQuoteTarget(QuoteTarget target) {
        quoteTarget = new java.lang.ref.WeakReference<>(target);
    }

    /** Richtet das Menü für alle Textfelder der App ein; mehrfaches Aufrufen schadet nicht. */
    public static synchronized void install() {
        if (installed != null || java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        installed = event -> {
            if (event instanceof MouseEvent mouse && mouse.isPopupTrigger()) {
                requested(mouse);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(installed, AWTEvent.MOUSE_EVENT_MASK);
    }

    /**
     * Ein Rechtsklick irgendwo in der App. Trifft er ein Textfeld, kommt das Menü, sobald das Feld den Klick selbst
     * verarbeitet hat: So gewinnt ein eigenes Menü des Feldes.
     */
    static void requested(MouseEvent mouse) {
        if (!(mouse.getSource() instanceof Component source)) {
            return;
        }
        Component hit = source instanceof JTextComponent ? source
                : SwingUtilities.getDeepestComponentAt(source, mouse.getX(), mouse.getY());
        if (!(hit instanceof JTextComponent field) || !field.isEnabled() || !field.isShowing()) {
            return;
        }
        if (field instanceof org.fife.ui.rtextarea.RTextArea editor) {
            // Der Editor bringt ein eigenes, englisches Menü mit denselben Einträgen mit; hier gilt das der App.
            editor.setPopupMenu(null);
        }
        Point at = SwingUtilities.convertPoint(source, mouse.getPoint(), field);
        SwingUtilities.invokeLater(() -> {
            if (mouse.isConsumed() || field.getComponentPopupMenu() != null || !field.isShowing()
                    || MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                return;
            }
            prepare(field, at);
            menu(field).show(field, at.x, at.y);
        });
    }

    /** Wie überall üblich: Das Feld bekommt den Fokus; ein Klick außerhalb der Markierung setzt die Schreibmarke dorthin. */
    static void prepare(JTextComponent field, Point at) {
        field.requestFocusInWindow();
        int position = field.viewToModel2D(at);
        if (position >= 0 && (position < field.getSelectionStart() || position > field.getSelectionEnd()
                || field.getSelectionStart() == field.getSelectionEnd())) {
            field.setCaretPosition(Math.min(position, field.getDocument().getLength()));
        }
    }

    /** Das Menü für dieses Feld, im Zustand von jetzt (Markierung, Schreibschutz, Zwischenablage). */
    public static JPopupMenu menu(JTextComponent field) {
        boolean secret = field instanceof JPasswordField;
        boolean writable = field.isEditable() && field.isEnabled();
        boolean marked = field.getSelectionStart() != field.getSelectionEnd();
        boolean any = field.getDocument().getLength() > 0;
        int shortcut = shortcutMask();
        JPopupMenu menu = new JPopupMenu();
        if (writable && field instanceof org.fife.ui.rtextarea.RTextArea editor) {
            menu.add(item(UNDO, KeyEvent.VK_Z, shortcut, editor.canUndo(), editor::undoLastAction));
            menu.add(item(REDO, KeyEvent.VK_Y, shortcut, editor.canRedo(), editor::redoLastAction));
            menu.addSeparator();
        }
        if (writable && !secret) {
            menu.add(item(CUT, KeyEvent.VK_X, shortcut, marked, field::cut));
        }
        if (!secret) {
            menu.add(item(COPY, KeyEvent.VK_C, shortcut, marked, field::copy));
        }
        if (writable) {
            menu.add(item(PASTE, KeyEvent.VK_V, shortcut, clipboardHasText(), field::paste));
        }
        QuoteTarget target = quoteTarget.get();
        if (!secret && marked && target != null && target.accepts(field)) {
            String selected = field.getSelectedText();
            if (selected != null && !selected.isBlank()) {
                menu.addSeparator();
                JMenuItem quote = new JMenuItem(QUOTE, new QuoteIcon());
                quote.addActionListener(e -> quote(field, selected));
                menu.add(quote);
            }
        }
        menu.addSeparator();
        menu.add(item(SELECT_ALL, KeyEvent.VK_A, shortcut, any, () -> {
            field.requestFocusInWindow();
            field.selectAll();
        }));
        return menu;
    }

    /** Strg, auf dem Mac die Befehlstaste; ohne Bildschirm (Tests) Strg. */
    public static int shortcutMask() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            return InputEvent.CTRL_DOWN_MASK;
        }
    }

    private static JMenuItem item(String text, int key, int shortcut, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        // Nur zur Anzeige des Kürzels; ausgelöst wird es weiter vom Feld selbst.
        item.setAccelerator(KeyStroke.getKeyStroke(key, shortcut));
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    private static boolean clipboardHasText() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (IllegalStateException | java.awt.HeadlessException e) {
            // Die Zwischenablage ist gerade von einem anderen Programm belegt: Einfügen anbieten, das Feld prüft selbst.
            return true;
        }
    }
}
