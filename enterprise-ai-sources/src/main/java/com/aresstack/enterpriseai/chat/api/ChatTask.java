package com.aresstack.enterpriseai.chat.api;

/** Handle auf eine laufende Streaming-Anfrage. */
public interface ChatTask {

    /**
     * Bricht die Anfrage ab. Idempotent; nach Abschluss wirkungslos. Ist die Anfrage noch nicht
     * abgeschlossen, endet sie mit {@link ChatStreamListener#onCancelled()}.
     */
    void cancel();

    /** @return {@code true}, sobald ein Abschluss-Callback zugestellt wurde oder gerade zugestellt wird */
    boolean isDone();

    /** @return {@code true}, wenn die Anfrage durch {@link #cancel()} beendet wurde */
    boolean isCancelled();
}
