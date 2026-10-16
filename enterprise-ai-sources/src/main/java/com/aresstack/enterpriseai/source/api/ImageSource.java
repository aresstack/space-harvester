package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;

import java.util.List;

/**
 * Optionale Fähigkeit einer Quelle: Bilder einer Seite als Binärdaten (der eigene Binärpfad neben dem Text).
 * Wer Bilder auswertet (OCR, Bildbeschreibung), fragt die Quelle über diese Schnittstelle; Text und Index
 * bleiben unberührt.
 */
public interface ImageSource {

    /** Die Bilder einer Seite ({@code image/*}), höchstens {@code max}; Revision je Bild für das Zwischenspeichern. */
    List<KnowledgeResource> imagesOf(KnowledgeResourceId page, int max) throws KnowledgeSourceException;

    /** Die Bytes eines Bildes aus {@link #imagesOf}. */
    byte[] loadImage(KnowledgeResourceId image) throws KnowledgeSourceException;
}
