package com.aresstack.spaceharvester.application.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ModelPriorityTest {

    private final ModelPriority priority = new ModelPriority();

    @Test
    void withoutAnInteractiveDescriptionTheBackgroundDoesNotWait() {
        StopContext context = new StopContext();

        assertThat(priority.awaitIdle(context, () -> context.phases.add("wartet"))).isFalse();
        assertThat(context.phases).isEmpty();
    }

    @Test
    void theBackgroundWaitsUntilTheInteractiveDescriptionEnds() throws Exception {
        StopContext context = new StopContext();
        ModelPriority.Interactive running = priority.interactive();
        CountDownLatch waiting = new CountDownLatch(1);

        CompletableFuture<Boolean> background = CompletableFuture.supplyAsync(
                () -> priority.awaitIdle(context, waiting::countDown));
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(background).isNotDone();

        running.close();

        assertThat(background.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(priority.busy()).isFalse();
    }

    @Test
    void aFailingInteractiveDescriptionAlsoReleasesTheBackground() throws Exception {
        StopContext context = new StopContext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        CompletableFuture<Void> interactive = CompletableFuture.runAsync(() -> {
            try (ModelPriority.Interactive ignored = priority.interactive()) {
                entered.countDown();
                fail.await(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Modell nicht erreichbar");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Boolean> background = CompletableFuture.supplyAsync(
                () -> priority.awaitIdle(context, waiting::countDown));
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();

        fail.countDown();

        assertThat(background.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interactive).isCompletedExceptionally();
    }

    @Test
    void cancellingEndsTheWaitWithoutTheInteractiveDescriptionEnding() throws Exception {
        StopContext context = new StopContext();
        ModelPriority.Interactive running = priority.interactive();
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Boolean> background = CompletableFuture.supplyAsync(
                () -> priority.awaitIdle(context, waiting::countDown));
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();

        context.cancel();

        assertThatThrownBy(() -> background.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(JobCancelledException.class);
        assertThat(priority.busy()).as("die Beschreibung des Benutzers läuft weiter").isTrue();
        running.close();
    }

    @Test
    void aWaitRequestedAfterCancellingEndsAtOnce() {
        StopContext context = new StopContext();
        context.cancel();
        try (ModelPriority.Interactive ignored = priority.interactive()) {
            assertThatThrownBy(() -> priority.awaitIdle(context, () -> { }))
                    .isInstanceOf(JobCancelledException.class);
        }
    }

    /** Auftrag, dessen Abbruch die Wartenden über {@link #whenStopRequested} weckt, wie {@code Jobs}. */
    private static final class StopContext implements JobContext {

        final List<String> phases = new CopyOnWriteArrayList<>();
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;

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
            return "job-1";
        }

        @Override
        public void checkpoint() {
            if (cancelled) {
                throw new JobCancelledException(jobId());
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
}
