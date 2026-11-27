package com.aresstack.knowledge4j.run;

/** Lets long work end early when the user or the host cancels. Implementations are thread-safe. */
@FunctionalInterface
public interface CancellationSignal {

    CancellationSignal NEVER = () -> false;

    boolean isCancelled();

    /** Throws {@link CancelledException} once cancelled; a cheap check between steps. */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }
}
