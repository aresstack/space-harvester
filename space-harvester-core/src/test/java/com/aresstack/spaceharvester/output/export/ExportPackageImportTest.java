package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetText;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescription;
import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.export.ExportPackageException;
import com.aresstack.spaceharvester.application.export.ExportPackageImport;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.InMemorySyncStore;
import com.aresstack.spaceharvester.application.sync.SpaceSync;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.output.convert.JsonPackageConverter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Import eines JSON-Pakets wie ein Abgleich ohne Quelle: Seiten, Generation, Dateien und Texte. */
class ExportPackageImportTest {

    private static final byte[] PRETTY = "{\n  \"id\" : \"101\",\n  \"title\" : \"Grüße\"\n}\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final byte[] PDF = "%PDF-1.4 bericht".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SVG = "<svg/>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MXFILE = "<mxfile/>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T21:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final InMemorySyncStore store = new InMemorySyncStore(repository);
    private final MemoryAssets assets = new MemoryAssets();
    private final Map<String, String> texts = new HashMap<>();
    private final List<SpaceSync.SyncRun> committed = new ArrayList<>();
    private final ExportPackageImport imports = new ExportPackageImport(jobs,
            new ZipExportPackageReader(1024 * 1024, 1024 * 1024), store, Set.of("laptop", "confluence")::contains,
            assets, (sha, text) -> texts.put(sha, text) == null, null, List.of(committed::add), clock);

    @Test
    void importsPagesAsANewGenerationOfTheTargetSourceAndKeepsTheRawBytes() throws Exception {
        Path pkg = pkg(pages("100", "101", "102", "200"), ExportAssets.NONE);

        JobStatus job = imports.start(pkg, "laptop", false, "client-a");

        assertThat(jobs.status(job.id()).state()).as(String.valueOf(jobs.status(job.id())))
                .isEqualTo(JobState.COMPLETED);
        SyncBaseline baseline = store.confirmed("laptop", "DEV").orElseThrow();
        assertThat(baseline.generation()).isEqualTo(1);
        assertThat(baseline.pages().keySet()).containsExactly("100", "101", "102", "200");
        // Der nächste Abgleich fragt ab dem frühesten Abruf im Paket.
        assertThat(baseline.changesSince()).isEqualTo(Instant.parse("2026-10-16T20:00:00Z"));
        PageSnapshot imported = repository.find(new PageKey("laptop", "101")).orElseThrow();
        assertThat(imported.payload().bytes()).isEqualTo(PRETTY);
        assertThat(imported.metadata().resourceId()).isEqualTo("confluence:wiki.example.org/page/101");
        assertThat(imported.metadata().parentPageId()).isEqualTo("100");
        assertThat(repository.find(new PageKey("confluence", "101"))).isEmpty();
        assertThat(committed).hasSize(1);
        assertThat(jobs.status(job.id()).progress().written()).isEqualTo(4);
    }

    @Test
    void aFilledSpaceIsOnlyReplacedOnRequestAndPagesOutsideThePackageGo() throws Exception {
        imports.start(pkg(pages("100", "101", "102", "200"), ExportAssets.NONE), "laptop", false, "client-a");
        Path smaller = pkg(pages("100", "101"), ExportAssets.NONE);
        assertThat(imports.preview(smaller, "laptop").spaceHasData()).isTrue();

        JobStatus refused = imports.start(smaller, "laptop", false, "client-a");

        assertThat(jobs.status(refused.id()).state()).isEqualTo(JobState.FAILED);
        assertThat(jobs.status(refused.id()).error().code()).isEqualTo(ExportPackageImport.SPACE_NOT_EMPTY);
        assertThat(store.confirmed("laptop", "DEV").orElseThrow().generation()).isEqualTo(1);

        JobStatus replaced = imports.start(smaller, "laptop", true, "client-a");

        assertThat(jobs.status(replaced.id()).state()).isEqualTo(JobState.COMPLETED);
        SyncBaseline baseline = store.confirmed("laptop", "DEV").orElseThrow();
        assertThat(baseline.generation()).isEqualTo(2);
        assertThat(baseline.pages().keySet()).containsExactly("100", "101");
        assertThat(repository.find(new PageKey("laptop", "200"))).isEmpty();
    }

