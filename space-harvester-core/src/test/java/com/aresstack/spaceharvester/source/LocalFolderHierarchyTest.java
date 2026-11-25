package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Eine Ordner-Quelle zeigt ihre Unterordner als Gliederung statt aller Dateien nebeneinander (Angelo, 24.11.2026). */
class LocalFolderHierarchyTest {

    @TempDir
    Path directory;

    @BeforeEach
    void folders() throws IOException {
        write("Handbuch/Einleitung.md", "Willkommen");
        write("Handbuch/Betrieb/Nachtlauf.md", "Ablauf in der Nacht");
        write("Handbuch/Betrieb/Stoerung.txt", "Was tun bei Störung");
        write("Handbuch-Anhang.md", "Steht zwischen den Ordnern, wenn man nach Pfad sortiert");
        write("Protokolle/2026/Januar.md", "Sitzung im Januar");
        write("notiz.txt", "Eine Datei an der Wurzel");
        write(".versteckt/geheim.md", "Nie lesen");
        Files.createDirectories(directory.resolve("Leer"));
    }

    @Test
    void theTreeShowsTheFoldersAsInTheExplorerInEveryBatchSize() {
        KnowledgeSourceHarvestSource source = open();
        for (int limit : List.of(1, 2, 500)) {
            Map<String, PageMetadata> pages = list(source, limit);

            assertThat(titles(pages)).as("Stapel " + limit).containsExactlyInAnyOrder("Handbuch", "Einleitung.md",
                    "Betrieb", "Nachtlauf.md", "Stoerung.txt", "Handbuch-Anhang.md", "Protokolle", "2026",
                    "Januar.md", "notiz.txt");
            PageTree tree = PageTreeBuilder.build(pages.values().stream()
                    .map(page -> new TreeEntry(page.pageId(), page.parentPageId())).toList());
            assertThat(tree.anomalies()).isEmpty();
            assertThat(tree.roots()).extracting(node -> pages.get(node.pageId()).title())
                    .containsExactlyInAnyOrder("Handbuch", "Handbuch-Anhang.md", "Protokolle", "notiz.txt");
            assertThat(tree.depth()).isEqualTo(3);
            assertThat(children(tree, pages, "Handbuch")).containsExactlyInAnyOrder("Betrieb", "Einleitung.md");
            assertThat(children(tree, pages, "Betrieb")).containsExactlyInAnyOrder("Nachtlauf.md", "Stoerung.txt");
            assertThat(children(tree, pages, "2026")).containsExactly("Januar.md");
        }
    }

    @Test
    void singlePassInventoryMatchesPagedMetadataAndChecksCancellationDuringTraversal() {
        KnowledgeSourceHarvestSource source = open();
        Map<String, PageMetadata> visited = new LinkedHashMap<>();
        java.util.concurrent.atomic.AtomicInteger checkpoints = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(source.visitInventory("docs", checkpoints::incrementAndGet,
                page -> {
                    assertThat(visited.put(page.pageId(), page)).isNull();
                })).isTrue();
        assertThat(visited.keySet()).containsExactlyInAnyOrderElementsOf(list(source, 1).keySet());
        visited.forEach((id, page) -> assertThat(page.attributes()).isEqualTo(source.pageMetadata(id).attributes()));
        assertThat(checkpoints.get()).isGreaterThanOrEqualTo(6);

        java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> source.visitInventory("docs", () -> {
            if (cancelled.incrementAndGet() == 3) {
                throw new java.util.concurrent.CancellationException();
            }
        }, page -> { })).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void aFolderIsMarkedAsFolderAndHasNoContent() {
        KnowledgeSourceHarvestSource source = open();

        PageMetadata folder = source.pageMetadata(id("Handbuch/Betrieb"));
        assertThat(folder.title()).isEqualTo("Betrieb");
        assertThat(folder.attributes()).containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FOLDER_KIND);
        assertThat(folder.parentPageId()).isEqualTo(id("Handbuch"));
        assertThat(source.storedPage(id("Handbuch/Betrieb")).payload()).isEmpty();

