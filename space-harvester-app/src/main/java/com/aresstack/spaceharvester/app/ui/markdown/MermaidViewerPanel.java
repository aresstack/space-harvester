package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.controls.ViewerWheel;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Der einbettbare Mermaid-Betrachter (aus askai-java8 {@code MermaidViewerPanel}): Ziehen zum Verschieben,
 * Strg+Mausrad-Zoom am Cursor (Rad allein und Shift+Rad verschieben, {@link ViewerWheel}), ständig sichtbare Knöpfe ➖/➕/🔄 (unten rechts) sowie ⧉ kopieren und 💾 speichern
 * (oben rechts), Infozeile mit Flächenfarbe und Deckkraft und das automatische Neu-Rendern in hoher Auflösung.
 * Er sitzt im Comic-Overlay über dem Verlauf ({@link DiagramOverlayHost}); ein Renderfehler zeigt den
 * Mermaid-Quelltext statt einer leeren Fläche.
 *
 * <p>Fläche: weiß wie die Overlay-Platte. Das Farbfeld färbt Fläche UND Platte gemeinsam, der Regler blendet
 * die Fläche bis zur Platte aus. Tasten: + / − / 0 (einpassen).</p>
 */
public final class MermaidViewerPanel extends JPanel {

    private static final int BASE_WIDTH = 1200;
    private static final int HIGH_RES_WIDTH = 2600;

    /** The diagram surface color — white like the overlay frame. THE retheme constant. */
    public static final Color DEFAULT_SURFACE_COLOR = AppColors.of("app.MermaidViewerPanel.paper");
    /** Surface opacity in percent: 100 = solid, 0 = fully transparent (the frame shines through). */
    public static final int DEFAULT_SURFACE_OPACITY_PERCENT = 100;

    private final String diagramCode;
    private final MermaidImageRenderer renderer;
    private final String kindLabel;
    private final Canvas canvas = new Canvas();
    private final JLabel info = new JLabel(" ", SwingConstants.CENTER);
    private final JLayeredPane layers = new JLayeredPane();
    private final JLabel busy = new JLabel("Diagramm wird gerendert …", SwingConstants.CENTER);
    private Color surfaceColor = DEFAULT_SURFACE_COLOR;

    public MermaidViewerPanel(String diagramCode, MermaidImageRenderer renderer) {
        this(diagramCode, renderer, null);
    }

    /**
     * @param initial ein schon gerendertes Bild (etwa aus der Antwortblase) für die sofortige Anzeige; darf
     *                {@code null} sein
     */
    public MermaidViewerPanel(String diagramCode, MermaidImageRenderer renderer, BufferedImage initial) {
        this(diagramCode, renderer, initial, "Mermaid-Diagramm");
    }

