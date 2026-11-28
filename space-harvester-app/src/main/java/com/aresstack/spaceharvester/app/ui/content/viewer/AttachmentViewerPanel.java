package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;
import com.aresstack.spaceharvester.app.ui.controls.ViewerWheel;

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
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Der Anhang-Viewer im Comic-Overlay über dem Reiter „Anhänge“: zeigt einen Dateianhang der Seite. Bilder über den
 * Bildweg ({@link ReaderImageStore.Loaded}), PDF als Seitenbilder über Apache PDFBox ({@link PdfPages}; die Pfeile und
 * ← → und das Mausrad blättern Seiten (bei hineingezoomter Seite verschiebt das Rad erst den Ausschnitt), Strg+Rad
 * zoomt am Cursor, Shift+Rad verschiebt waagrecht ({@link ViewerWheel}), + − 0 zoomen bzw. einpassen, beim
 * Hineinzoomen wird die Seite schärfer
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
    /** Eine Meldung statt des Inhalts, etwa warum er sich nicht zeigen lässt. */
    private final JLabel busy = new JLabel(LOADING_ATTACHMENT_TEXT, SwingConstants.CENTER);
    /** Solange geladen wird: die Sanduhr der Suche, ohne Esc, mit dem Ladetext darunter. */
    private final com.aresstack.spaceharvester.app.ui.controls.BusyOverlay spinner =
            new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(transparent());
    private final JLabel info = new JLabel(" ", SwingConstants.CENTER);
    private final JTextArea textArea = new JTextArea();
    private final JScrollPane textPane;
    /** Ein Word-Dokument als HTML mit seinen Bildern an ihrer Stelle. */
    private final javax.swing.JEditorPane documentArea = new javax.swing.JEditorPane();
    private final JScrollPane documentPane;
    /** Der Klartext der gezeigten Text- oder Dokumentansicht. */
    private String shownText = "";
    private final JPanel structuredPreviewPane = new JPanel(new BorderLayout());
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
    private final ComposerButton saveButton = ComposerButton.iconButton(ComposerIcons.download(),
            "Originaldatei speichern");
    private final ComposerButton openButton = ComposerButton.iconButton(ComposerIcons.openExternal(),
            "Originaldatei mit dem Standardprogramm öffnen");
    /**
     * Die Leiste unten: links neben der Lupe „Speichern“ und „Öffnen“ als Symbolknöpfe, mittig Seite und Zoom, rechts
     * „Typ · Größe“ (Angelo, 19.11.2026). In der Leseansicht steht sie in der Werkzeugleiste mit der Lupe
     * ({@link #detachFooter()}), sonst (Anhang als Overlay) unter der Vorschau.
     */
    private final JPanel footer = new JPanel(new BorderLayout());
    /** Die Originaldatei, sobald geladen. */
    private byte[] original;
    private List<Consumer<byte[]>> originalWaiting;
    private List<Consumer<String>> originalFailed;
    private RenderedPages pages;
    private int pageCount;
    private int page;
    private double renderScale = PDF_BASE_SCALE;
    /** Zählt jede Zeichenanfrage; ein älteres Ergebnis wird verworfen. */
    private int renderGeneration;
    /** Die markierte Fundstelle im PDF ({@link #showPassage}); {@code null} ohne. */
    private RenderedPages.Passage passage;
    /** Eine Fundstelle, die auf das Ende des Ladens wartet. */
    private Runnable passageWaiting;
    private boolean closed;
    /** Zählt jedes Laden; ein Dekodier-Ergebnis für ein älteres Laden wird verworfen. */
    private int loadGeneration;
    private final javax.swing.Timer sharpen = new javax.swing.Timer(250, event -> sharpenPage());
    /** Text statt Bild (Textansicht). */
    private boolean showingText;
    private List<Heading> headings = List.of();
    /** Die gezeigte E-Mail, sonst {@code null}. */
    private MailMessage mail;
    private final List<Runnable> mailObservers = new java.util.ArrayList<>();
    /** Wer eine Anlage der E-Mail selbst öffnet (etwa der Baum der E-Akte); {@code null}: hier im Viewer. */
    private Consumer<MailMessage.Attachment> mailAttachmentHandler;
    /** Eine geöffnete Anlage der E-Mail, über der E-Mail, mit „Zurück zur E-Mail“. */
    private final JPanel mailPart = new JPanel(new BorderLayout());
    private AttachmentViewerPanel mailPartViewer;
    private final List<Runnable> headingObservers = new java.util.ArrayList<>();

    /**
     * Der Viewer für {@code attachment}.
     *
     * @param loader   holt Originaldatei und Vorschau der Quelle
     * @param opener   öffnet die gespeicherte Kopie mit dem Standardprogramm; {@code null}: das System
     * @param renderer Thread für PDF-Seiten; {@code null}: der gemeinsame Zeichen-Thread
     */
    public AttachmentViewerPanel(ReaderAttachment attachment, ReaderAttachmentLoader loader, ReaderFileOpener opener,
                                 Executor renderer) {
        this(attachment, loader, opener, renderer, null);
    }

    /**
     * Ohne Vorschau (Angelo, 20.11.2026): Name, Typ, Größe und {@code reason} statt des Inhalts, etwa für eine Datei
     * über der Größengrenze; „Speichern“ und „Öffnen“ im Standardprogramm bleiben, über
     * {@link ReaderAttachmentLoader#downloadTo} direkt auf die Platte, wenn der Lader das kann.
     */
    public static AttachmentViewerPanel withoutPreview(ReaderAttachment attachment, ReaderAttachmentLoader loader,
                                                       ReaderFileOpener opener, Executor renderer, String reason) {
        return new AttachmentViewerPanel(attachment, loader, opener, renderer,
                reason == null || reason.isBlank() ? NO_PREVIEW_TEXT : reason);
    }

    private AttachmentViewerPanel(ReaderAttachment attachment, ReaderAttachmentLoader loader, ReaderFileOpener opener,
                                  Executor renderer, String noPreview) {
        super(new BorderLayout(0, 4));
        this.noPreview = noPreview;
        if (attachment == null || loader == null) {
            throw new IllegalArgumentException("attachment and loader must not be null");
        }
        this.attachment = attachment;
        this.attachmentLoader = loader;
        this.fileOpener = opener == null ? ReaderFileOpener.system() : opener;
        this.renderer = renderer == null ? PDF_RENDERER : renderer;
        sharpen.setRepeats(false);
        setOpaque(false);

        busy.setForeground(AppColors.of("app.AttachmentViewerPanel.init"));
        busy.setFont(busy.getFont().deriveFont(Font.ITALIC, 14f));

        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setLineWrap(false);
        textArea.setBackground(ResearchUiPalette.LIGHT_CONTROL_BG);
        textArea.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        textPane = com.aresstack.spaceharvester.app.ui.Comic.scroll(textArea);
        textPane.setBorder(BorderFactory.createEmptyBorder());
        textPane.setVisible(false);
        documentArea.setEditable(false);
        documentArea.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        documentArea.setBackground(AppColors.of("app.AttachmentViewerPanel.init2"));
        documentPane = com.aresstack.spaceharvester.app.ui.Comic.scroll(documentArea);
        documentPane.setBorder(BorderFactory.createEmptyBorder());
        documentPane.setVisible(false);
        structuredPreviewPane.setVisible(false);
        structuredPreviewPane.setOpaque(true);

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
        spinner.setHighlight(com.aresstack.spaceharvester.app.ui.Comic.BLUE);
        spinner.setCaptionColor(AppColors.of("app.AttachmentViewerPanel.init"));
        layers.add(spinner, Integer.valueOf(JLayeredPane.MODAL_LAYER.intValue() + 1));
        for (JComponent button : new JComponent[] {previous, next, download, zoomOut, zoomIn, zoomFit}) {
            layers.add(button, JLayeredPane.PALETTE_LAYER);
        }
        layers.add(textPane, Integer.valueOf(JLayeredPane.MODAL_LAYER.intValue() + 10));
        layers.add(documentPane, Integer.valueOf(JLayeredPane.MODAL_LAYER.intValue() + 10));
        layers.add(structuredPreviewPane, Integer.valueOf(JLayeredPane.MODAL_LAYER.intValue() + 11));
        mailPart.setOpaque(true);
        mailPart.setBackground(ResearchUiPalette.LIGHT_CONTROL_BG);
        mailPart.setVisible(false);
        layers.add(mailPart, Integer.valueOf(JLayeredPane.MODAL_LAYER.intValue() + 12));
        documentArea.addHyperlinkListener(event -> {
            if (event.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED && mail != null
                    && event.getDescription() != null
                    && event.getDescription().startsWith(MailMessage.ATTACHMENT_LINK)) {
                try {
                    openMailAttachment(Integer.parseInt(event.getDescription()
                            .substring(MailMessage.ATTACHMENT_LINK.length())));
                } catch (NumberFormatException ignored) {
                    // nur eigene Verweise
                }
            }
        });
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = layers.getWidth();
                int h = layers.getHeight();
                canvas.setBounds(0, 0, w, h);
                busy.setBounds(0, 0, w, h);
                spinner.setBounds(0, 0, w, h);
                int inset = 8; // die Textansicht eines Anhangs blättert nicht
                textPane.setBounds(inset, 8, Math.max(0, w - 2 * inset), Math.max(0, h - 16));
                documentPane.setBounds(inset, 8, Math.max(0, w - 2 * inset), Math.max(0, h - 16));
                structuredPreviewPane.setBounds(inset, 8, Math.max(0, w - 2 * inset), Math.max(0, h - 16));
                mailPart.setBounds(0, 0, w, h);
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
        footer.setOpaque(false);
        fileInfo.setText(attachment.summary());
        fileInfo.setFont(ResearchUiTypography.semiBold(12f));
        fileInfo.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 0));
        saveButton.getAccessibleContext().setAccessibleName(SAVE_TEXT);
        openButton.getAccessibleContext().setAccessibleName(OPEN_TEXT);
        saveButton.addActionListener(event -> saveAttachment());
        openButton.addActionListener(event -> openAttachment());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        actions.add(saveButton);
        actions.add(openButton);
        footer.add(actions, BorderLayout.WEST);
        footer.add(info, BorderLayout.CENTER);
        footer.add(fileInfo, BorderLayout.EAST);
        footer.putClientProperty(com.aresstack.spaceharvester.app.ui.View.TOOLS_FILL, Boolean.TRUE);
        add(footer, BorderLayout.SOUTH);

        installCanvasInteractions();
        installHover();
        installKeyBindings();
        loadAttachment();
    }

    // ------------------------------------------------------------------ Bedienung (auch für Tests)

    /**
     * Die benachbarten Anhänge derselben Art, zu denen die Pfeile blättern, wenn der Anhang selbst keine weitere Seite
     * hat, wie die Pfeile über die Bilder der Leseansicht.
     */
    public interface Neighbours {

        /** Keine Nachbarn: die Pfeile blättern nur in den Seiten. */
        Neighbours NONE = new Neighbours() {
            @Override
            public boolean hasPrevious() {
                return false;
            }

            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public void previous() {
            }

            @Override
            public void next() {
            }
        };

        boolean hasPrevious();

        boolean hasNext();

        void previous();

        void next();
    }

    private Neighbours neighbours = Neighbours.NONE;

    /** Die Nachbarn für die Pfeile; ohne Aufruf blättern sie nur in den Seiten. */
    public void neighbours(Neighbours neighbours) {
        this.neighbours = java.util.Objects.requireNonNull(neighbours, "neighbours");
    }

    public boolean hasPrevious() {
        return pages != null && page > 0 || neighbours.hasPrevious();
    }

    public boolean hasNext() {
        return pages != null && page < pageCount - 1 || neighbours.hasNext();
    }

    /** Vorige PDF-Seite, auf der ersten Seite der vorige Anhang derselben Art. */
    public void previous() {
        if (pages != null && page > 0) {
            showPage(page - 1);
            SwingUtilities.invokeLater(this::refreshHover);
        } else if (neighbours.hasPrevious()) {
            handOver(neighbours::previous);
        }
    }

    /**
     * Zoom und Ausschnitt, wie der Benutzer sie eingestellt hat (Angelo, 20.11.2026): Sie bleiben beim Blättern auf eine
     * andere PDF-Seite und beim Wechsel auf das vorige oder nächste Bild; „Einpassen“ (0) setzt sie zurück.
     *
     * @param zoom    Bildschirmpixel je Pixel des Bildes bzw. der in Grundgröße gezeichneten PDF-Seite
     * @param offsetX linke Kante des Bildes im Viewer
     * @param offsetY obere Kante des Bildes im Viewer
     */
    public record View(double zoom, double offsetX, double offsetY) {
    }

    /** Was {@link #handOver} dem nächsten Viewer mitgibt; nur auf dem EDT während des Wechsels gesetzt. */
    private static View handedOver;
    /** Zoom und Ausschnitt für das nächste Bild bzw. die nächste Seite; {@code null}: einpassen. */
    private View pendingView = handedOver;

    /** Der eingestellte Zoom samt Ausschnitt; {@code null}, solange eingepasst ist. */
    public View view() {
        if (!canvas.adjusted || canvas.staticImage == null) {
            return null;
        }
        return new View(canvas.zoom * (pages == null ? 1.0 : renderScale / PDF_BASE_SCALE), canvas.offsetX,
                canvas.offsetY);
    }

    /** Übernimmt Zoom und Ausschnitt für das nächste gezeigte Bild bzw. die nächste Seite ({@code null}: einpassen). */
    public void keepView(View view) {
        this.pendingView = view;
    }

    /**
     * Wechselt zum Nachbarn: Der Aufrufer baut dafür einen neuen Viewer, auf dem EDT und gleich hier; er übernimmt
     * Zoom und Ausschnitt dieses Viewers.
     */
    private void handOver(Runnable open) {
        handedOver = view();
        try {
            open.run();
        } finally {
            handedOver = null;
        }
    }

    /** Nächste PDF-Seite, auf der letzten Seite der nächste Anhang derselben Art. */
    public void next() {
        if (pages != null && page < pageCount - 1) {
            showPage(page + 1);
            SwingUtilities.invokeLater(this::refreshHover);
        } else if (neighbours.hasNext()) {
            handOver(neighbours::next);
        }
    }

    /** Ob der Viewer {@code attachment} selbst zeigt (sonst „keine Vorschau“ bzw. nur über die Vorschau der Quelle). */
    public static boolean showsFormatted(ReaderAttachment attachment) {
        return switch (attachment.kind()) {
            case PDF, IMAGE, TEXT, PRESENTATION, SPREADSHEET, NOTEBOOK, MAIL -> true;
            case OFFICE -> WordDocumentPreview.supports(attachment.fileName(), attachment.mediaType());
            default -> false;
        };
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

    /** Whether the locally rendered read-only spreadsheet is visible. */
    public boolean isShowingSpreadsheet() {
        return structuredPreviewPane.isVisible() && structuredPreviewPane.getComponentCount() == 1
                && structuredPreviewPane.getComponent(0) instanceof SpreadsheetPreviewPanel;
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

    /** Die Leiste mit „Speichern“, „Öffnen“, Seite/Zoom und „Typ · Größe“. */
    public JComponent footer() {
        return footer;
    }

    /**
     * Nimmt die Leiste aus der Vorschau, damit sie in der Werkzeugleiste mit der Lupe stehen kann; liefert sie.
     */
    public JComponent detachFooter() {
        remove(footer);
        revalidate();
        return footer;
    }

    /** Die Knöpfe „Speichern“ und „Öffnen“ des Anhang-Viewers. */
    ComposerButton saveButton() {
        return saveButton;
    }

    ComposerButton openButton() {
        return openButton;
    }

    /** „Typ · Größe“ unten rechts. */
    public String fileInfoText() {
        return fileInfo.getText();
    }

    public String infoText() {
        return info.getText();
    }

    /** Für Tests: der Knopf „Öffnen“ der Leiste. */
    javax.swing.AbstractButton openButtonForTest() {
        return openButton;
    }

    /** Der Text über dem Bild (Laden oder Fehlschlag mit Grund). */
    public String statusText() {
        return spinner.isBusy() ? spinner.text() : busy.getText();
    }

    /** Ob gerade die Sanduhr zu sehen ist (für Tests). */
    public boolean loadingShown() {
        return spinner.isBusy();
    }

    /** Zeigt die Sanduhr mit {@code text} darunter, solange der Inhalt lädt. */
    private void loading(String text) {
        busy.setVisible(false);
        spinner.setVeilColor(canvas.getBackground());
        spinner.setText(text);
        spinner.setBusy(true);
    }

    /** Der Inhalt steht: keine Sanduhr, keine Meldung. */
    private void loaded() {
        spinner.setBusy(false);
        busy.setVisible(false);
        SwingUtilities.invokeLater(this::passageReady); // nach dem Einsetzen des Inhalts
    }

    /** Statt des Inhalts eine Meldung. */
    private void message(String text) {
        spinner.setBusy(false);
        busy.setText(text);
        busy.setVisible(true);
        SwingUtilities.invokeLater(this::passageReady);
    }

    private static JComponent transparent() {
        javax.swing.JPanel nothing = new javax.swing.JPanel();
        nothing.setOpaque(false);
        return nothing;
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
        String pageLabel = attachment.kind() == ReaderAttachmentKind.PRESENTATION ? "Folie " : "Seite ";
        info.setText(pages == null ? (zoom.isEmpty() ? " " : zoom)
                : pageLabel + (page + 1) + " / " + pageCount + (zoom.isEmpty() ? "" : "  |  " + zoom));
    }

    private void zoomBy(double factor) {
        canvas.zoomAt(factor, null);
        updateInfo();
        zoomed();
    }

    /** Nach jedem Zoom: eine PDF-Seite wird nach kurzer Pause in der neuen Größe scharf nachgezeichnet. */
    private void zoomed() {
        if (pages != null) {
            sharpen.restart();
        }
    }

    private void fit() {
        pendingView = null;
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

    /**
     * Blendet die Knöpfe nach der Maus ein, wo sie gerade steht, ohne auf eine Bewegung zu warten: nach dem Blättern
     * und wenn ein neuer Viewer erscheint (beim Blättern zum nächsten Anhang), sonst verschwände der Pfeil unter der
     * ruhenden Maus und ließe sich nicht mehrmals hintereinander klicken.
     */
    void refreshHover() {
        if (closed || !layers.isShowing()) {
            return;
        }
        java.awt.PointerInfo info = java.awt.MouseInfo.getPointerInfo();
        if (info == null) {
            return;
        }
        Point p = new Point(info.getLocation());
        SwingUtilities.convertPointFromScreen(p, layers);
        hover(p);
    }

    private void installHover() {
        // Erscheint der Viewer oder ändert er seine Größe, gilt die ruhende Maus sofort.
        layers.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                SwingUtilities.invokeLater(this::refreshHover);
            }
        });
        layers.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                refreshHover();
            }
        });
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
                    canvas.adjusted = true;
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
                zoomed();
            }

            @Override
            public void previous() {
                AttachmentViewerPanel.this.previous();
            }

            @Override
            public void next() {
                AttachmentViewerPanel.this.next();
            }
        }));
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

    /**
     * Aus der Hierarchie genommen (etwa beim Umschalten von Baum und Liste, das die Detailansicht aus- und wieder
     * einhängt): nur den Schärfe-Timer anhalten. Endgültig schließt erst {@link #close()} beim Schließen des Overlays.
     */
    @Override
    public void removeNotify() {
        super.removeNotify();
        sharpen.stop();
    }

    /** Gibt das PDF frei; danach zeichnet das Overlay nichts mehr. Nur beim ausdrücklichen Schließen. */
    public void close() {
        closed = true;
        sharpen.stop();
        closeMailPart();
        final RenderedPages open = pages;
        pages = null;
        if (open != null) {
            renderer.execute(() -> closeQuietly(open));
        }
    }

    // ------------------------------------------------------------------ Anhang laden und zeigen

    /** Ohne Vorschau: der Grund statt des Inhalts ({@link #withoutPreview}); sonst {@code null}. */
    private final String noPreview;

    /** Lädt und zeigt den Anhang je nach Typ. */
    private void loadAttachment() {
        loadGeneration++;
        if (!headings.isEmpty()) {
            headings(List.of());
        }
        showingText = false;
        structuredPreviewPane.setVisible(false);
        closeMailPart();
        mail = null;
        canvas.setStaticImage(null);
        loading(LOADING_ATTACHMENT_TEXT);
        updateInfo();
        if (noPreview != null) {
            showUnavailable(noPreview);
            return;
        }
        switch (attachment.kind()) {
            case OTHER:
                showUnavailable(NO_PREVIEW_TEXT);
                return;
            case NOTEBOOK:
                if (!com.aresstack.spaceharvester.source.OneNotePreviewReader.supportsSize(attachment.size())) {
                    showUnavailable("OneNote: Datei zu groß für die Vorschau (maximal 20 MiB).");
                    return;
                }
                loading("OneNote wird geladen …");
                requestOriginal(bytes -> decodeAside(() -> {
                    try {
                        return com.aresstack.spaceharvester.source.OneNotePreviewReader.shared()
                                .read(attachment.fileName(), bytes);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, this::showOneNote), reason -> showUnavailable("OneNote nicht lesbar: " + reasonText(reason)));
                return;
            case SPREADSHEET:
                loading("Tabelle wird geladen …");
                requestOriginal(bytes -> decodeAside(() -> {
                    try {
                        return SpreadsheetPreviewData.read(attachment.fileName(), attachment.mediaType(), bytes);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                }, this::showSpreadsheet), reason -> showUnavailable(
                        "Tabelle nicht lesbar: " + reasonText(reason)));
                return;
            case MAIL:
                loading("E-Mail wird geladen …");
                requestOriginal(bytes -> decodeAside(() -> {
                    try {
                        return MailMessage.read(attachment.fileName(), bytes);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                }, this::showMail), reason -> showUnavailable("E-Mail nicht lesbar: " + reasonText(reason)));
                return;
            case PRESENTATION:
                loading("PowerPoint wird geladen …");
                requestOriginal(this::openPresentation, reason -> showUnavailable(
                        "PowerPoint nicht geladen: " + reasonText(reason)));
                return;
            case OFFICE:
                if (WordDocumentPreview.supports(attachment.fileName(), attachment.mediaType())) {
                    loading("Word-Vorschau wird geladen …");
                    requestOriginal(bytes -> decodeAside(
                            () -> {
                                try {
                                    return WordDocumentPreview.render(attachment.fileName(), bytes);
                                } catch (IOException failure) {
                                    throw new IllegalStateException(failure.getMessage(), failure);
                                }
                            }, this::showDocument), reason -> showUnavailable(
                            "Word-Dokument nicht lesbar: " + reasonText(reason)));
                    return;
                }
                // Use the source-generated preview for remaining Office formats.
                loading(LOADING_PREVIEW_TEXT);
                attachmentLoader.preview(attachment, bytes -> show(bytes, true),
                        reason -> showUnavailable(NO_PREVIEW_TEXT + "\n" + (reason == null ? "" : reason)));
                return;
            default:
                loading(LOADING_ATTACHMENT_TEXT);
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
            decodeAside(() -> decodeText(bytes, attachment.mediaType()), this::showText);
            return;
        }
        if (isPdf(bytes)) {
            openPdf(bytes);
            return;
        }
        decodeAside(() -> ReaderImageStore.Loaded.of(bytes), this::showLoaded);
    }

    /**
     * Dekodiert abseits des EDT (auf dem Zeichen-Thread, wie das PDF) und zeigt das Ergebnis auf dem EDT, aber nur,
     * wenn der Viewer offen ist und inzwischen nichts anderes geladen wurde.
     */
    private <T> void decodeAside(java.util.function.Supplier<T> work, Consumer<T> apply) {
        final int generation = loadGeneration;
        if (!spinner.isBusy()) {
            loading(spinner.text());
        }
        renderer.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException | OutOfMemoryError e) {
                onEdt(() -> {
                    if (!closed && generation == loadGeneration) {
                        showUnavailable("Anhang nicht darstellbar: " + (e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage()));
                    }
                });
                return;
            }
            onEdt(() -> {
                if (!closed && generation == loadGeneration) {
                    apply.accept(result);
                }
            });
        });
    }

    private void showLoaded(ReaderImageStore.Loaded loaded) {
        if (!loaded.ok()) {
            showUnavailable(loaded.failure());
            return;
        }
        loaded();
        if (loaded.isAnimatedGif()) {
            canvas.setAnimatedImage(loaded.bytes(), loaded.image());
        } else {
            canvas.setStaticImage(loaded.image());
        }
        // Vom vorigen Bild übernommen: derselbe Zoom, derselbe Ausschnitt.
        canvas.apply(pendingView);
        pendingView = null;
        updateInfo();
    }

    private void showOneNote(com.aresstack.spaceharvester.application.assets.OneNotePreview data) {
        if (data.sections().isEmpty()) {
            showUnavailable(OneNotePreviewPanel.unavailableMessage(data.outcome()));
            return;
        }
        showStructuredPreview(new OneNotePreviewPanel(data));
    }

    private void showSpreadsheet(SpreadsheetPreviewData data) {
        showStructuredPreview(new SpreadsheetPreviewPanel(data));
    }

    private void showStructuredPreview(javax.swing.JComponent preview) {
        structuredPreviewPane.removeAll();
        structuredPreviewPane.add(preview, BorderLayout.CENTER);
        structuredPreviewPane.setVisible(true);
        loaded();
        showingText = false;
        textPane.setVisible(false);
        documentPane.setVisible(false);
        structuredPreviewPane.revalidate();
        structuredPreviewPane.repaint();
        updateInfo();
    }

    private void showText(String text) {
        structuredPreviewPane.setVisible(false);
        documentPane.setVisible(false);
        showingText = true;
        loaded();
        shownText = text;
        textArea.setText(text);
        textArea.setCaretPosition(0);
        textPane.setVisible(true);
        hover(pointer);
        updateInfo();
    }

    /**
     * Zeigt ein Word-Dokument wie gesetzt: Überschriften, Absätze, Tabellen und die Bilder an ihrer Stelle (aus dem
     * Speicher, wie in der Leseansicht), nicht nur den Text.
     */
    private void showDocument(WordDocumentPreview.Rendered document) {
        structuredPreviewPane.setVisible(false);
        textPane.setVisible(false);
        showingText = true;
        loaded();
        shownText = document.text();
        javax.swing.text.html.HTMLEditorKit kit = new javax.swing.text.html.HTMLEditorKit();
        kit.getStyleSheet().addRule("body { font-family: " + com.aresstack.spaceharvester.app.ui.Comic.regular(13.5f)
                .getFamily() + "; font-size: 13pt; }");
        kit.getStyleSheet().addRule("table { border-collapse: collapse; } td, th { border: 1px solid #c8c8c8; "
                + "padding: 3px; }");
        documentArea.setEditorKit(kit);
        javax.swing.text.html.HTMLDocument html = (javax.swing.text.html.HTMLDocument) kit.createDefaultDocument();
        java.util.Hashtable<java.net.URL, java.awt.Image> images = new java.util.Hashtable<>();
        document.images().forEach((src, image) -> {
            try {
                images.put(java.net.URI.create(src).toURL(), image);
            } catch (IOException | IllegalArgumentException e) {
                // Eigene Adressen sind immer gültig.
            }
        });
        html.putProperty("imageCache", images);
        html.setAsynchronousLoadPriority(-1);
        try {
            kit.read(new java.io.StringReader(document.html()), html, 0);
        } catch (IOException | javax.swing.text.BadLocationException e) {
            showText(document.text());
            return;
        }
        documentArea.setDocument(html);
        documentArea.setCaretPosition(0);
        documentPane.setVisible(true);
        headings(collectHeadings(html));
        hover(pointer);
        updateInfo();
    }

    /**
     * Zeigt eine E-Mail formatiert: Kopf (Von, An, Cc, Datum, Betreff), die Anlagen als Verweise (ein Klick öffnet
     * sie, PDF formatiert, mit Speichern), darunter der Text mit eingebetteten Bildern (Angelo 24.11.2026).
     */
    private void showMail(MailMessage message) {
        mail = message;
        showDocument(message.render());
        mailObservers.forEach(Runnable::run);
    }

    /** Die gezeigte E-Mail (Kopf, Text, Anlagen), sonst leer. */
    public java.util.Optional<MailMessage> mail() {
        return java.util.Optional.ofNullable(mail);
    }

    /** Meldet, wenn eine E-Mail gezeigt wird (auf dem EDT). */
    public void onMail(Runnable observer) {
        mailObservers.add(java.util.Objects.requireNonNull(observer, "observer"));
    }

    /**
     * Wer eine angeklickte Anlage selbst öffnet, etwa die E-Akte über ihren Baum; ohne öffnet der Viewer sie über der
     * E-Mail.
     */
    public void onMailAttachment(Consumer<MailMessage.Attachment> handler) {
        mailAttachmentHandler = handler;
    }

    /** Öffnet die Anlage {@code index} der gezeigten E-Mail (wie ein Klick auf ihren Verweis). */
    public void openMailAttachment(int index) {
        if (mail == null) {
            return;
        }
        MailMessage.Attachment chosen = mail.allAttachments().stream().filter(a -> a.index() == index).findFirst()
                .orElse(null);
        if (chosen == null) {
            return;
        }
        if (mailAttachmentHandler != null) {
            mailAttachmentHandler.accept(chosen);
            return;
        }
        closeMailPart();
        byte[] data = chosen.data();
        mailPartViewer = new AttachmentViewerPanel(chosen.asReaderAttachment(),
                (ignored, done, failed) -> done.accept(data), fileOpener, renderer);
        ViewerButton back = new ViewerButton(ComposerIcons.chevronLeft(), "Zurück zur E-Mail");
        back.onClick(this::closeMailPart);
        back.setPreferredSize(new Dimension(BUTTON, BUTTON));
        JLabel title = new JLabel(chosen.name());
        title.setFont(ResearchUiTypography.semiBold(12f));
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bar.add(back, BorderLayout.WEST);
        bar.add(title, BorderLayout.CENTER);
        mailPart.removeAll();
        mailPart.add(bar, BorderLayout.NORTH);
        mailPart.add(mailPartViewer, BorderLayout.CENTER);
        mailPart.setVisible(true);
        mailPart.revalidate();
        mailPart.repaint();
    }

    /** Die über der E-Mail geöffnete Anlage, sonst {@code null}. */
    public AttachmentViewerPanel mailPartViewer() {
        return mailPartViewer;
    }

    private void closeMailPart() {
        if (mailPartViewer != null) {
            mailPartViewer.close();
            mailPartViewer = null;
        }
        mailPart.removeAll();
        mailPart.setVisible(false);
    }

    /** Eine Überschrift des gezeigten Word-Dokuments (Formatvorlagen Überschrift 1–3) und ihre Stelle im Text. */
    public record Heading(int level, String text, int offset) {
    }

    /** Die Überschriften des gezeigten Word-Dokuments; leer, solange keins gezeigt wird oder es keine hat. */
    public List<Heading> headings() {
        return headings;
    }

    /** Meldet jede neue Überschriftenliste (auf dem EDT), auch die leere beim Laden. */
    public void onHeadings(Runnable observer) {
        headingObservers.add(observer);
    }

    /**
     * Springt zur Fundstelle und markiert sie (Angelo 24.11.2026: „das Interessante ist ja, wo es steht“). Gesucht wird
     * der wörtliche {@code excerpt} ({@link Passages}): im PDF auf den Seiten (blättert hin, orange Rechtecke über dem
     * Text), im Word-Dokument und in der Textansicht im Text (markiert und rollt hin). Lädt der Anhang noch, wird
     * danach gesucht. {@code found} erfährt auf dem EDT, ob die Stelle gefunden wurde; ohne Auszug, in Bildern und
     * Folien nie.
     */
    public void showPassage(String excerpt, Consumer<Boolean> found) {
        String wanted = excerpt == null ? "" : excerpt.strip();
        if (closed || wanted.isEmpty()) {
            found.accept(false);
            return;
        }
        if (spinner.isBusy()) {
            passageWaiting = () -> locatePassage(wanted, found);
            return;
        }
        locatePassage(wanted, found);
    }

    private void locatePassage(String wanted, Consumer<Boolean> found) {
        if (closed) {
            found.accept(false);
        } else if (documentPane.isVisible()) {
            found.accept(markText(documentArea, wanted));
        } else if (textPane.isVisible()) {
            found.accept(markText(textArea, wanted));
        } else if (pages != null) {
            markPdf(wanted, found);
        } else {
            found.accept(false);
        }
    }

    /** Die markierte Stelle im PDF (für Tests): Seite und Rechtecke, {@code null} ohne. */
    RenderedPages.Passage passage() {
        return passage;
    }

    /** Der markierte Text im Text oder Word-Dokument (für Tests), {@code null} ohne. */
    String markedText() {
        javax.swing.text.JTextComponent area = documentPane.isVisible() ? documentArea : textArea;
        for (javax.swing.text.Highlighter.Highlight mark : area.getHighlighter().getHighlights()) {
            if (mark.getPainter() instanceof PassagePainter) {
                try {
                    return area.getDocument().getText(mark.getStartOffset(),
                            mark.getEndOffset() - mark.getStartOffset());
                } catch (javax.swing.text.BadLocationException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Lädt nichts mehr (Inhalt oder Meldung steht): die wartende Fundstelle suchen. */
    private void passageReady() {
        Runnable waiting = passageWaiting;
        passageWaiting = null;
        if (waiting != null) {
            waiting.run();
        }
    }

    private boolean markText(javax.swing.text.JTextComponent area, String excerpt) {
        javax.swing.text.Highlighter highlighter = area.getHighlighter();
        for (javax.swing.text.Highlighter.Highlight mark : highlighter.getHighlights()) {
            if (mark.getPainter() instanceof PassagePainter) {
                highlighter.removeHighlight(mark);
            }
        }
        javax.swing.text.Document text = area.getDocument();
        try {
            int[] at = Passages.locate(text.getText(0, text.getLength()), excerpt, -1, -1);
            if (at == null) {
                return false;
            }
            highlighter.addHighlight(at[0], at[1], new PassagePainter());
            area.setCaretPosition(at[0]);
            SwingUtilities.invokeLater(() -> reveal(area, at[0], at[1]));
            return true;
        } catch (javax.swing.text.BadLocationException e) {
            return false;
        }
    }

    /** Rollt so, dass die Stelle im oberen Drittel steht (wie die Suche im Editor). */
    private static void reveal(javax.swing.text.JTextComponent area, int start, int end) {
        try {
            java.awt.geom.Rectangle2D from = area.modelToView2D(start);
            java.awt.geom.Rectangle2D to = area.modelToView2D(end);
            if (from == null) {
                return;
            }
            java.awt.Rectangle target = from.getBounds();
            if (to != null) {
                target.add(to.getBounds());
            }
            java.awt.Rectangle visible = area.getVisibleRect();
            int top = Math.max(0, target.y - visible.height / 3);
            area.scrollRectToVisible(new java.awt.Rectangle(target.x, top, 1, Math.max(target.height,
                    visible.height - visible.height / 3)));
        } catch (javax.swing.text.BadLocationException | IllegalArgumentException ignored) {
            // Stelle inzwischen außerhalb des Textes.
        }
    }

    /** Sucht auf dem Zeichen-Thread (PDFBox ist nicht threadsicher) und blättert zur Seite mit der Stelle. */
    private void markPdf(String excerpt, Consumer<Boolean> found) {
        final RenderedPages document = pages;
        renderer.execute(() -> {
            RenderedPages.Passage at;
            try {
                at = document.find(excerpt);
            } catch (IOException | RuntimeException e) {
                at = null;
            }
            final RenderedPages.Passage hit = at;
            onEdt(() -> {
                if (closed || document != pages) {
                    found.accept(false);
                    return;
                }
                passage = hit;
                if (hit == null) {
                    canvas.marks = List.of();
                    canvas.repaint();
                    found.accept(false);
                    return;
                }
                if (hit.page() == page && canvas.staticImage != null) {
                    canvas.marks = hit.boxes();
                    canvas.revealMarks();
                    canvas.repaint();
                } else {
                    showPage(hit.page());
                }
                found.accept(true);
            });
        });
    }

    /** Markiert die Fundstelle im Text; eigene Klasse, damit nur sie wieder entfernt wird. */
    private static final class PassagePainter extends javax.swing.text.DefaultHighlighter.DefaultHighlightPainter {
        PassagePainter() {
            super(Passages.MARK);
        }
    }

    /** Springt zur Überschrift: sie steht danach oben in der Ansicht. */
    public void showHeading(Heading heading) {
        if (!documentPane.isVisible()) {
            return;
        }
        try {
            java.awt.geom.Rectangle2D at = documentArea.modelToView2D(heading.offset());
            if (at != null) {
                java.awt.Rectangle target = at.getBounds();
                target.height = Math.max(1, documentPane.getViewport().getHeight());
                documentArea.scrollRectToVisible(target);
            }
            documentArea.setCaretPosition(heading.offset());
        } catch (javax.swing.text.BadLocationException | IllegalArgumentException ignored) {
            // Stelle außerhalb des Textes: nichts tun.
        }
    }

    private void headings(List<Heading> next) {
        headings = List.copyOf(next);
        headingObservers.forEach(Runnable::run);
    }

    private static List<Heading> collectHeadings(javax.swing.text.html.HTMLDocument html) {
        List<Heading> found = new java.util.ArrayList<>();
        javax.swing.text.ElementIterator elements = new javax.swing.text.ElementIterator(html);
        for (javax.swing.text.Element element = elements.first(); element != null; element = elements.next()) {
            String name = element.getName();
            if (name != null && name.length() == 2 && name.charAt(0) == 'h' && Character.isDigit(name.charAt(1))) {
                try {
                    String text = html.getText(element.getStartOffset(),
                            element.getEndOffset() - element.getStartOffset()).strip();
                    if (!text.isEmpty()) {
                        found.add(new Heading(name.charAt(1) - '0', text, element.getStartOffset()));
                    }
                } catch (javax.swing.text.BadLocationException ignored) {
                    // Element ohne Text.
                }
            }
        }
        return found;
    }

    /** Die gezeigte Text- oder Dokumentansicht als Klartext ({@code ""} außerhalb). */
    public String textContent() {
        return showingText ? shownText : "";
    }

    /** Ob ein Word-Dokument mit seinen Bildern gezeigt wird, und wie viele Bilder es zeigt (für Tests). */
    public int documentImages() {
        if (!documentPane.isVisible() || !(documentArea.getDocument() instanceof javax.swing.text.html.HTMLDocument html)) {
            return -1;
        }
        Object images = html.getProperty("imageCache");
        return images instanceof java.util.Map<?, ?> map ? map.size() : 0;
    }

    /** Statt einer Vorschau: Name, Typ, Größe und der Grund; Speichern und Öffnen bleiben unten. */
    private void showUnavailable(String reason) {
        if (closed) {
            return;
        }
        showingText = false;
        structuredPreviewPane.setVisible(false);
        textPane.setVisible(false);
        documentPane.setVisible(false);
        canvas.setStaticImage(null);
        StringBuilder html = new StringBuilder("<html><div style='text-align:center;width:560px'><b>")
                .append(escape(attachment.fileName())).append("</b><br>").append(escape(attachment.summary()));
        for (String line : String.valueOf(reason).split("\n")) {
            if (!line.trim().isEmpty()) {
                html.append("<br>").append(escape(line));
            }
        }
        html.append("<br><br>").append(escape("Mit „" + SAVE_TEXT + "“ oder „" + OPEN_TEXT
                + "“ unten links bekommst du die Originaldatei.")).append("</div></html>");
        message(html.toString());
        updateInfo();
    }

    /** Öffnet das PDF auf dem Zeichen-Thread und zeigt Seite 1. */
    private void openPdf(final byte[] bytes) {
        loading("PDF wird geöffnet …");
        renderer.execute(() -> {
            PdfPages opened = null;
            String failure = null;
            try {
                opened = PdfPages.open(bytes);
            } catch (IOException | RuntimeException e) {
                failure = "PDF nicht lesbar: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage());
            }
            final RenderedPages document = opened;
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
                pages = document;
                pageCount = document.pageCount();
                if (pageCount == 0) {
                    showUnavailable("Das PDF hat keine Seiten.");
                    return;
                }
                showPage(0);
            });
        });
    }

    /** Open and render presentations through the same paginated view as PDFs. */
    private void openPresentation(final byte[] bytes) {
        loading("Präsentation wird geöffnet …");
        renderer.execute(() -> {
            RenderedPages opened = null;
            String failure = null;
            try {
                opened = PresentationPages.open(bytes);
            } catch (IOException | RuntimeException e) {
                failure = "PowerPoint nicht lesbar: " + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
            final RenderedPages document = opened;
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
                pages = document;
                pageCount = document.pageCount();
                if (pageCount == 0) {
                    showUnavailable("Die Präsentation hat keine Folien.");
                    return;
                }
                showPage(0);
            });
        });
    }

    /** Zeichnet Seite {@code target} in der Grundschärfe und passt sie ein. */
    private void showPage(final int target) {
        if (pages == null) {
            return;
        }
        page = Math.max(0, Math.min(target, pageCount - 1));
        // Beim Blättern bleiben Zoom und Ausschnitt (Angelo, 20.11.2026); die neue Seite kommt in Grundgröße.
        View kept = view();
        if (kept != null) {
            pendingView = kept;
        }
        renderScale = PDF_BASE_SCALE;
        sharpen.stop();
        if (canvas.staticImage == null) {
            loading("Seite " + (page + 1) + " wird gezeichnet …");
        }
        hover(pointer);
        render(page, PDF_BASE_SCALE, true);
    }

    /**
     * Zeichnet die Seite auf dem Zeichen-Thread. {@code fresh}: neu einpassen (Seitenwechsel); sonst ersetzt die
     * schärfere Fassung die alte, ohne dass sich Größe oder Ausschnitt auf dem Schirm ändern.
     */
    private void render(final int target, final double scale, final boolean fresh) {
        final RenderedPages document = pages;
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
                if (closed || document != pages || generation != renderGeneration) {
                    return; // inzwischen geblättert, gezoomt oder geschlossen
                }
                if (drawn == null) {
                    message(reason);
                    return;
                }
                loaded();
                if (fresh) {
                    canvas.marks = passage != null && passage.page() == target ? passage.boxes() : List.of();
                    canvas.setStaticImage(drawn);
                    if (pendingView != null) {
                        canvas.apply(pendingView);
                        pendingView = null;
                        zoomed();
                    }
                    canvas.revealMarks();
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
        if (pages == null || canvas.staticImage == null) {
            return;
        }
        double wanted = Math.min(renderScale * canvas.zoom, pages.maxScale(page));
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
            if (attachment.size() == 0 && bytes.length > 0) {
                fileInfo.setText(attachment.typeLabel() + " · " + ReaderAttachment.size(bytes.length));
            }
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
        if (original == null && attachmentLoader.downloadsToFile()) {
            saveToFile();
            return;
        }
        requestOriginal(bytes -> {
            JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create(safeFileName(attachment.fileName()));
            chooser.setDialogTitle("Anhang speichern");
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

    /** Speichern einer großen Datei: direkt aus der Quelle in die gewählte Datei, ohne Umweg über den Speicher. */
    private void saveToFile() {
        JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create(safeFileName(attachment.fileName()));
        chooser.setDialogTitle("Anhang speichern");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        loading("Wird gespeichert …");
        attachmentLoader.downloadTo(attachment, target.toPath(), () -> {
            loadAttachment();
            JOptionPane.showMessageDialog(this, FILE_SAVED_PREFIX + target.getName(), "Download",
                    JOptionPane.INFORMATION_MESSAGE);
        }, reason -> {
            loadAttachment();
            JOptionPane.showMessageDialog(this, SAVE_FAILED_PREFIX + reasonText(reason), "Download-Fehler",
                    JOptionPane.ERROR_MESSAGE);
        });
    }

    /** Öffnen einer großen Datei: direkt aus der Quelle in einen eigenen temporären Ordner, dann im Standardprogramm. */
    private void openFromFile() {
        File copy;
        try {
            File folder = Files.createTempDirectory("space-harvester-anhang").toFile();
            folder.deleteOnExit();
            copy = new File(folder, safeFileName(attachment.fileName()));
            copy.deleteOnExit();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, OPEN_FAILED_PREFIX + e.getMessage(), "Öffnen",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        loading("Wird geladen …");
        attachmentLoader.downloadTo(attachment, copy.toPath(), () -> {
            loadAttachment();
            try {
                fileOpener.open(copy);
            } catch (IOException | RuntimeException e) {
                JOptionPane.showMessageDialog(this, OPEN_FAILED_PREFIX + reasonText(e.getMessage()), "Öffnen",
                        JOptionPane.ERROR_MESSAGE);
            }
        }, reason -> {
            loadAttachment();
            JOptionPane.showMessageDialog(this, OPEN_FAILED_PREFIX + reasonText(reason), "Öffnen",
                    JOptionPane.ERROR_MESSAGE);
        });
    }

    private void openAttachment() {
        if (original == null && attachmentLoader.downloadsToFile()) {
            openFromFile();
            return;
        }
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
        // Mehr als MAX_TEXT_CHARS Zeichen zeigt die Ansicht nie: nur so viele Bytes dekodieren, wie dafür reichen.
        int end = bytes.length;
        boolean cut = (long) end - offset > (long) MAX_TEXT_CHARS * 4;
        if (cut) {
            end = offset + MAX_TEXT_CHARS * 4;
            while (end > offset && (bytes[end] & 0xC0) == 0x80) {
                end--; // nicht mitten in einer UTF-8-Folge abschneiden
            }
            if (charset != null && charset.name().startsWith("UTF-16")) {
                end -= (end - offset) % 2; // und nicht mitten in einem UTF-16-Zeichen (bei UTF-8 nie: das träfe
                                           // wieder eine Folge und kippte die Erkennung nach Windows-1252)
            }
        }
        String text;
        if (charset != null) {
            text = new String(bytes, offset, end - offset, charset);
        } else {
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, end))
                        .toString();
            } catch (CharacterCodingException notUtf8) {
                text = new String(bytes, 0, end, Charset.forName("windows-1252"));
            }
        }
        if (cut || text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, Math.min(text.length(), MAX_TEXT_CHARS)) + "\n\n[… gekürzt; die ganze Datei über „" + SAVE_TEXT
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

    private static void closeQuietly(RenderedPages document) {
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
        /** Ob der Benutzer gezoomt oder den Ausschnitt verschoben hat; „Einpassen“ setzt es zurück. */
        boolean adjusted;
        /** Die markierte Fundstelle als Rechtecke, Anteil der Seite (0…1); leer ohne. */
        List<java.awt.geom.Rectangle2D> marks = List.of();

        Canvas() {
            setBackground(AppColors.of("app.AttachmentViewerPanel.canvas"));
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

        /** Setzt Zoom und Ausschnitt; {@code null} lässt das eingepasste Bild. */
        void apply(View view) {
            if (view == null || staticImage == null) {
                return;
            }
            zoom = Math.max(0.05, Math.min(view.zoom(), 20.0));
            offsetX = view.offsetX();
            offsetY = view.offsetY();
            fitted = true;
            adjusted = true;
            repaint();
        }

        void resetView() {
            adjusted = false;
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
            adjusted = true;
            double px = pivot != null ? pivot.x : getWidth() / 2.0;
            double py = pivot != null ? pivot.y : getHeight() / 2.0;
            offsetX = px - (px - offsetX) * (zoom / oldZoom);
            offsetY = py - (py - offsetY) * (zoom / oldZoom);
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
            adjusted = true;
            repaint();
            return true;
        }

        int getZoomPercent() {
            return (int) Math.round(zoom * 100);
        }

        /** Ist die Seite größer als die Fläche, den Ausschnitt so legen, dass die Fundstelle mittig steht. */
        void revealMarks() {
            if (marks.isEmpty() || imgW <= 0 || getWidth() <= 0) {
                return;
            }
            java.awt.geom.Rectangle2D first = marks.get(0);
            double w = imgW * zoom;
            double h = imgH * zoom;
            if (w > getWidth()) {
                offsetX = ViewerWheel.panTo(offsetX, getWidth() / 2.0 - first.getCenterX() * w, w, getWidth());
            }
            if (h > getHeight()) {
                offsetY = ViewerWheel.panTo(offsetY, getHeight() / 2.0 - first.getCenterY() * h, h, getHeight());
            }
            repaint();
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
                for (java.awt.geom.Rectangle2D mark : marks) {
                    int mx = x + (int) Math.round(mark.getX() * w);
                    int my = y + (int) Math.round(mark.getY() * h);
                    int mw = Math.max(2, (int) Math.round(mark.getWidth() * w));
                    int mh = Math.max(2, (int) Math.round(mark.getHeight() * h));
                    g2.setColor(Passages.MARK);
                    g2.fillRect(mx, my, mw, mh);
                    g2.setColor(Passages.MARK.darker());
                    g2.drawRect(mx, my, mw, mh);
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
            Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tooltip);
            JLabel label = new JLabel(icon, SwingConstants.CENTER);
            label.setForeground(AppColors.of("app.AttachmentViewerPanel.viewerButton"));
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
                g2.setColor(AppColors.of("app.AttachmentViewerPanel.paintComponent"));
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
