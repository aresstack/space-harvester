package com.aresstack.spaceharvester.app.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Die gemeinsamen Regeln für Dateien des Bedienprofils im Datenordner der App (ADR 0034, ADR 0043 Abschnitt 3): atomar
 * schreiben (erst eine Teildatei, dann umbenannt) und eine unlesbare Datei in Quarantäne legen statt sie je zu
 * überschreiben. Favoriten und Projekte benutzen dieselben Regeln.
 */
public final class ProfileFiles {

    private ProfileFiles() {
    }

    /** Schreibt {@code text} atomar nach {@code path}; legt fehlende Ordner an. */
    public static void writeAtomic(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".part");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * Benennt eine unlesbare Datei um ({@code <Name>.defekt-<Zeit>}), damit nichts sie überschreibt; liefert das Ziel
     * oder {@code null}, wenn auch das nicht ging.
     */
    public static Path quarantine(Path path) {
        Path target = path.resolveSibling(path.getFileName() + ".defekt-" + System.currentTimeMillis());
        try {
            Files.move(path, target);
            return target;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
