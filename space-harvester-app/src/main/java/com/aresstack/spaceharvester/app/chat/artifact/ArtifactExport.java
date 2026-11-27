package com.aresstack.spaceharvester.app.chat.artifact;

import com.aresstack.spaceharvester.app.chat.ChatExport;
import com.aresstack.spaceharvester.document.ExportDocumentNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Export eines Artefakts (ADR 0045, Abschnitt 8): Dokumente nach Markdown, Word und PDF über die vorhandenen Schreiber
 * ({@link ChatExport#write}), Grafiken als SVG. Die Datei liegt im Ausgabeordner und heißt wie der Titel; gibt es den
 * Namen schon, bekommt die neue Datei eine Nummer, nichts wird überschrieben.
 */
public final class ArtifactExport {

    /** Die Endung einer Grafik. */
    public static final String SVG = "svg";

    private ArtifactExport() {
    }

    /** Das Dokument für die Schreiber: Titel und Markdown; eine Überschrift erster Ebene gleich dem Titel fällt weg. */
    public static ExportDocumentNode document(ChatArtifact artifact) {
        SectionDocument document = artifact.document();
        List<SectionDocument.Section> sections = document.sections();
        StringBuilder markdown = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            SectionDocument.Section section = sections.get(i);
            if (i == 0 && section.level() == 1 && section.heading().equalsIgnoreCase(artifact.title())) {
                markdown.append(section.text()).append("\n\n");
            } else {
                markdown.append(section.markdown());
            }
        }
        return new ExportDocumentNode(artifact.title(), markdown.toString().strip() + "\n", List.of());
    }

    /** Schreibt ein Dokument im gewählten Format in den Ordner; liefert die Datei. */
    public static Path write(ChatArtifact artifact, ChatExport.Format format, Path directory) throws IOException {
        if (artifact.kind() != ChatArtifact.Kind.DOCUMENT) {
            throw new IllegalArgumentException("Nur Dokumente haben diese Formate");
        }
        Files.createDirectories(directory);
        Path target = free(directory, filename(artifact.title()), format.extension);
        if (format == ChatExport.Format.MARKDOWN) {
            // Markdown wie in der Ansicht, samt Überschriften: so lässt es sich weiterbearbeiten.
            Files.writeString(target, artifact.document().markdown(), StandardCharsets.UTF_8);
        } else {
            ChatExport.write(document(artifact), format, target);
        }
        return target;
    }

    /** Schreibt eine Grafik als SVG in den Ordner; liefert die Datei. */
    public static Path writeSvg(ChatArtifact artifact, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path target = free(directory, filename(artifact.title()), SVG);
        Files.writeString(target, artifact.current().svg(), StandardCharsets.UTF_8);
        return target;
    }

    /** Ein Dateiname aus dem Titel, ohne Zeichen, die Windows verbietet. */
    static String filename(String title) {
        String name = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").strip().replaceAll("[. ]+$", "");
        if (name.length() > 90) {
            name = name.substring(0, 90).strip().replaceAll("[. ]+$", "");
        }
        return name.isBlank() ? "Dokument" : name;
    }

    private static Path free(Path directory, String name, String extension) {
        Path candidate = directory.resolve(name + "." + extension);
        for (int number = 2; Files.exists(candidate); number++) {
            candidate = directory.resolve(name + " (" + number + ")." + extension);
        }
        return candidate;
    }
}
