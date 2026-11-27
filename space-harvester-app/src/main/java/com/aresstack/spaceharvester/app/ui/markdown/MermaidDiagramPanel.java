package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * Rendert Mermaid abseits des EDT und zeichnet das Ergebnis mit Java2D, mit Aktionen „Bild kopieren“ und
 * „Code kopieren“ (aus askai-java8 {@code MermaidDiagramPanel}). Ein Klick auf das Bild öffnet den vollen
 * Betrachter {@link MermaidViewerPanel} als Comic-Overlay über dem Verlauf ({@link DiagramOverlayHost}); nur ohne
 * solchen Verlauf (etwa eingebettet anderswo) bleibt der eigenständige {@link MermaidViewerDialog}.
 */
public final class MermaidDiagramPanel extends JPanel {

    private static final int PADDING = 12;
    private static final int MIN_RENDER_WIDTH = 640;
    private static final int MAX_RENDER_WIDTH = 2400;

    private final String diagramCode;
    private final String kindLabel;
    private final boolean showSourceOnFailure;
    private final MarkdownTheme theme;
    private final MermaidImageRenderer imageRenderer;
    private final DiagramCanvas canvas;
    private final MarkdownActionButton copyImageButton;
    private BufferedImage image;
    private String errorMessage;
    private boolean rendering;

    public MermaidDiagramPanel(String diagramCode, MarkdownTheme theme,
                               MermaidImageRenderer imageRenderer) {
        this(diagramCode, theme, imageRenderer, "Mermaid-Diagramm", false);
    }

    /**
     * Ein SVG-Bild aus der Antwort: gleicher Bild- und Overlay-Weg wie Mermaid; scheitert das Rendern, zeigt die
     * Blase den SVG-Quelltext als Codeblock mit einer kurzen Fehlerzeile.
     */
    static MermaidDiagramPanel svg(String svgSource, MarkdownTheme theme, MermaidImageRenderer svgRenderer) {
        return new MermaidDiagramPanel(svgSource, theme, svgRenderer, SVG_LABEL, true);
    }

    /**
     * Ein draw.io-Diagramm aus der Antwort, gezeichnet mit drawio-java: gleicher Bild- und Overlay-Weg wie Mermaid;
     * scheitert das Zeichnen, zeigt die Blase den XML-Quelltext mit dem Grund.
     */
    static MermaidDiagramPanel drawio(String xml, MarkdownTheme theme, MermaidImageRenderer drawioRenderer) {
        return new MermaidDiagramPanel(xml, theme, drawioRenderer, DRAWIO, true);
    }

    static final String DRAWIO = "draw.io-Diagramm";
    private static final String SVG_LABEL = "SVG-Grafik";

    /** Woraus das Diagramm gezeichnet wird. */
    public enum Kind { MERMAID, DRAWIO, SVG }

    /**
     * Schlüssel einer Client-Eigenschaft an einem Vorfahren: liefert zu einem Diagramm das Rechtsklick-Menü oder
     * {@code null} (etwa „Element an Chat anhängen“ in einem Artefakt, ADR 0045 §6), als
     * {@code Function<MermaidDiagramPanel, JPopupMenu>}.
     */
    public static final String MENU = "spaceharvester.diagramMenu";

