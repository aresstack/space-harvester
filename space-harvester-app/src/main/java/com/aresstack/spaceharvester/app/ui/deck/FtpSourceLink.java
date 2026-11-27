package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import java.awt.Component;
import java.util.function.Consumer;

/**
 * „Als Wissensquelle hinzufügen“ im FTP-Bereich (Angelo, 27.11.2026): das gezeigte Verzeichnis (HLQ, Dataset, PDS oder
 * Unix-Ordner) ohne Formular als Wissensquelle vom Typ {@code ftp} eintragen – mit demselben Server und demselben
 * KeePass-Eintrag wie die Sitzung. Besteht für Server und Konto schon eine Quelle, kommt das Verzeichnis dort als
 * weiterer Startpunkt hinzu. Synchronisiert wird nichts; das bleibt ein Klick im Space Harvester.
 */
public interface FtpSourceLink {

    /** Ohne Wissensquellen (Tests, Deck ohne App). */
    FtpSourceLink NONE = (parent, session, location, status) -> status.accept(
            new StatusNote("Wissensquellen sind gerade nicht verfügbar.", StatusNote.Tone.WARNING, null, null));

    /**
     * @param location der Ort, wie der FTP-Bereich ihn zeigt (MVS mit oder ohne Hochkommas)
     * @param status   Rückmeldung für die Statuszeile der App
     */
    void add(Component parent, FtpSession session, String location, Consumer<StatusNote> status);
}
