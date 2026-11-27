package com.aresstack.spaceharvester.app.chat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Bilder als Chat-Anhang (Angelo 27.11.2026): Das Chat-Modell (etwa gpt-oss-120b) sieht keine Bilder, also liest das
 * eingestellte Vision-Modell (Einstellungen › KI-Dienst › Modelle › „Vision-Modell“) das Bild ab, und sein Text geht
 * statt des Bildes mit der Frage an das Chat-Modell.
 */
public final class AttachmentImages {

    /** Bildarten, die als Bild gelesen werden; PNG und JPEG gehen unverändert hinaus, der Rest als PNG. */
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "wbmp", "webp", "tif",
            "tiff");

    /** Prompt an das Vision-Modell; die Frage des Benutzers steht dahinter, damit es weiß, worauf es ankommt. */
    public static final String PROMPT = "Lies dieses Bild für einen Chat aus. Gib zuerst allen Text, der im Bild steht, "
            + "möglichst wörtlich wieder (Überschriften, Beschriftungen, Handschrift auf Whiteboards oder Zetteln, "
            + "Tabellen, Diagrammtexte, Bildschirminhalte), in der Reihenfolge und Gliederung des Bildes; Unleserliches "
            + "markiere mit [unleserlich]. Beschreibe danach knapp, was zu sehen ist (Art des Bildes, Aufbau, Pfeile "
            + "und Zusammenhänge, Personen oder Gegenstände). Erfinde nichts. Antworte auf Deutsch.";

    /** Liest Bildbytes mit dem Vision-Modell ab ({@code Workspace#describeAttachmentImage}). */
    @FunctionalInterface
    public interface Reader {
        String read(byte[] image, String mediaType, String instruction);
    }

    private AttachmentImages() {
    }

    /** Ob die Datei nach ihrer Endung ein Bild ist. */
    public static boolean isImage(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 && IMAGES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Ob unter {@code files} ein Bild ist. */
    public static boolean anyImage(java.util.Collection<Path> files) {
        return files.stream().anyMatch(file -> file.getFileName() != null && isImage(file.getFileName().toString()));
    }

    /** Der Prompt für ein Bild zu {@code question} (Frage ohne Anhänge). */
    public static String instruction(String question) {
        return question == null || question.isBlank() ? PROMPT
                : PROMPT + "\n\nDie Frage des Benutzers zu diesem Bild: " + question.strip();
    }

    /**
     * Der Text eines Bildes für die Anfrage: Kopfzeile mit Modell, dann die Antwort des Vision-Modells.
     *
     * @throws IOException wenn die Datei nicht lesbar oder kein Bild ist
     */
    public static String read(Path file, String question, String model, Reader reader) throws IOException {
        Image image = load(file);
        String answer = reader.read(image.bytes(), image.mediaType(), instruction(question));
        if (answer == null || answer.isBlank()) {
            return "(Bild: Das Vision-Modell " + model + " hat nichts erkannt)";
        }
        return "(Bildinhalt, abgelesen vom Vision-Modell " + model + ")\n" + answer.strip();
    }

    /** Bildbytes samt Medientyp; was nicht PNG oder JPEG ist, wird als PNG neu geschrieben. */
    record Image(byte[] bytes, String mediaType) {
    }

    static Image load(Path file) throws IOException {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        byte[] bytes = Files.readAllBytes(file);
        if (name.endsWith(".png")) {
            return new Image(bytes, "image/png");
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return new Image(bytes, "image/jpeg");
        }
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (decoded == null) {
            throw new IOException("Bildformat nicht lesbar: " + name);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(decoded, "png", png)) {
            throw new IOException("PNG nicht schreibbar");
        }
        return new Image(png.toByteArray(), "image/png");
    }
}
