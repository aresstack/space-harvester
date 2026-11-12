package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.application.diagrams.VectorDiagram;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Bildbetrachter für die Bilder einer Seite, übernommen aus dem {@code ImageSlideshowPanel} des Enterprise AI Client
 * (dort aus MainframeMates {@code ImageStripPanel.openOverlay}): Das Bild ist beim Öffnen und nach jeder
 * Größenänderung des Fensters eingepasst, bis der Benutzer selbst zoomt oder verschiebt. Mausrad zoomt am Cursor
 * (×1,15), Ziehen verschiebt, + − 0 zoomen bzw. passen ein (×1,25), ← → blättern. Die Knöpfe liegen im Bild und
 * erscheinen nur, solange die Maus in ihrer Zone steht: Speichern oben rechts, Verkleinern/Vergrößern/Einpassen unten
 * rechts, die Pfeile im mittleren Drittel links bzw. rechts (nur wo es ein vorheriges bzw. nächstes Bild gibt).
 * Darunter die Infozeile „n / m — Titel (B × H) | Zoom: x %“, nach dem Speichern die Meldung dazu.
 */
public final class ImageViewer extends JPanel {

    /**
     * Ein Bild mit seinem Titel (Alternativtext der Seite). Ein draw.io-Diagramm bringt zusätzlich seine
     * Vektorzeichnung mit ({@code vector}, sonst {@code null}): Dann zeichnet der Betrachter bei jedem Zoom direkt aus
     * dem Vektor, statt das Rasterbild {@code image} zu vergrößern, und speichert als SVG.
     */
    public record Picture(BufferedImage image, String label, VectorDiagram vector) {
        public Picture {
            Objects.requireNonNull(image, "image");
            label = label == null || label.isBlank() ? "Bild" : label.strip();
        }

        public Picture(BufferedImage image, String label) {
            this(image, label, null);
        }

        /** Eigenbreite in Pixeln (beim Vektor seine Breite bei 100 %). */
        int width() {
            return vector != null ? (int) Math.round(vector.width()) : image.getWidth();
        }

        int height() {
            return vector != null ? (int) Math.round(vector.height()) : image.getHeight();
        }
    }

    static final String SAVED_PREFIX = "Bild gespeichert: ";
    static final String SAVE_FAILED_PREFIX = "Fehler beim Speichern: ";
    static final double STEP = 1.25;
    static final double WHEEL_STEP = 1.15;
    private static final int BUTTON = 36;
    private static final int ARROW_W = 48;

    private final List<Picture> pictures;
    private final Canvas canvas = new Canvas();
    private final JLayeredPane layers = new JLayeredPane();
    private final JLabel info = new JLabel(" ", SwingConstants.CENTER);
    private final ViewerButton previous;
    private final ViewerButton next;
    private final ViewerButton save;
    private final ViewerButton zoomOut;
    private final ViewerButton zoomIn;
    private final ViewerButton zoomFit;
    private int index;
    /** Meldung nach dem Speichern; gilt, bis gezoomt oder geblättert wird. */
    private String message;