    @Test
    void storesImagesDiagramsAndAttachmentTextsUnderTheirOriginalKeys() throws Exception {
        Path pkg = pkg(pages("100"), new PackageAssets());

        JobStatus job = imports.start(pkg, "laptop", false, "client-a");

        assertThat(jobs.status(job.id()).state()).as(String.valueOf(jobs.status(job.id())))
                .isEqualTo(JobState.COMPLETED);
        assertThat(assets.content("laptop", "att-foto", "1")).contains(PNG);
        assertThat(assets.content("laptop", "att-pdf", "2")).contains(PDF);
        assertThat(assets.content("laptop", "att-drawio", "1")).contains(MXFILE);
        assertThat(assets.content("laptop", Assets.renditionId(sha256(MXFILE)), Assets.DRAWIO_RENDITION))
                .contains(SVG);
        assertThat(texts).containsEntry(sha256(PDF), "Bericht über alles");
    }

    @Test
    void storesTheFilesOfThePackageAsStreamsThroughFilesOfTheStore() throws Exception {
        JobStatus job = imports.start(pkg(pages("100"), new PackageAssets()), "laptop", false, "client-a");

        assertThat(jobs.status(job.id()).state()).as(String.valueOf(jobs.status(job.id())))
                .isEqualTo(JobState.COMPLETED);
        // Bild, draw.io und PDF über eine Datei der Ablage, nie ganz im Speicher; nur die kleine Grafik als Bytes.
        assertThat(assets.streamed).containsExactlyInAnyOrder("att-foto", "att-drawio", "att-pdf");
        assertThat(assets.staged).hasSize(3).allSatisfy(file -> assertThat(file).doesNotExist());
    }

    @Test
    void aChangedFileOfThePackageIsNotStoredAndLeavesNoFileBehind() throws Exception {
        Path pkg = pkg(pages("100"), new PackageAssets());
        Path tampered = rewrite(pkg, name(pkg, PDF), "%PDF-1.4 verändert".getBytes(StandardCharsets.UTF_8));

        JobStatus job = imports.start(tampered, "laptop", false, "client-a");

        assertThat(jobs.status(job.id()).state()).as(String.valueOf(jobs.status(job.id())))
                .isEqualTo(JobState.COMPLETED);
        assertThat(assets.content("laptop", "att-pdf", "2")).isEmpty();
        assertThat(assets.content("laptop", "att-foto", "1")).contains(PNG);
        assertThat(assets.staged).isNotEmpty().allSatisfy(file -> assertThat(file).doesNotExist());
    }

    @Test
    void refusesPackagesWithoutRawDataOrWithChangedBytes() throws Exception {
        Path old = root.resolve("alt.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(old))) {
            zip.putNextEntry(new ZipEntry("pages.json"));
            zip.write("[]".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> imports.start(old, "laptop", false, "client-a"))
                .isInstanceOfSatisfying(ExportPackageException.class,
                        e -> assertThat(e.code()).isEqualTo(ExportPackageException.UNSUPPORTED));
        assertThatThrownBy(() -> imports.start(pkg(pages("100"), ExportAssets.NONE), "unbekannt", false, "c"))
                .isInstanceOf(UnknownSourceException.class);

        Path tampered = rewrite(pkg(pages("100", "101"), ExportAssets.NONE), "raw/101.json",
                "{\"id\":\"101\",\"title\":\"anders\"}".getBytes(StandardCharsets.UTF_8));
        JobStatus job = imports.start(tampered, "laptop", false, "client-a");

        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.FAILED);
        assertThat(jobs.status(job.id()).error().code()).isEqualTo(ExportPackageException.DAMAGED);
        assertThat(store.confirmed("laptop", "DEV")).isEmpty();
        assertThat(repository.find(new PageKey("laptop", "100"))).isEmpty();
    }

    @Test
    void anUploadedPackageIsDeletedWhenTheImportEndsOrDoesNotStart() throws Exception {
        Path uploaded = pkg(pages("100"), ExportAssets.NONE);
        imports.start(uploaded, "laptop", false, "client-a", true);
        assertThat(uploaded).doesNotExist();

        Path refused = pkg(pages("100"), ExportAssets.NONE);
        assertThatThrownBy(() -> imports.start(refused, "unbekannt", false, "client-a", true))
                .isInstanceOf(UnknownSourceException.class);
        assertThat(refused).doesNotExist();
    }

