package com.aresstack.knowledge4j.run;

/** The work was cancelled through its {@link CancellationSignal}. */
public final class CancelledException extends RuntimeException {

    public CancelledException() {
        super("cancelled");
    }
}