        PageMetadata file = source.pageMetadata(id("Handbuch/Betrieb/Nachtlauf.md"));
        assertThat(file.parentPageId()).isEqualTo(id("Handbuch/Betrieb"));
        assertThat(file.attributes()).containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND);
        assertThat(source.storedPage(id("Handbuch/Betrieb/Nachtlauf.md")).metadata().attributes())
                .containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND);
        assertThat(source.pageMetadata(id("notiz.txt")).attributes())
                .containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND);
        assertThat(list(source, 500).get(id("notiz.txt")).attributes())
                .containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND);
        assertThat(new String(source.storedPage(id("Handbuch/Betrieb/Nachtlauf.md")).payload(),
                StandardCharsets.UTF_8)).isEqualTo("Ablauf in der Nacht");
        assertThat(source.pageMetadata(id("notiz.txt")).parentPageId()).isNull();
    }

    @Test
    void browseOnlyDirectChildrenAndIncludeEmptyFoldersWithoutIndexing() throws IOException {
        KnowledgeSourceHarvestSource source = open();

        HarvestSource.ChildPages roots = source.children("docs", null, null);
        assertThat(roots.pages()).extracting(HarvestSource.PageNode::title)
                .containsExactlyInAnyOrder("Handbuch", "Handbuch-Anhang.md", "Leer", "Protokolle", "notiz.txt");
        assertThat(roots.pages()).filteredOn(node -> node.title().equals("Leer"))
                .allSatisfy(node -> assertThat(node.kind()).isEqualTo(HarvestSource.PageNode.Kind.FOLDER));
        assertThat(source.children("docs", id("Leer"), null).pages()).isEmpty();

        Files.createDirectories(directory.resolve("Handbuch/Neu"));
        assertThat(source.children("docs", id("Handbuch"), null).pages())
                .extracting(HarvestSource.PageNode::title).containsExactlyInAnyOrder("Betrieb", "Einleitung.md", "Neu");
        assertThat(source.children("docs", id("Handbuch/Betrieb"), null).pages())
                .extracting(HarvestSource.PageNode::title).containsExactlyInAnyOrder("Nachtlauf.md", "Stoerung.txt");
        assertThat(source.position(id("Handbuch/Betrieb/Nachtlauf.md")).ancestors())
                .extracting(HarvestSource.PageNode::title).containsExactly("Handbuch", "Betrieb");
    }

    @Test
    void paginateDirectChildrenWithoutLosingFolders() throws IOException {
        Files.createDirectories(directory.resolve("Viele"));
        for (int i = 0; i < 205; i++) {
            Files.createDirectory(directory.resolve("Viele/Ordner" + i));
        }
        KnowledgeSourceHarvestSource source = open();
        List<String> titles = new ArrayList<>();
        String cursor = null;
        do {
            HarvestSource.ChildPages batch = source.children("docs", id("Viele"), cursor);
            titles.addAll(batch.pages().stream().map(HarvestSource.PageNode::title).toList());
            cursor = batch.nextCursor();
        } while (cursor != null);
        assertThat(titles).hasSize(205).doesNotHaveDuplicates();
    }

    @Test
    void navigationRespectsExclusionsAndDocumentFilters() throws IOException {
        write("Handbuch/ignoriert.txt", "Excluded");
        Files.createDirectories(directory.resolve("Ausgeschlossen/Unterordner"));
        write("Ausgeschlossen/Unterordner/geheim.md", "Excluded");
        SourceSettingsConfig config = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString(), "include", "*.md", "exclude", "Ausgeschlossen/**"));
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("docs", "files", "docs",
                new LocalFilesSourceAdapter().open(config, null).port());

        assertThat(source.children("docs", null, null).pages())
                .extracting(HarvestSource.PageNode::title)
                .containsExactlyInAnyOrder("Handbuch", "Handbuch-Anhang.md", "Leer", "Protokolle");
        assertThat(source.children("docs", id("Handbuch"), null).pages())
                .extracting(HarvestSource.PageNode::title).containsExactlyInAnyOrder("Betrieb", "Einleitung.md");
    }

    @Test
    void disabledFilterIncludesUnknownEmptyAndLargeFilesInNavigationAndInventory() throws IOException {
        write("notes.one", "not a real section");
        write("unknown.zzz", "unknown");
        write("empty", "");
        Files.write(directory.resolve("large.bin"), new byte[2 * 1024 * 1024]);
        SourceSettingsConfig config = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString(), "include", "*.md", "includeEnabled", "false",
                        "maxFileMegabytes", "1"));
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("docs", "files", "docs",
                new LocalFilesSourceAdapter().open(config, null).port());
        assertThat(source.children("docs", null, null).pages()).extracting(HarvestSource.PageNode::title)
                .contains("notes.one", "unknown.zzz", "empty", "large.bin", "notiz.txt");
        assertThat(titles(list(source, 500))).contains("notes.one", "unknown.zzz", "empty", "large.bin");
        assertThat(source.storedPage(id("empty")).payload()).isEmpty();
        assertThat(source.storedPage(id("large.bin")).nativeFormat())
                .isEqualTo(com.aresstack.spaceharvester.application.snapshot.LargeFiles.FORMAT);
        SourceSettingsConfig filtered = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString(), "include", "*.zzz", "includeEnabled", "true"));
        KnowledgeSourceHarvestSource restricted = new KnowledgeSourceHarvestSource("docs", "files", "docs",
                new LocalFilesSourceAdapter().open(filtered, null).port());
        assertThat(titles(list(restricted, 500))).containsExactly("unknown.zzz");
    }

    @Test
    void completeInventoryDoesNotWrapAnEmptyCursor() {
        KnowledgeSourceHarvestSource source = open();
        PageMetadataBatch batch = source.pageMetadata("docs", null, 500);

        assertThat(batch.nextCursor()).isNull();
        assertThat(batch.pages()).isNotEmpty();
    }

    @Test
    void localVideoUsesTheSameMetadataAndBoundedRangesAsBscw() throws IOException {
        byte[] video = new byte[2 * 1024 * 1024];
        video[100] = 11;
        video[101] = 22;
        Files.write(directory.resolve("aufnahme.mp4"), video);
        SourceSettingsConfig config = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString(), "maxFileMegabytes", "1"));
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("docs", "files", "docs",
                new LocalFilesSourceAdapter().open(config, null).port());
        String id = id("aufnahme.mp4");
        assertThat(source.children("docs", null, null).pages()).extracting(HarvestSource.PageNode::title)
                .contains("aufnahme.mp4");
        assertThat(source.pageMetadata(id).attributes())
                .containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND)
                .containsEntry(PageMetadata.REMOTE_MEDIA, "true")
                .containsEntry(PageMetadata.REMOTE_MEDIA_TYPE, "video/mp4")
                .containsEntry("file.size", String.valueOf(video.length));
        assertThat(source.storedPage(id).nativeFormat()).isEqualTo("remote-media-metadata");
        assertThat(source.storedPage(id).payload()).isEmpty();
        assertThat(source.mediaRange(id, 100, 2)).containsExactly((byte) 11, (byte) 22);
        assertThat(source.mediaRange(id, video.length, 2)).isEmpty();
    }

    @Test
    void eligibleOriginalCanBeCopiedDirectlyWithoutUsingTheSnapshot() throws IOException {
        KnowledgeSourceHarvestSource source = open();
        Path target = Files.createTempFile("local-original-", ".txt");
        try {
            assertThat(source.downloadOriginal(id("notiz.txt"), target, type -> true, 1024)).isPresent();
            assertThat(Files.readString(target)).isEqualTo("Eine Datei an der Wurzel");
            assertThat(source.downloadOriginal(id("notiz.txt"), target, type -> false, 1024)).isEmpty();
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void largeLocalTextIsReadAtItsOriginalPathWithoutCreatingTemporaryCopies() throws IOException {
        HarvestSource source = open();
        String pageId = id("notiz.txt");
        PageMetadata metadata = source.pageMetadata(pageId);
        Path temporary = directory.resolve("must-not-be-created");
        LargeFileTexts texts = new LargeFileTexts(new TikaDocumentStructure(),
                () -> com.aresstack.spaceharvester.application.assets.ExtractionLimits.DEFAULTS, temporary);
        assertThat(source.readsOriginalInPlace()).isTrue();
        assertThat(source.readOriginalInPlace(pageId, type -> true, 1024,
                (path, actual, type) -> path)).contains(directory.resolve("notiz.txt").toRealPath());
        assertThat(texts.text(source, metadata, 1024)).contains("Eine Datei an der Wurzel");
        assertThat(temporary).doesNotExist();

        Files.writeString(directory.resolve("notiz.txt"), "Changed and longer content");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> texts.text(source, metadata, 1024))
                .isInstanceOf(com.aresstack.spaceharvester.application.sources.SourceAccessException.class);
        assertThat(temporary).doesNotExist();
    }

    @Test
    void sameSizeModificationChangesTheContentRevisionInInventoryAndSingleRead() throws IOException {
        KnowledgeSourceHarvestSource source = open();
        Path file = directory.resolve("notiz.txt");
        String pageId = id("notiz.txt");
        PageMetadata previous = source.pageMetadata(pageId);
        byte[] bytes = Files.readAllBytes(file);
        bytes[0] = 'X';
        Files.write(file, bytes);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(previous.modifiedAt().plusSeconds(5)));
        PageMetadata current = source.pageMetadata(pageId);
        assertThat(current.version()).isNotEqualTo(previous.version());
        assertThat(current.version()).isEqualTo(list(source, 500).get(pageId).version());
        assertThat(source.storedPage(pageId).metadata().version()).isEqualTo(current.version());
        Map<String, PageMetadata> inventory = new LinkedHashMap<>();
        source.visitInventory("docs", () -> { }, page -> inventory.put(page.pageId(), page));
        assertThat(inventory.get(pageId).version()).isEqualTo(current.version());
    }

    @Test
    void referenceStorageKeepsCompleteStructuredTextAndOnlyABoundedPreviewInTheSnapshot() throws IOException {
        KnowledgeSourceHarvestSource source = open();
        String pageId = id("notiz.txt");
        PageMetadata metadata = source.pageMetadata(pageId);
        Path storeDirectory = directory.resolve(".extraction-store");
        var store = new com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore(storeDirectory);
        var documents = new com.aresstack.spaceharvester.application.assets.DocumentExtractions(
                new TikaDocumentStructure(), store,
                com.aresstack.spaceharvester.application.assets.ExtractionLimits.DEFAULTS, 8);
        var storage = new com.aresstack.spaceharvester.application.snapshot.LocalOriginalStorage(
                new com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions(documents),
                LocalFilesTextProjector::readable);
        var snapshots = new com.aresstack.spaceharvester.application.snapshot.SourceSnapshots(catalog(source), storage);
        var first = snapshots.fetch("docs", metadata);
        var reference = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                .read(first.payload()).orElseThrow();

        assertThat(first.metadata()).isEqualTo(metadata);
        assertThat(first.payload().nativeFormat()).isEqualTo(
                com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference.FORMAT);
        assertThat(reference.originalSha256()).isNotEqualTo(first.payload().rawSha256());
        assertThat(reference.preview()).isEqualTo("Eine Dat");
        assertThat(reference.previewTruncated()).isTrue();
        assertThat(reference.status()).isEqualTo(
                com.aresstack.spaceharvester.application.assets.ExtractionStatus.OK);
        assertThat(reference.extractionKey()).isNotNull();
        assertThat(store.find(reference.extractionKey())).hasValueSatisfying(extraction -> {
            try (var sections = documents.indexSections(extraction)) {
                assertThat(sections.map(section -> section.text()).toList())
                        .containsExactly("Eine Datei an der Wurzel");
            }
        });
        assertThat(snapshots.fetch("docs", pageId).payload()).isEqualTo(first.payload());
        snapshots.maxTextBytes(() -> 1024);
        assertThat(new String(snapshots.original("docs", metadata), StandardCharsets.UTF_8))
                .isEqualTo("Eine Datei an der Wurzel");
        assertThat(Files.readString(directory.resolve("notiz.txt"))).isEqualTo("Eine Datei an der Wurzel");
        assertThat(new com.aresstack.spaceharvester.application.snapshot.SourceSnapshots(catalog(source))
                .fetch("docs", metadata).payload().nativeFormat()).isEqualTo("file");
    }

    @Test
    void aChangedOriginalCannotPublishItsStagedStructuredExtraction() throws IOException {
        KnowledgeSourceHarvestSource source = open();
        Path file = directory.resolve("notiz.txt");
        PageMetadata metadata = source.pageMetadata(id("notiz.txt"));
        String hash = new com.aresstack.spaceharvester.application.snapshot.NativePayload(
                Files.readAllBytes(file), "text/plain", "file").rawSha256();
        var store = new com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore(
                directory.resolve(".extraction-store"));
        var structure = new TikaDocumentStructure();
        var changing = new com.aresstack.spaceharvester.application.assets.DocumentStructure() {
            @Override
            public String id() {
                return structure.id();
            }

            @Override
            public String version() {
                return structure.version();
            }

            @Override
            public com.aresstack.spaceharvester.application.assets.StructuredDocument extract(
                    Path original, String title, String mediaType,
                    com.aresstack.spaceharvester.application.assets.ExtractionLimits limits,
                    com.aresstack.spaceharvester.application.assets.StructureSink sink) {
                var result = structure.extract(original, title, mediaType, limits, sink);
                try {
                    Files.writeString(original, "Changed during parsing");
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
                return result;
            }
        };
        var documents = new com.aresstack.spaceharvester.application.assets.DocumentExtractions(changing, store,
                com.aresstack.spaceharvester.application.assets.ExtractionLimits.DEFAULTS);
        var direct = new com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions(documents);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> direct.extract(source, metadata, type -> true))
                .isInstanceOf(com.aresstack.spaceharvester.application.sources.SourceAccessException.class);
        assertThat(store.find(documents.key(hash))).isEmpty();
    }

    @Test
    void referenceStrategyDoesNotChangeFolderOrVideoStorageAndMarksUnsupportedFiles() throws IOException {
        Files.write(directory.resolve("unknown.zzz"), new byte[] {1, 2, 3});
        Files.write(directory.resolve("aufnahme.mp4"), new byte[] {1, 2, 3});
        KnowledgeSourceHarvestSource source = open();
        var store = new com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore(
                directory.resolve(".extraction-store"));
        var storage = new com.aresstack.spaceharvester.application.snapshot.LocalOriginalStorage(
                new com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions(
                        new com.aresstack.spaceharvester.application.assets.DocumentExtractions(
                                new TikaDocumentStructure(), store,
                                com.aresstack.spaceharvester.application.assets.ExtractionLimits.DEFAULTS)),
                LocalFilesTextProjector::readable);
        var snapshots = new com.aresstack.spaceharvester.application.snapshot.SourceSnapshots(catalog(source), storage);
        assertThat(snapshots.fetch("docs", id("Handbuch")).payload().nativeFormat()).isEqualTo("local-folder");
        assertThat(snapshots.fetch("docs", id("aufnahme.mp4")).payload().nativeFormat())
                .isEqualTo("remote-media-metadata");
        var reference = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                .read(snapshots.fetch("docs", id("unknown.zzz")).payload()).orElseThrow();
        assertThat(reference.status()).isEqualTo(
                com.aresstack.spaceharvester.application.assets.ExtractionStatus.UNSUPPORTED);
        assertThat(reference.originalSha256()).isNull();
        assertThat(reference.preview()).isEmpty();
    }

    @Test
    void fullTextFindsTermsBeyondTheBoundedPreviewAndReplacesAllOldSections() throws IOException {
        Path original = directory.resolve("notiz.txt");
        Files.writeString(original, "Beginning\n" + "ordinary paragraph\n\n".repeat(7000) + "Zielbegriff");
        KnowledgeSourceHarvestSource source = open();
        String pageId = id("notiz.txt");
        var store = new com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore(
                directory.resolve(".extraction-store"));
        var documents = new com.aresstack.spaceharvester.application.assets.DocumentExtractions(
                new TikaDocumentStructure(), store,
                com.aresstack.spaceharvester.application.assets.ExtractionLimits.DEFAULTS, 8);
        var storage = new com.aresstack.spaceharvester.application.snapshot.LocalOriginalStorage(
                new com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions(documents),
                LocalFilesTextProjector::readable);
        var snapshots = new com.aresstack.spaceharvester.application.snapshot.SourceSnapshots(catalog(source), storage);
        var first = snapshots.fetch("docs", pageId);
        assertThat(com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference.read(first.payload())
                .orElseThrow().preview()).doesNotContain("Zielbegriff");
        try (var index = com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex.open(
                directory.resolve(".index"))) {
            var texts = new com.aresstack.spaceharvester.application.query.PageTextIndexing(List.of(), index,
                    new com.aresstack.spaceharvester.application.query.LocalPageSections(documents));
            assertThat(texts.index(first)).isTrue();
            var hits = index.search("Zielbegriff", java.util.Set.of("docs"),
                    com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).pageId()).isEqualTo(pageId);
            assertThat(hits.get(0).attachmentId()).isNull();
            assertThat(texts.index(first)).isFalse();
            index.putAttachment(new com.aresstack.spaceharvester.application.query.PageTextIndex.AttachmentEntry(
                    first.key(), first.spaceKey(), "real-attachment", "Attachment", "Anhangbegriff", "stand"));
            Files.writeString(original, "Neuerbegriff");
            assertThat(texts.index(snapshots.fetch("docs", pageId))).isTrue();
            assertThat(index.search("Zielbegriff", java.util.Set.of("docs"),
                    com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 10)).isEmpty();
            assertThat(index.search("Neuerbegriff", java.util.Set.of("docs"),
                    com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 10)).hasSize(1);
            assertThat(index.search("Anhangbegriff", java.util.Set.of("docs"),
                    com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 10)).hasSize(1);
        }
    }

    @Test
    void localDocumentAnalysesUseAllStoredSectionsWithoutSyntheticAttachments() throws IOException {
        Files.writeString(directory.resolve("notiz.txt"),
                "Beginning\n" + "ordinary paragraph\n\n".repeat(7000) + "Zielbegriff");
        KnowledgeSourceHarvestSource source = open();
        var store = new com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore(
                directory.resolve(".extraction-store"));
        var documents = new com.aresstack.spaceharvester.application.assets.DocumentExtractions(
                new TikaDocumentStructure(), store,
                com.aresstack.spaceharvester.application.assets.ExtractionLimits.DEFAULTS, 8);
        var strategy = new com.aresstack.spaceharvester.application.snapshot.LocalOriginalStorage(
                new com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions(documents),
                LocalFilesTextProjector::readable);
        var snapshots = new com.aresstack.spaceharvester.application.snapshot.SourceSnapshots(catalog(source), strategy);
        var current = new java.util.concurrent.atomic.AtomicReference<>(
                snapshots.fetch("docs", id("notiz.txt")));
        var results = new com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore();
        var input = new ArrayList<String>();
        var processor = new com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor() {
            public String id() { return "section-test"; }
            public String version() { return "1"; }
            public boolean documents() { return true; }
            public com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput process(
                    com.aresstack.spaceharvester.application.enrichment.PageText text) {
                input.add(text.text());
                return com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput.of("{}");
            }
        };
        var enrichments = new com.aresstack.spaceharvester.application.enrichment.Enrichments(
                snapshot -> java.util.Optional.empty(), List.of(processor), results, java.time.Clock.systemUTC());
        try (var analyses = new com.aresstack.spaceharvester.application.enrichment.DocumentEnrichments(
                enrichments, null, () -> true)) {
            analyses.localDocuments(key -> java.util.Optional.of(current.get()).filter(page -> page.key().equals(key)),
                    documents);
            analyses.indexedLocal(current.get());
            var key = com.aresstack.spaceharvester.application.assets.DocumentKey.localOriginal(current.get().key());
            assertThat(input).anyMatch(text -> text.contains("Zielbegriff"));
            assertThat(results.find(key)).hasSizeGreaterThan(1);
            var found = results.find(key).getLast();
            assertThat(found.document().attachmentId()).isEmpty();
            assertThat(analyses.sectionText(key, found.section(), found.inputSha256(), found.extraction()))
                    .hasValueSatisfying(text -> assertThat(text.text()).contains("Zielbegriff"));
            int calls = input.size();
            analyses.indexedLocal(current.get());
            assertThat(input).hasSize(calls);
            Files.writeString(directory.resolve("notiz.txt"), "New revision");
            current.set(snapshots.fetch("docs", id("notiz.txt")));
            analyses.indexedLocal(current.get());
            assertThat(results.find(key)).hasSize(1);
            assertThat(analyses.sectionText(key, found.section(), found.inputSha256(), found.extraction())).isEmpty();
        }
    }

    private static com.aresstack.spaceharvester.application.sources.SourceCatalog catalog(HarvestSource source) {
        return new com.aresstack.spaceharvester.application.sources.SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public java.util.Optional<HarvestSource> find(String sourceId) {
                return java.util.Optional.of(source).filter(item -> item.id().equals(sourceId));
            }
        };
    }

    private Map<String, PageMetadata> list(KnowledgeSourceHarvestSource source, int limit) {
        Map<String, PageMetadata> pages = new LinkedHashMap<>();
        String cursor = null;
        do {
            PageMetadataBatch batch = source.pageMetadata("docs", cursor, limit);
            for (PageMetadata page : batch.pages()) {
                assertThat(pages.put(page.pageId(), page)).as("einmal: " + page.title()).isNull();
                if (page.parentPageId() != null) {
                    assertThat(pages).as("Ordner vor seinem Inhalt: " + page.title())
                            .containsKey(page.parentPageId());
                }
            }
            cursor = batch.nextCursor();
        } while (cursor != null);
        return pages;
    }

    private static List<String> titles(Map<String, PageMetadata> pages) {
        return pages.values().stream().map(PageMetadata::title).toList();
    }

    private static List<String> children(PageTree tree, Map<String, PageMetadata> pages, String title) {
        List<PageTree.Node> open = new ArrayList<>(tree.roots());
        while (!open.isEmpty()) {
            PageTree.Node node = open.removeFirst();
            if (pages.get(node.pageId()).title().equals(title)) {
                return node.children().stream().map(child -> pages.get(child.pageId()).title()).toList();
            }
            open.addAll(node.children());
        }
        throw new AssertionError("Kein Knoten " + title);
    }

    private KnowledgeSourceHarvestSource open() {
        SourceSettingsConfig config = new SourceSettingsConfig("docs", "files", null, null, null,
                Map.of("directory", directory.toString()));
        return new KnowledgeSourceHarvestSource("docs", "files", "docs",
                new LocalFilesSourceAdapter().open(config, null).port());
    }

    private void write(String relative, String text) throws IOException {
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String id(String relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
    }
}
