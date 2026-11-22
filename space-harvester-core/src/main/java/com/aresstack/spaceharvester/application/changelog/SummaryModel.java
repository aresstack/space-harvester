package com.aresstack.spaceharvester.application.changelog;

/**
 * Das Chat-Modell für Zusammenfassungen (ADR 0030, Abschnitt 5): Anweisung und Eingabe hinein, der Text der Antwort
 * heraus. Aufgerufen nur aus dem Auftrag „Zusammenfassen“.
 */
public interface SummaryModel {

    /** Kennung samt Verfahren und Modell; nie die Adresse des Dienstes. */
    String version();

    /** Ob gerade ein Modell eingestellt ist. */
    boolean available();

    /**
     * @throws RuntimeException wenn der Dienst nicht antwortet; die Meldung nennt nie Text, Antwort oder Schlüssel
     */
    String complete(String instructions, String input);

    /** So viele Anfragen gehen höchstens zugleich an das Modell ({@code ai.parallel}); ohne Angabe eine. */
    default int parallel() {
        return 1;
    }

    /** Kein Modell. */
    SummaryModel NONE = new SummaryModel() {
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
