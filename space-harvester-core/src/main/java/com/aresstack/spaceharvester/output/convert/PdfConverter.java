package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionLimitExceededException;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.document.ExportGraphics;
import com.aresstack.spaceharvester.document.PdfExportWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PDF (#30): alle Seiten eines Exports in einer Datei {@code pages.pdf}, geschrieben mit dem PDF-Exporter des
 * Enterprise AI Client (Modul {@code :document-export}, PDFBox) aus demselben Kapitelmodell wie Word
 * ({@link ExportChapters}): der Space ist Kapitel 1, jede Seite ein Unterkapitel nach ihrer Tiefe im Baum. Vorn steht
 * ein sichtbares Inhaltsverzeichnis mit Einrückung nach Hierarchie, Seitenzahlen und Sprüngen; jedes Kapitel beginnt
 * auf einer neuen Seite, die Fußzeile zeigt „Seite / Gesamt“, Lesezeichen folgen dem Seitenbaum, Verweise zwischen
 * Seiten desselben Exports springen. Bilder, die nur als Anhang oder Adresse vorliegen, gelten nicht als eingebettet:
 * an ihrer Stelle steht „Abbildung nicht verfügbar“.
 *
 * <p>Schrift: ohne Konfiguration die Standardschriften Helvetica und Courier (WinAnsi, deckt westeuropäische Sprachen
 * ab); Zeichen außerhalb erscheinen als {@code ?}. Mit {@code space-harvester.export.pdf.font} wird eine
 * TrueType-Schrift eingebettet, die dann alle ihre Zeichen darstellt. Die Datei hat feste Kennungen und keine
 * Zeitstempel, damit gleicher Input dieselben Bytes ergibt.
 */
public final class PdfConverter implements ExportConverter {

    public static final String FORMAT = "pdf";
    public static final String VERSION = "pdf/6";

    /**
     * Höchste Seitenzahl einer PDF-Datei (#90): Seitenbaum, Gliederung und Verweise wachsen im Speicher, bevor
     * {@code max-converted-bytes} greift; darüber scheitert nur dieser Export ({@code CONVERSION_TOO_LARGE}).
     */
    static final int MAX_PAGES = PdfExportWriter.MAX_PAGES;

    private final Path font;
    private final int maxPages;

    /** Mit den Standardschriften. */
    public PdfConverter() {
        this(null);
    }

    /** @param font TrueType-Schrift zum Einbetten oder {@code null} für die Standardschriften */
    public PdfConverter(Path font) {
        this(font, MAX_PAGES);
    }

    PdfConverter(Path font, int maxPages) {
        this.font = font;
        this.maxPages = maxPages;
    }

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
        return "application/pdf";
    }

    @Override
    public String fileName() {
        return "pages.pdf";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) throws IOException {
        StorageParser parser = new StorageParser(PageLinks.of(input.headers(), input.spaceKey()));
        String title = ExportChapters.title(input);
        Set<String> anchors = new HashSet<>();
        for (PageHeader header : input.headers()) {
            anchors.add(PageLinks.anchor(header.key().pageId()));
        }
        MessageDigest identity = sha256();
        identity.update((input.sourceId() + "\n" + input.spaceKey() + "\n").getBytes(StandardCharsets.UTF_8));
        ExportGraphics graphics = ExportGraphics.rendering();
        PdfExportWriter writer = new PdfExportWriter(graphics, font, maxPages, "Space Harvester");
        try (PdfExportWriter.Chapters chapters = writer.open(title, input.headers().size() + 1, anchors)) {
            chapters.chapter(title, null, ExportChapters.rootText(input, VERSION), List.of(), 1);
            try {
                input.forEachPage(page -> {
                    identity.update((page.pageId() + "\t" + page.snapshot().payload().rawSha256() + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    try {
                        ExportChapters.Figures figures = ExportChapters.figures(page, parser, input);
                        figures.notices().forEach(input::report);
                        chapters.chapter(ExportChapters.title(page), PageLinks.anchor(page.pageId()),
                                figures.markdown(), figures.images(), ExportChapters.depth(page));
                        ExportChapters.mermaidNotices(page, graphics.takeMermaidFailures()).forEach(input::report);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            // Feste Dateikennung statt einer aus der Uhrzeit: gleicher Input, gleiche Bytes.
            chapters.finish(out, identity.digest());
        } catch (PdfExportWriter.PageLimitExceededException e) {
            throw ConversionLimitExceededException.pages(fileName(), e.maxPages());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
