package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;

/**
 * Der EINE Knopf „Programmsuche“ (#1486) für FTP, NDV und die Seitenansicht: ein runder Symbolknopf wie alle Aktionen
 * (Angelo, 27.11.2026: kein Textknopf). Während die gespeicherten Analysen gelesen werden, zeigt er das Kreuz; ein
 * zweiter Klick bricht ab.
 */
public final class ProgramSearchButtons {

    public static final String TIP = "Programmsuche: gespeicherte COBOL-, JCL- und Natural-Analysen durchsuchen";
    public static final String BUSY_TIP = "Programmsuche läuft – klicken zum Abbrechen";

    private ProgramSearchButtons() {
    }

    /** Ein neuer Knopf im Ruhezustand. */
    public static IconButton create() {
        IconButton button = new IconButton(Glyph.PROGRAM_SEARCH, TIP);
        busy(button, false);
        return button;
    }

    /** Ruhe (Klammern mit Lupe) oder laufende Suche (Kreuz, Klick bricht ab). */
    public static void busy(IconButton button, boolean busy) {
        button.setIcon(busy ? Glyph.CANCEL : Glyph.PROGRAM_SEARCH);
        String tip = busy ? BUSY_TIP : TIP;
        button.setToolTipText(tip);
        button.getAccessibleContext().setAccessibleName(busy ? "Programmsuche abbrechen" : "Programmsuche");
    }
}
