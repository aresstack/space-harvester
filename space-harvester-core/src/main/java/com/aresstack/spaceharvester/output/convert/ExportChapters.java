package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Kind;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.document.ExportGraphics;
import com.aresstack.spaceharvester.document.ExportImage;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Block;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Note;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Das gemeinsame Kapitelmodell von Word und PDF: der Space ist Kapitel 1, jede Seite ein Unterkapitel nach ihrer
 * Tiefe im Baum ({@code depth + 2}), linear in Baumreihenfolge. Der Inhalt eines Kapitels ist dasselbe Markdown wie
 * bei {@link MarkdownConverter}, davor Seiten-ID, Version und Link in Confluence. Jede Seite trägt ihren Anker
 * {@link PageLinks#anchor(String)}; Verweise auf Seiten desselben Exports springen dorthin.
 *
 * <p>Bilder kommen nur aus dem Datenbestand ({@link ConversionInput#assets()}): ein abgelegtes Bild wird eingebettet,
 * jedes andere erscheint als „Abbildung nicht verfügbar“ mit seinem Grund. Kein Converter fragt die Quelle an.
 * Mermaid-Diagramme zeichnet der Writer aus dem Diagrammtext der Seite, draw.io-Diagramme der Diagramm-Dienst des
 * Core aus ihren abgelegten Quelldaten.</p>
 */
final class ExportChapters {

    private ExportChapters() {
    }

    /**
     * Titel des Wurzelkapitels und des Dokuments: der Name des Space wie in der Quelle (so auch der Enterprise AI
     * Client); ohne Namen {@code Space <Schlüssel> (<Quelle>)}.
     */
    static String title(ConversionInput input) {
        String name = input.spaceName();
        return name == null || name.isBlank() || name.equals(input.spaceKey())
                ? "Space " + input.spaceKey() + " (" + input.sourceId() + ")" : name;
    }

    /** Text des Wurzelkapitels: Seitenzahl, Quelle und Konverterversion. */
    static String rootText(ConversionInput input, String version) {
        return "*" + MarkdownConverter.escape(input.headers().size() + " Seiten des Space " + input.spaceKey()
                + " der Quelle " + input.sourceId()
                + ", erzeugt mit " + version + " aus dem Confluence-Storage-Format.") + "*";
    }

    /** Tiefe des Kapitels einer Seite: Wurzelseiten 2, deren Kinder 3 … */
    static int depth(ConvertiblePage page) {
        return page.depth() + 2;
    }

    static String title(ConvertiblePage page) {
        String title = page.header().metadata().title();
        return title == null || title.isBlank() ? "Seite " + page.pageId() : title;
    }

    /** Ein externes Bild ohne Bilddaten im Markdown aus {@link MarkdownConverter}: Hinweis mit Link auf die Adresse. */
    private static final Pattern EXTERNAL_IMAGE = Pattern.compile(
            "\\*\\\\\\[Abbildung nicht verfügbar: ((?:[^\\\\\\]]|\\\\.)*)\\\\\\]\\* \\(\\[Adresse\\]\\((<[^>]*>|[^)\\s]*)\\)\\)");

    /** Inhalt eines Kapitels: das Markdown der Seite und die Abbildungen, auf die es verweist. */
    record Figures(String markdown, List<ExportImage> images, List<ConversionNotice> notices) {
    }

    /** Verweis auf ein draw.io-Diagramm im Markdown ({@link StorageParser}): {@code drawio:[<Seiten-ID>/]<Name>}. */
    private static final Pattern DRAWIO = Pattern.compile("\\]\\((?:<)?drawio:(?:(\\d+)/)?([^)>]*)(?:>)?\\)");

    /**
     * Markdown und Abbildungen einer Seite. Die Bilder kommen nur aus dem Datenbestand, adressiert wie im Markdown:
     * Anhänge als {@code attachment:<Datei>}, Bilder der Lese-Ansicht mit ihrer Adresse, draw.io-Diagramme als
     * {@code drawio:[<Seiten-ID>/]<Name>}, gezeichnet aus ihren abgelegten Quelldaten ({@link Diagrams#drawio}), sonst
     * die abgelegte Vorschau des Plugins. Mermaid fehlt hier; der Writer zeichnet es aus dem Diagrammtext. Ein externes
     * Bild, das der Datenbestand kennt, wird ein Bildverweis, damit der Writer es einbettet oder als fehlend meldet; die
     * übrigen bleiben Hinweis mit Link.
     */
    static Figures figures(ConvertiblePage page, StorageParser parser, ExportAssets assets) {
        String markdown = markdown(page, parser);
        List<ExportImage> images = new ArrayList<>();
        Map<String, String> codes = new HashMap<>();
        for (Entry entry : assets.forPage(page.pageId())) {
            String src = src(entry);
            if (src == null) {
                continue;
            }
            String title = entry.title().isBlank() ? src : entry.title();
            Optional<byte[]> bytes = entry.stored() ? assets.content(entry) : Optional.empty();
            images.add(bytes.isPresent() ? ExportImage.loaded(src, title, bytes.get())
                    : ExportImage.failed(src, title, reason(entry, entry.stored())));
            if (bytes.isEmpty()) {
                codes.put(src, code(entry));
            }
        }
        for (Entry entry : assets.forPage(page.pageId())) {
            // Mermaid, das beim Beschaffen gezeichnet wurde: der Writer nimmt dieses Bild statt selbst zu zeichnen
            if (entry.reference().kind() == Kind.MERMAID && entry.reference().source() != null && drawn(entry)) {
                assets.rendition(entry).ifPresent(png -> images.add(ExportImage.loaded(
                        ExportGraphics.mermaidAddress(entry.reference().source()), "Mermaid-Diagramm", png)));
            }
        }
        Matcher drawio = DRAWIO.matcher(markdown);
        Set<String> drawn = new HashSet<>();
        while (drawio.find()) {
            String pageId = drawio.group(1);
            String name = drawio.group(2);
            String src = "drawio:" + (pageId == null ? "" : pageId + "/") + name;
            if (drawn.add(src)) {
                images.add(drawio(src, pageId == null ? page.pageId() : pageId, name, assets, codes));
            }
        }
        String content = images.isEmpty() ? markdown : withKnownExternalImages(markdown, images);
        return new Figures(content, images, notices(page.pageId(), content, images, codes));
    }

    /** Hinweise für Mermaid-Diagramme einer Seite, die der Writer nicht zeichnen konnte (bleiben Quelltext). */
    /** Ob die Beschaffung das Diagramm schon gezeichnet abgelegt hat. */
    private static boolean drawn(Entry entry) {
        return entry.rendition() != null && entry.rendition().drawn();
    }

    static List<ConversionNotice> mermaidNotices(ConvertiblePage page, List<String> failures) {
        return failures.isEmpty() ? List.of() : List.of(new ConversionNotice(page.pageId(),
                ConversionNotice.Kind.MISSING_DIAGRAM, "mermaid", "render-failed"));
    }

    /** Markdown-Bildverweis {@code ![alt](ziel)}; Ziel mit oder ohne spitze Klammern. */
    private static final Pattern IMAGE = Pattern.compile("!\\[(?:[^\\]\\\\]|\\\\.)*\\]\\((<[^>]*>|[^)\\s]*)\\)");

    /**
     * Die Hinweise einer Seite fürs Ergebnis der Konvertierung: jede Abbildung, die das Dokument als „nicht verfügbar“
     * zeigt, einmal, mit dem Status aus dem Datenbestand. Verweise ohne Zugangsdaten: Anhangname, Diagrammname,
     * Bildadresse ohne Query.
     */
    private static List<ConversionNotice> notices(String pageId, String markdown, List<ExportImage> images,
                                                  Map<String, String> codes) {
        Set<ConversionNotice> notices = new LinkedHashSet<>();
        Matcher image = IMAGE.matcher(markdown);
        while (image.find()) {
            String destination = image.group(1);
            String src = destination.startsWith("<") ? destination.substring(1, destination.length() - 1)
                    .replace("%3C", "<").replace("%3E", ">") : destination;
            if (src.startsWith("data:") || images.stream().anyMatch(i -> i.src().equals(src) && i.ok())) {
                continue;
            }
            if (src.startsWith("drawio:")) {
                notices.add(new ConversionNotice(pageId, ConversionNotice.Kind.MISSING_DIAGRAM,
                        src.substring(src.lastIndexOf('/') + 1).replace("drawio:", ""),
                        codes.getOrDefault(src, "not-collected")));
            } else {
                notices.add(new ConversionNotice(pageId, ConversionNotice.Kind.MISSING_IMAGE, reference(src),
                        codes.getOrDefault(src, "not-collected")));
            }
        }
        Matcher external = EXTERNAL_IMAGE.matcher(markdown);
        while (external.find()) {
            String destination = external.group(2);
            String src = destination.startsWith("<") ? destination.substring(1, destination.length() - 1) : destination;
            notices.add(new ConversionNotice(pageId, ConversionNotice.Kind.MISSING_IMAGE, reference(src),
                    codes.getOrDefault(src, "not-collected")));
        }
        return List.copyOf(notices);
    }

    /** Bildverweis fürs Ergebnis: Anhangname oder Adresse ohne Query und Fragment (keine Zugangsdaten). */
    private static String reference(String src) {
        if (src.startsWith("attachment:")) {
            return src.substring("attachment:".length());
        }
        int cut = src.length();
        for (char c : new char[] {'?', '#'}) {
            int at = src.indexOf(c);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        String plain = src.substring(0, cut);
        int scheme = plain.indexOf("://");
        int at = plain.indexOf('@');
        // Benutzerangaben in der Adresse (user:pass@host) gehören nicht ins Ergebnis
        return scheme >= 0 && at > scheme ? plain.substring(0, scheme + 3) + plain.substring(at + 1) : plain;
    }

    /** Status aus {@code assets.json} klein geschrieben, wie {@link ConversionNotice} ihn erwartet. */
    private static String code(Entry entry) {
        return switch (entry.status()) {
            case MISSING -> "missing";
            case FORBIDDEN -> "forbidden";
            case TOO_LARGE -> "too-large";
            case UNAVAILABLE -> "unavailable";
            case STORED -> "not-collected";
        };
    }

    /** Ein draw.io-Diagramm aus dem Datenbestand: gezeichnet, sonst die Vorschau, sonst mit Grund als fehlend. */
    private static ExportImage drawio(String src, String pageId, String name, ExportAssets assets,
                                      Map<String, String> codes) {
        String title = "Diagramm (draw.io) „" + name + "“";
        List<Entry> entries = assets.forPage(pageId);
        Entry source = entries.stream().filter(e -> e.reference().kind() == Kind.ATTACHMENT
                        && e.reference().filename() != null && Diagrams.isDrawio(e.mediaType(), e.reference().filename())
                        && Diagrams.drawioName(e.reference().filename()).equals(name))
                .findFirst().orElse(null);
        String problem;
        String code;
        if (source == null) {
            problem = "Quelldaten nicht im Datenbestand";
            code = "not-collected";
        } else if (source.rendition() != null && source.rendition().problem() != null) {
            // Beim Beschaffen schon nicht darstellbar: nicht erneut zeichnen
            problem = "draw.io nicht darstellbar: " + source.rendition().problem();
            code = "render-failed";
        } else {
            Optional<byte[]> drawn = drawn(source) ? assets.rendition(source) : Optional.empty();
            if (drawn.isPresent()) {
                return ExportImage.loaded(src, title, drawn.get());
            }
            Optional<byte[]> mxfile = source.stored() ? assets.content(source) : Optional.empty();
            if (mxfile.isPresent()) {
                Diagrams.Rendering rendering = Diagrams.drawio(mxfile.get());
                if (rendering.drawn()) {
                    return ExportImage.loaded(src, title, rendering.content());
                }
                problem = rendering.problem();
                code = "render-failed";
            } else {
                problem = reason(source, source.stored());
                code = code(source);
            }
        }
        for (Entry preview : entries) {
            if (preview.reference().kind() == Kind.ATTACHMENT && preview.reference().filename() != null
                    && Diagrams.isDrawioPreview(preview.reference().filename(), name) && preview.stored()) {
                Optional<byte[]> png = assets.content(preview);
                if (png.isPresent()) {
                    return ExportImage.loaded(src, title, png.get());
                }
            }
        }
        codes.put(src, code);
        return ExportImage.failed(src, title, problem);
    }

    private static String src(Entry entry) {
        return switch (entry.reference().kind()) {
            case ATTACHMENT -> entry.reference().filename() == null ? null : "attachment:" + entry.reference().filename();
            case VIEW_IMAGE -> entry.reference().src();
            case MERMAID -> null;
        };
    }

    private static String reason(Entry entry, boolean storedButGone) {
        if (storedButGone) {
            return "im Datenbestand nicht mehr vorhanden";
        }
        String status = switch (entry.status()) {
            case MISSING -> "in der Quelle nicht vorhanden";
            case FORBIDDEN -> "keine Berechtigung";
            case TOO_LARGE -> "zu groß";
            case UNAVAILABLE -> "nicht beschafft";
            case STORED -> "nicht beschafft";
        };
        return entry.reason() == null || entry.reason().isBlank() ? status : status + " (" + entry.reason() + ")";
    }

    private static String withKnownExternalImages(String markdown, List<ExportImage> images) {
        Matcher matcher = EXTERNAL_IMAGE.matcher(markdown);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String destination = matcher.group(2);
            String src = destination.startsWith("<") ? destination.substring(1, destination.length() - 1)
                    .replace("%3C", "<").replace("%3E", ">") : destination;
            boolean known = images.stream().anyMatch(image -> image.src().equals(src));
            matcher.appendReplacement(out, Matcher.quoteReplacement(known
                    ? "![" + matcher.group(1) + "](" + destination + ")" : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Seiten-ID, Version und Link in Confluence, darunter der Inhalt; Überschriften ab Ebene 1 (verschiebt der Writer). */
    static String markdown(ConvertiblePage page, StorageParser parser) {
        PageMetadata metadata = page.header().metadata();
        StringBuilder facts = new StringBuilder("Seite " + MarkdownConverter.escape(page.pageId()));
        if (metadata.version() != null) {
            facts.append(" · Version ").append(MarkdownConverter.escape(metadata.version()));
        }
        if (metadata.url() != null) {
            facts.append(" · [In Confluence öffnen](").append(MarkdownConverter.destination(metadata.url().toString()))
                    .append(')');
        }
        List<Block> blocks = PageBody.storage(page.snapshot().payload()).map(parser::parse)
                .orElse(List.of(new Note("Die Payload enthält keinen Inhalt im Storage-Format")));
        return "*" + facts + "*\n\n" + MarkdownConverter.blocks(blocks, 0);
    }
}
