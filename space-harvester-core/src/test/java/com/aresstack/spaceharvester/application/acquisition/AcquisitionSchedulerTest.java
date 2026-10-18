package com.aresstack.spaceharvester.application.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AcquisitionSchedulerTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final AccessContext SERVER = AccessContext.technicalClient("hintergrund");
    private static final AccessContext USER = AccessContext.endUser("u", "app", new UserCredential("wiki", "t"));

    /** Wer welche Seite geladen hat, in der Reihenfolge der Starts. */
    private final List<String> started = Collections.synchronizedList(new ArrayList<>());
    private final List<AccessContext> callers = Collections.synchronizedList(new ArrayList<>());
    /** Seiten, deren Abruf erst nach Freigabe endet. */
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> running = new ConcurrentHashMap<>();
    private AcquisitionScheduler scheduler;

    @AfterEach
    void close() throws Exception {
        gates.values().forEach(CountDownLatch::countDown);
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private AcquisitionScheduler scheduler(int foreground, int background, int perSource) {
        return scheduler(foreground, background, perSource, Duration.ZERO);
    }

    private AcquisitionScheduler scheduler(int foreground, int background, int perSource, Duration resumeDelay) {
        scheduler = new AcquisitionScheduler((caller, key) -> {
            started.add(key.pageId());
            callers.add(caller);
            running.computeIfAbsent(key.pageId(), id -> new CountDownLatch(1)).countDown();
            CountDownLatch gate = gates.get(key.pageId());
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("unterbrochen");
                }
            }
        }, new AcquisitionScheduler.Limits(foreground, background, perSource, 50), null, resumeDelay);
        return scheduler;
    }

    private void gate(String... pageIds) {
        for (String pageId : pageIds) {
            gates.put(pageId, new CountDownLatch(1));
        }
    }

    private void release(String pageId) {
        gates.get(pageId).countDown();
    }

    private void awaitRunning(String pageId) throws InterruptedException {
        assertThat(running.computeIfAbsent(pageId, id -> new CountDownLatch(1)).await(10, TimeUnit.SECONDS))
                .as("Seite %s lädt", pageId).isTrue();
    }

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    @Test
    void aForegroundRequestPromotesAWaitingBackgroundPageInsteadOfLoadingItTwice() throws Exception {
        AcquisitionScheduler planner = scheduler(4, 1, 4);
        AutoCloseable pause = planner.pauseBackground();
        AcquisitionScheduler.Ticket background = planner.request(SERVER, key("1"), AcquisitionScheduler.Priority.BACKGROUND);
        assertThat(planner.status().backgroundQueued()).isEqualTo(1);

        AcquisitionScheduler.Ticket foreground = planner.request(USER, key("1"), AcquisitionScheduler.Priority.FOREGROUND);

        assertThat(foreground.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(background.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(started).containsExactly("1");
        // Die Seite lädt mit dem technischen Aufrufer, die Anmeldung des Benutzers bleibt ungenutzt.
        assertThat(callers).containsExactly(SERVER);
        assertThat(planner.status().promoted()).isEqualTo(1);
        pause.close();
    }

    @Test
    void aForegroundRequestForAPageTheBackgroundIsLoadingWaitsForTheSameLoad() throws Exception {
        gate("1");
        AcquisitionScheduler planner = scheduler(4, 1, 4);
        AcquisitionScheduler.Ticket background = planner.request(SERVER, key("1"), AcquisitionScheduler.Priority.BACKGROUND);
        awaitRunning("1");

        AcquisitionScheduler.Ticket foreground = planner.request(APP, key("1"), AcquisitionScheduler.Priority.FOREGROUND);
        assertThat(foreground.done()).isNotDone();
        release("1");

        assertThat(foreground.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(background.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(started).containsExactly("1");
    }

    @Test
    void theBackgroundWaitsAShortWhileAfterTheLastForegroundLoad() throws Exception {
        AcquisitionScheduler planner = scheduler(4, 1, 4, Duration.ofMillis(300));
        assertThat(planner.request(APP, key("f1"), AcquisitionScheduler.Priority.FOREGROUND).done()
                .get(10, TimeUnit.SECONDS)).isTrue();

        long before = System.nanoTime();
        AcquisitionScheduler.Ticket background = planner.request(SERVER, key("b1"),
                AcquisitionScheduler.Priority.BACKGROUND);
        assertThat(planner.status().pause()).isEqualTo(AcquisitionStatus.Pause.COOLDOWN);

        assertThat(background.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(System.nanoTime() - before).isGreaterThanOrEqualTo(Duration.ofMillis(200).toNanos());
        assertThat(started).containsExactly("f1", "b1");
        assertThat(planner.status().pause()).isEqualTo(AcquisitionStatus.Pause.NONE);
    }

    @Test
    void theForegroundPreemptsTheBackgroundAtTheNextPage() throws Exception {
        gate("b1", "f1");
        AcquisitionScheduler planner = scheduler(2, 1, 4);
        List<AcquisitionScheduler.Ticket> background = new ArrayList<>();
        for (String id : List.of("b1", "b2", "b3")) {
            background.add(planner.request(SERVER, key(id), AcquisitionScheduler.Priority.BACKGROUND));
        }
        awaitRunning("b1");

        // Der Vordergrund hat eigene Plätze: er beginnt sofort, obwohl der Hintergrund lädt.
        AcquisitionScheduler.Ticket foreground = planner.request(APP, key("f1"), AcquisitionScheduler.Priority.FOREGROUND);
        awaitRunning("f1");
        // Der laufende Hintergrundabruf endet regulär, aber kein neuer beginnt, solange der Vordergrund lädt.
        release("b1");
        assertThat(background.get(0).done().get(10, TimeUnit.SECONDS)).isTrue();
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(started).containsExactly("b1", "f1");
        assertThat(planner.status().backgroundQueued()).isEqualTo(2);

        release("f1");
        assertThat(foreground.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(background.get(2).done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(started).containsExactly("b1", "f1", "b2", "b3");
    }

    @Test
    void eachSourceKeepsOnePlaceForTheForeground() throws Exception {
        gate("b1", "b2", "f1");
        AcquisitionScheduler planner = scheduler(2, 2, 2);
        planner.request(SERVER, key("b1"), AcquisitionScheduler.Priority.BACKGROUND);
        planner.request(SERVER, key("b2"), AcquisitionScheduler.Priority.BACKGROUND);
        awaitRunning("b1");
        TimeUnit.MILLISECONDS.sleep(100);
        // Je Quelle höchstens zwei, der Hintergrund davon höchstens einer.
        assertThat(planner.status().backgroundRunning()).isEqualTo(1);

        AcquisitionScheduler.Ticket foreground = planner.request(APP, key("f1"), AcquisitionScheduler.Priority.FOREGROUND);
        awaitRunning("f1");
        assertThat(planner.status().foregroundRunning()).isEqualTo(1);
        release("f1");
        release("b1");
        release("b2");
        assertThat(foreground.done().get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cancellingTheOnlyRequestOfAWaitingPageDropsIt() throws Exception {
        gate("x");
        AcquisitionScheduler planner = scheduler(1, 1, 4);
        planner.request(APP, key("x"), AcquisitionScheduler.Priority.FOREGROUND);
        awaitRunning("x");
        AcquisitionScheduler.Ticket waiting = planner.request(USER, key("y"), AcquisitionScheduler.Priority.FOREGROUND);

        assertThat(waiting.cancel()).isTrue();
        release("x");
        TimeUnit.MILLISECONDS.sleep(100);

        assertThat(started).containsExactly("x");
        assertThat(waiting.done()).isCancelled();
    }

    @Test
    void cancellingAUserRequestOnAPromotedPageMovesItBackToTheBackground() throws Exception {
        gate("x");
        AcquisitionScheduler planner = scheduler(1, 1, 4);
        planner.request(APP, key("x"), AcquisitionScheduler.Priority.FOREGROUND);
        awaitRunning("x");
        AcquisitionScheduler.Ticket background = planner.request(SERVER, key("1"),
                AcquisitionScheduler.Priority.BACKGROUND);
        AcquisitionScheduler.Ticket user = planner.request(USER, key("1"), AcquisitionScheduler.Priority.FOREGROUND);
        assertThat(planner.status().foregroundQueued()).isEqualTo(1);

        user.cancel();
        assertThat(planner.status().foregroundQueued()).isZero();
        assertThat(planner.status().backgroundQueued()).isEqualTo(1);
        release("x");

        assertThat(background.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(started).containsExactly("x", "1");
        assertThat(callers).doesNotContain(USER);
    }

    @Test
    void theBackgroundNeverTakesAUserCredential() {
        AcquisitionScheduler planner = scheduler(1, 1, 1);

        assertThatThrownBy(() -> planner.request(USER, key("1"), AcquisitionScheduler.Priority.BACKGROUND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closingDropsWhatWaitsButLetsTheRunningLoadFinish() throws Exception {
        gate("x");
        AcquisitionScheduler planner = scheduler(1, 1, 4);
        AcquisitionScheduler.Ticket first = planner.request(APP, key("x"), AcquisitionScheduler.Priority.FOREGROUND);
        AcquisitionScheduler.Ticket second = planner.request(APP, key("y"), AcquisitionScheduler.Priority.FOREGROUND);
        awaitRunning("x");

        Thread closing = Thread.ofVirtual().start(() -> {
            try {
                planner.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(closing.isAlive()).isTrue();
        release("x");
        closing.join(10_000);

        assertThat(first.done().get()).isTrue();
        assertThat(second.done()).isCancelled();
        assertThat(started).containsExactly("x");
        scheduler = null;
    }
}