    @Test
    void anUploadedPackageOfAnImportCancelledWhileWaitingGoesWithTheNextImport() throws Exception {
        List<Runnable> pending = new ArrayList<>();
        Jobs held = new Jobs(new InMemoryJobStore(), pending::add, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        ExportPackageImport waiting = new ExportPackageImport(held,
                new ZipExportPackageReader(1024 * 1024, 1024 * 1024), store, Set.of("laptop")::contains, null, null,
                null, List.of(), clock);
        waiting.start(pkg(pages("100"), ExportAssets.NONE), "laptop", false, "client-a", true);
        Path queued = pkg(pages("100"), ExportAssets.NONE);
        JobStatus second = waiting.start(queued, "laptop", false, "client-a", true);
        assertThat(held.status(second.id()).state()).isEqualTo(JobState.QUEUED);

        held.cancel(second.id());
        assertThat(queued).exists();
        waiting.preview(pkg(pages("100"), ExportAssets.NONE), "laptop");

        assertThat(queued).doesNotExist();
    }

    @Test
    void theNewGenerationReachesTheFollowUpStepsEvenIfAFileFails() throws Exception {
        ExportPackageImport tight = new ExportPackageImport(jobs, new ZipExportPackageReader(1024 * 1024, 4), store,
                Set.of("laptop")::contains, assets, null, null, List.of(committed::add), clock);

        JobStatus job = tight.start(pkg(pages("100"), new PackageAssets()), "laptop", false, "client-a");

        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.FAILED);
        assertThat(jobs.status(job.id()).error().code()).isEqualTo(ExportPackageException.TOO_LARGE);
        assertThat(store.confirmed("laptop", "DEV").orElseThrow().generation()).isEqualTo(1);
        assertThat(committed).hasSize(1);
    }

