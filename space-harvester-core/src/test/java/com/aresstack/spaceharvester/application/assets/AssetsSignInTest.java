package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
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

    private static final class LockedSource implements HarvestSource {

        volatile boolean locked = true;
        final List<String> codes = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            if (locked) {
                throw new SignInRequiredException(id(), "Einmalcode verlangt", new Challenge(), null);
            }
            return new AttachmentFile(attachment, CONTENT, NOW);
        }

        private final class Challenge implements SignInChallenge {

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
