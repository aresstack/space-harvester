package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.diagrams.VectorDiagram;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Eine Bühne für ein Bild über dem Inhaltsbereich (Angelo 22.11.2026: Der Podcast zeigt zu jedem Thema ein Bild,
 * synchron zur Zeitleiste). Das Bild steht so groß wie möglich in seinem Seitenverhältnis auf heller Fläche, darunter
 * die Bildunterschrift; ein neues Bild blendet weich über das alte. Oben rechts der Kreis „Esc“, sonst keine Knöpfe.
 * Die Bühne fängt die Maus ab, damit der Inhalt darunter nicht versehentlich bedient wird.
 */
public final class PictureStage extends JPanel {

    public static final String HIDE = "Bild ausblenden";
    /** Dauer der Überblendung in Millisekunden. */
    static final int FADE_MILLIS = 240;
    private static final int FADE_STEP = 30;

    /**
     * Ein Bild der Bühne.
     *
     * @param image   das Rasterbild, {@code null} bei einem Schaubild
     * @param vector  das Schaubild (SVG), scharf in jedem Maßstab; {@code null} bei einem Rasterbild
     * @param caption Bildunterschrift, kann leer sein
     */
    public record Picture(BufferedImage image, VectorDiagram vector, String caption) {
        public Picture {
            if (image == null && vector == null) {
                throw new IllegalArgumentException("Ein Bild braucht Raster oder Schaubild");
            }
            caption = caption == null ? "" : caption.strip();
        }

        double width() {
            return vector != null ? vector.width() : image.getWidth();
        }

        double height() {
            return vector != null ? vector.height() : image.getHeight();
        }
    }

    private final JLabel caption = Comic.muted(" ");
    private final Canvas canvas = new Canvas();
    private final Timer fade;
    private Picture shown;
    private Picture previous;
    private float progress = 1f;

