package com.aresstack.spaceharvester.application.passages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Benennt erkannte Themenabschnitte über ein Sprachmodell (#181, ADR 0020), wie {@code ChatSectionLabeler} im
 * Enterprise AI Client. Die Umsetzung über den KI-Dienst liefert {@code ChatNavigationLabeler}; der Core kennt nur
 * diesen Port. Aufgerufen nur aus der Anreicherung ({@link NavigationProcessor}), nie aus einer Anfrage.
 */
public interface NavigationLabelModel {

    /** Kennung samt Verfahren und Modell; ein anderer Wert macht vorhandene Navigationen alt. */
    String version();

    /** Ob gerade ein Modell eingestellt ist. */
    boolean available();

    /** Ob Text dieser Quelle an das Modell gehen darf ({@code external-processing-sources}, ADR 0011). */
    boolean mayLabel(String sourceId);

    /**
     * @param documentTitle Titel des Dokuments oder {@code null}
     * @param sectionTexts  der Text je Themenabschnitt in Lesereihenfolge
     * @return je Abschnitt eine Bezeichnung oder {@code null}; {@link Labels#complete()} falsch, wenn ein Teil der
     *         Anfragen scheiterte
     * @throws RuntimeException wenn das Modell nicht antwortet; die Meldung nennt nie Text, Antwort oder Schlüssel
     */
    Labels labels(String documentTitle, List<String> sectionTexts);

    /**
     * Die Bezeichnungen.
     *
     * @param labels   je Abschnitt eine Bezeichnung oder {@code null} (dann gilt die der Analyse)
     * @param complete ob alle Anfragen an das Modell beantwortet wurden
     */
    record Labels(List<String> labels, boolean complete) {

        public Labels {
            labels = labels == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(labels));
        }
    }
}
