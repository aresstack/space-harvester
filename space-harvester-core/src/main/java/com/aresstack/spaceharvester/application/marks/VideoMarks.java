package com.aresstack.spaceharvester.application.marks;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

import java.util.List;

/**
 * Die Marken eines Videos, wie der Player sie gefunden hat (Folienwechsel in Millisekunden), je Seite und Fassung
 * gespeichert. Sie erscheinen als virtuelle Anhänge der Seite, und die Bildanalyse beschreibt ihre Standbilder
 * ({@link VideoMark}). Ohne Marken gibt es keine virtuellen Anhänge.
 */
public interface VideoMarks {

    /** Die Marken dieser Fassung in zeitlicher Reihenfolge; leer, solange der Player keine gefunden hat. */
    List<Long> find(PageKey page, String version);

    /** Speichert die Marken dieser Fassung; leer entfernt sie. */
    void save(PageKey page, String version, List<Long> marks);

    /** Die Marken in der Anreicherungsablage, wie die Lese-Ansicht einer Seite. */
    static VideoMarks in(EnrichmentStore store) {
        return new StoredVideoMarks(store);
    }
}
