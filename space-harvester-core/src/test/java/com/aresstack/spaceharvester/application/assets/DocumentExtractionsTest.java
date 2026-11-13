package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Anschluss des strukturierten Lesens an die Ablage (ADR 0026, AP3b). */
class DocumentExtractionsTest {

    private static final Instant NOW = Instant.parse("2026-11-13T06:00:00Z");

    @TempDir
    Path directory;

    @Test
    void aDocumentAboveTheOldInMemoryLimitIsReadFromItsFile() {
        Lines structure = new Lines();
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        String line = "Absatz " + "x".repeat(1000);
        int lines = (int) (Assets.IN_MEMORY_BYTES / line.length()) + 10;
        Asset asset = put(store, (line + "\n").repeat(lines));
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS, Integer.MAX_VALUE);

        AssetText text = extractions.text(asset, store, NOW);

        assertThat(structure.readFromFile).as("aus der Datei gelesen, nicht kopiert").isTrue();
        assertThat(text.truncated()).isFalse();
        assertThat(text.text()).startsWith(line + "\n\n" + line).endsWith(line);
        assertThat(text.extractor()).isEqualTo(structure.id());
        assertThat(store.text(asset.sha256(), structure.id(), structure.version())).hasValue(text);
    }

    @Test
    void theTextIsCutAtTheConfiguredLengthAndMarkedTruncated() {
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\nzwei\ndrei\n");
        DocumentExtractions extractions = new DocumentExtractions(new Lines(),
                new FileSystemExtractionStore(directory), ExtractionLimits.DEFAULTS, 8);

        AssetText text = extractions.text(asset, store, NOW);

        assertThat(text.text()).isEqualTo("eins\n\nzw");
        assertThat(text.truncated()).isTrue();
    }

    @Test
    void aStoredExtractionIsNotReadAgain() {
        Lines structure = new Lines();
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);

        extractions.text(asset, store, NOW);
        extractions.text(asset, store, NOW);

        assertThat(structure.calls).hasValue(1);
        assertThat(extractions.stored(asset.sha256())).isPresent();
    }

    @Test
    void aTransientLimitIsNotStoredSoALaterRunTriesAgain() {
        Lines structure = new Lines().ending(Outcome.of(ExtractionStatus.FAILED, StatusReason.TIMEOUT));
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);

        assertThatThrownBy(() -> extractions.text(asset, store, NOW))
                .isInstanceOfSatisfying(DocumentTextException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentTextException.Reason.TIMEOUT));
        assertThat(extractions.stored(asset.sha256())).isEmpty();

        assertThatThrownBy(() -> extractions.text(asset, store, NOW)).isInstanceOf(DocumentTextException.class);
        assertThat(structure.calls).hasValue(2);
    }

    @Test
    void aPartialTimeoutLeavesNoEmptyTextBehind() {
        Lines structure = new Lines().ending(Outcome.truncated(StatusReason.TIMEOUT, new ReadExtent(2, 4)));
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);

        assertThatThrownBy(() -> extractions.text(asset, store, NOW))
                .isInstanceOfSatisfying(DocumentTextException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentTextException.Reason.TIMEOUT));

        assertThat(store.text(asset.sha256(), structure.id(), structure.version())).isEmpty();
        assertThat(extractions.stored(asset.sha256())).isEmpty();
    }

    @Test
    void peekReadsWithoutStoringAnything() {
        Lines structure = new Lines();
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\nzwei\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);

        AssetText text = extractions.peek(asset, store, NOW);

        assertThat(text.text()).isEqualTo("eins\n\nzwei");
        assertThat(extractions.stored(asset.sha256())).isEmpty();
        assertThat(store.text(asset.sha256(), structure.id(), structure.version())).isEmpty();

        extractions.text(asset, store, NOW);
        assertThat(extractions.peek(asset, store, NOW).text()).as("aus der Ablage").isEqualTo("eins\n\nzwei");
        assertThat(structure.calls).hasValue(2);
    }

    @Test
    void aPartAtATransientLimitKeepsTheContainerFromBeingStored() {
        Lines structure = new Lines().withPart(Outcome.of(ExtractionStatus.FAILED, StatusReason.TEMP_SPACE_LOW));
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> extractions.text(asset, store, NOW))
                    .isInstanceOf(DocumentTextException.class);
        }
        assertThat(structure.calls).hasValue(2);
        assertThat(extractions.stored(asset.sha256())).isEmpty();
    }

    @Test
    void aDocumentWithoutTextIsStoredWithItsStatusAndNotReadAgain() {
        Lines structure = new Lines().ending(Outcome.of(ExtractionStatus.UNSUPPORTED, StatusReason.NO_TEXT));
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> extractions.text(asset, store, NOW))
                    .isInstanceOfSatisfying(DocumentTextException.class,
                            e -> assertThat(e.reason()).isEqualTo(DocumentTextException.Reason.UNSUPPORTED));
        }
        assertThat(structure.calls).hasValue(1);
        assertThat(extractions.stored(asset.sha256()).orElseThrow().document().outcome().reason())
                .contains(StatusReason.NO_TEXT);
    }

    @Test
    void aPartialExtractionKeepsItsTextMarkedTruncated() {
        Lines structure = new Lines().ending(Outcome.truncated(StatusReason.CORRUPT, new ReadExtent(5, 4)));
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);

        AssetText text = extractions.text(asset, store, NOW);

        assertThat(text.text()).isEqualTo("eins");
        assertThat(text.truncated()).isTrue();
    }

    @Test
    void tableCellsBecomeTabsAndRowsLines() {
        Block.Table table = new Block.Table(0, Optional.empty(), List.of(), List.of(
                new Block.Cell(0, 0, "A", true, Optional.empty()), new Block.Cell(0, 1, "B", true, Optional.empty()),
                new Block.Cell(1, 0, "1", false, Optional.empty()), new Block.Cell(1, 1, "2", false, Optional.empty())),
                false);

        assertThat(DocumentExtractions.plain(table)).isEqualTo("A\tB\n1\t2");
    }

    private static Asset put(AssetStore store, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Asset asset = new Asset("wiki", "1", "att1", "1", "bericht.txt", "text/plain", AssetKind.OTHER, bytes.length,
                Assets.sha256(bytes), NOW);
        store.put(asset, bytes);
        return asset;
    }

    /** Ein Absatz je Zeile der Datei; zuletzt der eingestellte Status. */
    private static final class Lines implements DocumentStructure {

        final AtomicInteger calls = new AtomicInteger();
        boolean readFromFile;
        private Outcome ending;
        private List<EmbeddedEntry> directory = List.of();

        Lines withPart(Outcome outcome) {
            this.directory = List.of(new EmbeddedEntry(new PartId.Position("a".repeat(64), "/1"), Optional.empty(),
                    Optional.of("anhang.pdf"), Optional.of("application/pdf"), "/1", Optional.empty(), 1,
                    Optional.empty(), outcome));
            return this;
        }

        Lines ending(Outcome outcome) {
            this.ending = outcome;
            return this;
        }

        @Override
        public String id() {
            return "zeilen";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                          StructureSink sink) {
            calls.incrementAndGet();
            readFromFile = !original.getFileName().toString().startsWith("document-");
            try (BlockSink document = sink.document()) {
                long index = 0;
                long chars = 0;
                for (String line : Files.readAllLines(original, StandardCharsets.UTF_8)) {
                    document.accept(new Block.Paragraph(index++, Optional.empty(), List.of(), line));
                    chars += line.length();
                }
                Outcome outcome = ending != null ? ending : Outcome.ok(new ReadExtent(Files.size(original), chars));
                return new StructuredDocument(DocumentHead.EMPTY, outcome, directory, index, chars);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
