package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.ExtractionStore;
import com.aresstack.spaceharvester.application.assets.ExtractionWriter;
import com.aresstack.spaceharvester.application.assets.PartWriter;
import com.aresstack.spaceharvester.application.assets.StoredPart;
import com.aresstack.spaceharvester.application.assets.StoredExtraction;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.application.assets.TextSection;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Legt strukturierte Extraktionen in Abschnitten im Asset-Verzeichnis ab (ADR 0026, Punkte 8 und 11):
 *
 * <ul>
 *   <li>{@code extractions/<sha256-hex>/<Extraktion>/<Version>/<Strukturmodell>/current}: Name des gültigen
 *       Stands</li>
 *   <li>{@code …/g-<uuid>/document.json}: Schema, Kopfdaten, Status, Verzeichnis, Zähler und Abschnitte</li>
 *   <li>{@code …/g-<uuid>/s000000.ndjson}: die Blöcke eines Abschnitts, je Zeile einer</li>
 *   <li>{@code …/g-<uuid>/parts/<Schlüssel>/s000000.ndjson}: die Abschnitte eines enthaltenen Teils (AP7b);
 *       Kopf und Zähler stehen in {@code document.json}. Behälter und Teile werden zusammen gültig.</li>
 * </ul>
 *
 * <p>Ein neuer Stand wird vollständig in ein eigenes Verzeichnis geschrieben und erst dann durch atomares Ersetzen von
 * {@code current} gültig; bis dahin liest jeder den bisherigen Stand, ein abgebrochener Lauf hinterlässt nichts
 * Sichtbares. Der ersetzte Stand bleibt bis zum nächsten Ersetzen liegen, damit ein laufender Leser ihn zu Ende liest.
 * Fehlt ein Abschnitt oder ist er beschädigt, wird der Stand verworfen und gilt danach als nicht vorhanden. Ein
 * Abschnitt hat höchstens {@link #MAX_SECTION_BLOCKS} Blöcke. Namen aus Extraktion, Version und Modell gehen über {@link FileSystemAssetStore#name} nie ungeprüft in
 * Pfade. Eine beschädigte Ablage gilt als nicht vorhanden; dann wird neu gelesen.
 */
public class FileSystemExtractionStore implements ExtractionStore {

    static final String SCHEMA = "structured-extraction/v1";
    /** Höchstens so viele Blöcke je Abschnitt, damit auch Blöcke ohne Zeichen keinen unbegrenzten Abschnitt bilden. */
    static final int MAX_SECTION_BLOCKS = 10_000;
    private static final String CURRENT = "current";
    private static final int MOVE_ATTEMPTS = 5;
    private static final String DOCUMENT = "document.json";
    private static final String PARTS = "parts";
    private static final Pattern GENERATION = Pattern.compile("g-[0-9a-f-]{36}");

    private final Path root;

    /** @param assetRoot Verzeichnis des {@link FileSystemAssetStore}; abgelegt wird unter {@code extractions/} */
    public FileSystemExtractionStore(Path assetRoot) {
        this.root = Objects.requireNonNull(assetRoot, "assetRoot").toAbsolutePath().normalize()
                .resolve("extractions");
    }

    @Override
    public Optional<StoredExtraction> find(ExtractionKey key) {
        Optional<Path> dir = generation(key);
        Optional<Manifest> manifest = dir.flatMap(path -> manifest(key, path));
        if (manifest.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < manifest.get().sections().size(); i++) {
            if (!Files.isRegularFile(dir.get().resolve(sectionName(i)))) {
                invalidate(key, dir.get());
                return Optional.empty();
            }
        }
        for (PartInfo part : manifest.get().parts()) {
            Path partDir = partDir(dir.get(), part.stored().key());
            for (int i = 0; i < part.sections().size(); i++) {
                if (!Files.isRegularFile(partDir.resolve(sectionName(i)))) {
                    invalidate(key, dir.get());
                    return Optional.empty();
                }
            }
        }
        return Optional.of(manifest.get().stored());
    }

    @Override
    public ExtractionWriter writer(ExtractionKey key, int sectionChars) {
        Objects.requireNonNull(key, "key");
        if (sectionChars < 1) {
            throw new IllegalArgumentException("Abschnitte brauchen mindestens ein Zeichen");
        }
        try {
            return new Writer(key, sectionChars);
        } catch (IOException e) {
            throw new AssetStoreException("Extraktion nicht ablegbar", e);
        }
    }

    @Override
    public Optional<TextSection> section(ExtractionKey key, int index) {
        Optional<Path> dir = generation(key);
        if (dir.isEmpty()) {
            return Optional.empty();
        }
        Optional<Manifest> manifest = manifest(key, dir.get());
        if (manifest.isEmpty() || index < 0 || index >= manifest.get().sections().size()) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(dir.get(), index, manifest.get().sections().get(index)));
        } catch (DamagedSection e) {
            invalidate(key, dir.get());
            return Optional.empty();
        }
    }

    @Override
    public Stream<TextSection> sections(ExtractionKey key) {
        Optional<Path> dir = generation(key);
        Optional<Manifest> manifest = dir.flatMap(path -> manifest(key, path));
        if (manifest.isEmpty()) {
            return Stream.empty();
        }
        List<SectionInfo> infos = manifest.get().sections();
        return IntStream.range(0, infos.size()).mapToObj(i -> {
            try {
                return read(dir.get(), i, infos.get(i));
            } catch (DamagedSection e) {
                invalidate(key, dir.get());
                throw new AssetStoreException("Abschnitt beschädigt; Extraktion verworfen", null);
            }
        });
    }

    @Override
    public List<StoredPart> parts(ExtractionKey key) {
        return generation(key).flatMap(path -> manifest(key, path))
                .map(manifest -> manifest.parts().stream().map(PartInfo::stored).toList()).orElse(List.of());
    }

    @Override
    public Stream<TextSection> partSections(ExtractionKey key, String partKey) {
        StoredPart.checkKey(partKey);
        Optional<Path> dir = generation(key);
        Optional<PartInfo> part = dir.flatMap(path -> manifest(key, path))
                .flatMap(manifest -> manifest.parts().stream().filter(p -> p.stored().key().equals(partKey))
                        .findFirst());
        if (part.isEmpty()) {
            return Stream.empty();
        }
        Path partDir = partDir(dir.get(), partKey);
        List<SectionInfo> infos = part.get().sections();
        return IntStream.range(0, infos.size()).mapToObj(i -> {
            try {
                return read(partDir, i, infos.get(i));
            } catch (DamagedSection e) {
                invalidate(key, dir.get());
                throw new AssetStoreException("Abschnitt beschädigt; Extraktion verworfen", null);
            }
        });
    }

    private static Path partDir(Path generation, String partKey) {
        return generation.resolve(PARTS).resolve(StoredPart.checkKey(partKey));
    }

    /**
     * Verwirft einen beschädigten Stand, damit er als nicht vorhanden gilt und neu gelesen wird; nur, wenn er noch der
     * gültige ist.
     */
    private void invalidate(ExtractionKey key, Path dir) {
        Path current = base(key).resolve(CURRENT);
        try {
            if (generation(key).filter(dir::equals).isPresent()) {
                Files.deleteIfExists(current);
            }
        } catch (IOException | AssetStoreException e) {
            // nichts weiter: dann bleibt der Lesefehler sichtbar
        }
    }

    /** Entfernt alle Extraktionen eines Inhalts, etwa wenn der Inhalt die Ablage verlässt. */
    public void remove(String sha256) {
        if (!sha256.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256:<hex> erwartet");
        }
        try {
            deleteTree(root.resolve(sha256.substring("sha256:".length())));
        } catch (IOException e) {
            throw new AssetStoreException("Extraktionen nicht entfernbar", e);
        }
    }

    private Path base(ExtractionKey key) {
        return root.resolve(key.sha256().substring("sha256:".length()))
                .resolve(FileSystemAssetStore.name(key.extractor())).resolve(FileSystemAssetStore.name(key.version()))
                .resolve(FileSystemAssetStore.name(key.model()));
    }

    private Optional<Path> generation(ExtractionKey key) {
        Path base = base(key);
        try {
            String name = Files.readString(base.resolve(CURRENT), StandardCharsets.UTF_8).strip();
            if (!GENERATION.matcher(name).matches()) {
                return Optional.empty();
            }
            return Optional.of(base.resolve(name));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new AssetStoreException("Extraktion nicht lesbar", e);
        }
    }

    private Optional<Manifest> manifest(ExtractionKey key, Path dir) {
        try {
            JsonNode node = ExtractionJson.JSON.readTree(Files.readString(dir.resolve(DOCUMENT),
                    StandardCharsets.UTF_8));
            if (!SCHEMA.equals(node.path("schema").asString(null))
                    || !key.sha256().equals(node.path("sha256").asString(null))
                    || !key.extractor().equals(node.path("extractor").asString(null))
                    || !key.version().equals(node.path("version").asString(null))
                    || !key.model().equals(node.path("model").asString(null))) {
                return Optional.empty();
            }
            List<SectionInfo> sections = sectionInfos(node.path("sections"));
            List<PartInfo> parts = new ArrayList<>();
            for (JsonNode p : node.path("parts")) {
                List<SectionInfo> partSections = sectionInfos(p.path("sections"));
                parts.add(new PartInfo(new StoredPart(p.path("key").asString(), ExtractionJson.head(p.path("head")),
                        partSections.size(), p.path("totalBlocks").asLong(), p.path("totalChars").asLong()),
                        partSections));
            }
            StructuredDocument document = ExtractionJson.document(node.path("document"));
            return Optional.of(new Manifest(new StoredExtraction(key, document, sections.size(),
                    node.path("totalBlocks").asLong(), node.path("totalChars").asLong(),
                    Instant.parse(node.path("createdAt").asString())), sections, List.copyOf(parts)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new AssetStoreException("Extraktion nicht lesbar", e);
        } catch (JacksonException | IllegalArgumentException | java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    private static List<SectionInfo> sectionInfos(JsonNode list) {
        List<SectionInfo> sections = new ArrayList<>();
        for (JsonNode s : list) {
            sections.add(new SectionInfo(s.path("firstBlock").asLong(), s.path("blocks").asInt(),
                    s.path("chars").asLong()));
        }
        return sections;
    }

    private static void sectionInfos(ArrayNode list, List<SectionInfo> sections) {
        for (SectionInfo info : sections) {
            ObjectNode s = list.addObject();
            s.put("firstBlock", info.firstBlock());
            s.put("blocks", info.blocks());
            s.put("chars", info.chars());
        }
    }

    private static TextSection read(Path dir, int index, SectionInfo info) {
        List<Block> blocks = new ArrayList<>(Math.min(info.blocks(), MAX_SECTION_BLOCKS));
        try (BufferedReader in = Files.newBufferedReader(dir.resolve(sectionName(index)), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isEmpty()) {
                    blocks.add(ExtractionJson.block(ExtractionJson.JSON.readTree(line)));
                }
            }
        } catch (NoSuchFileException e) {
            throw new DamagedSection();
        } catch (IOException e) {
            throw new AssetStoreException("Abschnitt nicht lesbar", e);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new DamagedSection();
        }
        if (blocks.size() != info.blocks() || blocks.getFirst().index() != info.firstBlock()) {
            throw new DamagedSection();
        }
        return new TextSection(index, info.firstBlock(), blocks, info.chars());
    }

    private static String sectionName(int index) {
        return String.format(java.util.Locale.ROOT, "s%06d.ndjson", index);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record SectionInfo(long firstBlock, int blocks, long chars) {
    }

    /** Ein Abschnitt fehlt oder ist beschädigt. */
    private static final class DamagedSection extends RuntimeException {

        DamagedSection() {
            super(null, null, false, false);
        }
    }

    private record Manifest(StoredExtraction stored, List<SectionInfo> sections, List<PartInfo> parts) {
    }

    private record PartInfo(StoredPart stored, List<SectionInfo> sections) {
    }

    /** Schreibt Blöcke lückenlos ab 0 in Abschnitte eines Verzeichnisses. */
    private static final class SectionSink {

        private final Path dir;
        private final int sectionChars;
        private final List<SectionInfo> sections = new ArrayList<>();
        private BufferedWriter out;
        private long sectionFirst;
        private int sectionBlocks;
        private long sectionCharCount;
        private long blocks;
        private long chars;

        SectionSink(Path dir, int sectionChars) {
            this.dir = dir;
            this.sectionChars = sectionChars;
        }

        void accept(Block block) {
            if (block.index() != blocks) {
                throw new IllegalArgumentException("Blöcke lückenlos ab 0 erwartet");
            }
            long size = block.chars();
            try {
                if (out != null && (sectionCharCount + size > sectionChars || sectionBlocks >= MAX_SECTION_BLOCKS)) {
                    finishSection();
                }
                if (out == null) {
                    Files.createDirectories(dir);
                    out = Files.newBufferedWriter(dir.resolve(sectionName(sections.size())), StandardCharsets.UTF_8);
                    sectionFirst = block.index();
                }
                out.write(ExtractionJson.JSON.writeValueAsString(ExtractionJson.block(block)));
                out.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            sectionBlocks++;
            sectionCharCount += size;
            blocks++;
            chars += size;
        }

        void finish() throws IOException {
            if (out != null) {
                finishSection();
            }
        }

        void abandon() throws IOException {
            if (out != null) {
                out.close();
                out = null;
            }
        }

        private void finishSection() throws IOException {
            out.close();
            out = null;
            sections.add(new SectionInfo(sectionFirst, sectionBlocks, sectionCharCount));
            sectionBlocks = 0;
            sectionCharCount = 0;
        }
    }

    /** Ein Teil im Stand: seine Abschnitte und sein Kopf. */
    private static final class PartState {

        final String key;
        final SectionSink sink;
        DocumentHead head = DocumentHead.EMPTY;
        boolean closed;

        PartState(String key, SectionSink sink) {
            this.key = key;
            this.sink = sink;
        }
    }

    /** Schreibt einen Stand in sein eigenes Verzeichnis, Abschnitt für Abschnitt; Teile darunter. */
    private final class Writer implements ExtractionWriter {

        private final ExtractionKey key;
        private final int sectionChars;
        private final Path base;
        private final Path dir;
        private final SectionSink document;
        private final Map<String, PartState> parts = new LinkedHashMap<>();
        private boolean done;

        Writer(ExtractionKey key, int sectionChars) throws IOException {
            this.key = key;
            this.sectionChars = sectionChars;
            this.base = base(key);
            this.dir = base.resolve("g-" + UUID.randomUUID());
            Files.createDirectories(dir);
            this.document = new SectionSink(dir, sectionChars);
        }

        @Override
        public synchronized void accept(Block block) {
            if (done) {
                throw new IllegalStateException("Extraktion schon abgeschlossen");
            }
            document.accept(block);
        }

        @Override
        public synchronized Optional<PartWriter> part(String partKey) {
            if (done) {
                throw new IllegalStateException("Extraktion schon abgeschlossen");
            }
            StoredPart.checkKey(partKey);
            if (parts.containsKey(partKey)) {
                return Optional.empty();
            }
            PartState part = new PartState(partKey, new SectionSink(partDir(dir, partKey), sectionChars));
            parts.put(partKey, part);
            return Optional.of(new PartWriter() {
                @Override
                public void accept(Block block) {
                    synchronized (Writer.this) {
                        if (done || part.closed) {
                            throw new IllegalStateException("Teil schon abgeschlossen");
                        }
                        part.sink.accept(block);
                    }
                }

                @Override
                public void head(DocumentHead head) {
                    synchronized (Writer.this) {
                        part.head = Objects.requireNonNull(head, "head");
                    }
                }

                @Override
                public void close() {
                    synchronized (Writer.this) {
                        if (part.closed || done) {
                            return;
                        }
                        part.closed = true;
                        try {
                            part.sink.finish();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }
            });
        }

        @Override
        public synchronized StoredExtraction commit(StructuredDocument structured) {
            if (done) {
                throw new IllegalStateException("Extraktion schon abgeschlossen");
            }
            if (structured.blocks() != document.blocks || structured.chars() != document.chars) {
                throw new IllegalArgumentException("Zähler passen nicht zu den geschriebenen Blöcken");
            }
            Instant createdAt = Instant.now();
            try {
                document.finish();
                ObjectNode node = ExtractionJson.JSON.createObjectNode();
                node.put("schema", SCHEMA);
                node.put("sha256", key.sha256());
                node.put("extractor", key.extractor());
                node.put("version", key.version());
                node.put("model", key.model());
                node.put("createdAt", createdAt.toString());
                node.put("totalBlocks", document.blocks);
                node.put("totalChars", document.chars);
                sectionInfos(node.putArray("sections"), document.sections);
                ArrayNode partList = node.putArray("parts");
                for (PartState part : parts.values()) {
                    if (!part.closed) {
                        part.closed = true;
                        part.sink.finish();
                    }
                    ObjectNode p = partList.addObject();
                    p.put("key", part.key);
                    p.set("head", ExtractionJson.head(part.head));
                    p.put("totalBlocks", part.sink.blocks);
                    p.put("totalChars", part.sink.chars);
                    sectionInfos(p.putArray("sections"), part.sink.sections);
                }
                node.set("document", ExtractionJson.document(structured));
                Files.writeString(dir.resolve(DOCUMENT), ExtractionJson.JSON.writeValueAsString(node),
                        StandardCharsets.UTF_8);
                Optional<Path> previous = generation(key);
                replace(base.resolve(CURRENT), dir.getFileName().toString());
                done = true;
                removeOlderGenerations(previous.orElse(null));
            } catch (IOException | UncheckedIOException e) {
                close();
                throw new AssetStoreException("Extraktion nicht ablegbar", e);
            }
            return new StoredExtraction(key, structured, document.sections.size(), document.blocks, document.chars,
                    createdAt);
        }

        @Override
        public synchronized void close() {
            if (done) {
                return;
            }
            done = true;
            try {
                document.abandon();
                for (PartState part : parts.values()) {
                    part.sink.abandon();
                }
                deleteTree(dir);
            } catch (IOException e) {
                // nichts weiter: ein nie gültig gewordener Stand
            }
        }

        /**
         * Entfernt alle Stände außer dem neuen und dem eben ersetzten: Ein Leser, der den ersetzten Stand vor dem
         * Ersetzen geöffnet hat, liest ihn zu Ende; erst das übernächste Ersetzen räumt ihn weg.
         */
        private void removeOlderGenerations(Path previous) throws IOException {
            try (Stream<Path> children = Files.list(base)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    String name = child.getFileName().toString();
                    if (GENERATION.matcher(name).matches() && !child.equals(dir) && !child.equals(previous)) {
                        deleteTree(child);
                    }
                }
            }
        }

        /**
         * Ersetzt atomar; unter Windows kann das scheitern, solange ein Leser die Datei offen hat, deshalb einige kurze
         * Wiederholungen statt eines Abbruchs.
         */
        private static void move(Path temp, Path target) throws IOException {
            for (int attempt = 1; ; attempt++) {
                try {
                    try {
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return;
                } catch (java.nio.file.FileSystemException e) {
                    if (attempt >= MOVE_ATTEMPTS) {
                        throw e;
                    }
                    try {
                        Thread.sleep(20L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }

        private static void replace(Path target, String content) throws IOException {
            Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }
}
