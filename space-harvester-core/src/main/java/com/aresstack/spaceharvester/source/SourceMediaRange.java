package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;

/** Source adapter capability for authenticated, bounded remote media reads. */
interface SourceMediaRange {

    byte[] readRange(String localId, long offset, int maximumBytes) throws KnowledgeSourceException;
}
