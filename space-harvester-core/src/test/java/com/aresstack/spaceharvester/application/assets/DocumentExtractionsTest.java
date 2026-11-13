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
import java.util.Objects;
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

    /**
     * AP7c: Musste die Quelle beim Erzeugen der Datei Teile auslassen (angehängtes Outlook-Element), ist das vollständig
     * gelesene Dokument PARTIAL/entries-not-readable und sein Text gekürzt; dieselben Bytes ohne Angabe bleiben OK, und
     * die geteilte Extraktion bleibt unverändert. Der Eintrag im Asset-Speicher behält die Angabe.
     */
    @Test
    void partsTheSourceLeftOutMakeTheDocumentPartial() {
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset complete = put(store, "Mail\n");
        Asset shortened = new Asset(complete.sourceId(), complete.pageId(), "att2", "1", "element.eml",
                "message/rfc822", AssetKind.OTHER, complete.size(), complete.sha256(), NOW, 2);
        store.put(shortened, "Mail\n".getBytes(StandardCharsets.UTF_8));
        DocumentExtractions extractions = new DocumentExtractions(new Lines(),
                new FileSystemExtractionStore(directory), ExtractionLimits.DEFAULTS);

        AssetText text = extractions.text(shortened, store, NOW);

        assertThat(text.truncated()).isTrue();
        assertThat(extractions.outcome(shortened)).hasValueSatisfying(outcome -> {
            assertThat(outcome.status()).isEqualTo(ExtractionStatus.PARTIAL);
            assertThat(outcome.reason()).contains(StatusReason.ENTRIES_NOT_READABLE);
            assertThat(outcome.truncated()).isTrue();
        });
        assertThat(extractions.outcome(complete)).hasValueSatisfying(outcome ->
                assertThat(outcome.status()).isEqualTo(ExtractionStatus.OK));
        assertThat(extractions.text(complete, store, NOW).truncated()).isFalse();
        assertThat(store.find("wiki", "att2", "1")).hasValueSatisfying(asset ->
                assertThat(asset.partsWithoutFile()).isEqualTo(2));
        assertThat(DocumentExtractions.withSource(Outcome.of(ExtractionStatus.FAILED, StatusReason.CORRUPT),
                shortened).reason()).as("ein Grund je Status").contains(StatusReason.CORRUPT);
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
    void markdownComesFromTheSameExtractionAsTheText() {
        Lines structure = new Lines();
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "eins *fett*\nzwei\n");
        DocumentExtractions extractions = new DocumentExtractions(structure, new FileSystemExtractionStore(directory),
                ExtractionLimits.DEFAULTS);
        StringBuilder markdown = new StringBuilder();

        extractions.text(asset, store, NOW);
        Outcome outcome = extractions.markdown(asset, store, markdown);

        assertThat(markdown).hasToString("eins \\*fett\\*\n\nzwei\n");
        assertThat(outcome.status()).isEqualTo(ExtractionStatus.OK);
        assertThat(structure.calls).as("einmal gelesen, zwei Ansichten").hasValue(1);
    }

    @Test
    void partsHaveTheirOwnStorageTheTextStillContainsThemOnceAndMarkdownShowsOnlyTheDocument() {
        String sha = "cd".repeat(32);
        DocumentStructure withParts = new DocumentStructure() {
            @Override
            public String id() {
                return "teile";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                              StructureSink sink) {
                try (BlockSink document = sink.document()) {
                    document.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "Mailtext"));
                }
                List<EmbeddedEntry> entries = new java.util.ArrayList<>();
                for (int ordinal = 1; ordinal <= 2; ordinal++) {
                    EmbeddedEntry entry = new EmbeddedEntry(new PartId.Content(sha, ordinal), Optional.of(sha),
                            Optional.of("kopie-" + ordinal + ".txt"), Optional.of("text/plain"), "/" + ordinal,
                            Optional.empty(), 1, Optional.of(7L), Outcome.ok(new ReadExtent(7, 7)));
                    entries.add(entry);
                    try (PartSink part = sink.part(entry)) {
                        part.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "Anhang"));
                        part.head(new DocumentHead(Optional.of("Anhang"), Optional.empty(), Optional.empty(),
                                List.of(), Optional.empty()));
                    }
                }
                return new StructuredDocument(DocumentHead.EMPTY, Outcome.ok(new ReadExtent(10, 8)), entries, 1, 8);
            }
        };
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "egal\n");
        FileSystemExtractionStore stored = new FileSystemExtractionStore(directory);
        DocumentExtractions extractions = new DocumentExtractions(withParts, stored, ExtractionLimits.DEFAULTS);

        AssetText text = extractions.text(asset, store, NOW);
        StringBuilder markdown = new StringBuilder();
        extractions.markdown(asset, store, markdown);

        assertThat(text.text()).isEqualTo("Mailtext\n\nAnhang");
        assertThat(markdown).hasToString("Mailtext\n");
        StoredExtraction extraction = extractions.stored(asset.sha256()).orElseThrow();
        assertThat(extraction.chars()).as("nur das Dokument selbst").isEqualTo(8);
        assertThat(stored.parts(extraction.key())).singleElement().satisfies(part -> {
            assertThat(part.key()).isEqualTo(sha);
            assertThat(part.blocks()).as("Blöcke nicht doppelt").isEqualTo(1);
            assertThat(part.head().title()).contains("Anhang");
        });
    }

    /** Codex #715: Ein abgewiesener erster Teil belegt die Ablage nicht; der lesbare gleiche Teil füllt sie. */
    @Test
    void aRefusedPartLeavesTheStorageToAReadableCopy() {
        String sha = "ef".repeat(32);
        DocumentStructure refusedFirst = new DocumentStructure() {
            @Override
            public String id() {
                return "teile";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                              StructureSink sink) {
                try (BlockSink document = sink.document()) {
                    document.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "Mailtext"));
                }
                EmbeddedEntry refused = new EmbeddedEntry(new PartId.Content(sha, 1), Optional.of(sha),
                        Optional.of("gross.bin"), Optional.of("application/octet-stream"), "/1", Optional.empty(), 1,
                        Optional.of(7L), Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.MEMORY_LIMIT));
                try (PartSink part = sink.part(refused)) {
                    Objects.requireNonNull(part);
                }
                EmbeddedEntry readable = new EmbeddedEntry(new PartId.Content(sha, 2), Optional.of(sha),
                        Optional.of("gross.pdf"), Optional.of("application/pdf"), "/2", Optional.empty(), 1,
                        Optional.of(7L), Outcome.ok(new ReadExtent(7, 7)));
                try (PartSink part = sink.part(readable)) {
                    part.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "Anhang"));
                }
                return new StructuredDocument(DocumentHead.EMPTY, Outcome.ok(new ReadExtent(10, 8)),
                        List.of(refused, readable), 1, 8);
            }
        };
        FileSystemAssetStore store = new FileSystemAssetStore(directory);
        Asset asset = put(store, "egal\n");
        DocumentExtractions extractions = new DocumentExtractions(refusedFirst,
                new FileSystemExtractionStore(directory), ExtractionLimits.DEFAULTS);

        assertThat(extractions.text(asset, store, NOW).text()).isEqualTo("Mailtext\n\nAnhang");
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
