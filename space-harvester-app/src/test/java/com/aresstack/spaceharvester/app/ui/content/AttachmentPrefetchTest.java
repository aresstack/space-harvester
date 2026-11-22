package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Der Vorlader gibt die übrigen Anhänge an den Hintergrund ab (Fundstelle 18); nur erfundene Anhänge. */
class AttachmentPrefetchTest {

    private static final List<String> KEYS = List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "mark-1");

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 30 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static final class Recording implements AttachmentPrefetch.Source {

        final List<String> loaded = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger idle = new AtomicInteger();

        @Override
        public byte[] load(String key) {
            loaded.add(key);
            return new byte[] {1};
        }

        @Override
        public void idle() {
            idle.incrementAndGet();
        }
    }

    @Test
    void itLoadsTheShownItsNeighboursAndTheMarksAndHandsOffTheRestOnce() throws Exception {
        Recording source = new Recording();
        List<List<String>> handed = Collections.synchronizedList(new ArrayList<>());
        try (AttachmentPrefetch prefetch = new AttachmentPrefetch(source, Throwable::getMessage, () -> { }, 10, 100,
                rest -> handed.add(rest), key -> key.startsWith("mark-"))) {
            prefetch.focus(KEYS, "a1");
            eventually(() -> source.idle.get() > 0);
        }

        assertThat(source.loaded).containsExactlyInAnyOrder("a1", "a2", "a3", "mark-1");
        assertThat(handed).containsExactly(List.of("a4", "a5", "a6", "a7"));
    }

    @Test
    void withoutAPlaceInTheBackgroundItLoadsEverythingAsBefore() throws Exception {
        Recording source = new Recording();
        try (AttachmentPrefetch prefetch = new AttachmentPrefetch(source, Throwable::getMessage, () -> { }, 10, 100,
                rest -> false, key -> key.startsWith("mark-"))) {
            prefetch.focus(KEYS, "a1");
            eventually(() -> source.idle.get() > 0);
        }

        assertThat(source.loaded).containsExactlyInAnyOrderElementsOf(KEYS);
    }
}
