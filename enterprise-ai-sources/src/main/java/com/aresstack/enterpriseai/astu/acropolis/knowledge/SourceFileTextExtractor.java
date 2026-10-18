package com.aresstack.enterpriseai.astu.acropolis.knowledge;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;

/**
 * Port: macht aus einer Datei einer Quelle (etwa einem Confluence-Anhang) Text für Index und Modell. Die Umsetzung
 * liegt außen (Dokumenterkennung und Apache Tika, Bilder über die Bildbeschreibung); der Kern kennt nur den Text.
 */
public interface SourceFileTextExtractor {

    /**
     * @param file    die Ressource der Datei (Titel = Dateiname, Typ = Medientyp der Quelle)
     * @param content die Originalbytes
     * @return der Text, leer, wenn die Datei keinen hat
     * @throws KnowledgeSourceException {@code UNSUPPORTED}, wenn das Format nicht lesbar ist
     */
    String extract(KnowledgeResource file, byte[] content) throws KnowledgeSourceException;
}
