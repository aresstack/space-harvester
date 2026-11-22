package com.aresstack.spaceharvester.deck.files;

import java.util.Optional;

/** Prüft neue Datei- und Ordnernamen, bevor die Quelle gefragt wird. */
public final class FileNames {

    private static final String FORBIDDEN = "/\\:*?\"<>|";

    private FileNames() {
    }

    /** Der Grund, warum {@code name} kein gültiger neuer Name ist; leer, wenn er passt. */
    public static Optional<String> problem(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            return Optional.of("Bitte einen Namen eingeben.");
        }
        if (trimmed.equals(".") || trimmed.equals("..")) {
            return Optional.of("„" + trimmed + "“ ist kein gültiger Name.");
        }
        for (char c : trimmed.toCharArray()) {
            if (FORBIDDEN.indexOf(c) >= 0 || c < 32) {
                return Optional.of("Der Name darf keines dieser Zeichen enthalten: / \\ : * ? \" < > |");
            }
        }
        if (trimmed.endsWith(".")) {
            return Optional.of("Der Name darf nicht mit einem Punkt enden.");
        }
        return Optional.empty();
    }
}
