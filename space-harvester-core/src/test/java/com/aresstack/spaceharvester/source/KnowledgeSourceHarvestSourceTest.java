package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.OneTimeCodeChallenge;
import com.aresstack.enterpriseai.source.api.OneTimeCodeRequiredException;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.testing.InMemoryKnowledgeSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.aresstack.spaceharvester.application.signin.SignInFailures;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import org.junit.jupiter.api.Test;

/** Abbildung der Quellressourcen auf {@link PageMetadata}, insbesondere stabil sortierte Labels. */
class KnowledgeSourceHarvestSourceTest {

    @Test
    void labelsAreSortedAndDeduplicatedSoTheirOrderDoesNotChangeTheAttributesHash() {
        PageMetadata sourceOrder = only(new Listing("veraltet,api,architektur,api"));
        PageMetadata otherOrder = only(new Listing("api,architektur,veraltet"));

        assertThat(sourceOrder.attributes()).containsEntry("confluence.labels", "api,architektur,veraltet");
        assertThat(AttributesHash.of("wiki", sourceOrder)).isEqualTo(AttributesHash.of("wiki", otherOrder));
        assertThat(sourceOrder.pageId()).isEqualTo("101");
        assertThat(sourceOrder.parentPageId()).isEqualTo("100");
    }

    @Test
    void sourceWithoutSinglePageMetadataIsUnsupportedNotEmpty() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing("api"));

        assertThatThrownBy(() -> source.pageMetadata("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void sourceWithoutNativeResponsesIsUnsupportedNotEmpty() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing("api"));

        assertThatThrownBy(() -> source.storedPage("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
        assertThatThrownBy(() -> source.attachments("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void throttlingIsRateLimitedAndOtherFailuresKeepTheirKind() {
        KnowledgeSourceHarvestSource throttled = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing(new RateLimitedException("HTTP 429")));
        KnowledgeSourceHarvestSource down = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Listing(new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE, "HTTP 503")));

        assertThatThrownBy(() -> throttled.pageMetadata("DEV", null, 10))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.RATE_LIMITED));
        assertThatThrownBy(() -> down.pageMetadata("DEV", null, 10))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE));
    }

    @Test
    void theCallersLimitReachesTheTransportAndOversizedDownloadsAreTooLarge() {
        Files files = new Files();
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", files);
        PageAttachment attachment = new PageAttachment("att7", "confluence:wiki.example.org/attachment/att7", "a.png",
                "image/png", null, "1", null, null);

        assertThatThrownBy(() -> source.attachmentFile(attachment, 1024))
                .isInstanceOfSatisfying(AttachmentTooLargeException.class,
                        e -> assertThat(e.maxBytes()).isEqualTo(1024));
        assertThat(files.maxBytes).isEqualTo(1024);
        assertThatThrownBy(() -> source.attachmentFile(attachment, 10L * Integer.MAX_VALUE))
                .isInstanceOf(AttachmentTooLargeException.class);
        assertThat(files.maxBytes).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void withoutPageMetadataThereIsNoViewToReadImagesFrom() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Files());

        assertThatThrownBy(() -> source.viewImages("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void withoutPageMetadataThereIsNoReadingView() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new Files());

        assertThatThrownBy(() -> source.readingPage("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void aSourceWithoutPartitionsIsOnePartitionListedFromOneDiscovery() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));

        assertThat(source.partitions()).containsExactly(new Partition("notizen", "Notizen", null));
        assertThat(source.capabilities()).contains(SourceCapability.PAGE_LISTING, SourceCapability.PAGE_METADATA,
                SourceCapability.STORED_PAGE, SourceCapability.TITLE_SEARCH, SourceCapability.ADDRESSES)
                .doesNotContain(SourceCapability.LIVE_SEARCH, SourceCapability.NAVIGATION);
        PageMetadataBatch first = source.pageMetadata("notizen", null, 2);
        PageMetadataBatch second = source.pageMetadata("notizen", first.nextCursor(), 2);

        assertThat(first.pages()).extracting(PageMetadata::title).containsExactly("Start", "Betrieb");
        assertThat(second.pages()).extracting(PageMetadata::title).containsExactly("Kessel");
        assertThat(second.nextCursor()).isNull();
        // Folgestapel lesen aus derselben Discovery; die Quelle wird je Listung einmal gefragt.
        assertThat(notes.calls()).containsExactly("discover:[start]");
        PageMetadata kessel = second.pages().getFirst();
        assertThat(kessel.pageId()).isEqualTo(encoded(notes, "kessel"));
        assertThat(kessel.resourceId()).isEqualTo(notes.idOf("kessel").value());
        assertThat(kessel.parentPageId()).isEqualTo(encoded(notes, "betrieb"));
        assertThat(kessel.partitionKey()).isEqualTo("notizen");
        assertThat(kessel.version()).isEqualTo("1");
    }

    @Test
    void singlePagesAndTheirTextComeFromLoad() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));
        String betrieb = encoded(notes, "betrieb");

        assertThat(source.pageMetadata(betrieb).title()).isEqualTo("Betrieb");
        StoredPage page = source.storedPage(betrieb);

        assertThat(page.metadata().pageId()).isEqualTo(betrieb);
        assertThat(page.metadata().partitionKey()).isEqualTo("notizen");
        assertThat(new String(page.payload(), StandardCharsets.UTF_8)).isEqualTo("Nachtlauf um zwei");
        assertThat(page.mediaType()).isEqualTo("text/markdown");
        assertThat(page.nativeFormat()).isEqualTo(KnowledgeDocumentTextProjector.NATIVE_FORMAT);
        assertThat(notes.calls()).containsExactly("load:betrieb", "load:betrieb");
        assertThatThrownBy(() -> source.storedPage(encoded(notes, "fehlt")))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> source.pageMetadata("101")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    @Test
    void aRedirectIsNotFoundUnderTheRequestedPage() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourcePort redirecting = new WithoutSearch(notes) {
            @Override
            public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
                return notes.load(notes.idOf("betrieb"));
            }
        };
        KnowledgeSourceHarvestSource source = discovering(redirecting);

        assertThatThrownBy(() -> source.storedPage(encoded(notes, "start")))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
        assertThat(source.storedPage(encoded(notes, "betrieb")).metadata().title()).isEqualTo("Betrieb");
    }

    @Test
    void foreignCursorsAndPartitionsAreRejectedAndAChangeDuringTheListingStopsIt() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));

        assertThatThrownBy(() -> source.pageMetadata("notizen", "c1.abc", 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
        assertThatThrownBy(() -> source.pageMetadata("DEV", null, 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));

        String cursor = source.pageMetadata("notizen", null, 2).nextCursor();
        notes.update("kessel", "Kessel neu");
        source.pageMetadata("notizen", null, 2);

        assertThatThrownBy(() -> source.pageMetadata("notizen", cursor, 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE));
    }

    @Test
    void failuresOfTheseSourcesNameOnlyTheirKind() {
        InMemoryKnowledgeSource notes = notes().failWith("betrieb", KnowledgeSourceException.Kind.UNAVAILABLE);
        KnowledgeSourceHarvestSource source = discovering(new WithoutSearch(notes));

        assertThatThrownBy(() -> source.storedPage(encoded(notes, "betrieb")))
                .isInstanceOfSatisfying(SourceAccessException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("betrieb");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void titlesAreFoundInTheDiscoveryAndAnOwnSearchKeepsTheSamePageIds() {
        InMemoryKnowledgeSource notes = notes();
        KnowledgeSourceHarvestSource plain = discovering(new WithoutSearch(notes));
        KnowledgeSourceHarvestSource searchable = discovering(notes);

        assertThat(plain.findByTitle("kes", SearchSpaces.all(), 10)).extracting(HarvestSource.PageNode::pageId)
                .containsExactly(encoded(notes, "kessel"));
        assertThat(plain.findByTitle("kes", new SearchSpaces(Set.of("DEV"), Set.of()), 10)).isEmpty();
        assertThat(searchable.capabilities()).contains(SourceCapability.LIVE_SEARCH);
        assertThat(searchable.searchPages("Nachtlauf", 10)).extracting(SourceSearchMatch::pageId)
                .containsExactly(encoded(notes, "betrieb"));
    }

    @Test
    void resourceIdsAndBookmarksOfTheSourceAreItsAddresses() {
        KnowledgeSourcePort wiki = new KnowledgeSourcePort() {
            @Override
            public SourceAddress sourceId() {
                return SourceAddress.of("wiki", "wiki.intern/w");
            }

            @Override
            public List<KnowledgeResource> discover(SourceScope scope) {
                return List.of();
            }

            @Override
            public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.NOT_FOUND, "fehlt");
            }

            @Override
            public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
                return List.of();
            }
        };
        KnowledgeSourceHarvestSource source = discovering(wiki);
        String start = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "wiki:wiki.intern/w/Start".getBytes(StandardCharsets.UTF_8));

        assertThat(source.resolveAddress("wiki:wiki.intern/w/Start")).contains(start);
        assertThat(source.resolveAddress("wiki://wiki.intern/w/Start")).contains(start);
        assertThat(source.resolveAddress("wiki:anderes.intern/w/Start")).isEmpty();
        assertThat(source.resolveAddress("confluence:wiki.intern/w/Start")).isEmpty();
    }

    @Test
    void withoutScopeASourceWithoutPartitionsStaysUnsupported() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("notizen", "notes", "Notizen",
                new WithoutSearch(notes()));

        assertThat(source.partitions()).isEmpty();
        assertThatThrownBy(() -> source.pageMetadata("notizen", null, 2))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
    }

    /** Start → Betrieb → Kessel, dazu eine Seite außerhalb des Umfangs; Fake-Quelle aus den Fixtures der Kopie. */
    private static InMemoryKnowledgeSource notes() {
        return new InMemoryKnowledgeSource("notes://notes.intern", "notes")
                .add("start", "Start", "Willkommen", "betrieb")
                .add("betrieb", "Betrieb", "Nachtlauf um zwei")
                .addChild("betrieb", "kessel", "Kessel", "Kessel prüfen")
                .add("verwaist", "Verwaist", "außerhalb");
    }

    private static KnowledgeSourceHarvestSource discovering(KnowledgeSourcePort port) {
        return new KnowledgeSourceHarvestSource("notizen", "notes", "Notizen", port, null,
                SourceScope.builder().startPoints("start").maxDepth(3).build());
    }

    private static String encoded(InMemoryKnowledgeSource notes, String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(notes.idOf(key).value()
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Nur {@code discover} und {@code load}: ohne Suche, ohne Partitionen. */
    private static class WithoutSearch implements KnowledgeSourcePort {

        private final InMemoryKnowledgeSource notes;

        WithoutSearch(InMemoryKnowledgeSource notes) {
            this.notes = notes;
        }

        @Override
        public SourceAddress sourceId() {
            return notes.sourceId();
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
            return notes.discover(scope);
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
            return notes.load(resourceId);
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
            return notes.discoverLinks(resourceId);
        }
    }

    private static PageMetadata only(Listing port) {
        return new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", port)
                .pageMetadata("DEV", null, 10).pages().get(0);
    }

    /** Anhänge, deren Download immer an der Grenze scheitert; merkt sich die Grenze. */
    private static final class Files implements KnowledgeSourcePort, PageViewSource {

        int maxBytes;

        @Override
        public boolean isAttachment(KnowledgeResourceId id) {
            return true;
        }

        @Override
        public SourceFile loadAttachmentFile(KnowledgeResourceId attachment, int maxBytes)
                throws KnowledgeSourceException {
            this.maxBytes = maxBytes;
            throw new ResponseTooLargeException("zu groß", maxBytes);
        }

        @Override
        public PageView pageView(KnowledgeResourceId page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] loadViewImage(String src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SourceAddress sourceId() {
            return SourceAddress.of("confluence-dc");
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void theSpaceSelectionGoesIntoTheQueryWithoutListingAllOtherSpaces() {
        Searchable port = new Searchable(List.of(), new KnowledgeSourceException(KnowledgeSourceException.Kind
                .ACCESS_DENIED, "Spaces"));
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", port);

        // Die Spaceliste wird dafür nicht gebraucht: auch wenn sie nicht lesbar ist, läuft die Suche.
        source.searchPages("x", 10, new SearchSpaces(Set.of("A"), Set.of("B")));

        assertThat(port.last.includedPartitions()).containsExactly("A");
        assertThat(port.last.excludedPartitions()).containsExactly("B");
    }

    /** Sucht ohne Treffer und merkt sich die letzte Anfrage. */
    private static final class Searchable implements SearchableKnowledgeSource {

        private final List<String> partitions;
        private final KnowledgeSourceException failure;
        private SourceQuery last;

        Searchable(List<String> partitions, KnowledgeSourceException failure) {
            this.partitions = partitions;
            this.failure = failure;
        }

        @Override
        public List<SourceSearchHit> search(SourceQuery query) {
            last = query;
            return List.of();
        }

        @Override
        public List<SourcePartition> partitions() throws KnowledgeSourceException {
            if (failure != null) {
                throw failure;
            }
            return partitions.stream().map(key -> new SourcePartition(key, key)).toList();
        }

        @Override
        public SourceAddress sourceId() {
            return SourceAddress.of("confluence-dc");
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Liefert genau eine Seite mit den angegebenen Labels. */
    private static final class Listing implements KnowledgeSourcePort, PartitionListingSource {

        private final String labels;
        private final KnowledgeSourceException failure;

        Listing(String labels) {
            this.labels = labels;
            this.failure = null;
        }

        Listing(KnowledgeSourceException failure) {
            this.labels = "";
            this.failure = failure;
        }

        @Override
        public PartitionResources resources(String partitionKey, String cursor, int limit)
                throws KnowledgeSourceException {
            if (failure != null) {
                throw failure;
            }
            KnowledgeResource page = KnowledgeResource.builder(
                            KnowledgeResourceId.of("confluence", "wiki.example.org/page/101"),
                            SourceAddress.of("confluence-dc"))
                    .title("Schnittstellen")
                    .revision(KnowledgeRevision.of(Instant.parse("2026-10-15T10:30:00Z"), "3"))
                    .parentId(KnowledgeResourceId.of("confluence", "wiki.example.org/page/100"))
                    .scope("DEV")
                    .metadata(KnowledgeMetadata.of(Map.of("confluence.labels", labels)))
                    .build();
            return new PartitionResources(List.of(page), "");
        }

        @Override
        public SourceAddress sourceId() {
            return SourceAddress.of("confluence-dc");
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * #571: scheitert das Senden des Einmalcodes im Adapter, bleibt dessen Meldung samt Ursachenkette in der
     * {@link com.aresstack.spaceharvester.application.sources.SourceAccessException} erhalten, ohne Query und
     * Geheimnisse; daraus nennt {@link SignInFailures} die Art des Fehlers.
     */
    @Test
    void aFailedOneTimeCodeKeepsTheAdaptersCauseWithoutQueryOrSecrets() {
        java.io.IOException transport = new java.net.HttpRetryException(
                "cannot retry due to proxy authentication, in streaming mode", 407);
        OneTimeCodeChallenge failing = new OneTimeCodeChallenge() {
            @Override
            public String service() {
                return "wiki.example.org";
            }

            @Override
            public String title() {
                return "";
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
            public OneTimeCodeChallenge submit(char[] code) throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE,
                        "Einmalcode nicht gesendet (HttpRetryException: https://wiki.example.org/otp?atl_token=t0k3n "
                                + "Cookie: JSESSIONID=S3SS10N)", transport);
            }

            @Override
            public OneTimeCodeChallenge resend() throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "kein neuer Code");
            }
        };
        KnowledgeSourcePort port = new SearchableKnowledgeSource() {
            @Override
            public List<SourceSearchHit> search(SourceQuery query) throws KnowledgeSourceException {
                throw new OneTimeCodeRequiredException("Einmalcode verlangt", failing, null);
            }

            @Override
            public SourceAddress sourceId() {
                return SourceAddress.of("confluence://wiki.example.org");
            }

            @Override
            public List<com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource> discover(SourceScope scope) {
                return List.of();
            }

            @Override
            public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "nicht hier");
            }

            @Override
            public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
                return List.of();
            }
        };
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki", port);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.searchPages("x", 5), SignInRequiredException.class);

        SourceAccessException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> required.challenge().submit("123456".toCharArray()), SourceAccessException.class);

        assertThat(failure.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE);
        assertThat(failure.getMessage()).startsWith("Einmalcode nicht gesendet (HttpRetryException: "
                + "https://wiki.example.org/otp?…").doesNotContain("t0k3n").doesNotContain("S3SS10N");
        assertThat(failure).hasRootCause(transport);
        assertThat(SignInFailures.describe(failure, required.challenge().service()))
                .isEqualTo("Proxy verlangt Anmeldung (HTTP 407) – Ziel wiki.example.org");
    }

    /**
     * #571: Mit Personal Access Token nimmt die Zwei-Faktor-Anmeldung von Confluence den Einmalcode nicht an und
     * antwortet mit HTTP 500. Die Quelle meldet das klar statt der bloßen 500.
     */
    @Test
    void aServerErrorAfterSendingTheCodeWithATokenSaysTokensAreNotAccepted() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                lockedBy(serverErrorAfterSending()), null, null, true);

        SourceAccessException failure = submitFails(source);

        assertThat(failure.reason()).isEqualTo(SourceAccessException.Reason.ACCESS_DENIED);
        assertThat(SignInFailures.describe(failure, "wiki.example.org"))
                .isEqualTo(SignInFailures.tokenLoginRejected(500) + " – Ziel wiki.example.org")
                .contains("Personal Access Token").contains("Benutzer und Passwort");
    }

    /** #571: Mit Benutzer und Passwort bleibt es bei der Art „Quelle antwortet mit HTTP 500“. */
    @Test
    void aServerErrorAfterSendingTheCodeWithAPasswordStaysAPlainHttpStatus() {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                lockedBy(serverErrorAfterSending()), null, null, false);

        SourceAccessException failure = submitFails(source);

        assertThat(SignInFailures.describe(failure, "wiki.example.org"))
                .isEqualTo("Quelle antwortet mit HTTP 500 – Ziel wiki.example.org");
    }

    /**
     * #571: Bei einer Quelle mit Personal Access Token fragt die Abfrage neben dem Code Benutzer und Passwort ab. Für
     * genau dieses Absenden löst der Adapter die Anmeldung als Benutzer und Passwort auf (Basic an dieselbe
     * Web-Sitzung); davor und danach gilt das Token, das Passwort wird überschrieben.
     */
    @Test
    void aTokenSourceSendsTheCodeOnceWithUserAndPassword() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                lockedBy(resolvingOnSubmit(secrets, seen)), null, null, true);
        com.aresstack.spaceharvester.application.signin.SignInRequests signIns =
                new com.aresstack.spaceharvester.application.signin.SignInRequests(java.time.Duration.ofMinutes(15),
                        java.time.Clock.systemUTC());
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.searchPages("x", 5), SignInRequiredException.class);
        com.aresstack.spaceharvester.application.signin.SignInRequest request =
                signIns.open("wiki", "anna", required.challenge());
        char[] password = "pw-value".toCharArray();

        assertThat(request.asksForLogin()).isTrue();
        assertThat(signIns.submit(request.id(), "000000".toCharArray(), "anna", password))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.ACCEPTED);

        assertThat(seen).containsExactly("anna:pw-value");
        assertThat(password).containsOnly('\0');
        assertThat(principalAndSecret(secrets)).isEqualTo("-:pat-value");
    }

    /** #571: Mit Benutzer und Passwort konfiguriert fragt die Abfrage nichts zusätzlich ab und ersetzt nichts. */
    @Test
    void aPasswordSourceNeitherAsksForNorUsesAnExtraLogin() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", "anna", "configured");
        List<String> seen = new java.util.ArrayList<>();
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                lockedBy(resolvingOnSubmit(secrets, seen)), null, null, false);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.searchPages("x", 5), SignInRequiredException.class);

        assertThat(required.challenge().asksForLogin()).isFalse();
        assertThat(required.challenge().submit("000000".toCharArray(), "other", "pw-value".toCharArray())).isNull();

        assertThat(seen).containsExactly("anna:configured");
    }

    /** Wie der Confluence-Adapter: das Absenden löst die Anmeldung der Quelle neu auf. */
    private static OneTimeCodeChallenge resolvingOnSubmit(ConfiguredSecrets secrets, List<String> seen) {
        return new OneTimeCodeChallenge() {
            @Override
            public String service() {
                return "wiki.example.org";
            }

            @Override
            public String title() {
                return "";
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
            public OneTimeCodeChallenge submit(char[] code) {
                seen.add(principalAndSecret(secrets));
                return null;
            }

            @Override
            public OneTimeCodeChallenge resend() throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "kein neuer Code");
            }
        };
    }

    private static String principalAndSecret(ConfiguredSecrets secrets) {
        try {
            com.aresstack.enterpriseai.adyton.SecretMaterial material =
                    secrets.resolve(ConfiguredSecrets.refFor("wiki"));
            return (material.hasPrincipal() ? material.principal() : "-") + ":" + new String(material.copySecret());
        } catch (com.aresstack.enterpriseai.adyton.SecretUnavailableException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * #571: Die Web-Sitzung, die ein Bearer-Abruf eröffnet hat, nimmt keinen Code an (HTTP 500, auch mit Basic). Mit
     * Benutzer und Passwort läuft der letzte Dateiabruf darum in einer neuen Adapter-Instanz mit Basic noch einmal; der
     * Code beantwortet deren Code-Seite, danach kommen Dateien über diese Sitzung, wie bei Benutzer und Passwort.
     */
    @Test
    void aTokenSourceAnswersTheCodeInANewWebSessionWithUserAndPassword() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles tokenPort = new WebFiles(secrets, seen, "token-port", 1, null);
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        int[] opened = {0};
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                tokenPort, null, null, true, () -> {
                    opened[0]++;
                    return webPort;
                });
        assertThat(opened[0]).isZero();
        HarvestSource.ViewImage image =
                new HarvestSource.ViewImage("/download/attachments/1/a.png", "a",
                        null, null, null);
        // Wie das Codefeld: Anforderung öffnen, dann Code mit Benutzer und Passwort über SignInRequests senden. In
        // beta.364 hing genau dieses Absenden ohne Protokollzeile (#571); es muss sofort zurückkommen.
        com.aresstack.spaceharvester.application.signin.SignInRequests signIns =
                new com.aresstack.spaceharvester.application.signin.SignInRequests(java.time.Duration.ofMinutes(15),
                        java.time.Clock.systemUTC());
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(image, 1000), SignInRequiredException.class);
        // Die zweite Web-Sitzung entsteht, wenn Confluence den Code anfordert, nicht beim Start und nicht beim Absenden.
        assertThat(opened[0]).isEqualTo(1);
        assertThat(source.webPort()).isSameAs(webPort);
        com.aresstack.spaceharvester.application.signin.SignInRequest request =
                signIns.open("wiki", "anna", required.challenge());
        assertThat(request.asksForLogin()).isTrue();
        char[] password = "pw-value".toCharArray();

        assertThat(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> signIns.submit(request.id(), "000000".toCharArray(), "anna", password)))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.ACCEPTED);

        assertThat(password).containsOnly('\0');
        assertThat(opened[0]).isEqualTo(1);
        assertThat(seen).containsExactly("token-port image -:pat-value", "web-port image anna:pw-value",
                "web-port code anna:pw-value");
        assertThat(source.viewImage(image, 1000)).containsExactly(1, 2, 3);
        assertThat(seen).last().isEqualTo("web-port image anna:pw-value");
        assertThat(principalAndSecret(secrets)).isEqualTo("-:pat-value");
    }

    /**
     * #571: Nimmt die neue Web-Sitzung den schon gesendeten Code nicht an, bleibt die Abfrage offen, nennt den neuen
     * Code und fragt nur noch ihn ab; gesendet wird er wieder mit Benutzer und Passwort.
     */
    @Test
    void aRejectedCodeInTheNewWebSessionAsksOnlyForTheNewCode() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles tokenPort = new WebFiles(secrets, seen, "token-port", 1, null);
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, "rejectFirst");
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                tokenPort, null, null, true, () -> webPort);
        HarvestSource.ViewImage image =
                new HarvestSource.ViewImage("/download/attachments/1/a.png", "a",
                        null, null, null);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(image, 1000), SignInRequiredException.class);

        com.aresstack.spaceharvester.application.sources.SignInChallenge again =
                required.challenge().submit("000000".toCharArray(), "anna", "pw-value".toCharArray());

        assertThat(again).isNotNull();
        assertThat(again.asksForLogin()).isFalse();
        assertThat(again.message()).isEqualTo(KnowledgeSourceHarvestSource.NEW_SESSION_NOTE);
        assertThat(again.error()).as("ein Hinweis, kein Fehler").isEmpty();
        assertThat(again.submit("111111".toCharArray())).isNull();
        assertThat(seen).endsWith("web-port code anna:pw-value", "web-port code anna:pw-value");
    }

    /**
     * Erster Schritt des Codefelds bei einem Token (Angelo 10.11.2026): nur Benutzer und Passwort, kein Code. Die neue
     * Web-Sitzung meldet sich an, sendet keinen Code und fragt nur den Code ab, den Confluence ihr schickt.
     */
    @Test
    void anEmptyCodeOnlySignsInAndAsksForTheCodeOfTheNewSession() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles tokenPort = new WebFiles(secrets, seen, "token-port", 1, null);
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                tokenPort, null, null, true, () -> webPort);
        HarvestSource.ViewImage image = new HarvestSource.ViewImage("/download/attachments/1/a.png", "a", null, null,
                null);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(image, 1000), SignInRequiredException.class);

        com.aresstack.spaceharvester.application.sources.SignInChallenge again =
                required.challenge().submit(new char[0], "anna", "pw-value".toCharArray());

        assertThat(again).isNotNull();
        assertThat(again.asksForLogin()).isFalse();
        assertThat(again.message()).isEqualTo(KnowledgeSourceHarvestSource.LOGIN_NOTE);
        assertThat(again.error()).as("ein Hinweis, kein Fehler").isEmpty();
        assertThat(seen).as("angemeldet, aber kein Code gesendet").endsWith("web-port image anna:pw-value")
                .doesNotContain("web-port code anna:pw-value");
        assertThat(again.submit("111111".toCharArray())).isNull();
        assertThat(seen).last().isEqualTo("web-port code anna:pw-value");
    }

    /** Über die Anforderung: Anmelden meldet den nächsten Schritt, nicht „Code nicht angenommen“. */
    @Test
    void beginWebLoginSignsInWithoutACodeAndThenTakesTheCodeOfTheNewSession() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles tokenPort = new WebFiles(secrets, seen, "token-port", 1, null);
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                tokenPort, null, null, true, () -> webPort);
        HarvestSource.ViewImage image = new HarvestSource.ViewImage("/download/attachments/1/a.png", "a", null, null,
                null);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(image, 1000), SignInRequiredException.class);
        var signIns = new com.aresstack.spaceharvester.application.signin.SignInRequests(
                java.time.Duration.ofMinutes(15), java.time.Clock.systemUTC());
        var request = signIns.open("wiki", "user", required.challenge());
        char[] password = "pw-value".toCharArray();

        assertThat(signIns.beginWebLogin(request.id(), "anna", password))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.NEXT_STEP);
        assertThat(password).containsOnly('\0');
        var second = signIns.request(request.id()).orElseThrow();
        assertThat(second.asksForLogin()).isFalse();
        assertThat(second.message()).isEqualTo(KnowledgeSourceHarvestSource.LOGIN_NOTE);
        assertThat(second.error()).isEmpty();
        assertThat(seen).doesNotContain("web-port code anna:pw-value");

        assertThat(signIns.submit(request.id(), "111111".toCharArray()))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.ACCEPTED);
        assertThat(seen).last().isEqualTo("web-port code anna:pw-value");
        assertThat(source.viewImage(image, 1000)).containsExactly(1, 2, 3);
        assertThat(principalAndSecret(secrets)).isEqualTo("-:pat-value");
    }

    /**
     * Neu anmelden (Angelo 11.11.2026): Ist die Web-Sitzung abgelaufen, lässt sich aus dem Codefeld zurück auf
     * Benutzer und Passwort wechseln; das öffnet eine neue Web-Sitzung wie beim ersten Mal, deren Code dann gilt.
     */
    @Test
    void anExpiredWebSessionCanBeReplacedByLoggingInAgain() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles tokenPort = new WebFiles(secrets, seen, "token-port", 1, null);
        java.util.Deque<WebFiles> webPorts = new java.util.ArrayDeque<>(List.of(
                new WebFiles(secrets, seen, "web-1", 1, null), new WebFiles(secrets, seen, "web-2", 1, null)));
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                tokenPort, null, null, true, () -> webPorts.size() > 1 ? webPorts.poll() : webPorts.peek());
        HarvestSource.ViewImage image = new HarvestSource.ViewImage("/download/attachments/1/a.png", "a", null, null,
                null);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(image, 1000), SignInRequiredException.class);
        var signIns = new com.aresstack.spaceharvester.application.signin.SignInRequests(
                java.time.Duration.ofMinutes(15), java.time.Clock.systemUTC());
        var request = signIns.open("wiki", "user", required.challenge());
        assertThat(signIns.beginWebLogin(request.id(), "anna", "pw-value".toCharArray()))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.NEXT_STEP);
        var codeStep = signIns.request(request.id()).orElseThrow();
        assertThat(codeStep.asksForLogin()).isFalse();
        assertThat(codeStep.canRestartLogin()).as("zurück auf Benutzer und Passwort möglich").isTrue();

        assertThat(signIns.beginWebLogin(request.id(), "anna", "pw-new".toCharArray()))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.NEXT_STEP);
        assertThat(signIns.submit(request.id(), "222222".toCharArray()))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.ACCEPTED);
        assertThat(seen).last().as("der Code geht an die neue Sitzung").isEqualTo("web-2 code anna:pw-new");
        assertThat(principalAndSecret(secrets)).isEqualTo("-:pat-value");
    }

    /**
     * #571: Scheitert das erste Absenden in der neuen Web-Sitzung (Netz), bleibt deren Code-Seite die Abfrage; der
     * nächste Versuch geht wieder an diese Sitzung, ohne eine weitere zu öffnen.
     */
    @Test
    void aFailedSendInTheNewWebSessionKeepsThatSessionsPage() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles tokenPort = new WebFiles(secrets, seen, "token-port", 1, null);
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, "failFirst");
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                tokenPort, null, null, true, () -> webPort);
        HarvestSource.ViewImage image = new HarvestSource.ViewImage("/download/attachments/1/a.png", "a", null, null,
                null);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(image, 1000), SignInRequiredException.class);

        com.aresstack.spaceharvester.application.sources.SignInChallenge again =
                required.challenge().submit("000000".toCharArray(), "anna", "pw-value".toCharArray());

        assertThat(again).isNotNull();
        assertThat(again.asksForLogin()).isFalse();
        assertThat(again.error()).isEqualTo("Verbindung abgelehnt (ConnectException) – Ziel wiki.example.org");
        assertThat(again.submit("000000".toCharArray())).isNull();
        assertThat(seen).endsWith("web-port code anna:pw-value", "web-port code anna:pw-value");
    }

    /**
     * #571, Angelo 11.11.2026: Lehnt Confluence Benutzer und Passwort der Web-Anmeldung ab (HTTP 401), sagt das
     * Codefeld genau das statt „Quelle antwortet mit HTTP 401“ und fragt weiter Benutzer und Passwort ab.
     */
    @Test
    void aRejectedWebLoginSaysThatUserOrPasswordWereRefused() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new WebFiles(secrets, seen, "token-port", 1, null), null, null, true,
                () -> new WebFiles(secrets, seen, "web-port", 0, "wrongPassword"));
        com.aresstack.spaceharvester.application.signin.SignInRequests signIns =
                new com.aresstack.spaceharvester.application.signin.SignInRequests(java.time.Duration.ofMinutes(15),
                        java.time.Clock.systemUTC());
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);
        com.aresstack.spaceharvester.application.signin.SignInRequest request =
                signIns.open("wiki", "anna", required.challenge());

        assertThat(signIns.submit(request.id(), "000000".toCharArray(), "anna", "falsch".toCharArray()))
                .isEqualTo(com.aresstack.spaceharvester.application.signin.SignInRequest.Answer.FAILED);

        com.aresstack.spaceharvester.application.signin.SignInRequest after = signIns.request(request.id())
                .orElseThrow();
        assertThat(after.failure()).startsWith("Confluence lehnt Benutzername oder Passwort ab (HTTP 401)")
                .doesNotContain("falsch");
        assertThat(after.asksForLogin()).isTrue();
        assertThat(seen).containsExactly("token-port image -:pat-value", "web-port image anna:falsch");
    }

    /** Quelle mit Token, schon mit Benutzer und Passwort in der eigenen Web-Sitzung angemeldet (#571). */
    private static KnowledgeSourceHarvestSource signedInWebSession(WebFiles tokenPort, WebFiles webPort) {
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                tokenPort, null, null, true, () -> webPort);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);
        assertThat(required.challenge().submit("000000".toCharArray(), "anna", "pw-value".toCharArray())).isNull();
        return source;
    }

    private static final HarvestSource.ViewImage IMAGE = new HarvestSource.ViewImage("/download/attachments/1/a.png",
            "a", null, null, null);

    /**
     * #571, Angelo 11.11.2026: Confluence beendet die Web-Sitzung, ein Bild bringt eine neue Code-Seite; bis zum
     * Absenden ist der Benutzer über den Browser wieder freigeschaltet, die Seite antwortet mit HTTP 500. Dann lädt das
     * Bild einmal neu, kommt ohne Code, und die Anmeldung gilt als erledigt, statt bei der 500 stehen zu bleiben.
     */
    @Test
    void aServerErrorOnSubmitReloadsOnceAndAcceptsWhenTheFileNowLoads() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                webPort);
        webPort.locked = 1;
        webPort.serverErrorNext = true;
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);
        assertThat(required.challenge().asksForLogin()).isFalse();
        seen.clear();

        assertThat(required.challenge().submit("000000".toCharArray())).isNull();

        assertThat(seen).containsExactly("web-port code anna:pw-value", "web-port image anna:pw-value");
        assertThat(source.viewImage(IMAGE, 1000)).containsExactly(1, 2, 3);
    }

    /**
     * #571: Bringt das Neuladen nach HTTP 500 eine neue Code-Seite (neue Mail), gilt sie: das Codefeld nennt den Grund
     * und fragt nur den neuen Code ab, der dann an dieselbe Web-Sitzung geht.
     */
    @Test
    void aServerErrorOnSubmitThatReloadsToANewCodePageAsksForTheNewCode() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                webPort);
        webPort.locked = 2;
        webPort.serverErrorNext = true;
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);

        com.aresstack.spaceharvester.application.sources.SignInChallenge again =
                required.challenge().submit("000000".toCharArray());

        assertThat(again).isNotNull();
        assertThat(again.asksForLogin()).isFalse();
        assertThat(again.message()).isEqualTo(KnowledgeSourceHarvestSource.RELOADED_NOTE);
        assertThat(again.error()).isEmpty();
        assertThat(again.submit("111111".toCharArray())).isNull();
        assertThat(seen).last().isEqualTo("web-port code anna:pw-value");
    }

    /**
     * #571, Angelo 11.11.2026, 21:28: Ein Bild mit der Wurzel der Quelle als Adresse brachte das Dashboard, der
     * Neuversuch mit frischem REST-Cookie die Code-Seite einer neuen Sitzung (Mail, Code-Abfrage mitten im Lauf). Ein
     * Bild ohne Dateipfad geht darum nie ans Netz.
     */
    @Test
    void anImageWithoutAFilePathIsNeverFetched() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                new WebFiles(secrets, seen, "web-port", 0, null));
        seen.clear();

        for (String src : List.of("/", "https://wiki.example.org", "https://wiki.example.org/?x=1",
                "https://wiki.example.org/confluence/")) {
            SourceAccessException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> source.viewImage(new HarvestSource.ViewImage(src, "", null, null, null), 1000),
                    SourceAccessException.class);
            assertThat(failure.reason()).as(src).isEqualTo(SourceAccessException.Reason.NOT_FOUND);
        }

        assertThat(seen).isEmpty();
        assertThat(KnowledgeSourceHarvestSource.withoutFile("/download/attachments/1/a.png?version=2")).isFalse();
        assertThat(KnowledgeSourceHarvestSource.withoutFile("https://wiki.example.org/download/thumbnails/1/a.png"))
                .isFalse();
    }

    /**
     * #571, Angelo 11.11.2026: Der Proxy lehnte den Tunnel beim Absenden nach 20 s mit HTTP 503 ab; Confluence sah den
     * Code nie, derselbe Code galt zwei Minuten später. Darum geht er nach einer solchen Ablehnung gleich noch einmal.
     */
    @Test
    void aTunnelTheProxyRefusesOnceSendsTheSameCodeAgain() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                webPort);
        webPort.locked = 1;
        webPort.refusedTunnels = 1;
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);
        seen.clear();

        assertThat(required.challenge().submit("000000".toCharArray())).isNull();

        assertThat(seen).containsExactly("web-port code anna:pw-value", "web-port code anna:pw-value");
    }

    /**
     * #571: Scheiterte der Tunnel erst auf der Weiterleitung nach dem angenommenen Code, ist die Seite beim zweiten
     * Senden verbraucht (HTTP 500). Dann lädt der Dateiabruf neu wie nach jeder 500, und die Anmeldung gilt.
     */
    @Test
    void aCodeResentAfterATunnelFailureOnTheRedirectRecoversThroughTheFileFetch() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                webPort);
        webPort.locked = 1;
        webPort.refusedTunnels = 1;
        webPort.serverErrorNext = true;
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);
        seen.clear();

        assertThat(required.challenge().submit("000000".toCharArray())).isNull();

        assertThat(seen).containsExactly("web-port code anna:pw-value", "web-port code anna:pw-value",
                "web-port image anna:pw-value");
    }

    /** #571: Lehnt der Proxy auch den zweiten Tunnel ab, sagt das Codefeld, dass der Code noch gilt. */
    @Test
    void aTunnelTheProxyRefusesTwiceSaysTheCodeDidNotArriveAndKeepsThePage() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                webPort);
        webPort.locked = 1;
        webPort.refusedTunnels = 2;
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);
        seen.clear();

        SourceAccessException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> required.challenge().submit("000000".toCharArray()), SourceAccessException.class);

        assertThat(failure.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE);
        assertThat(com.aresstack.spaceharvester.application.signin.SignInFailures.describe(failure,
                "wiki.example.org")).startsWith("Der Proxy hat keine Verbindung zu Confluence aufgebaut (HTTP 503, "
                        + "auch im zweiten Versuch). Der Code hat Confluence nicht erreicht");
        assertThat(seen).hasSize(2);
        assertThat(required.challenge().submit("000000".toCharArray())).isNull();
    }

    /**
     * #571, Angelo 11.11.2026: Confluence nahm den Code an und leitete auf ein PDF mit 30 MB weiter; der Transport las
     * es bis zur Grenze für Antworten mit und meldete „zu groß“. Das ist kein abgelehnter Code: Der Abruf lädt die Datei
     * neu, und die Anmeldung gilt.
     */
    @Test
    void aTooLargeAnswerToTheCodeIsTheFileAndCountsAsAcceptedOnceItLoads() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                webPort);
        webPort.locked = 1;
        webPort.tooLargeNext = true;
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.viewImage(IMAGE, 1000), SignInRequiredException.class);
        seen.clear();

        assertThat(required.challenge().submit("000000".toCharArray())).isNull();

        assertThat(seen).containsExactly("web-port code anna:pw-value", "web-port image anna:pw-value");
    }

    private static final PageAttachment PDF = new PageAttachment("att7", "confluence:wiki.example.org/attachment/att7",
            "gross.pdf", "application/pdf", null, "1", null, null);

    /** Ein gestreamter Anhang landet im Ziel; jeder Versuch beginnt es von vorn (Angelo 11.11.2026). */
    @Test
    void aStreamedAttachmentArrivesInTheSinkWithoutTheMemoryPath() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new WebFiles(secrets, seen, "port", 0, null));
        MemorySink sink = new MemorySink();

        com.aresstack.spaceharvester.application.sources.StreamedAttachment streamed = source.streamAttachment(PDF,
                sink);

        assertThat(sink.bytes.toString(StandardCharsets.UTF_8)).isEqualTo("PDF-Inhalt");
        assertThat(sink.restarts).isEqualTo(1);
        assertThat(streamed.attachment().attachmentId()).isEqualTo("att7");
        assertThat(seen).containsExactly("port stream -:pat-value");
    }

    /**
     * Nach dem Code lädt nicht der gestreamte Abruf neu (sein Ziel ist verworfen), sondern die Probe: derselbe Download,
     * verworfen und nach {@link KnowledgeSourceHarvestSource#PROBE_BYTES} abgebrochen.
     */
    @Test
    void afterTheCodeTheProbeReloadsTheAttachmentNotTheStream() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new WebFiles(secrets, seen, "token-port", 1, null), null, null, true,
                () -> new WebFiles(secrets, seen, "web-port", 1, null));
        MemorySink sink = new MemorySink();
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.streamAttachment(PDF, sink), SignInRequiredException.class);

        assertThat(required.challenge().submit("000000".toCharArray(), "anna", "pw-value".toCharArray())).isNull();

        assertThat(seen).containsExactly("token-port stream -:pat-value",
                "web-port probe anna:pw-value",
                "web-port code anna:pw-value");
        assertThat(sink.bytes.size()).as("die Probe schreibt nie in das alte Ziel").isZero();
    }

    /** Bekommt die Probe statt der Code-Seite die Datei, nur größer als ihre Grenze, gilt die neue Sitzung. */
    @Test
    void aProbeAnsweredWithAFileTooLargeForItCountsAsSignedIn() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 0, null);
        webPort.probeTooLarge = true;
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new WebFiles(secrets, seen, "token-port", 1, null), null, null, true, () -> webPort);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.streamAttachment(PDF, new MemorySink()), SignInRequiredException.class);

        assertThat(required.challenge().submit("000000".toCharArray(), "anna", "pw-value".toCharArray())).isNull();

        MemorySink sink = new MemorySink();
        source.streamAttachment(PDF, sink);
        assertThat(sink.bytes.toString(StandardCharsets.UTF_8)).isEqualTo("PDF-Inhalt");
        assertThat(seen).containsExactly("token-port stream -:pat-value",
                "web-port probe anna:pw-value",
                "web-port stream anna:pw-value");
    }

    /**
     * Eine Datei größer als die Probe: Die neue Web-Sitzung fragt ihren Code trotzdem ab, denn die Probe lädt wirklich
     * und lehnt nicht schon nach der Größe aus den Angaben des Anhangs ab (Codex zu #668).
     */
    @Test
    void aFileLargerThanTheProbeStillAsksForTheCodeOfTheNewWebSession() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        webPort.probeTooLarge = true;
        KnowledgeSourceHarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
                new WebFiles(secrets, seen, "token-port", 1, null), null, null, true, () -> webPort);
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.streamAttachment(PDF, new MemorySink()), SignInRequiredException.class);

        com.aresstack.spaceharvester.application.sources.SignInChallenge again =
                required.challenge().submit(new char[0], "anna", "pw-value".toCharArray());

        assertThat(again).as("angemeldet ist erst mit dem Code der neuen Sitzung").isNotNull();
        assertThat(again.message()).isEqualTo(KnowledgeSourceHarvestSource.LOGIN_NOTE);
        assertThat(again.submit("111111".toCharArray())).isNull();
        assertThat(seen).containsExactly("token-port stream -:pat-value", "web-port probe anna:pw-value",
                "web-port code anna:pw-value");
    }

    /** Wie {@link #aServerErrorOnSubmitReloadsOnceAndAcceptsWhenTheFileNowLoads}, mit der Probe eines Anhangs. */
    @Test
    void aServerErrorOnSubmitReloadsTheProbeAndATooLargeFileMeansSignedIn() {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("wiki", null, "pat-value");
        List<String> seen = new java.util.ArrayList<>();
        WebFiles webPort = new WebFiles(secrets, seen, "web-port", 1, null);
        KnowledgeSourceHarvestSource source = signedInWebSession(new WebFiles(secrets, seen, "token-port", 1, null),
                webPort);
        webPort.locked = 1;
        webPort.serverErrorNext = true;
        webPort.probeTooLarge = true;
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.streamAttachment(PDF, new MemorySink()), SignInRequiredException.class);
        seen.clear();

        assertThat(required.challenge().submit("000000".toCharArray())).isNull();

        assertThat(seen).containsExactly("web-port code anna:pw-value",
                "web-port probe anna:pw-value");
    }

    /** Ziel im Speicher, das mitzählt, wie oft es von vorn begann. */
    private static final class MemorySink extends com.aresstack.spaceharvester.application.sources.AttachmentSink {

        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int restarts;

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes.write(b, off, len);
        }

        @Override
        public void restart() {
            restarts++;
            bytes.reset();
        }

        @Override
        public long maxBytes() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean exceeded() {
            return false;
        }
    }

    /**
     * Bild-Port wie Confluence mit Zwei-Faktor-Anmeldung: die ersten {@code locked} Bildabrufe liefern die Code-Seite,
     * danach das Bild. Merkt sich je Abruf und je Absenden, mit welcher Anmeldung der Adapter es täte.
     */
    private static final class WebFiles implements KnowledgeSourcePort, PageViewSource {

        private final ConfiguredSecrets secrets;
        private final List<String> seen;
        private final String name;
        private final String mode;
        private int locked;
        /** Das nächste Absenden endet wie bei Angelo am 11.11.2026 mit HTTP 500 (Seite nimmt keinen Code mehr an). */
        private boolean serverErrorNext;
        /** So oft lehnt der Proxy den Tunnel beim Absenden noch mit HTTP 503 ab (Angelo 11.11.2026). */
        private int refusedTunnels;
        /** Das nächste Absenden nimmt den Code an, die Weiterleitung auf die Datei ist aber zu groß (Angelo 11.11.2026). */
        private boolean tooLargeNext;
        /** Die Probe nach dem Code bekommt die Datei, aber größer als ihre Grenze. */
        private boolean probeTooLarge;

        WebFiles(ConfiguredSecrets secrets, List<String> seen, String name, int locked, String mode) {
            this.secrets = secrets;
            this.seen = seen;
            this.name = name;
            this.locked = locked;
            this.mode = mode;
        }

        @Override
        public byte[] loadViewImage(String src) throws KnowledgeSourceException {
            seen.add(name + " image " + principalAndSecret(secrets));
            if ("wrongPassword".equals(mode)) {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.ACCESS_DENIED,
                        "Bild nicht geladen: Confluence antwortet mit HTTP 401");
            }
            if (locked > 0) {
                locked--;
                throw new OneTimeCodeRequiredException("Einmalcode verlangt", page("rejectFirst".equals(mode)), null);
            }
            return new byte[] {1, 2, 3};
        }

        @Override
        public boolean isAttachment(KnowledgeResourceId id) {
            return true;
        }

        /**
         * Wie Bilder: die ersten {@code locked} Abrufe liefern die Code-Seite, ohne etwas zu schreiben. Die Probe nach
         * dem Code schreibt nicht in ein {@link com.aresstack.spaceharvester.application.sources.AttachmentSink}; ein
         * Fehler des Ziels kommt wie bei Confluence als {@code UNAVAILABLE} mit der Ursache zurück.
         */
        @Override
        public KnowledgeResource streamAttachmentFile(KnowledgeResourceId attachment, java.io.OutputStream sink)
                throws KnowledgeSourceException {
            boolean probe = !(sink instanceof com.aresstack.spaceharvester.application.sources.AttachmentSink);
            seen.add(name + (probe ? " probe " : " stream ") + principalAndSecret(secrets));
            if (locked > 0) {
                locked--;
                throw new OneTimeCodeRequiredException("Einmalcode verlangt", page(false), null);
            }
            try {
                if (probe && probeTooLarge) {
                    byte[] chunk = new byte[64 * 1024];
                    for (long sent = 0; sent <= KnowledgeSourceHarvestSource.PROBE_BYTES; sent += chunk.length) {
                        sink.write(chunk);
                    }
                }
                sink.write("PDF-Inhalt".getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE,
                        "Anhang: Confluence nicht erreichbar", e);
            }
            return attachmentResource(attachment);
        }

        /** Wie Confluence: die Größe aus den Angaben des Anhangs lehnt schon vor dem Download ab (Codex zu #668). */
        @Override
        public SourceFile loadAttachmentFile(KnowledgeResourceId attachment, int maxBytes)
                throws KnowledgeSourceException {
            seen.add(name + " file " + maxBytes + " " + principalAndSecret(secrets));
            if (probeTooLarge) {
                throw new ResponseTooLargeException("Anhang größer als " + maxBytes + " Bytes", maxBytes);
            }
            if (locked > 0) {
                locked--;
                throw new OneTimeCodeRequiredException("Einmalcode verlangt", page(false), null);
            }
            return new SourceFile(attachmentResource(attachment), "PDF-Inhalt".getBytes(StandardCharsets.UTF_8));
        }

        private static KnowledgeResource attachmentResource(KnowledgeResourceId id) {
            return KnowledgeResource.builder(id, SourceAddress.of("confluence://wiki.example.org"))
                    .title("gross.pdf")
                    .contentType("application/pdf")
                    .revision(KnowledgeRevision.of(Instant.parse("2026-11-11T20:00:00Z"), "1"))
                    .build();
        }

        private OneTimeCodeChallenge page(boolean rejectFirst) {
            boolean[] rejected = {!rejectFirst};
            boolean[] failed = {false};
            return new OneTimeCodeChallenge() {
                @Override
                public String service() {
                    return "wiki.example.org";
                }

                @Override
                public String title() {
                    return "";
                }

                @Override
                public String message() {
                    return "";
                }

                @Override
                public String error() {
                    return rejected[0] ? "Der Code wurde nicht angenommen." : "";
                }

                @Override
                public boolean resendAvailable() {
                    return true;
                }

                @Override
                public OneTimeCodeChallenge submit(char[] code) throws KnowledgeSourceException {
                    seen.add(name + " code " + principalAndSecret(secrets));
                    if (refusedTunnels > 0) {
                        refusedTunnels--;
                        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE,
                                "Einmalcode nicht gesendet (IOException)", new java.io.IOException("Unable to tunnel "
                                        + "through proxy. Proxy returns \"HTTP/1.1 503 Service Unavailable\""));
                    }
                    if (tooLargeNext) {
                        tooLargeNext = false;
                        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE,
                                "Einmalcode nicht gesendet (ConfluenceResponseTooLargeException)",
                                new com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence
                                        .ConfluenceResponseTooLargeException(1000));
                    }
                    if (serverErrorNext) {
                        serverErrorNext = false;
                        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.ACCESS_DENIED,
                                "Einmalcode gesendet, Confluence antwortet mit HTTP 500");
                    }
                    if ("token-port".equals(name)) {
                        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.ACCESS_DENIED,
                                "Einmalcode gesendet, Confluence antwortet mit HTTP 500");
                    }
                    if ("failFirst".equals(mode) && !failed[0]) {
                        failed[0] = true;
                        throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE,
                                "Einmalcode nicht gesendet (ConnectException)", new java.net.ConnectException("x"));
                    }
                    if (!rejected[0]) {
                        rejected[0] = true;
                        return this;
                    }
                    return null;
                }

                @Override
                public OneTimeCodeChallenge resend() {
                    return this;
                }
            };
        }

        @Override
        public PageView pageView(KnowledgeResourceId page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SourceAddress sourceId() {
            return SourceAddress.of("confluence://wiki.example.org");
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) {
            return List.of();
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
            return List.of();
        }
    }

    private static SourceAccessException submitFails(KnowledgeSourceHarvestSource source) {
        SignInRequiredException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> source.searchPages("x", 5), SignInRequiredException.class);
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> required.challenge().submit("000000".toCharArray()), SourceAccessException.class);
    }

    /** Eine Abfrage, deren Senden Confluence wie bei HaVdB mit Token mit HTTP 500 beantwortet. */
    private static OneTimeCodeChallenge serverErrorAfterSending() {
        return new OneTimeCodeChallenge() {
            @Override
            public String service() {
                return "wiki.example.org";
            }

            @Override
            public String title() {
                return "";
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
            public OneTimeCodeChallenge submit(char[] code) throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.ACCESS_DENIED,
                        "Einmalcode gesendet, Confluence antwortet mit HTTP 500");
            }

            @Override
            public OneTimeCodeChallenge resend() throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "kein neuer Code");
            }
        };
    }

    /** Ein Port, dessen Suche die Abfrage verlangt. */
    private static KnowledgeSourcePort lockedBy(OneTimeCodeChallenge challenge) {
        return new SearchableKnowledgeSource() {
            @Override
            public List<SourceSearchHit> search(SourceQuery query) throws KnowledgeSourceException {
                throw new OneTimeCodeRequiredException("Einmalcode verlangt", challenge, null);
            }

            @Override
            public SourceAddress sourceId() {
                return SourceAddress.of("confluence://wiki.example.org");
            }

            @Override
            public List<com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource> discover(SourceScope scope) {
                return List.of();
            }

            @Override
            public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
                throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED, "nicht hier");
            }

            @Override
            public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
                return List.of();
            }
        };
    }
}
