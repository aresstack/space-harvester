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
 * blauer, unterstrichener Link mit Hand-Zeiger. Nur ein Klick auf das Wort selbst löst ihn aus. Mit Link ist die
 * Meldung per Tab erreichbar (gelber Rahmen um das Wort), Enter oder Leertaste lösen ihn aus; Bildschirmleser lesen
 * den Text als Namen und den Link als Beschreibung (UX).
 */
public final class StatusLabel extends JLabel {

    /** Gelb der Ampel als Schrift: dunkel genug, um auf hellem Grund lesbar zu sein. */
    static final Color WARNING = Comic.STANDING_UNCLEAR;
    /** Der Ton „gelb“ (bewusst ausgelassen, wartet) auch für Hinweise außerhalb der Statuszeile. */
    public static final Color WARNING_COLOR = WARNING;

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
        setFocusable(false);
        javax.swing.Action open = new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (note.hasLink()) {
                    note.action().run();
                }
            }
        };
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "open-link");
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "open-link");
        getActionMap().put("open-link", open);
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                repaint();
            }
        });
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
        setFocusable(note.hasLink());
        getAccessibleContext().setAccessibleName(text.isBlank() ? null : text);
        getAccessibleContext().setAccessibleDescription(note.hasLink()
                ? "Link „" + note.link() + "“, öffnen mit Enter" : null);
    }

    @Override
    protected void paintComponent(java.awt.Graphics graphics) {
        super.paintComponent(graphics);
        if (note.hasLink() && isFocusOwner()) {
            int[] span = linkSpan();
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Comic.YELLOW);
                g.setStroke(new java.awt.BasicStroke(2f));
                g.drawRoundRect(span[0] - 3, 1, span[1] - span[0] + 6, getHeight() - 3, 6, 6);
            } finally {
                g.dispose();
            }
        }
    }

    public StatusNote note() {
        return note;
    }

    /** Ob {@code x} über dem Link-Wort liegt, gemessen am Text davor (eine Zeile, linksbündig). */
    boolean overLink(int x) {
        if (!note.hasLink()) {
            return false;
        }
        int[] span = linkSpan();
        return x >= span[0] && x <= span[1];
    }

    /** Anfang und Ende des Link-Worts in Pixeln. */
    private int[] linkSpan() {
        FontMetrics metrics = getFontMetrics(getFont());
        Insets insets = getInsets();
        String text = note.text();
        int at = text.lastIndexOf(note.link());
        int start = insets.left + metrics.stringWidth(text.substring(0, at));
        return new int[] {start, start + metrics.stringWidth(note.link())};
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
