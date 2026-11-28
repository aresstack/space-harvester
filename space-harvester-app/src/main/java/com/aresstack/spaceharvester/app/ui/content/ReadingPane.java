package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.settings.ViewerBackground;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import com.aresstack.spaceharvester.app.ui.controls.ViewerPopup;
import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.diagrams.VectorDiagram;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URL;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Leseansicht einer Seite (#192): das gerenderte HTML der Quelle aus dem Core-Use-Case (#176) mit den Bildern, die die
 * Ansicht zeigt (aus dem AssetStore, sonst einmalig über die Anmeldung der Quelle abgelegt) und Mermaid-Diagrammen als
 * abgelegte Grafik des Core (einmal gezeichnet, {@code Assets.rendition}). Eigene Abruf- oder Renderlogik gibt es hier
 * nicht. Ein Bild, das sich nicht laden lässt, steht als
 * markierter Platzhalter im Text; ein Klick auf ein Bild zeigt es vergrößert.
 *
 * <p>Der Swing-HTML-Leser lädt nichts selbst: Jedes Bild steht vorab im Bildspeicher des Dokuments, alle anderen
 * Verweise auf fremde Ressourcen (Skripte, Stile, eingebettete Objekte) werden entfernt.
 */
final class ReadingPane extends JPanel {

    /** Breiteste Darstellung eines Bildes im Text; größere werden verkleinert, der Klick zeigt das Original. */
    static final int MAX_WIDTH = 720;
    /** Höchstens so viele Bilder je Seite; weitere erscheinen als Platzhalter. */
    static final int MAX_IMAGES = 200;
    /** Platzhalter eines Bilds, für das die Quelle einen Einmalcode verlangt; Text wie im EAC. */
    static final String SIGN_IN_PENDING = "Für dieses Bild ist eine zusätzliche Anmeldung (Einmalcode) "
            + "erforderlich: ";
    /** Adressraum der vorab geladenen Bilder; {@code .invalid} wird nie aufgelöst. */
    private static final String IMAGE_BASE = "http://reading.invalid/image/";
    private static final Set<String> REMOVED = Set.of("script", "style", "link", "meta", "iframe", "frame", "object",
            "embed", "form", "input", "button", "select", "textarea", "noscript", "svg", "video", "audio", "source",
            "canvas", "template", "base");
    private static final Map<String, Set<String>> KEPT_ATTRIBUTES = Map.of(
            "a", Set.of("href", "title", "name"),
            "img", Set.of("src", "alt", "title", "width", "height"),
            "td", Set.of("colspan", "rowspan"),
            "th", Set.of("colspan", "rowspan"),
            "ol", Set.of("start"));

    private final JEditorPane pane = new JEditorPane();
    private final JLabel hint = Comic.muted(" ");
    /** Hinweis oben, wenn die Quelle einen neueren Stand hat als der gezeigte aus dem Cache (Angelo, 17.11.2026). */
    private final MessagePlate update = new MessagePlate();
    /** Text des Links im Hinweis auf einen neueren Stand. */
    static final String RELOAD = "Seite neu laden";
    /** Oberer Innenabstand der Ansicht ohne Wolkenbanner. */
    private static final int PADDING_TOP = 10;
    /** Die Ansicht mit den Inhaltswolken der Dokumentnavigation darüber, wie unter „Text“. */
    private final javax.swing.JScrollPane scroll = Comic.scroll(pane);
    private final NavigableDocumentPage page = new NavigableDocumentPage(scroll, pane, PADDING_TOP);
    /** Vorlesen der Seite mit dem runden Knopf oben rechts und Doppelklick-Weiterlesen wie im EAC. */
    private final PageReadAloud readAloud = new PageReadAloud(pane);
    /** Drehender Ring über dem Text, solange die Leseansicht lädt; statt eines Hinweistexts oben. */
    private final BusyOverlay overlay = new BusyOverlay(page);
    private String details = "";
    private Map<String, BufferedImage> originals = Map.of();
    /** Vektorzeichnungen der draw.io-Diagramme je eigener Bildadresse; der Betrachter zeichnet sie bei jedem Zoom. */
    private Map<String, VectorDiagram> vectors = Map.of();
    /** HTML der gezeigten Ansicht, für die Bildtitel im Betrachter. */
    private String html = "";
    /** Bildspeicher des gezeigten Dokuments; nachgeladene Bilder landen hier und das Dokument holt sie sich. */
    private Hashtable<URL, Image> cache = new Hashtable<>();
    /** Die Bilder der gezeigten Ansicht, die nach dem Anzeigen im Hintergrund geladen werden. */
    private List<Slot> slots = List.of();
    /** Zähler der Bildzeile für die gezeigte Ansicht. */
    private int images;
    private int missing;
    private boolean stored;
    /** Jede neue Ansicht (oder das Leeren) zählt hoch; ein Nachladen der vorigen Ansicht verfällt damit. */
    private volatile int generation;
    /** Was ein angeklickter Link auslöst; ohne Abnehmer öffnet er im Browser. */
    private java.util.function.Consumer<URL> links = ReadingPane::browse;
    /** Farben des Bildbetrachters beim nächsten Öffnen, aus der Einstellung; ohne Angabe die Vorgabe. */
    private java.util.function.Supplier<ImageViewer.Look> viewerLook =
            () -> Comic.viewerLook(ViewerBackground.DEFAULT);

    ReadingPane() {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.setBorder(BorderFactory.createEmptyBorder(PADDING_TOP, 12, 10, 12));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setFont(Comic.regular(13.5f));
        pane.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                links.accept(event.getURL());
            }
        });
        pane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                String src = imageAt(event);
                if (src != null && originals.containsKey(src)) {
                    enlarge(List.copyOf(originals.keySet()).indexOf(src));
                }
            }
        });
        pane.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                // Abseits eines Bildes ist die Quelle null; Map.of() verträgt keinen null-Schlüssel.
                String src = imageAt(event);
                boolean image = src != null && originals.containsKey(src);
                Cursors.set(pane, image ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                pane.setToolTipText(image ? "Klicken zum Vergrößern" : null);
            }
        });
        overlay.setHighlight(Comic.BLUE);
        overlay.setVeilColor(Comic.SURFACE);
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        update.setAlignmentX(LEFT_ALIGNMENT);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        top.add(update);
        top.add(hint);
        add(top, BorderLayout.NORTH);
        add(overlay, BorderLayout.CENTER);
        page.setOrb(readAloud.orb());
        clear(" ");
    }

    /** Das Vorlesen dieser Ansicht. */
    PageReadAloud readAloud() {
        return readAloud;
    }

    /**
     * Der Satz über einen neueren Stand: „Es gibt N Updates seit Ihrem letzten Besuch –“, N aus den Versionsnummern
     * (neu minus gezeigt); sind es keine Zahlen oder ist die Differenz nicht positiv, ohne Zahl.
     */
    static String updateSentence(String shownVersion, String freshVersion) {
        long count = 0;
        try {
            count = Long.parseLong(freshVersion.trim()) - Long.parseLong(shownVersion.trim());
        } catch (RuntimeException notNumbers) {
            count = 0;
        }
        if (count == 1) {
            return "Es gibt 1 Update seit Ihrem letzten Besuch –";
        }
        return "Es gibt " + (count > 1 ? count + " " : "") + "Updates seit Ihrem letzten Besuch –";
    }

    /**
     * Zeigt oben den Hinweis auf einen neueren Stand (Meldungsplatte mit ✕); „Seite neu laden“ führt {@code reload} aus
     * und blendet ihn aus.
     */
    void offerUpdate(String sentence, Runnable reload) {
        update.show(sentence, RELOAD, java.util.Objects.requireNonNull(reload, "reload"));
    }

    private void hideUpdate() {
        update.dismiss();
    }

    /** Für Tests: der Satz des Hinweises auf einen neueren Stand, leer ohne Hinweis. */
    String updateOffer() {
        return update.sentence();
    }

    /** Für Tests: die Meldungsplatte des Hinweises. */
    MessagePlate updatePlate() {
        return update;
    }

    /**
     * Zeigt einen neueren Stand derselben Seite an derselben Rollposition (nach „Seite neu laden“): Die Ansicht springt
     * nicht an den Anfang.
     */
    void showKeepingPosition(Prepared prepared) {
        java.awt.Point position = scroll.getViewport().getViewPosition();
        show(prepared);
        javax.swing.SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(position));
    }

    /** Ein Klick auf „Seite neu laden“ (auch für Tests). */
    void clickUpdate() {
        update.clickAction();
    }

    /**
     * Was ein angeklickter Link tut (Leseansicht des EAC: Seiten derselben Quelle öffnen im Lesebereich, alles andere
     * im Browser über {@link #browse}).
     */
    void onLink(java.util.function.Consumer<URL> listener) {
        this.links = java.util.Objects.requireNonNull(listener, "listener");
    }

    /**
     * Die Inhaltswolken über der Ansicht (dieselbe Dokumentnavigation wie unter „Text“, mit Unterwolken des gewählten
     * Kapitels); {@code null} blendet sie aus. Die Abschnitte findet die Ansicht über ihren Anker im Text.
     */
    void setNavigation(Navigation navigation, String plainText) {
        page.setNavigation(navigation, plainText == null ? "" : plainText);
    }

    /** Für Tests: die Ansicht mit dem Wolkenbanner. */
    NavigableDocumentPage navigablePage() {
        return page;
    }

    /** Rollt zum Anker {@code fragment} der gezeigten Ansicht; ohne passenden Anker nichts. */
    void scrollToAnchor(String fragment) {
        if (fragment == null || fragment.isBlank() || !(pane.getDocument() instanceof HTMLDocument document)) {
            return;
        }
        // Wie scrollToReference, aber das Ziel steht unter den Inhaltswolken statt dahinter.
        HTMLDocument.Iterator anchors = document.getIterator(HTML.Tag.A);
        for (; anchors.isValid(); anchors.next()) {
            Object name = anchors.getAttributes().getAttribute(HTML.Attribute.NAME);
            if (fragment.equals(name)) {
                page.scrollToOffset(anchors.getStartOffset());
                return;
            }
        }
    }

    /** Leert die Ansicht und zeigt {@code text} als Hinweis. */
    void clear(String text) {
        readAloud.speechText(null);
        hideUpdate();
        overlay.setBusy(false);
        generation++;
        slots = List.of();
        cache = new Hashtable<>();
        originals = Map.of();
        vectors = Map.of();
        html = "";
        details = "";
        pane.setText("");
        hint.setText(text);
        hint.setForeground(Comic.TEXT);
    }

    /** Leert die Ansicht und zeigt den drehenden Ring über dem Textbereich, bis {@link #show} oder {@link #clear}. */
    void loading() {
        clear(" ");
        overlay.setBusy(true);
    }

    /** Ob der Ring gerade gezeigt wird, für Tests. */
    boolean loadingShown() {
        return overlay.isBusy();
    }

    /** Für Tests: der angezeigte Hinweis und das HTML, aus dem die Ansicht gebaut ist. */
    String hint() {
        return hint.getText();
    }

    /** Die Bildzeile der gezeigten Ansicht („1 Bild · live aus der Quelle …“) für die Seitendetails; sonst leer. */
    String details() {
        return details;
    }

    /**
     * Zeigt eine vorbereitete Ansicht; auf dem EDT. Die Bildzeile steht nicht dauerhaft über dem Text, sondern in den
     * Seitendetails (ⓘ); über dem Text nur, wenn etwas nicht darstellbar ist oder es keinen Text gibt. Bilder, die
     * die Ansicht noch nicht geladen hat ({@link Prepared#slots}), stehen zuerst als Platzhalter im Text und werden im
     * Hintergrund nacheinander geladen und eingesetzt, ohne dass die Rollposition springt.
     */
    void show(Prepared prepared) {
        hideUpdate();
        overlay.setBusy(false);
        generation++;
        originals = new LinkedHashMap<>(prepared.originals());
        vectors = new LinkedHashMap<>(prepared.vectors());
        html = prepared.html();
        slots = prepared.slots();
        images = prepared.images();
        missing = prepared.missing();
        stored = prepared.stored();
        if (prepared.html().isEmpty()) {
            details = "";
            hint.setText(prepared.note());
            hint.setForeground(Comic.TEXT);
            pane.setText("");
            return;
        }
        showNote(prepared.note());
        HTMLEditorKit kit = new HTMLEditorKit();
        kit.getStyleSheet().addRule("body { font-family: " + Comic.regular(13.5f).getFamily() + "; font-size: 13pt; }");
        kit.getStyleSheet().addRule("table { border-collapse: collapse; } td, th { border: 1px solid #c8c8c8; "
                + "padding: 3px; }");
        pane.setEditorKit(kit);
        HTMLDocument document = (HTMLDocument) kit.createDefaultDocument();
        cache = new Hashtable<>();
        prepared.scaled().forEach((src, image) -> {
            try {
                cache.put(URI.create(src).toURL(), image);
            } catch (IOException | IllegalArgumentException e) {
                // Eigene Adressen sind immer gültig.
            }
        });
        document.putProperty("imageCache", cache);
        document.setAsynchronousLoadPriority(-1);
        try {
            kit.read(new StringReader(prepared.html()), document, 0);
        } catch (IOException | javax.swing.text.BadLocationException e) {
            clear("Leseansicht nicht darstellbar: " + e.getClass().getSimpleName());
            return;
        }
        pane.setDocument(document);
        pane.setCaretPosition(0);
        page.contentChanged();
        loadDeferred();
    }

    /** Die Bildzeile zur gezeigten Ansicht: in den Details immer, über dem Text nur mit nicht darstellbaren Bildern. */
    private void showNote(String note) {
        details = note;
        hint.setText(missing > 0 ? note : " ");
        hint.setForeground(missing > 0 ? Comic.ORANGE : Comic.TEXT);
    }

    /**
     * Lädt die noch offenen Bilder der gezeigten Ansicht nacheinander im Hintergrund (ein Abruf zur Zeit, wie bisher,
     * damit die Anmeldung der Quelle nicht mehrfach angestoßen wird) und setzt jedes sofort ein. Schon geladene Bilder
     * derselben Ansicht erscheinen gleich.
     */
    private void loadDeferred() {
        List<Slot> open = new java.util.ArrayList<>();
        for (Slot slot : slots) {
            Result known = slot.result();
            if (known != null) {
                apply(slot, known);
            } else {
                open.add(slot);
            }
        }
        if (open.isEmpty()) {
            return;
        }
        int shown = generation;
        Background.execute(() -> {
            for (Slot slot : open) {
                if (shown != generation) {
                    return;
                }
                Result result = slot.resolve();
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (shown == generation) {
                        apply(slot, result);
                    }
                });
            }
        }, () -> { }, error -> { });
    }

    /** Für Tests: wie viele Bilder der gezeigten Ansicht noch nicht geladen sind. */
    int pendingImages() {
        int pending = 0;
        for (Slot slot : slots) {
            if (slot.result() == null) {
                pending++;
            }
        }
        return pending;
    }

    /** Für Tests: der Bildspeicher des gezeigten Dokuments. */
    Map<URL, Image> imageCache() {
        return Map.copyOf(cache);
    }

    /** Setzt ein geladenes Bild (oder seinen Platzhaltertext) an die Stelle des Platzhalters; auf dem EDT. */
    private void apply(Slot slot, Result result) {
        URL url;
        try {
            url = URI.create(slot.src()).toURL();
        } catch (IOException | IllegalArgumentException e) {
            return;
        }
        Image shown;
        if (result.ok()) {
            originals.put(slot.src(), result.original());
            if (result.vector() != null) {
                vectors.put(slot.src(), result.vector());
            }
            shown = result.shown();
            images++;
        } else {
            shown = marker(result.missing(), pane.getFont());
            missing++;
        }
        cache.put(url, shown);
        int width = shown.getWidth(null);
        int height = shown.getHeight(null);
        keepingAnchor(() -> resize(slot.src(), result.ok() ? result.width() : width,
                result.ok() ? result.height() : height));
        showNote(note(images, missing, stored));
    }

    /**
     * Gibt dem {@code <img>} mit dieser Adresse die neue Größe. Die geänderten Attribute lassen die Bildansicht ihr
     * Bild neu aus dem Bildspeicher holen (Swing lädt es nach einer Attributänderung neu).
     */
    private void resize(String src, int width, int height) {
        if (!(pane.getDocument() instanceof HTMLDocument document)) {
            return;
        }
        HTMLDocument.Iterator imgs = document.getIterator(HTML.Tag.IMG);
        for (; imgs.isValid(); imgs.next()) {
            if (!src.equals(imgs.getAttributes().getAttribute(HTML.Attribute.SRC))) {
                continue;
            }
            javax.swing.text.SimpleAttributeSet size = new javax.swing.text.SimpleAttributeSet();
            size.addAttribute(HTML.Attribute.WIDTH, String.valueOf(width));
            size.addAttribute(HTML.Attribute.HEIGHT, String.valueOf(height));
            document.setCharacterAttributes(imgs.getStartOffset(), imgs.getEndOffset() - imgs.getStartOffset(),
                    size, false);
            pane.revalidate();
            pane.repaint();
            return;
        }
    }

    /**
     * Führt {@code change} aus und hält dabei die Zeile fest, die oben im Sichtfenster steht: Wird darüber ein Bild
     * größer oder kleiner, rollt die Ansicht so nach, dass der Leser dieselbe Stelle sieht.
     */
    private void keepingAnchor(Runnable change) {
        javax.swing.JViewport viewport = scroll.getViewport();
        java.awt.Point before = viewport.getViewPosition();
        int offset;
        int lineAbove;
        try {
            offset = before.y <= 0 ? -1 : pane.viewToModel2D(before);
            lineAbove = offset < 0 ? 0 : (int) Math.round(pane.modelToView2D(offset).getY()) - before.y;
        } catch (javax.swing.text.BadLocationException | RuntimeException e) {
            offset = -1;
            lineAbove = 0;
        }
        change.run();
        if (offset < 0) {
            return;
        }
        int anchor = offset;
        int above = lineAbove;
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                int y = (int) Math.round(pane.modelToView2D(anchor).getY()) - above;
                if (y != viewport.getViewPosition().y) {
                    viewport.setViewPosition(new java.awt.Point(before.x, Math.max(0, y)));
                }
            } catch (javax.swing.text.BadLocationException | RuntimeException e) {
                // Die Stelle gibt es nicht mehr: die Ansicht bleibt, wo sie ist.
            }
        });
    }

    /**
     * Vorbereitete Ansicht: HTML mit eigenen Bildadressen, die Bilder dazu und was fehlt. {@code slots} sind Bilder, die
     * erst nach dem Anzeigen geladen werden ({@link #prepareDeferred}); {@code images} und {@code missing} zählen dann
     * nur, was schon beim Vorbereiten feststand.
     */
    record Prepared(String html, Map<String, Image> scaled, Map<String, BufferedImage> originals,
                    Map<String, VectorDiagram> vectors, int images, int missing, String note, List<Slot> slots,
                    boolean stored) {

        Prepared(String html, Map<String, Image> scaled, Map<String, BufferedImage> originals,
                 Map<String, VectorDiagram> vectors, int images, int missing, String note) {
            this(html, scaled, originals, vectors, images, missing, note, List.of(), true);
        }

        /** Dieselbe Ansicht mit anderem HTML, etwa mit markierten Änderungen ({@link ReadingChanges}). */
        Prepared withHtml(String changed) {
            return new Prepared(changed, scaled, originals, vectors, images, missing, note, slots, stored);
        }
    }

    /**
     * Ein Bild der Ansicht, das erst nach dem Anzeigen geladen wird: seine eigene Adresse im Text und das Ergebnis,
     * sobald es einmal geladen wurde (ein zweites Anzeigen derselben Ansicht lädt nicht erneut).
     */
    static final class Slot {

        private final String src;
        private final String label;
        private final java.util.concurrent.Callable<Result> loader;
        private volatile Result result;

        private Slot(String src, String label, java.util.concurrent.Callable<Result> loader) {
            this.src = src;
            this.label = label;
            this.loader = loader;
        }

        String src() {
            return src;
        }

        String label() {
            return label;
        }

        /** Das Ergebnis, sobald geladen; sonst {@code null}. */
        Result result() {
            return result;
        }

        /** Lädt das Bild (einmal) und merkt sich das Ergebnis; nie auf dem EDT. */
        Result resolve() {
            Result known = result;
            if (known == null) {
                try {
                    known = loader.call();
                } catch (Exception e) {
                    known = Result.missing("Bild fehlt: " + label);
                }
                result = known;
            }
            return known;
        }
    }

    /**
     * Ein geladenes Bild: Original für den Betrachter, Anzeigebild und -größe im Text, die Vektorzeichnung bei
     * draw.io; oder nur der Text des Platzhalters, wenn es sich nicht laden ließ.
     */
    record Result(BufferedImage original, Image shown, int width, int height, VectorDiagram vector, String missing) {

        static Result missing(String text) {
            return new Result(null, null, 0, 0, null, text);
        }

        boolean ok() {
            return original != null;
        }
    }

    /**
     * Baut die Ansicht außerhalb des EDT: säubert das HTML, lädt die Bilder über {@code load} und die Mermaid-Grafiken
     * über {@code mermaid} (beides Bytes aus dem Core, {@code null} wenn es keine gibt). Ein Bild oder Diagramm, das sich nicht laden oder lesen lässt, wird markiert.
     */
    static Prepared prepare(ReadingView view, Function<HarvestSource.ViewImage, byte[]> load,
                            Function<String, byte[]> mermaid) {
        return prepare(view, image -> Optional.empty(), load, mermaid, screenScale());
    }

    /**
     * Wie {@link #prepare(ReadingView, Function, Function)}, holt aber zuerst über {@code vector} die Vektorzeichnung
     * eines draw.io-Diagramms. Gibt es sie, wird nur sie genutzt (kein zweiter Abruf über {@code load}): im Text
     * gerastert in Anzeigebreite × {@code scale} (Bildschirmskalierung), im Betrachter bei jedem Zoom neu gezeichnet.
     */
    static Prepared prepare(ReadingView view, Function<HarvestSource.ViewImage, Optional<VectorDiagram>> vector,
                            Function<HarvestSource.ViewImage, byte[]> load, Function<String, byte[]> mermaid,
                            double scale) {
        return build(view, vector, load, mermaid, scale, false);
    }

    /**
     * Wie {@link #prepare(ReadingView, Function, Function, Function, double)}, lädt aber kein Bild: Jedes bekommt
     * einen Platzhalter in der im HTML genannten Größe (sonst einer Standardgröße) und einen {@link Slot}, über den die
     * Ansicht es nach dem Anzeigen im Hintergrund nachholt. Das HTML steht damit sofort (Angelo, 17.11.2026).
     */
    static Prepared prepareDeferred(ReadingView view,
                                    Function<HarvestSource.ViewImage, Optional<VectorDiagram>> vector,
                                    Function<HarvestSource.ViewImage, byte[]> load, Function<String, byte[]> mermaid,
                                    double scale) {
        return build(view, vector, load, mermaid, scale, true);
    }

    private static Prepared build(ReadingView view, Function<HarvestSource.ViewImage, Optional<VectorDiagram>> vector,
                                  Function<HarvestSource.ViewImage, byte[]> load, Function<String, byte[]> mermaid,
                                  double scale, boolean deferred) {
        switch (view.status()) {
            case RESTRICTED -> {
                return note("Diese Seite bindet Inhalte ein, die du nicht nachweislich lesen darfst. Die Leseansicht "
                        + "bleibt deshalb verborgen; unter „Inhalt“ steht der Text der Seite selbst.");
            }
            case UNSUPPORTED -> {
                return note("Diese Quelle hat keine Leseansicht; der Text steht unter „Inhalt“.");
            }
            default -> {
            }
        }
        Document document = Jsoup.parseBodyFragment(view.html(), view.baseUri());
        document.select(String.join(",", REMOVED)).remove();
        Map<String, Image> scaled = new LinkedHashMap<>();
        Map<String, BufferedImage> originals = new LinkedHashMap<>();
        Map<String, VectorDiagram> vectors = new LinkedHashMap<>();
        List<Slot> slots = new java.util.ArrayList<>();
        int[] counts = new int[2];
        for (Element img : document.select("img")) {
            HarvestSource.ViewImage image = match(view, img);
            String label = label(img, image);
            if (image == null || counts[0] + slots.size() >= MAX_IMAGES) {
                missing(img, "Bild fehlt: " + label);
                counts[1]++;
                continue;
            }
            if (deferred) {
                slots.add(placeholder(img, label, slots.size(), scaled,
                        () -> loadImage(vector, load, image, label, scale)));
                continue;
            }
            Result result = loadImage(vector, load, image, label, scale);
            if (!result.ok()) {
                missing(img, result.missing());
                counts[1]++;
                continue;
            }
            place(img, IMAGE_BASE + originals.size(), result, label, scaled, originals, vectors);
            counts[0]++;
        }
        for (Element diagram : document.select("[class~=(?i)mermaid]")) {
            String source = diagram.wholeText().strip();
            if (source.isEmpty()) {
                continue;
            }
            if (deferred) {
                Element img = new Element(Tag.valueOf("img"), "");
                diagram.replaceWith(img);
                slots.add(placeholder(img, "Mermaid-Diagramm", slots.size(), scaled, () -> loadMermaid(mermaid, source)));
                continue;
            }
            Result result = loadMermaid(mermaid, source);
            if (!result.ok()) {
                Element marker = new Element(Tag.valueOf("p"), "");
                missing(marker, result.missing());
                diagram.before(marker);
                counts[1]++;
                continue;
            }
            Element img = new Element(Tag.valueOf("img"), "");
            diagram.replaceWith(img);
            place(img, IMAGE_BASE + originals.size(), result, "Mermaid-Diagramm", scaled, originals, vectors);
            counts[0]++;
        }
        for (Element element : document.body().getAllElements()) {
            clean(element);
        }
        return new Prepared("<html><body>" + document.body().html() + "</body></html>", scaled, originals, vectors,
                counts[0], counts[1], note(counts[0], counts[1], view.stored()), List.copyOf(slots), view.stored());
    }

    /** Die Bildzeile („1 Bild · live aus der Quelle …“). */
    private static String note(int images, int missing, boolean stored) {
        return Texts.count(images, "Bild", "Bilder")
                + (missing == 0 ? "" : " · " + missing + " nicht darstellbar, im Text markiert")
                + (stored ? " · aus dem Bestand" : " · live aus der Quelle")
                + (images == 0 ? "" : " · Klick auf ein Bild vergrößert es");
    }

    private static Prepared note(String text) {
        return new Prepared("", Map.of(), Map.of(), Map.of(), 0, 0, text);
    }

    /** Lädt ein Bild der Ansicht: zuerst die Vektorzeichnung, sonst die Bytes aus dem Core. */
    private static Result loadImage(Function<HarvestSource.ViewImage, Optional<VectorDiagram>> vector,
                                    Function<HarvestSource.ViewImage, byte[]> load, HarvestSource.ViewImage image,
                                    String label, double scale) {
        try {
            Optional<VectorDiagram> drawing = vectorOf(vector, image);
            if (drawing.isPresent()) {
                return vectorResult(drawing.get(), scale);
            }
            BufferedImage decoded = decode(load, image);
            return decoded == null ? Result.missing("Bild fehlt: " + label) : rasterResult(decoded);
        } catch (SignInPendingException e) {
            // Die Quelle verlangt einen Einmalcode; das Formular oben fragt ihn, danach lädt die Seite neu.
            return Result.missing(SIGN_IN_PENDING + label);
        }
    }

    /** Zeichnet ein Mermaid-Diagramm über den Core. */
    private static Result loadMermaid(Function<String, byte[]> mermaid, String source) {
        byte[] drawn;
        try {
            drawn = mermaid.apply(source);
        } catch (RuntimeException e) {
            drawn = null;
        }
        BufferedImage decoded = drawn == null ? null : read(drawn);
        return decoded == null ? Result.missing("Diagramm nicht darstellbar") : rasterResult(decoded);
    }

    /** Das Bild der Ansicht zu einem {@code <img>}: gleiche Adresse, roh oder gegen die Basis aufgelöst. */
    private static HarvestSource.ViewImage match(ReadingView view, Element img) {
        String raw = img.attr("src");
        String absolute = img.absUrl("src");
        for (HarvestSource.ViewImage image : view.images()) {
            if (image.src().equals(raw) || (!absolute.isEmpty() && image.src().equals(absolute))) {
                return image;
            }
        }
        for (HarvestSource.ViewImage image : view.images()) {
            String resolved = resolve(view.baseUri(), image.src());
            if (!absolute.isEmpty() && absolute.equals(resolved)) {
                return image;
            }
        }
        return null;
    }

    private static String resolve(String base, String src) {
        try {
            return base.isEmpty() ? src : URI.create(base).resolve(src).toString();
        } catch (IllegalArgumentException e) {
            return src;
        }
    }

    private static String label(Element img, HarvestSource.ViewImage image) {
        if (image != null && !image.title().isBlank()) {
            return image.title();
        }
        String alt = img.attr("alt");
        if (!alt.isBlank()) {
            return alt;
        }
        String src = img.attr("src");
        int slash = src.lastIndexOf('/');
        String name = slash < 0 ? src : src.substring(slash + 1);
        int query = name.indexOf('?');
        return query < 0 ? name : name.substring(0, query);
    }

    private static BufferedImage decode(Function<HarvestSource.ViewImage, byte[]> load, HarvestSource.ViewImage image) {
        byte[] content;
        try {
            content = load.apply(image);
        } catch (SignInPendingException e) {
            throw e;
        } catch (RuntimeException e) {
            return null;
        }
        return content == null ? null : read(content);
    }

    private static BufferedImage read(byte[] content) {
        try {
            return ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Die Vektorzeichnung zu einem Bild; eine fehlende Anmeldung geht weiter (Platzhalter), sonst leer. */
    private static Optional<VectorDiagram> vectorOf(Function<HarvestSource.ViewImage, Optional<VectorDiagram>> vector,
                                                    HarvestSource.ViewImage image) {
        try {
            Optional<VectorDiagram> drawing = vector.apply(image);
            return drawing == null ? Optional.empty() : drawing;
        } catch (SignInPendingException e) {
            // Die Anforderung ist schon offen: kein weiterer Abruf über das Bild (#571).
            throw e;
        } catch (RuntimeException | LinkageError e) {
            // Auch SignInRequiredException: Erst der Bildabruf (Workspace.viewImage) meldet die Anforderung an, damit
            // das Codefeld erscheint; die Sperre in Assets.fromSource verhindert dabei einen neuen Netzabruf.
            return Optional.empty();
        }
    }

    /**
     * Ein draw.io-Diagramm: im Text höchstens {@link #MAX_WIDTH} breit, gerastert in dieser Breite mal der
     * Bildschirmskalierung, damit es auch auf hochauflösenden Bildschirmen scharf ist.
     */
    private static Result vectorResult(VectorDiagram drawing, double scale) {
        int width = Math.max(1, (int) Math.round(Math.min(MAX_WIDTH, drawing.width())));
        int height = Math.max(1, (int) Math.round(drawing.height() * (width / drawing.width())));
        BufferedImage shown = drawing.raster(Math.max(1, (int) Math.round(width * Math.max(1.0, scale))));
        return new Result(shown, shown, width, height, drawing, null);
    }

    /** Ein geladenes Bild: im Text höchstens {@link #MAX_WIDTH} breit. */
    private static Result rasterResult(BufferedImage decoded) {
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        Image shown = decoded;
        if (width > MAX_WIDTH) {
            height = Math.max(1, (int) Math.round(height * (MAX_WIDTH / (double) width)));
            width = MAX_WIDTH;
            shown = decoded.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        }
        return new Result(decoded, new ImageIcon(shown).getImage(), width, height, null, null);
    }

    /** Skalierung des Bildschirms (Windows 150 % = 1,5); ohne Bildschirm 1. */
    static double screenScale() {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return 1.0;
            }
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX();
        } catch (RuntimeException e) {
            return 1.0;
        }
    }

    /** Setzt ein geladenes Bild unter {@code src} ein. */
    private static void place(Element img, String src, Result result, String label, Map<String, Image> scaled,
                              Map<String, BufferedImage> originals, Map<String, VectorDiagram> vectors) {
        originals.put(src, result.original());
        if (result.vector() != null) {
            vectors.put(src, result.vector());
        }
        scaled.put(src, result.shown());
        attributes(img, src, result.width(), result.height(), label);
    }

    /** Breite × Höhe eines Platzhalters, wenn das HTML keine Größe nennt. */
    static final int PLACEHOLDER_WIDTH = 240;
    static final int PLACEHOLDER_HEIGHT = 135;

    /**
     * Setzt einen Platzhalter in der Größe aus dem HTML ({@code width}/{@code height}, auch {@code data-width} und
     * {@code data-height} von Confluence; höchstens {@link #MAX_WIDTH} breit), sonst in Standardgröße; das Bild dazu
     * kommt später über den {@link Slot}.
     */
    private static Slot placeholder(Element img, String label, int index, Map<String, Image> scaled,
                                    java.util.concurrent.Callable<Result> loader) {
        String src = IMAGE_BASE + index;
        int width = size(img, "width", "data-width");
        int height = size(img, "height", "data-height");
        if (width <= 0 || height <= 0) {
            width = PLACEHOLDER_WIDTH;
            height = PLACEHOLDER_HEIGHT;
        } else if (width > MAX_WIDTH) {
            height = Math.max(1, (int) Math.round(height * (MAX_WIDTH / (double) width)));
            width = MAX_WIDTH;
        }
        scaled.put(src, placeholderImage(width, height));
        attributes(img, src, width, height, label);
        return new Slot(src, label, loader);
    }

    private static int size(Element img, String attribute, String fallback) {
        for (String name : new String[] {attribute, fallback}) {
            String value = img.attr(name).trim().replace("px", "");
            try {
                int size = Integer.parseInt(value);
                if (size > 0) {
                    return size;
                }
            } catch (NumberFormatException notANumber) {
                // nächstes Attribut
            }
        }
        return -1;
    }

    /** Eigene Adresse, Größe und Beschriftung eines Bildes im Text; alle anderen Attribute fallen weg. */
    private static void attributes(Element img, String src, int width, int height, String label) {
        img.clearAttributes();
        img.attr("src", src).attr("width", String.valueOf(width)).attr("height", String.valueOf(height))
                .attr("alt", label).attr("title", label);
    }

    /** Eine helle Fläche an der Stelle eines Bildes, das noch lädt. */
    static BufferedImage placeholderImage(int width, int height) {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(AppColors.of("app.ReadingPane.placeholderFill"));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(AppColors.of("app.ReadingPane.placeholderBorder"));
            g.drawRect(0, 0, image.getWidth() - 1, image.getHeight() - 1);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Der rote Platzhaltertext („[Bild fehlt: …]“) als Bild, an der Stelle eines Bildes, das sich nicht laden ließ. */
    static BufferedImage marker(String text, java.awt.Font font) {
        String shown = "[" + text + "]";
        java.awt.Font used = font == null ? Comic.regular(13.5f) : font;
        java.awt.FontMetrics metrics = new java.awt.Canvas().getFontMetrics(used);
        int width = Math.max(1, Math.min(MAX_WIDTH, metrics.stringWidth(shown) + 2));
        int height = Math.max(1, metrics.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(used);
            g.setColor(AppColors.of("app.ReadingPane.marker"));
            g.drawString(shown, 1, metrics.getAscent());
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Ersetzt ein Element durch einen sichtbaren Platzhalter wie in den Exporten. */
    private static void missing(Element element, String text) {
        Element marker = new Element(Tag.valueOf("font"), "").attr("color", "#c0392b").text("[" + text + "]");
        if ("img".equals(element.tagName())) {
            element.replaceWith(marker);
        } else {
            element.appendChild(marker);
        }
    }

    /** Behält nur harmlose Attribute; Verweise werden absolut und nur http(s) bleibt klickbar. */
    private static void clean(Element element) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        // Sprungziele (id) kennt die Swing-HTML-Ansicht nur als <a name>: als solches davor setzen, damit Links mit
        // Anker (#abschnitt) in der Leseansicht dorthin rollen.
        String id = element.id();
        if (!id.isBlank() && !"body".equals(tag)) {
            element.prependChild(new Element(Tag.valueOf("a"), "").attr("name", id));
        }
        Set<String> kept = KEPT_ATTRIBUTES.getOrDefault(tag, Set.of());
        for (Attribute attribute : List.copyOf(element.attributes().asList())) {
            String key = attribute.getKey().toLowerCase(Locale.ROOT);
            boolean ours = "font".equals(tag) && "color".equals(key);
            if (!kept.contains(key) && !ours) {
                element.removeAttr(attribute.getKey());
            }
        }
        if ("a".equals(tag)) {
            String href = element.absUrl("href");
            if (href.startsWith("http://") || href.startsWith("https://")) {
                element.attr("href", href);
            } else {
                element.removeAttr("href");
            }
        }
        if ("img".equals(tag) && !element.attr("src").startsWith(IMAGE_BASE)) {
            element.remove();
        }
    }

    private String imageAt(MouseEvent event) {
        if (!(pane.getDocument() instanceof HTMLDocument document)) {
            return null;
        }
        int position = pane.viewToModel2D(event.getPoint());
        for (int offset : new int[] {position, position - 1}) {
            if (offset < 0) {
                continue;
            }
            javax.swing.text.Element element = document.getCharacterElement(offset);
            AttributeSet attributes = element.getAttributes();
            if (attributes.getAttribute(javax.swing.text.StyleConstants.NameAttribute) == HTML.Tag.IMG) {
                Object src = attributes.getAttribute(HTML.Attribute.SRC);
                if (src != null) {
                    return src.toString();
                }
            }
        }
        return null;
    }

    /**
     * Bildergalerie im Bildbetrachter des EAC ({@link ImageViewer}): das Bild {@code index} eingepasst, Zoom,
     * Speichern und Blättern als Knöpfe im Bild. Er öffnet ohne Fensterrahmen ({@link ViewerPopup}): wächst von der
     * Stelle des Bilds im Text auf und schrumpft dorthin zurück; ein Klick außerhalb oder Esc schließt ihn.
     */
    private void enlarge(int index) {
        openPicture(index);
    }

    /**
     * Öffnet das Bild {@code index} der Seite (Reihenfolge im Text) im Betrachter, auch per Programm: Der Podcast
     * zeigt so das Bild eines Kapitels und schließt es mit {@link #closePicture()} oder {@link ViewerPopup#close()}.
     *
     * @return der offene Betrachter; leer, wenn es das Bild nicht gibt oder die Ansicht in keinem Fenster hängt
     */
    public Optional<ViewerPopup> openPicture(int index) {
        if (index < 0 || index >= originals.size() || javax.swing.SwingUtilities.getRootPane(this) == null) {
            return Optional.empty();
        }
        Map<String, String> labels = labels();
        List<String> sources = List.copyOf(originals.keySet());
        List<ImageViewer.Picture> pictures = sources.stream()
                .map(src -> new ImageViewer.Picture(originals.get(src), labels.get(src), vectors.get(src)))
                .toList();
        ImageViewer viewer = new ImageViewer(pictures, index, viewerLook.get());
        // Geschlossen wird zu dem Bild hin, das zuletzt gezeigt war, nicht zu dem, das geöffnet wurde.
        return Optional.of(ViewerPopup.open(this, viewer, () -> pictureBounds(sources.get(viewer.index()))));
    }

    /** Schließt den Betrachter dieser Ansicht, falls er offen ist. */
    public void closePicture() {
        ViewerPopup.current(this).ifPresent(ViewerPopup::close);
    }

    /** Wo das Bild {@code src} im Text gerade steht, in den Koordinaten dieser Ansicht; {@code null} ohne Stelle. */
    java.awt.Rectangle pictureBounds(String src) {
        if (!(pane.getDocument() instanceof HTMLDocument document)) {
            return null;
        }
        javax.swing.text.ElementIterator elements = new javax.swing.text.ElementIterator(document);
        for (javax.swing.text.Element element = elements.next(); element != null; element = elements.next()) {
            AttributeSet attributes = element.getAttributes();
            if (attributes.getAttribute(javax.swing.text.StyleConstants.NameAttribute) != HTML.Tag.IMG
                    || !src.equals(String.valueOf(attributes.getAttribute(HTML.Attribute.SRC)))) {
                continue;
            }
            try {
                java.awt.geom.Rectangle2D from = pane.getUI().modelToView2D(pane, element.getStartOffset(),
                        javax.swing.text.Position.Bias.Forward);
                java.awt.geom.Rectangle2D to = pane.getUI().modelToView2D(pane, element.getEndOffset(),
                        javax.swing.text.Position.Bias.Backward);
                if (from == null || to == null) {
                    return null;
                }
                java.awt.Rectangle bounds = from.createUnion(to).getBounds();
                // Nur der sichtbare Teil: Ein weggerolltes Bild schrumpft zum Rand des Textes, nicht ins Leere.
                bounds = bounds.intersection(pane.getVisibleRect());
                return bounds.isEmpty() ? null : javax.swing.SwingUtilities.convertRectangle(pane, bounds, this);
            } catch (javax.swing.text.BadLocationException e) {
                return null;
            }
        }
        return null;
    }

    /** Woher der Bildbetrachter seine Farben nimmt; gelesen bei jedem Öffnen, damit die Einstellung sofort gilt. */
    void onViewerLook(java.util.function.Supplier<ImageViewer.Look> look) {
        this.viewerLook = Objects.requireNonNull(look, "look");
    }

    /** Titel der Bilder (Alternativtext) je eigener Bildadresse, aus dem gezeigten HTML. */
    private Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Element img : Jsoup.parse(html).select("img[src^=" + IMAGE_BASE + "]")) {
            labels.put(img.attr("src"), img.attr("alt"));
        }
        return labels;
    }

    /** Öffnet eine http(s)-Adresse im Browser des Systems; andere Protokolle nie. */
    static void browse(URL url) {
        String protocol = url.getProtocol();
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            return;
        }
        Background.execute(() -> {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(url.toURI());
            }
        }, () -> { }, error -> { });
    }
}
