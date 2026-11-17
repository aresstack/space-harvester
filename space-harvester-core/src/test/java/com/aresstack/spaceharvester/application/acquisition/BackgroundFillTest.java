package com.aresstack.spaceharvester.application.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFillQueue;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundFillTest {

    private static final AccessContext SERVER = AccessContext.technicalClient("hintergrund");
    private static final BackgroundFill.Timing NOW = new BackgroundFill.Timing(Duration.ZERO, Duration.ofHours(1),
            1000);

    private final List<String> loaded = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> running = new ConcurrentHashMap<>();
    /** Quellen, deren Füllung an ist (Quelle aktiv, technisch freigegeben, {@code background-fill} nicht aus). */
    private final Set<String> eligible = ConcurrentHashMap.newKeySet();
    private final List<AutoCloseable> open = new ArrayList<>();

    @TempDir
    Path directory;

    @AfterEach
    void close() throws Exception {
        gates.values().forEach(CountDownLatch::countDown);
        for (AutoCloseable resource : open.reversed()) {
            resource.close();
        }
    }

    /** Seiten, deren Abruf jedes Mal scheitert, und wie oft er versucht wurde. */
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    private AcquisitionScheduler scheduler(int background) {
        AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
            running.computeIfAbsent(key.pageId(), id -> new CountDownLatch(1)).countDown();
            attempts.merge(key.pageId(), 1, Integer::sum);
            if (failing.contains(key.pageId())) {
                throw new IllegalStateException("Quelle drosselt");
            }
            CountDownLatch gate = gates.get(key.pageId());
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            loaded.add(key.pageId());
        }, new AcquisitionScheduler.Limits(4, background, 4, 50), null, Duration.ZERO);
        open.add(scheduler);
        return scheduler;
    }

    private BackgroundFill fill(AcquisitionScheduler scheduler, BackgroundFill.FillQueue queue,
                                BackgroundFill.Catalog catalog, boolean enabled) {
        BackgroundFill fill = new BackgroundFill(scheduler, queue, catalog, SERVER, eligible::contains, NOW, enabled);
        open.add(fill);
        return fill.start();
    }

    /** Ein Katalog mit einem Space „DEV“ der Quelle „wiki“ und den gegebenen Lücken. */
    private static BackgroundFill.Catalog catalog(List<BackgroundFill.Gap> gaps) {
        return new BackgroundFill.Catalog() {
            @Override
            public List<SyncBaselineStore.SpaceRef> spaces() {
                return List.of(new SyncBaselineStore.SpaceRef("wiki", "DEV"), new SyncBaselineStore.SpaceRef("other",
                        "DEV"));
            }

            @Override
            public List<BackgroundFill.Gap> gaps(String sourceId, String spaceKey, int limit) {
                return gaps.stream().filter(gap -> gap.key().sourceId().equals(sourceId)).limit(limit).toList();
            }
        };
    }

    private static BackgroundFill.Gap gap(String sourceId, String pageId, BackgroundFill.Kind kind) {
        return new BackgroundFill.Gap(new PageKey(sourceId, pageId), kind);
    }

    private static final List<BackgroundFill.Gap> GAPS = List.of(
            gap("wiki", "new-1", BackgroundFill.Kind.NOT_LOADED),
            gap("wiki", "old-1", BackgroundFill.Kind.OUTDATED),
            gap("other", "x", BackgroundFill.Kind.NOT_LOADED));

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 30 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void wikiIsEligible() {
        eligible.add("wiki");
    }

    /** Wartet auf eine passende Meldung (ADR 0027). */
    private static AcquisitionStatus next(BlockingQueue<AcquisitionStatus> seen, Predicate<AcquisitionStatus> wanted)
            throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            AcquisitionStatus next = seen.poll(until - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (next != null && wanted.test(next)) {
                return next;
            }
        }
        throw new AssertionError("Keine passende Meldung");
    }

    @Test
    void theStatusOfFillAndPlannerArrivesWithoutAnyQueryUntilTheSubscriptionIsClosed() throws Exception {
        gates.put("new-1", new CountDownLatch(1));
        gates.put("old-1", new CountDownLatch(1));
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = new BackgroundFill(scheduler(1), queue, catalog(GAPS), SERVER, eligible::contains, NOW,
                true);
        open.add(fill);
        BlockingQueue<AcquisitionStatus> seen = new LinkedBlockingQueue<>();
        Subscription subscription = fill.subscribe(seen::add);
        fill.start();

        AcquisitionStatus loading = next(seen, status -> status.backgroundRunning() == 1 && status.queued() == 2);
        assertThat(loading.pause()).isEqualTo(AcquisitionStatus.Pause.NONE);
        gates.values().forEach(CountDownLatch::countDown);
        AcquisitionStatus idle = next(seen, status -> status.loaded() == 2 && status.queued() == 0
                && status.inFlight() == 0);
        assertThat(idle.pause()).isEqualTo(AcquisitionStatus.Pause.IDLE);

        fill.enabled(false);
        assertThat(next(seen, status -> status.pause() == AcquisitionStatus.Pause.DISABLED)).isNotNull();

        subscription.close();
        fill.enabled(true);
        assertThat(seen.poll(500, TimeUnit.MILLISECONDS)).as("abgemeldet").isNull();
    }

    @Test
    void fillsNotLoadedAndOutdatedPagesOfEligibleSources() throws Exception {
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = fill(scheduler(2), queue, catalog(GAPS), true);

        // Der Zähler steigt erst nach dem Austragen aus der Warteschlange: auf beides warten.
        eventually(() -> loaded.size() == 2 && queue.size() == 0 && fill.status().loaded() == 2);
        // „other“ ist nicht zugelassen (etwa ohne technische Zugangsdaten): nichts von dort.
        assertThat(loaded).containsExactlyInAnyOrder("new-1", "old-1");
        assertThat(fill.status().loaded()).isEqualTo(2);
    }

    @Test
    void cataloguedFindingsAreLoadedEvenOutsideConfirmedSpaces() throws Exception {
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        // Der Katalog führt keinen Space mit bestätigter Generation; die Lücken kommen nur vom Katalogisieren.
        BackgroundFill fill = fill(scheduler(2), queue, catalog(List.of()), true);
        eventually(() -> fill.status().lastScan() != null);

        fill.offer(List.of(gap("wiki", "found-1", BackgroundFill.Kind.NOT_LOADED),
                gap("other", "found-2", BackgroundFill.Kind.NOT_LOADED)));

        eventually(() -> loaded.size() == 1 && queue.size() == 0);
        TimeUnit.MILLISECONDS.sleep(100);
        // „other“ ist nicht zugelassen: nicht eingereiht, nicht geladen.
        assertThat(loaded).containsExactly("found-1");
    }

    @Test
    void aSwitchedOffFillTakesNoFindings() throws Exception {
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = fill(scheduler(2), queue, catalog(List.of()), false);

        fill.offer(List.of(gap("wiki", "found-1", BackgroundFill.Kind.NOT_LOADED)));

        assertThat(queue.size()).isZero();
    }

    @Test
    void aSourceWithItsFillSwitchedOffLoadsNothingUntilItIsSwitchedOn() throws Exception {
        eligible.clear();
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = fill(scheduler(2), queue, catalog(GAPS), true);
        eventually(() -> fill.status().lastScan() != null);
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(loaded).isEmpty();
        assertThat(queue.size()).isZero();
        assertThat(fill.status().sources()).isEmpty();

        // Nur „other“ eingeschaltet (sync.sources.other.background-fill=true): nur von dort.
        eligible.add("other");
        fill.wake();
        eventually(() -> loaded.size() == 1);
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(loaded).containsExactly("x");
        assertThat(fill.status().sources()).extracting(AcquisitionStatus.SourceCounts::sourceId)
                .containsExactly("other");
    }

    @Test
    void switchingOffASourceWithdrawsItsWaitingRequests() throws Exception {
        AcquisitionScheduler scheduler = scheduler(1);
        AutoCloseable pause = scheduler.pauseBackground();
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = fill(scheduler, queue, catalog(GAPS), true);
        eventually(() -> fill.status().inFlight() == 2);

        // sync.sources.wiki.background-fill=false: die Konfiguration ruft enabled(true), weil global noch an.
        eligible.remove("wiki");
        fill.enabled(true);

        assertThat(fill.status().inFlight()).isZero();
        assertThat(scheduler.status().backgroundQueued()).isZero();
        pause.close();
        eventually(() -> queue.size() == 0);
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(loaded).isEmpty();
    }

    @Test
    void permanentFailuresDoNotBlockTheRestOfAnOverfullCatalog() throws Exception {
        failing.add("kaputt");
        List<BackgroundFill.Gap> gaps = List.of(
                new BackgroundFill.Gap(new PageKey("wiki", "kaputt"), BackgroundFill.Kind.OUTDATED,
                        Instant.parse("2026-02-01T00:00:00Z")),
                new BackgroundFill.Gap(new PageKey("wiki", "gut"), BackgroundFill.Kind.OUTDATED,
                        Instant.parse("2026-01-01T00:00:00Z")));
        // Eine Seite je Suche im Katalog: die Schlange ist übervoll.
        BackgroundFill fill = new BackgroundFill(scheduler(1), BackgroundFill.FillQueue.inMemory(), catalog(gaps),
                SERVER, eligible::contains, new BackgroundFill.Timing(Duration.ZERO, Duration.ofHours(1), 1), true);
        open.add(fill);
        fill.start();

        eventually(() -> loaded.contains("gut"));
        TimeUnit.MILLISECONDS.sleep(300);
        // Die gescheiterte Seite kommt erst nach der Wartezeit wieder, nicht in einer Schleife.
        assertThat(attempts.get("kaputt")).isEqualTo(1);
        assertThat(fill.status().failed()).isEqualTo(1);
    }

    @Test
    void theCatalogIsSearchedAfterEachChangeAndNeverInIntervals() throws Exception {
        List<BackgroundFill.Gap> gaps = new CopyOnWriteArrayList<>();
        AtomicInteger searches = new AtomicInteger();
        BackgroundFill.Catalog counting = new BackgroundFill.Catalog() {
            @Override
            public List<SyncBaselineStore.SpaceRef> spaces() {
                searches.incrementAndGet();
                return List.of(new SyncBaselineStore.SpaceRef("wiki", "DEV"));
            }

            @Override
            public List<BackgroundFill.Gap> gaps(String sourceId, String spaceKey, int limit) {
                return gaps.stream().limit(limit).toList();
            }
        };
        BackgroundFill fill = new BackgroundFill(scheduler(1), BackgroundFill.FillQueue.inMemory(), counting, SERVER,
                eligible::contains, new BackgroundFill.Timing(Duration.ZERO, Duration.ofMillis(200), 1000), true);
        open.add(fill);
        fill.start();
        eventually(() -> fill.status().lastScan() != null);

        TimeUnit.MILLISECONDS.sleep(800);
        assertThat(searches.get()).as("keine Suche in Abständen").isEqualTo(1);

        gaps.add(gap("wiki", "neu", BackgroundFill.Kind.NOT_LOADED));
        fill.wake();
        fill.wake();
        eventually(() -> loaded.contains("neu"));
        assertThat(searches.get()).as("zwei Weckrufe kurz hintereinander, eine Suche").isEqualTo(2);
    }

    @Test
    void aCommitDuringTheStartupDelayDoesNotEndIt() throws Exception {
        // Die Titelsuche katalogisiert ihre Funde und bietet sie an, solange die Füllung anläuft.
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        queue.add(List.of(gap("wiki", "fund", BackgroundFill.Kind.NOT_LOADED)));
        BackgroundFill fill = new BackgroundFill(scheduler(1), queue, catalog(List.of()), SERVER, eligible::contains,
                new BackgroundFill.Timing(Duration.ofSeconds(2), Duration.ofHours(1), 1000), true);
        open.add(fill);
        fill.start();

        // Der Commit des Seiten-Cache weckt; die Füllung lädt trotzdem erst nach der Anlaufzeit.
        fill.wake();
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(loaded).isEmpty();
        assertThat(fill.status().lastScan()).isNull();

        eventually(() -> loaded.contains("fund"));
    }

    @Test
    void aFailedPageComesBackOnceItsWaitIsOverWithoutAnyOtherEvent() throws Exception {
        failing.add("new-1");
        BackgroundFill fill = new BackgroundFill(scheduler(1), BackgroundFill.FillQueue.inMemory(), catalog(GAPS),
                SERVER, eligible::contains, new BackgroundFill.Timing(Duration.ZERO, Duration.ofMillis(300), 1000),
                true);
        open.add(fill);
        fill.start();
        eventually(() -> fill.status().failed() == 1);

        failing.remove("new-1");

        eventually(() -> loaded.contains("new-1"));
    }

    @Test
    void aFailedCatalogSearchIsRepeatedAfterThePauseWithoutAnyOtherEvent() throws Exception {
        List<BackgroundFill.Gap> gaps = new CopyOnWriteArrayList<>();
        AtomicBoolean broken = new AtomicBoolean();
        AtomicInteger searches = new AtomicInteger();
        BackgroundFill.Catalog flaky = new BackgroundFill.Catalog() {
            @Override
            public List<SyncBaselineStore.SpaceRef> spaces() {
                searches.incrementAndGet();
                if (broken.getAndSet(false)) {
                    throw new IllegalStateException("Katalog gerade nicht lesbar");
                }
                return List.of(new SyncBaselineStore.SpaceRef("wiki", "DEV"));
            }

            @Override
            public List<BackgroundFill.Gap> gaps(String sourceId, String spaceKey, int limit) {
                return gaps.stream().limit(limit).toList();
            }
        };
        BackgroundFill fill = new BackgroundFill(scheduler(1), BackgroundFill.FillQueue.inMemory(), flaky, SERVER,
                eligible::contains, new BackgroundFill.Timing(Duration.ZERO, Duration.ofMillis(200), 1000), true);
        open.add(fill);
        fill.start();
        eventually(() -> fill.status().lastScan() != null);

        // Die Suche nach dieser Änderung scheitert einmal; danach kommt kein Ereignis mehr.
        gaps.add(gap("wiki", "neu", BackgroundFill.Kind.NOT_LOADED));
        broken.set(true);
        fill.wake();

        eventually(() -> loaded.contains("neu"));
        assertThat(searches.get()).isEqualTo(3);
    }

    @Test
    void entriesOfASwitchedOffSourceAtTheHeadOfTheQueueDoNotHideTheRest() throws Exception {
        // Nach einem Neustart: vorn mehr als ein Fenster Einträge einer Quelle, die nicht mehr füllen soll.
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        queue.add(List.of(gap("other", "a", BackgroundFill.Kind.NOT_LOADED),
                gap("other", "b", BackgroundFill.Kind.NOT_LOADED), gap("other", "c", BackgroundFill.Kind.NOT_LOADED),
                gap("other", "d", BackgroundFill.Kind.NOT_LOADED), gap("wiki", "dahinter",
                        BackgroundFill.Kind.NOT_LOADED)));

        fill(scheduler(1), queue, catalog(List.of()), true);

        // Der Lader meldet die Seite, bevor ihr Abruf endet und den Eintrag aus der Schlange nimmt: auf beides warten.
        eventually(() -> loaded.contains("dahinter") && queue.size() == 0);
        assertThat(loaded).containsExactly("dahinter");
    }

    @Test
    void recentlyChangedPagesComeFirst() throws Exception {
        List<BackgroundFill.Gap> gaps = List.of(
                new BackgroundFill.Gap(new PageKey("wiki", "alt"), BackgroundFill.Kind.NOT_LOADED,
                        Instant.parse("2020-01-01T00:00:00Z")),
                new BackgroundFill.Gap(new PageKey("wiki", "ohne-datum"), BackgroundFill.Kind.OUTDATED, null),
                new BackgroundFill.Gap(new PageKey("wiki", "neu"), BackgroundFill.Kind.OUTDATED,
                        Instant.parse("2026-01-01T00:00:00Z")));
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        fill(scheduler(1), queue, catalog(gaps), true);

        eventually(() -> loaded.size() == 3);
        assertThat(loaded).containsExactly("neu", "alt", "ohne-datum");
    }

    @Test
    void aDisabledFillLoadsNothingUntilItIsSwitchedOn() throws Exception {
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = fill(scheduler(2), queue, catalog(GAPS), false);
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(loaded).isEmpty();
        assertThat(fill.status().lastScan()).isNull();

        fill.enabled(true);

        eventually(() -> loaded.size() == 2);
    }

    @Test
    void aSchedulerWithoutBackgroundPlacesLoadsNothing() throws Exception {
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = fill(scheduler(0), queue, catalog(GAPS), true);
        eventually(() -> fill.status().lastScan() != null);
        TimeUnit.MILLISECONDS.sleep(200);

        assertThat(loaded).isEmpty();
        // Die Lücken warten; sie gehen nicht verloren.
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void theQueueSurvivesARestart() throws Exception {
        gates.put("new-1", new CountDownLatch(1));
        gates.put("old-1", new CountDownLatch(1));
        Path path = directory.resolve("fill-queue");
        LuceneFillQueue first = LuceneFillQueue.open(path);
        AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
            running.computeIfAbsent(key.pageId(), id -> new CountDownLatch(1)).countDown();
            try {
                gates.get(key.pageId()).await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, new AcquisitionScheduler.Limits(4, 1, 4, 50), null, Duration.ZERO);
        BackgroundFill fill = new BackgroundFill(scheduler, first, catalog(GAPS), SERVER, eligible::contains, NOW,
                true).start();
        eventually(() -> running.containsKey("new-1") || running.containsKey("old-1"));
        assertThat(first.size()).isEqualTo(2);

        // Beenden, während ein Abruf läuft und einer wartet: beide bleiben eingereiht.
        fill.close();
        gates.values().forEach(CountDownLatch::countDown);
        scheduler.close();
        first.close();

        LuceneFillQueue second = LuceneFillQueue.open(path);
        open.add(second);
        assertThat(second.pending(10)).extracting(gap -> gap.key().pageId()).containsExactly("new-1", "old-1");
        assertThat(second.pending(10)).extracting(BackgroundFill.Gap::kind)
                .containsExactly(BackgroundFill.Kind.NOT_LOADED, BackgroundFill.Kind.OUTDATED);

        // Nach dem Neustart zuerst die Schlange, auch wenn der Katalog nichts mehr meldet.
        gates.clear();
        fill(scheduler(1), second, catalog(List.of()), true);
        eventually(() -> loaded.size() == 2 && second.size() == 0);
        assertThat(loaded).containsExactly("new-1", "old-1");
    }

    @Test
    void theFillNeverRunsWithAUserCredential() {
        AccessContext user = AccessContext.endUser("u", "app", new UserCredential("wiki", "t"));

        assertThatThrownBy(() -> new BackgroundFill(scheduler(1), BackgroundFill.FillQueue.inMemory(),
                catalog(GAPS), user, id -> true, NOW, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aForegroundRequestTakesOverAPageTheFillQueued() throws Exception {
        AcquisitionScheduler scheduler = scheduler(1);
        AutoCloseable pause = scheduler.pauseBackground();
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill fill = fill(scheduler, queue, catalog(GAPS), true);
        eventually(() -> fill.status().inFlight() == 2);

        AcquisitionScheduler.Ticket search = scheduler.request(AccessContext.technicalClient("app"),
                new PageKey("wiki", "old-1"), AcquisitionScheduler.Priority.FOREGROUND);

        assertThat(search.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(loaded).containsExactly("old-1");
        assertThat(scheduler.status().promoted()).isEqualTo(1);
        eventually(() -> queue.size() == 1);
        pause.close();
        eventually(() -> loaded.size() == 2);
        assertThat(loaded).containsExactly("old-1", "new-1");
    }
    @Test
    void aWithdrawalBetweenCheckAndHandOverStillWithdrawsTheRequest() throws Exception {
        AcquisitionScheduler scheduler = scheduler(1);
        AutoCloseable pause = scheduler.pauseBackground();
        AtomicReference<BackgroundFill> fill = new AtomicReference<>();
        AtomicBoolean withdrawn = new AtomicBoolean();
        AtomicInteger checkedAfter = new AtomicInteger();
        // Die Füllung der Quelle geht aus, nachdem feed() sie geprüft, aber bevor es die Anfrage eingetragen hat;
        // revalidate() läuft genau dazwischen und sieht die Anfrage deshalb noch nicht.
        Predicate<String> eligibleUntilFeed = sourceId -> {
            boolean inFeed = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(BackgroundFill.class.getName())
                            && frame.getMethodName().equals("feed")));
            if (inFeed && withdrawn.compareAndSet(false, true)) {
                eligible.remove(sourceId);
                fill.get().revalidate();
                return true;
            }
            if (inFeed) {
                // Die Prüfung nach dem Eintragen: Erst danach zieht feed() die Anfrage zurück.
                checkedAfter.incrementAndGet();
            }
            return eligible.contains(sourceId);
        };
        BackgroundFill.FillQueue queue = BackgroundFill.FillQueue.inMemory();
        BackgroundFill started = new BackgroundFill(scheduler, queue, catalog(GAPS), SERVER, eligibleUntilFeed, NOW,
                true);
        open.add(started);
        fill.set(started);
        started.start();

        // Nicht schon zwischen Rückzug und Eintragen weitermachen: Dann wäre noch nichts übergeben, der Planer liefe nach
        // pause.close() an und lüde die Anfrage, bevor feed() sie zurückziehen kann.
        eventually(() -> checkedAfter.get() > 0 && started.status().inFlight() == 0);
        pause.close();
        assertThat(loaded).isEmpty();
    }
}
