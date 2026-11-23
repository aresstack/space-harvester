package com.aresstack.spaceharvester.app.ui.perspective;

import java.util.Objects;
import javax.swing.JComponent;

/**
 * Ein Arbeitsplatz an Bord desselben Raumschiffs (#1141): Space Harvester, Command Deck, Proving Ground, später Mission Control. Eine
 * Perspektive bringt ihre eigene Navigation (oben neben dem Titel), ihre Arbeitsfläche (Mitte) und, wenn sie eine hat,
 * ihre linke Leiste mit. Logo, Titelleiste, rechte Chat-/Auftrags-Seitenleiste und Statuszeile gehören zum gemeinsamen
 * Rahmen und bleiben beim Wechsel stehen; das Hauptfenster kennt keine Sonderfälle einzelner Perspektiven.
 *
 * @param id         stabiler Schlüssel (Karten des Fensters, Tests)
 * @param title      Name im Umschalter und im Titel
 * @param hint       eine Zeile unter dem Namen im Menü
 * @param ready      {@code false}: nur ein Platzhalter; der Umschalter zeigt „in Vorbereitung“
 * @param navigation Knöpfe der Perspektive neben dem Titel, {@code null} für keine
 * @param workspace  Arbeitsfläche in der Mitte
 * @param drawer     linke Leiste, {@code null} für keine (die Mitte nimmt dann die ganze Breite)
 * @param tools      Bedienelemente oben rechts in der Titelleiste vor Chat und Aufträgen (etwa „Datasets | JES-Jobs“
 *                   im FTP-Bereich des Command Deck, #1142), {@code null} für keine
 * @param onShown    läuft nach dem Einblenden
 * @param onHidden   läuft nach dem Ausblenden
 * @param status     Stand der Perspektive links in der Statuszeile der App (etwa der Zustand der Terminal-Sitzung samt
 *                   Fehler), {@code null} für keinen
 */
public record Perspective(String id, String title, String hint, boolean ready, JComponent navigation,
                          JComponent workspace, JComponent drawer, JComponent tools, Runnable onShown,
                          Runnable onHidden, JComponent status) {

    /** Ohne eigenen Stand in der Statuszeile. */
    public Perspective(String id, String title, String hint, boolean ready, JComponent navigation,
                       JComponent workspace, JComponent drawer, JComponent tools, Runnable onShown,
                       Runnable onHidden) {
        this(id, title, hint, ready, navigation, workspace, drawer, tools, onShown, onHidden, null);
    }

    public Perspective {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(workspace, "workspace");
        hint = hint == null ? "" : hint;
        onShown = onShown == null ? () -> { } : onShown;
        onHidden = onHidden == null ? () -> { } : onHidden;
    }

    /** Eine Perspektive ohne eigene Rückrufe. */
    public static Perspective of(String id, String title, String hint, boolean ready, JComponent navigation,
                                 JComponent workspace, JComponent drawer) {
        return new Perspective(id, title, hint, ready, navigation, workspace, drawer, null, null, null);
    }

    /** Eine Perspektive ohne eigene Rückrufe, mit Bedienelementen oben rechts. */
    public static Perspective of(String id, String title, String hint, boolean ready, JComponent navigation,
                                 JComponent workspace, JComponent drawer, JComponent tools) {
        return new Perspective(id, title, hint, ready, navigation, workspace, drawer, tools, null, null);
    }
}
