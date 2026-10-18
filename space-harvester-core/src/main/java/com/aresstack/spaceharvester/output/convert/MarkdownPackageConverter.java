package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.document.MarkdownExportWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Markdown mit Medienordner (ADR 0018): ein ZIP mit {@code pages.md} wie {@link MarkdownConverter} und den
 * Abbildungen als Dateien unter {@code assets/}, im Text relativ verlinkt statt als {@code data:}-URI.
 *
 * <pre>
 * pages.md                       alle Seiten, Bilder als ![Titel](assets/&lt;sha256&gt;.png)
 * assets/&lt;sha256&gt;.png            jede Abbildung einmal: Bilder, gezeichnete draw.io- und Mermaid-Diagramme
 * </pre>
 *
 * Der Dateiname ist die Prüfsumme der PNG-Datei; dieselbe Abbildung auf mehreren Seiten liegt einmal im Paket.
 * Mermaid bleibt darüber als Codeblock mit seinem Quelltext. Deterministisch: feste Zeitstempel, Reihenfolge des Textes.
 */
public final class MarkdownPackageConverter implements ExportConverter {

    public static final String FORMAT = "markdown-package";
    public static final String VERSION = "markdown-package/1";
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

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
        return "application/zip";
    }

    @Override
    public String fileName() {
        return "export-markdown.zip";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) throws IOException {
        Map<String, byte[]> figures = new LinkedHashMap<>();
        ZipOutputStream zip = new ZipOutputStream(new Unclosed(out)); // den Strom schließt der Aufrufer
        entry(zip, "pages.md");
        // Der Text geht direkt ins ZIP; die Abbildungen sammelt die Map und folgen danach.
        MarkdownConverter.write(input, new Unclosed(zip), (graphic, caption) -> {
            String path = "assets/" + sha256(graphic.png()) + ".png";
            figures.putIfAbsent(path, graphic.png());
            return path;
        }, VERSION);
        zip.closeEntry();
        for (Map.Entry<String, byte[]> figure : figures.entrySet()) {
            entry(zip, figure.getKey());
            zip.write(figure.getValue());
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    private static void entry(ZipOutputStream zip, String path) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTimeLocal(ZIP_TIME);
        zip.putNextEntry(entry);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Schreibt durch, schließt aber nicht: ZIP-Einträge und der Strom des Aufrufers bleiben offen. */
    private static final class Unclosed extends FilterOutputStream {

        Unclosed(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
