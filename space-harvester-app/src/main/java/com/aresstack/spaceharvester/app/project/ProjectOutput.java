package com.aresstack.spaceharvester.app.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Wohin ein Projekt Exporte und Downloads speichert (Angelo, 27.11.2026): Mit dem Haken „Alles hier speichern“ landen
 * sie im Projektordner (dem Ziel des Projekts, wenn es ein lokaler Ordner ist), im Unterordner, Vorgabe
 * {@value #DEFAULT_SUBFOLDER}. Ein leerer Unterordner heißt: direkt im Projektordner. Ohne Haken, ohne Ordner als Ziel
 * und beim Standardprojekt „AresStack“ gilt der Ausgabeordner der Einstellungen (Einstellungen › Allgemein).
 *
 * @param saveHere  ob Exporte und Downloads im Projektordner landen
 * @param subfolder der Unterordner im Projektordner, relativ, nie {@code null}; leer = direkt im Projektordner
 */
public record ProjectOutput(boolean saveHere, String subfolder) {

    /** Der vorgeschlagene Unterordner im Projektordner. */
    public static final String DEFAULT_SUBFOLDER = "Downloads";

    private static final System.Logger LOG = System.getLogger(ProjectOutput.class.getName());

    public ProjectOutput {
        subfolder = clean(subfolder);
    }

    /** Die Vorgabe für neue und ältere Projekte: Haken gesetzt, Unterordner {@value #DEFAULT_SUBFOLDER}. */
    public static ProjectOutput standard() {
        return new ProjectOutput(true, DEFAULT_SUBFOLDER);
    }

    /**
     * Ob die Eingabe als Unterordner taugt: leer oder ein relativer Pfad, der im Projektordner bleibt (kein
     * Laufwerk, kein „..“).
     */
    public static boolean valid(String input) {
        String value = clean(input);
        if (value.isEmpty()) {
            return true;
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute() || path.getRoot() != null || value.contains(":")) {
                return false;
            }
            for (Path part : path) {
                if ("..".equals(part.toString())) {
                    return false;
                }
            }
            return true;
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * Der Ordner für Exporte und Downloads des Projekts, ohne ihn anzulegen; {@code null}, wenn der Ausgabeordner der
     * Einstellungen gilt (Standardprojekt, kein Haken, kein Ziel, Website oder Datei als Ziel, ungültiger Pfad).
     */
    public static Path directory(Project project) {
        if (project == null || project.isDefault() || !project.output().saveHere()) {
            return null;
        }
        ProjectTarget target = project.target();
        if (target == null || target.isWebsite() || !valid(project.output().subfolder())) {
            return null;
        }
        try {
            Path base = Path.of(target.value());
            if (Files.isRegularFile(base)) {
                return null;
            }
            String sub = project.output().subfolder();
            return sub.isEmpty() ? base : base.resolve(sub).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Wie {@link #directory(Project)}, legt den Ordner aber still an, wenn er fehlt (Angelo: ohne Fehlermeldung).
     * Lässt er sich nicht anlegen, gilt der Ausgabeordner der Einstellungen ({@code null}); der Grund steht im Log,
     * der Pfad nicht.
     */
    public static Path prepare(Project project) {
        Path directory = directory(project);
        if (directory == null) {
            return null;
        }
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Projektordner nicht anzulegen, es gilt der Ausgabeordner: "
                    + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Ohne Leerraum und Trenner am Anfang und Ende; {@code null} wird leer. */
    private static String clean(String input) {
        if (input == null) {
            return "";
        }
        String value = input.strip();
        int start = 0;
        int end = value.length();
        while (start < end && isSeparator(value.charAt(start))) {
            start++;
        }
        while (end > start && isSeparator(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end).strip();
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }
}
