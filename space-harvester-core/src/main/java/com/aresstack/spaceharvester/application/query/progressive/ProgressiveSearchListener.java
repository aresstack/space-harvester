package com.aresstack.spaceharvester.application.query.progressive;

import java.util.List;

/**
 * Ereignisse einer progressiven Suche für eine Oberfläche, die zusieht (ADR 0017). Ein Agent braucht sie nicht, er
 * wartet auf {@link ProgressiveSearch#result()}. Aufrufe kommen nacheinander aus dem Suchvorgang; eine Ausnahme des
 * Empfängers wird verworfen. Je freigegebener Suchanfrage kommt höchstens ein {@link #ranked}, damit die Liste nicht
 * springt.
 */
public interface ProgressiveSearchListener {

    /** Die Suchanfragen mit Priorität und Stand, etwa zur Anzeige als Vorschläge. */
    default void variants(List<ProgressiveResult.Variant> variants) {
    }

    /** Die ganze Trefferliste nach einer Freigabe: neue Treffer eingeordnet, bekannte an ihrem neuen Platz. */
    default void ranked(List<ProgressiveResult.RankedHit> hits) {
    }

    /** Die Suche ist abgeschlossen, abgebrochen oder gescheitert. */
    default void completed(ProgressiveResult result) {
    }

    ProgressiveSearchListener NONE = new ProgressiveSearchListener() {
    };
}
