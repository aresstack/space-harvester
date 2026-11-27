package com.aresstack.spaceharvester.app.project;

import java.util.Objects;

/**
 * Ein Projekt (ADR 0043): vorgezogen nur mit Kennung, Name und Farbe (Nachtrag F1, F4). Chats verweisen über ihre
 * Projekt-ID darauf ({@code ChatRecord#projectId}); das Projekt selbst kennt seine Chats nicht. Das Standardprojekt
 * heißt immer „AresStack“ (Angelo, 27.11.2026: nicht umzubenennen, nicht zu löschen), hat eine feste Kennung und keine
 * Farbe; jeder Chat ohne gültige Zuordnung gehört dazu (F2).
 *
 * @param id    feste Kennung (UUID, beim Standardprojekt {@link #DEFAULT_ID}), zugleich Name des Projektordners
 * @param name  Anzeigename, frei wählbar, darf mehrfach vorkommen; beim Standardprojekt immer {@link #DEFAULT_NAME}
 * @param color die Farbe aus der festen Auswahl; {@code null} nur beim Standardprojekt
 */
public record Project(String id, String name, ProjectColor color) {

    /** Die feste Kennung des Standardprojekts. */
    public static final String DEFAULT_ID = "standard";
    /** Der feste Name des Standardprojekts. */
    public static final String DEFAULT_NAME = "AresStack";

    public Project {
        Objects.requireNonNull(id, "id");
        boolean standard = DEFAULT_ID.equals(id);
        name = standard ? DEFAULT_NAME : name == null || name.isBlank() ? "Projekt" : name.trim();
        color = standard ? null : color == null ? ProjectColor.GRAU : color;
    }

    /** Das Standardprojekt. */
    public static Project standard() {
        return new Project(DEFAULT_ID, DEFAULT_NAME, null);
    }

    /** Ob dies das Standardprojekt „AresStack“ ist. */
    public boolean isDefault() {
        return DEFAULT_ID.equals(id);
    }

    public Project withName(String newName) {
        return new Project(id, newName, color);
    }

    public Project withColor(ProjectColor newColor) {
        return new Project(id, name, newColor);
    }
}
