package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;

/** Verify actual connectivity before publishing source partitions. */
interface SourceConnectionVerifier {

    void verifyConnection() throws KnowledgeSourceException;
}
