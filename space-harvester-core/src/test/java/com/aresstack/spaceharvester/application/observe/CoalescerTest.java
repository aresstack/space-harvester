package com.aresstack.spaceharvester.application.observe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Gebündelte Zustellung (ADR 0027): je Schlüssel einmal je Intervall, Werte gesammelt, Fehler stoppen nichts. */
class CoalescerTest {

    private static final Duration INTERVAL = Duration.ofMillis(100);

    @Test
    void marksWithinTheIntervalArriveAsOneDeliveryWithAllValues() throws InterruptedException {
        BlockingQueue<Map.Entry<String, Set<String>>> delivered = new LinkedBlockingQueue<>();
        try (Coalescer<String, String> coalescer = new Coalescer<>("test", INTERVAL,
                (key, values) -> delivered.add(Map.entry(key, Set.copyOf(values))))) {
            coalescer.mark("DEV", List.of("1"));
            coalescer.mark("DEV", List.of("2", "3"));
            coalescer.mark("OPS");

            assertThat(List.of(next(delivered), next(delivered))).containsExactlyInAnyOrder(
                    Map.entry("DEV", Set.of("1", "2", "3")), Map.entry("OPS", Set.of()));
            assertThat(delivered.poll(INTERVAL.toMillis() * 3, TimeUnit.MILLISECONDS)).as("nichts doppelt").isNull();

            coalescer.mark("DEV", List.of("4"));
            assertThat(next(delivered)).isEqualTo(Map.entry("DEV", Set.of("4")));
        }
    }

    @Test
    void aFailingDeliveryStopsNeitherLaterDeliveriesNorTheThread() throws InterruptedException {
        BlockingQueue<String> delivered = new LinkedBlockingQueue<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        try (Coalescer<String, String> coalescer = new Coalescer<>("test", INTERVAL, (key, values) -> {
            delivered.add(key);
            if (fail.getAndSet(false)) {
                throw new IllegalStateException("kaputt");
            }
        })) {
            coalescer.mark("DEV");
            assertThat(next(delivered)).isEqualTo("DEV");
            coalescer.mark("OPS");
            assertThat(next(delivered)).isEqualTo("OPS");
            coalescer.execute(() -> {
                throw new IllegalStateException("kaputt");
            });
            coalescer.execute(() -> delivered.add("danach"));
            assertThat(next(delivered)).isEqualTo("danach");
        }
    }

    @Test
    void aLaterMarkArrivesAfterItsDelayAndNothingArrivesAfterClose() throws InterruptedException {
        BlockingQueue<String> delivered = new LinkedBlockingQueue<>();
        Coalescer<String, String> coalescer = new Coalescer<>("test", INTERVAL, (key, values) -> delivered.add(key));
        long start = System.nanoTime();
        coalescer.markLater("DEV", List.of(), Duration.ofMillis(200));
        assertThat(next(delivered)).isEqualTo("DEV");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));

        coalescer.mark("OPS");
        coalescer.close();
        coalescer.mark("DEV");
        coalescer.close();
        assertThat(delivered.poll(INTERVAL.toMillis() * 3, TimeUnit.MILLISECONDS)).isNull();
    }

    private static <T> T next(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(5, TimeUnit.SECONDS);
        assertThat(value).as("Zustellung").isNotNull();
        return value;
    }
}
