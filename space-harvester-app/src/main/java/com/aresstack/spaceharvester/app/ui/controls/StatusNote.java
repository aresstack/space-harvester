package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Glyph;

import java.util.List;
import java.util.Objects;

/**
 * Eine Meldung für die Statuszeile ({@link StatusLabel}): Text, Ampelton und höchstens ein Wort darin als Link, etwa
 * „Aufträge“, das die Auftragsverwaltung öffnet (UX, Fahrplan D3). Teile des Textes können einen eigenen Ton haben
 * ({@link Mark}, etwa ein roter Hinweis „ohne Zugriff“ neben gelben), und hinter einem Teil kann ein gezeichnetes
 * Symbol als Link stehen ({@link IconLink}, etwa der Kreispfeil „wiederholen“ hinter einer gescheiterten Stufe).
 *
 * @param text   der Text; leer zeigt nichts
 * @param tone   grau, gelb (bewusst ausgelassen) oder rot (nur Fehler und fehlender Zugriff); der Ton der Zeile
 * @param link   ein Wort im Text, das als Link erscheint; {@code null} ohne Link
 * @param action was der Link tut; {@code null} ohne Link
 * @param marks  Teile des Textes in eigenem Ton; nur die, die im Text vorkommen
 * @param icon   ein Symbol als Link hinter einem Teil des Textes; {@code null} ohne
 */
public record StatusNote(String text, Tone tone, String link, Runnable action, List<Mark> marks, IconLink icon) {

    /** Ampel der Statuszeile: Grün gibt es dort nicht, ein normaler Stand ist grau. */
    public enum Tone { PLAIN, WARNING, ERROR }

    /**
     * Ein Teil des Textes in eigenem Ton (das erste Vorkommen).
     *
     * @param part der Teil, wörtlich
     * @param tone sein Ton
     */
    public record Mark(String part, Tone tone) {

        public Mark {
            Objects.requireNonNull(part, "part");
            tone = Objects.requireNonNullElse(tone, Tone.PLAIN);
        }
    }

    /**
     * Ein gezeichnetes Symbol als Link, direkt hinter dem ersten Vorkommen von {@code after} im Text.
     *
     * @param glyph   das Symbol, etwa {@link Glyph#REFRESH} (Kreispfeil)
     * @param after   der Teil des Textes, hinter dem es steht
     * @param tooltip was es tut, auch für Bildschirmleser
     * @param action  was ein Klick tut
     */
    public record IconLink(Glyph glyph, String after, String tooltip, Runnable action) {

        public IconLink {
            Objects.requireNonNull(glyph, "glyph");
            Objects.requireNonNull(after, "after");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            Objects.requireNonNull(action, "action");
        }
    }

    public StatusNote {
        text = text == null ? " " : text;
        tone = Objects.requireNonNullElse(tone, Tone.PLAIN);
        if (link == null || action == null || link.isBlank() || !text.contains(link)) {
            link = null;
            action = null;
        }
        String shown = text;
        marks = marks == null ? List.of()
                : marks.stream().filter(mark -> !mark.part().isEmpty() && shown.contains(mark.part())).toList();
        if (icon != null && (icon.after().isEmpty() || !text.contains(icon.after()))) {
            icon = null;
        }
    }

    /** Wie bisher: Text, Ton und höchstens ein Wort als Link, ohne eigene Töne und ohne Symbol. */
    public StatusNote(String text, Tone tone, String link, Runnable action) {
        this(text, tone, link, action, List.of(), null);
    }

    /** Ein grauer Text ohne Link, wie bisher. */
    public static StatusNote plain(String text) {
        return new StatusNote(text, Tone.PLAIN, null, null);
    }

    /**
     * Derselbe Ton, Link, dieselben Töne der Teile und dasselbe Symbol mit anderem Text; jedes bleibt nur, wenn sein
     * Teil noch vorkommt.
     */
    public StatusNote withText(String other) {
        return new StatusNote(other, tone, link, action, marks, icon);
    }

    /**
     * Dieselbe Meldung mit {@code suffix} dahinter (etwa „ · 5 Knoten, 4 Kanten“), der grau bleibt, auch wenn die Zeile
     * gelb oder rot ist: Nur die Hinweise tragen den Ton, nicht der Stand dahinter.
     */
    public StatusNote plus(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return this;
        }
        String part = suffix.startsWith(" · ") ? suffix.substring(3) : suffix;
        List<Mark> more = marks;
        if (tone != Tone.PLAIN && !part.isBlank() && !text.contains(part)) {
            more = new java.util.ArrayList<>(marks);
            more.add(new Mark(part, Tone.PLAIN));
        }
        return new StatusNote(text + suffix, tone, link, action, more, icon);
    }

    /** Dieselbe Meldung mit diesen Tönen der Teile. */
    public StatusNote withMarks(List<Mark> other) {
        return new StatusNote(text, tone, link, action, other, icon);
    }

    /** Dieselbe Meldung mit diesem Symbol; {@code null} ohne. */
    public StatusNote withIcon(IconLink other) {
        return new StatusNote(text, tone, link, action, marks, other);
    }

    public boolean hasLink() {
        return link != null;
    }

    public boolean hasIcon() {
        return icon != null;
    }

    /** Der Ton eines Teils: sein eigener, sonst der der Zeile. */
    public Tone toneOf(String part) {
        return marks.stream().filter(mark -> mark.part().equals(part)).map(Mark::tone).findFirst().orElse(tone);
    }
}
