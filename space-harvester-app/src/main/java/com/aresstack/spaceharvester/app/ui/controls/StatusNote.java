package com.aresstack.spaceharvester.app.ui.controls;

import java.util.Objects;

/**
 * Eine Meldung für die Statuszeile ({@link StatusLabel}): Text, Ampelton und höchstens ein Wort darin als Link, etwa
 * „Aufträge“, das die Auftragsverwaltung öffnet (UX, Fahrplan D3).
 *
 * @param text   der Text; leer zeigt nichts
 * @param tone   grau, gelb (bewusst ausgelassen) oder rot (nur Fehler)
 * @param link   ein Wort im Text, das als Link erscheint; {@code null} ohne Link
 * @param action was der Link tut; {@code null} ohne Link
 */
public record StatusNote(String text, Tone tone, String link, Runnable action) {

    /** Ampel der Statuszeile: Grün gibt es dort nicht, ein normaler Stand ist grau. */
    public enum Tone { PLAIN, WARNING, ERROR }

    public StatusNote {
        text = text == null ? " " : text;
        tone = Objects.requireNonNullElse(tone, Tone.PLAIN);
        if (link == null || action == null || link.isBlank() || !text.contains(link)) {
            link = null;
            action = null;
        }
    }

    /** Ein grauer Text ohne Link, wie bisher. */
    public static StatusNote plain(String text) {
        return new StatusNote(text, Tone.PLAIN, null, null);
    }

    /** Derselbe Ton und Link mit anderem Text; der Link bleibt nur, wenn sein Wort noch vorkommt. */
    public StatusNote withText(String other) {
        return new StatusNote(other, tone, link, action);
    }

    public boolean hasLink() {
        return link != null;
    }
}
