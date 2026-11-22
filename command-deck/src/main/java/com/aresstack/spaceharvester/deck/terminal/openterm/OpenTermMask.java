package com.aresstack.spaceharvester.deck.terminal.openterm;

import com.aresstack.spaceharvester.deck.terminal.MaskRefused;
import com.aresstack.spaceharvester.deck.terminal.TerminalMask;
import com.ascert.open.term.core.AbstractTermField;
import com.ascert.open.term.core.IsProtectedException;
import com.ascert.open.term.core.TermChar;
import com.ascert.open.term.core.TermField;
import com.ascert.open.term.core.Terminal;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * Der Port {@link TerminalMask} über OpenTerm (#1151). Eingetippt wird wie in MainframeMate erprobt (Auto-Login,
 * Einfügen, Makros): Schreibmarke an den Feldanfang, Zeichen über den {@code InputCharHandler} von OpenTerm – der
 * prüft Schutz und Eingaberegeln selbst und setzt das MDT, schickt aber nichts. Den Rest des Feldes leert die Klasse
 * wie „Erase EOF“. Eine AID-Taste ({@code Terminal.Fkey}) ruft sie nie auf; diese Klasse ist bewusst getrennt von
 * der Anzeige ({@code OpenTermScreen}), die als {@code TerminalKeyboard} auch Host-Tasten drückt.
 *
 * <p>Gelesen und geschrieben wird auf dem EDT, wo auch die Tastatur des Menschen tippt; so mischen sich beide nie
 * innerhalb eines Feldes.
 */
final class OpenTermMask implements TerminalMask {

    private final Terminal terminal;
    /** Zeichnet die Anzeige neu und meldet das neue Bild; die Anzeige selbst (mit Host-Tasten) kennt sie nicht. */
    private final Runnable repaint;

    OpenTermMask(Terminal terminal, Runnable repaint) {
        this.terminal = terminal;
        this.repaint = repaint;
    }

    @Override
    public ScreenImage capture() {
        return onEdt(this::captureNow);
    }

    ScreenImage captureNow() {
        int rows = terminal.getRows();
        int columns = terminal.getCols();
        int cells = Math.max(0, rows * columns);
        char[] text = new char[cells];
        for (int i = 0; i < cells; i++) {
            TermChar c = terminal.getChar(i);
            char value = c == null || c.isStartField() ? ' ' : c.getChar();
            text[i] = value == 0 ? ' ' : value;
        }
        List<Field> fields = new ArrayList<>();
        java.util.Vector<?> all = terminal.getFields();
        if (all != null && cells > 0) {
            for (Object entry : all) {
                if (entry instanceof TermField field) {
                    fields.add(field(field, cells));
                }
            }
        }
        fields.sort(java.util.Comparator.comparingInt(Field::start));
        return new ScreenImage(rows, columns, terminal.getCursorPosition(), terminal.isKeyboardLocked(), text,
                fields);
    }

    /** Das Attributbyte steht auf {@code getBeginBA()}, die Daten dahinter bis einschließlich {@code getEndBA()}. */
    static Field field(TermField field, int cells) {
        int attribute = field.getBeginBA();
        int start = (attribute + 1) % cells;
        int length = Math.floorMod(field.getEndBA() - attribute, cells);
        boolean hidden = field instanceof AbstractTermField abstractField ? abstractField.isHidden()
                : field.getFAChar() != null && field.getFAChar().isHidden();
        return new Field(start, length, field.isProtected(), field.isNumeric(), hidden, field.isModified());
    }

    @Override
    public void write(int start, int length, String value) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        onEdt(() -> {
            try {
                writeNow(start, length, value);
            } catch (RuntimeException e) {
                failure.set(e);
            }
            return null;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private void writeNow(int start, int length, String value) {
        if (terminal.isKeyboardLocked()) {
            throw new MaskRefused("Der Host nimmt gerade keine Eingabe an (X-Wait).");
        }
        int cells = terminal.getRows() * terminal.getCols();
        TermField field = terminal.getField(start);
        if (field == null || field.isProtected()) {
            throw new MaskRefused("Das Feld ist geschützt.");
        }
        int cursor = terminal.getCursorPosition();
        try {
            terminal.setCursorPosition(start);
            var input = terminal.getCharHandler();
            for (int i = 0; i < value.length(); i++) {
                if (!input.type(value.charAt(i))) {
                    throw new MaskRefused("Das Terminal hat das Zeichen '" + value.charAt(i) + "' abgelehnt.");
                }
            }
            char empty = terminal.getEmptyChar();
            for (int i = value.length(); i < length; i++) {
                TermChar c = terminal.getChar((start + i) % cells);
                if (c != null && !c.isStartField()) {
                    c.setChar(empty);
                }
            }
            field.setModified(true);
        } catch (IsProtectedException e) {
            throw new MaskRefused("Das Feld ist geschützt.");
        } catch (java.io.IOException e) {
            throw new MaskRefused("Eingabe gescheitert (" + e.getClass().getSimpleName() + ").");
        } finally {
            terminal.setCursorPosition(cursor);
            repaint.run();
        }
    }

    @Override
    public void moveCursor(int position) {
        onEdt(() -> {
            terminal.setCursorPosition(position);
            repaint.run();
            return null;
        });
    }

    private static <T> T onEdt(Supplier<T> work) {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(work.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MaskRefused("Unterbrochen.");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
        return result.get();
    }
}
