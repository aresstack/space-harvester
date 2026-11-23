package com.aresstack.spaceharvester.mermaid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Hintergrund-Aufwärmlauf ({@code MermaidRenderer.getInstance()}) und das erste Rendern dürfen den Kontext nicht
 * je einmal aufwärmen: das zweite Aufwärmen verwarf den ersten Kontext, ein Rendern dazwischen wurde abgebrochen und
 * fiel auf einen kalten Kontext zurück (dreimal 2,9 MB Präambel; auf ausgelasteter CI über zwei Minuten).
 */
class GraalJsExecutorWarmUpTest {

    private final GraalJsExecutor executor = new GraalJsExecutor();
    private final ExecutorService threads = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
        executor.disposeWarmContext();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentWarmUpAndRenderEvaluateThePreambleOnce() throws Exception {
        AtomicInteger warmUps = new AtomicInteger();
        Supplier<String> preamble = () -> {
            warmUps.incrementAndGet();
            return "var answer = 0; for (var i = 0; i < 20000; i++) { answer = (answer + i) % 1000; } answer = 42;";
        };
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            boolean render = i % 2 == 1;
            results.add(threads.submit((Callable<String>) () -> {
                start.await();
                if (render) {
                    JsExecutionResult result = executor.executeWarm(preamble, "var out = answer + 1;", "out");
                    return result.isSuccessful() ? result.getOutput() : "FAILED: " + result.getErrorMessage();
                }
                return executor.ensureWarm(preamble) ? "warm" : "FAILED";
            }));
        }
        start.countDown();
        for (int i = 0; i < results.size(); i++) {
            String outcome = results.get(i).get(50, TimeUnit.SECONDS);
            assertEquals(i % 2 == 1 ? "43" : "warm", outcome);
        }
        assertEquals(1, warmUps.get(), "die Präambel wird genau einmal ausgewertet");
        assertTrue(executor.isWarm());
    }
}
