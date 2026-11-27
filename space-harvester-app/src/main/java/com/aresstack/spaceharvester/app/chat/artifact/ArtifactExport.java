package com.aresstack.spaceharvester.app.chat.artifact;

import com.aresstack.spaceharvester.app.chat.ChatExport;
import com.aresstack.spaceharvester.document.ExportDocumentNode;
import com.aresstack.spaceharvester.document.ExportGraphics;

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

    /** Das Dokument ohne eingebundene Grafiken (für Tests und Aufrufer ohne Speicher). */
    public static ExportDocumentNode document(ChatArtifact artifact) {
        return document(artifact, id -> null);
    }

    /**
     * Das Dokument für die Schreiber: Titel und Markdown; eine Überschrift erster Ebene gleich dem Titel fällt weg.
     * Eingebundene Grafiken gehen als Bilder des Exports mit, die Schreiber zeichnen sie wie jedes SVG.
     *
     * @param graphics liefert zu einer Kennung die Grafik oder {@code null}
     */
    public static ExportDocumentNode document(ChatArtifact artifact,
                                              java.util.function.Function<String, ChatArtifact> graphics) {
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
        List<com.aresstack.spaceharvester.document.ExportImage> images = new java.util.ArrayList<>();
        for (String id : GraphicReferences.ids(markdown.toString())) {
            ChatArtifact graphic = graphics.apply(id);
            String src = DocumentChatTools.LINK_SCHEME + id;
            images.add(graphic == null || graphic.kind() != ChatArtifact.Kind.GRAPHIC
                    ? com.aresstack.spaceharvester.document.ExportImage.failed(src, id, "Grafik fehlt")
                    : com.aresstack.spaceharvester.document.ExportImage.loaded(src, graphic.title(),
                    graphic.current().svg().getBytes(StandardCharsets.UTF_8)));
        }
        // Nur Leerraum am Ende fällt weg: Ein eingerückter Codeblock direkt unter dem Titel bleibt ein Codeblock.
        return new ExportDocumentNode(artifact.title(), null, markdown.toString().stripTrailing() + "\n", images,
                List.of());
    }

    /** Schreibt ein Dokument im gewählten Format in den Ordner; liefert die Datei. */
    public static Path write(ChatArtifact artifact, ChatExport.Format format, Path directory) throws IOException {
        return write(artifact, format, directory, id -> null);
    }

    /** Wie {@link #write(ChatArtifact, ChatExport.Format, Path)}, mit den eingebundenen Grafiken. */
    public static Path write(ChatArtifact artifact, ChatExport.Format format, Path directory,
                             java.util.function.Function<String, ChatArtifact> graphics) throws IOException {
        if (artifact.kind() != ChatArtifact.Kind.DOCUMENT) {
            throw new IllegalArgumentException("Nur Dokumente haben diese Formate");
        }
        Files.createDirectories(directory);
        Path target = reserve(directory, filename(artifact.title()), format.extension);
        try {
            if (format == ChatExport.Format.MARKDOWN) {
                // Markdown wie in der Ansicht, samt Überschriften; Grafiken als SVG-Block, damit die Datei für sich
                // steht.
                Files.writeString(target, GraphicReferences.inline(artifact.document().markdown(), id -> {
                    ChatArtifact graphic = graphics.apply(id);
                    return graphic == null ? null : graphic.current().svg();
                }), StandardCharsets.UTF_8);
            } else {
                // Mit den Offline-Zeichnern (Batik für SVG, mermaid-java), damit Diagramme und eingebundene Grafiken
                // als Bild erscheinen.
                ChatExport.write(document(artifact, graphics), format, target, ExportGraphics.rendering());
            }
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(target);
            throw failed;
        }
        return target;
    }

    /** Schreibt eine Grafik als SVG in den Ordner; liefert die Datei. */
    public static Path writeSvg(ChatArtifact artifact, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path target = reserve(directory, filename(artifact.title()), SVG);
        try {
            Files.writeString(target, artifact.current().svg(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(target);
            throw failed;
        }
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

    /**
     * Legt die Zieldatei leer an und reserviert damit den Namen; gibt es ihn schon, die nächste Nummer. Das Anlegen ist
     * atomar, sodass zwei gleichzeitige Exporte mit gleichem Titel nie dieselbe Datei bekommen.
     */
    private static Path reserve(Path directory, String name, String extension) throws IOException {
        Path candidate = directory.resolve(name + "." + extension);
        for (int number = 2; ; number++) {
            try {
                return Files.createFile(candidate);
            } catch (java.nio.file.FileAlreadyExistsException taken) {
                candidate = directory.resolve(name + " (" + number + ")." + extension);
            }
        }
    }
}
