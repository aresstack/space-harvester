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

    /** Ende eines gekürzten Texts. */
    private static final String ELLIPSIS = "…";
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
    /**
     * Mit Tönen, Link oder Symbol (HTML): der ganze Text wie gemessen ({@link #measured} ungekürzt), je Zeichen Farbe
     * und Link, wo der Platz des Symbols beginnt und das Link-Wort; {@code null} ohne HTML.
     */
    private String full;
    private Color[] fullColors;
    private boolean[] fullLinked;
    private int fullSlot = -1;
    private int fullLink = -1;
    /**
     * Bis wohin im {@link #full} nie gekürzt wird: das Ende des letzten roten Teils samt seinem Symbol (Fehler mit
     * Kreispfeil bleiben immer ganz, gekürzt wird nur, was dahinter steht, etwa ein gelber Hinweis); 0 ohne Rot.
     */
    private int fullKeep;
    /** Ob das Link-Wort ganz zu sehen ist (nicht weggekürzt). */
    private boolean linkShown;
    /** Die Breite, für die der HTML-Text zuletzt gebaut wurde. */
    private int renderedWidth = -1;

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
        full = null;
        linkShown = note.hasLink();
        renderedWidth = -1;
        if (!note.hasLink() && note.marks().isEmpty() && !note.hasIcon()) {
            setText(text);
            setToolTipText(null);
        } else {
            prepare(text);
            render(getWidth());
        }
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
     * Zerlegt den Text für HTML: je Zeichen Ton und Link, hinter dem Teil des Symbols Platz dafür (Leerzeichen, gezeichnet
     * in {@link #paintComponent}). Gebaut wird er erst für eine Breite ({@link #render}).
     */
    private void prepare(String text) {
        int n = text.length();
        Color[] colors = new Color[n];
        boolean[] linked = new boolean[n];
        for (StatusNote.Mark mark : note.marks()) {
            int at = text.indexOf(mark.part());
            for (int i = at; i < at + mark.part().length(); i++) {
                colors[i] = color(mark.tone());
            }
        }
        int linkAt = -1;
        if (note.hasLink()) {
            linkAt = text.lastIndexOf(note.link());
            for (int i = linkAt; i < linkAt + note.link().length(); i++) {
                linked[i] = true;
            }
        }
        int slot = -1;
        if (note.hasIcon()) {
            slot = text.indexOf(note.icon().after()) + note.icon().after().length();
            int space = Math.max(1, getFontMetrics(getFont()).stringWidth(" "));
            slotSpaces = Math.max(3, (ICON + 2 * space + space - 1) / space);
        }
        StringBuilder plain = new StringBuilder(text);
        Color[] withSlotColors = colors;
        boolean[] withSlotLinked = linked;
        fullSlot = -1;
        if (slot >= 0) {
            plain.insert(slot, " ".repeat(slotSpaces));
            withSlotColors = new Color[n + slotSpaces];
            withSlotLinked = new boolean[n + slotSpaces];
            System.arraycopy(colors, 0, withSlotColors, 0, slot);
            System.arraycopy(colors, slot, withSlotColors, slot + slotSpaces, n - slot);
            System.arraycopy(linked, 0, withSlotLinked, 0, slot);
            System.arraycopy(linked, slot, withSlotLinked, slot + slotSpaces, n - slot);
            fullSlot = slot;
            if (linkAt >= slot) {
                linkAt += slotSpaces;
            }
        }
        full = plain.toString();
        fullKeep = keepEnd(text, slot);
        fullColors = withSlotColors;
        fullLinked = withSlotLinked;
        fullLink = linkAt;
    }

    /**
     * Baut den HTML-Text für {@code width}: immer eine Zeile. Passt er nicht, endet er mit „…“ (wie ein einfacher Text);
     * ein weggekürztes Symbol oder Link-Wort ist dann nicht anklickbar, der ganze Text steht im Tooltip. Setzt
     * {@link #measured} und den Platz des Symbols.
     */
    private void render(int width) {
        if (full == null) {
            return;
        }
        renderedWidth = width;
        FontMetrics metrics = getFontMetrics(getFont());
        Insets insets = getInsets();
        // Etwas Luft: HTML misst minimal anders als FontMetrics und bräche sonst in eine zweite Zeile um.
        int room = width - insets.left - insets.right - 2;
        int shown = full.length();
        boolean cut = width > 0 && metrics.stringWidth(full) > room;
        if (cut) {
            int ellipsis = metrics.stringWidth(ELLIPSIS);
            while (shown > 0 && metrics.stringWidth(full.substring(0, shown)) + ellipsis > room) {
                shown--;
            }
            shown = Math.max(shown, fullKeep);
            if (fullSlot >= 0 && shown > fullSlot && shown < fullSlot + slotSpaces) {
                shown = fullSlot;
            }
            // Leerzeichen am Ende fallen weg, aber nie der Platz eines ganz gezeigten Symbols oder Rotes.
            int floor = Math.max(fullKeep,
                    fullSlot >= 0 && shown >= fullSlot + slotSpaces ? fullSlot + slotSpaces : 0);
            while (shown > floor && full.charAt(shown - 1) == ' ') {
                shown--;
            }
        }
        slotAt = fullSlot >= 0 && fullSlot + slotSpaces <= shown ? fullSlot : -1;
        linkShown = fullLink >= 0 && fullLink + note.link().length() <= shown;
        StringBuilder html = new StringBuilder("<html><nobr>");
        int i = 0;
        while (i < shown) {
            int end = i + 1;
            boolean slotRun = slotAt >= 0 && i >= slotAt && i < slotAt + slotSpaces;
            while (end < shown && !slotRun && end != slotAt && Objects.equals(fullColors[end], fullColors[i])
                    && fullLinked[end] == fullLinked[i]) {
                end++;
            }
            if (slotRun) {
                end = slotAt + slotSpaces;
                html.append("&nbsp;".repeat(slotSpaces));
                i = end;
                continue;
            }
            String escaped = escape(full.substring(i, end));
            if (fullLinked[i] && linkShown) {
                escaped = "<u><font color='" + hex(Comic.BLUE) + "'>" + escaped + "</font></u>";
            } else if (fullColors[i] != null) {
                escaped = "<font color='" + hex(fullColors[i]) + "'>" + escaped + "</font>";
            }
            html.append(escaped);
            i = end;
        }
        measured = full.substring(0, shown) + (cut ? ELLIPSIS : "");
        if (cut) {
            html.append(ELLIPSIS);
        }
        setText(html.append("</nobr></html>").toString());
        setToolTipText(note.hasLink() || note.hasIcon() || cut ? note.text() : null);
    }

    /** Mit HTML immer eine Zeile, so breit wie der ganze Text; schmaler gesetzt, kürzt er sich mit „…“. */
    @Override
    public java.awt.Dimension getPreferredSize() {
        if (full == null || isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        FontMetrics metrics = getFontMetrics(getFont());
        Insets insets = getInsets();
        return new java.awt.Dimension(insets.left + insets.right + metrics.stringWidth(full) + 2,
                insets.top + insets.bottom + metrics.getHeight());
    }

    /** Mit HTML so schmal, wie es geht, ohne Rot und sein Symbol zu kürzen ({@link #keptWidth}). */
    @Override
    public java.awt.Dimension getMinimumSize() {
        if (full == null || isMinimumSizeSet()) {
            return super.getMinimumSize();
        }
        return new java.awt.Dimension(keptWidth(), getPreferredSize().height);
    }

    /**
     * Die Breite, unter die die Meldung nie gekürzt wird: der Text bis zum Ende des letzten roten Teils samt seinem
     * Symbol und, wenn danach noch etwas käme, „…“; 0, wenn die Meldung nichts Rotes hat (oder ohne Töne und Symbol
     * ist; dann kürzt sie sich wie ein einfaches Label).
     */
    public int keptWidth() {
        if (full == null || fullKeep <= 0) {
            return 0;
        }
        FontMetrics metrics = getFontMetrics(getFont());
        Insets insets = getInsets();
        return insets.left + insets.right + metrics.stringWidth(full.substring(0, fullKeep))
                + (fullKeep < full.length() ? metrics.stringWidth(ELLIPSIS) : 0) + 2;
    }

    /**
     * Das Ende des letzten roten Zeichens in {@code text} (Leerzeichen und Trenner „·“ zählen nicht, sie tragen den Ton
     * der Zeile; das Link-Wort ist blau), im Text mit dem Platz des Symbols an {@code slot}; steht das Symbol direkt hinter oder in Rot, gehört
     * sein Platz dazu.
     */
    private int keepEnd(String text, int slot) {
        boolean[] red = new boolean[text.length()];
        if (note.tone() == StatusNote.Tone.ERROR) {
            java.util.Arrays.fill(red, true);
        }
        for (StatusNote.Mark mark : note.marks()) {
            int at = text.indexOf(mark.part());
            for (int i = at; i >= 0 && i < at + mark.part().length(); i++) {
                red[i] = mark.tone() == StatusNote.Tone.ERROR;
            }
        }
        if (note.hasLink()) {
            // Das Link-Wort ist blau, nicht rot.
            int at = text.lastIndexOf(note.link());
            for (int i = at; i >= 0 && i < at + note.link().length(); i++) {
                red[i] = false;
            }
        }
        int end = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (red[i] && !Character.isWhitespace(c) && c != '·') {
                end = i + 1;
            }
        }
        if (end > 0 && slot >= 0 && slot <= end) {
            end += slotSpaces;
        }
        return end;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        if (full != null && width != renderedWidth) {
            render(width);
        }
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
        if (note.hasLink() && linkShown && isFocusOwner()) {
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
        if (!note.hasLink() || !linkShown) {
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
