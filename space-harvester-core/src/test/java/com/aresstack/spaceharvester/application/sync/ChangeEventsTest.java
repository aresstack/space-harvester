package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR 0014: Webhooks markieren nur den Katalog, laden nie Inhalte und wirken nur, wenn eingeschaltet. */
class ChangeEventsTest {

    private static final String SECRET = "geheim-7f3a";

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
    private final InMemorySyncStore store = new InMemorySyncStore(repository);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add,
            new JobLimits(2, 10, Duration.ofHours(1)), FakeSource.CLOCK);
    private final List<String> fetched = new ArrayList<>();
    private final SpaceSyncJobs syncs = new SpaceSyncJobs(jobs, source.queries(), store, (sourceId, pageId) -> {
        fetched.add(pageId);
        PageMetadata page = source.pageMetadata(pageId);
        return PageSnapshot.of(sourceId, page, new NativePayload(("{\"id\":\"" + pageId + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    }, FakeSource.CLOCK);
    private boolean enabled = true;
    private String secret = SECRET;
    private ChangeEvents events;

    @BeforeEach
    void loadTheSpace() {
        source.readsChangeEvents = true;
        events = new ChangeEvents(syncs, source.queries(), sourceId -> enabled,
                sourceId -> secret == null ? null : secret.toCharArray());
        syncs.start("wiki", "DOC", "client:a");
        runQueued();
        fetched.clear();
        source.singlePageCalls = 0;
    }

    @AfterEach
    void close() {
        events.close();
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    private ChangeEvents.Receipt send(String body) throws Exception {
        ChangeEvents.Receipt receipt = events.receive("wiki", body.getBytes(StandardCharsets.UTF_8),
                sign(body, SECRET), null);
        assertThat(events.awaitIdle(Duration.ofSeconds(10))).isTrue();
        return receipt;
    }

    private static String sign(String body, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private SyncBaseline baseline() {
        return store.confirmed("wiki", "DOC").orElseThrow();
    }

    @Test
    void aChangedPageIsOnlyMarkedOutdatedAndNothingIsLoaded() throws Exception {
        SyncBaseline before = baseline();
        source.change("1", "4", 1);

        assertThat(send("CHANGED:1:DOC:4")).isEqualTo(ChangeEvents.Receipt.ACCEPTED);

        assertThat(fetched).isEmpty();
        assertThat(source.singlePageCalls).isEqualTo(1);
        assertThat(baseline().generation()).isEqualTo(before.generation() + 1);
        assertThat(baseline().stalePageIds()).containsExactly("1");
        assertThat(baseline().changesSince()).isEqualTo(before.changesSince());
        assertThat(repository.find(new PageKey("wiki", "1")).orElseThrow().metadata().version()).isEqualTo("3");
        assertThat(repository.findHeader(new PageKey("wiki", "1")).orElseThrow().metadata().version())
                .isEqualTo("4");
    }

    @Test
    void anUnknownPageOnlyEntersTheCatalog() throws Exception {
        source.put("9", "DOC", null, "1", null);

        send("CHANGED:9:DOC:1");

        assertThat(fetched).isEmpty();
        assertThat(baseline().missingPageIds()).containsExactly("9");
        assertThat(repository.find(new PageKey("wiki", "9"))).isEmpty();
        assertThat(repository.findHeader(new PageKey("wiki", "9"))).isPresent();
    }

    @Test
    void aVersionTheCatalogKnowsCostsNoRequestAndChangesNothing() throws Exception {
        long generation = baseline().generation();

        send("CHANGED:1:DOC:3");

        assertThat(source.singlePageCalls).isZero();
        assertThat(baseline().generation()).isEqualTo(generation);
    }

    @Test
    void aRemovedPageThatIsGoneIsDeleted() throws Exception {
        source.remove("2");

        send("REMOVED:2:DOC:");

        assertThat(baseline().pages()).doesNotContainKey("2");
        assertThat(baseline().withheldPageIds()).isEmpty();
        assertThat(repository.find(new PageKey("wiki", "2"))).isEmpty();
    }

    @Test
    void aRemovalWithoutSpaceFindsTheSpaceThatKnowsThePage() throws Exception {
        source.remove("2");

        send("REMOVED:2::");

        assertThat(baseline().pages()).doesNotContainKey("2");
    }

    @Test
    void aPermissionChangeMarksAVisiblePageOutdatedAndWithholdsAnInvisibleOne() throws Exception {
        source.remove("3");

        send("PERMISSIONS:1:DOC:\nPERMISSIONS:3:DOC:");

        assertThat(fetched).isEmpty();
        assertThat(baseline().stalePageIds()).containsExactly("1");
        assertThat(baseline().withheldPageIds()).containsOnlyKeys("3");
        assertThat(baseline().pages()).doesNotContainKey("3");
    }

    @Test
    void switchedOffEventsAreDroppedWithoutAnyEffect() throws Exception {
        enabled = false;
        long generation = baseline().generation();
        source.change("1", "4", 1);

        assertThat(send("CHANGED:1:DOC:4")).isEqualTo(ChangeEvents.Receipt.DISABLED);
        assertThat(events.accept("wiki", List.of(new ChangeEvent(ChangeEvent.Kind.CHANGED, "1", "DOC", "4"))))
                .isFalse();

        assertThat(events.awaitIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(source.singlePageCalls).isZero();
        assertThat(baseline().generation()).isEqualTo(generation);
    }

    @Test
    void withoutSecretOrWithAWrongSignatureNothingHappens() throws Exception {
        long generation = baseline().generation();
        byte[] body = "CHANGED:1:DOC:4".getBytes(StandardCharsets.UTF_8);

        assertThat(events.receive("wiki", body, sign("CHANGED:1:DOC:4", "falsch"), null))
                .isEqualTo(ChangeEvents.Receipt.BAD_SIGNATURE);
        assertThat(events.receive("wiki", body, null, null)).isEqualTo(ChangeEvents.Receipt.BAD_SIGNATURE);
        assertThat(events.receive("wiki", body, "sha256=zz", null)).isEqualTo(ChangeEvents.Receipt.BAD_SIGNATURE);
        secret = null;
        assertThat(events.receive("wiki", body, sign("CHANGED:1:DOC:4", SECRET), null))
                .isEqualTo(ChangeEvents.Receipt.DISABLED);
        assertThat(events.receive("andere", body, null, null)).isEqualTo(ChangeEvents.Receipt.UNKNOWN_SOURCE);

        assertThat(events.awaitIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(source.singlePageCalls).isZero();
        assertThat(baseline().generation()).isEqualTo(generation);
    }

    @Test
    void anUnreadableBodyWithAValidSignatureIsRejected() throws Exception {
        assertThat(send("kein Ereignis")).isEqualTo(ChangeEvents.Receipt.UNREADABLE);
    }

    @Test
    void aSourceThatReadsNoEventsAcceptsNone() throws Exception {
        source.readsChangeEvents = false;

        assertThat(send("CHANGED:1:DOC:4")).isEqualTo(ChangeEvents.Receipt.DISABLED);
    }

    @Test
    void aPageMovedBetweenTwoCataloguedSpacesLeavesTheOldOne() throws Exception {
        source.put("5", "OPS", null, "1", null);
        syncs.start("wiki", "OPS", "client:a");
        runQueued();
        fetched.clear();
        source.put("2", "OPS", null, "2", null);

        send("CHANGED:2::");

        assertThat(fetched).isEmpty();
        assertThat(baseline().pages()).doesNotContainKey("2");
        assertThat(store.confirmed("wiki", "OPS").orElseThrow().missingPageIds()).containsExactly("2");
    }

    @Test
    void aSpaceWithoutCatalogIsLeftToTheSearch() {
        source.put("20", "NEU", null, "1", null);

        assertThat(syncs.applyEvents("wiki", List.of(new ChangeEvent(ChangeEvent.Kind.CHANGED, "20", "NEU",
                "1")))).isEmpty();

        assertThat(store.confirmed("wiki", "NEU")).isEmpty();
        assertThat(repository.findHeader(new PageKey("wiki", "20"))).isEmpty();
    }

    @Test
    void eventsForASpaceInSyncWaitWithoutAskingTheSource() {
        syncs.start("wiki", "DOC", "client:a");
        ChangeEvent event = new ChangeEvent(ChangeEvent.Kind.CHANGED, "1", "DOC", "4");

        assertThat(syncs.applyEvents("wiki", List.of(event))).containsExactly(event);
        assertThat(source.singlePageCalls).isZero();

        runQueued();
        source.change("1", "4", 1);
        assertThat(syncs.applyEvents("wiki", List.of(event))).isEmpty();
        assertThat(baseline().stalePageIds()).containsExactly("1");
    }
}
