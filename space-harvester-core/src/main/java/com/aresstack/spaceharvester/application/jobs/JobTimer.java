package com.aresstack.spaceharvester.application.jobs;

import java.time.Duration;

/** Führt eine Aufgabe nach einer Wartezeit aus; für den Wiederanlauf in {@link Jobs}, in Tests von Hand. */
@FunctionalInterface
public interface JobTimer {

    void schedule(Duration delay, Runnable task);
}
