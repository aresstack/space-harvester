package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Kind;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.document.DrawioDiagramRenderer;
import com.aresstack.spaceharvester.document.DrawioGraph;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * assets/&lt;Seiten-ID&gt;/&lt;Name&gt;.svg    gezeichnete Grafik eines Diagramms
 * assets/&lt;Seiten-ID&gt;/&lt;Name&gt;.graph.json  draw.io als Knoten und Verbindungen für KI-Systeme
 * </pre>
 *
 * Keine Base64-Einbettung; Pfade sind relativ zum Paket. Was nicht abgelegt ist, steht nur mit Status und Grund in
 * {@code assets.json} ({@code REDUNDANT}: draw.io-Vorschau, bewusst weggelassen). Deterministisch: feste Zeitstempel,
 * Reihenfolge wie das Manifest.
 */
public final class JsonPackageConverter implements ExportConverter {

    public static final String FORMAT = "json-package";
    public static final String VERSION = "json-package/1";
    public static final String ASSETS_FORMAT = "space-harvester/export-package-assets/v1";
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final Pattern UNSAFE = Pattern.compile("[^\\p{L}\\p{N}._ -]+");

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
        List<Entry> entries = manifest.map(ExportAssetManifest::assets).orElse(List.of());
        Set<String> used = new HashSet<>();
        entry(zip, "assets.json");
        JsonGenerator generator = json.createGenerator(new Unclosed(zip));
        // Erst die Liste schreiben, Dateien danach: der Generator puffert, das ZIP schreibt nacheinander.
        List<Runnable> files = new ArrayList<>();
        generator.writeStartObject();
        generator.writeStringProperty("format", ASSETS_FORMAT);
        generator.writeBooleanProperty("collected", manifest.isPresent());
        generator.writeBooleanProperty("complete", manifest.map(ExportAssetManifest::complete).orElse(false));
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
            Optional<byte[]> content = entry.stored() ? assets.content(entry) : Optional.empty();
            if (content.isPresent()) {
                String path = unique(base + name, used);
                generator.writeStringProperty("path", path);
                generator.writeNumberProperty("size", content.get().length);
                generator.writeStringProperty("sha256", entry.sha256());
                byte[] bytes = content.get();
                files.add(() -> file(zip, path, bytes));
                if (entry.reference().kind() == Kind.ATTACHMENT
                        && Diagrams.isDrawio(entry.mediaType(), entry.reference().filename())) {
                    graph(generator, files, zip, bytes, unique(base + stem(name) + ".graph.json", used));
                }
            } else {
                generator.writeNullProperty("path");
            }
            Optional<byte[]> rendition = assets.rendition(entry);
            if (rendition.isPresent()) {
                String extension = "image/svg+xml".equals(entry.rendition().mediaType()) ? ".svg" : ".png";
                String path = unique(base + stem(name) + extension, used);
                generator.writeName("rendition");
                generator.writeStartObject();
                generator.writeStringProperty("path", path);
                generator.writeStringProperty("mediaType", entry.rendition().mediaType());
                generator.writeStringProperty("sha256", entry.rendition().sha256());
                generator.writeEndObject();
                byte[] bytes = rendition.get();
                files.add(() -> file(zip, path, bytes));
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
        generator.close();
        zip.closeEntry();
        try {
            files.forEach(Runnable::run);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        zip.finish();
        zip.flush();
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
        files.add(() -> file(zip, path, bytes));
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

    private static void file(ZipOutputStream zip, String path, byte[] bytes) {
        try {
            entry(zip, path);
            zip.write(bytes);
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

    private static String unique(String path, Set<String> used) {
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
