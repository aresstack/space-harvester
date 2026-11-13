package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.AssetText;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.DocumentRereads;
import com.aresstack.spaceharvester.application.assets.DocumentStructure;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.assets.PartRules;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StoredExtraction;
import com.aresstack.spaceharvester.application.assets.StructureSink;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import com.aresstack.spaceharvester.testcorpus.Archives;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * „Neu lesen“ (ADR 0026, Punkt 11, Abnahme 8): nach dem Neulesen ist der Text aktuell, Dokumente und Teile sind neu
 * gebildet und der alte Stand ist abgeräumt; scheitert das Neulesen, bleibt der alte Stand. Der Auftrag lässt sich
 * anhalten und im selben Eintrag fortsetzen, auch nach einem Neustart, und liest dabei jedes Dokument nur einmal.
 */
class DocumentRereadsTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final String NEXT_VERSION = "3.3-neu";
    private static final String OWNER = "test-client";
    private static final PageKey PAGE = new PageKey("wiki", "101");

    @TempDir
    Path directory;

    private final Map<String, byte[]> files = new HashMap<>();
    private final MovingClock clock = new MovingClock(NOW);
    private final InMemoryJobStore jobStore = new InMemoryJobStore();
    private final List<Jobs> opened = new ArrayList<>();
    private LucenePageTextIndex index;

    @AfterEach
    void close() {
        opened.forEach(Jobs::close);
        if (index != null) {
            index.close();
        }
    }

    @Test
    void afterAVersionChangeTheRereadMakesTheTextCurrentRebuildsDocumentsAndPartsAndRemovesTheOldState() {
        files.put("att1", DocumentFixtures.docx("Abnahme der Schleuse"));
        files.put("att2", Archives.zip(Map.of("pumpe.txt",
                "Wartung im Pumpenhaus".getBytes(StandardCharsets.UTF_8))));
        String docx = Assets.sha256(files.get("att1"));
        String zip = Assets.sha256(files.get("att2"));
        assets(new TikaDocumentStructure()).harvest(list(attachment("att1", "protokoll.docx", DocumentFixtures.DOCX),
                attachment("att2", "anlagen.zip", Archives.ZIP)));
        Optional<String> indexedBefore = index.attachmentInputSha256(PAGE, "att2");
        assertThat(indexedBefore).isPresent();

        Versioned next = new Versioned(title -> false);
        Assets upgraded = assets(next);
        assertThat(upgraded.storedText(docx)).map(AssetText::stale).contains(true);
        clock.advance();
        Jobs jobs = jobs();
        JobStatus started = rereads(jobs, upgraded).start("wiki", OWNER, Set.of());

        JobStatus done = jobs.status(started.id(), OWNER);
        assertThat(done.type()).isEqualTo(JobType.REREAD);
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.incomplete()).isFalse();
        assertThat(done.progress().discovered()).isEqualTo(2);
        assertThat(done.progress().written()).isEqualTo(2);
        assertThat(done.progress().failed()).isZero();
        assertThat(next.titles).containsExactlyInAnyOrder("protokoll.docx", "anlagen.zip");

        AssetText text = upgraded.storedText(docx).orElseThrow();
        assertThat(text.stale()).as("nach Neulesen aktuell").isFalse();
        assertThat(text.version()).isEqualTo(NEXT_VERSION);
        assertThat(text.text()).contains("Schleuse");
        // Dokumente und Teile neu gebildet, der alte Stand erst danach abgeräumt.
        FileSystemExtractionStore extractions = extractions();
        assertThat(extractions.all(docx)).extracting(stored -> stored.key().version()).containsExactly(NEXT_VERSION);
        List<StoredExtraction> archive = extractions.all(zip);
        assertThat(archive).extracting(stored -> stored.key().version()).containsExactly(NEXT_VERSION);
        assertThat(archive.getFirst().document().directory()).as("Teile des Behälters").isNotEmpty();
        assertThat(store().text(docx, TikaDocumentStructure.ID, TikaDocumentStructure.VERSION))
                .as("Text der alten Version entfernt").isEmpty();
        // Der Volltext kommt aus dem neuen Stand, auch der Teil im Behälter.
        assertThat(index.attachmentInputSha256(PAGE, "att2")).isPresent().isNotEqualTo(indexedBefore);
        assertThat(index.search("Pumpenhaus", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
        assertThat(index.search("Schleuse", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);

        // Ein zweiter Lauf findet nichts mehr zu tun.
        JobStatus again = rereads(jobs, upgraded).start("wiki", OWNER, Set.of());
        assertThat(jobs.status(again.id(), OWNER).progress().written()).isZero();
        assertThat(next.titles).hasSize(2);
    }

    @Test
    void aFailedRereadKeepsTheOldStateUntilARereadOfTheFailedDocumentsSucceeds() {
        files.put("att1", DocumentFixtures.docx("Abnahme der Schleuse"));
        files.put("att2", DocumentFixtures.docx("Prüfung der Pumpe"));
        String broken = Assets.sha256(files.get("att1"));
        String fine = Assets.sha256(files.get("att2"));
        PageAttachmentList list = list(attachment("att1", "protokoll.docx", DocumentFixtures.DOCX),
                attachment("att2", "pruefung.docx", DocumentFixtures.DOCX));
        assets(new TikaDocumentStructure()).harvest(list);

        Versioned failing = new Versioned("protokoll.docx"::equals);
        Assets upgraded = assets(failing);
        clock.advance();
        Jobs jobs = jobs();
        JobStatus first = jobs.status(rereads(jobs, upgraded).start("wiki", OWNER, Set.of()).id(), OWNER);

        assertThat(first.state()).isEqualTo(JobState.COMPLETED);
        assertThat(first.incomplete()).as("fertig mit Fehlern").isTrue();
        assertThat(first.progress().written()).isEqualTo(1);
        assertThat(first.progress().failed()).isEqualTo(1);
        AssetText kept = upgraded.storedText(broken).orElseThrow();
        assertThat(kept.stale()).as("der alte Stand bleibt, als veraltet").isTrue();
        assertThat(kept.text()).contains("Schleuse");
        assertThat(extractions().all(broken)).extracting(stored -> stored.key().version())
                .contains(TikaDocumentStructure.VERSION);
        assertThat(index.search("Schleuse", Set.of("wiki"), SearchSpaces.all(), 10)).hasSize(1);
        assertThat(upgraded.storedText(fine)).map(AssetText::stale).contains(false);

        // Beschränkt auf den Status „gescheitert“: nur das gescheiterte Dokument wird erneut gelesen.
        Versioned repaired = new Versioned(title -> false);
        Assets fixed = assets(repaired);
        clock.advance();
        JobStatus retried = jobs.status(rereads(jobs, fixed).start("wiki", OWNER,
                EnumSet.of(ExtractionStatus.FAILED)).id(), OWNER);

        assertThat(retried.state()).isEqualTo(JobState.COMPLETED);
        assertThat(retried.progress().written()).isEqualTo(1);
        assertThat(repaired.titles).containsExactly("protokoll.docx");
        AssetText current = fixed.storedText(broken).orElseThrow();
        assertThat(current.stale()).isFalse();
        assertThat(current.version()).isEqualTo(NEXT_VERSION);
        assertThat(extractions().all(broken)).extracting(stored -> stored.key().version())
                .containsExactly(NEXT_VERSION);
        assertThat(DocumentRereads.statuses(retried)).containsExactly(ExtractionStatus.FAILED);
    }

    @Test
    void aPausedRereadContinuesInTheSameEntryAlsoAfterARestartAndReadsEachDocumentOnce() {
        List<PageAttachment> attachments = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            files.put("att" + i, DocumentFixtures.docx("Abschnitt Nummer " + i));
            attachments.add(attachment("att" + i, "teil" + i + ".docx", DocumentFixtures.DOCX));
        }
        assets(new TikaDocumentStructure()).harvest(list(attachments.toArray(PageAttachment[]::new)));
        Jobs jobs = jobs();
        Versioned next = new Versioned(title -> false);
        next.afterRead = () -> {
            if (next.titles.size() == 1) {
                jobs.pause(jobs.list(OWNER, JobType.REREAD).getFirst().id(), OWNER);
            }
        };
        Assets upgraded = assets(next);
        clock.advance();
        String id = rereads(jobs, upgraded).start("wiki", OWNER, Set.of()).id();

        JobStatus paused = jobs.status(id, OWNER);
        assertThat(paused.state()).isEqualTo(JobState.PAUSED);
        assertThat(paused.progress().written()).as("das neu Gelesene bleibt").isEqualTo(1);
        assertThat(next.titles).hasSize(1);
        assertThat(paused.lifecycle().actions()).contains(com.aresstack.spaceharvester.application.jobs.JobAction
                .RESUME);

        // Neustart: ein neuer Auftragsdienst über derselben Ablage setzt aus den abgelegten Angaben fort.
        Jobs restarted = jobs();
        JobStatus resumed = rereads(restarted, upgraded).resume(id, OWNER);
        assertThat(resumed.id()).isEqualTo(id);
        JobStatus done = restarted.status(id, OWNER);
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(next.titles).as("jedes Dokument einmal").containsExactlyInAnyOrder("teil1.docx", "teil2.docx",
                "teil3.docx");
        for (byte[] content : List.copyOf(files.values())) {
            assertThat(upgraded.storedText(Assets.sha256(content))).map(AssetText::stale).contains(false);
        }
    }

    @Test
    void anUnknownSourceIsRejectedAtTheStart() {
        Jobs jobs = jobs();
        DocumentRereads rereads = rereads(jobs, assets(new TikaDocumentStructure()));

        Assertions.assertThatThrownBy(() -> rereads.start("unbekannt", OWNER, Set.of()))
                .isInstanceOf(UnknownSourceException.class);
        assertThat(jobs.list(OWNER, JobType.REREAD)).isEmpty();
    }

    private Jobs jobs() {
        Jobs jobs = new Jobs(jobStore, Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        opened.add(jobs);
        return jobs;
    }

    private DocumentRereads rereads(Jobs jobs, Assets assets) {
        return new DocumentRereads(jobs, assets, store(), "wiki"::equals,
                key -> PAGE.equals(key) ? Optional.of("DEV") : Optional.empty(), clock);
    }

    private FileSystemAssetStore store() {
        return new FileSystemAssetStore(directory.resolve("assets"));
    }

    private FileSystemExtractionStore extractions() {
        return new FileSystemExtractionStore(directory.resolve("assets"));
    }

    private Assets assets(DocumentStructure structure) {
        if (index == null) {
            index = LucenePageTextIndex.open(directory.resolve("text"));
        }
        Assets assets = new Assets(catalog(), store(), 16_000_000, clock, null, new TikaDocumentText(), index);
        assets.documentExtractions(new DocumentExtractions(structure, extractions(), ExtractionLimits.DEFAULTS));
        return assets;
    }

    /** Tika unter einer neuen Version; merkt sich, was gelesen wird, und scheitert auf Wunsch je Dateiname. */
    private static final class Versioned implements DocumentStructure {
        private final TikaDocumentStructure tika = new TikaDocumentStructure();
        private final Predicate<String> fails;
        private final List<String> titles = new CopyOnWriteArrayList<>();
        private Runnable afterRead = () -> { };

        Versioned(Predicate<String> fails) {
            this.fails = fails;
        }

        @Override
        public String id() {
            return tika.id();
        }

        @Override
        public String version() {
            return NEXT_VERSION;
        }

        @Override
        public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                          StructureSink sink) {
            return extract(original, title, mediaType, limits, sink, null);
        }

        @Override
        public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                          StructureSink sink, PartRules rules) {
            titles.add(title);
            try {
                if (fails.test(title)) {
                    return new StructuredDocument(DocumentHead.EMPTY, Outcome.of(ExtractionStatus.FAILED,
                            StatusReason.CORRUPT), List.of(), 0, 0);
                }
                return rules == null ? tika.extract(original, title, mediaType, limits, sink)
                        : tika.extract(original, title, mediaType, limits, sink, rules);
            } finally {
                afterRead.run();
            }
        }

        @Override
        public Optional<InputStream> openPart(Path original, String title, String mediaType, ExtractionLimits limits,
                                              PartRules rules, PartId.Content id) {
            return tika.openPart(original, title, mediaType, limits, rules, id);
        }
    }

    /** Eine Uhr, die nur auf Zuruf weitergeht: Neugelesenes ist jünger als der Beginn des Auftrags. */
    private static final class MovingClock extends Clock {
        private volatile Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance() {
            now = now.plus(Duration.ofMinutes(1));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private SourceCatalog catalog() {
        HarvestSource source = new FakeSource();
        return new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
    }

    private static PageAttachment attachment(String id, String title, String mediaType) {
        return new PageAttachment(id, "test:wiki/attachment/" + id, title, mediaType, null, "1", null, null);
    }

    private static PageAttachmentList list(PageAttachment... attachments) {
        return new PageAttachmentList("wiki", "DEV", new PageAttachments("101", List.of(attachments), List.of(),
                "application/json", "test", List.of(), NOW), NOW);
    }

    private final class FakeSource implements HarvestSource {

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            byte[] content = files.get(attachment.attachmentId());
            if (content == null) {
                throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "weg", null);
            }
            return new AttachmentFile(attachment, content, NOW);
        }

        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "test";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, pageId, "Planung", "DEV", null, "1", null, null, new TreeMap<>());
        }
    }
}
