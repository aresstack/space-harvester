package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.reading.ReadingView;
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
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Toolkit;
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
    /** Adressraum der vorab geladenen Bilder; {@code .invalid} wird nie aufgelöst. */
    private static final String IMAGE_BASE = "http://reading.invalid/image/";
    private static final Set<String> REMOVED = Set.of("script", "style", "link", "meta", "iframe", "frame", "object",
            "embed", "form", "input", "button", "select", "textarea", "noscript", "svg", "video", "audio", "source",
            "canvas", "template", "base");
    private static final Map<String, Set<String>> KEPT_ATTRIBUTES = Map.of(
            "a", Set.of("href", "title"),
            "img", Set.of("src", "alt", "title", "width", "height"),
            "td", Set.of("colspan", "rowspan"),
            "th", Set.of("colspan", "rowspan"),
            "ol", Set.of("start"));

    private final JEditorPane pane = new JEditorPane();
    private final JLabel hint = Comic.muted(" ");
    private Map<String, BufferedImage> originals = Map.of();

    ReadingPane() {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setFont(Comic.regular(13.5f));
        pane.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                browse(event.getURL());
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
                boolean image = originals.containsKey(imageAt(event));
                pane.setCursor(image ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                pane.setToolTipText(image ? "Klicken zum Vergrößern" : null);
            }
        });
        add(hint, BorderLayout.NORTH);
        add(Comic.scroll(pane), BorderLayout.CENTER);
        clear(" ");
    }

    /** Leert die Ansicht und zeigt {@code text} als Hinweis. */
    void clear(String text) {
        originals = Map.of();
        pane.setText("");
        hint.setText(text);
        hint.setForeground(Comic.TEXT);
    }

    /** Für Tests: der angezeigte Hinweis und das HTML, aus dem die Ansicht gebaut ist. */
    String hint() {
        return hint.getText();
    }

    /** Zeigt eine vorbereitete Ansicht; auf dem EDT. */
    void show(Prepared prepared) {
        originals = prepared.originals();
        hint.setText(prepared.note());
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
            BufferedImage decoded = decode(load, image);
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
     * Bildergalerie: zeigt das Bild {@code index} in Originalgröße (höchstens bildschirmgroß, scrollbar); ◀/▶ und die
     * Pfeiltasten blättern durch alle Bilder der Seite.
     */
    private void enlarge(int index) {
        List<BufferedImage> images = List.copyOf(originals.values());
        if (index < 0 || images.isEmpty()) {
            return;
        }
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(400);
        int height = images.stream().mapToInt(BufferedImage::getHeight).max().orElse(300);
        JLabel label = new JLabel();
        label.setHorizontalAlignment(JLabel.CENTER);
        JLabel position = Comic.muted(" ");
        int[] current = {index};
        Runnable show = () -> {
            label.setIcon(new ImageIcon(images.get(current[0])));
            position.setText("Bild " + (current[0] + 1) + " von " + images.size());
        };
        javax.swing.JComponent scroll = Comic.scroll(label);
        scroll.setPreferredSize(new Dimension(Math.min(width + 30, screen.width - 160),
                Math.min(height + 30, screen.height - 240)));
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(scroll, BorderLayout.CENTER);
        if (images.size() > 1) {
            ComposerButton previous = Comic.button("◀", "Vorheriges Bild");
            ComposerButton next = Comic.button("▶", "Nächstes Bild");
            Runnable back = () -> {
                current[0] = (current[0] + images.size() - 1) % images.size();
                show.run();
            };
            Runnable forward = () -> {
                current[0] = (current[0] + 1) % images.size();
                show.run();
            };
            previous.addActionListener(e -> back.run());
            next.addActionListener(e -> forward.run());
            body.registerKeyboardAction(e -> back.run(), javax.swing.KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_LEFT, 0), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
            body.registerKeyboardAction(e -> forward.run(), javax.swing.KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_RIGHT, 0), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
            body.add(Comic.row(previous, position, next), BorderLayout.SOUTH);
        } else {
            body.add(position, BorderLayout.SOUTH);
        }
        show.run();
        Dialogs.view(this, "Bilder der Seite", body);
    }

    private static void browse(URL url) {
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
