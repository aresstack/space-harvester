package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;

/**
 * SPI eines deterministischen Prozessors. Gleiche Eingabe und gleiche Version ergeben dasselbe Ergebnis; ändert sich
 * die Logik, steigt {@link #version()}, und alle bisherigen Ergebnisse dieses Prozessors gelten als veraltet.
 */
public interface EnrichmentProcessor {

    /** Stabile Kennung, z. B. {@code acronyms}; Kleinbuchstaben, Ziffern und Bindestriche. */
    String id();

    /** Version der Logik, z. B. {@code 1}. */
    String version();

    /**
     * @return das Ergebnis; eine Ausnahme macht nur dieses Ergebnis fehlgeschlagen
     */
    EnrichmentOutput process(PageText text);

    /**
     * Wie {@link #process(PageText)}, mit der Seite, zu der der Text gehört; für Prozessoren, deren Verfahren von der
     * Quelle abhängt (etwa nur freigegebene Quellen an den KI-Dienst, ADR 0011). Standard von
     * {@link #process(PageSnapshot, PageText)}.
     */
    default EnrichmentOutput process(PageKey key, PageText text) {
        return process(text);
    }

    /**
     * Wie {@link #process(PageKey, PageText)}, mit dem ganzen Snapshot, etwa für den Titel der Seite.
     * {@link Enrichments} ruft diese Form.
     */
    default EnrichmentOutput process(PageSnapshot snapshot, PageText text) {
        return process(snapshot.key(), text);
    }

    /**
     * Ob der Prozessor diese Seite bearbeitet. Ein Prozessor, der Inhalte an einen externen Dienst schickt, bearbeitet
     * nur Seiten aus dafür freigegebenen Quellen (ADR 0011); für alle anderen entsteht kein Ergebnis.
     */
    default boolean appliesTo(PageKey key) {
        return true;
    }

    /**
     * Ob der Prozessor über das Netz rechnet. Dann berechnet {@link Enrichments#refresh} ihn im Hintergrund, sofern
     * {@link Enrichments} einen Hintergrund hat: Lesen, Abgleich und Export warten nie auf ihn.
     */
    default boolean background() {
        return false;
    }

    /**
     * Ob der Prozessor für diese Seite über das Netz rechnet; Vorgabe {@link #background()}. Ein Prozessor, der nur für
     * manche Seiten das Netz braucht, rechnet die übrigen sofort und belegt dafür keinen Platz im Hintergrund.
     */
    default boolean background(PageKey key) {
        return background();
    }

    /**
     * Ob der Prozessor auch Anhänge und enthaltene Dateien bearbeitet (ADR 0026, AP9a): je abgelegtem Abschnitt eines
     * Dokuments, Eingabe {@link DocumentSectionText}. Ob er es darf, entscheidet {@link #appliesTo(PageKey)} für die
     * Seite des Dokuments: Ein Dokument hat die Rechte und die Freigabe seiner Seite.
     */
    default boolean documents() {
        return false;
    }

    /**
     * Wie {@link #process(PageKey, PageText)} für einen Abschnitt eines Anhangs oder einer enthaltenen Datei; Vorgabe
     * mit der Seite des Dokuments.
     */
    default EnrichmentOutput process(DocumentKey document, PageText text) {
        return process(document.page(), text);
    }

    /**
     * Wie {@link #process(DocumentKey, PageText)} mit der Stelle des Abschnitts im Dokument und der Grenze für das
     * Embedding-Modell; Vorgabe ohne beides.
     */
    default EnrichmentOutput process(DocumentKey document, PageText text, DocumentPosition position) {
        return process(document, text);
    }

    /**
     * Ob ein gespeicherter Stand eines Dokuments trotz gleichen Textes und gleicher Version neu gerechnet werden muss,
     * weil sich außerhalb des Textes etwas geändert hat, etwa die Freigabe der Quelle; ohne den Text prüfbar.
     */
    default boolean outdated(Enrichment stored) {
        return false;
    }

    /**
     * Ob ein gespeicherter Stand mit gleichem Text und gleicher Version für diesen Abschnitt an dieser Stelle gilt;
     * Vorgabe: wenn er nicht {@link #outdated(Enrichment)} ist.
     */
    default boolean current(Enrichment stored, PageText text, DocumentPosition position) {
        return !outdated(stored);
    }
}
