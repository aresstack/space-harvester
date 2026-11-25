package com.aresstack.enterpriseai.source.api;

/** Verify actual remote connectivity without discovering an entire resource scope. */
public interface SourceConnectionVerifier {
    void verifyConnection() throws KnowledgeSourceException;
}
