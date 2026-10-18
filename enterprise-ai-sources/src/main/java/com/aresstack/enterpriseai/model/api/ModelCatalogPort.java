package com.aresstack.enterpriseai.model.api;

import com.aresstack.enterpriseai.astu.modelcatalog.ModelDescriptor;

import java.util.List;

/**
 * Eine Quelle von Modellen. {@link #models()} darf blockieren (HTTP, Prozessstart) und wird deshalb nie auf dem
 * Swing-EDT gerufen. Die Deskriptoren tragen {@link #catalogId()} als Katalog.
 */
public interface ModelCatalogPort {

    /** Stabile Kennung des Katalogs, Präfix der Auswahl in der Konfiguration (ohne Doppelpunkt). */
    String catalogId();

    /** Anzeigename des Katalogs. */
    String displayName();

    /**
     * @return die aktuell verfügbaren Modelle, nie {@code null}; leer, wenn die Quelle keine anbietet
     * @throws ModelCatalogException wenn die Quelle nicht erreichbar ist oder unlesbar antwortet
     */
    List<ModelDescriptor> models() throws ModelCatalogException;
}
