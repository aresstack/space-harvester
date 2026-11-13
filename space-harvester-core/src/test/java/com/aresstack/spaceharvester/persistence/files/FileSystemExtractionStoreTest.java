package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.DocumentStructure;
import com.aresstack.spaceharvester.application.assets.EmbeddedEntry;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.ExtractionWriter;
import com.aresstack.spaceharvester.application.assets.Link;
import com.aresstack.spaceharvester.application.assets.Location;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.assets.ReadExtent;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StoredExtraction;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.application.assets.TextSection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ablage strukturierter Extraktionen in Abschnitten (ADR 0026, AP2). */
class FileSystemExtractionStoreTest {

    private static final String HEX = "ab".repeat(32);
    private static final ExtractionKey KEY = new ExtractionKey("sha256:" + HEX, "tika", "3.3-2",
            DocumentStructure.MODEL_VERSION);

    @TempDir
    Path root;

    @Test
    void writesSectionsAtBlockBoundariesAndReadsThemBackOneByOne() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        List<Block> blocks = List.of(
                new Block.Heading(0, Optional.empty(), List.of(), 1, "Titel"),
                new Block.Paragraph(1, Optional.of(Location.page(2)),
                        List.of(new Link("https://example.org", Optional.of("Beispiel"))), "Absatz"),
                new Block.ListItem(2, Optional.empty(), List.of(), true, 2, "Punkt"),
                new Block.Table(3, Optional.of(Location.sheet("Kosten")), List.of(),
                        List.of(new Block.Cell(0, 0, "Kopf", true, Optional.empty()),
                                new Block.Cell(1, 3, "Wert", false, Optional.of("D17"))), true),
                new Block.Code(4, Optional.empty(), List.of(), "x=1", Optional.of("java")));
        long chars = blocks.stream().mapToLong(Block::chars).sum();

        StoredExtraction stored;
        try (ExtractionWriter writer = store.writer(KEY, 11)) {
            blocks.forEach(writer::accept);
            stored = writer.commit(document(blocks.size(), chars));
        }

        assertThat(stored.blocks()).isEqualTo(5);
        assertThat(stored.chars()).isEqualTo(chars);
        // Titel(5)+Absatz(6) = 11 passt; Punkt(5) allein, weil Tabelle(8) dazu 13 wäre; Tabelle(8)+Code(3) = 11
        assertThat(stored.sections()).isEqualTo(3);
        List<TextSection> sections;
        try (Stream<TextSection> stream = store.sections(KEY)) {
            sections = stream.toList();
        }
        assertThat(sections).extracting(TextSection::firstBlock).containsExactly(0L, 2L, 3L);
        assertThat(sections.stream().flatMap(section -> section.blocks().stream()).toList()).isEqualTo(blocks);
        assertThat(sections).allSatisfy(section -> assertThat(section.chars()).isLessThanOrEqualTo(11));
        assertThat(store.section(KEY, 2)).map(TextSection::blocks).contains(List.of(blocks.get(3), blocks.get(4)));
        assertThat(store.section(KEY, 3)).isEmpty();

