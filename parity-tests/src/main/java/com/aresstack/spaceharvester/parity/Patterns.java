package com.aresstack.spaceharvester.parity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Textmuster, die in allen Formaten gleich gezählt werden. */
final class Patterns {

    /** Hinweise der Exporter auf Bilder, die nicht eingebettet wurden (Core: {@code [Bild: …]}, EAC: „Abbildung …“). */
    static final Pattern IMAGE_NOTE = Pattern.compile(
            "\\[Bild:|Abbildung nicht verfügbar|Abbildung fehlt|Bild nicht verfügbar|\\(attachment:");

    /** Anfang eines Mermaid-Quelltexts. */
    static final Pattern MERMAID = Pattern.compile(
            "(?m)^\\s*(graph|flowchart)\\s+(TD|TB|LR|RL|BT)\\b|(?m)^\\s*(sequenceDiagram|classDiagram|stateDiagram)");

    /** Zeilen, die ein Exporter selbst schreibt (Bild- und Exporthinweise); zählen nicht zum Inhalt. */
    static final Pattern CHROME_LINE = Pattern.compile(
            "(?m)^.*(\\[Bild:|\\[Export:|Abbildung nicht verfügbar|Abbildung fehlt|Bild nicht verfügbar).*$");

    private Patterns() {
    }

    static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
