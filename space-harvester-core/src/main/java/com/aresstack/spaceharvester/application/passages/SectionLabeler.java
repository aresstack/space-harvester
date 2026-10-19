package com.aresstack.spaceharvester.application.passages;

import java.util.List;

/**
 * Benennt erkannte Themenabschnitte (FA-04), austauschbar, wie {@code SectionLabeler} im Enterprise AI Client. Ohne
 * Benenner gilt die Bezeichnung aus der Analyse (erster Satz).
 */
public interface SectionLabeler {

    /**
     * Je Abschnitt eine Bezeichnung in derselben Reihenfolge; {@code null} insgesamt oder je Eintrag lässt die
     * Bezeichnung aus der Analyse stehen.
     *
     * @param documentTitle Titel der Seite oder {@code null}
     * @param sectionTexts  der Wortlaut je Abschnitt
     */
    List<String> labels(String documentTitle, List<String> sectionTexts);
}
