package com.aresstack.spaceharvester.application.stepmodel;

/**
 * Das Chat-Modell eines Analyseschritts (ADR 0032): der gemeinsame Port der Vorstufe „Sätze vereinfachen“ (FL3) und
 * des Prüfschritts (FL4). Anweisung und Eingabe hinein, der Text der Antwort heraus; Temperatur 0, eine am
 * Ausgabelimit abgeschnittene Antwort wirft. Aufgerufen nur auf ausdrücklichen Auftrag und nur für Seiten, die für den
 * KI-Dienst freigegeben sind (KI-Freigabe je Seite, ADR 0028); das entscheidet der aufrufende Schritt.
 */
public interface StepModel {

    /** Kennung samt Verfahren, Modell und Fingerabdruck des Dienstes; nie die Adresse. Teil des Schlüssels der Ablage. */
    String version();

    /** Ob gerade ein Modell eingestellt ist. */
    boolean available();

    /**
     * @throws RuntimeException wenn der Dienst nicht antwortet oder die Antwort abgeschnitten ist; die Meldung nennt nie
     *                          Text, Antwort oder Schlüssel
     */
    String complete(String instructions, String input);

    /** Kein Modell. */
    StepModel NONE = new StepModel() {
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
