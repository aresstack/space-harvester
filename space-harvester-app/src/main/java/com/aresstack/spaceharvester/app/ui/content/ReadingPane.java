package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Texts;
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
import java.util.Set;
import java.util.function.Function;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;

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
    /** Oberer Innenabstand der Ansicht ohne Wolkenbanner. */
    private static final int PADDING_TOP = 10;
    /** Die Ansicht mit den Inhaltswolken der Dokumentnavigation darüber, wie unter „Text“. */
    private final NavigableDocumentPage page = new NavigableDocumentPage(Comic.scroll(pane), pane, PADDING_TOP);
    /** Drehender Ring über dem Text, solange die Leseansicht lädt; statt eines Hinweistexts oben. */
    private final BusyOverlay overlay = new BusyOverlay(page);
    private String details = "";
    private Map<String, BufferedImage> originals = Map.of();
    /** HTML der gezeigten Ansicht, für die Bildtitel im Betrachter. */
    private String html = "";
    /** Was ein angeklickter Link auslöst; ohne Abnehmer öffnet er im Browser. */
    private java.util.function.Consumer<URL> links = ReadingPane::browse;

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
                pane.setCursor(image ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                pane.setToolTipText(image ? "Klicken zum Vergrößern" : null);
            }
        });
        overlay.setHighlight(Comic.BLUE);
        overlay.setVeilColor(Comic.SURFACE);
        add(hint, BorderLayout.NORTH);
        add(overlay, BorderLayout.CENTER);
        clear(" ");
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
    void setNavigation(Navigation navigation) {
        page.setNavigation(navigation);
    }

    /** Für Tests: die Ansicht mit dem Wolkenbanner. */
    NavigableDocumentPage navigablePage() {
        return page;
    }

    /** Rollt zum Anker {@code fragment} der gezeigten Ansicht; ohne passenden Anker nichts. */
    void scrollToAnchor(String fragment) {
        if (fragment != null && !fragment.isBlank()) {
            pane.scrollToReference(fragment);
        }
    }

    /** Leert die Ansicht und zeigt {@code text} als Hinweis. */
    void clear(String text) {
        overlay.setBusy(false);
        originals = Map.of();
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
     * Seitendetails (ⓘ); über dem Text nur, wenn etwas nicht darstellbar ist oder es keinen Text gibt.
     */
    void show(Prepared prepared) {
        overlay.setBusy(false);
        originals = prepared.originals();
        html = prepared.html();
        boolean visible = prepared.html().isEmpty() || prepared.missing() > 0;
        details = prepared.html().isEmpty() ? "" : prepared.note();
        hint.setText(visible ? prepared.note() : " ");
        hint.setForeground(prepared.missing() > 0 ? Comic.ORANGE : Comic.TEXT);
        if (prepared.html().isEmpty()) {
            pane.setText("");
            return;
        }
        HTMLEditorKit kit = new HTMLEditorKit();
        kit.getStyleSheet().addRule("body { font-family: " + Comic.regular(13.5f).getFamily() + "; font-size: 13pt; }");
        kit.getStyleSheet().addRule("table { border-collapse: collapse; } td, th { border: 1px solid #c8c8c8; "
                + "padding: 3px; }");
        pane.setEditorKit(kit);
        HTMLDocument document = (HTMLDocument) kit.createDefaultDocument();
        Hashtable<URL, Image> cache = new Hashtable<>();
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
    }

    /** Vorbereitete Ansicht: HTML mit eigenen Bildadressen, die Bilder dazu und was fehlt. */
    record Prepared(String html, Map<String, Image> scaled, Map<String, BufferedImage> originals, int images,
                    int missing, String note) {
    }

    /**
     * Baut die Ansicht außerhalb des EDT: säubert das HTML, lädt die Bilder über {@code load} und die Mermaid-Grafiken
     * über {@code mermaid} (beides Bytes aus dem Core, {@code null} wenn es keine gibt). Ein Bild oder Diagramm, das sich nicht laden oder lesen lässt, wird markiert.
     */
    static Prepared prepare(ReadingView view, Function<HarvestSource.ViewImage, byte[]> load,
                            Function<String, byte[]> mermaid) {
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
        int[] counts = new int[2];
        for (Element img : document.select("img")) {
            HarvestSource.ViewImage image = match(view, img);
            String label = label(img, image);
            if (image == null || counts[0] >= MAX_IMAGES) {
                missing(img, "Bild fehlt: " + label);
                counts[1]++;
                continue;
            }
            BufferedImage decoded;
            try {
                decoded = decode(load, image);
            } catch (SignInPendingException e) {
                // Die Quelle verlangt einen Einmalcode; das Formular oben fragt ihn, danach lädt die Seite neu.
                missing(img, SIGN_IN_PENDING + label);
                counts[1]++;
                continue;
            }
            if (decoded == null) {
                missing(img, "Bild fehlt: " + label);
                counts[1]++;
                continue;
            }
            place(img, decoded, label, scaled, originals);
            counts[0]++;
        }
        for (Element diagram : document.select("[class~=(?i)mermaid]")) {
            String source = diagram.wholeText().strip();
            if (source.isEmpty()) {
                continue;
            }
            byte[] drawn;
            try {
                drawn = mermaid.apply(source);
            } catch (RuntimeException e) {
                drawn = null;
            }
            BufferedImage decoded = drawn == null ? null : read(drawn);
            if (decoded == null) {
                Element marker = new Element(Tag.valueOf("p"), "");
                missing(marker, "Diagramm nicht darstellbar");
                diagram.before(marker);
                counts[1]++;
                continue;
            }
            Element img = new Element(Tag.valueOf("img"), "");
            diagram.replaceWith(img);
            place(img, decoded, "Mermaid-Diagramm", scaled, originals);
            counts[0]++;
        }
        for (Element element : document.body().getAllElements()) {
            clean(element);
        }
        String note = Texts.count(counts[0], "Bild", "Bilder")
                + (counts[1] == 0 ? "" : " · " + counts[1] + " nicht darstellbar, im Text markiert")
                + (view.stored() ? " · aus dem Bestand" : " · live aus der Quelle")
                + (counts[0] == 0 ? "" : " · Klick auf ein Bild vergrößert es");
        return new Prepared("<html><body>" + document.body().html() + "</body></html>", scaled, originals, counts[0],
                counts[1], note);
    }

    private static Prepared note(String text) {
        return new Prepared("", Map.of(), Map.of(), 0, 0, text);
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

    /** Setzt ein geladenes Bild ein: eigene Adresse, höchstens {@link #MAX_WIDTH} breit. */
    private static void place(Element img, BufferedImage decoded, String label, Map<String, Image> scaled,
                              Map<String, BufferedImage> originals) {
        String src = IMAGE_BASE + originals.size();
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        Image shown = decoded;
        if (width > MAX_WIDTH) {
            height = Math.max(1, (int) Math.round(height * (MAX_WIDTH / (double) width)));
            width = MAX_WIDTH;
            shown = decoded.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        }
        originals.put(src, decoded);
        scaled.put(src, new ImageIcon(shown).getImage());
        img.clearAttributes();
        img.attr("src", src).attr("width", String.valueOf(width)).attr("height", String.valueOf(height))
                .attr("alt", label).attr("title", label);
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
     * Speichern und Blättern als Knöpfe im Bild. Das Fenster merkt sich die zuletzt gezogene Größe, begrenzt auf den
     * aktuellen Bildschirm.
     */
    private void enlarge(int index) {
        if (index < 0 || originals.isEmpty()) {
            return;
        }
        Map<String, String> labels = labels();
        List<ImageViewer.Picture> pictures = originals.entrySet().stream()
                .map(entry -> new ImageViewer.Picture(entry.getValue(), labels.get(entry.getKey())))
                .toList();
        Dialogs.viewSized(this, "Bilder der Seite", new ImageViewer(pictures, index), "reading.images", 0.8);
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
