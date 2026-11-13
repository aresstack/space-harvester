package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Kind;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.document.DrawioDiagramRenderer;
import com.aresstack.spaceharvester.document.DrawioGraph;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON mit Medien (ADR 0018): ein ZIP-Paket, das auf anderen Rechnern ohne Quelle und ohne Asset-Speicher lesbar ist.
 *
 * <pre>
 * pages.json                       wie {@link JsonArrayConverter}
 * assets.json                      Zuordnung Seite → Datei, Medientyp, Prüfsumme, Status, relative Pfade
 * assets/&lt;Seiten-ID&gt;/&lt;Datei&gt;       Bilder und Anhänge wie beschafft (draw.io: die Originaldatei)
 * assets/&lt;Seiten-ID&gt;/&lt;Name&gt;.png    gezeichnete Grafik eines Mermaid-Diagramms (draw.io: keine, json-package/4)
 * assets/&lt;Seiten-ID&gt;/&lt;Name&gt;.graph.json  draw.io als Knoten und Verbindungen für KI-Systeme
 * attachments.json                 Dokument-Anhänge (ADR 0023): Seite, Anhang, Typ, Prüfsumme, Status, Pfade
 * attachments/&lt;Seiten-ID&gt;/&lt;Datei&gt;  das Original des Anhangs
 * attachments/&lt;Seiten-ID&gt;/&lt;Datei&gt;.txt  sein beim Beschaffen gelesener Text (ab attachments/v2 nur der eigene)
 * attachments/&lt;Seiten-ID&gt;/&lt;Datei&gt;.md   seine Markdown-Ansicht (attachments/v2)
 * attachments/&lt;Seiten-ID&gt;/parts/&lt;Anhang&gt;/&lt;Kennung&gt;.&lt;Endung&gt;  enthaltene Dateien nach Kennung, dazu .txt und .md
 * raw/&lt;Seiten-ID&gt;.json            die Payload jeder Seite Byte für Byte (json-package/3)
 * views/&lt;Seiten-ID&gt;.json          die Lese-Ansicht einer Seite mit dem Inhalt ausgeführter Makros, sofern abgelegt
 * tree.json                        der Seitenbaum mit Geschwister-Reihenfolge, nur Verweise über IDs
 * manifest.json                    Paketmanifest (export-package/v1): Space, je Seite Kopfdaten-Hashes wie in
 *                                  pages.json, Abruf, Medientyp, rawSha256 und Pfade,
 *                                  dazu das vollständige Bildmanifest des Datenbestands
 * </pre>
 *
 * <p>Mit {@code raw/}, {@code views/}, {@code tree.json} und {@code manifest.json} enthält das Paket den
 * Datenbestand verlustfrei: {@code rawSha256} jeder Seite passt zu ihrer Datei unter {@code raw/}, auch für
 * Antworten, die kein JSON sind (in {@code pages.json} dann {@code content: null}).
 *
 * <p>draw.io steht nur als Text im Paket: Originaldatei und {@code *.graph.json}, ohne gezeichnete Grafik (ADR 0012,
 * Textform vor Bild; Angelo 13.11.2026). Auch {@code manifest.json} nennt für draw.io keine Grafik; nach einem Import
 * zeichnet die Leseansicht bei Bedarf aus den Quelldaten.
 *
 * Keine Base64-Einbettung; Pfade sind relativ zum Paket. Was nicht abgelegt ist, steht nur mit Status und Grund in
 * {@code assets.json} ({@code REDUNDANT}: draw.io-Vorschau, bewusst weggelassen). Deterministisch: feste Zeitstempel,
 * Reihenfolge wie das Manifest.
 */
public final class JsonPackageConverter implements ExportConverter {

    public static final String FORMAT = "json-package";
    public static final String VERSION = "json-package/5";
    /** Format von {@code manifest.json}: alles, was ein Import braucht, um den Datenbestand wiederherzustellen. */
    public static final String PACKAGE_FORMAT = "space-harvester/export-package/v1";
    public static final String ASSETS_FORMAT = "space-harvester/export-package-assets/v1";
    /**
     * Format von {@code attachments.json} (ADR 0023); {@code v2}: enthaltene Dateien als eigene Einträge, Status der
     * Extraktion, Markdown (ADR 0026, AP4c).
     */
    public static final String ATTACHMENTS_FORMAT = "attachments/v2";
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final Pattern UNSAFE = Pattern.compile("[^\\p{L}\\p{N}._ -]+");
    /** Dateinamen der Anhänge: Buchstaben (auch Umlaute), Ziffern, Punkt, Bindestrich, Unterstrich; sonst {@code _}. */
    private static final Pattern UNSAFE_ATTACHMENT = Pattern.compile("[^\\p{L}\\p{N}._-]+");

