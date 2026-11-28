package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;

/**
 * Ein Hauptbereich. Das Hauptfenster zeigt rechts {@link #component()}, links dauerhaft den {@link Explorer} und über
 * der Statusleiste die {@link #actions()} des Bereichs.
 */
public interface View {

    JComponent component();

    /** Der Bereich wird sichtbar; Gelegenheit, veraltete Daten neu zu laden. */
    default void shown() {
    }

    /** Aktionen und Fortschritt des Bereichs direkt über der Statusleiste; {@code null}, wenn er keine hat. */
    default JComponent actions() {
        return null;
    }

    /**
     * Stand des Bereichs (etwa „25 Treffer · weitere vorhanden“) für die Statuszeile des Hauptfensters, links neben
     * dem Stand der App; {@code null}, wenn er keinen hat. Sichtbar nur, solange der Bereich gezeigt wird.
     */
    default JComponent statusInfo() {
        return null;
    }

    /**
     * Die „?“-Hilfe des Bereichs. Sie steht für jeden Bereich an derselben Stelle: unten rechts in der Ecke der
     * Werkzeugleiste (Angelo 27.11.2026), nicht mehr im Kopf. {@code null}, wenn der Bereich keine hat; wechselt die
     * Hilfe mit der Ansicht, liefert der Bereich eine Zeile, in der nur die passende sichtbar ist.
     */
    default JComponent help() {
        return null;
    }

    /**
     * Umschalter des Bereichs mittig in der Werkzeugleiste unten; die Lupe links klappt über sie auf. {@code null},
     * wenn der Bereich keine hat. Die Seitenreiter der Leseansicht stehen seit dem 13.11.2026 nicht mehr hier,
     * sondern in der Icon-Leiste oben ({@code ContentView.tabBar()}).
     */
    default JComponent tools() {
        return null;
    }

    /**
     * Client-Eigenschaft für {@link #tools()}: {@code Boolean.TRUE} lässt die Werkzeuge die ganze Zeile rechts der Lupe
     * füllen statt mittig zu stehen, etwa die Datei-Aktionen der Vorschau direkt neben der Lupe und „Typ · Größe“
     * rechtsbündig (Angelo, 19.11.2026). Die Eigenschaft darf sich ändern; die Leiste liest sie bei jedem Layout.
     */
    String TOOLS_FILL = "spaceHarvester.tools.fill";
}
