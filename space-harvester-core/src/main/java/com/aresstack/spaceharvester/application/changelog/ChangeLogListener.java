package com.aresstack.spaceharvester.application.changelog;

/** Empfänger des Änderungsprotokolls (ADR 0027/0030). */
@FunctionalInterface
public interface ChangeLogListener {

    /**
     * Im Protokoll der Quelle sind neue Zeilen oder eine neue Update-Marke; wer danach {@link ChangeLog#updates} oder
     * {@link ChangeLog#entries} liest, verpasst nichts. Gebündelt, je Quelle höchstens alle
     * {@link com.aresstack.spaceharvester.application.observe.Coalescer#INTERVAL}.
     */
    void changed(String sourceId);
}
