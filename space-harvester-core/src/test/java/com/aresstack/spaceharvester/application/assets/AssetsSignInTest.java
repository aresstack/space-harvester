package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.AttachmentPreview;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Einmalcode beim Lesen auf Abruf (ADR 0018), wie die Lese-Ansicht des Enterprise AI Client: statt „keine Berechtigung“
 * eine offene Anforderung, die alle Bilder und Anhänge der Quelle teilen; nach der Freischaltung lädt der Aufrufer neu.
 */
class AssetsSignInTest {

    private static final byte[] CONTENT = "Inhalt".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    @TempDir
    Path directory;

    private final LockedSource source = new LockedSource();

    private Assets assets(SignInRequests signIns) {
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return source.id().equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
        return new Assets(catalog, new FileSystemAssetStore(directory), 1_000_000, Clock.systemUTC(), null, null, null,
                LargeFileGate.defaults(), signIns);
    }

    private PageAttachmentList list() {
        return new PageAttachmentList("wiki", "DEV", source.attachments("100"), NOW);
    }

    @Test
    void aReaderGetsAnOpenRequestThatAllFilesOfTheSourceShareAndLoadsAfterTheCode() {
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), Clock.systemUTC());
        Assets assets = assets(signIns);

        SignInPendingException first = (SignInPendingException) org.assertj.core.api.Assertions.catchThrowable(
                () -> assets.content(list(), "att1", false, "anna"));
        SignInPendingException second = (SignInPendingException) org.assertj.core.api.Assertions.catchThrowable(
                () -> assets.content(list(), "att2", false, "anna"));

        assertThat(first.reason()).isEqualTo(SourceAccessException.Reason.ACCESS_DENIED);
        assertThat(first.sourceId()).isEqualTo("wiki");
        assertThat(second.signInRequestId()).isEqualTo(first.signInRequestId());
        assertThat(signIns.ownedBy(first.signInRequestId(), "anna")).isTrue();
        assertThat(first.getMessage()).doesNotContain("123456");

        assertThat(signIns.submit(first.signInRequestId(), "123456".toCharArray()))
                .isEqualTo(SignInRequest.Answer.ACCEPTED);

        assertThat(assets.content(list(), "att1", false, "anna").content()).isEqualTo(CONTENT);
        assertThat(source.codes).containsExactly("123456");
    }

    @Test
    void withoutOwnerOrUserInterfaceItStaysAccessDenied() {
        assertThatThrownBy(() -> assets(new SignInRequests(Duration.ofMinutes(15), Clock.systemUTC()))
                .content(list(), "att1", false, null))
                .isExactlyInstanceOf(SignInRequiredException.class);
        assertThatThrownBy(() -> assets(SignInRequests.none()).content(list(), "att1", false, "anna"))
                .isExactlyInstanceOf(SignInRequiredException.class);
        assertThatThrownBy(() -> assets(null).content(list(), "att1", false, "anna"))
                .isExactlyInstanceOf(SignInRequiredException.class);
    }

    @Test
    void whileTheRequestIsOpenFurtherReadsJoinItWithoutAskingTheSourceAgain() {
        List<String> logs = capture();
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), Clock.systemUTC());
        Assets assets = assets(signIns);
        HarvestSource.ViewImage image = new HarvestSource.ViewImage("https://wiki.example.org/download/bild.png",
                "Bild", null, "image/png", null);

        SignInPendingException first = (SignInPendingException) org.assertj.core.api.Assertions.catchThrowable(
                () -> assets.content(list(), "att1", false, "anna"));
        assertThat(source.calls).hasValue(1);

        // Weitere Anhänge, Bilder der Ansicht, Vorschauen und Ströme: dieselbe Anforderung, keine neue Abfrage, die
        // eine neue Zwei-Faktor-Seite und damit eine neue Sitzung brächte (#571).
        List<Throwable> held = List.of(
                org.assertj.core.api.Assertions.catchThrowable(() -> assets.content(list(), "att2", false, "anna")),
                org.assertj.core.api.Assertions.catchThrowable(() -> assets.viewImage("wiki", "100", "1", image,
                        "anna")),
                org.assertj.core.api.Assertions.catchThrowable(() -> assets.preview(list(), "att1",
                        AttachmentPreview.THUMBNAIL, false, "anna")),
                org.assertj.core.api.Assertions.catchThrowable(() -> assets.stream(list(), "att2", false, "bernd")));
        assertThat(held).allSatisfy(e -> assertThat(e).isExactlyInstanceOf(SignInPendingException.class)
                .extracting(x -> ((SignInPendingException) x).signInRequestId()).isEqualTo(first.signInRequestId()));
        assertThat(signIns.ownedBy(first.signInRequestId(), "bernd")).isTrue();
        // Ohne Halter wie von der Quelle: Anmeldung erforderlich, ebenfalls ohne Abfrage.
        assertThatThrownBy(() -> assets.content(list(), "att2", false))
                .isExactlyInstanceOf(SignInRequiredException.class);
        assertThatThrownBy(() -> assets.content(list(), "att2", false, null))
                .isExactlyInstanceOf(SignInRequiredException.class);
        assertThatThrownBy(() -> assets.viewImage("wiki", "100", "1", image))
                .isExactlyInstanceOf(SignInRequiredException.class);
        assertThat(source.calls).hasValue(1);

        assertThat(signIns.submit(first.signInRequestId(), "123456".toCharArray()))
                .isEqualTo(SignInRequest.Answer.ACCEPTED);

        // Der Code ging an die Seite der ersten Abfrage, zu der die Sitzung noch passt.
        assertThat(source.codes).containsExactly("123456");
        assertThat(source.submittedOnPage).containsExactly(1);
        assertThat(assets.content(list(), "att2", false, "anna").content()).isEqualTo(CONTENT);
        assertThat(assets.viewImage("wiki", "100", "1", image, "anna").content()).isEqualTo(CONTENT);
        assertThat(source.calls).hasValue(3);
        assertThat(logs).isNotEmpty().noneMatch(line -> line.contains("123456"));
    }

    @Test
    void simultaneousFirstReadsReachTheSourceOnceAndShareOneRequest() throws Exception {
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), Clock.systemUTC());
        Assets assets = assets(signIns);
        HarvestSource.ViewImage image = new HarvestSource.ViewImage("https://wiki.example.org/download/bild.png",
                "Bild", null, "image/png", null);
        source.gate = new java.util.concurrent.CountDownLatch(1);

        // Lese-Ansicht und Bildauftrag zugleich, bevor jemand weiß, dass die Quelle einen Code verlangt: bei Confluence
        // brächte jeder Abruf eine eigene Zwei-Faktor-Sitzung und eine eigene Mail mit Code (#571).
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Throwable> reader = pool.submit(() -> org.assertj.core.api.Assertions
                    .catchThrowable(() -> assets.viewImage("wiki", "100", "1", image, "anna")));
            while (source.calls.get() == 0) {
                Thread.onSpinWait();
            }
            java.util.concurrent.Future<Throwable> other = pool.submit(() -> org.assertj.core.api.Assertions
                    .catchThrowable(() -> assets.content(list(), "att1", false, "bernd")));
            Thread.sleep(200);
            assertThat(source.calls).hasValue(1);
            source.gate.countDown();

            Throwable first = reader.get(10, java.util.concurrent.TimeUnit.SECONDS);
            Throwable second = other.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first).isExactlyInstanceOf(SignInPendingException.class);
            assertThat(second).isExactlyInstanceOf(SignInPendingException.class);
            assertThat(((SignInPendingException) second).signInRequestId())
                    .isEqualTo(((SignInPendingException) first).signInRequestId());
            assertThat(source.calls).hasValue(1);

            assertThat(signIns.submit(((SignInPendingException) first).signInRequestId(), "123456".toCharArray()))
                    .isEqualTo(SignInRequest.Answer.ACCEPTED);
            assertThat(source.submittedOnPage).containsExactly(1);
            assertThat(assets.content(list(), "att1", false, "bernd").content()).isEqualTo(CONTENT);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whileTheRequestIsOpenStoredFilesStillComeFromTheStore() {
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), Clock.systemUTC());
        Assets assets = assets(signIns);
        source.locked = false;
        assets.content(list(), "att1", true);
        source.locked = true;

        SignInPendingException pending = (SignInPendingException) org.assertj.core.api.Assertions.catchThrowable(
                () -> assets.content(list(), "att2", false, "anna"));
        int calls = source.calls.get();

        assertThat(signIns.pendingFor("wiki")).map(SignInRequest::id).contains(pending.signInRequestId());
        assertThat(assets.content(list(), "att1", false, "anna").content()).isEqualTo(CONTENT);
        assertThat(source.calls).hasValue(calls);
    }

    private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("com.aresstack");
    private java.util.logging.Handler handler;

    @org.junit.jupiter.api.AfterEach
    void release() {
        if (handler != null) {
            logger.removeHandler(handler);
            logger.setLevel(null);
        }
    }

    /** Protokollzeilen unter {@code com.aresstack} ({@code System.Logger} läuft hier über java.util.logging). */
    private List<String> capture() {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        logger.setLevel(java.util.logging.Level.ALL);
        handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                lines.add(java.text.MessageFormat.format(String.valueOf(record.getMessage()),
                        record.getParameters() == null ? new Object[0] : record.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        return lines;
    }

    private static final class LockedSource implements HarvestSource {

        volatile boolean locked = true;
        final List<String> codes = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Jede Abfrage einer Datei, auch die abgewiesene: jede brächte bei Confluence eine neue Zwei-Faktor-Seite. */
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        /** Die Seite (Nummer der Abfrage), an die ein Code ging. */
        final List<Integer> submittedOnPage = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public java.util.Set<SourceCapability> capabilities() {
            java.util.Set<SourceCapability> capabilities = java.util.EnumSet.copyOf(HarvestSource.super.capabilities());
            capabilities.add(SourceCapability.ATTACHMENT_PREVIEWS);
            return capabilities;
        }

        /** Hält den Abruf in der Quelle an, bis er geöffnet wird ({@code null}: sofort). */
        volatile java.util.concurrent.CountDownLatch gate;

        private void guarded() {
            int page = calls.incrementAndGet();
            java.util.concurrent.CountDownLatch waiting = gate;
            if (waiting != null) {
                try {
                    waiting.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (locked) {
                throw new SignInRequiredException(id(), "Einmalcode verlangt", new Challenge(page), null);
            }
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            guarded();
            return CONTENT;
        }

        @Override
        public byte[] attachmentPreview(PageAttachment attachment, AttachmentPreview preview, long maxBytes) {
            guarded();
            return CONTENT;
        }

        @Override
        public String id() {
            return "wiki";
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
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, "1",
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public PageAttachments attachments(String pageId) {
            return new PageAttachments(pageId, List.of(attachment("att1"), attachment("att2")), List.of(),
                    "application/json", "test", List.of(), NOW);
        }

        private static PageAttachment attachment(String id) {
            return new PageAttachment(id, "confluence:wiki/attachment/" + id, id + ".txt", "text/plain",
                    (long) CONTENT.length, "1", null, null);
        }

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            guarded();
            return new AttachmentFile(attachment, CONTENT, NOW);
        }

        private final class Challenge implements SignInChallenge {

            private final int page;

            Challenge(int page) {
                this.page = page;
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
                return "";
            }

            @Override
            public String error() {
                return "";
            }

            @Override
            public boolean resendAvailable() {
                return false;
            }

            @Override
            public SignInChallenge submit(char[] code) {
                codes.add(new String(code));
                submittedOnPage.add(page);
                locked = false;
                return null;
            }

            @Override
            public SignInChallenge resend() {
                return this;
            }
        }
    }
}
