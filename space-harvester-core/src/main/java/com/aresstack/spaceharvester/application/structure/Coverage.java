package com.aresstack.spaceharvester.application.structure;

/**
 * Wie vollständig eine Kantenart aus dem lokalen Bestand beantwortet ist. Bezieht sich nur auf Seiten, die der
 * Aufrufer sehen darf; eine gesperrte Seite ändert die Angabe nie.
 *
 * <p>Bedeutung je Art:
 * <ul>
 *   <li>{@link ConnectionKind#PARENT}: {@link #COMPLETE}, wenn die Ausgangsseite keine Elternseite nennt oder die
 *       genannte lokal katalogisiert und sichtbar ist; sonst {@link #PARTIAL} (nicht geerntet, gelöscht oder nicht
 *       sichtbar, bewusst ununterscheidbar).</li>
 *   <li>{@link ConnectionKind#CHILD}: immer {@link #COMPLETE} gegenüber dem <em>lokalen Katalog</em>: alle lokal
 *       katalogisierten, sichtbaren Unterseiten. Ob die Quelle Seiten hat, die nie katalogisiert wurden, weiß der Core
 *       nicht; dafür gibt es keine Angabe je Quelle, und G1 erfindet keine.</li>
 *   <li>{@link ConnectionKind#LINKS_TO}/{@link ConnectionKind#LINKED_FROM}: {@link #UNAVAILABLE} ohne Verzeichnis
 *       der Verweise oder wenn es die Seite nicht kennt; {@link #PARTIAL}, wenn es sich selbst als unvollständig
 *       meldet oder ein Verweisziel nicht lokal gespeichert bzw. nicht sichtbar ist.</li>
 *   <li>{@link ConnectionKind#TERM_RELATION}: {@link #UNAVAILABLE} ohne Begriffsindex oder wenn für die Seite keine
 *       aktuellen Begriffe im Index liegen (nicht analysiert oder ohne Begriffe); sonst {@link #COMPLETE} gegenüber
 *       dem lokalen Inhalt. Begriffe werden nur aus lokal gespeichertem Text gewonnen.</li>
 * </ul>
 */
public enum Coverage {
    /** Vollständig im oben beschriebenen Sinn. */
    COMPLETE,
    /** Etwas Bekanntes fehlt lokal. */
    PARTIAL,
    /** Die Grundlage fehlt; die Art liefert nichts. */
    UNAVAILABLE
}
