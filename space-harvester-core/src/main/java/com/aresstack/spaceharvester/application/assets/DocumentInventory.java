package com.aresstack.spaceharvester.application.assets;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Bestand der abgelegten Dokument-Anhänge für die Analysen je Dokument (ADR 0026, AP9a); umgesetzt von {@link Assets}. */
public interface DocumentInventory {

    /** Die angeschlossene strukturierte Extraktion, wenn es eine gibt. */
    Optional<DocumentExtractions> documentExtractions();

    /**
     * Die abgelegten Dokument-Anhänge dieser Seiten, die im Volltextindex ihrer Seite stehen, je Anhang in der zuletzt
     * abgelegten Version. Liest nichts von der Quelle.
     */
    List<Asset> indexedDocuments(String sourceId, Collection<String> pageIds);
}
