package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;

/**
 * Ein Dateianhang einer Quelle samt Inhalt ({@link PageViewSource#loadAttachmentFile}): die Ressource mit Herkunft
 * (Titel, Typ, Revision, Seite als Eltern, Adresse) und die Originalbytes. Die Bytes werden nicht kopiert; der
 * Empfänger macht daraus Text und verwirft sie danach.
 */
public final class SourceFile {

    private final KnowledgeResource resource;
    private final byte[] content;

    public SourceFile(KnowledgeResource resource, byte[] content) {
        if (resource == null || content == null) {
            throw new IllegalArgumentException("resource and content must not be null");
        }
        this.resource = resource;
        this.content = content;
    }

    public KnowledgeResource resource() {
        return resource;
    }

    public byte[] content() {
        return content;
    }
}
