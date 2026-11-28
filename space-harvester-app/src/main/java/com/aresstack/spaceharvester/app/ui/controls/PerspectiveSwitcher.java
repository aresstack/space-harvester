package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.perspective.Perspective;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

/**
 * Der Titel oben links als Umschalter der Perspektiven (#1141, Angelo 22.11.2026): Logo und Name der gewählten
 * Perspektive, rechts daneben ein Dreieck. Ein Klick auf Titel oder Dreieck (oder Leertaste/Eingabe/Alt+↓) öffnet
 * das Menü mit allen Perspektiven; Platzhalter tragen „in Vorbereitung“. Das Logo bleibt in jeder Perspektive gleich
 * und öffnet das Menü nicht. Unter dem Namen kann die alte Schiffskennung durchschimmern ({@link #showHeritage}).
 * Der Name ist der einzige kürzbare Teil der Kopfzeile („Space Har…“, siehe HeaderLayout).
 */
public final class PerspectiveSwitcher extends JPanel {

    static final String TOOLTIP = "Perspektive wechseln";
    static final String PREPARING = "in Vorbereitung";

    private final Perspectives perspectives;
    private final com.aresstack.spaceharvester.app.ui.IconButton logo;
    private final HeritageTitle title = new HeritageTitle();
    private final JLabel arrow = new JLabel(new DownTriangle());
    private final KeyboardFocusRing ring;
    private JPopupMenu menu;

