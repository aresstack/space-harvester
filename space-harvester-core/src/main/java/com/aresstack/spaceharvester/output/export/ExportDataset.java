package com.aresstack.spaceharvester.output.export;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.convert.SpaceNames;
import com.aresstack.spaceharvester.application.export.ExportDatasetDamagedException;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.output.convert.PageJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Der gespeicherte Datenbestand eines veröffentlichten Exports als Eingabe für Converter: Kopfdaten aus
 * {@code pages.ndjson}, Baum aus {@code tree.json}, Payloads Byte für Byte aus {@code raw/}. Liest nie den Cache oder
 * die Quelle. Payloads werden einzeln geladen und gegen ihre {@code sha256} geprüft; eine Abweichung ist ein
 * beschädigter Datenbestand ({@link ExportDatasetDamagedException}), nie eine stillschweigend andere Datei.
 */
final class ExportDataset implements ConversionInput {

    private final String exportId;
    private final Path directory;
    private final String sourceId;
    private final String spaceKey;
    private final PageTree tree;
    private final Map<String, Entry> pages;
    private final JsonMapper json;

    private record Entry(PageHeader header, String file, String mediaType, String nativeFormat, String sha256,
                         String revision, Instant retrievedAt) {
    }

    private ExportDataset(String exportId, Path directory, String sourceId, String spaceKey, PageTree tree,
                          Map<String, Entry> pages, JsonMapper json) {
        this.exportId = exportId;
        this.directory = directory;
        this.sourceId = sourceId;
        this.spaceKey = spaceKey;
        this.tree = tree;
        this.pages = pages;
        this.json = json;
    }

