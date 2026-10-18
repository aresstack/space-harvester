package com.aresstack.spaceharvester.application.query.progressive;

/**
 * Ein Sprachmodell, das Suchvarianten formuliert (ADR 0017): Anweisung und Frage hinein, der Text der Antwort heraus.
 * Die Umsetzung über den KI-Dienst liefert {@code RemoteAi#queryVariants}; der Core kennt nur diesen Port. Die Frage
 * ist die Eingabe des Nutzers, nie Inhalt einer Quelle.
 */
@FunctionalInterface
public interface QueryVariantModel {

    /**
     * @param instructions Anweisung an das Modell (Systemnachricht)
     * @param question     die ursprüngliche Anfrage des Nutzers
     * @return die Antwort des Modells, unbearbeitet
     * @throws RuntimeException wenn der Dienst nicht antwortet; die Meldung nennt nie Frage oder Schlüssel
     */
    String complete(String instructions, String question);
}