    public ImageViewer(List<Picture> pictures, int startIndex) {
        super(new BorderLayout(0, 4));
        if (pictures == null || pictures.isEmpty()) {
            throw new IllegalArgumentException("Keine Bilder");
        }
        this.pictures = List.copyOf(pictures);
        this.index = Math.max(0, Math.min(startIndex, this.pictures.size() - 1));
        setOpaque(false);

        previous = new ViewerButton(new ScaledIcon(ComposerIcons.chevronLeft(), 2), "Vorheriges Bild (Pfeiltaste links)");
        next = new ViewerButton(new ScaledIcon(ComposerIcons.chevronRight(), 2), "Nächstes Bild (Pfeiltaste rechts)");
        save = new ViewerButton(ComposerIcons.download(), "Bild speichern");
        zoomOut = new ViewerButton(ComposerIcons.minus(15), "Verkleinern (Minus)");
        zoomIn = new ViewerButton(ComposerIcons.plus(15), "Vergrößern (+)");
        zoomFit = new ViewerButton(ComposerIcons.refresh(), "Einpassen (0)");
        previous.onClick(this::previous);
        next.onClick(this::next);
        save.onClick(this::saveWithChooser);
        zoomOut.onClick(this::zoomOut);
        zoomIn.onClick(this::zoomIn);
        zoomFit.onClick(this::fit);

        layers.setLayout(null);
        layers.add(canvas, JLayeredPane.DEFAULT_LAYER);
        for (JComponent button : buttons()) {
            layers.add(button, JLayeredPane.PALETTE_LAYER);
        }
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutLayers();
            }
        });
        add(layers, BorderLayout.CENTER);

        info.setFont(info.getFont().deriveFont(Font.ITALIC, 11f));
        info.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(info, BorderLayout.SOUTH);

        installCanvasInteractions();
        installHover();
        installKeyBindings();
        show(index);
    }

    // ------------------------------------------------------------------ Bedienung (auch für Tests)

    public int index() {
        return index;
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public boolean hasNext() {
        return index < pictures.size() - 1;
    }

    public void previous() {
        if (hasPrevious()) {
            show(index - 1);
        }
    }

    public void next() {
        if (hasNext()) {
            show(index + 1);
        }
    }

    public void zoomIn() {
        zoomBy(STEP);
    }

    public void zoomOut() {
        zoomBy(1.0 / STEP);
    }

    /** Passt das Bild in den Sichtbereich ein und hält es eingepasst, bis wieder gezoomt oder verschoben wird. */
    public void fit() {
        canvas.resetView();
        message = null;
        updateInfo();
    }

    public int zoomPercent() {
        return canvas.zoomPercent();
    }

    /** Ob das Bild gerade eingepasst ist (beim Öffnen immer). */
    public boolean isFitted() {
        return canvas.fitted;
    }

    public String infoText() {
        return info.getText();
    }

    /** Für Tests: Größe der Bildfläche setzen und auslegen, auch ohne Bildschirm. */
    void layoutForTest(int width, int height) {
        setSize(width, height);
        doLayout();
        layoutLayers();
    }

    /**
     * Schreibt das gezeigte Bild nach {@code target}, im Format der Endung (ohne bekannte Endung PNG).
     *
     * @return die Meldung für den Benutzer, wie sie auch in der Infozeile steht
     */
    String saveTo(File target) {
        Picture picture = pictures.get(index);
        String format = format(target.getName());
        if (picture.vector() != null && (format == null || "svg".equals(format))) {
            File file = format == null ? new File(target.getPath() + ".svg") : target;
            try {
                java.nio.file.Files.write(file.toPath(), picture.vector().svg());
                message = SAVED_PREFIX + file.getName();
            } catch (IOException | RuntimeException ex) {
                message = SAVE_FAILED_PREFIX + ex.getClass().getSimpleName();
            }
            updateInfo();
            return message;
        }
        if ("svg".equals(format)) {
            message = SAVE_FAILED_PREFIX + "Dieses Bild ist keine Vektorgrafik; bitte als PNG speichern";
            updateInfo();
            return message;
        }
        File file = format == null ? new File(target.getPath() + ".png") : target;
        try {
            // Ein Diagramm als Rasterbild in der Größe, in der es gerade zu sehen ist.
            BufferedImage image = picture.vector() == null ? picture.image()
                    : picture.vector().raster(Math.max(1, (int) Math.round(picture.vector().width() * canvas.zoom)));
            if (("jpg".equals(format) || "bmp".equals(format)) && image.getColorModel().hasAlpha()) {
                image = opaque(image);
            }
            if (!ImageIO.write(image, format == null ? "png" : format, file)) {
                message = SAVE_FAILED_PREFIX + "Format nicht unterstützt";
            } else {
                message = SAVED_PREFIX + file.getName();
            }
        } catch (IOException | RuntimeException ex) {
            message = SAVE_FAILED_PREFIX + ex.getClass().getSimpleName();
        } catch (OutOfMemoryError ex) {
            message = SAVE_FAILED_PREFIX + "In dieser Zoomstufe zu groß für ein PNG; kleiner zoomen oder als SVG speichern";
        }
        updateInfo();
        return message;
    }

    /** Vorgeschlagener Dateiname: der Titel ohne Zeichen, die Windows nicht erlaubt, mit {@code .png} (Diagramm {@code .svg}). */
    static String fileName(Picture picture) {
        String base = picture.label().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (base.length() > 80) {
            base = base.substring(0, 80).strip();
        }
        String extension = picture.vector() != null ? ".svg" : ".png";
        if (format(base) != null) {
            int dot = base.lastIndexOf('.');
            return picture.vector() != null ? base.substring(0, dot) + extension : base;
        }
        return (base.isEmpty() ? "bild" : base) + extension;
    }

    private static String format(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (extension) {
            case "png" -> "png";
            case "jpg", "jpeg" -> "jpg";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            case "svg" -> "svg";
            default -> null;
        };
    }

    private static BufferedImage opaque(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, copy.getWidth(), copy.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private void saveWithChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Bild speichern");
        Picture picture = pictures.get(index);
        if (picture.vector() != null) {
            javax.swing.filechooser.FileNameExtensionFilter svg =
                    new javax.swing.filechooser.FileNameExtensionFilter("SVG-Vektorgrafik (*.svg)", "svg");
            chooser.addChoosableFileFilter(svg);
            chooser.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "PNG in der aktuellen Zoomgröße (*.png)", "png"));
            chooser.setFileFilter(svg);
            // Wechsel des Formats passt die Endung des vorgeschlagenen Namens an.
            chooser.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, event -> {
                String name = chooser.getSelectedFile() == null ? fileName(picture) : chooser.getSelectedFile().getName();
                chooser.setSelectedFile(new File(withExtension(name, extensionOf(chooser.getFileFilter()))));
            });
        }
        chooser.setSelectedFile(new File(fileName(picture)));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            File chosen = chooser.getSelectedFile();
            String wanted = extensionOf(chooser.getFileFilter());
            if (picture.vector() != null && wanted != null && !wanted.equals(format(chosen.getName()))) {
                // Das gewählte Format gilt, auch wenn der Name noch die andere Endung trägt.
                chosen = new File(chosen.getParentFile(), withExtension(chosen.getName(), wanted));
            }
            saveTo(chosen);
        }
    }

    /** Die Endung eines Formatfilters des Speicherdialogs ({@code svg}, {@code png}); sonst {@code null}. */
    private static String extensionOf(javax.swing.filechooser.FileFilter filter) {
        return filter instanceof javax.swing.filechooser.FileNameExtensionFilter extensions
                ? extensions.getExtensions()[0] : null;
    }

    /** {@code name} mit der Endung {@code extension}; eine bekannte Bildendung wird ersetzt. */
    static String withExtension(String name, String extension) {
        if (extension == null) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 && format(name) != null ? name.substring(0, dot) : name;
        return base + "." + extension;
    }

    private void show(int target) {
        index = target;
        message = null;
        canvas.setPicture(pictures.get(index));
        updateInfo();
        if (layers.getWidth() > 0) {
            hover(canvas.pointer);
        }
    }

    private void zoomBy(double factor) {
        canvas.zoomAt(factor, null);
        message = null;
        updateInfo();
    }

    private void updateInfo() {
        Picture picture = pictures.get(index);
        String line = (index + 1) + " / " + pictures.size() + "  —  " + picture.label() + "  ("
                + picture.width() + " × " + picture.height() + ")  |  Zoom: "
                + canvas.zoomPercent() + " %";
        info.setText(message == null ? line : message);
    }

    private JComponent[] buttons() {
        return new JComponent[] {previous, next, save, zoomOut, zoomIn, zoomFit};
    }

    private void layoutLayers() {
        int w = layers.getWidth();
        int h = layers.getHeight();
        canvas.setBounds(0, 0, w, h);
        previous.setBounds(0, h / 3, ARROW_W, h / 3);
        next.setBounds(w - ARROW_W, h / 3, ARROW_W, h / 3);
        save.setBounds(w - BUTTON - 8, 8, BUTTON, BUTTON);
        int zoomY = h - BUTTON - 10;
        int zoomX = w - (3 * BUTTON + 8) - 10;
        zoomOut.setBounds(zoomX, zoomY, BUTTON, BUTTON);
        zoomIn.setBounds(zoomX + BUTTON + 4, zoomY, BUTTON, BUTTON);
        zoomFit.setBounds(zoomX + 2 * (BUTTON + 4), zoomY, BUTTON, BUTTON);
        // Canvas passt sich in seinem eigenen Listener an; ohne Bildschirm (Tests) kommt der nicht, daher hier.
        if (canvas.fitted || canvas.pending) {
            canvas.resetView();
        }
        updateInfo();
    }

    /**
     * Blendet die Knöpfe ein, deren Zone die Maus berührt: Speichern oben rechts, Zoom unten rechts, Pfeile im
     * mittleren Drittel; {@code null} blendet alle aus.
     */
    void hover(Point p) {
        canvas.pointer = p;
        int w = layers.getWidth();
        int h = layers.getHeight();
        boolean inImage = p != null && p.x >= 0 && p.x < w && p.y >= 0 && p.y < h;
        boolean showSave = inImage && p.x >= w - BUTTON - 20 && p.y <= BUTTON + 20;
        int zoomY = h - BUTTON - 10;
        int zoomX = w - (3 * BUTTON + 8) - 10;
        boolean showZoom = inImage && p.x >= zoomX - 10 && p.y >= zoomY - 10;
        int arrowTop = h / 3;
        boolean inArrowBand = inImage && p.y >= arrowTop - 20 && p.y <= arrowTop + h / 3 + 20;
        previous.setVisible(inArrowBand && !showSave && !showZoom && hasPrevious() && p.x < ARROW_W);
        next.setVisible(inArrowBand && !showSave && !showZoom && hasNext() && p.x >= w - ARROW_W);
        save.setVisible(showSave);
        zoomOut.setVisible(showZoom);
        zoomIn.setVisible(showZoom);
        zoomFit.setVisible(showZoom);
    }

    /** Für Tests: sichtbar sind {vorheriges, nächstes, speichern, zoom}. */
    boolean[] visibleButtons() {
        return new boolean[] {previous.isVisible(), next.isVisible(), save.isVisible(), zoomOut.isVisible()};
    }

    private void installHover() {
        MouseAdapter tracker = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hover(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), layers));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), layers);
                if (p.x < 0 || p.y < 0 || p.x >= layers.getWidth() || p.y >= layers.getHeight()) {
                    hover(null); // nur beim Verlassen der Fläche, nicht beim Wechsel auf einen Knopf
                }
            }
        };
        canvas.addMouseMotionListener(tracker);
        canvas.addMouseListener(tracker);
        for (JComponent button : buttons()) {
            button.addMouseMotionListener(tracker);
            button.addMouseListener(tracker);
        }
        hover(null);
    }

    private void installKeyBindings() {
        canvas.setFocusable(true);
        bind("LEFT", "previous", this::previous);
        bind("RIGHT", "next", this::next);
        bind("PAGE_UP", "previousPage", this::previous);
        bind("PAGE_DOWN", "nextPage", this::next);
        bind("PLUS", "zoomIn", this::zoomIn);
        bind("EQUALS", "zoomIn2", this::zoomIn);
        bind("shift EQUALS", "zoomIn4", this::zoomIn); // „+“ auf US-Tastaturen
        bind("typed +", "zoomIn5", this::zoomIn);
        bind("ADD", "zoomIn3", this::zoomIn);
        bind("MINUS", "zoomOut", this::zoomOut);
        bind("SUBTRACT", "zoomOut2", this::zoomOut);
        bind("0", "fit", this::fit);
        bind("NUMPAD0", "fit2", this::fit);
    }

    private void bind(String key, String name, Runnable action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void installCanvasInteractions() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                canvas.dragStart = e.getPoint();
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                canvas.dragStart = null;
                canvas.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (canvas.dragStart != null) {
                    canvas.offsetX += e.getX() - canvas.dragStart.x;
                    canvas.offsetY += e.getY() - canvas.dragStart.y;
                    canvas.dragStart = e.getPoint();
                    canvas.fitted = false;
                    canvas.repaint();
                }
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(e -> {
            canvas.zoomAt(e.getWheelRotation() < 0 ? WHEEL_STEP : 1.0 / WHEEL_STEP, e.getPoint());
            message = null;
            updateInfo();
        });
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(700, 460);
    }

    // ------------------------------------------------------------------ Teile

    /** Dunkle Fläche (wie EAC und MainframeMate): einpassen, Zoom am Cursor, verschieben. */
    private static final class Canvas extends JPanel {
        private BufferedImage image;
        private VectorDiagram vector;
        private double imgW;
        private double imgH;
        double zoom = 1.0;
        /** Zoom des eingepassten Bildes; die Grenzen schließen ihn immer ein, damit ± nie die Richtung umkehrt. */
        private double fitZoom = 1.0;
        double offsetX;
        double offsetY;
        Point dragStart;
        Point pointer;
        /** Eingepasst: folgt jeder Größenänderung, bis gezoomt oder verschoben wird. */
        boolean fitted;
        /** Noch nie eingepasst, weil die Fläche noch keine Größe hatte. */
        boolean pending;

        Canvas() {
            setBackground(new Color(30, 30, 30));
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (fitted || pending) {
                        resetView();
                    }
                }
            });
        }

        void setPicture(Picture next) {
            image = next.image();
            vector = next.vector();
            imgW = vector != null ? vector.width() : image.getWidth();
            imgH = vector != null ? vector.height() : image.getHeight();
            pending = true;
            resetView();
        }

        void resetView() {
            zoom = 1.0;
            offsetX = 0;
            offsetY = 0;
            if (image != null && imgW > 0 && imgH > 0 && getWidth() > 0 && getHeight() > 0) {
                zoom = Math.min(getWidth() / imgW, getHeight() / imgH);
                offsetX = (getWidth() - imgW * zoom) / 2.0;
                offsetY = (getHeight() - imgH * zoom) / 2.0;
                fitZoom = zoom;
                fitted = true;
                pending = false;
            }
            repaint();
        }

        void zoomAt(double factor, Point pivot) {
            if (image == null) {
                return;
            }
            double oldZoom = zoom;
            zoom = Math.max(Math.min(0.05, fitZoom), Math.min(zoom * factor, Math.max(20.0, fitZoom)));
            double px = pivot != null ? pivot.x : getWidth() / 2.0;
            double py = pivot != null ? pivot.y : getHeight() / 2.0;
            offsetX = px - (px - offsetX) * (zoom / oldZoom);
            offsetY = py - (py - offsetY) * (zoom / oldZoom);
            fitted = false;
            pending = false;
            repaint();
        }

        int zoomPercent() {
            return (int) Math.round(zoom * 100);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (vector != null) {
                    // Bei jedem Zoom neu aus dem Vektor gezeichnet: scharf in jeder Größe (Clip begrenzt die Arbeit).
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(Color.WHITE);
                    g2.fill(new java.awt.geom.Rectangle2D.Double(offsetX, offsetY, imgW * zoom, imgH * zoom));
                    g2.translate(offsetX, offsetY);
                    vector.paint(g2, zoom);
                    return;
                }
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, zoom < 1.0
                        ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, (int) Math.round(offsetX), (int) Math.round(offsetY),
                        (int) Math.round(image.getWidth() * zoom), (int) Math.round(image.getHeight() * zoom), null);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Halbtransparenter runder Knopf mit gezeichnetem Symbol. */
    private static final class ViewerButton extends JPanel {
        private Runnable action;

        ViewerButton(Icon icon, String tooltip) {
            super(new BorderLayout());
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tooltip);
            JLabel label = new JLabel(icon, SwingConstants.CENTER);
            label.setForeground(new Color(255, 255, 255, 220));
            add(label, BorderLayout.CENTER);
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
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g2.setColor(Color.BLACK);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** Ein gezeichnetes Symbol in größerem Maßstab (die Blätter-Pfeile). */
    private record ScaledIcon(Icon icon, int scale) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.scale(scale, scale);
                icon.paintIcon(c, g2, 0, 0);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return icon.getIconWidth() * scale;
        }

        @Override
        public int getIconHeight() {
            return icon.getIconHeight() * scale;
        }
    }
}
