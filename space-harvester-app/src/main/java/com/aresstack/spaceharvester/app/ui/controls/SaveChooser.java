package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.settings.AppSettings;

import javax.swing.JFileChooser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Der Dialog „Speichern unter“ der App (Angelo 20.11.2026): er öffnet überall im eingestellten Ausgabeordner
 * ({@code space-harvester-app.output-directory}, Vorgabe der Downloads-Ordner) statt in „Dokumente“. Welcher Ordner
 * eingestellt ist, meldet die {@code AppContext} über {@link #directory(Supplier)}.
 */
public final class SaveChooser {

    private static volatile Supplier<Path> directory = AppSettings::defaultOutputDirectory;

    private SaveChooser() {
    }

    /** Woher der Ausgabeordner kommt; die App setzt das einmal beim Start. */
    public static void directory(Supplier<Path> source) {
        directory = Objects.requireNonNull(source, "source");
    }

    /** Der Ausgabeordner, in dem Speichern-Dialoge öffnen. */
    public static Path directory() {
        Path current = null;
        try {
            current = directory.get();
        } catch (RuntimeException e) {
            // dann die Vorgabe
        }
        return current == null ? AppSettings.defaultOutputDirectory() : current;
    }

    /**
     * Ein Speichern-Dialog im Ausgabeordner mit vorgeschlagenem Dateinamen.
     *
     * @param fileName vorgeschlagener Name ohne Ordner; {@code null} schlägt keinen vor
     */
    public static JFileChooser create(String fileName) {
        Path folder = directory();
        JFileChooser chooser = new JFileChooser(Files.isDirectory(folder) ? folder.toFile() : null);
        if (fileName != null) {
            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), fileName));
        }
        return chooser;
    }
}