        StoredExtraction found = store.find(KEY).orElseThrow();
        assertThat(found.document()).isEqualTo(stored.document());
        assertThat(found.sections()).isEqualTo(3);
    }

    @Test
    void aBlockOverTheLimitFormsASectionOfItsOwn() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        String big = "x".repeat(25);
        try (ExtractionWriter writer = store.writer(KEY, 10)) {
            writer.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "ab"));
            writer.accept(new Block.Paragraph(1, Optional.empty(), List.of(), big));
            writer.accept(new Block.Paragraph(2, Optional.empty(), List.of(), "cd"));
            writer.commit(document(3, 29));
        }

        try (Stream<TextSection> stream = store.sections(KEY)) {
            assertThat(stream.map(TextSection::chars).toList()).containsExactly(2L, 25L, 2L);
        }
    }

    @Test
    void thePreviousStateHoldsUntilTheNewOneIsCommitted() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        write(store, "alt");

        ExtractionWriter running = store.writer(KEY);
        running.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "neu"));
        assertThat(firstText(store)).isEqualTo("alt");
        running.close();
        assertThat(firstText(store)).isEqualTo("alt");

        write(store, "neu");
        assertThat(firstText(store)).isEqualTo("neu");
        try (Stream<Path> generations = Files.list(root.resolve("extractions").resolve(HEX).resolve("ptika")
                .resolve("x332e332d32").resolve(FileSystemAssetStore.name(DocumentStructure.MODEL_VERSION)))) {
            assertThat(generations.filter(path -> path.getFileName().toString().startsWith("g-")).count())
                    .isEqualTo(2);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void anotherModelVersionOrExtractorVersionIsAnotherKey() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        write(store, "text");

        assertThat(store.find(new ExtractionKey(KEY.sha256(), "tika", "3.3-2", "structured-document/v2"))).isEmpty();
        assertThat(store.find(new ExtractionKey(KEY.sha256(), "tika", "3.3-3", KEY.model()))).isEmpty();
        try (Stream<TextSection> stream = store.sections(new ExtractionKey(KEY.sha256(), "tika", "9", KEY.model()))) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void rejectsGapsAndCountersThatDoNotMatch() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        try (ExtractionWriter writer = store.writer(KEY)) {
            assertThatThrownBy(() -> writer.accept(new Block.Paragraph(1, Optional.empty(), List.of(), "x")))
                    .isInstanceOf(IllegalArgumentException.class);
            writer.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "x"));
            assertThatThrownBy(() -> writer.commit(document(2, 1))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(store.find(KEY)).isEmpty();
        assertThatThrownBy(() -> store.writer(KEY, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDocumentWithStatusHasNoSections() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        StructuredDocument failed = new StructuredDocument(DocumentHead.EMPTY,
                Outcome.of(ExtractionStatus.FAILED, StatusReason.ENCRYPTED), List.of(), 0, 0);
        try (ExtractionWriter writer = store.writer(KEY)) {
            writer.commit(failed);
        }

        StoredExtraction found = store.find(KEY).orElseThrow();
        assertThat(found.sections()).isZero();
        assertThat(found.document().outcome().reason()).contains(StatusReason.ENCRYPTED);
    }

    @Test
    void aDamagedManifestCountsAsMissingAndRemoveDeletesAllExtractionsOfTheContent() throws Exception {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        write(store, "text");
        Path base = root.resolve("extractions").resolve(HEX);
        try (Stream<Path> files = Files.walk(base)) {
            Path manifest = files.filter(path -> path.getFileName().toString().equals("document.json")).findFirst()
                    .orElseThrow();
            Files.writeString(manifest, "{kaputt");
        }
        assertThat(store.find(KEY)).isEmpty();

        store.remove(KEY.sha256());
        assertThat(base).doesNotExist();
    }

    @Test
    void aReaderOpenedBeforeAReplacementReadsItsStateToTheEnd() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        write(store, "alt");
        try (Stream<TextSection> running = store.sections(KEY)) {
            write(store, "neu");
            assertThat(running.map(section -> ((Block.Paragraph) section.blocks().getFirst()).text()).toList())
                    .containsExactly("alt");
        }
        write(store, "neuer");
        assertThat(firstText(store)).isEqualTo("neuer");
    }

    @Test
    void aMissingOrDamagedSectionDiscardsTheExtraction() throws Exception {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        write(store, "text");
        Path section = section();
        Files.delete(section);
        assertThat(store.find(KEY)).isEmpty();

        write(store, "text");
        Files.writeString(section(), "{kaputt\n");
        assertThat(store.find(KEY)).isPresent();
        assertThat(store.section(KEY, 0)).isEmpty();
        assertThat(store.find(KEY)).isEmpty();
    }

    @Test
    void blocksWithoutCharactersStillEndASectionAtTheBlockLimit() {
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        int count = FileSystemExtractionStore.MAX_SECTION_BLOCKS + 1;
        try (ExtractionWriter writer = store.writer(KEY)) {
            for (int i = 0; i < count; i++) {
                writer.accept(new Block.Paragraph(i, Optional.empty(), List.of(), ""));
            }
            assertThat(writer.commit(document(count, 0)).sections()).isEqualTo(2);
        }
        assertThat(store.section(KEY, 1).orElseThrow().blocks()).hasSize(1);
    }

    @Test
    void sectionNamesDoNotDependOnTheLocale() {
        java.util.Locale before = java.util.Locale.getDefault();
        FileSystemExtractionStore store = new FileSystemExtractionStore(root);
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"));
            write(store, "text");
            java.util.Locale.setDefault(java.util.Locale.US);
            assertThat(firstText(store)).isEqualTo("text");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    private Path section() throws java.io.IOException {
        try (Stream<Path> files = Files.walk(root.resolve("extractions"))) {
            Path dir = files.filter(path -> path.getFileName().toString().equals("current")).findFirst()
                    .orElseThrow().getParent();
            return dir.resolve(Files.readString(dir.resolve("current")).strip()).resolve("s000000.ndjson");
        }
    }

    private static void write(FileSystemExtractionStore store, String text) {
        try (ExtractionWriter writer = store.writer(KEY)) {
            writer.accept(new Block.Paragraph(0, Optional.empty(), List.of(), text));
            writer.commit(document(1, text.length()));
        }
    }

    private static String firstText(FileSystemExtractionStore store) {
        return ((Block.Paragraph) store.section(KEY, 0).orElseThrow().blocks().getFirst()).text();
    }

    private static StructuredDocument document(long blocks, long chars) {
        String child = "cd".repeat(32);
        EmbeddedEntry ok = new EmbeddedEntry(new PartId.Content(child, 1), Optional.of(child),
                Optional.of("../notiz.txt"), Optional.of("text/plain"), "/1", Optional.empty(), 1, Optional.of(3L),
                Outcome.ok(new ReadExtent(3, 3)));
        EmbeddedEntry cut = new EmbeddedEntry(new PartId.Position(HEX, "/1/1"), Optional.empty(), Optional.empty(),
                Optional.empty(), "/1/1", Optional.of(ok.id()), 2, Optional.empty(),
                Outcome.truncated(StatusReason.UNPACKED_TOO_LARGE, new ReadExtent(20, 0)));
        DocumentHead head = new DocumentHead(Optional.of("Titel"), Optional.of("Betreff"), Optional.of("a@b"),
                List.of("c@d", "e@f"), Optional.of(Instant.parse("2026-11-13T05:00:00Z")));
        return new StructuredDocument(head, Outcome.ok(new ReadExtent(100, chars)), List.of(ok, cut), blocks, chars);
    }
}
