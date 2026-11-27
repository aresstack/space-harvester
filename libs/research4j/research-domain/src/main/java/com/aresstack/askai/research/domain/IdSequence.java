package com.aresstack.askai.research.domain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable id generation as a PORT: the domain never invents randomness or clocks itself, so every test run
 * produces identical ids. The default counting sequence yields {@code <prefix>-1}, {@code <prefix>-2}, …
 * per prefix-independent global counter (monotonic within one aggregate lifetime; persisted aggregates
 * restore their counter from the highest seen id).
 */
public interface IdSequence {

    String next(String prefix);

    static IdSequence counting() {
        final AtomicLong counter = new AtomicLong();
        return new IdSequence() {
            public String next(String prefix) {
                return prefix + "-" + counter.incrementAndGet();
            }
        };
    }
}
