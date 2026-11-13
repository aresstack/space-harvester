package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import java.util.Set;

/**
 * Hört, wenn ein Dokument-Anhang mit seinen abgelegten Abschnitten neu oder geändert im Volltextindex liegt und wenn
 * Anhänge aus der vollständigen Liste einer Seite verschwinden (ADR 0026, AP9a), etwa für die Analysen je Dokument.
 * Wird im Thread des Indexierens gerufen; wer rechnen will, plant einen Auftrag ein. Fehler bleiben beim Hörer.
 */
public interface IndexedDocuments {

    /**
     * Ein Dokument-Anhang liegt neu oder geändert im Volltextindex.
     *
     * @param extractions die strukturierte Extraktion, aus deren Ablage er gelesen wurde
     */
    void indexed(PageAttachmentList list, Asset asset, DocumentExtractions extractions);

    /** Die vollständige Anhangsliste der Seite nennt nur noch diese Anhänge. */
    void retained(PageKey page, Set<String> attachmentIds);
}
