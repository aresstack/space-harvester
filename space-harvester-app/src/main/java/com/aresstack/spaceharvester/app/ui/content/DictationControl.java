package com.aresstack.spaceharvester.app.ui.content;

/**
 * Bedienabsicht „Diktieren“ für den Composer (wie {@link ReadAloudControl} nur Absichten, keine Ports): Mikrofon an,
 * Mikrofon aus und erkennen, verwerfen, nach einem Fehler erneut erkennen; dazu der Live-Pegel für Pegelbalken und
 * Wellenform. Der erkannte Text landet am Cursor im Eingabefeld. Ist keine Spracheingabe verfügbar
 * (kein STT-Modell gewählt oder nicht installiert), bleibt der Mikrofon-Knopf deaktiviert und sein Tooltip nennt den
 * Grund. Alle Methoden laufen auf dem EDT, die Rückrufe ebenso.
 */
public interface DictationControl {

    /** Die Spracheingabe wurde ausgetauscht (anderes STT-Modell): Verfügbarkeit und Tooltip neu lesen. */
    interface Listener {
        void dictationChanged();
    }

    /**
     * Ausgang einer Aufnahme; genau ein Aufruf von {@link #recognized} oder {@link #failed} je {@link #start}, auf dem
     * EDT. Davor meldet {@link #partial} jeden schon erkannten Abschnitt, damit der Text während der Erkennung wächst.
     */
    interface Result {
        /** Ein fertig erkannter Abschnitt in Reihenfolge; {@link #recognized} bringt danach den ganzen Text. */
        default void partial(String text) {
        }

        /**
         * „Live mitschreiben“: ersetzt den Live-Text dieser Aufnahme im Entwurf. {@code committed} steht fest,
         * {@code provisional} kann die nächste Erkennung noch ändern.
         */
        default void live(String committed, String provisional) {
        }

        /** Der Live-Text ist fertig ({@code text}); danach eintreffende Abschnitte folgen dahinter. */
        default void liveDone(String text) {
        }

        void recognized(String text);

        void failed(String message);
    }

    /** Ob diktiert werden kann. */
    boolean isAvailable();

    /** Tooltip-Zusatz des Knopfs: das Modell oder, wenn nicht verfügbar, der Grund. */
    String description();

    /** Schaltet zwischen „erst komplett“ und „live mitschreiben“ um, auch während der Aufnahme. */
    default void setLive(boolean live) {
    }

    /** Öffnet das Mikrofon und nimmt auf; Fehler (kein Mikrofon) kommen über {@link Result#failed}. */
    void start(Result result);

    /** Beendet die Aufnahme und erkennt sie im Hintergrund; das Ergebnis kommt über {@link Result}. */
    void finish();

    /** Verwirft Aufnahme bzw. Erkennung ohne Ergebnis; idempotent. */
    void cancel();

    /**
     * Der Mikrofonpegel seit dem letzten Aufruf in Prozent auf der dB-Skala (0 = -60 dB, 100 = Vollausschlag), 0
     * ohne laufende Aufnahme. Der Composer fragt ihn während der Aufnahme alle 100 ms ab (Pegelbalken, Wellenform).
     */
    int level();

    /** Ob die laufende Aufnahme übersteuert war. */
    boolean clipping();

    /** Ob die letzte Aufnahme nach einem Fehlschlag noch einmal erkannt werden kann. */
    boolean canRetry();

    /** Erkennt die letzte fehlgeschlagene Aufnahme erneut; das Ergebnis kommt über {@link Result}. */
    void retry(Result result);

    void addListener(Listener listener);
}
