package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0018: Medien vor dem Download einordnen (draw.io-Vorschau überflüssig) und beim Einmalcode an derselben Stelle
 * auf die Anmeldung warten.
 */
class ExportMediaSignInTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final byte[] MXFILE = ("<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>").getBytes(StandardCharsets.UTF_8);

    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.systemUTC());
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter()),
            ExportConverters.DEFAULT_MAX_BYTES);

    @TempDir
    Path data;

    @Test
    void theDrawioPreviewIsNotLoadedWhenTheDiagramIsDrawnButAPlainImageAlwaysIs() {
        Source source = new Source(false);
        SignInRequests signIns = SignInRequests.none();
        Setup setup = setup(source, signIns);
        drain();

        ExportAssetManifest manifest = setup.writer.assets(setup.export.id()).orElseThrow();
        assertThat(manifest.forPage("100")).extracting(e -> e.reference().filename(), ExportAssetManifest.Entry::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ablauf.png", ExportAssetManifest.Status.REDUNDANT),
                        org.assertj.core.groups.Tuple.tuple("ablauf.drawio", ExportAssetManifest.Status.STORED),
                        org.assertj.core.groups.Tuple.tuple("foto.png", ExportAssetManifest.Status.STORED));
        assertThat(manifest.complete()).isTrue();
        // Das Backup-PNG wurde weder als Anhang noch als Bild der Ansicht geladen.
        assertThat(source.loaded).containsExactlyInAnyOrder("att-drawio", "att-foto");
        assertThat(setup.conversions.dataset(setup.export.id(), "client-a").orElseThrow().images())
                .isEqualTo(ExportDatasetInfo.Images.COMPLETE);
    }

    @Test
    void anUnreadableDiagramFallsBackToItsPreview() {
        Source source = new Source(false);
        source.brokenDrawio = true;
        Setup setup = setup(source, SignInRequests.none());
        drain();

        ExportAssetManifest manifest = setup.writer.assets(setup.export.id()).orElseThrow();
        assertThat(manifest.forPage("100")).filteredOn(e -> "ablauf.png".equals(e.reference().filename()))
                .singleElement().satisfies(e -> assertThat(e.stored()).isTrue());
        assertThat(source.loaded).contains("att-preview");
    }

    @Test
    void withoutAnyoneToAskTheFilesAreMissingWithReasonAndNobodyWaits() {
        Source source = new Source(true);
        Setup setup = setup(source, SignInRequests.none());
        drain();

        JobStatus collection = jobs.list("client-a", JobType.ASSETS).get(0);
        assertThat(collection.state()).isEqualTo(JobState.COMPLETED);
        ExportAssetManifest manifest = setup.writer.assets(setup.export.id()).orElseThrow();
        assertThat(manifest.complete()).isFalse();
        assertThat(manifest.forPage("100")).filteredOn(e -> !e.present()).isNotEmpty().allSatisfy(e -> {
            assertThat(e.status()).isEqualTo(ExportAssetManifest.Status.SIGN_IN_REQUIRED);
            assertThat(e.reason()).isEqualTo("sign-in-required");
        });
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theJobWaitsForTheOneTimeCodeAndContinuesAtTheSamePlace() throws Exception {
        Source source = new Source(true);
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(5), Clock.systemUTC());
        List<Integer> changes = new ArrayList<>();
        signIns.onChange(() -> changes.add(1));
        Setup setup = setup(source, signIns);
        queued.poll().run(); // der Export selbst
        Thread worker = new Thread(this::drain);
        worker.start();

        SignInRequest request = awaitPending(signIns);
        String jobId = jobs.list("client-a", JobType.ASSETS).get(0).id();
        assertThat(request.sourceId()).isEqualTo("confluence");
        assertThat(request.jobIds()).containsExactly(jobId);
        assertThat(request.message()).isEqualTo("Code per E-Mail gesendet");
        assertThat(request.resendAvailable()).isTrue();
        assertThat(signIns.waiting(jobId)).isPresent();
        assertThat(jobs.status(jobId).state()).isEqualTo(JobState.RUNNING);

        // Neu anfordern, dann ein falscher Code: die Anforderung bleibt offen, mit dem Fehler der Quelle.
        assertThat(signIns.resend(request.id())).hasValueSatisfying(again ->
                assertThat(again.message()).isEqualTo("Neuer Code gesendet"));
        char[] wrong = "000000".toCharArray();
        assertThat(signIns.submit(request.id(), wrong)).isEqualTo(SignInRequest.Answer.REJECTED);
        assertThat(wrong).containsOnly('\0');
        assertThat(signIns.request(request.id())).hasValueSatisfying(again ->
                assertThat(again.error()).isEqualTo("Code ungültig"));

        char[] right = "424242".toCharArray();
        assertThat(signIns.submit(request.id(), right)).isEqualTo(SignInRequest.Answer.ACCEPTED);
        assertThat(right).containsOnly('\0');
        worker.join(10_000);

        assertThat(signIns.pending()).isEmpty();
        assertThat(jobs.status(jobId).state()).isEqualTo(JobState.COMPLETED);
        ExportAssetManifest manifest = setup.writer.assets(setup.export.id()).orElseThrow();
        assertThat(manifest.complete()).isTrue();
        assertThat(manifest.forPage("100")).allSatisfy(e -> assertThat(e.present()).isTrue());
        assertThat(changes).isNotEmpty();
        // Der Code erreicht nur die Quelle.
        assertThat(source.codes).containsExactly("000000", "424242");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void declinedTheJobFinishesWithoutTheFilesAndAsksOnlyOnce() throws Exception {
        Source source = new Source(true);
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(5), Clock.systemUTC());
        Setup setup = setup(source, signIns);
        queued.poll().run();
        Thread worker = new Thread(this::drain);
        worker.start();

        SignInRequest request = awaitPending(signIns);
        assertThat(signIns.decline(request.id())).isTrue();
        worker.join(10_000);

        assertThat(signIns.pending()).isEmpty();
        ExportAssetManifest manifest = setup.writer.assets(setup.export.id()).orElseThrow();
        assertThat(manifest.complete()).isFalse();
        assertThat(manifest.assets()).filteredOn(e -> e.status() == ExportAssetManifest.Status.SIGN_IN_REQUIRED)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(signIns.submit(request.id(), "1".toCharArray())).isEqualTo(SignInRequest.Answer.GONE);

        // Später nachholen: ein neuer Auftrag für denselben Export.
        source.locked = false;
        assertThat(setup.conversions.collectImages(setup.export.id(), "client-a")).isPresent();
        drain();
        assertThat(setup.writer.assets(setup.export.id()).orElseThrow().complete()).isTrue();
    }

    private SignInRequest awaitPending(SignInRequests signIns) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (!signIns.pending().isEmpty()) {
                return signIns.pending().get(0);
            }
            Thread.sleep(25);
        }
        throw new AssertionError("keine Anforderung");
    }

    private record Setup(FileSystemExportArtifactWriter writer, ExportConversions conversions, JobStatus export) {
    }

    private Setup setup(Source source, SignInRequests signIns) {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(data.resolve("exports"));
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return Optional.of(source);
            }
        };
        Assets assets = new Assets(catalog, new FileSystemAssetStore(data.resolve("assets")), 1_000_000,
                Clock.systemUTC());
        ExportAssetCollection images = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog), assets,
                (owner, sourceId) -> true, signIns);
        Exports exports = new Exports(jobs, cache, writer, converters, Clock.systemUTC());
        exports.onPublished(images::start);
        ExportConversions conversions = new ExportConversions(jobs, writer, converters, images);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null)));
        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of("json"));
        return new Setup(writer, conversions, export);
    }

    private synchronized void drain() {
        Runnable next;
        while ((next = poll()) != null) {
            next.run();
        }
    }

    private Runnable poll() {
        synchronized (queued) {
            return queued.poll();
        }
    }

    /** Seite 100: draw.io mit Backup-PNG, ein Foto; mit {@code locked} verlangt jede Datei erst einen Einmalcode. */
    private static final class Source implements HarvestSource {

        volatile boolean locked;
        boolean brokenDrawio;
        final List<String> loaded = new ArrayList<>();
        final List<String> codes = new ArrayList<>();

        Source(boolean locked) {
            this.locked = locked;
        }

        @Override
        public String id() {
            return "confluence";
        }

        @Override
        public String type() {
            return "confluence";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of(new Partition("DEV", "Entwicklung", null));
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, "3",
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public PageAttachments attachments(String pageId) {
            // Die Vorschau steht in der Liste vor dem Diagramm, wie Confluence sie oft liefert.
            List<PageAttachment> list = List.of(
                    new PageAttachment("att-preview", "confluence:wiki/attachment/att-preview", "ablauf.png",
                            "image/png", (long) PNG.length, "2", null, null),
                    new PageAttachment("att-drawio", "confluence:wiki/attachment/att-drawio", "ablauf.drawio",
                            "application/vnd.jgraph.mxfile", (long) MXFILE.length, "2", null, null),
                    new PageAttachment("att-foto", "confluence:wiki/attachment/att-foto", "foto.png", "image/png",
                            (long) PNG.length, "1", null, null));
            return new PageAttachments(pageId, list, List.of(), "application/json", "test", List.of(),
                    Instant.parse("2026-10-18T01:00:00Z"));
        }

        @Override
        public synchronized AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            if (locked) {
                throw new SignInRequiredException(id(), "Einmalcode verlangt", new Challenge("Code per E-Mail gesendet",
                        ""), null);
            }
            loaded.add(attachment.attachmentId());
            byte[] content = attachment.attachmentId().equals("att-drawio")
                    ? (brokenDrawio ? "kein xml".getBytes(StandardCharsets.UTF_8) : MXFILE) : PNG;
            return new AttachmentFile(attachment, content, Instant.parse("2026-10-18T01:00:00Z"));
        }

        /** Die Ansicht zeigt die Vorschau des Diagramms über die Download-Adresse, ohne Anhangs-ID. */
        @Override
        public List<ViewImage> viewImages(String pageId) {
            return List.of(new ViewImage("https://wiki.example.org/download/attachments/100/ablauf.png?version=2",
                    null, null, "image/png", null));
        }

        @Override
        public ReadingPage readingPage(String pageId) {
            return new ReadingPage("3", "<p>Seite</p>", "https://wiki.example.org", "Seite", viewImages(pageId));
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            loaded.add("view:" + image.src());
            return PNG;
        }

        private final class Challenge implements SignInChallenge {

            private final String message;
            private final String error;

            Challenge(String message, String error) {
                this.message = message;
                this.error = error;
            }

            @Override
            public String service() {
                return "wiki.example.org";
            }

            @Override
            public String title() {
                return "Zwei-Faktor-Anmeldung";
            }

            @Override
            public String message() {
                return message;
            }

            @Override
            public String error() {
                return error;
            }

            @Override
            public boolean resendAvailable() {
                return true;
            }

            @Override
            public SignInChallenge submit(char[] code) {
                String value = new String(code);
                codes.add(value);
                if (!value.equals("424242")) {
                    return new Challenge(message, "Code ungültig");
                }
                locked = false;
                return null;
            }

            @Override
            public SignInChallenge resend() {
                return new Challenge("Neuer Code gesendet", "");
            }
        }
    }
}
