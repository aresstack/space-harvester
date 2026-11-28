package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Ein Fenster des Bordcomputers über der aktuellen Ansicht: dunkler Sternenhimmel, leuchtender Rahmen, eine selbst
 * gezeichnete Schiffssilhouette mit Abtastlinie und ein Datensatz, der Zeile für Zeile erscheint. Liegt als Overlay in
 * der Ebene des Fensters, ändert also nichts an der Ansicht darunter; Esc oder der Kreis schließen es und geben den
 * Fokus zurück. Was angezeigt wird, kommt vom Aufrufer – das Fenster kennt keinen Auslöser.
 */
public final class ShipArchive extends JPanel {

    static final Color GLOW = AppColors.of("app.ShipArchive.glow");
    static final Color TEXT = AppColors.of("app.ShipArchive.text");
    static final Color DIM = AppColors.of("app.ShipArchive.dim");
    private static final int WIDTH = 620;
    private static final int TYPE_DELAY_MS = 14;

    private final List<String> lines;
    private final JLabel body = new JLabel();
    private final Timer typing;
    private final Timer scan;
    private final Component focusBefore;
    private int shown;
    private double scanLine;

    private ShipArchive(String heading, List<String> lines, Component focusBefore) {
        this.lines = List.copyOf(lines);
        this.focusBefore = focusBefore;
        setOpaque(false);
        setLayout(new GridBagLayout());
        // Klicks auf den abgedunkelten Rand schlucken, damit darunter nichts ausgelöst wird.
        addMouseListener(new MouseAdapter() { });

        Starfield sky = new Starfield(Starfield.Mode.DRIFT);
        JComponent ship = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                paintSilhouette((Graphics2D) g, getWidth());
            }
        };
        ship.setPreferredSize(new Dimension(WIDTH - 60, 84));
        sky.setLayout(new BorderLayout(0, 8));
        sky.setBorder(BorderFactory.createEmptyBorder(16, 22, 20, 22));
        JLabel title = new JLabel(heading);
        title.setFont(Comic.semiBold(13f).deriveFont(Font.BOLD));
        title.setForeground(GLOW);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(title, BorderLayout.CENTER);
        head.add(new EscCircle(this::close, "Bordarchiv schließen (Esc)"), BorderLayout.EAST);
        body.setFont(Comic.regular(13f));
        body.setForeground(TEXT);
        body.setVerticalAlignment(JLabel.TOP);
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setOpaque(false);
        center.add(ship, BorderLayout.NORTH);
        center.add(body, BorderLayout.CENTER);
        sky.add(head, BorderLayout.NORTH);
        sky.add(center, BorderLayout.CENTER);

        JPanel frame = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int i = 6; i >= 1; i--) {
                    g2.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), 10 + (6 - i) * 6));
                    g2.setStroke(new BasicStroke(i * 2f));
                    g2.draw(new RoundRectangle2D.Double(6, 6, getWidth() - 13, getHeight() - 13, 22, 22));
                }
                g2.dispose();
            }

            @Override
            protected void paintChildren(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setClip(new RoundRectangle2D.Double(8, 8, getWidth() - 16, getHeight() - 16, 20, 20));
                super.paintChildren(g2);
                g2.dispose();
            }
        };
        frame.setOpaque(false);
        frame.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        frame.add(sky, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(WIDTH, 470));
        add(frame);

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });
        typing = new Timer(TYPE_DELAY_MS, e -> typeNext());
        scan = new Timer(30, e -> {
            scanLine = (scanLine + 0.02) % 1.0;
            repaint();
        });
        getAccessibleContext().setAccessibleName(heading);
    }

    /**
     * Öffnet das Archiv mittig über dem Fenster von {@code anywhere}. Ein schon offenes Archiv bleibt und wird nicht
     * verdoppelt.
     */
    public static ShipArchive open(Component anywhere, String heading, List<String> lines) {
        JRootPane root = SwingUtilities.getRootPane(anywhere);
        if (root == null) {
            throw new IllegalStateException("Kein Fenster für das Bordarchiv");
        }
        JLayeredPane layers = root.getLayeredPane();
        for (Component child : layers.getComponentsInLayer(JLayeredPane.MODAL_LAYER)) {
            if (child instanceof ShipArchive open) {
                return open;
            }
        }
        ShipArchive archive = new ShipArchive(heading, lines,
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        archive.setBounds(0, 0, layers.getWidth(), layers.getHeight());
        layers.add(archive, JLayeredPane.MODAL_LAYER);
        layers.addComponentListener(archive.follow);
        archive.typing.start();
        archive.scan.start();
        archive.requestFocusInWindow();
        layers.revalidate();
        layers.repaint();
        return archive;
    }

    private final java.awt.event.ComponentAdapter follow = new java.awt.event.ComponentAdapter() {
        @Override
        public void componentResized(java.awt.event.ComponentEvent event) {
            setBounds(0, 0, event.getComponent().getWidth(), event.getComponent().getHeight());
            revalidate();
        }
    };

    /** Schließt das Archiv; die Ansicht darunter bleibt, der Fokus kehrt zurück. */
    public void close() {
        typing.stop();
        scan.stop();
        java.awt.Container parent = getParent();
        if (parent != null) {
            parent.removeComponentListener(follow);
            parent.remove(this);
            parent.repaint();
        }
        if (focusBefore != null && focusBefore.isShowing()) {
            focusBefore.requestFocusInWindow();
        }
    }

    /** Zeigt sofort den ganzen Text (für Tests und Bildschirmfotos). */
    public void finish() {
        shown = totalLength();
        render();
        typing.stop();
    }

    /** Der angezeigte Text ohne Auszeichnung (für Tests). */
    public String shownText() {
        StringBuilder text = new StringBuilder();
        int left = shown;
        for (String line : lines) {
            if (left <= 0) {
                break;
            }
            text.append(line, 0, Math.min(left, line.length())).append('\n');
            left -= line.length();
        }
        return text.toString();
    }

    public boolean isOpen() {
        return getParent() != null;
    }

    private int totalLength() {
        return lines.stream().mapToInt(String::length).sum();
    }

    private void typeNext() {
        shown = Math.min(totalLength(), shown + 2);
        render();
        if (shown >= totalLength()) {
            typing.stop();
        }
    }

    private void render() {
        StringBuilder html = new StringBuilder("<html><body style='width:" + (WIDTH - 190) + "px'>");
        int left = shown;
        for (String line : lines) {
            if (left <= 0) {
                break;
            }
            String part = line.substring(0, Math.min(left, line.length()));
            left -= line.length();
            boolean label = part.contains(":");
            String escaped = part.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            if (label) {
                int colon = escaped.indexOf(':');
                escaped = "<span style='color:" + Comic.css(DIM) + "'>" + escaped.substring(0, colon + 1) + "</span>"
                        + escaped.substring(colon + 1);
            }
            html.append("<p style='margin-bottom:6px'>").append(escaped).append("</p>");
        }
        body.setText(html.append("</body></html>").toString());
    }

    /** Eine eigene, schlichte Schiffssilhouette (keine fremde Vorlage) mit wandernder Abtastlinie. */
    private void paintSilhouette(Graphics2D g, int width) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cx = width / 2;
        int top = 12;
        Path2D hull = new Path2D.Double();
        hull.moveTo(cx - 150, top + 44);
        hull.lineTo(cx - 110, top + 22);
        hull.lineTo(cx - 40, top + 16);
        hull.lineTo(cx + 10, top);
        hull.lineTo(cx + 120, top + 10);
        hull.lineTo(cx + 168, top + 30);
        hull.lineTo(cx + 120, top + 50);
        hull.lineTo(cx + 10, top + 58);
        hull.lineTo(cx - 40, top + 46);
        hull.lineTo(cx - 110, top + 52);
        hull.closePath();
        g2.setPaint(new GradientPaint(cx - 150, top, AppColors.of("app.ShipArchive.paintSilhouette"), cx + 170, top + 60, AppColors.of("app.ShipArchive.paintSilhouette2")));
        g2.fill(hull);
        g2.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), 150));
        g2.setStroke(new BasicStroke(1.3f));
        g2.draw(hull);
        // Triebwerke und Fensterreihe
        g2.setColor(AppColors.of("app.ShipArchive.paintSilhouette3"));
        g2.fillOval(cx - 158, top + 38, 8, 8);
        g2.fillOval(cx - 118, top + 46, 6, 6);
        g2.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), 200));
        for (int i = 0; i < 9; i++) {
            g2.fillRect(cx - 20 + i * 14, top + 26, 6, 3);
        }
        // Abtastlinie
        int x = (int) (cx - 160 + scanLine * 340);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g2.setPaint(new GradientPaint(x - 18, 0, new Color(0, 0, 0, 0), x, 0, GLOW));
        g2.fillRect(x - 18, top - 6, 18, 72);
        g2.dispose();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(AppColors.of("app.ShipArchive.paintComponent"));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
    }
}
