package com.aresstack.spaceharvester.source.network;

import java.util.List;

/** Die Proxy-Einstellungen sind fehlerhaft; die Nachricht nennt jedes Problem. */
public class InvalidNetworkConfigurationException extends IllegalStateException {

    private final List<String> problems;

    public InvalidNetworkConfigurationException(List<String> problems) {
        super("Ungültige Netzwerkkonfiguration (space-harvester.network.proxy):\n  - "
                + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
