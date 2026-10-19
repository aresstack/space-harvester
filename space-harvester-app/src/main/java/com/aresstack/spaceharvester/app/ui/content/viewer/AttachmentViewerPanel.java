package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Der Anhang-Viewer im Comic-Overlay über dem Reiter „Anhänge“: zeigt einen Dateianhang der Seite. Bilder über den
 * Bildweg ({@link ReaderImageStore.Loaded}), PDF als Seitenbilder über Apache PDFBox ({@link PdfPages}; die Pfeile und
 * ← → blättern Seiten, Mausrad-Zoom am Cursor, + − 0 zoomen bzw. einpassen, beim Hineinzoomen wird die Seite schärfer
 * nachgezeichnet), Text in einer Textfläche, Office-Dateien über die Vorschau der Quelle (PDF oder Bild), alles andere
 * mit Name, Typ und Größe. Unten stehen immer „Typ · Größe“, Seite und Zoom sowie Speichern und Öffnen der
 * Originaldatei. Geladen wird erst, wenn der Viewer geöffnet wird.
 *
 * <p>Kopie von {@code ImageSlideshowPanel} aus dem Enterprise AI Client (exedra ui.reader), nur der Anhang-Viewer
 * (zweiter Konstruktor dort). Weggelassen: die Diashow der Seitenbilder, der Umschalter „Bild | Beschreibung“ (KI),
 * der draw.io-Umschalter „Gerendert | Original“ und die Einmalcode-Abfrage an Stelle der Datei; die Texte sind
 * unverändert, der temporäre Ordner von „Öffnen“ heißt {@code space-harvester-anhang}.</p>
 */
public final class AttachmentViewerPanel extends JPanel {

