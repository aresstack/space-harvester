package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.Objects;

/**
 * Ein selbst gezeichnetes Hinweisfenster an der Mausposition: Überschrift, umbrechender Fließtext in Proportionalschrift
 * und darunter wahlweise Links („Zum Beleg im Text“). Es ist höchstens {@link #MAX_WIDTH} breit, schmaler bei kurzem
 * Text, und schließt sich bei Klick daneben, mit Esc oder beim Klick auf einen Link. Es erscheint leicht versetzt zum
 * Mauszeiger, damit ein Doppelklick auf das Element darunter ankommt.
 *
 * <p>Gedacht für Details zu einem angeklickten Element (Knoten, Kante, Tabellenzeile), die keinen festen Platz im
 * Reiter verdienen; der Reiter selbst bleibt ganz dem Inhalt.
 */
public final class InfoPopup extends JPopupMenu {

    /** Breite der Fläche, mehr nimmt sich der Text nie. */
    public static final int MAX_WIDTH = 480;
    private static final int PAD = 12;
    private static final int OFFSET = 10;

    private final JLabel title = new JLabel(" ");
    private final WrappedText body = Comic.wrapped(" ", Comic.TEXT, 13f);
    private final JPanel links = new JPanel();
    private final JPanel content = new JPanel();

    public InfoPopup() {
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD));
        setLightWeightPopupEnabled(true);
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        title.setFont(Comic.semiBold(13f));
        title.setForeground(Comic.INK);
        title.setAlignmentX(LEFT_ALIGNMENT);
        body.setAlignmentX(LEFT_ALIGNMENT);
        links.setOpaque(false);
        links.setLayout(new BoxLayout(links, BoxLayout.X_AXIS));
        links.setAlignmentX(LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(6));
        content.add(body);
        content.add(links);
        add(content);
    }

    /** Überschrift (fett) und Fließtext; ein leerer Titel entfällt. */
    public InfoPopup text(String heading, String text) {
        title.setText(heading == null ? "" : heading);
        title.setVisible(heading != null && !heading.isBlank());
        body.setText(text == null ? "" : text);
        return this;
    }

    /** Entfernt alle Links. */
    public InfoPopup clearLinks() {
        links.removeAll();
        links.setVisible(false);
        return this;
    }

    /** Ein Link unter dem Text; der Klick schließt das Fenster und führt {@code action} aus. */
    public InfoPopup link(String text, Runnable action) {
        Objects.requireNonNull(action, "action");
        JLabel link = new JLabel(text);
        link.setFont(Comic.regular(13f));
        link.setForeground(Comic.BLUE);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(null);
        link.putClientProperty("info-popup.link", Boolean.TRUE);
        @SuppressWarnings("unchecked")
        Map<java.awt.font.TextAttribute, Object> attributes =
                (Map<java.awt.font.TextAttribute, Object>) link.getFont().getAttributes();
        attributes.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
        link.setFont(link.getFont().deriveFont(attributes));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setVisible(false);
                action.run();
            }
        });
        if (links.getComponentCount() > 0) {
            links.add(Box.createHorizontalStrut(14));
        } else {
            links.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        }
        links.add(link);
        links.setVisible(true);
        return this;
    }

    /**
     * Zeigt das Fenster neben der Stelle {@code x, y} in {@code owner} (Koordinaten des Mausklicks). Die Breite folgt
     * dem Text bis {@link #MAX_WIDTH}; am Fensterrand rückt Swing es hinein.
     */
    public void showAt(Component owner, int x, int y) {
        int width = Math.min(MAX_WIDTH, naturalWidth() + 2 * PAD);
        int inner = width - 2 * PAD;
        body.setSize(inner, Short.MAX_VALUE);
        int height = PAD * 2 + (title.isVisible() ? title.getPreferredSize().height + 6 : 0) + body.heightFor(inner)
                + (links.isVisible() ? links.getPreferredSize().height : 0);
        content.setPreferredSize(new Dimension(inner, height - 2 * PAD));
        setPopupSize(width, height);
        show(owner, x + OFFSET, y + OFFSET);
    }

    private int naturalWidth() {
        int widest = title.isVisible() ? title.getPreferredSize().width : 0;
        FontMetrics metrics = body.getFontMetrics(body.getFont());
        for (String line : body.getText().split("\n")) {
            widest = Math.max(widest, metrics.stringWidth(line));
        }
        if (links.isVisible()) {
            widest = Math.max(widest, links.getPreferredSize().width);
        }
        return Math.max(160, widest + 2);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Weicher Schatten nach unten rechts, dann die Fläche mit feinem Rand.
            for (int i = 3; i >= 1; i--) {
                g.setColor(new Color(0, 0, 0, 10));
                g.fillRoundRect(i, i, getWidth() - i, getHeight() - i, 12, 12);
            }
            g.setColor(Color.WHITE);
            g.fillRoundRect(0, 0, getWidth() - 3, getHeight() - 3, 12, 12);
            g.setColor(Comic.LINE);
            g.drawRoundRect(0, 0, getWidth() - 4, getHeight() - 4, 12, 12);
        } finally {
            g.dispose();
        }
    }

    // ---- für Tests

    public String titleText() {
        return title.isVisible() ? title.getText() : "";
    }

    public String bodyText() {
        return body.getText();
    }

    /** Die Texte der Links, in Reihenfolge. */
    public java.util.List<String> linkTexts() {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (Component component : links.getComponents()) {
            if (component instanceof JLabel label
                    && Boolean.TRUE.equals(label.getClientProperty("info-popup.link"))) {
                texts.add(label.getText());
            }
        }
        return texts;
    }

    /** Klickt den Link {@code index} (ab 0) an. */
    public void clickLink(int index) {
        int seen = -1;
        for (Component component : links.getComponents()) {
            if (component instanceof JComponent label
                    && Boolean.TRUE.equals(label.getClientProperty("info-popup.link")) && ++seen == index) {
                MouseEvent click = new MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false);
                for (java.awt.event.MouseListener listener : label.getMouseListeners()) {
                    listener.mouseClicked(click);
                }
                return;
            }
        }
    }
}
