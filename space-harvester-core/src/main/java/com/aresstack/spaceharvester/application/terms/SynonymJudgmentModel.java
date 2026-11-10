package com.aresstack.spaceharvester.application.terms;

/**
 * Ein Sprachmodell, das Kandidatenpaare der Synonymerkennung (#336, Stufe C) anhand von Fundstellen beurteilt:
 * Anweisung und Eingabe hinein, der Text der Antwort heraus; geprüft wird die Antwort in {@link SynonymDiscovery}.
 * Die Umsetzung über den KI-Dienst liefert {@code ChatSynonymJudgmentModel}; der Core kennt nur diesen Port.
 * Aufgerufen nur aus dem Auftrag, nie beim Start, Abgleich, Suchen oder Öffnen einer Seite.
 */
public interface SynonymJudgmentModel {

    /** Kennung samt Verfahren und Modell; nie die Adresse des Dienstes. */
    String version();

    /** Ob gerade ein Chat-Modell eingestellt ist. */
    boolean available();

    /** Ob Text dieser Quelle an das Modell gehen darf ({@code external-processing-sources}, ADR 0011). */
    boolean mayProcess(String sourceId);

    /**
     * @param instructions Anweisung an das Modell (Systemnachricht)
     * @param input        die Kandidaten mit Fundstellen als JSON
     * @return die Antwort des Modells, unbearbeitet
     * @throws RuntimeException wenn der Dienst nicht antwortet; die Meldung nennt nie Text, Antwort oder Schlüssel
     */
    String complete(String instructions, String input);

    /** Kein Modell: nichts verfügbar, nichts freigegeben. */
    SynonymJudgmentModel NONE = new SynonymJudgmentModel() {
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
