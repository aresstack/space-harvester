package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Die Meldung links in der Statuszeile ({@link StatusNote}): grau, gelb oder rot, ein Wort darin auf Wunsch als
 * blauer, unterstrichener Link mit Hand-Zeiger. Nur ein Klick auf das Wort selbst löst ihn aus.
 */
public final class StatusLabel extends JLabel {

    /** Gelb der Ampel als Schrift: dunkel genug, um auf hellem Grund lesbar zu sein. */
    static final Color WARNING = Comic.STANDING_UNCLEAR;

    private StatusNote note = StatusNote.plain(" ");

    public StatusLabel() {
        super(" ");
        setFont(Comic.regular(12f));
        setForeground(Comic.MUTED);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (overLink(event.getX())) {
                    note.action().run();
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                setCursor(overLink(event.getX()) ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                setCursor(null);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Zeigt die Meldung; leer zeigt eine leere Zeile. */
    public void show(StatusNote shown) {
        note = shown == null ? StatusNote.plain(" ") : shown;
        String text = note.text().isBlank() ? " " : note.text();
        setForeground(switch (note.tone()) {
            case PLAIN -> Comic.MUTED;
            case WARNING -> WARNING;
            case ERROR -> Comic.RED;
        });
        if (!note.hasLink()) {
            setText(text);
        } else {
            int at = text.lastIndexOf(note.link());
            setText("<html>" + escape(text.substring(0, at)) + "<u><font color='" + hex(Comic.BLUE) + "'>"
                    + escape(note.link()) + "</font></u>" + escape(text.substring(at + note.link().length()))
                    + "</html>");
        }
        setToolTipText(note.hasLink() ? note.text() : null);
        setCursor(null);
    }

    public StatusNote note() {
        return note;
    }

    /** Ob {@code x} über dem Link-Wort liegt, gemessen am Text davor (eine Zeile, linksbündig). */
    boolean overLink(int x) {
        if (!note.hasLink()) {
            return false;
        }
        FontMetrics metrics = getFontMetrics(getFont());
        Insets insets = getInsets();
        String text = note.text();
        int at = text.lastIndexOf(note.link());
        int start = insets.left + metrics.stringWidth(text.substring(0, at));
        int end = start + metrics.stringWidth(note.link());
        return x >= start && x <= end;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
