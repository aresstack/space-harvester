package com.aresstack.spaceharvester.application.terms;

/**
 * Ein Sprachmodell, das Akronyme aus Fundstellen ausschreibt (#122): Anweisung und Eingabe hinein, der Text der Antwort
 * heraus; geprüft wird die Antwort in {@link AcronymResolution}. Die Umsetzung über den KI-Dienst liefert
 * {@code ChatAcronymExpansionModel}; der Core kennt nur diesen Port. Aufgerufen nur aus dem Auftrag, nie aus einer
 * Anfrage.
 */
public interface AcronymExpansionModel {

    /** Kennung samt Verfahren und Modell; nie die Adresse des Dienstes. */
    String version();

    /** Ob gerade ein Modell eingestellt ist. */
    boolean available();

    /** Ob Text dieser Quelle an das Modell gehen darf ({@code external-processing-sources}, ADR 0011). */
    boolean mayProcess(String sourceId);

    /**
     * @param instructions Anweisung an das Modell (Systemnachricht)
     * @param input        die Fundstellen als JSON
     * @return die Antwort des Modells, unbearbeitet
     * @throws RuntimeException wenn der Dienst nicht antwortet; die Meldung nennt nie Text, Antwort oder Schlüssel
     */
    String complete(String instructions, String input);

    /** Kein Modell: nichts verfügbar, nichts freigegeben. */
    AcronymExpansionModel NONE = new AcronymExpansionModel() {
        @Override
        public String version() {
            return "none";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean mayProcess(String sourceId) {
            return false;
        }

        @Override
        public String complete(String instructions, String input) {
            throw new IllegalStateException("Kein Modell eingestellt");
        }
    };
}
