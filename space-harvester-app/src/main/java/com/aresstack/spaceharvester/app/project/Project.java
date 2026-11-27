package com.aresstack.spaceharvester.app.project;

import java.util.Objects;

/**
 * Ein Projekt (ADR 0043): vorgezogen nur mit Kennung, Name und Ziel (Nachtrag F1, F4, F6). Chats verweisen über ihre
 * Projekt-ID darauf ({@code ChatRecord#projectId}); das Projekt selbst kennt seine Chats nicht. Ein Projekt hat keine
 * Farbe: Farbe bedeutet überall die Gruppe ({@link ChatGroup}), das Projekt erkennt man am Namen in der Ecke.
 *
 * <p>Das Standardprojekt heißt immer „AresStack“ (Angelo, 27.11.2026: nicht umzubenennen, nicht zu löschen), hat eine
 * feste Kennung und ein festes Ziel in der Oberfläche; jeder Chat ohne gültige Zuordnung gehört dazu (F2).
 *
 * @param id     feste Kennung (UUID, beim Standardprojekt {@link #DEFAULT_ID}), zugleich Name des Projektordners
 * @param name   Anzeigename, frei wählbar, darf mehrfach vorkommen; beim Standardprojekt immer {@link #DEFAULT_NAME}
 * @param target wohin ein Klick auf den Projektnamen unten in der Ecke führt (F6); {@code null}, solange keines gesetzt
 *               ist; beim Standardprojekt immer {@code null}
 * @param output wohin Exporte und Downloads des Projekts gehen ({@link ProjectOutput}); nie {@code null}, Vorgabe
 *               {@link ProjectOutput#standard()}; beim Standardprojekt ohne Wirkung, dort gilt immer der Ausgabeordner
 *               der Einstellungen
 */
public record Project(String id, String name, ProjectTarget target, ProjectOutput output) {

    /** Die feste Kennung des Standardprojekts. */
    public static final String DEFAULT_ID = "standard";
    /** Der feste Name des Standardprojekts. */
    public static final String DEFAULT_NAME = "AresStack";

    public Project {
        Objects.requireNonNull(id, "id");
        boolean standard = DEFAULT_ID.equals(id);
        name = standard ? DEFAULT_NAME : name == null || name.isBlank() ? "Projekt" : name.strip();
        target = standard ? null : target;
        output = output == null ? ProjectOutput.standard() : output;
    }

    /** Ein Projekt mit der Vorgabe für Exporte und Downloads. */
    public Project(String id, String name, ProjectTarget target) {
        this(id, name, target, null);
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
        return new Project(id, newName, target, output);
    }

    public Project withTarget(ProjectTarget newTarget) {
        return new Project(id, name, newTarget, output);
    }

    public Project withOutput(ProjectOutput newOutput) {
        return new Project(id, name, target, newOutput);
    }
}
