package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.controls.ViewerWheel;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Vergrößerter Mermaid-Betrachter (aus askai-java8 {@code MermaidViewerDialog}): dunkle, rahmenlose Fläche mit
 * Ziehen zum Verschieben, Strg+Mausrad-Zoom am Cursor (Rad allein und Shift+Rad verschieben, {@link ViewerWheel}), halbtransparenten runden Überlagerungsknöpfen (verkleinern /
 * vergrößern / einpassen sowie kopieren und speichern) und einer Infozeile unten.
 *
 * <p>Das Diagramm wird einmal in hoher Auflösung aus seinem Mermaid-Quelltext neu gerendert (scharfe
 * Vektorausgabe) und dann als Bitmap verschoben und gezoomt, sodass das kopierte oder gespeicherte Bild scharf
 * bleibt.</p>
 */
public final class MermaidViewerDialog extends JDialog {

    private static final int HIGH_RES_WIDTH = 2600;
    private static final int OVERLAY_SIZE = 40;
    private static final int ZOOM_BUTTON_SIZE = 36;
    private static final int BUTTON_GAP = 4;

    private final String diagramCode;
    private final ZoomableImagePanel imagePanel = new ZoomableImagePanel();
    private final JLabel infoLabel = new JLabel(" ", SwingConstants.CENTER);
    private int imageWidth;
    private int imageHeight;

    private MermaidViewerDialog(Window owner, final String diagramCode, final MermaidImageRenderer imageRenderer,
                                BufferedImage initial) {
        super(owner instanceof Frame ? (Frame) owner : null, "Mermaid-Diagramm", true);
        if (owner != null) {
            setIconImages(owner.getIconImages()); // ohne Besitzer-Fenster bliebe die Java-Tasse
        }
        this.diagramCode = diagramCode;
        setLayout(new BorderLayout(0, 0));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration());
        int screenMaxW = screen.width - insets.left - insets.right - 80;
        int screenMaxH = screen.height - insets.top - insets.bottom - 120;

        imagePanel.setPreferredSize(new Dimension(600, 400));

        final JLayeredPane layered = new JLayeredPane();
        layered.setLayout(null);

        final OverlayButton zoomOut = new OverlayButton(DiagramViewerIcons.zoomOut(), ZOOM_BUTTON_SIZE, "Verkleinern (−)");
        final OverlayButton zoomIn = new OverlayButton(DiagramViewerIcons.zoomIn(), ZOOM_BUTTON_SIZE, "Vergrößern (+)");
        final OverlayButton zoomReset = new OverlayButton(DiagramViewerIcons.fit(), ZOOM_BUTTON_SIZE, "Einpassen (0)");
        final OverlayButton copyBtn = new OverlayButton(DiagramViewerIcons.copy(), OVERLAY_SIZE, "Bild kopieren");
        final OverlayButton saveBtn = new OverlayButton(DiagramViewerIcons.save(), OVERLAY_SIZE, "Als PNG speichern");

        layered.add(imagePanel, JLayeredPane.DEFAULT_LAYER);
        layered.add(zoomOut, JLayeredPane.PALETTE_LAYER);
        layered.add(zoomIn, JLayeredPane.PALETTE_LAYER);
        layered.add(zoomReset, JLayeredPane.PALETTE_LAYER);
        layered.add(copyBtn, JLayeredPane.PALETTE_LAYER);
        layered.add(saveBtn, JLayeredPane.PALETTE_LAYER);
        setHidden(zoomOut, zoomIn, zoomReset, copyBtn, saveBtn);

