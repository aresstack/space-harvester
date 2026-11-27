package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Das Rechtsklick-Menü jedes Textfelds: Ausschneiden, Kopieren, Einfügen, Alles markieren. */
class TextContextMenuTest {

    @Test
    void anEditableFieldOffersCutCopyPasteAndSelectAll() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField("erfundener Suchtext");
            field.select(0, 10);
            JPopupMenu menu = TextContextMenu.menu(field);
            assertThat(labels(menu)).containsExactly(TextContextMenu.CUT, TextContextMenu.COPY,
                    TextContextMenu.PASTE, TextContextMenu.SELECT_ALL);
            assertThat(item(menu, TextContextMenu.CUT).isEnabled()).isTrue();
            assertThat(item(menu, TextContextMenu.COPY).getAccelerator()).isNotNull();

            item(menu, TextContextMenu.SELECT_ALL).doClick();
            assertThat(field.getSelectedText()).isEqualTo("erfundener Suchtext");
        });
    }

    @Test
    void withoutASelectionCutAndCopyAreOffButStillThere() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField("Text");
            JPopupMenu menu = TextContextMenu.menu(field);
            assertThat(item(menu, TextContextMenu.CUT).isEnabled()).isFalse();
            assertThat(item(menu, TextContextMenu.COPY).isEnabled()).isFalse();
            assertThat(item(menu, TextContextMenu.SELECT_ALL).isEnabled()).isTrue();
        });
    }

    @Test
    void aReadOnlyFieldOnlyCopies() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea("nur lesen");
            area.setEditable(false);
            assertThat(labels(TextContextMenu.menu(area))).containsExactly(TextContextMenu.COPY,
                    TextContextMenu.SELECT_ALL);
        });
    }

    @Test
    void aPasswordFieldNeverCutsOrCopies() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPasswordField field = new JPasswordField("erfunden");
            field.selectAll();
            assertThat(labels(TextContextMenu.menu(field))).containsExactly(TextContextMenu.PASTE,
                    TextContextMenu.SELECT_ALL);
        });
    }

    @Test
    void theEditorGetsUndoAndRedoInGerman() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            org.fife.ui.rsyntaxtextarea.RSyntaxTextArea editor = new org.fife.ui.rsyntaxtextarea.RSyntaxTextArea("x");
            assertThat(labels(TextContextMenu.menu(editor))).containsExactly(TextContextMenu.UNDO,
                    TextContextMenu.REDO, TextContextMenu.CUT, TextContextMenu.COPY, TextContextMenu.PASTE,
                    TextContextMenu.SELECT_ALL);
        });
    }

    /** Ein Klick außerhalb der Markierung setzt die Schreibmarke dorthin, einer in der Markierung lässt sie stehen. */
    @Test
    void aRightClickInsideTheSelectionKeepsIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField("erfundener Suchtext");
            field.setSize(field.getPreferredSize());
            field.select(0, 10);
            try {
                java.awt.geom.Rectangle2D inside = field.modelToView2D(4);
                TextContextMenu.prepare(field, new java.awt.Point((int) inside.getX(), (int) inside.getCenterY()));
                assertThat(field.getSelectedText()).isEqualTo("erfundener");
                java.awt.geom.Rectangle2D outside = field.modelToView2D(15);
                TextContextMenu.prepare(field, new java.awt.Point((int) outside.getX() + 1,
                        (int) outside.getCenterY()));
                assertThat(field.getSelectedText()).isNull();
            } catch (javax.swing.text.BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Wer den Rechtsklick selbst verbraucht oder ein eigenes Menü hat, bekommt dieses Menü nicht dazu. */
    @Test
    void aConsumedClickOrAnOwnMenuWins() throws Exception {
        JTextField own = new JTextField("eigenes Menü");
        SwingUtilities.invokeAndWait(() -> {
            own.setComponentPopupMenu(new JPopupMenu());
            MouseEvent click = new MouseEvent(own, MouseEvent.MOUSE_PRESSED, 0, 0, 2, 2, 1, true,
                    MouseEvent.BUTTON3);
            TextContextMenu.requested(click);
        });
        // Nach dem Durchlauf der Ereignisschlange ist kein Menü offen (das Feld ist nicht einmal sichtbar).
        SwingUtilities.invokeAndWait(() -> assertThat(
                javax.swing.MenuSelectionManager.defaultManager().getSelectedPath()).isEmpty());
    }

    @Test
    void markedTextCanBeQuotedIntoTheChat() throws Exception {
        List<String> quoted = new ArrayList<>();
        JTextField[] own = new JTextField[1];
        TextContextMenu.QuoteTarget chat = new TextContextMenu.QuoteTarget() {
            @Override
            public boolean accepts(java.awt.Component field) {
                return field != own[0];
            }

            @Override
            public void quote(String text, String reference) {
                quoted.add(reference == null ? text : reference + " | " + text);
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                TextContextMenu.setQuoteTarget(chat);
                JTextArea page = new JTextArea("Der Nachtlauf startet um 22 Uhr.");
                page.setEditable(false);
                page.select(4, 13);
                JPopupMenu menu = TextContextMenu.menu(page);
                assertThat(labels(menu)).containsExactly(TextContextMenu.COPY, TextContextMenu.QUOTE,
                        TextContextMenu.SELECT_ALL);
                item(menu, TextContextMenu.QUOTE).doClick();
                assertThat(quoted).containsExactly("Nachtlauf");

                // Ein Bereich mit Bezug (ADR 0045 §7: Artefakt und Abschnitt) gibt ihn dem Zitat mit.
                javax.swing.JPanel section = new javax.swing.JPanel();
                section.putClientProperty(TextContextMenu.QUOTE_REFERENCE, "Dokument D1, Abschnitt a2");
                section.add(page);
                item(TextContextMenu.menu(page), TextContextMenu.QUOTE).doClick();
                assertThat(quoted).containsExactly("Nachtlauf", "Dokument D1, Abschnitt a2 | Nachtlauf");
                page.select(4, 13);

                // Ohne Markierung, im Passwortfeld und in der Chat-Eingabe selbst gibt es kein Zitieren.
                page.select(0, 0);
                assertThat(labels(TextContextMenu.menu(page))).doesNotContain(TextContextMenu.QUOTE);
                JPasswordField secret = new JPasswordField("erfunden");
                secret.selectAll();
                assertThat(labels(TextContextMenu.menu(secret))).doesNotContain(TextContextMenu.QUOTE);
                own[0] = new JTextField("meine Frage");
                own[0].selectAll();
                assertThat(labels(TextContextMenu.menu(own[0]))).doesNotContain(TextContextMenu.QUOTE);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> TextContextMenu.setQuoteTarget(null));
        }
    }

    private static List<String> labels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        for (Component child : menu.getComponents()) {
            if (child instanceof JMenuItem item) {
                labels.add(item.getText());
            }
        }
        return labels;
    }

    private static JMenuItem item(JPopupMenu menu, String label) {
        for (Component child : menu.getComponents()) {
            if (child instanceof JMenuItem item && label.equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError(label);
    }
}
