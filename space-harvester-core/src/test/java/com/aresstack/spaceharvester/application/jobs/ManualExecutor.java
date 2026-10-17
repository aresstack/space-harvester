package com.aresstack.spaceharvester.application.jobs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/** Führt übergebene Aufgaben erst aus, wenn der Test es verlangt; so bleiben Jobtests deterministisch. */
final class ManualExecutor implements Executor {

    private final Deque<Runnable> pending = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable command) {
        pending.addLast(command);
    }

    synchronized int pending() {
        return pending.size();
    }

    void runNext() {
        Runnable next;
        synchronized (this) {
            next = pending.removeFirst();
        }
        next.run();
    }
}
