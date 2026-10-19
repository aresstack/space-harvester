package com.aresstack.spaceharvester.application.acquisition;

import java.time.Instant;
import java.util.List;

/**
 * Stand der Beschaffung für den Bereich „Status“ (ADR 0016): Planer und Hintergrundfüllung, nur Zähler, keine
 * Seiten-IDs. Je Quelle erscheinen nur Quellen, die die Hintergrundfüllung lädt (aktiv, technisch freigegeben,
 * Füllung nicht abgeschaltet).
 *
 * @param enabled           ob die Hintergrundfüllung eingeschaltet ist
 * @param pause             warum der Hintergrund gerade keine neue Seite beginnt, {@link Pause#NONE}, wenn er lädt
 *                          oder darf
 * @param queued            Einträge der dauerhaften Warteschlange
 * @param inFlight          davon an den Planer übergeben (wartend oder ladend)
 * @param foregroundQueued  Seiten der Suche, die warten
 * @param foregroundRunning Seiten der Suche, die laden
 * @param backgroundRunning Seiten des Hintergrunds, die laden
 * @param loaded            seit dem Start im Hintergrund geladen
 * @param failed            seit dem Start im Hintergrund gescheitert
 * @param promoted          wie oft eine Seite des Hintergrunds für die Suche hochgestuft wurde
 * @param parallelism       so viele Seiten lädt der Hintergrund höchstens zugleich
 * @param lastScan          letzte Suche im Katalog nach Lücken oder {@code null}
 * @param sources           je Quelle, nach Quellen-ID
 */
public record AcquisitionStatus(boolean enabled, Pause pause, int queued, int inFlight, int foregroundQueued,
                                int foregroundRunning, int backgroundRunning, long loaded, long failed, long promoted,
                                int parallelism, Instant lastScan, List<SourceCounts> sources) {

    public AcquisitionStatus {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Warum der Hintergrund ruht. */
    public enum Pause {
        /** Er lädt oder darf laden. */
        NONE,
        /** Die Suche lädt oder wartet; der Hintergrund beginnt keine neue Seite. */
        FOREGROUND,
        /** Eine Aktion des Benutzers hält ihn an ({@link AcquisitionScheduler#pauseBackground()}). */
        USER_ACTION,
        /** Die Wartezeit nach der letzten Aktion des Vordergrunds läuft noch. */
        COOLDOWN,
        /** Die Hintergrundfüllung ist abgeschaltet. */
        DISABLED,
        /** Nichts zu tun: die Warteschlange ist leer, die nächste Suche im Katalog kommt später. */
        IDLE,
        /** Beendet. */
        CLOSED
    }

    /** Zähler einer Quelle. */
    public record SourceCounts(String sourceId, int queued, long loaded, long failed) {
    }
}
