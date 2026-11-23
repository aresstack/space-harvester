package com.aresstack.spaceharvester.deck.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Zeichnet auf, was der Mensch in einer Sitzung eingibt, als abspielbares Makro (TerminalMacroRecorder aus
 * MainframeMate): getippter Text wird zu einem Schritt zusammengefasst, jede AID-Taste (ENTER, PF, PA, CLEAR, SysReq)
 * schließt ihn ab. Die Aufzeichnung läuft immer; „Leeren“ fängt neu an. Eingaben in verdeckte Felder (Passwörter)
 * zeichnet sie nie auf – die Anzeige meldet sie gar nicht erst.
 */
public final class TerminalMacro {

    /** Ein Schritt: Text oder eine Taste. */
    public record Step(Kind kind, String value) {

        /** Text, eine AID-Taste oder Tab (Schreibmarke ins nächste Eingabefeld). */
        public enum Kind { TEXT, AID, TAB }

        public Step {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }

        public static Step text(String text) {
            return new Step(Kind.TEXT, text);
        }

        public static Step aid(AidKey key) {
            return new Step(Kind.AID, key.name());
        }

        public static Step tab() {
            return new Step(Kind.TAB, "TAB");
        }

        /** Die Taste eines AID-Schritts; leer bei Text oder unbekanntem Namen. */
        public java.util.Optional<AidKey> key() {
            if (kind != Kind.AID) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.of(AidKey.valueOf(value));
            } catch (IllegalArgumentException unknown) {
                return java.util.Optional.empty();
            }
        }

        /** Lesbar wie formatAidName in MainframeMate: „⏎ Enter“, „F3“, Text in Anführungszeichen. */
        public String display() {
            if (kind == Kind.TEXT) {
                return "\"" + value + "\"";
            }
            if (kind == Kind.TAB) {
                return "⇥ Tab";
            }
            return key().map(aid -> switch (aid) {
                case ENTER -> "⏎ Enter";
                case CLEAR -> "⌧ Clear";
                case SYSREQ -> "⚡ SysReq";
                case PA1, PA2, PA3 -> aid.name();
                default -> "F" + aid.pfNumber();
            }).orElse(value);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Step> steps = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    /** Getippter oder eingefügter Text. */
    public void typed(String typed) {
        if (typed == null || typed.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            text.append(typed);
        } finally {
            lock.unlock();
        }
    }

    /** Eine AID-Taste; schließt den Text davor ab. */
    public void aid(AidKey key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            flush();
            steps.add(Step.aid(key));
        } finally {
            lock.unlock();
        }
    }

    /** Tab: ins nächste Eingabefeld; schließt den Text davor ab, damit mehrere Felder richtig abgespielt werden. */
    public void tab() {
        lock.lock();
        try {
            flush();
            steps.add(Step.tab());
        } finally {
            lock.unlock();
        }
    }

    /** Vergisst alles; die Aufzeichnung läuft weiter. */
    public void clear() {
        lock.lock();
        try {
            steps.clear();
            text.setLength(0);
        } finally {
            lock.unlock();
        }
    }

    /** Alle Schritte bis jetzt, offener Text als letzter Schritt. */
    public List<Step> steps() {
        lock.lock();
        try {
            flush();
            return List.copyOf(steps);
        } finally {
            lock.unlock();
        }
    }

    private void flush() {
        if (!text.isEmpty()) {
            steps.add(Step.text(text.toString()));
            text.setLength(0);
        }
    }
}
