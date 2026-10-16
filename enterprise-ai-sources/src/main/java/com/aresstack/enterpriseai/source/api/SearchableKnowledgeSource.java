package com.aresstack.enterpriseai.source.api;

import java.util.List;

/**
 * Optionale Fähigkeit einer Quelle, selbst zu suchen (MediaWiki-Volltextsuche, Confluence-CQL). Quellen ohne
 * eigene Suche implementieren nur {@link KnowledgeSourcePort}; Aufrufer prüfen mit {@code instanceof}.
 *
 * <p>Die Suche der Quelle ersetzt nicht den Knowledge-Index: Sie dient dazu, Ressourcen zum Indexieren oder
 * Nachladen zu finden, nicht als Retrieval für den RAG-Kontext.
 */
public interface SearchableKnowledgeSource extends KnowledgeSourcePort {

    /** Höchstens {@link SourceQuery#limit()} Treffer in der Rangfolge der Quelle. */
    List<SourceSearchHit> search(SourceQuery query) throws KnowledgeSourceException;
}
