package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.source.api.AttachmentListSource;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class BscwWebDavSourceTest {

    @org.junit.jupiter.api.Test
    void aVideoWithoutExtensionIsStreamedByItsReportedContentType() {
        assertThat(BscwWebDavKnowledgeSource.streamableMediaType("2023_Roadmapplugin_JIRA_How_to", "video/mp4"))
                .isEqualTo("video/mp4");
        assertThat(BscwWebDavKnowledgeSource.streamableMediaType("Aufnahme.MP4", null)).isEqualTo("video/mp4");
        assertThat(BscwWebDavKnowledgeSource.streamableMediaType("clip", "video/webm; codecs=vp9"))
                .isEqualTo("video/webm");
        assertThat(BscwWebDavKnowledgeSource.streamableMediaType("Bericht", "application/pdf")).isNull();
        assertThat(BscwWebDavKnowledgeSource.streamableMediaType("ohne", null)).isNull();
    }


    private static final byte[] ZIP_CONTENT = new byte[] {0x50, 0x4b, 0x03, 0x04, 0, 0};
    private static final byte[] TEXT_CONTENT = "Example BSCW document".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private AtomicInteger downloads;
    private AtomicInteger propfinds;
    private java.util.concurrent.atomic.AtomicBoolean denyNested;
    /** Ob der Server für readme.txt HTTP-Range beantwortet. */
    private final java.util.concurrent.atomic.AtomicBoolean rangesForText =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    /** So oft antwortet der Server auf das Stück ab Byte 5 von readme.txt mit 503 (abgerissenes Stück). */
    private final AtomicInteger failSecondChunk = new AtomicInteger();
    private final java.util.List<String> textRanges = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private String root;

    @TempDir
    Path data;

    @BeforeEach
    void startWebDavServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downloads = new AtomicInteger();
        propfinds = new AtomicInteger();
        denyNested = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/sec/bscw.cgi/", this::handle);
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort() + "/sec/bscw.cgi/146377797";
    }

    @AfterEach
    void stopWebDavServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void navigatesFoldersLazilyAndKeepsFilesAsLeafNodes() throws Exception {
        KnowledgeSourcePort port = open("reader", "secret");
        PageNavigationSource navigation = (PageNavigationSource) port;

        NavigationChildren rootChildren = navigation.children(
                NavigationNode.root("space:bscw-test", "BSCW", "bscw-test", NavigationNode.Children.YES), "");
        assertThat(rootChildren.nodes()).extracting(NavigationNode::title)
                .containsExactly("Nested", "readme.txt");
        assertThat(rootChildren.nodes().get(0).children()).isEqualTo(NavigationNode.Children.YES);
        assertThat(((ResourceNodeKindSource) port).kind(rootChildren.nodes().get(0).resource()))
                .isEqualTo(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind.FOLDER);
        assertThat(rootChildren.nodes().get(1).children()).isEqualTo(NavigationNode.Children.NO);
        assertThat(((ResourceNodeKindSource) port).kind(rootChildren.nodes().get(1).resource()))
                .isEqualTo(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind.FILE);
        assertThat(propfinds).hasValue(1);
        assertThat(downloads).hasValue(0);

        NavigationChildren folderChildren = navigation.children(rootChildren.nodes().get(0), "");
        assertThat(folderChildren.nodes()).extracting(NavigationNode::title).containsExactly("archive.zip");
        assertThat(propfinds).hasValue(2);
        assertThat(downloads).hasValue(0);
        assertThat(((ResourceNodeKindSource) port).kind(folderChildren.nodes().get(0).resource()))
                .isEqualTo(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind.FILE);

        assertThat(((ResourceMetadataSource) port).resourceMetadata(
                ((ResourceMetadataSource) port).localId(rootChildren.nodes().get(0).resource()))
                .contentType()).isEqualTo("inode/directory");
        assertThat(navigation.position(folderChildren.nodes().get(0).resource()).ancestors())
                .extracting(NavigationNode::title).containsExactly("Nested");
    }

    @Test
    void canonicalizesTrailingSlashesWithoutDoubleEncodingEscapedPaths() {
        URI escaped = URI.create("https://example.org/sec/bscw.cgi/a%20b%2Fc/");
        URI canonical = BscwWebDavKnowledgeSource.normalize(escaped);

        assertThat(canonical.toASCIIString())
                .isEqualTo("https://example.org/sec/bscw.cgi/a%20b%2Fc");
        assertThat(BscwWebDavKnowledgeSource.normalize(canonical)).isEqualTo(canonical);
        assertThat(BscwWebDavKnowledgeSource.normalize(
                URI.create("https://example.org/sec/bscw.cgi/a%20b%2Fc")))
                .isEqualTo(canonical);
    }

    @Test
    void titleLookupUsesTheOnlineSearchAndKnownWebDavEntriesWithoutCrawling() throws Exception {
        KnowledgeSourcePort port = open("reader", "secret");
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource(
                "bscw-test", "bscw", "BSCW", port);
        PageNavigationSource navigation = (PageNavigationSource) port;

        // Seit der Online-Suche (27.11.2026) findet die Titelsuche auch nie aufgeklappte Dateien, ohne den Baum zu
        // durchlaufen (keine PROPFIND-Anfrage), über die Namenssuche von BSCW.
        assertThat(source.findByTitle("readme", com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 5))
                .extracting(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::title)
                .containsExactly("readme.txt");
        assertThat(searchForms).singleElement().satisfies(form -> assertThat(form).contains("qsmode=q_search_all"));
        assertThat(propfinds).hasValue(0);

        navigation.children(NavigationNode.root("space:bscw-test", "BSCW", "bscw-test",
                NavigationNode.Children.YES), "");

        assertThat(source.findByTitle("README", com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 5))
                .extracting(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::title)
                .containsExactly("readme.txt");
        assertThat(source.findByTitle("README", com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 5))
                .extracting(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::kind)
                .containsExactly(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind.FILE);
        assertThat(source.findByTitle("Nested",
                com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 5))
                .extracting(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::title)
                .containsExactly("Nested");
        assertThat(source.findByTitle("Nested",
                com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 5))
                .extracting(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::kind)
                .containsExactly(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind.FOLDER);
        assertThat(source.findByTitle("not-found",
                com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 5))
                .isEmpty();
        assertThat(propfinds).hasValue(1);
        assertThat(downloads).hasValue(0);
    }

    @Test
    void usesTheSharedBaseUrlToOpenBscwWithoutLegacyRootUrl() {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW",
                URI.create(root),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("maxDepth", "2"));

        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            com.aresstack.spaceharvester.application.navigation.PageNavigation.Children children =
                    harvester.navigation().children(harvester.caller(), "bscw-test", "bscw-test", null, null);
            assertThat(children.pages())
                    .extracting(com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::title)
                    .containsExactly("Nested", "readme.txt");
            assertThat(children.pages()).extracting(
                    com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::kind)
                    .containsExactly(
                            com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind.FOLDER,
                            com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode.Kind.FILE);
            assertThat(downloads).hasValue(0);
        }
    }

    @Test
    void coreNavigationLoadsOnlyTheCurrentWebDavFolder() {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root));

        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            com.aresstack.spaceharvester.application.navigation.PageNavigation.Children top =
                    harvester.navigation().children(harvester.caller(), "bscw-test", "bscw-test", null, null);
            assertThat(top.pages()).extracting(
                    com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::title)
                    .containsExactly("Nested", "readme.txt");
            assertThat(propfinds).hasValue(1);
            assertThat(downloads).hasValue(0);

            com.aresstack.spaceharvester.application.navigation.PageNavigation.Children subfolder =
                    harvester.navigation().children(harvester.caller(), "bscw-test", "bscw-test",
                            top.pages().get(0).pageId(), null);
            assertThat(subfolder.pages()).extracting(
                    com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode::title)
                    .containsExactly("archive.zip");
            assertThat(propfinds).hasValue(2);
            assertThat(downloads).hasValue(0);
        }
    }

    @Test
    void videoSelectionStoresOnlyMetadataAndReadsOnlyRequestedRange() throws Exception {
        String videoRoot = root.replace("/146377797", "/888");
        String videoUrl = videoRoot.replace("/888", "/779");
        String videoId = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(videoUrl.getBytes(StandardCharsets.UTF_8));
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", videoRoot));

        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "bscw-test", videoId,
                    false).snapshot();

            assertThat(snapshot.metadata().attributes())
                    .containsEntry(com.aresstack.spaceharvester.application.sources.PageMetadata.REMOTE_MEDIA, "true");
            assertThat(snapshot.payload().nativeFormat()).isEqualTo("remote-media-metadata");
            assertThat(snapshot.payload().length()).isZero();
            assertThat(downloads).hasValue(0);

            assertThat(harvester.mediaRange("bscw-test", videoId, 2, 4))
                    .isEqualTo("2345".getBytes(StandardCharsets.US_ASCII));
            assertThat(downloads).hasValue(1);
        }
    }

    @Test
    void inventoryPagesDoNotScanUnopenedSubfolders() throws Exception {
        PartitionListingSource listing = (PartitionListingSource) open("reader", "secret");
        PartitionResources first = listing.resources("bscw-test", null, 20);
        assertThat(first.resources()).extracting(KnowledgeResource::title)
                .containsExactly("Root", "Nested", "readme.txt");
        assertThat(first.complete()).isFalse();
        int firstRequests = propfinds.get();

        PartitionResources repeated = listing.resources("bscw-test",
                first.nextCursor().replaceFirst("\\.1$", ".0"), 20);
        assertThat(repeated.resources()).extracting(KnowledgeResource::title)
                .containsExactly("Root", "Nested", "readme.txt");
        assertThat(propfinds.get()).isEqualTo(firstRequests);

        PartitionResources nested = listing.resources("bscw-test", first.nextCursor(), 20);
        assertThat(nested.resources()).extracting(KnowledgeResource::title)
                .containsExactly("archive.zip");
        assertThat(nested.complete()).isTrue();
        assertThat(propfinds.get()).isGreaterThan(firstRequests);
        assertThat(downloads).hasValue(0);
    }

    @Test
    void inaccessibleNestedFolderNeverConfirmsAnIncompleteInventory() {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW",
                URI.create(root),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("maxDepth", "2"));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            com.aresstack.spaceharvester.application.sync.InventoryCollector collector =
                    new com.aresstack.spaceharvester.application.sync.InventoryCollector(
                            harvester.sources(), java.time.Clock.systemUTC());
            com.aresstack.spaceharvester.application.sync.Inventory complete =
                    collector.collect("bscw-test", "bscw-test");
            assertThat(complete.confirmed()).isTrue();

            denyNested.set(true);
            com.aresstack.spaceharvester.application.sync.Inventory partial =
                    collector.collect("bscw-test", "bscw-test");
            assertThat(partial.status())
                    .isEqualTo(com.aresstack.spaceharvester.application.sync.Inventory.Status.INCOMPLETE);
            assertThat(partial.pages()).extracting(entry -> entry.page().title())
                    .contains("Root", "Nested", "readme.txt")
                    .doesNotContain("archive.zip");
            assertThat(partial.spaceFingerprint()).isEmpty();
        }
    }

    @Test
    void inventoriedFoldersCanBeCancelledBeforeNextRequest() {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW",
                URI.create(root),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("maxDepth", "2"));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            AtomicInteger checkpoints = new AtomicInteger();
            assertThatThrownBy(() -> new com.aresstack.spaceharvester.application.sync.InventoryCollector(
                    harvester.sources(), java.time.Clock.systemUTC()).collect("bscw-test", "bscw-test", () -> {
                if (checkpoints.incrementAndGet() == 2) {
                    throw new IllegalStateException("cancelled");
                }
            })).isInstanceOf(IllegalStateException.class).hasMessage("cancelled");
            assertThat(checkpoints).hasValue(2);
            assertThat(downloads).hasValue(0);
        }
    }

    @Test
    void readsCsvWithGenericWebDavContentTypeAsText() throws Exception {
        String csvRoot = root.replace("/146377797", "/888");
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", csvRoot));
        String csvUrl = csvRoot.replace("/888", "/777");
        String localId = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(csvUrl.getBytes(StandardCharsets.UTF_8));

        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "bscw-test", localId,
                    false).snapshot();
            assertThat(snapshot.metadata().title()).isEqualTo("metrics.csv");
            assertThat(snapshot.payload().mediaType()).isEqualTo("text/csv");
            assertThat(harvester.pageText(snapshot)).map(PageText::text)
                    .hasValueSatisfying(text -> assertThat(text).contains("first,second", "abc,def"));
            com.aresstack.spaceharvester.application.reading.ReadingView view =
                    harvester.readingViews().view(harvester.caller(), "bscw-test", localId, false);
            assertThat(view.status()).isEqualTo(
                    com.aresstack.spaceharvester.application.reading.ReadingView.Status.AVAILABLE);
            assertThat(view.html()).contains("first,second", "abc,def");
        }
    }

    @Test
    void listsNestedFilesWithoutDownloadingAndReturnsOriginalBytes() throws Exception {
        KnowledgeSourcePort port = open("reader", "secret");
        PartitionListingSource listing = (PartitionListingSource) port;
        PartitionResources first = listing.resources("bscw-test", null, 1);
        assertThat(first.resources()).hasSize(1);
        assertThat(first.complete()).isFalse();
        List<KnowledgeResource> inventory = allResources(listing);
        assertThat(inventory).extracting(KnowledgeResource::title)
                .containsExactlyInAnyOrder("Root", "Nested", "archive.zip", "readme.txt");
        assertThat(downloads).hasValue(0);

        KnowledgeResource document = inventory.stream()
                .filter(resource -> "archive.zip".equals(resource.title())).findFirst().orElseThrow();
        String id = ((StoredContentSource) port).localId(document.id());
        KnowledgeResource metadata = ((ResourceMetadataSource) port).resourceMetadata(id);
        assertThat(metadata.id()).isEqualTo(document.id());
        StoredContent original = ((StoredContentSource) port).storedContent(id);
        assertThat(original.nativeFormat()).isEqualTo("file");
        assertThat(original.payload()).isEqualTo(ZIP_CONTENT);
        assertThat(downloads).hasValue(1);

        KnowledgeResource readme = inventory.stream()
                .filter(resource -> "readme.txt".equals(resource.title())).findFirst().orElseThrow();
        KnowledgeDocument text = port.load(readme.id());
        assertThat(text.text()).contains("Example BSCW document");
        assertThat(readme.contentType()).isEqualTo("text/plain");
    }

    @Test
    void bscwFilesAreNativePagesNotSyntheticAttachments() throws Exception {
        KnowledgeSourcePort source = open("reader", "secret");
        PageNavigationSource navigation = (PageNavigationSource) source;
        ResourceMetadataSource metadata = (ResourceMetadataSource) source;
        NavigationNode nested = navigation.children(NavigationNode.root("space:bscw-test", "BSCW",
                "bscw-test", NavigationNode.Children.YES), "").nodes().get(0);
        NavigationNode archive = navigation.children(nested, "").nodes().get(0);

        assertThat(source).isNotInstanceOf(AttachmentListSource.class);
        assertThat(((PageViewSource) source).attachmentsOf(nested.resource())).isEmpty();
        assertThat(((PageViewSource) source).attachmentsOf(archive.resource())).isEmpty();
        assertThat(((PageViewSource) source).isAttachment(archive.resource())).isFalse();
        assertThat(downloads).hasValue(0);

        String local = metadata.localId(archive.resource());
        StoredContent original = ((StoredContentSource) source).storedContent(local);
        assertThat(original.payload()).isEqualTo(ZIP_CONTENT);
        assertThat(original.nativeFormat()).isEqualTo("file");
        assertThat(downloads).hasValue(1);
    }

    @Test
    void coreKeepsBscwFileOriginalInOnePageSnapshot() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW",
                URI.create(root),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("maxDepth", "2"));

        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            List<com.aresstack.spaceharvester.application.sources.PageMetadataListing.Entry> allEntries =
                    new java.util.ArrayList<>();
            String cursor = null;
            do {
                com.aresstack.spaceharvester.application.sources.PageMetadataListing batch =
                        harvester.sources().pageMetadata("bscw-test", "bscw-test", cursor, 20);
                allEntries.addAll(batch.entries());
                cursor = batch.nextCursor();
            } while (cursor != null);

            String archivePageId = allEntries.stream()
                    .filter(entry -> "archive.zip".equals(entry.page().title()))
                    .findFirst().orElseThrow().page().pageId();
            com.aresstack.spaceharvester.application.sources.HarvestSource source =
                    harvester.sources().sources().stream()
                            .filter(candidate -> "bscw-test".equals(candidate.id()))
                            .findFirst().orElseThrow();
            assertThat(source.capabilities()).doesNotContain(
                    com.aresstack.spaceharvester.application.sources.SourceCapability.PAGE_ATTACHMENTS);
            assertThatThrownBy(() -> harvester.sources().attachments("bscw-test", archivePageId))
                    .isInstanceOf(com.aresstack.spaceharvester.application.sources.SourceAccessException.class);
            assertThat(downloads).hasValue(0);

            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "bscw-test", archivePageId, false)
                    .snapshot();
            assertThat(snapshot.payload().bytes()).isEqualTo(ZIP_CONTENT);
            assertThat(snapshot.payload().nativeFormat()).isEqualTo("file");
            assertThat(downloads).hasValue(1);
        }
    }

    @Test
    void jsonExportDoesNotFetchTheSameBscwOriginalAgainAsAnAttachment() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW",
                URI.create(root),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("maxDepth", "2"));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            com.aresstack.spaceharvester.application.jobs.JobStatus exported = harvester.await(
                    harvester.export("bscw-test", "bscw-test", List.of("json")).id(),
                    Duration.ofSeconds(30));
            assertThat(exported.state()).isEqualTo(
                    com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
            assertThat(harvester.exportArtifact(exported.id())).hasValueSatisfying(
                    artifact -> assertThat(artifact.complete()).isTrue());
            int originalDownloads = downloads.get();
            assertThat(originalDownloads).isEqualTo(2);
            try (java.io.InputStream pageIndex = harvester.exports()
                    .artifactFile(exported.id(), Harvester.OWNER, "pages.ndjson").orElseThrow()) {
                String exportedPages = new String(pageIndex.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(exportedPages).contains("archive.zip");
                assertThat(exportedPages).contains(com.aresstack.spaceharvester.application.assets.Assets
                        .sha256(ZIP_CONTENT));
            }
            List<com.aresstack.spaceharvester.application.jobs.JobStatus> attachments =
                    harvester.jobs().list(Harvester.OWNER, JobType.ATTACHMENTS);
            assertThat(attachments).hasSize(1);
            com.aresstack.spaceharvester.application.jobs.JobStatus collected = harvester.await(
                    attachments.get(0).id(), Duration.ofSeconds(30));
            assertThat(collected.state()).isEqualTo(
                    com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
            assertThat(downloads).hasValue(originalDownloads);
        }
    }

    @Test
    void failedExportCannotPublishPartialTreeOrDeletePreviousCompletedExport() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW",
                URI.create(root),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("maxDepth", "2"));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            com.aresstack.spaceharvester.application.jobs.JobStatus first = harvester.await(
                    harvester.export("bscw-test", "bscw-test", List.of("json")).id(),
                    Duration.ofSeconds(30));
            assertThat(first.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);

            denyNested.set(true);
            com.aresstack.spaceharvester.application.jobs.JobStatus refreshed = harvester.await(
                    harvester.export("bscw-test", "bscw-test", List.of("json"), true).id(),
                    Duration.ofSeconds(30));
            assertThat(refreshed.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.FAILED);
            assertThat(harvester.exportArtifact(refreshed.id())).isEmpty();
            assertThat(harvester.exportArtifact(first.id())).isPresent();
        }
    }

    @Test
    void folderOpensAsVirtualPageWithoutDownloadingItsFiles() throws Exception {
        KnowledgeSourcePort source = open("reader", "secret");
        NavigationNode nested = ((PageNavigationSource) source).children(
                NavigationNode.root("space:bscw-test", "BSCW", "bscw-test",
                        NavigationNode.Children.YES), "").nodes().get(0);

        com.aresstack.enterpriseai.source.api.PageView preview =
                ((PageViewSource) source).pageView(nested.resource());
        assertThat(preview.html()).contains("archive.zip").doesNotContain("Example BSCW document");
        assertThat(preview.baseUri()).isEqualTo(root.replace("/146377797", "/444"));
        assertThat(source.load(nested.resource()).text()).isEmpty();
        assertThat(downloads).hasValue(0);
    }

    @Test
    void binaryFilesAreNotReplacedByExtractedTextSnippets() throws Exception {
        KnowledgeSourcePort source = open("reader", "secret");
        KnowledgeResource archive = allResources((PartitionListingSource) source).stream()
                .filter(resource -> "archive.zip".equals(resource.title()))
                .findFirst().orElseThrow();

        String html = ((PageViewSource) source).pageView(archive.id()).html();
        assertThat(html).contains("Originaldatei", "archive.zip").doesNotContain("<pre");
    }

    @Test
    void exposesOriginalFileToTheExistingCoreTextProjector() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root));
        String url = root.replace("/146377797", "/666");
        String localId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                url.getBytes(StandardCharsets.UTF_8));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "bscw-test", localId,
                    false).snapshot();
            assertThat(snapshot.payload().bytes()).isEqualTo(TEXT_CONTENT);
            assertThat(harvester.pageText(snapshot)).map(PageText::text)
                    .hasValueSatisfying(text -> assertThat(text).contains("Example BSCW document"));
            assertThat(harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Example"), null, 10).hits())
                    .extracting(KeywordSearch.Hit::sourceId).containsExactly("bscw-test");
        }
    }

    @Test
    void synchronizationIndexesEveryBscwDocumentInTheSharedLuceneIndex() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root, "maxDepth", "2"));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            assertThat(harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Example"), null, 10).hits()).isEmpty();

            harvester.await(harvester.sync("bscw-test", "bscw-test").id(), Duration.ofSeconds(30));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                if (!harvester.keywordSearch().search(harvester.caller(),
                        KeywordSearch.Query.of("Example"), null, 10).hits().isEmpty()) {
                    break;
                }
                Thread.sleep(50);
            }

            assertThat(harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Example"), null, 10).hits())
                    .extracting(KeywordSearch.Hit::sourceId).containsExactly("bscw-test");
        }
    }

    @Test
    void connectionTestChecksWebDavInsteadOfOnlyReturningAnOfflinePartition() throws Exception {
        SourceSettingsConfig valid = new SourceSettingsConfig("bscw-test", "bscw", "BSCW", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root));
        SourceSettingsConfig invalid = new SourceSettingsConfig("bscw-test", "bscw", "BSCW", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "wrong", null),
                Map.of("rootUrl", root));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            assertThat(harvester.sourceManagement().test(valid, false).successful()).isTrue();
            assertThat(propfinds.get()).isGreaterThan(0);
            assertThat(harvester.sourceManagement().test(invalid, false).successful()).isFalse();
        }
    }

    @Test
    void networkDiagnosisUsesTheWebDavRootInsteadOfSkippingTheNetworkStages() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            SourceDiagnosis diagnosis = harvester.sourceManagement().diagnose(config, false);
            assertThat(diagnosis.successful()).isTrue();
            assertThat(diagnosis.steps()).extracting(ConnectionStep::stage)
                    .contains(ConnectionStep.Stage.PROXY_ROUTE, ConnectionStep.Stage.API);
            assertThat(diagnosis.steps()).extracting(ConnectionStep::message)
                    .doesNotContain("keine Netzverbindung für Quellentyp bscw")
                    .contains("BSCW-Startordner über WebDAV erreichbar");
        }
    }

    /**
     * Angelo 20.11.2026: Das Original einer großen Datei kommt für den Textauszug in Stücken per HTTP-Range auf die
     * Platte; ignoriert BSCW die Range, einmal ganz. Dateien ohne lesbaren Text lädt es gar nicht.
     */
    @Test
    void largeOriginalsAreDownloadedToDiskInChunks() throws Exception {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("bscw-test", "reader", "secret");
        BscwWebDavTransport transport = new BscwWebDavTransport(URI.create(root), "bscw-test", secrets,
                destination -> HttpRoute.direct("test"), ProxyCredentials.none(), "test-agent", 1024, null);
        BscwWebDavKnowledgeSource port = new BscwWebDavKnowledgeSource(
                com.aresstack.enterpriseai.astu.knowledge.SourceAddress.of("bscw-test"), URI.create(root), transport, 20,
                TikaDocumentText.shared(), 5);
        String localId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                root.replace("/146377797", "/666").getBytes(StandardCharsets.UTF_8));
        Path target = data.resolve("readme.part");

        assertThat(port.downloadOriginal(localId, target, type -> false, 0)).as("ungewollter Typ").isEmpty();
        assertThat(downloads).hasValue(0);

        assertThat(port.downloadOriginal(localId, target, LocalFilesTextProjector::readable, 0))
                .hasValue("text/plain");
        assertThat(java.nio.file.Files.readAllBytes(target)).isEqualTo(TEXT_CONTENT);
        assertThat(downloads).as("je 5 Bytes ein Abruf").hasValue((TEXT_CONTENT.length + 4) / 5);

        rangesForText.set(false);
        downloads.set(0);
        java.nio.file.Files.delete(target);
        assertThat(port.downloadOriginal(localId, target, LocalFilesTextProjector::readable, 0))
                .hasValue("text/plain");
        assertThat(java.nio.file.Files.readAllBytes(target)).as("ohne Range einmal ganz").isEqualTo(TEXT_CONTENT);
        assertThat(downloads).hasValue(1);
    }

    /**
     * Angelo 26.11.2026 (WLAN): Reißt ein Stück ab, wird genau dieses Stück neu angefordert; die schon geladenen
     * Stücke bleiben. Ohne Wiederholungen scheitert der Download sichtbar.
     */
    @Test
    void aBrokenChunkIsRequestedAgainWithoutLoadingTheEarlierChunksAgain() throws Exception {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("bscw-test", "reader", "secret");
        BscwWebDavTransport transport = new BscwWebDavTransport(URI.create(root), "bscw-test", secrets,
                destination -> HttpRoute.direct("test"), ProxyCredentials.none(), "test-agent", 1024, null);
        String localId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                root.replace("/146377797", "/666").getBytes(StandardCharsets.UTF_8));
        Path target = data.resolve("readme.part");
        BscwWebDavKnowledgeSource retrying = new BscwWebDavKnowledgeSource(
                com.aresstack.enterpriseai.astu.knowledge.SourceAddress.of("bscw-test"), URI.create(root), transport, 20,
                TikaDocumentText.shared(), 5,
                new com.aresstack.spaceharvester.application.jobs.RetrySettings(2, java.time.Duration.ZERO,
                        java.time.Duration.ZERO, 1));
        failSecondChunk.set(1);

        assertThat(retrying.downloadOriginal(localId, target, LocalFilesTextProjector::readable, 0))
                .hasValue("text/plain");

        assertThat(java.nio.file.Files.readAllBytes(target)).isEqualTo(TEXT_CONTENT);
        assertThat(textRanges).as("das erste Stück nur einmal, das abgerissene zweimal")
                .filteredOn(range -> range.startsWith("bytes=0-")).hasSize(1);
        assertThat(textRanges).filteredOn(range -> range.startsWith("bytes=5-")).hasSize(2);

        BscwWebDavKnowledgeSource once = new BscwWebDavKnowledgeSource(
                com.aresstack.enterpriseai.astu.knowledge.SourceAddress.of("bscw-test"), URI.create(root), transport, 20,
                TikaDocumentText.shared(), 5,
                new com.aresstack.spaceharvester.application.jobs.RetrySettings(0, java.time.Duration.ZERO,
                        java.time.Duration.ZERO, 1));
        failSecondChunk.set(1);
        assertThatThrownBy(() -> once.downloadOriginal(localId, target, LocalFilesTextProjector::readable, 0))
                .isInstanceOf(KnowledgeSourceException.class);
    }

    @Test
    void genericWebDavMimeDoesNotPreventDownloadingOneNoteForExtraction() throws Exception {
        KnowledgeSourcePort source = open("reader", "secret");
        String localId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                root.replace("/146377797", "/780").getBytes(StandardCharsets.UTF_8));
        assertThat(((ResourceMetadataSource) source).resourceMetadata(localId).contentType())
                .isEqualTo("application/onenote");
        Path target = data.resolve("notes.one");
        assertThat(((OriginalFileSource) source).downloadOriginal(localId, target,
                LocalFilesTextProjector::readable, 1024 * 1024)).hasValue("application/onenote");
        assertThat(java.nio.file.Files.readAllBytes(target)).isEqualTo(ZIP_CONTENT);
    }

    @Test
    void unavailableProxyRouteHasARecognizableErrorWithoutSendingCredentials() throws Exception {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("bscw-test", "reader", "secret");
        BscwWebDavTransport transport = new BscwWebDavTransport(URI.create(root), "bscw-test", secrets,
                destination -> HttpRoute.unavailable("pac-url-not-found", "no PAC/WPAD URL was discovered"),
                ProxyCredentials.none(), "test-agent", 1024, null);

        assertThatThrownBy(() -> transport.properties(URI.create(root), 0))
                .isInstanceOf(KnowledgeSourceException.class)
                .satisfies(error -> {
                    KnowledgeSourceException failure = (KnowledgeSourceException) error;
                    assertThat(failure.kind()).isEqualTo(KnowledgeSourceException.Kind.UNAVAILABLE);
                    assertThat(failure.getMessage()).contains("BSCW WebDAV route-unavailable");
                });
        assertThat(propfinds.get()).isZero();
    }

    @Test
    void reportsTheRealWebDavHttpStatusInsteadOfCallingItAProxyFailure() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root.replace("/146377797", "/999")));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            SourceDiagnosis diagnosis = harvester.sourceManagement().diagnose(config, false);
            assertThat(diagnosis.successful()).isFalse();
            assertThat(diagnosis.steps()).filteredOn(step -> step.stage() == ConnectionStep.Stage.API)
                    .extracting(ConnectionStep::message).containsExactly("BSCW WebDAV HTTP 405");
        }
    }

    @Test
    void refusesInvalidCredentialsAndNonBscwUrls() throws Exception {
        KnowledgeSourcePort wrong = open("reader", "wrong");
        assertThatThrownBy(() -> ((PartitionListingSource) wrong).resources("bscw-test", null, 2))
                .isInstanceOf(KnowledgeSourceException.class)
                .satisfies(error -> assertThat(((KnowledgeSourceException) error).kind())
                        .isEqualTo(KnowledgeSourceException.Kind.ACCESS_DENIED));
        BscwSourceAdapter adapter = new BscwSourceAdapter();
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "Test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", "https://example.com/sec/bscw.cgi/home"));
        assertThat(adapter.check(config)).anyMatch(problem -> problem.contains("base-url"));
    }

    private static List<KnowledgeResource> allResources(PartitionListingSource listing)
            throws KnowledgeSourceException {
        List<KnowledgeResource> all = new java.util.ArrayList<>();
        String cursor = null;
        do {
            PartitionResources batch = listing.resources("bscw-test", cursor, 20);
            all.addAll(batch.resources());
            cursor = batch.complete() ? null : batch.nextCursor();
        } while (cursor != null);
        return all;
    }

    private static SourceAdapter.Context context() {
        return new SourceAdapter.Context(new ConfiguredSecrets(),
                ProxyRoutes.from(ProxySettings.disabled()), "bscw-test-agent",
                TrustBundles.NONE, null, ProxyCredentials.none());
    }

    private KnowledgeSourcePort open(String username, String password) {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("bscw-test", username, password);
        SourceAdapter.Context context = new SourceAdapter.Context(secrets,
                ProxyRoutes.from(ProxySettings.disabled()), "bscw-test-agent",
                TrustBundles.NONE, null, ProxyCredentials.none());
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "Test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, username, password, null),
                Map.of("rootUrl", root, "maxDepth", "2"));
        return new BscwSourceAdapter().open(config, context).port();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"BSCW\"");
                respond(exchange, 401, new byte[0], "text/plain");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if ("PROPFIND".equals(exchange.getRequestMethod())) {
                propfinds.incrementAndGet();
                if (denyNested.get() && "/sec/bscw.cgi/444".equals(path)) {
                    respond(exchange, 403, new byte[0], "text/plain");
                    return;
                }
                if ("/sec/bscw.cgi/999".equals(path)) {
                    respond(exchange, 405, new byte[0], "text/plain");
                    return;
                }
                String depth = exchange.getRequestHeaders().getFirst("Depth");
                String body = directoryResponse(path, "1".equals(depth));
                respond(exchange, 207, body.getBytes(StandardCharsets.UTF_8), "application/xml");
                return;
            }
            if (search(exchange, path)) {
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                downloads.incrementAndGet();
                if ("/sec/bscw.cgi/555".equals(path)) {
                    respond(exchange, 200, ZIP_CONTENT, "application/zip");
                    return;
                }
                if ("/sec/bscw.cgi/666".equals(path)) {
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    textRanges.add(String.valueOf(range));
                    if (range != null && range.startsWith("bytes=5-") && failSecondChunk.getAndUpdate(
                            left -> Math.max(0, left - 1)) > 0) {
                        respond(exchange, 503, new byte[0], "text/plain");
                        return;
                    }
                    java.util.regex.Matcher bytes = range == null ? null
                            : java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(range);
                    if (bytes != null && bytes.matches() && rangesForText.get()) {
                        int from = Integer.parseInt(bytes.group(1));
                        int to = Math.min(Integer.parseInt(bytes.group(2)), TEXT_CONTENT.length - 1);
                        respond(exchange, 206, java.util.Arrays.copyOfRange(TEXT_CONTENT, from, to + 1), "text/plain");
                        return;
                    }
                    respond(exchange, 200, TEXT_CONTENT, "text/plain");
                    return;
                }
                if ("/sec/bscw.cgi/777".equals(path)) {
                    respond(exchange, 200, "first,second\\nabc,def".getBytes(StandardCharsets.UTF_8), "text/csv");
                    return;
                }
                if ("/sec/bscw.cgi/780".equals(path)) {
                    respond(exchange, 200, ZIP_CONTENT, "application/octet-stream");
                    return;
                }
                if ("/sec/bscw.cgi/779".equals(path)) {
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    if ("bytes=2-5".equals(range)) {
                        respond(exchange, 206, "2345".getBytes(StandardCharsets.US_ASCII), "video/mp4");
                    } else {
                        respond(exchange, 200, "012345678901234567890123".getBytes(StandardCharsets.US_ASCII),
                                "video/mp4");
                    }
                    return;
                }
            }
            respond(exchange, 404, new byte[0], "text/plain");
        } finally {
            exchange.close();
        }
    }

    /** Die Formulare, die die Schnellsuche an den Startordner geschickt hat. */
    private final java.util.List<String> searchForms = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Die Schnellsuche der Weboberfläche, mit erfundenen Daten nachgebildet: Ordnerseite mit Formular-Token, Formular an
     * den Startordner, zwei Weiterleitungen auf den Suchordner 900, dessen Seite die Treffer trägt.
     */
    private boolean search(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getRawQuery();
        if ("GET".equals(method) && "/sec/bscw.cgi/146377797".equals(path)) {
            respond(exchange, 200, ("<html><form><input type=\"hidden\" name=\"bscw_v_post\" value=\"tok/1\" />"
                    + "</form></html>").getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8");
            return true;
        }
        if ("POST".equals(method) && "/sec/bscw.cgi/146377797".equals(path)) {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            searchForms.add(form + "|" + exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getResponseHeaders().set("Location", "/sec/bscw.cgi/900?op=applyQuery");
            respond(exchange, 303, new byte[0], "text/html");
            return true;
        }
        if ("GET".equals(method) && "/sec/bscw.cgi/900".equals(path) && "op=applyQuery".equals(query)) {
            exchange.getResponseHeaders().set("Location", "/sec/bscw.cgi/900");
            respond(exchange, 303, new byte[0], "text/html");
            return true;
        }
        if ("GET".equals(method) && "/sec/bscw.cgi/900".equals(path)) {
            respond(exchange, 200, (BscwSearchPageTest.row("900", "666", "d666/readme.txt", "readme.txt",
                    List.of("4", "Root"), "2026-11-17 18:00")
                    + BscwSearchPageTest.row("900", "555", "d555/archive.zip", "archive.zip",
                    List.of("4", "Root", "444", "Nested"), "2026-11-16 09:30"))
                    .getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8");
            return true;
        }
        return false;
    }

    @Test
    void searchesWithTheQuickSearchOfTheWebInterfaceAndHitsAreTheWebDavResources() throws Exception {
        KnowledgeSourcePort port = open("reader", "secret");

        List<com.aresstack.enterpriseai.source.api.SourceSearchHit> hits =
                ((com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource) port)
                        .search(new com.aresstack.enterpriseai.source.api.SourceQuery("Betriebshandbuch Nord", 10));

        assertThat(hits).extracting(com.aresstack.enterpriseai.source.api.SourceSearchHit::title)
                .containsExactly("readme.txt", "archive.zip");
        assertThat(hits.get(1).snippet()).isEqualTo("Root / Nested");
        assertThat(hits.get(0).url().getRawPath()).isEqualTo("/sec/bscw.cgi/666");
        assertThat(searchForms).singleElement().satisfies(form -> assertThat(form)
                .contains("operation=op_qsearch", "bscw_v_post=tok%2F1", "query=Betriebshandbuch+Nord",
                        "qstype=BscwSearch", "qsmode=q_search_content", "application/x-www-form-urlencoded"));
        // Ein Treffer ist dieselbe Ressource wie im Baum: Kopfdaten über WebDAV.
        ResourceMetadataSource metadata = (ResourceMetadataSource) port;
        assertThat(metadata.resourceMetadata(metadata.localId(hits.get(0).resourceId())).title())
                .isEqualTo("readme.txt");
    }

    @Test
    void titleSearchAndTheSwitchedOffContentOptionAlsoSearchNames() throws Exception {
        KnowledgeSourcePort port = open("reader", "secret");
        ((com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource) port).search(
                new com.aresstack.enterpriseai.source.api.SourceQuery("readme", 5).withTitleOnly(true));
        assertThat(searchForms).singleElement().satisfies(form -> assertThat(form).contains("qsmode=q_search_all"));

        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "Test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root, "searchContentOnly", "false"));
        KnowledgeSourcePort names = new BscwSourceAdapter().open(config, context()).port();
        ((com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource) names).search(
                new com.aresstack.enterpriseai.source.api.SourceQuery("readme", 5));
        assertThat(searchForms).hasSize(2).last().satisfies(form -> assertThat(form).contains("qsmode=q_search_all"));

        assertThat(new BscwSourceAdapter().check(new SourceSettingsConfig("bscw-test", "bscw", "Test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root, "searchContentOnly", "vielleicht"))))
                .contains("options.searchContentOnly muss true oder false sein");
    }

    private static boolean authorized(HttpExchange exchange) {
        String credentials = java.util.Base64.getEncoder().encodeToString(
                "reader:secret".getBytes(StandardCharsets.UTF_8));
        return ("Basic " + credentials).equals(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static String directoryResponse(String path, boolean children) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
        if ("/sec/bscw.cgi/146377797".equals(path)) {
            xml.append(entry(path, "Root", true, null));
            if (children) {
                xml.append(entry("/sec/bscw.cgi/444", "Nested", true, null));
                xml.append(entry("/sec/bscw.cgi/666", "readme.txt", false, null));
            }
        } else if ("/sec/bscw.cgi/444".equals(path)) {
            xml.append(entry(path, "Nested", true, null));
            if (children) {
                xml.append(entry("/sec/bscw.cgi/555", "archive.zip", false, "application/zip"));
                xml.append(entry("/sec/bscw.cgi/556", ".DS_Store", false, "application/octet-stream"));
                xml.append(entry("/sec/bscw.cgi/557", "._archive.zip", false, "application/octet-stream"));
            }
        } else if ("/sec/bscw.cgi/888".equals(path)) {
            xml.append(entry(path, "CSV root", true, null));
            if (children) {
                xml.append(entry("/sec/bscw.cgi/777", "metrics.csv", false, "application/octet-stream"));
            }
        } else if ("/sec/bscw.cgi/777".equals(path)) {
            xml.append(entry(path, "metrics.csv", false, "application/octet-stream"));
        } else if ("/sec/bscw.cgi/780".equals(path)) {
            xml.append(entry(path, "Notes.one", false, "application/octet-stream"));
        } else if ("/sec/bscw.cgi/779".equals(path)) {
            xml.append(entry(path, "recording.mp4", false, "application/octet-stream"));
        } else if ("/sec/bscw.cgi/555".equals(path)) {
            xml.append(entry(path, "archive.zip", false, "application/zip"));
        } else if ("/sec/bscw.cgi/666".equals(path)) {
            xml.append(entry(path, "readme.txt", false, null));
        }
        return xml.append("</d:multistatus>").toString();
    }

    private static String entry(String path, String name, boolean folder, String mime) {
        return "<d:response><d:href>" + path + "</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status>"
                + "<d:prop><d:displayname>" + name + "</d:displayname><d:resourcetype>"
                + (folder ? "<d:collection/>" : "") + "</d:resourcetype>"
                + "<d:getetag>\"version-one\"</d:getetag>"
                + "<d:getlastmodified>Tue, 17 Nov 2026 18:00:00 GMT</d:getlastmodified>"
                + "<d:getcontentlength>24</d:getcontentlength>"
                + (mime == null ? "" : "<d:getcontenttype>" + mime + "</d:getcontenttype>")
                + "</d:prop></d:propstat></d:response>";
    }

    private static void respond(HttpExchange exchange, int status, byte[] content, String contentType)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
        if (content.length > 0) {
            exchange.getResponseBody().write(content);
        }
    }
}