    private MermaidDiagramPanel(String diagramCode, MarkdownTheme theme, MermaidImageRenderer imageRenderer,
                                String kindLabel, boolean showSourceOnFailure) {
        this.kindLabel = kindLabel;
        this.showSourceOnFailure = showSourceOnFailure;
        this.diagramCode = diagramCode == null ? "" : diagramCode;
        this.theme = theme;
        this.imageRenderer = imageRenderer;
        this.canvas = new DiagramCanvas();
        this.copyImageButton = new MarkdownActionButton(new MarkdownActionButton.ImageIcon(),
                "Diagramm als Bild kopieren", theme.getMutedForeground(), this::copyImageToClipboard);
        this.copyImageButton.setEnabled(false);

        setOpaque(false);
        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getSeparatorColor()),
                BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING)));
        add(buildToolbar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        renderAsync();
    }

    /** @return der unveränderte Mermaid-Quelltext dieses Diagramms */
    public String getDiagramCode() {
        return diagramCode;
    }

    /** Woraus dieses Diagramm gezeichnet wird. */
    public Kind kind() {
        return SVG_LABEL.equals(kindLabel) ? Kind.SVG : DRAWIO.equals(kindLabel) ? Kind.DRAWIO : Kind.MERMAID;
    }

    /**
     * Das Diagramm als SVG mit denselben Bibliotheken wie die Anzeige (mermaid-java, drawio-java); ein SVG unverändert.
     * Rechnet (GraalJS), also nicht auf dem EDT aufrufen.
     *
     * @throws IllegalStateException mit dem Grund, wenn es sich nicht zeichnen lässt
     */
    public String svg() {
        return switch (kind()) {
            case SVG -> diagramCode.strip();
            case MERMAID -> {
                String svg = com.aresstack.Mermaid.render(diagramCode);
                if (svg == null || svg.isBlank()) {
                    throw new IllegalStateException("Mermaid nicht darstellbar");
                }
                yield svg;
            }
            case DRAWIO -> {
                com.aresstack.spaceharvester.application.diagrams.Diagrams.Rendering rendering =
                        com.aresstack.spaceharvester.application.diagrams.Diagrams.drawio(
                                diagramCode.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!rendering.drawn()) {
                    throw new IllegalStateException(rendering.problem());
                }
                yield new String(rendering.content(), java.nio.charset.StandardCharsets.UTF_8);
            }
        };
    }

    /** Das Rechtsklick-Menü des nächsten Vorfahren mit {@link #MENU} an der Mausposition. */
    @SuppressWarnings("unchecked")
    private void showMenu(MouseEvent event) {
        for (Container at = getParent(); at != null; at = at.getParent()) {
            if (at instanceof JComponent component && component.getClientProperty(MENU)
                    instanceof java.util.function.Function<?, ?> provider) {
                javax.swing.JPopupMenu menu =
                        ((java.util.function.Function<MermaidDiagramPanel, javax.swing.JPopupMenu>) provider).apply(this);
                if (menu != null) {
                    menu.show(event.getComponent(), event.getX(), event.getY());
                }
                return;
            }
        }
    }

    private JComponent buildToolbar() {
        MarkdownActionButton copyCodeButton = new MarkdownActionButton(new MarkdownActionButton.CopyIcon(),
                kindLabel.startsWith("SVG") ? "SVG-Code kopieren"
                        : DRAWIO.equals(kindLabel) ? "draw.io-Code kopieren" : "Mermaid-Code kopieren", theme.getMutedForeground(), this::copyCodeToClipboard);
        copyCodeButton.setEnabled(diagramCode.trim().length() > 0);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        toolbar.setOpaque(false);
        toolbar.add(copyImageButton);
        toolbar.add(copyCodeButton);
        return toolbar;
    }

    private void renderAsync() {
        if (rendering) {
            return;
        }
        if (diagramCode.trim().isEmpty()) {
            errorMessage = "Das " + kindLabel + " ist leer.";
            canvas.repaint();
            return;
        }
        rendering = true;
        int availableWidth = Math.max(1, canvas.getWidth());
        final int targetWidth = Math.max(MIN_RENDER_WIDTH,
                Math.min(MAX_RENDER_WIDTH, availableWidth * 2));
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return imageRenderer.render(diagramCode, targetWidth);
            }

            @Override
            protected void done() {
                try {
                    image = get();
                    if (image == null) {
                        errorMessage = kindLabel + " konnte nicht gerendert werden.";
                    }
                } catch (Exception ex) {
                    // Der Grund des Renderers, nicht die Hülle des SwingWorkers (ExecutionException).
                    Throwable cause = ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null
                            ? ex.getCause() : ex;
                    errorMessage = kindLabel + ": Fehler beim Rendern: " + cause.getMessage();
                } finally {
                    if (image == null && showSourceOnFailure) {
                        showSourceInstead();
                    }
                    rendering = false;
                    copyImageButton.setEnabled(image != null);
                    Cursors.set(canvas, Cursor.getPredefinedCursor(
                            image != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    canvas.updatePreferredSize();
                    canvas.revalidate();
                    canvas.repaint();
                }
            }
        }.execute();
    }

    /** Ersetzt die Bildfläche durch eine Fehlerzeile und den Quelltext als Codeblock. */
    private void showSourceInstead() {
        removeAll();
        setBorder(null);
        JLabel error = new JLabel(errorMessage);
        error.putClientProperty("html.disable", Boolean.TRUE);
        error.setFont(theme.getBodyFont());
        error.setForeground(theme.getErrorForeground());
        add(error, BorderLayout.NORTH);
        add(new CodeBlockPanel(DRAWIO.equals(kindLabel) ? "xml" : "svg", diagramCode, theme), BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /** Öffnet den vergrößerten, zoombaren Betrachter; er rendert das Diagramm aus dem Mermaid-Quelltext neu. */
    private void openEnlargedViewer() {
        Container host = SwingUtilities.getAncestorOfClass(DiagramOverlayHost.class, this);
        if (host instanceof DiagramOverlayHost) {
            ((DiagramOverlayHost) host).showTranscriptOverlay(
                    new MermaidViewerPanel(diagramCode, imageRenderer, image, kindLabel), kindLabel);
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        MermaidViewerDialog.open(owner, diagramCode, imageRenderer, image);
    }

    private void copyImageToClipboard() {
        if (image == null) {
            return;
        }
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(imageTransferable(image), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Das Bild des Diagramms konnte nicht kopiert werden: "
                    + ex.getMessage(), "Kopieren fehlgeschlagen", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void copyCodeToClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(diagramCode), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Der Mermaid-Code konnte nicht kopiert werden: "
                    + ex.getMessage(), "Kopieren fehlgeschlagen", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Ein Zwischenablage-Transferable, das ein gerendertes Diagramm als Bild anbietet. */
    static Transferable imageTransferable(final BufferedImage image) {
        return new Transferable() {
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
        };
    }

    /** Die Fläche, die das gerenderte Diagramm (oder eine Statuszeile) zeichnet und ihre Höhe vom Bild ableitet. */
    private final class DiagramCanvas extends JComponent {

        DiagramCanvas() {
            setOpaque(false);
            setPreferredSize(new Dimension(600, 140));
            // Space Harvester (Codex-Befund zu #966): Ändert sich die Breite nach dem Rendern, die Höhe neu messen,
            // sonst schneidet ein breiteres Fenster das Diagramm unten ab.
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (image == null) {
                        return;
                    }
                    int before = getPreferredSize().height;
                    updatePreferredSize();
                    if (getPreferredSize().height != before) {
                        revalidate();
                        repaint();
                    }
                }
            });
            setToolTipText("Klicken, um das Diagramm vergrößert zu öffnen");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getButton() == MouseEvent.BUTTON1 && image != null) {
                        openEnlargedViewer();
                    }
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    if (event.isPopupTrigger()) {
                        showMenu(event);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (event.isPopupTrigger()) {
                        showMenu(event);
                    }
                }
            });
        }

        void updatePreferredSize() {
            if (image == null) {
                setPreferredSize(new Dimension(600, 140));
                return;
            }
            int width = Math.max(1, getWidth());
            if (width <= 1) {
                width = Math.min(900, image.getWidth());
            }
            // Nie über die native Größe vergrößern, sonst aber die verfügbare Breite füllen, damit das Diagramm
            // lesbar bleibt.
            double scale = Math.min(1.0d, width / (double) image.getWidth());
            int height = (int) Math.ceil(image.getHeight() * scale);
            setPreferredSize(new Dimension(width, Math.max(80, height)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(80, height)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (image != null) {
                    paintImage(g2);
                    return;
                }
                paintStatus(g2, errorMessage == null ? kindLabel + " wird gerendert …" : errorMessage,
                        errorMessage == null ? theme.getMutedForeground() : theme.getErrorForeground());
            } finally {
                g2.dispose();
            }
        }

        private void paintImage(Graphics2D graphics) {
            double scale = Math.min(1.0d, getWidth() / (double) image.getWidth());
            int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            // Linksbündig; das Diagramm füllt die verfügbare Breite und bleibt so lesbar.
            graphics.drawImage(image, 0, 0, drawWidth, drawHeight, null);
        }

        private void paintStatus(Graphics2D graphics, String text, Color color) {
            graphics.setFont(theme.getBodyFont());
            graphics.setColor(color);
            FontMetrics metrics = graphics.getFontMetrics();
            int y = Math.max(metrics.getAscent(), (getHeight() + metrics.getAscent()) / 2);
            graphics.drawString(text, 0, y);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }
    }
}
