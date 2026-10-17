package com.aresstack.spaceharvester.source;

import java.util.List;

/** Die konfigurierten Quellen sind fehlerhaft; die Nachricht nennt jedes Problem mit Quelle und Feld. */
public class InvalidSourceConfigurationException extends IllegalStateException {

    private final List<String> problems;

    public InvalidSourceConfigurationException(List<String> problems) {
        super("Ungültige Quellenkonfiguration (space-harvester.sources):\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
