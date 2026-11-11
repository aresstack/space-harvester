package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.Objects;

/**
 * Ein selbst gezeichnetes Hinweisfenster an der Mausposition: Überschrift, umbrechender Fließtext in Proportionalschrift
 * und darunter wahlweise Links („Zum Beleg im Text“). Es ist höchstens {@link #MAX_WIDTH} breit, schmaler bei kurzem
 * Text; Überschrift und Text brechen um. Wird der Text höher als das Fenster hergibt, scrollt er, Überschrift und
 * Links bleiben stehen. Es schließt sich bei Klick daneben, mit Esc oder beim Klick auf einen Link; der erste Link hat
 * den Tastaturfokus (Enter oder Leertaste). Es erscheint leicht versetzt zum Mauszeiger, damit ein Doppelklick auf
 * das Element darunter ankommt.
 *
 * <p>Gedacht für Details zu einem angeklickten Element (Knoten, Kante, Tabellenzeile), die keinen festen Platz im
 * Reiter verdienen; der Reiter selbst bleibt ganz dem Inhalt.
 */
public final class InfoPopup extends JPopupMenu {

    /** Breite der Fläche, mehr nimmt sich der Text nie. */
    public static final int MAX_WIDTH = 480;
    /** Höhe, wenn kein Fenster eine kleinere vorgibt. */
    public static final int MAX_HEIGHT = 600;
    private static final int PAD = 12;
    private static final int OFFSET = 10;
    private static final int GAP = 6;

    private final WrappedText title = Comic.wrapped(" ", Comic.INK, 13f);
    private final WrappedText body = Comic.wrapped(" ", Comic.TEXT, 13f);
    private final JScrollPane scroll = Comic.scroll(body);
    private final JPanel links = new JPanel();
    private final JPanel content = new JPanel();

    public InfoPopup() {
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD));
        setLightWeightPopupEnabled(true);
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        title.setFont(Comic.semiBold(13f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        links.setOpaque(false);
        links.setLayout(new BoxLayout(links, BoxLayout.X_AXIS));
        links.setAlignmentX(LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(GAP));
        content.add(scroll);
        content.add(links);
        add(content);
        // Esc schließt auch, wenn ein Link den Fokus hat.
        content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        content.getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setVisible(false);
            }
        });
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

    /** Ein Link unter dem Text; Klick, Enter oder Leertaste schließt das Fenster und führt {@code action} aus. */
    public InfoPopup link(String text, Runnable action) {
        Objects.requireNonNull(action, "action");
        JLabel link = new JLabel(text);
        link.setFont(Comic.regular(13f));
        link.setForeground(Comic.BLUE);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.putClientProperty("info-popup.link", Boolean.TRUE);
        @SuppressWarnings("unchecked")
        Map<java.awt.font.TextAttribute, Object> attributes =
                (Map<java.awt.font.TextAttribute, Object>) link.getFont().getAttributes();
        attributes.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
        link.setFont(link.getFont().deriveFont(attributes));
        link.setFocusable(true);
        link.getAccessibleContext().setAccessibleName(text);
        link.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        Runnable go = () -> {
            setVisible(false);
            action.run();
        };
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                go.run();
            }
        });
        link.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                    e.consume();
                    go.run();
                }
            }
        });
        link.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                link.setBorder(BorderFactory.createLineBorder(Comic.BLUE, 1, true));
            }

            @Override
            public void focusLost(FocusEvent e) {
                link.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
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
     * dem Text bis {@link #MAX_WIDTH}, die Höhe dem Fenster von {@code owner} (sonst {@link #MAX_HEIGHT}); am
     * Fensterrand rückt Swing es hinein. Der erste Link bekommt den Tastaturfokus.
     */
    public void showAt(Component owner, int x, int y) {
        Window window = SwingUtilities.getWindowAncestor(owner);
        Dimension size = sizeFor(window == null ? MAX_HEIGHT : Math.min(MAX_HEIGHT, window.getHeight() - 2 * PAD));
        setPopupSize(size.width, size.height);
        show(owner, x + OFFSET, y + OFFSET);
        firstLink().ifPresent(link -> SwingUtilities.invokeLater(link::requestFocusInWindow));
    }

    /**
     * Die Größe des Fensters für höchstens {@code availableHeight} Pixel: Breite nach Text bis {@link #MAX_WIDTH};
     * Überschrift und Links ganz, der Text scrollt, wenn er nicht mehr hineinpasst.
     */
    public Dimension sizeFor(int availableHeight) {
        int width = Math.min(MAX_WIDTH, naturalWidth() + 2 * PAD);
        int inner = width - 2 * PAD;
        int titleHeight = title.isVisible() ? title.heightFor(inner) + GAP : 0;
        int linksHeight = links.isVisible() ? links.getPreferredSize().height : 0;
        int bodyHeight = body.heightFor(inner);
        int fixed = 2 * PAD + titleHeight + linksHeight;
        int bodyRoom = Math.max(48, Math.min(bodyHeight, availableHeight - fixed));
        boolean scrolls = bodyRoom < bodyHeight;
        int barWidth = scrolls ? scroll.getVerticalScrollBar().getPreferredSize().width : 0;
        scroll.setVerticalScrollBarPolicy(scrolls ? JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
                : JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        title.setPreferredSize(new Dimension(inner, title.isVisible() ? title.heightFor(inner) : 0));
        title.setMaximumSize(title.getPreferredSize());
        links.setMaximumSize(new Dimension(inner, linksHeight));
        body.setPreferredSize(new Dimension(inner - barWidth, scrolls ? body.heightFor(inner - barWidth) : bodyHeight));
        scroll.setPreferredSize(new Dimension(inner, bodyRoom));
        scroll.setMaximumSize(new Dimension(inner, bodyRoom));
        content.setPreferredSize(new Dimension(inner, fixed - 2 * PAD + bodyRoom));
        return new Dimension(width, fixed + bodyRoom);
    }

    /** Ob der Text bei der zuletzt berechneten Größe scrollt. */
    public boolean scrolls() {
        return scroll.getVerticalScrollBarPolicy() == JScrollPane.VERTICAL_SCROLLBAR_ALWAYS;
    }

    private int naturalWidth() {
        int widest = 0;
        FontMetrics titleMetrics = title.getFontMetrics(title.getFont());
        if (title.isVisible()) {
            widest = titleMetrics.stringWidth(title.getText());
        }
        FontMetrics metrics = body.getFontMetrics(body.getFont());
        for (String line : body.getText().split("\n")) {
            widest = Math.max(widest, metrics.stringWidth(line));
        }
        if (links.isVisible()) {
            widest = Math.max(widest, links.getPreferredSize().width);
        }
        return Math.max(160, widest + 2);
    }

    private java.util.Optional<JComponent> firstLink() {
        for (Component component : links.getComponents()) {
            if (component instanceof JComponent label
                    && Boolean.TRUE.equals(label.getClientProperty("info-popup.link"))) {
                return java.util.Optional.of(label);
            }
        }
        return java.util.Optional.empty();
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

    /** Drückt Enter auf dem Link {@code index} (ab 0), wie ein Tastaturbenutzer. */
    public void pressEnterOnLink(int index) {
        int seen = -1;
        for (Component component : links.getComponents()) {
            if (component instanceof JComponent label
                    && Boolean.TRUE.equals(label.getClientProperty("info-popup.link")) && ++seen == index) {
                KeyEvent enter = new KeyEvent(label, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n');
                for (java.awt.event.KeyListener listener : label.getKeyListeners()) {
                    listener.keyPressed(enter);
                }
                return;
            }
        }
    }
}
