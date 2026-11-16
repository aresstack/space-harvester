package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.Map;

/**
 * Ein Update einer Quelle (ADR 0030, Abschnitt 3): alles nach der vorigen bis einschließlich dieser Marke. Das offene
 * Update nach der letzten Marke ist einsehbar, aber noch kein Update.
 *
 * @param id         Kennung für {@link ChangeLog#report}: Zeitpunkt der Marke in Nanosekunden, {@link #OPEN} für das
 *                   offene
 * @param from       Zeitpunkt der vorigen Marke (ausschließlich), {@code null} vor der ersten
 * @param to         Zeitpunkt der Marke (einschließlich), {@code null} beim offenen
 * @param mark       die Marke, {@code null} beim offenen
 * @param initial    Erstbefüllung: nur Zähler, keine Seitenliste
 * @param kinds      Seiten je Art, nach Zusammenfassung mehrerer Änderungen derselben Seite
 * @param spaces     geänderte Seiten je Space
 * @param pages      geänderte Seiten insgesamt
 * @param loaded     Inhalte erstmals lokal ({@link ChangeKind#LOADED}), nur als Zahl
 * @param reloaded   Inhalte schon bekannter Versionen jetzt lokal ({@link ChangeKind#RELOADED}), nur als Zahl
 * @param summarized ob eine Zusammenfassung vorliegt (ADR 0030, Abschnitt 5)
 */
public record ChangeUpdate(String id, String sourceId, Instant from, Instant to, UpdateMark mark, boolean initial,
                           Map<ChangeKind, Integer> kinds, Map<String, Integer> spaces, int pages, int loaded,
                           int reloaded, boolean summarized) {

    /** Kennung des offenen Updates nach der letzten Marke. */
    public static final String OPEN = "open";

    public ChangeUpdate {
        kinds = Map.copyOf(kinds);
        spaces = Map.copyOf(spaces);
    }

    /** Ob das Update noch offen ist (nach der letzten Marke). */
    public boolean open() {
        return mark == null;
    }
}
