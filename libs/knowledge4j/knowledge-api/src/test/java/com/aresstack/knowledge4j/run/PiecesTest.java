package com.aresstack.knowledge4j.run;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiecesTest {

    @Test
    void resultsKeepTheOrderAndPiecesRunAtTheSameTime() {
        final CountDownLatch both = new CountDownLatch(2);
        List<String> out = Pieces.parallel(2).run("stage", Arrays.asList("a", "b"), item -> item, item -> {
            both.countDown();
            try {
                assertTrue(both.await(5, TimeUnit.SECONDS), "both pieces run at once");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return item.toUpperCase();
        });
        assertEquals(Arrays.asList("A", "B"), out);
    }

    @Test
    void aFailingPieceEndsTheStageWithItsException() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Pieces.parallel(1).run("stage", Arrays.asList(1, 2), String::valueOf, item -> {
                    if (item == 2) {
                        throw new IllegalArgumentException("two");
                    }
                    return item;
                }));
        assertEquals("two", thrown.getMessage());
        assertEquals(Collections.emptyList(), Pieces.parallel(3).run("stage", Collections.<Integer>emptyList(),
                String::valueOf, item -> item));
    }
}
