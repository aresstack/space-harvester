package com.aresstack.spaceharvester.application.podcast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RankedPoolTest {

    @Test
    void takesTheSmallestRankFirstAndKeepsTheOrderWithinARank() throws Exception {
        List<String> done = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch busy = new CountDownLatch(1);
        try (RankedPool pool = new RankedPool(1, "test")) {
            CompletableFuture<Void> blocker = pool.supply(0, () -> {
                busy.await(5, TimeUnit.SECONDS);
                return null;
            });
            List<CompletableFuture<Boolean>> all = new ArrayList<>();
            all.add(pool.supply(PodcastProduction.rank(1, PodcastProduction.Stage.SPEECH, 0), () -> done.add("2 Audio")));
            all.add(pool.supply(PodcastProduction.rank(0, PodcastProduction.Stage.PICTURE, 0), () -> done.add("1 Bild")));
            all.add(pool.supply(PodcastProduction.rank(0, PodcastProduction.Stage.SPEECH, 1), () -> done.add("1 Audio b")));
            all.add(pool.supply(PodcastProduction.rank(0, PodcastProduction.Stage.SPEECH, 0), () -> done.add("1 Audio a")));
            all.add(pool.supply(PodcastProduction.rank(0, PodcastProduction.Stage.SPEECH, 0), () -> done.add("1 Audio a2")));
            all.add(pool.supply(PodcastProduction.rank(0, PodcastProduction.Stage.SCRIPT, 3), () -> done.add("1 Text")));
            busy.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        }
        assertThat(done).containsExactly("1 Text", "1 Audio a", "1 Audio a2", "1 Audio b", "1 Bild", "2 Audio");
    }

    @Test
    void aFailureLandsInTheFuture() {
        try (RankedPool pool = new RankedPool(2, "test")) {
            CompletableFuture<String> failed = pool.supply(0, () -> {
                throw new IllegalStateException("kaputt");
            });
            assertThat(failed).failsWithin(5, TimeUnit.SECONDS).withThrowableOfType(
                    java.util.concurrent.ExecutionException.class).withCauseInstanceOf(IllegalStateException.class);
        }
    }
}
