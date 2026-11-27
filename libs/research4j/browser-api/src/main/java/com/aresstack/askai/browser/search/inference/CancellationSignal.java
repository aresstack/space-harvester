package com.aresstack.askai.browser.search.inference;

/**
 * A neutral, framework-free cancellation query. The research runtime's cooperative cancellation
 * (an {@code AtomicBoolean} gate) adapts to this so the layout resolver and the inference port can
 * be cancellation-aware without importing any runtime type. {@link #NONE} never cancels.
 */
public interface CancellationSignal {

    CancellationSignal NONE = new CancellationSignal() {
        public boolean isCancelled() {
            return false;
        }
    };

    boolean isCancelled();
}
