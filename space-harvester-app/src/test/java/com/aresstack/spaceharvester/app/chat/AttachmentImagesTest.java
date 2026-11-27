package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bildanhänge liest das Vision-Modell ab, sein Text geht mit der Frage an das Chat-Modell (Angelo 27.11.2026). */
class AttachmentImagesTest {

    @TempDir
    Path folder;

    @Test
    void recognisesImagesByTheirEnding() {
        assertThat(AttachmentImages.isImage("Tafel.JPG")).isTrue();
        assertThat(AttachmentImages.isImage("foto.webp")).isTrue();
        assertThat(AttachmentImages.isImage("notiz.txt")).isFalse();
        assertThat(AttachmentImages.isImage("bericht.pdf")).isFalse();
        assertThat(AttachmentImages.isImage("png")).isFalse();
    }

    @Test
    void thePngGoesUnchangedToTheVisionModelTogetherWithTheQuestion() throws Exception {
        Path png = folder.resolve("tafel.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        AtomicReference<String> type = new AtomicReference<>();
        AtomicReference<String> prompt = new AtomicReference<>();
        AtomicReference<byte[]> sent = new AtomicReference<>();

        String text = AttachmentImages.read(png, "Was steht hier?", "gemma4:12b", (bytes, mediaType, instruction) -> {
            sent.set(bytes);
            type.set(mediaType);
            prompt.set(instruction);
            return " Sprint-Ziele: Login fertig ";
        });

        assertThat(sent.get()).isEqualTo(Files.readAllBytes(png));
        assertThat(type.get()).isEqualTo("image/png");
        assertThat(prompt.get()).startsWith(AttachmentImages.PROMPT).endsWith("Was steht hier?");
        assertThat(text).isEqualTo("(Bildinhalt, abgelesen vom Vision-Modell gemma4:12b)\nSprint-Ziele: Login fertig");
    }

    @Test
    void otherFormatsGoAsPng() throws Exception {
        Path bmp = folder.resolve("scan.bmp");
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "bmp", bmp.toFile());

        AttachmentImages.Image image = AttachmentImages.load(bmp);

        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(image.bytes())).getWidth()).isEqualTo(3);
    }

    @Test
    void aBrokenImageIsReportedNotSent() throws Exception {
        Path broken = Files.writeString(folder.resolve("kaputt.gif"), "kein Bild");

        assertThatThrownBy(() -> AttachmentImages.read(broken, "?", "gemma", (b, t, i) -> {
            throw new AssertionError("darf nicht hinausgehen");
        })).isInstanceOf(IOException.class);
    }

    @Test
    void anEmptyAnswerSaysNothingWasRecognised() throws Exception {
        Path jpg = folder.resolve("leer.jpg");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", jpg.toFile());

        assertThat(AttachmentImages.read(jpg, "", "gemma", (b, t, i) -> " "))
                .isEqualTo("(Bild: Das Vision-Modell gemma hat nichts erkannt)");
    }

    /**
     * Echttest gegen ein Ollama (nur HTTP-API, erfundenes Whiteboard): {@code OLLAMA_PROBE_IMAGES} (Basis-URL, etwa
     * {@code http://localhost:11434}), optional {@code OLLAMA_PROBE_VISION} (Vorgabe {@code gemma4:12b}); in der CI
     * übersprungen. Geht über den gestreamten Weg mit eigenem Prompt ({@code RemoteAi#describeImage} mit Anweisung).
     */
    @Test
    void aRealVisionModelReadsAWhiteboard() throws Exception {
        String base = System.getenv("OLLAMA_PROBE_IMAGES");
        org.junit.jupiter.api.Assumptions.assumeTrue(base != null && !base.isBlank());
        String model = java.util.Optional.ofNullable(System.getenv("OLLAMA_PROBE_VISION")).orElse("gemma4:12b");
        com.aresstack.spaceharvester.source.ai.RemoteAi ai = new com.aresstack.spaceharvester.source.ai.RemoteAi(
                new com.aresstack.spaceharvester.ai.AiServiceSettings(java.net.URI.create(base), null, model, null,
                        null, java.time.Duration.ofMinutes(5), null, java.util.Set.of(), null, model)
                        .withProvider(com.aresstack.spaceharvester.ai.AiProvider.OLLAMA),
                com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort.direct(),
                com.aresstack.spaceharvester.source.TrustBundles.NONE, "chat-bild-probe");
        BufferedImage board = new BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = board.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 900, 500);
        g.setColor(new java.awt.Color(20, 40, 160));
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 54));
        g.drawString("Sprint-Ziele", 60, 100);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 44));
        g.drawString("1. Login fertig", 80, 200);
        g.drawString("2. Export testen", 80, 280);
        g.drawString("Demo: Freitag", 80, 360);
        g.dispose();
        Path png = folder.resolve("whiteboard.png");
        ImageIO.write(board, "png", png.toFile());

        long start = System.currentTimeMillis();
        String text = AttachmentImages.read(png, "Was steht auf dem Whiteboard?", model, ai::describeImage);
        long millis = System.currentTimeMillis() - start;

        System.out.println("LIVE model=" + model + " ms=" + millis + "\n" + text);
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        assertThat(upper).contains("SPRINT").contains("LOGIN").contains("EXPORT").contains("FREITAG");
    }
}