    @Test
    void aPayloadOlderThanItsHeaderComesInWithItsOwnVersion() throws Exception {
        List<PageSnapshot> pages = pages("100", "101");
        PageMetadata old = pages.get(1).metadata();
        PageMetadata newer = new PageMetadata(old.pageId(), old.resourceId(), "Seite 101 neu", old.partitionKey(),
                old.parentPageId(), "4", Instant.parse("2026-10-20T08:00:00Z"), old.url(), old.attributes());
        PageHeader header = new PageHeader(pages.get(1).key(), newer, MetadataHash.of("confluence", newer),
                AttributesHash.of("confluence", newer), Instant.parse("2026-10-21T00:00:00Z"));
        Path pkg = file(new Input(pages, ExportAssets.NONE, Map.of(), Map.of("101", header)));

        JobStatus job = imports.start(pkg, "laptop", false, "client-a");

        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.COMPLETED);
        // Bestätigt ist die Version der Bytes: Der nächste Abgleich sieht Version 4 als neu und lädt sie.
        assertThat(store.confirmed("laptop", "DEV").orElseThrow().pages().get("101").version()).isEqualTo("3");
        assertThat(repository.find(new PageKey("laptop", "101")).orElseThrow().revision()).isEqualTo("3");
    }

    @Test
    void refusesPagesOutsideTheSpaceOfThePackage() throws Exception {
        Path pkg = pkg(pages("100"), ExportAssets.NONE);
        String manifest = new String(entry(pkg, "manifest.json"), StandardCharsets.UTF_8);
        Path moved = rewrite(pkg, "manifest.json",
                manifest.replace("\"spaceKey\":\"DEV\"", "\"spaceKey\":\"OPS\"").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> imports.preview(moved, "laptop"))
                .isInstanceOfSatisfying(ExportPackageException.class,
                        e -> assertThat(e.code()).isEqualTo(ExportPackageException.DAMAGED));
    }

    @Test
    void readsTheReadingViewOfAPage() throws Exception {
        Path pkg = pkg(pages("100", "101"), ExportAssets.NONE, Map.of("101",
                ExportReadingView.available("101", "3", "<p>Eingebunden</p>", "https://wiki.example.org/", "Text")));

        try (var opened = new ZipExportPackageReader(1024 * 1024, 1024 * 1024).open(pkg)) {
            assertThat(opened.readingView("101")).map(ExportReadingView::html).contains("<p>Eingebunden</p>");
            assertThat(opened.readingView("100")).isEmpty();
            assertThat(opened.pages()).extracting(p -> p.pageId() + "@" + p.depth()).containsExactly("100@0",
                    "101@1");
        }
    }

    // --- Paket bauen ----------------------------------------------------------------------------------------------

    private static List<PageSnapshot> pages(String... ids) {
        List<PageSnapshot> pages = new ArrayList<>();
        for (String id : ids) {
            String parent = switch (id) {
                case "101" -> "100";
                case "102" -> "101";
                default -> null;
            };
            pages.add("101".equals(id) ? page("confluence", "DEV", id, parent, PRETTY)
                    : page("confluence", "DEV", id, parent));
        }
        return pages;
    }

    private Path pkg(List<PageSnapshot> pages, ExportAssets media) throws Exception {
        return pkg(pages, media, Map.of());
    }

    private Path pkg(List<PageSnapshot> pages, ExportAssets media, Map<String, ExportReadingView> views)
            throws Exception {
        return file(new Input(pages, media, views, Map.of()));
    }

    private Path file(Input input) throws Exception {
        Path file = Files.createTempFile(root, "export", ".zip");
        try (OutputStream out = Files.newOutputStream(file)) {
            new JsonPackageConverter().convert(input, out);
        }
        return file;
    }

    private static byte[] entry(Path pkg, String name) throws Exception {
        try (InputStream in = Files.newInputStream(pkg); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new AssertionError(name + " fehlt");
    }

    /** Der Name des Eintrags mit genau diesen Bytes. */
    private static String name(Path pkg, byte[] content) throws Exception {
        try (InputStream in = Files.newInputStream(pkg); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (java.util.Arrays.equals(zip.readAllBytes(), content)) {
                    return entry.getName();
                }
            }
        }
        throw new AssertionError("Kein Eintrag mit diesen Bytes");
    }

    private Path rewrite(Path pkg, String name, byte[] replacement) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(pkg); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), entry.getName().equals(name) ? replacement : zip.readAllBytes());
            }
        }
        Path file = Files.createTempFile(root, "changed", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    /** Seiten in Baumreihenfolge, Tiefe aus der Elternseite; {@code replaced} ersetzt Kopfdaten einzelner Seiten. */
    private record Input(List<PageSnapshot> pages, ExportAssets media, Map<String, ExportReadingView> views,
                         Map<String, PageHeader> replaced) implements ConversionInput {

        @Override
        public String sourceId() {
            return "confluence";
        }

        @Override
        public String spaceKey() {
            return "DEV";
        }

        @Override
        public PageTree tree() {
            Map<String, List<PageTree.Node>> children = new HashMap<>();
            List<PageTree.Node> roots = new ArrayList<>();
            for (int i = pages.size() - 1; i >= 0; i--) {
                PageSnapshot page = pages.get(i);
                PageTree.Node node = new PageTree.Node(page.key().pageId(),
                        children.getOrDefault(page.key().pageId(), List.of()));
                if (page.parentPageId() == null) {
                    roots.add(0, node);
                } else {
                    children.computeIfAbsent(page.parentPageId(), k -> new ArrayList<>()).add(0, node);
                }
            }
            return new PageTree(roots, List.of(), pages.size(), 3);
        }

        @Override
        public List<PageHeader> headers() {
            return pages.stream().map(this::header).toList();
        }

        private PageHeader header(PageSnapshot page) {
            return replaced.getOrDefault(page.key().pageId(), page.header());
        }

        @Override
        public void forEachPage(Consumer<ConvertiblePage> action) {
            Map<String, Integer> depth = new HashMap<>();
            for (PageSnapshot page : pages) {
                int d = page.parentPageId() == null ? 0 : depth.getOrDefault(page.parentPageId(), 0) + 1;
                depth.put(page.key().pageId(), d);
                action.accept(new ConvertiblePage(header(page), page, d));
            }
        }

        @Override
        public ExportAssets assets() {
            return media;
        }

        @Override
        public Optional<ExportReadingView> readingView(String pageId) {
            return Optional.ofNullable(views.get(pageId));
        }
    }

    /** Ein Bild, ein draw.io mit gezeichneter Grafik und ein PDF mit Text an Seite 100. */
    private static final class PackageAssets implements ExportAssets {

        private final ExportAssetManifest.Entry image = new ExportAssetManifest.Entry("100",
                ExportAssetManifest.Reference.attachment("att-foto", "foto.png"), "att-foto", "1", "foto.png",
                "image/png", PNG.length, sha256(PNG), ExportAssetManifest.Status.STORED, null);
        private final ExportAssetManifest.Entry drawio = new ExportAssetManifest.Entry("100",
                ExportAssetManifest.Reference.attachment("att-drawio", "ablauf.drawio"), "att-drawio", "1",
                "ablauf.drawio", "application/vnd.jgraph.mxfile", MXFILE.length, sha256(MXFILE),
                ExportAssetManifest.Status.STORED, null)
                .withRendition(ExportAssetManifest.Rendition.drawn("image/svg+xml", SVG.length, sha256(SVG)));
        private final ExportAssetManifest.Entry pdf = new ExportAssetManifest.Entry("100",
                ExportAssetManifest.Reference.document("att-pdf", "bericht.pdf"), "att-pdf", "2", "bericht.pdf",
                "application/pdf", PDF.length, sha256(PDF), ExportAssetManifest.Status.STORED, null);

        @Override
        public Optional<ExportAssetManifest> manifest() {
            return Optional.of(new ExportAssetManifest(true, List.of(image, drawio, pdf)));
        }

        @Override
        public Optional<byte[]> content(ExportAssetManifest.Entry entry) {
            return Optional.of(entry == image ? PNG : entry == drawio ? MXFILE : PDF);
        }

        @Override
        public Optional<byte[]> rendition(ExportAssetManifest.Entry entry) {
            return entry == drawio ? Optional.of(SVG) : Optional.empty();
        }

        @Override
        public Optional<String> text(ExportAssetManifest.Entry entry) {
            return entry == pdf ? Optional.of("Bericht über alles") : Optional.empty();
        }
    }

    /** Asset-Ablage im Speicher, je Quelle, Anhang und Version. */
    private static final class MemoryAssets implements AssetStore {

        private final Map<String, byte[]> contents = new HashMap<>();
        private final Map<String, Asset> entries = new HashMap<>();
        /** Die Dateien, die {@link #stagingFile()} ausgegeben hat. */
        private final List<Path> staged = new ArrayList<>();
        /** Die Anhänge, die über {@link #putFile} kamen. */
        private final List<String> streamed = new ArrayList<>();

        @Override
        public Path stagingFile() throws IOException {
            Path file = AssetStore.super.stagingFile();
            staged.add(file);
            return file;
        }

        @Override
        public void putFile(Asset asset, Path file) {
            streamed.add(asset.attachmentId());
            AssetStore.super.putFile(asset, file);
        }

        Optional<byte[]> content(String sourceId, String attachmentId, String version) {
            return find(sourceId, attachmentId, version).map(asset -> contents.get(asset.sha256()));
        }

        @Override
        public Optional<Asset> find(String sourceId, String attachmentId, String version) {
            return Optional.ofNullable(entries.get(sourceId + "/" + attachmentId + "/" + version));
        }

        @Override
        public void put(Asset asset, byte[] content) {
            if (!sha256(content).equals(asset.sha256()) || content.length != asset.size()) {
                throw new IllegalArgumentException("passt nicht");
            }
            contents.put(asset.sha256(), content);
            entries.put(asset.sourceId() + "/" + asset.attachmentId() + "/" + asset.version(), asset);
        }

        @Override
        public byte[] read(Asset asset) {
            byte[] content = contents.get(asset.sha256());
            if (content == null) {
                throw new AssetStoreException("fehlt", null);
            }
            return content;
        }

        @Override
        public Optional<ImageDescription> description(String imageSha256, String describer, String modelVersion) {
            return Optional.empty();
        }

        @Override
        public void putDescription(ImageDescription description) {
        }

        @Override
        public Optional<AssetText> text(String sha256, String extractor, String version) {
            return Optional.empty();
        }

        @Override
        public void putText(AssetText text) {
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
