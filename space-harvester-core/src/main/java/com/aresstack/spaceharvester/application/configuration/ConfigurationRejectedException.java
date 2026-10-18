package com.aresstack.spaceharvester.application.configuration;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Eine Konfiguration ist ungültig oder ein Beobachter konnte sie nicht vorbereiten (etwa weil der KI-Dienst das
 * Modell nicht kennt). Die bisherige Konfiguration bleibt aktiv.
 */
public class ConfigurationRejectedException extends RuntimeException {

    private final List<ConfigurationProblem> problems;

    public ConfigurationRejectedException(List<ConfigurationProblem> problems) {
        super(message(problems));
        this.problems = List.copyOf(problems);
    }

    public ConfigurationRejectedException(String key, String message) {
        this(List.of(new ConfigurationProblem(key, message)));
    }

    public List<ConfigurationProblem> problems() {
        return problems;
    }

    private static String message(List<ConfigurationProblem> problems) {
        return problems.stream().map(p -> p.key() + ": " + p.message()).collect(Collectors.joining("; "));
    }
}
