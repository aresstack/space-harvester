package com.aresstack.enterpriseai.astu.acropolis.image;

/** Ablage der Auswertungen je Bild und Version (der Host legt sie im Projekt ab). */
public interface ImageAnalysisCache {

    /** @return die gespeicherte Auswertung oder {@code null} */
    ImageAnalysis get(String cacheKey);

    void put(String cacheKey, ImageAnalysis analysis);
}
