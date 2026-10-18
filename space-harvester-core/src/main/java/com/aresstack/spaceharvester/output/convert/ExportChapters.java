package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Block;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Note;
import java.util.List;

/**
 * Das gemeinsame Kapitelmodell von Word und PDF: der Space ist Kapitel 1, jede Seite ein Unterkapitel nach ihrer
 * Tiefe im Baum ({@code depth + 2}), linear in Baumreihenfolge. Der Inhalt eines Kapitels ist dasselbe Markdown wie
 * bei {@link MarkdownConverter}, davor Seiten-ID, Version und Link in Confluence. Jede Seite trägt ihren Anker
 * {@link PageLinks#anchor(String)}; Verweise auf Seiten desselben Exports springen dorthin.
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
