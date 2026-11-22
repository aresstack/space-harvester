package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.perspective.Perspective;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
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
 * das Menü mit allen Perspektiven; Platzhalter tragen „in Vorbereitung“. Das Logo bleibt in jeder Perspektive gleich.
 * Der Name ist der einzige kürzbare Teil der Kopfzeile („Space Har…“, siehe HeaderLayout).
 */
public final class PerspectiveSwitcher extends JPanel {

    static final String TOOLTIP = "Perspektive wechseln";
    static final String PREPARING = "in Vorbereitung";

    private final Perspectives perspectives;
    private final JLabel title = new JLabel();
    private final JLabel arrow = new JLabel(new DownTriangle());
    private final KeyboardFocusRing ring;
    private JPopupMenu menu;

    public PerspectiveSwitcher(Icon logo, Perspectives perspectives) {
        super(new BorderLayout(2, 0));
        this.perspectives = perspectives;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(TOOLTIP);
        title.setFont(Comic.semiBold(18f));
        title.setForeground(Comic.INK);
        title.setIcon(logo);
        title.setIconTextGap(8);
        arrow.setForeground(Comic.INK);
        arrow.setVerticalAlignment(SwingConstants.CENTER);
        arrow.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
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
        int fixed = title.getIcon() == null ? 0 : title.getIcon().getIconWidth() + title.getIconTextGap();
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