    /** @param kindLabel Art der Grafik für Infozeile und Fehleransicht, etwa „Mermaid-Diagramm“ oder „SVG-Grafik“ */
    public MermaidViewerPanel(String diagramCode, MermaidImageRenderer renderer, BufferedImage initial,
                              String kindLabel) {
        super(new BorderLayout(0, 0));
        this.kindLabel = kindLabel == null ? "Mermaid-Diagramm" : kindLabel;
        if (renderer == null) {
            throw new IllegalArgumentException("renderer must not be null");
        }
        this.diagramCode = diagramCode == null ? "" : diagramCode;
        this.renderer = renderer;
        setOpaque(false);

        busy.setForeground(AppColors.of("app.MermaidViewerPanel.busyText")); // legible on the (light) default surface
        busy.setFont(busy.getFont().deriveFont(Font.ITALIC, 12f));

        final ViewerButton zoomOut = new ViewerButton(DiagramViewerIcons.zoomOut(), 36, "Verkleinern (−)");
        final ViewerButton zoomIn = new ViewerButton(DiagramViewerIcons.zoomIn(), 36, "Vergrößern (+)");
        final ViewerButton zoomFit = new ViewerButton(DiagramViewerIcons.fit(), 36, "Einpassen (0)");
        final ViewerButton copy = new ViewerButton(DiagramViewerIcons.copy(), 36, "Bild kopieren");
        final ViewerButton save = new ViewerButton(DiagramViewerIcons.save(), 36, "Als PNG speichern");
        zoomOut.onClick(() -> {
            canvas.zoomAt(1.0 / 1.25, null);
            updateInfo();
        });
        zoomIn.onClick(() -> {
            canvas.zoomAt(1.25, null);
            updateInfo();
        });
        zoomFit.onClick(() -> {
            canvas.resetView();
            updateInfo();
        });
        copy.onClick(this::copyImage);
        save.onClick(this::saveImage);

        layers.setLayout(null);
        layers.add(canvas, JLayeredPane.DEFAULT_LAYER);
        layers.add(busy, JLayeredPane.MODAL_LAYER);
        layers.add(zoomOut, JLayeredPane.PALETTE_LAYER);
        layers.add(zoomIn, JLayeredPane.PALETTE_LAYER);
        layers.add(zoomFit, JLayeredPane.PALETTE_LAYER);
        layers.add(copy, JLayeredPane.PALETTE_LAYER);
        layers.add(save, JLayeredPane.PALETTE_LAYER);
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = layers.getWidth();
                int h = layers.getHeight();
                canvas.setBounds(0, 0, w, h);
                busy.setBounds(0, 0, w, h);
                save.setBounds(w - 44, 8, 36, 36);
                copy.setBounds(w - 88, 8, 36, 36);
                int zy = h - 46;
                zoomOut.setBounds(w - 130, zy, 36, 36);
                zoomIn.setBounds(w - 90, zy, 36, 36);
                zoomFit.setBounds(w - 50, zy, 36, 36);
            }
        });
        add(layers, BorderLayout.CENTER);

        info.setFont(info.getFont().deriveFont(Font.ITALIC, 11f));
        info.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(buildInfoBar(), BorderLayout.SOUTH);

        installCanvasInteractions();
        installKeyBindings();
        if (initial != null) {
            busy.setVisible(false);
            canvas.setStaticImage(initial);
            updateInfo();
            rerenderHighRes();
        } else {
            renderAsync();
        }
    }

    /**
     * + / − / 0 wie im bisherigen Betrachter, solange die Fläche den Fokus hat (sie holt ihn beim Öffnen und beim
     * Klicken), damit Tippen im Composer nie zoomt; Esc schließt das Overlay über dessen Wirt.
     */
    private void installKeyBindings() {
        canvas.setFocusable(true);
        bind("PLUS", "zoomIn", () -> zoomBy(1.25));
        bind("EQUALS", "zoomIn2", () -> zoomBy(1.25));
        bind("ADD", "zoomIn3", () -> zoomBy(1.25));
        bind("MINUS", "zoomOut", () -> zoomBy(1.0 / 1.25));
        bind("SUBTRACT", "zoomOut2", () -> zoomBy(1.0 / 1.25));
        bind("0", "fit", this::fit);
        bind("NUMPAD0", "fit2", this::fit);
    }

    private void zoomBy(double factor) {
        canvas.zoomAt(factor, null);
        updateInfo();
    }

    private void fit() {
        canvas.resetView();
        updateInfo();
    }

    private void bind(String key, String name, final Runnable action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    /**
     * The bottom strip: the info line in the center, the SURFACE controls bottom right — a color
     * swatch and the opacity slider. The strip itself stays transparent, so it always shows the
     * frame plate it sits on (one color, never two).
     */
    private JComponent buildInfoBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(info, BorderLayout.CENTER);

        final javax.swing.JSlider opacity = new javax.swing.JSlider(0, 100,
                DEFAULT_SURFACE_OPACITY_PERCENT);
        opacity.setOpaque(false);
        opacity.setFocusable(false);
        opacity.setToolTipText("Deckkraft der Diagrammfläche");
        opacity.setPreferredSize(new Dimension(90, 18));
        opacity.addChangeListener(e -> canvas.setSurface(surfaceColor, opacity.getValue()));

        final SwatchButton swatch = new SwatchButton();
        swatch.setToolTipText("Farbe der Diagrammfläche (färbt den Rahmen mit)");
        swatch.onClick(() -> {
            Color chosen = javax.swing.JColorChooser.showDialog(
                    MermaidViewerPanel.this, "Farbe der Diagrammfläche", surfaceColor);
            if (chosen != null) {
                surfaceColor = chosen;
                swatch.repaint();
                canvas.setSurface(surfaceColor, opacity.getValue());
                applyFrameColor(chosen);
            }
        });

        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);
        controls.add(swatch);
        controls.add(opacity);
        bar.add(controls, BorderLayout.EAST);
        return bar;
    }

    /**
     * Surface and frame must never disagree: the swatch recolors the comic overlay plate this
     * viewer sits on together with the canvas. Outside an overlay (no plate ancestor) the canvas
     * alone changes — there is no frame to keep in sync.
     */
    private void applyFrameColor(Color color) {
        java.awt.Container plate = javax.swing.SwingUtilities.getAncestorOfClass(
                com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel.class, this);
        if (plate instanceof com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel) {
            ((com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel) plate).setPlateFill(color);
        }
    }

    private void installCanvasInteractions() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                canvas.dragStart = e.getPoint();
                Cursors.set(canvas, Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                canvas.dragStart = null;
                Cursors.set(canvas, Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (canvas.dragStart != null) {
                    canvas.offsetX += e.getX() - canvas.dragStart.x;
                    canvas.offsetY += e.getY() - canvas.dragStart.y;
                    canvas.dragStart = e.getPoint();
                    canvas.repaint();
                }
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(new ViewerWheel(new ViewerWheel.Target() {
            @Override
            public boolean pan(int dx, int dy) {
                return canvas.pan(dx, dy);
            }

            @Override
            public void zoom(double factor, Point at) {
                canvas.zoomAt(factor, at);
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
    }

    /** Base render first (fast feedback), then the crisp high-resolution pass — like the dialog. */
    private void renderAsync() {
        if (diagramCode.trim().isEmpty()) {
            showFailure();
            return;
        }
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return renderer.render(diagramCode, BASE_WIDTH);
            }

            @Override
            protected void done() {
                BufferedImage image = null;
                try {
                    image = get();
                } catch (Exception ignored) {
                    // fall through to the failure view
                }
                busy.setVisible(false);
                if (image == null) {
                    showFailure();
                    return;
                }
                canvas.setStaticImage(image);
                updateInfo();
                rerenderHighRes();
            }
        }.execute();
    }

    private void rerenderHighRes() {
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return renderer.render(diagramCode, HIGH_RES_WIDTH);
            }

            @Override
            protected void done() {
                try {
                    BufferedImage crisp = get();
                    if (crisp != null) {
                        canvas.setStaticImage(crisp);
                        updateInfo();
                    }
                } catch (Exception ignored) {
                    // keep the base render on a failed hi-res pass
                }
            }
        }.execute();
    }

    /** No blank canvas, ever: the failure view names the problem and shows the Mermaid source. */
    private void showFailure() {
        removeAll();
        JTextArea details = new JTextArea(
                "Das Diagramm konnte nicht gerendert werden.\n\n"
                        + "Quelltext:\n" + diagramCode);
        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new com.aresstack.enterpriseai.ui.comic.control.ComicScrollPane(details,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void updateInfo() {
        BufferedImage image = canvas.staticImage;
        String dims = image != null ? "  (" + image.getWidth() + " × " + image.getHeight() + ")" : "";
        info.setText(kindLabel + dims + "  |  Zoom: " + canvas.getZoomPercent() + " %");
    }

    private void copyImage() {
        BufferedImage image = canvas.staticImage;
        if (image == null) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new ImageTransferable(image), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Das Diagramm konnte nicht kopiert werden: " + ex.getMessage(),
                    "Kopieren fehlgeschlagen", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void saveImage() {
        BufferedImage image = canvas.staticImage;
        if (image == null) {
            return;
        }
        JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create("diagramm.png");
        chooser.setDialogTitle("Diagramm als PNG speichern");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            target = new File(target.getParentFile(), target.getName() + ".png");
        }
        try {
            javax.imageio.ImageIO.write(image, "png", target);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Das Diagramm konnte nicht gespeichert werden: " + ex.getMessage(),
                    "Speichern fehlgeschlagen", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ inner parts

    /** The canvas: fit-to-view, cursor-anchored zoom, pan, and the configurable surface. */
    private static final class Canvas extends JPanel {
        private BufferedImage staticImage;
        private int imgW;
        private int imgH;
        private double zoom = 1.0;
        double offsetX;
        double offsetY;
        Point dragStart;
        private boolean fitted;
        private Color surface = DEFAULT_SURFACE_COLOR;
        private int opacityPercent = DEFAULT_SURFACE_OPACITY_PERCENT;

        Canvas() {
            // The surface paints itself (color + opacity) — never an opaque Swing background,
            // or the transparency slider would have nothing to reveal.
            setOpaque(false);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (!fitted) {
                        resetView();
                    }
                }
            });
        }

        /** Recolor/fade the surface (0 = fully transparent — the frame plate shows through). */
        void setSurface(Color color, int opacityPercent) {
            this.surface = color;
            this.opacityPercent = Math.max(0, Math.min(100, opacityPercent));
            repaint();
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
            Graphics2D g2 = (Graphics2D) g.create();
            if (opacityPercent > 0) {
                g2.setColor(new Color(surface.getRed(), surface.getGreen(), surface.getBlue(),
                        Math.round(opacityPercent * 255 / 100f)));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            if (staticImage == null) {
                g2.dispose();
                return;
            }
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(staticImage, (int) Math.round(offsetX), (int) Math.round(offsetY),
                    (int) Math.round(imgW * zoom), (int) Math.round(imgH * zoom), null);
            g2.dispose();
        }
    }

    /** A small round swatch showing the CURRENT surface color, ink-outlined like the comic chips. */
    private final class SwatchButton extends JPanel {
        private Runnable action;

        SwatchButton() {
            setOpaque(false);
            Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(18, 18));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (action != null) {
                        action.run();
                    }
                }
            });
        }

        void onClick(Runnable action) {
            this.action = action;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(surfaceColor);
            g2.fillOval(1, 1, getWidth() - 3, getHeight() - 3);
            g2.setColor(AppColors.of("app.MermaidViewerPanel.buttonRing"));
            g2.setStroke(new java.awt.BasicStroke(1.4f));
            g2.drawOval(1, 1, getWidth() - 3, getHeight() - 3);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Translucent rounded control (always visible — an overlay has no glass pane for hover). */
    private static final class ViewerButton extends JPanel {
        private Runnable action;

        ViewerButton(javax.swing.Icon symbol, int size, String tooltip) {
            super(new BorderLayout());
            setOpaque(false);
            Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tooltip);
            JLabel label = new JLabel(symbol, SwingConstants.CENTER);
            label.setForeground(AppColors.of("app.MermaidViewerPanel.viewerButton"));
            add(label, BorderLayout.CENTER);
            setPreferredSize(new Dimension(size, size));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (action != null) {
                        action.run();
                    }
                }
            });
        }

        void onClick(Runnable action) {
            this.action = action;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            g2.setColor(AppColors.of("app.MermaidViewerPanel.dimVeil"));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Exposes the diagram to the clipboard as an image. */
    private static final class ImageTransferable implements Transferable {
        private final BufferedImage image;

        ImageTransferable(BufferedImage image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        javax.swing.SwingUtilities.invokeLater(canvas::requestFocusInWindow);
    }

    /** Reasonable default when the overlay asks before layout. */
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(700, 460);
    }
}
