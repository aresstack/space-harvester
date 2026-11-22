package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * Die Meldung links in der Statuszeile ({@link StatusNote}): grau, gelb oder rot, ein Wort darin auf Wunsch als
 * blauer, unterstrichener Link mit Hand-Zeiger. Nur ein Klick auf das Wort selbst löst ihn aus. Mit Link ist die
 * Meldung per Tab erreichbar (gelber Rahmen um das Wort), Enter oder Leertaste lösen ihn aus; Bildschirmleser lesen
 * den Text als Namen und den Link als Beschreibung (UX).
 *
 * <p>Teile mit eigenem Ton ({@link StatusNote.Mark}) erscheinen in ihrer Farbe. Ein Symbol als Link
 * ({@link StatusNote.IconLink}, etwa der Kreispfeil „wiederholen“) steht blau direkt hinter seinem Teil, mit Hand-Zeiger
 * und eigenem Tooltip; per Tastatur löst es Strg+Enter aus, ohne Link-Wort auch Enter und Leertaste.
 */
public final class StatusLabel extends JLabel {

    /** Gelb der Ampel als Schrift: dunkel genug, um auf hellem Grund lesbar zu sein. */
    static final Color WARNING = Comic.STANDING_UNCLEAR;
    /** Breite des Symbols eines {@link StatusNote.IconLink} in Pixeln. */
    static final int ICON = 16;
    /** Malt das Symbol in Link-Blau ({@link com.aresstack.spaceharvester.app.ui.Glyph} nimmt die Vordergrundfarbe). */
    private static final JLabel ICON_HOST = new JLabel();

    static {
        ICON_HOST.setForeground(Comic.BLUE);
    }

    private StatusNote note = StatusNote.plain(" ");
    /** Der Text, wie er gemessen wird: mit Leerzeichen als Platz für das Symbol. */
    private String measured = " ";
    /** Wo im {@link #measured} der Platz für das Symbol beginnt, und wie viele Leerzeichen er hat; -1 ohne. */
    private int slotAt = -1;
    private int slotSpaces;

    public StatusLabel() {
        super(" ");
        setFont(Comic.regular(12f));
        setForeground(Comic.MUTED);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (overLink(event.getX())) {
                    note.action().run();
                } else if (overIcon(event.getX())) {
                    note.icon().action().run();
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                setCursor(overLink(event.getX()) || overIcon(event.getX())
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
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
                } else if (note.hasIcon()) {
                    note.icon().action().run();
                }
            }
        };
        javax.swing.Action icon = new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (note.hasIcon()) {
                    note.icon().action().run();
                }
            }
        };
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "open-link");
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "open-link");
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("ctrl ENTER"), "icon-link");
        getActionMap().put("open-link", open);
        getActionMap().put("icon-link", icon);
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
        slotAt = -1;
        slotSpaces = 0;
        measured = text;
        if (!note.hasLink() && note.marks().isEmpty() && !note.hasIcon()) {
            setText(text);
        } else {
            setText(html(text));
        }
        setToolTipText(note.hasLink() || note.hasIcon() ? note.text() : null);
        setCursor(null);
        setFocusable(note.hasLink() || note.hasIcon());
        getAccessibleContext().setAccessibleName(text.isBlank() ? null : text);
        String description = note.hasLink() ? "Link „" + note.link() + "“, öffnen mit Enter" : null;
        if (note.hasIcon()) {
            String icon = "Symbol „" + note.icon().tooltip() + "“, auslösen mit " + (note.hasLink() ? "Strg+Enter"
                    : "Enter");
            description = description == null ? icon : description + "; " + icon;
        }
        getAccessibleContext().setAccessibleDescription(description);
    }

    /**
     * Der Text als HTML: Teile in ihrem Ton, das Link-Wort blau unterstrichen, hinter dem Teil des Symbols Platz dafür
     * (geschützte Leerzeichen, gezeichnet in {@link #paintComponent}). Setzt {@link #measured} und den Platz.
     */
    private String html(String text) {
        int n = text.length();
        Color[] colors = new Color[n];
        boolean[] linked = new boolean[n];
        for (StatusNote.Mark mark : note.marks()) {
            int at = text.indexOf(mark.part());
            for (int i = at; i < at + mark.part().length(); i++) {
                colors[i] = color(mark.tone());
            }
        }
        if (note.hasLink()) {
            int at = text.lastIndexOf(note.link());
            for (int i = at; i < at + note.link().length(); i++) {
                linked[i] = true;
            }
        }
        int slot = -1;
        if (note.hasIcon()) {
            slot = text.indexOf(note.icon().after()) + note.icon().after().length();
            int space = Math.max(1, getFontMetrics(getFont()).stringWidth(" "));
            slotSpaces = Math.max(3, (ICON + 2 * space + space - 1) / space);
        }
        StringBuilder html = new StringBuilder("<html>");
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i <= n) {
            if (i == slot) {
                slotAt = plain.length();
                html.append("&nbsp;".repeat(slotSpaces));
                plain.append(" ".repeat(slotSpaces));
            }
            if (i == n) {
                break;
            }
            int end = i + 1;
            while (end < n && end != slot && Objects.equals(colors[end], colors[i]) && linked[end] == linked[i]) {
                end++;
            }
            String run = text.substring(i, end);
            plain.append(run);
            String escaped = escape(run);
            if (linked[i]) {
                escaped = "<u><font color='" + hex(Comic.BLUE) + "'>" + escaped + "</font></u>";
            } else if (colors[i] != null) {
                escaped = "<font color='" + hex(colors[i]) + "'>" + escaped + "</font>";
            }
            html.append(escaped);
            i = end;
        }
        measured = plain.toString();
        return html.append("</html>").toString();
    }

    private static Color color(StatusNote.Tone tone) {
        return switch (tone) {
            case PLAIN -> Comic.MUTED;
            case WARNING -> WARNING;
            case ERROR -> Comic.RED;
        };
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (event != null && overIcon(event.getX())) {
            return note.icon().tooltip();
        }
        return super.getToolTipText(event);
    }

    @Override
    protected void paintComponent(java.awt.Graphics graphics) {
        super.paintComponent(graphics);
        if (note.hasIcon() && slotAt >= 0) {
            int[] icon = iconSpan();
            note.icon().glyph().paintIcon(ICON_HOST, graphics, icon[0], (getHeight() - ICON) / 2);
        }
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
        String text = measured;
        int at = text.lastIndexOf(note.link());
        int start = insets.left + metrics.stringWidth(text.substring(0, at));
        return new int[] {start, start + metrics.stringWidth(note.link())};
    }

    /** Ob {@code x} über dem Symbol liegt (mit dem Platz drumherum). */
    boolean overIcon(int x) {
        if (!note.hasIcon() || slotAt < 0) {
            return false;
        }
        FontMetrics metrics = getFontMetrics(getFont());
        int start = getInsets().left + metrics.stringWidth(measured.substring(0, slotAt));
        int end = start + metrics.stringWidth(" ".repeat(slotSpaces));
        return x >= start && x <= end;
    }

    /** Anfang und Ende des Symbols in Pixeln: mittig im Platz hinter seinem Teil. */
    int[] iconSpan() {
        FontMetrics metrics = getFontMetrics(getFont());
        int start = getInsets().left + metrics.stringWidth(measured.substring(0, slotAt));
        int room = metrics.stringWidth(" ".repeat(slotSpaces));
        int x = start + Math.max(0, (room - ICON) / 2);
        return new int[] {x, x + ICON};
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