    static ExportDataset open(String exportId, Path directory, JsonMapper json) {
        try {
            JsonNode manifest = json.readTree(directory.resolve(FileSystemExportArtifactWriter.MANIFEST).toFile());
            Map<String, Entry> pages = new LinkedHashMap<>();
            try (BufferedReader lines = Files.newBufferedReader(directory.resolve(FileSystemExportArtifactWriter.PAGES),
                    StandardCharsets.UTF_8)) {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = json.readTree(line);
                    PageHeader header = PageJson.readHeader(node.path("header"));
                    JsonNode raw = node.path("raw");
                    pages.put(header.key().pageId(), new Entry(header, raw.path("file").asString(),
                            raw.path("mediaType").asString(), raw.path("nativeFormat").asString(),
                            raw.path("sha256").asString(), raw.hasNonNull("revision")
                            ? raw.path("revision").asString() : null, Instant.parse(raw.path("retrievedAt")
                            .asString())));
                }
            }
            List<PageTree.Node> roots = nodes(json.readTree(directory.resolve(FileSystemExportArtifactWriter.TREE)
                    .toFile()).path("roots"), pages, new HashSet<>(), exportId);
            int[] stats = new int[2];
            measure(roots, 1, stats);
            if (stats[0] != pages.size()) {
                throw new ExportDatasetDamagedException(exportId, "Baum und Seitenliste passen nicht zusammen");
            }
            String sourceId = manifest.path("sourceId").asString();
            String spaceKey = manifest.path("spaceKey").asString();
            return new ExportDataset(exportId, directory, sourceId, spaceKey,
                    new PageTree(roots, List.of(), stats[0], stats[1]), pages, json);
        } catch (IOException | UncheckedIOException e) {
            // Eine Datei des Datenbestands fehlt oder ist nicht lesbar.
            throw new ExportDatasetDamagedException(exportId, "Datei des Datenbestands fehlt oder ist nicht lesbar");
        } catch (RuntimeException e) {
            if (e instanceof ExportDatasetDamagedException) {
                throw e;
            }
            throw new ExportDatasetDamagedException(exportId, "Datenbestand nicht lesbar (" + e.getClass()
                    .getSimpleName() + ")");
        }
    }

    private static List<PageTree.Node> nodes(JsonNode array, Map<String, Entry> pages, Set<String> seen,
                                             String exportId) {
        List<PageTree.Node> nodes = new ArrayList<>();
        for (JsonNode node : array) {
            String pageId = node.path("id").asString();
            if (!pages.containsKey(pageId)) {
                throw new ExportDatasetDamagedException(exportId, "Baum nennt eine Seite ohne Payload");
            }
            if (!seen.add(pageId)) {
                // Zusammen mit der Zählung unten: jede Seite der Liste genau einmal im Baum.
                throw new ExportDatasetDamagedException(exportId, "Baum nennt eine Seite mehrfach");
            }
            nodes.add(new PageTree.Node(pageId, nodes(node.path("children"), pages, seen, exportId)));
        }
        return nodes;
    }

    private static void measure(List<PageTree.Node> nodes, int depth, int[] stats) {
        for (PageTree.Node node : nodes) {
            stats[0]++;
            stats[1] = Math.max(stats[1], depth);
            measure(node.children(), depth + 1, stats);
        }
    }

    int pageCount() {
        return pages.size();
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String spaceKey() {
        return spaceKey;
    }

    /** Aus der ersten Seite in Baumreihenfolge; eine Seite genügt, alle tragen denselben Space. */
    @Override
    public String spaceName() {
        if (tree.roots().isEmpty()) {
            return spaceKey;
        }
        return SpaceNames.of(snapshot(pages.get(tree.roots().get(0).pageId())), spaceKey).orElse(spaceKey);
    }

    @Override
    public PageTree tree() {
        return tree;
    }

    @Override
    public List<PageHeader> headers() {
        List<PageHeader> headers = new ArrayList<>(pages.size());
        walk(tree.roots(), 0, (pageId, depth) -> headers.add(pages.get(pageId).header()));
        return headers;
    }

    @Override
    public void forEachPage(Consumer<ConvertiblePage> action) {
        walk(tree.roots(), 0, (pageId, depth) -> action.accept(new ConvertiblePage(pages.get(pageId).header(),
                snapshot(pages.get(pageId)), depth)));
    }

    @Override
    public Optional<ExportReadingView> readingView(String pageId) {
        Entry entry = pages.get(pageId);
        if (entry == null) {
            return Optional.empty();
        }
        // Nur die Ansicht zur exportierten Version; eine ältere oder neuere fehlt.
        return FileSystemExportArtifactWriter.readReadingView(directory, pageId, json)
                .filter(view -> Objects.equals(view.version(), entry.revision()));
    }

    private PageSnapshot snapshot(Entry entry) {
        Path file = directory.resolve(entry.file()).normalize();
        if (!file.startsWith(directory)) {
            throw new ExportDatasetDamagedException(exportId, "Payload liegt außerhalb des Datenbestands");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ExportDatasetDamagedException(exportId, "Payload einer Seite fehlt");
        }
        if (!("sha256:" + sha256Hex(bytes)).equals(entry.sha256())) {
            throw new ExportDatasetDamagedException(exportId, "Payload einer Seite weicht von ihrer Prüfsumme ab");
        }
        PageHeader header = entry.header();
        PageMetadata current = header.metadata();
        PageMetadata retrieved = new PageMetadata(current.pageId(), current.resourceId(), current.title(),
                current.partitionKey(), current.parentPageId(), entry.revision(), current.modifiedAt(), current.url(),
                current.attributes());
        return new PageSnapshot(header.key(), retrieved, header.metadataHash(), header.attributesHash(),
                new NativePayload(bytes, entry.mediaType(), entry.nativeFormat()), entry.retrievedAt());
    }

    private interface Visitor {
        void visit(String pageId, int depth);
    }

    private static void walk(List<PageTree.Node> nodes, int depth, Visitor visitor) {
        for (PageTree.Node node : nodes) {
            visitor.visit(node.pageId(), depth);
            walk(node.children(), depth + 1, visitor);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
