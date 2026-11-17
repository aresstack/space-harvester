package com.aresstack.spaceharvester.application.simplification;

/**
 * Das Chat-Modell der Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3): Anweisung und Eingabe hinein, der Text der Antwort
 * heraus. Aufgerufen nur aus {@link Simplifications}, also nur auf ausdrücklichen Auftrag und nur für Seiten, die für
 * den KI-Dienst freigegeben sind.
 */
public interface SimplificationModel {

    /** Kennung samt Verfahren und Modell; nie die Adresse des Dienstes. Teil des Schlüssels der Ablage. */
    String version();

    /** Ob gerade ein Modell eingestellt ist. */
    boolean available();

    /**
     * @throws RuntimeException wenn der Dienst nicht antwortet; die Meldung nennt nie Text, Antwort oder Schlüssel
     */
    String complete(String instructions, String input);

    /** Kein Modell. */
    SimplificationModel NONE = new SimplificationModel() {
        @Override
        public String version() {
            return "none";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String complete(String instructions, String input) {
            throw new IllegalStateException("Kein Modell eingestellt");
        }
    };
}