    public PictureStage(Runnable onHide) {
        super(new BorderLayout());
        Objects.requireNonNull(onHide, "onHide");
        setBackground(Comic.SURFACE);
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        head.setOpaque(false);
        head.add(new EscCircle(onHide, HIDE));
        add(head, BorderLayout.NORTH);
        caption.setHorizontalAlignment(SwingConstants.CENTER);
        caption.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        // Die Unterschrift steht direkt unter dem Bild, nicht am unteren Rand der Bühne; eine lange kürzt sich mit „…“.
        JPanel body = new JPanel(new java.awt.LayoutManager() {
            @Override
            public void addLayoutComponent(String name, java.awt.Component component) {
            }

            @Override
            public void removeLayoutComponent(java.awt.Component component) {
            }

            @Override
            public Dimension preferredLayoutSize(java.awt.Container parent) {
                return new Dimension(320, 180 + caption.getPreferredSize().height);
            }

            @Override
            public Dimension minimumLayoutSize(java.awt.Container parent) {
                return new Dimension(0, 0);
            }

            @Override
            public void layoutContainer(java.awt.Container parent) {
                int line = caption.getPreferredSize().height;
                int height = Math.max(0, parent.getHeight() - line);
                canvas.setBounds(0, 0, parent.getWidth(), height);
                java.awt.Rectangle sheet = shown == null ? null : canvas.sheet(shown);
                caption.setBounds(0, sheet == null ? height : sheet.y + sheet.height, parent.getWidth(), line);
            }
        });
        body.setOpaque(false);
        body.add(canvas);
        body.add(caption);
        add(body, BorderLayout.CENTER);
        // Klicks und Mausrad bleiben auf der Bühne.
        addMouseListener(new MouseAdapter() { });
        addMouseWheelListener(event -> { });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide");
        getActionMap().put("hide", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                onHide.run();
            }
        });
        fade = new Timer(FADE_STEP, event -> step());
        getAccessibleContext().setAccessibleName("Bild zum Podcast");
    }

    /** Zeigt ein Bild; ein anderes als das gezeigte blendet weich über. Dasselbe Bild noch einmal ändert nichts. */
    public void show(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        if (picture.equals(shown)) {
            return;
        }
        previous = shown;
        shown = picture;
        caption.setText(picture.caption().isEmpty() ? " " : picture.caption());
        caption.setToolTipText(picture.caption().isEmpty() ? null : picture.caption());
        getAccessibleContext().setAccessibleDescription(picture.caption());
        if (previous == null || !isShowing()) {
            progress = 1f;
            previous = null;
            fade.stop();
        } else {
            progress = 0f;
            fade.restart();
        }
        canvas.getParent().revalidate();
        canvas.repaint();
    }

    /** Leert die Bühne: Das nächste Bild erscheint ohne Überblendung. */
    public void clear() {
        fade.stop();
        shown = null;
        previous = null;
        progress = 1f;
        caption.setText(" ");
        caption.setToolTipText(null);
        canvas.repaint();
    }

    public Picture shown() {
        return shown;
    }

    public String caption() {
        return caption.getText().strip();
    }

    private void step() {
        progress = Math.min(1f, progress + (float) FADE_STEP / FADE_MILLIS);
        if (progress >= 1f) {
            previous = null;
            fade.stop();
        }
        canvas.repaint();
    }

    @Override
    public void removeNotify() {
        fade.stop();
        progress = 1f;
        previous = null;
        super.removeNotify();
    }

    /** Die Fläche des Bildes: weiß hinterlegt wie ein Blatt, mit dünnem Rand, im Seitenverhältnis des Bildes. */
    private final class Canvas extends JComponent {

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (previous != null && progress < 1f) {
                paint(g2, previous, 1f - progress);
            }
            if (shown != null) {
                paint(g2, shown, previous == null ? 1f : progress);
            }
            g2.dispose();
        }

        java.awt.Rectangle sheet(Picture picture) {
            return PictureStage.sheet(picture, getWidth(), getHeight());
        }

        private void paint(Graphics2D g2, Picture picture, float alpha) {
            PictureStage.paint(g2, picture, alpha, getWidth(), getHeight());
        }
    }

    /**
     * Wo das Bild auf einer Fläche von {@code width} × {@code height} steht: mittig, im Seitenverhältnis; leer ohne
     * Platz. Dieselbe Rechnung für die Bühne und für die Bilder eines Podcast-Videos.
     */
    public static java.awt.Rectangle sheet(Picture picture, int width, int height) {
        double scale = scale(picture, width, height);
        int sheetWidth = (int) Math.round(picture.width() * scale);
        int sheetHeight = (int) Math.round(picture.height() * scale);
        return new java.awt.Rectangle((width - sheetWidth) / 2, (height - sheetHeight) / 2, sheetWidth, sheetHeight);
    }

    private static double scale(Picture picture, int width, int height) {
        double scale = Math.max(0, Math.min(width / picture.width(), height / picture.height()));
        // Ein kleines Seitenbild wird nicht aufgeblasen; ein Schaubild füllt die Fläche.
        return picture.vector() == null ? Math.min(scale, 1.0) : scale;
    }

    /**
     * Malt das Bild auf eine Fläche von {@code width} × {@code height}: weiß hinterlegt wie ein Blatt, mit dünnem Rand,
     * mittig im Seitenverhältnis ({@link #sheet}), mit der Deckkraft {@code alpha}. Die Bühne malt so, und das
     * Podcast-Video malt seine Bilder genauso.
     */
    public static void paint(Graphics2D g2, Picture picture, float alpha, int width, int height) {
        double scale = scale(picture, width, height);
        if (scale <= 0) {
            return;
        }
        java.awt.Rectangle sheet = sheet(picture, width, height);
        Graphics2D layer = (Graphics2D) g2.create();
        layer.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        layer.setColor(Color.WHITE);
        layer.fillRect(sheet.x, sheet.y, sheet.width, sheet.height);
        if (picture.vector() != null) {
            Graphics2D drawing = (Graphics2D) layer.create(sheet.x, sheet.y, sheet.width, sheet.height);
            picture.vector().paint(drawing, scale);
            drawing.dispose();
        } else {
            layer.drawImage(picture.image(), sheet.x, sheet.y, sheet.width, sheet.height, null);
        }
        layer.setColor(Comic.LINE);
        layer.drawRect(sheet.x, sheet.y, sheet.width - 1, sheet.height - 1);
        layer.dispose();
    }
}
