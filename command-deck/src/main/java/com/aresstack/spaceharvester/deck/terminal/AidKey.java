package com.aresstack.spaceharvester.deck.terminal;

/**
 * Die Tasten, die eine Eingabe an den Host schicken (Attention Identifier): ENTER, CLEAR, PA1–PA3, SysReq und PF1–PF24.
 * Sondertasten und PF-Leiste der Oberfläche wie in MainframeMate.
 */
public enum AidKey {
    ENTER("ENT", "Daten senden (Enter)"),
    CLEAR("CLR", "Bildschirm löschen (Clear)"),
    PA1("PA1", "Program Attention 1"),
    PA2("PA2", "Program Attention 2"),
    PA3("PA3", "Program Attention 3"),
    SYSREQ("SYS", "System Request"),
    PF1, PF2, PF3, PF4, PF5, PF6, PF7, PF8, PF9, PF10, PF11, PF12,
    PF13, PF14, PF15, PF16, PF17, PF18, PF19, PF20, PF21, PF22, PF23, PF24;

    /** Die Sondertasten der Werkzeugleiste in der Reihenfolge aus MainframeMate. */
    public static final java.util.List<AidKey> SPECIAL = java.util.List.of(ENTER, CLEAR, PA1, PA2, PA3, SYSREQ);

    private final String label;
    private final String hint;

    AidKey() {
        this(null, null);
    }

    AidKey(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /** PF-Taste 1 bis 24. */
    public static AidKey pf(int number) {
        if (number < 1 || number > 24) {
            throw new IllegalArgumentException("PF-Taste gibt es nur von 1 bis 24: " + number);
        }
        return values()[PF1.ordinal() + number - 1];
    }

    /** Nummer der PF-Taste, 0 für die Sondertasten. */
    public int pfNumber() {
        return ordinal() >= PF1.ordinal() ? ordinal() - PF1.ordinal() + 1 : 0;
    }

    /** Kurzname auf dem Knopf: „ENT“, „CLR“ … bzw. „F3“. */
    public String label() {
        return label != null ? label : "F" + pfNumber();
    }

    /** Tooltip. */
    public String hint() {
        return hint != null ? hint : "PF" + pfNumber();
    }
}
