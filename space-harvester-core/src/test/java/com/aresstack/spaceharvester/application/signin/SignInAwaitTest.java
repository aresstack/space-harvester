package com.aresstack.spaceharvester.application.signin;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Warten auf die Anmeldung ohne Takt (ADR 0027) und ohne Monitor, der einen Träger festhält. */
class SignInAwaitTest {

    @Test
    void manyWaitersOnVirtualThreadsNeverHoldTheirCarriers() throws Exception {
        // Architekt 22.11.2026: Mit synchronized und wait() hielte jeder Wartende seinen Träger fest; der Thread,
        // der den Code sendet, käme dann nicht mehr dran.
        int waiters = 400;
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), new FixedClock());
        CountDownLatch joined = new CountDownLatch(waiters);
        signIns.onChange(joined::countDown);
        Challenge challenge = new Challenge();
        List<Future<SignInRequests.Outcome>> waiting = new ArrayList<>();
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < waiters; i++) {
                String jobId = "job-" + i;
                waiting.add(virtual.submit(() -> signIns.await("wiki", jobId, challenge, null, new StopContext(jobId))));
            }
            assertThat(joined.await(10, TimeUnit.SECONDS)).isTrue();
            String requestId = signIns.pendingFor("wiki").orElseThrow().id();

            Future<SignInRequest.Answer> sent = virtual.submit(() -> signIns.submit(requestId, "123456".toCharArray()));

            assertThat(sent.get(10, TimeUnit.SECONDS)).isEqualTo(SignInRequest.Answer.ACCEPTED);
            for (Future<SignInRequests.Outcome> each : waiting) {
                assertThat(each.get(10, TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.UNLOCKED);
            }
        }
        assertThat(signIns.pendingFor("wiki")).isEmpty();
    }

    @Test
    void cancellingTheJobWakesItsWaiterAtOnceAndWithdrawsTheRequest() throws Exception {
        // Die Uhr steht: die Wartezeit läuft nie ab, und ohne Takt weckt nur die Anforderung zum Abbrechen.
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), new FixedClock());
        CountDownLatch joined = new CountDownLatch(1);
        signIns.onChange(joined::countDown);
        StopContext context = new StopContext("job-1");
        CompletableFuture<SignInRequests.Outcome> waiter = CompletableFuture.supplyAsync(
                () -> signIns.await("wiki", "job-1", new Challenge(), null, context),
                Executors.newVirtualThreadPerTaskExecutor());
        assertThat(joined.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(signIns.waiting("job-1")).isPresent();

        context.cancel();

        assertThat(waiter).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingCause().isInstanceOf(JobCancelledException.class);
        assertThat(signIns.waiting("job-1")).isEmpty();
        assertThat(signIns.pendingFor("wiki")).isEmpty();
        assertThat(context.listeners).isEmpty();
    }

    @Test
    void aRunTimeLimitExpiringWhileWaitingEndsTheJobAtOnceWithTimeout() throws Exception {
        // Architekt 22.11.2026: Die Laufzeitgrenze meldet ihr Ablaufen über whenStopRequested (Zeitgeber in Jobs);
        // niemand antwortet, und die Anmelde-Wartezeit von 15 Minuten ist nicht um, trotzdem sofort TIMEOUT.
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), new FixedClock());
        CountDownLatch joined = new CountDownLatch(1);
        signIns.onChange(joined::countDown);
        StopContext context = new StopContext("job-1");
        CompletableFuture<SignInRequests.Outcome> waiter = CompletableFuture.supplyAsync(
                () -> signIns.await("wiki", "job-1", new Challenge(), null, context));
        assertThat(joined.await(5, TimeUnit.SECONDS)).isTrue();

        context.expire();

        assertThat(waiter).failsWithin(5, TimeUnit.SECONDS).withThrowableThat().havingCause()
                .isInstanceOfSatisfying(JobFailedException.class, e -> assertThat(e.code()).isEqualTo("TIMEOUT"));
        assertThat(signIns.waiting("job-1")).isEmpty();
    }

    @Test
    void anAlreadyCancelledJobDoesNotWaitAtAll() {
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), new FixedClock());
        StopContext context = new StopContext("job-1");
        context.cancel();

        CompletableFuture<SignInRequests.Outcome> waiter = CompletableFuture.supplyAsync(
                () -> signIns.await("wiki", "job-1", new Challenge(), null, context));

        assertThat(waiter).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingCause().isInstanceOf(JobCancelledException.class);
        assertThat(signIns.pendingFor("wiki")).isEmpty();
    }

    @Test
    void withoutAnswerTheWaitEndsDeclinedWhenItsTimeIsUp() {
        SignInRequests signIns = new SignInRequests(Duration.ofMillis(200), Clock.systemUTC());

        assertThat(signIns.await("wiki", "job-1", new Challenge(), null, new StopContext("job-1")))
                .isEqualTo(SignInRequests.Outcome.DECLINED);
        assertThat(signIns.pendingFor("wiki")).isEmpty();
    }

    @Test
    void decliningWakesEveryWaiter() throws Exception {
        SignInRequests signIns = new SignInRequests(Duration.ofMinutes(15), new FixedClock());
        CountDownLatch joined = new CountDownLatch(2);
        signIns.onChange(joined::countDown);
        CompletableFuture<SignInRequests.Outcome> first = CompletableFuture.supplyAsync(
                () -> signIns.await("wiki", "job-1", new Challenge(), null, null));
        CompletableFuture<SignInRequests.Outcome> second = CompletableFuture.supplyAsync(
                () -> signIns.await("wiki", "job-2", new Challenge(), null, new StopContext("job-2")));
        assertThat(joined.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(signIns.decline(signIns.pendingFor("wiki").orElseThrow().id())).isTrue();

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.DECLINED);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(SignInRequests.Outcome.DECLINED);
    }

    private static final class StopContext implements JobContext {

        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final String jobId;
        private volatile boolean cancelled;
        /** Laufzeitgrenze überschritten: meldet sich nicht, nur der Prüfpunkt sieht es. */
        volatile boolean timedOut;

        StopContext(String jobId) {
            this.jobId = jobId;
        }

        /** Wie der Zeitgeber der Laufzeitgrenze in {@code Jobs}: überfällig, dann die Wartenden wecken. */
        void expire() {
            timedOut = true;
            for (Runnable listener : listeners) {
                if (listeners.remove(listener)) {
                    listener.run();
                }
            }
        }

        void cancel() {
            cancelled = true;
            for (Runnable listener : listeners) {
                if (listeners.remove(listener)) {
                    listener.run();
                }
            }
        }

        @Override
        public AutoCloseable whenStopRequested(Runnable listener) {
            listeners.add(listener);
            if (cancelled && listeners.remove(listener)) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        @Override
        public String jobId() {
            return jobId;
        }

        @Override
        public void checkpoint() {
            if (cancelled) {
                throw new JobCancelledException(jobId);
            }
            if (timedOut) {
                throw new JobFailedException("TIMEOUT", "Laufzeitgrenze überschritten");
            }
        }

        @Override
        public boolean cancelRequested() {
            return cancelled;
        }

        @Override
        public void discovered(long pages) {
        }

        @Override
        public void processed(long pages) {
        }

        @Override
        public void written(long pages) {
        }

        @Override
        public void failed(long pages) {
        }
    }

    private static final class Challenge implements SignInChallenge {

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
            return "Code per E-Mail gesendet";
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
            return null;
        }

        @Override
        public SignInChallenge resend() {
            return this;
        }
    }

    /** Eine Uhr, die steht: nur ein Signal kann einen Wartenden wecken. */
    private static final class FixedClock extends Clock {

        private final Instant now = Instant.parse("2026-10-19T08:00:00Z");

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
}
