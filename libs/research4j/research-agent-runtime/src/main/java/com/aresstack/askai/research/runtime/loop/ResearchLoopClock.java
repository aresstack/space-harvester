package com.aresstack.askai.research.runtime.loop;

/**
 * Injectable time source — tests advance it manually. {@link #sleepMillis(long)} exists ONLY for the
 * cooperative manual-challenge wait (short ticks, cancel-aware caller); tests advance the clock instead of
 * sleeping, so no test ever waits on real time.
 */
public interface ResearchLoopClock {

    long currentTimeMillis();

    /** Block the calling thread briefly (production); test clocks advance their time instead. */
    void sleepMillis(long millis);
}