    static final String LOADING_ATTACHMENT_TEXT = "Lade Anhang …";
    static final String LOADING_PREVIEW_TEXT = "Lade Vorschau …";
    public static final String NO_PREVIEW_TEXT = "Für diesen Dateityp gibt es keine Vorschau.";
    static final String SAVE_TEXT = "Speichern";
    static final String OPEN_TEXT = "Öffnen";
    static final String SAVE_FAILED_PREFIX = "Fehler beim Speichern: ";
    static final String FILE_SAVED_PREFIX = "Datei gespeichert: ";
    static final String OPEN_FAILED_PREFIX = "Öffnen fehlgeschlagen: ";
    static final String NOT_LOADED = "Die Datei ist noch nicht geladen.";
    /** Zeichenskala einer PDF-Seite beim Öffnen (2 = 144 dpi); beim Hineinzoomen wird nachgeschärft. */
    static final double PDF_BASE_SCALE = 2.0;
    /** Mehr Text zeigt die Textansicht nicht (Zeichen); der Rest steckt in der Originaldatei. */
    static final int MAX_TEXT_CHARS = 2_000_000;
    /** Ein Zeichen-Thread für alle PDF-Seiten (PDFBox ist nicht threadsicher, der EDT bleibt frei). */
    private static final ExecutorService PDF_RENDERER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "reader-pdf");
        thread.setDaemon(true);
        return thread;
    });
    private static final int BUTTON = 36;
    private static final int ARROW_W = 48;

    private final Canvas canvas = new Canvas();
    private final JLayeredPane layers = new JLayeredPane();
    private final JLabel busy = new JLabel(LOADING_ATTACHMENT_TEXT, SwingConstants.CENTER);
    private final JLabel info = new JLabel(" ", SwingConstants.CENTER);
    private final JTextArea textArea = new JTextArea();
    private final JScrollPane textPane;
    private final ViewerButton previous;
    private final ViewerButton next;
    private final ViewerButton download;
    private final ViewerButton zoomOut;
    private final ViewerButton zoomIn;
    private final ViewerButton zoomFit;
    /** Letzte Mausposition über dem Bild (in {@code layers}) oder {@code null}, wenn die Maus nicht darüber steht. */
    private Point pointer;

    private final ReaderAttachment attachment;
    private final ReaderAttachmentLoader attachmentLoader;
    private final ReaderFileOpener fileOpener;
    private final Executor renderer;
    private final JLabel fileInfo = new JLabel(" ");
    private final ComposerButton saveButton = new ComposerButton(ComposerIcons.download(), SAVE_TEXT, false,
            "Originaldatei speichern");
    private final ComposerButton openButton = new ComposerButton(ComposerIcons.openExternal(), OPEN_TEXT, false,
            "Originaldatei mit dem Standardprogramm öffnen");
    /** Die Originaldatei, sobald geladen. */
    private byte[] original;
    private List<Consumer<byte[]>> originalWaiting;
    private List<Consumer<String>> originalFailed;
    private PdfPages pdf;
    private int pageCount;
    private int page;
    private double renderScale = PDF_BASE_SCALE;
    /** Zählt jede Zeichenanfrage; ein älteres Ergebnis wird verworfen. */
    private int renderGeneration;
    private boolean closed;
    private final javax.swing.Timer sharpen = new javax.swing.Timer(250, event -> sharpenPage());
    /** Text statt Bild (Textansicht). */
    private boolean showingText;

    /**
     * Der Viewer für {@code attachment}.
     *
     * @param loader   holt Originaldatei und Vorschau der Quelle
     * @param opener   öffnet die gespeicherte Kopie mit dem Standardprogramm; {@code null}: das System
     * @param renderer Thread für PDF-Seiten; {@code null}: der gemeinsame Zeichen-Thread
     */
    public AttachmentViewerPanel(ReaderAttachment attachment, ReaderAttachmentLoader loader, ReaderFileOpener opener,
                                 Executor renderer) {
        super(new BorderLayout(0, 4));
        if (attachment == null || loader == null) {
            throw new IllegalArgumentException("attachment and loader must not be null");
        }
        this.attachment = attachment;
        this.attachmentLoader = loader;
        this.fileOpener = opener == null ? ReaderFileOpener.system() : opener;
        this.renderer = renderer == null ? PDF_RENDERER : renderer;
        sharpen.setRepeats(false);
        setOpaque(false);

        busy.setForeground(new Color(255, 255, 255, 210));
        busy.setFont(busy.getFont().deriveFont(Font.ITALIC, 14f));

        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setLineWrap(false);
        textArea.setBackground(ResearchUiPalette.LIGHT_CONTROL_BG);
        textArea.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        textPane = new JScrollPane(textArea);
        textPane.setBorder(BorderFactory.createEmptyBorder());
        textPane.setVisible(false);

        previous = new ViewerButton(new ScaledIcon(ComposerIcons.chevronLeft(), 2),
                "Vorherige Seite (Pfeiltaste links oder Bild auf)");
        next = new ViewerButton(new ScaledIcon(ComposerIcons.chevronRight(), 2),
                "Nächste Seite (Pfeiltaste rechts oder Bild ab)");
        download = new ViewerButton(ComposerIcons.download(), "Originaldatei speichern");
        zoomOut = new ViewerButton(ComposerIcons.minus(15), "Verkleinern (Minus)");
        zoomIn = new ViewerButton(ComposerIcons.plus(15), "Vergrößern (+)");
        zoomFit = new ViewerButton(ComposerIcons.refresh(), "Einpassen (0)");
        previous.onClick(this::previous);
        next.onClick(this::next);
        download.onClick(this::saveAttachment);
        zoomOut.onClick(() -> zoomBy(1.0 / 1.25));
        zoomIn.onClick(() -> zoomBy(1.25));
        zoomFit.onClick(this::fit);

        layers.setLayout(null);
        layers.add(canvas, JLayeredPane.DEFAULT_LAYER);
        layers.add(busy, JLayeredPane.MODAL_LAYER);
        for (JComponent button : new JComponent[] {previous, next, download, zoomOut, zoomIn, zoomFit}) {
            layers.add(button, JLayeredPane.PALETTE_LAYER);
        }
        layers.add(textPane, Integer.valueOf(JLayeredPane.MODAL_LAYER.intValue() + 10));
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = layers.getWidth();
                int h = layers.getHeight();
                canvas.setBounds(0, 0, w, h);
                busy.setBounds(0, 0, w, h);
                int inset = 8; // die Textansicht eines Anhangs blättert nicht
                textPane.setBounds(inset, 8, Math.max(0, w - 2 * inset), Math.max(0, h - 16));
                previous.setBounds(0, h / 3, ARROW_W, h / 3);
                next.setBounds(w - ARROW_W, h / 3, ARROW_W, h / 3);
                download.setBounds(w - BUTTON - 8, 8, BUTTON, BUTTON);
                int zoomY = h - BUTTON - 10;
                int zoomX = w - (3 * BUTTON + 8) - 10;
                zoomOut.setBounds(zoomX, zoomY, BUTTON, BUTTON);
                zoomIn.setBounds(zoomX + BUTTON + 4, zoomY, BUTTON, BUTTON);
                zoomFit.setBounds(zoomX + 2 * (BUTTON + 4), zoomY, BUTTON, BUTTON);
            }
        });
        add(layers, BorderLayout.CENTER);

        info.setFont(info.getFont().deriveFont(Font.ITALIC, 11f));
        info.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        // Wie Angelos Skizze: unten „Typ · Größe“ links, Seite und Zoom in der Mitte, rechts die Datei-Aktionen.
        fileInfo.setText(attachment.summary());
        fileInfo.setFont(ResearchUiTypography.semiBold(12f));
        fileInfo.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        bar.add(fileInfo, BorderLayout.WEST);
        saveButton.setFont(ResearchUiTypography.semiBold(12f));
        openButton.setFont(ResearchUiTypography.semiBold(12f));
        saveButton.addActionListener(event -> saveAttachment());
        openButton.addActionListener(event -> openAttachment());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actions.add(saveButton);
        actions.add(openButton);
        bar.add(actions, BorderLayout.EAST);
        bar.add(info, BorderLayout.CENTER);
        add(bar, BorderLayout.SOUTH);

        installCanvasInteractions();
        installHover();
        installKeyBindings();
        loadAttachment();
    }

    // ------------------------------------------------------------------ Bedienung (auch für Tests)

    public boolean hasPrevious() {
        return pdf != null && page > 0;
    }

    public boolean hasNext() {
        return pdf != null && page < pageCount - 1;
    }

    /** Vorige PDF-Seite. */
    public void previous() {
        if (hasPrevious()) {
            showPage(page - 1);
        }
    }

    /** Nächste PDF-Seite. */
    public void next() {
        if (hasNext()) {
            showPage(page + 1);
        }
    }

    /** Der gezeigte Anhang. */
    public ReaderAttachment attachment() {
        return attachment;
    }

    /** Die gezeigte PDF-Seite (0-basiert). */
    public int page() {
        return page;
    }

    /** Seiten des gezeigten PDFs; 0, wenn keins gezeigt wird. */
    public int pageCount() {
        return pageCount;
    }

    /** Ob gerade ein Bild bzw. eine Seite auf der Fläche steht. */
    public boolean showsPicture() {
        return canvas.staticImage != null && !busy.isVisible();
    }

    /** Ob die Textansicht zu sehen ist. */
    public boolean isShowingText() {
        return showingText;
    }

    /** Der Zoom in Prozent (bezogen auf die gezeichnete Seite bzw. das Bild). */
    public int zoomPercent() {
        return canvas.getZoomPercent();
    }

    /** Vergrößert wie „+“. */
    public void zoomIn() {
        zoomBy(1.25);
    }

    /** Verkleinert wie „−“. */
    public void zoomOut() {
        zoomBy(1.0 / 1.25);
    }

    /** Die Breite des gezeichneten Bildes in Pixeln (0 ohne Bild); wächst beim Nachschärfen einer PDF-Seite. */
    int pictureWidth() {
        return canvas.imgW;
    }

    /** Die Skala, mit der die gezeigte PDF-Seite gezeichnet ist. */
    double renderScale() {
        return renderScale;
    }

    /** Zeichnet die PDF-Seite jetzt schärfer nach, wenn der Zoom es verlangt (sonst nach kurzer Pause). */
    void sharpenNow() {
        sharpen.stop();
        sharpenPage();
    }

    /** Die Knöpfe „Speichern“ und „Öffnen“ des Anhang-Viewers. */
    ComposerButton saveButton() {
        return saveButton;
    }

    ComposerButton openButton() {
        return openButton;
    }

    /** „Typ · Größe“ unten links. */
    public String fileInfoText() {
        return fileInfo.getText();
    }

    public String infoText() {
        return info.getText();
    }

    /** Der Text über dem Bild (Laden oder Fehlschlag mit Grund). */
    public String statusText() {
        return busy.getText();
    }

    // ------------------------------------------------------------------ intern

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void updateInfo() {
        if (showingText) {
            info.setText(" ");
            return;
        }
        String zoom = canvas.staticImage == null ? "" : "Zoom: " + canvas.getZoomPercent() + " %";
        info.setText(pdf == null ? (zoom.isEmpty() ? " " : zoom)
                : "Seite " + (page + 1) + " / " + pageCount + (zoom.isEmpty() ? "" : "  |  " + zoom));
    }

    private void zoomBy(double factor) {
        canvas.zoomAt(factor, null);
        updateInfo();
        zoomed();
    }

    /** Nach jedem Zoom: eine PDF-Seite wird nach kurzer Pause in der neuen Größe scharf nachgezeichnet. */
    private void zoomed() {
        if (pdf != null) {
            sharpen.restart();
        }
    }

    private void fit() {
        canvas.resetView();
        updateInfo();
    }

    /**
     * Blendet die Knöpfe ein, deren Zone die Maus berührt, wie MainframeMates Glasscheibe ({@code mouseMoved}):
     * Herunterladen oben rechts, Zoom unten rechts, Pfeile im mittleren Drittel; {@code null} blendet alle aus.
     *
     * @param p Mausposition in Koordinaten der Bildfläche
     */
    void hover(Point p) {
        pointer = p;
        int w = layers.getWidth();
        int h = layers.getHeight();
        boolean inImage = p != null && p.x >= 0 && p.x < w && p.y >= 0 && p.y < h;
        boolean showDownload = inImage && p.x >= w - BUTTON - 20 && p.y <= BUTTON + 20;
        int zoomY = h - BUTTON - 10;
        int zoomX = w - (3 * BUTTON + 8) - 10;
        boolean showZoom = inImage && p.x >= zoomX - 10 && p.y >= zoomY - 10;
        int arrowTop = h / 3;
        boolean inArrowBand = inImage && p.y >= arrowTop - 20 && p.y <= arrowTop + h / 3 + 20;
        previous.setVisible(inArrowBand && !showDownload && !showZoom && hasPrevious() && p.x < ARROW_W);
        next.setVisible(inArrowBand && !showDownload && !showZoom && hasNext() && p.x >= w - ARROW_W);
        download.setVisible(showDownload);
        zoomOut.setVisible(showZoom);
        zoomIn.setVisible(showZoom);
        zoomFit.setVisible(showZoom);
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
        for (JComponent component : new JComponent[] {canvas, previous, next, download, zoomOut, zoomIn, zoomFit}) {
            component.addMouseMotionListener(tracker);
            component.addMouseListener(tracker);
        }
        hover(null);
    }

    private void installKeyBindings() {
        canvas.setFocusable(true);
        bind("LEFT", "previous", this::previous);
        bind("RIGHT", "next", this::next);
        bind("PLUS", "zoomIn", () -> zoomBy(1.25));
        bind("EQUALS", "zoomIn2", () -> zoomBy(1.25));
        bind("ADD", "zoomIn3", () -> zoomBy(1.25));
        bind("MINUS", "zoomOut", () -> zoomBy(1.0 / 1.25));
        bind("SUBTRACT", "zoomOut2", () -> zoomBy(1.0 / 1.25));
        bind("0", "fit", this::fit);
        bind("PAGE_UP", "previousPage", this::previous);
        bind("PAGE_DOWN", "nextPage", this::next);
        bind("NUMPAD0", "fit2", this::fit);
    }

    private void bind(String key, String name, final Runnable action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
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
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
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
        canvas.addMouseWheelListener(e -> {
            canvas.zoomAt(e.getWheelRotation() < 0 ? 1.15 : 1.0 / 1.15, e.getPoint());
            updateInfo();
            zoomed();
        });
    }

    /**
     * Schreibt die Originaldatei nach {@code target}.
     *
     * @return die Meldung für den Benutzer: „Datei gespeichert: name“ oder „Fehler beim Speichern: …“
     */
    String saveTo(File target) {
        if (original == null) {
            return SAVE_FAILED_PREFIX + NOT_LOADED;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(original);
            out.close();
            out = null;
            return FILE_SAVED_PREFIX + target.getName();
        } catch (IOException ex) {
            return SAVE_FAILED_PREFIX + ex.getMessage();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // der Fehler ist schon gemeldet
                }
            }
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(canvas::requestFocusInWindow);
    }

    /** Das Overlay ist zu: ein offenes PDF wird auf seinem Zeichen-Thread geschlossen. */
    @Override
    public void removeNotify() {
        super.removeNotify();
        close();
    }

    /** Gibt das PDF frei; danach zeichnet das Overlay nichts mehr. */
    public void close() {
        closed = true;
        sharpen.stop();
        final PdfPages open = pdf;
        pdf = null;
        if (open != null) {
            renderer.execute(() -> closeQuietly(open));
        }
    }

    // ------------------------------------------------------------------ Anhang laden und zeigen

    /** Lädt und zeigt den Anhang je nach Typ. */
    private void loadAttachment() {
        showingText = false;
        canvas.setStaticImage(null);
        busy.setVisible(true);
        updateInfo();
        switch (attachment.kind()) {
            case OTHER:
                showUnavailable(NO_PREVIEW_TEXT);
                return;
            case OFFICE:
                // Office zeichnet der Client nicht selbst: Vorschau der Quelle (PDF oder Bild).
                busy.setText(LOADING_PREVIEW_TEXT);
                attachmentLoader.preview(attachment, bytes -> show(bytes, true),
                        reason -> showUnavailable(NO_PREVIEW_TEXT + "\n" + (reason == null ? "" : reason)));
                return;
            default:
                busy.setText(LOADING_ATTACHMENT_TEXT);
                requestOriginal(bytes -> show(bytes, false), reason -> showUnavailable(
                        ReaderImageStore.Loaded.FAILED_TEXT.replace("Bild", "Anhang") + ":\n"
                                + (reason == null ? ReaderImageStore.Loaded.NO_REASON : reason)));
        }
    }

    /** Zeigt Bytes des Anhangs bzw. seiner Vorschau: Text, PDF oder Bild (an den Bytes erkannt). */
    private void show(byte[] bytes, boolean preview) {
        if (closed) {
            return;
        }
        if (bytes == null || bytes.length == 0) {
            showUnavailable("Leere Datei (0 Bytes).");
            return;
        }
        if (!preview && attachment.kind() == ReaderAttachmentKind.TEXT) {
            showText(decodeText(bytes, attachment.mediaType()));
            return;
        }
        if (isPdf(bytes)) {
            openPdf(bytes);
            return;
        }
        ReaderImageStore.Loaded loaded = ReaderImageStore.Loaded.of(bytes);
        if (!loaded.ok()) {
            showUnavailable(loaded.failure());
            return;
        }
        busy.setVisible(false);
        if (loaded.isAnimatedGif()) {
            canvas.setAnimatedImage(loaded.bytes(), loaded.image());
        } else {
            canvas.setStaticImage(loaded.image());
        }
        updateInfo();
    }

    private void showText(String text) {
        showingText = true;
        busy.setVisible(false);
        textArea.setText(text);
        textArea.setCaretPosition(0);
        textPane.setVisible(true);
        hover(pointer);
        updateInfo();
    }

    /** Die gezeigte Textansicht ({@code ""} außerhalb). */
    public String textContent() {
        return showingText ? textArea.getText() : "";
    }

    /** Statt einer Vorschau: Name, Typ, Größe und der Grund; Speichern und Öffnen bleiben unten. */
    private void showUnavailable(String reason) {
        if (closed) {
            return;
        }
        showingText = false;
        canvas.setStaticImage(null);
        busy.setVisible(true);
        StringBuilder html = new StringBuilder("<html><div style='text-align:center;width:560px'><b>")
                .append(escape(attachment.fileName())).append("</b><br>").append(escape(attachment.summary()));
        for (String line : String.valueOf(reason).split("\n")) {
            if (!line.trim().isEmpty()) {
                html.append("<br>").append(escape(line));
            }
        }
        html.append("<br><br>").append(escape("Mit „" + SAVE_TEXT + "“ oder „" + OPEN_TEXT
                + "“ unten rechts bekommst du die Originaldatei.")).append("</div></html>");
        busy.setText(html.toString());
        updateInfo();
    }

    /** Öffnet das PDF auf dem Zeichen-Thread und zeigt Seite 1. */
    private void openPdf(final byte[] bytes) {
        busy.setText("PDF wird geöffnet …");
        busy.setVisible(true);
        renderer.execute(() -> {
            PdfPages opened = null;
            String failure = null;
            try {
                opened = PdfPages.open(bytes);
            } catch (IOException | RuntimeException e) {
                failure = "PDF nicht lesbar: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage());
            }
            final PdfPages document = opened;
            final String reason = failure;
            onEdt(() -> {
                if (closed) {
                    if (document != null) {
                        renderer.execute(() -> closeQuietly(document));
                    }
                    return;
                }
                if (document == null) {
                    showUnavailable(reason);
                    return;
                }
                pdf = document;
                pageCount = document.pageCount();
                if (pageCount == 0) {
                    showUnavailable("Das PDF hat keine Seiten.");
                    return;
                }
                showPage(0);
            });
        });
    }

    /** Zeichnet Seite {@code target} in der Grundschärfe und passt sie ein. */
    private void showPage(final int target) {
        if (pdf == null) {
            return;
        }
        page = Math.max(0, Math.min(target, pageCount - 1));
        renderScale = PDF_BASE_SCALE;
        sharpen.stop();
        busy.setText("Seite " + (page + 1) + " wird gezeichnet …");
        busy.setVisible(canvas.staticImage == null);
        hover(pointer);
        render(page, PDF_BASE_SCALE, true);
    }

    /**
     * Zeichnet die Seite auf dem Zeichen-Thread. {@code fresh}: neu einpassen (Seitenwechsel); sonst ersetzt die
     * schärfere Fassung die alte, ohne dass sich Größe oder Ausschnitt auf dem Schirm ändern.
     */
    private void render(final int target, final double scale, final boolean fresh) {
        final PdfPages document = pdf;
        final int generation = ++renderGeneration;
        renderer.execute(() -> {
            BufferedImage image = null;
            String failure = null;
            try {
                image = document.render(target, scale);
            } catch (IOException | RuntimeException e) {
                failure = "Seite " + (target + 1) + " nicht darstellbar: " + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
            final BufferedImage drawn = image;
            final String reason = failure;
            onEdt(() -> {
                if (closed || document != pdf || generation != renderGeneration) {
                    return; // inzwischen geblättert, gezoomt oder geschlossen
                }
                if (drawn == null) {
                    busy.setText(reason);
                    busy.setVisible(true);
                    return;
                }
                busy.setVisible(false);
                if (fresh) {
                    canvas.setStaticImage(drawn);
                } else {
                    canvas.replaceImage(drawn, drawn.getWidth() / (double) Math.max(1, canvas.imgW));
                    renderScale = scale;
                }
                updateInfo();
            });
        });
    }

    /** Nach dem Zoomen: ist die Seite größer gezeigt als gezeichnet, in der gezeigten Größe nachzeichnen. */
    private void sharpenPage() {
        if (pdf == null || canvas.staticImage == null) {
            return;
        }
        double wanted = Math.min(renderScale * canvas.zoom, pdf.maxScale(page));
        if (wanted > renderScale * 1.1) {
            render(page, wanted, false);
        }
    }

    /** Die Originaldatei (einmal geladen, Wartende bekommen sie alle). */
    private void requestOriginal(Consumer<byte[]> done, Consumer<String> failed) {
        if (original != null) {
            done.accept(original);
            return;
        }
        if (originalWaiting != null) {
            originalWaiting.add(done);
            originalFailed.add(failed);
            return;
        }
        originalWaiting = new ArrayList<Consumer<byte[]>>();
        originalFailed = new ArrayList<Consumer<String>>();
        originalWaiting.add(done);
        originalFailed.add(failed);
        attachmentLoader.load(attachment, bytes -> {
            List<Consumer<byte[]>> listeners = originalWaiting;
            List<Consumer<String>> failures = originalFailed;
            originalWaiting = null;
            originalFailed = null;
            if (bytes == null) {
                for (Consumer<String> listener : failures) {
                    listener.accept(null);
                }
                return;
            }
            original = bytes;
            for (Consumer<byte[]> listener : listeners) {
                listener.accept(bytes);
            }
        }, reason -> {
            List<Consumer<String>> failures = originalFailed;
            originalWaiting = null;
            originalFailed = null;
            for (Consumer<String> listener : failures) {
                listener.accept(reason);
            }
        });
    }

    /** Ob die Originaldatei geladen ist. */
    boolean hasOriginal() {
        return original != null;
    }

    private void saveAttachment() {
        requestOriginal(bytes -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Anhang speichern");
            chooser.setSelectedFile(new File(safeFileName(attachment.fileName())));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            String message = saveTo(chooser.getSelectedFile());
            boolean saved = message.startsWith(FILE_SAVED_PREFIX);
            JOptionPane.showMessageDialog(this, message, saved ? "Download" : "Download-Fehler",
                    saved ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
        }, reason -> JOptionPane.showMessageDialog(this, SAVE_FAILED_PREFIX + reasonText(reason),
                "Download-Fehler", JOptionPane.ERROR_MESSAGE));
    }

    private void openAttachment() {
        requestOriginal(bytes -> {
            String problem = openOriginal();
            if (problem != null) {
                JOptionPane.showMessageDialog(this, problem, "Öffnen", JOptionPane.ERROR_MESSAGE);
            }
        }, reason -> JOptionPane.showMessageDialog(this, OPEN_FAILED_PREFIX + reasonText(reason), "Öffnen",
                JOptionPane.ERROR_MESSAGE));
    }

    /**
     * Legt eine Kopie der Originaldatei unter ihrem Namen in einem eigenen temporären Ordner ab (beim Beenden
     * gelöscht) und öffnet sie mit dem Standardprogramm.
     *
     * @return {@code null} bei Erfolg, sonst die Meldung für den Benutzer
     */
    String openOriginal() {
        if (original == null) {
            return OPEN_FAILED_PREFIX + NOT_LOADED;
        }
        try {
            File folder = Files.createTempDirectory("space-harvester-anhang").toFile();
            folder.deleteOnExit();
            File copy = new File(folder, safeFileName(attachment.fileName()));
            copy.deleteOnExit();
            Files.write(copy.toPath(), original);
            fileOpener.open(copy);
            return null;
        } catch (IOException | RuntimeException e) {
            return OPEN_FAILED_PREFIX + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String reasonText(String reason) {
        return reason == null || reason.trim().isEmpty() ? ReaderImageStore.Loaded.NO_REASON : reason;
    }

    /** Dateiname ohne Pfadteile und Zeichen, die Windows nicht erlaubt. */
    static String safeFileName(String name) {
        String cleaned = (name == null ? "" : name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return cleaned.isEmpty() || cleaned.matches("\\.+") ? "anhang" : cleaned;
    }

    static boolean isPdf(byte[] data) {
        return data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
    }

    /**
     * Text mit dem Zeichensatz aus dem Medientyp, sonst nach BOM, sonst UTF-8 und, wenn das nicht passt,
     * Windows-1252 (ältere Dateien aus dem Büro). Sehr lange Texte werden gekürzt.
     */
    static String decodeText(byte[] bytes, String mediaType) {
        Charset charset = charsetOf(mediaType);
        int offset = 0;
        if (charset == null) {
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF) {
                charset = StandardCharsets.UTF_8;
                offset = 3;
            } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                charset = StandardCharsets.UTF_16BE;
                offset = 2;
            } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                charset = StandardCharsets.UTF_16LE;
                offset = 2;
            }
        }
        String text;
        if (charset != null) {
            text = new String(bytes, offset, bytes.length - offset, charset);
        } else {
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException notUtf8) {
                text = new String(bytes, Charset.forName("windows-1252"));
            }
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n\n[… gekürzt; die ganze Datei über „" + SAVE_TEXT
                    + "“ oder „" + OPEN_TEXT + "“]";
        }
        return text;
    }

    private static Charset charsetOf(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        for (String part : mediaType.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                try {
                    return Charset.forName(p.substring(8).replace("\"", "").trim());
                } catch (RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void closeQuietly(PdfPages document) {
        try {
            document.close();
        } catch (IOException ignored) {
            // nur Speicher
        }
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(700, 460);
    }

    // ------------------------------------------------------------------ Teile

    /** Dunkle Fläche (wie MainframeMate), einpassen, Zoom am Cursor, verschieben; animierte GIFs laufen. */
    private static final class Canvas extends JPanel {
        private BufferedImage staticImage;
        private javax.swing.ImageIcon animatedIcon;
        private int imgW;
        private int imgH;
        private double zoom = 1.0;
        double offsetX;
        double offsetY;
        Point dragStart;
        private boolean fitted;

        Canvas() {
            setBackground(new Color(30, 30, 30));
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (!fitted) {
                        resetView();
                    }
                }
            });
        }

        /** Wie MainframeMate {@code setAnimatedImage}: {@link javax.swing.ImageIcon} spielt das GIF ab. */
        void setAnimatedImage(byte[] gif, BufferedImage firstFrame) {
            setStaticImage(firstFrame);
            animatedIcon = new javax.swing.ImageIcon(gif);
            animatedIcon.setImageObserver(this);
        }

        /**
         * Ersetzt das Bild durch eine um {@code factor} größer gezeichnete Fassung desselben Inhalts (schärfere
         * PDF-Seite): Ausschnitt und Größe auf dem Schirm bleiben, nur der Zoom wird umgerechnet.
         */
        void replaceImage(BufferedImage img, double factor) {
            if (img == null || factor <= 0) {
                return;
            }
            this.animatedIcon = null;
            this.staticImage = img;
            this.imgW = img.getWidth();
            this.imgH = img.getHeight();
            this.zoom = zoom / factor;
            repaint();
        }

        void setStaticImage(BufferedImage img) {
            this.animatedIcon = null;
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
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, zoom < 1.0
                        ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                int x = (int) Math.round(offsetX);
                int y = (int) Math.round(offsetY);
                int w = (int) Math.round(imgW * zoom);
                int h = (int) Math.round(imgH * zoom);
                if (animatedIcon != null) {
                    g2.drawImage(animatedIcon.getImage(), x, y, w, h, this);
                } else {
                    g2.drawImage(staticImage, x, y, w, h, null);
                }
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
    private static final class ScaledIcon implements Icon {
        private final Icon icon;
        private final int scale;

        ScaledIcon(Icon icon, int scale) {
            this.icon = icon;
            this.scale = scale;
        }

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
