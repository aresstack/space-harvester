package com.aresstack.enterpriseai.astu.acropolis.navigation;

import java.util.List;

/**
 * Benennt automatisch erkannte Themenabschnitte (FA-04), austauschbar. Ohne Benenner gilt die Bezeichnung aus der
 * Analyse (askai arch {@code TopicClusterer}: erster Satz der repräsentativen Passage).
 */
public interface SectionLabeler {

    /**
     * @param documentTitle Titel des Dokuments (Kontext)
     * @param sectionTexts  der Text je Themenabschnitt in Lesereihenfolge
     * @return je Abschnitt eine kurze Bezeichnung (gleiche Länge und Reihenfolge); ein leerer oder fehlender Eintrag
     *         behält die Bezeichnung aus der Analyse; {@code null}: alle behalten
     */
    List<String> labels(String documentTitle, List<String> sectionTexts);
}