    private final JsonMapper json = JsonMapper.builder().build();
    private final JsonArrayConverter pages = new JsonArrayConverter();

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
        return "export.zip";
    }

    @Override
    public void convert(ConversionInput input, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new Unclosed(out)); // den Strom schließt der Aufrufer
        entry(zip, "pages.json");
        pages.convert(input, new Unclosed(zip));
        zip.closeEntry();

        ExportAssets assets = input.assets();
        Optional<ExportAssetManifest> manifest = assets.manifest();
        List<Entry> entries = manifest.map(ExportAssetManifest::images).orElse(List.of());
        Set<String> used = new HashSet<>();
        entry(zip, "assets.json");
        JsonGenerator generator = json.createGenerator(new Unclosed(zip));
        // Erst die Liste schreiben, Dateien danach: der Generator puffert, das ZIP schreibt nacheinander. Die Dateien
        // werden dafür erneut aus der Ablage gelesen, statt bis zum Ende alle im Speicher zu liegen (100 MiB je Anhang).
        List<Runnable> files = new ArrayList<>();
        generator.writeStartObject();
        generator.writeStringProperty("format", ASSETS_FORMAT);
        generator.writeBooleanProperty("collected", manifest.isPresent());
        generator.writeBooleanProperty("complete", manifest.map(ExportAssetManifest::imagesComplete).orElse(false));
        generator.writeName("assets");
        generator.writeStartArray();
        for (Entry entry : entries) {
            String base = "assets/" + safe(entry.pageId()) + "/";
            String name = fileName(entry);
            generator.writeStartObject();
            generator.writeStringProperty("pageId", entry.pageId());
            generator.writeStringProperty("kind", entry.reference().kind().json());
            generator.writeStringProperty("title", entry.title());
            generator.writeStringProperty("mediaType", entry.mediaType());
            generator.writeStringProperty("status", entry.status().name());
            if (entry.reason() != null) {
                generator.writeStringProperty("reason", entry.reason());
            }
            if (entry.reference().filename() != null) {
                generator.writeStringProperty("filename", entry.reference().filename());
            }
            if (entry.reference().src() != null) {
                generator.writeStringProperty("src", entry.reference().src());
            }
            OptionalLong size = entry.stored() ? checked(assets, entry) : OptionalLong.empty();
            if (size.isPresent()) {
                String path = unique(base + name, used);
                generator.writeStringProperty("path", path);
                generator.writeNumberProperty("size", size.getAsLong());
                generator.writeStringProperty("sha256", entry.sha256());
                files.add(() -> copy(zip, path, assets.open(entry)));
                if (entry.reference().kind() == Kind.ATTACHMENT
                        && Diagrams.isDrawio(entry.mediaType(), entry.reference().filename())) {
                    // draw.io-Daten sind klein und werden ohnehin ganz gelesen.
                    Optional<byte[]> mxfile = assets.content(entry);
                    if (mxfile.isPresent()) {
                        graph(generator, files, zip, mxfile.get(), unique(base + stem(name) + ".graph.json", used));
                    }
                }
            } else {
                generator.writeNullProperty("path");
            }
            // draw.io nur als Text (json-package/4): keine gezeichnete Grafik
            Optional<byte[]> rendition = entry.rendition() == null || drawio(entry) ? Optional.empty()
                    : assets.rendition(entry);
            if (rendition.isPresent()) {
                String extension = "image/svg+xml".equals(entry.rendition().mediaType()) ? ".svg" : ".png";
                String path = unique(base + stem(name) + extension, used);
                generator.writeName("rendition");
                generator.writeStartObject();
                generator.writeStringProperty("path", path);
                generator.writeStringProperty("mediaType", entry.rendition().mediaType());
                generator.writeStringProperty("sha256", entry.rendition().sha256());
                generator.writeEndObject();
                files.add(() -> file(zip, path, assets.rendition(entry)));
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
        generator.close();
        zip.closeEntry();
        attachments(zip, assets, manifest.map(ExportAssetManifest::documents).orElse(List.of()), files, used);
        try {
            files.forEach(Runnable::run);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        snapshots(zip, input, manifest.map(JsonPackageConverter::withoutDrawioRenditions).orElse(null));
        zip.finish();
        zip.flush();
    }

    /**
     * json-package/3: Rohdaten, Lese-Ansichten, Baum und Paketmanifest. Die Seiten werden dafür ein zweites Mal
     * einzeln aus dem Datenbestand gelesen; im Speicher bleiben nur die Angaben je Seite für das Manifest.
     */
    private void snapshots(ZipOutputStream zip, ConversionInput input, ExportAssetManifest assets)
            throws IOException {
        List<PackagePage> written = new ArrayList<>();
        Map<String, String> urls = new HashMap<>();
        try {
            input.forEachPage(page -> {
                PageSnapshot snapshot = page.snapshot();
                NativePayload payload = snapshot.payload();
                String raw = "raw/" + ExportFileJson.rawFileName(page.pageId(), payload.mediaType());
                file(zip, raw, Optional.of(payload.bytes()));
                String view = input.readingView(page.pageId()).map(reading -> {
                    String path = "views/" + ExportFileJson.rawFileName(page.pageId(), "application/json");
                    file(zip, path, Optional.of(readingViewJson(reading)));
                    return path;
                }).orElse(null);
                URI url = page.header().metadata().url();
                urls.put(page.pageId(), url == null ? null : url.toString());
                // Kopfdaten wie in pages.json (aktueller Stand), damit ein Import sie dagegen prüfen kann.
                written.add(new PackagePage(page.pageId(), page.header().metadata().resourceId(), page.depth(),
                        snapshot.revision(), page.header().metadataHash(), page.header().attributesHash(),
                        snapshot.retrievedAt(), payload.mediaType(), payload.nativeFormat(), payload.rawSha256(),
                        payload.length(), raw, view));
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        entry(zip, "tree.json");
        try (JsonGenerator generator = json.createGenerator(new Unclosed(zip))) {
            generator.writeStartObject();
            generator.writeName("roots");
            nodes(generator, input.tree().roots(), urls);
            generator.writeEndObject();
        }
        zip.closeEntry();

        entry(zip, "manifest.json");
        try (JsonGenerator generator = json.createGenerator(new Unclosed(zip))) {
            generator.writeStartObject();
            generator.writeStringProperty("format", PACKAGE_FORMAT);
            generator.writeStringProperty("converter", VERSION);
            generator.writeStringProperty("pageSnapshotSchema", PageSnapshot.SCHEMA);
            generator.writeStringProperty("sourceId", input.sourceId());
            generator.writeStringProperty("spaceKey", input.spaceKey());
            generator.writeStringProperty("spaceName", input.spaceName());
            generator.writeStringProperty("rootPageId", input.rootPageId());
            generator.writeNumberProperty("pageCount", written.size());
            generator.writeName("pages");
            generator.writeStartArray();
            for (PackagePage page : written) {
                generator.writeStartObject();
                generator.writeStringProperty("pageId", page.pageId());
                generator.writeStringProperty("resourceId", page.resourceId());
                generator.writeNumberProperty("depth", page.depth());
                generator.writeStringProperty("revision", page.revision());
                generator.writeStringProperty("metadataHash", page.metadataHash());
                generator.writeStringProperty("attributesHash", page.attributesHash());
                generator.writeStringProperty("retrievedAt", PageJson.text(page.retrievedAt()));
                generator.writeStringProperty("mediaType", page.mediaType());
                generator.writeStringProperty("nativeFormat", page.nativeFormat());
                generator.writeStringProperty("rawSha256", page.rawSha256());
                generator.writeNumberProperty("size", page.size());
                generator.writeStringProperty("raw", page.raw());
                generator.writeStringProperty("view", page.view());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeName("assets");
            if (assets == null) {
                generator.writeNull();
            } else {
                ExportFileJson.writeAssets(generator, assets);
            }
            generator.writeEndObject();
        }
        zip.closeEntry();
    }

    private static void nodes(JsonGenerator generator, List<PageTree.Node> nodes, Map<String, String> urls) {
        generator.writeStartArray();
        for (PageTree.Node node : nodes) {
            generator.writeStartObject();
            generator.writeStringProperty("id", node.pageId());
            generator.writeStringProperty("url", urls.get(node.pageId()));
            generator.writeName("children");
            nodes(generator, node.children(), urls);
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    private byte[] readingViewJson(ExportReadingView view) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (JsonGenerator generator = json.createGenerator(out)) {
            ExportFileJson.writeReadingView(generator, view);
        }
        return out.toByteArray();
    }

    /** Der Datenbestand ohne gezeichnete Grafiken von draw.io-Diagrammen; Mermaid behält seine. */
    static ExportAssetManifest withoutDrawioRenditions(ExportAssetManifest manifest) {
        return new ExportAssetManifest(manifest.imagesComplete(), manifest.documentsComplete(),
                manifest.assets().stream().map(entry -> entry.rendition() != null && drawio(entry)
                        ? entry.withRendition(null) : entry).toList());
    }

    private static boolean drawio(Entry entry) {
        return entry.reference().kind() == Kind.ATTACHMENT && entry.reference().filename() != null
                && Diagrams.isDrawio(entry.mediaType(), entry.reference().filename());
    }

    /** Angaben einer Seite für {@code manifest.json}; ohne Payload. */
    private record PackagePage(String pageId, String resourceId, int depth, String revision, String metadataHash,
                               String attributesHash, Instant retrievedAt, String mediaType, String nativeFormat,
                               String rawSha256, int size, String raw, String view) {
    }

    /**
     * {@code attachments.json} (ADR 0023, {@code attachments/v2} nach ADR 0026): je Dokument-Anhang Seite, Anhang, Typ,
     * Größe, Prüfsumme, Status und Grund, dazu die Pfade von Original, Text und Markdown und der Status der Extraktion.
     * Jede enthaltene Datei folgt ihrem Behälter als eigener Eintrag mit Teilkennung; ihre Dateien heißen nach der
     * Kennung, der Anzeigename steht nur in {@code title}. Ohne Text bleibt das Original mit Grund
     * {@code text-unavailable} und {@code textPath: null}; was nicht abgelegt ist, steht ohne Dateien darin. Ohne
     * strukturierte Extraktion gilt der bisherige Text ({@code extraction: null}).
     */
    private void attachments(ZipOutputStream zip, ExportAssets assets, List<Entry> documents, List<Runnable> files,
                             Set<String> used) throws IOException {
        // Vorab: Originale der Teile, die sich abrufen lassen; was scheitert, steht ohne Pfad im Verzeichnis.
        Map<Entry, com.aresstack.spaceharvester.application.assets.DocumentExport> structured = new HashMap<>();
        Map<Entry, OptionalLong> sizes = new HashMap<>();
        Map<String, String> originals = new HashMap<>();
        for (Entry entry : documents) {
            OptionalLong size = entry.stored() ? checked(assets, entry) : OptionalLong.empty();
            sizes.put(entry, size);
            if (size.isEmpty()) {
                continue;
            }
            Optional<com.aresstack.spaceharvester.application.assets.DocumentExport> document = assets.document(entry);
            if (document.isEmpty()) {
                continue;
            }
            structured.put(entry, document.get());
            String partsDir = "attachments/" + safeAttachment(entry.pageId()) + "/parts/"
                    + safeAttachment(entry.attachmentId()) + "/";
            for (var part : document.get().parts()) {
                if (!part.original()) {
                    continue;
                }
                String path = unique(partsDir + part.fileName() + "." + partExtension(part.mediaType()), used);
                if (copyOptional(zip, path, document.get(), part)) {
                    originals.put(entry.attachmentId() + '\u0000' + entry.pageId() + '\u0000' + part.partId(),
                            path);
                } else {
                    used.remove(path);
                }
            }
        }
        entry(zip, "attachments.json");
        JsonGenerator generator = json.createGenerator(new Unclosed(zip));
        generator.writeStartObject();
        generator.writeStringProperty("format", ATTACHMENTS_FORMAT);
        generator.writeName("attachments");
        generator.writeStartArray();
        for (Entry entry : documents) {
            OptionalLong size = sizes.get(entry);
            var document = structured.get(entry);
            String path = null;
            String textPath = null;
            String markdownPath = null;
            String reason = entry.reason();
            if (size.isPresent()) {
                String base = "attachments/" + safeAttachment(entry.pageId()) + "/";
                path = unique(base + attachmentName(entry), used);
                String original = path;
                files.add(() -> copy(zip, original, assets.open(entry)));
                if (document != null) {
                    if (document.hasText()) {
                        textPath = unique(path + ".txt", used);
                        markdownPath = unique(path + ".md", used);
                        String text = textPath;
                        String markdown = markdownPath;
                        files.add(() -> write(zip, text, document::writeText));
                        files.add(() -> write(zip, markdown, document::writeMarkdown));
                    } else if (reason == null && document.parts().stream().noneMatch(part -> part.hasText())) {
                        reason = "text-unavailable";
                    }
                } else if (assets.text(entry).filter(t -> !t.isBlank()).isPresent()) {
                    // Leerer Text gilt als nicht lesbar: keine leere .txt-Datei.
                    textPath = unique(path + ".txt", used);
                    String target = textPath;
                    // Wie die Originale erst beim Schreiben erneut aus der Ablage lesen, nicht bis dahin halten.
                    files.add(() -> file(zip, target, assets.text(entry).filter(t -> !t.isBlank())
                            .map(t -> t.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                } else if (reason == null) {
                    reason = "text-unavailable";
                }
            } else if (entry.stored() && reason == null) {
                reason = "asset-store-error";
            }
            generator.writeStartObject();
            generator.writeStringProperty("pageId", entry.pageId());
            generator.writeStringProperty("attachmentId", entry.attachmentId());
            generator.writeStringProperty("partId", null);
            generator.writeStringProperty("parentPartId", null);
            generator.writeStringProperty("title", entry.title());
            generator.writeStringProperty("mediaType", entry.mediaType());
            generator.writeNumberProperty("size", size.isPresent() ? size.getAsLong() : entry.size());
            generator.writeStringProperty("sha256", size.isPresent() ? entry.sha256() : null);
            generator.writeStringProperty("status", entry.status().name());
            generator.writeStringProperty("reason", reason);
            generator.writeStringProperty("path", path);
            generator.writeStringProperty("textPath", textPath);
            generator.writeStringProperty("markdownPath", markdownPath);
            extraction(generator, document == null ? null : document.extraction());
            generator.writeEndObject();
            if (document != null) {
                parts(generator, zip, entry, document, files, used, originals);
            }
        }
        generator.writeEndArray();
        generator.writeEndObject();
        generator.close();
        zip.closeEntry();
    }

    /** Die enthaltenen Dateien eines Dokument-Anhangs als eigene Einträge nach ihrem Behälter. */
    private void parts(JsonGenerator generator, ZipOutputStream zip, Entry container,
                       com.aresstack.spaceharvester.application.assets.DocumentExport document, List<Runnable> files,
                       Set<String> used, Map<String, String> originals) {
        String dir = "attachments/" + safeAttachment(container.pageId()) + "/parts/"
                + safeAttachment(container.attachmentId()) + "/";
        for (var part : document.parts()) {
            String textPath = null;
            String markdownPath = null;
            if (part.hasText()) {
                textPath = unique(dir + part.fileName() + ".txt", used);
                markdownPath = unique(dir + part.fileName() + ".md", used);
                String text = textPath;
                String markdown = markdownPath;
                files.add(() -> write(zip, text, out -> document.writeText(part, out)));
                files.add(() -> write(zip, markdown, out -> document.writeMarkdown(part, out)));
            }
            generator.writeStartObject();
            generator.writeStringProperty("pageId", container.pageId());
            generator.writeStringProperty("attachmentId", container.attachmentId());
            generator.writeStringProperty("partId", part.partId());
            generator.writeStringProperty("parentPartId", part.parentPartId());
            generator.writeStringProperty("position", part.position());
            generator.writeNumberProperty("depth", part.depth());
            generator.writeStringProperty("title", part.title());
            generator.writeStringProperty("mediaType", part.mediaType());
            if (part.size() == null) {
                generator.writeNullProperty("size");
            } else {
                generator.writeNumberProperty("size", part.size());
            }
            generator.writeStringProperty("sha256", part.sha256());
            generator.writeStringProperty("path", originals.get(container.attachmentId() + '\u0000'
                    + container.pageId() + '\u0000' + part.partId()));
            generator.writeStringProperty("textPath", textPath);
            generator.writeStringProperty("markdownPath", markdownPath);
            extraction(generator, part.extraction());
            generator.writeEndObject();
        }
    }

    private static void extraction(JsonGenerator generator,
                                   com.aresstack.spaceharvester.application.assets.DocumentExport.Extraction extraction) {
        if (extraction == null) {
            generator.writeNullProperty("extraction");
            return;
        }
        generator.writeName("extraction");
        generator.writeStartObject();
        generator.writeStringProperty("status", extraction.status());
        generator.writeStringProperty("reason", extraction.reason());
        generator.writeBooleanProperty("truncated", extraction.truncated());
        if (extraction.bytesRead() == null) {
            generator.writeNullProperty("bytesRead");
        } else {
            generator.writeNumberProperty("bytesRead", extraction.bytesRead());
        }
        if (extraction.charsRead() == null) {
            generator.writeNullProperty("charsRead");
        } else {
            generator.writeNumberProperty("charsRead", extraction.charsRead());
        }
        generator.writeStringProperty("extractor", extraction.extractor());
        generator.writeStringProperty("version", extraction.version());
        generator.writeStringProperty("model", extraction.model());
        generator.writeEndObject();
    }

    /** Schreibt das Original eines Teils; {@code false}, wenn es sich nicht abrufen ließ (dann ohne Datei). */
    private static boolean copyOptional(ZipOutputStream zip, String path,
                                        com.aresstack.spaceharvester.application.assets.DocumentExport document,
                                        com.aresstack.spaceharvester.application.assets.DocumentExport.Part part) {
        InputStream in;
        try {
            Optional<InputStream> opened = document.openOriginal(part);
            if (opened.isEmpty()) {
                return false;
            }
            in = opened.get();
        } catch (RuntimeException e) {
            // Teil nicht mehr abrufbar (fester Code am Fehler); er steht ohne Pfad im Verzeichnis.
            return false;
        }
        try (InputStream content = in) {
            entry(zip, path);
            content.transferTo(zip);
            zip.closeEntry();
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ziel eines Texts im Paket. */
    private interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    private static void write(ZipOutputStream zip, String path, Content content) {
        try {
            entry(zip, path);
            content.writeTo(zip);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Endung des Originals einer enthaltenen Datei aus einer festen Tabelle nach dem am Inhalt erkannten Medientyp, nie
     * aus dem Anzeigenamen (ADR 0026, Abnahme 3); unbekannt {@code bin}.
     */
    static String partExtension(String mediaType) {
        if (mediaType == null) {
            return "bin";
        }
        return EXTENSIONS.getOrDefault(mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip(), "bin");
    }

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/vnd.oasis.opendocument.text", "odt"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", "ods"),
            Map.entry("application/vnd.oasis.opendocument.presentation", "odp"),
            Map.entry("application/rtf", "rtf"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/html", "html"),
            Map.entry("application/xhtml+xml", "xhtml"),
            Map.entry("application/xml", "xml"),
            Map.entry("text/xml", "xml"),
            Map.entry("application/json", "json"),
            Map.entry("text/csv", "csv"),
            Map.entry("text/markdown", "md"),
            Map.entry("message/rfc822", "eml"),
            Map.entry("application/vnd.ms-outlook", "msg"),
            Map.entry("application/zip", "zip"),
            Map.entry("application/x-7z-compressed", "7z"),
            Map.entry("application/x-tar", "tar"),
            Map.entry("application/gzip", "gz"),
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/svg+xml", "svg"),
            Map.entry("image/tiff", "tif"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("image/webp", "webp"));

    /** Dateiname eines Anhangs im Paket: sein Titel, nur mit sicheren Zeichen, sonst {@code anhang}. */
    static String attachmentName(Entry entry) {
        String name = entry.reference().filename();
        if (name == null || name.isBlank()) {
            name = entry.title();
        }
        return safeAttachment(name == null || name.isBlank() ? "anhang" : name);
    }

    static String safeAttachment(String value) {
        String cleaned = UNSAFE_ATTACHMENT.matcher(value).replaceAll("_").replace("..", "_");
        cleaned = cleaned.replaceAll("^[._]+(?=.)", "");
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("_")) {
            return "_";
        }
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    /** draw.io als Struktur; nicht lesbare Daten bekommen nur einen Grund. */
    private void graph(JsonGenerator generator, List<Runnable> files, ZipOutputStream zip, byte[] mxfile,
                       String path) {
        DrawioGraph graph;
        try {
            graph = DrawioGraph.of(mxfile);
        } catch (DrawioDiagramRenderer.Unsupported | RuntimeException e) {
            generator.writeStringProperty("graphProblem", e instanceof DrawioDiagramRenderer.Unsupported
                    ? e.getMessage() : e.getClass().getSimpleName());
            return;
        }
        generator.writeStringProperty("graph", path);
        generator.writeStringProperty("text", graph.text());
        byte[] bytes = graphJson(graph);
        files.add(() -> file(zip, path, Optional.of(bytes)));
    }

    private byte[] graphJson(DrawioGraph graph) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringProperty("format", "space-harvester/drawio-graph/v1");
            generator.writeStringProperty("name", graph.name());
            generator.writeName("nodes");
            generator.writeStartArray();
            for (DrawioGraph.Node node : graph.nodes()) {
                generator.writeStartObject();
                generator.writeStringProperty("id", node.id());
                generator.writeStringProperty("label", node.label());
                generator.writeStringProperty("parent", node.parent());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeName("edges");
            generator.writeStartArray();
            for (DrawioGraph.Edge edge : graph.edges()) {
                generator.writeStartObject();
                generator.writeStringProperty("id", edge.id());
                generator.writeStringProperty("label", edge.label());
                generator.writeStringProperty("source", edge.source());
                generator.writeStringProperty("target", edge.target());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeStringProperty("text", graph.text());
            generator.writeEndObject();
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String path) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTimeLocal(ZIP_TIME);
        zip.putNextEntry(entry);
    }

    /**
     * Liest einen abgelegten Eintrag einmal als Strom gegen seine Prüfsumme und zählt dabei seine Größe, ohne ihn zu
     * halten (Originale beliebiger Größe, Angelo 11.11.2026); beim Schreiben wird er erneut gelesen.
     *
     * @return leer, wenn er fehlt, nicht lesbar ist oder nicht zur Prüfsumme passt
     */
    static OptionalLong checked(ExportAssets assets, Entry entry) {
        Optional<InputStream> opened = assets.open(entry);
        if (opened.isEmpty()) {
            return OptionalLong.empty();
        }
        try (InputStream in = opened.get()) {
            return OptionalLong.of(in.transferTo(OutputStream.nullOutputStream()));
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    /** Wie {@link #file}, aber aus einem Strom: Das Original kommt nie ganz in den Speicher. */
    private static void copy(ZipOutputStream zip, String path, Optional<InputStream> content) {
        try {
            if (content.isEmpty()) {
                throw new IOException("Datei verschwand während des Exports: " + path);
            }
            try (InputStream in = content.get()) {
                entry(zip, path);
                in.transferTo(zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void file(ZipOutputStream zip, String path, Optional<byte[]> bytes) {
        try {
            if (bytes.isEmpty()) {
                throw new IOException("Datei verschwand während des Exports: " + path);
            }
            entry(zip, path);
            zip.write(bytes.get());
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Dateiname im Paket: der Anhangname, sonst Titel oder letzter Pfadteil, sonst nach Art. */
    private static String fileName(Entry entry) {
        String name = entry.reference().filename();
        if (name == null || name.isBlank()) {
            if (entry.reference().kind() == Kind.MERMAID) {
                return "mermaid-" + (entry.reference().index() == null ? 0 : entry.reference().index() + 1) + ".mmd";
            }
            name = entry.title();
        }
        if ((name == null || name.isBlank()) && entry.reference().src() != null) {
            String src = entry.reference().src().split("[?#]", 2)[0];
            name = src.substring(src.lastIndexOf('/') + 1);
        }
        name = safe(name == null || name.isBlank() ? "bild" : name);
        if (!name.contains(".") && entry.mediaType() != null) {
            name += extension(entry.mediaType());
        }
        return name;
    }

    private static String extension(String mediaType) {
        String type = mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/svg+xml" -> ".svg";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safe(String value) {
        String cleaned = UNSAFE.matcher(value).replaceAll("_").replace("..", "_").strip();
        return cleaned.isEmpty() ? "_" : cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    static String unique(String path, Set<String> used) {
        if (used.add(path)) {
            return path;
        }
        int slash = path.lastIndexOf('/');
        String dir = path.substring(0, slash + 1);
        String name = path.substring(slash + 1);
        int dot = name.indexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = dir + stem + "-" + i + extension;
            if (used.add(candidate)) {
                return candidate;
            }
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
