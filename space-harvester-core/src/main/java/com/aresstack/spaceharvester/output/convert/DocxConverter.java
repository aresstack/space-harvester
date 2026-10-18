package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.document.DocxExportWriter;
import com.aresstack.spaceharvester.document.ExportGraphics;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Word (#30): alle Seiten eines Exports in einer Datei {@code pages.docx}, geschrieben mit dem Word-Exporter des
 * Enterprise AI Client (Modul {@code :document-export}, Apache POI). Das Kapitelmodell: der Space ist Kapitel 1
 * (Überschrift 1), jede Seite ein Unterkapitel nach ihrer Tiefe im Baum (Wurzelseiten Überschrift 2, deren Kinder
 * Überschrift 3 …), linear in Baumreihenfolge. Davor steht ein echtes Inhaltsverzeichnis-Feld über alle Ebenen, das
 * Word beim Öffnen aktualisiert; es gibt damit die Hierarchie von Space und Seiten wieder.
 *
 * <p>Der Inhalt kommt aus dem Storage-Format, über dasselbe Markdown wie bei {@link MarkdownConverter}: Überschriften
 * der Seite liegen unter ihrer Kapitelüberschrift, Listen werden Word-Listen, Tabellen Word-Tabellen, Links
 * Hyperlinks, Code ein schattierter Block. Jede Seite trägt eine Textmarke; Verweise auf Seiten desselben Exports
 * springen dorthin. Bilder, die nur als Anhang oder Adresse vorliegen, gelten nicht als eingebettet: an ihrer Stelle
 * steht „Abbildung nicht verfügbar“. Siehe {@code docs/CONVERTERS.md}.
 */
public final class DocxConverter implements ExportConverter {

    public static final String FORMAT = "docx";
    public static final String VERSION = "docx/6";

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String mediaType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override
    public String fileName() {
        return "pages.docx";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) throws IOException {
        StorageParser parser = new StorageParser(PageLinks.of(input.headers(), input.spaceKey()));
        String title = ExportChapters.title(input);
        ExportGraphics graphics = ExportGraphics.rendering();
        DocxExportWriter writer = new DocxExportWriter(graphics, "Space Harvester");
        try (DocxExportWriter.Chapters chapters = writer.open(title, !input.headers().isEmpty())) {
            chapters.chapter(title, null, ExportChapters.rootText(input, VERSION), List.of(), 1);
            input.forEachPage(page -> {
                ExportChapters.Figures figures = ExportChapters.figures(page, parser, input);
                figures.notices().forEach(input::report);
                chapters.chapter(ExportChapters.title(page), PageLinks.anchor(page.pageId()),
                        figures.markdown(), figures.images(), ExportChapters.depth(page));
                ExportChapters.mermaidNotices(page, graphics.takeMermaidFailures()).forEach(input::report);
            });
            chapters.finish(out);
        }
    }
}
