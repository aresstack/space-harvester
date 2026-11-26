package com.aresstack.spaceharvester.application.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.RetrySettings;
import com.aresstack.spaceharvester.application.network.NetworkState;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Seitenweise fortsetzen statt scheitern (Angelo 26.11.2026, WLAN): Eine vorübergehend gescheiterte Seite fordert der
 * Planer im selben Auftrag erneut an; ohne Netz wartet der Vordergrund, bis es zurück ist. Erfundene Seiten und Quellen.
 */
class AcquisitionSchedulerRetryTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final PageKey PAGE = new PageKey("erfundene-ablage", "seite-1");
    /** Ein weiterer Versuch nach 1 s (die kleinste Wartezeit des Wiederanlaufs). */
    private static final RetrySettings ONE_RETRY = new RetrySettings(1, Duration.ZERO, Duration.ZERO, 1,
            Duration.ofSeconds(1), Duration.ofSeconds(1));

    private final AtomicInteger calls = new AtomicInteger();
    /** Was die Abrufe der Reihe nach werfen; leer heißt geladen. */
    private final Deque<RuntimeException> failures = new ArrayDeque<>();
    private AcquisitionScheduler scheduler;
    private NetworkState network;

    @AfterEach
    void close() throws Exception {
        if (scheduler != null) {
            scheduler.close();
        }
        if (network != null) {
            network.close();
        }
    }

    private AcquisitionScheduler scheduler(RetrySettings retry) {
        scheduler = new AcquisitionScheduler((caller, key) -> {
            calls.incrementAndGet();
            RuntimeException next;
            synchronized (failures) {
                next = failures.pollFirst();
            }
            if (next != null) {
                throw next;
            }
        }, new AcquisitionScheduler.Limits(2, 1, 2, 50), null, Duration.ZERO);
        scheduler.retry(() -> retry);
        return scheduler;
    }

    private void failNext(RuntimeException... thrown) {
        synchronized (failures) {
            for (RuntimeException each : thrown) {
                failures.addLast(each);
            }
        }
    }

    private static SourceAccessException unavailable() {
        return new SourceAccessException(PAGE.sourceId(), SourceAccessException.Reason.UNAVAILABLE,
                "erfunden: Verbindung abgerissen", null);
    }

    @Test
    void transientFailureIsRequestedAgainInTheSameTicket() throws Exception {
        scheduler(ONE_RETRY);
        failNext(unavailable());

        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);

        assertThat(ticket.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(ticket.failure()).isEmpty();
    }

    @Test
    void afterTheLastAttemptThePageFailsWithItsReason() throws Exception {
        scheduler(ONE_RETRY);
        SourceAccessException last = unavailable();
        failNext(unavailable(), last);

        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);

        assertThat(ticket.done().get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(calls).hasValue(2);
        assertThat(ticket.failure()).containsSame(last);
    }

    @Test
    void permanentFailureIsNotRepeated() throws Exception {
        scheduler(ONE_RETRY);
        failNext(new SourceAccessException(PAGE.sourceId(), SourceAccessException.Reason.NOT_FOUND, "erfunden", null));

        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);

        assertThat(ticket.done().get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void withoutRetrySettingsAFailureEndsAtOnce() throws Exception {
        scheduler = new AcquisitionScheduler((caller, key) -> {
            calls.incrementAndGet();
            throw unavailable();
        }, new AcquisitionScheduler.Limits(2, 1, 2, 50), null, Duration.ZERO);

        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);

        assertThat(ticket.done().get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void foregroundWaitsForTheNetworkWithoutUsingAnAttempt() throws Exception {
        scheduler(new RetrySettings(0, Duration.ZERO, Duration.ZERO, 1));
        network = new NetworkState();
        scheduler.network(network);
        failNext(new SourceAccessException(PAGE.sourceId(), SourceAccessException.Reason.UNAVAILABLE, "erfunden",
                new ConnectException("erfunden: kein Netz")));

        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);

        awaitOffline();
        assertThat(ticket.done()).isNotDone();
        // Ein anderer Abruf bekommt Antwort: Das Netz ist zurück, die Seite lädt im selben Auftrag weiter.
        network.succeeded();

        assertThat(ticket.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void cancellingAWaitingPageDropsItsNextAttempt() throws Exception {
        scheduler(new RetrySettings(0, Duration.ZERO, Duration.ZERO, 1));
        network = new NetworkState();
        scheduler.network(network);
        failNext(new SourceAccessException(PAGE.sourceId(), SourceAccessException.Reason.UNAVAILABLE, "erfunden",
                new ConnectException("erfunden: kein Netz")));
        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);
        awaitOffline();

        assertThat(ticket.cancel()).isTrue();
        network.succeeded();

        assertThat(ticket.done()).isCancelled();
        assertThat(calls).hasValue(1);
        // Danach eine neue Anfrage für dieselbe Seite: ein eigener Abruf.
        AcquisitionScheduler.Ticket again = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);
        assertThat(again.done().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void backgroundLeavesNetworkFailuresToTheFill() throws Exception {
        scheduler(new RetrySettings(0, Duration.ZERO, Duration.ZERO, 1));
        network = new NetworkState();
        scheduler.network(network);
        failNext(new SourceAccessException(PAGE.sourceId(), SourceAccessException.Reason.UNAVAILABLE, "erfunden",
                new ConnectException("erfunden: kein Netz")));

        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.BACKGROUND);

        assertThat(ticket.done().get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void closeCancelsWaitingPages() throws Exception {
        scheduler(new RetrySettings(0, Duration.ZERO, Duration.ZERO, 1));
        network = new NetworkState();
        scheduler.network(network);
        failNext(new SourceAccessException(PAGE.sourceId(), SourceAccessException.Reason.UNAVAILABLE, "erfunden",
                new ConnectException("erfunden: kein Netz")));
        AcquisitionScheduler.Ticket ticket = scheduler.request(APP, PAGE, AcquisitionScheduler.Priority.FOREGROUND);
        awaitOffline();

        scheduler.close();
        scheduler = null;

        assertThat(ticket.done()).isCancelled();
        try {
            ticket.done().join();
        } catch (CancellationException expected) {
            // abgebrochen wie jede wartende Anfrage
        }
    }

    private void awaitOffline() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (network.online() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            Thread.sleep(5);
        }
        assertThat(network.online()).as("Netz als weg gemeldet").isFalse();
        // Bis die Seite tatsächlich zurückgelegt ist.
        Thread.sleep(50);
    }
}
