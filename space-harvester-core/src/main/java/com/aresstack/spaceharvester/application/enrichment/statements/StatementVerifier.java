package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Die optionale zweite, knappe Prüfung einer Aussage gegen ihren Satz (#108): Sagt der Satz genau das? Zustimmung ist
 * kein Beweis; sie filtert nur Aussagen, die der Satz nicht trägt („der Beleg löst die Zahlung aus“, obwohl jedes Wort
 * vorkommt).
 */
public interface StatementVerifier {

    /** Die Antwort der Prüfung. */
    enum Verdict {
        YES,
        NO,
        UNSURE,
        /** Die Prüfung lief nicht (Modell gescheitert oder nicht da); die Aussage behält ihren Status. */
        UNAVAILABLE
    }

    /** Ob überhaupt geprüft wird. */
    boolean isEnabled();

    /** Kennung der Prüfung; Teil der Prozessorversion. */
    String version();

    /** Blockiert (Modellaufruf). */
    Verdict verify(String sentence, String claim);

    /** Keine Prüfung: jede Aussage behält ihren Status. */
    static StatementVerifier none() {
        return new StatementVerifier() {
            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public String version() {
                return "none";
            }

            @Override
            public Verdict verify(String sentence, String claim) {
                return Verdict.UNAVAILABLE;
            }
        };
    }
}