    public PerspectiveSwitcher(Icon logo, Perspectives perspectives) {
        super(new BorderLayout(2, 0));
        this.perspectives = perspectives;
        setOpaque(false);
        setFocusable(true);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(TOOLTIP);
        title.setFont(Comic.semiBold(18f));
        title.setForeground(Comic.INK);
        this.logo = new com.aresstack.spaceharvester.app.ui.IconButton(logo, "Seitenleiste einklappen");
        this.logo.setBorder(BorderFactory.createEmptyBorder());
        arrow.setForeground(Comic.INK);
        arrow.setVerticalAlignment(SwingConstants.CENTER);
        arrow.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        add(this.logo, BorderLayout.WEST);
        add(title, BorderLayout.CENTER);
        add(arrow, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        ring = KeyboardFocusRing.install(this);
        MouseAdapter opener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1) {
                    requestFocusInWindow();
                    openMenu();
                }
            }
        };
        addMouseListener(opener);
        title.addMouseListener(opener);
        arrow.addMouseListener(opener);
        for (String key : new String[] {"SPACE", "ENTER", "alt DOWN", "DOWN"}) {
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "open");
        }
        getActionMap().put("open", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openMenu();
            }
        });
        perspectives.onChange(ignored -> update());
        update();
        getAccessibleContext().setAccessibleDescription(TOOLTIP);
    }

    private void update() {
        Perspective current = perspectives.current();
        title.setText(current == null ? "" : current.title());
        getAccessibleContext().setAccessibleName(current == null ? TOOLTIP : current.title() + ", " + TOOLTIP);
        revalidate();
        repaint();
    }

    /** Der Name kann gekürzt werden, Logo und Dreieck bleiben. */
    @Override
    public Dimension getMinimumSize() {
        Dimension pref = getPreferredSize();
        int fixed = logo.getPreferredSize().width;
        return new Dimension(fixed + arrow.getPreferredSize().width + 24, pref.height);
    }

    /** Öffnet das Menü unter dem Titel. */
    public void openMenu() {
        menu = menu();
        menu.show(this, 0, getHeight());
    }

    /** Das Menü mit allen Perspektiven (für Tests ohne Bildschirm). */
    public JPopupMenu menu() {
        JPopupMenu popup = new JPopupMenu();
        for (Perspective perspective : perspectives.all()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(itemText(perspective));
            item.setFont(Comic.regular(13f));
            item.setSelected(perspectives.isCurrent(perspective.id()));
            item.setToolTipText(perspective.hint().isBlank() ? null : perspective.hint());
            item.getAccessibleContext().setAccessibleName(perspective.title()
                    + (perspective.ready() ? "" : ", " + PREPARING));
            item.addActionListener(e -> perspectives.select(perspective.id()));
            popup.add(item);
        }
        return popup;
    }

    /** Name fett, darunter blass der Hinweis; Platzhalter mit „in Vorbereitung“. */
    static String itemText(Perspective perspective) {
        String name = escape(perspective.title());
        String state = perspective.ready() ? "" : " <span style='color:" + Comic.css(Comic.ORANGE) + "'>· "
                + PREPARING + "</span>";
        String hint = perspective.hint().isBlank() ? ""
                : "<br><span style='color:" + Comic.css(Comic.MUTED) + "'>" + escape(perspective.hint()) + "</span>";
        return "<html><b>" + name + "</b>" + state + hint + "</html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void onSidebarToggle(Runnable toggle) {
        logo.addActionListener(event -> toggle.run());
    }

    public void sidebarState(boolean available, boolean open) {
        String tooltip = !available ? "Keine Seitenleiste verfügbar"
                : open ? "Seitenleiste einklappen" : "Seitenleiste ausklappen";
        logo.setToolTipText(tooltip);
        logo.getAccessibleContext().setAccessibleName(tooltip);
    }

    /** Die Seitenleiste hat ihren eigenen Umschalter, der sie auch zuklappt: Das Logo verspricht nichts. */
    public void sidebarElsewhere() {
        logo.setToolTipText(null);
        logo.getAccessibleContext().setAccessibleName(title.getText());
    }

    /** Das Logo oben links, für versteckte Auslöser. */
    public JComponent logo() {
        return logo;
    }

    /**
     * Lässt unter dem Namen eine alte, überlackierte Kennung durchschimmern: blendet sie verwittert ein, hält sie und
     * blendet sie wieder aus. Läuft über einen kurzen Zeitgeber, der danach stoppt.
     */
    public void showHeritage(String oldName, java.time.Duration hold) {
        title.reveal(oldName, hold);
    }

    /** Die gerade durchschimmernde Kennung, leer wenn keine (für Tests). */
    public String heritage() {
        return title.ghost == null ? "" : title.ghost;
    }

    /** Der Titel, unter dem eine alte Kennung durchschimmern kann: verwitterte Großbuchstaben mit Kratzern. */
    private static final class HeritageTitle extends JLabel {

        private static final int FADE_MS = 700;
        private String ghost;
        private float alpha;
        private javax.swing.Timer timer;

        void reveal(String oldName, java.time.Duration hold) {
            if (timer != null) {
                timer.stop();
            }
            ghost = oldName;
            long start = System.currentTimeMillis();
            long total = FADE_MS * 2L + hold.toMillis();
            timer = new javax.swing.Timer(40, e -> {
                long t = System.currentTimeMillis() - start;
                if (t >= total) {
                    ((javax.swing.Timer) e.getSource()).stop();
                    ghost = null;
                    alpha = 0;
                } else if (t < FADE_MS) {
                    alpha = t / (float) FADE_MS;
                } else if (t > total - FADE_MS) {
                    alpha = (total - t) / (float) FADE_MS;
                } else {
                    alpha = 1f;
                }
                repaint();
            });
            alpha = 0;
            timer.start();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (ghost != null && alpha > 0) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                // Die alte Kennung groß und gesperrt quer unter dem Namen, wie unter frischer Farbe durchscheinend.
                java.awt.FontMetrics titleMetrics = getFontMetrics(getFont());
                int titleWidth = titleMetrics.stringWidth(getText());
                // So gesperrt, dass die alte Kennung genau unter dem Namen liegt.
                java.awt.Font base = Comic.semiBold(19f).deriveFont(java.awt.Font.BOLD);
                float tracking = Math.max(0f, (titleWidth - getFontMetrics(base).stringWidth(ghost))
                        / (float) Math.max(1, ghost.length() - 1) / base.getSize2D());
                java.awt.Font font = base.deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, tracking));
                g2.setFont(font);
                java.awt.FontMetrics metrics = g2.getFontMetrics();
                int x = getInsets().left;
                int y = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
                int width = metrics.stringWidth(ghost);
                g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.75f * alpha));
                g2.setPaint(new java.awt.GradientPaint(x, y - metrics.getAscent(), new java.awt.Color(0xB07A45), x,
                        y, new java.awt.Color(0x6E4A2A)));
                g2.drawString(ghost, x, y);
                // Abgeplatzte Stellen: kleine Flecken in der Hintergrundfarbe lassen die Schrift löchrig wirken.
                g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.9f * alpha));
                g2.setColor(Comic.SURFACE);
                java.util.Random chips = new java.util.Random(ghost.hashCode());
                for (int i = 0; i < 40; i++) {
                    int sx = x + chips.nextInt(Math.max(1, width));
                    int sy = y - chips.nextInt(Math.max(1, metrics.getAscent()));
                    g2.fillOval(sx, sy, 1 + chips.nextInt(3), 1 + chips.nextInt(2));
                }
                g2.dispose();
            }
            super.paintComponent(g);
            paintHat(g);
        }

        /**
         * In der Weihnachtszeit sitzt die Mütze auf dem „H“ von „Harvester“ (Angelo 28.11.2026), nicht auf dem Logo.
         * Ist der Name so weit gekürzt, dass das „H“ fehlt, oder heißt die Perspektive anders, gibt es keine Mütze.
         */
        private void paintHat(java.awt.Graphics g) {
            Rectangle letter = hatLetter();
            if (letter != null) {
                SantaHat.paint(g, letter.x - SantaHat.WIDTH * HAT_OVERHANG, letter.y + SantaHat.height() * HAT_SINK);
            }
        }

        /** Die Oberkante des „H“, auf dem die Mütze sitzt (x, y, Breite); {@code null} ohne Mütze. */
        Rectangle hatLetter() {
            String text = getText();
            int at = text == null ? -1 : text.indexOf(HAT_WORD);
            if (at < 0 || !Season.christmasNow()) {
                return null;
            }
            java.awt.FontMetrics metrics = getFontMetrics(getFont());
            int x = getInsets().left + metrics.stringWidth(text.substring(0, at));
            int width = metrics.charWidth(HAT_WORD.charAt(0));
            if (x + width > getWidth() - getInsets().right) {
                return null;
            }
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            int capHeight = (int) Math.round(getFont().createGlyphVector(metrics.getFontRenderContext(),
                    HAT_WORD.substring(0, 1)).getVisualBounds().getHeight());
            return new Rectangle(x, baseline - capHeight, width, capHeight);
        }
    }

    /** Das Wort im Titel, dessen erster Buchstabe in der Weihnachtszeit die Mütze trägt. */
    static final String HAT_WORD = "Harvester";
    /** Wie weit die Mütze links über den Buchstaben ragt und wie tief sie in ihn sinkt, als Anteil ihrer Maße. */
    static final double HAT_OVERHANG = 0.2;
    static final double HAT_SINK = 0.25;

    /** Wo im Titel die Mütze sitzt (für Tests); {@code null} ohne Mütze. */
    java.awt.Rectangle hatLetter() {
        return title.hatLetter();
    }

    /** Der Name im Titel (für Tests). */
    public String shownTitle() {
        return title.getText();
    }

    /** Fokus über die Tastatur: gelber Rahmen um Titel und Dreieck. */
    @Override
    protected void paintChildren(java.awt.Graphics graphics) {
        super.paintChildren(graphics);
        if (ring.shown()) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Comic.YELLOW);
            g.setStroke(new java.awt.BasicStroke(2f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
            g.dispose();
        }
    }
}
