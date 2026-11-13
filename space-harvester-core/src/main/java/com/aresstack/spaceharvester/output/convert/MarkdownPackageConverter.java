package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.document.MarkdownExportWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Markdown mit Medienordner (ADR 0018): ein ZIP mit {@code pages.md} wie {@link MarkdownConverter} und den
 * Abbildungen als Dateien unter {@code assets/}, im Text relativ verlinkt statt als {@code data:}-URI. Abgelegte
 * Dokument-Anhänge (ADR 0023) liegen wie im JSON-Paket unter {@code attachments/}; Anhangslinks im Text zeigen relativ
 * darauf, ein Anhang außerhalb des Pakets wird Text mit Hinweis „Anhang nicht im Paket“.
 *
 * <pre>
 * pages.md                       alle Seiten, Bilder als ![Titel](assets/&lt;sha256&gt;.png)
 * assets/&lt;sha256&gt;.png            jede Abbildung einmal: Bilder und gezeichnete Mermaid-Diagramme
 * attachments/&lt;Seiten-ID&gt;/&lt;Datei&gt;  das Original des Anhangs, verlinkt als [Titel](attachments/…)
 * </pre>
 *
 * Der Dateiname ist die Prüfsumme der PNG-Datei; dieselbe Abbildung auf mehreren Seiten liegt einmal im Paket.
 * Mermaid bleibt darüber als Codeblock mit seinem Quelltext; draw.io steht nur als Codeblock {@code ```drawio} mit
 * seinem XML im Text (gezeichnet nur ohne lesbare Quelldaten). Deterministisch: feste Zeitstempel, Reihenfolge des Textes.
 */
public final class MarkdownPackageConverter implements ExportConverter {

    public static final String FORMAT = "markdown-package";
    public static final String VERSION = "markdown-package/3";
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
        ExportAssets assets = input.assets();
        Map<Entry, String> attachments = attachments(assets);
        Map<String, String> byName = new HashMap<>();
        attachments.forEach((entry, path) -> byName.putIfAbsent(key(entry.pageId(), name(entry)), path));
        ZipOutputStream zip = new ZipOutputStream(new Unclosed(out)); // den Strom schließt der Aufrufer
        entry(zip, "pages.md");
        // Der Text geht direkt ins ZIP; die Abbildungen sammelt die Map und folgen danach.
        MarkdownConverter.write(input, new Unclosed(zip), (graphic, caption) -> {
            String path = "assets/" + sha256(graphic.png()) + ".png";
            figures.putIfAbsent(path, graphic.png());
            return path;
        }, (pageId, filename) -> byName.get(key(pageId, filename)), VERSION);
        zip.closeEntry();
        for (Map.Entry<String, byte[]> figure : figures.entrySet()) {
            entry(zip, figure.getKey());
            zip.write(figure.getValue());
            zip.closeEntry();
        }
        // Originale erst jetzt erneut aus der Ablage lesen und durchreichen, nie ganz im Speicher (wie im JSON-Paket).
        for (Map.Entry<Entry, String> attachment : attachments.entrySet()) {
            Optional<InputStream> content = assets.open(attachment.getKey());
            if (content.isEmpty()) {
                throw new IOException("Datei verschwand während des Exports: " + attachment.getValue());
            }
            try (InputStream in = content.get()) {
                entry(zip, attachment.getValue());
                in.transferTo(zip);
                zip.closeEntry();
            }
        }
        zip.finish();
        zip.flush();
    }

    /** Die lesbar abgelegten Dokument-Anhänge mit ihrem Pfad im Paket, wie im JSON-Paket benannt. */
    private static Map<Entry, String> attachments(ExportAssets assets) {
        List<Entry> documents = assets.manifest().map(ExportAssetManifest::documents).orElse(List.of());
        Map<Entry, String> paths = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Entry entry : documents) {
            if (entry.stored() && JsonPackageConverter.checked(assets, entry).isPresent()) {
                paths.put(entry, JsonPackageConverter.unique("attachments/"
                        + JsonPackageConverter.safeAttachment(entry.pageId()) + "/"
                        + JsonPackageConverter.attachmentName(entry), used));
            }
        }
        return paths;
    }

    /** Der Dateiname, unter dem der Seitentext den Anhang verlinkt ({@code ri:filename}), sonst sein Titel. */
    private static String name(Entry entry) {
        String filename = entry.reference().filename();
        return filename == null || filename.isBlank() ? entry.title() : filename;
    }

    private static String key(String pageId, String filename) {
        return pageId + "\u0000" + filename;
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