        layered.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = layered.getWidth();
                int h = layered.getHeight();
                imagePanel.setBounds(0, 0, w, h);
                saveBtn.setBounds(w - OVERLAY_SIZE - 8, 8, OVERLAY_SIZE, OVERLAY_SIZE);
                copyBtn.setBounds(w - 2 * OVERLAY_SIZE - 16, 8, OVERLAY_SIZE, OVERLAY_SIZE);
                int zoomY = h - ZOOM_BUTTON_SIZE - 10;
                int zoomX = w - (3 * ZOOM_BUTTON_SIZE + 2 * BUTTON_GAP) - 10;
                zoomOut.setBounds(zoomX, zoomY, ZOOM_BUTTON_SIZE, ZOOM_BUTTON_SIZE);
                zoomIn.setBounds(zoomX + ZOOM_BUTTON_SIZE + BUTTON_GAP, zoomY, ZOOM_BUTTON_SIZE, ZOOM_BUTTON_SIZE);
                zoomReset.setBounds(zoomX + 2 * (ZOOM_BUTTON_SIZE + BUTTON_GAP), zoomY,
                        ZOOM_BUTTON_SIZE, ZOOM_BUTTON_SIZE);
            }
        });
        add(layered, BorderLayout.CENTER);

        infoLabel.setFont(infoLabel.getFont().deriveFont(Font.ITALIC, 11f));
        infoLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(infoLabel, BorderLayout.SOUTH);

        // Glasscheibe: Hover zeigt die Überlagerungsknöpfe, das Rad zoomt am Cursor, Ziehen verschiebt.
        final JPanel glass = new JPanel(null);
        glass.setOpaque(false);
        setGlassPane(glass);
        glass.setVisible(true);
        glass.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(glass, e.getPoint(), layered);
                int w = layered.getWidth();
                int h = layered.getHeight();
                boolean inImage = p.x >= 0 && p.x < w && p.y >= 0 && p.y < h;
                boolean showTop = inImage && p.x >= w - 2 * OVERLAY_SIZE - 24 && p.y <= OVERLAY_SIZE + 16;
                boolean showZoom = inImage && p.x >= w - (3 * ZOOM_BUTTON_SIZE + 2 * BUTTON_GAP) - 20
                        && p.y >= h - ZOOM_BUTTON_SIZE - 20;
                copyBtn.setVisible(showTop);
                saveBtn.setVisible(showTop);
                zoomOut.setVisible(showZoom);
                zoomIn.setVisible(showZoom);
                zoomReset.setVisible(showZoom);
            }
        });
        glass.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                setHidden(zoomOut, zoomIn, zoomReset, copyBtn, saveBtn);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                imagePanel.dragStart = SwingUtilities.convertPoint(glass, e.getPoint(), imagePanel);
                Cursors.set(imagePanel, Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                imagePanel.dragStart = null;
                Cursors.set(imagePanel, Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(glass, e.getPoint(), layered);
                int w = layered.getWidth();
                int h = layered.getHeight();
                if (p.x >= w - OVERLAY_SIZE - 20 && p.y <= OVERLAY_SIZE + 16) {
                    saveImage();
                    return;
                }
                if (p.x >= w - 2 * OVERLAY_SIZE - 24 && p.x < w - OVERLAY_SIZE - 16 && p.y <= OVERLAY_SIZE + 16) {
                    copyImage();
                    return;
                }
                int zoomY = h - ZOOM_BUTTON_SIZE - 10;
                int zoomX = w - (3 * ZOOM_BUTTON_SIZE + 2 * BUTTON_GAP) - 10;
                if (p.x >= zoomX - 5 && p.y >= zoomY - 5) {
                    int relX = p.x - zoomX;
                    if (relX < ZOOM_BUTTON_SIZE) {
                        imagePanel.zoomAt(1.0 / 1.25, null);
                    } else if (relX < 2 * ZOOM_BUTTON_SIZE + BUTTON_GAP) {
                        imagePanel.zoomAt(1.25, null);
                    } else {
                        imagePanel.resetView();
                    }
                    updateInfo();
                }
            }
        });
        glass.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (imagePanel.dragStart != null) {
                    Point p = SwingUtilities.convertPoint(glass, e.getPoint(), imagePanel);
                    imagePanel.offsetX += p.x - imagePanel.dragStart.x;
                    imagePanel.offsetY += p.y - imagePanel.dragStart.y;
                    imagePanel.dragStart = p;
                    imagePanel.repaint();
                }
            }
        });
        glass.addMouseWheelListener(new ViewerWheel(new ViewerWheel.Target() {
            @Override
            public boolean pan(int dx, int dy) {
                return imagePanel.pan(dx, dy);
            }

            @Override
            public void zoom(double factor, Point at) {
                imagePanel.zoomAt(factor, SwingUtilities.convertPoint(glass, at, imagePanel));
                updateInfo();
            }

            @Override
            public void previous() {
                // ein Diagramm hat keine Seiten
            }

            @Override
            public void next() {
                // ein Diagramm hat keine Seiten
            }
        }));

        installKeyBindings();

        int fixedH = Math.min(screen.height * 6 / 10, screenMaxH);
        int fixedW = Math.min(fixedH * 16 / 10, screenMaxW);
        setSize(fixedW, fixedH);
        setLocationRelativeTo(owner);

        if (initial != null) {
            setImage(initial);
        }
        // Abseits des EDT scharf in hoher Auflösung neu rendern und dann in den Ausschnitt einpassen.
        if (!diagramCode.trim().isEmpty()) {
            new SwingWorker<BufferedImage, Void>() {
                @Override
                protected BufferedImage doInBackground() {
                    return imageRenderer.render(diagramCode, HIGH_RES_WIDTH);
                }

                @Override
                protected void done() {
                    try {
                        BufferedImage rendered = get();
                        if (rendered != null) {
                            setImage(rendered);
                        }
                    } catch (Exception ignored) {
                        // Scheitert das hochauflösende Rendering, bleibt das Ausgangsbild.
                    }
                }
            }.execute();
        }
    }

    /** Öffnet den vergrößerten Mermaid-Betrachter für {@code diagramCode}. */
    public static void open(Window owner, String diagramCode, MermaidImageRenderer imageRenderer,
                            BufferedImage initial) {
        new MermaidViewerDialog(owner, diagramCode == null ? "" : diagramCode, imageRenderer, initial)
                .setVisible(true);
    }

    private void setImage(BufferedImage image) {
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
        imagePanel.setStaticImage(image);
        updateInfo();
    }

    private void updateInfo() {
        String dims = imageWidth > 0 ? "  (" + imageWidth + " × " + imageHeight + ")" : "";
        infoLabel.setText("Mermaid-Diagramm" + dims + "  |  Zoom: " + imagePanel.getZoomPercent() + " %");
    }

    private void installKeyBindings() {
        JComponent root = getRootPane();
        bind(root, "ESCAPE", "close", this::dispose);
        bind(root, "PLUS", "zoomIn", this::zoomIn);
        bind(root, "EQUALS", "zoomIn2", this::zoomIn);
        bind(root, "ADD", "zoomIn3", this::zoomIn);
        bind(root, "MINUS", "zoomOut", this::zoomOut);
        bind(root, "SUBTRACT", "zoomOut2", this::zoomOut);
        bind(root, "0", "fit", this::fit);
        bind(root, "NUMPAD0", "fit2", this::fit);
    }

    private void zoomIn() {
        imagePanel.zoomAt(1.25, null);
        updateInfo();
    }

    private void zoomOut() {
        imagePanel.zoomAt(1.0 / 1.25, null);
        updateInfo();
    }

    private void fit() {
        imagePanel.resetView();
        updateInfo();
    }

    private static void bind(JComponent root, String key, String name, final Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private static void setHidden(JComponent... components) {
        for (JComponent c : components) {
            c.setVisible(false);
        }
    }

    private void copyImage() {
        BufferedImage image = imagePanel.staticImage;
        if (image == null) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(MermaidDiagramPanel.imageTransferable(image), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Das Diagramm konnte nicht kopiert werden: " + ex.getMessage(),
                    "Kopieren fehlgeschlagen", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void saveImage() {
        BufferedImage image = imagePanel.staticImage;
        if (image == null) {
            return;
        }
        JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create("diagramm.png");
        chooser.setDialogTitle("Diagramm als PNG speichern");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            target = new File(target.getParentFile(), target.getName() + ".png");
        }
        try {
            ImageIO.write(image, "png", target);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Das Diagramm konnte nicht gespeichert werden: " + ex.getMessage(),
                    "Speichern fehlgeschlagen", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Dunkle Fläche, die das Diagramm in den Ausschnitt einpasst und Zoom am Cursor sowie Verschieben erlaubt. */
    private static final class ZoomableImagePanel extends JPanel {
        private BufferedImage staticImage;
        private int imgW;
        private int imgH;
        private double zoom = 1.0;
        double offsetX;
        double offsetY;
        Point dragStart;
        private boolean fitted;

        ZoomableImagePanel() {
            setBackground(AppColors.of("app.MermaidViewerDialog.zoomableImagePanel"));
            // Das Bild wird oft gesetzt, bevor die Fläche eine Größe hat; einpassen, sobald eine da ist.
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (!fitted) {
                        resetView();
                    }
                }
            });
        }

        void setStaticImage(BufferedImage img) {
            this.staticImage = img;
            this.imgW = img != null ? img.getWidth() : 0;
            this.imgH = img != null ? img.getHeight() : 0;
            this.fitted = false;
            resetView();
        }

        void resetView() {
            zoom = 1.0;
            if (imgW > 0 && imgH > 0 && getWidth() > 0 && getHeight() > 0) {
                zoom = Math.min(getWidth() / (double) imgW, getHeight() / (double) imgH);
                offsetX = (getWidth() - imgW * zoom) / 2.0;
                offsetY = (getHeight() - imgH * zoom) / 2.0;
                fitted = true;
            } else {
                offsetX = 0;
                offsetY = 0;
            }
            repaint();
        }

        /** Verschiebt den Ausschnitt um Bildschirmpixel; siehe {@link ViewerWheel#panTo}. */
        boolean pan(int dx, int dy) {
            if (imgW <= 0) {
                return false;
            }
            double nx = ViewerWheel.panTo(offsetX, offsetX + dx, imgW * zoom, getWidth());
            double ny = ViewerWheel.panTo(offsetY, offsetY + dy, imgH * zoom, getHeight());
            if (nx == offsetX && ny == offsetY) {
                return false;
            }
            offsetX = nx;
            offsetY = ny;
            repaint();
            return true;
        }

        void zoomAt(double factor, Point pivot) {
            if (imgW <= 0) {
                return;
            }
            double oldZoom = zoom;
            zoom = Math.max(0.05, Math.min(zoom * factor, 20.0));
            double px = pivot != null ? pivot.x : getWidth() / 2.0;
            double py = pivot != null ? pivot.y : getHeight() / 2.0;
            offsetX = px - (px - offsetX) * (zoom / oldZoom);
            offsetY = py - (py - offsetY) * (zoom / oldZoom);
            repaint();
        }

        int getZoomPercent() {
            return (int) Math.round(zoom * 100);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (staticImage == null) {
                return;
            }
            int drawW = (int) Math.round(imgW * zoom);
            int drawH = (int) Math.round(imgH * zoom);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(staticImage, (int) Math.round(offsetX), (int) Math.round(offsetY), drawW, drawH, null);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Halbtransparenter runder Überlagerungsknopf. */
    private static final class OverlayButton extends JPanel {
        OverlayButton(javax.swing.Icon symbol, int size, String tooltip) {
            super(new BorderLayout());
            setOpaque(false);
            Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tooltip);
            JLabel label = new JLabel(symbol, SwingConstants.CENTER);
            label.setForeground(AppColors.of("app.MermaidViewerDialog.overlayButton"));
            add(label, BorderLayout.CENTER);
            setPreferredSize(new Dimension(size, size));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g2.setColor(AppColors.of("app.MermaidViewerDialog.dimVeil"));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
