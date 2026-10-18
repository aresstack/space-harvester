package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.document.ExportImage;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Block;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Note;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * Mermaid-Diagramme zeichnet der Writer aus dem Diagrammtext der Seite.</p>
 */
final class ExportChapters {

    private ExportChapters() {
    }

    /** Titel des Wurzelkapitels und des Dokuments. */
    static String title(ConversionInput input) {
        return "Space " + input.spaceKey() + " (" + input.sourceId() + ")";
    }

    /** Text des Wurzelkapitels: Seitenzahl, Quelle und Konverterversion. */
    static String rootText(ConversionInput input, String version) {
        return "*" + MarkdownConverter.escape(input.headers().size() + " Seiten der Quelle " + input.sourceId()
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

    /**
     * Die Bilder einer Seite aus dem Datenbestand, adressiert wie im Markdown der Seite: Anhänge als
     * {@code attachment:<Datei>}, Bilder der Lese-Ansicht mit ihrer Adresse. Mermaid fehlt hier; der Writer zeichnet es
     * aus dem Diagrammtext.
     */
    static List<ExportImage> images(ConvertiblePage page, ExportAssets assets) {
        List<ExportImage> images = new ArrayList<>();
        for (Entry entry : assets.forPage(page.pageId())) {
            String src = src(entry);
            if (src == null) {
                continue;
            }
            String title = entry.title().isBlank() ? src : entry.title();
            Optional<byte[]> bytes = entry.stored() ? assets.content(entry) : Optional.empty();
            images.add(bytes.isPresent() ? ExportImage.loaded(src, title, bytes.get())
                    : ExportImage.failed(src, title, reason(entry, entry.stored())));
        }
        return images;
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

    /**
     * Seiten-ID, Version und Link in Confluence, darunter der Inhalt; Überschriften ab Ebene 1 (verschiebt der Writer).
     * Ein externes Bild, das der Datenbestand kennt ({@code images}), wird ein Bildverweis, damit der Writer es
     * einbettet oder als fehlend meldet; die übrigen bleiben Hinweis mit Link.
     */
    static String markdown(ConvertiblePage page, StorageParser parser, List<ExportImage> images) {
        String markdown = markdown(page, parser);
        if (images.isEmpty()) {
            return markdown;
        }
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
